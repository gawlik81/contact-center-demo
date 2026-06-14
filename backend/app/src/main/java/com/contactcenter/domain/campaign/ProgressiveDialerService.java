package com.contactcenter.domain.campaign;

import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.telephony.TelephonyAdapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Silnik Progressive Dialer – automatyczne dzwonienie dla kampanii wychodzących.
 *
 * <p>Nasłuchuje na eventy {@code agent.status.changed} z kolejki RabbitMQ.
 * Gdy agent zmienia status na AVAILABLE i istnieje aktywna kampania (status=RUNNING)
 * mieszcząca się w oknie harmonogramu, dialer pobiera następny kontakt PENDING
 * z listy kampanii i inicjuje połączenie przez {@link TelephonyAdapter}.
 *
 * <p>Ochrona przed race condition: {@link #initiateDialForAgent} ustawia klucz Redis
 * {@code dialer:agent:{agentId}} z TTL 60s (SET NX). Jeśli klucz istnieje → agent już
 * obsługiwany przez dialer, metoda zwraca bez działania. Dzięki temu zarówno eventy
 * RabbitMQ jak i cykliczny scheduler korzystają z tej samej blokady.
 *
 * <p>Aktywny warunkowo przez właściwość {@code dialer.enabled} (domyślnie: true).
 * Wyłącz przez {@code DIALER_ENABLED=false} w ENV vars.
 *
 * <p>Klucze Redis:
 * <ul>
 *   <li>{@code dialer:agent:{agentId}} → callSid, TTL 60s (guard przed duplikacją)</li>
 *   <li>{@code dialer:call:{callSid}} → JSON z campaignContactId/agentId/tenantId/campaignId, TTL 60s</li>
 *   <li>{@code dialer:timeout:{callSid}} → "", TTL = campaign.ringTimeoutSeconds (po wygaśnięciu = NO_ANSWER)</li>
 * </ul>
 */
public interface ProgressiveDialerService {

    /**
     * Nasłuchuje na zdarzenia zmiany statusu agenta z kolejki {@code cc.queue.agent-status}.
     *
     * <p>Gdy agent zmienia status na AVAILABLE:
     * <ol>
     *   <li>Sprawdza blokadę Redis (ochrona przed race condition)</li>
     *   <li>Wyszukuje aktywne kampanie (status=RUNNING) dla tenanta agenta</li>
     *   <li>Sprawdza harmonogram każdej kampanii</li>
     *   <li>Pobiera następny kontakt PENDING (pessimistic lock)</li>
     *   <li>Inicjuje połączenie przez TelephonyAdapter</li>
     *   <li>Zapisuje stan w Redis z TTL</li>
     * </ol>
     *
     * <p>Serwis działa w wątku RabbitMQ (brak TenantContext z HTTP filter).
     * TenantContext jest ustawiany jawnie na podstawie danych z eventu i czyszczony
     * w bloku finally.
     *
     * @param event zdarzenie zmiany statusu agenta
     */
    void onAgentStatusChanged(AgentStatusChangedEvent event);

    /**
     * Cyklicznie wyzwala logikę dialera dla wszystkich aktualnie AVAILABLE agentów.
     *
     * <p>Uzupełnienie event-driven triggeringu (RabbitMQ): obsługuje przypadki gdy
     * kontakt kampanijny trafił do kolejki zanim agent zmienił status lub gdy event zaginął.
     *
     * <p>Używa {@code fixedDelay} – kolejna iteracja startuje PO zakończeniu poprzedniej
     * (ochrona przed nakładaniem się przy wolnych tenantach).
     *
     * <p>Ochrona przed race condition jest w {@link #initiateDialForAgent} (Redis SET NX)
     * – jeśli agent jest już obsługiwany przez dialer, metoda zwróci bez działania.
     */
    void pollAvailableAgents();

    /**
     * Inicjuje połączenie wychodzące dla dostępnego agenta.
     *
     * <p>Przed właściwą logiką ustawia blokadę Redis (SET NX) dla agenta – chroni przed
     * race condition gdy {@code onAgentStatusChanged} i {@code pollAvailableAgents} wywołają
     * tę metodę równolegle dla tego samego agenta. Blokada jest zwalniana gdy:
     * <ul>
     *   <li>brak kampanii lub kontaktów (zwolnienie jawne)</li>
     *   <li>błąd po stronie wywołującego (zwolnienie przez wywołującego, nie tę metodę)</li>
     *   <li>wygaśnięcie TTL (60s, failsafe)</li>
     * </ul>
     * Gdy połączenie zostaje zainicjowane, blokada pozostaje aktywna przez cały czas trwania
     * połączenia (do momentu obsługi dyspozycji przez {@link DialerCallbackHandler}).
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     */
    void initiateDialForAgent(UUID agentId, UUID tenantId);

    /**
     * Sprawdza czy kampania aktualnie mieści się w oknie harmonogramu.
     *
     * <p>Analizuje pola JSONB: {@code start_date}, {@code end_date},
     * {@code active_days} (np. ["MON","TUE"]) i {@code active_hours}
     * ({@code from}, {@code to}).
     *
     * @param campaign kampania do sprawdzenia
     * @return true gdy kampania jest w oknie harmonogramu
     */
    boolean isInSchedule(Campaign campaign);

    /**
     * Zapisuje stan aktywnego połączenia dialera w Redis.
     *
     * <p>Klucz {@code dialer:call:{callSid}} zawiera CSV z danymi połączenia.
     * TTL = 60 sekund (z marginesem ponad timeout 30s).
     *
     * @param callSid           identyfikator sesji telefonicznej
     * @param campaignContactId UUID rekordu campaign_contact
     * @param campaignId        UUID kampanii
     * @param agentId           UUID agenta
     * @param tenantId          UUID tenanta
     */
    void saveCallState(String callSid, UUID campaignContactId, UUID campaignId, UUID agentId, UUID tenantId);

    /**
     * Ustawia klucz timeout w Redis dla połączenia dialera.
     *
     * <p>Po wygaśnięciu TTL zewnętrzny komponent (np. Redis Keyspace Notification
     * lub scheduled job) może zareagować na brak odpowiedzi. W tej implementacji
     * {@link DialerCallbackHandler} sprawdza klucz przy przetwarzaniu wyników połączeń.
     *
     * @param callSid        identyfikator sesji telefonicznej
     * @param timeoutSeconds czas oczekiwania na odebranie (konfigurowany per kampania)
     */
    void scheduleNoAnswerTimeout(String callSid, int timeoutSeconds);

    // =========================================================================
    // Dostęp do encji campaign_contact (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    /**
     * Zlicza rekordy campaign_contact pogrupowane po (campaign_id, status) jednym zapytaniem SQL.
     *
     * <p>Używane przez GET /api/dialer/status do budowy podsumowania dialera bez problemu N+1.
     *
     * @param tenantId    UUID tenanta (do RLS)
     * @param campaignIds lista UUID kampanii (musi być niepusta)
     * @param statuses    lista statusów do filtrowania
     * @return mapa campaign_id → mapa status → liczba rekordów
     */
    Map<UUID, Map<String, Long>> getContactCountsByStatus(UUID tenantId, List<UUID> campaignIds, List<String> statuses);

    /**
     * Pobiera rekordy dostępne do wybierania dla podanych kampanii manualnych (batch).
     *
     * @param tenantId    UUID tenanta (do RLS)
     * @param campaignIds lista UUID kampanii do sprawdzenia (musi być niepusta)
     * @return lista map kolumn: record_id, campaign_id, phone, first_name, last_name, status
     */
    List<Map<String, Object>> findPendingRecordsByCampaignIds(UUID tenantId, List<UUID> campaignIds);

    /**
     * Pobiera rekord campaign_contact z weryfikacją tenanta – do walidacji przed manualnym połączeniem.
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return Optional z mapą kolumn (record_id, phone, status, next_attempt_at) lub empty
     */
    Optional<Map<String, Object>> findRecordForManualDial(UUID recordId, UUID campaignId, UUID tenantId);

    /**
     * Oznacza rekord campaign_contact jako DIALING (przy starcie połączenia manualnego).
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    void markRecordAsDialing(UUID recordId, UUID campaignId, UUID tenantId);

    /**
     * Oznacza rekord campaign_contact jako ERROR (trwały błąd techniczny adaptera telefonii).
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    void markRecordAsError(UUID recordId, UUID campaignId, UUID tenantId);

    /**
     * Ustawia {@code last_contact_id} na rekordzie campaign_contact po wydzwonieniu (manualnym lub automatycznym).
     *
     * @param recordId   UUID rekordu campaign_contact
     * @param campaignId UUID kampanii
     * @param contactId  UUID nowo utworzonego kontaktu
     */
    void updateLastContactId(UUID recordId, UUID campaignId, UUID contactId);
}
