package com.contactcenter.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts {@link PubSubListenerContainer} after the context is refreshed, in a background daemon thread, retrying with
 * exponential backoff (1 s .. 30 s) until the first {@code start()} succeeds - so the application boots with Redis
 * down and the subscriptions come up once Redis is back. After the first successful start, loss/recovery of the
 * connection is handled by the container itself ({@code recoveryInterval}).
 *
 * <p>A failed {@code start()} leaves the container in the "started" state, therefore every retry is {@code stop()}
 * followed by {@code start()}. Listeners must be registered during bean initialisation (before this lifecycle
 * starts); {@code addMessageListener} on a started container with Redis down throws and is absorbed by the bus.
 */
@Slf4j
public class PubSubListenerContainerSupervisor implements SmartLifecycle {

    private static final long INITIAL_DELAY_MS = 1_000;
    private static final long MAX_DELAY_MS = 30_000;

    private final RedisMessageListenerContainer container;
    private final long initialDelayMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;

    public PubSubListenerContainerSupervisor(RedisMessageListenerContainer container) {
        this(container, INITIAL_DELAY_MS);
    }

    public PubSubListenerContainerSupervisor(RedisMessageListenerContainer container, long initialDelayMs) {
        this.container = container;
        this.initialDelayMs = initialDelayMs;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::startWithRetry, "redis-pubsub-supervisor");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void startWithRetry() {
        long delay = initialDelayMs;
        int attempt = 0;
        while (running.get()) {
            attempt++;
            try {
                if (container.isRunning()) {
                    container.stop();
                }
                container.start();
                log.info("[PubSub] Kontener subskrypcji Redis uruchomiony (próba {})", attempt);
                return;
            } catch (Exception e) {
                log.warn("[PubSub] Start kontenera subskrypcji Redis nieudany (próba {}): {}. Ponowię za {} ms; "
                        + "cache lokalne odświeżą się po TTL.", attempt, e.toString(), delay);
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = Math.min(delay * 2, MAX_DELAY_MS);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Start late (after beans that register listeners in their initialisation). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
