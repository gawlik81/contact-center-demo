package com.contactcenter.api.email.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO żądania wysłania odpowiedzi email, opcjonalnie z załącznikami.
 *
 * <p>Załączniki muszą być uprzednio załadowane przez endpoint
 * {@code POST /api/email/attachments/upload}, który zwraca {@code s3Key}.
 */
public record EmailReplyRequest(

        @NotBlank(message = "Treść odpowiedzi jest wymagana")
        String bodyHtml,

        @Size(max = 998, message = "Temat nie może być dłuższy niż 998 znaków")
        String subject,

        /**
         * Lista załączników do dołączenia do odpowiedzi.
         * Null lub pusta lista oznacza brak załączników.
         * Każdy załącznik musi być wcześniej załadowany przez upload endpoint.
         */
        List<PendingAttachment> attachments
) {

    /**
     * Referencja do wcześniej załadowanego załącznika (pending upload w S3).
     *
     * @param s3Key       klucz S3 zwrócony przez upload endpoint
     * @param filename    oryginalna nazwa pliku
     * @param contentType MIME type
     * @param sizeBytes   rozmiar w bajtach
     */
    public record PendingAttachment(
            String s3Key,
            String filename,
            String contentType,
            long sizeBytes
    ) {}
}
