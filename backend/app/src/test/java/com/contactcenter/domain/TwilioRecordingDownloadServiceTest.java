package com.contactcenter.domain;

import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.ContactTranscriptionRepository;
import com.contactcenter.domain.service.RecordingService;
import com.contactcenter.domain.service.TenantTwilioConfigService;
import com.contactcenter.domain.service.TwilioRecordingDownloadService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.infrastructure.config.TwilioProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla TwilioRecordingDownloadService.
 *
 * <p>Testy HTTP używają wbudowanego {@code com.sun.net.httpserver.HttpServer}
 * (dostępny w JDK bez dodatkowych zależności) jako minimalnego serwera HTTP
 * symulującego odpowiedzi Twilio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TwilioRecordingDownloadService")
class TwilioRecordingDownloadServiceTest {

    @Mock
    private TwilioProperties twilioProperties;
    @Mock
    private TenantTwilioConfigService tenantTwilioConfigService;
    @Mock
    private S3Properties s3Properties;
    @Mock
    private S3Client s3Client;
    @Mock
    private RecordingService recordingService;
    @Mock
    private ContactRepository contactRepository;
    @Mock
    private ContactTranscriptionRepository transcriptionRepository;

    private TwilioRecordingDownloadService service;

    // Minimalny serwer HTTP symulujący odpowiedzi Twilio
    private HttpServer httpServer;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        service = new TwilioRecordingDownloadService(
                twilioProperties, tenantTwilioConfigService, s3Properties, s3Client, recordingService,
                contactRepository, transcriptionRepository, java.util.Optional.empty());

        // Uruchom serwer HTTP na losowym porcie
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverPort = httpServer.getAddress().getPort();

        // Stub credentials Twilio (wymagane przez buildBasicAuthCredentials)
        when(twilioProperties.getAccountSid()).thenReturn("ACtest");
        when(twilioProperties.getAuthToken()).thenReturn("authtoken123");
        when(s3Properties.getBucket()).thenReturn("test-bucket");
        // Brak per-tenant config – fallback na globalne credentials
        when(tenantTwilioConfigService.getDecryptedConfig(any())).thenReturn(java.util.Optional.empty());
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    // =========================================================================
    // buildS3Key
    // =========================================================================

    @Nested
    @DisplayName("buildS3Key()")
    class BuildS3Key {

        @Test
        @DisplayName("powinien zwrócić klucz S3 w formacie {tenantId}/{year}/{month}/{contactId}.mp3")
        void shouldBuildDeterministicKey() {
            UUID tenantId  = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
            UUID contactId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

            // RecordingService.buildS3Key deleguje do serwisu – stubujemy wynik
            when(recordingService.buildS3Key(tenantId, contactId, null))
                    .thenReturn("aaaaaaaa-0000-0000-0000-000000000001/2026/01/bbbbbbbb-0000-0000-0000-000000000002.mp3");

            String key = service.buildS3Key(tenantId, contactId);

            assertThat(key).isEqualTo(
                    "aaaaaaaa-0000-0000-0000-000000000001/2026/01/bbbbbbbb-0000-0000-0000-000000000002.mp3"
            );
        }

        @Test
        @DisplayName("klucz zawiera suffix .mp3 zawsze")
        void keyAlwaysEndsWithMp3() {
            UUID tenantId  = UUID.randomUUID();
            UUID contactId = UUID.randomUUID();

            when(recordingService.buildS3Key(tenantId, contactId, null))
                    .thenReturn(tenantId + "/2026/01/" + contactId + ".mp3");

            String key = service.buildS3Key(tenantId, contactId);

            assertThat(key).endsWith(".mp3");
        }
    }

    // =========================================================================
    // downloadAndStoreSync – scenariusz sukcesu
    // =========================================================================

    @Nested
    @DisplayName("downloadAndStoreSync() – scenariusze sukcesu")
    class DownloadAndStoreSyncSuccess {

        @Test
        @DisplayName("powinien pobrać plik z serwera HTTP, uploadować do S3 i zapisać klucz w DB")
        void shouldDownloadUploadAndSaveKey() throws Exception {
            // Arrange: serwer zwraca 200 z przykładowymi bajtami audio
            byte[] fakeAudio = "FAKE_MP3_CONTENT".getBytes(StandardCharsets.UTF_8);
            httpServer.createContext("/recordings/RE123.mp3", exchange -> {
                exchange.sendResponseHeaders(200, fakeAudio.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(fakeAudio);
                }
            });
            httpServer.start();

            UUID tenantId    = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
            UUID contactId   = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
            String recordingSid = "RE123";
            String recordingUrl = "http://localhost:" + serverPort + "/recordings/RE123.mp3";
            String expectedKey  = "aaaaaaaa-0000-0000-0000-000000000001/2026/04/bbbbbbbb-0000-0000-0000-000000000002.mp3";

            when(recordingService.buildS3Key(tenantId, contactId, null)).thenReturn(expectedKey);
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            // Act
            service.downloadAndStoreSync(recordingUrl, recordingSid, contactId, tenantId);

            // Assert: S3 putObject wywołany z poprawnym kluczem i bucketem
            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));

            PutObjectRequest putRequest = putCaptor.getValue();
            assertThat(putRequest.bucket()).isEqualTo("test-bucket");
            assertThat(putRequest.key()).isEqualTo(expectedKey);
            assertThat(putRequest.contentType()).isEqualTo("audio/mpeg");

            // Assert: klucz S3 zapisany w DB przez RecordingService
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(recordingService).saveRecordingUrlToContact(
                    any(UUID.class), any(UUID.class), keyCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo(expectedKey);
        }
    }

    // =========================================================================
    // downloadAndStoreSync – scenariusze błędów
    // =========================================================================

    @Nested
    @DisplayName("downloadAndStoreSync() – scenariusze błędów")
    class DownloadAndStoreSyncErrors {

        @Test
        @DisplayName("powinien rzucić IllegalStateException gdy Twilio zwróci HTTP 401")
        void shouldThrowWhenTwilioReturns401() throws Exception {
            httpServer.createContext("/recordings/RE_UNAUTH.mp3", exchange ->
                    exchange.sendResponseHeaders(401, 0));
            httpServer.start();

            UUID tenantId  = UUID.randomUUID();
            UUID contactId = UUID.randomUUID();
            String recordingUrl = "http://localhost:" + serverPort + "/recordings/RE_UNAUTH.mp3";

            assertThatThrownBy(() ->
                    service.downloadAndStoreSync(recordingUrl, "RE_UNAUTH", contactId, tenantId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 401");

            // S3 ani DB nie powinny być wywołane
            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(recordingService, never())
                    .saveRecordingUrlToContact(any(), any(), any());
        }

        @Test
        @DisplayName("powinien rzucić IllegalStateException gdy Twilio zwróci HTTP 404")
        void shouldThrowWhenTwilioReturns404() throws Exception {
            httpServer.createContext("/recordings/RE_NOTFOUND.mp3", exchange ->
                    exchange.sendResponseHeaders(404, 0));
            httpServer.start();

            UUID tenantId  = UUID.randomUUID();
            UUID contactId = UUID.randomUUID();
            String recordingUrl = "http://localhost:" + serverPort + "/recordings/RE_NOTFOUND.mp3";

            assertThatThrownBy(() ->
                    service.downloadAndStoreSync(recordingUrl, "RE_NOTFOUND", contactId, tenantId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP 404");
        }

        @Test
        @DisplayName("powinien rzucić IllegalStateException gdy brakuje Twilio credentials")
        void shouldThrowWhenCredentialsMissing() {
            when(twilioProperties.getAccountSid()).thenReturn("");
            when(twilioProperties.getAuthToken()).thenReturn("token");

            UUID tenantId  = UUID.randomUUID();
            UUID contactId = UUID.randomUUID();

            assertThatThrownBy(() ->
                    service.downloadAndStoreSync(
                            "http://localhost/recording.mp3", "RE_SID", contactId, tenantId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TWILIO_ACCOUNT_SID");
        }
    }
}
