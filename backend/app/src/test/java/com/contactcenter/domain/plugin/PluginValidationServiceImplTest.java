package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.domain.plugin.dto.ValidationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy {@link PluginValidationServiceImpl} — pipeline walidacji JAR-a pluginu
 * (ARCHITECTURE.md §11.4, BE-098).
 *
 * <p>Wszystkie JAR-y testowe są budowane w pamięci przez {@link TestJarBuilder} (ZIP +
 * bytecode generowany programowo przez ASM {@code ClassWriter}) — żadna klasa testowa nie
 * jest faktycznie ładowana przez {@code ClassLoader}, zgodnie z zasadą "skan przed dotknięciem
 * klasy" weryfikowaną przez sam serwis.
 */
@DisplayName("PluginValidationService – pipeline walidacji JAR-a pluginu")
class PluginValidationServiceImplTest {

    private static final UUID UPLOADED_BY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_ENTRY_POINT = "com.acme.contactcenter.plugin.AcmeCrmPlugin";

    private final PluginValidationServiceImpl service = new PluginValidationServiceImpl();

    @Test
    @DisplayName("Odrzuca JAR przekraczający limit rozmiaru 50MB")
    void rejectsOversizedJar() {
        byte[] oversized = new byte[(int) (50L * 1024 * 1024) + 1];
        // Magic bytes ZIP, by guard rozmiaru był sprawdzony jako pierwszy/niezależnie od MIME.
        oversized[0] = 0x50;
        oversized[1] = 0x4B;
        oversized[2] = 0x03;
        oversized[3] = 0x04;

        ValidationResult result = service.validate(oversized, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("50MB"));
    }

    @Test
    @DisplayName("Odrzuca plik z nieprawidłowymi magic bytes (nie jest ZIP/JAR)")
    void rejectsInvalidMimeMagicBytes() {
        byte[] notAZip = "to nie jest archiwum jar".getBytes();

        ValidationResult result = service.validate(notAZip, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).containsIgnoringCase("magic bytes"));
    }

    @Test
    @DisplayName("Odrzuca pusty plik")
    void rejectsEmptyFile() {
        ValidationResult result = service.validate(new byte[0], UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).containsIgnoringCase("pusty"));
    }

    @Test
    @DisplayName("Odrzuca JAR bez META-INF/plugin-manifest.json")
    void rejectsJarWithoutManifest() {
        byte[] jarBytes = TestJarBuilder.newJar()
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .build();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("plugin-manifest.json"));
    }

    @Test
    @DisplayName("Checksum mismatch -> REJECTED z opisowym błędem")
    void rejectsOnChecksumMismatch() {
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(VALID_ENTRY_POINT);
        manifestFields.put("checksumSha256", "f".repeat(64)); // niezgodny z faktyczną zawartością

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .build();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).containsIgnoringCase("checksum"));
    }

    @Test
    @DisplayName("Manifest niezgodny z JSON Schema (brak wymaganych pól) -> REJECTED")
    void rejectsManifestFailingJsonSchema() {
        Map<String, Object> incompleteManifest = Map.of(
                "pluginKey", "acme-crm-sync"
                // brak: displayName, version, vendor, sdkVersion, entryPointClass,
                // extensionPoints, permissions, checksumSha256
        );

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(incompleteManifest)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .build();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("ASM scan odrzuca JAR z referencją do Method#setAccessible")
    void rejectsJarReferencingMethodSetAccessible() {
        String entryPointClass = "com.acme.contactcenter.plugin.MaliciousReflectionPlugin";
        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(TestJarBuilder.validManifestFields(entryPointClass))
                .withEntryPointClassUsingSetAccessible(entryPointClass)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("setAccessible"));
    }

    @Test
    @DisplayName("ASM scan odrzuca JAR gdy entryPointClass nie implementuje PluginEntryPoint")
    void rejectsJarWhenEntryPointDoesNotImplementSdkInterface() {
        String entryPointClass = "com.acme.contactcenter.plugin.NotAnEntryPoint";
        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(TestJarBuilder.validManifestFields(entryPointClass))
                .withPlainClass(entryPointClass)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("nie implementuje"));
    }

    @Test
    @DisplayName("ASM scan odrzuca JAR gdy entryPointClass nie istnieje w JAR-ze")
    void rejectsJarWhenEntryPointClassMissing() {
        String declaredButMissingEntryPoint = "com.acme.contactcenter.plugin.GhostPlugin";
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(declaredButMissingEntryPoint);

        // JAR zawiera inną klasę niż zadeklarowana w manifeście jako entryPointClass.
        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass("com.acme.contactcenter.plugin.SomeOtherClass")
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("nie istnieje w JAR-ze"));
    }

    @Test
    @DisplayName("extensionPoints poza enumem platformy -> REJECTED")
    void rejectsUnknownExtensionPoint() {
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(VALID_ENTRY_POINT);
        manifestFields.put("extensionPoints", java.util.List.of("PRE_CONTACT_CONNECT", "SOMETHING_MADE_UP"));

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("SOMETHING_MADE_UP"));
    }

    @Test
    @DisplayName("permissions poza zbiorem dozwolonym przez platformę -> REJECTED")
    void rejectsUnknownPermission() {
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(VALID_ENTRY_POINT);
        manifestFields.put("permissions", java.util.List.of("customer:read", "filesystem:delete-everything"));

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).contains("filesystem:delete-everything"));
    }

    @Test
    @DisplayName("http:egress:<host> jest dozwolonym uprawnieniem dla dowolnego hosta")
    void allowsHttpEgressPermissionForAnyHost() {
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(VALID_ENTRY_POINT);
        manifestFields.put("permissions",
                java.util.List.of("customer:read", "http:egress:api.some-other-vendor.example"));

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.VALIDATED);
    }

    @Test
    @DisplayName("sdkVersion niewspierana major (np. '2.x') -> REJECTED")
    void rejectsUnsupportedSdkMajorVersion() {
        Map<String, Object> manifestFields = TestJarBuilder.validManifestFields(VALID_ENTRY_POINT);
        manifestFields.put("sdkVersion", "2.x");

        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(manifestFields)
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(result.validationErrors()).anySatisfy(
                error -> assertThat(error).containsIgnoringCase("SDK"));
    }

    @Test
    @DisplayName("JAR poprawny pod każdym względem -> VALIDATED bez błędów")
    void validatesSuccessfully() {
        byte[] jarBytes = TestJarBuilder.newJar()
                .withManifest(TestJarBuilder.validManifestFields(VALID_ENTRY_POINT))
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .buildWithAutoChecksum();

        ValidationResult result = service.validate(jarBytes, UPLOADED_BY);

        assertThat(result.status()).isEqualTo(ValidationStatus.VALIDATED);
        assertThat(result.validationErrors()).isEmpty();
    }
}
