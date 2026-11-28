package com.contactcenter.domain.websocket;

import java.util.Collection;
import java.util.UUID;

/**
 * Closes the WebSocket sessions of a user (or of a whole tenant) on ALL backend instances (BE-233) and makes the
 * tokens those sessions were opened with unusable for a new CONNECT / HTTP request (BE-249, "revoked since" markers).
 *
 * <p>Domain services call it when the right to keep an open session ends: logout, deactivation, role change,
 * password reset/change, tenant deactivation. The closed client receives a STOMP ERROR frame with a stable
 * {@code code} (so that the frontend refreshes the token and reconnects, or falls into SESSION_EXPIRED when the
 * refresh token is gone) and the transport is closed.
 *
 * <p>All methods are safe to call inside a transaction: when a transaction synchronisation is active the work
 * happens after the commit (a client that reconnects before the commit would otherwise still see the old state);
 * without a transaction it is scheduled immediately. The work runs on a bounded executor OUTSIDE the calling thread
 * (a slow Redis must not stall the request), in this order: revocation marker(s) -> local sessions -> control envelope
 * to the other instances. Nothing here throws: a failure (Redis down, executor saturated) is logged and counted, the
 * local sessions are closed regardless.
 */
public interface WebSocketSessionTerminator {

    /**
     * Closes every session of the user (all devices) and revokes the access tokens issued before now.
     *
     * @param tenantId tenant of the user ({@code null} only for the platform-level SUPER_ADMIN)
     * @param userId   user whose sessions are closed
     * @param reason   a {@linkplain SessionCloseReason#isRemote() remote} reason
     */
    void closeUserSessions(UUID tenantId, UUID userId, SessionCloseReason reason);

    /**
     * Closes the sessions of ONE login session (device) and revokes the access tokens of that login session issued
     * before now; the user's other devices are untouched (single-device logout).
     *
     * @param loginSessionId claim {@code sid} / refresh-token family of the device
     */
    void closeLoginSession(UUID tenantId, UUID userId, UUID loginSessionId, SessionCloseReason reason);

    /**
     * Closes the sessions of every device of the user EXCEPT {@code keepLoginSessionId} (self-service password
     * change) and revokes the tokens of {@code otherLoginSessionIds} (the other devices known from their still-active
     * refresh tokens).
     */
    void closeOtherLoginSessions(UUID tenantId, UUID userId, UUID keepLoginSessionId,
                                 Collection<UUID> otherLoginSessionIds, SessionCloseReason reason);

    /** Closes every session of the tenant (tenant deactivation). */
    void closeTenantSessions(UUID tenantId, SessionCloseReason reason);
}
