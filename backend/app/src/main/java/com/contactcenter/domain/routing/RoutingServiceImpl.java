package com.contactcenter.domain.routing;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AgentAvailabilityRefreshedEvent;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.contact.AssignmentResult;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.social.SocialDefaultQueueResolver;
import com.contactcenter.domain.contact.ContactChannels;
import com.contactcenter.domain.queue.Queue;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.contact.QueuedContactView;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.queue.QueueAssignmentService;
import com.contactcenter.domain.queue.QueueService;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementacja {@link RoutingService}.
 *
 * <p>Metoda {@link #routeContact} jest synchroniczna – wywołanie z {@link RabbitListener}
 * pozwala Spring AMQP poprawnie obsłużyć wyjątki (NACK / DLQ) przy błędzie routingu.
 *
 * <p>Retry dla oczekujących kontaktów (brak agentów) jest wyzwalany przez event
 * {@code agent.status.changed} zamiast ponownej publikacji {@code contact.queued},
 * co eliminuje ryzyko nieskończonej pętli.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RoutingServiceImpl implements RoutingService {

    private static final String RK_CONTACT_ASSIGNED = "contact.assigned";

    private final RoutingEngine routingEngine;
    private final QueueService queueService;
    private final CustomerService customerService;
    private final RabbitTemplate rabbitTemplate;
    private final QueueAssignmentService queueAssignmentService;
    private final UserService userService;
    private final WebSocketEventBroadcaster broadcaster;
    private final ContactService contactService;
    private final ContactEventService contactEventService;
    private final SocialDefaultQueueResolver socialDefaultQueueResolver;
    private final ReleaseExclusionStore releaseExclusionStore;

    /** Upper bound of engine re-asks within one routeContact when chosen agents turn out to be at their limit. */
    private static final int MAX_ROUTING_ATTEMPTS = 10;

    // =========================================================================
    // Główna metoda routingu
    // =========================================================================

    /**
     * <strong>Transaction note:</strong> every caller invokes this method from inside this class (self-invocation),
     * so the {@code @Transactional} below is inert in practice - which is what we rely on: the async limit lock in
     * {@code ContactRepository#assignIfQueued} must live in the SHORT transaction of
     * {@code ContactService#assignQueuedContact}, not for the whole retry loop (agent locks taken in different
     * orders by two passes could deadlock). Do not call this through the proxy from another bean.
     */
    @Override
    @Transactional
    public Optional<UUID> routeContact(UUID contactId, UUID queueId, UUID tenantId) {
        log.info("[RoutingService] Routing kontaktu: contactId={}, queueId={}, tenantId={}",
                contactId, queueId, tenantId);

        // 1. Pobierz kolejkę
        Queue queue = queueService.findQueueEntity(queueId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kolejka nie istnieje: queueId=" + queueId + ", tenantId=" + tenantId));

        // 2. Pobierz kontakt
        Optional<Contact> contactOpt = contactService.findContactEntity(contactId, tenantId);
        if (contactOpt.isEmpty()) {
            log.error("[RoutingService] Kontakt {} nie istnieje w DB – pomijam routing (tenantId={}). " +
                      "Sprawdź czy TwilioTelephonyAdapter tworzy rekord contact przy webhook.",
                      contactId, tenantId);
            return Optional.empty();
        }
        Contact contact = contactOpt.get();

        // 3. Wyznacz listę uprawnionych agentów dla kolejki (jedno zapytanie DB)
        // all_agents=TRUE → eligibleAgentIds=null (brak filtru, wszyscy agenci tenanta)
        // all_agents=FALSE → pobierz UNION bezpośrednich agentów + agentów przez grupy
        Set<UUID> eligibleAgentIds = null;
        if (!queueAssignmentService.isAllAgents(queue.getQueueId(), tenantId)) {
            eligibleAgentIds = queueAssignmentService.resolveEligibleAgentIds(
                    queue.getQueueId(), tenantId);
            log.debug("[RoutingService] Kolejka {} ma all_agents=FALSE, uprawnionych agentów: {}",
                    queueId, eligibleAgentIds.size());
        }

        // 4. Zbuduj RoutingRequest
        // BE-160 anti-ping-pong: agents who just returned this async contact to the queue are skipped.
        // Phone contacts are never excluded, so the phone path does not even touch Redis.
        Set<UUID> excludedAgentIds = ContactChannels.isAsync(contact.getChannel())
                ? releaseExclusionStore.excludedAgents(tenantId, contactId)
                : Set.of();
        RoutingRequest request = RoutingRequest.of(contact, queue, tenantId, eligibleAgentIds, excludedAgentIds);

        // 5. Wywołaj silnik routingu. BE-156: the engine already skips agents at their async limit, but the
        // limit is re-checked atomically at write time; an agent that filled up in between is excluded for
        // this pass and the engine is asked again (each round excludes one more agent, so it terminates).
        Optional<RoutingResult> result = routingEngine.findBestAgent(request);
        for (int attempt = 0; attempt < MAX_ROUTING_ATTEMPTS; attempt++) {
            if (result.isEmpty()) {
                break;
            }
            RoutingResult routing = result.get();
            UUID agentId = routing.agentId();

            log.info("[RoutingService] Agent znaleziony: contactId={}, agentId={}, strategy={}",
                    contactId, agentId, routing.strategy());

            // 6a. Conditional assignment (BE-159): WHERE status='QUEUED' AND agent_id IS NULL, plus the atomic
            // async limit (BE-156). Anything but ASSIGNED: no contact.assigned, the agent stays free.
            AssignmentResult assignment = assignContactToAgent(contact, agentId, tenantId,
                    queueId, request.maxConcurrentAsyncPerAgent());
            if (assignment == AssignmentResult.AGENT_AT_LIMIT) {
                Set<UUID> excluded = new HashSet<>(request.excludedAgentIds());
                excluded.add(agentId);
                request = request.withExcludedAgents(excluded);
                result = routingEngine.findBestAgent(request);
                continue;
            }
            if (assignment != AssignmentResult.ASSIGNED) {
                return Optional.empty(); // CONTACT_UNAVAILABLE: a parallel path took the contact
            }

            // 6b. Opublikuj event contact.assigned (only after the successful conditional write)
            publishAssignedEvent(contactId, agentId, queueId, tenantId, routing.strategy(), contact, queue.getName());

            return Optional.of(agentId);
        }

        log.info("[RoutingService] Brak dostępnych agentów (zajęci / na limicie / wykluczeni), kontakt czeka: " +
                "contactId={}, queueId={}", contactId, queueId);

        // Kontakt pozostaje w statusie QUEUED w DB.
        // NIE publikujemy contact.queued – to spowodowałoby nieskończoną pętlę
        // (onContactQueued → routeContact → brak agentów → contact.queued → ...).
        // Retry nastąpi przy evencie agent.status.changed, po zwolnieniu slotu async (BE-156)
        // albo w pollAvailableAgents (patrz onAgentStatusChanged).
        return Optional.empty();
    }

    // =========================================================================
    // RabbitMQ Listener – pierwotny routing nowego kontaktu
    // =========================================================================

    /**
     * Nasłuchuje eventów {@code contact.queued} i próbuje przydzielić agenta.
     *
     * <p>Wywołuje {@link #routeContact} synchronicznie – wyjątek propaguje się do Spring AMQP,
     * który ponowi próbę i po wyczerpaniu prób wyśle do DLQ (konfiguracja w {@link RabbitMQConfig}).
     *
     * @param event event z danymi kontaktu do routowania
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_CONTACT_ROUTING)
    public void onContactQueued(ContactQueuedMessage event) {
        log.debug("[RoutingService] Odebrano event contact.queued: contactId={}, queueId={}",
                event.contactId(), event.queueId());

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(
                event.tenantId(), null, null, "SYSTEM");
        TenantContext.restore(snapshot);
        try {
            routeContact(event.contactId(), event.queueId(), event.tenantId());
        } catch (Exception e) {
            log.error("[RoutingService] Błąd podczas routingu kontaktu z eventu RabbitMQ: " +
                    "contactId={}, error={}", event.contactId(), e.getMessage(), e);
            // Rzucamy wyjątek – Spring AMQP ponowi próbę, a po wyczerpaniu prób wyśle do DLQ
            throw new RuntimeException("Routing kontaktu " + event.contactId() + " zakończony błędem", e);
        } finally {
            broadcastQueueStateToAgents(event.tenantId());
            TenantContext.clear();
        }
    }

    // =========================================================================
    // RabbitMQ Listener – retry routingu po zmianie statusu agenta
    // =========================================================================

    /**
     * Nasłuchuje eventów {@code agent.status.changed} i próbuje routować oczekujące kontakty
     * gdy agent staje się AVAILABLE.
     *
     * <p>To jest właściwy mechanizm retry dla kontaktów bez agenta (brak nieskończonej pętli):
     * kontakt pozostaje w statusie QUEUED w DB, a po zmianie statusu agenta na AVAILABLE
     * serwis pobiera wszystkie kontakty QUEUED dla danego tenanta i próbuje je przydzielić.
     *
     * <p>Metoda ignoruje eventy gdy nowy status agenta nie jest AVAILABLE (optymalizacja –
     * routing nie ma sensu gdy agent staje się BUSY/BREAK/AFTER_CONTACT).
     *
     * @param event event zmiany statusu agenta
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_AGENT_STATUS)
    public void onAgentStatusChanged(AgentStatusChangedEvent event) {
        // Interesuje nas tylko przejście do statusu AVAILABLE
        if (event.newStatus() != UserStatus.AVAILABLE) {
            log.debug("[RoutingService] Ignoruję zmianę statusu agenta na {}: agentId={}",
                    event.newStatus(), event.userId());
            return;
        }

        retryQueuedContacts(event.tenantId(), event.userId());
    }

    /**
     * BE-166: ponowny zapis AVAILABLE przez agenta, który już jest AVAILABLE, nie publikuje
     * {@code agent.status.changed} (brak eventu WS ani ruszania {@code status_changed_at}), ale nadal
     * ma uruchomić retry routingu kontaktów QUEUED (jedyny mechanizm retry dla inbound/social/e-mail).
     * Dlatego {@code UserService} publikuje wewnętrzny {@link AgentAvailabilityRefreshedEvent}
     * (bez zależności beana user → routing, która tworzyłaby cykl). {@code @Async} - nie blokuje żądania HTTP;
     * kontekst tenanta ustawiany i czyszczony na wątku roboczym.
     */
    @Async
    @EventListener
    public void onAgentAvailabilityRefreshed(AgentAvailabilityRefreshedEvent event) {
        retryQueuedContacts(event.tenantId(), event.userId());
    }

    /**
     * Tenants with a backlog pass running right now (single JVM; multi-instance coordination is BE-172, EPIC-33).
     */
    private final Set<UUID> passesRunning = ConcurrentHashMap.newKeySet();

    /** Tenants that requested another pass while one was running: at most ONE follow-up pass is run for them. */
    private final Set<UUID> passesDirty = ConcurrentHashMap.newKeySet();

    /**
     * One backlog pass per tenant at a time, with coalescing (BE-156 review): a request that arrives while a pass is
     * running only marks the tenant "dirty" and returns; the running thread then runs ONE more pass. This bounds
     * the load when many contacts close at once (each close asks for a pass) or when the 30 s poll overlaps
     * with event-driven passes. Exceptions propagate to the caller of the pass that threw (cleanup in finally).
     */
    private void retryQueuedContacts(UUID tenantId, UUID availableAgentId) {
        while (true) {
            if (!passesRunning.add(tenantId)) {
                passesDirty.add(tenantId);
                log.debug("[RoutingService] Przebieg dla tenanta {} w toku – zlecono kolejny (koalescencja)", tenantId);
                return;
            }
            try {
                do {
                    passesDirty.remove(tenantId);
                    runRetryPass(tenantId, availableAgentId);
                } while (passesDirty.contains(tenantId));
            } finally {
                passesRunning.remove(tenantId);
            }
            // A request may have marked the tenant dirty between the loop check and the removal above
            if (!passesDirty.contains(tenantId)) {
                return;
            }
        }
    }

    private void runRetryPass(UUID tenantId, UUID availableAgentId) {
        log.info("[RoutingService] Agent AVAILABLE, próbuję routować oczekujące kontakty: " +
                "agentId={}, tenantId={}", availableAgentId, tenantId);

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(
                tenantId, availableAgentId, null, "SYSTEM");
        TenantContext.restore(snapshot);
        try {
            List<Contact> queuedContacts = contactService.findQueuedContacts(tenantId);

            if (queuedContacts.isEmpty()) {
                log.debug("[RoutingService] Brak oczekujących kontaktów dla tenanta: {}", tenantId);
                return;
            }

            log.info("[RoutingService] Znaleziono {} oczekujących kontaktów dla tenanta: {}",
                    queuedContacts.size(), tenantId);

            // BE-155: the default social queue is resolved at most ONCE per pass (2 queries + one WARN),
            // and only when some social contact actually lacks a queue.
            boolean needsSocialQueue = queuedContacts.stream()
                    .anyMatch(c -> c.getQueueId() == null && ContactChannels.isSocial(c.getChannel()));
            Optional<UUID> socialQueueId = needsSocialQueue
                    ? socialDefaultQueueResolver.resolve(tenantId)
                    : Optional.empty();

            boolean phoneAssigned = false;
            for (Contact contact : queuedContacts) {
                if (phoneAssigned && !ContactChannels.isAsync(contact.getChannel())) {
                    continue; // phone path: the agent that took a call is busy until the next trigger
                }
                // Kontakty OUTBOUND z kampanii mają agentId ustawiony przez dialer w momencie
                // inicjowania połączenia. RoutingService nie powinien ich dotykać – agent jest
                // już przypisany, a połączenie czeka na odebranie przez klienta.
                // Próba routingu skończyłaby się ERROR (brak queueId gdy kampania go nie ma)
                // lub duplikacją przypisania.
                if ("OUTBOUND".equals(contact.getDirection()) && contact.getAgentId() != null) {
                    log.debug("[RoutingService] Pomijam kontakt OUTBOUND z przypisanym agentem: " +
                            "contactId={}, agentId={}", contact.getContactId(), contact.getAgentId());
                    continue;
                }

                if (contact.getQueueId() == null && ContactChannels.isSocial(contact.getChannel())) {
                    // BE-155: social contact without a queue (created before the default social queue
                    // existed, or the tenant had no queue at that time) - resolve the tenant default
                    // instead of ending the contact with ERROR.
                    if (!assignDefaultSocialQueue(contact, tenantId, socialQueueId)) {
                        continue; // stays QUEUED, retried on the next pass; WARN logged by the resolver
                    }
                }

                if (contact.getQueueId() == null) {
                    log.warn("[RoutingService] Kontakt bez queueId – kończę ze statusem ERROR: contactId={}",
                            contact.getContactId());
                    try {
                        // BE-159: conditional (still QUEUED, no queue, no agent) - a concurrent pass that
                        // assigned the contact or resolved its queue wins
                        if (!contactService.markQueuedContactWithoutQueueAsError(contact, Instant.now())) {
                            log.info("[RoutingService] Kontakt bez queueId zmienił stan w międzyczasie – " +
                                    "pomijam ERROR: contactId={}", contact.getContactId());
                        }
                    } catch (Exception ex) {
                        log.error("[RoutingService] Błąd przy kończeniu kontaktu bez queueId: contactId={}, error={}",
                                contact.getContactId(), ex.getMessage());
                    }
                    continue;
                }

                try {
                    Optional<UUID> agentId = routeContact(
                            contact.getContactId(),
                            contact.getQueueId(),
                            tenantId
                    );

                    if (agentId.isPresent()) {
                        log.info("[RoutingService] Oczekujący kontakt przydzielony: contactId={}, agentId={}",
                                contact.getContactId(), agentId.get());
                        // Telefon: jeden agent = jeden kontakt naraz - kolejne kontakty TELEFONICZNE pomijamy, ale
                        // async leżące za nim w liście nadal przydzielamy (brak zagłodzenia, BE-156 review).
                        // Async (BE-156): agent zostaje AVAILABLE; kolejne kontakty przydzielamy dalej,
                        // a limit slotów egzekwuje silnik routingu i atomowy zapis.
                        if (!ContactChannels.isAsync(contact.getChannel())) {
                            phoneAssigned = true;
                        }
                    }
                } catch (Exception e) {
                    log.error("[RoutingService] Błąd routingu oczekującego kontaktu: contactId={}, error={}",
                            contact.getContactId(), e.getMessage(), e);
                    // Kontynuuj z kolejnym kontaktem – jeden błąd nie blokuje pozostałych
                }
            }
        } catch (Exception e) {
            log.error("[RoutingService] Błąd pobierania oczekujących kontaktów dla tenanta={}: error={}",
                    tenantId, e.getMessage(), e);
            throw new RuntimeException("Błąd retry routingu dla tenanta " + tenantId, e);
        } finally {
            broadcastQueueStateToAgents(tenantId);
            TenantContext.clear();
        }
    }

    // =========================================================================
    // RabbitMQ Listener – odświeżenie kolejki agenta po rozłączeniu klienta
    // =========================================================================

    /**
     * Nasłuchuje eventów {@code call.hangup} i odświeża stan kolejki w panelu agenta.
     *
     * <p>Gdy klient się rozłącza, kontakt znika z DB (status COMPLETED), ale panel agenta
     * nie jest aktualizowany aż do następnego eventu contact.queued lub agent.status.changed.
     * Ten listener naprawia lukę – po każdym hangup rozsyła aktualny stan kolejki do wszystkich
     * agentów danego tenanta.
     *
     * <p>Błędy są pochłaniane (nie rzucamy wyjątku) – nieudane odświeżenie UI to nie powód,
     * aby zatruwać kolejkę RabbitMQ (DLQ).
     *
     * @param event event zakończenia połączenia
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ROUTING_HANGUP)
    public void onCallHangup(CallEvent event) {
        UUID tenantId = event.getTenantId();
        log.info("[RoutingService] Odebrano call.hangup – odświeżam kolejkę agentów: " +
                "callId={}, contactId={}, tenantId={}",
                event.getCallId(), event.getContactId(), tenantId);

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(tenantId, null, null, "SYSTEM");
        TenantContext.restore(snapshot);
        try {
            broadcastQueueStateToAgents(tenantId);
        } catch (Exception e) {
            log.warn("[RoutingService] Błąd odświeżania kolejki agentów po hangup: " +
                    "callId={}, tenantId={}, error={}",
                    event.getCallId(), tenantId, e.getMessage());
            // Celowo nie rzucamy – nieudany broadcast UI nie powinien trafiać do DLQ
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // RabbitMQ Listener – bezpośrednie przypisanie agenta po transferze BLIND
    // =========================================================================

    /**
     * Nasłuchuje {@code DirectAgentAssignmentMessage} i przypisuje wskazanego agenta do kontaktu.
     *
     * <p>Wywoływany przez {@code TwilioTelephonyAdapter.transferToAgentViaConference()} po tym,
     * jak klient zostanie przeniesiony do nowej konferencji Twilio. Omija silnik routingu –
     * agent jest znany z góry (wybrany przez oryginalnego agenta podczas transferu).
     *
     * <p>Wiadomość ma TTL 30s – po wygaśnięciu trafia do DLQ. Błędy są pochłaniane
     * (nie rzucamy wyjątku), bo klient jest już w konferencji i ponowna próba bez kontekstu
     * nie ma sensu.
     *
     * @param message wiadomość z danymi przypisania
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_AGENT_DIRECT)
    public void onDirectAgentAssignment(DirectAgentAssignmentMessage message) {
        log.info("[RoutingService] Bezpośrednie przypisanie agenta po transferze: contactId={}, agentId={}",
                message.contactId(), message.agentId());

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(
                message.tenantId(), null, null, "SYSTEM");
        TenantContext.restore(snapshot);
        try {
            // Używamy assignContactToAgent (tylko zapis DB, status=ASSIGNED) zamiast
            // contactService.assignAgent(), które wywołuje openAgent() — etap AGENT
            // zostanie otwarty przez AgentCallController gdy agent fizycznie odbierze.
            // Unikamy w ten sposób podwójnego wpisu AGENT w historii kontaktu.
            Contact contact = contactService.findContactEntity(message.contactId(), message.tenantId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Kontakt nie istnieje: " + message.contactId()));

            // Since BE-159 review the telephony adapter inserts the transferred contact ALREADY ASSIGNED to the
            // target agent (no QUEUED window in which a routing pass could hand it to someone else). A QUEUED
            // contact (message from an older adapter / retried delivery) is still assigned here conditionally.
            boolean insertedAssigned = "ASSIGNED".equals(contact.getStatus())
                    && message.agentId().equals(contact.getAgentId());
            if (!insertedAssigned
                    && assignContactToAgent(contact, message.agentId(), message.tenantId(), null, null)
                    != AssignmentResult.ASSIGNED) {
                // BE-159: the contact was claimed by another path first (e.g. a routing pass picked the
                // freshly inserted QUEUED contact) - no contact.assigned, nothing else to do.
                log.warn("[RoutingService] Bezpośrednie przypisanie pominięte – kontakt nie jest już " +
                        "QUEUED bez agenta: contactId={}, agentId={}", message.contactId(), message.agentId());
                return;
            }
            // Nie wywołujemy closeQueue() — dla direct transfer do agenta etap QUEUE
            // nie jest tworzony (transferToAgentViaConference nie wywołuje openQueue()),
            // więc closeQueue() zawsze trafiałby w pustkę i generował WARN w logach.

            log.info("[RoutingService] Bezpośrednie przypisanie agenta zakończone sukcesem: " +
                    "contactId={}, agentId={}", message.contactId(), message.agentId());

            // Publikuj contact.assigned → RabbitToWebSocketRelay wyśle WS CONTACT_ASSIGNED
            // do softphone'u docelowego agenta, żeby wyświetlił powiadomienie o przychodzącym połączeniu.
            String channel = contact.getChannel() != null ? contact.getChannel() : "PHONE";
            String address = contact.getRemoteAddress() != null ? contact.getRemoteAddress() : "";
            String customerId = contact.getCustomerId() != null ? contact.getCustomerId().toString() : null;
            ContactAssignedEvent wsEvent = ContactAssignedEvent.of(
                    message.contactId(), message.agentId(),
                    message.queueId(), message.tenantId(),
                    "DIRECT_TRANSFER",
                    channel, address, address,
                    message.queueName(), customerId);
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, RK_CONTACT_ASSIGNED, wsEvent);
                log.info("[RoutingService] Event contact.assigned opublikowany dla transferu do agenta: " +
                        "contactId={}, agentId={}", message.contactId(), message.agentId());
            } catch (Exception ex) {
                log.error("[RoutingService] Błąd publikacji contact.assigned po transferze: " +
                        "contactId={}, error={}", message.contactId(), ex.getMessage());
            }
        } catch (Exception e) {
            log.error("[RoutingService] Błąd bezpośredniego przypisania agenta: " +
                    "contactId={}, agentId={}, error={}",
                    message.contactId(), message.agentId(), e.getMessage(), e);
            // Celowo nie rzucamy – klient jest już w konferencji, ponowna próba z DLQ byłaby błędna
        } finally {
            broadcastQueueStateToAgents(message.tenantId());
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Scheduled polling – uzupełnienie event-driven triggering
    // =========================================================================

    /**
     * Cyklicznie wyzwala routing dla wszystkich aktualnie AVAILABLE agentów.
     *
     * <p>Uzupełnienie event-driven triggeringu: obsługuje przypadki gdy kontakt
     * trafił do kolejki zanim agent zmienił status lub gdy event RabbitMQ zaginął.
     *
     * <p>Używa {@code fixedDelay} – kolejna iteracja startuje PO zakończeniu poprzedniej
     * (ochrona przed nakładaniem się przy wolnych tenantach).
     */
    @Scheduled(fixedDelayString = "${dialer.agent-poll-interval-ms:30000}")
    public void pollAvailableAgents() {
        List<AppUser> availableAgents = userService.findAvailableAgents();

        if (availableAgents.isEmpty()) {
            log.debug("[RoutingService] pollAvailableAgents: brak AVAILABLE agentów – pomijam");
            return;
        }

        log.debug("[RoutingService] pollAvailableAgents: sprawdzam {} AVAILABLE agentów",
                availableAgents.size());

        // BE-156 review: ONE backlog pass per tenant (not per agent). The pass lets the engine pick among all
        // AVAILABLE agents, so repeating it for every agent only multiplied the DB load (agents x backlog).
        Map<UUID, UUID> triggerAgentByTenant = new LinkedHashMap<>();
        for (AppUser agent : availableAgents) {
            triggerAgentByTenant.putIfAbsent(agent.getTenantId(), agent.getId());
        }
        triggerAgentByTenant.forEach((tenantId, agentId) -> {
            try {
                retryQueuedContacts(tenantId, agentId);
            } catch (Exception e) {
                log.warn("[RoutingService] pollAvailableAgents: błąd dla tenanta {}: {}", tenantId, e.getMessage());
            }
        });
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Aktualizuje kontakt – przypisuje agenta i ustawia status ASSIGNED.
     *
     * <p>Status ASSIGNED (nie ACTIVE) pozwala ContactAssignmentMonitor wykryć sytuację,
     * gdy CONTACT_ASSIGNED WebSocket event nie dotarł do agenta (utrata połączenia WS).
     * Przejście ASSIGNED → ACTIVE następuje dopiero gdy adapter telefoniczny wykona
     * faktyczne zestawienie połączenia audio (dialAgentIntoConference).
     *
     * <p>BE-159: zapis jest warunkowy ({@code WHERE status='QUEUED' AND agent_id IS NULL}); wynik inny niż
     * {@code ASSIGNED} oznacza brak przydziału i caller MUSI pominąć kontakt (bez efektów ubocznych).
     * BE-156: {@code maxAsyncPerAgent != null} dodatkowo egzekwuje atomowo limit otwartych kontaktów async agenta.
     *
     * @param contact          encja kontaktu
     * @param agentId          UUID wybranego agenta
     * @param tenantId         UUID tenanta
     * @param queueId          kolejka tego przebiegu routingu (jawnie, nie z encji) lub null (transfer bezpośredni)
     * @param maxAsyncPerAgent limit kontaktów async agenta w kolejce lub null (telefon / transfer bezpośredni)
     * @return wynik przydziału
     */
    private AssignmentResult assignContactToAgent(Contact contact, UUID agentId, UUID tenantId,
                                                  UUID queueId, Integer maxAsyncPerAgent) {
        AssignmentResult result = contactService.assignQueuedContact(contact, agentId, queueId, maxAsyncPerAgent);
        if (result != AssignmentResult.ASSIGNED) {
            log.info("[RoutingService] Kontakt nieprzydzielony ({}) – pomijam: contactId={}, agentId={}, " +
                    "tenantId={}", result, contact.getContactId(), agentId, tenantId);
            return result;
        }
        log.debug("[RoutingService] Kontakt przypisany do agenta: contactId={}, agentId={}",
                contact.getContactId(), agentId);
        return result;
    }

    /**
     * Publikuje event {@code contact.assigned} do RabbitMQ.
     *
     * <p>Pobiera z encji kontaktu kanał, identyfikator i nazwę klienta,
     * aby frontend mógł poprawnie wyświetlić zakładkę kontaktu.
     *
     * <p>Błąd publikacji nie przerywa operacji – logujemy error (agent już przypisany w DB).
     */
    private void publishAssignedEvent(UUID contactId, UUID agentId, UUID queueId,
                                       UUID tenantId, String strategy, Contact contact,
                                       String queueName) {
        String channel    = contact.getChannel() != null ? contact.getChannel() : "UNKNOWN";
        String rawAddress = contact.getRemoteAddress() != null ? contact.getRemoteAddress() : "";

        // Dla kanału EMAIL remoteAddress może być w formacie RFC 2822 "Display Name <email@domain>".
        // Wyodrębniamy czysty adres email, żeby frontend mógł go użyć do lookup klienta.
        String customerIdentifier = "EMAIL".equals(channel) ? extractEmailAddress(rawAddress) : rawAddress;
        String customerName       = resolveCustomerName(contact, customerIdentifier, tenantId);
        String customerId         = contact.getCustomerId() != null ? contact.getCustomerId().toString() : null;

        ContactAssignedEvent event = ContactAssignedEvent.of(contactId, agentId, queueId, tenantId, strategy,
                channel, customerName, customerIdentifier, queueName, customerId);
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, RK_CONTACT_ASSIGNED, event);
            log.debug("[RoutingService] Event contact.assigned opublikowany: contactId={}, agentId={}, channel={}",
                    contactId, agentId, channel);
        } catch (Exception e) {
            log.error("[RoutingService] Błąd publikacji contact.assigned: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Broadcasts the current QUEUED contact list to all agents of the tenant.
     *
     * <p>Called after every routing attempt (both {@code contact.queued} and
     * {@code agent.status.changed} events) so the agent sidebar stays up to date.
     * Errors are swallowed – a failed broadcast must not roll back the routing transaction.
     *
     * @param tenantId UUID of the tenant whose agents should receive the update
     */
    private void broadcastQueueStateToAgents(UUID tenantId) {
        try {
            List<QueuedContactView> views =
                    contactService.findQueuedContactsForAgentView(tenantId);

            List<WebSocketEvent.QueueItemDto> items = views.stream()
                    .map(v -> new WebSocketEvent.QueueItemDto(
                            v.contactId().toString(),
                            ContactChannels.toAgentDesktopType(v.channel()),
                            v.customerName(),
                            v.remoteAddress(),
                            v.queuedAt(),
                            v.queueName(),
                            0
                    ))
                    .toList();

            broadcaster.sendToTenantAgents(tenantId, WebSocketEvent.queueAgentUpdate(tenantId, items));
            log.debug("[RoutingService] Queue update sent to agents: tenantId={}, items={}",
                    tenantId, items.size());
        } catch (Exception e) {
            log.warn("[RoutingService] Failed to broadcast queue update to agents: tenantId={}, error={}",
                    tenantId, e.getMessage());
        }
    }

    /**
     * Assigns the tenant's default social queue to a QUEUED social contact without a queue (BE-155).
     *
     * @param queueId the tenant's default social queue, resolved once per pass by the caller
     * @return true when the contact now has a queue (in memory and in DB); false when the tenant has
     *         no usable queue or the update did not apply - the contact is left untouched (QUEUED)
     */
    private boolean assignDefaultSocialQueue(Contact contact, UUID tenantId, Optional<UUID> queueId) {
        if (queueId.isEmpty()) {
            return false;
        }
        int updated = contactService.updateQueueId(contact.getContactId(), tenantId, queueId.get());
        if (updated == 0) {
            log.warn("[RoutingService] Nie udało się przypisać kolejki social do kontaktu: contactId={}",
                    contact.getContactId());
            return false;
        }
        contact.setQueueId(queueId.get());
        log.info("[RoutingService] Kontakt social bez kolejki dostał domyślną kolejkę: contactId={}, queueId={}",
                contact.getContactId(), queueId.get());
        return true;
    }

    /**
     * Resolves the display name for a contact's customer.
     * Looks up firstName + lastName from the Customer record when customerId is available.
     * Falls back to {@code fallbackIdentifier} (phone/email) when customer is unknown or lookup fails.
     */
    private String resolveCustomerName(Contact contact, String fallbackIdentifier, UUID tenantId) {
        UUID customerId = contact.getCustomerId();
        if (customerId == null) {
            return fallbackIdentifier;
        }
        try {
            return customerService.findById(customerId, tenantId)
                    .map(c -> {
                        String first = c.getFirstName() != null ? c.getFirstName() : "";
                        String last  = c.getLastName()  != null ? c.getLastName()  : "";
                        String name  = (first + " " + last).trim();
                        return name.isBlank() ? fallbackIdentifier : name;
                    })
                    .orElse(fallbackIdentifier);
        } catch (Exception e) {
            log.warn("[RoutingService] Nie udało się pobrać nazwy klienta: customerId={}, error={}",
                    customerId, e.getMessage());
            return fallbackIdentifier;
        }
    }

    /**
     * Wyodrębnia czysty adres email z formatu RFC 2822 "Display Name &lt;email@domain&gt;".
     * Jeśli format nie pasuje, zwraca oryginalny ciąg po przycięciu białych znaków.
     */
    private String extractEmailAddress(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int lt = raw.lastIndexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return raw.substring(lt + 1, gt).trim();
        }
        return raw.trim();
    }
}
