package com.contactcenter.api.contact.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Odpowiedź z presigned URL do nagrania kontaktu (BE-037).
 *
 * <p>URL wygasa po 15 minutach od wygenerowania.
 * Klient powinien odtworzyć lub pobrać nagranie przed upłynięciem {@code expiresAt}.
 */
@Schema(description = "Presigned URL do nagrania kontaktu (TTL 15 minut)")
public record ContactRecordingUrlResponse(

    @Schema(
        description = "Presigned URL ważny przez 15 minut. " +
                      "Umożliwia bezpośrednie pobranie pliku audio bez dodatkowego uwierzytelnienia.",
        example = "https://minio.example.com/recordings/tenant-id/2026/04/contact-id.mp3?X-Amz-Expires=900&..."
    )
    String presignedUrl,

    @Schema(
        description = "Czas wygaśnięcia presigned URL (ISO-8601 UTC). " +
                      "Po tym czasie URL przestaje działać.",
        example = "2026-04-08T12:15:00Z"
    )
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant expiresAt,

    @Schema(
        description = "Nazwa pliku nagrania w MinIO/S3 (klucz obiektu).",
        example = "11111111-1111-1111-1111-111111111111/2026/04/22222222-2222-2222-2222-222222222222.mp3"
    )
    String fileName,

    @Schema(
        description = "Czas trwania rozmowy w sekundach. NULL gdy nagranie istnieje, " +
                      "ale czas trwania nie został jeszcze obliczony przez trigger bazy danych.",
        example = "185",
        nullable = true
    )
    Integer durationSeconds

) {}
