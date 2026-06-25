package com.acme.contactcenter.plugin.callresultdbsync;

import com.contactcenter.pluginsdk.DbEgressClient;
import com.contactcenter.pluginsdk.HttpEgressClient;
import com.contactcenter.pluginsdk.HttpResponse;
import com.contactcenter.pluginsdk.PluginConfig;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginLogger;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.CustomerView;
import com.contactcenter.pluginsdk.model.DispositionEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy {@link CustomerCallResultDbSyncPlugin}, w szczególności:
 * <ul>
 *   <li>walidacji nazwy tabeli configurowalnej przez {@code dbTable} (interpolowana w SQL
 *       identifier position — musi być sprawdzona przed budową zapytania);</li>
 *   <li>że {@code onPostContactEnd}/{@code onDispositionSet} nigdy nie propagują wyjątku na
 *       zewnątrz (fire-and-forget — błąd DB jest zawierany wewnątrz pluginu i tylko logowany).</li>
 * </ul>
 *
 * <p>Brak realnej bazy danych w tym teście — {@link DbEgressClient} jest zamockowany przez
 * {@link RecordingDbEgressClient}, która jedynie przechwytuje SQL/parametry przekazane przez
 * plugin, bez nawiązywania żadnego połączenia.
 */
class CustomerCallResultDbSyncPluginTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void onActivate_whenJdbcUrlMissing_throws() {
        CustomerCallResultDbSyncPlugin plugin = new CustomerCallResultDbSyncPlugin();
        PluginContext ctx = new StubPluginContext(Map.of(), null);

        assertThrows(IllegalStateException.class, () -> plugin.onActivate(ctx));
    }

    @Test
    void onActivate_whenDbTableInvalid_throws() {
        CustomerCallResultDbSyncPlugin plugin = new CustomerCallResultDbSyncPlugin();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db", "dbTable", "drop table; --"),
                null);

        assertThrows(IllegalStateException.class, () -> plugin.onActivate(ctx));
    }

    @Test
    void onActivate_whenConfigValid_doesNotThrow() {
        CustomerCallResultDbSyncPlugin plugin = new CustomerCallResultDbSyncPlugin();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), null);

        plugin.onActivate(ctx); // nie powinno rzucić
    }

    @Test
    void onPostContactEnd_buildsInsertWithDefaultTableAndContactFields() {
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), dbClient);

        ContactEvent event = new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", Instant.now());
        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx, event);

        assertEquals(1, dbClient.calls.size());
        RecordedCall call = dbClient.calls.get(0);
        assertTrue(call.sql.contains("INSERT INTO call_results"), "tabela domyślna 'call_results'");
        assertTrue(call.sql.contains("event_type"));
        assertEquals(contactId, call.params.get(0));
        assertEquals(CUSTOMER_ID, call.params.get(1));
        assertEquals("CONTACT_ENDED", call.params.get(2));
        assertEquals("VOICE", call.params.get(3)); // channel z ContactView
        assertEquals(AGENT_ID, call.params.get(6));
    }

    @Test
    void onPostContactEnd_usesConfiguredTableName() {
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db", "dbTable", "tenant_call_log"),
                dbClient);

        ContactEvent event = new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", Instant.now());
        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx, event);

        assertEquals(1, dbClient.calls.size());
        assertTrue(dbClient.calls.get(0).sql.contains("INSERT INTO tenant_call_log"));
    }

    @Test
    void onPostContactEnd_whenDbCallThrows_swallowsExceptionAndDoesNotPropagate() {
        UUID contactId = UUID.randomUUID();
        DbEgressClient throwingClient = (sql, params) -> {
            throw new SecurityException("host poza allow-listą");
        };
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), throwingClient);

        ContactEvent event = new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", Instant.now());

        // Fire-and-forget — nie powinno rzucić na zewnątrz, błąd jest zawierany wewnątrz pluginu.
        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx, event);
    }

    @Test
    void onDispositionSet_buildsInsertWithDispositionFieldsAndNullChannelInfo() {
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), dbClient);

        DispositionEvent event = new DispositionEvent(
                contactId, CUSTOMER_ID, "RESOLVED", AGENT_ID, Instant.now());
        new CustomerCallResultDbSyncPlugin().onDispositionSet(ctx, event);

        assertEquals(1, dbClient.calls.size());
        RecordedCall call = dbClient.calls.get(0);
        assertEquals(contactId, call.params.get(0));
        assertEquals(CUSTOMER_ID, call.params.get(1));
        assertEquals("DISPOSITION_SET", call.params.get(2));
        assertEquals(null, call.params.get(3)); // channel nieznany z DispositionEvent
        assertEquals(AGENT_ID, call.params.get(6));
        assertEquals("RESOLVED", call.params.get(7));
    }

    @Test
    void onDispositionSet_whenDbTableBecameInvalidAfterActivation_doesNotPropagate() {
        // Symuluje admina tenanta zmieniającego config PATCH .../config (bez re-enable) na
        // niedozwoloną nazwę tabeli między aktywacją a kolejnym eventem.
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db", "dbTable", "1invalid"),
                dbClient);

        DispositionEvent event = new DispositionEvent(
                UUID.randomUUID(), CUSTOMER_ID, "RESOLVED", AGENT_ID, Instant.now());

        new CustomerCallResultDbSyncPlugin().onDispositionSet(ctx, event);

        assertTrue(dbClient.calls.isEmpty(), "INSERT nie powinien zostać wykonany dla nieprawidłowej nazwy tabeli");
    }

    // =========================================================================
    // Test doubles
    // =========================================================================

    private record RecordedCall(String sql, List<Object> params) {
    }

    private static final class RecordingDbEgressClient implements DbEgressClient {
        private final List<RecordedCall> calls = new java.util.ArrayList<>();

        @Override
        public int executeUpdate(String sql, List<Object> params) {
            calls.add(new RecordedCall(sql, params));
            return 1;
        }
    }

    /** Minimalny stub {@link PluginContext} z configurowalnym configiem i DbEgressClient. */
    private static final class StubPluginContext implements PluginContext {

        private final Map<String, String> configValues;
        private final DbEgressClient dbEgressClient;

        StubPluginContext(Map<String, String> configValues, DbEgressClient dbEgressClient) {
            this.configValues = new HashMap<>(configValues);
            this.dbEgressClient = dbEgressClient;
        }

        @Override
        public CustomerView getCustomer(UUID customerId) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public void updateCustomerFields(UUID customerId, Map<String, Object> customFields) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public ContactView getContact(UUID contactId) {
            return new ContactView(
                    contactId, CUSTOMER_ID, "VOICE", "INBOUND", "COMPLETED",
                    AGENT_ID, UUID.randomUUID(), Instant.now().minusSeconds(120), Instant.now());
        }

        @Override
        public void appendContactNote(UUID contactId, String note) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public HttpEgressClient httpClient() {
            return new HttpEgressClient() {
                @Override
                public HttpResponse get(String url, Map<String, String> headers) {
                    throw new UnsupportedOperationException("nieużywane w tym teście");
                }

                @Override
                public HttpResponse post(String url, Map<String, String> headers, byte[] body) {
                    throw new UnsupportedOperationException("nieużywane w tym teście");
                }
            };
        }

        @Override
        public DbEgressClient dbClient() {
            return dbEgressClient;
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void error(String message, Throwable throwable) {
                }
            };
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig() {
                @Override
                public Optional<String> get(String key) {
                    return Optional.ofNullable(configValues.get(key));
                }

                @Override
                public String getOrDefault(String key, String defaultValue) {
                    return get(key).orElse(defaultValue);
                }
            };
        }
    }
}
