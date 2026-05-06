package com.contactcenter.domain.event;

import java.util.UUID;

public record TwilioConfigChangedEvent(UUID tenantId) {}
