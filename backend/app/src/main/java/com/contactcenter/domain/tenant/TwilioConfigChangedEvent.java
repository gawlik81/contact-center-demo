package com.contactcenter.domain.tenant;

import java.util.UUID;

public record TwilioConfigChangedEvent(UUID tenantId) {}
