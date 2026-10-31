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
 * @param workTimeSeconds      czas pracy w sekundach liczony od OSTATNIEGO logowania agenta
 *                             ({@code app_user.last_login_at}, zapisywane po pełnym uwierzytelnieniu
 *                             wraz z MFA; refresh tokenu go nie zmienia) do teraz, nie więcej niż od
 *                             dzisiejszej północy UTC (gdy sesja przekracza północ, liczone od północy);
 *                             0 gdy brak zapisanego logowania. Niezależne od liczby obsłużonych kontaktów
 *                             (decyzja właściciela 2026-10-28, BE-158)
 */
public record AgentKpiResponse(
    long contactsHandledToday,
    double slaPercent,
    double avgHandleTimeSeconds,
    long workTimeSeconds
) {
}
