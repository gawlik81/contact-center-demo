package com.contactcenter.domain.tenant;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantStatusGate (BE-249): lokalny cache TTL 30 s, SUPER_ADMIN pominiety, inwalidacja, reaktywacja")
class TenantStatusGateTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final Map<UUID, TenantStatus> db = new ConcurrentHashMap<>();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicLong nanos = new AtomicLong();
    private TenantStatusGate gate;
    private boolean failLookup;

    @BeforeEach
    void setUp() {
        Ticker ticker = nanos::get;
        gate = new TenantStatusGate(id -> {
            lookups.incrementAndGet();
            if (failLookup) {
                throw new IllegalStateException("db down");
            }
            return Optional.ofNullable(db.get(id));
        }, Duration.ofSeconds(30), ticker);
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    @DisplayName("INACTIVE blokuje, ACTIVE i SUSPENDED nie; nieznany tenant nie jest blokowany (jak przed BE-249)")
    void onlyInactiveBlocks() {
        UUID active = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        db.put(TENANT, TenantStatus.INACTIVE);
        db.put(active, TenantStatus.ACTIVE);
        db.put(suspended, TenantStatus.SUSPENDED);

        assertThat(gate.isBlocked(TENANT)).isTrue();
        assertThat(gate.isBlocked(active)).isFalse();
        assertThat(gate.isBlocked(suspended)).isFalse();
        assertThat(gate.isBlocked(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("SUPER_ADMIN (tenantId null) jest pomijany bez odczytu zrodla")
    void nullTenantIsNeverBlockedAndNeverLooksUp() {
        assertThat(gate.isBlocked(null)).isFalse();
        assertThat(lookups.get()).isZero();
    }

    @Test
    @DisplayName("wynik jest cache'owany przez TTL (1 odczyt), po TTL odczytywany ponownie; reaktywacja widoczna po TTL")
    void cachesForTtlThenReloads() {
        db.put(TENANT, TenantStatus.INACTIVE);
        assertThat(gate.isBlocked(TENANT)).isTrue();
        assertThat(gate.isBlocked(TENANT)).isTrue();
        assertThat(lookups.get()).isEqualTo(1);

        db.put(TENANT, TenantStatus.ACTIVE); // reaktywacja w bazie (np. na innej instancji)
        advance(Duration.ofSeconds(29));
        assertThat(gate.isBlocked(TENANT)).as("przed TTL nadal stary werdykt").isTrue();
        advance(Duration.ofSeconds(2));
        assertThat(gate.isBlocked(TENANT)).as("po TTL dostep przywrocony").isFalse();
        assertThat(lookups.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidate: natychmiastowa zmiana werdyktu na tej instancji (updateTenant), bez czekania na TTL")
    void invalidateMakesChangeImmediate() {
        db.put(TENANT, TenantStatus.ACTIVE);
        assertThat(gate.isBlocked(TENANT)).isFalse();

        db.put(TENANT, TenantStatus.INACTIVE);
        gate.invalidate(TENANT);
        assertThat(gate.isBlocked(TENANT)).isTrue();

        db.put(TENANT, TenantStatus.ACTIVE);
        gate.invalidate(TENANT);
        assertThat(gate.isBlocked(TENANT)).as("reaktywacja przywraca dostep od razu").isFalse();
    }

    @Test
    @DisplayName("invalidateAroundCommit: w transakcji unieważnia ROWNIEZ po commicie (czytelnik z okna miedzy zmiana a commitem)")
    void invalidateAroundCommitInvalidatesAgainAfterCommit() {
        db.put(TENANT, TenantStatus.ACTIVE);
        TransactionSynchronizationManager.initSynchronization();
        try {
            db.put(TENANT, TenantStatus.INACTIVE);
            gate.invalidateAroundCommit(TENANT);
            // a concurrent reader still sees the OLD committed state and caches it
            db.put(TENANT, TenantStatus.ACTIVE);
            assertThat(gate.isBlocked(TENANT)).isFalse();
            db.put(TENANT, TenantStatus.INACTIVE); // the commit
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            assertThat(gate.isBlocked(TENANT)).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("awaria zrodla propaguje wyjatek (wolajacy sa fail-closed) i nie zatruwa cache")
    void lookupFailurePropagatesAndIsNotCached() {
        db.put(TENANT, TenantStatus.INACTIVE);
        failLookup = true;
        assertThatThrownBy(() -> gate.isBlocked(TENANT)).isInstanceOf(RuntimeException.class);

        failLookup = false;
        assertThat(gate.isBlocked(TENANT)).isTrue();
    }
}
