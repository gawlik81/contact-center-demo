package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.DbEgressClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Implementacja {@link DbEgressClient} z wymuszeniem allow-list per {@code host:port}, analogicznie
 * do {@link PluginHttpEgressClientImpl} dla HTTP.
 *
 * <p>Allow-list pochodzi z {@code grantedPermissions} tej instalacji — uprawnienia w formie
 * {@code db:egress:<host>:<port>} (kontrakt zdefiniowany i zwalidowany w {@code PluginPermission}).
 * Host:port poza allow-listą skutkuje {@link SecurityException}, sprawdzaną PRZED jakimkolwiek
 * nawiązaniem połączenia (nawet przejściowym) — kontrakt SDK ("typically surfaced as a
 * RuntimeException").
 *
 * <p>Dane połączenia ({@code jdbcUrl}/{@code dbUsername}/{@code dbPassword}) pochodzą z
 * {@code installation_config} tej instalacji (już odszyfrowanego — ta klasa nie wie nic o
 * szyfrowaniu, identycznie jak {@link PluginConfigImpl}). Plugin nigdy nie widzi tych wartości —
 * tylko host zna JDBC URL/credentiale, plugin podaje wyłącznie SQL + parametry.
 *
 * <p><strong>Brak connection pooling jest świadomym uproszczeniem</strong> — każde wywołanie
 * {@link #executeUpdate(String, List)} otwiera i zamyka własne połączenie przez
 * {@link DriverManager} (fire-and-forget, niska częstotliwość oczekiwana dla tego use-case'u:
 * {@code POST_CONTACT_END}/{@code DISPOSITION_SET}, fire-and-forget extension pointy). Pooling
 * (np. HikariCP) jest naturalnym follow-upem, jeśli wolumen wywołań to uzasadni — nie jest
 * blokerem teraz.
 */
@Slf4j
final class PluginDbEgressClientImpl implements DbEgressClient {

    private static final String EGRESS_PERMISSION_PREFIX = "db:egress:";

    private final Set<String> allowedHostPorts;
    private final UUID tenantId;
    private final String pluginKey;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;

    /**
     * @param grantedPermissions uprawnienia zatwierdzone przy instalacji (do allow-listy egress)
     * @param tenantId           tenant wywołujący — wyłącznie do logowania/diagnostyki
     * @param pluginKey          identyfikator pluginu — wyłącznie do logowania/diagnostyki
     * @param jdbcUrl            JDBC URL skonfigurowany przez administratora tenanta w
     *                           {@code installation_config} (np. {@code jdbcUrl})
     * @param dbUsername         nazwa użytkownika bazy z {@code installation_config}
     * @param dbPassword         hasło bazy z {@code installation_config} — nigdy nie loguj go
     */
    PluginDbEgressClientImpl(
            List<String> grantedPermissions,
            UUID tenantId,
            String pluginKey,
            String jdbcUrl,
            String dbUsername,
            String dbPassword) {
        this.allowedHostPorts = extractAllowedHostPorts(grantedPermissions);
        this.tenantId = tenantId;
        this.pluginKey = pluginKey;
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    @Override
    public int executeUpdate(String sql, List<Object> params) {
        assertHostAllowed();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> boundParams = params != null ? params : List.of();
            for (int i = 0; i < boundParams.size(); i++) {
                statement.setObject(i + 1, boundParams.get(i));
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            // Nigdy nie loguj dbPassword w plaintext — tylko tenantId/pluginKey/host do diagnostyki.
            log.warn("[PluginEgress][Db] Zapytanie SQL nie powiodło się: tenant={}, plugin={}, error={}",
                    tenantId, pluginKey, e.getMessage());
            throw new RuntimeException(
                    "Zapytanie DB egress pluginu nie powiodło się: tenant=" + tenantId
                            + ", plugin=" + pluginKey + ", error=" + e.getMessage(), e);
        }
    }

    private void assertHostAllowed() {
        String hostPort = extractHostPort(jdbcUrl);
        if (hostPort == null || !allowedHostPorts.contains(hostPort)) {
            log.warn("[PluginEgress][Db][Security] Odmowa egress poza allow-listą: tenant={}, plugin={}, "
                            + "hostPort={}, allowedHostPorts={}",
                    tenantId, pluginKey, hostPort, allowedHostPorts);
            throw new SecurityException(
                    "Host:port '" + hostPort + "' nie jest w allow-liście db egress tej instalacji pluginu");
        }
    }

    /**
     * Wyciąga {@code host:port} z JDBC URL postaci {@code jdbc:<subprotocol>://host:port/...} —
     * strip prefiksu {@code jdbc:}, reszta parsowana jako {@link URI} (schemat=subprotocol,
     * authority=host:port).
     */
    private static String extractHostPort(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port < 0) {
                return null;
            }
            return host + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Set<String> extractAllowedHostPorts(List<String> grantedPermissions) {
        if (grantedPermissions == null) {
            return Set.of();
        }
        return grantedPermissions.stream()
                .filter(p -> p != null && p.startsWith(EGRESS_PERMISSION_PREFIX))
                .map(p -> p.substring(EGRESS_PERMISSION_PREFIX.length()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
