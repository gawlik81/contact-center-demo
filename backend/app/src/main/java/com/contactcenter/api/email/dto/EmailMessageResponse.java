package com.contactcenter.api.email.dto;

import com.contactcenter.domain.email.EmailMessage;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi dla pojedynczej wiadomości email.
 *
 * <p>Hasła IMAP/SMTP NIGDY nie pojawiają się w tym DTO.
 * Treść {@code bodyHtml} i {@code bodyText} dostępne tylko w endpoincie szczegółów.
 */
public record EmailMessageResponse(
        UUID id,
        UUID tenantId,
        UUID contactId,
        String direction,
        String fromAddress,
        List<String> toAddresses,
        List<String> ccAddresses,
        String subject,
        String bodyHtml,
        String bodyText,
        String messageIdHeader,
        String threadRootMessageId,
        String inReplyTo,
        String deliveryStatus,
        Instant receivedAt,
        Instant sentAt,
        Instant createdAt
) {
    public static EmailMessageResponse from(EmailMessage message) {
        return new EmailMessageResponse(
                message.getId(),
                message.getTenantId(),
                message.getContactId(),
                message.getDirection(),
                message.getFromAddress(),
                splitAddresses(message.getToAddress()),
                splitAddresses(message.getCcAddress()),
                message.getSubject(),
                message.getBodyHtml(),
                message.getBodyText(),
                message.getMessageIdHeader(),
                resolveThreadRoot(message),
                message.getInReplyTo(),
                message.getDeliveryStatus(),
                message.getReceivedAt(),
                message.getSentAt(),
                message.getCreatedAt()
        );
    }

    private static List<String> splitAddresses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Zwraca Message-ID korzenia wątku: dla reply = inReplyTo, dla root = własny messageIdHeader. */
    private static String resolveThreadRoot(EmailMessage message) {
        return message.getInReplyTo() != null && !message.getInReplyTo().isBlank()
                ? message.getInReplyTo()
                : message.getMessageIdHeader();
    }
}
