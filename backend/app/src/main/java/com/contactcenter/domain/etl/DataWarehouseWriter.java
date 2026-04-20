package com.contactcenter.domain.etl;

import java.util.List;

/**
 * Port abstrakcji zapisu do Data Warehouse.
 *
 * <p>Implementacje:
 * <ul>
 *   <li>{@code PostgresDwWriter} – zapis do lokalnej tabeli {@code contacts_dw}
 *       (fallback gdy ClickHouse niedostępne lub w środowisku dev)</li>
 *   <li>{@code ClickHouseDwWriter} – zapis przez JDBC do zewnętrznego ClickHouse
 *       (aktywowany profilem Spring lub przez konfigurację)</li>
 * </ul>
 *
 * <p>Implementacja musi być idempotentna – ponowne wywołanie z tymi samymi danymi
 * nie może tworzyć duplikatów (upsert po {@code contact_id}).
 */
public interface DataWarehouseWriter {

    /**
     * Wykonuje upsert listy wierszy kontaktów do Data Warehouse.
     *
     * <p>Operacja musi być idempotentna – ten sam {@code contactId} może pojawić się
     * wielokrotnie (np. przy retransmisji lub ponownym przetworzeniu).
     *
     * @param rows lista wierszy do wstawienia / aktualizacji
     * @throws DataWarehouseException gdy zapis się nie powiedzie
     */
    void upsert(List<ContactDwRow> rows);

    /**
     * Wstawia lub aktualizuje rekordy kampanii w {@code campaigns_dw}.
     *
     * <p>Deduplikacja po {@code (campaign_id, record_id)} – ReplacingMergeTree
     * w ClickHouse lub ON CONFLICT w PostgreSQL.
     *
     * @param rows lista wierszy – nie może być null
     * @throws DataWarehouseException gdy zapis się nie powiedzie
     */
    void upsertCampaigns(List<CampaignDwRow> rows);

    /**
     * Wstawia snapshot wymiarów agentów do {@code agent_dim}.
     *
     * <p>Pełny refresh per cykl – ReplacingMergeTree(snapshot_at) deduplikuje
     * po {@code (tenant_id, agent_id)}, zachowując najnowszy snapshot.
     *
     * @param rows lista wierszy – nie może być null
     * @throws DataWarehouseException gdy zapis się nie powiedzie
     */
    void upsertAgentDim(List<AgentDimRow> rows);

    /**
     * Wstawia snapshot wymiarów kolejek do {@code queue_dim}.
     *
     * <p>Pełny refresh per cykl – ReplacingMergeTree(snapshot_at) deduplikuje
     * po {@code (tenant_id, queue_id)}, zachowując najnowszy snapshot.
     *
     * @param rows lista wierszy – nie może być null
     * @throws DataWarehouseException gdy zapis się nie powiedzie
     */
    void upsertQueueDim(List<QueueDimRow> rows);
}
