package com.contactcenter.domain.plugin.runtime;

/**
 * Thrown to plugin code that calls a platform API through a context of an instance that was already unloaded, timed
 * out or revoked (BE-244 W2). Deliberately carries no tenant/plugin details: the caller is, by definition, code that
 * should no longer be running.
 */
public class PluginRevokedException extends IllegalStateException {

    public PluginRevokedException() {
        super("Plugin instance was deactivated; the platform API is no longer available");
    }
}
