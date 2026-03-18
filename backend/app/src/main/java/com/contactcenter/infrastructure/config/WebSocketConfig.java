package com.contactcenter.infrastructure.config;

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
 * Destinations:
 *   /topic/tenant/{tenantId}/supervisor  – broadcast do supervisorów tenanta
 *   /topic/tenant/{tenantId}/agents      – broadcast do agentów tenanta
 *   /user/{userId}/events                – unicast do konkretnego agenta/supervisora
 *
 * App destinations (klient → serwer):
 *   /app/ping                            – heartbeat (klient → serwer → /user/{userId}/events)
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
     *   <li>{@code /user} – point-to-point (unicast do konkretnego użytkownika)</li>
     * </ul>
     *
     * <p>Application destination prefix {@code /app} – wiadomości z tym prefixem
     * trafiają do {@code @MessageMapping} metod w kontrolerach.
     *
     * <p>User destination prefix {@code /user} – Spring STOMP automatycznie tłumaczy
     * {@code /user/queue/...} na {@code /user/{sessionId}/queue/...} dla danej sesji.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker dla /topic (broadcast) i /user (unicast)
        registry.enableSimpleBroker("/topic", "/user");

        // Prefix dla wiadomości kierowanych do @MessageMapping
        registry.setApplicationDestinationPrefixes("/app");

        // Prefix dla wiadomości unicast do konkretnego użytkownika
        // Spring STOMP resolve'uje /user/{userId}/events
        registry.setUserDestinationPrefix("/user");

        log.info("[WebSocket] Message broker skonfigurowany: /topic, /user | app prefix: /app");
    }

    // =========================================================================
    // STOMP endpoint
    // =========================================================================

    /**
     * Rejestruje endpoint WebSocket/SockJS.
     *
     * <p>Klienci łączą się pod adresem:
     * <ul>
     *   <li>Native WebSocket: {@code ws://localhost:8080/ws}</li>
     *   <li>SockJS: {@code http://localhost:8080/ws} (fallback HTTP long-polling / SSE)</li>
     * </ul>
     *
     * <p>Endpoint {@code /ws} jest skonfigurowany jako publiczny w SecurityConfig,
     * ponieważ autentykacja odbywa się w warstwie STOMP (frame CONNECT + JWT interceptor).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new))
                // SockJS fallback dla środowisk bez natywnych WebSocket
                .withSockJS();

        log.info("[WebSocket] STOMP endpoint zarejestrowany: /ws (SockJS enabled). Allowed origins: {}",
                allowedOrigins);
    }

    // =========================================================================
    // Channel interceptors
    // =========================================================================

    /**
     * Rejestruje {@link WebSocketAuthInterceptor} na kanale wejściowym.
     *
     * <p>Interceptor przechwytuje frame CONNECT i weryfikuje JWT,
     * ustawiając {@link com.contactcenter.security.StompPrincipal} dla sesji.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
