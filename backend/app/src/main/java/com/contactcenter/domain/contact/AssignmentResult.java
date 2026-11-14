package com.contactcenter.domain.contact;

/**
 * Outcome of a conditional contact-to-agent assignment (BE-159 / BE-156).
 */
public enum AssignmentResult {
    /** This caller won the contact; the row is now ASSIGNED to the agent. */
    ASSIGNED,
    /** The contact is no longer QUEUED / already has an agent (a parallel path got it first). */
    CONTACT_UNAVAILABLE,
    /** The agent already holds the maximum number of open async contacts in the queue (BE-156). */
    AGENT_AT_LIMIT,
    /**
     * Voice exclusivity (BE-230): the agent already holds an open voice contact (or a DIALING dialer record), so
     * a PHONE contact must not be routed to them. The contact stays QUEUED; routing tries another agent.
     */
    AGENT_BUSY,
    /**
     * The per-agent advisory lock could not be taken within {@code agent-assignment.lock-timeout-ms} (BE-230 S5):
     * another transaction holds it for unusually long. Handled like {@link #AGENT_BUSY} (skip the agent, contact stays
     * QUEUED) but counted separately - it signals a stuck holder, not ordinary contention.
     */
    AGENT_LOCK_TIMEOUT
}
