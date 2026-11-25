package com.contactcenter.infrastructure.lock;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-228: {@link MeteredLockProvider} over the REAL JDBC provider and PostgreSQL (Testcontainers, V149). Two providers
 * with separate pools and DISTINCT {@code locked_by} stand for two application instances (in production each JVM has
 * its own {@code host:pid:uuid}).
 */
@DisplayName("MeteredLockProvider na prawdziwym PostgreSQL (BE-228)")
class MeteredLockProviderIntegrationTest {

    private static final String A = "instance-A:1:aaaa";
    private static final String B = "instance-B:2:bbbb";
    private static final String PREFIX = "BE228_";

    private static HikariDataSource poolA;
    private static HikariDataSource poolB;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        poolA = PostgresTestDatabase.superuserPool(3);
        poolB = PostgresTestDatabase.superuserPool(3);
        jdbc = new JdbcTemplate(poolA);
    }

    @AfterAll
    static void stop() {
        jdbc.update("DELETE FROM shedlock WHERE name LIKE 'BE228\\_%'");
        poolA.close();
        poolB.close();
    }

    private static MeteredLockProvider instance(HikariDataSource pool, String lockedBy, String table,
                                                MeterRegistry registry) {
        JdbcTemplate reader = new JdbcTemplate(pool);
        JdbcTemplateLockProvider raw = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(pool))
                .withTableName(table)
                .withLockedByValue(lockedBy)
                .withThrowUnexpectedException(true)
                .usingDbTime()
                .build());
        return new MeteredLockProvider(raw, registry,
                name -> reader.query("SELECT locked_by FROM shedlock WHERE name = ?", (rs, i) -> rs.getString(1), name)
                        .stream().findFirst(),
                lockedBy);
    }

    private static LockConfiguration config(String name, Duration atMost) {
        return new LockConfiguration(Instant.now(), name, atMost, Duration.ZERO);
    }

    private static double count(MeterRegistry registry, String metric, String lock, String tag, String value) {
        var c = registry.find(metric).tags("lock", lock, tag, value).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("acquired, potem busy dla drugiej instancji; po unlock znów acquired")
    void acquiredThenBusyThenAcquiredAgain() {
        MeterRegistry reg = new SimpleMeterRegistry();
        MeteredLockProvider a = instance(poolA, A, "shedlock", reg);
        MeteredLockProvider b = instance(poolB, B, "shedlock", reg);
        String name = PREFIX + "busy_" + UUID.randomUUID();

        SimpleLock lockA = a.lock(config(name, Duration.ofMinutes(1))).orElseThrow();
        assertThat(b.lock(config(name, Duration.ofMinutes(1)))).as("trzymana przez A").isEmpty();
        lockA.unlock();
        Optional<SimpleLock> afterUnlock = b.lock(config(name, Duration.ofMinutes(1)));
        assertThat(afterUnlock).isPresent();
        afterUnlock.get().unlock();

        String type = MeteredLockProvider.normalize(name);
        assertThat(count(reg, MeteredLockProvider.METRIC_ATTEMPTS, type, "result", "acquired")).isEqualTo(2);
        assertThat(count(reg, MeteredLockProvider.METRIC_ATTEMPTS, type, "result", "busy")).isEqualTo(1);
    }

    @Test
    @DisplayName("blokada wygasła pod trwającym jobem i została przejęta: właściciel widzi expired{taken_over}, a jego unlock nie zwalnia cudzej blokady")
    void expiredUnderRunningJobAndTakenOverByAnotherInstance() throws Exception {
        MeterRegistry regA = new SimpleMeterRegistry();
        MeterRegistry regB = new SimpleMeterRegistry();
        MeteredLockProvider a = instance(poolA, A, "shedlock", regA);
        MeteredLockProvider b = instance(poolB, B, "shedlock", regB);
        String name = PREFIX + "takeover_" + UUID.randomUUID();

        SimpleLock lockA = a.lock(config(name, Duration.ofMillis(400))).orElseThrow();
        Thread.sleep(700); // the job of A is still running, its lock has expired
        SimpleLock lockB = b.lock(config(name, Duration.ofMinutes(5))).orElseThrow(); // B takes over
        lockA.unlock(); // A finishes late

        assertThat(count(regA, MeteredLockProvider.METRIC_EXPIRED, MeteredLockProvider.normalize(name), "outcome", "taken_over")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT locked_by FROM shedlock WHERE name = ?", String.class, name))
                .as("późny unlock A nie ruszył blokady przejętej przez B").isEqualTo(B);
        assertThat(count(regB, MeteredLockProvider.METRIC_EXPIRED, MeteredLockProvider.normalize(name), "outcome", "taken_over")).isZero();
        lockB.unlock();
    }

    @Test
    @DisplayName("wygasła, ale nikt nie przejął: expired{not_taken}")
    void expiredButNotTaken() throws Exception {
        MeterRegistry reg = new SimpleMeterRegistry();
        MeteredLockProvider a = instance(poolA, A, "shedlock", reg);
        String name = PREFIX + "nottaken_" + UUID.randomUUID();

        SimpleLock lock = a.lock(config(name, Duration.ofMillis(300))).orElseThrow();
        Thread.sleep(600);
        lock.unlock();

        assertThat(count(reg, MeteredLockProvider.METRIC_EXPIRED, MeteredLockProvider.normalize(name), "outcome", "not_taken")).isEqualTo(1);
    }

    @Test
    @DisplayName("brak tabeli blokad: BadSqlGrammar propaguje się jak przy gołym providerze (UPDATE nie jest połykany) i jest liczony jako error")
    void missingTableIsCountedAndRethrown() {
        MeterRegistry reg = new SimpleMeterRegistry();
        MeteredLockProvider broken = instance(poolA, A, "shedlock_does_not_exist", reg);
        String name = PREFIX + "error_" + UUID.randomUUID();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> broken.lock(config(name, Duration.ofMinutes(1))))
                .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);

        assertThat(count(reg, MeteredLockProvider.METRIC_ATTEMPTS, MeteredLockProvider.normalize(name), "result", "error"))
                .isEqualTo(1);
    }
}
