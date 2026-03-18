package com.contactcenter.domain.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketEventBroadcaster {

    private static final String DESTINATION_USER_EVENTS    = "/events";
    private static final String DESTINATION_TENANT_SUPERVISOR = "/topic/tenant/%s/supervisor";
    private static final String DESTINATION_TENANT_AGENTS     = "/topic/tenant/%s/agents";

    private final SimpMessagingTemplate messagingTemplate;

    // =========================================================================
    // Publiczne API
    // =========================================================================

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
    public void sendToUser(UUID userId, WebSocketEvent event) {
        String userIdStr = userId.toString();
        try {
            messagingTemplate.convertAndSendToUser(userIdStr, DESTINATION_USER_EVENTS, event);
            log.debug("[WS-Broadcast] Unicast → userId={}, eventType={}", userIdStr, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd wysyłki unicast do userId={}, eventType={}: {}",
                    userIdStr, event.eventType(), e.getMessage(), e);
        }
    }

    /**
     * Wysyła event do wszystkich supervisorów tenanta (broadcast).
     *
     * <p>Destination: {@code /topic/tenant/{tenantId}/supervisor}.
     * Supervisorzy subskrybują ten topic po zalogowaniu.
     *
     * @param tenantId UUID tenanta
     * @param event    event do wysłania
     */
    public void sendToTenantSupervisors(UUID tenantId, WebSocketEvent event) {
        String destination = DESTINATION_TENANT_SUPERVISOR.formatted(tenantId);
        try {
            messagingTemplate.convertAndSend(destination, event);
            log.debug("[WS-Broadcast] Supervisor broadcast → tenantId={}, eventType={}",
                    tenantId, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd broadcast do supervisorów tenantId={}, eventType={}: {}",
                    tenantId, event.eventType(), e.getMessage(), e);
        }
    }

    /**
     * Wysyła event do wszystkich agentów tenanta (broadcast).
     *
     * <p>Destination: {@code /topic/tenant/{tenantId}/agents}.
     * Agenci subskrybują ten topic po zalogowaniu (np. do globalnych powiadomień).
     *
     * @param tenantId UUID tenanta
     * @param event    event do wysłania
     */
    public void sendToTenantAgents(UUID tenantId, WebSocketEvent event) {
        String destination = DESTINATION_TENANT_AGENTS.formatted(tenantId);
        try {
            messagingTemplate.convertAndSend(destination, event);
            log.debug("[WS-Broadcast] Agent broadcast → tenantId={}, eventType={}",
                    tenantId, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd broadcast do agentów tenantId={}, eventType={}: {}",
                    tenantId, event.eventType(), e.getMessage(), e);
        }
    }
}
