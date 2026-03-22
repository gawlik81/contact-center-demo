package com.contactcenter.api.reports;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Pojedynczy wiersz raportu agenta – agregacja dzienna.
 *
 * <p>Reprezentuje zagregowane statystyki jednego agenta dla jednego kanału
 * komunikacji w danym dniu. Zwracany przez {@link ReportsController#getAgentReport}.
 *
 * @param agentId                UUID agenta
 * @param agentName              pełne imię i nazwisko agenta (lub email jeśli brak)
 * @param date                   dzień agregacji
 * @param contactsCount          liczba zakończonych kontaktów
 * @param avgHandleTimeSeconds   średni czas obsługi (duration_seconds) w sekundach
 * @param avgWaitTimeSeconds     średni czas oczekiwania klienta (assigned_at - queued_at) w sekundach
 * @param firstContactResolution wskaźnik FCR: % kontaktów bez CALLBACK/TRANSFER/ESCALATE
 * @param channel                kanał komunikacji (PHONE, EMAIL, CHAT, SOCIAL itp.)
 */
public record AgentReportRow(
        UUID agentId,
        String agentName,
        LocalDate date,
        long contactsCount,
        double avgHandleTimeSeconds,
        double avgWaitTimeSeconds,
        double firstContactResolution,
        String channel
) {}
