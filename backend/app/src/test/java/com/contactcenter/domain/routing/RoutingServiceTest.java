package com.contactcenter.domain.routing;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.contact.AssignmentResult;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.contact.ContactEventService;
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
import java.util.Set;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
    private QueueService queueService;

    @Mock
    private ContactService contactService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private QueueAssignmentService queueAssignmentService;

    @Mock
    private UserService userService;

    @Mock
    private WebSocketEventBroadcaster broadcaster;

    @Mock
    private CustomerService customerService;

    @Mock
    private ContactEventService contactEventService;

    @Mock
    private com.contactcenter.domain.social.SocialDefaultQueueResolver socialDefaultQueueResolver;

    @Mock
    private ReleaseExclusionStore releaseExclusionStore;

    private RoutingServiceImpl routingService;

    /** Real lock class over the no-op provider (always acquired) - the lock itself is tested in BE-173 tests. */
    private static RoutingQueueLock passThroughQueueLock() {
        return new RoutingQueueLock(com.contactcenter.support.NoOpLockProvider.executor(),
                new com.contactcenter.infrastructure.lock.LockSkipMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), 0, 50, 60_000);
    }

    @BeforeEach
    void setUp() {
        routingService = new RoutingServiceImpl(routingEngine, queueService,
                customerService, rabbitTemplate, queueAssignmentService, userService,
                broadcaster, contactService, contactEventService, socialDefaultQueueResolver,
                releaseExclusionStore, passThroughQueueLock());
        // Domyślnie: all_agents=TRUE (brak filtru) – zachowanie sprzed BE-047
        lenient().when(queueAssignmentService.isAllAgents(any(UUID.class), any(UUID.class)))
                .thenReturn(true);
        // Broadcast po routingu – domyślnie pusta lista żeby nie rzucać NPE w testach
        lenient().when(contactService.findQueuedContactsForAgentView(any(UUID.class)))
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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any(RoutingRequest.class)))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "SKILL_BASED")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isPresent().contains(AGENT_ID);

            // Weryfikuj aktualizację kontaktu
            ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            verify(contactService).assignQueuedContact(contactCaptor.capture(), eq(AGENT_ID), any(), any());
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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "ROUND_ROBIN")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isEmpty();

            // Kontakt NIE powinien być aktualizowany
            verify(contactService, never()).assignQueuedContact(any(), any(), any(), any());

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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
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
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(QUEUE_ID.toString());
        }

        @Test
        @DisplayName("powinien zwrócić Optional.empty() gdy kontakt nie istnieje (graceful – nie rzuca wyjątku)")
        void shouldThrowWhenContactNotFound() {
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));
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

            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            ContactQueuedMessage message = new ContactQueuedMessage(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThatCode(() -> routingService.onContactQueued(message))
                    .doesNotThrowAnyException();

            // Weryfikuj że próba routingu nastąpiła (kontakt wyszukany w DB)
            verify(contactService, atLeastOnce()).findContactEntity(CONTACT_ID, TENANT_ID);
        }

        @Test
        @DisplayName("powinien rzucić RuntimeException przy błędzie routingu (dla retry RabbitMQ)")
        void shouldThrowRuntimeExceptionOnRoutingError() {
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
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

            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(queuedContact));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(queuedContact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BUSY, UserStatus.AVAILABLE, null);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            verify(contactService).findQueuedContacts(TENANT_ID);
            verify(routingEngine, atLeastOnce()).findBestAgent(any());
        }

        @Test
        @DisplayName("BE-166: niezmienione AVAILABLE (AgentAvailabilityRefreshedEvent) – retry routingu bez eventu agent.status.changed")
        void shouldRetryRoutingOnAvailabilityRefreshedWithoutStatusEvent() {
            Contact queuedContact = buildContact();
            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());

            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(queuedContact));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(queuedContact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

            routingService.onAgentAvailabilityRefreshed(
                    new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID));

            verify(contactService).findQueuedContacts(TENANT_ID);
            verify(routingEngine, atLeastOnce()).findBestAgent(any());
            // the retry publishes no user-facing status event (only contact.assigned)
            verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("agent.status.changed"), any(Object.class));
            // tenant context cleaned on the worker thread
            assertThat(com.contactcenter.security.TenantContext.getTenantIdOrNull()).isNull();
        }

        @Test
        @DisplayName("powinien ignorować zmianę statusu na inny niż AVAILABLE")
        void shouldIgnoreNonAvailableStatusChange() {
            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.AVAILABLE, UserStatus.BUSY, null);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            // Nie powinien szukać oczekujących kontaktów
            verify(contactService, never()).findQueuedContacts(any());
        }

        @Test
        @DisplayName("powinien nic nie robić gdy brak oczekujących kontaktów")
        void shouldDoNothingWhenNoQueuedContacts() {
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of());

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            verify(contactService).findQueuedContacts(TENANT_ID);
            verify(routingEngine, never()).findBestAgent(any());
        }

        @Test
        @DisplayName("powinien zatrzymać się po przydzieleniu pierwszego kontaktu (agent zajęty)")
        void shouldStopAfterFirstSuccessfulAssignment() {
            Contact contact1 = buildContact();
            Contact contact2 = buildContact(UUID.fromString("44444444-4444-4444-4444-444444444444"));

            Queue queue = buildQueue("FIRST_AVAILABLE", List.of());

            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(contact1, contact2));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
            when(contactService.findContactEntity(contact1.getContactId(), TENANT_ID)).thenReturn(Optional.of(contact1));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null);

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

            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(contactWithoutQueue));

            AgentStatusChangedEvent event = AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null);

            assertThatCode(() -> routingService.onAgentStatusChanged(event))
                    .doesNotThrowAnyException();

            // Routing nie powinien być wywołany dla kontaktu bez queueId
            verify(routingEngine, never()).findBestAgent(any());
        }

        private Contact socialContactWithoutQueue() {
            return Contact.builder()
                    .contactId(CONTACT_ID).tenantId(TENANT_ID).queueId(null)
                    .channel("SOCIAL_WHATSAPP").direction("INBOUND").status("QUEUED")
                    .remoteAddress("48123456789")
                    .startedAt(Instant.now()).queuedAt(Instant.now())
                    .build();
        }

        @Test
        @DisplayName("BE-155: kontakt social bez queueId dostaje domyślną kolejkę i jest routowany (nie ERROR)")
        void shouldAssignDefaultQueueToSocialContactAndRoute() {
            Contact social = socialContactWithoutQueue();
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(social));
            when(socialDefaultQueueResolver.resolve(TENANT_ID)).thenReturn(Optional.of(QUEUE_ID));
            when(contactService.updateQueueId(CONTACT_ID, TENANT_ID, QUEUE_ID)).thenReturn(1);
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(social));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(Contact.class), any(UUID.class), any(), any())).thenAnswer(inv -> assignWins(inv));

            routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null));

            verify(contactService).updateQueueId(CONTACT_ID, TENANT_ID, QUEUE_ID);
            verify(routingEngine).findBestAgent(any());
            assertThat(social.getStatus()).isNotEqualTo("ERROR");
            assertThat(social.getEndedAt()).isNull();
        }

        @Test
        @DisplayName("BE-155: kontakt social bez queueId i tenant bez kolejki → zostaje QUEUED, nie ERROR")
        void shouldLeaveSocialContactQueuedWhenTenantHasNoQueue() {
            Contact social = socialContactWithoutQueue();
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(social));
            when(socialDefaultQueueResolver.resolve(TENANT_ID)).thenReturn(Optional.empty());

            routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null));

            assertThat(social.getStatus()).isEqualTo("QUEUED");
            assertThat(social.getEndedAt()).isNull();
            verify(contactService, never()).assignQueuedContact(any(), any(), any(), any());
            verify(routingEngine, never()).findBestAgent(any());
        }

        @Test
        @DisplayName("kontakt nie-social bez queueId nadal kończy się ERROR (bez zmiany)")
        void shouldStillErrorNonSocialContactWithoutQueue() {
            Contact voice = Contact.builder()
                    .contactId(CONTACT_ID).tenantId(TENANT_ID).queueId(null)
                    .channel("PHONE").direction("INBOUND").status("QUEUED")
                    .startedAt(Instant.now()).queuedAt(Instant.now()).build();
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(voice));

            routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null));

            verify(contactService).markQueuedContactWithoutQueueAsError(eq(voice), any(Instant.class));
            verify(socialDefaultQueueResolver, never()).resolve(any());
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

        @Nested
    @DisplayName("routeContact - wykluczenia po zwrocie do kolejki (BE-160)")
    class ReleaseExclusions {

        @Test
        @DisplayName("kontakt async: wykluczeni agenci z magazynu trafiaja do RoutingRequest")
        void passesExcludedAgentsForAsyncContact() {
            Contact contact = buildContact();
            contact.setChannel("EMAIL");
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(releaseExclusionStore.excludedAgents(TENANT_ID, CONTACT_ID)).thenReturn(Set.of(AGENT_ID));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isEmpty();
            ArgumentCaptor<RoutingRequest> captor = ArgumentCaptor.forClass(RoutingRequest.class);
            verify(routingEngine).findBestAgent(captor.capture());
            assertThat(captor.getValue().excludedAgentIds()).containsExactly(AGENT_ID);
            verify(contactService, never()).assignQueuedContact(any(), any(), any(), any());
        }

        @Test
        @DisplayName("kontakt telefoniczny: magazyn wykluczen nie jest w ogole odpytywany")
        void phoneContactNeverQueriesStore() {
            Contact contact = buildContact(); // channel VOICE (phone path)
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any())).thenReturn(Optional.empty());

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            verifyNoInteractions(releaseExclusionStore);
        }
    }

    // =========================================================================
    // BE-159 - conditional assignment: 0 rows = skip, no side effects
    // =========================================================================

    @Nested
    @DisplayName("BE-159 - warunkowy przydzial kontaktu")
    class ConditionalAssignment {

        @Test
        @DisplayName("routeContact: 0 wierszy -> empty, bez contact.assigned")
        void routeContactLostRaceSkipsContact() {
            Contact contact = buildContact();
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(eq(contact), eq(AGENT_ID), any(), any())).thenReturn(AssignmentResult.CONTACT_UNAVAILABLE);

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isEmpty();
            verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("contact.assigned"), any(Object.class));
            assertThat(contact.getStatus()).isEqualTo("QUEUED");
            assertThat(contact.getAgentId()).isNull();
        }

        @Test
        @DisplayName("retry po AVAILABLE: przegrany kontakt nie zuzywa agenta - przydzielany jest nastepny")
        void retryPassContinuesWithNextContactAfterLostRace() {
            Contact lost = buildContact();
            UUID secondId = UUID.fromString("44444444-4444-4444-4444-444444444444");
            Contact second = buildContact(secondId);
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(lost, second));
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(lost));
            when(contactService.findContactEntity(secondId, TENANT_ID)).thenReturn(Optional.of(second));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(eq(lost), eq(AGENT_ID), any(), any())).thenReturn(AssignmentResult.CONTACT_UNAVAILABLE);
            when(contactService.assignQueuedContact(eq(second), eq(AGENT_ID), any(), any())).thenAnswer(inv -> assignWins(inv));

            routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null));

            ArgumentCaptor<ContactAssignedEvent> published = ArgumentCaptor.forClass(ContactAssignedEvent.class);
            verify(rabbitTemplate, times(1)).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.assigned"), published.capture());
            assertThat(published.getValue().contactId()).isEqualTo(secondId);
        }

        @Test
        @DisplayName("sciezka ERROR: 0 wierszy -> bez wyjatku, kontakt nietkniety")
        void errorPathZeroRowsIsSilent() {
            Contact voice = Contact.builder()
                    .contactId(CONTACT_ID).tenantId(TENANT_ID).queueId(null)
                    .channel("PHONE").direction("INBOUND").status("QUEUED")
                    .startedAt(Instant.now()).queuedAt(Instant.now()).build();
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(voice));
            when(contactService.markQueuedContactWithoutQueueAsError(eq(voice), any(Instant.class)))
                    .thenReturn(false);

            assertThatCode(() -> routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null)))
                    .doesNotThrowAnyException();

            assertThat(voice.getStatus()).isEqualTo("QUEUED");
            assertThat(voice.getEndedAt()).isNull();
            verify(routingEngine, never()).findBestAgent(any());
        }

        private DirectAgentAssignmentMessage directMessage() {
            return new DirectAgentAssignmentMessage(CONTACT_ID, AGENT_ID, TENANT_ID, "Jan Kowalski", QUEUE_ID, "Q");
        }

        @Test
        @DisplayName("onDirectAgentAssignment: 0 wierszy -> bez contact.assigned")
        void directAssignmentLostRaceSkipsEvent() {
            Contact contact = buildContact();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(contactService.assignQueuedContact(eq(contact), eq(AGENT_ID), any(), any())).thenReturn(AssignmentResult.CONTACT_UNAVAILABLE);

            routingService.onDirectAgentAssignment(directMessage());

            verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("contact.assigned"), any(Object.class));
            assertThat(contact.getAgentId()).isNull();
        }

        @Test
        @DisplayName("onDirectAgentAssignment: 1 wiersz -> contact.assigned (DIRECT_TRANSFER)")
        void directAssignmentWinPublishesEvent() {
            Contact contact = buildContact();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));
            when(contactService.assignQueuedContact(eq(contact), eq(AGENT_ID), any(), any())).thenAnswer(inv -> assignWins(inv));

            routingService.onDirectAgentAssignment(directMessage());

            ArgumentCaptor<ContactAssignedEvent> published = ArgumentCaptor.forClass(ContactAssignedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.assigned"), published.capture());
            assertThat(published.getValue().agentId()).isEqualTo(AGENT_ID);
            assertThat(published.getValue().strategy()).isEqualTo("DIRECT_TRANSFER");
        }
    }

    // =========================================================================
    // BE-159/BE-156 code review follow-ups
    // =========================================================================

    @Nested
    @DisplayName("Poprawki po code review BE-159/BE-156")
    class ReviewFollowUps {

        private void stubQueue() {
            when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildQueue("FIRST_AVAILABLE", List.of())));
        }

        @Test
        @DisplayName("queueId do limitu jest przekazywany z routingu, nie z encji (kontakt async bez queue_id)")
        void queueIdComesFromRoutingNotFromEntity() {
            Contact email = buildContact();
            email.setChannel("EMAIL");
            email.setQueueId(null);
            stubQueue();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(email));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(), any(), any(), any()))
                    .thenAnswer(inv -> assignWins(inv));

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            verify(contactService).assignQueuedContact(eq(email), eq(AGENT_ID), eq(QUEUE_ID), eq(3));
        }

        @Test
        @DisplayName("wyczerpanie 10 rund: agent zawsze na limicie -> empty, 10 prob zapisu, bez contact.assigned")
        void exhaustsRetryRoundsWithoutLooping() {
            Contact email = buildContact();
            email.setChannel("EMAIL");
            stubQueue();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(email));
            // a misbehaving engine that keeps offering an agent despite the exclusions
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(), any(), any(), any()))
                    .thenReturn(AssignmentResult.AGENT_AT_LIMIT);

            Optional<UUID> result = routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            assertThat(result).isEmpty();
            verify(contactService, times(10)).assignQueuedContact(any(), any(), any(), any());
            verify(routingEngine, times(11)).findBestAgent(any());
            verify(rabbitTemplate, never()).convertAndSend(anyString(), eq("contact.assigned"), any(Object.class));
        }

        @Test
        @DisplayName("ponowne pytanie silnika po AGENT_AT_LIMIT jest oznaczone jako retryRound i wyklucza agenta")
        void retryRoundMarksRequestAndExcludesAgent() {
            Contact email = buildContact();
            email.setChannel("EMAIL");
            stubQueue();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(email));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")))
                    .thenReturn(Optional.empty());
            when(contactService.assignQueuedContact(any(), any(), any(), any()))
                    .thenReturn(AssignmentResult.AGENT_AT_LIMIT);

            routingService.routeContact(CONTACT_ID, QUEUE_ID, TENANT_ID);

            ArgumentCaptor<RoutingRequest> captor = ArgumentCaptor.forClass(RoutingRequest.class);
            verify(routingEngine, times(2)).findBestAgent(captor.capture());
            assertThat(captor.getAllValues().get(0).retryRound()).isFalse();
            assertThat(captor.getAllValues().get(1).retryRound()).isTrue();
            assertThat(captor.getAllValues().get(1).excludedAgentIds()).containsExactly(AGENT_ID);
        }

        @Test
        @DisplayName("poll: jeden przebieg backlogu na tenant, nie na agenta")
        void pollRunsOnePassPerTenant() {
            UUID otherTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
            when(userService.findAvailableAgents()).thenReturn(List.of(
                    com.contactcenter.domain.user.AppUser.builder().id(UUID.randomUUID()).tenantId(TENANT_ID).build(),
                    com.contactcenter.domain.user.AppUser.builder().id(UUID.randomUUID()).tenantId(TENANT_ID).build(),
                    com.contactcenter.domain.user.AppUser.builder().id(UUID.randomUUID()).tenantId(TENANT_ID).build(),
                    com.contactcenter.domain.user.AppUser.builder().id(UUID.randomUUID()).tenantId(otherTenant).build()));
            when(contactService.findQueuedContacts(any())).thenReturn(List.of());
            lenient().when(contactService.findQueuedContactsForAgentView(any())).thenReturn(List.of());

            routingService.pollAvailableAgents();

            verify(contactService, times(1)).findQueuedContacts(TENANT_ID);
            verify(contactService, times(1)).findQueuedContacts(otherTenant);
        }

        @Test
        @DisplayName("BE-211: przebieg z pollu przy pustej kolejce nie loguje INFO")
        void pollWithEmptyQueueDoesNotLogInfo() {
            when(userService.findAvailableAgents()).thenReturn(List.of(
                    com.contactcenter.domain.user.AppUser.builder().id(AGENT_ID).tenantId(TENANT_ID).build()));
            when(contactService.findQueuedContacts(any())).thenReturn(List.of());

            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureRoutingLogs(
                    () -> routingService.pollAvailableAgents());

            assertThat(events).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO);
            assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                    && e.getFormattedMessage().contains("próbuję routować"));
        }

        @Test
        @DisplayName("BE-211: przebieg wyzwolony zdarzeniem loguje INFO 'próbuję routować'")
        void eventDrivenPassLogsInfo() {
            when(contactService.findQueuedContacts(any())).thenReturn(List.of());

            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureRoutingLogs(
                    () -> routingService.onAgentAvailabilityRefreshed(
                            new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID)));

            assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().contains("próbuję routować"));
        }

        @Test
        @DisplayName("BE-211: 'Znaleziono N oczekujących' z pollu zostaje na INFO")
        void pollWithQueuedContactsStillLogsFoundAtInfo() {
            when(userService.findAvailableAgents()).thenReturn(List.of(
                    com.contactcenter.domain.user.AppUser.builder().id(AGENT_ID).tenantId(TENANT_ID).build()));
            Contact queued = new Contact();
            queued.setContactId(UUID.randomUUID());
            queued.setTenantId(TENANT_ID);
            queued.setStatus("QUEUED");
            // OUTBOUND with an assigned agent is skipped by the pass, so it ends normally without further stubs
            queued.setDirection("OUTBOUND");
            queued.setAgentId(AGENT_ID);
            when(contactService.findQueuedContacts(any())).thenReturn(List.of(queued));

            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureRoutingLogs(
                    () -> routingService.pollAvailableAgents());

            assertThat(events).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().contains("Znaleziono 1 oczekujących"));
        }

        @Test
        @DisplayName("BE-211: kontynuacja koalescencji (przebieg 'dirty') loguje 'próbuję routować' na DEBUG")
        void coalescedContinuationAnnouncesAtDebug() {
            java.util.concurrent.atomic.AtomicInteger passes = new java.util.concurrent.atomic.AtomicInteger();
            when(contactService.findQueuedContacts(TENANT_ID)).thenAnswer(inv -> {
                if (passes.incrementAndGet() == 1) {
                    routingService.onAgentAvailabilityRefreshed(
                            new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID));
                }
                return List.<Contact>of();
            });

            List<ch.qos.logback.classic.spi.ILoggingEvent> events = captureRoutingLogs(
                    () -> routingService.onAgentAvailabilityRefreshed(
                            new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID)));

            assertThat(passes.get()).isEqualTo(2);
            assertThat(events.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().contains("próbuję routować"))).hasSize(1);
            assertThat(events.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                    && e.getFormattedMessage().contains("próbuję routować"))).hasSize(1);
        }

        private List<ch.qos.logback.classic.spi.ILoggingEvent> captureRoutingLogs(Runnable action) {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RoutingServiceImpl.class);
            ch.qos.logback.classic.Level previous = logger.getLevel();
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            try {
                action.run();
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previous);
            }
            return List.copyOf(appender.list);
        }

        @Test
        @DisplayName("koalescencja: zlecenia w trakcie przebiegu daja dokladnie jeden kolejny przebieg")
        void requestsDuringAPassCoalesceIntoOneFollowUp() {
            java.util.concurrent.atomic.AtomicInteger passes = new java.util.concurrent.atomic.AtomicInteger();
            when(contactService.findQueuedContacts(TENANT_ID)).thenAnswer(inv -> {
                if (passes.incrementAndGet() == 1) {
                    // five contacts close while the first pass is running (re-entrant on this thread = "running")
                    for (int i = 0; i < 5; i++) {
                        routingService.onAgentAvailabilityRefreshed(
                                new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID));
                    }
                }
                return List.<Contact>of();
            });

            routingService.onAgentAvailabilityRefreshed(
                    new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID));

            assertThat(passes.get()).as("first pass + ONE coalesced follow-up").isEqualTo(2);
            // and the tenant is free again afterwards
            routingService.onAgentAvailabilityRefreshed(
                    new com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent(AGENT_ID, TENANT_ID));
            assertThat(passes.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("po przydziale kontaktu telefonicznego kolejne PHONE sa pomijane, ale async za nimi jest routowany")
        void phoneAssignmentDoesNotStarveAsyncContacts() {
            Contact phone1 = buildContact();
            Contact phone2 = buildContact(UUID.fromString("44444444-4444-4444-4444-444444444444"));
            UUID emailId = UUID.fromString("55555555-5555-5555-5555-555555555555");
            Contact email = buildContact(emailId);
            email.setChannel("EMAIL");
            when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(phone1, phone2, email));
            stubQueue();
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(phone1));
            when(contactService.findContactEntity(emailId, TENANT_ID)).thenReturn(Optional.of(email));
            when(routingEngine.findBestAgent(any()))
                    .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));
            when(contactService.assignQueuedContact(any(), any(), any(), any()))
                    .thenAnswer(inv -> assignWins(inv));

            routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                    AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE, null));

            verify(contactService).assignQueuedContact(eq(phone1), any(), any(), any());
            verify(contactService, never()).assignQueuedContact(eq(phone2), any(), any(), any());
            verify(contactService).assignQueuedContact(eq(email), any(), any(), any());
        }

        @Test
        @DisplayName("transfer bezposredni: kontakt wstawiony jako ASSIGNED do agenta docelowego -> bez ponownego zapisu, jest contact.assigned")
        void directAssignmentOfAlreadyAssignedContactOnlyPublishes() {
            Contact contact = buildContact();
            contact.setStatus("ASSIGNED");
            contact.setAgentId(AGENT_ID);
            when(contactService.findContactEntity(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(contact));

            routingService.onDirectAgentAssignment(
                    new DirectAgentAssignmentMessage(CONTACT_ID, AGENT_ID, TENANT_ID, "Jan", QUEUE_ID, "Q"));

            verify(contactService, never()).assignQueuedContact(any(), any(), any(), any());
            verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.assigned"),
                    any(ContactAssignedEvent.class));
        }
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

    /** Stands in for the conditional DB write that wins: mutates the entity like the real service does (BE-159). */
    private static AssignmentResult assignWins(org.mockito.invocation.InvocationOnMock inv) {
        Contact c = inv.getArgument(0);
        c.setAgentId(inv.getArgument(1));
        c.setStatus("ASSIGNED");
        c.setAssignedAt(Instant.now());
        return AssignmentResult.ASSIGNED;
    }
}
