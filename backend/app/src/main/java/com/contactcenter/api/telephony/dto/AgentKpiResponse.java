package com.contactcenter.api.telephony.dto;

/**
 * Statystyki KPI agenta za bieżący dzień (UTC).
 *
 * <p>Zwracane przez {@code GET /api/agent/me/kpi} – używane przez frontend
 * do wyświetlania paska KPI w top-navbar bez hardkodowanych wartości.
 *
 * @param contactsHandledToday łączna liczba zakończonych kontaktów agenta dziś
 * @param slaPercent           odsetek kontaktów gdzie czas oczekiwania (assigned_at - queued_at)
 *                             był krótszy niż 30 sekund; 0.0 gdy brak danych
 * @param avgHandleTimeSeconds średni czas obsługi (duration_seconds) zakończonych kontaktów dziś;
 *                             0.0 gdy brak danych
 * @param workTimeSeconds      czas pracy w sekundach liczony od pierwszego started_at dziś do NOW();
 *                             0 gdy agent nie obsłużył żadnego kontaktu dziś
 */
public record AgentKpiResponse(
    long contactsHandledToday,
    double slaPercent,
    double avgHandleTimeSeconds,
    long workTimeSeconds
) {
}
