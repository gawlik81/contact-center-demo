package com.contactcenter.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Implementacja {@link Principal} dla sesji STOMP WebSocket.
 *
 * <p>Ustawiany przez {@link WebSocketAuthInterceptor} podczas handshake'u WebSocket
 * (frame CONNECT). Dzięki temu Spring STOMP może routować wiadomości do konkretnego
 * użytkownika przez {@code SimpMessagingTemplate.convertAndSendToUser(userId, ...)}.
 *
 * <p>Pole {@code name} (userId jako String) jest używane przez Spring STOMP jako klucz
 * do identyfikacji sesji użytkownika w {@code UserDestinationResolver}.
 */
public class StompPrincipal implements Principal {

    private final String userId;
    private final UUID tenantId;
    private final String role;

    public StompPrincipal(UUID userId, UUID tenantId, String role) {
        this.userId   = userId.toString();
        this.tenantId = tenantId;
        this.role     = role;
    }

    /**
     * Zwraca userId jako String – używany przez Spring STOMP do routowania wiadomości
     * na destination {@code /user/{userId}/events}.
     */
    @Override
    public String getName() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getRole() {
        return role;
    }

    /**
     * Zwraca userId jako UUID (dla wygody w kodzie domenowym).
     */
    public UUID getUserIdAsUuid() {
        return UUID.fromString(userId);
    }

    @Override
    public String toString() {
        return "StompPrincipal{userId=" + userId + ", tenantId=" + tenantId + ", role=" + role + "}";
    }
}
