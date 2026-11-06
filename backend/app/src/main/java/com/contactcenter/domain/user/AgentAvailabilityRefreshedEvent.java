package com.contactcenter.domain.user;

import java.util.UUID;

/**
 * Internal (in-JVM, Spring) event: an agent saved AVAILABLE while already AVAILABLE (BE-166).
 *
 * <p>Deliberately NOT a RabbitMQ message: the user-facing {@code agent.status.changed} (WS to the agent and
 * supervisors, dialer) must not fire without a real transition. The only consumer is the routing retry of
 * QUEUED contacts, which used to piggy-back on that event.
 */
public record AgentAvailabilityRefreshedEvent(UUID userId, UUID tenantId) {
}
