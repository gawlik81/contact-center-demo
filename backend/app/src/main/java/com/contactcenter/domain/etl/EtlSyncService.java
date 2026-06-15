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
 * <p>Gdy lag > 30 min, publikowany jest event do RabbitMQ (exchange {@code cc.events},
 * routing key {@code etl.lag.alert}). Logowany jest WARN niezależnie od RabbitMQ.
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
     * @return lista statusów per tabela
     */
    List<EtlTableStatus> getStatus();
}
