package com.contactcenter.api.agentbreak.dto;

import java.util.List;

/**
 * Odpowiedź endpointu GET /api/agent/calendar (BE-051).
 *
 * <p>Agreguje trzy źródła danych kalendarza agenta:
 * <ul>
 *   <li>{@code callbacks} – zaplanowane oddzwonienia agenta w zakresie dat</li>
 *   <li>{@code campaigns} – kampanie powiązane z agentem przez kolejkę (bez COMPLETED/CANCELLED)</li>
 *   <li>{@code breaks} – przerwy agenta w zakresie dat</li>
 * </ul>
 */
public record AgentCalendarResponse(
        List<CalendarCallbackItem> callbacks,
        List<CalendarCampaignItem> campaigns,
        List<CalendarBreakItem> breaks
) {}
