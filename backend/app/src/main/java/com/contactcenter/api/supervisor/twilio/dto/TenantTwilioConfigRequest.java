package com.contactcenter.api.supervisor.twilio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TenantTwilioConfigRequest(
        @NotBlank(message = "accountSid jest wymagany")
        @Pattern(regexp = "^AC[0-9a-fA-F]{32}$", message = "Invalid Twilio Account SID format")
        String accountSid,

        @NotBlank(message = "authToken jest wymagany")
        String authToken,

        String apiKeySid,
        String apiKeySecret,
        String twimlAppSid,

        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone number must be in E.164 format")
        String phoneNumber,

        String statusCallbackUrl
) {}
