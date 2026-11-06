package com.contactcenter.domain.campaign;

import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.contactcenter.api.user.dto.AgentStatusChangedEvent;
import com.contactcenter.domain.user.AppUser.UserStatus;
import com.contactcenter.domain.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * BE-153: the native dialer UPDATE ({@code AVAILABLE/BUSY -> AFTER_CONTACT}) stamps
 * {@code app_user.status_changed_at}; other statuses are untouched. Real PostgreSQL.
 */
@DisplayName("Dialer AFTER_CONTACT - status_changed_at (BE-153)")
class DialerAfterContactStatusSinceIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static DialerCallbackHandlerImpl handler;
    private static final UserService userService = mock(UserService.class);

    @BeforeAll
    static void start() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        handler = new DialerCallbackHandlerImpl(mock(ScheduledCallbackRepository.class),
                mock(CampaignRepository.class), mock(StringRedisTemplate.class), jdbc, mock(TelephonyAdapter.class),
                userService);
    }

    @BeforeEach
    void resetMock() {
        Mockito.reset(userService);
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

    @Test
    @DisplayName("BE-165: AVAILABLE/BUSY -> AFTER_CONTACT publikuje event ze statusSince = status_changed_at z DB")
    void publishesEventWithPersistedStatusSince() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant dialer " + UUID.randomUUID());
        UUID busy = agent(tenantId, "BUSY");
        UUID available = agent(tenantId, "AVAILABLE");

        cleanup(busy);
        cleanup(available);

        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(userService).publishStatusChangedAfterCommit(
                Mockito.eq(busy), Mockito.eq(tenantId), Mockito.eq(UserStatus.BUSY),
                Mockito.eq(UserStatus.AFTER_CONTACT), sinceCaptor.capture());
        assertThat(sinceCaptor.getValue()).isEqualTo(since(busy));
        verify(userService).publishStatusChangedAfterCommit(
                Mockito.eq(available), Mockito.eq(tenantId), Mockito.eq(UserStatus.AVAILABLE),
                Mockito.eq(UserStatus.AFTER_CONTACT), any(Instant.class));

        // Wire format read by RabbitToWebSocketRelay (userId/tenantId/newStatus/statusSince) -> WS payload
        AgentStatusChangedEvent event = AgentStatusChangedEvent.of(busy, tenantId, UserStatus.BUSY,
                UserStatus.AFTER_CONTACT, sinceCaptor.getValue());
        Message wire = new Jackson2JsonMessageConverter().toMessage(event, new MessageProperties());
        java.util.Map<String, String> map = new ObjectMapper().readValue(wire.getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(map.get("userId")).isEqualTo(busy.toString());
        assertThat(map.get("newStatus")).isEqualTo("AFTER_CONTACT");
        assertThat(Instant.parse(map.get("statusSince"))).isEqualTo(since(busy));
    }

    @Test
    @DisplayName("BE-165/BE-166: UPDATE nie zmienil wiersza (juz AFTER_CONTACT / BREAK) -> brak eventu i statusSince bez zmian")
    void noEventWhenNoRowChanged() throws Exception {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant dialer " + UUID.randomUUID());
        UUID busy = agent(tenantId, "BUSY");
        UUID onBreak = agent(tenantId, "BREAK");

        cleanup(busy);                       // real transition -> 1 publish
        Instant first = since(busy);
        cleanup(busy);                       // already AFTER_CONTACT -> 0 rows
        cleanup(onBreak);                    // BREAK untouched

        verify(userService, Mockito.times(1)).publishStatusChangedAfterCommit(
                any(), any(), any(), any(), any());
        verify(userService, never()).publishStatusChangedAfterCommit(
                Mockito.eq(onBreak), any(), any(), any(), any());
        assertThat(since(busy)).isEqualTo(first);
    }
}
