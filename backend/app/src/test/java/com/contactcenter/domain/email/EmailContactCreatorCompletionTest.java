package com.contactcenter.domain.email;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-159: closing an e-mail contact after a sent reply is a conditional, targeted write. */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailContactCreator - warunkowe zamkniecie kontaktu po email.sent (BE-159)")
class EmailContactCreatorCompletionTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID = UUID.randomUUID();

    @Mock private ContactService contactService;
    @Mock private CustomerService customerService;
    @Mock private EmailMessageRepository emailMessageRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private EmailEmlService emailEmlService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EmailEventPublisher.EmailEvent sentEvent() {
        return new EmailEventPublisher.EmailEvent(EmailEventPublisher.EventType.SENT, null, TENANT_ID,
                CONTACT_ID, UUID.randomUUID(), null, "a@b.pl", "s", "OUTBOUND", Instant.now(), null, null);
    }

    @Test
    @DisplayName("kontakt otwarty -> completeContactIfOpen wywolane; 0 wierszy nie rzuca wyjatku")
    void completesOpenContactAndToleratesLostRace() {
        EmailContactCreator creator = new EmailContactCreator(contactService, customerService,
                emailMessageRepository, rabbitTemplate, emailEmlService);
        Contact contact = Contact.builder().contactId(CONTACT_ID).tenantId(TENANT_ID).status("ACTIVE")
                .startedAt(Instant.now()).build();
        when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
        when(contactService.completeContactIfOpen(any(Contact.class), any(Instant.class))).thenReturn(false);

        creator.onEmailEvent(sentEvent());

        verify(contactService).completeContactIfOpen(any(Contact.class), any(Instant.class));
        verify(contactService, never()).updateContactEntity(any());
    }

    @Test
    @DisplayName("kontakt juz COMPLETED -> brak zapisu")
    void terminalContactIsNotWritten() {
        EmailContactCreator creator = new EmailContactCreator(contactService, customerService,
                emailMessageRepository, rabbitTemplate, emailEmlService);
        Contact contact = Contact.builder().contactId(CONTACT_ID).tenantId(TENANT_ID).status("COMPLETED")
                .startedAt(Instant.now()).build();
        when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

        creator.onEmailEvent(sentEvent());

        verify(contactService, never()).completeContactIfOpen(any(), any());
    }
}
