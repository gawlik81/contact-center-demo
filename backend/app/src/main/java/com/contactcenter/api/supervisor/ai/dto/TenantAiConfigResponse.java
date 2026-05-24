package com.contactcenter.api.supervisor.ai.dto;

import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.domain.model.TenantAiConfig;

import java.time.Instant;
import java.util.UUID;

public record TenantAiConfigResponse(
        UUID id,
        UUID tenantId,
        AiProvider provider,
        String apiKeyMasked,            // format: "●●●●●●●●...xxxx" (ostatnie 4 znaki)
        String modelName,
        String azureEndpoint,
        String azureDeploymentName,
        String summaryPromptTemplate,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantAiConfigResponse from(TenantAiConfig entity) {
        return new TenantAiConfigResponse(
                entity.getId(),
                entity.getTenantId(),
                entity.getProvider(),
                mask(entity.getApiKeyEncrypted()),
                entity.getModelName(),
                entity.getAzureEndpoint(),
                entity.getAzureDeploymentName(),
                entity.getSummaryPromptTemplate(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return value == null ? null : "●●●●";
        }
        return "●●●●●●●●..." + value.substring(value.length() - 4);
    }
}
