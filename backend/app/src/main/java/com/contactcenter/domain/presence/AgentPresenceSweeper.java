package com.contactcenter.domain.presence;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.presence.AgentPresenceRegistry.Presence;
import com.contactcenter.domain.presence.AgentPresenceRegistry.AgentRef;
import com.contactcenter.domain.user.AgentPresenceCandidate;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * BE-210: the single executor of the automatic AVAILABLE/AFTER_CONTACT/BREAK/BUSY -> OFFLINE transition for
 * agents that are no longer present ("ghost agents").
 *
 * <p>Rules (owner decisions 2026-11-08):
 * <ul>
 *   <li>AVAILABLE, AFTER_CONTACT, BREAK: OFFLINE once there is no alive WebSocket session and the last
 *       presence signal is older than {@code grace-period-ms}.</li>
 *   <li>BUSY: untouched while the agent holds a PHONE contact in ACTIVE/ON_HOLD; otherwise OFFLINE after
 *       {@code busy-orphan-timeout-ms} without presence.</li>
 * </ul>
 * The reference moment of "no presence since" is the latest of: last presence signal, application start (so a
 * backend restart does not log everybody out before they reconnect), last status change and last login (an agent
 * who just set a status or logged in over REST has not opened the WebSocket yet) - and, against a Redis outage,
 * of two more moments: the first successful read after a failed pass ({@code redisRecoveredAt}) and the
 * registry epoch sentinel ({@link AgentPresenceRegistry#ensureEpoch()}; a missing sentinel = Redis was flushed or
 * restarted without persistence). Either way a Redis problem gives every agent a full new grace period to ping
 * again instead of switching them all OFFLINE at once.
 *
 * <p>Cost: candidates are a light projection and the presence of all of them is read in pipelined batches
 * (not 2 round trips per agent); the startup pass runs asynchronously (does not block {@code ApplicationReadyEvent}).
 *
 * <p>Safety: Redis read failure = the whole pass skipped (and remembered, see above); presence is re-read right
 * before the write (only for real candidates); the write is a
 * compare-and-set on the status read by the sweeper ({@code UserService#markOfflineOnPresenceTimeout}), so two
 * instances or a manual change racing the sweeper produce at most one transition and one event.
 * Cross-instance locking (BE-172): {@link #scheduledSweep()} runs under ShedLock, so normally one instance sweeps per
 * interval; the compare-and-set above keeps it correct even if the lock expires mid-pass. The startup pass
 * ({@link #onApplicationReady()}) is deliberately NOT locked - it is per instance (own {@code startedAt} grace).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.presence.enabled", havingValue = "true", matchIfMissing = true)
class AgentPresenceSweeper {

    static final String METRIC_AUTO_OFFLINE = "agent.presence.auto_offline";
    static final String CAUSE_WS_DISCONNECT = "WS_DISCONNECT";
    static final String CAUSE_NO_PRESENCE = "SWEEPER_NO_PRESENCE";

    private final UserService userService;
    private final ContactService contactService;
    private final AgentPresenceRegistry registry;
    private final AgentPresenceProperties props;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private volatile Instant startedAt;
    /** Set when a Redis read failed; the next successful pass stamps {@link #redisRecoveredAt}. */
    private volatile boolean redisUnhealthy;
    private volatile Instant redisRecoveredAt;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Executor startupExecutor;

    @Autowired
    AgentPresenceSweeper(UserService userService, ContactService contactService, AgentPresenceRegistry registry,
                         AgentPresenceProperties props, MeterRegistry meterRegistry, TaskScheduler taskScheduler) {
        this(userService, contactService, registry, props, meterRegistry, Clock.systemUTC(),
                task -> taskScheduler.schedule(task, Instant.now()));
    }

    /** Test constructor: the startup pass runs inline. */
    AgentPresenceSweeper(UserService userService, ContactService contactService, AgentPresenceRegistry registry,
                         AgentPresenceProperties props, MeterRegistry meterRegistry, Clock clock) {
        this(userService, contactService, registry, props, meterRegistry, clock, Runnable::run);
    }

    AgentPresenceSweeper(UserService userService, ContactService contactService, AgentPresenceRegistry registry,
                         AgentPresenceProperties props, MeterRegistry meterRegistry, Clock clock,
                         Executor startupExecutor) {
        this.userService = userService;
        this.contactService = contactService;
        this.registry = registry;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.startupExecutor = startupExecutor;
        this.startedAt = clock.instant();
    }

    /** Startup: pins the "start" reference of the grace period and sweeps once, off the startup thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        startedAt = clock.instant();
        log.info("[Presence] Start aplikacji - karencja obecnosci liczona od startu: grace={}ms, busyOrphan={}ms",
                props.getGracePeriodMs(), props.getBusyOrphanTimeoutMs());
        startupExecutor.execute(this::sweep);
    }

    @SchedulerLock(name = "AgentPresenceSweeper_scheduledSweep",
            lockAtMostFor = "${shedlock.presence-sweeper.at-most-for:PT90S}",
            lockAtLeastFor = "${shedlock.presence-sweeper.at-least-for:PT24S}")
    @Scheduled(fixedDelayString = "${agent.presence.sweep-interval-ms:30000}",
            initialDelayString = "${agent.presence.sweep-interval-ms:30000}")
    public void scheduledSweep() {
        sweep();
    }

    /**
     * One pass over all presence-tracked agents; a failure for one agent never stops the pass. Passes never
     * overlap (the startup pass runs asynchronously): a second caller returns 0 immediately.
     */
    int sweep() {
        if (!running.compareAndSet(false, true)) {
            log.debug("[Presence] Poprzedni przebieg jeszcze trwa - pomijam");
            return 0;
        }
        try {
            return doSweep();
        } finally {
            running.set(false);
        }
    }

    private int doSweep() {
        List<AgentPresenceCandidate> candidates;
        try {
            candidates = userService.findPresenceTrackedAgents().stream()
                    .filter(c -> c.tenantId() != null).toList();
        } catch (Exception e) {
            log.error("[Presence] Nie mozna pobrac kandydatow: {}", e.getMessage(), e);
            return 0;
        }

        Instant epoch;
        Map<AgentRef, Presence> presence;
        try {
            epoch = registry.ensureEpoch();
            presence = registry.snapshots(candidates.stream()
                    .map(c -> new AgentRef(c.tenantId(), c.id())).toList());
        } catch (Exception e) {
            redisUnhealthy = true;
            log.warn("[Presence] Redis niedostepny - przebieg pominiety, nikt nie jest przelaczany: {}",
                    e.getMessage());
            return 0;
        }
        if (redisUnhealthy) {
            redisRecoveredAt = clock.instant();
            redisUnhealthy = false;
            log.info("[Presence] Redis znowu dostepny - karencja liczona od {}", redisRecoveredAt);
        }

        int switched = 0;
        for (AgentPresenceCandidate agent : candidates) {
            Presence agentPresence = presence.get(new AgentRef(agent.tenantId(), agent.id()));
            if (agentPresence == null) {
                continue;
            }
            // Own thread (scheduler / startup): no inherited context; set per agent and always clear.
            TenantContext.setTenantId(agent.tenantId());
            try {
                if (sweepAgent(agent, agentPresence, epoch)) {
                    switched++;
                }
            } catch (Exception e) {
                log.warn("[Presence] Blad sprawdzenia obecnosci (pominiety): userId={}, tenantId={}, error={}",
                        agent.id(), agent.tenantId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
        if (switched > 0) {
            log.info("[Presence] Przebieg zakonczony: {} agentow przelaczonych na OFFLINE (kandydatow: {})",
                    switched, candidates.size());
        }
        return switched;
    }

    private boolean sweepAgent(AgentPresenceCandidate agent, Presence presence, Instant epoch) {
        Instant now = clock.instant();
        UserStatus status = agent.status();
        if (presence.connected()) {
            return false;
        }

        Instant floor = latest(startedAt, agent.statusChangedAt(), agent.lastLoginAt(), epoch, redisRecoveredAt);
        Instant reference = latest(presence.lastSeen(), floor);
        long threshold = status == UserStatus.BUSY ? props.getBusyOrphanTimeoutMs() : props.getGracePeriodMs();
        Duration absent = Duration.between(reference, now);
        if (absent.toMillis() < threshold) {
            return false;
        }
        if (status == UserStatus.BUSY && contactService.hasActivePhoneContact(agent.tenantId(), agent.id())) {
            log.debug("[Presence] BUSY z rozmowa w toku - pomijam: userId={}", agent.id());
            return false;
        }

        // Reconnect race: presence is re-read right before the write.
        if (registry.snapshot(agent.tenantId(), agent.id()).connected()) {
            log.debug("[Presence] Agent wrocil tuz przed zapisem - pomijam: userId={}", agent.id());
            return false;
        }

        boolean switched = userService.markOfflineOnPresenceTimeout(agent.id(), agent.tenantId(), status);
        if (switched) {
            String cause = presence.lastSeen() != null && presence.lastSeen().isAfter(floor)
                    ? CAUSE_WS_DISCONNECT : CAUSE_NO_PRESENCE;
            log.info("[Presence] Auto-OFFLINE: userId={}, tenantId={}, poprzedni status={}, brak obecnosci od {}s, "
                            + "cause={}", agent.id(), agent.tenantId(), status, absent.toSeconds(), cause);
            meterRegistry.counter(METRIC_AUTO_OFFLINE, "reason", cause).increment();
        }
        return switched;
    }

    private static Instant latest(Instant... values) {
        return Stream.of(values).filter(v -> v != null).max(Instant::compareTo).orElse(Instant.EPOCH);
    }
}
