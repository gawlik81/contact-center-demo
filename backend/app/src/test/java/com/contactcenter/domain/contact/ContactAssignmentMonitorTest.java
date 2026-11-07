package com.contactcenter.domain.contact;

import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-159: the "max retries exceeded" un-assignment is conditional on the contact still being ASSIGNED to the same agent. */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContactAssignmentMonitor - warunkowe cofniecie przydzialu (BE-159)")
class ContactAssignmentMonitorTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private ContactRepository contactRepository;
    @Mock private CustomerService customerService;
    @Mock private QueueService queueService;
    @Mock private WebSocketEventBroadcaster broadcaster;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private ContactAssignmentMonitor monitor;
    private Contact contact;

    @BeforeEach
    void setUp() {
        monitor = new ContactAssignmentMonitor(contactRepository, customerService, queueService,
                broadcaster, rabbitTemplate, redisTemplate);
        Instant now = Instant.now();
        contact = Contact.builder().contactId(CONTACT_ID).tenantId(TENANT_ID).queueId(QUEUE_ID)
                .agentId(AGENT_ID).channel("PHONE").direction("INBOUND").status("ASSIGNED")
                .startedAt(now.minusSeconds(60)).assignedAt(now.minusSeconds(40)).build();
        when(contactRepository.findStaleAssignedContacts(any())).thenReturn(List.of(contact));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3"); // MAX_RETRIES reached
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("1 wiersz -> reset do QUEUED, klucz retry usuniety, contact.queued opublikowany")
    void resetsAndRequeuesWhenStillAssignedToSameAgent() {
        when(contactRepository.resetAssignmentIfAssigned(CONTACT_ID, TENANT_ID, contact.getStartedAt(), AGENT_ID))
                .thenReturn(1);

        monitor.checkStaleAssignments();

        verify(redisTemplate).delete("cc:assign-retry:" + CONTACT_ID);
        verify(rabbitTemplate).convertAndSend(anyString(), org.mockito.ArgumentMatchers.eq("contact.queued"),
                any(Object.class));
        assertThat(contact.getStatus()).isEqualTo("QUEUED");
        assertThat(contact.getAgentId()).isNull();
    }

    @Test
    @DisplayName("0 wierszy (kontakt zaakceptowany / przejety) -> brak re-kolejkowania, brak ruszania Redis")
    void zeroRowsHasNoSideEffects() {
        when(contactRepository.resetAssignmentIfAssigned(CONTACT_ID, TENANT_ID, contact.getStartedAt(), AGENT_ID))
                .thenReturn(0);

        monitor.checkStaleAssignments();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        verify(redisTemplate, never()).delete(anyString());
        assertThat(contact.getStatus()).isEqualTo("ASSIGNED");
        assertThat(contact.getAgentId()).isEqualTo(AGENT_ID);
    }
}
