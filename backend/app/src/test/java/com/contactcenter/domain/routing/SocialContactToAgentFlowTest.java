package com.contactcenter.domain.routing;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.social.IncomingSocialMessage;
import com.contactcenter.domain.social.SocialDefaultQueueResolver;
import com.contactcenter.domain.social.SocialIntegration;
import com.contactcenter.domain.social.SocialMessage;
import com.contactcenter.domain.social.SocialMessageService;
import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-155: flow integration test (service level, real wiring of the three production classes
 * {@code SocialMessageServiceImpl} + {@link SocialDefaultQueueResolver} + {@code RoutingServiceImpl};
 * only persistence, broker, and the routing engine are stubbed):
 * social message -> contact WITH a queue -> routing to an AVAILABLE agent -> {@code contact.assigned}.
 *
 * <p>Lives in the {@code domain.routing} package because {@code RoutingServiceImpl} is package-private;
 * {@code SocialMessageServiceImpl} (package-private in another package) is created reflectively.
 */
@DisplayName("BE-155: wiadomość social -> kontakt z kolejką -> agent AVAILABLE -> CONTACT_ASSIGNED")
class SocialContactToAgentFlowTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID QUEUE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INTEGRATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String PAGE_ID = "PAGE_1";
    private static final String SENDER_ID = "SENDER_1";

    private final List<Contact> storedContacts = new ArrayList<>();
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final QueueService queueService = mock(QueueService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final RoutingEngine routingEngine = mock(RoutingEngine.class);
    private final Map<String, Object> tenantConfig = new HashMap<>();

    private SocialMessageService socialService;
    private RoutingServiceImpl routingService;

    @BeforeEach
    void setUp() throws Exception {
        Tenant tenant = mock(Tenant.class);
        when(tenant.getConfig()).thenReturn(tenantConfig);
        when(tenantService.findTenantEntity(TENANT_ID)).thenReturn(Optional.of(tenant));

        Queue queue = Queue.builder().queueId(QUEUE_ID).tenantId(TENANT_ID).name("Social")
                .routingStrategy("FIRST_AVAILABLE").active(true).build();
        when(queueService.getAllQueues(TENANT_ID)).thenReturn(List.of(queue));
        when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));

        when(contactService.insertContact(any())).thenAnswer(inv -> {
            storedContacts.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(contactService.findActiveSocialContact(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(contactService.findContactEntity(any(), any())).thenAnswer(inv -> storedContacts.stream()
                .filter(c -> c.getContactId().equals(inv.getArgument(0))).findFirst());
        when(contactService.updateContactEntity(any())).thenReturn(1);
        lenient().when(contactService.findQueuedContactsForAgentView(any())).thenReturn(List.of());

        QueueAssignmentService queueAssignmentService = mock(QueueAssignmentService.class);
        when(queueAssignmentService.isAllAgents(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(routingEngine.findBestAgent(any()))
                .thenReturn(Optional.of(RoutingResult.of(AGENT_ID, "FIRST_AVAILABLE")));

        SocialDefaultQueueResolver resolver = new SocialDefaultQueueResolver(tenantService, queueService);
        routingService = new RoutingServiceImpl(routingEngine, queueService, mock(CustomerService.class),
                rabbitTemplate, queueAssignmentService, mock(UserService.class),
                mock(WebSocketEventBroadcaster.class), contactService, mock(ContactEventService.class),
                resolver, mock(ReleaseExclusionStore.class));

        // Repositories are package-private in domain.social: mocked reflectively with name-based answers
        Object integrationRepository = mock(Class.forName(
                "com.contactcenter.domain.social.SocialIntegrationRepository"), inv -> {
            if ("findByPlatformAndPageId".equals(inv.getMethod().getName())) {
                return Optional.of(SocialIntegration.builder().integrationId(INTEGRATION_ID)
                        .tenantId(TENANT_ID).platform(inv.getArgument(0)).pageId(PAGE_ID)
                        .webhookStatus("ACTIVE").build());
            }
            return null;
        });
        Object messageRepository = mock(Class.forName(
                "com.contactcenter.domain.social.SocialMessageRepository"), inv -> {
            return switch (inv.getMethod().getName()) {
                case "findByExternalMessageId" -> Optional.empty();
                case "save" -> Optional.of(inv.getArgument(0));
                default -> null;
            };
        });

        Class<?> implClass = Class.forName("com.contactcenter.domain.social.SocialMessageServiceImpl");
        Constructor<?> ctor = implClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        socialService = (SocialMessageService) ctor.newInstance(integrationRepository, messageRepository,
                contactService, mock(SocialAdapterRegistry.class), rabbitTemplate, resolver);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest(name = "{0} -> kanał {1}, typ pulpitu SOCIAL")
    @CsvSource({"FACEBOOK,SOCIAL_FACEBOOK", "INSTAGRAM,SOCIAL_INSTAGRAM", "WHATSAPP,SOCIAL_WHATSAPP"})
    @DisplayName("pełny flow dla każdej platformy: kolejka, przydział, CONTACT_ASSIGNED (nie ERROR)")
    void socialMessageReachesAvailableAgent(SocialPlatform platform, String expectedChannel) {
        socialService.processIncomingMessage(new IncomingSocialMessage(
                platform, PAGE_ID, SENDER_ID, "MID-" + platform, "Hej", null, Instant.now()));

        // 1. contact created WITH the default social queue and the persisted SOCIAL_* channel
        assertThat(storedContacts).hasSize(1);
        Contact contact = storedContacts.get(0);
        assertThat(contact.getChannel()).isEqualTo(expectedChannel);
        assertThat(contact.getQueueId()).isEqualTo(QUEUE_ID);

        // 2. contact.queued carries the queue
        ArgumentCaptor<Object> queued = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"),
                queued.capture());
        ContactQueuedMessage queuedMsg = (ContactQueuedMessage) queued.getValue();
        assertThat(queuedMsg.queueId()).isEqualTo(QUEUE_ID);

        // 3. routing (RabbitMQ listener) assigns the contact to the AVAILABLE agent
        routingService.onContactQueued(queuedMsg);
        assertThat(contact.getStatus()).isEqualTo("ASSIGNED").isNotEqualTo("ERROR");
        assertThat(contact.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(contact.getEndedAt()).isNull();

        // 4. contact.assigned is published (relay turns it into WS CONTACT_ASSIGNED)
        ArgumentCaptor<Object> assigned = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.assigned"),
                assigned.capture());
        ContactAssignedEvent event = (ContactAssignedEvent) assigned.getValue();
        assertThat(event.agentId()).isEqualTo(AGENT_ID);
        assertThat(event.channel()).isEqualTo(expectedChannel);

        // 5. the WS payload the frontend reads ('type') is the single collapsed value
        WebSocketEvent ws = WebSocketEvent.contactAssigned(TENANT_ID, event.agentId(), event.contactId(),
                event.channel(), event.customerName(), event.customerIdentifier(), event.queueName(),
                event.customerId());
        assertThat(((WebSocketEvent.ContactAssignedPayload) ws.payload()).type()).isEqualTo("SOCIAL");
    }

    @Test
    @DisplayName("jawny tenant.config[social_default_queue_id] wygrywa z fallbackiem")
    void explicitConfigWins() {
        UUID other = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Queue otherQueue = Queue.builder().queueId(other).tenantId(TENANT_ID).name("Zzz")
                .routingStrategy("FIRST_AVAILABLE").active(true).build();
        when(queueService.findQueueEntity(other, TENANT_ID)).thenReturn(Optional.of(otherQueue));
        tenantConfig.put(SocialDefaultQueueResolver.CONFIG_KEY, other.toString());

        socialService.processIncomingMessage(new IncomingSocialMessage(
                SocialPlatform.WHATSAPP, PAGE_ID, SENDER_ID, "MID-X", "Hej", null, Instant.now()));

        assertThat(storedContacts.get(0).getQueueId()).isEqualTo(other);
    }

    @Test
    @DisplayName("stary kontakt social bez queueId (sprzed BE-155) jest uleczony przy retry routingu")
    void legacyQueuelessSocialContactIsHealedOnRetry() {
        Contact legacy = Contact.builder().contactId(UUID.randomUUID()).tenantId(TENANT_ID).queueId(null)
                .channel("SOCIAL_FACEBOOK").direction("INBOUND").status("QUEUED").remoteAddress(SENDER_ID)
                .startedAt(Instant.now()).queuedAt(Instant.now()).build();
        storedContacts.add(legacy);
        when(contactService.findQueuedContacts(TENANT_ID)).thenReturn(List.of(legacy));
        when(contactService.updateQueueId(legacy.getContactId(), TENANT_ID, QUEUE_ID)).thenReturn(1);

        routingService.onAgentStatusChanged(AgentStatusChangedEvent.of(
                AGENT_ID, TENANT_ID, UserStatus.BREAK, UserStatus.AVAILABLE));

        verify(contactService).updateQueueId(legacy.getContactId(), TENANT_ID, QUEUE_ID);
        assertThat(legacy.getStatus()).isEqualTo("ASSIGNED");
    }
}
