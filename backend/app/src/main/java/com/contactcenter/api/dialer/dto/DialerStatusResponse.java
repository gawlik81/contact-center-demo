package com.contactcenter.api.dialer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Odpowiedź REST dla endpointu {@code GET /api/dialer/status}.
 *
 * <p>Zawiera informacje o aktywnych kampaniach dialera dla tenanta
 * oraz liczbie aktualnie obsługiwanych połączeń wychodzących.
 *
 * @param tenantId         UUID tenanta
 * @param activeCampaigns  lista aktywnych kampanii (status=RUNNING) z metrykami
 * @param totalActiveCallsłączna liczba połączeń inicjowanych przez dialer w tej chwili
 * @param generatedAt      moment wygenerowania odpowiedzi
 */
public record DialerStatusResponse(
        UUID tenantId,
        List<ActiveCampaignSummary> activeCampaigns,
        int totalActiveCalls,
        Instant generatedAt
) {

    /**
     * Podsumowanie aktywnej kampanii widoczne w statusie dialera.
     *
     * @param campaignId    UUID kampanii
     * @param name          nazwa kampanii
     * @param pendingCount  liczba kontaktów PENDING do zadzwonienia
     * @param dialingCount  liczba kontaktów aktualnie w stanie DIALING
     * @param completedCount liczba kontaktów zakończonych (COMPLETED + NO_ANSWER + FAILED)
     * @param inSchedule    czy kampania aktualnie mieści się w oknie harmonogramu
     */
    public record ActiveCampaignSummary(
            UUID campaignId,
            String name,
            long pendingCount,
            long dialingCount,
            long completedCount,
            boolean inSchedule
    ) {}
}
