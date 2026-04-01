package com.contactcenter.domain.service;

import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.infrastructure.config.TwilioProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * Serwis odpowiedzialny za pobieranie nagrań z Twilio i ich przechowywanie w S3/MinIO.
 *
 * <p><strong>Przepływ:</strong>
 * <ol>
 *   <li>Pobierz plik MP3 z Twilio przez HTTP GET z Basic Auth (AccountSid:AuthToken)</li>
 *   <li>Streamuj plik tymczasowo na dysk lokalny (unika ładowania całości do pamięci)</li>
 *   <li>Uploaduj plik z dysku do S3/MinIO przez AWS SDK v2</li>
 *   <li>Usuń plik tymczasowy</li>
 *   <li>Zwróć klucz S3 jako wynik</li>
 * </ol>
 *
 * <p><strong>Obsługa błędów:</strong> metoda {@link #downloadAndStore} jest oznaczona
 * {@code @Async} – wyjątki są logowane przez {@link AsyncConfig} i nie propagują
 * do wątku webhook HTTP. Wewnętrznie metoda jest podzielona na synchroniczną
 * {@link #downloadAndStoreSync} używaną w testach i asynchroniczną otoczkę.
 *
 * <p><strong>Naming S3:</strong>
 * {@code {tenantId}/{year}/{month}/{contactId}.mp3} – zgodnie z konwencją {@link RecordingService#buildS3Key}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")
public class TwilioRecordingDownloadService {

    /** Timeout HTTP na połączenie z Twilio API. */
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Timeout HTTP na odczyt ciała odpowiedzi (nagrania mogą być duże). */
    private static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(120);


    private final TwilioProperties twilioProperties;
    private final S3Properties s3Properties;
    private final S3Client s3Client;
    private final RecordingService recordingService;

    // =========================================================================
    // Publiczne API – używane przez TwilioWebhookController
    // =========================================================================

    /**
     * Asynchronicznie pobiera nagranie z Twilio i zapisuje do S3/MinIO.
     *
     * <p>Po pomyślnym uploadzie aktualizuje {@code recording_url} w tabeli {@code contact}
     * delegując do {@link RecordingService#saveRecordingUrlToContact}.
     *
     * <p>Metoda jest {@code @Async} – webhook HTTP zwraca 204 natychmiast,
     * a faktyczne pobieranie dzieje się w tle (wątek {@code cc-async-*}).
     *
     * @param twilioRecordingUrl URL nagrania z Twilio (z dopisanym .mp3)
     * @param recordingSid       Twilio Recording SID (do nazwy obiektu w S3)
     * @param contactId          UUID kontaktu (do nazwy obiektu w S3 i aktualizacji DB)
     * @param tenantId           UUID tenanta (do nazwy obiektu i kontekstu DB)
     */
    @Async("applicationTaskExecutor")
    public void downloadAndStore(
            String twilioRecordingUrl,
            String recordingSid,
            UUID contactId,
            UUID tenantId) {

        log.info("[TwilioRecDownload] Start async pobierania: contactId={}, recordingSid={}, tenantId={}",
                contactId, recordingSid, tenantId);
        try {
            downloadAndStoreSync(twilioRecordingUrl, recordingSid, contactId, tenantId);
        } catch (Exception e) {
            log.error("[TwilioRecDownload] Błąd pobierania/uploadowania nagrania: " +
                      "contactId={}, recordingSid={}, tenantId={}, error={}",
                    contactId, recordingSid, tenantId, e.getMessage(), e);
            // Nie rzucamy dalej – AsyncUncaughtExceptionHandler loguje, ale wątek nie propaguje
        }
    }

    // =========================================================================
    // Metody wewnętrzne (package-private dla testów jednostkowych)
    // =========================================================================

    /**
     * Synchroniczna implementacja pobierania i uploadu nagrania.
     *
     * <p>Użycie pliku tymczasowego zamiast buforowania w pamięci pozwala obsłużyć
     * nagrania o dowolnej długości bez ryzyka OOM. Plik jest usuwany w bloku
     * {@code finally} niezależnie od sukcesu/błędu.
     *
     * @param twilioRecordingUrl pełny URL do pliku MP3 w Twilio
     * @param recordingSid       Recording SID (unikalny identyfikator nagrania Twilio)
     * @param contactId          UUID kontaktu
     * @param tenantId           UUID tenanta
     * @throws IOException      przy błędzie I/O (pobieranie lub plik tymczasowy)
     * @throws InterruptedException przy przerwaniu wątku HTTP
     */
    public void downloadAndStoreSync(
            String twilioRecordingUrl,
            String recordingSid,
            UUID contactId,
            UUID tenantId) throws IOException, InterruptedException {

        Path tempFile = null;
        try {
            // 1. Pobierz plik z Twilio do pliku tymczasowego (streaming, nie byte[])
            tempFile = downloadToTempFile(twilioRecordingUrl, recordingSid);

            // 2. Zbuduj klucz S3 zgodny z konwencją: {tenantId}/{year}/{month}/{contactId}.mp3
            String s3Key = buildS3Key(tenantId, contactId);

            // 3. Uploaduj plik do S3/MinIO
            uploadToS3(s3Key, tempFile);

            // 4. Zapisz klucz S3 do tabeli contact (deleguje do RecordingService)
            recordingService.saveRecordingUrlToContact(contactId, tenantId, s3Key);

            log.info("[TwilioRecDownload] Nagranie zapisane w S3: contactId={}, s3Key={}, size={}B",
                    contactId, s3Key, Files.size(tempFile));

        } finally {
            // Zawsze czyść plik tymczasowy
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException delEx) {
                    log.warn("[TwilioRecDownload] Nie udało się usunąć pliku tymczasowego {}: {}",
                            tempFile, delEx.getMessage());
                }
            }
        }
    }

    /**
     * Pobiera plik nagrania z Twilio do pliku tymczasowego.
     *
     * <p>Twilio wymaga Basic Auth: {@code Base64(AccountSid:AuthToken)}.
     * Plik jest streamowany przez {@link HttpResponse.BodyHandlers#ofInputStream()}
     * i kopiowany do pliku tymczasowego – nie trafia do pamięci heap.
     *
     * @param recordingUrl pełny URL nagrania (z .mp3)
     * @param recordingSid Recording SID (do nazwy pliku tymczasowego)
     * @return ścieżka do pobranego pliku tymczasowego
     * @throws IOException      przy błędzie sieci lub I/O
     * @throws InterruptedException przy przerwaniu wątku
     * @throws IllegalStateException gdy Twilio odpowie statusem != 200
     */
    private Path downloadToTempFile(String recordingUrl, String recordingSid)
            throws IOException, InterruptedException {

        String credentials = buildBasicAuthCredentials();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(recordingUrl))
                .header("Authorization", "Basic " + credentials)
                .timeout(HTTP_READ_TIMEOUT)
                .GET()
                .build();

        log.debug("[TwilioRecDownload] HTTP GET {}", recordingUrl);

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Twilio zwrócił HTTP " + response.statusCode() +
                    " dla RecordingSid=" + recordingSid +
                    ", URL=" + recordingUrl
            );
        }

        // Stwórz plik tymczasowy z czytelną nazwą dla debugowania
        Path tempFile = Files.createTempFile("twilio_rec_" + recordingSid + "_", ".mp3");

        try (InputStream bodyStream = response.body()) {
            long copied = Files.copy(bodyStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.debug("[TwilioRecDownload] Pobrano {}B z Twilio do pliku tymczasowego: {}",
                    copied, tempFile.getFileName());
        }

        return tempFile;
    }

    /**
     * Uploaduje plik z dysku do S3/MinIO z content-type audio/mpeg.
     *
     * <p>Używa {@link RequestBody#fromFile(Path)} zamiast {@code fromInputStream} –
     * AWS SDK v2 wymaga {@code Content-Length} w żądaniu PUT, który jest znany
     * dopiero po zapisaniu do pliku tymczasowego.
     *
     * @param s3Key    klucz obiektu w S3 (np. recordings/{tenantId}/{contactId}/{sid}.mp3)
     * @param filePath ścieżka do pliku lokalnego
     * @throws S3Exception przy błędzie po stronie S3/MinIO
     */
    private void uploadToS3(String s3Key, Path filePath) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType("audio/mpeg")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromFile(filePath));

            log.debug("[TwilioRecDownload] Upload S3 zakończony: key={}", s3Key);

        } catch (S3Exception e) {
            log.error("[TwilioRecDownload] Błąd uploadu do S3: key={}, error={}", s3Key, e.getMessage(), e);
            throw new RecordingService.RecordingException("Upload nagrania Twilio do S3 nie powiódł się: " + s3Key, e);
        }
    }

    /**
     * Buduje klucz S3 delegując do {@link RecordingService#buildS3Key}.
     * Format: {@code {tenantId}/{year}/{month}/{contactId}.mp3}
     */
    public String buildS3Key(UUID tenantId, UUID contactId) {
        return recordingService.buildS3Key(tenantId, contactId, null);
    }

    /**
     * Buduje wartość nagłówka Basic Auth z danych Twilio.
     *
     * <p>Format RFC 7617: {@code Base64(accountSid:authToken)}.
     * Twilio wymaga tej formy autentykacji przy pobieraniu pliku nagrania.
     *
     * @return zakodowane credentials (sam Base64, bez prefiksu "Basic ")
     */
    private String buildBasicAuthCredentials() {
        String accountSid = twilioProperties.getAccountSid();
        String authToken  = twilioProperties.getAuthToken();

        if (accountSid == null || accountSid.isBlank() || authToken == null || authToken.isBlank()) {
            throw new IllegalStateException(
                    "Brak konfiguracji Twilio credentials (twilio.account-sid / twilio.auth-token). " +
                    "Ustaw zmienne środowiskowe TWILIO_ACCOUNT_SID i TWILIO_AUTH_TOKEN."
            );
        }

        return Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
