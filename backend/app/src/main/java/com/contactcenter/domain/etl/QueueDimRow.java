package com.contactcenter.domain.etl;

import java.util.UUID;

/**
 * DTO reprezentujący jeden wiersz wymiaru kolejki w Data Warehouse.
 *
 * <p>Zasilany pełnym snapshotem aktywnych kolejek z tabeli {@code queue}
 * (is_active = TRUE). SCD Type 1 – brak historii zmian.
 *
 * <p>Kolumna {@code snapshot_at} (DateTime64 w ClickHouse) ustawiana na czas
 * synchronizacji – silnik {@code ReplacingMergeTree(snapshot_at)} deduplikuje
 * po {@code (tenant_id, queue_id)}, zachowując najnowszy rekord.
 *
 * @param queueId         UUID kolejki
 * @param tenantId        UUID tenanta
 * @param name            nazwa kolejki
 * @param routingStrategy strategia routingu (ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED)
 * @param isActive        czy kolejka aktywna
 */
public record QueueDimRow(
        UUID queueId,
        UUID tenantId,
        String name,
        String routingStrategy,
        boolean isActive
) {}
