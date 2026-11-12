package com.contactcenter.domain.etl;

import java.util.List;

/**
 * Serwis ETL: polling-based CDC (Change Data Capture) z PostgreSQL do Data Warehouse.
 *
 * <h3>Algorytm</h3>
 * <ol>
 *   <li>Odczyt {@code last_synced_at} z tabeli {@code etl_sync_state}.</li>
 *   <li>Pobranie rekordów {@code contact} gdzie {@code updated_at > last_synced_at}
 *       (lub {@code created_at > last_synced_at} gdy {@code updated_at IS NULL}).</li>
 *   <li>Filtrowanie: pomijamy rekordy anonimizowane RODO (contact bez customer lub
 *       powiązany customer ma first_name='ANONYMIZED') oraz nieaktywne.</li>
 *   <li>Transformacja do {@link ContactDwRow} i zapis przez {@link DataWarehouseWriter}.</li>
 *   <li>Aktualizacja {@code last_synced_at} do max(updated_at) przetworzonych rekordów.</li>
 * </ol>
 *
 * <h3>Idempotentność</h3>
 * <p>Upsert po {@code contact_id} zapewnia, że ponowne przetworzenie tych samych rekordów
 * nie tworzy duplikatów w DW.
 *
 * <h3>Multi-tenancy</h3>
 * <p>ETL jest zadaniem systemowym – odpytuje wszystkie tenanty jednocześnie.
 * Brak TenantContext – zapytanie natywne nie używa RLS (tabela ETL nie ma polityk RLS).
 * Tenant_id jest uwzględniany w danych wyjściowych ({@link ContactDwRow#tenantId()}).
 *
 * <h3>Alert monitoringowy</h3>
 * <p>Lag = wiek najstarszej niezsynchronizowanej zmiany w źródle (BE-212; brak zmian = 0). Dodatkowo
 * {@code checkJobLiveness} alarmuje o stanie ERROR i nieaktywnym jobie ({@code last_run_at} za stary);
 * payload zawiera {@code alertType} = DATA_LAG | JOB_STALE | JOB_ERROR.
 * Gdy lag > 30 min, publikowany jest event do RabbitMQ (exchange {@code cc.events},
 * routing key {@code etl.lag.alert}). Logowany jest WARN niezależnie od RabbitMQ.
 *
 * <h3>Payload {@code etl.lag.alert} (JSON, kontrakt)</h3>
 * <table>
 *   <caption>Pola payloadu per alertType (X = obecne)</caption>
 *   <tr><th>pole</th><th>DATA_LAG</th><th>JOB_STALE</th><th>JOB_ERROR</th><th>opis</th></tr>
 *   <tr><td>alertType</td><td>X</td><td>X</td><td>X</td><td>DATA_LAG | JOB_STALE | JOB_ERROR</td></tr>
 *   <tr><td>tableName</td><td>X</td><td>X</td><td>X</td><td>tabela źródłowa ({@code contact}, {@code campaign_contact})</td></tr>
 *   <tr><td>alertedAt</td><td>X</td><td>X</td><td>X</td><td>ISO-8601, moment wysłania</td></tr>
 *   <tr><td>lagMinutes</td><td>X</td><td></td><td></td><td>wiek najstarszej niezsynchronizowanej zmiany (min)</td></tr>
 *   <tr><td>lastSyncedAt</td><td>X</td><td></td><td></td><td>znacznik CDC (ISO-8601)</td></tr>
 *   <tr><td>status</td><td></td><td>X</td><td>X</td><td>status z {@code etl_sync_state} (JOB_STALE: "" gdy brak)</td></tr>
 *   <tr><td>staleMinutes</td><td></td><td>X</td><td></td><td>minuty od ostatniej aktywności joba</td></tr>
 *   <tr><td>lastRunAt</td><td></td><td>X</td><td>X</td><td>ISO-8601 ostatniej aktywności ("" gdy brak; w JOB_ERROR)</td></tr>
 *   <tr><td>errorMessage</td><td></td><td></td><td>X</td><td>skrócone do 200 znaków ("" gdy brak)</td></tr>
 * </table>
 *
 * <h3>Cooldown alertów (BE-214)</h3>
 * <p>Wiadomość dla pary (tabela, alertType) jest wysyłana raz na {@code etl.sync.alert-cooldown-ms} (domyślnie
 * 15 min) - Redis {@code SET NX PX}, klucz {@code etl:alert:{table}:{alertType}}, wspólny dla wszystkich instancji.
 * Powrót do normy (cykl DONE, brak ERROR/STALE, lag poniżej progu) kasuje klucz, więc kolejne wejście w stan
 * (np. DONE -> ERROR) alarmuje natychmiast. Awaria Redis = fail-open (alert wysyłany, WARN w logu).
 * Log {@code [ETL][ALERT]} jest pisany przy każdym sprawdzeniu, deduplikowany jest tylko komunikat do brokera.
 *
 * <h3>Gwarancje i granice CDC (BE-213, BE-216)</h3>
 * <p>Wiersz jest czytany, gdy {@code effective_ts <= LEAST(NOW(), początek najstarszej otwartej transakcji piszącej)
 * - safety-window}. Dzięki temu transakcja otwarta dowolnie długo (np. import w jednej transakcji) nie zostanie
 * pominięta: jej wiersze mają znacznik {@code >= xact_start}, więc kursor nie wyprzedzi ich przed commitem. Koszt:
 * długa, niezwiązana transakcja pisząca wstrzymuje kursor (lag rośnie, po 30 min alert DATA_LAG). Szczegóły i
 * ograniczenia (widoczność {@code pg_stat_activity} pod rolą bez {@code pg_read_all_stats}, transakcja bez xid):
 * Javadoc pola {@code EtlSyncServiceImpl#openTransactionGuardEnabled} i ARCHITECTURE.md.
 */
public interface EtlSyncService {

    /** Lag powyżej którego emitowany jest alert (minuty). */
    long LAG_ALERT_THRESHOLD_MINUTES = 30;

    /** Nazwa tabeli źródłowej kontaktów (klucz w etl_sync_state). */
    String TABLE_CONTACT = "contact";

    /** Nazwa tabeli rekordów kampanii (klucz w etl_sync_state). */
    String TABLE_CAMPAIGN_CONTACT = "campaign_contact";

    /**
     * Synchronizuje jedną tabelę: odczyt -> transformacja -> upsert -> aktualizacja stanu.
     *
     * <p>Operacja odbywa się w osobnych transakcjach:
     * <ol>
     *   <li>Transakcja 1: blokada wiersza + oznaczenie RUNNING.</li>
     *   <li>Poza transakcją: pobranie danych + zapis do DW (upsert ma własną transakcję).</li>
     *   <li>Transakcja 2: aktualizacja stanu na DONE lub ERROR.</li>
     * </ol>
     *
     * @param tableName nazwa tabeli do synchronizacji
     */
    void syncTable(String tableName);

    /**
     * Zwraca aktualny status synchronizacji wszystkich tabel ETL.
     *
     * <p>Payload (BE-223, pola żywotności addytywne i nullable): {@code stale} (Boolean, ta sama reguła co alert
     * JOB_STALE; null gdy job nigdy nie ruszył), {@code lastActivityAt} (max(last_run_at, heartbeat updated_at)),
     * {@code livenessThresholdMs} (z {@code etl.sync.liveness-threshold-ms}). Brak osobnej flagi wyłączenia ETL -
     * gdy cykle nie chodzą, heartbeat się nie odświeża i {@code stale} przechodzi w true po upływie progu.
     *
     * @return lista statusów per tabela
     */
    List<EtlTableStatus> getStatus();
}
