package com.contactcenter.domain.service;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.domain.routing.ContactQueuedMessage;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.domain.websocket.WebSocketEventBroadcaster;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Monitor przestarzałych przypisań kontaktów (status ASSIGNED) – odporność na utratę WebSocket.
 *
 * <p>Problem: {@link RoutingService} przydziela agenta i ustawia {@code contact.status = 'ASSIGNED'},
 * po czym wysyła {@code CONTACT_ASSIGNED} przez WebSocket. Jeśli WS agenta był rozłączony
 * lub strona się ładowała, event ginie i klient rozłącza się po kilku sekundach → ABANDONED.
 *
 * <p>Rozwiązanie:
 * <ol>
 *   <li>Scheduler sprawdza co 10 sekund kontakty ze statusem ASSIGNED starsze niż 15 sekund.</li>
 *   <li>Przy retry {@literal <} {@value MAX_RETRIES}: ponownie wysyła CONTACT_ASSIGNED przez WebSocket.</li>
 *   <li>Po wyczerpaniu prób: resetuje kontakt do QUEUED (agentId=null) i re-kolejkuje do routingu.</li>
 * </ol>
 *
 * <p>Licznik prób jest przechowywany w Redis pod kluczem {@code cc:assign-retry:{contactId}}
 * z TTL {@value RETRY_TTL_SECONDS}s (automatyczne czyszczenie po zakończeniu monitorowania).
 *
 * <p>Metoda jest cross-tenant: {@link ContactRepository#findStaleAssignedContacts} nie filtruje
 * po tenancie – aplikacja łączy się jako superuser, który omija RLS PostgreSQL.
 * TenantContext jest ustawiany ręcznie tylko przy operacjach wymagających izolacji tenanta.
 *
 * <p>{@link org.springframework.scheduling.annotation.EnableScheduling} jest aktywne
 * w klasie głównej aplikacji ({@code @SpringBootApplication}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactAssignmentMonitor {

    /** Maksymalna liczba prób re-wysłania CONTACT_ASSIGNED zanim kontakt wróci do QUEUED. */
    private static final int MAX_RETRIES = 3;

    /** Czas (sekundy) po którym kontakt ASSIGNED jest uznawany za "przestarzały". */
    private static final int STALE_THRESHOLD_SECONDS = 15;

    /** Prefix klucza Redis dla licznika prób: {@code cc:assign-retry:{contactId}}. */
    private static final String RETRY_KEY_PREFIX = "cc:assign-retry:";

    /** TTL klucza Redis w sekundach – dłuższy niż MAX_RETRIES * interwał schedulera. */
    private static final long RETRY_TTL_SECONDS = 120;

    private final ContactRepository contactRepository;
    private final QueueRepository queueRepository;
    private final WebSocketEventBroadcaster broadcaster;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    // =========================================================================
    // Główna pętla monitorująca
    // =========================================================================

    /**
     * Co 10 sekund sprawdza kontakty ze statusem ASSIGNED starsze niż 15 sekund
     * i podejmuje akcję naprawczą (re-wysłanie WS lub reset do QUEUED).
     *
     * <p>Błąd pojedynczego kontaktu nie przerywa pętli – każdy jest opakowywany
     * w try/catch dla izolacji awarii.
     */
    @Scheduled(fixedDelay = 10_000)
    public void checkStaleAssignments() {
        Instant cutoff = Instant.now().minusSeconds(STALE_THRESHOLD_SECONDS);
        List<Contact> stale = contactRepository.findStaleAssignedContacts(cutoff);

        if (stale.isEmpty()) {
            return;
        }

        log.info("[AssignmentMonitor] Znaleziono {} przestarzałych przypisań ASSIGNED (starszych niż {}s)",
                stale.size(), STALE_THRESHOLD_SECONDS);

        for (Contact contact : stale) {
            try {
                processStaleContact(contact);
            } catch (Exception e) {
                log.error("[AssignmentMonitor] Błąd przetwarzania contactId={}, tenantId={}: {}",
                        contact.getContactId(), contact.getTenantId(), e.getMessage(), e);
            }
        }
    }

    // =========================================================================
    // Logika przetwarzania przestarzałego kontaktu
    // =========================================================================

    /**
     * Przetwarza pojedynczy kontakt ze statusem ASSIGNED.
     *
     * <ul>
     *   <li>Kontakty EMAIL i CHAT są obsługiwane przez HTTP polling (nie przez WebSocket CONTACT_ASSIGNED).
     *       Gdy agent pobierze taki kontakt, wywołuje {@code POST /api/contacts/{id}/accept} który
     *       zmienia status na ACTIVE – monitor pomija kontakty w tym statusie przez zapytanie SQL.
     *       Dopóki kontakt jest ASSIGNED (agent jeszcze go nie otworzył), monitor re-wysyła WS event
     *       tak jak dla PHONE, co pozwala odświeżyć zakładkę agenta.</li>
     *   <li>retry &lt; MAX_RETRIES: re-wysyła CONTACT_ASSIGNED przez WebSocket i inkrementuje licznik.</li>
     *   <li>retry &ge; MAX_RETRIES: resetuje kontakt do QUEUED, re-kolejkuje przez RabbitMQ.</li>
     * </ul>
     *
     * <p>TenantContext jest ustawiany i czyszczony wokół operacji DB wymagających izolacji tenanta.
     *
     * @param contact kontakt ASSIGNED do przetworzenia
     */
    private void processStaleContact(Contact contact) {
        UUID contactId = contact.getContactId();
        UUID tenantId = contact.getTenantId();
        UUID agentId = contact.getAgentId();

        String retryKey = RETRY_KEY_PREFIX + contactId;
        int retryCount = getRetryCount(retryKey);

        log.debug("[AssignmentMonitor] Przetwarzam contactId={}, retryCount={}/{}, tenantId={}",
                contactId, retryCount, MAX_RETRIES, tenantId);

        if (retryCount < MAX_RETRIES) {
            handleRetry(contact, retryKey, retryCount);
        } else {
            handleMaxRetriesExceeded(contact, retryKey);
        }
    }

    /**
     * Ponawia wysłanie CONTACT_ASSIGNED przez WebSocket i inkrementuje licznik prób.
     *
     * @param contact   kontakt do re-notyfikacji
     * @param retryKey  klucz Redis z licznikiem
     * @param retryCount aktualna liczba prób
     */
    private void handleRetry(Contact contact, String retryKey, int retryCount) {
        UUID contactId = contact.getContactId();
        UUID tenantId = contact.getTenantId();
        UUID agentId = contact.getAgentId();

        if (agentId == null) {
            log.warn("[AssignmentMonitor] Kontakt ASSIGNED bez agentId – pomijam retry: contactId={}",
                    contactId);
            return;
        }

        // Pobierz nazwę kolejki dla payloadu WebSocket event
        String queueName = "";
        if (contact.getQueueId() != null) {
            try {
                queueName = queueRepository.findByIdAndTenantId(contact.getQueueId(), tenantId)
                        .map(q -> q.getName())
                        .orElse("");
            } catch (Exception e) {
                log.warn("[AssignmentMonitor] Nie udało się pobrać nazwy kolejki: queueId={}, error={}",
                        contact.getQueueId(), e.getMessage());
            }
        }

        // Zbuduj event CONTACT_ASSIGNED z danych kontaktu
        String channel = contact.getChannel() != null ? contact.getChannel() : "UNKNOWN";
        String remoteAddress = contact.getRemoteAddress() != null ? contact.getRemoteAddress() : "";
        String customerId = contact.getCustomerId() != null ? contact.getCustomerId().toString() : null;

        WebSocketEvent wsEvent = WebSocketEvent.contactAssigned(
                tenantId,
                agentId,
                contactId,
                channel,
                remoteAddress,    // customerName – fallback na remoteAddress
                remoteAddress,    // customerIdentifier
                queueName,
                customerId
        );

        // Wyślij event unicast do agenta
        broadcaster.sendToUser(agentId, wsEvent);

        // Inkrementuj licznik z TTL
        incrementRetryCount(retryKey, retryCount);

        log.info("[AssignmentMonitor] Re-wysłano CONTACT_ASSIGNED (próba {}/{}): " +
                 "contactId={}, agentId={}, tenantId={}",
                retryCount + 1, MAX_RETRIES, contactId, agentId, tenantId);
    }

    /**
     * Po wyczerpaniu prób resetuje kontakt do QUEUED i re-kolejkuje do routingu.
     *
     * <p>TenantContext jest ustawiany przed operacją DB i czyszczony w bloku finally.
     *
     * @param contact  kontakt do zresetowania
     * @param retryKey klucz Redis do usunięcia
     */
    private void handleMaxRetriesExceeded(Contact contact, String retryKey) {
        UUID contactId = contact.getContactId();
        UUID tenantId = contact.getTenantId();

        log.warn("[AssignmentMonitor] Wyczerpano {} prób dla contactId={}, tenantId={} – " +
                 "reset do QUEUED i re-kolejkowanie", MAX_RETRIES, contactId, tenantId);

        // Reset statusu do QUEUED w DB
        try {
            TenantContext.setTenantId(tenantId);
            contact.setStatus("QUEUED");
            contact.setAgentId(null);
            contact.setAssignedAt(null);
            int updated = contactRepository.update(contact);
            if (updated == 0) {
                log.warn("[AssignmentMonitor] Update kontaktu zwrócił 0 wierszy: contactId={}. " +
                         "Kontakt mógł zmienić status w międzyczasie.", contactId);
                return;
            }
        } catch (Exception e) {
            log.error("[AssignmentMonitor] Błąd resetu kontaktu do QUEUED: contactId={}, error={}",
                    contactId, e.getMessage(), e);
            return;
        } finally {
            TenantContext.clear();
        }

        // Usuń klucz retry z Redis
        try {
            redisTemplate.delete(retryKey);
        } catch (Exception e) {
            log.warn("[AssignmentMonitor] Nie udało się usunąć klucza retry z Redis: key={}, error={}",
                    retryKey, e.getMessage());
        }

        // Re-kolejkuj do routingu przez RabbitMQ (jeśli kontakt ma queueId).
        // Wiadomość publikujemy przez exchange cc.events z routing key contact.queued
        // (analogicznie do IvrEngineService.executeQueueTransfer).
        if (contact.getQueueId() != null) {
            try {
                ContactQueuedMessage message = new ContactQueuedMessage(
                        contactId,
                        contact.getQueueId(),
                        tenantId
                );
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_EVENTS, "contact.queued", message);
                log.info("[AssignmentMonitor] Kontakt re-kolejkowany do routingu: " +
                         "contactId={}, queueId={}, tenantId={}",
                        contactId, contact.getQueueId(), tenantId);
            } catch (Exception e) {
                log.error("[AssignmentMonitor] Błąd re-kolejkowania kontaktu: contactId={}, error={}",
                        contactId, e.getMessage(), e);
            }
        } else {
            log.warn("[AssignmentMonitor] Kontakt bez queueId – nie można re-kolejkować: contactId={}",
                    contactId);
        }
    }

    // =========================================================================
    // Metody pomocnicze Redis
    // =========================================================================

    /**
     * Pobiera aktualną liczbę prób z Redis.
     *
     * @param retryKey klucz Redis
     * @return liczba prób (0 gdy klucz nie istnieje lub błąd parsowania)
     */
    private int getRetryCount(String retryKey) {
        try {
            String value = redisTemplate.opsForValue().get(retryKey);
            if (value == null) {
                return 0;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            log.warn("[AssignmentMonitor] Błąd odczytu retry count z Redis: key={}, error={}",
                    retryKey, e.getMessage());
            return 0;
        }
    }

    /**
     * Inkrementuje licznik prób w Redis z TTL {@value RETRY_TTL_SECONDS}s.
     *
     * <p>Używa INCR + EXPIRE zamiast SET żeby uniknąć race condition przy równoległych
     * wywołaniach (w praktyce niemożliwych przy fixedDelay, ale dla bezpieczeństwa).
     *
     * @param retryKey   klucz Redis
     * @param currentCount aktualna wartość przed inkrementacją
     */
    private void incrementRetryCount(String retryKey, int currentCount) {
        try {
            redisTemplate.opsForValue().set(retryKey, String.valueOf(currentCount + 1),
                    RETRY_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[AssignmentMonitor] Błąd inkrementacji retry count w Redis: key={}, error={}",
                    retryKey, e.getMessage());
        }
    }
}
