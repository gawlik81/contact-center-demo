package com.contactcenter.api.campaign.dto;

import com.contactcenter.domain.campaign.Campaign;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi kampanii.
 *
 * <p>Mapuje encję {@link Campaign} na format JSON zgodny z PRD.
 */
public record CampaignResponse(
        UUID campaignId,
        UUID tenantId,
        String name,
        String type,
        String dialerType,
        Map<String, Object> schedule,
        String status,
        UUID queueId,
        List<Map<String, Object>> dispositionCodes,
        int maxAttempts,
        int retryDelayMinutes,
        int ringTimeoutSeconds,
        String callerId,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        boolean allAgents,
        int assignedAgentsCount,
        int totalRecords,
        int completedRecords,
        int remainingRecords
) {

    /**
     * Fabryka tworząca DTO z encji JPA (bez danych przypisania i bez statystyk rekordów — wszystko 0).
     *
     * @param campaign encja kampanii
     * @return zmapowane DTO odpowiedzi
     */
    public static CampaignResponse from(Campaign campaign) {
        return from(campaign, 0, 0, 0, 0);
    }

    /**
     * Fabryka tworząca DTO z encji JPA z liczbą przypisań (bez statystyk rekordów).
     *
     * @param campaign            encja kampanii
     * @param assignedAgentsCount suma bezpośrednich agentów + grup (0 gdy all_agents=true)
     * @return zmapowane DTO odpowiedzi
     */
    public static CampaignResponse from(Campaign campaign, int assignedAgentsCount) {
        return from(campaign, assignedAgentsCount, 0, 0, 0);
    }

    /**
     * Fabryka tworząca DTO z encji JPA z pełnym zestawem danych przypisania i statystyk rekordów.
     *
     * @param campaign            encja kampanii
     * @param assignedAgentsCount suma bezpośrednich agentów + grup (0 gdy all_agents=true)
     * @param totalRecords        łączna liczba rekordów kontaktów kampanii
     * @param completedRecords    liczba rekordów ze statusem COMPLETED
     * @param remainingRecords    liczba rekordów ze statusami PENDING + CALLBACK + NO_ANSWER
     * @return zmapowane DTO odpowiedzi
     */
    public static CampaignResponse from(Campaign campaign, int assignedAgentsCount,
                                        int totalRecords, int completedRecords, int remainingRecords) {
        return new CampaignResponse(
                campaign.getCampaignId(),
                campaign.getTenantId(),
                campaign.getName(),
                campaign.getType(),
                campaign.getDialerType(),
                campaign.getSchedule(),
                campaign.getStatus(),
                campaign.getQueueId(),
                campaign.getDispositionCodes(),
                campaign.getMaxAttempts(),
                campaign.getRetryDelayMinutes(),
                campaign.getRingTimeoutSeconds(),
                campaign.getCallerId(),
                campaign.getCreatedBy(),
                campaign.getCreatedAt(),
                campaign.getUpdatedAt(),
                campaign.isAllAgents(),
                campaign.isAllAgents() ? 0 : assignedAgentsCount,
                totalRecords,
                completedRecords,
                remainingRecords
        );
    }
}
