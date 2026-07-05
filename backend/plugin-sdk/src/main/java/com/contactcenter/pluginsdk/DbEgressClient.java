package com.contactcenter.pluginsdk;

import java.util.List;

/**
 * Restricted outbound database client exposed to a plugin via {@link PluginContext#dbClient()}.
 *
 * <p>This is the <b>only</b> way a plugin may reach an external database. A plugin never sees
 * the JDBC URL, username, or password used to connect — the host resolves all of that from the
 * installation's {@code installation_config} and manages the connection entirely on the
 * plugin's behalf, for the duration of a single call. Before every call, the host checks the
 * target {@code host:port} against this installation's declared {@code db:egress:<host>:<port>}
 * permissions (allow-list per host:port, both required); a target outside the allow-list is
 * rejected before any connection is attempted. None of that enforcement is part of this
 * interface's contract — a plugin author should simply expect calls to a database it did not
 * declare in its manifest to be rejected by the implementation, typically surfaced as a
 * {@link RuntimeException}.
 */
public interface DbEgressClient {

    /**
     * Executes a parameterized SQL update/insert/delete statement against the tenant-configured
     * external database.
     *
     * @param sql    a SQL statement using {@code ?} placeholders (standard
     *               {@code PreparedStatement} syntax) — never build the statement by
     *               concatenating untrusted values directly into {@code sql}
     * @param params values bound positionally to the {@code ?} placeholders, in order
     * @return the number of rows affected by the statement
     * @throws RuntimeException if the statement fails or the connection cannot be established;
     *                          the exact exception type is host-implementation-defined
     */
    int executeUpdate(String sql, List<Object> params);
}
