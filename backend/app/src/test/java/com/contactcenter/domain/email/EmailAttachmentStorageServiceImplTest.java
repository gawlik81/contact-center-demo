package com.contactcenter.domain.email;

import com.contactcenter.infrastructure.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link EmailAttachmentStorageServiceImpl}: nowa metoda {@code delete}
 * (BE-125) oraz regresja schematu kluczy {@code store}/{@code storePending} po przeniesieniu
 * budowania kluczy do {@link EmailAttachmentKeys}.
 *
 * <p>Semantyka {@code DeleteObject} na nieistniejącym kluczu (204) jest sprawdzona na prawdziwym
 * MinIO w {@code EmailAttachmentStorageServiceMinioTest} — mock {@code S3Client} tego nie dowiedzie.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailAttachmentStorageServiceImpl – delete i klucze (BE-125)")
class EmailAttachmentStorageServiceImplTest {

    private static final String BUCKET = "contact-center-recordings";
    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PENDING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private S3Client s3Client;
    @Mock private S3Presigner s3Presigner;

    private EmailAttachmentStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        S3Properties props = new S3Properties();
        props.setBucket(BUCKET);
        service = new EmailAttachmentStorageServiceImpl(s3Client, s3Presigner, props);
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("woła DeleteObject z bucketem z konfiguracji i podanym kluczem")
        void delete_callsS3WithBucketAndKey() {
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

            service.delete("email-attachments/x/y/a.pdf");

            ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(captor.getValue().key()).isEqualTo("email-attachments/x/y/a.pdf");
        }

        @Test
        @DisplayName("błąd usługi S3 (S3Exception) → EmailAttachmentException z przyczyną (nie jest połykany)")
        void delete_s3Exception_isWrappedAndRethrown() {
            S3Exception cause = (S3Exception) S3Exception.builder().message("AccessDenied").statusCode(403).build();
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(cause);

            assertThatThrownBy(() -> service.delete("email-attachments/x/y/a.pdf"))
                    .isInstanceOf(EmailAttachmentException.class)
                    .hasCause(cause);
        }

        @Test
        @DisplayName("błąd klienta (SdkClientException: sieć/timeout — NIE podklasa S3Exception) → EmailAttachmentException")
        void delete_sdkClientException_isWrappedAndRethrown() {
            SdkClientException cause = SdkClientException.create("Unable to execute HTTP request: Connection refused");
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(cause);

            assertThatThrownBy(() -> service.delete("email-attachments/x/y/a.pdf"))
                    .isInstanceOf(EmailAttachmentException.class)
                    .hasCause(cause);
        }

        @Test
        @DisplayName("null i pusty klucz → IllegalArgumentException, S3 nietknięte")
        void delete_blankKey_isRejectedWithoutCallingS3() {
            assertThatThrownBy(() -> service.delete(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.delete("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.delete("   ")).isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("błąd programistyczny (RuntimeException spoza SDK) nie jest maskowany jako awaria S3")
        void delete_nonSdkException_propagatesUnchanged() {
            IllegalStateException cause = new IllegalStateException("bug");
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(cause);

            assertThatThrownBy(() -> service.delete("email-attachments/x/y/a.pdf")).isSameAs(cause);
        }
    }

    @Nested
    @DisplayName("klucze zapisu (regresja po refaktorze na EmailAttachmentKeys)")
    class KeyLayout {

        @Test
        @DisplayName("store() zapisuje pod email-attachments/{tenantId}/{messageId}/{zakodowana nazwa}")
        void store_usesInboundLayout() {
            String key = service.store(TENANT_ID, MESSAGE_ID, "Umowa końcowa.pdf", "application/pdf", new byte[]{1});

            assertThat(key).isEqualTo("email-attachments/" + TENANT_ID + "/" + MESSAGE_ID + "/Umowa_ko%C5%84cowa.pdf");
            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
            assertThat(captor.getValue().key()).isEqualTo(key);
        }

        @Test
        @DisplayName("storePending() zapisuje pod email-attachments/{tenantId}/pending/{uuid}/{nazwa}")
        void storePending_usesPendingLayout() {
            String key = service.storePending(TENANT_ID, PENDING_ID, "a.png", "image/png", new byte[]{1});

            assertThat(key).isEqualTo("email-attachments/" + TENANT_ID + "/pending/" + PENDING_ID + "/a.png");
        }

        @Test
        @DisplayName("pusta nazwa pliku → \"attachment\"; klucz przechodzi allow-listę własnego tenanta")
        void blankFilename_fallsBackAndPassesAllowlist() {
            String key = service.store(TENANT_ID, MESSAGE_ID, " ", null, new byte[]{1});

            assertThat(key).endsWith("/attachment");
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_ID, key)).isTrue();
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }

        // BE125-11: URLEncoder nie koduje kropek — nazwa "." / ".." dawała segment klucza odrzucany przez allow-listę purge
        @ParameterizedTest(name = "nazwa pliku \"{0}\" → \"attachment\"")
        @ValueSource(strings = {".", ".."})
        @DisplayName("nazwa pliku będąca segmentem '.'/'..' jest zastępowana \"attachment\" (store i storePending) i klucz przechodzi allow-listę")
        void dotSegmentFilename_fallsBackToAttachmentAndPassesAllowlist(String filename) {
            String inbound = service.store(TENANT_ID, MESSAGE_ID, filename, "application/pdf", new byte[]{1});
            String pending = service.storePending(TENANT_ID, PENDING_ID, filename, "application/pdf", new byte[]{1});

            assertThat(inbound).isEqualTo("email-attachments/" + TENANT_ID + "/" + MESSAGE_ID + "/attachment");
            assertThat(pending).isEqualTo("email-attachments/" + TENANT_ID + "/pending/" + PENDING_ID + "/attachment");
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_ID, inbound)).isTrue();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_ID, pending)).isTrue();
        }

        @Test
        @DisplayName("obiekt trafia do S3 pod bezpiecznym kluczem (nie pod '.../..'), a nie tylko zwracany klucz jest poprawny")
        void dotSegmentFilename_isUploadedUnderSafeKey() {
            service.store(TENANT_ID, MESSAGE_ID, "..", "application/pdf", new byte[]{1});

            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
            assertThat(captor.getValue().key()).endsWith("/" + MESSAGE_ID + "/attachment");
        }

        @ParameterizedTest(name = "nazwa pliku \"{0}\" → klucz przechodzi allow-listę")
        @ValueSource(strings = {
                "...", "....", ".hidden", "a..b", "..a", "a..", " .. ", "../../etc/passwd", "..\\..\\x",
                "a/b/c.pdf", "%2e%2e", "x\ny.pdf", "‮gpj.exe", "zażółć gęślą jaźń.pdf"
        })
        @DisplayName("inwariant: każdy klucz zapisany przez store/storePending przechodzi allow-listę własnego tenanta (także nazwy wrogie)")
        void everyWrittenKey_passesOwnAllowlist(String filename) {
            String inbound = service.store(TENANT_ID, MESSAGE_ID, filename, null, new byte[]{1});
            String pending = service.storePending(TENANT_ID, PENDING_ID, filename, null, new byte[]{1});

            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_ID, inbound)).as("inbound: %s", inbound).isTrue();
            assertThat(EmailAttachmentKeys.isOwnedByTenant(TENANT_ID, pending)).as("pending: %s", pending).isTrue();
        }

        @Test
        @DisplayName("nazwa zawierająca kropki, ale nie będąca samym '.'/'..' (np. \"archive..tar.gz\"), zostaje bez zmian")
        void filenameWithDotsInside_isNotReplaced() {
            String key = service.store(TENANT_ID, MESSAGE_ID, "archive..tar.gz", null, new byte[]{1});

            assertThat(key).endsWith("/" + MESSAGE_ID + "/archive..tar.gz");
        }
    }
}
