package com.contactcenter.domain.etl;

import io.swagger.v3.oas.annotations.media.Schema;

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
 * @param lagMinutes     lag danych w minutach: wiek najstarszej niezsynchronizowanej zmiany w źródle (0 = brak zaległości,
 *                       BE-212), null dla tabel bez definicji CDC
 * @param errorMessage   komunikat błędu gdy status=ERROR (null w pozostałych)
 * @param stale          BE-223: czy job jest "zastały" - liczone na serwerze TA SAMA regułą co alert JOB_STALE
 *                       ({@code EtlSyncServiceImpl#evaluateStale}, wiek liczony zegarem bazy {@code NOW()}): ostatnia aktywność starsza niż
 *                       {@code livenessThresholdMs}. Niezależne od statusu (RUNNING ze starym heartbeatem = true,
 *                       RUNNING ze świeżym = false; ERROR raportowany regułą czasową). {@code null} gdy job nigdy
 *                       nie ruszył ({@code lastRunAt == null}) - ZNANE OGRANICZENIE: scheduler martwy od instalacji (brak
 *                       last_run_at) nie jest wykrywany ani w statusie, ani przez alert (bez zmiany semantyki BE-212).
 *                       Ta sama reguła CZASOWA co JOB_STALE; alert dodatkowo pomija tabele nie-CDC i status ERROR
 *                       (alarmowany osobno jako JOB_ERROR), a status raportuje {@code stale} dla każdego wiersza. Heartbeat pisze tylko działający cykl ETL, więc gdy
 *                       joby nie chodzą (aplikacja zatrzymana/scheduler martwy) wartość przechodzi w true po
 *                       przekroczeniu progu - to zamierzone: pipeline faktycznie nie pracuje
 * @param lastActivityAt BE-223: max(last_run_at, updated_at heartbeat), ISO-8601 UTC; {@code null} gdy job nigdy nie ruszył
 *                       Uwaga: globalne {@code default-property-inclusion: non_null} pomija pola null w JSON
 *                       (brak pola == null), jak dla {@code lagMinutes}/{@code lastRunAt}
 * @param livenessThresholdMs BE-223: próg żywotności z {@code etl.sync.liveness-threshold-ms} (ms)
 */
public record EtlTableStatus(
        String tableName,
        Instant lastSyncedAt,
        Instant lastRunAt,
        long lastRowCount,
        String status,
        Long lagMinutes,
        String errorMessage,
        @Schema(nullable = true, description = "BE-223: czy job jest zastaly (ostatnia aktywnosc starsza niz "
                + "livenessThresholdMs; zegar bazy). Ta sama regula czasowa co alert JOB_STALE (alert pomija tabele "
                + "nie-CDC i ERROR). Pole POMIJANE w JSON gdy job nigdy nie ruszyl (non_null).")
        Boolean stale,
        @Schema(nullable = true, type = "string", format = "date-time", description = "BE-223: max(last_run_at, "
                + "heartbeat updated_at), ISO-8601 UTC. Pole POMIJANE w JSON gdy job nigdy nie ruszyl.")
        Instant lastActivityAt,
        @Schema(nullable = true, description = "BE-223: prog zywotnosci z etl.sync.liveness-threshold-ms (ms)")
        Long livenessThresholdMs
) {}
