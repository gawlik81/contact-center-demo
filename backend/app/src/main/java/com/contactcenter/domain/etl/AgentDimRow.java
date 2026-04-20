package com.contactcenter.domain.etl;

import java.util.List;
import java.util.UUID;

/**
 * DTO reprezentujący jeden wiersz wymiaru agenta w Data Warehouse.
 *
 * <p>Zasilany pełnym snapshotem aktywnych agentów z tabeli {@code app_user}
 * (role = 'AGENT', is_deleted = FALSE). SCD Type 1 – brak historii zmian.
 *
 * <p>Kolumna {@code snapshot_at} (DateTime64 w ClickHouse) ustawiana na czas
 * synchronizacji – silnik {@code ReplacingMergeTree(snapshot_at)} deduplikuje
 * po {@code (tenant_id, agent_id)}, zachowując najnowszy rekord.
 *
 * @param agentId  UUID agenta (app_user.user_id)
 * @param tenantId UUID tenanta
 * @param fullName imię i nazwisko agenta (first_name + ' ' + last_name)
 * @param role     rola użytkownika (AGENT, SUPERVISOR, ADMIN)
 * @param skills   lista skills agenta (np. ["SALES", "TECH_SUPPORT"])
 * @param isActive czy konto aktywne (status != INACTIVE i is_deleted = false)
 */
public record AgentDimRow(
        UUID agentId,
        UUID tenantId,
        String fullName,
        String role,
        List<String> skills,
        boolean isActive
) {}
