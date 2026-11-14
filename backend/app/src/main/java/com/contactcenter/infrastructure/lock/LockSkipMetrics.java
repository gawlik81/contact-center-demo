package com.contactcenter.infrastructure.lock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

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

    private final Counter dialerSkipped;
    private final Counter routingSkipped;
    private final Counter callStateLost;
    private final Counter dialingReaped;

    public LockSkipMetrics(MeterRegistry meterRegistry) {
        this.dialerSkipped = meterRegistry.counter(METRIC, "type", TYPE_DIALER);
        this.routingSkipped = meterRegistry.counter(METRIC, "type", TYPE_ROUTING);
        this.callStateLost = meterRegistry.counter(METRIC_CALL_STATE_LOST);
        this.dialingReaped = meterRegistry.counter(METRIC_DIALING_REAPED);
    }

    /** Increments the skip counter for the given type (counters are registered once, no per-call lookup). */
    public void recordSkipped(String type) {
        (TYPE_DIALER.equals(type) ? dialerSkipped : routingSkipped).increment();
    }

    public void recordCallStateLost() {
        callStateLost.increment();
    }

    public void recordDialingReaped(int count) {
        dialingReaped.increment(count);
    }
}
