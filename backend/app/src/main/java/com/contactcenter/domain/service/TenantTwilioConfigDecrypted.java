package com.contactcenter.domain.service;

import com.contactcenter.domain.model.TenantTwilioConfig;

public record TenantTwilioConfigDecrypted(
        String accountSid,
        String authToken,
        String apiKeySid,
        String apiKeySecret,
        String twimlAppSid,
        String phoneNumber,
        String statusCallbackUrl
) {
    public static TenantTwilioConfigDecrypted from(TenantTwilioConfig entity) {
        return new TenantTwilioConfigDecrypted(
                entity.getAccountSid(),
                entity.getAuthToken(),
                entity.getApiKeySid(),
                entity.getApiKeySecret(),
                entity.getTwimlAppSid(),
                entity.getPhoneNumber(),
                entity.getStatusCallbackUrl()
        );
    }
}
