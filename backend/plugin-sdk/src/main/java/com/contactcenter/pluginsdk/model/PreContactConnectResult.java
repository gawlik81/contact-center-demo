package com.contactcenter.pluginsdk.model;

import java.util.Map;

/**
 * Result returned by {@code PluginEntryPoint#onPreContactConnect}.
 *
 * <p>{@code PRE_CONTACT_CONNECT} is a <b>blocking, timeout-bounded</b> extension point: the
 * agent desktop waits briefly for this result before connecting the call, but on timeout or
 * error the connect proceeds anyway — a plugin can never block core telephony. The returned
 * {@code displayData} (if any) is surfaced read-only in the agent desktop's customer panel
 * (e.g. "last 3 orders from CRM"); it has no effect on routing or call control.
 *
 * @param displayData  key/value pairs to display to the agent before/while connecting, never
 *                      {@code null} (use {@link #empty()} for "nothing to show")
 * @param warning       optional human-readable warning to surface to the agent (e.g. "customer
 *                      has an open complaint"), or {@code null} if none
 */
public record PreContactConnectResult(Map<String, Object> displayData, String warning) {

    /**
     * Default result used by {@code PluginEntryPoint}'s no-op implementation and returned by
     * the host when a plugin invocation times out or fails — conveys "nothing to show", never
     * blocks the connect flow.
     *
     * @return an empty result with no display data and no warning
     */
    public static PreContactConnectResult empty() {
        return new PreContactConnectResult(Map.of(), null);
    }
}
