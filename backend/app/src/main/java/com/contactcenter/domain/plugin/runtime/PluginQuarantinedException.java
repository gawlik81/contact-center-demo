package com.contactcenter.domain.plugin.runtime;

/**
 * Load refused without touching the callback pool: the installation is quarantined after a timeout of its
 * {@code onActivate} (BE-244 N2). Without it every retry (failed-load backoff x every instance) would park one more
 * thread of the bounded lifecycle pool in a plugin that does not return.
 */
public class PluginQuarantinedException extends PluginActivationException {

    public PluginQuarantinedException(String message) {
        super(message);
    }
}
