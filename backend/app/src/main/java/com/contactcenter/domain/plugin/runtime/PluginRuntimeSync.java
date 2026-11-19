package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronisation of the in-memory plugin runtime ({@link PluginRegistry}, {@link PluginRuntimeManager},
 * circuit breaker) between backend instances (BE-178, ADR-09).
 *
 * <p><strong>Model.</strong> The database ({@code tenant_plugin_installation.enabled}, {@code plugin_version.status})
 * is the source of truth. Every instance converges its own memory to it by three independent mechanisms, so no single
 * failure leaves an instance stale for good:
 * <ol>
 *   <li><em>events</em> (Redis pub/sub, {@link PluginSyncBus}): the instance that changed something calls
 *       {@link #installationChanged}/{@link #versionRevoked}; the others reconcile within ~100 ms;</li>
 *   <li><em>periodic reconciliation</em> against the database on EVERY instance (default 30 s, and on every
 *       (re)subscription of the Redis channel) - repairs a lost event, a Redis outage, a missed rolling-deploy window;</li>
 *   <li><em>lazy load</em> in the invocation path ({@link #ensureTenantLoaded}) - an instance without the handle that
 *       receives an invocation loads it from the database instead of dropping it.</li>
 * </ol>
 */
public interface PluginRuntimeSync {

    /**
     * Announces that the installation was installed/enabled/disabled/rolled back/uninstalled. To be called AFTER the
     * database change is committed (otherwise receivers read the old state). Also resets the local circuit breaker
     * of the installation (administrator intervention). Never throws; a Redis outage only delays the other instances
     * until the next periodic reconciliation.
     */
    void installationChanged(UUID tenantId, UUID installationId);

    /**
     * Announces the platform-level revocation (kill switch) of a plugin version. To be called AFTER
     * {@code plugin_version.status = REVOKED} is committed. Never throws.
     */
    void versionRevoked(UUID pluginVersionId);

    /**
     * Loads, from the database, the enabled installations of the tenant that this instance does not have loaded
     * (blocking). Used by the RabbitMQ consumer when the registry has no handle: a database query per tenant at most
     * once per {@code plugins.sync.lazy-sync-cooldown-ms} (concurrent callers of one tenant wait for the first one),
     * and a failed installation is not retried for {@code plugins.sync.failed-load-backoff-ms}.
     *
     * @return what was loaded and which installations could not be loaded (never {@code null})
     * @throws RuntimeException when the database itself is unavailable (infrastructure error - the caller lets the
     *                          message be retried)
     */
    EnsureResult ensureTenantLoaded(UUID tenantId);

    /**
     * Non-blocking variant for paths with a hard latency budget ({@code PRE_CONTACT_CONNECT}, {@code MANUAL_ACTION}):
     * schedules {@link #ensureTenantLoaded} in the background (coalesced, same cooldown) and returns immediately. The
     * current call proceeds without the plugin; the next one sees it.
     */
    void requestTenantSync(UUID tenantId);

    /**
     * @param loaded   installations loaded by this call
     * @param failures installationId -> failure, for enabled installations that could not be loaded now or inside the
     *                 failure backoff (never {@code null})
     */
    record EnsureResult(int loaded, Map<UUID, LoadFailure> failures) {
        static final EnsureResult EMPTY = new EnsureResult(0, Map.of());
    }

    /**
     * @param reason          FIXED text plus the exception class - never text produced by plugin code (it may echo
     *                        configuration); the full message stays in the technical log only
     * @param extensionPoints extension points the plugin declares in its manifest; EMPTY = unknown (report for all)
     */
    record LoadFailure(String reason, Set<ExtensionPoint> extensionPoints) {
    }
}
