package com.contactcenter.domain.service;

import com.contactcenter.domain.model.ContactEvent;
import com.contactcenter.domain.repository.ContactEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serwis zarządzający historią etapów kontaktu (contact_event).
 *
 * <p>Każda metoda {@code open*} i {@code close*} jest otoczona blokiem
 * try/catch – błąd zapisu historii NIE przerywa głównego przepływu biznesowego.
 * Serwis loguje ostrzeżenie i kontynuuje.
 *
 * <p>Zdarzenie TRANSFER jest punktowe: {@code started_at = ended_at = Instant.now()},
 * {@code duration_seconds = 0}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContactEventService {

    private final ContactEventRepository repository;

    // =========================================================================
    // IVR
    // =========================================================================

    /**
     * Otwiera etap IVR dla kontaktu.
     *
     * @param contactId   UUID kontaktu
     * @param tenantId    UUID tenanta
     * @param ivrTreeId   UUID drzewa IVR (może być null)
     * @param ivrTreeName nazwa drzewa IVR (może być null)
     */
    public void openIvr(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("ivr_tree_id",   ivrTreeId != null ? ivrTreeId.toString() : null);
            meta.put("ivr_tree_name", ivrTreeName);
            repository.save(buildEvent(contactId, tenantId, "IVR", meta));
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu IVR: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap IVR dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void closeIvr(UUID contactId, UUID tenantId) {
        closeStage(contactId, tenantId, "IVR");
    }

    // =========================================================================
    // VOICEBOT
    // =========================================================================

    /**
     * Otwiera etap VOICEBOT dla kontaktu.
     *
     * @param contactId   UUID kontaktu
     * @param tenantId    UUID tenanta
     * @param ivrTreeId   UUID drzewa IVR/voicebota (może być null)
     * @param ivrTreeName nazwa drzewa IVR/voicebota (może być null)
     */
    public void openVoicebot(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("ivr_tree_id",   ivrTreeId != null ? ivrTreeId.toString() : null);
            meta.put("ivr_tree_name", ivrTreeName);
            repository.save(buildEvent(contactId, tenantId, "VOICEBOT", meta));
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu VOICEBOT: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap VOICEBOT dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param outcome   wynik sesji voicebota (np. "TRANSFERRED", "ABANDONED") – pomijany przy zamykaniu,
     *                  powinien być przekazany do metadata przy openVoicebot
     */
    public void closeVoicebot(UUID contactId, UUID tenantId, String outcome) {
        closeStage(contactId, tenantId, "VOICEBOT");
    }

    // =========================================================================
    // QUEUE
    // =========================================================================

    /**
     * Otwiera etap QUEUE dla kontaktu.
     *
     * <p>Czas rozpoczęcia (queuedAt) może różnić się od bieżącego czasu – używany jest
     * jawnie jako {@code started_at}, aby poprawnie odzwierciedlić faktyczny czas kolejkowania.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param queueId   UUID kolejki (może być null)
     * @param queueName nazwa kolejki (może być null)
     * @param queuedAt  czas faktycznego wejścia do kolejki (null = Instant.now())
     */
    public void openQueue(UUID contactId, UUID tenantId, UUID queueId, String queueName, Instant queuedAt) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("queue_id",   queueId != null ? queueId.toString() : null);
            meta.put("queue_name", queueName);
            ContactEvent event = ContactEvent.builder()
                    .eventId(UUID.randomUUID())
                    .contactId(contactId)
                    .tenantId(tenantId)
                    .stage("QUEUE")
                    .startedAt(queuedAt != null ? queuedAt : Instant.now())
                    .metadata(meta)
                    .build();
            repository.save(event);
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu QUEUE: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap QUEUE dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void closeQueue(UUID contactId, UUID tenantId) {
        closeStage(contactId, tenantId, "QUEUE");
    }

    // =========================================================================
    // AGENT
    // =========================================================================

    /**
     * Otwiera etap AGENT (rozmowa z agentem) dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta
     * @param agentName imię i nazwisko agenta (może być null)
     */
    public void openAgent(UUID contactId, UUID tenantId, UUID agentId, String agentName) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("agent_id",   agentId != null ? agentId.toString() : null);
            meta.put("agent_name", agentName);
            repository.save(buildEvent(contactId, tenantId, "AGENT", meta));
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu AGENT: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap AGENT dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void closeAgent(UUID contactId, UUID tenantId) {
        closeStage(contactId, tenantId, "AGENT");
    }

    // =========================================================================
    // ON_HOLD
    // =========================================================================

    /**
     * Otwiera etap ON_HOLD (kontakt zawieszony przez agenta).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void openHold(UUID contactId, UUID tenantId) {
        try {
            repository.save(buildEvent(contactId, tenantId, "ON_HOLD", new HashMap<>()));
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu ON_HOLD: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap ON_HOLD dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void closeHold(UUID contactId, UUID tenantId) {
        closeStage(contactId, tenantId, "ON_HOLD");
    }

    // =========================================================================
    // CONSULTING
    // =========================================================================

    /**
     * Otwiera etap CONSULTING (konsultacja z innym agentem lub numerem zewnętrznym).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param target    cel konsultacji (numer telefonu lub identyfikator agenta)
     */
    public void openConsulting(UUID contactId, UUID tenantId, String target) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("target",        target);
            meta.put("transfer_type", "ATTENDED");
            repository.save(buildEvent(contactId, tenantId, "CONSULTING", meta));
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu etapu CONSULTING: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    /**
     * Zamyka ostatni otwarty etap CONSULTING dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    public void closeConsulting(UUID contactId, UUID tenantId) {
        closeStage(contactId, tenantId, "CONSULTING");
    }

    // =========================================================================
    // TRANSFER – zdarzenie punktowe
    // =========================================================================

    /**
     * Rejestruje zdarzenie transferu kontaktu (zdarzenie punktowe).
     *
     * <p>TRANSFER to zdarzenie punktowe: {@code started_at = ended_at = Instant.now()},
     * {@code duration_seconds = 0}. Nie otwiera etapu do późniejszego zamknięcia.
     *
     * @param contactId       UUID kontaktu
     * @param tenantId        UUID tenanta
     * @param target          cel transferu (numer telefonu, kolejka lub identyfikator agenta)
     * @param transferType    typ transferu (np. "BLIND", "ATTENDED")
     * @param targetAgentName imię agenta docelowego (może być null)
     */
    public void recordTransfer(UUID contactId, UUID tenantId,
                               String target, String transferType, String targetAgentName) {
        try {
            Instant now = Instant.now();
            Map<String, Object> meta = new HashMap<>();
            meta.put("target",        target);
            meta.put("transfer_type", transferType);
            if (targetAgentName != null) {
                meta.put("target_agent_name", targetAgentName);
            }
            ContactEvent event = ContactEvent.builder()
                    .eventId(UUID.randomUUID())
                    .contactId(contactId)
                    .tenantId(tenantId)
                    .stage("TRANSFER")
                    .startedAt(now)
                    .endedAt(now)
                    .durationSeconds(0)
                    .metadata(meta)
                    .build();
            repository.save(event);
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zapisu TRANSFER: contactId={}, error={}",
                    contactId, e.getMessage());
        }
    }

    // =========================================================================
    // Historia kontaktu
    // =========================================================================

    /**
     * Zwraca pełną historię etapów kontaktu posortowaną chronologicznie.
     *
     * <p>Przy błędzie odczytu zwraca pustą listę – nie przerywa głównego przepływu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista zdarzeń posortowana po started_at ASC (pusta przy błędzie)
     */
    public List<ContactEvent> getHistory(UUID contactId, UUID tenantId) {
        try {
            return repository.findByContactId(contactId, tenantId);
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd pobierania historii: contactId={}, error={}",
                    contactId, e.getMessage());
            return List.of();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje zdarzenie kontaktu z bieżącym czasem jako started_at.
     */
    private ContactEvent buildEvent(UUID contactId, UUID tenantId, String stage, Map<String, Object> meta) {
        return ContactEvent.builder()
                .eventId(UUID.randomUUID())
                .contactId(contactId)
                .tenantId(tenantId)
                .stage(stage)
                .startedAt(Instant.now())
                .metadata(meta != null ? meta : new HashMap<>())
                .build();
    }

    /**
     * Zamyka ostatni otwarty etap danego stage. Loguje ostrzeżenie gdy brak otwartego etapu.
     */
    private void closeStage(UUID contactId, UUID tenantId, String stage) {
        try {
            int updated = repository.closeLastOpen(contactId, tenantId, stage, Instant.now());
            if (updated == 0) {
                log.warn("[ContactEventService] Brak otwartego etapu {} do zamknięcia: contactId={}",
                        stage, contactId);
            }
        } catch (Exception e) {
            log.warn("[ContactEventService] Błąd zamknięcia etapu {}: contactId={}, error={}",
                    stage, contactId, e.getMessage());
        }
    }
}
