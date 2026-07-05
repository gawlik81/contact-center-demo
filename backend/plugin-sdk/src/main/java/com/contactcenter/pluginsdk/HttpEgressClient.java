package com.contactcenter.pluginsdk;

import java.util.Map;

/**
 * Restricted outbound HTTP client exposed to a plugin via {@link PluginContext#httpClient()}.
 *
 * <p>This is the <b>only</b> way a plugin may reach the network. Only {@code GET} and
 * {@code POST} are supported, intentionally — this SDK module declares the minimal contract;
 * the host's implementation is responsible for enforcing the manifest's declared
 * {@code http:egress:<host>} permissions (allow-list per host) before any call is made, and for
 * applying a circuit breaker per {@code (tenant_id, plugin_key, host)} so a failing external
 * system degrades gracefully. None of that enforcement is part of this interface's contract —
 * a plugin author should simply expect calls to hosts it did not declare in its manifest to be
 * rejected by the implementation, typically surfaced as a {@link RuntimeException} or an
 * {@link HttpResponse} with a 4xx/5xx status, depending on the host implementation.
 */
public interface HttpEgressClient {

    /**
     * Performs an HTTP GET request.
     *
     * @param url     absolute URL to request; the host must match one of the plugin's
     *                declared egress permissions
     * @param headers request headers to send, never {@code null} (may be empty)
     * @return the response received from the remote host
     */
    HttpResponse get(String url, Map<String, String> headers);

    /**
     * Performs an HTTP POST request.
     *
     * @param url     absolute URL to request; the host must match one of the plugin's
     *                declared egress permissions
     * @param headers request headers to send, never {@code null} (may be empty)
     * @param body     raw request body bytes, never {@code null} (may be empty)
     * @return the response received from the remote host
     */
    HttpResponse post(String url, Map<String, String> headers, byte[] body);
}
