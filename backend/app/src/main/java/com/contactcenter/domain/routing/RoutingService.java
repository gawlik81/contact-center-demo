package com.contactcenter.domain.routing;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.model.AppUser.UserStatus;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private final ContactRepository contactRepository;
    private final RabbitTemplate rabbitTemplate;

    // =========================================================================
    // Główna metoda routingu
    // =========================================================================

    /**
     * Routuje kontakt do najlepszego dostępnego agenta.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Pobierz kolejkę z bazy danych</li>
     *   <li>Zbuduj {@link RoutingRequest} z konfiguracji kolejki</li>
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
        Optional<Contact> contactOpt = contactRepository.findById(contactId, tenantId);
        if (contactOpt.isEmpty()) {
            log.error("[RoutingService] Kontakt {} nie istnieje w DB – pomijam routing (tenantId={}). " +
                      "Sprawdź czy TwilioTelephonyAdapter tworzy rekord contact przy webhook.",
                      contactId, tenantId);
            return Optional.empty();
        }
        Contact contact = contactOpt.get();

        // 3. Zbuduj RoutingRequest
        RoutingRequest request = RoutingRequest.of(contact, queue, tenantId);

        // 4. Wywołaj silnik routingu
        Optional<RoutingResult> result = routingEngine.findBestAgent(request);

        if (result.isPresent()) {
            RoutingResult routing = result.get();
            UUID agentId = routing.agentId();

            log.info("[RoutingService] Agent znaleziony: contactId={}, agentId={}, strategy={}",
                    contactId, agentId, routing.strategy());

            // 5a. Aktualizuj kontakt – przypisz agenta i zmień status na ACTIVE
            assignContactToAgent(contact, agentId, tenantId);

            // 5b. Opublikuj event contact.assigned
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
            List<Contact> queuedContacts = contactRepository.findQueuedContacts(event.tenantId());

            if (queuedContacts.isEmpty()) {
                log.debug("[RoutingService] Brak oczekujących kontaktów dla tenanta: {}", event.tenantId());
                return;
            }

            log.info("[RoutingService] Znaleziono {} oczekujących kontaktów dla tenanta: {}",
                    queuedContacts.size(), event.tenantId());

            for (Contact contact : queuedContacts) {
                if (contact.getQueueId() == null) {
                    log.warn("[RoutingService] Kontakt bez queueId – kończę ze statusem ERROR: contactId={}",
                            contact.getContactId());
                    try {
                        contact.setStatus("ERROR");
                        contact.setEndedAt(Instant.now());
                        contactRepository.update(contact);
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
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Aktualizuje kontakt – przypisuje agenta i ustawia status ACTIVE.
     *
     * @param contact  encja kontaktu
     * @param agentId  UUID wybranego agenta
     * @param tenantId UUID tenanta
     */
    private void assignContactToAgent(Contact contact, UUID agentId, UUID tenantId) {
        contact.setAgentId(agentId);
        contact.setStatus("ACTIVE");
        contact.setAssignedAt(Instant.now());

        int updated = contactRepository.update(contact);
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
        String channel            = contact.getChannel() != null ? contact.getChannel() : "UNKNOWN";
        String customerIdentifier = contact.getRemoteAddress() != null ? contact.getRemoteAddress() : "";
        String customerName       = customerIdentifier;

        ContactAssignedEvent event = ContactAssignedEvent.of(contactId, agentId, queueId, tenantId, strategy,
                channel, customerName, customerIdentifier, queueName);
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_EVENTS, RK_CONTACT_ASSIGNED, event);
            log.debug("[RoutingService] Event contact.assigned opublikowany: contactId={}, agentId={}, channel={}",
                    contactId, agentId, channel);
        } catch (Exception e) {
            log.error("[RoutingService] Błąd publikacji contact.assigned: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }
}
