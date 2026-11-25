package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebSocketSessionTerminatorImpl (BE-233): lokalnie zawsze, Redis best-effort, afterCommit")
class WebSocketSessionTerminatorImplTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private WebSocketSessionCloser closer;
    private WebSocketSessionControlBus bus;
    private ObjectProvider<WebSocketSessionControlBus> provider;
    private WebSocketSessionTerminatorImpl terminator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        closer = mock(WebSocketSessionCloser.class);
        bus = mock(WebSocketSessionControlBus.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bus);
        terminator = new WebSocketSessionTerminatorImpl(closer, provider);
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

        verify(closer).closeUser(TENANT, USER, SessionCloseReason.LOGOUT);
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

        verify(closer).closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);
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
        verify(closer).closeUser(TENANT, USER, SessionCloseReason.LOGOUT);

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
}
