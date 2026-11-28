package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.security.TokenRevocationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebSocketSessionTerminatorImpl (BE-233/BE-249): lokalnie zawsze, Redis best-effort, afterCommit, async, znaczniki")
class WebSocketSessionTerminatorImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private WebSocketSessionCloser closer;
    private WebSocketSessionControlBus bus;
    private ObjectProvider<WebSocketSessionControlBus> provider;
    private TokenRevocationService revocation;
    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private Executor executor;
    private WebSocketSessionTerminatorImpl terminator;
    private static final Instant T0 = Instant.parse("2026-11-26T10:00:00Z");
    private final List<Runnable> queued = new java.util.ArrayList<>();
    private boolean direct = true;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        closer = mock(WebSocketSessionCloser.class);
        bus = mock(WebSocketSessionControlBus.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bus);
        revocation = mock(TokenRevocationService.class);
        meters = new SimpleMeterRegistry();
        clock = new MutableClock(T0);
        // direct = the task runs on the calling thread; otherwise it waits in `queued` (a test runs it by hand)
        executor = task -> {
            if (direct) {
                task.run();
            } else {
                queued.add(task);
            }
        };
        terminator = new WebSocketSessionTerminatorImpl(closer, provider, revocation, meters, executor, clock);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("Bez transakcji: lokalne zamkniecie i publikacja natychmiast")
    void withoutTransactionRunsImmediately() {
        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT);

        verify(closer, org.mockito.Mockito.times(2)).closeUser(TENANT, USER, SessionCloseReason.LOGOUT);
        verify(bus).publishCloseUser(TENANT, USER, SessionCloseReason.LOGOUT);
    }

    @Test
    @DisplayName("W transakcji: nic przed commitem, po commicie lokalnie + Redis; po rollbacku nic")
    void insideTransactionWaitsForCommit() {
        TransactionSynchronizationManager.initSynchronization();

        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
        verify(closer, never()).closeUser(any(), any(), any());
        verify(bus, never()).publishCloseUser(any(), any(), any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(closer, org.mockito.Mockito.times(2)).closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
        verify(bus).publishCloseUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
    }

    @Test
    @DisplayName("Rollback: afterCommit nie jest wolane, wiec sesje zostaja")
    void rollbackKeepsSessions() {
        TransactionSynchronizationManager.initSynchronization();

        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.DEACTIVATED);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(closer, never()).closeUser(any(), any(), any());
        verify(bus, never()).publishCloseUser(any(), any(), any());
    }

    @Test
    @DisplayName("Redis niedostepny (publish zwraca false lub brak busa): lokalne zamkniecie dziala")
    void localCloseWorksWithoutRedis() {
        when(bus.publishCloseUser(any(), any(), any())).thenReturn(false);
        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT);
        verify(closer, org.mockito.Mockito.times(2)).closeUser(TENANT, USER, SessionCloseReason.LOGOUT);

        when(provider.getIfAvailable()).thenReturn(null); // websocket.session.control.enabled=false
        terminator.closeTenantSessions(TENANT, SessionCloseReason.TENANT_DEACTIVATED);
        verify(closer).closeTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED);
    }

    @Test
    @DisplayName("Wyjatek w zamykaniu nie propaguje sie do wywolujacego (np. do commitu)")
    void exceptionsAreContained() {
        doThrow(new IllegalStateException("x")).when(closer).closeUser(any(), any(), any());

        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT);
    }

    @Test
    @DisplayName("Niepoprawne argumenty: brak usera, powod lokalny")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> terminator.closeUserSessions(TENANT, null, SessionCloseReason.LOGOUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> terminator.closeUserSessions(TENANT, USER, SessionCloseReason.MAX_LIFETIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> terminator.closeTenantSessions(null, SessionCloseReason.TENANT_DEACTIVATED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("closeUserSessions: lokalne zamkniecie -> znacznik per user (czas zdarzenia) -> koperta, w tej kolejnosci")
    void userCloseClosesLocallyThenWritesMarkerThenPublishes() {
        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.DEACTIVATED);

        var order = inOrder(closer, revocation, bus);
        order.verify(closer).closeUser(TENANT, USER, SessionCloseReason.DEACTIVATED);
        order.verify(revocation).revokeUserTokensIssuedBefore(USER, T0);
        order.verify(closer).closeUser(TENANT, USER, SessionCloseReason.DEACTIVATED); // W2: second local pass
        order.verify(bus).publishCloseUser(TENANT, USER, SessionCloseReason.DEACTIVATED);
    }

    @Test
    @DisplayName("closeLoginSession: znacznik per sid (NIE per user), zamkniecie i koperta CLOSE_SESSION")
    void loginSessionCloseUsesSessionMarkerOnly() {
        UUID sid = UUID.randomUUID();

        terminator.closeLoginSession(TENANT, USER, sid, SessionCloseReason.LOGOUT);

        verify(revocation).revokeSessionTokensIssuedBefore(sid, T0);
        verify(revocation, never()).revokeUserTokensIssuedBefore(any(), any());
        verify(closer, org.mockito.Mockito.times(2)).closeLoginSession(TENANT, USER, sid, SessionCloseReason.LOGOUT);
        verify(bus).publishCloseSession(TENANT, USER, sid, SessionCloseReason.LOGOUT);
    }

    @Test
    @DisplayName("closeOtherLoginSessions: znaczniki dla pozostalych sid, biezacy sid nietkniety")
    void otherSessionsCloseMarksOnlyTheOthers() {
        UUID keep = UUID.randomUUID();
        UUID other1 = UUID.randomUUID();
        UUID other2 = UUID.randomUUID();

        terminator.closeOtherLoginSessions(TENANT, USER, keep, List.of(other1, other2),
                SessionCloseReason.PASSWORD_CHANGED);

        verify(revocation).revokeSessionTokensIssuedBefore(other1, T0);
        verify(revocation).revokeSessionTokensIssuedBefore(other2, T0);
        verify(revocation, never()).revokeSessionTokensIssuedBefore(keep, T0);
        verify(revocation, never()).revokeUserTokensIssuedBefore(any(), any());
        verify(closer, org.mockito.Mockito.times(2)).closeUserExceptLoginSession(TENANT, USER, keep, SessionCloseReason.PASSWORD_CHANGED);
        verify(bus).publishCloseUserExceptSession(TENANT, USER, keep, SessionCloseReason.PASSWORD_CHANGED);
    }

    @Test
    @DisplayName("closeTenantSessions: bez znacznika (status tenanta blokuje bramka)")
    void tenantCloseWritesNoMarker() {
        terminator.closeTenantSessions(TENANT, SessionCloseReason.TENANT_DEACTIVATED);

        verify(revocation, never()).revokeUserTokensIssuedBefore(any(), any());
        verify(revocation, never()).revokeSessionTokensIssuedBefore(any(), any());
        verify(bus).publishCloseTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED);
    }

    @Test
    @DisplayName("BE-249 W4: praca idzie na executor, nie na watek wywolujacy; znacznik ma czas COMMITU, nie wykonania")
    void workRunsOffTheCallingThreadWithCommitTime() {
        direct = false;
        TransactionSynchronizationManager.initSynchronization();
        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
        clock.advance(java.time.Duration.ofSeconds(3));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(queued).hasSize(1);
        verify(closer, never()).closeUser(any(), any(), any());
        verify(bus, never()).publishCloseUser(any(), any(), any());
        verify(revocation, never()).revokeUserTokensIssuedBefore(any(), any());

        clock.advance(java.time.Duration.ofSeconds(30)); // the queue was slow
        queued.get(0).run();

        verify(revocation).revokeUserTokensIssuedBefore(USER, T0.plusSeconds(3));
        verify(closer, org.mockito.Mockito.times(2)).closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
        verify(bus).publishCloseUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
    }

    @Test
    @DisplayName("BE-249: awaria Redis (znacznik) nie psuje zamkniecia lokalnego ani wywolujacego")
    void redisFailureDoesNotBreakLocalClose() {
        doThrow(new IllegalStateException("redis down")).when(revocation).revokeUserTokensIssuedBefore(any(), any());

        terminator.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT); // must not throw

        // a failing marker neither skips the (earlier) local close nor the (later) publication
        verify(closer, org.mockito.Mockito.times(2)).closeUser(TENANT, USER, SessionCloseReason.LOGOUT);
        verify(bus).publishCloseUser(TENANT, USER, SessionCloseReason.LOGOUT);
    }

    @Test
    @DisplayName("BE-249: nasycony executor -> zamkniecie TYLKO lokalne na watku wywolujacym, licznik, bez wyjatku")
    void saturatedExecutorFallsBackToLocalClose() {
        WebSocketSessionTerminatorImpl saturated = new WebSocketSessionTerminatorImpl(closer, provider, revocation,
                meters, task -> {
                    throw new RejectedExecutionException("full");
                }, clock);

        saturated.closeUserSessions(TENANT, USER, SessionCloseReason.DEACTIVATED);

        verify(closer).closeUser(TENANT, USER, SessionCloseReason.DEACTIVATED);
        verify(bus, never()).publishCloseUser(any(), any(), any());
        verify(revocation, never()).revokeUserTokensIssuedBefore(any(), any());
        assertThat(meters.get(WebSocketSessionTerminatorImpl.METRIC_ASYNC_REJECTED).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-249: prawdziwy executor (konstruktor produkcyjny) wykonuje prace na osobnym watku")
    void realExecutorRunsOnAnotherThread() throws Exception {
        WebSocketSessionProperties props = new WebSocketSessionProperties();
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        when(closer.closeUser(any(), any(), any())).thenAnswer(i -> {
            worker.set(Thread.currentThread());
            done.countDown();
            return 1;
        });
        WebSocketSessionTerminatorImpl real = new WebSocketSessionTerminatorImpl(closer, provider, revocation, meters,
                props);
        try {
            real.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT);
            assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotSameAs(Thread.currentThread());
            assertThat(worker.get().getName()).startsWith("ws-session-control-");
        } finally {
            real.shutdown();
        }
    }

    @Test
    @DisplayName("W2: CONNECT tym samym tokenem MIEDZY pierwszym zamknieciem a zapisem znacznika konczy sie zamknieta sesja (drugi przebieg)")
    void sessionOpenedBetweenCloseAndMarkerIsClosedBySecondPass() {
        WebSocketSessionProperties props = new WebSocketSessionProperties();
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(props, m);
        java.util.List<String> closedSessions = new java.util.ArrayList<>();
        WebSocketSessionCloser realCloser = new WebSocketSessionCloser(registry, (msg, timeout) -> {
            closedSessions.add(org.springframework.messaging.simp.SimpMessageHeaderAccessor.getSessionId(msg.getHeaders()));
            return true;
        }, m, props);
        registry.register("old", USER, TENANT, null);
        TokenRevocationService racing = mock(TokenRevocationService.class);
        // the client reconnects with the same token while the marker is being written (no marker visible yet)
        org.mockito.Mockito.doAnswer(i -> {
            registry.register("raced", USER, TENANT, null);
            return true;
        }).when(racing).revokeUserTokensIssuedBefore(any(), any());
        WebSocketSessionTerminatorImpl t = new WebSocketSessionTerminatorImpl(realCloser, provider, racing, m,
                Runnable::run, clock);

        t.closeUserSessions(TENANT, USER, SessionCloseReason.DEACTIVATED);

        assertThat(closedSessions).containsExactly("old", "raced");
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("T4/A2: watek executora nie niesie TenantContext wolajacego (InheritableThreadLocal czyszczony przy starcie watku)")
    void workerThreadDoesNotCarryTenantContext() throws Exception {
        com.contactcenter.security.TenantContext.setTenantId(TENANT);
        java.util.concurrent.atomic.AtomicReference<UUID> seen = new java.util.concurrent.atomic.AtomicReference<>(UUID.randomUUID());
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        when(closer.closeUser(any(), any(), any())).thenAnswer(i -> {
            seen.set(com.contactcenter.security.TenantContext.getTenantIdOrNull());
            done.countDown();
            return 0;
        });
        WebSocketSessionTerminatorImpl real = new WebSocketSessionTerminatorImpl(closer, provider, revocation, meters,
                new WebSocketSessionProperties());
        try {
            real.closeUserSessions(TENANT, USER, SessionCloseReason.LOGOUT); // creates the worker thread FROM this thread
            assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isNull();
        } finally {
            com.contactcenter.security.TenantContext.clear();
            real.shutdown();
        }
    }
}
