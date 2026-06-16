package com.contactcenter.api.email.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * DTO żądania wysyłki nowej wiadomości email ad hoc (bez powiązania z istniejącym wątkiem).
 *
 * <p>Różnica od {@link EmailReplyRequest}: tu podajemy odbiorcę i temat explicite;
 * brak nagłówków In-Reply-To / References – tworzy nowy wątek emailowy.
 */
public record OutboundEmailRequest(

        @Email(message = "Nieprawidłowy adres email odbiorcy")
        @NotBlank(message = "Adres odbiorcy jest wymagany")
        String toAddress,

        @NotBlank(message = "Temat jest wymagany")
        @Size(max = 500, message = "Temat nie może być dłuższy niż 500 znaków")
        String subject,

        @NotBlank(message = "Treść wiadomości jest wymagana")
        String bodyHtml,

        UUID customerId,  // opcjonalne – nie używane do tworzenia kontaktu w tej wersji

        List<EmailReplyRequest.PendingAttachment> attachments
) {}
