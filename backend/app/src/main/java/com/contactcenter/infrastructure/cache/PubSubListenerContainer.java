package com.contactcenter.infrastructure.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The application's shared Redis pub/sub listener container (BE-179; also instantiated, with other executor sizing, for the WebSocket fan-out, BE-175).
 *
 * <ul>
 *   <li><b>No auto-start.</b> {@code RedisMessageListenerContainer.start()} fails fast when Redis is unreachable
 *       at the very first subscription attempt (spring-data-redis 3.3.5: the initial attempt is not retried, only
 *       recoveries after a successful subscription are), which would abort the context refresh. The container is
 *       therefore started by {@link PubSubListenerContainerSupervisor} in the background, with retry/backoff.</li>
 *   <li><b>Bounded executors</b> instead of the default {@code SimpleAsyncTaskExecutor} (a new unbounded thread per
 *       message): one dedicated thread holds the Redis subscription, a small pool with a bounded queue runs the
 *       listeners. A full queue drops the message (log + {@code cc.pubsub.dispatch.rejected}); acceptable for
 *       idempotent cache invalidations whose TTL is the safety net. Messages are NOT dispatched in order (2+
 *       threads) - the WebSocket fan-out (BE-175) therefore uses its own instance with 1 dispatch thread.</li>
 * </ul>
 */
@Slf4j
public class PubSubListenerContainer extends RedisMessageListenerContainer {

    static final int DISPATCH_THREADS = 2;
    static final int DISPATCH_QUEUE_CAPACITY = 256;
    static final long RECOVERY_INTERVAL_MS = 5_000;

    private final ThreadPoolExecutor subscriptionExecutor;
    private final ThreadPoolExecutor dispatchExecutor;

    /** The shared container for idempotent notifications (cache invalidation): 2 dispatch threads, no ordering. */
    public PubSubListenerContainer(RedisConnectionFactory connectionFactory, MeterRegistry meterRegistry) {
        this(connectionFactory, "redis-pubsub", DISPATCH_THREADS, DISPATCH_QUEUE_CAPACITY,
                () -> meterRegistry.counter("cc.pubsub.dispatch.rejected").increment());
    }

    /**
     * A dedicated container (BE-175 WebSocket fan-out): the caller picks the executor sizing.
     *
     * @param name            thread name prefix ({@code <name>-subscription-}, {@code <name>-dispatch-})
     * @param dispatchThreads {@code 1} gives strictly ordered delivery of messages (per Redis connection)
     * @param queueCapacity   bounded dispatch queue; when full the message is dropped ({@code onRejected} + log)
     * @param onRejected      invoked for every dropped message (metric)
     */
    public PubSubListenerContainer(RedisConnectionFactory connectionFactory, String name, int dispatchThreads,
                                   int queueCapacity, Runnable onRejected) {
        subscriptionExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), daemonThreads(name + "-subscription-"));
        dispatchExecutor = new ThreadPoolExecutor(dispatchThreads, dispatchThreads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonThreads(name + "-dispatch-"),
                rejectionHandler(name, queueCapacity, onRejected));
        dispatchExecutor.allowCoreThreadTimeOut(true);
        setConnectionFactory(connectionFactory);
        setSubscriptionExecutor(subscriptionExecutor);
        setTaskExecutor(dispatchExecutor);
        setRecoveryInterval(RECOVERY_INTERVAL_MS);
    }

    private static CustomizableThreadFactory daemonThreads(String prefix) {
        CustomizableThreadFactory factory = new CustomizableThreadFactory(prefix);
        factory.setDaemon(true);
        return factory;
    }

    private static RejectedExecutionHandler rejectionHandler(String name, int queueCapacity, Runnable onRejected) {
        AtomicLong lastWarnMillis = new AtomicLong();
        return (task, executor) -> {
            onRejected.run();
            long now = System.currentTimeMillis();
            long last = lastWarnMillis.get();
            if (now - last > 10_000 && lastWarnMillis.compareAndSet(last, now)) {
                log.warn("[PubSub] Kolejka dispatchu '{}' pełna ({}) - komunikat Redis odrzucony (log co 10 s)",
                        name, queueCapacity);
            }
        };
    }

    /** Starting is the job of {@link PubSubListenerContainerSupervisor}. */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public void destroy() throws Exception {
        try {
            super.destroy();
        } finally {
            // the subscription is closed (no new messages): let already queued messages finish for ~2 s
            dispatchExecutor.shutdown();
            try {
                if (!dispatchExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    dispatchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                dispatchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            subscriptionExecutor.shutdownNow();
        }
    }
}
