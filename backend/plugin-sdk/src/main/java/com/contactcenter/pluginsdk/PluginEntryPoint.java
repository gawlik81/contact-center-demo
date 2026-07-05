package com.contactcenter.pluginsdk;

import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.CustomerSyncResult;
import com.contactcenter.pluginsdk.model.DispositionEvent;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;

/**
 * Entry point every plugin JAR must implement and declare as {@code entryPointClass} in its
 * {@code META-INF/plugin-manifest.json}.
 *
 * <p>The host instantiates exactly one instance of the implementing class per installed
 * {@code (tenant_id, plugin_key)} pair, via a no-arg constructor, inside a dedicated
 * {@code ClassLoader} — dependency injection into the plugin's own constructor is not
 * supported; all platform access happens exclusively through the {@link PluginContext} passed
 * into {@link #onActivate(PluginContext)} and into each extension-point callback.
 *
 * <p>Every callback below corresponds to one of the platform's fixed, backend-defined
 * extension points ({@code PRE_CONTACT_CONNECT}, {@code POST_CONTACT_END},
 * {@code CUSTOMER_SYNC}, {@code DISPOSITION_SET}, {@code MANUAL_ACTION}) and is only invoked
 * if the installation's manifest declares that extension point and the tenant administrator
 * has bound it. A plugin only needs to override the {@code default} methods corresponding to
 * the extension points it actually supports — all five have safe no-op defaults so a minimal
 * plugin can implement just {@link #onActivate(PluginContext)} and
 * {@link #onDeactivate()}.
 *
 * <p>Every callback is invoked through a bounded executor with a per-call timeout and is
 * wrapped in exception containment by the host — a plugin that throws or hangs cannot
 * destabilize the platform, but it also means a plugin should not assume its return value is
 * always observed (e.g. on timeout, the host discards the result and uses the relevant
 * {@code *.empty()}/{@code *.noop()}/{@code *.unsupported()} default instead).
 */
public interface PluginEntryPoint {

    /**
     * Called exactly once when the installation is enabled (first install, or admin
     * re-enabling a previously disabled installation).
     *
     * <p>This is the only privileged callback that may run setup logic — e.g. validating
     * connectivity to an external system using {@code context.httpClient()} and
     * {@code context.config()}. Throwing from this method aborts activation; the
     * installation is left disabled and the exception is recorded for the tenant
     * administrator to see.
     *
     * @param context facade through which this plugin instance reaches the platform for the
     *                 lifetime of the installation
     */
    void onActivate(PluginContext context);

    /**
     * Called exactly once when the installation is disabled or uninstalled.
     *
     * <p>Implementations should release any resources acquired in
     * {@link #onActivate(PluginContext)} (e.g. close cached connections held in plugin-local
     * fields). No {@link PluginContext} is supplied here — by the time this is called, the
     * installation's facade is no longer considered valid for new platform calls.
     */
    void onDeactivate();

    /**
     * Called for the {@code PRE_CONTACT_CONNECT} extension point, just before an agent
     * connects to a contact (e.g. an incoming call is about to be presented).
     *
     * <p><b>Blocking, timeout-bounded:</b> the agent desktop waits briefly for this result
     * before connecting; on timeout or any error, the connect proceeds anyway — a plugin can
     * never block core telephony. Typical use: look up the customer in an external CRM and
     * surface a short summary to the agent.
     *
     * @param ctx facade for this invocation, scoped to the calling tenant
     * @param e    event describing the contact about to connect
     * @return data to display to the agent, or {@link PreContactConnectResult#empty()} if the
     *         plugin has nothing to add; the default implementation returns
     *         {@link PreContactConnectResult#empty()}
     */
    default PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
        return PreContactConnectResult.empty();
    }

    /**
     * Called for the {@code POST_CONTACT_END} extension point, after a contact has ended.
     *
     * <p><b>Fire-and-forget:</b> dispatched asynchronously over
     * {@code cc.queue.plugin-invocation}; plugin latency or failure here never affects the
     * agent desktop. Typical use: push a summary of the completed interaction to an external
     * system. The default implementation does nothing.
     *
     * @param ctx facade for this invocation, scoped to the calling tenant
     * @param e    event describing the contact that ended
     */
    default void onPostContactEnd(PluginContext ctx, ContactEvent e) {
        // no-op by default
    }

    /**
     * Called for the {@code CUSTOMER_SYNC} extension point, when the platform asks a plugin to
     * reconcile a customer record with the plugin's external system.
     *
     * <p><b>Fire-and-forget:</b> dispatched asynchronously over
     * {@code cc.queue.plugin-invocation}. The default implementation returns
     * {@link CustomerSyncResult#noop()}, indicating this plugin does not support customer
     * synchronization.
     *
     * @param ctx facade for this invocation, scoped to the calling tenant
     * @param req  identifies the customer to synchronize and why
     * @return the outcome of the synchronization attempt
     */
    default CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) {
        return CustomerSyncResult.noop();
    }

    /**
     * Called for the {@code DISPOSITION_SET} extension point, when an agent sets the outcome
     * of a contact.
     *
     * <p><b>Fire-and-forget:</b> dispatched asynchronously over
     * {@code cc.queue.plugin-invocation}. Typical use: notify an external ticketing system
     * that a case was resolved. The default implementation does nothing.
     *
     * @param ctx facade for this invocation, scoped to the calling tenant
     * @param e    event describing the disposition that was set
     */
    default void onDispositionSet(PluginContext ctx, DispositionEvent e) {
        // no-op by default
    }

    /**
     * Called for the {@code MANUAL_ACTION} extension point, when an agent explicitly invokes a
     * plugin-provided action from the desktop UI (e.g. clicking a toolbar button the plugin
     * declared in its manifest's {@code manualActions}).
     *
     * <p><b>Blocking, timeout-bounded:</b> the agent is waiting on a direct request/response.
     * The default implementation returns {@link ManualActionResult#unsupported()}.
     *
     * @param ctx facade for this invocation, scoped to the calling tenant
     * @param req  identifies the action invoked and any agent-supplied parameters
     * @return the outcome of the action, rendered directly in the agent desktop UI
     */
    default ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
        return ManualActionResult.unsupported();
    }
}
