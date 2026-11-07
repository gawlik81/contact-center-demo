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
    AGENT_AT_LIMIT
}
