package com.contactcenter.domain.email;

import com.contactcenter.infrastructure.config.S3Properties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EmailEmlService - klucze EML: pierwsza wiadomosc vs wiadomosc follow-up (V145)")
class EmailEmlServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MESSAGE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant AT = Instant.parse("2026-10-17T10:30:00Z");

    private S3Client s3Client;
    private EmailEmlService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        S3Properties props = new S3Properties();
        props.setBucket("bucket");
        service = new EmailEmlService(s3Client, mock(S3Presigner.class), props,
                mock(EmailAttachmentStorageService.class), new ObjectMapper());
    }

    private static EmailMessage message() {
        return EmailMessage.builder().id(MESSAGE).tenantId(TENANT).contactId(CONTACT).direction("INBOUND")
                .fromAddress("klient@example.com").toAddress("biuro@example.com").subject("Temat")
                .bodyText("Tresc").messageAt(AT).receivedAt(AT).build();
    }

    @Test
    @DisplayName("generateAndUploadForMessage: klucz {tenant}/{yyyy}/{MM}/{contact}/{message}.eml, message/rfc822")
    void perMessageKeyIsDistinctFromContactKey() {
        String key = service.generateAndUploadForMessage(message(), CONTACT, TENANT);

        assertThat(key).isEqualTo(TENANT + "/2026/10/" + CONTACT + "/" + MESSAGE + ".eml");
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo(key);
        assertThat(put.getValue().contentType()).isEqualTo("message/rfc822");
        assertThat(key).isNotEqualTo(service.buildEmlS3Key(TENANT, CONTACT, AT));
        assertThat(EmailAttachmentKeys.isRecordingKeyOwnedByTenant(TENANT, key)).isTrue();
    }

    @Test
    @DisplayName("generateAndUpload (pierwsza wiadomosc): klucz {tenant}/{yyyy}/{MM}/{contact}.eml - bez zmian")
    void firstMessageKeyUnchanged() {
        assertThat(service.generateAndUpload(message(), CONTACT, TENANT))
                .isEqualTo(TENANT + "/2026/10/" + CONTACT + ".eml");
    }

    @Test
    @DisplayName("blad uploadu S3 -> EmailEmlException")
    void s3FailureThrows() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow((S3Exception) S3Exception.builder().message("down").statusCode(500).build());

        assertThatThrownBy(() -> service.generateAndUploadForMessage(message(), CONTACT, TENANT))
                .isInstanceOf(EmailEmlService.EmailEmlException.class);
    }
}
