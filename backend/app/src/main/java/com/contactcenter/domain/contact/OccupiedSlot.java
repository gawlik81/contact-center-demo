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
 * </ul>
 */
public final class OccupiedSlot {

    /** BE-156 async slot statuses; SQL fragment for {@code status IN (...)} (compile-time constant). */
    public static final String STATUSES_SQL = "'ASSIGNED', 'ACTIVE', 'ON_HOLD'";

    /** Agent load statuses = slot statuses + QUEUED (agent-owned outbound dial); use only with an agent_id filter. */
    public static final String AGENT_LOAD_STATUSES_SQL = "'QUEUED', " + STATUSES_SQL;

    private OccupiedSlot() {
    }
}
