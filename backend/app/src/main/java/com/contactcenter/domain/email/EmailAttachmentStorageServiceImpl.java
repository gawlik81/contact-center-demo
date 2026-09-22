package com.contactcenter.domain.email;

import com.contactcenter.infrastructure.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Implementacja serwisu przechowywania załączników email w S3/MinIO.
 *
 * <p>Używa tych samych beanów {@link S3Client} i {@link S3Presigner} co nagrania,
 * skonfigurowanych w {@code S3Config} dla tego samego bucketu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class EmailAttachmentStorageServiceImpl implements EmailAttachmentStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    // =========================================================================
    // Zapis
    // =========================================================================

    @Override
    public String store(UUID tenantId, UUID messageId, String filename, String contentType, byte[] data) {
        String encodedFilename = encodeFilename(filename);
        String s3Key = EmailAttachmentKeys.inboundKey(tenantId, messageId, encodedFilename);
        upload(s3Key, contentType, data);
        return s3Key;
    }

    @Override
    public String storePending(UUID tenantId, UUID pendingId, String filename, String contentType, byte[] data) {
        String encodedFilename = encodeFilename(filename);
        String s3Key = EmailAttachmentKeys.pendingKey(tenantId, pendingId, encodedFilename);
        upload(s3Key, contentType, data);
        return s3Key;
    }

    // =========================================================================
    // Presigned URL
    // =========================================================================

    @Override
    public String presignedDownloadUrl(String s3Key) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(s3Properties.getPresignedUrlExpirationMinutes()))
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            log.debug("[EmailAttachment] Wygenerowano presigned URL: s3Key={}, ttlMin={}",
                    s3Key, s3Properties.getPresignedUrlExpirationMinutes());

            return url;
        } catch (S3Exception e) {
            log.error("[EmailAttachment] Błąd generowania presigned URL: s3Key={}, error={}",
                    s3Key, e.getMessage(), e);
            throw new EmailAttachmentException("Nie udało się wygenerować URL załącznika: " + s3Key, e);
        }
    }

    // =========================================================================
    // Pobieranie
    // =========================================================================

    @Override
    public byte[] download(String s3Key) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(getRequest);
            byte[] data = responseBytes.asByteArray();

            log.debug("[EmailAttachment] Pobrano załącznik z S3: s3Key={}, size={}B", s3Key, data.length);
            return data;
        } catch (S3Exception e) {
            log.error("[EmailAttachment] Błąd pobierania załącznika z S3: s3Key={}, error={}",
                    s3Key, e.getMessage(), e);
            throw new EmailAttachmentException("Nie udało się pobrać załącznika z S3: " + s3Key, e);
        }
    }

    // =========================================================================
    // Usuwanie (BE-125)
    // =========================================================================

    @Override
    public void delete(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            throw new IllegalArgumentException("Klucz S3 do usunięcia nie może być pusty");
        }
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            // Brak obiektu = sukces (S3/MinIO odpowiada 204) — ponowny przebieg jest idempotentny.
            s3Client.deleteObject(deleteRequest);

            log.debug("[EmailAttachment] Usunięto obiekt z S3: s3Key={}", EmailAttachmentKeys.forLog(s3Key));
        } catch (SdkException e) {
            // SdkException obejmuje S3Exception (odpowiedź usługi) ORAZ SdkClientException (sieć,
            // timeout) — dla „S3 przed wierszem" oba znaczą to samo: obiekt mógł nie zostać usunięty.
            log.error("[EmailAttachment] Błąd usuwania obiektu z S3: s3Key={}, error={}",
                    EmailAttachmentKeys.forLog(s3Key), e.getMessage(), e);
            throw new EmailAttachmentException(
                    "Nie udało się usunąć załącznika z S3: " + EmailAttachmentKeys.forLog(s3Key), e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private void upload(String s3Key, String contentType, byte[] data) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .contentLength((long) data.length)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(data));

            log.debug("[EmailAttachment] Upload S3 zakończony: key={}, size={}B", s3Key, data.length);
        } catch (S3Exception e) {
            log.error("[EmailAttachment] Błąd uploadu do S3: key={}, error={}", s3Key, e.getMessage(), e);
            throw new EmailAttachmentException("Upload załącznika do S3 nie powiódł się: " + s3Key, e);
        }
    }

    /**
     * Enkoduje nazwę pliku do bezpiecznego użycia w kluczu S3 (UTF-8 URL encoding).
     * Zachowuje rozszerzenie czytelne, zastępuje spacje i znaki specjalne.
     */
    private String encodeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "_");
        // URLEncoder nie koduje kropek: nazwa dokładnie "." lub ".." (kontrolowana przez nadawcę INBOUND)
        // dałaby segment klucza, który allow-lista purge (EmailAttachmentKeys#isOwnedByTenant) słusznie
        // odrzuca — obiekt zostałby nieusuwalny przez retencję. Oryginalna nazwa zostaje w JSONB attachments.
        return ".".equals(encoded) || "..".equals(encoded) ? "attachment" : encoded;
    }
}
