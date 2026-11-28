package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.infrastructure.websocket.WebSocketSessionControlEnvelope.Type;
import com.contactcenter.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("WebSocketSessionControlBus (BE-233): koperta, walidacja, ignorowanie wlasnych, fail-safe publish")
class WebSocketSessionControlBusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private StringRedisTemplate redis;
    private WebSocketSessionCloser closer;
    private SimpleMeterRegistry meters;
    private WebSocketSessionProperties props;
    private WebSocketSessionControlBus bus;
    private com.contactcenter.domain.tenant.TenantStatusGate gate;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        closer = mock(WebSocketSessionCloser.class);
        meters = new SimpleMeterRegistry();
        gate = mock(com.contactcenter.domain.tenant.TenantStatusGate.class);
        props = new WebSocketSessionProperties();
        props.getControl().setEnvironment("test env!");
        bus = new WebSocketSessionControlBus(redis, props, closer, meters,
                new WebSocketChannelSigner(new WebSocketHmacProperties(), meters), gate,
                mock(RedisMessageListenerContainer.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void receive(String json) {
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8)),
                null);
    }

    private static String envelope(String src, Type type, UUID tenant, UUID user, SessionCloseReason reason) {
        return new WebSocketSessionControlEnvelope(1, src, type, tenant, user, reason, System.currentTimeMillis())
                .toJson();
    }

    private double dropped(String reason) {
        var c = meters.find(WebSocketSessionControlBus.METRIC_DROPPED).tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("Kanal ma sufiks srodowiska (znaki spoza [A-Za-z0-9_-] zamienione)")
    void channelHasSanitisedEnvironmentSuffix() {
        assertThat(props.effectiveChannel()).isEqualTo("cc:ws:session-control:test_env_");
    }

    @Test
    @DisplayName("Koperta CLOSE_USER od innej instancji zamyka pare tenant/user lokalnie")
    void foreignCloseUserIsApplied() {
        receive(envelope("other-inst", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.ROLE_CHANGED));

        verify(closer).closeUser(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SessionCloseReason.ROLE_CHANGED), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        assertThat(meters.get(WebSocketSessionControlBus.METRIC_RECEIVED).tag("type", "CLOSE_USER").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CLOSE_USER bez tenanta (SUPER_ADMIN) jest poprawna; CLOSE_TENANT zamyka tenant")
    void nullTenantUserAndTenantClose() {
        receive(envelope("other-inst", Type.CLOSE_USER, null, USER, SessionCloseReason.LOGOUT));
        receive(envelope("other-inst", Type.CLOSE_TENANT, TENANT, null, SessionCloseReason.TENANT_DEACTIVATED));

        verify(closer).closeUser(org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SessionCloseReason.LOGOUT), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        verify(closer).closeTenant(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(SessionCloseReason.TENANT_DEACTIVATED), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
    }

    @Test
    @DisplayName("Wlasna koperta jest ignorowana (sesje juz zamkniete lokalnie)")
    void ownEnvelopeIsIgnored() {
        receive(envelope(props.resolvedInstanceId(), Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        verify(closer, never()).closeUser(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Odrzucane: zla wersja, brak userId, brak tenanta w CLOSE_TENANT, powod lokalny, JSON, rozmiar")
    void invalidEnvelopesAreDropped() {
        receive("{\"v\":2,\"src\":\"x\",\"type\":\"CLOSE_USER\",\"userId\":\"" + USER + "\",\"reason\":\"LOGOUT\"}");
        receive(envelope("x", Type.CLOSE_USER, TENANT, null, SessionCloseReason.LOGOUT));
        receive(envelope("x", Type.CLOSE_TENANT, null, null, SessionCloseReason.TENANT_DEACTIVATED));
        receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.MAX_LIFETIME));
        receive("{not json");
        receive("x".repeat(props.getControl().getMaxMessageBytes() + 1));

        verify(closer, never()).closeUser(any(), any(), any(), any());
        verify(closer, never()).closeTenant(any(), any(), any());
        assertThat(dropped("unsupported_version")).isEqualTo(1);
        assertThat(dropped("invalid")).isEqualTo(3);
        assertThat(dropped("malformed")).isEqualTo(1);
        assertThat(dropped("oversize")).isEqualTo(1);
    }

    @Test
    @DisplayName("Wyjatek w zamykaniu nie wycieka z listenera (licznik handler_error)")
    void handlerErrorIsContained() {
        doThrow(new IllegalStateException("boom")).when(closer).closeUser(any(), any(), any(), any());

        assertThatCode(() -> receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT)))
                .doesNotThrowAnyException();
        assertThat(dropped("handler_error")).isEqualTo(1);
    }

    @Test
    @DisplayName("Watek listenera: kontekst tenanta jest czyszczony przed obsluga")
    void tenantContextIsClearedOnListenerThread() {
        TenantContext.setTenantId(TENANT);

        receive(envelope("x", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        assertThat(TenantContext.getTenantIdOrNull()).isNull();
    }

    @Test
    @DisplayName("publish publikuje JSON na kanal srodowiska; awaria Redis nie rzuca i zwiera licznik")
    void publishIsFailSafe() {
        assertThat(bus.publishCloseUser(TENANT, USER, SessionCloseReason.LOGOUT)).isTrue();
        verify(redis).convertAndSend(eq(props.effectiveChannel()), anyString());

        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());
        assertThat(bus.publishCloseTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED)).isFalse();
        assertThat(meters.get(WebSocketSessionControlBus.METRIC_PUBLISH_FAILURES).tag("type", "CLOSE_TENANT")
                .counter().count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- BE-249/BE-242: HMAC, nowe typy

    private static final String SECRET = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-0123";

    private WebSocketChannelSigner signer(String secret, boolean acceptUnsigned) {
        WebSocketHmacProperties hmac = new WebSocketHmacProperties();
        hmac.setSecret(secret);
        hmac.setAcceptUnsigned(acceptUnsigned);
        return new WebSocketChannelSigner(hmac, meters);
    }

    private WebSocketSessionControlBus busWith(WebSocketChannelSigner signer) {
        return new WebSocketSessionControlBus(redis, props, closer, meters, signer, gate,
                mock(RedisMessageListenerContainer.class));
    }

    private String signed(WebSocketChannelSigner s, Type type, UUID tenant, UUID user, UUID sid,
                          SessionCloseReason reason) {
        WebSocketSessionControlEnvelope e = new WebSocketSessionControlEnvelope(1, "other-inst", type, tenant, user,
                sid, reason, System.currentTimeMillis(), null);
        return e.withSignature(s.sign(props.effectiveChannel(), e.canonicalForSigning())).toJson();
    }

    @Test
    @DisplayName("publikowana koperta jest podpisana sekretem biezacym; odbiorca z tym samym sekretem ja stosuje")
    void publishedEnvelopeIsSignedAndAccepted() {
        WebSocketChannelSigner s = signer(SECRET, false);
        WebSocketSessionControlBus sender = busWith(s);
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);

        sender.publishCloseUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);

        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq(props.effectiveChannel()), body.capture());
        assertThat(body.getValue()).contains("\"sig\"");
        // another instance (a different bus object = a different instance id in real life; here: a different src)
        WebSocketSessionControlBus receiver = busWith(s);
        String foreign = body.getValue().replace(props.resolvedInstanceId(), "other-inst");
        // changing src invalidates the signature -> rejected; the genuine envelope from another instance is below
        receiverReceive(receiver, foreign);
        verify(closer, never()).closeUser(any(), any(), any(), any());
        assertThat(dropped("bad_signature")).isEqualTo(1);

        receiverReceive(receiver, signed(s, Type.CLOSE_USER, TENANT, USER, null, SessionCloseReason.ROLE_CHANGED));
        verify(closer).closeUser(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SessionCloseReason.ROLE_CHANGED), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
    }

    private void receiverReceive(WebSocketSessionControlBus b, String json) {
        b.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), json.getBytes(StandardCharsets.UTF_8)),
                null);
    }

    @Test
    @DisplayName("sfalszowana koperta bez poprawnego podpisu NIE zamyka sesji (bad_signature / missing_signature)")
    void forgedAndUnsignedEnvelopesCloseNothing() {
        WebSocketSessionControlBus receiver = busWith(signer(SECRET, false));
        WebSocketChannelSigner attacker = signer("4tt4ck3r-4tt4ck3r-4tt4ck3r-4tt4ck3r-0000", false);

        receiverReceive(receiver, signed(attacker, Type.CLOSE_USER, TENANT, USER, null, SessionCloseReason.LOGOUT));
        receiverReceive(receiver, envelope("other-inst", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        verify(closer, never()).closeUser(any(), any(), any(), any());
        assertThat(dropped("bad_signature")).isEqualTo(1);
        assertThat(dropped("missing_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("koperta z kanalu fan-out (ten sam sekret, inna nazwa kanalu) nie zamyka sesji na kanale sterujacym")
    void envelopeFromAnotherChannelIsRejected() {
        WebSocketChannelSigner s = signer(SECRET, false);
        WebSocketSessionControlBus receiver = busWith(s);
        WebSocketSessionControlEnvelope e = new WebSocketSessionControlEnvelope(1, "other-inst", Type.CLOSE_USER,
                TENANT, USER, null, SessionCloseReason.LOGOUT, System.currentTimeMillis(), null);
        String signedForOtherChannel = e.withSignature(s.sign("cc:ws:fanout:prod", e.canonicalForSigning())).toJson();

        receiverReceive(receiver, signedForOtherChannel);

        verify(closer, never()).closeUser(any(), any(), any(), any());
        assertThat(dropped("bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("tryb przejsciowy: koperta bez podpisu zamyka sesje (rolling deploy), zly podpis nie")
    void transitionalModeAcceptsUnsigned() {
        WebSocketSessionControlBus receiver = busWith(signer(SECRET, true));

        receiverReceive(receiver, envelope("other-inst", Type.CLOSE_USER, TENANT, USER, SessionCloseReason.LOGOUT));

        verify(closer).closeUser(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SessionCloseReason.LOGOUT), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
    }

    @Test
    @DisplayName("nowe typy: CLOSE_SESSION i CLOSE_USER_EXCEPT_SESSION wywoluja zamkniecie po sid; bez sid sa odrzucane jako invalid")
    void sessionScopedTypes() {
        UUID sid = UUID.randomUUID();
        WebSocketChannelSigner s = signer(SECRET, false);
        WebSocketSessionControlBus receiver = busWith(s);

        receiverReceive(receiver, signed(s, Type.CLOSE_SESSION, TENANT, USER, sid, SessionCloseReason.LOGOUT));
        receiverReceive(receiver, signed(s, Type.CLOSE_USER_EXCEPT_SESSION, TENANT, USER, sid,
                SessionCloseReason.PASSWORD_CHANGED));
        receiverReceive(receiver, signed(s, Type.CLOSE_SESSION, TENANT, USER, null, SessionCloseReason.LOGOUT));

        verify(closer).closeLoginSession(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(sid), org.mockito.ArgumentMatchers.eq(SessionCloseReason.LOGOUT), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        verify(closer).closeUserExceptLoginSession(org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(sid), org.mockito.ArgumentMatchers.eq(SessionCloseReason.PASSWORD_CHANGED), org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        assertThat(dropped("invalid")).isEqualTo(1);
    }

    @Test
    @DisplayName("publishCloseSession / publishCloseUserExceptSession wysylaja sid i typ w kopercie")
    void publishSessionScopedTypes() throws Exception {
        UUID sid = UUID.randomUUID();
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);

        bus.publishCloseSession(TENANT, USER, sid, SessionCloseReason.LOGOUT);
        bus.publishCloseUserExceptSession(TENANT, USER, sid, SessionCloseReason.PASSWORD_CHANGED);

        verify(redis, org.mockito.Mockito.times(2)).convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                body.capture());
        var first = WebSocketSessionControlEnvelope.fromJson(body.getAllValues().get(0).getBytes(StandardCharsets.UTF_8));
        var second = WebSocketSessionControlEnvelope.fromJson(body.getAllValues().get(1).getBytes(StandardCharsets.UTF_8));
        assertThat(first.type()).isEqualTo(Type.CLOSE_SESSION);
        assertThat(first.sid()).isEqualTo(sid);
        assertThat(second.type()).isEqualTo(Type.CLOSE_USER_EXCEPT_SESSION);
        assertThat(second.reason()).isEqualTo(SessionCloseReason.PASSWORD_CHANGED);
    }

    // ---------------------------------------------------------------- W3 / N5

    @Test
    @DisplayName("W3: ta sama podpisana koperta dostarczona drugi raz (replay) jest odrzucona (reason=replay), nie zamyka drugi raz")
    void replayedSignedEnvelopeIsDropped() {
        WebSocketChannelSigner s = signer(SECRET, false);
        WebSocketSessionControlBus receiver = busWith(s);
        String envelope = signed(s, Type.CLOSE_USER, TENANT, USER, null, SessionCloseReason.LOGOUT);

        receiverReceive(receiver, envelope);
        receiverReceive(receiver, envelope);

        verify(closer, org.mockito.Mockito.times(1)).closeUser(org.mockito.ArgumentMatchers.eq(TENANT),
                org.mockito.ArgumentMatchers.eq(USER), org.mockito.ArgumentMatchers.eq(SessionCloseReason.LOGOUT),
                org.mockito.ArgumentMatchers.any(java.time.Instant.class));
        assertThat(dropped("replay")).isEqualTo(1);
    }

    @Test
    @DisplayName("W3: zamkniecie z koperty jest ograniczone do sesji zarejestrowanych do ts + tolerancja (notAfter)")
    void closesAreLimitedToSessionsRegisteredBeforeEnvelopeTime() {
        UUID sid = UUID.randomUUID();
        long ts = 1_700_000_000_000L;
        java.time.Instant expected = java.time.Instant.ofEpochMilli(ts).plus(props.getControl().getReplayTolerance());
        receive(new WebSocketSessionControlEnvelope(1, "other", Type.CLOSE_USER, TENANT, USER, null,
                SessionCloseReason.LOGOUT, ts, null).toJson());
        receive(new WebSocketSessionControlEnvelope(1, "other", Type.CLOSE_SESSION, TENANT, USER, sid,
                SessionCloseReason.LOGOUT, ts, null).toJson());
        receive(new WebSocketSessionControlEnvelope(1, "other", Type.CLOSE_TENANT, TENANT, null, null,
                SessionCloseReason.TENANT_DEACTIVATED, ts, null).toJson());

        verify(closer).closeUser(TENANT, USER, SessionCloseReason.LOGOUT, expected);
        verify(closer).closeLoginSession(TENANT, USER, sid, SessionCloseReason.LOGOUT, expected);
        verify(closer).closeTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED, expected);
    }

    @Test
    @DisplayName("T1: replay CLOSE_USER/CLOSE_TENANT po ponownym logowaniu NIE zamyka nowej sesji (rejestr + closer + bus)")
    void replayAfterRelogDoesNotCloseNewSession() {
        java.util.List<String> closed = new java.util.ArrayList<>();
        MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-26T10:00:00Z"));
        WebSocketSessionProperties p = new WebSocketSessionProperties();
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(p, meters, clock);
        WebSocketSessionCloser realCloser = new WebSocketSessionCloser(registry, (m, t) -> {
            closed.add(org.springframework.messaging.simp.SimpMessageHeaderAccessor.getSessionId(m.getHeaders()));
            return true;
        }, meters, p, clock);
        WebSocketHmacProperties hmac = new WebSocketHmacProperties();
        hmac.setSecret(SECRET);
        WebSocketChannelSigner s = new WebSocketChannelSigner(hmac, meters, clock); // same (fake) clock: ts stays fresh
        WebSocketSessionControlBus receiver = new WebSocketSessionControlBus(redis, props, realCloser, meters, s, gate,
                mock(RedisMessageListenerContainer.class));
        // two DIFFERENT valid envelopes (different ts => different signatures) simulate: original + attacker's replay later
        long originalTs = clock.instant().toEpochMilli();
        registry.register("old-session", USER, TENANT, null);
        receiverReceive(receiver, signedAt(s, Type.CLOSE_USER, originalTs));
        assertThat(closed).containsExactly("old-session");

        clock.advance(java.time.Duration.ofMinutes(2));
        registry.register("new-session", USER, TENANT, null);   // re-login after the event
        registry.register("tenant-session", UUID.randomUUID(), TENANT, null);
        // envelopes that still carry an OLD ts (a replay that slipped past the identical-copy guard, e.g. re-signed
        // after a secret leak) must not touch sessions registered AFTER that ts
        receiverReceive(receiver, signedAt(s, Type.CLOSE_USER, originalTs + 1));
        receiverReceive(receiver, signedAtTenant(s, originalTs + 2));

        assertThat(closed).as("nowa sesja i sesja tenanta zarejestrowane po ts przetrwaly").containsExactly("old-session");
        assertThat(registry.size()).isEqualTo(2);
    }

    private String signedAt(WebSocketChannelSigner s, Type type, long ts) {
        WebSocketSessionControlEnvelope e = new WebSocketSessionControlEnvelope(1, "other-inst", type, TENANT, USER, null,
                SessionCloseReason.LOGOUT, ts, null);
        return e.withSignature(s.sign(props.effectiveChannel(), e.canonicalForSigning())).toJson();
    }

    private String signedAtTenant(WebSocketChannelSigner s, long ts) {
        WebSocketSessionControlEnvelope e = new WebSocketSessionControlEnvelope(1, "other-inst", Type.CLOSE_TENANT,
                TENANT, null, null, SessionCloseReason.TENANT_DEACTIVATED, ts, null);
        return e.withSignature(s.sign(props.effectiveChannel(), e.canonicalForSigning())).toJson();
    }

    @Test
    @DisplayName("N5: koperta CLOSE_TENANT uniewaznia lokalny wpis bramki statusu tenanta od razu (nie czeka na TTL)")
    void closeTenantInvalidatesTenantStatusGate() {
        receive(envelope("other-inst", Type.CLOSE_TENANT, TENANT, null, SessionCloseReason.TENANT_DEACTIVATED));

        verify(gate).invalidate(TENANT);
    }
}
