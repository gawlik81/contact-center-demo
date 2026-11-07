package com.contactcenter.domain.user;

import java.util.UUID;

/**
 * Internal (in-JVM, Spring) event: an agent saved AVAILABLE while already AVAILABLE (BE-166).
 *
 * <p>Deliberately NOT a RabbitMQ message: the user-facing {@code agent.status.changed} (WS to the agent and
 * supervisors, dialer) must not fire without a real transition. The only consumer is the routing retry of
 * QUEUED contacts, which used to piggy-back on that event.
 *
 * <p>Also published by {@code ContactServiceImpl} when an agent frees an async slot (BE-156): the name is kept
 * (other code depends on it); {@code userId} is informational, routing runs a backlog pass for the whole tenant
 * and coalesces concurrent requests per tenant.
 */
public record AgentAvailabilityRefreshedEvent(UUID userId, UUID tenantId) {
}
