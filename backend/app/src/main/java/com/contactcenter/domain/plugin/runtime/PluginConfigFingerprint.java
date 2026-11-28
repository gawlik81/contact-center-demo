package com.contactcenter.domain.plugin.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprint of the plaintext {@code installationConfig} of an installation (BE-245). A loaded
 * {@link PluginInstanceHandle} remembers the fingerprint of the configuration its {@code onActivate} saw; the
 * reconciliation compares it with the database and reloads the plugin when they differ, so a configuration change made
 * through ANY instance (or a lost event) converges everywhere within one reconciliation interval, without a migration
 * (the column {@code updated_at} is bumped by health-status updates too, so it cannot serve as a revision).
 *
 * <p>The database column is AES-GCM encrypted with a random IV (the ciphertext differs on every write), hence the
 * fingerprint is taken over the DECRYPTED value. It lives only in memory and is never logged or sent anywhere; SHA-256
 * is a one-way function, but the value is still treated as sensitive and not exposed through any API.
 */
final class PluginConfigFingerprint {

    /** Sorted keys at every level, exact numbers: the same logical document always serialises to the same text. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);

    private PluginConfigFingerprint() {
    }

    /**
     * @param installationConfig plaintext config JSON. {@code null}, blank, {@code {}} and JSON {@code null} are ONE
     *                           "no configuration"; objects are compared irrespective of the order of their keys
     *                           (BE-245 review R3); text that is not valid JSON is hashed as trimmed text
     */
    static String of(String installationConfig) {
        String normalized = canonicalText(installationConfig);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 niedostepny w JVM", e);
        }
    }

    private static String canonicalText(String installationConfig) {
        if (installationConfig == null || installationConfig.isBlank()) {
            return "";
        }
        String trimmed = installationConfig.trim();
        try {
            Object parsed = CANONICAL.readValue(trimmed, Object.class);
            if (parsed == null || (parsed instanceof java.util.Map<?, ?> map && map.isEmpty())) {
                return "";
            }
            return CANONICAL.writeValueAsString(parsed);
        } catch (java.io.IOException | RuntimeException e) {
            return trimmed; // not JSON: still deterministic
        }
    }
}
