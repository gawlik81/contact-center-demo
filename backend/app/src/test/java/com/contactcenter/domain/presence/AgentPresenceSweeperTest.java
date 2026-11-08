package com.contactcenter.domain.presence;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.presence.AgentPresenceRegistry.Presence;
import com.contactcenter.domain.presence.AgentPresenceRegistry.AgentRef;
import com.contactcenter.domain.user.AgentPresenceCandidate;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BE-210: decision logic of the sweeper with mocked collaborators (fast, edge cases). */
@DisplayName("AgentPresenceSweeper - decision logic (BE-210)")
class AgentPresenceSweeperTest {

    private final UserService userService = mock(UserService.class);
    private final ContactService contactService = mock(ContactService.class);
    private final AgentPresenceRegistry registry = mock(AgentPresenceRegistry.class);
    private final AgentPresenceProperties props = new AgentPresenceProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MutableClock clock;
    private AgentPresenceSweeper sweeper;
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-08T12:00:00Z"));
        sweeper = new AgentPresenceSweeper(userService, contactService, registry, props, meters, clock);
        stubRegistry();
    }

    /** Presence returned by the mocked registry; agents without an entry are "absent, never seen". */
    private final Map<AgentRef, Presence> presences = new HashMap<>();
    private Instant epoch;

    private void stubRegistry() {
        org.mockito.Mockito.reset(registry);
        epoch = clock.instant().minus(Duration.ofDays(1));
        when(registry.ensureEpoch()).thenAnswer(inv -> epoch);
        when(registry.snapshots(any())).thenAnswer(inv -> {
            Map<AgentRef, Presence> result = new HashMap<>();
            for (AgentRef ref : inv.<java.util.Collection<AgentRef>>getArgument(0)) {
                result.put(ref, presences.getOrDefault(ref, new Presence(false, null)));
            }
            return result;
        });
        when(registry.snapshot(any(), any())).thenAnswer(inv ->
                presences.getOrDefault(new AgentRef(inv.getArgument(0), inv.getArgument(1)),
                        new Presence(false, null)));
    }

    private AgentPresenceCandidate agent(UserStatus status, Instant statusChangedAt, Instant lastLoginAt) {
        return new AgentPresenceCandidate(UUID.randomUUID(), tenant, status, statusChangedAt, lastLoginAt);
    }

    private void candidates(AgentPresenceCandidate... agents) {
        when(userService.findPresenceTrackedAgents()).thenReturn(List.of(agents));
    }

    private void absent(AgentPresenceCandidate a, Instant lastSeen) {
        presences.put(new AgentRef(a.tenantId(), a.id()), new Presence(false, lastSeen));
    }

    @Test
    @DisplayName("agent, ktory przed chwila zmienil status/zalogowal sie przez REST (WS jeszcze zamkniety), nie jest przelaczany")
    void freshStatusChangeOrLoginDelaysSwitch() {
        Instant old = clock.instant().minus(Duration.ofHours(5));
        AgentPresenceCandidate freshStatus = agent(UserStatus.AVAILABLE, clock.instant(), null);
        AgentPresenceCandidate freshLogin = agent(UserStatus.AVAILABLE, old, clock.instant());
        candidates(freshStatus, freshLogin);
        absent(freshStatus, null);
        absent(freshLogin, null);
        clock.advance(Duration.ofSeconds(80));

        assertThat(sweeper.sweep()).isZero();
        verify(userService, never()).markOfflineOnPresenceTimeout(any(), any(), any());

        clock.advance(Duration.ofSeconds(11));
        when(userService.markOfflineOnPresenceTimeout(any(), any(), any())).thenReturn(true);
        assertThat(sweeper.sweep()).isEqualTo(2);
    }

    @Test
    @DisplayName("blad odczytu z Redis = brak decyzji (zaden agent nie jest przelaczany, wyjatek nie wycieka)")
    void redisFailureSkipsPass() {
        AgentPresenceCandidate a = agent(UserStatus.AVAILABLE, clock.instant().minus(Duration.ofHours(1)), null);
        AgentPresenceCandidate b = agent(UserStatus.AVAILABLE, clock.instant().minus(Duration.ofHours(1)), null);
        candidates(a, b);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(registry).snapshots(any());
        clock.advance(Duration.ofSeconds(100));

        assertThat(sweeper.sweep()).isZero();

        verify(userService, never()).markOfflineOnPresenceTimeout(any(), any(), any());
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("po powrocie Redis karencja liczona od pierwszego udanego odczytu (nie masowy OFFLINE), potem OFFLINE")
    void graceRestartsFromRedisRecovery() {
        AgentPresenceCandidate a = agent(UserStatus.AVAILABLE, clock.instant().minus(Duration.ofHours(5)), null);
        candidates(a);
        absent(a, clock.instant().minus(Duration.ofHours(1))); // last-seen from before the outage
        when(userService.markOfflineOnPresenceTimeout(any(), any(), any())).thenReturn(true);
        clock.advance(Duration.ofMinutes(5));
        // outage: pass fails
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(registry).snapshots(any());
        assertThat(sweeper.sweep()).isZero();
        clock.advance(Duration.ofSeconds(30));
        stubRegistry(); // Redis is back (keys of before the outage are stale)

        assertThat(sweeper.sweep()).as("first pass after recovery").isZero();
        clock.advance(Duration.ofSeconds(89));
        assertThat(sweeper.sweep()).as("within grace measured from recovery").isZero();
        verify(userService, never()).markOfflineOnPresenceTimeout(any(), any(), any());

        clock.advance(Duration.ofSeconds(2));
        assertThat(sweeper.sweep()).as("no ping after the grace").isEqualTo(1);
    }

    @Test
    @DisplayName("sentinel epoki (Redis wyczyszczony): odniesienie = epoka, agent bez pingu dostaje pelna karencje od wykrycia")
    void freshEpochRestartsGrace() {
        AgentPresenceCandidate a = agent(UserStatus.AVAILABLE, clock.instant().minus(Duration.ofHours(5)), null);
        candidates(a);
        absent(a, null);
        when(userService.markOfflineOnPresenceTimeout(any(), any(), any())).thenReturn(true);
        clock.advance(Duration.ofMinutes(5));
        epoch = clock.instant(); // ensureEpoch() just recreated the sentinel

        assertThat(sweeper.sweep()).isZero();
        clock.advance(Duration.ofSeconds(89));
        assertThat(sweeper.sweep()).isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(sweeper.sweep()).isEqualTo(1);
    }

    @Test
    @DisplayName("przebieg startowy nie blokuje watku startu: idzie przez executor; rownolegly przebieg jest pomijany")
    void startupPassIsAsyncAndPassesDoNotOverlap() {
        List<Runnable> queued = new java.util.ArrayList<>();
        AgentPresenceSweeper async = new AgentPresenceSweeper(userService, contactService, registry, props, meters,
                clock, queued::add);
        candidates();

        async.onApplicationReady();

        assertThat(queued).hasSize(1);
        verify(userService, never()).findPresenceTrackedAgents();
        queued.get(0).run();
        verify(userService, times(1)).findPresenceTrackedAgents();

        when(userService.findPresenceTrackedAgents()).thenAnswer(inv -> {
            assertThat(async.sweep()).as("nested pass is skipped").isZero();
            return List.of();
        });
        async.sweep();
    }

    @Test
    @DisplayName("wyscig reconnect: obecnosc odczytana ponownie tuz przed zapisem -> brak zapisu")
    void reconnectBetweenDecisionAndWrite() {
        AgentPresenceCandidate a = agent(UserStatus.AVAILABLE, clock.instant().minus(Duration.ofHours(1)), null);
        candidates(a);
        absent(a, null);
        clock.advance(Duration.ofSeconds(100));
        // the agent pings between the batch read and the write: the targeted re-read sees the session
        when(registry.snapshot(a.tenantId(), a.id())).thenReturn(new Presence(true, clock.instant()));
        clock.advance(Duration.ofSeconds(100));

        assertThat(sweeper.sweep()).isZero();

        verify(userService, never()).markOfflineOnPresenceTimeout(any(), any(), any());
    }

    @Test
    @DisplayName("BUSY: kontakt PHONE sprawdzany dopiero po uplywie progu; z rozmowa -> bez zapisu")
    void busyChecksPhoneContactOnlyAfterTimeout() {
        AgentPresenceCandidate a = agent(UserStatus.BUSY, clock.instant().minus(Duration.ofHours(1)), null);
        candidates(a);
        absent(a, null);
        when(contactService.hasActivePhoneContact(tenant, a.id())).thenReturn(true);

        clock.advance(Duration.ofMinutes(5));
        assertThat(sweeper.sweep()).isZero();
        verify(contactService, never()).hasActivePhoneContact(any(), any());

        clock.advance(Duration.ofMinutes(6));
        assertThat(sweeper.sweep()).isZero();
        verify(contactService, times(1)).hasActivePhoneContact(tenant, a.id());
        verify(userService, never()).markOfflineOnPresenceTimeout(any(), any(), any());
    }

    @Test
    @DisplayName("metryka agent.presence.auto_offline{reason}: WS_DISCONNECT gdy widziano obecnosc, inaczej SWEEPER_NO_PRESENCE")
    void metricReasons() {
        Instant old = clock.instant().minus(Duration.ofHours(1));
        AgentPresenceCandidate seen = agent(UserStatus.AVAILABLE, old, null);
        AgentPresenceCandidate neverSeen = agent(UserStatus.AVAILABLE, old, null);
        candidates(seen, neverSeen);
        clock.advance(Duration.ofSeconds(10));
        absent(seen, clock.instant()); // last signal AFTER startup
        absent(neverSeen, null);
        when(userService.markOfflineOnPresenceTimeout(any(), any(), any())).thenReturn(true);
        clock.advance(Duration.ofSeconds(100));

        assertThat(sweeper.sweep()).isEqualTo(2);

        assertThat(meters.get(AgentPresenceSweeper.METRIC_AUTO_OFFLINE)
                .tag("reason", AgentPresenceSweeper.CAUSE_WS_DISCONNECT).counter().count()).isEqualTo(1.0);
        assertThat(meters.get(AgentPresenceSweeper.METRIC_AUTO_OFFLINE)
                .tag("reason", AgentPresenceSweeper.CAUSE_NO_PRESENCE).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("blad pobrania kandydatow nie propaguje wyjatku do schedulera")
    void candidateFailureIsContained() {
        when(userService.findPresenceTrackedAgents()).thenThrow(new IllegalStateException("db down"));

        assertThat(sweeper.sweep()).isZero();
    }
}
