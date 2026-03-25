package com.contactcenter.api.email;

import com.contactcenter.domain.email.EmailAccountConfig;

/**
 * DTO odpowiedzi konfiguracji email – BEZ hasła.
 *
 * <p>Hasło IMAP/SMTP jest nigdy zwracane w odpowiedzi API.
 * Pole {@code hasPassword} informuje frontend czy hasło jest skonfigurowane.
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
        boolean emailEnabled
) {
    public static EmailConfigResponse from(EmailAccountConfig config, boolean emailEnabled) {
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
                emailEnabled
        );
    }
}
