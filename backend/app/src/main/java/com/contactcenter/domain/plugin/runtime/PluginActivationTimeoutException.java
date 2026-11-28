package com.contactcenter.domain.plugin.runtime;

/**
 * A plugin lifecycle callback ({@code onActivate}/{@code onDeactivate}) was STARTED and did not finish within the
 * timeout (BE-244 N2). The JVM cannot kill the thread, so it stays behind as an orphan (counted by
 * {@code plugin.lifecycle.orphaned}); for {@code onActivate} the installation is quarantined. Distinct from
 * {@link PluginLifecycleCapacityException}: there the callback never ran.
 */
public class PluginActivationTimeoutException extends PluginActivationException {

    private final String callback;

    public PluginActivationTimeoutException(String message, String callback, Throwable cause) {
        super(message, cause);
        this.callback = callback;
    }

    public String getCallback() {
        return callback;
    }
}
