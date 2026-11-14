package com.contactcenter.domain.contact;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Test seam (BE-230): one application "instance" of the routing write path ({@code ContactRepository#assignIfQueued})
 * on its own connection pool, reachable from other packages (the repository is package-private).
 * Every call runs with its own {@link TenantContext} (set in the calling thread, always cleared in {@code finally}).
 *
 * <p>Three entry points, named for what they are:
 * <ul>
 *   <li>{@link #assignProduction} - the real PHONE path: per-agent lock + voice-load check + conditional UPDATE;</li>
 *   <li>{@link #assignWithoutExclusivity} - pre-BE-230 behaviour (no check, no lock). Only a detector sanity check:
 *       it proves the invariant assertion sees a double booking, NOT that the lock is needed;</li>
 *   <li>{@link #assignCheckWithoutLock} - the control that shows the LOCK is needed: the very same voice-load check
 *       and the very same UPDATE, in one transaction, but WITHOUT the advisory lock, with a hook between them
 *       (a barrier in the tests). No production code is touched: it composes the public pieces
 *       ({@link OccupiedSlot#hasVoiceLoad} and {@code assignIfQueued(..., exclusiveVoice=false)}).</li>
 * </ul>
 */
public final class VoiceAssignmentHarness implements AutoCloseable {

    private final AnnotationConfigApplicationContext ctx;
    private final ContactRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private VoiceAssignmentHarness(HikariDataSource pool, long lockTimeoutMs) {
        this.jdbc = new JdbcTemplate(pool);
        this.ctx = JpaTestContext.create(pool, new Class<?>[]{Contact.class}, new Class<?>[]{ContactRepository.class},
                c -> {
                    c.getBeanFactory().registerSingleton("jdbcTemplate", jdbc);
                    c.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
                    c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("be230",
                            Map.of("agent-assignment.lock-timeout-ms", lockTimeoutMs)));
                });
        this.repository = ctx.getBean(ContactRepository.class);
        this.tx = new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
    }

    /** @param pool the pool of THIS instance (closed by the caller); lock timeout = the production default (500 ms) */
    public static VoiceAssignmentHarness create(HikariDataSource pool) {
        return new VoiceAssignmentHarness(pool, 500);
    }

    /** @param lockTimeoutMs {@code agent-assignment.lock-timeout-ms} for this instance */
    public static VoiceAssignmentHarness create(HikariDataSource pool, long lockTimeoutMs) {
        return new VoiceAssignmentHarness(pool, lockTimeoutMs);
    }

    /** The production PHONE path of routing (BE-230). */
    public AssignmentResult assignProduction(UUID tenantId, UUID contactId, Instant startedAt, UUID agentId,
                                             UUID queueId) {
        return withTenant(tenantId, () -> repository.assignIfQueued(contactId, tenantId, startedAt, agentId, queueId,
                null, true));
    }

    /** Pre-BE-230 behaviour: no voice check, no lock (detector sanity control, see the class comment). */
    public AssignmentResult assignWithoutExclusivity(UUID tenantId, UUID contactId, Instant startedAt, UUID agentId,
                                                     UUID queueId) {
        return withTenant(tenantId, () -> repository.assignIfQueued(contactId, tenantId, startedAt, agentId, queueId,
                null, false));
    }

    /**
     * "Check WITHOUT lock" control: voice-load check, then {@code betweenCheckAndUpdate}, then the conditional UPDATE -
     * all in ONE transaction, with NO advisory lock. The hook is where the test parks the thread (barrier), which
     * turns the check-then-act window into a deterministic double booking; with the production path the lock makes
     * that window unreachable.
     */
    public AssignmentResult assignCheckWithoutLock(UUID tenantId, UUID contactId, Instant startedAt, UUID agentId,
                                                   UUID queueId, Runnable betweenCheckAndUpdate) {
        return withTenant(tenantId, () -> tx.execute(status -> {
            jdbc.queryForRowSet("SELECT set_tenant_context(?::uuid)", tenantId.toString());
            if (OccupiedSlot.hasVoiceLoad(jdbc, tenantId, agentId)) {
                return AssignmentResult.AGENT_BUSY;
            }
            betweenCheckAndUpdate.run();
            return repository.assignIfQueued(contactId, tenantId, startedAt, agentId, queueId, null, false);
        }));
    }

    /** The routing engine's batch probe (S7) through the real repository (Hibernate native query). */
    public java.util.Set<UUID> findAgentIdsWithVoiceLoad(UUID tenantId, java.util.Collection<UUID> agentIds) {
        TenantContext.setTenantId(tenantId);
        try {
            return tx.execute(status -> repository.findAgentIdsWithVoiceLoad(tenantId, agentIds));
        } finally {
            TenantContext.clear();
        }
    }

    private static AssignmentResult withTenant(UUID tenantId, java.util.function.Supplier<AssignmentResult> work) {
        TenantContext.setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Override
    public void close() {
        JpaTestContext.close(ctx);
    }
}
