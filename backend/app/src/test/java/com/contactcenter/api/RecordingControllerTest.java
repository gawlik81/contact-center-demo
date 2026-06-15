package com.contactcenter.api;

import com.contactcenter.api.recording.RecordingController;
import com.contactcenter.api.recording.dto.RecordingUrlResponse;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link RecordingController}.
 *
 * <p>Weryfikuje logikę kontrolera bez uruchomionej infrastruktury Spring MVC.
 * TenantContext jest mockowany statycznie (symuluje filtr JWT).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingController – presigned URL nagrań")
class RecordingControllerTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PRESIGNED_URL =
            "https://minio.test/contact-center-recordings/tenant-id/recording.mp3?X-Amz-Expires=3600";

    @Mock private RecordingService recordingService;
    @Mock private S3Properties s3Properties;

    private RecordingController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new RecordingController(recordingService, s3Properties);
        // Mockuj TenantContext.getTenantId() → TENANT_ID
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    // =========================================================================
    // GET /api/recordings/{contactId}
    // =========================================================================

    @Nested
    @DisplayName("GET /api/recordings/{contactId}")
    class GetRecordingUrl {

        @Test
        @DisplayName("powinien zwrócić HTTP 200 z presigned URL gdy nagranie istnieje")
        void shouldReturn200WithPresignedUrl() {
            when(s3Properties.getPresignedUrlExpirationMinutes()).thenReturn(60L);
            when(recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(PRESIGNED_URL));

            ResponseEntity<RecordingUrlResponse> response = controller.getRecordingUrl(CONTACT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().contactId()).isEqualTo(CONTACT_ID);
            assertThat(response.getBody().presignedUrl()).isEqualTo(PRESIGNED_URL);
            assertThat(response.getBody().expiresAt()).isNotNull();
        }

        @Test
        @DisplayName("powinien zwrócić HTTP 404 gdy nagranie nie istnieje")
        void shouldReturn404WhenRecordingNotFound() {
            when(recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            ResponseEntity<RecordingUrlResponse> response = controller.getRecordingUrl(CONTACT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("powinien przekazać tenantId z TenantContext do serwisu")
        void shouldPassTenantIdFromContextToService() {
            when(s3Properties.getPresignedUrlExpirationMinutes()).thenReturn(60L);
            when(recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(PRESIGNED_URL));

            controller.getRecordingUrl(CONTACT_ID);

            verify(recordingService).generatePresignedUrl(CONTACT_ID, TENANT_ID);
        }

        @Test
        @DisplayName("powinien ustawić expiresAt w odpowiedzi na podstawie S3Properties")
        void shouldSetExpiresAtFromS3Properties() {
            when(s3Properties.getPresignedUrlExpirationMinutes()).thenReturn(30L);
            when(recordingService.generatePresignedUrl(CONTACT_ID, TENANT_ID))
                    .thenReturn(Optional.of(PRESIGNED_URL));

            ResponseEntity<RecordingUrlResponse> response = controller.getRecordingUrl(CONTACT_ID);

            assertThat(response.getBody()).isNotNull();
            // expiresAt powinien być w przyszłości (30 min = 1800s od teraz)
            assertThat(response.getBody().expiresAt())
                    .isAfter(java.time.Instant.now())
                    .isBefore(java.time.Instant.now().plusSeconds(31 * 60));
        }
    }
}
