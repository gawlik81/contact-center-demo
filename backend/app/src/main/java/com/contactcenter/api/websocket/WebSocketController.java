package com.contactcenter.api.websocket;

import com.contactcenter.domain.presence.AgentPresenceRegistry;
import com.contactcenter.domain.websocket.WebSocketEvent;
import com.contactcenter.security.StompPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Kontroler STOMP obsługujący wiadomości przychodzące od klientów WebSocket.
 *
 * <p>Obsługiwane message destinations (prefix {@code /app} konfigurowany w {@code WebSocketConfig}):
 * <ul>
 *   <li>{@code /app/ping} – heartbeat, zwraca PONG do nadawcy</li>
 * </ul>
 *
 * <p>Autoryzacja SUBSCRIBE/SEND (allow-list destynacji, tenant, rola) odbywa się w
 * {@link com.contactcenter.security.StompDestinationAuthorizationInterceptor} (BE-229) –
 * {@code @SubscribeMapping} nie chroni destynacji brokera, dlatego został usunięty.
 *
 * <p>Principal użytkownika (userId) jest dostępny przez parametr {@link Principal},
 * ustawiony przez {@link com.contactcenter.security.WebSocketAuthInterceptor}.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final AgentPresenceRegistry presenceRegistry;

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
     * <p>BE-210: ping jest także sygnałem obecności agenta (odświeża sesję w
     * {@link AgentPresenceRegistry}); frontend wysyła go po połączeniu i co 30 s.
     *
     * @param principal zalogowany użytkownik (ustawiony przez WebSocketAuthInterceptor)
     * @param headerAccessor nagłówki ramki (identyfikator sesji STOMP)
     * @return event PONG wysyłany z powrotem do tego samego użytkownika
     */
    @MessageMapping("/ping")
    @SendToUser("/events")
    public WebSocketEvent handlePing(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        if (principal instanceof StompPrincipal stompPrincipal) {
            presenceRegistry.touch(stompPrincipal, headerAccessor != null ? headerAccessor.getSessionId() : null);
            log.trace("[WS-Controller] PING od userId={}", stompPrincipal.getName());
            return WebSocketEvent.pong(stompPrincipal.getTenantId());
        }

        log.warn("[WS-Controller] PING od nieznanego principal: {}", principal);
        return WebSocketEvent.pong(null);
    }
}
