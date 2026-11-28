package com.contactcenter.infrastructure.websocket;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded "already seen" set of envelope signatures (BE-249 review W3): a signature is unique per (channel, fields, ts),
 * so a second delivery of the same signature within the freshness window is a replay (the transport itself never
 * duplicates a PUBLISH to one subscriber). Oldest entries are evicted first; the capacity covers far more than the
 * envelopes of one freshness window under normal load, and eviction only re-opens the replay window for old entries.
 * Thread-safe; O(1) per call.
 */
final class WebSocketReplayGuard {

    private final Map<String, Boolean> seen;

    WebSocketReplayGuard(int capacity) {
        this.seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /** @return {@code true} the first time {@code signature} is offered, {@code false} for a replay; {@code null}/blank = unsigned, never a replay */
    synchronized boolean firstSeen(String signature) {
        if (signature == null || signature.isBlank()) {
            return true;
        }
        return seen.put(signature, Boolean.TRUE) == null;
    }
}
