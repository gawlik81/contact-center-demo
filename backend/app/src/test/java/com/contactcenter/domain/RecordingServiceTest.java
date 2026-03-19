package com.contactcenter.domain;

import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.service.RecordingService;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.infrastructure.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link RecordingService}.
 *
 * <p>Używa mocków S3Client, S3Presigner i ContactRepository.
 * Nie wymaga uruchomionej infrastruktury (S3, DB, RabbitMQ).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingService – nagrywanie rozmów")
class RecordingServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID  = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String BUCKET    = "contact-center-recordings";

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;
    @Mock private ContactRepository contactRepository;

    private S3Properties s3Properties;
    private RecordingService recordingService;

    @BeforeEach
    void setUp() {
        s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);
        s3Properties.setPresignedUrlExpirationMinutes(60);
        s3Properties.setRetentionDays(90);

        recordingService = new RecordingService(s3Client, s3Presigner, s3Properties, contactRepository);
    }

    // =========================================================================
    // buildS3Key
    // =========================================================================

    @Nested
    @DisplayName("buildS3Key()")
    class BuildS3Key {

        @Test
        @DisplayName("powinien zbudować klucz S3 w formacie /{tenantId}/{year}/{month}/{contactId}.mp3")
        void shouldBuildCorrectS3Key() {
            Instant ts = Instant.parse("2026-03-19T10:00:00Z");

            String key = recordingService.buildS3Key(TENANT_ID, CONTACT_ID, ts);

            assertThat(key).isEqualTo(TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3");
        }

        @Test
        @DisplayName("powinien użyć aktualnego czasu gdy timestamp jest null")
        void shouldUseCurrentTimeWhenTimestampNull() {
            String key = recordingService.buildS3Key(TENANT_ID, CONTACT_ID, null);

            // Klucz powinien zawierać tenantId i contactId
            assertThat(key).startsWith(TENANT_ID.toString());
            assertThat(key).endsWith(CONTACT_ID + ".mp3");
            // Oraz prawidłowy format roku/miesiąca (4 cyfry/2 cyfry)
            assertThat(key).matches(TENANT_ID + "/\\d{4}/\\d{2}/" + CONTACT_ID + "\\.mp3");
        }
    }

    // =========================================================================
    // onCallHangup
    // =========================================================================

    @Nested
    @DisplayName("onCallHangup()")
    class OnCallHangup {

        @Test
        @DisplayName("powinien zignorować null event")
        void shouldIgnoreNullEvent() {
            assertThatNoException().isThrownBy(() -> recordingService.onCallHangup(null));
            verifyNoInteractions(s3Client, contactRepository);
        }

        @Test
        @DisplayName("powinien zignorować zdarzenie inne niż CALL_HANGUP")
        void shouldIgnoreNonHangupEvent() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_INCOMING)
                    .callId("call-1")
                    .tenantId(TENANT_ID)
                    .timestamp(Instant.now())
                    .build();

            recordingService.onCallHangup(event);

            verifyNoInteractions(s3Client, contactRepository);
        }

        @Test
        @DisplayName("powinien zignorować CALL_HANGUP bez tenantId")
        void shouldIgnoreHangupWithoutTenantId() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_HANGUP)
                    .callId("call-1")
                    .tenantId(null)
                    .timestamp(Instant.now())
                    .build();

            assertThatNoException().isThrownBy(() -> recordingService.onCallHangup(event));
            verifyNoInteractions(s3Client, contactRepository);
        }

        @Test
        @DisplayName("powinien zignorować CALL_HANGUP bez contactId w metadata")
        void shouldIgnoreHangupWithoutContactId() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_HANGUP)
                    .callId("call-1")
                    .tenantId(TENANT_ID)
                    .metadata(Map.of()) // brak contactId
                    .timestamp(Instant.now())
                    .build();

            assertThatNoException().isThrownBy(() -> recordingService.onCallHangup(event));
            verifyNoInteractions(s3Client, contactRepository);
        }

        @Test
        @DisplayName("powinien uploadować stub MP3 do S3 i zapisać recording_url gdy brak audioFilePath")
        void shouldUploadStubWhenNoAudioFilePath() {
            CallEvent event = CallEvent.builder()
                    .eventType(CallEvent.EventType.CALL_HANGUP)
                    .callId("call-1")
                    .tenantId(TENANT_ID)
                    .metadata(Map.of("contactId", CONTACT_ID.toString()))
                    .timestamp(Instant.parse("2026-03-19T10:00:00Z"))
                    .build();

            recordingService.onCallHangup(event);

            // Weryfikacja: S3 PutObject wywołane z poprawnym kluczem i szyfrowaniem
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));

            PutObjectRequest capturedPut = putCaptor.getValue();
            assertThat(capturedPut.bucket()).isEqualTo(BUCKET);
            assertThat(capturedPut.key()).isEqualTo(TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3");
            assertThat(capturedPut.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);

            // Weryfikacja: recording_url zaktualizowane w DB
            verify(contactRepository).updateRecordingUrl(
                    eq(CONTACT_ID),
                    eq(TENANT_ID),
                    eq(TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3")
            );
        }

        @Test
        @DisplayName("powinien uploadować rzeczywisty plik audio gdy audioFilePath jest podane i istnieje")
        void shouldUploadRealAudioFileWhenPathProvided() throws IOException {
            // Utwórz tymczasowy plik
            Path tempAudio = Files.createTempFile("test_audio_", ".mp3");
            Files.write(tempAudio, new byte[]{ 0x49, 0x44, 0x33 });

            try {
                CallEvent event = CallEvent.builder()
                        .eventType(CallEvent.EventType.CALL_HANGUP)
                        .callId("call-2")
                        .tenantId(TENANT_ID)
                        .metadata(Map.of(
                                "contactId", CONTACT_ID.toString(),
                                "audioFilePath", tempAudio.toString()
                        ))
                        .timestamp(Instant.parse("2026-03-19T10:00:00Z"))
                        .build();

                recordingService.onCallHangup(event);

                ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
                verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));

                assertThat(putCaptor.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
                verify(contactRepository).updateRecordingUrl(eq(CONTACT_ID), eq(TENANT_ID), any());

            } finally {
                Files.deleteIfExists(tempAudio);
            }
        }
    }

    // =========================================================================
    // uploadToS3
    // =========================================================================

    @Nested
    @DisplayName("uploadToS3()")
    class UploadToS3 {

        @Test
        @DisplayName("powinien wysłać PutObjectRequest z SSE-AES256 i content-type audio/mpeg")
        void shouldSendPutWithSseAes256() throws IOException {
            Path tempFile = Files.createTempFile("test_", ".mp3");
            Files.write(tempFile, new byte[]{ 0x49, 0x44, 0x33 });
            String s3Key = TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3";

            try {
                recordingService.uploadToS3(s3Key, tempFile);

                ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
                verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

                PutObjectRequest req = captor.getValue();
                assertThat(req.bucket()).isEqualTo(BUCKET);
                assertThat(req.key()).isEqualTo(s3Key);
                assertThat(req.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
                assertThat(req.contentType()).isEqualTo("audio/mpeg");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    // =========================================================================
    // deleteFromS3
    // =========================================================================

    @Nested
    @DisplayName("deleteFromS3()")
    class DeleteFromS3 {

        @Test
        @DisplayName("powinien wysłać DeleteObjectRequest z poprawnym kluczem")
        void shouldSendDeleteRequest() {
            String s3Key = TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3";

            recordingService.deleteFromS3(s3Key);

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());

            assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(captor.getValue().key()).isEqualTo(s3Key);
        }

        @Test
        @DisplayName("powinien zalogować błąd i NIE rzucać wyjątku gdy S3 zwróci błąd")
        void shouldNotThrowOnS3Error() {
            when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder()
                            .message("NoSuchKey")
                            .statusCode(404)
                            .build());

            assertThatNoException().isThrownBy(
                    () -> recordingService.deleteFromS3(TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3")
            );
        }
    }

    // =========================================================================
    // generatePresignedUrl
    // =========================================================================

    @Nested
    @DisplayName("generatePresignedUrl()")
    class GeneratePresignedUrl {

        @Test
        @DisplayName("powinien zwrócić empty gdy recording_url jest null")
        void shouldReturnEmptyWhenNoRecordingUrl() {
            when(contactRepository.findRecordingUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            Optional<String> result = recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID);

            assertThat(result).isEmpty();
            verifyNoInteractions(s3Presigner);
        }

        @Test
        @DisplayName("powinien wygenerować presigned URL gdy recording_url istnieje")
        void shouldGeneratePresignedUrlWhenRecordingExists() throws MalformedURLException {
            String s3Key = TENANT_ID + "/2026/03/" + CONTACT_ID + ".mp3";
            when(contactRepository.findRecordingUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(s3Key));

            // Mock presigned URL
            PresignedGetObjectRequest presignedRequest = mock(PresignedGetObjectRequest.class);
            when(presignedRequest.url()).thenReturn(URI.create("https://minio.test/" + s3Key + "?X-Amz-Expires=3600").toURL());
            when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                    .thenReturn(presignedRequest);

            Optional<String> result = recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get()).contains(s3Key);
        }
    }
}
