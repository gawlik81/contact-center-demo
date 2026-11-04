package com.contactcenter.api.contact.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO z podglądem CAŁEJ rozmowy e-mail powiązanej z kontaktem.
 *
 * <p>Zwracany przez {@code GET /api/contacts/{id}/email-preview}. Zawiera wiadomości kontaktu w kolejności
 * chronologicznej (najstarsza pierwsza), każda z nagłówkami, treścią HTML/tekstową, załącznikami i flagą
 * dostępności własnego archiwum EML ({@code GET /api/contacts/{id}/email-messages/{messageId}/eml}).
 */
@Schema(description = "Podgląd całej rozmowy e-mail kontaktu: wiadomości chronologicznie (ASC), licznik i flaga ucięcia")
public record EmailPreviewResponse(

        @Schema(description = "Wiadomości kontaktu chronologicznie (message_at ASC, potem created_at ASC). " +
                "Gdy rozmowa przekracza limit, zwracane są NAJNOWSZE wiadomości (starsze są ucięte).")
        List<MessageDto> messages,

        @Schema(description = "Łączna liczba wiadomości kontaktu (bez limitu)", example = "3")
        long totalMessages,

        @Schema(description = "true gdy wiadomości jest więcej niż zwrócono (limit 100)", example = "false")
        boolean truncated
) {

    /** Jedna wiadomość rozmowy. */
    @Schema(description = "Wiadomość e-mail w rozmowie kontaktu")
    public record MessageDto(

            @Schema(description = "UUID wiadomości (parametr {messageId} endpointu EML)")
            UUID messageId,

            @Schema(description = "Adres nadawcy (format RFC 2822: 'Name <email@domain.com>')",
                    example = "Jan Kowalski <jan.kowalski@example.com>")
            String from,

            @Schema(description = "Adres(y) odbiorcy w polu To (lista RFC 2822 rozdzielona przecinkami)",
                    example = "support@firma.pl")
            String to,

            @Schema(description = "Adres(y) odbiorcy w polu CC (opcjonalne)", example = "manager@firma.pl",
                    nullable = true)
            String cc,

            @Schema(description = "Temat wiadomości", example = "Prośba o pomoc z zamówieniem #12345")
            String subject,

            @Schema(description = "Treść HTML (null gdy brak)", nullable = true)
            String bodyHtml,

            @Schema(description = "Treść plain text (null gdy brak)", nullable = true)
            String bodyText,

            @Schema(description = "Data/czas odebrania (ISO-8601, null dla OUTBOUND)",
                    example = "2026-04-17T10:30:00Z", nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant receivedAt,

            @Schema(description = "Znacznik czasu wiadomości (INBOUND: INTERNALDATE, OUTBOUND: czas wysłania); " +
                    "zawsze obecny, klucz sortowania", example = "2026-04-17T10:30:00Z")
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Instant messageAt,

            @Schema(description = "Kierunek: INBOUND lub OUTBOUND", example = "INBOUND",
                    allowableValues = {"INBOUND", "OUTBOUND"})
            String direction,

            @Schema(description = "Załączniki wiadomości")
            List<AttachmentDto> attachments,

            @Schema(description = "true gdy istnieje archiwum EML tej wiadomości (eml_s3_key albo, dla " +
                    "najwcześniejszej wiadomości sprzed V145, contact.recording_url zakończone na .eml)")
            boolean emlAvailable
    ) {}

    public record AttachmentDto(
            String filename,
            String contentType,
            long sizeBytes,
            String s3Key
    ) {}
}
