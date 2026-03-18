package com.contactcenter.api.websocket;

import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.security.StompPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * Kontroler STOMP obsługujący wiadomości przychodzące od klientów WebSocket.
 *
 * <p>Obsługiwane message destinations (prefix {@code /app} konfigurowany w {@code WebSocketConfig}):
 * <ul>
 *   <li>{@code /app/ping} – heartbeat, zwraca PONG do nadawcy</li>
 * </ul>
 *
 * <p>Obsługiwane subscribe destinations:
 * <ul>
 *   <li>{@code /topic/tenant/{tenantId}/supervisor} – walidacja uprawnień przy subskrypcji</li>
 * </ul>
 *
 * <p>Principal użytkownika (userId) jest dostępny przez parametr {@link Principal},
 * ustawiony przez {@link com.contactcenter.security.WebSocketAuthInterceptor}.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    // =========================================================================
    // Heartbeat
    // =========================================================================

    /**
     * Obsługuje żądania ping od klientów (heartbeat mechanizm).
     *
     * <p>Klient wysyła STOMP SEND na {@code /app/ping}.
     * Serwer odpowiada PONG na {@code /user/{userId}/events} (unicast do nadawcy).
     *
     * <p>Mechanizm ping/pong pozwala klientowi wykryć zerwanie połączenia
     * zanim timeout TCP to wykryje (ważne dla reconnect z backoffem).
     *
     * @param principal zalogowany użytkownik (ustawiony przez WebSocketAuthInterceptor)
     * @return event PONG wysyłany z powrotem do tego samego użytkownika
     */
    @MessageMapping("/ping")
    @SendToUser("/events")
    public WebSocketEvent handlePing(Principal principal) {
        if (principal instanceof StompPrincipal stompPrincipal) {
            log.trace("[WS-Controller] PING od userId={}", stompPrincipal.getName());
            return WebSocketEvent.pong(stompPrincipal.getTenantId());
        }

        log.warn("[WS-Controller] PING od nieznanego principal: {}", principal);
        return WebSocketEvent.pong(null);
    }

    // =========================================================================
    // Subscribe handlers – walidacja uprawnień
    // =========================================================================

    /**
     * Obsługuje subskrypcję topic supervisora tenanta.
     *
     * <p>Klient subskrybuje {@code /topic/tenant/{tenantId}/supervisor}.
     * Handler waliduje, czy użytkownik ma rolę SUPERVISOR lub ADMIN dla danego tenanta.
     *
     * <p><strong>Uwaga:</strong> {@code @SubscribeMapping} z prefixem {@code /app}
     * obsługuje wiadomości SUBSCRIBE na {@code /app/topic/tenant/{tenantId}/supervisor}.
     * Bezpośrednie subskrypcje na {@code /topic/...} są obsługiwane przez broker –
     * ten handler służy do logowania i walidacji przy subskrypcji przez relay.
     *
     * @param tenantId  UUID tenanta z destination
     * @param principal zalogowany użytkownik
     * @param headerAccessor dostęp do atrybutów sesji WebSocket
     */
    @SubscribeMapping("/topic/tenant/{tenantId}/supervisor")
    public void handleSupervisorSubscription(
            @DestinationVariable String tenantId,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        if (!(principal instanceof StompPrincipal stompPrincipal)) {
            log.warn("[WS-Controller] Subskrypcja supervisor topic bez StompPrincipal: {}",
                    principal);
            return;
        }

        String role = stompPrincipal.getRole();
        UUID principalTenantId = stompPrincipal.getTenantId();

        // Walidacja: tylko SUPERVISOR lub ADMIN może subskrybować tenant supervisor topic
        if (!"SUPERVISOR".equals(role) && !"ADMIN".equals(role)) {
            log.warn("[WS-Controller] Nieautoryzowana subskrypcja supervisor topic: " +
                            "userId={}, role={}, requestedTenantId={}",
                    stompPrincipal.getName(), role, tenantId);
            return;
        }

        // Walidacja: tenant w destination musi pasować do tenanta w tokenie JWT
        // (zapobiega nasłuchiwaniu na eventy innych tenantów)
        if (!principalTenantId.toString().equals(tenantId)) {
            log.warn("[WS-Controller] Cross-tenant subskrypcja supervisor topic: " +
                            "userId={}, jwtTenantId={}, requestedTenantId={}",
                    stompPrincipal.getName(), principalTenantId, tenantId);
            return;
        }

        log.info("[WS-Controller] Supervisor subskrypcja: userId={}, tenantId={}",
                stompPrincipal.getName(), tenantId);
    }
}
