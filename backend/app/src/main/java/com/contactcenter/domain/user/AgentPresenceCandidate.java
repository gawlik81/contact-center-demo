package com.contactcenter.domain.user;

import com.contactcenter.domain.user.AppUser.UserStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-210: read-only projection of an agent for the presence sweeper - only what the decision needs, so that
 * password hash, MFA secret, skills etc. never get loaded (or logged on a Hibernate failure) every 30 s.
 */
public record AgentPresenceCandidate(
        UUID id,
        UUID tenantId,
        UserStatus status,
        Instant statusChangedAt,
        Instant lastLoginAt
) {
}
