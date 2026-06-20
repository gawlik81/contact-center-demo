package com.contactcenter.pluginsdk.model;

import java.util.Map;

/**
 * Result returned by {@code PluginEntryPoint#onManualAction}.
 *
 * <p>Since {@code MANUAL_ACTION} is a blocking, user-initiated request, this result is
 * returned directly to the agent desktop UI to render (e.g. open a link, show a confirmation
 * message, display returned data).
 *
 * @param success    {@code true} if the action completed successfully, {@code false} otherwise
 * @param resultData key/value pairs to surface to the agent desktop UI, never {@code null}
 *                    (may be empty)
 * @param message    optional human-readable detail, e.g. an error summary when
 *                    {@code success} is {@code false}, or {@code null}
 */
public record ManualActionResult(boolean success, Map<String, Object> resultData, String message) {

    /**
     * Default result used by {@code PluginEntryPoint}'s no-op implementation for plugins that
     * do not implement the requested action (e.g. the manifest declares
     * {@code MANUAL_ACTION} for one action ID but the entry point's default was invoked for
     * an unrecognized one).
     *
     * @return a failed result indicating the action is not supported
     */
    public static ManualActionResult unsupported() {
        return new ManualActionResult(false, Map.of(), "Action not supported by plugin");
    }
}
