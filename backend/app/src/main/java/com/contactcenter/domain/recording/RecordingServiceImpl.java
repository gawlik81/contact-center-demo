package com.contactcenter.domain.recording;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.aspect.Audited;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
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

@Slf4j
@Service
@RequiredArgsConstructor
class RecordingServiceImpl implements RecordingService {

    private static final DateTimeFormatter YEAR_FMT  = DateTimeFormatter.ofPattern("yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);

    // Klucz metadanych w CallEvent.metadata dla ścieżki do pliku audio
    private static final String METADATA_AUDIO_PATH    = "audioFilePath";
    private static final String METADATA_CONTACT_ID    = "contactId";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;
    private final WebSocketEventBroadcaster wsEventBroadcaster;

    /**
     * Konfiguracja Twilio – wstrzykiwana opcjonalnie (null gdy twilio.enabled=false).
     * Używana do pominięcia tworzenia stub MP3 gdy aktywny jest provider Twilio
     * (nagranie zarządzane po stronie Twilio przez recording callback).
     */
    @Autowired(required = false)
    private TwilioProperties twilioProperties;

    /**
     * ContactService – wstrzykiwany przez setter z {@code @Lazy} aby uniknąć
     * circular dependency: ContactServiceImpl → RecordingService → ContactService.
     */
    private ContactService contactService;

    @Autowired
    @Lazy
    public void setContactService(ContactService contactService) {
        this.contactService = contactService;
    }

    // =========================================================================
    // Obsługa zdarzenia call.hangup
    // =========================================================================

    @Override
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

        // Pobierz contactId – najpierw z dedykowanego pola, fallback do metadanych (legacy)
        UUID contactId = event.getContactId();
        if (contactId == null && event.getMetadata() != null) {
            String contactIdStr = event.getMetadata().get(METADATA_CONTACT_ID);
            if (contactIdStr != null && !contactIdStr.isBlank()) {
                try {
                    contactId = UUID.fromString(contactIdStr);
                } catch (IllegalArgumentException e) {
                    log.warn("[Recording] Nieprawidłowy contactId='{}' w metadata CallEvent callId={}", contactIdStr, event.getCallId());
                }
            }
        }

        if (contactId == null) {
            log.warn("[Recording] Brak contactId w CallEvent callId={} – pomijam", event.getCallId());
            return;
        }

        // Pobierz lub wygeneruj plik audio
        Path audioFile = resolveAudioFile(event);
        boolean isStub = audioFile == null;

        if (isStub) {
            // Gdy Twilio jest aktywne – nagranie jest zarządzane przez Twilio Recording API
            // i dostarczane przez callback POST /api/telephony/webhook/twilio/recording.
            // RecordingService nie powinien tworzyć stub MP3 ani nadpisywać recording_url.
            if (twilioProperties != null && twilioProperties.isEnabled()) {
                log.debug("[Recording] Twilio provider aktywny – pomijam stub MP3 dla callId={}. " +
                          "Nagranie przyjdzie przez /recording webhook.", event.getCallId());
                return;
            }
            // Tryb DEV (MockTelephonyAdapter): stwórz pusty stub MP3 do testów
            audioFile = createStubAudioFile(contactId);
            log.debug("[Recording] Tryb DEV: Stworzono stub MP3 dla contactId={}", contactId);
        }

        try {
            String s3Key = buildS3Key(tenantId, contactId, event.getTimestamp());
            uploadToS3(s3Key, audioFile);

            // Wątek RabbitMQ listener nie przechodzi przez TenantFilter – ustawiamy kontekst ręcznie
            TenantContext.setTenantId(tenantId);
            try {
                contactService.updateRecordingUrl(contactId, tenantId, s3Key);
            } finally {
                TenantContext.clear();
            }
            notifyRecordingReady(contactId, tenantId);

            log.info("[Recording] Nagranie uploadowane: contactId={}, s3Key={}", contactId, s3Key);
        } finally {
            // Usuń tymczasowy stub; rzeczywisty plik audio usuwa caller (TelephonyAdapter)
            if (isStub) {
                Files.deleteIfExists(audioFile);
            }
        }
    }

    // =========================================================================
    // Zapis URL nagrania do kontaktu (używane przez TwilioRecordingDownloadService)
    // =========================================================================

    @Override
    public void saveRecordingUrlToContact(UUID contactId, UUID tenantId, String s3Key) {
        UUID previousTenantId = TenantContext.getTenantIdOrNull();
        TenantContext.setTenantId(tenantId);
        try {
            contactService.updateRecordingUrl(contactId, tenantId, s3Key);
            log.info("[Recording] Zapisano recording_url w DB: contactId={}, s3Key={}", contactId, s3Key);
        } finally {
            if (previousTenantId != null) {
                TenantContext.setTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
    }

    @Override
    public void notifyRecordingReady(UUID contactId, UUID tenantId) {
        UUID previousTenantId = TenantContext.getTenantIdOrNull();
        TenantContext.setTenantId(tenantId);
        try {
            contactService.findContactEntity(contactId, tenantId).ifPresent(contact -> {
                if (contact.getAgentId() != null) {
                    wsEventBroadcaster.sendToUser(contact.getAgentId(),
                            WebSocketEvent.recordingReady(tenantId, contact.getAgentId(), contactId));
                }
            });
        } finally {
            if (previousTenantId != null) {
                TenantContext.setTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
    }

    // =========================================================================
    // Presigned URL
    // =========================================================================

    @Override
    public String generatePresignedUrlForKey(String s3Key, Duration ttl) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            log.info("[Recording] Wygenerowano presigned URL (BE-037): s3Key={}, ttlMinutes={}",
                    s3Key, ttl.toMinutes());

            return url;

        } catch (S3Exception e) {
            log.error("[Recording] Błąd generowania presigned URL dla s3Key={}: {}",
                    s3Key, e.getMessage(), e);
            throw new RecordingException("Nie udało się wygenerować URL nagrania dla: " + s3Key, e);
        }
    }

    @Override
    @Audited(action = "RECORDING_ACCESS", entityType = "RECORDING")
    public Optional<String> generatePresignedUrl(UUID contactId, UUID tenantId) {
        Optional<String> recordingUrlOpt = contactService.findRecordingUrl(contactId, tenantId);

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

    @Override
    public void uploadToS3(String s3Key, Path filePath) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType("audio/mpeg")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromFile(filePath));

            log.debug("[Recording] Upload S3 zakończony: key={}, size={}B",
                    s3Key, Files.size(filePath));

        } catch (S3Exception | IOException e) {
            log.error("[Recording] Błąd uploadu do S3: key={}, error={}", s3Key, e.getMessage(), e);
            throw new RecordingException("Upload nagrania do S3 nie powiódł się: " + s3Key, e);
        }
    }

    @Override
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

    @Override
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
}
