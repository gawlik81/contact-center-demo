package com.contactcenter.infrastructure.lock;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import com.contactcenter.infrastructure.config.ShedLockConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-228: cleanup of orphaned dynamic lock rows ({@code RoutingQueue_<queueId>}, {@code EmailPolling_pollInbox_<tenantId>})
 * on a real PostgreSQL. Test names are unique per run, so rows of other tests sharing the database are untouched by the
 * assertions (the job itself deletes by prefix, hence the test only counts its own rows).
 */
@DisplayName("ShedLockOrphanCleanupJob - sprzątanie wierszy shedlock (BE-228)")
class ShedLockOrphanCleanupJobIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    private final UUID queueOld = UUID.randomUUID();
    private final UUID queueRecent = UUID.randomUUID();
    private final UUID queueHeld = UUID.randomUUID();
    private final UUID tenantOld = UUID.randomUUID();

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    @BeforeEach
    void seed() {
        // lock_until is a naive UTC TIMESTAMP: age is expressed against the database's UTC wall clock
        insert("RoutingQueue_" + queueOld, "now() - interval '30 days'");
        insert("RoutingQueue_" + queueRecent, "now() - interval '1 day'");
        insert("RoutingQueue_" + queueHeld, "now() + interval '1 hour'");
        insert("EmailPolling_pollInbox_" + tenantOld, "now() - interval '30 days'");
        insert("BE228CleanupStatic_run", "now() - interval '30 days'");
    }

    private static void insert(String name, String lockUntilExpr) {
        jdbc.update("DELETE FROM shedlock WHERE name = ?", name);
        jdbc.update("INSERT INTO shedlock(name, lock_until, locked_at, locked_by) VALUES (?, timezone('utc', "
                + lockUntilExpr + "), timezone('utc', now()), 'test')", name);
    }

    private static boolean exists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shedlock WHERE name = ?)",
                Boolean.class, name));
    }

    private ShedLockOrphanCleanupJob job(LockProvider provider, int retentionDays) {
        return new ShedLockOrphanCleanupJob(jdbc, provider, retentionDays);
    }

    @Test
    @DisplayName("usuwa tylko stare, niezablokowane wiersze dynamicznych rodzin; statyczne, świeże i trzymane zostają")
    void deletesOnlyOldDynamicRows() {
        int deleted = job(ShedLockConfig.meteredLockProvider(pool, new SimpleMeterRegistry()), 7).deleteOrphans();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(exists("RoutingQueue_" + queueOld)).as("stara kolejka - usunięta").isFalse();
        assertThat(exists("EmailPolling_pollInbox_" + tenantOld)).as("stara skrzynka - usunięta").isFalse();
        assertThat(exists("RoutingQueue_" + queueRecent)).as("użyta wczoraj - zostaje").isTrue();
        assertThat(exists("RoutingQueue_" + queueHeld)).as("trzymana (lock_until w przyszłości) - zostaje").isTrue();
        assertThat(exists("BE228CleanupStatic_run")).as("blokady statyczne nie są ruszane").isTrue();
        jdbc.update("DELETE FROM shedlock WHERE name IN (?, ?, ?)", "RoutingQueue_" + queueRecent,
                "RoutingQueue_" + queueHeld, "BE228CleanupStatic_run");
    }

    @Test
    @DisplayName("krótsza retencja obejmuje też wiersze sprzed doby; retencja < 1 wraca do domyślnych 7 dni")
    void retentionIsConfigurableAndSanitised() {
        job(ShedLockConfig.meteredLockProvider(pool, new SimpleMeterRegistry()), 1).deleteOrphans();
        assertThat(exists("RoutingQueue_" + queueRecent))
                .as("wiersz sprzed 1 doby (granica) jest już starszy niż 1 dzień").isFalse();

        insert("RoutingQueue_" + queueRecent, "now() - interval '1 day'");
        job(ShedLockConfig.meteredLockProvider(pool, new SimpleMeterRegistry()), 0).deleteOrphans();
        assertThat(exists("RoutingQueue_" + queueRecent)).as("0 -> 7 dni, wiersz sprzed doby zostaje").isTrue();
        jdbc.update("DELETE FROM shedlock WHERE name IN (?, ?, ?)", "RoutingQueue_" + queueRecent,
                "RoutingQueue_" + queueHeld, "BE228CleanupStatic_run");
    }

    @Test
    @DisplayName("po usunięciu wiersza aktywnej kolejki blokada działa dalej (provider wstawia brakujący wiersz) - także z cache providera")
    void lockOfARecreatedQueueStillWorksAfterCleanup() {
        MeteredLockProvider provider = ShedLockConfig.meteredLockProvider(pool, new SimpleMeterRegistry());
        String name = "RoutingQueue_" + queueOld;
        LockConfiguration cfg = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
        // the provider "remembers" that the row exists (weak cache) - the dangerous state for a deleted row
        provider.lock(cfg).orElseThrow().unlock();
        jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now() - interval '30 days') WHERE name = ?", name);

        assertThat(job(provider, 7).deleteOrphans()).isGreaterThanOrEqualTo(1);
        assertThat(exists(name)).isFalse();

        List<SimpleLock> relock = provider.lock(cfg).stream().toList();
        assertThat(relock).as("blokada założona mimo wcześniejszego cache i usuniętego wiersza").hasSize(1);
        relock.get(0).unlock();
        assertThat(exists(name)).isTrue();
        jdbc.update("DELETE FROM shedlock WHERE name IN (?, ?, ?)", name, "RoutingQueue_" + queueRecent,
                "RoutingQueue_" + queueHeld);
        jdbc.update("DELETE FROM shedlock WHERE name = 'BE228CleanupStatic_run'");
    }
}
