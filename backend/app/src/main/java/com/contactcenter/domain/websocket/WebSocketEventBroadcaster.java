package com.contactcenter.domain.websocket;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

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

    /**
     * Idempotent PERIODIC snapshot published by a job that runs on EVERY instance (no ShedLock): with the cross-instance
     * fan-out (BE-175) N instances would deliver N copies to every session. Cluster-wide, roughly one instance per
     * {@code window} (up to ~1.25x with unsynchronised pollers: the window is shorter than the poll interval) passes the gate for the given {@code key} and tenant and publishes; the others skip (and do not
     * even build the event). Redis outage = fail open (publish), because then the event is local-only anyway.
     * Never use for event-driven (non-idempotent) updates: a suppressed event would be lost.
     *
     * <p>The default implementation (no gate) always publishes.
     *
     * @return {@code true} if this call published
     */
    default boolean sendToTenantAgentsOncePerWindow(UUID tenantId, String key, Duration window,
                                                    Supplier<WebSocketEvent> event) {
        sendToTenantAgents(tenantId, event.get());
        return true;
    }
}
