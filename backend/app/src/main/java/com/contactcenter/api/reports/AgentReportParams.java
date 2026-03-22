package com.contactcenter.api.reports;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Parametry żądania raportu agentów – walidowane przez Spring Validation.
 *
 * <p>Reguły walidacji:
 * <ul>
 *   <li>{@code dateFrom} i {@code dateTo} są wymagane (@NotNull)</li>
 *   <li>Zakres nie może przekraczać 90 dni – sprawdzane w {@link ReportsService}</li>
 *   <li>{@code size} ograniczone do max 100 wierszy na stronę</li>
 * </ul>
 *
 * @param dateFrom data początkowa zakresu (ISO date, wymagane)
 * @param dateTo   data końcowa zakresu (ISO date, wymagane)
 * @param agentId  filtr po agencie (null = wszystkie)
 * @param queueId  filtr po kolejce (null = wszystkie)
 * @param channel  filtr po kanale: CALL/EMAIL/CHAT/SOCIAL (null = wszystkie)
 * @param page     numer strony (0-based, domyślnie 0)
 * @param size     rozmiar strony (domyślnie 20, max 100)
 */
public record AgentReportParams(
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo,
        UUID agentId,
        UUID queueId,
        String channel,
        @Min(0) int page,
        @Min(1) @Max(100) int size
) {

    /** Wylicza offset dla zapytań paginowanych. */
    public int offset() {
        return page * size;
    }
}
