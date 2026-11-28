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

    @Test
    @DisplayName("W1: warianty tekstowe tego samego podpisu (spacje, nieistotny bit ostatniego znaku) to JEDEN wpis - powtorka")
    void textualVariantsOfOneSignatureAreOneEntry() {
        WebSocketReplayGuard guard = new WebSocketReplayGuard(10);
        String sig = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"; // 43 chars = 32 bytes
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String sameBytes = sig.substring(0, 42) + alphabet.charAt(alphabet.indexOf(sig.charAt(42)) ^ 1);

        assertThat(guard.firstSeen(sig)).isTrue();
        assertThat(guard.firstSeen(sig + "=")).isFalse(); // padding variant of the same bytes
        assertThat(guard.firstSeen(sameBytes)).isFalse();
    }
}
