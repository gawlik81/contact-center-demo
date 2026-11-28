package com.contactcenter.infrastructure.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.contactcenter.security.StompPrincipal;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WebSocketFanoutSubscriber - walidacja kopert z kanału Redis (BE-175)")
class WebSocketFanoutSubscriberTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");
    private static final String JSON = "{\"eventType\":\"X\"}";

    private WebSocketLocalDispatcher dispatcher;
    private MeterRegistry registry;
    private WebSocketFanoutProperties properties;
    private WebSocketFanoutSubscriber subscriber;
    private SimpUserRegistry userRegistry;

    private void localSession(UUID user, UUID tenant) {
        SimpUser simpUser = mock(SimpUser.class);
        when(simpUser.getPrincipal()).thenReturn(new StompPrincipal(user, tenant, "AGENT"));
        when(userRegistry.getUser(user.toString())).thenReturn(simpUser);
    }

    @BeforeEach
    void setUp() {
        dispatcher = mock(WebSocketLocalDispatcher.class);
        registry = new SimpleMeterRegistry();
        properties = new WebSocketFanoutProperties();
        properties.setInstanceId("self");
        userRegistry = mock(SimpUserRegistry.class);
        subscriber = new WebSocketFanoutSubscriber(dispatcher, new WebSocketFanoutMetrics(registry), properties,
                userRegistry, new WebSocketChannelSigner(new WebSocketHmacProperties(), registry),
                mock(RedisMessageListenerContainer.class));
    }

    private void receive(String body) {
        subscriber.onMessage(new DefaultMessage(properties.effectiveChannel().getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }

    private static String envelope(int v, String src, String dest, UUID tenant, String payload) {
        return new WebSocketFanoutEnvelope(v, src, dest, tenant, System.currentTimeMillis(), payload).toJson();
    }

    private double dropped(String reason) {
        var c = registry.find(WebSocketFanoutMetrics.DROPPED).tags("direction", "receive", "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("rejestruje listenera na kanale z konfiguracji")
    void registersListenerOnConfiguredChannel() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        new WebSocketFanoutSubscriber(dispatcher, new WebSocketFanoutMetrics(registry), properties, userRegistry,
                new WebSocketChannelSigner(new WebSocketHmacProperties(), registry), container);
        verify(container).addMessageListener(any(org.springframework.data.redis.connection.MessageListener.class),
                eq(new org.springframework.data.redis.listener.ChannelTopic("cc:ws:fanout:default")));
    }

    @Test
    @DisplayName("koperta z innej instancji dla kazdego z trzech tematow trafia do lokalnego brokera bajt w bajt")
    void deliversValidEnvelopesOfAllThreeTopics() {
        localSession(USER, TENANT);
        String user = "/topic/user/" + USER + "/events";
        String agents = "/topic/tenant/" + TENANT + "/agents";
        String supervisor = "/topic/tenant/" + TENANT + "/supervisor";

        receive(envelope(1, "other", user, TENANT, JSON));
        receive(envelope(1, "other", agents, TENANT, JSON));
        receive(envelope(1, "other", supervisor, TENANT, JSON));

        verify(dispatcher).deliver(user, JSON.getBytes(StandardCharsets.UTF_8));
        verify(dispatcher).deliver(agents, JSON.getBytes(StandardCharsets.UTF_8));
        verify(dispatcher).deliver(supervisor, JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(registry.find(WebSocketFanoutMetrics.RECEIVED).counters()).hasSize(3);
        assertThat(registry.find(WebSocketFanoutMetrics.DELIVERED).tag("origin", "remote").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.find(WebSocketFanoutMetrics.LAG).timer().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("wlasna koperta (ten sam instanceId) jest ignorowana - brak dubla lokalnie")
    void ignoresOwnEnvelope() {
        receive(envelope(1, properties.resolvedInstanceId(), "/topic/tenant/" + TENANT + "/agents", TENANT, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(registry.find(WebSocketFanoutMetrics.RECEIVED).counters()).isEmpty();
        assertThat(registry.find("ws.fanout.ignored").tag("reason", "self").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("destynacje spoza allow-listy sa odrzucane (wildcard, /user, /app, obcy prefiks, wielkie litery)")
    void rejectsDestinationsOutsideAllowList() {
        for (String dest : new String[] {"/topic/**", "/topic/tenant/" + TENANT + "/**", "/user/queue/x",
                "/app/ping", "/queue/x", "/topic/tenant/" + TENANT + "/agents/extra",
                "/topic/tenant/" + "ABCDEF01-0000-0000-0000-000000000000" + "/agents", "/topic/user/*/events", ""}) {
            receive(envelope(1, "other", dest, TENANT, JSON));
        }
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("invalid_destination")).isEqualTo(9.0);
    }

    @Test
    @DisplayName("tenant w kopercie rozny od tenanta destynacji - odrzucone (izolacja tenantow)")
    void rejectsTenantMismatch() {
        receive(envelope(1, "other", "/topic/tenant/" + TENANT + "/agents", OTHER_TENANT, JSON));
        receive(envelope(1, "other", "/topic/tenant/" + TENANT + "/supervisor", null, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("tenant_mismatch")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("zly JSON, brak payloadu, nieznana wersja i zbyt duza koperta sa liczone i nie psuja listenera")
    void rejectsMalformedUnsupportedAndOversize() {
        String agents = "/topic/tenant/" + TENANT + "/agents";
        receive("not json\r\nFORGED");
        receive("");
        receive(envelope(2, "other", agents, TENANT, JSON));
        receive(envelope(1, "other", agents, TENANT, null));
        properties.setMaxMessageBytes(100);
        receive(envelope(1, "other", agents, TENANT, "x".repeat(200)));
        properties.setMaxMessageBytes(262_144);
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("malformed")).isEqualTo(2.0);
        assertThat(dropped("unsupported_version")).isEqualTo(1.0);
        assertThat(dropped("invalid_destination")).isEqualTo(1.0); // null payload
        assertThat(dropped("oversize")).isEqualTo(1.0);

        receive(envelope(1, "other", agents, TENANT, JSON)); // the listener survived
        verify(dispatcher).deliver(agents, JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("blad lokalnej dostawy jest liczony jako drop i nie propaguje sie")
    void deliveryErrorIsCounted() {
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down")).when(dispatcher).deliver(anyString(), any());
        receive(envelope(1, "other", "/topic/tenant/" + TENANT + "/agents", TENANT, JSON));
        assertThat(dropped("delivery_error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nieznane pola koperty sa ignorowane (zgodnosc w przod przy rolling deploy)")
    void ignoresUnknownEnvelopeFields() {
        String agents = "/topic/tenant/" + TENANT + "/agents";
        String body = envelope(1, "other", agents, TENANT, JSON).replaceFirst("\\{", "{\"future\":42,");
        receive(body);
        verify(dispatcher).deliver(agents, JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("unicast: brak lokalnej sesji uzytkownika - ignored no_local_session (nie dropped), broker nietkniety")
    void userTopicWithoutLocalSessionIsDropped() {
        receive(envelope(1, "other", "/topic/user/" + USER + "/events", TENANT, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(registry.find("ws.fanout.ignored").tag("reason", "no_local_session").counter().count()).isEqualTo(1.0);
        assertThat(dropped("no_local_session")).isZero();
    }

    @Test
    @DisplayName("unicast: tenant koperty rozny od tenanta sesji - tenant_mismatch; brak tenanta - tenant_missing")
    void userTopicTenantIsCheckedAgainstSessionPrincipal() {
        localSession(USER, TENANT);
        String dest = "/topic/user/" + USER + "/events";
        receive(envelope(1, "other", dest, OTHER_TENANT, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("tenant_mismatch")).isEqualTo(1.0);

        receive(envelope(1, "other", dest, null, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("tenant_missing")).isEqualTo(1.0);

        receive(envelope(1, "other", dest, TENANT, JSON));
        verify(dispatcher).deliver(dest, JSON.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("unicast: sesja bez StompPrincipal/tenanta - drop principal_unknown")
    void userTopicWithUnknownPrincipalIsDropped() {
        SimpUser simpUser = mock(SimpUser.class);
        when(simpUser.getPrincipal()).thenReturn(() -> "plain-principal");
        when(userRegistry.getUser(USER.toString())).thenReturn(simpUser);
        receive(envelope(1, "other", "/topic/user/" + USER + "/events", TENANT, JSON));
        verify(dispatcher, never()).deliver(anyString(), any());
        assertThat(dropped("principal_unknown")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- BE-242: HMAC

    private static final String HMAC_SECRET = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-0123";
    private static final String OLD_SECRET = "0ld-0ld-0ld-0ld-0ld-0ld-0ld-0ld-0ld-0ld";

    private WebSocketChannelSigner signer(String secret, String previous, boolean acceptUnsigned) {
        WebSocketHmacProperties hmac = new WebSocketHmacProperties();
        hmac.setSecret(secret);
        hmac.setPreviousSecret(previous);
        hmac.setAcceptUnsigned(acceptUnsigned);
        return new WebSocketChannelSigner(hmac, registry);
    }

    private WebSocketFanoutSubscriber signedSubscriber(WebSocketChannelSigner signer) {
        return new WebSocketFanoutSubscriber(dispatcher, new WebSocketFanoutMetrics(registry), properties, userRegistry,
                signer, mock(RedisMessageListenerContainer.class));
    }

    private String signedEnvelope(WebSocketChannelSigner sender, String dest, UUID tenant, String payload) {
        WebSocketFanoutEnvelope unsigned = new WebSocketFanoutEnvelope(1, "other", dest, tenant,
                System.currentTimeMillis(), payload);
        return unsigned.withSignature(sender.sign(properties.effectiveChannel(), unsigned.canonicalForSigning()))
                .toJson();
    }

    private void receiveBy(WebSocketFanoutSubscriber sub, String body) {
        sub.onMessage(new DefaultMessage(properties.effectiveChannel().getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)), null);
    }

    private static final String AGENTS_DEST = "/topic/tenant/" + TENANT + "/agents";

    @Test
    @DisplayName("BE-242: koperta z poprawnym podpisem jest dostarczana")
    void validSignatureIsDelivered() {
        WebSocketChannelSigner s = signer(HMAC_SECRET, null, false);

        receiveBy(signedSubscriber(s), signedEnvelope(s, AGENTS_DEST, TENANT, "{\"a\":1}"));

        verify(dispatcher).deliver(eq(AGENTS_DEST), any(byte[].class));
    }

    @Test
    @DisplayName("BE-242: sfalszowana koperta (zly podpis) nie trafia do brokera, reason=bad_signature, licznik wspolny + fanout.dropped")
    void forgedEnvelopeIsRejected() {
        WebSocketChannelSigner receiver = signer(HMAC_SECRET, null, false);
        WebSocketChannelSigner attacker = signer("4tt4ck3r-4tt4ck3r-4tt4ck3r-4tt4ck3r-0000", null, false);

        receiveBy(signedSubscriber(receiver), signedEnvelope(attacker, AGENTS_DEST, TENANT, "{\"forged\":true}"));

        verify(dispatcher, never()).deliver(anyString(), any(byte[].class));
        assertThat(dropped("bad_signature")).isEqualTo(1);
        assertThat(registry.get(WebSocketChannelSigner.METRIC_REJECTED)
                .tags("channel", "fanout", "reason", "bad_signature").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-242: podmiana payloadu po podpisaniu (poprawny tenant, ten sam podpis) jest wykrywana")
    void tamperedPayloadIsRejected() {
        WebSocketChannelSigner s = signer(HMAC_SECRET, null, false);
        String good = signedEnvelope(s, AGENTS_DEST, TENANT, "{\"a\":1}");
        String tampered = good.replace("\\\"a\\\":1", "\\\"a\\\":2");
        assertThat(tampered).isNotEqualTo(good);

        receiveBy(signedSubscriber(s), tampered);

        verify(dispatcher, never()).deliver(anyString(), any(byte[].class));
        assertThat(dropped("bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-242: koperta BEZ podpisu przy wlaczonym HMAC jest odrzucona (missing_signature)")
    void unsignedEnvelopeIsRejectedWhenHmacOn() {
        receiveBy(signedSubscriber(signer(HMAC_SECRET, null, false)),
                envelope(1, "other", AGENTS_DEST, TENANT, "{}"));

        verify(dispatcher, never()).deliver(anyString(), any(byte[].class));
        assertThat(dropped("missing_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-242: tryb przejsciowy akceptuje koperty bez podpisu (i liczy je), ale zly podpis dalej odrzuca")
    void transitionalModeAcceptsUnsignedButNotForged() {
        WebSocketFanoutSubscriber sub = signedSubscriber(signer(HMAC_SECRET, null, true));
        WebSocketChannelSigner attacker = signer("4tt4ck3r-4tt4ck3r-4tt4ck3r-4tt4ck3r-0000", null, false);

        receiveBy(sub, envelope(1, "other", AGENTS_DEST, TENANT, "{}"));
        receiveBy(sub, signedEnvelope(attacker, AGENTS_DEST, TENANT, "{}"));

        verify(dispatcher, org.mockito.Mockito.times(1)).deliver(eq(AGENTS_DEST), any(byte[].class));
        assertThat(registry.get(WebSocketChannelSigner.METRIC_UNSIGNED).tag("channel", "fanout").counter().count())
                .isEqualTo(1);
        assertThat(dropped("bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-242: rotacja bez utraty zdarzen - odbiorca (nowy+stary) przyjmuje koperty podpisane OBOMA sekretami")
    void rotationLosesNoEvents() {
        WebSocketFanoutSubscriber rotating = signedSubscriber(signer(HMAC_SECRET, OLD_SECRET, false));

        receiveBy(rotating, signedEnvelope(signer(OLD_SECRET, null, false), AGENTS_DEST, TENANT, "{\"n\":\"old\"}"));
        receiveBy(rotating, signedEnvelope(signer(HMAC_SECRET, null, false), AGENTS_DEST, TENANT, "{\"n\":\"new\"}"));

        verify(dispatcher, org.mockito.Mockito.times(2)).deliver(eq(AGENTS_DEST), any(byte[].class));
        assertThat(dropped("bad_signature")).isZero();
    }

    @Test
    @DisplayName("BE-242: poprawnie podpisana koperta nadal przechodzi walidacje odbiorcy (tenant z destynacji)")
    void signatureDoesNotReplaceReceiverValidation() {
        WebSocketChannelSigner s = signer(HMAC_SECRET, null, false);

        receiveBy(signedSubscriber(s), signedEnvelope(s, AGENTS_DEST, UUID.randomUUID(), "{}"));

        verify(dispatcher, never()).deliver(anyString(), any(byte[].class));
        assertThat(dropped("tenant_mismatch")).isEqualTo(1);
    }

    @Test
    @DisplayName("W3: powtorzona (przechwycona) podpisana koperta jest dostarczona tylko RAZ, kopia = drop reason=replay")
    void replayedEnvelopeIsDeliveredOnce() {
        WebSocketChannelSigner s = signer(HMAC_SECRET, null, false);
        WebSocketFanoutSubscriber sub = signedSubscriber(s);
        String envelope = signedEnvelope(s, AGENTS_DEST, TENANT, "{\"a\":1}");

        receiveBy(sub, envelope);
        receiveBy(sub, envelope);
        receiveBy(sub, envelope);

        verify(dispatcher, org.mockito.Mockito.times(1)).deliver(eq(AGENTS_DEST), any(byte[].class));
        assertThat(dropped("replay")).isEqualTo(2);
    }

    /** Replaces the {@code sig} value of a JSON envelope (a textual variant of the SAME signature bytes). */
    private static String withSig(String json, java.util.function.UnaryOperator<String> change) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"sig\":\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalStateException("no sig in " + json);
        }
        return json.substring(0, m.start(1)) + change.apply(m.group(1)) + json.substring(m.end(1));
    }

    private static String flipUnusedBit(String sig) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        return sig.substring(0, sig.length() - 1) + alphabet.charAt(alphabet.indexOf(sig.charAt(sig.length() - 1)) ^ 1);
    }

    @Test
    @DisplayName("W1: wariant tekstowy podpisu (spacja, nieistotny bit) nie omija guarda replay: dostarczona RAZ, warianty = bad_signature")
    void signatureTextVariantsDoNotBypassReplayGuard() {
        WebSocketChannelSigner s = signer(HMAC_SECRET, null, false);
        WebSocketFanoutSubscriber sub = signedSubscriber(s);
        String envelope = signedEnvelope(s, AGENTS_DEST, TENANT, "{\"a\":1}");

        receiveBy(sub, envelope);
        receiveBy(sub, withSig(envelope, sig -> sig + " "));
        receiveBy(sub, withSig(envelope, WebSocketFanoutSubscriberTest::flipUnusedBit));
        receiveBy(sub, envelope);

        verify(dispatcher, org.mockito.Mockito.times(1)).deliver(eq(AGENTS_DEST), any(byte[].class));
        assertThat(dropped("replay")).isEqualTo(1);
    }
}
