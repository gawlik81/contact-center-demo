package com.contactcenter.api.email.dto;

import com.contactcenter.domain.email.EmailMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi dla pojedynczej wiadomości email.
 *
 * <p>Hasła IMAP/SMTP NIGDY nie pojawiają się w tym DTO.
 * Treść {@code bodyHtml} i {@code bodyText} dostępne tylko w endpoincie szczegółów.
 * Klucze S3 załączników ({@code s3Key}) są eksponowane – pobieranie chronione przez
 * endpoint {@code GET /api/email/attachments/download?s3Key=...} z walidacją IDOR.
 */
@Slf4j
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
        Instant createdAt,
        List<AttachmentMeta> attachments
) {

    /**
     * Metadane pojedynczego załącznika email.
     *
     * @param filename    oryginalna nazwa pliku
     * @param contentType MIME type
     * @param sizeBytes   rozmiar w bajtach
     * @param s3Key       klucz S3 (wewnętrzny – dostęp tylko przez API)
     */
    public record AttachmentMeta(
            String filename,
            String contentType,
            long sizeBytes,
            String s3Key
    ) {}

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                message.getCreatedAt(),
                parseAttachments(message.getAttachments())
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

    /**
     * Parsuje JSON z kolumny JSONB {@code attachments} do listy {@link AttachmentMeta}.
     * Format JSON: [{filename, content_type, size_bytes, s3_key}].
     * Błędy parsowania logowane, zwracana pusta lista (graceful degradation).
     */
    private static List<AttachmentMeta> parseAttachments(String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank() || "[]".equals(attachmentsJson.trim())) {
            return List.of();
        }
        try {
            List<java.util.Map<String, Object>> raw = OBJECT_MAPPER.readValue(
                    attachmentsJson,
                    new TypeReference<List<java.util.Map<String, Object>>>() {}
            );
            return raw.stream()
                    .map(m -> new AttachmentMeta(
                            (String) m.get("filename"),
                            (String) m.get("content_type"),
                            m.get("size_bytes") instanceof Number n ? n.longValue() : 0L,
                            (String) m.get("s3_key")
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("[EmailMessageResponse] Nie można sparsować attachments JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
