package com.contactcenter.domain.etl;

import java.time.Instant;

/**
 * Status synchronizacji ETL dla jednej tabeli – używany przez endpoint
 * {@code GET /api/admin/etl/status}.
 *
 * @param tableName      nazwa tabeli źródłowej
 * @param lastSyncedAt   pozycja CDC (max updated_at przetworzonych rekordów)
 * @param lastRunAt      czas ostatniego uruchomienia zadania
 * @param lastRowCount   liczba wierszy przetworzonych w ostatnim cyklu
 * @param status         status: IDLE | RUNNING | DONE | ERROR
 * @param lagMinutes     lag w minutach (now - lastSyncedAt), null gdy brak danych
 * @param errorMessage   komunikat błędu gdy status=ERROR (null w pozostałych)
 */
public record EtlTableStatus(
        String tableName,
        Instant lastSyncedAt,
        Instant lastRunAt,
        long lastRowCount,
        String status,
        Long lagMinutes,
        String errorMessage
) {}
