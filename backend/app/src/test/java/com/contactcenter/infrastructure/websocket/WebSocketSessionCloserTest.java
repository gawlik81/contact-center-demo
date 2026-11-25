package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.security.WsErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocketSessionCloser (BE-233): ramka ERROR z kodem, sweep z falszywym zegarem")
class WebSocketSessionCloserTest {

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final List<Message<?>> sent = new ArrayList<>();
    private boolean failSend;
    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private WebSocketSessionProperties props;
    private WebSocketSessionRegistry registry;
    private WebSocketSessionCloser closer;

    @BeforeEach
    void setUp() {
        props = new WebSocketSessionProperties();
        props.setCloseJitter(Duration.ZERO);
        meters = new SimpleMeterRegistry();
        clock = new MutableClock(T0);
        registry = new WebSocketSessionRegistry(props, meters, clock);
        MessageChannel channel = (message, timeout) -> {
            if (failSend) {
                throw new IllegalStateException("channel down");
            }
            return sent.add(message);
        };
        closer = new WebSocketSessionCloser(registry, channel, meters, clock);
    }

    @Test
    @DisplayName("closeUser: ERROR z code=WS_TOKEN_INVALID na sessionId, wpis usuniety, liczniki rosna")
    void closeUserSendsErrorFrameWithCode() {
        registry.register("s1", USER, TENANT, null);
        registry.register("s2", USER, TENANT, null);
        registry.register("other", UUID.randomUUID(), TENANT, null);

        int closed = closer.closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED);

        assertThat(closed).isEqualTo(2);
        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(m -> {
            StompHeaderAccessor a = StompHeaderAccessor.wrap(m);
            assertThat(a.getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(a.getFirstNativeHeader(WsErrorCode.HEADER)).isEqualTo("WS_TOKEN_INVALID");
            assertThat(SimpMessageHeaderAccessor.getSessionId(m.getHeaders())).isIn("s1", "s2");
        });
        assertThat(registry.size()).isEqualTo(1);
        assertThat(meters.get(WebSocketSessionCloser.METRIC_CLOSED).tag("reason", "ROLE_CHANGED").counter().count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Ramka ERROR jest ogolna: bez powodu, tenanta i identyfikatorow uzytkownika")
    void errorFrameCarriesNoIdentifiers() {
        registry.register("s1", USER, TENANT, null);

        closer.closeUser(TENANT, USER, SessionCloseReason.DEACTIVATED);

        StompHeaderAccessor a = StompHeaderAccessor.wrap(sent.get(0));
        String frame = a.getMessageHeaders().toString();
        assertThat(frame).doesNotContain(USER.toString()).doesNotContain(TENANT.toString())
                .doesNotContain("DEACTIVATED");
        assertThat(a.getMessage()).isEqualTo(WebSocketSessionCloser.ERROR_MESSAGE);
    }

    @Test
    @DisplayName("Obcy tenant w parze: nic nie zamkniete, brak ramek")
    void wrongTenantClosesNothing() {
        registry.register("s1", USER, TENANT, null);

        assertThat(closer.closeUser(UUID.randomUUID(), USER, SessionCloseReason.LOGOUT)).isZero();

        assertThat(sent).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("closeTenant zamyka sesje calego tenanta")
    void closeTenant() {
        registry.register("a", UUID.randomUUID(), TENANT, null);
        registry.register("b", UUID.randomUUID(), TENANT, null);
        registry.register("c", UUID.randomUUID(), UUID.randomUUID(), null);

        assertThat(closer.closeTenant(TENANT, SessionCloseReason.TENANT_DEACTIVATED)).isEqualTo(2);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Max lifetime z falszywym zegarem: przed terminem nic, po terminie ERROR WS_TOKEN_EXPIRED")
    void sweepClosesAfterMaxLifetimeOnFakeClock() {
        props.setMaxLifetime(Duration.ofHours(1));
        props.setCloseAtTokenExpiry(false);
        registry.register("s1", USER, TENANT, T0.plusSeconds(60));

        clock.advance(Duration.ofMinutes(59));
        closer.sweep();
        assertThat(sent).isEmpty();
        assertThat(registry.size()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        closer.sweep();
        assertThat(sent).hasSize(1);
        assertThat(StompHeaderAccessor.wrap(sent.get(0)).getFirstNativeHeader(WsErrorCode.HEADER))
                .isEqualTo("WS_TOKEN_EXPIRED");
        assertThat(registry.size()).isZero();
        assertThat(meters.get(WebSocketSessionCloser.METRIC_CLOSED).tag("reason", "MAX_LIFETIME").counter().count())
                .isEqualTo(1);

        closer.sweep(); // idempotentne: nic wiecej do zamkniecia
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("Wygasniecie tokenu z CONNECT zamyka sesje (reason TOKEN_EXPIRED), niezaleznie od limitu")
    void sweepClosesAtTokenExpiry() {
        registry.register("s1", USER, TENANT, T0.plusSeconds(900));
        registry.register("s2", UUID.randomUUID(), TENANT, T0.plusSeconds(1800));

        clock.advance(Duration.ofSeconds(900));
        closer.sweep();

        assertThat(sent).hasSize(1);
        assertThat(SimpMessageHeaderAccessor.getSessionId(sent.get(0).getHeaders())).isEqualTo("s1");
        assertThat(meters.get(WebSocketSessionCloser.METRIC_CLOSED).tag("reason", "TOKEN_EXPIRED").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("W1: blad wysylki ramki NIE gubi sesji - wpis wraca do rejestru, nastepny sweep ponawia i zamyka")
    void failedSendKeepsSessionTrackedAndIsRetried() {
        registry.register("s1", USER, TENANT, null);
        failSend = true;

        assertThat(closer.closeUser(TENANT, USER, SessionCloseReason.LOGOUT)).isZero();

        assertThat(registry.size()).as("sesja nadal sledzona (transport mogl zostac otwarty)").isEqualTo(1);
        assertThat(registry.countForUser(USER)).isEqualTo(1);
        assertThat(meters.get(WebSocketSessionCloser.METRIC_CLOSE_FAILURES).counter().count()).isEqualTo(1);
        assertThat(sent).isEmpty();

        // kanal wraca do zycia: sweep (termin = "teraz") ponawia zamkniecie z kodem zalezym od powodu LOGOUT
        failSend = false;
        closer.sweep();

        assertThat(sent).hasSize(1);
        assertThat(StompHeaderAccessor.wrap(sent.get(0)).getFirstNativeHeader(WsErrorCode.HEADER))
                .isEqualTo("WS_TOKEN_INVALID");
        assertThat(registry.size()).isZero();
        assertThat(meters.get(WebSocketSessionCloser.METRIC_CLOSED).tag("reason", "LOGOUT").counter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("W1: kanal zwracajacy false (ramka odrzucona) tez liczy sie jako porazka i jest ponawiany")
    void rejectedFrameIsRetried() {
        MessageChannel rejecting = (message, timeout) -> false;
        WebSocketSessionCloser c = new WebSocketSessionCloser(registry, rejecting, meters, clock);
        registry.register("s1", USER, TENANT, null);

        assertThat(c.closeUser(TENANT, USER, SessionCloseReason.ROLE_CHANGED)).isZero();

        assertThat(registry.size()).isEqualTo(1);
        // ponowienie przez sweep nastepuje dopiero po chwili: termin = "teraz" => juz wymagalny
        assertThat(registry.takeExpired(clock.instant())).hasSize(1);
    }

    @Test
    @DisplayName("W1: porazka wysylki przy wygasnieciu terminu tez zostawia sesje do ponowienia")
    void failedSweepCloseIsRetried() {
        registry.register("s1", USER, TENANT, T0.plusSeconds(10));
        clock.advance(Duration.ofSeconds(10));
        failSend = true;
        closer.sweep();
        assertThat(registry.size()).isEqualTo(1);

        failSend = false;
        closer.sweep();

        assertThat(sent).hasSize(1);
        assertThat(StompHeaderAccessor.wrap(sent.get(0)).getFirstNativeHeader(WsErrorCode.HEADER))
                .isEqualTo("WS_TOKEN_EXPIRED");
        assertThat(registry.size()).isZero();
    }
}
