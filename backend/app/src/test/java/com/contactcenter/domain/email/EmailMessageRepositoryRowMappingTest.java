package com.contactcenter.domain.email;

import com.contactcenter.domain.email.EmailMessageRepository.AttachmentsRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Test jednostkowy mapowania wiersza natywnej projekcji
 * {@code SELECT message_id, message_at, contact_id, CAST(attachments AS text)} na {@link AttachmentsRow}
 * (BE125-03; BE-134 dodał {@code message_at} — część pełnego klucza). Ścieżka {@code contact_id = NULL} (wiadomość osierocona — BE-127) nie jest osiągalna
 * przez {@code findAttachmentsByContactIds} ({@code contact_id IN (...)}), więc bez tego testu
 * regresja wyszłaby dopiero przy pierwszej osieroconej wiadomości w produkcji.
 *
 * <p>To samo mapowanie na prawdziwym wierszu z bazy pokrywa
 * {@code EmailMessagePurgeIntegrationTest.OrphanRowMapping}.
 */
@DisplayName("EmailMessageRepository#toAttachmentsRow – mapowanie wiersza projekcji (BE125-03)")
class EmailMessageRepositoryRowMappingTest {

    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1");
    private static final Instant MESSAGE_AT = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    @DisplayName("contact_id = NULL (wiadomość osierocona) → AttachmentsRow z contactId = null, bez wyjątku")
    void nullContactId_mapsToNullWithoutThrowing() {
        Object[] row = {MESSAGE_ID, MESSAGE_AT, null, "[{\"s3_key\":\"k/a.pdf\"}]"};

        assertThatCode(() -> EmailMessageRepository.toAttachmentsRow(row)).doesNotThrowAnyException();
        AttachmentsRow mapped = EmailMessageRepository.toAttachmentsRow(row);

        assertThat(mapped.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(mapped.messageAt()).isEqualTo(MESSAGE_AT);
        assertThat(mapped.contactId()).isNull();
        assertThat(mapped.attachmentsJson()).isEqualTo("[{\"s3_key\":\"k/a.pdf\"}]");
    }

    @Test
    @DisplayName("contact_id zwrócony jako UUID (typowy sterownik PostgreSQL) → ten sam UUID")
    void uuidValues_areMappedAsIs() {
        AttachmentsRow mapped = EmailMessageRepository.toAttachmentsRow(
                new Object[]{MESSAGE_ID, MESSAGE_AT, CONTACT_ID, "[]"});

        assertThat(mapped).isEqualTo(new AttachmentsRow(MESSAGE_ID, MESSAGE_AT, CONTACT_ID, "[]"));
    }

    @Test
    @DisplayName("identyfikatory zwrócone jako tekst (np. inny sterownik/rzutowanie) → sparsowane do UUID")
    void stringValues_areParsedToUuid() {
        AttachmentsRow mapped = EmailMessageRepository.toAttachmentsRow(
                new Object[]{MESSAGE_ID.toString(), MESSAGE_AT, CONTACT_ID.toString(), "[]"});

        assertThat(mapped).isEqualTo(new AttachmentsRow(MESSAGE_ID, MESSAGE_AT, CONTACT_ID, "[]"));
    }

    @Test
    @DisplayName("attachments = NULL w bazie → attachmentsJson = null (parsowanie i tak jest odporne: extractS3Keys(null) = brak kluczy)")
    void nullAttachments_mapsToNullJson() {
        AttachmentsRow mapped = EmailMessageRepository.toAttachmentsRow(new Object[]{MESSAGE_ID, MESSAGE_AT, null, null});

        assertThat(mapped.contactId()).isNull();
        assertThat(mapped.attachmentsJson()).isNull();
        assertThat(EmailAttachmentKeys.extractS3Keys(mapped.messageId(), mapped.attachmentsJson())).isEmpty();
    }
}
