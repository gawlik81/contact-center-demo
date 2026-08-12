package com.contactcenter.domain.contact;

import java.time.Instant;
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
public interface ContactEventService {

    /**
     * Otwiera etap IVR dla kontaktu.
     *
     * @param contactId   UUID kontaktu
     * @param tenantId    UUID tenanta
     * @param ivrTreeId   UUID drzewa IVR (może być null)
     * @param ivrTreeName nazwa drzewa IVR (może być null)
     */
    void openIvr(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName);

    /**
     * Zamyka ostatni otwarty etap IVR dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void closeIvr(UUID contactId, UUID tenantId);

    /**
     * Otwiera etap VOICEBOT dla kontaktu.
     *
     * @param contactId   UUID kontaktu
     * @param tenantId    UUID tenanta
     * @param ivrTreeId   UUID drzewa IVR/voicebota (może być null)
     * @param ivrTreeName nazwa drzewa IVR/voicebota (może być null)
     */
    void openVoicebot(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName);

    /**
     * Zamyka ostatni otwarty etap VOICEBOT dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param outcome   wynik sesji voicebota (np. "TRANSFERRED", "ABANDONED") – pomijany przy zamykaniu,
     *                  powinien być przekazany do metadata przy openVoicebot
     */
    void closeVoicebot(UUID contactId, UUID tenantId, String outcome);

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
    void openQueue(UUID contactId, UUID tenantId, UUID queueId, String queueName, Instant queuedAt);

    /**
     * Zamyka ostatni otwarty etap QUEUE dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void closeQueue(UUID contactId, UUID tenantId);

    /**
     * Otwiera etap AGENT (rozmowa z agentem) dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param agentId   UUID agenta
     * @param agentName imię i nazwisko agenta (może być null)
     */
    void openAgent(UUID contactId, UUID tenantId, UUID agentId, String agentName);

    /**
     * Zamyka ostatni otwarty etap AGENT dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void closeAgent(UUID contactId, UUID tenantId);

    /**
     * Otwiera etap ON_HOLD (kontakt zawieszony przez agenta).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void openHold(UUID contactId, UUID tenantId);

    /**
     * Zamyka ostatni otwarty etap ON_HOLD dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void closeHold(UUID contactId, UUID tenantId);

    /**
     * Otwiera etap CONSULTING (konsultacja z innym agentem lub numerem zewnętrznym).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param target    cel konsultacji (numer telefonu lub identyfikator agenta)
     */
    void openConsulting(UUID contactId, UUID tenantId, String target);

    /**
     * Otwiera etap CONSULTING z dodatkowymi metadanymi (np. target_type, target_agent_name).
     *
     * <p>Metadane z parametru {@code extraMeta} są scalane z domyślnymi (target, transfer_type).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param target    cel konsultacji (numer telefonu lub identyfikator agenta)
     * @param extraMeta dodatkowe metadane do scalenia (może być null)
     */
    void openConsulting(UUID contactId, UUID tenantId, String target, Map<String, Object> extraMeta);

    /**
     * Zamyka ostatni otwarty etap CONSULTING dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void closeConsulting(UUID contactId, UUID tenantId);

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
    void recordTransfer(UUID contactId, UUID tenantId,
                        String target, String transferType, String targetAgentName);

    /**
     * Rejestruje zdarzenie transferu kontaktu z dodatkowymi metadanymi.
     *
     * <p>Przeciążona wersja pozwalająca przekazać pełny zestaw metadanych specyficznych
     * dla typu celu (target_type, target_agent_id, target_queue_id itp.).
     * Metadane z parametru {@code extraMeta} są scalane z domyślnymi (target, transfer_type).
     *
     * @param contactId       UUID kontaktu
     * @param tenantId        UUID tenanta
     * @param target          cel transferu (numer telefonu, kolejka lub identyfikator agenta)
     * @param transferType    typ transferu (np. "BLIND", "ATTENDED")
     * @param targetAgentName imię agenta docelowego (może być null)
     * @param extraMeta       dodatkowe metadane do scalenia (może być null)
     */
    void recordTransfer(UUID contactId, UUID tenantId,
                        String target, String transferType, String targetAgentName,
                        Map<String, Object> extraMeta);

    /**
     * Zwraca pełną historię etapów kontaktu posortowaną chronologicznie.
     *
     * <p>Przy błędzie odczytu zwraca pustą listę – nie przerywa głównego przepływu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista zdarzeń posortowana po started_at ASC (pusta przy błędzie)
     */
    List<ContactEvent> getHistory(UUID contactId, UUID tenantId);

    /**
     * Usuwa batch zdarzeń kontaktu tenanta starszych niż {@code cutoff} (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS).
     *
     * @param tenantId  UUID tenanta
     * @param cutoff    granica czasowa – usuwane są zdarzenia z {@code started_at < cutoff}
     * @param batchSize maksymalna liczba wierszy usuwanych w jednym wywołaniu
     * @return liczba usuniętych wierszy (0 = brak kwalifikujących się wierszy)
     */
    int purgeOlderThan(UUID tenantId, Instant cutoff, int batchSize);
}
