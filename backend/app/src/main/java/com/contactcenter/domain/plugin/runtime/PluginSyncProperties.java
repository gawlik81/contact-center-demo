package com.contactcenter.domain.plugin.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Configuration of the cross-instance plugin runtime synchronisation (BE-178, ADR-09 / ADR-14 pattern).
 * Prefix {@code plugins.sync}.
 *
 * <p>Source of truth is the database; the Redis channel only carries "something changed" hints, so every value
 * below is a latency/cost trade-off, never a correctness one (the periodic reconciliation repairs a lost event).
 */
@Getter
@Setter
@Component
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
    private int maxMessageBytes = 4_096;

    /**
     * Interval of the per-instance DB reconciliation ({@code @Scheduled}, deliberately WITHOUT ShedLock: every
     * instance must converge its OWN in-memory state). Upper bound of staleness after a lost event.
     */
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
    private long lazySyncCooldownMs = 15_000;

    /**
     * After a FAILED load of an installation the instance does not try again for this long (broken JAR, failing
     * {@code onActivate}, storage outage); the periodic reconciliation retries later.
     */
    private long failedLoadBackoffMs = 60_000;

    /** Threads of the executor running event-triggered reconciliations and loads. */
    private int syncThreads = 2;

    /** Bounded queue of that executor; when full the task is dropped (counted) and the reconciliation repairs it. */
    private int syncQueueCapacity = 256;

    /** Bounded queue of the Redis listener's (single, ordered) dispatch thread. */
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
