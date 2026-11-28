package com.contactcenter.domain.plugin;

/**
 * Cause of {@code tenant_plugin_installation.health_status = 'DEGRADED'} (V153, column {@code degraded_reason}).
 *
 * <p>Meaningful ONLY while {@code health_status = DEGRADED}; every other status has no reason (NULL). The invariant
 * is kept by the application: every UPDATE that changes {@code health_status} sets or clears the reason in the SAME
 * statement (see {@link TenantPluginInstallationRepository}).
 *
 * <p>Precedence (BE-245/BE-244, documented in 04-backend.md section 4.9): the circuit breaker OVERWRITES
 * {@link #CONFIG_LOAD_FAILED} with {@link #CIRCUIT_OPEN} (a live plugin failing repeatedly is the fresher and
 * stronger signal), while a failed configuration load NEVER overwrites {@link #CIRCUIT_OPEN} (it only moves
 * {@code HEALTHY -> DEGRADED}).
 */
public enum DegradedReason {

    /** The circuit breaker opened after {@link TenantPluginInstallation#CIRCUIT_FAILURE_THRESHOLD} consecutive failures. */
    CIRCUIT_OPEN,

    /** The plugin instance failed to (re)load after an {@code installation_config} change. */
    CONFIG_LOAD_FAILED
}
