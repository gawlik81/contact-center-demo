package com.contactcenter.infrastructure.websocket;

import com.contactcenter.domain.websocket.SessionCloseReason;
import com.contactcenter.infrastructure.websocket.WebSocketSessionRegistry.Entry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocketSessionRegistry (BE-233): rejestr sesji po userId, terminy, brak przecieku")
class WebSocketSessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private WebSocketSessionProperties props;
    private SimpleMeterRegistry meters;
    private MutableClock clock;
    private WebSocketSessionRegistry registry;

    @BeforeEach
    void setUp() {
        props = new WebSocketSessionProperties();
        props.setCloseJitter(Duration.ZERO);
        props.setMaxLifetime(Duration.ofHours(12));
        meters = new SimpleMeterRegistry();
        clock = new MutableClock(T0);
        registry = new WebSocketSessionRegistry(props, meters, clock);
    }

    @Test
    @DisplayName("Termin = wczesniejszy z: exp tokenu i twardego limitu; powod odpowiada zrodlu terminu")
    void deadlineIsTheEarlierOfTokenExpiryAndMaxLifetime() {
        Entry byToken = registry.register("s1", USER, TENANT, T0.plusSeconds(900));
        Entry byLimit = registry.register("s2", USER, TENANT, T0.plus(Duration.ofHours(24)));
        Entry noExp = registry.register("s3", USER, TENANT, null);

        assertThat(byToken.deadline()).isEqualTo(T0.plusSeconds(900));
        assertThat(byToken.deadlineReason()).isEqualTo(SessionCloseReason.TOKEN_EXPIRED);
        assertThat(byLimit.deadline()).isEqualTo(T0.plus(Duration.ofHours(12)));
        assertThat(byLimit.deadlineReason()).isEqualTo(SessionCloseReason.MAX_LIFETIME);
        assertThat(noExp.deadline()).isEqualTo(T0.plus(Duration.ofHours(12)));
    }

    @Test
    @DisplayName("Konfiguracja: close-at-token-expiry=false i max-lifetime=0 -> brak terminu")
    void deadlinesCanBeDisabled() {
        props.setCloseAtTokenExpiry(false);
        props.setMaxLifetime(Duration.ZERO);

        Entry entry = registry.register("s1", USER, TENANT, T0.plusSeconds(900));

        assertThat(entry.deadline()).isNull();
        assertThat(entry.deadlineReason()).isNull();
        assertThat(registry.takeExpired(T0.plus(Duration.ofDays(365)))).isEmpty();
    }

    @Test
    @DisplayName("Tolerancja i jitter przesuwaja termin (jitter w [0, bound))")
    void graceAndJitterShiftTheDeadline() {
        props.setTokenExpiryGrace(Duration.ofSeconds(30));
        props.setCloseJitter(Duration.ofSeconds(10));

        Entry entry = registry.register("s1", USER, TENANT, T0.plusSeconds(900));

        assertThat(entry.deadline()).isBetween(T0.plusSeconds(930), T0.plusSeconds(940));
    }

    @Test
    @DisplayName("takeExpired: tylko sesje po terminie wg zegara; kazda wydana raz")
    void takeExpiredUsesTheClock() {
        registry.register("short", USER, TENANT, T0.plusSeconds(60));
        registry.register("long", UUID.randomUUID(), TENANT, T0.plusSeconds(600));

        assertThat(registry.takeExpired(clock.instant())).isEmpty();
        clock.advance(Duration.ofSeconds(60));
        assertThat(registry.takeExpired(clock.instant())).extracting(Entry::sessionId).containsExactly("short");
        assertThat(registry.takeExpired(clock.instant())).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(600));
        assertThat(registry.takeExpired(clock.instant())).extracting(Entry::sessionId).containsExactly("long");
        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("takeUser dopasowuje PARE tenant/user: obcy tenant nie zamyka nic")
    void takeUserRequiresTheTenantUserPair() {
        registry.register("a", USER, TENANT, null);
        registry.register("b", USER, TENANT, null);

        assertThat(registry.takeUser(OTHER_TENANT, USER)).isEmpty();
        assertThat(registry.takeUser(null, USER)).isEmpty();
        assertThat(registry.size()).isEqualTo(2);

        assertThat(registry.takeUser(TENANT, USER)).extracting(Entry::sessionId).containsExactlyInAnyOrder("a", "b");
        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("SUPER_ADMIN (tenant null) jest dopasowywany tylko z tenant null")
    void superAdminHasNullTenant() {
        registry.register("sa", USER, null, null);

        assertThat(registry.takeUser(TENANT, USER)).isEmpty();
        assertThat(registry.takeUser(null, USER)).hasSize(1);
    }

    @Test
    @DisplayName("takeTenant zabiera wszystkie sesje tenanta i nic z innych")
    void takeTenantTakesOnlyThatTenant() {
        registry.register("a", UUID.randomUUID(), TENANT, null);
        registry.register("b", UUID.randomUUID(), TENANT, null);
        registry.register("c", UUID.randomUUID(), OTHER_TENANT, null);
        registry.register("sa", UUID.randomUUID(), null, null);

        assertThat(registry.takeTenant(TENANT)).hasSize(2);
        assertThat(registry.takeTenant(null)).isEmpty();
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Rejestr nie przecieka: SessionDisconnectEvent czysci oba indeksy, wielokrotnie i idempotentnie")
    void disconnectCleansBothIndexes() {
        registry.register("a", USER, TENANT, null);
        registry.register("b", USER, TENANT, null);

        registry.onDisconnect(disconnect("a"));
        assertThat(registry.countForUser(USER)).isEqualTo(1);
        registry.onDisconnect(disconnect("a"));
        registry.onDisconnect(disconnect("b"));
        registry.onDisconnect(disconnect("nieznana"));

        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("Ponowna rejestracja tego samego sessionId zastepuje wpis (bez duplikatu w indeksie usera)")
    void reRegisteringReplaces() {
        registry.register("a", USER, TENANT, null);
        registry.register("a", USER, TENANT, null);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.countForUser(USER)).isEqualTo(1);
        registry.unregister("a");
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("Metryka ws.sessions.active (bez tagow) odzwierciedla liczbe sesji")
    void gaugeTracksSessionCount() {
        assertThat(gauge()).isZero();
        registry.register("a", USER, TENANT, null);
        registry.register("b", UUID.randomUUID(), OTHER_TENANT, null);
        assertThat(gauge()).isEqualTo(2);
        assertThat(meters.find(WebSocketSessionRegistry.GAUGE_ACTIVE).gauge().getId().getTags()).isEmpty();
        registry.unregister("a");
        assertThat(gauge()).isEqualTo(1);
    }

    @Test
    @DisplayName("Wspolbieznie: rejestracje i zamkniecia nie zostawiaja sierot w indeksach")
    void concurrentRegisterAndTakeLeavesNothingBehind() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = IntStream.range(0, 8).mapToObj(t -> pool.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    String id = "s-" + t + "-" + i;
                    registry.register(id, USER, TENANT, null);
                    if (i % 3 == 0) {
                        registry.takeUser(TENANT, USER);
                    } else if (i % 3 == 1) {
                        registry.unregister(id);
                    }
                }
                return null;
            })).collect(java.util.stream.Collectors.toList());
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        registry.takeUser(TENANT, USER);

        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("DISCONNECT przed rejestracja zostawia tombstone: pozniejszy register jest odrzucony (brak wpisu-sieroty)")
    void disconnectBeforeRegisterLeavesNoEntry() {
        registry.onDisconnect(disconnect("early"));
        assertThat(registry.tombstoneCount()).isEqualTo(1);

        assertThat(registry.register("early", USER, TENANT, null)).isNull();

        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
        assertThat(registry.tombstoneCount()).as("tombstone zuzyty").isZero();
        // inny identyfikator rejestruje sie normalnie
        assertThat(registry.register("ok", USER, TENANT, null)).isNotNull();
    }

    @Test
    @DisplayName("Tombstone wygasa (sweep z zegarem) i ma limit rozmiaru")
    void tombstonesExpireAndAreBounded() {
        registry.onDisconnect(disconnect("old"));
        clock.advance(Duration.ofMinutes(2));
        registry.takeExpired(clock.instant());
        assertThat(registry.tombstoneCount()).isZero();

        for (int i = 0; i < 12_000; i++) {
            registry.unregister("x" + i);
        }
        assertThat(registry.tombstoneCount()).isLessThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("restoreForRetry: wpis wraca z terminem 'teraz' i powodem zamkniecia; takeExpired go wydaje")
    void restoreForRetryMakesEntryDueNow() {
        Entry entry = registry.register("s1", USER, TENANT, T0.plusSeconds(900));
        registry.takeUser(TENANT, USER);

        registry.restoreForRetry(entry, SessionCloseReason.ROLE_CHANGED);

        assertThat(registry.countForUser(USER)).isEqualTo(1);
        assertThat(registry.takeExpired(clock.instant())).singleElement().satisfies(e -> {
            assertThat(e.deadlineReason()).isEqualTo(SessionCloseReason.ROLE_CHANGED);
            assertThat(e.deadline()).isEqualTo(T0);
        });
        assertThat(registry.userIndexSize()).isZero();
    }

    @Test
    @DisplayName("restoreForRetry nie wskrzesza wpisu sesji, ktora w miedzyczasie sie rozlaczyla (tombstone)")
    void restoreForRetryIgnoresDisconnectedSession() {
        Entry entry = registry.register("s1", USER, TENANT, null);
        registry.takeUser(TENANT, USER);
        registry.onDisconnect(disconnect("s1")); // brak wpisu -> tombstone

        registry.restoreForRetry(entry, SessionCloseReason.LOGOUT);

        assertThat(registry.size()).isZero();
        assertThat(registry.userIndexSize()).isZero();
    }

    private double gauge() {
        return meters.get(WebSocketSessionRegistry.GAUGE_ACTIVE).gauge().value();
    }

    private static SessionDisconnectEvent disconnect(String sessionId) {
        var accessor = org.springframework.messaging.simp.stomp.StompHeaderAccessor.create(
                org.springframework.messaging.simp.stomp.StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        var message = org.springframework.messaging.support.MessageBuilder.createMessage(new byte[0],
                accessor.getMessageHeaders());
        return new SessionDisconnectEvent("test", message, sessionId,
                org.springframework.web.socket.CloseStatus.NORMAL);
    }
}
