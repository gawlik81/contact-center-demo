package com.contactcenter.api.email;

import com.contactcenter.domain.email.EmailAttachmentStorageService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link EmailAttachmentController#downloadAttachment(String)} (BE-143).
 *
 * <p>Wzorzec testów kontrolera w tym projekcie: wywołanie metody kontrolera bezpośrednio, bez
 * {@code MockMvc} ani łańcucha filtrów Spring Security (zob. {@code RecordingControllerTest},
 * {@code RetentionControllerTest}) — {@code TenantContext} ustawiany ręcznie jak w {@code
 * CustomerControllerTest} (rzeczywisty {@link ThreadLocal}, nie {@code mockStatic}), bo test
 * "pusty kontekst" (WP-2) musi zaobserwować rzeczywiste zachowanie {@link TenantContext#getTenantId()}
 * ({@link IllegalStateException}), nie zachowanie mocka.
 *
 * <p>Ochrona IDOR (dawniej goły {@code startsWith}) jest od BE-143 realizowana przez
 * {@code EmailAttachmentKeys#isOwnedByTenant} — te same reguły co walidacja wysyłki w
 * {@code EmailSendServiceImpl} i purge w {@code EmailMessageServiceImpl}. Sama allow-lista
 * (obcy tenant, {@code .}/{@code ..}, znaki sterujące, sam prefiks, {@code null}/pusty klucz)
 * jest już wyczerpująco pokryta przez {@code EmailAttachmentKeysTest} (BE-125) — tu weryfikujemy
 * tylko, że kontroler faktycznie z niej korzysta i mapuje wynik na 403/200.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailAttachmentController – pobieranie załącznika (BE-143)")
class EmailAttachmentControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String PRESIGNED_URL = "https://minio.test/contact-center-recordings/plik.pdf?X-Amz-Expires=3600";

    @Mock
    private EmailAttachmentStorageService attachmentStorageService;

    private EmailAttachmentController controller;

    @BeforeEach
    void setUp() {
        controller = new EmailAttachmentController(attachmentStorageService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // GET /api/email/attachments/download
    // =========================================================================

    @Nested
    @DisplayName("downloadAttachment")
    class DownloadAttachment {

        @Test
        @DisplayName("własny klucz (INBOUND) → 200 z presigned URL")
        void ownInboundKey_returns200WithPresignedUrl() {
            TenantContext.setTenantId(TENANT_ID);
            String ownKey = "email-attachments/" + TENANT_ID + "/" + UUID.randomUUID() + "/plik.pdf";
            when(attachmentStorageService.presignedDownloadUrl(ownKey)).thenReturn(PRESIGNED_URL);

            ResponseEntity<Map<String, String>> response = controller.downloadAttachment(ownKey);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("url", PRESIGNED_URL);
        }

        @Test
        @DisplayName("własny klucz pending/ (OUTBOUND) → 200 z presigned URL")
        void ownPendingKey_returns200WithPresignedUrl() {
            TenantContext.setTenantId(TENANT_ID);
            String ownKey = "email-attachments/" + TENANT_ID + "/pending/" + UUID.randomUUID() + "/plik.pdf";
            when(attachmentStorageService.presignedDownloadUrl(ownKey)).thenReturn(PRESIGNED_URL);

            ResponseEntity<Map<String, String>> response = controller.downloadAttachment(ownKey);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("url", PRESIGNED_URL);
        }

        @Test
        @DisplayName("klucz obcego tenanta → 403, presigned URL NIE generowany")
        void foreignTenantKey_returns403WithoutGeneratingUrl() {
            TenantContext.setTenantId(TENANT_ID);
            String foreignKey = "email-attachments/" + OTHER_TENANT_ID + "/x/plik.pdf";

            ResponseEntity<Map<String, String>> response = controller.downloadAttachment(foreignKey);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(attachmentStorageService, never()).presignedDownloadUrl(anyString());
        }

        @ParameterizedTest(name = "próba wyjścia z prefiksu: {0}")
        @ValueSource(strings = {
                "email-attachments/11111111-1111-1111-1111-111111111111/../99999999-9999-9999-9999-999999999999/x",
                "email-attachments/11111111-1111-1111-1111-111111111111/x/../../y"
        })
        @DisplayName("klucz z segmentem .. → 403, presigned URL NIE generowany")
        void pathTraversalKey_returns403WithoutGeneratingUrl(String key) {
            TenantContext.setTenantId(TENANT_ID);

            ResponseEntity<Map<String, String>> response = controller.downloadAttachment(key);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(attachmentStorageService, never()).presignedDownloadUrl(anyString());
        }

        @Test
        @DisplayName("klucz nagrania {tenantId}/....mp3 (inny korzeń) → 403")
        void recordingKey_returns403() {
            TenantContext.setTenantId(TENANT_ID);
            String recordingKey = TENANT_ID + "/2026/09/" + UUID.randomUUID() + ".mp3";

            ResponseEntity<Map<String, String>> response = controller.downloadAttachment(recordingKey);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(attachmentStorageService, never()).presignedDownloadUrl(anyString());
        }

        @Test
        @DisplayName("(WP-2) pusty TenantContext (brak tenant_id ustawionego) — rzuca zamiast fałszywie autoryzować")
        void emptyTenantContext_throwsInsteadOfFailingOpen() {
            // TenantContext NIE jest ustawiony w tym teście (brak setTenantId w @BeforeEach/tym teście) –
            // symuluje sytuację, w której TenantFilter z jakiegoś powodu nie ustawił kontekstu.
            // getTenantId() musi rzucić, NIE zwrócić null i przepuścić dowolny klucz.
            String anyKey = "email-attachments/" + TENANT_ID + "/x/plik.pdf";

            assertThatThrownBy(() -> controller.downloadAttachment(anyKey))
                    .isInstanceOf(IllegalStateException.class);

            verify(attachmentStorageService, never()).presignedDownloadUrl(anyString());
        }
    }
}
