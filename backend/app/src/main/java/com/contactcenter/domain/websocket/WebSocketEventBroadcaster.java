package com.contactcenter.domain.websocket;

import java.util.UUID;

/**
 * Serwis odpowiedzialny za rozgłaszanie eventów WebSocket do klientów STOMP.
 *
 * <p>Dostarcza trzy tryby wysyłki:
 * <ul>
 *   <li><strong>Unicast do użytkownika</strong> – {@link #sendToUser} →
 *       destination {@code /user/{userId}/events} (tylko jeden agent/supervisor)</li>
 *   <li><strong>Broadcast do supervisorów tenanta</strong> – {@link #sendToTenantSupervisors} →
 *       destination {@code /topic/tenant/{tenantId}/supervisor}</li>
 *   <li><strong>Broadcast do agentów tenanta</strong> – {@link #sendToTenantAgents} →
 *       destination {@code /topic/tenant/{tenantId}/agents}</li>
 * </ul>
 *
 * <p>Wyjątki przy wysyłce są logowane, ale nie propagowane – awaria jednej wiadomości
 * nie powinna przerywać przetwarzania kolejnych eventów w listenerze RabbitMQ.
 */
public interface WebSocketEventBroadcaster {

    /**
     * Wysyła event do konkretnego użytkownika (unicast).
     *
     * <p>Spring STOMP tłumaczy to na destination {@code /user/{userId}/events}.
     * Klient musi subskrybować {@code /user/events} (bez userId – Spring automatycznie
     * dołącza identyfikator sesji użytkownika).
     *
     * @param userId UUID użytkownika (agenta lub supervisora)
     * @param event  event do wysłania
     */
    void sendToUser(UUID userId, WebSocketEvent event);

    /**
     * Wysyła event do wszystkich supervisorów tenanta (broadcast).
     *
     * <p>Destination: {@code /topic/tenant/{tenantId}/supervisor}.
     * Supervisorzy subskrybują ten topic po zalogowaniu.
     *
     * @param tenantId UUID tenanta
     * @param event    event do wysłania
     */
    void sendToTenantSupervisors(UUID tenantId, WebSocketEvent event);

    /**
     * Wysyła event do wszystkich agentów tenanta (broadcast).
     *
     * <p>Destination: {@code /topic/tenant/{tenantId}/agents}.
     * Agenci subskrybują ten topic po zalogowaniu (np. do globalnych powiadomień).
     *
     * @param tenantId UUID tenanta
     * @param event    event do wysłania
     */
    void sendToTenantAgents(UUID tenantId, WebSocketEvent event);
}
