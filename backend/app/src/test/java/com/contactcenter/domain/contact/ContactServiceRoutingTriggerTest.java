package com.contactcenter.domain.contact;

import com.contactcenter.domain.campaign.CampaignService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.domain.routing.ReleaseExclusionStore;
import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TelephonyEventPublisher;
import com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-156 review: the "slot freed" routing trigger outside a Spring transaction (no active synchronization ->
 * publish immediately), a failing publisher (swallowed) and the channel/agent guards.
 */
@DisplayName("ContactServiceImpl - wyzwalacz przebiegu routingu po zwolnieniu slotu (BE-156)")
class ContactServiceRoutingTriggerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private ContactRepository repository;
    private ApplicationEventPublisher publisher;
    private ContactServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ContactRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ContactServiceImpl(repository, null, null, mock(RecordingService.class),
                mock(EmailMessageService.class), mock(QueueService.class), mock(CustomerService.class),
                mock(CampaignService.class), mock(CustomDispositionService.class), mock(ContactEventService.class),
                mock(TelephonyAdapter.class), mock(TelephonyEventPublisher.class), new ObjectMapper(),
                mock(RabbitTemplate.class), mock(ReleaseExclusionStore.class));
        service.setRoutingTriggerPublisher(publisher);
    }

    private Contact contact(String channel, UUID agent) {
        return Contact.builder().contactId(UUID.randomUUID()).tenantId(TENANT).agentId(agent).channel(channel)
                .status("ACTIVE").startedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("bez aktywnej synchronizacji transakcji zdarzenie jest publikowane od razu")
    void publishesImmediatelyWithoutTransactionSynchronization() {
        Contact c = contact("EMAIL", AGENT);
        when(repository.completeIfOpen(any(), any(), any(), any())).thenReturn(1);

        assertThat(service.completeContactIfOpen(c, Instant.now())).isTrue();

        verify(publisher).publishEvent(new AgentAvailabilityRefreshedEvent(AGENT, TENANT));
    }

    @Test
    @DisplayName("awaria publishEvent jest polknieta - zamkniecie kontaktu sie udaje")
    void publisherFailureIsSwallowed() {
        Contact c = contact("EMAIL", AGENT);
        when(repository.completeIfOpen(any(), any(), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("boom")).when(publisher).publishEvent(any(Object.class));

        assertThat(service.completeContactIfOpen(c, Instant.now())).isTrue();
    }

    @Test
    @DisplayName("telefon, kontakt bez agenta albo 0 wierszy -> brak wyzwalacza")
    void noTriggerForPhoneUnassignedOrLostWrite() {
        when(repository.completeIfOpen(any(), any(), any(), any())).thenReturn(1);
        service.completeContactIfOpen(contact("PHONE", AGENT), Instant.now());
        service.completeContactIfOpen(contact("EMAIL", null), Instant.now());
        when(repository.completeIfOpen(any(), any(), any(), any())).thenReturn(0);
        service.completeContactIfOpen(contact("EMAIL", AGENT), Instant.now());

        verify(publisher, never()).publishEvent(any(Object.class));
    }
}
