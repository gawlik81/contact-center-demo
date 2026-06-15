package com.contactcenter.domain.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class WebSocketEventBroadcasterImpl implements WebSocketEventBroadcaster {

    private static final String DESTINATION_USER_EVENTS       = "/topic/user/%s/events";
    private static final String DESTINATION_TENANT_SUPERVISOR = "/topic/tenant/%s/supervisor";
    private static final String DESTINATION_TENANT_AGENTS     = "/topic/tenant/%s/agents";

    private final SimpMessagingTemplate messagingTemplate;

    // =========================================================================
    // Publiczne API
    // =========================================================================

    @Override
    public void sendToUser(UUID userId, WebSocketEvent event) {
        String userIdStr = userId.toString();
        String destination = DESTINATION_USER_EVENTS.formatted(userIdStr);
        try {
            messagingTemplate.convertAndSend(destination, event);
            log.debug("[WS-Broadcast] Unicast → userId={}, eventType={}", userIdStr, event.eventType());
        } catch (Exception e) {
            log.error("[WS-Broadcast] Błąd wysyłki unicast do userId={}, eventType={}: {}",
                    userIdStr, event.eventType(), e.getMessage(), e);
        }
    }

    @Override
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

    @Override
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
