package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import io.jsonwebtoken.ExpiredJwtException;
import com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Interceptor kanału STOMP weryfikujący JWT przy nawiązaniu połączenia WebSocket.
 *
 * <p>Przechwytuje frame CONNECT (oraz równoważny STOMP) i:
 * <ol>
 *   <li>Wyciąga token JWT z nagłówka {@code Authorization: Bearer <token>}
 *       lub query parametru {@code token}</li>
 *   <li>Waliduje token przez {@link JwtParser} (podpis RS256, exp, iss, claims)</li>
 *   <li>Sprawdza blacklistę tokenów (logout/revoke, BE-233) - jak {@link JwtAuthFilter} dla HTTP; token z
 *       blacklisty jest odrzucany z {@link WsErrorCode#WS_TOKEN_INVALID} (FE: jedna próba refresh, potem
 *       SESSION_EXPIRED). Błąd Redis = odrzucenie ({@link WsErrorCode#WS_INTERNAL}, FE: reconnect z backoffem) -
 *       fail-closed</li>
 *   <li>Ustawia {@link StompPrincipal} jako principal sesji WebSocket</li>
 *   <li>Zapisuje tenantId i role w atrybutach sesji (do użycia w handlerach)</li>
 *   <li>Rejestruje sesję w {@link WebSocketSessionRegistry} (po userId; zamykanie sesji i termin życia, BE-233)</li>
 * </ol>
 *
 * <p>Przy błędzie walidacji rzuca {@link StompRejectedException} (podklasa {@link MessagingException}) z kodem
 * {@link WsErrorCode#WS_TOKEN_MISSING} / {@link WsErrorCode#WS_TOKEN_EXPIRED} / {@link WsErrorCode#WS_TOKEN_INVALID},
 * co skutkuje odrzuceniem połączenia i wysłaniem STOMP ERROR frame (z nagłówkiem {@code code}, BE-236) do klienta.
 *
 * <p>Inne frame'y STOMP są tu przepuszczane – Principal jest już ustawiony w kontekście sesji.
 * Autoryzację SUBSCRIBE i SEND (allow-list destynacji, izolacja tenantów/użytkowników, role)
 * realizuje następny w łańcuchu {@link StompDestinationAuthorizationInterceptor} (BE-229).
 * Ten interceptor sam NIE chroni destynacji brokera.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SESSION_ATTR_TENANT_ID = "tenantId";
    private static final String SESSION_ATTR_ROLE = "role";

    /** Stałe komunikaty odrzucenia (nagłówek {@code message}); {@code ExpectedStompRejectionLogFilter} wycisza po nich duplikat ERROR Springa. */
    public static final String BLACKLISTED_MESSAGE = "Token został unieważniony";
    public static final String UNVERIFIABLE_MESSAGE = "Nie można zweryfikować tokenu";

    private static final long LOG_INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    private final java.util.concurrent.atomic.AtomicLong lastBlacklistErrorLog =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

    private final JwtParser jwtParser;
    private final TokenBlacklistService tokenBlacklistService;
    private final WebSocketSessionRegistry sessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);

        // Weryfikujemy JWT przy otwarciu sesji STOMP: STOMP 1.2 dopuszcza zarówno CONNECT, jak i STOMP
        // (StompSubProtocolHandler traktuje obie komendy tak samo) – pominięcie STOMP otwierałoby sesję bez JWT.
        if (accessor == null || !(StompCommand.CONNECT.equals(accessor.getCommand())
                || StompCommand.STOMP.equals(accessor.getCommand()))) {
            return message;
        }

        log.debug("[WS-Auth] Nowe połączenie STOMP – weryfikacja JWT");

        String token = extractToken(accessor);
        if (!StringUtils.hasText(token)) {
            log.warn("[WS-Auth] Brak tokenu JWT w frame CONNECT – odrzucam połączenie");
            throw new StompRejectedException(WsErrorCode.WS_TOKEN_MISSING,
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
            // BE-236: wygasły token ma osobny kod (FE odświeża token i łączy ponownie), reszta = WS_TOKEN_INVALID
            WsErrorCode code = isExpired(e) ? WsErrorCode.WS_TOKEN_EXPIRED : WsErrorCode.WS_TOKEN_INVALID;
            throw new StompRejectedException(code, "Nieprawidłowy token JWT: " + e.getMessage(), e);
        }

        // BE-233: ten sam warunek co w JwtAuthFilter (HTTP); po wylogowaniu token nie otwiera nowej sesji WS.
        checkNotBlacklisted(token, claims);

        // Używamy ORYGINALNEGO, mutable accessora z wiadomości CONNECT.
        // Frames CONNECT przychodzące przez kanał wejściowy są zawsze mutable –
        // mutacja oryginalnego accessora (nie tworzenie nowej wiadomości) jest wymagana,
        // aby Spring DefaultSimpUserRegistry poprawnie zarejestrował użytkownika.
        // Tworzenie nowej wiadomości przez MessageBuilder.createMessage() zrywa powiązanie
        // accessora z oryginałem, co powoduje że SimpUserRegistry nie widzi Principal
        // i convertAndSendToUser() nie dostarcza wiadomości (brak sesji w rejestrze).
        StompPrincipal principal = new StompPrincipal(
                claims.userId(),
                claims.tenantId(),
                claims.role()
        );
        accessor.setUser(principal);

        // Zapisz atrybuty w sesji – dostępne w @MessageMapping przez SimpMessageHeaderAccessor
        if (accessor.getSessionAttributes() != null) {
            // SUPER_ADMIN (globalny administrator platformy) nie ma tenanta – atrybut pomijamy.
            if (claims.tenantId() != null) {
                accessor.getSessionAttributes().put(SESSION_ATTR_TENANT_ID, claims.tenantId().toString());
            }
            accessor.getSessionAttributes().put(SESSION_ATTR_ROLE, claims.role());
        }

        // BE-233: rejestr sesji po userId; termin życia = exp tokenu z CONNECT i/lub twardy limit.
        if (accessor.getSessionId() != null) {
            sessionRegistry.register(accessor.getSessionId(), claims.userId(), claims.tenantId(), claims.expiresAt());
            // Wyścig CONNECT <-> logout: logout (blacklista + commit + zamknięcie sesji użytkownika) mógł przebiec MIĘDZY
            // pierwszym sprawdzeniem a rejestracją i nie widzieć tej sesji. Drugie sprawdzenie PO rejestracji domyka
            // okno: albo zamknięcie zobaczy wpis, albo my zobaczymy blacklistę (koszt: drugi O(1) GET w Redis).
            try {
                checkNotBlacklisted(token, claims);
            } catch (StompRejectedException e) {
                sessionRegistry.unregister(accessor.getSessionId());
                throw e;
            }
        }

        log.info("[WS-Auth] Autoryzacja WebSocket: userId={}, tenantId={}, role={}",
                claims.userId(), claims.tenantId(), claims.role());

        return message;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Odrzuca token z blacklisty. Kod {@link WsErrorCode#WS_TOKEN_INVALID}: FE odświeża token raz (po logout/
     * dezaktywacji refresh się nie powiedzie -> SESSION_EXPIRED). Redis niedostępny = odrzucenie z
     * {@link WsErrorCode#WS_INTERNAL} (nie można stwierdzić, że token nie jest unieważniony); komunikat bez
     * szczegółów wyjątku.
     */
    private void checkNotBlacklisted(String token, JwtClaims claims) {
        boolean blacklisted;
        try {
            blacklisted = tokenBlacklistService.isBlacklisted(token);
        } catch (RuntimeException e) {
            // przy awarii Redis każdy agent próbuje reconnectu w pętli: log ograniczony do 1 / 10 s
            long now = System.nanoTime();
            long prev = lastBlacklistErrorLog.get();
            if (now - prev >= LOG_INTERVAL_NANOS && lastBlacklistErrorLog.compareAndSet(prev, now)) {
                log.error("[WS-Auth] Nie można sprawdzić blacklisty JWT (Redis) - odrzucam połączenia "
                        + "(log co 10 s): {}", e.toString());
            }
            throw new StompRejectedException(WsErrorCode.WS_INTERNAL, UNVERIFIABLE_MESSAGE);
        }
        if (blacklisted) {
            log.debug("[WS-Auth] Token na blackliście - odrzucam połączenie: userId={}", claims.userId());
            throw new StompRejectedException(WsErrorCode.WS_TOKEN_INVALID, BLACKLISTED_MESSAGE);
        }
    }

    /** {@code true} gdy w łańcuchu przyczyn jest {@link ExpiredJwtException} (token wygasł). */
    private static boolean isExpired(Throwable e) {
        return CauseChains.of(e).stream().anyMatch(ExpiredJwtException.class::isInstance);
    }

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
