package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.websocket.ContactMessageReceivedPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Order (notify before archive) and orphan cleanup of the follow-up EML archive in {@link EmailFollowUpJoiner}. */
@DisplayName("EmailFollowUpJoiner - archiwum EML: kolejnosc powiadomienia i sprzatanie osieroconego obiektu")
class EmailFollowUpJoinerArchiveTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONTACT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-01T10:00:00Z");
    private static final String KEY = TENANT + "/2026/10/" + CONTACT + "/m.eml";

    private EmailMessageRepository repository;
    private ContactMessageReceivedPublisher publisher;
    private EmailEmlService eml;
    private EmailFollowUpJoiner joiner;
    private EmailMessage saved;

    @BeforeEach
    void setUp() {
        repository = mock(EmailMessageRepository.class);
        publisher = mock(ContactMessageReceivedPublisher.class);
        eml = mock(EmailEmlService.class);
        ContactService contactService = mock(ContactService.class);
        joiner = new EmailFollowUpJoiner(repository, contactService, mock(CustomerService.class), publisher, eml);

        EmailMessage parent = EmailMessage.builder().id(UUID.randomUUID()).tenantId(TENANT).contactId(CONTACT).build();
        when(repository.findByMessageIdHeader("<p@x>", TENANT)).thenReturn(Optional.of(parent));
        Contact contact = Contact.builder().contactId(CONTACT).tenantId(TENANT).channel("EMAIL").status("ACTIVE")
                .agentId(AGENT).startedAt(AT).remoteAddress("klient@example.com").build();
        when(contactService.findContactEntity(CONTACT, TENANT)).thenReturn(Optional.of(contact));
        when(repository.attachToOpenAssignedContact(any(), any(), eq(CONTACT), eq(TENANT), eq(AGENT), eq(AT)))
                .thenReturn(true);
        saved = EmailMessage.builder().id(UUID.randomUUID()).tenantId(TENANT).messageAt(AT).receivedAt(AT)
                .fromAddress("klient@example.com").toAddress("biuro@example.com").inReplyTo("<p@x>").build();
    }

    @Test
    @DisplayName("powiadomienie agenta wychodzi PRZED archiwizacja EML; klucz zapisany, bez sprzatania")
    void publishesBeforeArchiving() {
        when(eml.generateAndUploadForMessage(any(), any(), any())).thenReturn(KEY);
        when(repository.updateEmlS3Key(any(), any(), any(), anyString())).thenReturn(true);

        assertThat(joiner.tryJoin(saved, TENANT, "biuro@example.com")).isTrue();

        InOrder order = inOrder(publisher, eml, repository);
        order.verify(publisher).publish(eq(TENANT), eq(CONTACT), eq(AGENT), eq("EMAIL"), eq(saved.getId()), eq(AT));
        order.verify(eml).generateAndUploadForMessage(saved, CONTACT, TENANT);
        order.verify(repository).updateEmlS3Key(saved.getId(), AT, TENANT, KEY);
        verify(eml, never()).deleteQuietly(any());
        assertThat(saved.getEmlS3Key()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("archiwizacja nie startuje przed publikacja: w momencie uploadu zdarzenie juz poszlo (nawet gdy upload rzuca)")
    void notificationAlreadySentWhenArchiveFails() {
        when(eml.generateAndUploadForMessage(any(), any(), any())).thenAnswer(inv -> {
            verify(publisher).publish(any(), any(), any(), any(), any(), any());
            throw new EmailEmlService.EmailEmlException("s3 down", new RuntimeException());
        });

        assertThat(joiner.tryJoin(saved, TENANT, "biuro@example.com")).isTrue();

        verify(eml, never()).deleteQuietly(any());
        verify(repository, never()).updateEmlS3Key(any(), any(), any(), any());
    }

    @Test
    @DisplayName("updateEmlS3Key zwraca false po udanym uploadzie -> usuniecie wgranego obiektu")
    void deletesUploadedObjectWhenKeyNotStored() {
        when(eml.generateAndUploadForMessage(any(), any(), any())).thenReturn(KEY);
        when(repository.updateEmlS3Key(any(), any(), any(), anyString())).thenReturn(false);

        assertThat(joiner.tryJoin(saved, TENANT, "biuro@example.com")).isTrue();

        verify(eml).deleteQuietly(KEY);
        assertThat(saved.getEmlS3Key()).isNull();
    }

    @Test
    @DisplayName("updateEmlS3Key rzuca po udanym uploadzie -> usuniecie wgranego obiektu, join zachowany")
    void deletesUploadedObjectWhenKeyUpdateThrows() {
        when(eml.generateAndUploadForMessage(any(), any(), any())).thenReturn(KEY);
        doThrow(new IllegalStateException("db")).when(repository).updateEmlS3Key(any(), any(), any(), anyString());

        assertThat(joiner.tryJoin(saved, TENANT, "biuro@example.com")).isTrue();

        verify(eml).deleteQuietly(KEY);
    }

    @Test
    @DisplayName("blad samego sprzatania nie psuje joina")
    void cleanupFailureIsSwallowed() {
        when(eml.generateAndUploadForMessage(any(), any(), any())).thenReturn(KEY);
        when(repository.updateEmlS3Key(any(), any(), any(), anyString())).thenReturn(false);
        doThrow(new RuntimeException("s3")).when(eml).deleteQuietly(KEY);

        assertThat(joiner.tryJoin(saved, TENANT, "biuro@example.com")).isTrue();
    }
}
