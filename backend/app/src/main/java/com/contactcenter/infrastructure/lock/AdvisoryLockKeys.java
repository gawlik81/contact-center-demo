package com.contactcenter.infrastructure.lock;

/**
 * Registry of PostgreSQL advisory-lock key spaces used by the application (BE-173).
 *
 * <p><strong>Why a registry:</strong> advisory locks live in ONE global key space per database; two unrelated
 * features that happen to pick the same number block each other. Every use in the code base must be listed here.
 *
 * <p><strong>Two forms, two disjoint key spaces</strong> (PostgreSQL docs, "Advisory Locks"): the single-key form
 * {@code pg_advisory_xact_lock(bigint)} and the two-key form {@code pg_advisory_xact_lock(int, int)} never collide
 * with each other (different lock-tag type). Existing uses all take the {@code bigint} form with
 * {@code hashtextextended(<string>, 0)}:
 * <ul>
 *   <li>{@code ContactRepository#assignIfQueued} / async-limit counter: {@code "<tenantId>:<agentId>"}</li>
 *   <li>{@code EmailMessageRepository}: dedup key of the e-mail message</li>
 * </ul>
 * New locks use the TWO-KEY form: {@code key1} = a namespace constant from this class (one per feature),
 * {@code key2} = {@code hashtext(<entity id>)} computed in SQL. A 32-bit hash can collide between two entities of
 * the same namespace - the only effect is that two unrelated campaigns are (briefly) serialised; it never causes a
 * wrong result, because the lock only orders work and correctness is guaranteed by the conditional writes.
 *
 * <p>Always the {@code xact} variant: released with the transaction (commit, rollback, connection loss), safe with
 * PgBouncer in transaction-pooling mode (BE-181). Never use session-level advisory locks.
 */
public final class AdvisoryLockKeys {

    /** Namespace (key1): progressive dialer, one lock per {@code campaign_id} (BE-173). */
    public static final int NAMESPACE_DIALER_CAMPAIGN = 0x43430173;

    /**
     * Non-blocking attempt; params: (1) namespace {@code int}, (2) entity id as text (hashed in SQL).
     * Returns {@code true} when the lock is held until the end of the current transaction.
     */
    public static final String TRY_XACT_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?, hashtext(?))";

    /**
     * Per-agent assignment lock (BE-156, shared since BE-173): blocking, {@code bigint} form, param = the text from
     * {@link #agentAssignmentKey}. Taken by {@code ContactRepository#assignIfQueued} (async limit) and by the
     * dialer claim, so both serialise on ONE key per agent. Held until the end of the surrounding transaction.
     */
    public static final String AGENT_ASSIGNMENT_LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    /** Text hashed by {@link #AGENT_ASSIGNMENT_LOCK_SQL}. */
    public static String agentAssignmentKey(java.util.UUID tenantId, java.util.UUID agentId) {
        return tenantId + ":" + agentId;
    }

    private AdvisoryLockKeys() {
    }
}
