package com.contactcenter.domain.email;

import com.contactcenter.support.PostgresTestDatabase;
import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test integracyjny migracji DB-067 (V101 + V102): partycjonowanie RANGE tabeli
 * {@code email_message} po {@code message_at} (EPIC-30), na PRAWDZIWYM PostgreSQL 16
 * (Testcontainers).
 *
 * <p><strong>Wzorzec pre/post (dane zastane):</strong> kontener jest migrowany Flyway do wersji
 * TUŻ PRZED V101 (wersja wyznaczana dynamicznie z {@code Flyway#info()} po opisie migracji, bez
 * numerów na sztywno), zasilany wierszami w schemacie sprzed zmiany (INBOUND z {@code received_at},
 * OUTBOUND z {@code sent_at}, wiersz bez obu znaczników, wiersz orphan), a następnie dociągany do
 * pełnego łańcucha. Dzięki temu backfill {@code message_at = COALESCE(received_at, sent_at,
 * created_at)} i przepisanie tabeli są sprawdzane na danych, a nie tylko na pustej bazie.
 *
 * <p>Kontener jest WŁASNY (nie współdzielony {@link PostgresTestDatabase}), bo współdzielona baza
 * jest już w pełni zmigrowana i nie pozwala cofnąć schematu do stanu sprzed V101.
 *
 * <p>Testy RLS działają WYŁĄCZNIE pod {@code SET ROLE app_user} (bez {@code BYPASSRLS}) i odpytują
 * tabelę nadrzędną. Bezpośredni dostęp po nazwie partycji jest blokowany przez REVOKE (V102, decyzja
 * właściciela 2026-10-04) -- sprawdzany w {@code DirectPartitionAccess}.
 */
@Testcontainers
@DisplayName("email_message: partycjonowanie RANGE po message_at (DB-067 / V101 + V102)")
class EmailMessagePartitioningTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "contact_center_test";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    /** Opis migracji V101 w nazwie pliku ({@code V101__email_message_add_message_at.sql}). */
    private static final String MIGRATION_V101_DESCRIPTION = "email message add message at";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    /** Wiersze zasiane PRZED migracją: message_id -> oczekiwane message_at (= COALESCE). */
    private static final Map<UUID, OffsetDateTime> LEGACY_EXPECTED_MESSAGE_AT = new ConcurrentHashMap<>();
    private static UUID legacyTenant;
    private static int legacyRowCount;

    private static final OffsetDateTime LEGACY_INBOUND_RECEIVED = OffsetDateTime.parse("2026-03-10T08:00:00Z");
    private static final OffsetDateTime LEGACY_OUTBOUND_SENT = OffsetDateTime.parse("2026-06-01T09:00:00Z");
    private static final OffsetDateTime LEGACY_CREATED_ONLY = OffsetDateTime.parse("2025-01-02T10:00:00Z");

    private static JdbcTemplate superuserJdbc;

    @BeforeAll
    static void migratePreThenSeedThenMigrateToLatest() throws Exception {
        Flyway latest = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .locations("classpath:db/migration")
                .load();

        MigrationVersion v101 = null;
        for (MigrationInfo info : latest.info().all()) {
            if (MIGRATION_V101_DESCRIPTION.equals(info.getDescription())) {
                v101 = info.getVersion();
            }
        }
        if (v101 == null) {
            throw new IllegalStateException("Brak migracji V101 (" + MIGRATION_V101_DESCRIPTION + ") w classpath");
        }
        final MigrationVersion v101Final = v101;
        MigrationVersion preV101 = null;
        for (MigrationInfo info : latest.info().all()) {
            MigrationVersion v = info.getVersion();
            if (v != null && v.compareTo(v101Final) < 0 && (preV101 == null || v.compareTo(preV101) > 0)) {
                preV101 = v;
            }
        }

        // 1. Schemat sprzed V101 (ostatnia wersja przed partycjonowaniem email_message).
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .locations("classpath:db/migration")
                .target(preV101)
                .load()
                .migrate();

        superuserJdbc = new JdbcTemplate(new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), USER, PASSWORD, true));

        // 2. Dane zastane w schemacie sprzed zmiany.
        legacyTenant = UUID.randomUUID();
        superuserJdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)",
                legacyTenant, "Tenant legacy DB-067");
        seedLegacy("INBOUND", null, LEGACY_INBOUND_RECEIVED, LEGACY_INBOUND_RECEIVED.plusSeconds(5), null);
        seedLegacy("OUTBOUND", LEGACY_OUTBOUND_SENT, null, LEGACY_OUTBOUND_SENT, null);
        seedLegacy("INBOUND", null, null, LEGACY_CREATED_ONLY, null);
        seedLegacy("INBOUND", null, LEGACY_INBOUND_RECEIVED.plusDays(1), LEGACY_INBOUND_RECEIVED.plusDays(1), null);
        legacyRowCount = LEGACY_EXPECTED_MESSAGE_AT.size();

        // 3. Dociągnięcie do pełnego łańcucha (V101 backfill + V102 przepisanie tabeli).
        latest.migrate();
    }

    /**
     * Wstawia wiersz w schemacie sprzed V101. Oczekiwane message_at = COALESCE(received_at,
     * sent_at, created_at) -- liczone w teście niezależnie od migracji.
     */
    private static void seedLegacy(String direction, OffsetDateTime sentAt, OffsetDateTime receivedAt,
                                   OffsetDateTime createdAt, UUID contactId) {
        UUID messageId = UUID.randomUUID();
        superuserJdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address,
                                           to_address, message_id_header, sent_at, received_at, created_at)
                VALUES (?, ?, ?, ?, 'legacy@example.com', 'rcpt@example.com', ?, ?, ?, ?)
                """,
                messageId, legacyTenant, contactId, direction,
                "<legacy-" + messageId + "@example.com>",
                toTs(sentAt), toTs(receivedAt), toTs(createdAt));
        OffsetDateTime expected = receivedAt != null ? receivedAt
                : sentAt != null ? sentAt
                : createdAt;
        LEGACY_EXPECTED_MESSAGE_AT.put(messageId, expected);
    }

    // =========================================================================================
    // Backfill na danych zastanych (AC: 100 % wierszy message_at = COALESCE; liczba przed == po)
    // =========================================================================================

    @Nested
    @DisplayName("backfill V101 na danych zastanych")
    class Backfill {

        @Test
        @DisplayName("liczba wierszy przed migracją == po migracji (przepisanie tabeli nie gubi danych)")
        void rowCount_isPreservedAcrossMigration() {
            Long count = superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_message WHERE tenant_id = ?", Long.class, legacyTenant);
            assertThat(count).isEqualTo((long) legacyRowCount);
        }

        @Test
        @DisplayName("każdy zasiany wiersz ma message_at = COALESCE(received_at, sent_at, created_at)")
        void everyLegacyRow_hasMessageAtEqualToCoalesce() {
            for (Map.Entry<UUID, OffsetDateTime> entry : LEGACY_EXPECTED_MESSAGE_AT.entrySet()) {
                OffsetDateTime actual = superuserJdbc.queryForObject(
                        "SELECT message_at FROM email_message WHERE message_id = ?",
                        (rs, n) -> rs.getObject(1, OffsetDateTime.class),
                        entry.getKey());
                assertThat(actual.toInstant())
                        .as("message_at dla %s", entry.getKey())
                        .isEqualTo(entry.getValue().toInstant());
            }
        }

        @Test
        @DisplayName("message_at NOT NULL na całej tabeli (0 NULL-i)")
        void noNullMessageAt() {
            Long nulls = superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_message WHERE message_at IS NULL", Long.class);
            assertThat(nulls).isZero();
        }

        @Test
        @DisplayName("historia Flyway: V101 i V102 zastosowane (success), najnowsza wersja = V102")
        void flywayHistory_hasV101AndV102Successful() {
            List<String> failed = superuserJdbc.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE version IN ('101', '102') AND success = FALSE",
                    String.class);
            assertThat(failed).isEmpty();
            Integer applied = superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('101', '102') AND success = TRUE",
                    Integer.class);
            assertThat(applied).isEqualTo(2);
        }
    }

    // =========================================================================================
    // Struktura po przepisaniu (AC: PK, unikalność D4, FK, CHECK-i, brak wykonywalnego drop_old_*)
    // =========================================================================================

    @Nested
    @DisplayName("struktura po swap-ie")
    class Structure {

        @Test
        @DisplayName("email_message jest tabelą partycjonowaną (relkind='p') RANGE (message_at)")
        void isPartitionedByRangeOnMessageAt() {
            String relkind = superuserJdbc.queryForObject(
                    "SELECT relkind::text FROM pg_class WHERE oid = 'public.email_message'::regclass", String.class);
            assertThat(relkind).isEqualTo("p");

            String partKey = superuserJdbc.queryForObject(
                    "SELECT pg_get_partkeydef('public.email_message'::regclass)", String.class);
            assertThat(partKey).isEqualTo("RANGE (message_at)");
        }

        @Test
        @DisplayName("PK (message_id, message_at); UNIQUE (tenant_id, message_id_header, message_at) DEFERRABLE INITIALLY DEFERRED")
        void primaryKeyAndUnique_includePartitionKey_andAreDeferrable() {
            assertThat(constraintColumns("pk_email_message")).containsExactly("message_id", "message_at");
            assertThat(constraintColumns("uq_email_message_id_header"))
                    .containsExactly("tenant_id", "message_id_header", "message_at");

            Boolean deferrable = superuserJdbc.queryForObject("""
                    SELECT condeferrable AND condeferred FROM pg_constraint
                    WHERE conrelid = 'public.email_message'::regclass AND conname = 'uq_email_message_id_header'
                    """, Boolean.class);
            assertThat(deferrable).isTrue();
        }

        @Test
        @DisplayName("FK do tenant (ON DELETE RESTRICT) i CHECK-i (attachments jest tablicą, direction) zachowane")
        void foreignKeyAndChecks_arePreserved() {
            String deleteRule = superuserJdbc.queryForObject("""
                    SELECT confdeltype::text FROM pg_constraint
                    WHERE conrelid = 'public.email_message'::regclass AND conname = 'fk_email_message_tenant'
                    """, String.class);
            assertThat(deleteRule).as("'r' = RESTRICT").isEqualTo("r");

            assertThat(superuserJdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_constraint
                    WHERE conrelid = 'public.email_message'::regclass
                      AND conname IN ('chk_email_attachments_is_array', 'chk_email_message_direction')
                    """, Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("partycja DEFAULT istnieje; message_at ma NOT NULL i DEFAULT now()")
        void defaultPartition_andMessageAtColumnDefinition() {
            assertThat(superuserJdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_inherits
                    WHERE inhparent = 'public.email_message'::regclass
                      AND inhrelid = 'public.email_message_default'::regclass
                    """, Integer.class)).isEqualTo(1);

            Boolean notNull = superuserJdbc.queryForObject("""
                    SELECT attnotnull FROM pg_attribute
                    WHERE attrelid = 'public.email_message'::regclass AND attname = 'message_at'
                    """, Boolean.class);
            assertThat(notNull).isTrue();

            String defaultExpr = superuserJdbc.queryForObject("""
                    SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef
                    WHERE adrelid = 'public.email_message'::regclass
                      AND adnum = (SELECT attnum FROM pg_attribute
                                   WHERE attrelid = 'public.email_message'::regclass AND attname = 'message_at')
                    """, String.class);
            assertThat(defaultExpr).isEqualTo("now()");
        }

        @Test
        @DisplayName("indeksy wtórne odtworzone: contact, delivery, orphan (message_at), purge (tenant, message_at)")
        void secondaryIndexes_areRecreated() {
            List<String> names = superuserJdbc.queryForList("""
                    SELECT indexname FROM pg_indexes
                    WHERE schemaname = 'public' AND tablename = 'email_message'
                    """, String.class);
            assertThat(names).contains("idx_email_message_contact", "idx_email_message_delivery",
                    "idx_email_message_tenant_orphan_age", "idx_email_message_tenant_message_at");

            String orphanDef = superuserJdbc.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_email_message_tenant_orphan_age'",
                    String.class);
            assertThat(orphanDef)
                    .contains("(tenant_id, message_at)")
                    .contains("WHERE (contact_id IS NULL)")
                    .doesNotContain("COALESCE");
        }

        @Test
        @DisplayName("trigger ochronny message_at istnieje; brak wykonywalnej drop_old_email_message_partitions()")
        void immutabilityTrigger_exists_andNoDropOldFunction() {
            assertThat(superuserJdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_trigger
                    WHERE tgrelid = 'public.email_message'::regclass
                      AND tgname = 'trg_email_message_forbid_message_at_update' AND NOT tgisinternal
                    """, Integer.class)).isEqualTo(1);

            assertThat(superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_proc WHERE proname = 'drop_old_email_message_partitions'",
                    Integer.class)).isZero();
        }

        @Test
        @DisplayName("widok v_customer_timeline istnieje po przepisaniu i odpytuje email_message")
        void customerTimelineView_survivesSwap() {
            assertThat(superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_class WHERE relname = 'v_customer_timeline' AND relkind = 'v'",
                    Integer.class)).isEqualTo(1);
            assertThat(superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM v_customer_timeline WHERE tenant_id = ?", Integer.class, legacyTenant))
                    .isZero(); // wiersze legacy nie mają contact_id -> nie wchodzą do osi (JOIN contact)
        }

        private List<String> constraintColumns(String constraintName) {
            return superuserJdbc.queryForList("""
                    SELECT a.attname::text
                    FROM pg_constraint c
                    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.conrelid = 'public.email_message'::regclass AND c.conname = ?
                    ORDER BY k.ord
                    """, String.class, constraintName);
        }
    }

    // =========================================================================================
    // Routing do partycji (AC: tableoid -- bieżący miesiąc trafia do partycji miesięcznej, nie _default)
    // =========================================================================================

    @Nested
    @DisplayName("routing do partycji (tableoid)")
    class Routing {

        @Test
        @DisplayName("wiersz z message_at = teraz trafia do partycji bieżącego miesiąca, NIE do _default")
        void currentMonthRow_landsInMonthlyPartition_notDefault() {
            UUID id = insertWithMessageAt(OffsetDateTime.now(ZoneOffset.UTC));
            String partition = tableOf(id);
            String expected = "email_message_" + YearMonth.now(ZoneOffset.UTC).toString().replace("-", "_");
            assertThat(partition).isEqualTo(expected).isNotEqualTo("email_message_default");
        }

        @Test
        @DisplayName("wiersz bez jawnego message_at dostaje DEFAULT now() i trafia do partycji bieżącego miesiąca")
        void omittedMessageAt_usesDefaultNow_andRoutesToCurrentMonth() {
            UUID id = UUID.randomUUID();
            superuserJdbc.update("""
                    INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address, message_id_header)
                    VALUES (?, ?, 'OUTBOUND', 'a@x.com', 'b@x.com', ?)
                    """, id, newTenant("default-now"), "<default-" + id + "@x>");

            String partition = tableOf(id);
            assertThat(partition).isEqualTo("email_message_" + YearMonth.now(ZoneOffset.UTC).toString().replace("-", "_"));

            Timestamp messageAt = superuserJdbc.queryForObject(
                    "SELECT message_at FROM email_message WHERE message_id = ?", Timestamp.class, id);
            assertThat(Math.abs(messageAt.getTime() - System.currentTimeMillis())).isLessThan(60_000L);
        }

        @Test
        @DisplayName("message_at spoza zdefiniowanych zakresów trafia do _default (brak błędu routingu)")
        void outOfRangeMessageAt_landsInDefault() {
            UUID id = insertWithMessageAt(OffsetDateTime.parse("2001-05-01T00:00:00Z"));
            assertThat(tableOf(id)).isEqualTo("email_message_default");
        }

        private String tableOf(UUID id) {
            return superuserJdbc.queryForObject(
                    "SELECT tableoid::regclass::text FROM email_message WHERE message_id = ?", String.class, id);
        }
    }

    // =========================================================================================
    // Niemodyfikowalność message_at (AC: zmiana klucza partycjonowania odrzucona)
    // =========================================================================================

    @Nested
    @DisplayName("message_at niemodyfikowalne")
    class ImmutableMessageAt {

        @Test
        @DisplayName("UPDATE message_at odrzucony (SQLState 23514, trigger ochronny)")
        void updateMessageAt_isRejected() throws Exception {
            UUID id = insertWithMessageAt(OffsetDateTime.now(ZoneOffset.UTC));
            try (Connection c = connect()) {
                SQLException error = failureOf(c,
                        "UPDATE email_message SET message_at = message_at - INTERVAL '40 days' WHERE message_id = ?", id);
                assertThat(error.getSQLState()).isEqualTo("23514");
                assertThat(error.getMessage()).contains("immutable");
            }
        }

        @Test
        @DisplayName("UPDATE innych kolumn (status dostarczenia) działa i nie narusza trigera")
        void updateOtherColumns_isAllowed() {
            UUID id = insertWithMessageAt(OffsetDateTime.now(ZoneOffset.UTC));
            int updated = superuserJdbc.update(
                    "UPDATE email_message SET delivery_status = 'SENT' WHERE message_id = ?", id);
            assertThat(updated).isEqualTo(1);
        }
    }

    // =========================================================================================
    // Unikalność D4 (AC: duplikat odrzucony; ten sam nagłówek z innym message_at przechodzi)
    // =========================================================================================

    @Nested
    @DisplayName("unikalność (tenant_id, message_id_header, message_at) -- D4 = A (założenie robocze)")
    class UniquenessD4 {

        @Test
        @DisplayName("duplikat (tenant, nagłówek, message_at) odrzucony")
        void duplicateTriple_isRejected() {
            UUID tenant = newTenant("uniq-dup");
            OffsetDateTime at = OffsetDateTime.parse("2026-09-15T12:00:00Z");
            String header = "<dup-" + UUID.randomUUID() + "@example.com>";
            insertWithHeader(tenant, header, at);

            assertThatThrownBy(() -> insertWithHeader(tenant, header, at))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("ten sam nagłówek z INNYM message_at przechodzi (udokumentowane ograniczenie D4; obrona = BE-134)")
        void sameHeaderDifferentMessageAt_isAccepted_documentedLimitation() {
            UUID tenant = newTenant("uniq-d4");
            String header = "<d4-" + UUID.randomUUID() + "@example.com>";
            insertWithHeader(tenant, header, OffsetDateTime.parse("2026-09-15T12:00:00Z"));
            insertWithHeader(tenant, header, OffsetDateTime.parse("2026-09-15T12:00:01Z"));

            Integer count = superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_message WHERE tenant_id = ? AND message_id_header = ?",
                    Integer.class, tenant, header);
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("ten sam nagłówek u INNEGO tenanta przechodzi (unikalność jest per tenant)")
        void sameHeaderOtherTenant_isAccepted() {
            String header = "<tenant-scope-" + UUID.randomUUID() + "@example.com>";
            OffsetDateTime at = OffsetDateTime.parse("2026-09-16T12:00:00Z");
            insertWithHeader(newTenant("scope-a"), header, at);
            insertWithHeader(newTenant("scope-b"), header, at);
        }

        private void insertWithHeader(UUID tenant, String header, OffsetDateTime at) {
            superuserJdbc.update("""
                    INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address,
                                               message_id_header, message_at)
                    VALUES (?, ?, 'INBOUND', 'a@x.com', 'b@x.com', ?, ?)
                    """, UUID.randomUUID(), tenant, header, toTs(at));
        }
    }

    // =========================================================================================
    // RLS pod SET ROLE app_user przez tabelę nadrzędną (AC: cross-tenant INSERT odrzucony)
    // =========================================================================================

    @Nested
    @DisplayName("RLS pod app_user przez tabelę nadrzędną")
    class RowLevelSecurity {

        @Test
        @DisplayName("własny tenant: INSERT/SELECT/UPDATE/DELETE działają")
        void ownTenant_crudWorks() throws Exception {
            UUID tenantA = newTenant("rls-own");
            try (Connection c = connect()) {
                asAppUser(c, tenantA);
                UUID id = UUID.randomUUID();
                assertThat(update(c, """
                        INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address,
                                                   message_id_header, message_at)
                        VALUES (?, ?, 'INBOUND', 'a@x.com', 'b@x.com', ?, now())
                        """, id, tenantA, "<rls-own-" + id + ">")).isEqualTo(1);
                assertThat(scalar(c, "SELECT COUNT(*) FROM email_message WHERE message_id = ?", id))
                        .isEqualTo(1L);
                assertThat(update(c, "UPDATE email_message SET delivery_status = 'SENT' WHERE message_id = ?", id))
                        .isEqualTo(1);
                assertThat(update(c, "DELETE FROM email_message WHERE message_id = ?", id)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("GUC tenanta B: INSERT wiersza z tenant_id tenanta A odrzucony (SQLState 42501)")
        void crossTenantInsert_isRejected() throws Exception {
            UUID tenantA = newTenant("rls-cross-a");
            UUID tenantB = newTenant("rls-cross-b");
            try (Connection c = connect()) {
                asAppUser(c, tenantB);
                SQLException error = failureOf(c, """
                        INSERT INTO email_message (tenant_id, direction, from_address, to_address, message_at)
                        VALUES (?, 'INBOUND', 'a@x.com', 'b@x.com', now())
                        """, tenantA);
                assertThat(error.getSQLState()).isEqualTo("42501");
            }
        }

        @Test
        @DisplayName("GUC tenanta B: SELECT/UPDATE/DELETE wierszy tenanta A = 0 wierszy (bez błędu)")
        void crossTenantSelectUpdateDelete_seeZeroRows() throws Exception {
            UUID tenantA = newTenant("rls-vis-a");
            UUID tenantB = newTenant("rls-vis-b");
            insertWithHeader(tenantA, "<vis-" + UUID.randomUUID() + ">", OffsetDateTime.now(ZoneOffset.UTC));
            try (Connection c = connect()) {
                asAppUser(c, tenantB);
                assertThat(scalar(c, "SELECT COUNT(*) FROM email_message WHERE tenant_id = ?", tenantA))
                        .isEqualTo(0L);
                assertThat(update(c, "UPDATE email_message SET delivery_status = 'X' WHERE tenant_id = ?", tenantA))
                        .isZero();
                assertThat(update(c, "DELETE FROM email_message WHERE tenant_id = ?", tenantA)).isZero();
            }
        }

        @Test
        @DisplayName("app_user bez GUC: SELECT = 0 wierszy bez błędu, INSERT odrzucony (42501)")
        void withoutGuc_selectIsEmpty_insertRejected() throws Exception {
            UUID tenantA = newTenant("rls-noguc");
            insertWithHeader(tenantA, "<noguc-" + UUID.randomUUID() + ">", OffsetDateTime.now(ZoneOffset.UTC));
            try (Connection c = connect()) {
                update(c, "SET ROLE app_user");
                assertThat(scalar(c, "SELECT COUNT(*) FROM email_message WHERE tenant_id = ?", tenantA))
                        .isEqualTo(0L);
                SQLException error = failureOf(c, """
                        INSERT INTO email_message (tenant_id, direction, from_address, to_address, message_at)
                        VALUES (?, 'INBOUND', 'a@x.com', 'b@x.com', now())
                        """, tenantA);
                assertThat(error.getSQLState()).isEqualTo("42501");
            }
        }
    }

    // =========================================================================================
    // Bezpośredni dostęp do partycji (DECYZJA WŁAŚCICIELA 2026-10-04, V102 sekcje 9b/10/12):
    // app_user NIE ma uprawnień do partycji, więc zapytanie po nazwie partycji (omijające RLS
    // tabeli nadrzędnej) kończy się permission denied (42501). Partycja utworzona przez
    // create_email_message_partition też nie dostaje GRANT-a (REVOKE w funkcji).
    // =========================================================================================

    @Nested
    @DisplayName("app_user: bezpośredni dostęp do partycji = permission denied (42501)")
    class DirectPartitionAccess {

        @Test
        @DisplayName("SELECT wprost z partycji bieżącego miesiąca odrzucony")
        void directSelect_onCurrentMonthPartition_isPermissionDenied() throws Exception {
            try (Connection c = connect()) {
                asAppUser(c, newTenant("direct-sel"));
                assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM " + currentMonthPartition()));
            }
        }

        @Test
        @DisplayName("INSERT wprost do partycji bieżącego miesiąca odrzucony (42501), mimo własnego tenanta w GUC")
        void directInsert_onCurrentMonthPartition_isPermissionDenied() throws Exception {
            UUID tenant = newTenant("direct-ins");
            try (Connection c = connect()) {
                asAppUser(c, tenant);
                String sql = """
                        INSERT INTO %s (tenant_id, direction, from_address, to_address, message_at)
                        VALUES (?, 'INBOUND', 'a@x.com', 'b@x.com', now())
                        """.formatted(currentMonthPartition());
                assertPermissionDenied(failureOf(c, sql, tenant));
            }
        }

        @Test
        @DisplayName("SELECT wprost z partycji email_message_default odrzucony")
        void directSelect_onDefaultPartition_isPermissionDenied() throws Exception {
            try (Connection c = connect()) {
                asAppUser(c, newTenant("direct-def"));
                assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM email_message_default"));
            }
        }

        @Test
        @DisplayName("partycja utworzona przez create_email_message_partition: brak uprawnień app_user + SELECT odrzucony")
        void partitionCreatedByFunction_hasNoGrantForAppUser() throws Exception {
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    exec(c, "SELECT create_email_message_partition(2032, 4)");
                    assertThat(scalarRaw(c,
                            "SELECT has_table_privilege('app_user', 'email_message_2032_04', 'SELECT, INSERT, UPDATE, DELETE')"))
                            .isEqualTo(false);
                    exec(c, "SET ROLE app_user");
                    assertPermissionDenied(failureOfStatement(c, "SELECT COUNT(*) FROM email_message_2032_04"));
                } finally {
                    c.rollback();
                }
            }
        }
    }

    // =========================================================================================
    // Dostęp przez tabelę nadrzędną po REVOKE na partycjach (regresja: RLS nadal działa, a REVOKE
    // nie psuje zapytań przez rodzica -- PostgreSQL nie sprawdza uprawnień partycji w tym trybie).
    // =========================================================================================

    @Nested
    @DisplayName("app_user przez tabelę nadrzędną po REVOKE na partycjach")
    class ViaParentAfterRevoke {

        @Test
        @DisplayName("SELECT przez rodzica zwraca własny wiersz z partycji bez GRANT-u; wiersz obcego tenanta niewidoczny")
        void selectThroughParent_returnsOwnRowOnly_fromPartitionWithoutGrant() throws Exception {
            UUID tenantA = newTenant("via-parent-a");
            UUID tenantB = newTenant("via-parent-b");
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            insertWithHeader(tenantA, "<via-a-" + UUID.randomUUID() + "@x>", now);
            insertWithHeader(tenantB, "<via-b-" + UUID.randomUUID() + "@x>", now);
            try (Connection c = connect()) {
                asAppUser(c, tenantA);
                assertThat(scalar(c, "SELECT COUNT(*) FROM email_message WHERE tenant_id IN (?, ?)", tenantA, tenantB))
                        .isEqualTo(1L);
                assertThat(scalar(c, "SELECT tableoid::regclass::text FROM email_message WHERE tenant_id = ?", tenantA))
                        .isEqualTo(currentMonthPartition());
            }
        }

        @Test
        @DisplayName("INSERT przez rodzica trafiający do _default działa bez GRANT-u na partycji (routing + RLS WITH CHECK)")
        void insertThroughParent_routedToDefault_worksWithoutGrant() throws Exception {
            UUID tenant = newTenant("via-parent-default");
            UUID id = UUID.randomUUID();
            try (Connection c = connect()) {
                asAppUser(c, tenant);
                assertThat(update(c, """
                        INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address,
                                                   message_id_header, message_at)
                        VALUES (?, ?, 'INBOUND', 'a@x.com', 'b@x.com', ?, ?)
                        """, id, tenant, "<via-default-" + id + "@x>", toTs(OffsetDateTime.parse("2001-05-01T00:00:00Z"))))
                        .isEqualTo(1);
                assertThat(scalar(c, "SELECT tableoid::regclass::text FROM email_message WHERE message_id = ?", id))
                        .isEqualTo("email_message_default");
            }
        }
    }

    // =========================================================================================
    // Funkcje partycjonujące (AC: create_next_month_partitions obejmuje email_message;
    // create_email_message_partition idempotentna). Test w TRANSAKCJI wycofywanej -- bez skutków ubocznych.
    // =========================================================================================

    @Nested
    @DisplayName("funkcje tworzenia partycji")
    class PartitionFunctions {

        @Test
        @DisplayName("create_next_month_partitions() odtwarza brakującą partycję email_message na następny miesiąc (ROLLBACK)")
        void createNextMonthPartitions_coversEmailMessage() throws Exception {
            YearMonth next = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
            String partition = "email_message_" + next.toString().replace("-", "_");
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    update(c, "DROP TABLE " + partition);
                    exec(c, "SELECT create_next_month_partitions()");
                    String bounds = (String) scalarRaw(c,
                            "SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE relname = '" + partition + "'");
                    assertThat(bounds).contains(next.atDay(1).toString());
                } finally {
                    c.rollback();
                }
            }
        }

        @Test
        @DisplayName("create_email_message_partition(y, m) jest idempotentna (drugie wywołanie nie zgłasza błędu)")
        void createEmailMessagePartition_isIdempotent() throws Exception {
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    exec(c, "SELECT create_email_message_partition(2032, 3)");
                    exec(c, "SELECT create_email_message_partition(2032, 3)");
                    assertThat(scalarRaw(c, "SELECT COUNT(*) FROM pg_class WHERE relname = 'email_message_2032_03'"))
                            .isEqualTo(1L);
                } finally {
                    c.rollback();
                }
            }
        }
    }

    // =========================================================================================
    // Indeksy na partycjach (AC: indeksy wtórne i unikalności obecne na KAŻDEJ partycji).
    // Planu EXPLAIN NIE asertujemy: na tabelach z 0-1 wierszem planner wybiera między równorzędnymi
    // indeksami (np. (tenant_id, message_at) zamiast unikalności) -- wybór planu przy wolumenie
    // zmierzono na scratch (500 tys. wierszy, 16 partycji) i jest w notatce DB-067 w TASKS-DATABASE.md.
    // =========================================================================================

    @Nested
    @DisplayName("indeksy propagowane do każdej partycji")
    class PartitionIndexes {

        @Test
        @DisplayName("każdy indeks wtórny i unikalność rodzica mają potomka na każdej partycji (w tym DEFAULT)")
        void everyParentIndex_hasChildIndexOnEveryPartition() {
            Integer partitions = superuserJdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_inherits WHERE inhparent = 'public.email_message'::regclass",
                    Integer.class);
            assertThat(partitions).as("3 miesiące + bieżący/+2 + DEFAULT").isGreaterThanOrEqualTo(4);

            for (String parentIndex : List.of("uq_email_message_id_header", "pk_email_message",
                    "idx_email_message_contact", "idx_email_message_delivery",
                    "idx_email_message_tenant_orphan_age", "idx_email_message_tenant_message_at")) {
                Integer children = superuserJdbc.queryForObject("""
                        SELECT COUNT(*) FROM pg_inherits
                        WHERE inhparent = (SELECT oid FROM pg_class WHERE relname = ?)
                        """, Integer.class, parentIndex);
                assertThat(children).as("potomkowie indeksu %s", parentIndex).isEqualTo(partitions);
            }
        }

        @Test
        @DisplayName("partycja DEFAULT ma własne potomki indeksów (routing awaryjny nie omija indeksów)")
        void defaultPartition_hasIndexes() {
            Integer count = superuserJdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_index i
                    WHERE i.indrelid = 'public.email_message_default'::regclass
                    """, Integer.class);
            assertThat(count).as("indeksy (w tym PK i unikalność) na email_message_default").isGreaterThanOrEqualTo(6);
        }
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
    }

    /** Ustawia rolę app_user (bez BYPASSRLS) i GUC tenanta na bieżącej sesji połączenia. */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, false)", tenantId.toString());
    }

    private static UUID newTenant(String tag) {
        UUID tenantId = UUID.randomUUID();
        superuserJdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, "Tenant " + tag + " " + tenantId);
        return tenantId;
    }

    private static UUID insertWithMessageAt(OffsetDateTime at) {
        UUID id = UUID.randomUUID();
        superuserJdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address,
                                           message_id_header, message_at)
                VALUES (?, ?, 'INBOUND', 'a@x.com', 'b@x.com', ?, ?)
                """, id, newTenant("routing"), "<at-" + id + "@x>", toTs(at));
        return id;
    }

    private static void insertWithHeader(UUID tenant, String header, OffsetDateTime at) {
        superuserJdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, direction, from_address, to_address,
                                           message_id_header, message_at)
                VALUES (?, ?, 'INBOUND', 'a@x.com', 'b@x.com', ?, ?)
                """, UUID.randomUUID(), tenant, header, toTs(at));
    }

    private static Timestamp toTs(OffsetDateTime odt) {
        return odt == null ? null : Timestamp.from(odt.toInstant());
    }

    private static SQLException failureOf(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        } catch (SQLException e) {
            return e;
        }
        fail("Oczekiwano błędu SQL, a statement się powiódł: " + sql);
        return null;
    }

    /** Nazwa partycji miesięcznej bieżącego miesiąca (UTC), zgodna z konwencją PartitionScanner. */
    private static String currentMonthPartition() {
        return "email_message_" + YearMonth.now(ZoneOffset.UTC).toString().replace("-", "_");
    }

    /** Jak {@link #failureOf}, ale dla dowolnego statementu (SELECT też); nie zwraca wyniku. */
    private static SQLException failureOfStatement(Connection c, String sql) {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            return e;
        }
        fail("Oczekiwano błędu SQL, a statement się powiódł: " + sql);
        return null;
    }

    /** Oczekuje SQLState 42501 (insufficient_privilege) z komunikatem "permission denied". */
    private static void assertPermissionDenied(SQLException error) {
        assertThat(error.getSQLState()).as("SQLState 42501 (insufficient_privilege)").isEqualTo("42501");
        assertThat(error.getMessage()).contains("permission denied");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (java.sql.Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private static Object scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    private static Object scalarRaw(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getObject(1);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
