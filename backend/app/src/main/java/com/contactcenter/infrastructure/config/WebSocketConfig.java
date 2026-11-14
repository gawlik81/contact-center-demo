package com.contactcenter.infrastructure.config;

import com.contactcenter.security.StompDestinationAuthorizationInterceptor;
import com.contactcenter.security.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Konfiguracja WebSocket + STOMP dla real-time eventów Agent Desktop.
 *
 * <p>Architektura messagingu WebSocket (BE-012):
 * <pre>
 * Destinations (SUBSCRIBE dozwolone wyłącznie wg allow-listy BE-229):
 *   /topic/tenant/{tenantId}/supervisor  – broadcast do supervisorów tenanta (SUPERVISOR/ADMIN; niesie też SUPERVISOR_METRICS)
 *   /topic/tenant/{tenantId}/agents      – broadcast do użytkowników tenanta (także AGENT)
 *   /topic/user/{userId}/events          – unicast do konkretnego użytkownika (tylko on sam)
 *
 * App destinations (klient → serwer):
 *   /app/ping                            – heartbeat/obecność (odpowiedź PONG @SendToUser trafia pod /user/..., której
 * FE nie subskrybuje – martwy kod, dług)
 * </pre>
 *
 * <p>Autentykacja: JWT weryfikowany przy frame CONNECT przez {@link WebSocketAuthInterceptor}.
 * Endpoint {@code /ws} jest publiczny w SecurityConfig – auth dzieje się w warstwie STOMP.
 *
 * <p>Fallback: SockJS obsługuje starsze przeglądarki i środowiska bez natywnych WebSocket.
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final StompDestinationAuthorizationInterceptor destinationAuthorizationInterceptor;

    /** Dozwolone origins WebSocket – analogicznie do CORS w SecurityConfig. */
    @Value("${websocket.allowed-origins:http://localhost:4200,http://localhost:3000}")
    private List<String> allowedOrigins;

    // =========================================================================
    // Broker configuration
    // =========================================================================

    /**
     * Konfiguracja in-memory message brokera.
     *
     * <p>Destinations:
     * <ul>
     *   <li>{@code /topic} – publish-subscribe (broadcast do grup użytkowników)</li>
     *   <li>{@code /user} – prefiks brokera wymagany przez {@code @SendToUser} (PONG); klient nie może go subskrybować (allow-lista BE-229)</li>
     * </ul>
     *
     * <p>Application destination prefix {@code /app} – wiadomości z tym prefixem
     * trafiają do {@code @MessageMapping} metod w kontrolerach.
     *
     * <p>User destination prefix {@code /user} – używany tylko przez {@code @SendToUser} (PONG);
     * unicast domenowy idzie przez {@code /topic/user/{userId}/events} (broadcaster).
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker dla /topic (broadcast) i /user (unicast)
        registry.enableSimpleBroker("/topic", "/user");

        // Prefix dla wiadomości kierowanych do @MessageMapping
        registry.setApplicationDestinationPrefixes("/app");

        // Prefix dla wiadomości unicast do konkretnego użytkownika
        // używane wyłącznie przez @SendToUser (PONG), którego FE nie odbiera
        registry.setUserDestinationPrefix("/user");

        log.info("[WebSocket] Message broker skonfigurowany: /topic, /user | app prefix: /app");
    }

    // =========================================================================
    // STOMP endpoint
    // =========================================================================

    /**
     * Rejestruje endpointy WebSocket/STOMP.
     *
     * <p>Endpoint {@code /ws} – z SockJS fallback (dla przyszłych klientów/starszych przeglądarek):
     * <ul>
     *   <li>SockJS: {@code http://localhost:8080/ws}</li>
     * </ul>
     *
     * <p>Endpoint {@code /ws-native} – plain WebSocket + STOMP (bez SockJS):
     * <ul>
     *   <li>Native WebSocket: {@code ws://localhost:8080/ws-native}</li>
     * </ul>
     * Angular Agent Desktop łączy się przez {@code /ws-native} natywnym {@code WebSocket} API,
     * ponieważ {@code sockjs-client} nie jest zależnością projektu frontend.
     *
     * <p>Oba endpointy używają tych samych interceptorów (WebSocketAuthInterceptor), brokera STOMP
     * i destinations ({@code /topic/user/{id}/events}, {@code /topic/tenant/{id}/supervisor}).
     * Oba są publiczne w SecurityConfig (autentykacja na poziomie STOMP CONNECT frame + JWT).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint z SockJS – zachowany dla kompatybilności
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new))
                .withSockJS();

        // Endpoint plain WebSocket + STOMP – używany przez Angular Agent Desktop
        registry.addEndpoint("/ws-native")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
        // Brak .withSockJS() – natywny protokół WebSocket (RFC 6455)

        log.info("[WebSocket] STOMP endpointy zarejestrowane: /ws (SockJS), /ws-native (plain WS). Allowed origins: {}",
                allowedOrigins);
    }

    // =========================================================================
    // Channel interceptors
    // =========================================================================

    /**
     * Rejestruje interceptory uwierzytelnienia i autoryzacji destynacji na kanale wejściowym.
     *
     * <p>{@link WebSocketAuthInterceptor} weryfikuje JWT przy CONNECT/STOMP i ustawia
     * {@link com.contactcenter.security.StompPrincipal}; {@link StompDestinationAuthorizationInterceptor}
     * (BE-229) autoryzuje SUBSCRIBE i SEND (allow-list) oraz stosuje default-deny komend. Obie reakcje działają
     * na różnych ramkach, więc kolejność nie jest warunkiem poprawności (auth jest pierwszy dla czytelności).
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Auth (CONNECT/STOMP) przed autoryzacją SUBSCRIBE/SEND (BE-229) – dla czytelności, nie z konieczności.
        registration.interceptors(webSocketAuthInterceptor, destinationAuthorizationInterceptor);
    }
}
