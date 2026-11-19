package com.contactcenter.domain.plugin.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A real lock PER KEY (BE-178): two different installations never share a lock (no head-of-line blocking from hash
 * collisions as with striped locks), and the entry of a key is removed as soon as nobody holds or waits for it, so the
 * map does not grow with the number of installations/tenants ever seen.
 *
 * <p>Usage: {@code Handle h = locks.of(key); h.lock(); try { ... } finally { h.unlock(); }} - {@code of} registers
 * interest, {@code unlock} releases it, so every {@code of} must be followed by a lock/unlock pair.
 */
final class KeyedLocks {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    final class Handle {
        private final UUID key;
        private final Entry entry;

        private Handle(UUID key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        void lock() {
            entry.lock.lock();
        }

        void unlock() {
            entry.lock.unlock();
            synchronized (entries) {
                if (--entry.refs == 0) {
                    entries.remove(key, entry);
                }
            }
        }
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    Handle of(UUID key) {
        synchronized (entries) {
            Entry entry = entries.computeIfAbsent(key, k -> new Entry());
            entry.refs++;
            return new Handle(key, entry);
        }
    }

    /** Number of keys currently held or awaited (tests: the map is cleaned up). */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
