package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.infrastructure.websocket.WebSocketChannelSigner;
import com.contactcenter.infrastructure.websocket.WebSocketHmacProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PluginSyncBus - koperta, walidacja, ignorowanie własnych zdarzeń, fail-safe (BE-178)")
class PluginSyncBusTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final String SECRET = "plugin-sync-test-secret-0123456789-abcdef";

    private StringRedisTemplate redis;
    private PluginSyncProperties properties;
    private SimpleMeterRegistry meters;
    private PluginSyncBus bus;
    private WebSocketHmacProperties hmac;
    private WebSocketChannelSigner signer;
    private final List<PluginSyncEnvelope> received = new ArrayList<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new PluginSyncProperties();
        properties.setInstanceId("it");
        properties.setEnvironment("test env!");
        meters = new SimpleMeterRegistry();
        ObjectProvider<RedisMessageListenerContainer> noContainer = mock(ObjectProvider.class);
        when(noContainer.getIfAvailable()).thenReturn(null);
        hmac = new WebSocketHmacProperties();
        hmac.setSecret(SECRET);
        signer = new WebSocketChannelSigner(hmac, meters);
        bus = new PluginSyncBus(redis, properties, meters, signer, noContainer);
        bus.setListener(received::add);
    }

    /** Serializes the envelope SIGNED the way a legitimate peer would (an already signed envelope is left alone). */
    private byte[] json(PluginSyncEnvelope envelope) {
        PluginSyncEnvelope signed = envelope.sig() != null ? envelope
                : envelope.withSignature(signer.sign(properties.effectiveChannel(), envelope.canonicalForSigning()));
        return signed.toJson().getBytes(StandardCharsets.UTF_8);
    }

    private PluginSyncEnvelope fromOther(PluginSyncEnvelope.Type type) {
        return new PluginSyncEnvelope(1, "other-1234", type, TENANT, INSTALLATION, null, System.currentTimeMillis());
    }

    private void deliver(byte[] body) {
        bus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), body), null);
    }

    private double counter(String name, String tag, String value) {
        var c = meters.find(name).tag(tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("kanał ma sufiks środowiska, znaki spoza [A-Za-z0-9_-] zamienione; id instancji ma losowy sufiks")
    void channelAndInstanceId() {
        assertThat(properties.effectiveChannel()).isEqualTo("cc:plugin:sync:test_env_");
        assertThat(bus.instanceId()).startsWith("it-").hasSize("it-".length() + 8);
    }

    @Test
    @DisplayName("publish serializuje kopertę z id instancji i typem na kanał środowiska")
    void publishSendsEnvelope() {
        boolean ok = bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("cc:plugin:sync:test_env_"), payload.capture());
        assertThat(ok).isTrue();
        assertThat(payload.getValue()).contains("INSTALLATION_CHANGED").contains(TENANT.toString())
                .contains(bus.instanceId()).contains("\"sig\":\"");
        assertThat(counter(PluginSyncBus.METRIC_PUBLISHED, "type", "INSTALLATION_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("publish nigdy nie rzuca przy awarii Redisa - zwraca false i liczy błąd (operacja admina nie może paść)")
    void publishNeverThrows() {
        doThrow(new IllegalStateException("redis down")).when(redis).convertAndSend(anyString(), anyString());

        boolean ok = bus.publish(PluginSyncEnvelope.Type.VERSION_REVOKED, null, null, UUID.randomUUID());

        assertThat(ok).isFalse();
        assertThat(counter(PluginSyncBus.METRIC_PUBLISH_FAILURES, "type", "VERSION_REVOKED")).isEqualTo(1);
    }

    @Test
    @DisplayName("enabled=false: nic nie publikuje")
    void disabledDoesNotPublish() {
        properties.setEnabled(false);

        assertThat(bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null)).isTrue();

        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("własne zdarzenie jest ignorowane (licznik ignored{self}), cudze trafia do listenera")
    void ignoresOwnEventsDeliversForeign() {
        deliver(json(new PluginSyncEnvelope(1, bus.instanceId(), PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, System.currentTimeMillis())));
        deliver(json(fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED)));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).src()).isEqualTo("other-1234");
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "self")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_RECEIVED, "type", "INSTALLATION_CHANGED")).isEqualTo(1);
    }

    @Test
    @DisplayName("odrzuca: przekroczony rozmiar, niepoprawny JSON, nieznana wersja, brak wymaganych id, nieznany typ")
    void dropsInvalidEnvelopes() {
        properties.setMaxMessageBytes(100);
        deliver(new byte[101]);
        properties.setMaxMessageBytes(4096);
        deliver("not json".getBytes(StandardCharsets.UTF_8));
        deliver(json(new PluginSyncEnvelope(2, "other", PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT,
                INSTALLATION, null, 1)));
        deliver(json(new PluginSyncEnvelope(1, "other", PluginSyncEnvelope.Type.INSTALLATION_CHANGED, null, null,
                null, 1)));
        deliver(json(new PluginSyncEnvelope(1, "other", PluginSyncEnvelope.Type.VERSION_REVOKED, TENANT, null,
                null, 1)));
        deliver("{\"v\":1,\"src\":\"x\",\"type\":\"SELF_DESTRUCT\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "oversize")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "malformed")).isEqualTo(2);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "unsupported_version")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "invalid")).isEqualTo(2);
    }

    @Test
    @DisplayName("wyjątek listenera nie wycieka do wątku Redisa (licznik handler_error)")
    void listenerExceptionIsContained() {
        bus.setListener(e -> {
            throw new IllegalStateException("boom");
        });

        deliver(json(fromOther(PluginSyncEnvelope.Type.CIRCUIT_OPENED)));

        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "handler_error")).isEqualTo(1);
    }

    @Test
    @DisplayName("koperta ignoruje nieznane pola (zgodność w przód przy rolling deploy)")
    void unknownFieldsAreIgnored() throws Exception {
        String body = "{\"v\":1,\"src\":\"o\",\"type\":\"CIRCUIT_CLOSED\",\"tenantId\":\"" + TENANT
                + "\",\"installationId\":\"" + INSTALLATION + "\",\"ts\":1,\"futureField\":42}";

        PluginSyncEnvelope parsed = PluginSyncEnvelope.fromJson(body.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.isWellFormed()).isTrue();
        assertThat(parsed.type()).isEqualTo(PluginSyncEnvelope.Type.CIRCUIT_CLOSED);
    }

    // ------------------------------------------------------------------ BE-245: HMAC of the envelope

    private PluginSyncEnvelope published() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("cc:plugin:sync:test_env_"), payload.capture());
        try {
            return PluginSyncEnvelope.fromJson(payload.getValue().getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private double rejectedMetric(String reason) {
        var c = meters.find("ws.channel.signature.rejected").tag("channel", "plugin-sync")
                .tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("kontrola pozytywna: koperta opublikowana przez bus (podpisana) przechodzi weryfikację odbiorcy z inną instancją")
    void publishedEnvelopeIsAcceptedByAnotherInstance() {
        bus.publish(PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null);
        PluginSyncEnvelope sent = published();
        assertThat(sent.sig()).isNotBlank();

        // another instance: same secret, other instance id -> the sent envelope is "foreign" for it
        properties.setInstanceId("receiver");
        ObjectProvider<RedisMessageListenerContainer> none = org.mockito.Mockito.mock(ObjectProvider.class);
        PluginSyncBus receiver = new PluginSyncBus(redis, properties, meters, signer, none);
        List<PluginSyncEnvelope> got = new ArrayList<>();
        receiver.setListener(got::add);
        receiver.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                sent.toJson().getBytes(StandardCharsets.UTF_8)), null);

        assertThat(got).hasSize(1);
        assertThat(rejectedMetric("bad_signature") + rejectedMetric("missing_signature")).isZero();
    }

    @Test
    @DisplayName("koperta BEZ podpisu jest odrzucona i policzona (ignored{missing_signature} + ws.channel.signature.rejected)")
    void unsignedEnvelopeIsRejected() {
        deliver(fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED).toJson().getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "missing_signature")).isEqualTo(1);
        assertThat(rejectedMetric("missing_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("koperta ze ZŁYM podpisem (inny sekret, albo zmienione pole po podpisaniu) jest odrzucona")
    void wrongSignatureIsRejected() {
        PluginSyncEnvelope env = fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED);
        WebSocketHmacProperties otherSecret = new WebSocketHmacProperties();
        otherSecret.setSecret("another-secret-0123456789-abcdef-0123456789");
        String foreign = new WebSocketChannelSigner(otherSecret, new SimpleMeterRegistry())
                .sign(properties.effectiveChannel(), env.canonicalForSigning());
        deliver(env.withSignature(foreign).toJson().getBytes(StandardCharsets.UTF_8));

        // tampered: signed for INSTALLATION_CHANGED, delivered as CIRCUIT_CLOSED of the same installation
        PluginSyncEnvelope signed = new PluginSyncEnvelope(1, "other-1234",
                PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null, System.currentTimeMillis());
        signed = signed.withSignature(signer.sign(properties.effectiveChannel(), signed.canonicalForSigning()));
        PluginSyncEnvelope tampered = new PluginSyncEnvelope(1, "other-1234", PluginSyncEnvelope.Type.CIRCUIT_CLOSED,
                TENANT, INSTALLATION, null, signed.ts(), signed.sig());
        deliver(tampered.toJson().getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "bad_signature")).isEqualTo(2);
        assertThat(rejectedMetric("bad_signature")).isEqualTo(2);
    }

    @Test
    @DisplayName("podpis nie przenosi się między kanałami (nazwa kanału jest w podpisanych danych)")
    void signatureIsBoundToTheChannel() {
        PluginSyncEnvelope env = fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED);
        String otherChannelSig = signer.sign("cc:ws:fanout:test_env_", env.canonicalForSigning());

        deliver(env.withSignature(otherChannelSig).toJson().getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(rejectedMetric("bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("przeterminowana koperta (podpis poprawny, ts poza oknem) jest odrzucona jako stale_signature")
    void staleEnvelopeIsRejected() {
        PluginSyncEnvelope old = new PluginSyncEnvelope(1, "other-1234", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, System.currentTimeMillis() - 3_600_000L);

        deliver(json(old));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "stale_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("powtórka tej samej podpisanej koperty jest odrzucona (replay), pierwsza dostawa przechodzi")
    void replayedEnvelopeIsRejected() {
        byte[] body = json(fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED));

        deliver(body);
        deliver(body);

        assertThat(received).hasSize(1);
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "replay")).isEqualTo(1);
    }

    @Test
    @DisplayName("tryb przejściowy accept-unsigned: koperta bez podpisu przechodzi, ze złym nadal jest odrzucana")
    void transitionalModeAcceptsUnsignedButNotWrong() {
        hmac.setAcceptUnsigned(true);

        deliver(fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED).toJson().getBytes(StandardCharsets.UTF_8));
        deliver(fromOther(PluginSyncEnvelope.Type.CIRCUIT_OPENED).withSignature("AAAA")
                .toJson().getBytes(StandardCharsets.UTF_8));

        assertThat(received).hasSize(1);
        assertThat(rejectedMetric("bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("poprzedni sekret (rotacja) jest akceptowany przez odbiorcę")
    void previousSecretIsAccepted() {
        hmac.setSecret(SECRET);
        WebSocketHmacProperties old = new WebSocketHmacProperties();
        old.setSecret("old-secret-0123456789-abcdefghijklmnopqrstuv");
        WebSocketChannelSigner oldSigner = new WebSocketChannelSigner(old, new SimpleMeterRegistry());
        PluginSyncEnvelope env = fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED);
        byte[] body = env.withSignature(oldSigner.sign(properties.effectiveChannel(), env.canonicalForSigning()))
                .toJson().getBytes(StandardCharsets.UTF_8);

        WebSocketHmacProperties rotated = new WebSocketHmacProperties();
        rotated.setSecret(SECRET);
        rotated.setPreviousSecret("old-secret-0123456789-abcdefghijklmnopqrstuv");
        ObjectProvider<RedisMessageListenerContainer> none = org.mockito.Mockito.mock(ObjectProvider.class);
        PluginSyncBus receiver = new PluginSyncBus(redis, properties, meters,
                new WebSocketChannelSigner(rotated, meters), none);
        List<PluginSyncEnvelope> got = new ArrayList<>();
        receiver.setListener(got::add);
        receiver.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8), body), null);

        assertThat(got).hasSize(1);
    }

    private static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /** Another textual spelling of the SAME 32 signature bytes: flips an unused low bit of the last character. */
    private static String sameBytesVariant(String sig) {
        char last = sig.charAt(sig.length() - 1);
        char variant = B64URL.charAt(B64URL.indexOf(last) ^ 1);
        return sig.substring(0, sig.length() - 1) + variant;
    }

    @Test
    @DisplayName("W1: wariant tekstowy podpisu (spacja, nieistotny bit ostatniego znaku) nie jest 'nową' kopertą - odrzucony, kontrola pozytywna przechodzi")
    void textualVariantsOfASignatureAreNotNewEnvelopes() {
        PluginSyncEnvelope signed = fromOther(PluginSyncEnvelope.Type.INSTALLATION_CHANGED);
        signed = signed.withSignature(signer.sign(properties.effectiveChannel(), signed.canonicalForSigning()));

        deliver(signed.toJson().getBytes(StandardCharsets.UTF_8)); // positive control
        assertThat(received).hasSize(1);

        for (String variant : List.of(signed.sig() + " ", " " + signed.sig() + "\n", sameBytesVariant(signed.sig()))) {
            assertThat(variant).isNotEqualTo(signed.sig());
            deliver(signed.withSignature(variant).toJson().getBytes(StandardCharsets.UTF_8));
        }

        assertThat(received).hasSize(1);
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "bad_signature")).isEqualTo(3);
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "replay")).isZero();
    }

    @Test
    @DisplayName("R4: dwie koperty tej samej instancji, tego samego typu, w tej samej ms mają identyczny podpis - druga to 'replay' (udokumentowane zachowanie; zdarzenie to podpowiedź, stan czyta się z DB)")
    void twoIdenticalEnvelopesInTheSameMillisecondSecondIsReplay() {
        long ts = System.currentTimeMillis();
        PluginSyncEnvelope a = new PluginSyncEnvelope(1, "other-1234", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, ts);
        PluginSyncEnvelope b = new PluginSyncEnvelope(1, "other-1234", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, ts);

        deliver(json(a));
        deliver(json(b));

        assertThat(received).hasSize(1);
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "replay")).isEqualTo(1);
        // one millisecond later the envelope is a different message
        deliver(json(new PluginSyncEnvelope(1, "other-1234", PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, ts + 1)));
        assertThat(received).hasSize(2);
    }

    @Test
    @DisplayName("R4: src z bajtem NUL jest odrzucony jako invalid PRZED weryfikacją podpisu (brak dwuznaczności formy kanonicznej)")
    void srcWithNulIsDropped() {
        PluginSyncEnvelope nul = new PluginSyncEnvelope(1, "other\u0000-1234",
                PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null, System.currentTimeMillis());

        deliver(json(nul));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_DROPPED, "reason", "invalid")).isEqualTo(1);
        assertThat(rejectedMetric("bad_signature")).isZero();
    }

    @Test
    @DisplayName("N: sfałszowana koperta z src = id tej instancji liczy się jako bad_signature, nie jako 'self'")
    void forgedEnvelopeClaimingOwnSrcIsBadSignature() {
        PluginSyncEnvelope forged = new PluginSyncEnvelope(1, bus.instanceId(),
                PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null, System.currentTimeMillis(),
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        deliver(forged.toJson().getBytes(StandardCharsets.UTF_8));

        assertThat(received).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "bad_signature")).isEqualTo(1);
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "self")).isZero();
    }

    @Test
    @DisplayName("N: poprawnie podpisane własne zdarzenie to 'self'; bez sekretu (niepodpisane) własne zdarzenie też jest 'self'")
    void ownSignedAndUnsignedEventsAreSelf() {
        deliver(json(new PluginSyncEnvelope(1, bus.instanceId(), PluginSyncEnvelope.Type.INSTALLATION_CHANGED,
                TENANT, INSTALLATION, null, System.currentTimeMillis())));
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "self")).isEqualTo(1);

        // signing off (dev/test): envelopes are unsigned, own events are still recognised
        WebSocketChannelSigner off = new WebSocketChannelSigner(new WebSocketHmacProperties(), new SimpleMeterRegistry());
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> none = mock(ObjectProvider.class);
        PluginSyncBus unsignedBus = new PluginSyncBus(redis, properties, meters, off, none);
        List<PluginSyncEnvelope> got = new ArrayList<>();
        unsignedBus.setListener(got::add);
        PluginSyncEnvelope own = new PluginSyncEnvelope(1, unsignedBus.instanceId(),
                PluginSyncEnvelope.Type.INSTALLATION_CHANGED, TENANT, INSTALLATION, null, System.currentTimeMillis());
        unsignedBus.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                own.toJson().getBytes(StandardCharsets.UTF_8)), null);

        assertThat(got).isEmpty();
        assertThat(counter(PluginSyncBus.METRIC_IGNORED, "reason", "self")).isEqualTo(2);
    }
}
