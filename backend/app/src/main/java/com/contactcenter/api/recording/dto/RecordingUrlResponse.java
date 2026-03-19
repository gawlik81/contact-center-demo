package com.contactcenter.api.recording.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Odpowiedź z presigned URL do pobrania nagrania rozmowy (BE-010).
 *
 * <p>URL wygasa po czasie skonfigurowanym w {@code s3.presigned-url-expiration-minutes}
 * (domyślnie 60 minut). Klient powinien użyć URL przed upłynięciem terminu ważności.
 */
@Schema(description = "Presigned URL do jednorazowego pobrania nagrania rozmowy")
public record RecordingUrlResponse(

    @Schema(
        description = "UUID kontaktu (rozmowy), do której należy nagranie",
        example = "550e8400-e29b-41d4-a716-446655440000"
    )
    UUID contactId,

    @Schema(
        description = "Presigned URL ważny przez skonfigurowany czas (domyślnie 1h). " +
                      "URL umożliwia bezpośrednie pobranie pliku MP3 bez dodatkowego uwierzytelnienia.",
        example = "https://minio.example.com/contact-center-recordings/tenant-id/2026/03/contact-id.mp3?X-Amz-Expires=3600&..."
    )
    String presignedUrl,

    @Schema(
        description = "Czas wygaśnięcia presigned URL (ISO-8601 UTC)",
        example = "2026-03-19T15:00:00Z"
    )
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant expiresAt

) {}
