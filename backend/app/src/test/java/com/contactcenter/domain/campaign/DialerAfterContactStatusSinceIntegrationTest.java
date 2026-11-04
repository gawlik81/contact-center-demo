package com.contactcenter.domain.campaign;

import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * BE-153: the native dialer UPDATE ({@code AVAILABLE/BUSY -> AFTER_CONTACT}) stamps
 * {@code app_user.status_changed_at}; other statuses are untouched. Real PostgreSQL.
 */
@DisplayName("Dialer AFTER_CONTACT - status_changed_at (BE-153)")
class DialerAfterContactStatusSinceIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static DialerCallbackHandlerImpl handler;

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        handler = new DialerCallbackHandlerImpl(mock(ScheduledCallbackRepository.class),
                mock(CampaignRepository.class), mock(StringRedisTemplate.class), jdbc, mock(TelephonyAdapter.class));
    }

    @AfterAll
    static void stop() {
        pool.close();
    }

    private UUID agent(UUID tenantId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, status) "
                        + "VALUES (?, ?, 'AGENT', ?, ?, ?)", id, tenantId, "d-" + id + "@test.local",
                "h".repeat(60), status);
        return id;
    }

    private void cleanup(UUID agentId) throws Exception {
        Method m = DialerCallbackHandlerImpl.class.getDeclaredMethod("cleanupRedisKeys", String.class, UUID.class);
        m.setAccessible(true);
        m.invoke(handler, null, agentId);
    }

    private Instant since(UUID id) {
        Timestamp ts = jdbc.queryForObject("SELECT status_changed_at FROM app_user WHERE user_id = ?",
                Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    @DisplayName("BUSY -> AFTER_CONTACT ustawia status_changed_at; BREAK pozostaje nietkniety")
    void stampsOnlyRealTransition() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant dialer " + UUID.randomUUID());
        UUID busy = agent(tenantId, "BUSY");
        UUID onBreak = agent(tenantId, "BREAK");
        Instant before = Instant.now().minusSeconds(5);

        cleanup(busy);
        cleanup(onBreak);

        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, busy))
                .isEqualTo("AFTER_CONTACT");
        assertThat(since(busy)).isNotNull().isAfter(before);
        assertThat(jdbc.queryForObject("SELECT status FROM app_user WHERE user_id = ?", String.class, onBreak))
                .isEqualTo("BREAK");
        assertThat(since(onBreak)).isNull();
    }
}
