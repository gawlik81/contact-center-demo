package com.contactcenter.domain.contact;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

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

    /**
     * BE-230: contact half of "the agent holds a voice (PHONE) load" (alias {@code c}): an open contact
     * ({@link #AGENT_LOAD_STATUSES_SQL}) on the PHONE channel; legacy rows without a channel count as PHONE.
     * Must be combined with {@code c.agent_id} / {@code c.tenant_id} filters.
     */
    public static final String VOICE_CONTACT_LOAD_SQL = "c.status IN (" + AGENT_LOAD_STATUSES_SQL + ")"
            + " AND (c.channel = 'PHONE' OR c.channel IS NULL)";

    /**
     * BE-230: dialer half of the voice load (alias {@code d}, table {@code campaign_contact}): a record in
     * {@code DIALING} (a call about to be created). Combine with {@code d.dialing_agent_id} / {@code d.tenant_id}.
     */
    public static final String VOICE_DIALING_LOAD_SQL = "d.status = 'DIALING'";

    /**
     * BE-230: the ONE predicate "agent holds a voice load" (boolean expression, 4 positional parameters, see
     * {@link #voiceLoadParams}). Every writer that must respect agent exclusivity builds its SQL from this fragment:
     * routing ({@link #VOICE_LOAD_EXISTS_SQL}), the progressive dialer ({@code AGENT_STILL_FREE_SQL}) and the batch
     * probe of the routing engine ({@link #VOICE_LOAD_AGENTS_SQL}) - so the predicate cannot drift between them.
     */
    public static final String VOICE_LOAD_CONDITION_SQL =
            "(EXISTS (SELECT 1 FROM contact c WHERE c.agent_id = ?::uuid AND c.tenant_id = ?::uuid AND "
                    + VOICE_CONTACT_LOAD_SQL + ")"
                    + " OR EXISTS (SELECT 1 FROM campaign_contact d WHERE d.tenant_id = ?::uuid"
                    + " AND d.dialing_agent_id = ?::uuid AND " + VOICE_DIALING_LOAD_SQL + "))";

    /**
     * BE-230: single-agent voice load probe, TRUE = busy. Parameters: {@link #voiceLoadParams}. MUST be evaluated
     * while holding the per-agent advisory lock ({@code AgentAssignmentLock}), otherwise it is only a hint.
     */
    public static final String VOICE_LOAD_EXISTS_SQL = "SELECT " + VOICE_LOAD_CONDITION_SQL;

    /**
     * BE-230 S7: batch form of the same predicate for the routing engine - agent ids (from the candidate list) that
     * hold a voice load. Named parameters {@code :tenantId}, {@code :agentIds}. A cheap pre-filter only; the lock +
     * {@link #VOICE_LOAD_EXISTS_SQL} at write time stays the guarantee.
     */
    public static final String VOICE_LOAD_AGENTS_SQL =
            "SELECT c.agent_id FROM contact c WHERE c.tenant_id = CAST(:tenantId AS uuid)"
                    + " AND c.agent_id IN (:agentIds) AND " + VOICE_CONTACT_LOAD_SQL
                    + " UNION SELECT d.dialing_agent_id FROM campaign_contact d"
                    + " WHERE d.tenant_id = CAST(:tenantId AS uuid) AND d.dialing_agent_id IN (:agentIds)"
                    + " AND " + VOICE_DIALING_LOAD_SQL;

    /** Parameters of {@link #VOICE_LOAD_CONDITION_SQL} / {@link #VOICE_LOAD_EXISTS_SQL}, in the order of the {@code ?}. */
    public static Object[] voiceLoadParams(UUID agentId, UUID tenantId) {
        String agent = agentId.toString();
        String tenant = tenantId.toString();
        return new Object[]{agent, tenant, tenant, agent};
    }

    /** {@link #VOICE_LOAD_EXISTS_SQL} on the connection of the current transaction; call under the agent lock. */
    public static boolean hasVoiceLoad(JdbcTemplate jdbcTemplate, UUID tenantId, UUID agentId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(VOICE_LOAD_EXISTS_SQL, Boolean.class,
                voiceLoadParams(agentId, tenantId)));
    }

    private OccupiedSlot() {
    }
}
