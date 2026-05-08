package com.contactcenter.api.campaign.dto;

import com.contactcenter.domain.model.Campaign;

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
        Instant updatedAt
) {

    /**
     * Fabryka tworząca DTO z encji JPA.
     *
     * @param campaign encja kampanii
     * @return zmapowane DTO odpowiedzi
     */
    public static CampaignResponse from(Campaign campaign) {
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
                campaign.getUpdatedAt()
        );
    }
}
