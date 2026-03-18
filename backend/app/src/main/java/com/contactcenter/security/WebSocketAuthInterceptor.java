package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Interceptor kanału STOMP weryfikujący JWT przy nawiązaniu połączenia WebSocket.
 *
 * <p>Przechwytuje frame CONNECT i:
 * <ol>
 *   <li>Wyciąga token JWT z nagłówka {@code Authorization: Bearer <token>}
 *       lub query parametru {@code token}</li>
 *   <li>Waliduje token przez {@link JwtParser} (podpis RS256, exp, iss, claims)</li>
 *   <li>Ustawia {@link StompPrincipal} jako principal sesji WebSocket</li>
 *   <li>Zapisuje tenantId i role w atrybutach sesji (do użycia w handlerach)</li>
 * </ol>
 *
 * <p>Przy błędzie walidacji rzuca {@link MessagingException}, co skutkuje
 * odrzuceniem połączenia i wysłaniem STOMP ERROR frame do klienta.
 *
 * <p>Inne frame'y STOMP (SUBSCRIBE, SEND, DISCONNECT) są przepuszczane bez weryfikacji –
 * Principal jest już ustawiony w kontekście sesji.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SESSION_ATTR_TENANT_ID = "tenantId";
    private static final String SESSION_ATTR_ROLE = "role";

    private final JwtParser jwtParser;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);

        // Weryfikujemy JWT tylko przy CONNECT (pierwsze nawiązanie sesji STOMP)
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        log.debug("[WS-Auth] Nowe połączenie STOMP – weryfikacja JWT");

        String token = extractToken(accessor);
        if (!StringUtils.hasText(token)) {
            log.warn("[WS-Auth] Brak tokenu JWT w frame CONNECT – odrzucam połączenie");
            throw new MessagingException(
                    "Brak tokenu uwierzytelniającego. " +
                    "Wymagany nagłówek STOMP: Authorization: Bearer <token> " +
                    "lub query parametr: token=<token>"
            );
        }

        JwtClaims claims;
        try {
            claims = jwtParser.parse(token);
        } catch (JwtValidationException e) {
            log.warn("[WS-Auth] Nieprawidłowy token JWT – odrzucam połączenie: {}", e.getMessage());
            throw new MessagingException("Nieprawidłowy token JWT: " + e.getMessage(), e);
        }

        // Tworzymy mutable accessor, żeby móc ustawić Principal.
        // accessor.getAccessor() może zwrócić immutable headers gdy wiadomość jest "frozen",
        // dlatego tworzymy nowy mutable StompHeaderAccessor z nagłówkami oryginalnej wiadomości.
        StompHeaderAccessor mutableAccessor = StompHeaderAccessor.wrap(message);
        mutableAccessor.setLeaveMutable(true);

        // Ustaw StompPrincipal – Spring STOMP używa Principal.getName() do routowania
        // wiadomości na /user/{userId}/events
        StompPrincipal principal = new StompPrincipal(
                claims.userId(),
                claims.tenantId(),
                claims.role()
        );
        mutableAccessor.setUser(principal);

        // Zapisz atrybuty w sesji – dostępne w @MessageMapping przez SimpMessageHeaderAccessor
        if (mutableAccessor.getSessionAttributes() != null) {
            mutableAccessor.getSessionAttributes().put(SESSION_ATTR_TENANT_ID, claims.tenantId().toString());
            mutableAccessor.getSessionAttributes().put(SESSION_ATTR_ROLE, claims.role());
        }

        log.info("[WS-Auth] Autoryzacja WebSocket: userId={}, tenantId={}, role={}",
                claims.userId(), claims.tenantId(), claims.role());

        return MessageBuilder.createMessage(message.getPayload(), mutableAccessor.getMessageHeaders());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Próbuje wyciągnąć token JWT z nagłówka STOMP {@code Authorization}
     * lub natywnego nagłówka {@code token} (dla klientów bez wsparcia Authorization).
     *
     * <p>Kolejność priorytetów:
     * <ol>
     *   <li>Nagłówek STOMP {@code Authorization: Bearer <token>}</li>
     *   <li>Nagłówek STOMP {@code token: <token>} (dla SockJS gdzie nagłówki HTTP są ograniczone)</li>
     * </ol>
     */
    private String extractToken(StompHeaderAccessor accessor) {
        // 1. Nagłówek Authorization: Bearer <token>
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        // 2. Natywny nagłówek 'token' (fallback dla SockJS)
        String tokenHeader = accessor.getFirstNativeHeader("token");
        if (StringUtils.hasText(tokenHeader)) {
            return tokenHeader.trim();
        }

        return null;
    }
}
