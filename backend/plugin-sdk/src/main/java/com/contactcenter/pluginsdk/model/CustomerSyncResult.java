package com.contactcenter.pluginsdk.model;

/**
 * Result returned by {@code PluginEntryPoint#onCustomerSync}.
 *
 * <p>Since {@code CUSTOMER_SYNC} is fire-and-forget, this result is not surfaced to any
 * waiting caller — it is recorded into {@code plugin_invocation_log} for audit/debugging and,
 * on {@code synced = true}, may be reflected in the admin/supervisor UI as "last synced at".
 *
 * @param synced   {@code true} if the plugin successfully synchronized the customer with its
 *                  external system, {@code false} otherwise
 * @param message  optional human-readable detail (e.g. an external record ID, or an error
 *                  summary when {@code synced} is {@code false}), or {@code null}
 */
public record CustomerSyncResult(boolean synced, String message) {

    /**
     * Default result used by {@code PluginEntryPoint}'s no-op implementation for plugins that
     * do not support {@code CUSTOMER_SYNC} — recorded as "nothing happened", not as a failure.
     *
     * @return a result indicating no synchronization was performed
     */
    public static CustomerSyncResult noop() {
        return new CustomerSyncResult(false, "Plugin does not implement CUSTOMER_SYNC");
    }
}
