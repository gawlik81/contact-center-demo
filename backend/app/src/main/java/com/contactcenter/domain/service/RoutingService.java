package com.contactcenter.domain.service;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.contact.QueuedContactView;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.repository.QueueAssignmentRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.routing.ContactAssignedEvent;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.routing.DirectAgentAssignmentMessage;
import com.contactcenter.domain.routing.RoutingEngine;
import com.contactcenter.domain.routing.RoutingRequest;
import com.contactcenter.domain.routing.RoutingResult;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Serwis domenowy orkiestrujący routing kontaktów do agentów.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Pobieranie konfiguracji kolejki z bazy danych</li>
 *   <li>Budowanie {@link RoutingRequest} i delegowanie do {@link RoutingEngine}</li>
 *   <li>Aktualizacja statusu kontaktu po przydzieleniu</li>
 *   <li>Publikacja eventów domenowych ({@code contact.assigned})</li>
 *   <li>Nasłuchiwanie eventów {@code contact.queued} i pierwotne próby routingu</li>
 *   <li>Nasłuchiwanie eventów {@code agent.status.changed} i retry routingu dla oczekujących kontaktów</li>
 * </ul>
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
public class RoutingService {

    private static final String RK_CONTACT_ASSIGNED = "contact.assigned";

    private final RoutingEngine routingEngine;
    private final QueueRepository queueRepository;
    private final CustomerService customerService;
    private final RabbitTemplate rabbitTemplate;
    private final QueueAssignmentRepository queueAssignmentRepository;
    private final UserService userService;
    private final WebSocketEventBroadcaster broadcaster;
    private final ContactService contactService;
    private final ContactEventService contactEventService;

    // =========================================================================
    // Główna metoda routingu
    // =========================================================================

    /**
     * Routuje kontakt do najlepszego dostępnego agenta.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Pobierz kolejkę z bazy danych</li>
     *   <li>Pobierz kontakt z bazy danych</li>
     *   <li>Wyznacz zbiór uprawnionych agentów: gdy {@code all_agents=FALSE} – jeden SELECT UNION
     *       ({@code queue_agent} + grupy); gdy {@code all_agents=TRUE} – null (brak filtru)</li>
     *   <li>Zbuduj {@link RoutingRequest} z konfiguracji kolejki + {@code eligibleAgentIds}</li>
     *   <li>Wywołaj {@link RoutingEngine#findBestAgent(RoutingRequest)}</li>
     *   <li>Jeśli znaleziono agenta: zaktualizuj kontakt (status ACTIVE, agent_id) i opublikuj
     *       event {@code contact.assigned}</li>
     *   <li>Jeśli nie znaleziono: zwróć empty – kontakt pozostaje w statusie QUEUED w DB.
     *       Retry nastąpi gdy agent zmieni status na AVAILABLE (patrz {@link #onAgentStatusChanged}).</li>
     * </ol>
     *
     * <p>Metoda jest synchroniczna – wywołanie z {@link RabbitListener} na {@code contact.queued}
     * lub {@code agent.status.changed} pozwala Spring AMQP poprawnie obsłużyć NACK przy wyjątku.
     *
     * @param contactId UUID kontaktu do routowania
     * @param queueId   UUID kolejki docelowej
     * @param tenantId  UUID tenanta
     * @return Optional z UUID agenta lub empty gdy nie znaleziono
     */
    @Transactional
    public Optional<UUID> routeContact(UUID contactId, UUID queueId, UUID tenantId) {
        log.info("[RoutingService] Routing kontaktu: contactId={}, queueId={}, tenantId={}",
                contactId, queueId, tenantId);

        // 1. Pobierz kolejkę
        Queue queue = queueRepository.findByIdAndTenantId(queueId, tenantId)
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
        if (!queueAssignmentRepository.isAllAgents(queue.getQueueId(), tenantId)) {
            eligibleAgentIds = queueAssignmentRepository.resolveEligibleAgentIds(
                    queue.getQueueId(), tenantId);
            log.debug("[RoutingService] Kolejka {} ma all_agents=FALSE, uprawnionych agentów: {}",
                    queueId, eligibleAgentIds.size());
        }

        // 4. Zbuduj RoutingRequest
        RoutingRequest request = RoutingRequest.of(contact, queue, tenantId, eligibleAgentIds);

        // 5. Wywołaj silnik routingu
        Optional<RoutingResult> result = routingEngine.findBestAgent(request);

        if (result.isPresent()) {
            RoutingResult routing = result.get();
            UUID agentId = routing.agentId();

            log.info("[RoutingService] Agent znaleziony: contactId={}, agentId={}, strategy={}",
                    contactId, agentId, routing.strategy());

            // 6a. Aktualizuj kontakt – przypisz agenta i zmień status na ACTIVE
            assignContactToAgent(contact, agentId, tenantId);

            // 6b. Opublikuj event contact.assigned
            publishAssignedEvent(contactId, agentId, queueId, tenantId, routing.strategy(), contact, queue.getName());

            return Optional.of(agentId);

        } else {
            log.info("[RoutingService] Brak dostępnych agentów, kontakt czeka: contactId={}, queueId={}",
                    contactId, queueId);

            // Kontakt pozostaje w statusie QUEUED w DB.
            // NIE publikujemy contact.queued – to spowodowałoby nieskończoną pętlę
            // (onContactQueued → routeContact → brak agentów → contact.queued → ...).
            // Retry nastąpi przy evencie agent.status.changed (patrz onAgentStatusChanged).
            return Optional.empty();
        }
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

        log.info("[RoutingService] Agent stał się AVAILABLE, próbuję routować oczekujące kontakty: " +
                "agentId={}, tenantId={}", event.userId(), event.tenantId());

        TenantContext.Snapshot snapshot = new TenantContext.Snapshot(
                event.tenantId(), event.userId(), null, "SYSTEM");
        TenantContext.restore(snapshot);
        try {
            List<Contact> queuedContacts = contactService.findQueuedContacts(event.tenantId());

            if (queuedContacts.isEmpty()) {
                log.debug("[RoutingService] Brak oczekujących kontaktów dla tenanta: {}", event.tenantId());
                return;
            }

            log.info("[RoutingService] Znaleziono {} oczekujących kontaktów dla tenanta: {}",
                    queuedContacts.size(), event.tenantId());

            for (Contact contact : queuedContacts) {
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

                if (contact.getQueueId() == null) {
                    log.warn("[RoutingService] Kontakt bez queueId – kończę ze statusem ERROR: contactId={}",
                            contact.getContactId());
                    try {
                        contact.setStatus("ERROR");
                        contact.setEndedAt(Instant.now());
                        contactService.updateContactEntity(contact);
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
                            event.tenantId()
                    );

                    if (agentId.isPresent()) {
                        log.info("[RoutingService] Oczekujący kontakt przydzielony: contactId={}, agentId={}",
                                contact.getContactId(), agentId.get());
                        // Jeden agent może obsłużyć jeden kontakt naraz – przerywamy po pierwszym
                        // przydzieleniu (agent jest teraz zajęty)
                        break;
                    }
                } catch (Exception e) {
                    log.error("[RoutingService] Błąd routingu oczekującego kontaktu: contactId={}, error={}",
                            contact.getContactId(), e.getMessage(), e);
                    // Kontynuuj z kolejnym kontaktem – jeden błąd nie blokuje pozostałych
                }
            }
        } catch (Exception e) {
            log.error("[RoutingService] Błąd pobierania oczekujących kontaktów dla tenanta={}: error={}",
                    event.tenantId(), e.getMessage(), e);
            throw new RuntimeException("Błąd retry routingu dla tenanta " + event.tenantId(), e);
        } finally {
            broadcastQueueStateToAgents(event.tenantId());
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

            assignContactToAgent(contact, message.agentId(), message.tenantId());
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

        for (AppUser agent : availableAgents) {
            UUID agentId  = agent.getId();
            UUID tenantId = agent.getTenantId();

            TenantContext.Snapshot snapshot =
                    new TenantContext.Snapshot(tenantId, agentId, null, "SYSTEM");
            TenantContext.restore(snapshot);
            try {
                List<Contact> queuedContacts = contactService.findQueuedContacts(tenantId);
                if (queuedContacts.isEmpty()) {
                    continue;
                }

                for (Contact contact : queuedContacts) {
                    // Kontakty OUTBOUND z kampanii mają agenta przypisanego przez dialer –
                    // RoutingService nie powinien ich dotykać (duplikacja przypisania).
                    if ("OUTBOUND".equals(contact.getDirection()) && contact.getAgentId() != null) {
                        continue;
                    }

                    if (contact.getQueueId() == null) {
                        continue;
                    }

                    Optional<UUID> assignedAgent = routeContact(
                            contact.getContactId(),
                            contact.getQueueId(),
                            tenantId
                    );

                    if (assignedAgent.isPresent()) {
                        // Agent zajęty po przydzieleniu kontaktu – przejdź do następnego agenta
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("[RoutingService] pollAvailableAgents: błąd dla agenta {}: {}",
                        agentId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
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
     * @param contact  encja kontaktu
     * @param agentId  UUID wybranego agenta
     * @param tenantId UUID tenanta
     */
    private void assignContactToAgent(Contact contact, UUID agentId, UUID tenantId) {
        contact.setAgentId(agentId);
        contact.setStatus("ASSIGNED");
        contact.setAssignedAt(Instant.now());

        int updated = contactService.updateContactEntity(contact);
        if (updated == 0) {
            log.warn("[RoutingService] Nie zaktualizowano kontaktu (0 wierszy): contactId={}",
                    contact.getContactId());
        } else {
            log.debug("[RoutingService] Kontakt przypisany do agenta: contactId={}, agentId={}",
                    contact.getContactId(), agentId);
        }
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
                            mapChannelToType(v.channel()),
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
     * Maps a contact channel value to the frontend ContactType.
     * All {@code SOCIAL_*} variants are collapsed to {@code SOCIAL}.
     */
    private static String mapChannelToType(String channel) {
        if (channel == null) return "PHONE";
        if (channel.startsWith("SOCIAL")) return "SOCIAL";
        return channel;
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
