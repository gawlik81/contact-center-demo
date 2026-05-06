package com.contactcenter.api.supervisor.twilio.dto;

import java.time.Instant;

public record TwilioConnectionTestResult(
        boolean success,
        String message,
        Instant testedAt
) {}
