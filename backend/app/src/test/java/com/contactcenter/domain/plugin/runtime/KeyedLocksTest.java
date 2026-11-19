package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KeyedLocks - lock per klucz, bez kolizji i ze sprzątaniem (BE-178)")
class KeyedLocksTest {

    @Test
    @DisplayName("różne klucze nie blokują się nawzajem (brak kolizji jak w paskach)")
    void differentKeysDoNotBlockEachOther() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        KeyedLocks.Handle first = locks.of(UUID.randomUUID());
        first.lock();
        try {
            CountDownLatch acquired = new CountDownLatch(1);
            Thread other = new Thread(() -> {
                KeyedLocks.Handle h = locks.of(UUID.randomUUID());
                h.lock();
                acquired.countDown();
                h.unlock();
            });
            other.start();
            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            first.unlock();
        }
    }

    @Test
    @DisplayName("ten sam klucz jest wzajemnie wykluczający, a wpis znika po zwolnieniu (brak wycieku)")
    void sameKeyExcludesAndEntryIsRemoved() throws Exception {
        KeyedLocks locks = new KeyedLocks();
        UUID key = UUID.randomUUID();
        KeyedLocks.Handle held = locks.of(key);
        held.lock();
        CountDownLatch acquired = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            KeyedLocks.Handle h = locks.of(key);
            h.lock();
            acquired.countDown();
            h.unlock();
        });
        waiter.start();
        assertThat(acquired.await(300, TimeUnit.MILLISECONDS)).isFalse();

        held.unlock();

        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        waiter.join();
        assertThat(locks.size()).isZero();
    }
}
