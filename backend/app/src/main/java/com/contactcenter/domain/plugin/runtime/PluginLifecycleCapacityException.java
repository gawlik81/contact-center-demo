package com.contactcenter.domain.plugin.runtime;

/**
 * The lifecycle callback pool could not even START the callback: its queue is full or the task waited in the queue for
 * the whole timeout behind orphaned threads (BE-244 N2). It says nothing about the plugin, so it never leads to a
 * quarantine - the load is simply retried after the usual backoff, when the pool has room again.
 */
public class PluginLifecycleCapacityException extends PluginActivationException {

    public PluginLifecycleCapacityException(String message, Throwable cause) {
        super(message, cause);
    }
}
