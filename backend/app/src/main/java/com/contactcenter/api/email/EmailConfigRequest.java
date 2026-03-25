package com.contactcenter.api.email;

import jakarta.validation.constraints.*;

/**
 * DTO żądania zapisu konfiguracji IMAP/SMTP per-tenant.
 *
 * <p>Pole {@code password} jest opcjonalne przy aktualizacji –
 * gdy null, istniejące hasło zostaje zachowane.
 */
public record EmailConfigRequest(

        @NotBlank(message = "IMAP host jest wymagany")
        @Size(max = 255, message = "IMAP host nie może być dłuższy niż 255 znaków")
        String imapHost,

        @Min(value = 1, message = "Port IMAP musi być >= 1")
        @Max(value = 65535, message = "Port IMAP musi być <= 65535")
        int imapPort,

        boolean imapSsl,

        @NotBlank(message = "SMTP host jest wymagany")
        @Size(max = 255, message = "SMTP host nie może być dłuższy niż 255 znaków")
        String smtpHost,

        @Min(value = 1, message = "Port SMTP musi być >= 1")
        @Max(value = 65535, message = "Port SMTP musi być <= 65535")
        int smtpPort,

        boolean smtpSsl,

        @NotBlank(message = "Nazwa użytkownika (adres email) jest wymagana")
        @Email(message = "Nieprawidłowy format adresu email")
        @Size(max = 255, message = "Nazwa użytkownika nie może być dłuższa niż 255 znaków")
        String username,

        /**
         * Hasło IMAP/SMTP.
         * Opcjonalne przy aktualizacji – gdy null, zachowujemy istniejące hasło.
         * Przy pierwszej konfiguracji jest wymagane.
         */
        String password,

        @Min(value = 10, message = "Interwał pollingu musi być >= 10 sekund")
        @Max(value = 3600, message = "Interwał pollingu musi być <= 3600 sekund")
        int pollIntervalSeconds,

        /** Włącza/wyłącza obsługę email dla tenanta. */
        boolean emailEnabled
) {}
