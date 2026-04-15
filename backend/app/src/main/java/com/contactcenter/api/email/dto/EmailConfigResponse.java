package com.contactcenter.api.email.dto;

import com.contactcenter.domain.email.EmailAccountConfig;
import jakarta.annotation.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * DTO odpowiedzi konfiguracji email – BEZ hasła.
 *
 * <p>Hasło IMAP/SMTP jest nigdy zwracane w odpowiedzi API.
 * Pole {@code hasPassword} informuje frontend czy hasło jest skonfigurowane.
 *
 * <p>Pole {@code defaultQueueId} może być null gdy nie skonfigurowano domyślnej kolejki.
 */
public record EmailConfigResponse(
        String imapHost,
        int imapPort,
        boolean imapSsl,
        String smtpHost,
        int smtpPort,
        boolean smtpSsl,
        String username,
        /** true jeśli hasło jest skonfigurowane (nie ujawniamy wartości). */
        boolean hasPassword,
        int pollIntervalSeconds,
        boolean emailEnabled,
        /** UUID domyślnej kolejki dla przychodzących emaili; null gdy niezdefiniowane. */
        @Nullable UUID defaultQueueId
) {
    public static EmailConfigResponse from(EmailAccountConfig config, boolean emailEnabled,
                                           @Nullable Map<String, Object> tenantConfig) {
        Object queueIdObj = tenantConfig != null ? tenantConfig.get("email_default_queue_id") : null;
        UUID defaultQueueId = null;
        if (queueIdObj != null) {
            try {
                defaultQueueId = UUID.fromString(String.valueOf(queueIdObj));
            } catch (Exception ignored) {}
        }

        return new EmailConfigResponse(
                config.getImapHost(),
                config.getImapPort(),
                config.isImapSsl(),
                config.getSmtpHost(),
                config.getSmtpPort(),
                config.isSmtpSsl(),
                config.getUsername(),
                config.getPassword() != null && !config.getPassword().isBlank(),
                config.getPollIntervalSeconds(),
                emailEnabled,
                defaultQueueId
        );
    }
}
