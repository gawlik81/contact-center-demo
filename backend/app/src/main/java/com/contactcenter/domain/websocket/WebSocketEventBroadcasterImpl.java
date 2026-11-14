package com.contactcenter.domain.websocket;

import com.contactcenter.infrastructure.websocket.WebSocketFanoutPublisher;
import com.contactcenter.infrastructure.websocket.WebSocketOncePerWindowGate;
import com.contactcenter.security.StompDestinations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Publishes domain WebSocket events. Since BE-175 (ADR-14 option B) an event reaches the sessions connected to ANY
 * backend instance: {@link WebSocketFanoutPublisher} delivers it to the local {@code SimpleBroker} and to the other
 * instances over Redis pub/sub. Destination contracts (and thus the frontend) are unchanged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WebSocketEventBroadcasterImpl implements WebSocketEventBroadcaster {

    private final WebSocketFanoutPublisher fanout;
    private final WebSocketOncePerWindowGate onceGate;

    // =========================================================================
    // Publiczne API
    // =========================================================================

    @Override
    public void sendToUser(UUID userId, WebSocketEvent event) {
        String userIdStr = userId.toString();
        String destination = StompDestinations.USER_EVENTS_FORMAT.formatted(userIdStr);
        try {
            fanout.publish(destination, event.tenantId(), event);
            log.debug("[WS-Broadcast] Unicast → userId={}, eventType={}", userIdStr, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd wysyłki unicast do userId={}, eventType={}: {}",
                    userIdStr, event.eventType(), e.getMessage(), e);
        }
    }

    @Override
    public void sendToTenantSupervisors(UUID tenantId, WebSocketEvent event) {
        String destination = StompDestinations.TENANT_SUPERVISOR_FORMAT.formatted(tenantId);
        try {
            fanout.publish(destination, tenantId, event);
            log.debug("[WS-Broadcast] Supervisor broadcast → tenantId={}, eventType={}",
                    tenantId, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd broadcast do supervisorów tenantId={}, eventType={}: {}",
                    tenantId, event.eventType(), e.getMessage(), e);
        }
    }

    @Override
    public void sendToTenantAgents(UUID tenantId, WebSocketEvent event) {
        String destination = StompDestinations.TENANT_AGENTS_FORMAT.formatted(tenantId);
        try {
            fanout.publish(destination, tenantId, event);
            log.debug("[WS-Broadcast] Agent broadcast → tenantId={}, eventType={}",
                    tenantId, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd broadcast do agentów tenantId={}, eventType={}: {}",
                    tenantId, event.eventType(), e.getMessage(), e);
        }
    }

    @Override
    public boolean sendToTenantAgentsOncePerWindow(UUID tenantId, String key, Duration window,
                                                   Supplier<WebSocketEvent> event) {
        try {
            if (!onceGate.tryAcquire(key, tenantId, window)) {
                return false;
            }
        } catch (Exception e) {
            log.warn("[WS-Broadcast] Bramka okna nieudana (key={}): {} - publikuję", key, e.toString());
        }
        sendToTenantAgents(tenantId, event.get());
        return true;
    }
}
