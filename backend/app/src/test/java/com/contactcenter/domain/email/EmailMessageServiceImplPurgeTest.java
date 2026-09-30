package com.contactcenter.domain.email;

import com.contactcenter.domain.email.EmailMessageRepository.AttachmentsRow;
import com.contactcenter.domain.exception.CrossTenantAccessException;
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

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe orkiestracji {@link EmailMessageServiceImpl#purgeByContactIds} (BE-125) —
 * kolejność „S3 przed wierszem", kontakty zablokowane, allow-lista, deduplikacja kluczy,
 * bezpiecznik po kolejnych porażkach S3. Repozytorium i storage są mockowane: ten test sprawdza
 * WYŁĄCZNIE logikę decyzyjną; natywny SQL, RLS i transakcje sprawdza
 * {@code EmailMessagePurgeIntegrationTest} na prawdziwej bazie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailMessageServiceImpl.purgeByContactIds – logika S3 przed wierszem (BE-125)")
class EmailMessageServiceImplPurgeTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTACT_1 = UUID.fromString("c1c1c1c1-c1c1-c1c1-c1c1-c1c1c1c1c1c1");
    private static final UUID CONTACT_2 = UUID.fromString("c2c2c2c2-c2c2-c2c2-c2c2-c2c2c2c2c2c2");
    private static final UUID CONTACT_3 = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3");
    private static final UUID MSG_1 = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final UUID MSG_2 = UUID.fromString("a2a2a2a2-a2a2-a2a2-a2a2-a2a2a2a2a2a2");
    private static final UUID MSG_3 = UUID.fromString("a3a3a3a3-a3a3-a3a3-a3a3-a3a3a3a3a3a3");
    private static final UUID MSG_4 = UUID.fromString("a4a4a4a4-a4a4-a4a4-a4a4-a4a4a4a4a4a4");

    private static final String PREFIX = "email-attachments/" + TENANT + "/";

    @Mock private EmailMessageRepository repository;
    @Mock private EmailAttachmentStorageService storage;

    private EmailMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmailMessageServiceImpl(repository, storage);
        // domyślnie DELETE potwierdza wszystkie zlecone wiersze
        when(repository.deleteByIds(eq(TENANT), anyCollection()))
                .thenAnswer(inv -> Set.copyOf((Collection<UUID>) inv.getArgument(1)));
    }

    private static String key(String name) {
        return PREFIX + "m/" + name;
    }

    private static AttachmentsRow row(UUID msg, UUID contact, String... keys) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"filename\":\"f\",\"s3_key\":\"").append(keys[i]).append("\"}");
        }
        return new AttachmentsRow(msg, contact, json.append("]").toString());
    }

    private void givenRows(AttachmentsRow... rows) {
        when(repository.findAttachmentsByContactIds(eq(TENANT), anyList())).thenReturn(List.of(rows));
    }

    @Nested
    @DisplayName("wejście")
    class Input {

        @Test
        @DisplayName("null i pusta lista → wynik pusty, zero interakcji z bazą i S3")
        void nullOrEmpty_isNoOp() {
            assertThat(service.purgeByContactIds(TENANT, null)).isEqualTo(PurgedMessages.empty());
            assertThat(service.purgeByContactIds(TENANT, List.of())).isEqualTo(PurgedMessages.empty());

            verifyNoInteractions(repository, storage);
        }

        @Test
        @DisplayName("brak wiadomości kontaktów → wynik pusty, S3 i DELETE nietknięte")
        void noMessages_isEmptyResult() {
            givenRows();

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            assertThat(result).isEqualTo(PurgedMessages.empty());
            verifyNoInteractions(storage);
            verify(repository, never()).deleteByIds(any(), any());
        }

        @Test
        @DisplayName("naruszenie izolacji tenanta w repozytorium propaguje wyjątek i NIE dotyka S3")
        void crossTenant_propagatesAndTouchesNoS3() {
            when(repository.findAttachmentsByContactIds(any(), anyList()))
                    .thenThrow(new CrossTenantAccessException(null, TENANT, UUID.randomUUID()));

            assertThatThrownBy(() -> service.purgeByContactIds(TENANT, List.of(CONTACT_1)))
                    .isInstanceOf(CrossTenantAccessException.class);

            verifyNoInteractions(storage);
        }
    }

    @Nested
    @DisplayName("ścieżka szczęśliwa")
    class HappyPath {

        @Test
        @DisplayName("2 załączniki → oba klucze usunięte w S3, wiadomość zlecona do DELETE, brak blokad")
        void twoAttachments_deletedThenRowDeleted() {
            givenRows(row(MSG_1, CONTACT_1, key("a.pdf"), key("b.png")));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            verify(storage).delete(key("a.pdf"));
            verify(storage).delete(key("b.png"));
            assertThat(result).isEqualTo(new PurgedMessages(1, 2, 0, 0, Set.of()));
        }

        @Test
        @DisplayName("kolejność: obiekty S3 są usuwane PRZED DELETE wiersza")
        void s3DeletesHappenBeforeRowDelete() {
            givenRows(row(MSG_1, CONTACT_1, key("a.pdf")));

            service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(storage, repository);
            order.verify(storage).delete(key("a.pdf"));
            order.verify(repository).deleteByIds(eq(TENANT), anyCollection());
        }

        @Test
        @DisplayName("wiadomości bez załączników (puste/brakujące/uszkodzone attachments) są usuwane bez S3")
        void messagesWithoutObjects_areDeletedWithoutS3() {
            givenRows(
                    new AttachmentsRow(MSG_1, CONTACT_1, "[]"),
                    new AttachmentsRow(MSG_2, CONTACT_1, null),
                    new AttachmentsRow(MSG_3, CONTACT_2, "{ to nie json"),
                    new AttachmentsRow(MSG_4, CONTACT_2, "[{\"filename\":\"x\"},{\"s3_key\":\"\"},5]"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            verifyNoInteractions(storage);
            assertThat(result).isEqualTo(new PurgedMessages(4, 0, 0, 0, Set.of()));
        }
    }

    @Nested
    @DisplayName("awaria S3 → wiersz zostaje, kontakt zablokowany")
    class S3Failure {

        @Test
        @DisplayName("awaria na 2. obiekcie: wiadomość NIE trafia do DELETE, s3Failures=1, kontakt zablokowany; 1. obiekt usunięty")
        void failureOnSecondObject_keepsRowAndBlocksContact() {
            givenRows(row(MSG_1, CONTACT_1, key("a.pdf"), key("b.png")));
            doThrow(new EmailAttachmentException("S3 down", null)).when(storage).delete(key("b.png"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            verify(storage).delete(key("a.pdf"));
            verify(repository, never()).deleteByIds(any(), any());
            assertThat(result).isEqualTo(new PurgedMessages(0, 1, 1, 0, Set.of(CONTACT_1)));
        }

        @Test
        @DisplayName("awaria jednej wiadomości nie blokuje pozostałych: reszta usunięta, zablokowany tylko jej kontakt")
        void failureIsolatedToItsMessage() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("bad.pdf")),
                    row(MSG_2, CONTACT_2, key("ok.pdf")));
            doThrow(new EmailAttachmentException("boom", null)).when(storage).delete(key("bad.pdf"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            ArgumentCaptor<Collection<UUID>> deleted = ArgumentCaptor.forClass(Collection.class);
            verify(repository).deleteByIds(eq(TENANT), deleted.capture());
            assertThat(deleted.getValue()).containsExactly(MSG_2);
            assertThat(result).isEqualTo(new PurgedMessages(1, 1, 1, 0, Set.of(CONTACT_1)));
        }

        @Test
        @DisplayName("kontakt z 2 wiadomościami, z których jedna zawiodła, jest zablokowany (usunięcie kontaktu zgubiłoby wskaźnik)")
        void contactWithMixedMessages_isBlocked() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("ok.pdf")),
                    row(MSG_2, CONTACT_1, key("bad.pdf")));
            doThrow(new EmailAttachmentException("boom", null)).when(storage).delete(key("bad.pdf"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            assertThat(result.deletedRows()).isEqualTo(1);
            assertThat(result.contactIdsBlocked()).containsExactly(CONTACT_1);
        }

        @Test
        @DisplayName("dowolny RuntimeException ze storage (nie tylko EmailAttachmentException) = porażka, wiersz zostaje")
        void anyRuntimeException_countsAsFailure() {
            givenRows(row(MSG_1, CONTACT_1, key("a.pdf")));
            doThrow(new IllegalStateException("nieoczekiwane")).when(storage).delete(key("a.pdf"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            verify(repository, never()).deleteByIds(any(), any());
            assertThat(result.s3Failures()).isEqualTo(1);
            assertThat(result.contactIdsBlocked()).containsExactly(CONTACT_1);
        }

        @Test
        @DisplayName("błąd DELETE w bazie po usunięciu obiektów propaguje wyjątek (obiekty już usunięte — kolejny purge jest idempotentny)")
        void deleteFailure_propagates() {
            givenRows(row(MSG_1, CONTACT_1, key("a.pdf")));
            when(repository.deleteByIds(eq(TENANT), anyCollection())).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> service.purgeByContactIds(TENANT, List.of(CONTACT_1)))
                    .isInstanceOf(IllegalStateException.class);
            verify(storage).delete(key("a.pdf"));
        }
    }

    @Nested
    @DisplayName("bezpiecznik po kolejnych porażkach S3")
    class FailFast {

        @Test
        @DisplayName("po 3 porażkach z rzędu kolejne obiekty NIE są próbowane; ich wiadomości zostają, kontakty zablokowane; wiadomość bez obiektów jest usuwana")
        void afterThresholdConsecutiveFailures_restIsNotAttempted() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("1")),
                    row(MSG_2, CONTACT_1, key("2")),
                    row(MSG_3, CONTACT_2, key("3")),
                    row(MSG_4, CONTACT_3, key("4")),
                    new AttachmentsRow(UUID.fromString("a5a5a5a5-a5a5-a5a5-a5a5-a5a5a5a5a5a5"), CONTACT_3, "[]"));
            doThrow(new EmailAttachmentException("down", null)).when(storage).delete(anyString());

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2, CONTACT_3));

            verify(storage, times(EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD)).delete(anyString());
            verify(storage, never()).delete(key("4"));
            assertThat(result.s3Failures()).isEqualTo(EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD);
            assertThat(result.s3ObjectsDeleted()).isZero();
            assertThat(result.contactIdsBlocked()).containsExactlyInAnyOrder(CONTACT_1, CONTACT_2, CONTACT_3);
            assertThat(result.deletedRows()).isEqualTo(1); // wiadomość bez obiektów S3
        }

        @Test
        @DisplayName("sukces zeruje licznik porażek z rzędu — porażki przedzielone sukcesem nie przerywają fazy")
        void successResetsConsecutiveFailureCounter() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("fail1")),
                    row(MSG_2, CONTACT_1, key("ok1")),
                    row(MSG_3, CONTACT_2, key("fail2")),
                    row(MSG_4, CONTACT_2, key("ok2")));
            doThrow(new EmailAttachmentException("x", null)).when(storage).delete(key("fail1"));
            doThrow(new EmailAttachmentException("x", null)).when(storage).delete(key("fail2"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            verify(storage, times(4)).delete(anyString());
            assertThat(result).isEqualTo(new PurgedMessages(2, 2, 2, 0, Set.of(CONTACT_1, CONTACT_2)));
        }
    }

    @Nested
    @DisplayName("ten sam klucz w wielu wiadomościach")
    class SharedKey {

        @Test
        @DisplayName("klucz wskazywany przez 2 wiadomości jest usuwany w S3 RAZ; obie wiadomości usunięte; s3ObjectsDeleted=1")
        void sharedKey_deletedOnce() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("shared.pdf")),
                    row(MSG_2, CONTACT_2, key("shared.pdf")));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            verify(storage, times(1)).delete(key("shared.pdf"));
            assertThat(result).isEqualTo(new PurgedMessages(2, 1, 0, 0, Set.of()));
        }

        @Test
        @DisplayName("nieudane usunięcie współdzielonego klucza blokuje OBIE wiadomości (jeden wynik dla klucza), s3Failures=1")
        void sharedKeyFailure_blocksAllReferencingMessages() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("shared.pdf")),
                    row(MSG_2, CONTACT_2, key("shared.pdf")));
            doThrow(new EmailAttachmentException("x", null)).when(storage).delete(key("shared.pdf"));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            verify(storage, times(1)).delete(key("shared.pdf"));
            verify(repository, never()).deleteByIds(any(), any());
            assertThat(result).isEqualTo(new PurgedMessages(0, 0, 1, 0, Set.of(CONTACT_1, CONTACT_2)));
        }
    }

    @Nested
    @DisplayName("allow-lista prefiksu")
    class Allowlist {

        @Test
        @DisplayName("klucz cudzego tenanta i nagranie: S3 nietknięte, s3Rejected liczone raz na klucz, wiersz USUNIĘTY mimo to")
        void foreignKeys_areSkippedButRowIsDeleted() {
            String foreignTenant = "email-attachments/" + UUID.randomUUID() + "/pending/x/f.pdf";
            String recording = TENANT + "/2026/09/" + CONTACT_1 + ".mp3";
            givenRows(
                    row(MSG_1, CONTACT_1, foreignTenant, recording, key("own.pdf")),
                    row(MSG_2, CONTACT_2, foreignTenant)); // ten sam obcy klucz w drugiej wiadomości

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            verify(storage).delete(key("own.pdf"));
            verify(storage, never()).delete(foreignTenant);
            verify(storage, never()).delete(recording);
            assertThat(result).isEqualTo(new PurgedMessages(2, 1, 0, 2, Set.of()));
        }

        @Test
        @DisplayName("próba wyjścia z prefiksu ('..') jest odrzucona, nie trafia do S3")
        void pathTraversal_isRejected() {
            String traversal = PREFIX + "../" + UUID.randomUUID() + "/x/a.pdf";
            givenRows(row(MSG_1, CONTACT_1, traversal));

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1));

            verifyNoInteractions(storage);
            assertThat(result).isEqualTo(new PurgedMessages(1, 0, 0, 1, Set.of()));
        }
    }

    @Nested
    @DisplayName("purgeRows – wiadomości osierocone (contact_id = null; wspólna logika dla BE-127)")
    class OrphanRows {

        @Test
        @DisplayName("null contactId nie powoduje NPE ani w blokadach (porażka S3), ani przy niepotwierdzonym DELETE")
        void nullContactId_isNeverAddedToBlockedContacts() {
            AttachmentsRow failing = new AttachmentsRow(MSG_1, null, "[{\"s3_key\":\"" + key("bad.pdf") + "\"}]");
            AttachmentsRow unconfirmed = new AttachmentsRow(MSG_2, null, "[]");
            AttachmentsRow ok = new AttachmentsRow(MSG_3, null, "[]");
            doThrow(new EmailAttachmentException("boom", null)).when(storage).delete(key("bad.pdf"));
            when(repository.deleteByIds(eq(TENANT), anyCollection())).thenReturn(Set.of(MSG_3));

            PurgedMessages result = service.purgeRows(TENANT, List.of(failing, unconfirmed, ok));

            assertThat(result).isEqualTo(new PurgedMessages(1, 0, 1, 0, Set.of()));
        }

        @Test
        @DisplayName("pusta lista wierszy → wynik pusty, bez interakcji")
        void emptyRows_isNoOp() {
            assertThat(service.purgeRows(TENANT, List.of())).isEqualTo(PurgedMessages.empty());

            verifyNoInteractions(storage, repository);
        }
    }

    @Nested
    @DisplayName("deprecjacja detachContactReferences")
    class Deprecation {

        @Test
        @DisplayName("detachContactReferences jest @Deprecated w repozytorium, interfejsie i implementacji (zastąpione przez purgeByContactIds, usunięcie w BE-126)")
        void detachContactReferences_isDeprecated() throws NoSuchMethodException {
            assertThat(EmailMessageRepository.class.getMethod("detachContactReferences", UUID.class, List.class)
                    .isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(EmailMessageService.class.getMethod("detachContactReferences", UUID.class, List.class)
                    .isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(EmailMessageServiceImpl.class.getMethod("detachContactReferences", UUID.class, List.class)
                    .isAnnotationPresent(Deprecated.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("potwierdzenie DELETE")
    class DeleteConfirmation {

        @Test
        @DisplayName("wiersz zlecony do usunięcia, a niezwrócony przez DELETE…RETURNING (RLS bez polityki DELETE / wyścig) → kontakt zablokowany")
        void unconfirmedRow_blocksItsContact() {
            givenRows(
                    row(MSG_1, CONTACT_1, key("a.pdf")),
                    row(MSG_2, CONTACT_2, key("b.pdf")));
            when(repository.deleteByIds(eq(TENANT), anyCollection())).thenReturn(Set.of(MSG_2)); // MSG_1 „nie zniknęło"

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            assertThat(result).isEqualTo(new PurgedMessages(1, 2, 0, 0, Set.of(CONTACT_1)));
        }

        @Test
        @DisplayName("DELETE nie usunął nic (0 wierszy) → deletedRows=0, wszystkie kontakty zablokowane")
        void deleteRemovedNothing_blocksAllContacts() {
            givenRows(row(MSG_1, CONTACT_1), row(MSG_2, CONTACT_2));
            when(repository.deleteByIds(eq(TENANT), anyCollection())).thenReturn(Set.of());

            PurgedMessages result = service.purgeByContactIds(TENANT, List.of(CONTACT_1, CONTACT_2));

            assertThat(result.deletedRows()).isZero();
            assertThat(result.contactIdsBlocked()).containsExactlyInAnyOrder(CONTACT_1, CONTACT_2);
        }
    }
}
