package com.contactcenter.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Konfiguracja AWS SDK v2 – klient S3 i generator presigned URL.
 *
 * <p>Obsługuje zarówno AWS S3 jak i S3-compatible storage (MinIO).
 * Dla MinIO wymagane jest path-style access ({@link S3Properties#isPathStyleAccessEnabled()}).
 *
 * <p>Szyfrowanie SSE-S3 (AES-256) jest ustawiane na poziomie każdego {@code PutObjectRequest}
 * w {@link com.contactcenter.domain.recording.RecordingService}, nie na poziomie bucketu.
 * To daje elastyczność – część plików może być nieszyfrowana (np. testowe).
 *
 * <h3>Zmienne środowiskowe (produkcja):</h3>
 * <pre>
 * S3_ENDPOINT    – URL endpointu (np. https://s3.amazonaws.com)
 * S3_ACCESS_KEY  – klucz dostępu
 * S3_SECRET_KEY  – klucz tajny
 * S3_BUCKET      – nazwa bucketu
 * S3_REGION      – region (np. eu-central-1)
 * </pre>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final S3Properties s3Properties;

    /**
     * Klient S3 – do operacji PUT/DELETE.
     *
     * <p>Konfiguracja:
     * <ul>
     *   <li>Statyczne credentials (access key + secret key)</li>
     *   <li>Path-style access (wymagane dla MinIO)</li>
     *   <li>Niestandardowy endpoint (dla MinIO lub self-hosted S3)</li>
     * </ul>
     */
    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                s3Properties.getAccessKey(),
                s3Properties.getSecretKey()
        );

        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(s3Properties.isPathStyleAccessEnabled())
                .build();

        S3Client client = S3Client.builder()
                .region(Region.of(s3Properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(URI.create(s3Properties.getEndpoint()))
                .serviceConfiguration(s3Config)
                .build();

        log.info("[S3] Skonfigurowano S3Client: endpoint={}, bucket={}, region={}",
                s3Properties.getEndpoint(), s3Properties.getBucket(), s3Properties.getRegion());

        return client;
    }

    /**
     * Generator presigned URL – do operacji GET (odczyt nagrań przez klienta).
     *
     * <p>Używany przez {@link com.contactcenter.domain.recording.RecordingService}
     * do generowania jednorazowych linków z TTL 1h.
     *
     * <p>{@code S3Presigner} jest osobnym klientem od {@code S3Client} –
     * generuje URL bez faktycznego wywołania API (lokalnie, na podstawie credentials).
     */
    @Bean
    public S3Presigner s3Presigner() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                s3Properties.getAccessKey(),
                s3Properties.getSecretKey()
        );

        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(s3Properties.isPathStyleAccessEnabled())
                .build();

        String presignerEndpoint = s3Properties.getEffectivePresignerEndpoint();
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(s3Properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .endpointOverride(URI.create(presignerEndpoint))
                .serviceConfiguration(s3Config)
                .build();

        log.info("[S3] Skonfigurowano S3Presigner: endpoint={}, expirationMinutes={}",
                presignerEndpoint, s3Properties.getPresignedUrlExpirationMinutes());

        return presigner;
    }
}
