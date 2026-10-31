package com.contactcenter.domain.user;

import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test BE-122 {@link RefreshTokenCleanupJob} na PRAWDZIWYM PostgreSQL (Testcontainers, Flyway)
 * z realnym JPQL {@link RefreshTokenRepository#deleteExpiredAndRevoked} (WP-1) - mock nie wykryłby
 * błędu w warunku {@code OR (... AND ...)}.
 *
 * <p>Semantyka: cutoff = now - 7 dni; kasowane: wygasły przed cutoff, unieważniony wystawiony
 * przed cutoff. Zostają: aktywny, wygasły w grace, świeżo unieważniony.
 */
@DisplayName("RefreshTokenCleanupJob - integracja na prawdziwej bazie (BE-122)")
class RefreshTokenCleanupJobIntegrationTest {

    private static final int GRACE_DAYS = 7;

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static RefreshTokenRepository repository;

    /** Pusta konfiguracja - JpaTestContext wymaga co najmniej jednej klasy komponentu. */
    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private UUID tenantId;
    private UUID userId;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{RefreshToken.class},
                new Class<?>[]{NoOpConfig.class},
                null);
        EntityManagerFactory emf = ctx.getBean(EntityManagerFactory.class);
        repository = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                .getRepository(RefreshTokenRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        TenantContext.clear(); // WP-2: pusty kontekst
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-122 " + UUID.randomUUID());
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) "
                + "VALUES (?, ?, 'AGENT', ?, 'x')", userId, tenantId, "be122-" + userId + "@test.local");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    }

    private RefreshTokenCleanupJob job() {
        return new RefreshTokenCleanupJob(repository, ctx.getBean(PlatformTransactionManager.class), GRACE_DAYS);
    }

    @Test
    @DisplayName("usuwa dokładnie tokeny przewidziane semantyką: wygasły>grace i unieważniony>grace; reszta zostaje")
    void run_deletesExactlyExpectedTokens() {
        Instant now = Instant.now();
        Instant old = now.minus(Duration.ofDays(GRACE_DAYS + 3));
        Instant fresh = now.minus(Duration.ofDays(1));

        UUID active = insert(tenantId, false, now.plus(Duration.ofDays(5)), fresh);
        UUID expiredInGrace = insert(tenantId, false, now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(9)));
        UUID expiredBeyondGrace = insert(tenantId, false, old, now.minus(Duration.ofDays(20)));
        UUID revokedFresh = insert(tenantId, true, now.plus(Duration.ofDays(6)), fresh);
        UUID revokedOld = insert(tenantId, true, now.plus(Duration.ofDays(1)), old);
        UUID nullTenantExpired = insert(null, true, old, old);
        UUID nullTenantActive = insert(null, false, now.plus(Duration.ofDays(5)), fresh);

        job().run();

        assertThat(remaining()).containsExactlyInAnyOrder(
                active, expiredInGrace, revokedFresh, nullTenantActive);
        assertThat(remaining()).doesNotContain(expiredBeyondGrace, revokedOld, nullTenantExpired);
    }

    @Test
    @DisplayName("idempotentny: drugi przebieg nic nie usuwa, nie rzuca")
    void run_isIdempotent() {
        Instant now = Instant.now();
        insert(tenantId, true, now.plusSeconds(3600), now.minus(Duration.ofDays(30)));
        UUID keep = insert(tenantId, false, now.plus(Duration.ofDays(5)), now);

        job().run();
        job().run();

        assertThat(remaining()).containsExactly(keep);
    }

    @Test
    @DisplayName("wyjątek repozytorium nie crashuje schedulera (nie jest propagowany)")
    void run_repositoryFailure_isSwallowed() {
        RefreshTokenRepository failing = mock(RefreshTokenRepository.class);
        when(failing.deleteExpiredAndRevoked(any())).thenThrow(new IllegalStateException("db down"));
        RefreshTokenCleanupJob job = new RefreshTokenCleanupJob(
                failing, ctx.getBean(PlatformTransactionManager.class), GRACE_DAYS);

        assertThatNoException().isThrownBy(job::run);
    }

    @Test
    @DisplayName("ujemny grace-days -> fallback do domyślnego (7), job działa")
    void negativeGraceDays_fallsBackToDefault() {
        Instant now = Instant.now();
        UUID revokedFresh = insert(tenantId, true, now.plusSeconds(3600), now.minus(Duration.ofDays(1)));

        new RefreshTokenCleanupJob(repository, ctx.getBean(PlatformTransactionManager.class), -5).run();

        assertThat(remaining()).contains(revokedFresh);
    }

    // ------------------------------------------------------------------------------------------

    private UUID insert(UUID tenant, boolean revoked, Instant expiresAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO refresh_token (token_id, user_id, tenant_id, token_hash, expires_at, is_revoked, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, userId, tenant, id.toString(), Timestamp.from(expiresAt), revoked, Timestamp.from(createdAt));
        return id;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("SELECT token_id FROM refresh_token WHERE user_id = ?", UUID.class, userId);
    }
}
