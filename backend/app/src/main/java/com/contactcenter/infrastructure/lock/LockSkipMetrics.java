package com.contactcenter.infrastructure.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counter of passes SKIPPED because the per-campaign / per-queue lock was held elsewhere (BE-173).
 *
 * <p>Metric {@value #METRIC}, tag {@code type} = {@value #TYPE_DIALER} | {@value #TYPE_ROUTING}. Deliberately NO
 * {@code tenantId}/{@code campaignId}/{@code queueId} tags: their cardinality is unbounded (every tenant, every
 * campaign) and would blow up the time-series store; per-entity detail is in the DEBUG log of the skipping pass.
 * The raw counter feeds the dashboard of BE-183.
 */
@Component
public class LockSkipMetrics {

    public static final String METRIC = "scheduler.lock.skipped";
    public static final String TYPE_DIALER = "dialer";
    public static final String TYPE_ROUTING = "routing";

    /** Dialer: a call was placed but its Redis state could not be saved (record moved out of DIALING). */
    public static final String METRIC_CALL_STATE_LOST = "dialer.call_state_lost";
    /** Dialer reaper: DIALING records without a live call put back into the dialling flow. */
    public static final String METRIC_DIALING_REAPED = "dialer.orphaned_dialing.reaped";

    /**
     * BE-230: an assignment lost the race for an agent - the agent already holds a voice contact / DIALING record
     * (REAL contention only). Tag {@code source}.
     */
    public static final String METRIC_AGENT_CONTENTION = "agent.assignment.contention";
    /**
     * BE-230 S6: an assignment skipped an agent for a reason that is NOT contention. Tags {@code source} and
     * {@code reason} ({@value #REASON_STATUS_CHANGED} | {@value #REASON_LOCK_TIMEOUT}).
     */
    public static final String METRIC_AGENT_SKIPPED = "agent.assignment.skipped";
    public static final String SOURCE_ROUTING = "routing";
    public static final String SOURCE_DIALER = "dialer";
    public static final String SOURCE_CALLBACK = "callback";
    public static final String SOURCE_MANUAL = "manual";
    /** The agent was no longer AVAILABLE when the dialer claimed (status changed between trigger and claim). */
    public static final String REASON_STATUS_CHANGED = "status_changed";
    /** The per-agent lock was not obtained within the lock timeout (stuck holder). */
    public static final String REASON_LOCK_TIMEOUT = "lock_timeout";

    private static final List<String> SOURCES = List.of(SOURCE_ROUTING, SOURCE_DIALER, SOURCE_CALLBACK, SOURCE_MANUAL);
    private static final List<String> REASONS = List.of(REASON_STATUS_CHANGED, REASON_LOCK_TIMEOUT);

    private final Map<String, Counter> agentContention = new HashMap<>();
    private final Map<String, Counter> agentSkipped = new HashMap<>();
    private final Counter dialerSkipped;
    private final Counter routingSkipped;
    private final Counter callStateLost;
    private final Counter dialingReaped;

    public LockSkipMetrics(MeterRegistry meterRegistry) {
        for (String source : SOURCES) {
            agentContention.put(source, meterRegistry.counter(METRIC_AGENT_CONTENTION, "source", source));
            for (String reason : REASONS) {
                agentSkipped.put(source + '/' + reason,
                        meterRegistry.counter(METRIC_AGENT_SKIPPED, "source", source, "reason", reason));
            }
        }
        this.dialerSkipped = meterRegistry.counter(METRIC, "type", TYPE_DIALER);
        this.routingSkipped = meterRegistry.counter(METRIC, "type", TYPE_ROUTING);
        this.callStateLost = meterRegistry.counter(METRIC_CALL_STATE_LOST);
        this.dialingReaped = meterRegistry.counter(METRIC_DIALING_REAPED);
    }

    /** Increments the skip counter for the given type (counters are registered once, no per-call lookup). */
    public void recordSkipped(String type) {
        (TYPE_DIALER.equals(type) ? dialerSkipped : routingSkipped).increment();
    }

    /** BE-230: raw counter of real contention - the agent was taken by another path (no per-entity tags). */
    public void recordAgentContention(String source) {
        agentContention.get(source).increment();
    }

    /** BE-230 S6: agent skipped for a non-contention {@code reason} ({@code REASON_*}); source = {@code SOURCE_*}. */
    public void recordAgentSkipped(String source, String reason) {
        agentSkipped.get(source + '/' + reason).increment();
    }

    public void recordCallStateLost() {
        callStateLost.increment();
    }

    public void recordDialingReaped(int count) {
        dialingReaped.increment(count);
    }
}
