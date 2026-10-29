package com.contactcenter.domain.contact;

import com.contactcenter.api.contact.dto.ContactResponse;
import com.contactcenter.domain.disposition.CustomDispositionService;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.social.SocialDefaultQueueResolver;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-160: {@link ContactService#releaseContact} - every channel / state / ownership branch. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ContactService.releaseContact - zwrot kontaktu asynchronicznego do kolejki (BE-160)")
class ContactServiceReleaseTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTACT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AGENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_AGENT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID QUEUE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID SOCIAL_QUEUE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Mock private ContactRepository contactRepository;
    @Mock private QueueService queueService;
    @Mock private CustomDispositionService customDispositionService;
    @Mock private ContactEventService contactEventService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private com.contactcenter.domain.routing.ReleaseExclusionStore releaseExclusionStore;
    @Mock private UserService userService;
    @Mock private SocialDefaultQueueResolver socialDefaultQueueResolver;

    @InjectMocks
    private ContactServiceImpl contactService;

    @BeforeEach
    void setUp() {
        contactService.setUserService(userService);
        contactService.setSocialDefaultQueueResolver(socialDefaultQueueResolver);
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(AGENT_ID);
        TenantContext.setUserRole("AGENT");
        when(customDispositionService.findLabel(any(), any(), any(), any())).thenReturn(Optional.empty());
        Queue queue = Queue.builder().queueId(QUEUE_ID).tenantId(TENANT_ID).name("Email queue").build();
        when(queueService.findQueueEntity(QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(queue));
        when(queueService.findQueueEntity(SOCIAL_QUEUE_ID, TENANT_ID)).thenReturn(Optional.of(
                Queue.builder().queueId(SOCIAL_QUEUE_ID).tenantId(TENANT_ID).name("Social").build()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Contact contact(String channel, String status, UUID agentId, UUID queueId) {
        Instant now = Instant.now();
        return Contact.builder().contactId(CONTACT_ID).tenantId(TENANT_ID).agentId(agentId).queueId(queueId)
                .channel(channel).direction("INBOUND").status(status).remoteAddress("a@b.pl")
                .queuedAt(now).startedAt(now).channelMetadata(new HashMap<>()).createdAt(now).build();
    }

    /** First findById call returns {@code before}; subsequent calls (response build) return {@code after}. */
    private void givenContact(Contact only) {
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(only));
    }

    private void givenContact(Contact before, Contact after) {
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(before), Optional.of(after));
    }

    @ParameterizedTest(name = "{0} {1} -> QUEUED, zdarzenia i contact.queued")
    @ValueSource(strings = {"EMAIL:ASSIGNED", "EMAIL:ACTIVE", "CHAT:ASSIGNED", "CHAT:ACTIVE",
            "SOCIAL_FACEBOOK:ACTIVE", "SOCIAL_INSTAGRAM:ASSIGNED", "SOCIAL_WHATSAPP:ACTIVE"})
    @DisplayName("kanal async w stanie agenta: warunkowy UPDATE, zamkniecie AGENT, otwarcie QUEUE, publikacja")
    void releasesAsyncContactHeldByAgent(String channelAndStatus) {
        String[] parts = channelAndStatus.split(":");
        givenContact(contact(parts[0], parts[1], AGENT_ID, QUEUE_ID), contact(parts[0], "QUEUED", null, QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID), any(Instant.class))).thenReturn(1);

        ContactResponse response = contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.agentId()).isNull();
        InOrder order = inOrder(contactEventService);
        order.verify(contactEventService).closeQueueIfOpen(eq(CONTACT_ID), eq(TENANT_ID), any(Instant.class));
        order.verify(contactEventService).closeAgentIfOpen(CONTACT_ID, TENANT_ID);
        order.verify(contactEventService).openQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(QUEUE_ID),
                eq("Email queue"), any(Instant.class));
        ArgumentCaptor<Object> msg = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"), msg.capture());
        assertThat((ContactQueuedMessage) msg.getValue())
                .isEqualTo(new ContactQueuedMessage(CONTACT_ID, QUEUE_ID, TENANT_ID));
    }

    @Test
    @DisplayName("social bez queue_id: kolejka z SocialDefaultQueueResolver trafia do UPDATE i do eventu")
    void socialWithoutQueueUsesDefaultResolver() {
        givenContact(contact("SOCIAL_FACEBOOK", "ASSIGNED", AGENT_ID, null),
                contact("SOCIAL_FACEBOOK", "QUEUED", null, SOCIAL_QUEUE_ID));
        when(socialDefaultQueueResolver.resolve(TENANT_ID)).thenReturn(Optional.of(SOCIAL_QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(SOCIAL_QUEUE_ID), any(Instant.class))).thenReturn(1);

        contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        verify(contactRepository).releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(SOCIAL_QUEUE_ID), any(Instant.class));
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"),
                eq(new ContactQueuedMessage(CONTACT_ID, SOCIAL_QUEUE_ID, TENANT_ID)));
    }

    @Test
    @DisplayName("social bez queue_id i bez domyslnej kolejki tenanta -> 409, zadnych zmian")
    void socialWithoutAnyQueueIsConflict() {
        givenContact(contact("SOCIAL_FACEBOOK", "ASSIGNED", AGENT_ID, null));
        when(socialDefaultQueueResolver.resolve(TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verifyNothingChanged();
    }

    @Test
    @DisplayName("email bez queue_id -> 409 (resolver dotyczy tylko social)")
    void emailWithoutQueueIsConflict() {
        givenContact(contact("EMAIL", "ASSIGNED", AGENT_ID, null));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(socialDefaultQueueResolver, never()).resolve(any());
        verifyNothingChanged();
    }

    @ParameterizedTest(name = "kanal {0} -> 409")
    @ValueSource(strings = {"PHONE", "VOICEBOT", "UNKNOWN"})
    @DisplayName("kanaly nieasynchroniczne sa odrzucane")
    void rejectsNonAsyncChannel(String channel) {
        givenContact(contact(channel, "ACTIVE", AGENT_ID, QUEUE_ID));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verifyNothingChanged();
    }

    @Test
    @DisplayName("kanal null (legacy = PHONE) -> 409")
    void rejectsNullChannel() {
        givenContact(contact(null, "ACTIVE", AGENT_ID, QUEUE_ID));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verifyNothingChanged();
    }

    @ParameterizedTest(name = "status {0} -> 409")
    @ValueSource(strings = {"COMPLETED", "ABANDONED", "ERROR", "NOT_REACHED", "TRANSFERRED", "ON_HOLD", "IVR"})
    @DisplayName("stany koncowe i inne niz ASSIGNED/ACTIVE sa odrzucane")
    void rejectsNonAgentHeldStatus(String status) {
        givenContact(contact("EMAIL", status, AGENT_ID, QUEUE_ID));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verifyNothingChanged();
    }

    @Test
    @DisplayName("kontakt innego agenta -> 409")
    void rejectsOtherAgentsContact() {
        givenContact(contact("EMAIL", "ACTIVE", OTHER_AGENT, QUEUE_ID));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("innego agenta");
        verifyNothingChanged();
    }

    @Test
    @DisplayName("QUEUED z innym agentem (np. outbound) -> 409, nie idempotencja")
    void queuedWithAgentIsConflict() {
        givenContact(contact("EMAIL", "QUEUED", OTHER_AGENT, QUEUE_ID));

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verifyNothingChanged();
    }

    @Test
    @DisplayName("idempotencja: QUEUED bez agenta -> 200, bez UPDATE, zdarzen i publikacji")
    void alreadyQueuedIsIdempotent() {
        Contact queued = contact("EMAIL", "QUEUED", null, QUEUE_ID);
        givenContact(queued, queued);

        ContactResponse response = contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        assertThat(response.status()).isEqualTo("QUEUED");
        verifyNothingChanged();
    }

    @Test
    @DisplayName("brak kontaktu -> EntityNotFoundException (404)")
    void notFound() {
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNothingChanged();
    }

    @Test
    @DisplayName("wyscig: UPDATE 0 wierszy, kontakt juz QUEUED bez agenta -> 200 idempotentnie, bez zdarzen")
    void lostRaceToAnotherReleaseIsIdempotent() {
        Contact queued = contact("EMAIL", "QUEUED", null, QUEUE_ID);
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(
                Optional.of(contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID)), Optional.of(queued));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID), any(Instant.class))).thenReturn(0);

        ContactResponse response = contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        assertThat(response.status()).isEqualTo("QUEUED");
        verify(contactEventService, never()).openQueue(any(), any(), any(), any(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("wyscig: UPDATE 0 wierszy, kontakt zmienil stan (np. zakonczony) -> 409")
    void lostRaceToTerminalIsConflict() {
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(
                Optional.of(contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID)),
                Optional.of(contact("EMAIL", "COMPLETED", AGENT_ID, QUEUE_ID)));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID), any(Instant.class))).thenReturn(0);

        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(contactEventService, never()).openQueue(any(), any(), any(), any(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("w aktywnej transakcji contact.queued idzie dopiero po commicie")
    void publishesOnlyAfterCommit() {
        givenContact(contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID), contact("EMAIL", "QUEUED", null, QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID), any(Instant.class))).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCommit();
            verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"),
                    any(ContactQueuedMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("blad brokera przy publikacji nie cofa zwrotu (wiersz juz QUEUED)")
    void brokerFailureDoesNotFailRelease() {
        givenContact(contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID), contact("EMAIL", "QUEUED", null, QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID), any(Instant.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        ContactResponse response = contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        assertThat(response.status()).isEqualTo("QUEUED");
    }

    private void verifyNothingChanged() {
        verify(contactRepository, never()).releaseToQueue(any(), any(), any(), any(), any());
        verify(contactRepository, never()).update(any());
        verify(contactEventService, never()).closeAgentIfOpen(any(), any());
        verify(contactEventService, never()).closeQueueIfOpen(any(), any(), any());
        verify(contactEventService, never()).openQueue(any(), any(), any(), any(), any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    // ---- BE-160 review: stage times, queued_at, conditional accept, guards for unowned QUEUED async ----

    @Test
    @DisplayName("QUEUE zamykany z ended_at = assigned_at, a queued_at i nowy etap QUEUE dostaja ten sam Instant")
    void closesOriginalQueueStageAtAssignedAtAndSharesInstant() {
        Instant assignedAt = Instant.parse("2026-10-29T08:00:00Z");
        Contact held = contact("EMAIL", "ASSIGNED", AGENT_ID, QUEUE_ID);
        held.setAssignedAt(assignedAt);
        givenContact(held, contact("EMAIL", "QUEUED", null, QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID),
                any(Instant.class))).thenReturn(1);

        contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        ArgumentCaptor<Instant> queuedAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> openedAt = ArgumentCaptor.forClass(Instant.class);
        verify(contactRepository).releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID),
                queuedAt.capture());
        verify(contactEventService).closeQueueIfOpen(CONTACT_ID, TENANT_ID, assignedAt);
        verify(contactEventService).openQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(QUEUE_ID), eq("Email queue"),
                openedAt.capture());
        assertThat(openedAt.getValue()).isEqualTo(queuedAt.getValue());
        // old WARN-producing variants are not used for async
        verify(contactEventService, never()).closeAgent(any(), any());
        verify(contactEventService, never()).closeQueue(any(), any());
    }

    @Test
    @DisplayName("accept: warunkowy UPDATE (ASSIGNED + wlasciciel) -> ACTIVE, bez update() calej encji")
    void acceptUsesConditionalUpdate() {
        givenContact(contact("EMAIL", "ASSIGNED", AGENT_ID, QUEUE_ID), contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID));
        when(contactRepository.acceptIfAssigned(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(1);

        ContactResponse response = contactService.acceptContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(contactRepository, never()).update(any());
    }

    @Test
    @DisplayName("accept przegrywa z release (UPDATE 0 wierszy, kontakt QUEUED) -> 409, nic nie nadpisane")
    void acceptLosingToReleaseIsConflict() {
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(
                Optional.of(contact("EMAIL", "ASSIGNED", AGENT_ID, QUEUE_ID)),
                Optional.of(contact("EMAIL", "QUEUED", null, QUEUE_ID)));
        when(contactRepository.acceptIfAssigned(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(0);

        assertThatThrownBy(() -> contactService.acceptContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        verify(contactRepository, never()).update(any());
    }

    @Test
    @DisplayName("accept: UPDATE 0 wierszy, ale kontakt juz ACTIVE u tego agenta -> 200 idempotentnie")
    void acceptLostRaceToOwnAcceptIsIdempotent() {
        Contact active = contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID);
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(
                Optional.of(contact("EMAIL", "ASSIGNED", AGENT_ID, QUEUE_ID)), Optional.of(active));
        when(contactRepository.acceptIfAssigned(CONTACT_ID, TENANT_ID, AGENT_ID)).thenReturn(0);

        assertThat(contactService.acceptContact(CONTACT_ID, TENANT_ID, AGENT_ID).status()).isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "kanal {0}: AGENT nie moze abandon/update/disposition na QUEUED bez agenta")
    @ValueSource(strings = {"EMAIL", "SOCIAL_FACEBOOK", "SOCIAL_WHATSAPP"})
    @DisplayName("AGENT na kontakcie async QUEUED bez agenta -> 409 w abandon, updateContact, setDisposition")
    void agentCannotTouchUnownedQueuedAsyncContact(String channel) {
        givenContact(contact(channel, "QUEUED", null, QUEUE_ID));
        com.contactcenter.api.contact.dto.UpdateContactRequest patch =
                new com.contactcenter.api.contact.dto.UpdateContactRequest(null, "COMPLETED", null, null, null, null);
        com.contactcenter.api.contact.dto.DispositionRequest disposition =
                new com.contactcenter.api.contact.dto.DispositionRequest("RESOLVED", null);

        assertThatThrownBy(() -> contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);
        assertThatThrownBy(() -> contactService.updateContact(CONTACT_ID, patch, TENANT_ID, AGENT_ID, true))
                .isInstanceOf(InvalidOperationException.class);
        assertThatThrownBy(() -> contactService.setDisposition(CONTACT_ID, disposition, TENANT_ID, AGENT_ID, true))
                .isInstanceOf(InvalidOperationException.class);
        verify(contactRepository, never()).update(any());
        verify(contactEventService, never()).closeQueue(any(), any());
    }

    @Test
    @DisplayName("SUPERVISOR/ADMIN: abandon, updateContact i setDisposition na QUEUED async bez agenta bez zmian (nie blokowane)")
    void supervisorBehaviourUnchanged() {
        Contact queued = contact("EMAIL", "QUEUED", null, QUEUE_ID);
        givenContact(queued, queued);
        when(contactRepository.update(any())).thenReturn(1);
        TenantContext.setUserRole("SUPERVISOR");

        // abandon: terminal ABANDONED written
        contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID);
        verify(contactRepository).update(any());

        // updateContact (isAgent=false) passes the guard
        when(contactRepository.findById(CONTACT_ID, TENANT_ID)).thenReturn(Optional.of(queued));
        contactService.updateContact(CONTACT_ID, new com.contactcenter.api.contact.dto.UpdateContactRequest(
                null, null, null, null, "x@y.pl", null), TENANT_ID, AGENT_ID, false);
    }

    @Test
    @DisplayName("PHONE QUEUED bez agenta: AGENT nadal moze abandon (guard dotyczy tylko async)")
    void phoneUnaffectedByGuard() {
        Contact phone = contact("PHONE", "QUEUED", null, QUEUE_ID);
        givenContact(phone, phone);
        when(contactRepository.update(any())).thenReturn(1);

        contactService.abandonContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        verify(contactRepository).update(any());
    }

    @Test
    @DisplayName("udany zwrot zapisuje wykluczenie zwracajacego agenta (anti-ping-pong) przed publikacja")
    void recordsExclusionOfReleasingAgent() {
        givenContact(contact("EMAIL", "ACTIVE", AGENT_ID, QUEUE_ID), contact("EMAIL", "QUEUED", null, QUEUE_ID));
        when(contactRepository.releaseToQueue(eq(CONTACT_ID), eq(TENANT_ID), eq(AGENT_ID), eq(QUEUE_ID),
                any(Instant.class))).thenReturn(1);

        contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        InOrder order = inOrder(releaseExclusionStore, rabbitTemplate);
        order.verify(releaseExclusionStore).exclude(TENANT_ID, CONTACT_ID, AGENT_ID);
        order.verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE_EVENTS), eq("contact.queued"),
                any(Object.class));
    }

    @Test
    @DisplayName("idempotentny no-op i odrzucone zwroty NIE zapisuja wykluczenia")
    void noExclusionWithoutTransition() {
        Contact queued = contact("EMAIL", "QUEUED", null, QUEUE_ID);
        givenContact(queued, queued);
        contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID);

        givenContact(contact("PHONE", "ACTIVE", AGENT_ID, QUEUE_ID));
        assertThatThrownBy(() -> contactService.releaseContact(CONTACT_ID, TENANT_ID, AGENT_ID))
                .isInstanceOf(InvalidOperationException.class);

        verify(releaseExclusionStore, never()).exclude(any(), any(), any());
    }
}
