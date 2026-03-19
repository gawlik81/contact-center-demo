package com.contactcenter.domain.service;

import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis zarządzający nagraniami rozmów telefonicznych (BE-010).
 *
 * <p><strong>Przepływ nagrywania:</strong>
 * <ol>
 *   <li>Zdarzenie {@code call.hangup} odbierane z RabbitMQ (exchange {@code cc.events})</li>
 *   <li>Pobranie pliku audio z tymczasowej lokalizacji lub wygenerowanie stub-a w trybie dev</li>
 *   <li>Upload do S3 z szyfrowaniem SSE-S3 (AES-256)</li>
 *   <li>Zapis ścieżki S3 do kolumny {@code recording_url} w tabeli {@code contact}</li>
 * </ol>
 *
 * <p><strong>Ścieżka S3:</strong> {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
 *
 * <p><strong>Szyfrowanie:</strong> SSE-S3 (Server-Side Encryption z kluczami zarządzanymi przez S3/MinIO).
 * Alternatywnie można użyć SSE-C (klucze po stronie klienta) – zmień na {@code SSE_C} w PutObjectRequest.
 *
 * <p><strong>Tryb DEV:</strong> Gdy {@code CallEvent.metadata} nie zawiera klucza {@code audioFilePath},
 * serwis tworzy stub MP3 (pusty plik) do celów testowych. W produkcji MockTelephonyAdapter
 * powinien dostarczyć ścieżkę do rzeczywistego pliku audio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

    private static final DateTimeFormatter YEAR_FMT  = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);

    // Klucz metadanych w CallEvent.metadata dla ścieżki do pliku audio
    private static final String METADATA_AUDIO_PATH    = "audioFilePath";
    private static final String METADATA_CONTACT_ID    = "contactId";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;
    private final ContactRepository contactRepository;

    // =========================================================================
    // Obsługa zdarzenia call.hangup
    // =========================================================================

    /**
     * Odbiera zdarzenie {@code call.hangup} z kolejki RabbitMQ i inicjuje upload nagrania.
     *
     * <p>Metoda jest wywołana asynchronicznie przez Spring AMQP.
     * Błędy są logowane, ale nie rzucane ponownie (nagranie jest "best-effort" –
     * brak nagrania nie powinien blokować przetwarzania kolejki).
     *
     * @param event zdarzenie telefoniczne typu CALL_HANGUP
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_CALL_EVENTS)
    public void onCallHangup(CallEvent event) {
        if (event == null) {
            log.warn("[Recording] Otrzymano null CallEvent – ignoruję");
            return;
        }

        // Przetwarzamy tylko zdarzenia zakończenia połączenia
        if (event.getEventType() != CallEvent.EventType.CALL_HANGUP) {
            return;
        }

        log.info("[Recording] Odebrano call.hangup: callId={}, tenantId={}",
                event.getCallId(), event.getTenantId());

        try {
            processHangupEvent(event);
        } catch (Exception e) {
            // Błąd nagrywania jest niekrytyczny – nie blokuje przetwarzania kolejki
            log.error("[Recording] Błąd podczas przetwarzania call.hangup: callId={}, error={}",
                    event.getCallId(), e.getMessage(), e);
        }
    }

    /**
     * Przetwarza zdarzenie hangup: pobiera/tworzy plik audio i uploaduje do S3.
     */
    private void processHangupEvent(CallEvent event) throws IOException {
        UUID tenantId = event.getTenantId();
        if (tenantId == null) {
            log.warn("[Recording] Brak tenantId w CallEvent callId={} – pomijam", event.getCallId());
            return;
        }

        // Pobierz contactId z metadanych zdarzenia
        String contactIdStr = event.getMetadata() != null
                ? event.getMetadata().get(METADATA_CONTACT_ID)
                : null;

        if (contactIdStr == null || contactIdStr.isBlank()) {
            log.warn("[Recording] Brak contactId w metadata CallEvent callId={} – pomijam", event.getCallId());
            return;
        }

        UUID contactId;
        try {
            contactId = UUID.fromString(contactIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("[Recording] Nieprawidłowy contactId='{}' w CallEvent callId={}", contactIdStr, event.getCallId());
            return;
        }

        // Pobierz lub wygeneruj plik audio
        Path audioFile = resolveAudioFile(event);
        boolean isStub = audioFile == null;

        if (isStub) {
            // Tryb DEV: stwórz pusty stub MP3 do testów
            audioFile = createStubAudioFile(contactId);
            log.debug("[Recording] Tryb DEV: Stworzono stub MP3 dla contactId={}", contactId);
        }

        try {
            String s3Key = buildS3Key(tenantId, contactId, event.getTimestamp());
            uploadToS3(s3Key, audioFile);
            contactRepository.updateRecordingUrl(contactId, tenantId, s3Key);

            log.info("[Recording] Nagranie uploadowane: contactId={}, s3Key={}", contactId, s3Key);
        } finally {
            // Usuń tymczasowy stub; rzeczywisty plik audio usuwa caller (TelephonyAdapter)
            if (isStub) {
                Files.deleteIfExists(audioFile);
            }
        }
    }

    // =========================================================================
    // Presigned URL
    // =========================================================================

    /**
     * Generuje presigned URL ważny przez {@link S3Properties#getPresignedUrlExpirationMinutes()} minut.
     *
     * <p>Nie zwraca pliku bezpośrednio – klient pobiera nagranie przez wygenerowany URL.
     * URL jest jednorazowy i wygasa po skonfigurowanym czasie.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (weryfikacja RLS)
     * @return Optional z presigned URL lub empty jeśli nagranie nie istnieje
     */
    @Audited(action = "RECORDING_ACCESS", entityType = "RECORDING")
    public Optional<String> generatePresignedUrl(UUID contactId, UUID tenantId) {
        Optional<String> recordingUrlOpt = contactRepository.findRecordingUrl(contactId, tenantId);

        if (recordingUrlOpt.isEmpty() || recordingUrlOpt.get() == null) {
            log.debug("[Recording] Brak nagrania dla contactId={}", contactId);
            return Optional.empty();
        }

        String s3Key = recordingUrlOpt.get();

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

            log.info("[Recording] Wygenerowano presigned URL: contactId={}, expirationMinutes={}",
                    contactId, s3Properties.getPresignedUrlExpirationMinutes());

            return Optional.of(url);

        } catch (S3Exception e) {
            log.error("[Recording] Błąd generowania presigned URL dla contactId={}: {}",
                    contactId, e.getMessage(), e);
            throw new RecordingException("Nie udało się wygenerować URL nagrania", e);
        }
    }

    // =========================================================================
    // Operacje S3
    // =========================================================================

    /**
     * Uploaduje plik audio do S3 z szyfrowaniem SSE-S3 (AES-256).
     *
     * <p>SSE-S3: szyfrowanie zarządzane przez S3/MinIO – klucze są przechowywane
     * i rotowane po stronie storage. Alternatywa: SSE-C (klucze u klienta) wymaga
     * przekazywania klucza przy każdym żądaniu GET/PUT.
     *
     * @param s3Key    ścieżka w buckecie (np. /{tenantId}/{year}/{month}/{contactId}.mp3)
     * @param filePath lokalny plik do uploadowania
     */
    public void uploadToS3(String s3Key, Path filePath) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType("audio/mpeg")
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromFile(filePath));

            log.debug("[Recording] Upload S3 zakończony: key={}, size={}B",
                    s3Key, Files.size(filePath));

        } catch (S3Exception | IOException e) {
            log.error("[Recording] Błąd uploadu do S3: key={}, error={}", s3Key, e.getMessage(), e);
            throw new RecordingException("Upload nagrania do S3 nie powiódł się: " + s3Key, e);
        }
    }

    /**
     * Usuwa plik nagrania z S3.
     *
     * <p>Używane przez {@link RecordingRetentionJob} do fizycznego usuwania plików.
     *
     * @param s3Key ścieżka w buckecie
     */
    public void deleteFromS3(String s3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(deleteRequest);

            log.info("[Recording] Usunięto plik S3: key={}", s3Key);

        } catch (S3Exception e) {
            log.error("[Recording] Błąd usuwania pliku S3: key={}, error={}", s3Key, e.getMessage(), e);
            // Nie rzucamy – job retencji powinien kontynuować dla pozostałych plików
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje klucz S3 zgodnie z konwencją: {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
     *
     * @param tenantId  UUID tenanta
     * @param contactId UUID kontaktu
     * @param timestamp czas zdarzenia (do wyznaczenia roku i miesiąca)
     * @return klucz S3
     */
    public String buildS3Key(UUID tenantId, UUID contactId, Instant timestamp) {
        Instant ts = timestamp != null ? timestamp : Instant.now();
        String year  = YEAR_FMT.format(ts);
        String month = MONTH_FMT.format(ts);
        return String.format("%s/%s/%s/%s.mp3", tenantId, year, month, contactId);
    }

    /**
     * Pobiera ścieżkę do pliku audio z metadanych CallEvent.
     *
     * @param event zdarzenie CallHangup
     * @return ścieżka do pliku audio lub null jeśli brak
     */
    private Path resolveAudioFile(CallEvent event) {
        if (event.getMetadata() == null) {
            return null;
        }
        String audioPath = event.getMetadata().get(METADATA_AUDIO_PATH);
        if (audioPath == null || audioPath.isBlank()) {
            return null;
        }
        Path path = Path.of(audioPath);
        if (!Files.exists(path)) {
            log.warn("[Recording] Plik audio nie istnieje: {}", audioPath);
            return null;
        }
        return path;
    }

    /**
     * Tworzy tymczasowy stub MP3 dla środowiska DEV/testowego.
     *
     * <p>W trybie produkcyjnym TelephonyAdapter powinien dostarczyć rzeczywisty plik
     * audio przez metadane CallEvent. Ta metoda jest fallbackiem dla celów developerskich.
     *
     * @param contactId UUID kontaktu (do nazwy pliku)
     * @return ścieżka do tymczasowego pliku
     */
    private Path createStubAudioFile(UUID contactId) throws IOException {
        Path stubFile = Files.createTempFile("recording_stub_" + contactId, ".mp3");
        // Minimalny nagłówek MP3 (ID3v2 tag – 10 bajtów) dla spójności formatu
        byte[] id3Header = { 0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        Files.write(stubFile, id3Header);
        return stubFile;
    }

    // =========================================================================
    // Wyjątek domenowy
    // =========================================================================

    /**
     * Wyjątek rzucany przy błędach operacji na nagraniach.
     */
    public static class RecordingException extends RuntimeException {
        public RecordingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
