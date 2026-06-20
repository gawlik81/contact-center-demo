package com.contactcenter.pluginsdk;

/**
 * Logging facility exposed to a plugin via {@link PluginContext#logger()}.
 *
 * <p>Deliberately separate from any application-wide logging framework (e.g. SLF4J): messages
 * written here are captured by the host and persisted into {@code plugin_invocation_log}
 * (or an equivalent diagnostic sink), scoped to the installation that produced them — never
 * mixed into the platform's own application logs. This keeps a noisy or misbehaving plugin
 * from polluting operational logs the platform team relies on.
 */
public interface PluginLogger {

    /**
     * Logs an informational message about normal plugin operation.
     *
     * @param message human-readable message
     */
    void info(String message);

    /**
     * Logs a message about a recoverable or unexpected condition that does not necessarily
     * fail the current invocation.
     *
     * @param message human-readable message
     */
    void warn(String message);

    /**
     * Logs an error, typically alongside an exception caught by the plugin itself before it
     * would otherwise propagate (the host also independently records uncaught exceptions).
     *
     * @param message   human-readable message
     * @param throwable  the cause of the error, or {@code null} if not available
     */
    void error(String message, Throwable throwable);
}
