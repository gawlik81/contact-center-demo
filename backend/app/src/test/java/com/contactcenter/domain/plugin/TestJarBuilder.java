package com.contactcenter.domain.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pomocnicza fabryka budująca minimalne, prawdziwe pliki JAR "w pamięci" do testów
 * {@link PluginValidationServiceImplTest} — bez fixture'ów na dysku.
 *
 * <p>Każda metoda generuje bytecode klasy bezpośrednio przez ASM {@link ClassWriter}, więc
 * powstałe klasy są poprawnym, ładowalnym bytecode (choć nie są ładowane w testach — skaner
 * BE-098 działa wyłącznie na poziomie ASM, bez {@code ClassLoader.loadClass}).
 */
final class TestJarBuilder {

    static final String SDK_ENTRY_POINT_INTERFACE = "com/contactcenter/pluginsdk/PluginEntryPoint";

    private final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestJarBuilder() {
    }

    static TestJarBuilder newJar() {
        return new TestJarBuilder();
    }

    /**
     * Dodaje {@code META-INF/plugin-manifest.json} zgodny z manifestem przekazanym jako mapa
     * pól — checksum jest dopisywany automatycznie po zbudowaniu JAR-a (patrz {@link #buildWithAutoChecksum()}).
     */
    TestJarBuilder withManifest(Map<String, Object> manifestFields) {
        try {
            entries.put("META-INF/plugin-manifest.json", objectMapper.writeValueAsBytes(manifestFields));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    TestJarBuilder withRawEntry(String path, byte[] content) {
        entries.put(path, content);
        return this;
    }

    /**
     * Dodaje klasę implementującą (poprawnie) {@code com.contactcenter.pluginsdk.PluginEntryPoint}
     * z pustymi, bezpiecznymi implementacjami {@code onActivate}/{@code onDeactivate}.
     *
     * @param fullyQualifiedClassName np. {@code "com.acme.AcmePlugin"}
     */
    TestJarBuilder withValidEntryPointClass(String fullyQualifiedClassName) {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{SDK_ENTRY_POINT_INTERFACE});

        writeNoArgConstructor(cw);

        // void onActivate(PluginContext context) { }
        MethodVisitor onActivate = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null, null);
        onActivate.visitCode();
        onActivate.visitInsn(Opcodes.RETURN);
        onActivate.visitMaxs(0, 0);
        onActivate.visitEnd();

        // void onDeactivate() { }
        MethodVisitor onDeactivate = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDeactivate", "()V", null, null);
        onDeactivate.visitCode();
        onDeactivate.visitInsn(Opcodes.RETURN);
        onDeactivate.visitMaxs(0, 0);
        onDeactivate.visitEnd();

        cw.visitEnd();
        entries.put(internalName + ".class", cw.toByteArray());
        return this;
    }

    /**
     * Dodaje klasę "biznesowa" (nieimplementującą {@code PluginEntryPoint}) — symuluje
     * manifest deklarujący entryPointClass na klasę, która nie spełnia kontraktu SDK.
     */
    TestJarBuilder withPlainClass(String fullyQualifiedClassName) {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writeNoArgConstructor(cw);
        cw.visitEnd();
        entries.put(internalName + ".class", cw.toByteArray());
        return this;
    }

    /**
     * Dodaje klasę implementująca correctamente {@code PluginEntryPoint}, ale której metoda
     * {@code onActivate} wywołuje {@code Method.setAccessible(true)} — naruszenie blacklisty
     * ASM scanu (java.lang.reflect.Method#setAccessible).
     */
    TestJarBuilder withEntryPointClassUsingSetAccessible(String fullyQualifiedClassName) {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[]{SDK_ENTRY_POINT_INTERFACE});
        writeNoArgConstructor(cw);

        // void onActivate(PluginContext context) {
        //     try {
        //         Method m = Object.class.getDeclaredMethod("toString");
        //         m.setAccessible(true);
        //     } catch (NoSuchMethodException e) { }
        // }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onActivate",
                "(Lcom/contactcenter/pluginsdk/PluginContext;)V", null,
                new String[]{"java/lang/NoSuchMethodException"});
        mv.visitCode();
        mv.visitLdcInsn(org.objectweb.asm.Type.getType("Ljava/lang/Object;"));
        mv.visitLdcInsn("toString");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible",
                "(Z)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor onDeactivate = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDeactivate", "()V", null, null);
        onDeactivate.visitCode();
        onDeactivate.visitInsn(Opcodes.RETURN);
        onDeactivate.visitMaxs(0, 0);
        onDeactivate.visitEnd();

        cw.visitEnd();
        entries.put(internalName + ".class", cw.toByteArray());
        return this;
    }

    private void writeNoArgConstructor(ClassWriter cw) {
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    /**
     * Buduje archiwum JAR jako tablicę bajtów, bez modyfikacji checksum w manifeście
     * (manifest pozostaje taki, jak dodany przez {@link #withManifest(Map)}).
     */
    byte[] build() {
        return zip(entries);
    }

    /**
     * Buduje archiwum JAR, podstawiając do manifestu prawdziwy SHA-256 obliczony z wszystkich
     * wpisów ZIP-a <strong>z wyłączeniem samego manifestu</strong> — zgodnie z tym, jak
     * {@link PluginValidationServiceImpl#checkChecksum} faktycznie weryfikuje checksum
     * (patrz Javadoc tej metody: hashowanie całego pliku łącznie z polem zawierającym ten sam
     * hash jest matematycznie niewykonalne do dopasowania — klasyczny problem kura-i-jajko
     * self-referencyjnego SHA-256).
     *
     * <p>Ponieważ checksum nie zależy od treści manifestu, nie ma potrzeby budować JAR-a
     * dwukrotnie ani modyfikować bajtów po fakcie — liczymy hash z aktualnych wpisów (innych
     * niż manifest), podstawiamy do manifestu, i budujemy JAR jeden raz.
     */
    byte[] buildWithAutoChecksum() {
        String checksum = sha256OfEntriesExcept(entries, "META-INF/plugin-manifest.json");
        entries.put("META-INF/plugin-manifest.json",
                replaceChecksumPlaceholder(entries.get("META-INF/plugin-manifest.json"), checksum));
        return zip(entries);
    }

    private String sha256OfEntriesExcept(Map<String, byte[]> allEntries, String excludedEntryName) {
        List<String> names = new ArrayList<>(allEntries.keySet());
        names.remove(excludedEntryName);
        names.sort(String::compareTo);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String name : names) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update(allEntries.get(name));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @SuppressWarnings("unchecked")
    private byte[] replaceChecksumPlaceholder(byte[] manifestJsonBytes, String checksum) {
        try {
            Map<String, Object> manifestMap = objectMapper.readValue(manifestJsonBytes, Map.class);
            manifestMap.put("checksumSha256", checksum);
            return objectMapper.writeValueAsBytes(manifestMap);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Timestamp fiksowany (epoch 0) dla każdego {@link ZipEntry} — JAR-y testowe deterministyczne. */
    private static final long FIXED_ZIP_ENTRY_TIME_MILLIS = 0L;

    /**
     * Wpisy są zapisywane jako {@link ZipEntry#STORED} (bez kompresji DEFLATE) — wybór czysto
     * pragmatyczny dla testów (prostsze, brak narzutu kompresji na małych fixture'ach), nie
     * wymagany przez {@link #buildWithAutoChecksum()} (który od weryfikacji checksumu z
     * wyłączeniem manifestu — patrz Javadoc tej metody — nie zależy już od układu bajtów ZIP-a).
     */
    private byte[] zip(Map<String, byte[]> zipEntries) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : zipEntries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(FIXED_ZIP_ENTRY_TIME_MILLIS);
                zipEntry.setMethod(ZipEntry.STORED);
                zipEntry.setSize(entry.getValue().length);
                zipEntry.setCompressedSize(entry.getValue().length);
                zipEntry.setCrc(crc32(entry.getValue()));
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue());
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Manifest minimalny ale poprawny — wszystkie pola wymagane przez JSON Schema.
     */
    static Map<String, Object> validManifestFields(String entryPointClass) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pluginKey", "acme-crm-sync");
        fields.put("displayName", "Acme CRM Sync");
        fields.put("version", "1.3.0");
        fields.put("vendor", "Acme Sp. z o.o.");
        fields.put("vendorContact", "support@acme.example");
        fields.put("sdkVersion", "1.x");
        fields.put("entryPointClass", entryPointClass);
        fields.put("extensionPoints", List.of("PRE_CONTACT_CONNECT", "CUSTOMER_SYNC"));
        fields.put("permissions", List.of("customer:read", "contact:read", "http:egress:api.acme-crm.example"));
        fields.put("checksumSha256", "0".repeat(64));
        return fields;
    }
}
