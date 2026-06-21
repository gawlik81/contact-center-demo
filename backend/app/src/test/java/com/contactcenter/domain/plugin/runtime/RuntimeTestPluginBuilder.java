package com.contactcenter.domain.plugin.runtime;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generuje, przez ASM, bytecode minimalnych implementacji {@code PluginEntryPoint} do testów
 * {@link PluginRuntimeManagerImplTest} — w odróżnieniu od {@code TestJarBuilder} (BE-098, pakiet
 * {@code domain.plugin}, używany tylko do analizy statycznej ASM bez ładowania), te klasy są
 * faktycznie ładowane przez {@link PluginClassLoader} i wykonywane.
 *
 * <p>Każda wygenerowana klasa ma publiczne, statyczne, volatile pole {@code LAST_ACTIVATED_WITH}
 * typu {@code Object} (przechowuje referencję do {@code PluginContext} otrzymanego w
 * {@code onActivate}) i {@code ACTIVATE_CALLS}/{@code DEACTIVATE_CALLS} typu {@code int} —
 * odczytywane w testach przez refleksję na samej klasie (nie przez classloader aplikacji,
 * tylko przez {@code Class} zwrócony z {@code PluginClassLoader}, więc nie narusza izolacji
 * testowanej w innych przypadkach).
 */
final class RuntimeTestPluginBuilder {

    private RuntimeTestPluginBuilder() {
    }

    /**
     * Zapisuje na dysku tymczasowy JAR zawierający jedną klasę implementującą
     * {@code PluginEntryPoint}, której {@code onActivate}/{@code onDeactivate} inkrementują
     * statyczne liczniki ({@code ACTIVATE_CALLS}/{@code DEACTIVATE_CALLS}) i (dla onActivate)
     * wywołują {@code context.logger().info(...)} oraz {@code context.getCustomer(customerId)}
     * z ustalonym, zaszytym w bytecode UUID, zapisując wynik do statycznego pola
     * {@code LAST_FETCHED_CUSTOMER}.
     *
     * @param fullyQualifiedClassName nazwa generowanej klasy, np. {@code "com.acme.TestPlugin1"}
     * @param fixedCustomerIdString    UUID (jako String) klienta, którego onActivate ma pobrać
     * @return ścieżka do zapisanego pliku JAR
     */
    static Path buildJarWithCustomerFetchOnActivate(String fullyQualifiedClassName, String fixedCustomerIdString) throws IOException {
        byte[] classBytes = generateClass(fullyQualifiedClassName, fixedCustomerIdString);
        return writeJar(fullyQualifiedClassName, classBytes);
    }

    /**
     * Variant minimalny: onActivate/onDeactivate tylko inkrementują liczniki, bez wołania
     * PluginContext — używany w testach cyklu życia (load/unload) gdzie zachowanie
     * PluginContext nie jest pod testem.
     */
    static Path buildMinimalJar(String fullyQualifiedClassName) throws IOException {
        byte[] classBytes = generateClass(fullyQualifiedClassName, null);
        return writeJar(fullyQualifiedClassName, classBytes);
    }

    /**
     * Variant testujący granicę TCCL (Thread-Context ClassLoader) — w {@code onActivate} woła
     * {@code Thread.currentThread().getContextClassLoader()} i próbuje
     * {@code Class.forName(hostClassName, false, tccl)} przez ten TCCL, zapisując wynik do
     * statycznego pola {@code TCCL_LOOKUP_RESULT} ({@code "FOUND:" + nazwa} jeśli klasa hosta
     * była dostępna przez TCCL — czyli izolacja złamana — albo {@code "NOT_FOUND:" +
     * komunikat wyjątku} jeśli {@code ClassNotFoundException} — czyli izolacja poprawna).
     *
     * <p>Używany przez test regresyjny dla code review BE-101 (finding Critical): bez fixu w
     * {@link PluginExecutionContext}, TCCL wątku {@code lifecycleExecutor} dziedziczy
     * classloader aplikacji (wątek tworzący), więc {@code hostClassName} byłaby znaleziona —
     * po fixie TCCL jest ustawiony na {@link PluginClassLoader} tej instalacji, którego parent
     * ({@link PlatformApiClassLoader}) odrzuca każdą nazwę poza {@code com.contactcenter.pluginsdk.*}.
     */
    static Path buildJarThatProbesTcclOnActivate(String fullyQualifiedClassName, String hostClassName) throws IOException {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{"com/contactcenter/pluginsdk/PluginEntryPoint"});

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "TCCL_LOOKUP_RESULT", "Ljava/lang/String;", null, null).visitEnd();

        writeNoArgConstructor(cw, internalName);
        writeOnActivateProbingTccl(cw, internalName, hostClassName);
        writeNoOpOnDeactivate(cw);
        cw.visitEnd();

        return writeJar(fullyQualifiedClassName, cw.toByteArray());
    }

    /** Wartość zapisana do {@code TCCL_LOOKUP_RESULT} gdy klasa hosta NIE była dostępna przez TCCL (izolacja poprawna). */
    static final String TCCL_PROBE_NOT_FOUND = "NOT_FOUND";

    /** Prefiks wartości zapisanej do {@code TCCL_LOOKUP_RESULT} gdy klasa hosta BYŁA dostępna przez TCCL (izolacja złamana). */
    static final String TCCL_PROBE_FOUND_PREFIX = "FOUND:";

    private static void writeOnActivateProbingTccl(ClassWriter cw, String internalName, String hostClassName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null, null);
        mv.visitCode();

        // ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2); // local var 2 = tccl

        org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        org.objectweb.asm.Label catchStart = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterCatch = new org.objectweb.asm.Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/ClassNotFoundException");

        // try { Class.forName(hostClassName, false, tccl); TCCL_LOOKUP_RESULT = "FOUND:" + hostClassName; }
        mv.visitLabel(tryStart);
        mv.visitLdcInsn(hostClassName);
        mv.visitInsn(Opcodes.ICONST_0); // initialize = false
        mv.visitVarInsn(Opcodes.ALOAD, 2); // tccl
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.POP); // wynik Class.forName nie jest potrzebny, tylko fakt że nie rzuciło
        mv.visitLdcInsn(TCCL_PROBE_FOUND_PREFIX + hostClassName);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "TCCL_LOOKUP_RESULT", "Ljava/lang/String;");
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, afterCatch);

        // catch (ClassNotFoundException e) { TCCL_LOOKUP_RESULT = "NOT_FOUND"; }
        mv.visitLabel(catchStart);
        mv.visitVarInsn(Opcodes.ASTORE, 3); // exception (ignorowany — tylko fakt rzucenia ma znaczenie)
        mv.visitLdcInsn(TCCL_PROBE_NOT_FOUND);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "TCCL_LOOKUP_RESULT", "Ljava/lang/String;");

        mv.visitLabel(afterCatch);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Variant którego onActivate rzuca {@link RuntimeException} — do testu ścieżki błędu
     * aktywacji.
     */
    static Path buildJarThatThrowsOnActivate(String fullyQualifiedClassName) throws IOException {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{"com/contactcenter/pluginsdk/PluginEntryPoint"});
        writeNoArgConstructor(cw, internalName);

        MethodVisitor onActivate = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null, null);
        onActivate.visitCode();
        onActivate.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        onActivate.visitInsn(Opcodes.DUP);
        onActivate.visitLdcInsn("plugin testowy, który celowo zawodzi");
        onActivate.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(Ljava/lang/String;)V", false);
        onActivate.visitInsn(Opcodes.ATHROW);
        onActivate.visitMaxs(0, 0);
        onActivate.visitEnd();

        writeNoOpOnDeactivate(cw);
        cw.visitEnd();

        return writeJar(fullyQualifiedClassName, cw.toByteArray());
    }

    /**
     * Variant, którego onActivate zawiesza się (długi Thread.sleep) — do testu timeoutu.
     */
    static Path buildJarThatHangsOnActivate(String fullyQualifiedClassName, long sleepMillis) throws IOException {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{"com/contactcenter/pluginsdk/PluginEntryPoint"});
        writeNoArgConstructor(cw, internalName);

        MethodVisitor onActivate = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null,
                new String[]{"java/lang/InterruptedException"});
        onActivate.visitCode();
        onActivate.visitLdcInsn(sleepMillis);
        onActivate.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
        onActivate.visitInsn(Opcodes.RETURN);
        onActivate.visitMaxs(0, 0);
        onActivate.visitEnd();

        writeNoOpOnDeactivate(cw);
        cw.visitEnd();

        return writeJar(fullyQualifiedClassName, cw.toByteArray());
    }

    // =========================================================================
    // Generacja bytecode
    // =========================================================================

    private static byte[] generateClass(String fullyQualifiedClassName, String fixedCustomerIdString) {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{"com/contactcenter/pluginsdk/PluginEntryPoint"});

        // public static volatile int ACTIVATE_CALLS = 0;
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "ACTIVATE_CALLS", "I", null, 0)
                .visitEnd();
        // public static volatile int DEACTIVATE_CALLS = 0;
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, "DEACTIVATE_CALLS", "I", null, 0)
                .visitEnd();
        // public static volatile Object LAST_FETCHED_CUSTOMER = null;
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                "LAST_FETCHED_CUSTOMER", "Ljava/lang/Object;", null, null).visitEnd();

        writeNoArgConstructor(cw, internalName);
        writeOnActivate(cw, internalName, fixedCustomerIdString);
        writeOnDeactivate(cw, internalName);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeOnActivate(ClassWriter cw, String internalName, String fixedCustomerIdString) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null, null);
        mv.visitCode();

        // ACTIVATE_CALLS++
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "ACTIVATE_CALLS", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "ACTIVATE_CALLS", "I");

        if (fixedCustomerIdString != null) {
            // LAST_FETCHED_CUSTOMER = context.getCustomer(UUID.fromString("..."));
            mv.visitVarInsn(Opcodes.ALOAD, 1); // context
            mv.visitLdcInsn(fixedCustomerIdString);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/UUID", "fromString",
                    "(Ljava/lang/String;)Ljava/util/UUID;", false);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/contactcenter/pluginsdk/PluginContext",
                    "getCustomer", "(Ljava/util/UUID;)Lcom/contactcenter/pluginsdk/model/CustomerView;", true);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "LAST_FETCHED_CUSTOMER", "Ljava/lang/Object;");
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void writeOnDeactivate(ClassWriter cw, String internalName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDeactivate", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, internalName, "DEACTIVATE_CALLS", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "DEACTIVATE_CALLS", "I");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void writeNoOpOnDeactivate(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDeactivate", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void writeNoArgConstructor(ClassWriter cw, String internalName) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    // =========================================================================
    // JAR (ZIP) na dysku
    // =========================================================================

    private static Path writeJar(String fullyQualifiedClassName, byte[] classBytes) throws IOException {
        Path jarPath = Files.createTempFile("runtime-test-plugin-", ".jar");
        String entryName = fullyQualifiedClassName.replace('.', '/') + ".class";

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry(entryName);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(classBytes.length);
            entry.setCompressedSize(classBytes.length);
            CRC32 crc = new CRC32();
            crc.update(classBytes);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(classBytes);
            zos.closeEntry();
            zos.finish();
            Files.write(jarPath, baos.toByteArray());
        }
        return jarPath;
    }
}
