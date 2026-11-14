package com.contactcenter.infrastructure.lock;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

/**
 * Acquires the per-agent assignment advisory lock ({@link AdvisoryLockKeys#AGENT_ASSIGNMENT_LOCK_SQL}) with a BOUNDED
 * wait (BE-230 S5).
 *
 * <p>Without a limit a thread (RabbitMQ listener, ShedLock-held routing pass, dialer trigger) would wait for as long as
 * the holder keeps its transaction open. Here the wait is limited by {@code lock_timeout}; PostgreSQL then raises
 * {@code 55P03 lock_not_available}. That error would normally abort the whole surrounding transaction, so the
 * acquisition runs inside a SAVEPOINT that is rolled back on timeout: the caller gets {@code false}, the
 * transaction stays usable (and commits normally) and the caller reports "agent skipped".
 *
 * <p>The lock is transaction-scoped ({@code xact}), reentrant within one session, and the {@code lock_timeout}
 * is set with {@code set_config(..., is_local => true)} and RESTORED right after the acquisition, so it never leaks
 * onto the row locks of the writes that follow (nor onto the pooled connection).
 *
 * <p>Must be called inside an open transaction (a savepoint needs {@code autoCommit=false}).
 */
public final class AgentAssignmentLock {

    /** SQLSTATE of {@code lock_not_available} (raised when {@code lock_timeout} expires). */
    static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String READ_TIMEOUT_SQL = "SELECT current_setting('lock_timeout')";
    private static final String SET_TIMEOUT_SQL = "SELECT set_config('lock_timeout', ?, true)";

    private AgentAssignmentLock() {
    }

    /**
     * @param timeoutMs how long to wait for the lock; {@code <= 0} = a single try (1 ms is the smallest PostgreSQL
     *                  timeout that still waits at all, 0 would mean "wait forever")
     * @return {@code true} = lock held until the end of the transaction; {@code false} = another transaction held it
     *         for longer than the timeout (nothing was acquired, the transaction is still usable)
     */
    public static boolean tryAcquire(JdbcTemplate jdbcTemplate, UUID tenantId, UUID agentId, long timeoutMs) {
        long effectiveMs = Math.max(1, timeoutMs);
        Boolean acquired = jdbcTemplate.execute((ConnectionCallback<Boolean>) con -> acquire(con,
                AdvisoryLockKeys.agentAssignmentKey(tenantId, agentId), effectiveMs));
        return Boolean.TRUE.equals(acquired);
    }

    private static boolean acquire(Connection con, String key, long timeoutMs) throws SQLException {
        String previous = readString(con, READ_TIMEOUT_SQL);
        Savepoint savepoint = con.setSavepoint();
        try {
            setTimeout(con, timeoutMs + "ms");
            try (PreparedStatement ps = con.prepareStatement(AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL)) {
                ps.setString(1, key);
                ps.execute();
            }
            con.releaseSavepoint(savepoint);
            setTimeout(con, previous);
            return true;
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                // reverts the failed statement AND the SET LOCAL made inside the savepoint
                con.rollback(savepoint);
                return false;
            }
            throw e;
        }
    }

    private static String readString(Connection con, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void setTimeout(Connection con, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(SET_TIMEOUT_SQL)) {
            ps.setString(1, value);
            ps.execute();
        }
    }
}
