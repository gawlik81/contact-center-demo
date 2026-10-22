package com.contactcenter.infrastructure.etl;

import com.contactcenter.domain.etl.ContactDwRow;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla
 * {@link PostgresDwWriter#upsert(List)} (BE-141).
 *
 * <p><strong>Kontekst BE-141:</strong> ETL przestał kopiować {@code contact.remote_address}
 * (PII klienta – numer CLI / e-mail) do {@code contacts_dw} – {@link ContactDwRow} nie ma tego pola,
 * a {@code UPSERT_SQL} tego writera nie wymienia kolumny {@code remote_address}. Kolumnę usunął DB-078
 * (V127/V128), więc test dowodzi, że writer działa poprawnie na schemacie „po" poprzez asercje na
 * wartościach zapisanego wiersza oraz brak klucza {@code remote_address} w wyniku {@code SELECT *}
 * (a nie {@code get(...)==null}, które dla nieistniejącej kolumny jest tautologią).
 *
 * <p><strong>Od DB-078 (V127/V128)</strong> kolumna {@code remote_address} nie istnieje w pełnym łańcuchu
 * Flyway, więc ten test działa już na schemacie „po". Wariant „przed M2" (kolumna obecna) oraz sweep/drop
 * pokrywa {@code ContactsDwRemoteAddressDropMigrationTest} (baza budowana przez Flyway target API).
 */
@DisplayName("PostgresDwWriter#upsert – zapis contacts_dw bez remote_address (BE-141)")
class PostgresDwWriterIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static PostgresDwWriter writer;

    private UUID contactIdToCleanUp;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        writer = new PostgresDwWriter(jdbc);
    }

    @AfterAll
    static void stopContext() {
        pool.close();
    }

    @AfterEach
    void cleanUp() {
        if (contactIdToCleanUp != null) {
            jdbc.update("DELETE FROM contacts_dw WHERE contact_id = ?", contactIdToCleanUp);
        }
    }

    // =========================================================================================
    // Zapis nowego wiersza
    // =========================================================================================

    @Nested
    @DisplayName("nowy wiersz")
    class NewRow {

        @Test
        @DisplayName("zapisuje wszystkie pola ContactDwRow poprawnie, contacts_dw nie ma kolumny remote_address")
        void upsert_writesAllFields_noRemoteAddressColumn() {
            UUID contactId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            UUID agentId = UUID.randomUUID();
            UUID queueId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
            Instant endedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
            Instant queuedAt = startedAt.minusSeconds(30);

            ContactDwRow row = new ContactDwRow(
                    contactId, tenantId, agentId, queueId, campaignId,
                    "PHONE", "INBOUND", "COMPLETED", "SALE",
                    120, startedAt, endedAt, queuedAt
            );
            contactIdToCleanUp = contactId;

            writer.upsert(List.of(row));

            Map<String, Object> saved = jdbc.queryForMap(
                    "SELECT * FROM contacts_dw WHERE contact_id = ?", contactId);

            assertThat(uuid(saved.get("tenant_id"))).isEqualTo(tenantId);
            assertThat(uuid(saved.get("agent_id"))).isEqualTo(agentId);
            assertThat(uuid(saved.get("queue_id"))).isEqualTo(queueId);
            assertThat(uuid(saved.get("campaign_id"))).isEqualTo(campaignId);
            assertThat(saved.get("channel")).isEqualTo("PHONE");
            assertThat(saved.get("direction")).isEqualTo("INBOUND");
            assertThat(saved.get("status")).isEqualTo("COMPLETED");
            assertThat(saved.get("disposition_code")).isEqualTo("SALE");
            assertThat(saved.get("duration_sec")).isEqualTo(120);
            assertThat(((java.sql.Timestamp) saved.get("started_at")).toInstant()).isEqualTo(startedAt);
            assertThat(((java.sql.Timestamp) saved.get("ended_at")).toInstant()).isEqualTo(endedAt);
            assertThat(((java.sql.Timestamp) saved.get("queued_at")).toInstant()).isEqualTo(queuedAt);
            assertThat(saved.get("etl_synced_at")).isNotNull();

            // Rdzeń BE-141/DB-078: kolumna remote_address (PII) nie istnieje w schemacie po V128.
            // Map.get dla nieistniejącego klucza zwraca null, więc asercja na wartości byłaby
            // tautologią — sprawdzamy brak KLUCZA (zabezpieczenie przed ponownym dodaniem kolumny).
            assertThat(saved)
                    .as("contacts_dw nie ma kolumny remote_address po V128")
                    .doesNotContainKey("remote_address");
        }

        @Test
        @DisplayName("pola nullable (agent/queue/campaign/ended_at/duration/disposition) zapisywane jako NULL")
        void upsert_nullableFields_persistedAsNull() {
            UUID contactId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            ContactDwRow row = new ContactDwRow(
                    contactId, tenantId, null, null, null,
                    "EMAIL", "OUTBOUND", "ABANDONED", null,
                    null, startedAt, null, startedAt
            );
            contactIdToCleanUp = contactId;

            writer.upsert(List.of(row));

            Map<String, Object> saved = jdbc.queryForMap(
                    "SELECT * FROM contacts_dw WHERE contact_id = ?", contactId);

            assertThat(saved.get("agent_id")).isNull();
            assertThat(saved.get("queue_id")).isNull();
            assertThat(saved.get("campaign_id")).isNull();
            assertThat(saved.get("ended_at")).isNull();
            assertThat(saved.get("duration_sec")).isNull();
            assertThat(saved.get("disposition_code")).isNull();
            assertThat(saved).doesNotContainKey("remote_address");
        }

        @Test
        @DisplayName("batch wielu wierszy – wszystkie trafiają do contacts_dw")
        void upsert_batchOfRows_allPersisted() {
            UUID contactId1 = UUID.randomUUID();
            UUID contactId2 = UUID.randomUUID();
            Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            ContactDwRow row1 = new ContactDwRow(
                    contactId1, UUID.randomUUID(), null, null, null,
                    "PHONE", "INBOUND", "COMPLETED", "SALE",
                    60, startedAt, startedAt.plusSeconds(60), startedAt);
            ContactDwRow row2 = new ContactDwRow(
                    contactId2, UUID.randomUUID(), null, null, null,
                    "SOCIAL_WHATSAPP", "INBOUND", "ABANDONED", null,
                    null, startedAt, null, startedAt);

            writer.upsert(List.of(row1, row2));
            try {
                Long count = jdbc.queryForObject(
                        "SELECT count(*) FROM contacts_dw WHERE contact_id IN (?, ?)",
                        Long.class, contactId1, contactId2);
                assertThat(count).isEqualTo(2L);
            } finally {
                jdbc.update("DELETE FROM contacts_dw WHERE contact_id IN (?, ?)", contactId1, contactId2);
            }
        }
    }

    // =========================================================================================
    // Idempotentność – ON CONFLICT (contact_id) DO UPDATE
    // =========================================================================================

    @Nested
    @DisplayName("idempotentność upsertu")
    class Idempotency {

        @Test
        @DisplayName("drugi upsert tego samego contact_id aktualizuje wiersz, nie tworzy duplikatu")
        void upsert_sameContactIdTwice_updatesInPlace() {
            UUID contactId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            Instant startedAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
            contactIdToCleanUp = contactId;

            ContactDwRow firstVersion = new ContactDwRow(
                    contactId, tenantId, null, null, null,
                    "PHONE", "INBOUND", "ACTIVE", null,
                    null, startedAt, null, startedAt);
            writer.upsert(List.of(firstVersion));

            Object firstSyncedAt = jdbc.queryForObject(
                    "SELECT etl_synced_at FROM contacts_dw WHERE contact_id = ?", Object.class, contactId);

            ContactDwRow secondVersion = new ContactDwRow(
                    contactId, tenantId, UUID.randomUUID(), null, null,
                    "PHONE", "INBOUND", "COMPLETED", "SALE",
                    300, startedAt, startedAt.plusSeconds(300), startedAt);
            writer.upsert(List.of(secondVersion));

            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM contacts_dw WHERE contact_id = ?", Long.class, contactId);
            assertThat(count).as("brak duplikatu po drugim upsercie").isEqualTo(1L);

            Map<String, Object> saved = jdbc.queryForMap(
                    "SELECT * FROM contacts_dw WHERE contact_id = ?", contactId);
            assertThat(saved.get("status")).isEqualTo("COMPLETED");
            assertThat(saved.get("disposition_code")).isEqualTo("SALE");
            assertThat(saved.get("duration_sec")).isEqualTo(300);
            assertThat(uuid(saved.get("agent_id"))).isEqualTo(secondVersion.agentId());
            assertThat(saved).doesNotContainKey("remote_address");

            Object secondSyncedAt = saved.get("etl_synced_at");
            assertThat(secondSyncedAt).as("etl_synced_at zaktualizowany przy drugim upsercie")
                    .isNotEqualTo(firstSyncedAt);
        }
    }

    // =========================================================================================
    // Brak operacji dla pustej/null listy
    // =========================================================================================

    @Nested
    @DisplayName("brak operacji przy pustej liście")
    class NoOp {

        @Test
        @DisplayName("upsert(null) i upsert(List.of()) nie wykonują żadnego zapisu")
        void upsert_nullOrEmpty_noDatabaseWrite() {
            Long before = jdbc.queryForObject("SELECT count(*) FROM contacts_dw", Long.class);

            writer.upsert(null);
            writer.upsert(List.of());

            Long after = jdbc.queryForObject("SELECT count(*) FROM contacts_dw", Long.class);
            assertThat(after).isEqualTo(before);
        }
    }

    /** Konwertuje wartość kolumny UUID zwróconą przez sterownik JDBC (UUID albo String) na {@link UUID}. */
    private static UUID uuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuidValue ? uuidValue : UUID.fromString(value.toString());
    }
}
