package com.contactcenter.domain.campaign;

import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.domain.contact.OccupiedSlot;
import com.contactcenter.infrastructure.lock.LockSkipMetrics;
import com.contactcenter.security.TenantContext;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Reaper of orphaned {@code campaign_contact} records stuck in {@code DIALING} (BE-173 review, S3).
 *
 * <p>Since BE-173 the progressive dialer claims a record in a short transaction and places the call AFTER the commit,
 * so a JVM crash / SIGTERM (rolling deploy) between the two leaves the record {@code DIALING} for good: it is never
 * dialled again, campaign counters are inflated and the GDPR guard (409) refuses to anonymise that customer.
 *
 * <p>Scope: ONLY records the progressive dialer claimed ({@code dialing_agent_id IS NOT NULL}, V151) whose
 * {@code updated_at} is older than {@code dialer.dialing-reaper.stale-after-minutes} (default 10 min, far above the
 * 30 s ring timeout). Callback / manual dials also use DIALING and are left to their own flows.
 * For every such record, in this order:
 * <ol>
 *   <li>a linked contact is still OPEN (QUEUED/ASSIGNED/ACTIVE/ON_HOLD) - a live call: untouched;</li>
 *   <li>a contact exists (linked by {@code campaign_contact_record_id}, or same campaign + agent started after the
 *       claim) but is closed - the call was placed and its outcome is unknown: NO_ANSWER with the campaign retry
 *       delay, or NOT_REACHED when attempts are exhausted (same rule as {@code handleNoAnswer}); never PENDING
 *       (that would double-dial);</li>
 *   <li>no contact at all - the call was never placed: back to PENDING, the claim's attempt is given back.
 *       Known gap: a crash between the Twilio API call and the contact INSERT also lands here and can double-dial
 *       (no idempotency key on {@code initiateCall}).</li>
 * </ol>
 * Per tenant one short transaction ({@code set_tenant_context} is transaction-local, RLS), tenant context set and
 * cleared per tenant. Class S job (ShedLock): the statements are idempotent, so an expired lock only repeats no-ops.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)
class OrphanedDialingReaper {

    private static final String STALE_FILTER = """
              cc.tenant_id = ?::uuid
              AND cc.status = 'DIALING'
              AND cc.dialing_agent_id IS NOT NULL
              AND cc.updated_at < NOW() - (? * INTERVAL '1 minute')
            """;

    /** A contact of this dial: linked by record id, or same campaign + agent started after the claim. */
    private static final String CONTACT_OF_DIAL = """
            k.tenant_id = cc.tenant_id
            AND (k.campaign_contact_record_id = cc.record_id
                 OR (k.campaign_id = cc.campaign_id AND k.agent_id = cc.dialing_agent_id
                     AND k.started_at >= cc.last_attempt_at))
            """;

    /** Params: tenant, staleMinutes. Call placed, closed contact only (no live call). */
    static final String PLACED_CLOSED_SQL = """
            UPDATE campaign_contact cc
               SET status = CASE WHEN cc.attempt_count >= c.max_attempts THEN 'NOT_REACHED' ELSE 'NO_ANSWER' END,
                   next_attempt_at = CASE WHEN cc.attempt_count >= c.max_attempts THEN NULL
                                          ELSE NOW() + (c.retry_delay_minutes * INTERVAL '1 minute') END,
                   dialing_agent_id = NULL,
                   updated_at = NOW()
              FROM campaign c
             WHERE c.campaign_id = cc.campaign_id
              AND""" + STALE_FILTER + """
              AND EXISTS (SELECT 1 FROM contact k WHERE """ + " " + CONTACT_OF_DIAL + """
              )
              AND NOT EXISTS (SELECT 1 FROM contact k WHERE """ + " " + CONTACT_OF_DIAL + """
                  AND k.status IN (""" + OccupiedSlot.AGENT_LOAD_STATUSES_SQL + """
              ))
            """;

    /** Params: tenant, staleMinutes. No contact at all: the call was never placed. */
    static final String NEVER_PLACED_SQL = """
            UPDATE campaign_contact cc
               SET status = 'PENDING',
                   dialing_agent_id = NULL,
                   attempt_count = GREATEST(cc.attempt_count - 1, 0),
                   updated_at = NOW()
             WHERE""" + STALE_FILTER + """
              AND NOT EXISTS (SELECT 1 FROM contact k WHERE """ + " " + CONTACT_OF_DIAL + """
              )
            """;

    private final TenantService tenantService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final LockSkipMetrics metrics;
    private final int staleAfterMinutes;

    OrphanedDialingReaper(TenantService tenantService, JdbcTemplate jdbcTemplate,
                          PlatformTransactionManager transactionManager, LockSkipMetrics metrics,
                          @Value("${dialer.dialing-reaper.stale-after-minutes:10}") int staleAfterMinutes) {
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.staleAfterMinutes = Math.max(2, staleAfterMinutes);
    }

    /**
     * Interval 5 min (fixedDelay). {@code lockAtLeastFor} = 0.8 x interval (PT4M), {@code lockAtMostFor} = 3 x interval
     * (PT15M) - the rules of BE-172.
     */
    @SchedulerLock(name = "OrphanedDialingReaper_reapOrphanedDialing",
            lockAtMostFor = "${shedlock.dialing-reaper.at-most-for:PT15M}",
            lockAtLeastFor = "${shedlock.dialing-reaper.at-least-for:PT4M}")
    @Scheduled(fixedDelayString = "${dialer.dialing-reaper.interval-ms:300000}")
    public void reapOrphanedDialing() {
        List<Tenant> tenants = tenantService.getActiveTenants();
        int total = 0;
        for (Tenant tenant : tenants) {
            TenantContext.setTenantId(tenant.getId());
            try {
                total += reapForTenant(tenant.getId());
            } catch (Exception e) {
                log.error("[DialingReaper] Błąd dla tenanta {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        if (total > 0) {
            log.warn("[DialingReaper] Odzyskano {} osieroconych rekordów DIALING", total);
        }
    }

    /** @return number of records moved out of DIALING for the tenant */
    int reapForTenant(UUID tenantId) {
        Integer reaped = transactionTemplate.execute(status -> {
            jdbcTemplate.queryForRowSet("SELECT set_tenant_context(?::uuid)", tenantId.toString());
            int placed = jdbcTemplate.update(PLACED_CLOSED_SQL, tenantId.toString(), staleAfterMinutes);
            int never = jdbcTemplate.update(NEVER_PLACED_SQL, tenantId.toString(), staleAfterMinutes);
            if (placed + never > 0) {
                log.warn("[DialingReaper] tenant={}: {} po wykonanym połączeniu (NO_ANSWER/NOT_REACHED), {} bez "
                        + "połączenia (PENDING)", tenantId, placed, never);
            }
            return placed + never;
        });
        int count = reaped == null ? 0 : reaped;
        if (count > 0) {
            metrics.recordDialingReaped(count);
        }
        return count;
    }
}
