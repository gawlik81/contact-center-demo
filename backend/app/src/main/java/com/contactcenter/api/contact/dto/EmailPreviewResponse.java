package com.contactcenter.api.contact.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * DTO z podglądem treści wiadomości email powiązanej z kontaktem.
 *
 * <p>Zwracany przez {@code GET /api/contacts/{id}/email-preview}.
 * Zawiera nagłówki wiadomości (from, to, cc, subject) oraz treść HTML i/lub tekstową.
 */
@Schema(description = "Podgląd treści wiadomości email powiązanej z kontaktem")
public record EmailPreviewResponse(

        @Schema(description = "Adres nadawcy (format RFC 2822: 'Name <email@domain.com>')",
                example = "Jan Kowalski <jan.kowalski@example.com>")
        String from,

        @Schema(description = "Adres(y) odbiorcy w polu To (może być lista RFC 2822 rozdzielona przecinkami)",
                example = "support@firma.pl")
        String to,

        @Schema(description = "Adres(y) odbiorcy w polu CC (opcjonalne, może być null)",
                example = "manager@firma.pl", nullable = true)
        String cc,

        @Schema(description = "Temat wiadomości email",
                example = "Prośba o pomoc z zamówieniem #12345")
        String subject,

        @Schema(description = "Treść wiadomości w formacie HTML (może być null gdy brak treści HTML)",
                nullable = true)
        String bodyHtml,

        @Schema(description = "Treść wiadomości w formacie plain text (może być null gdy brak treści tekstowej)",
                nullable = true)
        String bodyText,

        @Schema(description = "Data/czas odebrania wiadomości (format ISO-8601, null dla OUTBOUND)",
                example = "2026-04-17T10:30:00Z", nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant receivedAt,

        @Schema(description = "Kierunek wiadomości: INBOUND (przychodzące) lub OUTBOUND (wychodzące)",
                example = "INBOUND", allowableValues = {"INBOUND", "OUTBOUND"})
        String direction,

        @Schema(description = "Lista załączników wiadomości")
        List<AttachmentDto> attachments
) {

    public record AttachmentDto(
            String filename,
            String contentType,
            long sizeBytes,
            String s3Key
    ) {}
}
