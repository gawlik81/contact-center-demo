package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** BE-244 N3: the token bucket of the lazy asset resolution, on a manual clock (no sleeping). */
@DisplayName("TokenBucket - limit, odnawianie, wspolbieznosc (zegar manualny)")
class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    @DisplayName("burst tokenow dostepny od razu, potem odmowa")
    void burstThenRefusal() {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(1.0, 3, clock::get);

        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("tokeny odnawiaja sie z czasem, ale nigdy ponad burst")
    void refillIsContinuousAndCappedAtBurst() {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(2.0, 3, clock::get);
        for (int i = 0; i < 3; i++) {
            bucket.tryAcquire();
        }

        clock.addAndGet(SECOND / 2); // 2/s * 0.5 s = 1 token
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        clock.addAndGet(100 * SECOND); // a long quiet period still yields only `burst`
        int granted = 0;
        while (bucket.tryAcquire()) {
            granted++;
        }
        assertThat(granted).isEqualTo(3);
    }

    @Test
    @DisplayName("release() zwraca niewykorzystany token (z limitem burst)")
    void releaseReturnsAnUnusedToken() {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(1.0, 1, clock::get);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();

        bucket.release();
        assertThat(bucket.tryAcquire()).isTrue();

        bucket.release();
        bucket.release(); // capped: never more than burst
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("rate = 0: tokeny nigdy sie nie odnawiaja (dlatego PluginSyncProperties wymaga rate > 0)")
    void zeroRateNeverRefills() {
        AtomicLong clock = new AtomicLong();
        TokenBucket bucket = new TokenBucket(0.0, 2, clock::get);
        assertThat(bucket.tryAcquire()).isTrue();
        assertThat(bucket.tryAcquire()).isTrue();

        clock.addAndGet(1_000 * SECOND);

        assertThat(bucket.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("8 watkow naraz przy zamrozonym zegarze: dokladnie burst sukcesow (<= burst + rate*czas, tu czas = 0)")
    void concurrentCallersNeverExceedBurstPlusRefill() throws Exception {
        AtomicLong clock = new AtomicLong();
        int burst = 20;
        TokenBucket bucket = new TokenBucket(5.0, burst, clock::get);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 100; i++) {
                        if (bucket.tryAcquire()) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get()).isEqualTo(burst);

        clock.addAndGet(2 * SECOND); // 5/s * 2 s = 10 more, never more
        int more = 0;
        while (bucket.tryAcquire()) {
            more++;
        }
        assertThat(more).isEqualTo(10);
    }
}
