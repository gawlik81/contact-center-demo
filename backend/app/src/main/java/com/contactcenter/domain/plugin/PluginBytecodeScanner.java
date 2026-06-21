package com.contactcenter.domain.plugin;

import com.contactcenter.pluginsdk.PluginEntryPoint;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Enumeration;

/**
 * Statyczny skan bytecode JAR-a pluginu przez ASM, <strong>bez ładowania żadnej klasy</strong>
 * (ARCHITECTURE.md §11.4 krok 4) — wyłącznie inspekcja listy klas i ich instrukcji.
 *
 * <p>Odrzuca JAR jeśli którakolwiek klasa zawiera referencję do zablokowanej kategorii API
 * (reflection {@code setAccessible}, {@code ProcessBuilder}, {@code java.nio.file.*} poza
 * dozwolonym scratch dir, {@code sun.misc.*}, własna podklasa {@code ClassLoader},
 * {@code Thread#getContextClassLoader}/{@code setContextClassLoader}, {@code ServiceLoader}),
 * lub jeśli {@code entryPointClass} z manifestu nie istnieje w JAR-ze albo nie implementuje
 * {@link PluginEntryPoint}.
 */
@Slf4j
final class PluginBytecodeScanner {

    /**
     * Wzorce zablokowanych wywołań metod, reprezentowane jako "owner#name" (internal name
     * klasy z separatorem '/', bez deskryptora — nie rozróżniamy przeciążeń, blokujemy całą
     * metodę niezależnie od sygnatury).
     */
    private static final Set<String> BLOCKED_METHOD_CALLS = Set.of(
            "java/lang/reflect/AccessibleObject#setAccessible",
            "java/lang/reflect/Method#setAccessible",
            "java/lang/reflect/Field#setAccessible",
            "java/lang/reflect/Constructor#setAccessible",
            // Defense in depth dla fix-u TCCL (PluginExecutionContext, code review BE-101,
            // finding Critical): nawet gdyby manager kiedyś przestał ustawiać/przywracać TCCL
            // wokół wywołania kodu pluginu, plugin nie może JAWNIE odczytać/nadpisać TCCL żeby
            // dotrzeć do classloadera aplikacji przez Thread.currentThread().getContextClassLoader().
            "java/lang/Thread#getContextClassLoader",
            "java/lang/Thread#setContextClassLoader"
    );

    /** Prefiksy internal-name pakietów całkowicie zablokowanych (każda referencja do klasy z tego pakietu). */
    private static final List<String> BLOCKED_OWNER_PREFIXES = List.of(
            "java/lang/ProcessBuilder",
            "java/nio/file/",
            "sun/misc/",
            // ServiceLoader.load(...) bez explicit classloadera używa domyślnie TCCL — blokowanie
            // całego typu (nie tylko #load) zamyka też warianty load(Class, ClassLoader) gdzie
            // plugin mógłby próbować przekazać TCCL jawnie odczytany skąd indziej.
            "java/util/ServiceLoader"
    );

    private PluginBytecodeScanner() {
    }

    /**
     * Wynik skanu — albo lista naruszeń (JAR odrzucony), albo potwierdzenie że
     * {@code entryPointClass} został znaleziony i zweryfikowany.
     */
    record ScanResult(List<String> violations) {

        boolean isClean() {
            return violations.isEmpty();
        }
    }

    /**
     * Skanuje wszystkie klasy w JAR-ze (entries {@code *.class}) i weryfikuje
     * {@code entryPointClass} z manifestu.
     *
     * @param zipFile          otwarty ZIP/JAR (NIE jest zamykany przez tę metodę — odpowiedzialność wołającego)
     * @param entryPointClass  pełna nazwa klasy z {@code manifest.entryPointClass} (notacja z '.')
     * @return {@link ScanResult} z listą naruszeń; pusta lista oznacza JAR czysty
     */
    static ScanResult scan(ZipFile zipFile, String entryPointClass) {
        List<String> violations = new ArrayList<>();
        String entryPointInternalName = entryPointClass == null
                ? null
                : entryPointClass.replace('.', '/');
        boolean entryPointFound = false;
        boolean entryPointImplementsSdkInterface = false;

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            ClassNode classNode;
            try {
                classNode = readClassNode(zipFile, entry);
            } catch (IOException e) {
                violations.add("Nie można odczytać klasy " + entry.getName() + " z JAR-a: " + e.getMessage());
                continue;
            } catch (RuntimeException e) {
                // ASM rzuca niesprawdzone wyjątki (np. IllegalArgumentException) dla
                // uszkodzonego/niezgodnego bytecode – traktujemy jak naruszenie, nie crash.
                violations.add("Uszkodzony bytecode w klasie " + entry.getName() + ": " + e.getMessage());
                continue;
            }

            scanMethodsForBlockedCalls(classNode, violations);
            scanForClassLoaderSubclass(classNode, violations);

            if (entryPointInternalName != null && entryPointInternalName.equals(classNode.name)) {
                entryPointFound = true;
                entryPointImplementsSdkInterface = implementsInterface(
                        classNode, PluginEntryPoint.class.getName().replace('.', '/'));
            }
        }

        if (entryPointInternalName == null) {
            violations.add("Manifest nie deklaruje entryPointClass");
        } else if (!entryPointFound) {
            violations.add("Klasa entryPointClass '" + entryPointClass + "' nie istnieje w JAR-ze");
        } else if (!entryPointImplementsSdkInterface) {
            violations.add("Klasa entryPointClass '" + entryPointClass
                    + "' nie implementuje " + PluginEntryPoint.class.getName());
        }

        return new ScanResult(violations);
    }

    private static ClassNode readClassNode(ZipFile zipFile, ZipEntry entry) throws IOException {
        ClassReader reader;
        try (var in = zipFile.getInputStream(entry)) {
            reader = new ClassReader(in);
        }
        ClassNode classNode = new ClassNode();
        // SKIP_FRAMES + SKIP_DEBUG: nie potrzebujemy ramek stack map ani debug info do
        // statycznej inspekcji wywołań metod / implementowanych interfejsów.
        reader.accept(classNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return classNode;
    }

    private static void scanMethodsForBlockedCalls(ClassNode classNode, List<String> violations) {
        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode methodInsn)) {
                    continue;
                }
                String ownerMethodKey = methodInsn.owner + "#" + methodInsn.name;
                if (BLOCKED_METHOD_CALLS.contains(ownerMethodKey)) {
                    violations.add("Klasa " + classNode.name + " odwołuje się do zablokowanego API: "
                            + methodInsn.owner.replace('/', '.') + "." + methodInsn.name + "()");
                    continue;
                }
                for (String blockedPrefix : BLOCKED_OWNER_PREFIXES) {
                    if (methodInsn.owner.startsWith(blockedPrefix)) {
                        violations.add("Klasa " + classNode.name + " odwołuje się do zablokowanego pakietu/klasy: "
                                + methodInsn.owner.replace('/', '.'));
                        break;
                    }
                }
            }
        }
    }

    private static void scanForClassLoaderSubclass(ClassNode classNode, List<String> violations) {
        if ("java/lang/ClassLoader".equals(classNode.superName)) {
            violations.add("Klasa " + classNode.name + " jest podklasą java.lang.ClassLoader — niedozwolone");
        }
    }

    /**
     * Sprawdza (statycznie, przez ASM) czy {@code classNode} implementuje interfejs o podanej
     * internal-name. Sprawdzamy tylko bezpośrednio zadeklarowane interfejsy — entryPointClass
     * powinien implementować {@code PluginEntryPoint} wprost, nie przez wielopoziomową
     * hierarchię, zgodnie z kontraktem SDK (BE-097: "no-arg constructor", prosta implementacja).
     */
    private static boolean implementsInterface(ClassNode classNode, String interfaceInternalName) {
        return classNode.interfaces != null && classNode.interfaces.contains(interfaceInternalName);
    }
}
