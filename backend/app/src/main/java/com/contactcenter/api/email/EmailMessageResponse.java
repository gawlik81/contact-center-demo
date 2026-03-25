package com.contactcenter.api.email;

import com.contactcenter.domain.email.EmailMessage;

import java.time.Instant;
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
        String toAddress,
        String ccAddress,
        String subject,
        String bodyHtml,
        String bodyText,
        String messageIdHeader,
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
                message.getToAddress(),
                message.getCcAddress(),
                message.getSubject(),
                message.getBodyHtml(),
                message.getBodyText(),
                message.getMessageIdHeader(),
                message.getInReplyTo(),
                message.getDeliveryStatus(),
                message.getReceivedAt(),
                message.getSentAt(),
                message.getCreatedAt()
        );
    }
}
