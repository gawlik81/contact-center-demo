package com.contactcenter.domain.user;

import com.contactcenter.api.auth.dto.ChangePasswordRequest;
import com.contactcenter.api.auth.dto.LoginRequest;
import com.contactcenter.api.auth.dto.LoginResponse;
import com.contactcenter.api.auth.dto.RefreshRequest;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import com.contactcenter.security.AppUserDetails;
import com.contactcenter.security.JwtParser;
import com.contactcenter.security.JwtService;
import com.contactcenter.security.LoginRateLimiter;
import com.contactcenter.security.MfaService;
import com.contactcenter.security.PendingLoginTracker;
import com.contactcenter.security.TokenBlacklistService;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-158 on a REAL PostgreSQL (Testcontainers + Flyway): {@code updateLastLoginAt} (a {@code @Modifying}
 * query with {@code clearAutomatically}) runs in the same transaction as the refresh-token INSERT.
 * Without {@code flushAutomatically} the clear would drop the not-yet-flushed refresh token, so a mock-based
 * test cannot see it. Real: AuthServiceImpl (with the {@code @Transactional} proxy), AppUserRepository,
 * RefreshTokenRepository, Hibernate. Mocked: JWT, MFA, Redis-backed collaborators.
 */
@DisplayName("AuthService.lastLoginAt - integracja na prawdziwej bazie (BE-158)")
class AuthServiceLastLoginIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static AuthService authService;
    private static final AtomicInteger TOKEN_SEQ = new AtomicInteger();
    private static final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private static final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @org.springframework.context.annotation.Configuration
    static class NoOpConfig {
    }

    private UUID tenantId;
    private UUID userId;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);

        JwtService jwtService = mock(JwtService.class);
        when(jwtService.issueAccessToken(any(), any(), anyBoolean())).thenReturn("access");
        when(jwtService.generateRefreshTokenValue())
                .thenAnswer(inv -> "rt-" + UUID.randomUUID() + "-" + TOKEN_SEQ.incrementAndGet());
        when(jwtService.refreshTokenExpiresAt()).thenAnswer(inv -> Instant.now().plusSeconds(3600));
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.findTenantEntity(any()))
                .thenReturn(Optional.of(Tenant.builder().id(UUID.randomUUID()).name("T").build()));

        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{AppUser.class, RefreshToken.class},
                new Class<?>[]{NoOpConfig.class},
                c -> {
                    EntityManagerFactory emf = c.getBeanFactory().getBean(EntityManagerFactory.class);
                    JpaRepositoryFactory factory = new JpaRepositoryFactory(
                            SharedEntityManagerCreator.createSharedEntityManager(emf));
                    AppUserRepository users = factory.getRepository(AppUserRepository.class);
                    RefreshTokenRepository tokens = factory.getRepository(RefreshTokenRepository.class);
                    // registerBean so the @Transactional proxy (EnableTransactionManagement) wraps the service
                    c.registerBean("authService", AuthServiceImpl.class, () -> new AuthServiceImpl(
                            authenticationManager, jwtService, mock(JwtParser.class),
                            mock(TokenBlacklistService.class), mock(MfaService.class), users, tokens,
                            tenantService, passwordEncoder, mock(LoginRateLimiter.class),
                            mock(UserService.class), mock(PendingLoginTracker.class)));
                });
        authService = ctx.getBean(AuthService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-158 " + UUID.randomUUID());
        userId = UUID.randomUUID();
    }

    private void insertUser(boolean passwordResetRequired) {
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, "
                        + "password_reset_required) VALUES (?, ?, 'AGENT', ?, ?, ?)",
                userId, tenantId, "be158-" + userId + "@test.local", "h".repeat(60), passwordResetRequired);
    }

    private void authenticateAsUser() {
        AppUser principal = AppUser.builder().id(userId).tenantId(tenantId).email("x@test.local")
                .passwordHash("h".repeat(60)).role(AppUser.UserRole.AGENT).active(true).build();
        AppUserDetails details = new AppUserDetails(principal);
        when(authenticationManager.authenticate(any())).thenReturn(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private Integer refreshTokenRows(String value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE token_hash = ? AND user_id = ?",
                Integer.class, value, userId);
    }

    private Timestamp lastLoginAt() {
        return jdbc.queryForObject("SELECT last_login_at FROM app_user WHERE user_id = ?",
                Timestamp.class, userId);
    }

    @Test
    @DisplayName("login bez MFA: refresh token ISTNIEJE w bazie, /refresh dziala, last_login_at ustawione")
    void login_persistsRefreshTokenAndLastLogin() {
        insertUser(false);
        authenticateAsUser();
        assertThat(lastLoginAt()).isNull();
        Instant before = Instant.now().minusSeconds(2);

        LoginResponse login = authService.login(
                new LoginRequest(tenantId.toString(), "x@test.local", "pw"), "1.1.1.1");

        assertThat(refreshTokenRows(login.refreshToken())).isEqualTo(1);
        assertThat(lastLoginAt()).isNotNull();
        assertThat(lastLoginAt().toInstant()).isAfterOrEqualTo(before);

        LoginResponse refreshed = authService.refresh(new RefreshRequest(login.refreshToken()));

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshTokenRows(refreshed.refreshToken())).isEqualTo(1);
        // refresh does not touch last_login_at
        Timestamp afterRefresh = lastLoginAt();
        assertThat(afterRefresh.toInstant()).isAfterOrEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT is_revoked FROM refresh_token WHERE token_hash = ?",
                Boolean.class, login.refreshToken())).isTrue();
    }

    @Test
    @DisplayName("refresh nie zmienia last_login_at")
    void refresh_doesNotChangeLastLogin() {
        insertUser(false);
        authenticateAsUser();
        LoginResponse login = authService.login(
                new LoginRequest(tenantId.toString(), "x@test.local", "pw"), "1.1.1.1");
        Timestamp afterLogin = lastLoginAt();

        authService.refresh(new RefreshRequest(login.refreshToken()));

        assertThat(lastLoginAt()).isEqualTo(afterLogin);
    }

    @Test
    @DisplayName("wymuszony reset hasla: changePassword zapisuje nowy refresh token i last_login_at")
    void forcedReset_changePassword_persistsRefreshTokenAndLastLogin() {
        insertUser(true);
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE user_id = ?",
                String.class, userId);
        when(passwordEncoder.matches("old", oldHash)).thenReturn(true);
        when(passwordEncoder.encode("NewPass2")).thenReturn("n".repeat(60));
        assertThat(lastLoginAt()).isNull();

        LoginResponse response = authService.changePassword(userId, tenantId,
                new ChangePasswordRequest("old", "NewPass2"), null);

        assertThat(refreshTokenRows(response.refreshToken())).isEqualTo(1);
        assertThat(lastLoginAt()).isNotNull();
        assertThat(authService.refresh(new RefreshRequest(response.refreshToken()))).isNotNull();
    }
}
