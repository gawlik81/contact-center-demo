package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.domain.websocket.WebSocketSessionTerminator;
import com.contactcenter.security.TenantContext;
import com.contactcenter.security.TokenRevocationService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link WebSocketSessionTerminator}. After the surrounding transaction commits (or at once without one) it hands ONE
 * task to a bounded executor; the task (1) closes the local sessions (works without Redis), (2) writes the "revoked
 * since" marker(s) so the old tokens cannot reconnect, (3) tells the other instances through
 * {@link WebSocketSessionControlBus} (absent when {@code websocket.session.control.enabled=false}). Each step is
 * isolated: a failing step never skips the next one.
 *
 * <p>Why local close BEFORE the marker: with a hanging Redis the marker write blocks for the command timeout, and the
 * session of a deactivated user must not stay open meanwhile. A window DOES exist for a client that reconnects at once
 * with the same token (before the marker is written); the local close is therefore repeated after the marker (see
 * {@code runSteps}). A well-behaved client needs an HTTP refresh first, and with Redis unreachable CONNECT is fail-closed.
 *
 * <p>Why asynchronous (BE-249, W4): Redis calls have multi-second timeouts; running them on the request thread (inside
 * {@code afterCommit}, still holding the transaction's thread and connection scope) would make a logout or a role
 * change as slow as the broken Redis. The executor is bounded ({@code publish-threads}, {@code publish-queue-capacity}).
 * When it is saturated (Redis has been failing for a long time) the caller falls back to closing the LOCAL sessions
 * only, synchronously (no I/O): the marker and the envelope are dropped and counted
 * ({@code ws.session.control.async.rejected}); the other instances then close at the token deadline.
 *
 * <p>The task needs no tenant context (it only talks to Redis and in-memory state); the worker threads clear whatever
 * {@code TenantContext} they inherit from the creating thread so nothing leaks into a pooled thread.
 */
@Slf4j
@Component
public class WebSocketSessionTerminatorImpl implements WebSocketSessionTerminator {

    static final String METRIC_ASYNC_REJECTED = "ws.session.control.async.rejected";
    private static final long WARN_INTERVAL_MILLIS = 10_000;

    private final WebSocketSessionCloser closer;
    private final ObjectProvider<WebSocketSessionControlBus> bus;
    private final TokenRevocationService revocation;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;
    private final AtomicLong lastWarn = new AtomicLong();

    @Autowired
    public WebSocketSessionTerminatorImpl(WebSocketSessionCloser closer,
                                          ObjectProvider<WebSocketSessionControlBus> bus,
                                          TokenRevocationService revocation, MeterRegistry meters,
                                          WebSocketSessionProperties properties) {
        this.closer = closer;
        this.bus = bus;
        this.revocation = revocation;
        this.meters = meters;
        this.clock = Clock.systemUTC();
        CustomizableThreadFactory threads = new CustomizableThreadFactory("ws-session-control-") {
            @Override
            public Thread newThread(Runnable runnable) {
                return super.newThread(() -> {
                    TenantContext.clear(); // InheritableThreadLocal: drop what the creating thread had
                    runnable.run();
                });
            }
        };
        threads.setDaemon(true);
        WebSocketSessionProperties.Control control = properties.getControl();
        this.ownedExecutor = new ThreadPoolExecutor(control.getPublishThreads(), control.getPublishThreads(), 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(control.getPublishQueueCapacity()), threads);
        this.executor = ownedExecutor;
    }

    /** Tests: run the work on the supplied executor (e.g. a direct one). */
    WebSocketSessionTerminatorImpl(WebSocketSessionCloser closer, ObjectProvider<WebSocketSessionControlBus> bus,
                                   TokenRevocationService revocation, MeterRegistry meters, Executor executor,
                                   Clock clock) {
        this.closer = closer;
        this.bus = bus;
        this.revocation = revocation;
        this.meters = meters;
        this.clock = clock;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    @Override
    public void closeUserSessions(UUID tenantId, UUID userId, SessionCloseReason reason) {
        requireRemote(reason);
        if (userId == null) {
            throw new IllegalArgumentException("userId and a remote close reason are required");
        }
        schedule(eventAt -> runSteps(
                        () -> closer.closeUser(tenantId, userId, reason),
                        () -> revocation.revokeUserTokensIssuedBefore(userId, eventAt),
                        b -> b.publishCloseUser(tenantId, userId, reason)),
                () -> closer.closeUser(tenantId, userId, reason));
    }

    @Override
    public void closeLoginSession(UUID tenantId, UUID userId, UUID loginSessionId, SessionCloseReason reason) {
        requireRemote(reason);
        if (userId == null || loginSessionId == null) {
            throw new IllegalArgumentException("userId, loginSessionId and a remote close reason are required");
        }
        schedule(eventAt -> runSteps(
                        () -> closer.closeLoginSession(tenantId, userId, loginSessionId, reason),
                        () -> revocation.revokeSessionTokensIssuedBefore(loginSessionId, eventAt),
                        b -> b.publishCloseSession(tenantId, userId, loginSessionId, reason)),
                () -> closer.closeLoginSession(tenantId, userId, loginSessionId, reason));
    }

    @Override
    public void closeOtherLoginSessions(UUID tenantId, UUID userId, UUID keepLoginSessionId,
                                        Collection<UUID> otherLoginSessionIds, SessionCloseReason reason) {
        requireRemote(reason);
        if (userId == null || keepLoginSessionId == null) {
            throw new IllegalArgumentException("userId, keepLoginSessionId and a remote close reason are required");
        }
        Collection<UUID> others = otherLoginSessionIds == null ? java.util.List.of() : java.util.List.copyOf(
                otherLoginSessionIds);
        schedule(eventAt -> runSteps(
                        () -> closer.closeUserExceptLoginSession(tenantId, userId, keepLoginSessionId, reason),
                        () -> others.forEach(other -> revocation.revokeSessionTokensIssuedBefore(other, eventAt)),
                        b -> b.publishCloseUserExceptSession(tenantId, userId, keepLoginSessionId, reason)),
                () -> closer.closeUserExceptLoginSession(tenantId, userId, keepLoginSessionId, reason));
    }

    @Override
    public void closeTenantSessions(UUID tenantId, SessionCloseReason reason) {
        requireRemote(reason);
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId and a remote close reason are required");
        }
        // no marker: the tenant status gate (TenantStatusGate) refuses CONNECT, login, refresh and HTTP for INACTIVE
        schedule(eventAt -> runSteps(
                        () -> closer.closeTenant(tenantId, reason),
                        null,
                        b -> b.publishCloseTenant(tenantId, reason)),
                () -> closer.closeTenant(tenantId, reason));
    }

    /**
     * local close -> marker (may be {@code null}) -> local close AGAIN -> publish; a failing step does not skip the
     * following ones. The second local pass (in-memory, no I/O) closes a session that a client which ignores the
     * refresh-then-reconnect contract managed to open with the SAME token between the first close and the marker write
     * (the second check after {@code register} cannot see the marker yet; tokens are blacklisted only on logout).
     */
    private void runSteps(Runnable local, Runnable marker, java.util.function.Consumer<WebSocketSessionControlBus> publish) {
        runSafely(local);
        if (marker != null) {
            runSafely(marker);
            runSafely(local);
        }
        runSafely(() -> {
            WebSocketSessionControlBus b = bus.getIfAvailable();
            if (b != null) {
                publish.accept(b);
            }
        });
    }

    private static void requireRemote(SessionCloseReason reason) {
        if (reason == null || !reason.isRemote()) {
            throw new IllegalArgumentException("a remote close reason is required");
        }
    }

    /**
     * Runs {@code full} (marker + local close + publish) on the executor after the commit; when the executor rejects
     * it, runs {@code localOnly} on the calling thread (in-memory, no I/O). The marker time is the moment of the
     * COMMIT (taken here, not when the task finally runs): tokens issued after the commit carry the new state and
     * must stay valid, tokens issued before it (also inside the transaction's window) carry the old one.
     */
    private void schedule(java.util.function.Consumer<java.time.Instant> full, Runnable localOnly) {
        Runnable submit = () -> {
            java.time.Instant eventAt = clock.instant();
            try {
                executor.execute(() -> runSafely(() -> full.accept(eventAt)));
            } catch (RejectedExecutionException e) {
                meters.counter(METRIC_ASYNC_REJECTED).increment();
                warnRateLimited("[WS-Session] Kolejka zamykania sesji WS pelna - zamykam tylko sesje lokalne; znacznik "
                        + "uniewaznienia i koperta dla innych instancji pominiete (licznik " + METRIC_ASYNC_REJECTED
                        + ")");
                runSafely(localOnly);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("[WS-Session] Zamkniecie sesji WebSocket nie powiodlo sie: {}", e.toString());
        }
    }

    private void warnRateLimited(String message) {
        long now = System.currentTimeMillis();
        long prev = lastWarn.get();
        if (now - prev >= WARN_INTERVAL_MILLIS && lastWarn.compareAndSet(prev, now)) {
            log.warn(message);
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor == null) {
            return;
        }
        ownedExecutor.shutdown(); // let queued closes/publications finish (rolling deploy), then force
        try {
            if (!ownedExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ownedExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ownedExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
