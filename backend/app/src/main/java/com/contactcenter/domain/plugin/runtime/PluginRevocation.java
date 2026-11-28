package com.contactcenter.domain.plugin.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-way "this plugin instance is dead" flag shared by the {@code PluginContextImpl}s of ONE loaded instance and the
 * clients they hand out (BE-244 W2).
 *
 * <p>Why: a timed-out / unloaded / revoked plugin may leave a thread behind (the JVM cannot kill a thread; closing the
 * class loader only blocks classes not loaded yet). That orphan would otherwise keep a valid context - the tenant, the
 * granted permissions, the services - and could keep calling the platform after a kill switch. Every platform API
 * reachable by a plugin calls {@link #requireActive()} first, so after {@link #revoke()} the orphan gets an exception
 * instead of data.
 *
 * <p>The flag is set BEFORE the class loader is closed (unload, timeout of a callback) and, for the kill switch,
 * immediately in phase 1 - before {@code onDeactivate} even starts.
 */
public final class PluginRevocation {

    private final AtomicBoolean revoked = new AtomicBoolean();

    /** Irreversible; idempotent. */
    public void revoke() {
        revoked.set(true);
    }

    public boolean isRevoked() {
        return revoked.get();
    }

    /** @throws PluginRevokedException when the instance was unloaded, timed out or revoked */
    public void requireActive() {
        if (revoked.get()) {
            throw new PluginRevokedException();
        }
    }
}
