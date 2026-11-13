package com.contactcenter.domain.contact;

/**
 * Single place for the contact-status sets that count against an agent (BE-156 / BE-207).
 *
 * <ul>
 *   <li>{@link #STATUSES_SQL} - the BE-156 async slot: {@code ASSIGNED} (routed, not yet accepted), {@code ACTIVE},
 *       {@code ON_HOLD}. A QUEUED async contact is waiting for an agent and holds no slot.</li>
 *   <li>{@link #AGENT_LOAD_STATUSES_SQL} - the general agent load (SKILL_BASED least-loaded, soft-delete guard):
 *       the slot set PLUS {@code QUEUED}. Outbound agent calls are inserted as {@code QUEUED} WITH
 *       {@code agent_id} set (Twilio/Mock adapters) and stay so until ACTIVE, so a QUEUED row with
 *       {@code agent_id = X} is an agent-owned outbound dial that really occupies the agent. Only valid in
 *       queries filtered by {@code agent_id}.</li>
 *   <li>{@link #IN_PROGRESS_STATUSES_SQL} - "active contact" in tenant/queue scope (BE-224): every contact that is
 *       not terminal and still has work attached - {@code QUEUED} (waiting or dialing), {@code ASSIGNED} (routed,
 *       ringing, not yet accepted), {@code ACTIVE}, {@code ON_HOLD}. Same members as the agent load set but
 *       a DIFFERENT meaning: it counts contacts, not an agent's load, so it needs no {@code agent_id} filter.</li>
 * </ul>
 */
public final class OccupiedSlot {

    /** BE-156 async slot statuses; SQL fragment for {@code status IN (...)} (compile-time constant). */
    public static final String STATUSES_SQL = "'ASSIGNED', 'ACTIVE', 'ON_HOLD'";

    /** Agent load statuses = slot statuses + QUEUED (agent-owned outbound dial); use only with an agent_id filter. */
    public static final String AGENT_LOAD_STATUSES_SQL = "'QUEUED', " + STATUSES_SQL;

    /**
     * Contacts in progress within a tenant or a queue (BE-224); SQL fragment for {@code status IN (...)}.
     *
     * <p>Used by the tenant "active contacts" counter ({@code ContactRepository#countActiveContactsByTenantId}:
     * admin metrics) and the queue deactivation guard ({@code QueueRepository#hasActiveContacts}). Both ask
     * "is there any live contact here?", so a contact waiting in the queue ({@code QUEUED}) and a routed-but-not-yet
     * -accepted one ({@code ASSIGNED}) must count as well - omitting {@code ASSIGNED} (the pre-BE-224 state) let
     * a queue with a ringing contact be deactivated and under-reported live contacts in the panels.
     * Deliberately kept as a separate constant from {@link #AGENT_LOAD_STATUSES_SQL} (equal value today) so the two
     * meanings can diverge without a silent behaviour change in the other one.
     */
    public static final String IN_PROGRESS_STATUSES_SQL = AGENT_LOAD_STATUSES_SQL;

    private OccupiedSlot() {
    }
}
