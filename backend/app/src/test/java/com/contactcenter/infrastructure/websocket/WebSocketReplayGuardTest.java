package com.contactcenter.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocketReplayGuard: ograniczony zbior widzianych podpisow")
class WebSocketReplayGuardTest {

    @Test
    @DisplayName("pierwszy raz true, powtorzenie false; null/pusty podpis (koperta niepodpisana) nigdy nie jest powtorka")
    void firstSeenAndUnsigned() {
        WebSocketReplayGuard guard = new WebSocketReplayGuard(10);
        assertThat(guard.firstSeen("a")).isTrue();
        assertThat(guard.firstSeen("a")).isFalse();
        assertThat(guard.firstSeen(null)).isTrue();
        assertThat(guard.firstSeen(null)).isTrue();
        assertThat(guard.firstSeen(" ")).isTrue();
    }

    @Test
    @DisplayName("pojemnosc jest ograniczona: najstarszy wypada (pamiec nie rosnie bez limitu)")
    void oldestIsEvictedAtCapacity() {
        WebSocketReplayGuard guard = new WebSocketReplayGuard(3);
        for (String s : new String[]{"1", "2", "3", "4"}) {
            guard.firstSeen(s);
        }
        assertThat(guard.firstSeen("1")).as("wypadl, wiec znow 'nowy'").isTrue();
        assertThat(guard.firstSeen("4")).isFalse();
    }
}
