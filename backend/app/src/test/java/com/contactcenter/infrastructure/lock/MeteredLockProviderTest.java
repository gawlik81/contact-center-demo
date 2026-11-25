package com.contactcenter.infrastructure.lock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-228: decorator of the ShedLock provider - metrics and unchanged behaviour (unit level, no database; the real
 * PostgreSQL provider is covered by {@code MeteredLockProviderIntegrationTest}).
 */
@DisplayName("MeteredLockProvider - metryki blokad ShedLock (BE-228)")
class MeteredLockProviderTest {

    private static final String OWN = "me:1:aaaa";

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private double attempts(String lock, String result) {
        var c = registry.find(MeteredLockProvider.METRIC_ATTEMPTS).tags("lock", lock, "result", result).counter();
        return c == null ? 0 : c.count();
    }

    private double expired(String lock, String outcome) {
        var c = registry.find(MeteredLockProvider.METRIC_EXPIRED).tags("lock", lock, "outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private static LockConfiguration config(String name, Duration atMost) {
        return new LockConfiguration(Instant.now(), name, atMost, Duration.ZERO);
    }

    private MeteredLockProvider provider(LockProvider delegate, MeteredLockProvider.LockOwnerReader reader) {
        return new MeteredLockProvider(delegate, registry, reader, OWN);
    }

    @Test
    @DisplayName("normalize: końcowy UUID jest odcinany (per kolejka/tenant), nazwy statyczne bez zmian")
    void normalizeDropsTrailingUuidOnly() {
        UUID id = UUID.randomUUID();
        assertThat(MeteredLockProvider.normalize("RoutingQueue_" + id)).isEqualTo("RoutingQueue");
        assertThat(MeteredLockProvider.normalize("EmailPolling_pollInbox_" + id)).isEqualTo("EmailPolling_pollInbox");
        assertThat(MeteredLockProvider.normalize("RefreshTokenCleanupJob_run")).isEqualTo("RefreshTokenCleanupJob_run");
        assertThat(MeteredLockProvider.normalize("Job_" + id + "_tail")).isEqualTo("Job_" + id + "_tail");
    }

    @Test
    @DisplayName("blokada założona -> acquired; tag to typ blokady, nie nazwa per kolejka")
    void acquiredIsCountedPerType() {
        MeteredLockProvider p = provider(c -> Optional.of(() -> { }), n -> Optional.of(OWN));

        p.lock(config("RoutingQueue_" + UUID.randomUUID(), Duration.ofMinutes(1)));
        p.lock(config("RoutingQueue_" + UUID.randomUUID(), Duration.ofMinutes(1)));

        assertThat(attempts("RoutingQueue", MeteredLockProvider.RESULT_ACQUIRED)).isEqualTo(2);
        assertThat(registry.find(MeteredLockProvider.METRIC_ATTEMPTS).meters())
                .as("jedna seria dla wszystkich kolejek (brak kardynalności per queueId)").hasSize(1);
    }

    @Test
    @DisplayName("blokada trzymana gdzie indziej -> busy i pusty Optional (przebieg pominięty)")
    void busyIsCounted() {
        MeteredLockProvider p = provider(c -> Optional.empty(), n -> Optional.empty());

        assertThat(p.lock(config("EtlSyncServiceImpl_runContactSync", Duration.ofMinutes(10)))).isEmpty();

        assertThat(attempts("EtlSyncServiceImpl_runContactSync", MeteredLockProvider.RESULT_BUSY)).isEqualTo(1);
        assertThat(attempts("EtlSyncServiceImpl_runContactSync", MeteredLockProvider.RESULT_ACQUIRED)).isZero();
    }

    @Test
    @DisplayName("błąd, który goły provider połyka (UncategorizedSQL/DataIntegrity), jest liczony jako error i nadal daje 'nie założono'")
    void swallowedProviderErrorIsCountedAndStillMeansNotObtained() {
        for (RuntimeException e : new RuntimeException[]{
                new org.springframework.jdbc.UncategorizedSQLException("lock", "UPDATE shedlock", new SQLException("boom")),
                new DataIntegrityViolationException("constraint")}) {
            MeteredLockProvider p = provider(c -> {
                throw e;
            }, n -> Optional.empty());

            assertThat(p.lock(config("J_run", Duration.ofMinutes(1)))).isEmpty();
        }

        assertThat(attempts("J_run", MeteredLockProvider.RESULT_ERROR)).isEqualTo(2);
    }

    @Test
    @DisplayName("BadSqlGrammarException (goły provider nie połykał go przy UPDATE) jest liczony i PRZEPROPAGOWANY")
    void badSqlGrammarIsRethrownAsBefore() {
        BadSqlGrammarException bad = new BadSqlGrammarException("lock", "UPDATE shedlock", new SQLException("boom"));
        MeteredLockProvider p = provider(c -> {
            throw bad;
        }, n -> Optional.empty());

        assertThatThrownBy(() -> p.lock(config("J_run", Duration.ofMinutes(1)))).isSameAs(bad);
        assertThat(attempts("J_run", MeteredLockProvider.RESULT_ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("pełny stack trace tylko przy pierwszym błędzie danego typu w oknie; inny typ ma własne okno")
    void fullTraceLoggedOncePerTypeAndWindow() {
        MeteredLockProvider p = provider(c -> Optional.empty(), n -> Optional.empty());

        assertThat(p.shouldLogFull("RoutingQueue")).isTrue();
        for (int i = 0; i < 40; i++) {
            assertThat(p.shouldLogFull("RoutingQueue")).isFalse();
        }
        assertThat(p.shouldLogFull("Other")).isTrue();
    }

    @Test
    @DisplayName("delegate() zwraca opakowany provider")
    void delegateIsExposed() {
        LockProvider inner = c -> Optional.empty();
        assertThat(provider(inner, n -> Optional.empty()).delegate()).isSameAs(inner);
    }

    @Test
    @DisplayName("pozostałe wyjątki (np. brak połączenia z DB) są liczone i PRZEPROPAGOWANE jak dotąd")
    void otherExceptionsAreCountedAndRethrown() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("db down");
        MeteredLockProvider p = provider(c -> {
            throw down;
        }, n -> Optional.empty());

        assertThatThrownBy(() -> p.lock(config("J_run", Duration.ofMinutes(1)))).isSameAs(down);
        assertThat(attempts("J_run", MeteredLockProvider.RESULT_ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("unlock w czasie < lockAtMostFor nie raportuje wygaśnięcia i nie czyta właściciela")
    void normalUnlockDoesNotProbe() {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger unlocks = new AtomicInteger();
        MeteredLockProvider p = provider(c -> Optional.of(unlocks::incrementAndGet), n -> {
            probes.incrementAndGet();
            return Optional.of(OWN);
        });

        p.lock(config("J_run", Duration.ofMinutes(5))).orElseThrow().unlock();

        assertThat(unlocks).hasValue(1);
        assertThat(probes).hasValue(0);
        assertThat(registry.find(MeteredLockProvider.METRIC_EXPIRED).meters()).isEmpty();
    }

    @Test
    @DisplayName("job trwał dłużej niż lockAtMostFor: właściciel wiersza to ktoś inny -> expired{taken_over}")
    void expiredAndTakenOver() throws Exception {
        AtomicInteger unlocks = new AtomicInteger();
        MeteredLockProvider p = provider(c -> Optional.of(unlocks::incrementAndGet), n -> Optional.of("other:2:bbbb"));

        SimpleLock lock = p.lock(config("J_run", Duration.ofMillis(20))).orElseThrow();
        Thread.sleep(60);
        lock.unlock();

        assertThat(expired("J_run", MeteredLockProvider.OUTCOME_TAKEN_OVER)).isEqualTo(1);
        assertThat(unlocks).as("unlock delegata nadal wykonany").hasValue(1);
    }

    @Test
    @DisplayName("wygasła, ale nikt nie przejął (właściciel = my) -> not_taken; brak wiersza / błąd odczytu -> unknown")
    void expiredNotTakenAndUnknown() throws Exception {
        MeteredLockProvider mine = provider(c -> Optional.of(() -> { }), n -> Optional.of(OWN));
        SimpleLock a = mine.lock(config("J_a", Duration.ofMillis(20))).orElseThrow();
        Thread.sleep(60);
        a.unlock();

        MeteredLockProvider gone = provider(c -> Optional.of(() -> { }), n -> Optional.empty());
        SimpleLock b = gone.lock(config("J_b", Duration.ofMillis(20))).orElseThrow();
        Thread.sleep(60);
        b.unlock();

        MeteredLockProvider failing = provider(c -> Optional.of(() -> { }), n -> {
            throw new DataAccessResourceFailureException("db down");
        });
        SimpleLock c = failing.lock(config("J_c", Duration.ofMillis(20))).orElseThrow();
        Thread.sleep(60);
        c.unlock(); // an unreadable owner must never break the unlock

        assertThat(expired("J_a", MeteredLockProvider.OUTCOME_NOT_TAKEN)).isEqualTo(1);
        assertThat(expired("J_b", MeteredLockProvider.OUTCOME_UNKNOWN)).isEqualTo(1);
        assertThat(expired("J_c", MeteredLockProvider.OUTCOME_UNKNOWN)).isEqualTo(1);
    }

    @Test
    @DisplayName("strażnik kardynalności: powyżej MAX_LOCK_TYPES nowe typy zbiera tag 'other'")
    void cardinalityGuardCollapsesOverflowTypes() {
        MeteredLockProvider p = provider(c -> Optional.empty(), n -> Optional.empty());

        for (int i = 0; i < MeteredLockProvider.MAX_LOCK_TYPES + 20; i++) {
            p.lock(config("Type" + i + "_run", Duration.ofMinutes(1)));
        }

        assertThat(registry.find(MeteredLockProvider.METRIC_ATTEMPTS).meters())
                .hasSize(MeteredLockProvider.MAX_LOCK_TYPES + 1);
        assertThat(attempts(MeteredLockProvider.OTHER, MeteredLockProvider.RESULT_BUSY)).isEqualTo(20);
    }
}
