package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.security.StompDestinations;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishing side of the cross-instance WebSocket fan-out (BE-175, ADR-14 option B).
 *
 * <pre>
 *   publish(dest, tenant, event)
 *     1. serialize once (messaging converter)
 *     2. deliver to THIS instance's SimpleBroker, synchronously (independent of Redis; the receiver ignores
 *        envelopes carrying our own instance id, so there is no local duplicate)
 *     3. wrap into an envelope and hand it to ONE ordered publisher thread -> Redis PUBLISH
 * </pre>
 *
 * <p>Redis is never on the caller's thread: callers are RabbitMQ listeners, schedulers and request threads, and a
 * hanging Redis must not stall them (the command timeout is seconds). The publisher queue is bounded; when Redis
 * fails the sender backs off ({@code publish-backoff-ms}) and drops events, counting them
 * ({@code ws.fanout.dropped}) with a rate-limited WARN - the application keeps running and local sessions keep
 * receiving. Events are NOT replayed after Redis comes back (consistent with FE-152: the client reloads state).
 *
 * <p>The worker thread does not use the tenant context; it clears any context inherited from the thread that
 * created it ({@link TenantContext} is an {@code InheritableThreadLocal}) so nothing leaks into a pooled thread.
 */
@Slf4j
@Component
public class WebSocketFanoutPublisher {

    private final WebSocketLocalDispatcher dispatcher;
    private final StringRedisTemplate redis;
    private final WebSocketFanoutProperties properties;
    private final WebSocketFanoutMetrics metrics;
    private final WebSocketChannelSigner signer;
    private final String instanceId;
    private final ThreadPoolExecutor publisherExecutor;
    private final BlockingQueue<Runnable> publishQueue;

    private final AtomicLong queuedBytes = new AtomicLong();
    private volatile boolean paused;
    private volatile long pausedUntilNanos;
    private volatile boolean degraded;

    public WebSocketFanoutPublisher(WebSocketLocalDispatcher dispatcher, StringRedisTemplate redis,
                                    WebSocketFanoutProperties properties, WebSocketFanoutMetrics metrics,
                                    MeterRegistry meterRegistry, WebSocketChannelSigner signer) {
        this.dispatcher = dispatcher;
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
        this.signer = signer;
        this.instanceId = properties.resolvedInstanceId();
        this.publishQueue = new ArrayBlockingQueue<>(properties.getPublishQueueCapacity());
        CustomizableThreadFactory threads = new CustomizableThreadFactory("ws-fanout-publisher-") {
            @Override
            public Thread newThread(Runnable runnable) {
                return super.newThread(() -> {
                    TenantContext.clear(); // drop the context inherited from the creating thread
                    runnable.run();
                });
            }
        };
        threads.setDaemon(true);
        this.publisherExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, publishQueue, threads);
        Gauge.builder("ws.fanout.publish.queue.size", publishQueue, BlockingQueue::size)
                .description("Envelopes waiting for the Redis publisher thread").register(meterRegistry);
        log.info("[WS-Fanout] instanceId={}, enabled={}, channel={}", instanceId, properties.isEnabled(),
                properties.effectiveChannel());
    }

    /**
     * Delivers {@code event} to the local broker and (when enabled) to all other instances.
     *
     * @param destination an allow-listed broadcast destination ({@link StompDestinations})
     * @param tenantId    tenant of the event; for tenant topics it must match the tenant inside {@code destination}
     * @throws IllegalArgumentException for a destination outside the allow-list (programming error)
     */
    public void publish(String destination, UUID tenantId, WebSocketEvent event) {
        StompDestinations.Parsed parsed = StompDestinations.parse(destination);
        if (parsed == null || (parsed.isTenantScoped() && !parsed.id().equals(tenantId))) {
            metrics.dropped("publish", "invalid_destination");
            throw new IllegalArgumentException("WebSocket destination not allowed for fan-out");
        }
        byte[] json = dispatcher.serialize(event);

        try {
            dispatcher.deliver(destination, json);
            metrics.delivered("local");
        } catch (Exception e) {
            log.error("[WS-Fanout] Błąd lokalnej dostawy destination={}, eventType={}: {}", destination,
                    event.eventType(), e.getMessage(), e);
        }

        if (properties.isEnabled() && parsed.kind() == StompDestinations.Kind.USER_EVENTS && tenantId == null) {
            // the receiver requires a tenant on unicast envelopes; delivered locally only
            metrics.dropped("publish", "tenant_missing");
        } else if (properties.isEnabled()) {
            enqueueForRedis(parsed, destination, tenantId, json);
        }
    }

    private void enqueueForRedis(StompDestinations.Parsed parsed, String destination, UUID tenantId, byte[] json) {
        if (isPaused()) {
            metrics.dropped("publish", "redis_unavailable");
            return;
        }
        WebSocketFanoutEnvelope unsigned = new WebSocketFanoutEnvelope(WebSocketFanoutEnvelope.VERSION, instanceId,
                destination, tenantId, System.currentTimeMillis(), new String(json, StandardCharsets.UTF_8));
        // BE-242: HMAC over the channel name and every field (null = signing off: dev/test without a secret)
        String envelope = unsigned.withSignature(
                signer.sign(properties.effectiveChannel(), unsigned.canonicalForSigning())).toJson();
        int envelopeBytes = envelope.getBytes(StandardCharsets.UTF_8).length;
        metrics.envelopeBytes("publish", envelopeBytes);
        if (envelopeBytes > properties.getMaxMessageBytes()) {
            metrics.dropped("publish", "oversize");
            metrics.warnRateLimited("publish-oversize",
                    "[WS-Fanout] Zdarzenie większe niż {} B nie zostało rozesłane do innych instancji "
                            + "(dostarczone lokalnie): destination={}", properties.getMaxMessageBytes(), destination);
            return;
        }
        String topic = topicTag(parsed);
        // byte budget of the queue (count alone would allow capacity x max-message-bytes)
        if (queuedBytes.addAndGet(envelopeBytes) > properties.getPublishQueueMaxBytes()) {
            queuedBytes.addAndGet(-envelopeBytes);
            metrics.dropped("publish", "publish_queue_bytes");
            metrics.warnRateLimited("publish-queue-bytes",
                    "[WS-Fanout] Budżet bajtowy kolejki publikacji ({} B) wyczerpany - zdarzenie nie trafi do innych "
                            + "instancji", properties.getPublishQueueMaxBytes());
            return;
        }
        try {
            publisherExecutor.execute(() -> {
                try {
                    publishToRedis(topic, envelope);
                } finally {
                    queuedBytes.addAndGet(-envelopeBytes);
                }
            });
        } catch (RejectedExecutionException e) {
            queuedBytes.addAndGet(-envelopeBytes);
            if (publisherExecutor.isShutdown()) {
                metrics.dropped("publish", "shutdown"); // instance stopping, not a full queue
                return;
            }
            metrics.dropped("publish", "publish_queue_full");
            metrics.warnRateLimited("publish-queue-full",
                    "[WS-Fanout] Kolejka publikacji do Redis pełna ({}) - zdarzenie nie trafi do innych instancji",
                    properties.getPublishQueueCapacity());
        }
    }

    /** Runs on the single publisher thread (keeps the publishing order of one caller). */
    private void publishToRedis(String topic, String envelope) {
        if (isPaused()) {
            metrics.dropped("publish", "redis_unavailable");
            return;
        }
        try {
            redis.convertAndSend(properties.effectiveChannel(), envelope);
            metrics.published(topic);
            if (degraded) {
                degraded = false;
                log.info("[WS-Fanout] Publikacja do Redis przywrócona");
            }
        } catch (Exception e) {
            degraded = true;
            pausedUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getPublishBackoffMs());
            paused = true;
            metrics.dropped("publish", "redis_unavailable");
            metrics.warnRateLimited("publish-failed",
                    "[WS-Fanout] Redis niedostępny - zdarzenia dostarczane tylko lokalnie, kolejne próby po {} ms: {}",
                    properties.getPublishBackoffMs(), e.toString());
        }
    }

    private boolean isPaused() {
        return paused && System.nanoTime() - pausedUntilNanos < 0;
    }

    private static String topicTag(StompDestinations.Parsed parsed) {
        return switch (parsed.kind()) {
            case USER_EVENTS -> "user";
            case TENANT_AGENTS -> "agents";
            case TENANT_SUPERVISOR -> "supervisor";
        };
    }

    @PreDestroy
    void shutdown() {
        // drain what is already queued (rolling deploy would otherwise lose the last seconds of events), then force
        publisherExecutor.shutdown();
        try {
            if (!publisherExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                publisherExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            publisherExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
