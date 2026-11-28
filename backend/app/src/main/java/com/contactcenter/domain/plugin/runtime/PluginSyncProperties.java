package com.contactcenter.domain.plugin.runtime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Configuration of the cross-instance plugin runtime synchronisation (BE-178, ADR-09 / ADR-14 pattern).
 * Prefix {@code plugins.sync}.
 *
 * <p>Source of truth is the database; the Redis channel only carries "something changed" hints, so every value
 * below is a latency/cost trade-off, never a correctness one (the periodic reconciliation repairs a lost event).
 *
 * <p>Rates, thread counts, queue capacities and timeouts are validated ({@code @Positive}) at startup (BE-244 N3): a
 * rate of 0 would mean tokens never refill (every lazy asset load 503 for good), a thread count of 0 an executor that
 * never runs.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "plugins.sync")
public class PluginSyncProperties {

    /**
     * {@code false} = no events are published or received and no reconciliation job runs (the instance behaves as
     * before BE-178). Correct for a single replica only.
     */
    private boolean enabled = true;

    /** Base name of the Redis pub/sub channel (see {@link #effectiveChannel()}). */
    private String channel = "cc:plugin:sync";

    /**
     * Environment suffix of the channel so that environments sharing one Redis (dev/staging) do not exchange events;
     * default (application.yml) = first active Spring profile, override with {@code PLUGINS_SYNC_ENV}.
     */
    private String environment = "default";

    /**
     * Optional human-readable prefix of the instance id. A random per-process suffix is ALWAYS appended, so replicas
     * sharing one configuration still get distinct ids (otherwise every receiver would ignore the others' events).
     */
    private String instanceId;

    /** Max size (UTF-8 bytes) of one envelope; events are ~250 B, anything bigger is not ours. */
    @Positive
    private int maxMessageBytes = 4_096;

    /**
     * Interval of the per-instance DB reconciliation ({@code @Scheduled}, deliberately WITHOUT ShedLock: every
     * instance must converge its OWN in-memory state). Upper bound of staleness after a lost event.
     */
    @Positive
    private long reconcileIntervalMs = 30_000;

    /** Delay of the first reconciliation after start (startup load is done by {@code PluginRuntimeStartupLoader}). */
    private long reconcileInitialDelayMs = 30_000;

    /**
     * A handle younger than this is never unloaded by the reconciliation because the DB does not list it as enabled:
     * {@code PluginAdminController#enable} loads the runtime BEFORE it flips {@code enabled} in the DB. Revocation of
     * a version is not subject to the grace period.
     */
    private long unloadGraceMs = 60_000;

    /**
     * Minimum interval between lazy tenant syncs (DB query + loading of missing installations) of ONE tenant on ONE
     * instance - protects the database and the object storage from a load storm of invocations on an instance that
     * has no handle.
     */
    @Positive
    private long lazySyncCooldownMs = 15_000;

    /**
     * After a FAILED load of an installation the instance does not try again for this long (broken JAR, failing
     * {@code onActivate}, storage outage); the periodic reconciliation retries later.
     */
    @Positive
    private long failedLoadBackoffMs = 60_000;

    /**
     * How long the HTTP thread of {@code PluginAssetController} waits for a lazy load of an installation this instance
     * has not reconciled yet (BE-246). On timeout the load continues in the background and the response is 503
     * (Retry-After), never an unbounded wait.
     */
    @Positive
    private long assetLazyLoadTimeoutMs = 3_000;

    /**
     * Max number of DIFFERENT installations being lazily resolved for the public asset endpoint at the same time
     * (each unknown id costs a scan over the tenants); beyond it the request is answered 503 (Retry-After) at once (counted as {@code shed}).
     */
    @Positive
    private int assetLazyLoadMaxInflight = 8;

    /**
     * Max number of HTTP threads waiting for the SAME slow lazy load of one installation (BE-246 review N); a further
     * waiter is answered 503 (Retry-After) at once instead of holding one more Tomcat thread for up to the timeout.
     */
    @Positive
    private int assetLazyLoadMaxWaiters = 4;

    /**
     * Max number of lazy asset resolutions of installations unknown to this instance STARTED per second, all callers
     * together (BE-244 W2): a token bucket checked BEFORE the tenant scan. Single-flight and the inflight cap bound
     * concurrency, not throughput - without this an unauthenticated caller with random ids keeps the asset executor
     * saturated. Beyond the rate the request is answered 503 (Retry-After) without any database work.
     */
    @DecimalMin(value = "0.0", inclusive = false)
    private double assetLazyLoadRatePerSecond = 10.0;

    /** Burst of that token bucket (tokens available after a quiet period). */
    @Positive
    private int assetLazyLoadRateBurst = 20;

    /** Threads of the executor dedicated to the lazy asset resolution (BE-244 W2): never shared with reconciliation. */
    @Positive
    private int assetLazyLoadThreads = 2;

    /** Bounded queue of that executor; when full the request is shed (503 + Retry-After, counted as {@code asset_queue_full}). */
    @Positive
    private int assetLazyLoadQueueCapacity = 16;

    /**
     * Threads of the priority executor that (re)loads an installation after an event (enable / version change /
     * configuration change, BE-244 R2): such a reload never waits behind a full reconciliation pass, which runs on its
     * own single-threaded bulk executor.
     */
    @Positive
    private int loadThreads = 2;

    /** Bounded queue of the priority load executor; when full the load is dropped (counted) and the reconciliation repairs it. */
    @Positive
    private int loadQueueCapacity = 64;

    /** Threads of the executor running event-triggered reconciliations (the fast part: unload). */
    @Positive
    private int syncThreads = 2;

    /** Bounded queue of that executor; when full the task is dropped (counted) and the reconciliation repairs it. */
    @Positive
    private int syncQueueCapacity = 256;

    /** Bounded queue of the Redis listener's (single, ordered) dispatch thread. */
    @Positive
    private int dispatchQueueCapacity = 512;

    private final String processSuffix = UUID.randomUUID().toString().substring(0, 8);

    /** Effective instance id: {@code [<configured>-]<random 8 hex>}, stable for the life of the process. */
    public String resolvedInstanceId() {
        return instanceId == null || instanceId.isBlank() ? processSuffix : instanceId.trim() + "-" + processSuffix;
    }

    /** Redis channel actually used: {@code <channel>:<environment>} (environment sanitised to [A-Za-z0-9_-]). */
    public String effectiveChannel() {
        String env = environment == null || environment.isBlank() ? "default" : environment.trim();
        return channel + ":" + env.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
