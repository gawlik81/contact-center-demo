package com.contactcenter.domain.email;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Konfiguracja konta email per-tenant.
 *
 * <p>Nie jest encją JPA – dane są pobierane z pola {@code config} (JSONB) encji {@link com.contactcenter.domain.tenant.Tenant}.
 *
 * <p>Klucze w tenant.config:
 * <pre>
 * email_imap_host, email_imap_port, email_imap_ssl
 * email_smtp_host, email_smtp_port, email_smtp_ssl
 * email_username, email_password (zaszyfrowane AES-GCM)
 * email_poll_interval_seconds (default: 60)
 * email_enabled (boolean)
 * email_default_queue_id (UUID kolejki domyślnej)
 * </pre>
 */
@Getter
@Builder
public class EmailAccountConfig {

    private final String imapHost;
    private final int imapPort;
    private final boolean imapSsl;

    private final String smtpHost;
    private final int smtpPort;
    private final boolean smtpSsl;

    /** Nazwa użytkownika (adres email konta). */
    private final String username;

    /** Hasło IMAP/SMTP – przechowywane zaszyfrowane AES-GCM w tenant.config. */
    private final String password;

    /** Interwał pollingu IMAP w sekundach. Domyślnie 60. */
    @Builder.Default
    private final int pollIntervalSeconds = 60;

    // =========================================================================
    // Deserializacja z tenant.config JSONB
    // =========================================================================

    /**
     * Tworzy instancję {@code EmailAccountConfig} na podstawie mapy tenant.config.
     *
     * @param config mapa klucz→wartość z pola {@code Tenant.config}
     * @return instancja konfiguracji lub {@code null} gdy brak wymaganych kluczy
     */
    public static EmailAccountConfig fromTenantConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        String imapHost = getString(config, "email_imap_host");
        String smtpHost = getString(config, "email_smtp_host");
        String username = getString(config, "email_username");

        if (imapHost == null || smtpHost == null || username == null) {
            return null;
        }

        return EmailAccountConfig.builder()
                .imapHost(imapHost)
                .imapPort(getInt(config, "email_imap_port", 993))
                .imapSsl(getBool(config, "email_imap_ssl", true))
                .smtpHost(smtpHost)
                .smtpPort(getInt(config, "email_smtp_port", 587))
                .smtpSsl(getBool(config, "email_smtp_ssl", true))
                .username(username)
                .password(getString(config, "email_password"))
                .pollIntervalSeconds(getInt(config, "email_poll_interval_seconds", 60))
                .build();
    }

    /**
     * Serializuje konfigurację do mapy gotowej do zapisu w tenant.config JSONB.
     * Hasło jest zapisywane w formie przekazanej (powinno być już zaszyfrowane).
     *
     * @return mapa klucz→wartość do scalenia z tenant.config
     */
    public Map<String, Object> toTenantConfig() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("email_imap_host", imapHost);
        map.put("email_imap_port", imapPort);
        map.put("email_imap_ssl", imapSsl);
        map.put("email_smtp_host", smtpHost);
        map.put("email_smtp_port", smtpPort);
        map.put("email_smtp_ssl", smtpSsl);
        map.put("email_username", username);
        if (password != null) {
            map.put("email_password", password);
        }
        map.put("email_poll_interval_seconds", pollIntervalSeconds);
        return map;
    }

    // =========================================================================
    // Metody pomocnicze do odczytu z Map
    // =========================================================================

    private static String getString(Map<String, Object> config, String key) {
        Object val = config.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static int getInt(Map<String, Object> config, String key, int defaultValue) {
        Object val = config.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBool(Map<String, Object> config, String key, boolean defaultValue) {
        Object val = config.get(key);
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(val));
    }
}
