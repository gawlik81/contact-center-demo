package com.contactcenter.domain.routing;

import java.util.Set;
import java.util.UUID;

/**
 * Per-contact routing exclusions set when an agent returns an async contact to the queue
 * (BE-160, narrow slice of BE-156 anti-ping-pong): the releasing agent must not get the same
 * contact back right away. Entries expire after a configurable TTL; after that the contact
 * may be routed to that agent again.
 *
 * <p>Implementations must never throw: a store failure means "no exclusion" so routing keeps working.
 */
public interface ReleaseExclusionStore {

    /** Excludes {@code agentId} from receiving {@code contactId} until the TTL expires (adds to the set). */
    void exclude(UUID tenantId, UUID contactId, UUID agentId);

    /** Agents currently excluded for the contact; empty when none or when the store is unavailable. */
    Set<UUID> excludedAgents(UUID tenantId, UUID contactId);
}
