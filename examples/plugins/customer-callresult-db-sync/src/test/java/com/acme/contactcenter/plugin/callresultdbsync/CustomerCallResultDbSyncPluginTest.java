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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertTrue(call.sql.contains("WHERE NOT EXISTS"), "SQL musi zawierać guard idempotentności");
        assertEquals(10, call.params.size(), "9 params INSERT + 1 dla WHERE NOT EXISTS subquery");
        assertEquals(contactId, call.params.get(0));
        assertEquals(CUSTOMER_ID, call.params.get(1));
        assertEquals("CONTACT_ENDED", call.params.get(2));
        assertEquals("VOICE", call.params.get(3)); // channel z ContactView
        assertEquals(AGENT_ID, call.params.get(6));
        assertInstanceOf(java.sql.Timestamp.class, call.params.get(8), "occurred_at musi być Timestamp");
        assertEquals(contactId, call.params.get(9), "params[9] to contact_id dla WHERE NOT EXISTS");
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

    @Test
    void onPostContactEnd_occurredAtStoredAsWarsawLocalTime() {
        // 2024-06-01 12:00:00 UTC = 2024-06-01 14:00:00 Warsaw (UTC+2 CEST)
        Instant utcNoon = Instant.parse("2024-06-01T12:00:00Z");
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), dbClient, utcNoon);

        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx,
                new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", utcNoon));

        java.sql.Timestamp ts = (java.sql.Timestamp) dbClient.calls.get(0).params.get(8);
        // Timestamp.valueOf(LocalDateTime) → toLocalDateTime() zwraca ten sam LocalDateTime co
        // włożono. Dla 12:00 UTC → Warsaw 14:00 (UTC+2) — niezależnie od JVM timezone.
        assertEquals(14, ts.toLocalDateTime().getHour(),
                "occurred_at musi kodować czas warszawski (14:00), nie UTC (12:00)");
        assertEquals(6, ts.toLocalDateTime().getMonthValue());
        assertEquals(1, ts.toLocalDateTime().getDayOfMonth());
    }

    @Test
    void onPostContactEnd_whenEndedAtNull_fallsBackToOccurredAt() {
        // endedAt=null w ContactView — plugin powinien użyć occurredAt z eventu
        Instant occurredAt = Instant.parse("2024-06-01T10:00:00Z"); // Warsaw 12:00
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), dbClient, null);

        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx,
                new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", occurredAt));

        assertEquals(1, dbClient.calls.size(), "INSERT powinien zostać wykonany (fallback do occurredAt)");
        java.sql.Timestamp ts = (java.sql.Timestamp) dbClient.calls.get(0).params.get(8);
        assertNotNull(ts, "occurred_at nie powinien być null gdy occurredAt jest dostępny");
        assertEquals(12, ts.toLocalDateTime().getHour(),
                "fallback occurredAt (10:00 UTC → 12:00 Warsaw) musi być skonwertowany do czasu warszawskiego");
    }

    @Test
    void onPostContactEnd_whenBothTimestampsNull_skipsInsertAndLogs() {
        UUID contactId = UUID.randomUUID();
        RecordingDbEgressClient dbClient = new RecordingDbEgressClient();
        // endedAt=null w ContactView, occurredAt=null w evencie
        PluginContext ctx = new StubPluginContext(
                Map.of("jdbcUrl", "jdbc:postgresql://localhost:5432/db"), dbClient, null);

        ContactEvent eventWithNullOccurredAt =
                new ContactEvent(contactId, CUSTOMER_ID, "POST_CONTACT_END", null);
        new CustomerCallResultDbSyncPlugin().onPostContactEnd(ctx, eventWithNullOccurredAt);

        assertTrue(dbClient.calls.isEmpty(),
                "INSERT nie powinien zostać wykonany gdy oba timestampy są null");
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
        private final Instant contactEndedAt;

        StubPluginContext(Map<String, String> configValues, DbEgressClient dbEgressClient) {
            this(configValues, dbEgressClient, Instant.now());
        }

        StubPluginContext(Map<String, String> configValues, DbEgressClient dbEgressClient,
                          Instant contactEndedAt) {
            this.configValues = new HashMap<>(configValues);
            this.dbEgressClient = dbEgressClient;
            this.contactEndedAt = contactEndedAt;
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
                    AGENT_ID, UUID.randomUUID(), Instant.now().minusSeconds(120), contactEndedAt);
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
