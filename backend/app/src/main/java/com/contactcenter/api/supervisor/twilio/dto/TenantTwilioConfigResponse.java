package com.contactcenter.api.supervisor.twilio.dto;

import com.contactcenter.domain.model.TenantTwilioConfig;
import java.time.Instant;
import java.util.UUID;

public record TenantTwilioConfigResponse(
        UUID configId,
        UUID tenantId,
        String accountSid,
        String authToken,        // masked
        String apiKeySid,
        String apiKeySecret,     // masked
        String twimlAppSid,
        String phoneNumber,
        String statusCallbackUrl,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static TenantTwilioConfigResponse from(TenantTwilioConfig entity) {
        return new TenantTwilioConfigResponse(
                entity.getConfigId(),
                entity.getTenantId(),
                entity.getAccountSid(),
                mask(entity.getAuthToken()),
                entity.getApiKeySid(),
                mask(entity.getApiKeySecret()),
                entity.getTwimlAppSid(),
                entity.getPhoneNumber(),
                entity.getStatusCallbackUrl(),
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
