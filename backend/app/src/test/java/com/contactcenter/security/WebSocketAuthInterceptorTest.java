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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry sessionRegistry;

    @Mock
    private TokenRevocationService tokenRevocationService;

    @Mock
    private com.contactcenter.domain.tenant.TenantStatusGate tenantStatusGate;

    @org.mockito.Spy
    private io.micrometer.core.instrument.MeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

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

    private Message<?> buildOpeningMessage(StompCommand command, String authHeaderValue) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setSessionId("test-session-id");
        if (authHeaderValue != null) {
            accessor.addNativeHeader("Authorization", authHeaderValue);
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
    // BE-229 (CR M1, S-NPE): komenda STOMP oraz SUPER_ADMIN bez tenanta
    // =========================================================================

    @Nested
    @DisplayName("Komenda STOMP (alias CONNECT) i SUPER_ADMIN bez tenanta")
    class StompAliasAndNullTenantTest {

        @Test
        @DisplayName("Ramka STOMP bez tokenu -> MessagingException (nie omija uwierzytelnienia)")
        void stompCommandWithoutTokenRejected() {
            Message<?> message = buildOpeningMessage(StompCommand.STOMP, null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Brak tokenu");
        }

        @Test
        @DisplayName("Ramka STOMP z poprawnym tokenem ustawia StompPrincipal")
        void stompCommandWithValidTokenSetsPrincipal() {
            when(jwtParser.parse(anyString())).thenReturn(
                    new JwtClaims(TENANT_ID, USER_ID, ROLE, "u", Instant.now().plusSeconds(60)));
            Message<?> message = buildOpeningMessage(StompCommand.STOMP, "Bearer t");

            interceptor.preSend(message, channel);

            assertThat(StompHeaderAccessor.wrap(message).getUser()).isInstanceOf(StompPrincipal.class);
        }

        @Test
        @DisplayName("SUPER_ADMIN bez tenanta: brak NPE, principal z null tenantem, atrybut tenantId nieustawiony")
        void superAdminWithoutTenantConnects() {
            when(jwtParser.parse(anyString())).thenReturn(
                    new JwtClaims(null, USER_ID, "SUPER_ADMIN", "u", Instant.now().plusSeconds(60)));
            Message<?> message = buildOpeningMessage(StompCommand.CONNECT, "Bearer t");

            interceptor.preSend(message, channel);

            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            StompPrincipal principal = (StompPrincipal) accessor.getUser();
            assertThat(principal.getTenantId()).isNull();
            assertThat(principal.getRole()).isEqualTo("SUPER_ADMIN");
            assertThat(accessor.getSessionAttributes()).doesNotContainKey("tenantId").containsEntry("role", "SUPER_ADMIN");
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
                    .hasMessageContaining("Brak tokenu")
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_MISSING));
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
                    .thenThrow(new JwtValidationException("Token JWT wygasł",
                            new io.jsonwebtoken.ExpiredJwtException(null, null, "expired")));

            Message<?> message = buildConnectMessage("Bearer expired.jwt.token", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Nieprawidłowy token JWT")
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_EXPIRED));
        }

        @Test
        @DisplayName("Token z nieprawidłowym podpisem → MessagingException")
        void shouldRejectConnectWithInvalidSignature() {
            when(jwtParser.parse(anyString()))
                    .thenThrow(new JwtValidationException("Nieprawidłowy podpis tokenu JWT"));

            Message<?> message = buildConnectMessage("Bearer tampered.jwt.token", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOf(MessagingException.class)
                    .hasMessageContaining("Nieprawidłowy token JWT")
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));
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

    // =========================================================================
    // BE-233: blacklista JWT przy CONNECT/STOMP i rejestr sesji
    // =========================================================================

    @Nested
    @DisplayName("BE-233: blacklista i rejestr sesji")
    class BlacklistAndRegistryTest {

        private JwtClaims claims;

        @BeforeEach
        void setUp() {
            claims = new JwtClaims(TENANT_ID, USER_ID, ROLE, "user@test.com",
                    Instant.parse("2030-01-01T00:00:00Z"));
        }

        @Test
        @DisplayName("CONNECT z tokenem z blacklisty -> WS_TOKEN_INVALID, bez Principala i bez wpisu w rejestrze")
        void blacklistedTokenIsRejectedOnConnect() {
            when(jwtParser.parse("revoked.jwt")).thenReturn(claims);
            when(tokenBlacklistService.isBlacklisted("revoked.jwt")).thenReturn(true);
            Message<?> message = buildConnectMessage("Bearer revoked.jwt", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));
            assertThat(StompHeaderAccessor.wrap(message).getUser()).isNull();
            verifyNoInteractions(sessionRegistry);
        }

        @Test
        @DisplayName("Ramka STOMP (zamiast CONNECT) z tokenem z blacklisty tez jest odrzucana")
        void blacklistedTokenIsRejectedOnStompFrame() {
            when(jwtParser.parse("revoked.jwt")).thenReturn(claims);
            when(tokenBlacklistService.isBlacklisted("revoked.jwt")).thenReturn(true);
            Message<?> message = buildOpeningMessage(StompCommand.STOMP, "Bearer revoked.jwt");

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));
            verifyNoInteractions(sessionRegistry);
        }

        @Test
        @DisplayName("Blacklista niedostepna (Redis) -> fail-closed: WS_INTERNAL, komunikat bez szczegolow wyjatku")
        void blacklistFailureIsFailClosed() {
            when(jwtParser.parse("valid.jwt")).thenReturn(claims);
            when(tokenBlacklistService.isBlacklisted("valid.jwt"))
                    .thenThrow(new IllegalStateException("redis://secret-host:6379 down"));
            Message<?> message = buildConnectMessage("Bearer valid.jwt", null);

            assertThatThrownBy(() -> interceptor.preSend(message, channel))
                    .isInstanceOfSatisfying(StompRejectedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_INTERNAL);
                        assertThat(e.getMessage()).doesNotContain("secret-host");
                    });
            verifyNoInteractions(sessionRegistry);
        }

        @Test
        @DisplayName("Poprawny CONNECT rejestruje sesje (sessionId, user, tenant, exp tokenu)")
        void validConnectRegistersSession() {
            when(jwtParser.parse("valid.jwt")).thenReturn(claims);

            interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel);

            verify(sessionRegistry).register("test-session-id", USER_ID, TENANT_ID, null, claims.expiresAt());
        }

        @Test
        @DisplayName("Wyscig CONNECT<->logout: blacklista pojawia sie MIEDZY sprawdzeniem a register -> sesja wyrejestrowana, WS_TOKEN_INVALID")
        void blacklistAppearingAfterRegisterUnregistersSession() {
            when(jwtParser.parse("racy.jwt")).thenReturn(claims);
            when(tokenBlacklistService.isBlacklisted("racy.jwt")).thenReturn(false, true);
            when(sessionRegistry.register(anyString(), any(), any(), any(), any())).thenReturn(registeredEntry());

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer racy.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));

            verify(sessionRegistry).register("test-session-id", USER_ID, TENANT_ID, null, claims.expiresAt());
            verify(sessionRegistry).unregister("test-session-id");
        }

        @Test
        @DisplayName("Odrzucony CONNECT (zly token) nie rejestruje sesji")
        void rejectedConnectIsNotRegistered() {
            when(jwtParser.parse("bad.jwt")).thenThrow(new JwtValidationException("bad"));

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer bad.jwt", null), channel))
                    .isInstanceOf(StompRejectedException.class);
            verify(sessionRegistry, never()).register(anyString(), any(), any(), any(), any());
        }
    }

    private static com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.Entry registeredEntry() {
        return new com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.Entry(
                "test-session-id", USER_ID, TENANT_ID, null, null, null);
    }

    private double rejectedCount(WsErrorCode code) {
        var c = meters.find(WebSocketAuthInterceptor.METRIC_CONNECT_REJECTED).tag("code", code.name()).counter();
        return c == null ? 0 : c.count();
    }

    @Nested
    @DisplayName("BE-249: znacznik 'unieważnione od', bramka statusu tenanta, metryka odrzuconych CONNECT")
    class RevocationMarkerAndTenantGateTest {

        private final UUID sid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        private final Instant iat = Instant.parse("2026-11-26T10:00:00Z");
        private JwtClaims claims;

        @BeforeEach
        void setUp() {
            claims = new JwtClaims(TENANT_ID, USER_ID, ROLE, "user@test.com", Instant.parse("2030-01-01T00:00:00Z"),
                    iat, sid);
            org.mockito.Mockito.lenient().when(jwtParser.parse("valid.jwt")).thenReturn(claims);
        }

        @Test
        @DisplayName("Token wystawiony przed znacznikiem -> WS_TOKEN_INVALID (bez nowego kodu), brak rejestracji, licznik")
        void tokenIssuedBeforeMarkerIsRejected() {
            when(tokenRevocationService.isRevoked(USER_ID, sid, iat)).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));

            verifyNoInteractions(sessionRegistry);
            assertThat(rejectedCount(WsErrorCode.WS_TOKEN_INVALID)).isEqualTo(1);
        }

        @Test
        @DisplayName("Awaria Redis przy znaczniku -> fail-closed WS_INTERNAL, komunikat bez szczegolow, licznik")
        void markerCheckFailureIsFailClosed() {
            when(tokenRevocationService.isRevoked(any(), any(), any()))
                    .thenThrow(new IllegalStateException("redis://secret-host:6379 down"));

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_INTERNAL);
                        assertThat(e.getMessage()).doesNotContain("secret-host");
                    });

            verifyNoInteractions(sessionRegistry);
            assertThat(rejectedCount(WsErrorCode.WS_INTERNAL)).isEqualTo(1);
        }

        @Test
        @DisplayName("Tenant nieaktywny -> WS_TOKEN_INVALID z komunikatem bramki, bez rejestracji")
        void inactiveTenantIsRejected() {
            when(tenantStatusGate.isBlocked(TENANT_ID)).thenReturn(true);

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID);
                        assertThat(e.getMessage()).contains(WebSocketAuthInterceptor.TENANT_INACTIVE_MESSAGE);
                    });
            verifyNoInteractions(sessionRegistry);
        }

        @Test
        @DisplayName("Awaria bazy przy sprawdzaniu tenanta -> fail-closed WS_INTERNAL")
        void tenantGateFailureIsFailClosed() {
            when(tenantStatusGate.isBlocked(TENANT_ID)).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_INTERNAL));
        }

        @Test
        @DisplayName("SUPER_ADMIN (tenantId null) laczy sie; bramka dostaje null i nie blokuje")
        void superAdminWithoutTenantPassesTheGate() {
            JwtClaims superAdmin = new JwtClaims(null, USER_ID, "SUPER_ADMIN", "root@test.com",
                    Instant.parse("2030-01-01T00:00:00Z"), iat, null);
            when(jwtParser.parse("root.jwt")).thenReturn(superAdmin);

            interceptor.preSend(buildConnectMessage("Bearer root.jwt", null), channel);

            verify(tenantStatusGate).isBlocked(null);
            verify(sessionRegistry).register("test-session-id", USER_ID, null, null, superAdmin.expiresAt());
        }

        @Test
        @DisplayName("sid z tokenu trafia do rejestru (identyfikacja urzadzenia przy wylogowaniu)")
        void sidIsStoredInRegistry() {
            when(sessionRegistry.register(anyString(), any(), any(), any(), any())).thenReturn(registeredEntry());

            interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel);

            verify(sessionRegistry).register("test-session-id", USER_ID, TENANT_ID, sid, claims.expiresAt());
        }

        @Test
        @DisplayName("Znacznik pojawia sie MIEDZY sprawdzeniem a register (wyscig z wylogowaniem) -> wyrejestrowanie")
        void markerAppearingAfterRegisterUnregistersSession() {
            when(tokenRevocationService.isRevoked(USER_ID, sid, iat)).thenReturn(false, true);
            when(sessionRegistry.register(anyString(), any(), any(), any(), any())).thenReturn(registeredEntry());

            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer valid.jwt", null), channel))
                    .isInstanceOfSatisfying(StompRejectedException.class,
                            e -> assertThat(e.getCode()).isEqualTo(WsErrorCode.WS_TOKEN_INVALID));

            verify(sessionRegistry).unregister("test-session-id");
        }

        @Test
        @DisplayName("R5: register() == null (rozlaczenie wyprzedzilo CONNECT) nie psuje CONNECT i nie robi drugiego sprawdzenia")
        void nullRegisterResultDoesNotBreakConnect() {
            when(sessionRegistry.register(anyString(), any(), any(), any(), any())).thenReturn(null);

            Message<?> message = buildConnectMessage("Bearer valid.jwt", null);
            Message<?> result = interceptor.preSend(message, channel);

            assertThat(result).isSameAs(message);
            verify(tokenRevocationService, org.mockito.Mockito.times(1)).isRevoked(USER_ID, sid, iat);
            verify(sessionRegistry, never()).unregister(anyString());
        }

        @Test
        @DisplayName("R6: liczniki ws.connect.rejected{code} sa rejestrowane z wartoscia 0 od startu (alert dziala od pierwszego scrape)")
        void rejectionCountersExistFromStartup() {
            interceptor.registerRejectionCounters();

            for (WsErrorCode code : List.of(WsErrorCode.WS_TOKEN_MISSING, WsErrorCode.WS_TOKEN_EXPIRED,
                    WsErrorCode.WS_TOKEN_INVALID, WsErrorCode.WS_INTERNAL)) {
                assertThat(meters.find(WebSocketAuthInterceptor.METRIC_CONNECT_REJECTED).tag("code", code.name())
                        .counter()).as(code.name()).isNotNull();
                assertThat(rejectedCount(code)).isZero();
            }
        }

        @Test
        @DisplayName("R6: ws.connect.rejected{code} liczy kazdy kod osobno (MISSING, INVALID, EXPIRED)")
        void rejectedConnectsAreCountedPerCode() {
            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage(null, null), channel))
                    .isInstanceOf(StompRejectedException.class);
            when(jwtParser.parse("bad.jwt")).thenThrow(new JwtValidationException("bad"));
            assertThatThrownBy(() -> interceptor.preSend(buildConnectMessage("Bearer bad.jwt", null), channel))
                    .isInstanceOf(StompRejectedException.class);

            assertThat(rejectedCount(WsErrorCode.WS_TOKEN_MISSING)).isEqualTo(1);
            assertThat(rejectedCount(WsErrorCode.WS_TOKEN_INVALID)).isEqualTo(1);
            assertThat(rejectedCount(WsErrorCode.WS_INTERNAL)).isZero();
        }
    }
}
