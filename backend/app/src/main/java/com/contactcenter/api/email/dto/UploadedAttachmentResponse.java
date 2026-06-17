package com.contactcenter.api.email.dto;

import java.util.UUID;

/**
 * DTO odpowiedzi dla endpointu upload załącznika agenta.
 *
 * <p>Zwracane przez {@code POST /api/email/attachments/upload}.
 * Klient (FE) przechowuje {@code s3Key} i przekazuje go w {@link EmailReplyRequest}
 * przy wysyłaniu odpowiedzi email.
 *
 * @param pendingId   UUID identyfikujący pending upload (unikalny per plik)
 * @param filename    oryginalna nazwa pliku
 * @param contentType MIME type
 * @param sizeBytes   rozmiar w bajtach
 * @param s3Key       klucz S3 do przekazania w EmailReplyRequest.attachments
 */
public record UploadedAttachmentResponse(
        UUID pendingId,
        String filename,
        String contentType,
        long sizeBytes,
        String s3Key
) {}
