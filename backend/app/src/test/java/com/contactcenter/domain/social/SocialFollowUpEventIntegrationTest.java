package com.contactcenter.domain.social;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.websocket.ContactMessageReceivedPublisher;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BE-154 (social path) on a REAL PostgreSQL: real {@link SocialMessageServiceImpl} behind the
 * {@code @Transactional} proxy, real {@link SocialMessageRepository}, real publisher; mocked: integration lookup,
 * contact lookup (tenant-scoped, as the production query), RabbitMQ and the WS broadcaster.
 */
@DisplayName("BE-154: follow-up social -> CONTACT_MESSAGE_RECEIVED (Testcontainers)")
class SocialFollowUpEventIntegrationTest {

    private static final String ROLE = "cc_be154_social";
    private static final String SENDER = "48123456789";

    private static HikariDataSource superuserPool;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static SocialMessageService service;
    private static final SocialIntegrationRepository integrations = mock(SocialIntegrationRepository.class);
    private static final ContactService contactService = mock(ContactService.class);
    private static final WebSocketEventBroadcaster broadcaster = mock(WebSocketEventBroadcaster.class);
    private static final SocialDefaultQueueResolver queueResolver = mock(SocialDefaultQueueResolver.class);

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private UUID tenantId;
    private UUID agentId;
    private UUID contactId;

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, ROLE);
        restrictedPool = PostgresTestDatabase.pool(ROLE, password, 4);
        ctx = JpaTestContext.create(restrictedPool, new Class<?>[]{SocialMessage.class},
                new Class<?>[]{SocialMessageRepository.class, NoOpConfig.class},
                c -> c.registerBean("socialMessageService", SocialMessageServiceImpl.class,
                        () -> new SocialMessageServiceImpl(integrations, c.getBean(SocialMessageRepository.class),
                                contactService, mock(SocialAdapterRegistry.class), mock(RabbitTemplate.class),
                                queueResolver, new ContactMessageReceivedPublisher(broadcaster))));
        service = ctx.getBean(SocialMessageService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        restrictedPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        reset(broadcaster, contactService, integrations, queueResolver);
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-154 social " + UUID.randomUUID());
        agentId = UUID.randomUUID();
        contactId = UUID.randomUUID();
        SocialIntegration integration = SocialIntegration.builder().integrationId(null).tenantId(tenantId)
                .platform(SocialPlatform.WHATSAPP).pageId("page-1").build();
        when(integrations.findByPlatformAndPageId(SocialPlatform.WHATSAPP, "page-1"))
                .thenReturn(Optional.of(integration));
        when(queueResolver.resolve(tenantId)).thenReturn(Optional.empty());
    }

    private Contact existing(UUID agent) {
        return Contact.builder().contactId(contactId).tenantId(tenantId).channel("SOCIAL_WHATSAPP")
                .status("ACTIVE").agentId(agent).build();
    }

    @Test
    @DisplayName("follow-up w kontakcie ASSIGNED i ON_HOLD (z agentem) dolacza i publikuje zdarzenie")
    void assignedAndOnHoldFollowUpPublishes() {
        for (String status : new String[]{"ASSIGNED", "ON_HOLD"}) {
            reset(broadcaster);
            Contact c = existing(agentId);
            c.setStatus(status);
            when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP")).thenReturn(Optional.of(c));

            service.processIncomingMessage(incoming("wamid-" + UUID.randomUUID(), Instant.parse("2026-10-15T10:00:00Z")));

            verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
        }
    }

    private IncomingSocialMessage incoming(String externalId, Instant sentAt) {
        return new IncomingSocialMessage(SocialPlatform.WHATSAPP, "page-1", SENDER, externalId,
                "Tajna tresc wiadomosci", null, sentAt);
    }

    @Test
    @DisplayName("kolejna wiadomosc w kontakcie z agentem: dokladnie 1 zdarzenie do tego agenta, payload bez tresci, po commit")
    void followUpPublishesExactlyOneEventToAssignedAgent() throws Exception {
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP"))
                .thenReturn(Optional.of(existing(agentId)));
        String externalId = "wamid-" + UUID.randomUUID();

        service.processIncomingMessage(incoming(externalId, Instant.parse("2026-10-15T10:00:00Z")));

        UUID messageId = jdbc.queryForObject(
                "SELECT message_id FROM social_message WHERE external_message_id = ? AND tenant_id = ?",
                UUID.class, externalId, tenantId);
        ArgumentCaptor<WebSocketEvent> captor = ArgumentCaptor.forClass(WebSocketEvent.class);
        verify(broadcaster, times(1)).sendToUser(eq(agentId), captor.capture());
        verify(broadcaster, never()).sendToTenantSupervisors(any(), any());
        verify(broadcaster, never()).sendToTenantAgents(any(), any());

        WebSocketEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("CONTACT_MESSAGE_RECEIVED");
        assertThat(event.tenantId()).isEqualTo(tenantId);
        WebSocketEvent.ContactMessageReceivedPayload p = (WebSocketEvent.ContactMessageReceivedPayload) event.payload();
        assertThat(p.contactId()).isEqualTo(contactId.toString());
        assertThat(p.channel()).as("SOCIAL_WHATSAPP zwiniete do SOCIAL").isEqualTo("SOCIAL");
        assertThat(p.messageId()).isEqualTo(messageId.toString());
        assertThat(p.receivedAt()).isNotNull();

        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(p);
        assertThat(json).doesNotContain("Tajna").doesNotContain(SENDER);
        assertThat(new ObjectMapper().readTree(json).fieldNames())
                .toIterable().containsExactlyInAnyOrder("contactId", "channel", "messageId", "receivedAt");
    }

    @Test
    @DisplayName("pierwsza wiadomosc (nowy kontakt): brak zdarzenia, sciezka routingu bez zmian")
    void firstMessagePublishesNothing() {
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP")).thenReturn(Optional.empty());
        when(contactService.insertContact(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processIncomingMessage(incoming("wamid-" + UUID.randomUUID(), Instant.parse("2026-10-15T10:00:00Z")));

        verifyNoInteractions(broadcaster);
    }

    @Test
    @DisplayName("kontakt bez agenta: brak zdarzenia, wiadomosc zapisana")
    void contactWithoutAgentPublishesNothing() {
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP"))
                .thenReturn(Optional.of(existing(null)));
        String externalId = "wamid-" + UUID.randomUUID();

        service.processIncomingMessage(incoming(externalId, Instant.parse("2026-10-15T10:00:00Z")));

        verifyNoInteractions(broadcaster);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM social_message WHERE external_message_id = ?",
                Integer.class, externalId)).isEqualTo(1);
    }

    @Test
    @DisplayName("duplikat (ten sam external id): brak drugiego zdarzenia")
    void duplicateDoesNotPublishAgain() {
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP"))
                .thenReturn(Optional.of(existing(agentId)));
        String externalId = "wamid-" + UUID.randomUUID();
        IncomingSocialMessage msg = incoming(externalId, Instant.parse("2026-10-15T10:00:00Z"));

        service.processIncomingMessage(msg);
        service.processIncomingMessage(msg);

        verify(broadcaster, times(1)).sendToUser(eq(agentId), any());
    }

    @Test
    @DisplayName("izolacja tenantow: kontakt/agent innego tenanta nigdy nie dostaje zdarzenia")
    void otherTenantAgentNeverReceives() {
        UUID otherTenantAgent = UUID.randomUUID();
        // production lookup is tenant-scoped: the other tenant's contact is never returned for this tenant
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP"))
                .thenReturn(Optional.of(existing(agentId)));
        when(contactService.findActiveSocialContact(org.mockito.ArgumentMatchers.argThat(t -> !tenantId.equals(t)),
                any(), any())).thenReturn(Optional.of(existing(otherTenantAgent)));

        service.processIncomingMessage(incoming("wamid-" + UUID.randomUUID(), Instant.parse("2026-10-15T10:00:00Z")));

        verify(broadcaster).sendToUser(eq(agentId), any());
        verify(broadcaster, never()).sendToUser(eq(otherTenantAgent), any());
    }

    @Test
    @DisplayName("blad nadawania WS nie przerywa przyjecia wiadomosci")
    void broadcastFailureDoesNotFailIngestion() {
        when(contactService.findActiveSocialContact(tenantId, SENDER, "SOCIAL_WHATSAPP"))
                .thenReturn(Optional.of(existing(agentId)));
        doThrow(new RuntimeException("ws down")).when(broadcaster).sendToUser(any(), any());
        String externalId = "wamid-" + UUID.randomUUID();

        service.processIncomingMessage(incoming(externalId, Instant.parse("2026-10-15T10:00:00Z")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM social_message WHERE external_message_id = ?",
                Integer.class, externalId)).isEqualTo(1);
    }
}
