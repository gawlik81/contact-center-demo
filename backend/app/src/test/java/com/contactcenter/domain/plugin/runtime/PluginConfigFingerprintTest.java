package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginConfigFingerprint (BE-245 R3): kanonizacja odcisku konfiguracji")
class PluginConfigFingerprintTest {

    @Test
    @DisplayName("inna kolejność kluczy (także zagnieżdżonych) i białe znaki -> ten sam odcisk")
    void keyOrderAndWhitespaceDoNotMatter() {
        String a = "{\"b\":1,\"a\":{\"y\":[1,2],\"x\":\"v\"}}";
        String b = " {\n \"a\" : {\"x\":\"v\", \"y\":[1,2]}, \"b\": 1 }\n";

        assertThat(PluginConfigFingerprint.of(a)).isEqualTo(PluginConfigFingerprint.of(b));
    }

    @Test
    @DisplayName("realna zmiana wartości, kolejność elementów tablicy albo dodatkowy klucz -> inny odcisk")
    void realChangesChangeTheFingerprint() {
        String base = PluginConfigFingerprint.of("{\"a\":[1,2],\"b\":\"x\"}");

        assertThat(PluginConfigFingerprint.of("{\"a\":[1,2],\"b\":\"y\"}")).isNotEqualTo(base);
        assertThat(PluginConfigFingerprint.of("{\"a\":[2,1],\"b\":\"x\"}")).isNotEqualTo(base);
        assertThat(PluginConfigFingerprint.of("{\"a\":[1,2],\"b\":\"x\",\"c\":1}")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("null, puste, białe znaki, {} i JSON null to jedna 'brak konfiguracji'; {\"a\":1} to co innego")
    void emptyFormsAreOne() {
        String empty = PluginConfigFingerprint.of(null);

        assertThat(PluginConfigFingerprint.of("")).isEqualTo(empty);
        assertThat(PluginConfigFingerprint.of("  \n")).isEqualTo(empty);
        assertThat(PluginConfigFingerprint.of("{}")).isEqualTo(empty);
        assertThat(PluginConfigFingerprint.of(" { } ")).isEqualTo(empty);
        assertThat(PluginConfigFingerprint.of("null")).isEqualTo(empty);
        assertThat(PluginConfigFingerprint.of("{\"a\":1}")).isNotEqualTo(empty);
    }

    @Test
    @DisplayName("tekst niebędący JSON-em jest deterministyczny (odcisk z przyciętego tekstu), nie rzuca")
    void nonJsonIsStable() {
        assertThat(PluginConfigFingerprint.of(" not json ")).isEqualTo(PluginConfigFingerprint.of("not json"))
                .isNotEqualTo(PluginConfigFingerprint.of(null));
    }

    @Test
    @DisplayName("N: toString uchwytu nie zawiera odcisku konfiguracji, checksumu JAR ani ścieżek")
    void handleToStringDoesNotLeakFingerprint() {
        String fingerprint = PluginConfigFingerprint.of("{\"pin\":\"1234\"}");
        PluginInstanceHandle handle = new PluginInstanceHandle(UUID.randomUUID(), UUID.randomUUID(), "k", null, null,
                java.nio.file.Path.of("/tmp/secret-name.jar"), Optional.empty(), List.of(), UUID.randomUUID(), 1L,
                fingerprint, "ABCDEF0123456789checksum", 10L, 20L);

        assertThat(handle.toString()).contains("installationId=").contains("pluginKey=k")
                .doesNotContain(fingerprint).doesNotContain("checksum").doesNotContain("secret-name.jar");
    }
}
