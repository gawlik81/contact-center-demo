package com.contactcenter.domain.websocket;

import java.util.UUID;

/**
 * Closes the WebSocket sessions of a user (or of a whole tenant) on ALL backend instances (BE-233).
 *
 * <p>Domain services call it when the right to keep an open session ends: logout, deactivation, role change,
 * forced password reset, tenant deactivation. The closed client receives a STOMP ERROR frame with a stable
 * {@code code} (so that the frontend refreshes the token and reconnects, or falls into SESSION_EXPIRED when the
 * refresh token is gone) and the transport is closed.
 *
 * <p>Both methods are safe to call inside a transaction: when a transaction synchronisation is active the work
 * happens in {@code afterCommit} (a client that reconnects before the commit would otherwise still see the old
 * state); without a transaction it happens immediately. They never throw: a failure to notify other instances
 * (Redis down) is logged and counted, the local sessions are closed regardless.
 */
public interface WebSocketSessionTerminator {

    /**
     * @param tenantId tenant of the user ({@code null} only for the platform-level SUPER_ADMIN)
     * @param userId   user whose sessions are closed
     * @param reason   a {@linkplain SessionCloseReason#isRemote() remote} reason
     */
    void closeUserSessions(UUID tenantId, UUID userId, SessionCloseReason reason);

    /** Closes every session of the tenant (tenant deactivation). */
    void closeTenantSessions(UUID tenantId, SessionCloseReason reason);
}
