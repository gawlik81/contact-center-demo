package com.contactcenter.infrastructure.websocket;

import com.contactcenter.infrastructure.websocket.WebSocketChannelSigner.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebSocketChannelSigner (BE-242/BE-249): HMAC-SHA256 kopert cc:ws:*, rotacja, tryb przejsciowy, start prod")
class WebSocketChannelSignerTest {

    private static final String SECRET = "current-secret-current-secret-0123456789";
    private static final String OLD_SECRET = "previous-secret-previous-secret-0123456789";
    private static final String CHANNEL = "cc:ws:fanout:prod";
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private SimpleMeterRegistry meters;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        clock = new MutableClock(NOW);
    }

    private WebSocketChannelSigner signer(String secret, String previous, boolean acceptUnsigned, boolean required) {
        WebSocketHmacProperties p = new WebSocketHmacProperties();
        p.setSecret(secret);
        p.setPreviousSecret(previous);
        p.setAcceptUnsigned(acceptUnsigned);
        p.setRequired(required);
        return new WebSocketChannelSigner(p, meters, clock);
    }

    private double rejected(String channel, String reason) {
        var c = meters.find(WebSocketChannelSigner.METRIC_REJECTED).tags("channel", channel, "reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("podpis jest deterministyczny, base64url bez paddingu, a weryfikacja go akceptuje")
    void signsAndVerifies() {
        WebSocketChannelSigner signer = signer(SECRET, null, false, false);

        String sig = signer.sign(CHANNEL, "payload");

        assertThat(sig).isNotBlank().doesNotContain("=", "+", "/");
        assertThat(signer.sign(CHANNEL, "payload")).isEqualTo(sig);
        assertThat(signer.verify("fanout", CHANNEL, "payload", sig, NOW.toEpochMilli())).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    @DisplayName("zmiana jakiegokolwiek bajtu danych albo podpisu = bad_signature + licznik")
    void tamperedDataOrSignatureIsRejected() {
        WebSocketChannelSigner signer = signer(SECRET, null, false, false);
        String sig = signer.sign(CHANNEL, "payload");

        assertThat(signer.verify("fanout", CHANNEL, "payloae", sig, NOW.toEpochMilli())).isEqualTo(Verdict.REJECTED_BAD);
        assertThat(signer.verify("fanout", CHANNEL, "payload", flipLastChar(sig), NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_BAD);
        assertThat(signer.verify("fanout", CHANNEL, "payload", "!!not-base64!!", NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_BAD);
        assertThat(rejected("fanout", "bad_signature")).isEqualTo(3);
    }

    @Test
    @DisplayName("nazwa kanalu wchodzi do podpisu: koperta z jednego kanalu nie przejdzie na drugim (separacja domen)")
    void envelopeCannotBeMovedBetweenChannels() {
        WebSocketChannelSigner signer = signer(SECRET, null, false, false);
        String sig = signer.sign("cc:ws:fanout:prod", "same-body");

        assertThat(signer.verify("session-control", "cc:ws:session-control:prod", "same-body", sig,
                NOW.toEpochMilli())).isEqualTo(Verdict.REJECTED_BAD);
        assertThat(rejected("session-control", "bad_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("podpis sekretem OBCYM jest odrzucony")
    void foreignSecretIsRejected() {
        String forged = signer("another-secret-another-secret-0123456789", null, false, false).sign(CHANNEL, "x");

        assertThat(signer(SECRET, null, false, false).verify("fanout", CHANNEL, "x", forged, NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_BAD);
    }

    @Test
    @DisplayName("rotacja: odbiorca z sekretem biezacym+poprzednim akceptuje oba; nadawca podpisuje ZAWSZE biezacym")
    void rotationAcceptsPreviousSecret() {
        WebSocketChannelSigner oldSender = signer(OLD_SECRET, null, false, false);
        WebSocketChannelSigner newSender = signer(SECRET, null, false, false);
        WebSocketChannelSigner rotatingReceiver = signer(SECRET, OLD_SECRET, false, false);

        assertThat(rotatingReceiver.verify("fanout", CHANNEL, "x", oldSender.sign(CHANNEL, "x"), NOW.toEpochMilli()))
                .isEqualTo(Verdict.ACCEPTED);
        assertThat(rotatingReceiver.verify("fanout", CHANNEL, "x", newSender.sign(CHANNEL, "x"), NOW.toEpochMilli()))
                .isEqualTo(Verdict.ACCEPTED);
        // sender with both configured signs with the CURRENT one only
        assertThat(signer(SECRET, OLD_SECRET, false, false).sign(CHANNEL, "x")).isEqualTo(newSender.sign(CHANNEL, "x"));
        // after the rotation (previous dropped) the old secret is no longer accepted
        assertThat(signer(SECRET, null, false, false).verify("fanout", CHANNEL, "x", oldSender.sign(CHANNEL, "x"),
                NOW.toEpochMilli())).isEqualTo(Verdict.REJECTED_BAD);
    }

    @Test
    @DisplayName("brak podpisu: odrzucony (missing_signature); w trybie przejsciowym zaakceptowany i policzony")
    void missingSignatureDependsOnTransitionalMode() {
        assertThat(signer(SECRET, null, false, false).verify("fanout", CHANNEL, "x", null, NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_MISSING);
        assertThat(signer(SECRET, null, false, false).verify("fanout", CHANNEL, "x", " ", NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_MISSING);
        assertThat(rejected("fanout", "missing_signature")).isEqualTo(2);

        WebSocketChannelSigner transitional = signer(SECRET, null, true, false);
        assertThat(transitional.verify("fanout", CHANNEL, "x", null, NOW.toEpochMilli()))
                .isEqualTo(Verdict.ACCEPTED_UNSIGNED);
        assertThat(meters.get(WebSocketChannelSigner.METRIC_UNSIGNED).tag("channel", "fanout").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("tryb przejsciowy NIGDY nie akceptuje ZLEGO podpisu")
    void transitionalModeStillRejectsWrongSignature() {
        WebSocketChannelSigner transitional = signer(SECRET, null, true, false);

        assertThat(transitional.verify("fanout", CHANNEL, "x", "AAAA", NOW.toEpochMilli()))
                .isEqualTo(Verdict.REJECTED_BAD);
        assertThat(meters.find(WebSocketChannelSigner.METRIC_UNSIGNED).counter()).isNull();
    }

    @Test
    @DisplayName("podpisany ts spoza okna swiezosci = stale_signature (powtorka starej koperty)")
    void staleEnvelopeIsRejected() {
        WebSocketChannelSigner signer = signer(SECRET, null, false, false);
        String sig = signer.sign(CHANNEL, "x");

        clock.advance(Duration.ofMinutes(6));

        assertThat(signer.verify("fanout", CHANNEL, "x", sig, NOW.toEpochMilli())).isEqualTo(Verdict.REJECTED_STALE);
        assertThat(rejected("fanout", "stale_signature")).isEqualTo(1);
    }

    @Test
    @DisplayName("bez sekretu (dev/test): podpis wylaczony - sign()=null, verify akceptuje wszystko")
    void disabledWithoutSecret() {
        WebSocketChannelSigner off = signer(null, null, false, false);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.sign(CHANNEL, "x")).isNull();
        assertThat(off.verify("fanout", CHANNEL, "x", null, 0)).isEqualTo(Verdict.ACCEPTED);
        assertThat(off.verify("fanout", CHANNEL, "x", "whatever", 0)).isEqualTo(Verdict.ACCEPTED);
    }

    @Test
    @DisplayName("prod (required=true): brak sekretu blokuje start z komunikatem z nazwa zmiennej i komenda generowania")
    void requiredWithoutSecretFailsStartup() {
        assertThatThrownBy(() -> signer(null, null, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WEBSOCKET_HMAC_SECRET")
                .hasMessageContaining("openssl rand -base64 48");
        assertThatThrownBy(() -> signer("   ", null, false, true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sekret krotszy niz minimum blokuje start (takze poza prod); poprzedni sekret tez jest walidowany")
    void tooShortSecretFailsStartup() {
        assertThatThrownBy(() -> signer("short-secret", null, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("za krotki").hasMessageContaining("32");
        assertThatThrownBy(() -> signer("short-secret", null, false, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer(SECRET, "short-previous", false, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("WEBSOCKET_HMAC_PREVIOUS_SECRET");
        assertThatThrownBy(() -> signer(null, OLD_SECRET, false, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sekret dokladnie o minimalnej dlugosci (32 B) jest akceptowany; komunikaty bledu nie ujawniaja sekretu")
    void minimumLengthSecretWorksAndErrorsDoNotLeak() {
        String exactly32 = "0123456789abcdef0123456789abcdef";
        assertThat(signer(exactly32, null, false, true).isEnabled()).isTrue();

        String shortSecret = "leaky-short-secret";
        assertThatThrownBy(() -> signer(shortSecret, null, false, true))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortSecret));
    }

    private static String flipLastChar(String s) {
        char last = s.charAt(s.length() - 1);
        return s.substring(0, s.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    @Test
    @DisplayName("N-S1: twarda podloga 32 B - nie da sie jej obnizyc przez websocket.hmac.min-secret-bytes")
    void minimumSecretLengthCannotBeLoweredByConfiguration() {
        WebSocketHmacProperties p = new WebSocketHmacProperties();
        p.setMinSecretBytes(1);
        p.setSecret("only-20-bytes-long!!");
        assertThatThrownBy(() -> new WebSocketChannelSigner(p, meters, clock))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
    }

    @Test
    @DisplayName("N-S1: okno swiezosci ma gorna granice 15 min niezaleznie od konfiguracji (takze 0/ujemne)")
    void maxAgeIsCapped() {
        for (Duration configured : new Duration[]{Duration.ofHours(5), Duration.ZERO, Duration.ofMinutes(-3)}) {
            WebSocketHmacProperties p = new WebSocketHmacProperties();
            p.setSecret(SECRET);
            p.setMaxAge(configured);
            WebSocketChannelSigner s = new WebSocketChannelSigner(p, new SimpleMeterRegistry(), clock);
            String sig = s.sign(CHANNEL, "x");
            clock.advance(Duration.ofMinutes(14));
            assertThat(s.verify("fanout", CHANNEL, "x", sig, NOW.toEpochMilli()).isAccepted())
                    .as(configured + ": 14 min jeszcze swiezo").isTrue();
            clock.advance(Duration.ofMinutes(2));
            assertThat(s.verify("fanout", CHANNEL, "x", sig, NOW.toEpochMilli())).as(configured + ": 16 min")
                    .isEqualTo(Verdict.REJECTED_STALE);
            clock = new MutableClock(NOW);
        }
    }

    @Test
    @DisplayName("N-S2: gauge ws.channel.accept_unsigned = 1 tylko gdy tryb przejsciowy akceptuje niepodpisane koperty")
    void acceptUnsignedGaugeIsAnAlarmSignal() {
        signer(SECRET, null, true, false);
        assertThat(meters.get(WebSocketChannelSigner.GAUGE_ACCEPT_UNSIGNED).gauge().value()).isEqualTo(1.0);

        SimpleMeterRegistry other = new SimpleMeterRegistry();
        WebSocketHmacProperties p = new WebSocketHmacProperties();
        p.setSecret(SECRET);
        new WebSocketChannelSigner(p, other, clock);
        assertThat(other.get(WebSocketChannelSigner.GAUGE_ACCEPT_UNSIGNED).gauge().value()).isZero();
    }

    @Test
    @DisplayName("T2: kontekst Springa na prod (required=true) BEZ sekretu nie startuje, z czytelnym komunikatem o WEBSOCKET_HMAC_SECRET")
    void contextWithoutSecretOnProdFailsToStart() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(SignerConfig.class)
                .withPropertyValues("websocket.hmac.required=true")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable root = ctx.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("WEBSOCKET_HMAC_SECRET").hasMessageContaining("openssl rand -base64 48");
                });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(SignerConfig.class)
                .withPropertyValues("websocket.hmac.required=true", "websocket.hmac.secret=" + SECRET)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(WebSocketHmacProperties.class)
    static class SignerConfig {
        @org.springframework.context.annotation.Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @org.springframework.context.annotation.Bean
        WebSocketChannelSigner signer(WebSocketHmacProperties p, io.micrometer.core.instrument.MeterRegistry m) {
            return new WebSocketChannelSigner(p, m);
        }
    }
}
