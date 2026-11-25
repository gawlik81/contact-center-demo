package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.domain.websocket.WebSocketSessionTerminator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * {@link WebSocketSessionTerminator}: closes the local sessions first (works without Redis), then tells the other
 * instances through {@link WebSocketSessionControlBus} (absent when {@code websocket.session.control.enabled=false}).
 * Inside a transaction everything runs in {@code afterCommit}.
 */
@Slf4j
@Component
public class WebSocketSessionTerminatorImpl implements WebSocketSessionTerminator {

    private final WebSocketSessionCloser closer;
    private final ObjectProvider<WebSocketSessionControlBus> bus;

    public WebSocketSessionTerminatorImpl(WebSocketSessionCloser closer,
                                          ObjectProvider<WebSocketSessionControlBus> bus) {
        this.closer = closer;
        this.bus = bus;
    }

    @Override
    public void closeUserSessions(UUID tenantId, UUID userId, SessionCloseReason reason) {
        if (userId == null || reason == null || !reason.isRemote()) {
            throw new IllegalArgumentException("userId and a remote close reason are required");
        }
        afterCommit(() -> {
            closer.closeUser(tenantId, userId, reason);
            WebSocketSessionControlBus b = bus.getIfAvailable();
            if (b != null) {
                b.publishCloseUser(tenantId, userId, reason);
            }
        });
    }

    @Override
    public void closeTenantSessions(UUID tenantId, SessionCloseReason reason) {
        if (tenantId == null || reason == null || !reason.isRemote()) {
            throw new IllegalArgumentException("tenantId and a remote close reason are required");
        }
        afterCommit(() -> {
            closer.closeTenant(tenantId, reason);
            WebSocketSessionControlBus b = bus.getIfAvailable();
            if (b != null) {
                b.publishCloseTenant(tenantId, reason);
            }
        });
    }

    /** Runs the action after the surrounding transaction commits (dropped on rollback), or now without one. */
    private static void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("[WS-Session] Zamkniecie sesji WebSocket nie powiodlo sie: {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
