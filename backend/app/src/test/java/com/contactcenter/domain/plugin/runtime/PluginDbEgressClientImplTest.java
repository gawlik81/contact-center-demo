package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy {@link PluginDbEgressClientImpl} — wymuszenie allow-listy {@code host:port} PRZED
 * jakimkolwiek nawiązaniem połączenia (analogiczne do {@code PluginHttpEgressClientImplTest},
 * gdyby istniał — host:port poza allow-listą musi rzucić {@link SecurityException}, zanim
 * {@link java.sql.DriverManager} zostanie wywołany).
 */
@DisplayName("PluginDbEgressClientImpl – allow-list host:port wymuszana przed połączeniem")
class PluginDbEgressClientImplTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PLUGIN_KEY = "acme-callresult-db-sync";

    @Test
    @DisplayName("Host poza allow-listą rzuca SecurityException, nigdy nie próbuje połączyć się z DB")
    void rejectsHostOutsideAllowList() {
        PluginDbEgressClientImpl client = new PluginDbEgressClientImpl(
                List.of("db:egress:db.acme-crm.example:5432"),
                TENANT_ID,
                PLUGIN_KEY,
                "jdbc:postgresql://evil.example:5432/somedb",
                "user",
                "secret-password");

        assertThatThrownBy(() -> client.executeUpdate("INSERT INTO call_results VALUES (?)", List.of(1)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("evil.example:5432");
    }

    @Test
    @DisplayName("Port w JDBC URL inny niż w allow-liście (sam host poprawny) jest odrzucony")
    void rejectsMismatchedPortEvenWhenHostMatches() {
        PluginDbEgressClientImpl client = new PluginDbEgressClientImpl(
                List.of("db:egress:db.acme-crm.example:5432"),
                TENANT_ID,
                PLUGIN_KEY,
                "jdbc:postgresql://db.acme-crm.example:9999/somedb",
                "user",
                "secret-password");

        assertThatThrownBy(() -> client.executeUpdate("INSERT INTO call_results VALUES (?)", List.of(1)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Brak jakichkolwiek grantedPermissions db:egress odrzuca każdy host")
    void rejectsEverythingWhenNoDbEgressGranted() {
        PluginDbEgressClientImpl client = new PluginDbEgressClientImpl(
                List.of("http:egress:api.acme-crm.example", "customer:read"),
                TENANT_ID,
                PLUGIN_KEY,
                "jdbc:postgresql://db.acme-crm.example:5432/somedb",
                "user",
                "secret-password");

        assertThatThrownBy(() -> client.executeUpdate("INSERT INTO call_results VALUES (?)", List.of(1)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("JDBC URL z hostem dopasowanym do allow-listy przechodzi walidację allow-listy i próbuje połączyć się "
            + "(brak realnej bazy w teście -> RuntimeException przy próbie połączenia, NIE SecurityException)")
    void allowedHostPassesAllowListCheck() {
        PluginDbEgressClientImpl client = new PluginDbEgressClientImpl(
                List.of("db:egress:127.0.0.1:54329"),
                TENANT_ID,
                PLUGIN_KEY,
                "jdbc:postgresql://127.0.0.1:54329/nonexistent-test-db",
                "user",
                "secret-password");

        // Allow-list przepuszcza host:port -> próba realnego połączenia (nieudana, bo port
        // nie jest obsadzony w tym środowisku testowym) musi skończyć się RuntimeException
        // (SQLException zawinięty), NIE SecurityException -- to potwierdza, że odrzucenie nie
        // nastąpiło na etapie allow-listy.
        assertThatThrownBy(() -> client.executeUpdate("INSERT INTO call_results VALUES (?)", List.of(1)))
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(SecurityException.class);
    }
}
