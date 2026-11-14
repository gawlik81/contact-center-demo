package com.contactcenter.domain.campaign;

/**
 * Outcome of an agent-exclusivity gate evaluated under the per-agent advisory lock (BE-230 P1) by the callback
 * executor and the manual dialer.
 */
public enum AgentGate {
    /** The agent holds no voice load (and, for a claim, the write was done). */
    FREE,
    /** The agent already holds an open PHONE contact / DIALING record - real contention. */
    BUSY,
    /** The per-agent lock stayed held beyond {@code agent-assignment.lock-timeout-ms}; nothing was written. */
    LOCK_TIMEOUT,
    /**
     * Manual dialer only: the agent is free, but the record is no longer dialable (another agent / the progressive
     * dialer claimed it first, or it left PENDING / NO_ANSWER / FAILED); nothing was written.
     */
    RECORD_UNAVAILABLE
}
