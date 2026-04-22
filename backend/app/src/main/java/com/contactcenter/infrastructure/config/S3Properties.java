package com.contactcenter.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Właściwości konfiguracyjne S3-compatible storage (MinIO / AWS S3).
 *
 * <p>Źródła konfiguracji (w kolejności priorytetu):
 * <ol>
 *   <li>Zmienne ENV (produkcja): {@code S3_ENDPOINT}, {@code S3_ACCESS_KEY},
 *       {@code S3_SECRET_KEY}, {@code S3_BUCKET}, {@code S3_REGION}</li>
 *   <li>application-dev.yml (lokalne środowisko deweloperskie)</li>
 *   <li>application.yml (wartości domyślne – puste/placeholder)</li>
 * </ol>
 *
 * <p>W środowisku DEV domyślnie wskazuje na lokalny MinIO uruchomiony przez Docker Compose.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "s3")
public class S3Properties {

    /**
     * Endpoint S3-compatible storage.
     * DEV: http://localhost:9000 (MinIO Docker)
     * PROD: https://s3.amazonaws.com lub URL MinIO produkcyjnego
     */
    private String endpoint = "http://localhost:9000";

    /**
     * Access key / klucz dostępu.
     * DEV: minioadmin (domyślny dla lokalnego MinIO)
     * PROD: ustaw przez ENV var S3_ACCESS_KEY
     */
    private String accessKey = "minioadmin";

    /**
     * Secret key / klucz tajny.
     * DEV: minioadmin (domyślny dla lokalnego MinIO)
     * PROD: ustaw przez ENV var S3_SECRET_KEY
     */
    private String secretKey = "minioadmin";

    /**
     * Nazwa bucketu S3.
     * Format: tylko małe litery, cyfry i myślniki.
     */
    private String bucket = "contact-center-recordings";

    /**
     * Region AWS S3 (ignorowany dla MinIO, ale wymagany przez AWS SDK v2).
     * DEV: us-east-1 (MinIO akceptuje dowolny region)
     * PROD: docelowy region AWS
     */
    private String region = "us-east-1";

    /**
     * Domyślna liczba dni retencji nagrań (90 dni zgodnie z wymaganiami).
     * Nadpisywalna per tenant przez JSONB config w tabeli TENANT.
     */
    private int retentionDays = 90;

    /**
     * Czas ważności presigned URL w minutach (domyślnie 60 min = 1h).
     */
    private long presignedUrlExpirationMinutes = 60;

    /**
     * Czy wymuszać path-style access (wymagane dla MinIO i self-hosted S3).
     * AWS S3: false (virtual-hosted-style)
     * MinIO: true
     */
    private boolean pathStyleAccessEnabled = true;

    /**
     * Publiczny endpoint używany wyłącznie przez S3Presigner do generowania presigned URL.
     * Gdy ustawiony (np. https://myngrok.ngrok-free.dev), presigned URL będzie zawierał
     * ten hostname zamiast wewnętrznego (minio:9000), dzięki czemu URL jest dostępny z przeglądarki.
     * Gdy null/pusty – używany jest {@link #endpoint}.
     *
     * ENV: S3_PUBLIC_ENDPOINT (opcjonalne)
     */
    private String publicEndpoint;

    public String getEffectivePresignerEndpoint() {
        return (publicEndpoint != null && !publicEndpoint.isBlank()) ? publicEndpoint : endpoint;
    }
}
