package com.contactcenter.domain;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.customer.CustomerRepository;
import com.contactcenter.domain.repository.QueueAssignmentRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.routing.ContactAssignedEvent;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.routing.RoutingEngine;
import com.contactcenter.domain.routing.RoutingRequest;
import com.contactcenter.domain.routing.RoutingResult;
import com.contactcenter.domain.service.ContactEventService;
import com.contactcenter.domain.service.ContactService;
import com.contactcenter.domain.service.RoutingService;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link RoutingService}.
 *
 * <p>Weryfikuje orkiestrację routingu: budowanie RoutingRequest,
 * aktualizację kontaktu i publikację eventów RabbitMQ.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoutingService – orkiestracja routingu kontaktów")
class RoutingServiceTest {

    private static final UUID TENANT_ID  = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONTACT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID AGENT_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private RoutingEngine routingEngine;

    @Mock
    private QueueRepository queueRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private QueueAssignmentRepository queueAssignmentRepository;

    @Mock
    private UserService userService;

    @Mock
    private WebSocketEventBroadcaster broadcaster;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private ContactService contactService;

    @Mock
    private ContactEventService contactEventService;

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(routingEngine, queueRepository, contactRepository,
                customerRepository, rabbitTemplate, queueAssignmentRepository, userService,
                broadcaster, contactService, contactEventService);
        // Domyślnie: all_agents=TRUE (brak filtru) – zachowanie sprzed BE-047
        lenient().when(queueAssignmentRepository.isAllAgents(any(UUID.class), any(UUID.class)))
                .thenReturn(true);
        // Broadcast po routingu – domyślnie pusta lista żeby nie rzucać NPE w testach
        lenient().when(contactRepository.findQueuedContactsForAgentView(any(UUID.class)))
                .thenReturn(List.of());
    }

    // =========================================================================
    // routeContact – agent znaleziony
    // =========================================================================

    @Nested
    @DisplayName("routeContact – gdy agent zostanie znaleziony")
    class WhenAgentFound {

        @Test
        @DisplayName("powinien przypisać agenta i opublikować contact.assigned")
        void shouldAssignAgentAndPublishAssignedEvent() {
            Queue queue = buildQueue("SKILL_BASED", List.of("SALES"));
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any(RoutingRequest.class)))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "SKILL_BASED")));
            when(contactRepository.update(any(Contact.class))).thenReturn(1);

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isPresent().contains(AGENT_ID);

            // Weryfikuj aktualizację kontaktu
            ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            verify(contactRepository).update(contactCaptor.capture());
            Contact updated = contactCaptor.getValue();
            assertThat(updated.getAgentId()).isEqualTo(AGENT_ID);
            assertThat(updated.getStatus()).isEqualTo("ASSIGNED");
            assertThat(updated.getAssignedAt()).isNotNull();

            // Weryfikuj event contact.assigned
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.assigned"),
                    any(ContactAssignedEvent.class)
            );
        }

        @Test
        @DisplayName("powinien zbudować RoutingRequest z konfiguracji kolejki")
        void shouldBuildRoutingRequestFromQueueConfig() {
            Queue queue = buildQueue("ROUND_ROBIN", List.of("BILLING", "POLISH"));
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "ROUND_ROBIN")));
            when(contactRepository.update(any())).thenReturn(1);

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            ArgumentCaptor<RoutingRequest> requestCaptor = ArgumentCaptor.forClass(RoutingRequest.class);
            verify(routingEngine).findBestAgent(requestCaptor.capture());

            RoutingRequest capturedRequest = requestCaptor.getValue();
            assertThat(capturedRequest.tenantId()).isEqualTo(TENANT_ID);
            assertThat(capturedRequest.queueId()).isEqualTo(QUEUE_ID);
            assertThat(capturedRequest.routingStrategy()).isEqualTo("ROUND_ROBIN");
            assertThat(capturedRequest.requiredSkills()).containsExactlyInAnyOrder("BILLING", "POLISH");
        }

        @Test
        @DisplayName("powinien opublikować ContactAssignedEvent z poprawnymi polami")
        void shouldPublishContactAssignedEventWithCorrectFields() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactRepository.update(any())).thenReturn(1);

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            ArgumentCaptor<ContactAssignedEvent> eventCaptor = ArgumentCaptor.forClass(ContactAssignedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS),
                    eq("contact.assigned"),
                    eventCaptor.capture()
            );

            ContactAssignedEvent event = eventCaptor.getValue();
            assertThat(event.contactId()).isEqualTo(CONTACT_ID);
            assertThat(event.agentId()).isEqualTo(AGENT_ID);
            assertThat(event.queueId()).isEqualTo(QUEUE_ID);
            assertThat(event.tenantId()).isEqualTo(TENANT_ID);
            assertThat(event.strategy()).isEqualTo("FIRST_AVAILABLE");
            assertThat(event.assignedAt()).isNotNull();
        }
    }

    // =========================================================================
    // routeContact – brak agentów
    // =========================================================================

    @Nested
    @DisplayName("routeContact – gdy brak dostępnych agentów")
    class WhenNoAgentFound {

        @Test
        @DisplayName("powinien zwrócić empty i NIE publikować contact.queued (brak pętli retry)")
        void shouldReturnEmptyAndNotPublishQueuedEvent() {
            Queue queue = buildQueue("SKILL_BASED", List.of("SALES"));
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isEmpty();

            // Kontakt NIE powinien być aktualizowany
            verify(contactRepository, never()).update(any());

            // Event contact.queued NIE powinien być publikowany – eliminuje nieskończoną pętlę.
            // Retry nastąpi przez onAgentStatusChanged gdy agent stanie się AVAILABLE.
            verify(rabbitTemplate, never()).convertAndSend(
                    anyString(),
                    eq("contact.queued"),
                    any(Object.class)
            );
        }

        @Test
        @DisplayName("nie powinien publikować żadnych eventów gdy brak agentów")
        void shouldNotPublishAnyEventWhenNoAgent() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            // Żaden event nie powinien być opublikowany
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
        }
    }

    // =========================================================================
    // routeContact – scenariusze błędów
    // =========================================================================

    @Nested
    @DisplayName("routeContact – scenariusze błędów")
    class ErrorScenarios {

        @Test
        @DisplayName("powinien rzucić EntityNotFoundException gdy kolejka nie istnieje")
        void shouldThrowWhenQueueNotFound() {
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(QUEUE_ID.toString());
        }

        @Test
        @DisplayName("powinien zwrócić Optional.empty() gdy kontakt nie istnieje (graceful – nie rzuca wyjątku)")
        void shouldThrowWhenContactNotFound() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

            // Fix: brak kontaktu nie rzuca wyjątku – RoutingService loguje ERROR i zwraca empty,
            // żeby wiadomość RabbitMQ nie trafiała do DLQ po exhausted retries.
            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("błąd RabbitMQ przy contact.assigned nie powinien przerywać operacji")
        void rabbitMqFailureOnAssignedShouldNotBreakOperation() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactRepository.update(any())).thenReturn(1);
            doThrow(new RuntimeException("RabbitMQ unavailable"))
                    .when(rabbitTemplate).convertAndSend(anyString(), eq("contact.assigned"), any(Object.class));

            // Operacja powinna się zakończyć pomyślnie mimo błędu RabbitMQ
            assertThatCode(() -> routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // onContactQueued
    // =========================================================================

    @Nested
    @DisplayName("onContactQueued – RabbitMQ listener")
    class OnContactQueued {

        @Test
        @DisplayName("powinien wywołać routeContact dla odebranego eventu")
        void shouldCallRouteContactForReceivedEvent() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            Contact contact = buildContact();

            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            ContactQueuedMessage message = new ContactQueuedMessage(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThatCode(() -> routingService.onContactQueued(message))
                    .doesNotThrowAnyException();

            // Weryfikuj że próba routingu nastąpiła (kontakt wyszukany w DB)
            verify(contactRepository, atLeastOnce()).findById(CONTACT_ID, TENANT_ID);
        }

        @Test
        @DisplayName("powinien rzucić RuntimeException przy błędzie routingu (dla retry RabbitMQ)")
        void shouldThrowRuntimeExceptionOnRoutingError() {
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID))
                    .thenThrow(new RuntimeException("DB connection error"));

            ContactQueuedMessage message = new ContactQueuedMessage(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThatThrownBy(() -> routingService.onContactQueued(message))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // =========================================================================
    // onAgentStatusChanged
    // =========================================================================

    @Nested
    @DisplayName("onAgentStatusChanged – retry routingu oczekujących kontaktów")
    class OnAgentStatusChanged {

        @Test
        @DisplayName("powinien próbować routować oczekujące kontakty gdy agent staje się AVAILABLE")
        void shouldRouteQueuedContactsWhenAgentBecomesAvailable() {
            Contact queuedContact = buildContact();
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());

            when(contactRepository.findQueuedContacts(TENANT_ID)).thenReturn(List.of(queuedContact));
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(queuedContact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactRepository.update(any())).thenReturn(1);

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BUSY, UserStatus.AVAILABLE);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            verify(contactRepository).findQueuedContacts(TENANT_ID);
            verify(routingEngine, atLeastOnce()).findBestAgent(any());
        }

        @Test
        @DisplayName("powinien ignorować zmianę statusu na inny niż AVAILABLE")
        void shouldIgnoreNonAvailableStatusChange() {
            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.AVAILABLE, UserStatus.BUSY);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            // Nie powinien szukać oczekujących kontaktów
            verify(contactRepository, never()).findQueuedContacts(any());
        }

        @Test
        @DisplayName("powinien nic nie robić gdy brak oczekujących kontaktów")
        void shouldDoNothingWhenNoQueuedContacts() {
            when(contactRepository.findQueuedContacts(TENANT_ID)).thenReturn(List.of());

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            verify(contactRepository).findQueuedContacts(TENANT_ID);
            verify(routingEngine, never()).findBestAgent(any());
        }

        @Test
        @DisplayName("powinien zatrzymać się po przydzieleniu pierwszego kontaktu (agent zajęty)")
        void shouldStopAfterFirstSuccessfulAssignment() {
            Contact contact1 = buildContact();
            Contact contact2 = buildContact(UUID.fromString("44444444-4444-4444-4444-444444444444"));

            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());

            when(contactRepository.findQueuedContacts(TENANT_ID)).thenReturn(List.of(contact1, contact2));
            when(queueRepository.findByIdAndTenantId(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactRepository.findById(contact1.getContactId(), TENANT_ID)).thenReturn(Optional.of(contact1));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactRepository.update(any())).thenReturn(1);

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE);

            routingService.onAgentStatusChanged(event);

            // Tylko jeden kontakt powinien być routowany (agent zajęty po przydzieleniu)
            verify(routingEngine, times(1)).findBestAgent(any());
        }

        @Test
        @DisplayName("powinien pominąć kontakt bez queueId")
        void shouldSkipContactWithoutQueueId() {
            Contact contactWithoutQueue = Contact.builder()
                    .contactId(CONTACT_ID)
                    .tenantId(TENANT_ID)
                    .queueId(null)  // brak kolejki
                    .channel("VOICE")
                    .direction("INBOUND")
                    .status("QUEUED")
                    .startedAt(Instant.now())
                    .queuedAt(Instant.now())
                    .build();

            when(contactRepository.findQueuedContacts(TENANT_ID)).thenReturn(List.of(contactWithoutQueue));

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            // Routing nie powinien być wywołany dla kontaktu bez queueId
            verify(routingEngine, never()).findBestAgent(any());
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private Queue buildQueue(String strategy, List<String> skills) {
        return Queue.builder()
                .queueId(QUEUE_ID)
                .tenantId(TENANT_ID)
                .name("Test Queue")
                .routingStrategy(strategy)
                .requiredSkills(skills)
                .active(true)
                .build();
    }

    private Contact buildContact() {
        return buildContact(CONTACT_ID);
    }

    private Contact buildContact(UUID contactId) {
        return Contact.builder()
                .contactId(contactId)
                .tenantId(TENANT_ID)
                .queueId(QUEUE_ID)
                .channel("VOICE")
                .direction("INBOUND")
                .status("QUEUED")
                .startedAt(Instant.now())
                .queuedAt(Instant.now())
                .build();
    }
}
