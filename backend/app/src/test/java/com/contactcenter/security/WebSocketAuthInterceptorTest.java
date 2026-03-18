package com.contactcenter.security;

import com.contactcenter.security.JwtParser.JwtClaims;
import com.contactcenter.security.JwtParser.JwtValidationException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link WebSocketAuthInterceptor}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Przepuszczenie frame'ów innych niż CONNECT bez walidacji</li>
 *   <li>Brak tokenu → {@link MessagingException}</li>
 *   <li>Nieprawidłowy token → {@link MessagingException}</li>
 *   <li>Poprawny token → ustawienie {@link StompPrincipal} w sesji</li>
 *   <li>Token w nagłówku Authorization (Bearer)</li>
 *   <li>Token w natywnym nagłówku 'token' (SockJS fallback)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketAuthInterceptor – weryfikacja JWT przy STOMP CONNECT")
class WebSocketAuthInterceptorTest {

    private static final String ISSUER    = "contact-center-test";
    private static final UUID   TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID   USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String ROLE      = "AGENT";

    private static PrivateKey privateKey;

    @Mock
    private JwtParser jwtParser;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    @Mock
    private MessageChannel channel;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = keyPair.getPrivate();
    }

    // =========================================================================
    // Pomocnicze metody do budowania wiadomości STOMP
    // =========================================================================

    /**
     * Buduje wiadomość STOMP CONNECT z mutowalnym accessorem – dokładnie tak,
     * jak Spring dostarcza wiadomości przez kanał wejściowy (inbound channel).
     * Wymaga {@code setLeaveMutable(true)}, inaczej {@code MessageHeaders}
     * zamrozi nagłówki i interceptor rzuci "Already immutable" przy {@code setUser()}.
     */
    private Message<?> buildConnectMessage(String authHeaderValue, String tokenHeaderValue) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setSessionId("test-session-id");

        if (authHeaderValue != null) {
            accessor.addNativeHeader("Authorization", authHeaderValue);
        }
        if (tokenHeaderValue != null) {
            accessor.addNativeHeader("token", tokenHeaderValue);
        }

        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> buildNonConnectMessage(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("test-session-id");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private String buildValidToken() {
        return Jwts.builder()
                .subject("user@test.com")
                .issuer(ISSUER)
                .claim("tenant_id", TENANT_ID.toString())
                .claim("user_id", USER_ID.toString())
                .claim("role", ROLE)
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(privateKey)
                .compact();
    }

    // =========================================================================
    // Testy przepuszczania non-CONNECT frame'ów
    // =========================================================================

    @Nested
    @DisplayName("Non-CONNECT frames – przepuszczanie bez walidacji")
    class NonConnectFramesTest {

        @Test
        @DisplayName("Frame SEND – przepuszczony bez weryfikacji JWT")
        void shouldPassThroughSendFrame() {
            Message<?> message = buildNonConnectMessage(StompCommand.SEND);
            Message<?> result = interceptor.preSend(message, channel);
            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Frame SUBSCRIBE – przepuszczony bez weryfikacji JWT")
        void shouldPassThroughSubscribeFrame() {
            Message<?> message = buildNonConnectMessage(StompCommand.SUBSCRIBE);
            Message<?> result = interceptor.preSend(message, channel);
            assertThat(result).isSameAs(message);
        }

        @Test
        @DisplayName("Frame DISCONNECT – przepuszczony bez weryfikacji JWT")
        void shouldPassThroughDisconnectFrame() {
            Message<?> message = buildNonConnectMessage(StompCommand.DISCONNECT);
            Message<?> result = interceptor.preSend(message, channel);
            assertThat(result).isSameAs(message);
        }
    }

    // =========================================================================
    // Testy CONNECT bez tokenu
    // =========================================================================

    @Nested
    @DisplayName("CONNECT bez tokenu – odrzucenie połączenia")
    class MissingTokenTest {

        @Test
        @DisplayName("Brak nagłówka Authorization → MessagingException")
        void shouldRejectConnectWithoutAnyToken() {
            Message<?> message = buildConnectMessage(null, null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Brak tokenu");
        }

        @Test
        @DisplayName("Authorization bez prefixu Bearer → MessagingException")
        void shouldRejectConnectWithInvalidAuthHeaderFormat() {
            Message<?> message = buildConnectMessage("invalid-token-no-bearer", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Brak tokenu");
        }

        @Test
        @DisplayName("Pusty nagłówek Authorization → MessagingException")
        void shouldRejectConnectWithEmptyAuthHeader() {
            Message<?> message = buildConnectMessage("Bearer ", null);

            // "Bearer " po trim() da pusty string
            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Brak tokenu");
        }
    }

    // =========================================================================
    // Testy CONNECT z nieprawidłowym tokenem
    // =========================================================================

    @Nested
    @DisplayName("CONNECT z nieprawidłowym JWT → odrzucenie")
    class InvalidTokenTest {

        @Test
        @DisplayName("Wygasły token → MessagingException")
        void shouldRejectConnectWithExpiredToken() {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Token JWT wygasł"));

            Message<?> message = buildConnectMessage("Bearer expired.jwt.token", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Nieprawidłowy token JWT");
        }

        @Test
        @DisplayName("Token z nieprawidłowym podpisem → MessagingException")
        void shouldRejectConnectWithInvalidSignature() {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Nieprawidłowy podpis tokenu JWT"));

            Message<?> message = buildConnectMessage("Bearer tampered.jwt.token", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Nieprawidłowy token JWT");
        }
    }

    // =========================================================================
    // Testy CONNECT z poprawnym tokenem
    // =========================================================================

    @Nested
    @DisplayName("CONNECT z poprawnym JWT → ustawienie Principal")
    class ValidTokenTest {

        private JwtClaims validClaims;

        @BeforeEach
        void setUp() {
            validClaims = new JwtClaims(
                    TENANT_ID,
                    USER_ID,
                    ROLE,
                    "user@test.com",
                    Instant.now().plus(15, ChronoUnit.MINUTES)
            );
        }

        @Test
        @DisplayName("Token w nagłówku Authorization: Bearer → Principal ustawiony")
        void shouldSetPrincipalFromAuthorizationHeader() {
            when(jwtParser.parse("valid.jwt.token")).thenReturn(validClaims);
            Message<?> message = buildConnectMessage("Bearer valid.jwt.token", null);

            Message<?> result = interceptor.preSend(message, channel);

            assertThat(result).isNotNull();
            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertThat(resultAccessor.getUser()).isInstanceOf(StompPrincipal.class);

            StompPrincipal principal = (StompPrincipal) resultAccessor.getUser();
            assertThat(principal.getName()).isEqualTo(USER_ID.toString());
            assertThat(principal.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(principal.getRole()).isEqualTo(ROLE);
        }

        @Test
        @DisplayName("Token w nagłówku 'token' (SockJS fallback) → Principal ustawiony")
        void shouldSetPrincipalFromTokenHeader() {
            when(jwtParser.parse("valid.jwt.token")).thenReturn(validClaims);
            // Brak nagłówka Authorization, token w natywnym nagłówku 'token'
            Message<?> message = buildConnectMessage(null, "valid.jwt.token");

            Message<?> result = interceptor.preSend(message, channel);

            assertThat(result).isNotNull();
            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertThat(resultAccessor.getUser()).isInstanceOf(StompPrincipal.class);

            StompPrincipal principal = (StompPrincipal) resultAccessor.getUser();
            assertThat(principal.getName()).isEqualTo(USER_ID.toString());
        }

        @Test
        @DisplayName("Authorization ma priorytet nad nagłówkiem 'token'")
        void authorizationHeaderHasPriorityOverTokenHeader() {
            // Gdy oba nagłówki są obecne, interceptor parsuje TYLKO token z Authorization.
            // Nagłówek 'token' jest ignorowany – JwtParser wywoływany raz z auth.bearer.token.
            when(jwtParser.parse("auth.bearer.token")).thenReturn(validClaims);

            Message<?> message = buildConnectMessage("Bearer auth.bearer.token", "other.token");

            Message<?> result = interceptor.preSend(message, channel);

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            StompPrincipal principal = (StompPrincipal) resultAccessor.getUser();
            // Powinien użyć tokenu z Authorization (userId z validClaims)
            assertThat(principal.getName()).isEqualTo(USER_ID.toString());
        }

        @Test
        @DisplayName("Atrybuty sesji zawierają tenantId i role")
        void shouldStoreSessionAttributesFromToken() {
            when(jwtParser.parse("valid.jwt.token")).thenReturn(validClaims);
            Message<?> message = buildConnectMessage("Bearer valid.jwt.token", null);

            Message<?> result = interceptor.preSend(message, channel);

            StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
            assertThat(resultAccessor.getSessionAttributes())
                    .containsEntry("tenantId", TENANT_ID.toString())
                    .containsEntry("role", ROLE);
        }
    }
}
