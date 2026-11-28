package com.contactcenter.domain.websocket;

/**
 * Why server-side closes WebSocket (STOMP) sessions of a user (BE-233). Used as a low-cardinality metric tag and in
 * the cross-instance control envelope; it is NEVER sent to the client (the ERROR frame is generic).
 */
public enum SessionCloseReason {

    /** Explicit logout (this device or all devices). */
    LOGOUT(true),
    /** The account was deactivated or deleted. */
    DEACTIVATED(true),
    /** The role of the user changed - the new role must be present in a re-issued token. */
    ROLE_CHANGED(true),
    /** An administrator forced a password reset (all refresh tokens revoked). */
    PASSWORD_RESET(true),
    /** The user changed the password himself: the OTHER devices are signed out (BE-249), the current one stays. */
    PASSWORD_CHANGED(true),
    /** The whole tenant was deactivated. */
    TENANT_DEACTIVATED(true),
    /** Local only: the access token presented at CONNECT has expired. */
    TOKEN_EXPIRED(false),
    /** Local only: the hard session lifetime limit was reached. */
    MAX_LIFETIME(false);

    private final boolean remote;

    SessionCloseReason(boolean remote) {
        this.remote = remote;
    }

    /** {@code true} when the reason may travel on the Redis control channel (event-driven closes). */
    public boolean isRemote() {
        return remote;
    }
}
