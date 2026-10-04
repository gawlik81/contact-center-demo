package com.contactcenter.domain.social;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny migracji V100 (DB-065): partycjonowanie RANGE tabeli {@code social_message}
 * po {@code sent_at}, na PRAWDZIWYM PostgreSQL (Testcontainers, pełny łańcuch Flyway — ten sam
 * współdzielony kontener co {@link SocialMessagePurgeIntegrationTest}/
 * {@link SocialMessageOrphanPurgeIntegrationTest}, {@link PostgresTestDatabase}).
 *
 * <p>Wzorzec „pojedyncza świeża baza" (jak {@code EmailSocialMessageRlsWritePoliciesTest}, DB-064) —
 * V100 DODAJE nową zdolność (partycjonowanie) na tabeli z 0 wierszy live, nie zmienia zachowania na
 * istniejących danych, więc dual-DB pre/post nie jest potrzebny.
 *
 * <p><strong>Partycje utworzone przez V100 są HARDCODOWANE na konkretne miesiące (2026-10/11/12 +
 * default)</strong> — zgodnie z zakresem DB-065 ("0 wierszy -&gt; bieżący miesiąc + 2 kolejne",
 * liczone względem daty napisania migracji, 2026-10-01). Testy routingu/pruningu używają więc
 * JAWNYCH znaczników czasu w tym zakresie (NIE {@code now()}), żeby być deterministyczne niezależnie
 * od tego, kiedy faktycznie uruchomi się {@code mvn test}.
 */
@DisplayName("social_message: partycjonowanie RANGE po sent_at (DB-065/V100)")
class SocialMessagePartitioningTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
    }

    @AfterAll
    static void stopContext() {
        pool.close();
    }

    // =========================================================================================
    // Kryterium akceptacji 3: struktura (RANGE po sent_at, PK złożony, FK, CHECK-i, enum)
    // =========================================================================================

    @Nested
    @DisplayName("struktura po swap-ie")
    class Structure {

        @Test
        @DisplayName("social_message jest tabelą partycjonowaną (relkind='p') RANGE po sent_at")
        void socialMessage_isPartitionedByRangeOnSentAt() {
            String relkind = jdbc.queryForObject(
                    "SELECT relkind FROM pg_class WHERE relname = 'social_message'", String.class);
            assertThat(relkind).isEqualTo("p");

            String partStrategy = jdbc.queryForObject("""
                    SELECT partstrat FROM pg_partitioned_table
                    WHERE partrelid = 'social_message'::regclass
                    """, String.class);
            assertThat(partStrategy).as("'r' = RANGE").isEqualTo("r");

            String partKeyColumn = jdbc.queryForObject("""
                    SELECT a.attname
                    FROM pg_partitioned_table p
                    JOIN pg_attribute a ON a.attrelid = p.partrelid AND a.attnum = p.partattrs[0]
                    WHERE p.partrelid = 'social_message'::regclass
                    """, String.class);
            assertThat(partKeyColumn).isEqualTo("sent_at");
        }

        @Test
        @DisplayName("PK złożony (message_id, sent_at); UNIQUE złożony (tenant_id, external_message_id, sent_at)")
        void primaryKeyAndUniqueConstraint_areComposite() {
            List<String> pkColumns = jdbc.queryForList("""
                    SELECT a.attname
                    FROM pg_constraint c
                    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.conrelid = 'social_message'::regclass AND c.contype = 'p'
                    ORDER BY k.ord
                    """, String.class);
            assertThat(pkColumns).containsExactly("message_id", "sent_at");

            List<String> uniqueColumns = jdbc.queryForList("""
                    SELECT a.attname
                    FROM pg_constraint c
                    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON true
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                    WHERE c.conname = 'uq_social_message_external_id'
                    ORDER BY k.ord
                    """, String.class);
            assertThat(uniqueColumns).containsExactly("tenant_id", "external_message_id", "sent_at");
        }

        @Test
        @DisplayName("FK tenant (RESTRICT) i social_integration (SET NULL) zachowane 1:1")
        void foreignKeys_arePreserved() {
            // Filtr conrelid = social_message (RODZIC) -- FK propaguje własny wiersz pg_constraint
            // (ta sama nazwa) do KAŻDEJ partycji, więc bez tego filtra zapytanie zwraca >1 wiersz
            // (inne testy/klasy współdzielące kontener mogły już utworzyć dodatkowe partycje).
            String tenantFkDeleteRule = jdbc.queryForObject("""
                    SELECT confdeltype FROM pg_constraint
                    WHERE conname = 'fk_social_message_tenant' AND conrelid = 'social_message'::regclass
                    """, String.class);
            assertThat(tenantFkDeleteRule).as("'r' = ON DELETE RESTRICT").isEqualTo("r");

            String integrationFkDeleteRule = jdbc.queryForObject("""
                    SELECT confdeltype FROM pg_constraint
                    WHERE conname = 'fk_social_message_integration' AND conrelid = 'social_message'::regclass
                    """, String.class);
            assertThat(integrationFkDeleteRule).as("'n' = ON DELETE SET NULL").isEqualTo("n");
        }

        @Test
        @DisplayName("CHECK-i chk_social_attachments_is_array i chk_social_message_direction zachowane; enum social_platform bez zmian")
        void checksAndEnum_arePreserved() {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = 'chk_social_attachments_is_array' AND conrelid = 'social_message'::regclass",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_constraint WHERE conname = 'chk_social_message_direction' AND conrelid = 'social_message'::regclass",
                    Integer.class)).isEqualTo(1);

            List<String> enumLabels = jdbc.queryForList("""
                    SELECT enumlabel FROM pg_enum
                    WHERE enumtypid = 'social_platform'::regtype
                    ORDER BY enumsortorder
                    """, String.class);
            assertThat(enumLabels).containsExactly("FACEBOOK", "INSTAGRAM", "WHATSAPP");
        }

        @Test
        @DisplayName("Indeksy wtórne odtworzone pod finalnymi nazwami: contact, sender, orphan (DB-059), tenant_sent_at (DB-065)")
        void secondaryIndexes_arePreservedUnderFinalNames() {
            List<String> indexNames = jdbc.queryForList("""
                    SELECT indexname FROM pg_indexes WHERE tablename = 'social_message' ORDER BY indexname
                    """, String.class);
            assertThat(indexNames).contains(
                    "idx_social_message_contact",
                    "idx_social_message_sender",
                    "idx_social_message_tenant_orphan_sent",
                    "idx_social_message_tenant_sent_at",
                    "pk_social_message");
        }

        @Test
        @DisplayName("RLS: FORCE ROW LEVEL SECURITY + polityka social_message_tenant_isolation (ALL, WITH CHECK, app.current_tenant_id) — identyczna z V099")
        void rls_isForceAllWithCheck_identicalToV099() {
            assertThat(jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'social_message'", Boolean.class))
                    .isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT cmd FROM pg_policies WHERE tablename = 'social_message' AND policyname = 'social_message_tenant_isolation'",
                    String.class)).isEqualTo("ALL");

            assertThat(jdbc.queryForObject(
                    "SELECT with_check FROM pg_policies WHERE tablename = 'social_message' AND policyname = 'social_message_tenant_isolation'",
                    String.class)).contains("app.current_tenant_id");
        }

        @Test
        @DisplayName("v_customer_timeline (V017/V025) nadal istnieje i odpytuje się bez błędu po swap-ie tabeli")
        void customerTimelineView_survivesTheSwap() {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.views WHERE table_name = 'v_customer_timeline'",
                    Integer.class)).isEqualTo(1);

            // Smoke-test: zapytanie się wykonuje (nie sprawdzamy konkretnych wierszy — inne klasy
            // testowe współdzielą ten sam kontener i mogą mieć własne dane).
            jdbc.queryForList("SELECT * FROM v_customer_timeline LIMIT 1");
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 2 + test regresyjny kluczowy: routing/pruning partycji po sent_at
    // =========================================================================================

    @Nested
    @DisplayName("routing i pruning partycji")
    class PartitionRouting {

        @Test
        @DisplayName("wiersz z sent_at w bieżącym (wg migracji) miesiącu trafia do partycji miesięcznej, NIE do social_message_default")
        void rowInCoveredMonth_routesToMonthlyPartition_notDefault() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant routing V100 " + UUID.randomUUID());
            UUID messageId = insertMessage(tenant, "WHATSAPP", "ext-routing-" + UUID.randomUUID(),
                    Instant.parse("2026-10-15T10:00:00Z"));

            String partition = jdbc.queryForObject(
                    "SELECT tableoid::regclass::text FROM social_message WHERE message_id = ?", String.class, messageId);
            assertThat(partition).isEqualTo("social_message_2026_10");
        }

        @Test
        @DisplayName("wiersze z sent_at w listopadzie/grudniu 2026 trafiają do odpowiednich partycji miesięcznych")
        void rowsInNovemberAndDecember_routeToTheirMonthlyPartitions() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant routing NovDec V100 " + UUID.randomUUID());

            UUID novMessage = insertMessage(tenant, "FACEBOOK", "ext-nov-" + UUID.randomUUID(),
                    Instant.parse("2026-11-05T08:30:00Z"));
            UUID decMessage = insertMessage(tenant, "INSTAGRAM", "ext-dec-" + UUID.randomUUID(),
                    Instant.parse("2026-12-25T23:59:00Z"));

            assertThat(partitionOf(novMessage)).isEqualTo("social_message_2026_11");
            assertThat(partitionOf(decMessage)).isEqualTo("social_message_2026_12");
        }

        @Test
        @DisplayName("wiersz z sent_at SPOZA utworzonych zakresów miesięcznych trafia do social_message_default")
        void rowOutsideCoveredRange_routesToDefaultPartition() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant routing default V100 " + UUID.randomUUID());
            UUID messageId = insertMessage(tenant, "WHATSAPP", "ext-outside-" + UUID.randomUUID(),
                    Instant.parse("2025-01-15T00:00:00Z"));

            assertThat(partitionOf(messageId)).isEqualTo("social_message_default");
        }

        @Test
        @DisplayName("EXPLAIN zapytania z filtrem sent_at w jednym miesiącu dotyka WYŁĄCZNIE tej jednej partycji (pruning)")
        void queryFilteredBySentAtRange_pruningVisibleInExplain() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN pruning V100 " + UUID.randomUUID());
            insertMessage(tenant, "WHATSAPP", "ext-explain-" + UUID.randomUUID(),
                    Instant.parse("2026-10-10T00:00:00Z"));

            String plan = String.join("\n", jdbc.queryForList(
                    "EXPLAIN SELECT * FROM social_message WHERE sent_at >= '2026-10-01' AND sent_at < '2026-11-01'",
                    String.class));

            assertThat(plan)
                    .as("plan dotyka tylko partycji 2026_10, bez listopada/grudnia/default")
                    .contains("social_message_2026_10")
                    .doesNotContain("social_message_2026_11")
                    .doesNotContain("social_message_2026_12")
                    .doesNotContain("social_message_default");
            System.out.println("[EXPLAIN DB-065 pruning po sent_at]\n" + plan);
        }

        private String partitionOf(UUID messageId) {
            return jdbc.queryForObject(
                    "SELECT tableoid::regclass::text FROM social_message WHERE message_id = ?", String.class, messageId);
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 7: unikalność złożona — WhatsApp (deterministyczny sent_at) chroniony,
    // Facebook/Instagram (sent_at = Instant.now() do czasu BE-132) NIE chronione — udokumentowane
    // ograniczenie, patrz OSTRZEŻENIE w nagłówku V100.
    // =========================================================================================

    @Nested
    @DisplayName("unikalność złożona (tenant_id, external_message_id, sent_at)")
    class CompositeUniqueness {

        @Test
        @DisplayName("ten sam (tenant_id, external_message_id, sent_at) -> druga INSERT narusza unikalność")
        void sameTripleTwice_violatesUniqueConstraint() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant unique dup V100 " + UUID.randomUUID());
            String externalId = "ext-dup-" + UUID.randomUUID();
            Instant sentAt = Instant.parse("2026-10-20T12:00:00Z");

            insertMessage(tenant, "WHATSAPP", externalId, sentAt);

            assertThatThrownBy(() -> insertMessage(tenant, "WHATSAPP", externalId, sentAt))
                    .isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        @DisplayName("sam external_message_id, RÓŻNY sent_at -> OBIE INSERT przechodzą (ograniczenie D4 dla FB/IG do czasu BE-132)")
        void sameExternalIdDifferentSentAt_bothRowsPersist() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant unique weak V100 " + UUID.randomUUID());
            String externalId = "ext-weak-" + UUID.randomUUID();

            UUID first = insertMessage(tenant, "FACEBOOK", externalId, Instant.parse("2026-10-20T12:00:00Z"));
            UUID second = insertMessage(tenant, "FACEBOOK", externalId, Instant.parse("2026-10-20T12:00:05Z"));

            assertThat(first).isNotEqualTo(second);
            Integer rowsWithThatExternalId = jdbc.queryForObject(
                    "SELECT count(*) FROM social_message WHERE external_message_id = ? AND tenant_id = ?",
                    Integer.class, externalId, tenant);
            assertThat(rowsWithThatExternalId)
                    .as("DB-065 OSTRZEŻENIE: redelivery FB/IG z innym sent_at NIE jest wykrywane przez "
                            + "unikalność złożoną — jedyna dzisiejsza obrona to dedup aplikacyjny "
                            + "SocialMessageServiceImpl#processIncomingMessage -> findByExternalMessageId")
                    .isEqualTo(2);
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 4: RLS przez tabelę nadrzędną po partycjonowaniu
    // =========================================================================================

    @Nested
    @DisplayName("RLS po partycjonowaniu — przez tabelę nadrzędną, SET ROLE app_user")
    class RowLevelSecurity {

        @Test
        @DisplayName("app_user + GUC własnego tenanta: INSERT przez social_message (rodzic) działa i routuje do właściwej partycji")
        void ownTenant_insertThroughParent_worksAndRoutesCorrectly() {
            String role = "cc_db065_rls_own";
            String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant RLS own V100 " + UUID.randomUUID());

            try (HikariDataSource restrictedPool = PostgresTestDatabase.pool(role, password, 1)) {
                JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
                restrictedJdbc.execute("BEGIN");
                try {
                    restrictedJdbc.execute("SET LOCAL ROLE " + role);
                    restrictedJdbc.queryForObject(
                            "SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());

                    restrictedJdbc.update("""
                            INSERT INTO social_message (tenant_id, platform, direction, external_message_id, sent_at)
                            VALUES (?, 'WHATSAPP', 'INBOUND', ?, '2026-10-15T10:00:00Z')
                            """, tenant, "ext-rls-own-" + UUID.randomUUID());

                    String partition = restrictedJdbc.queryForObject("""
                            SELECT tableoid::regclass::text FROM social_message WHERE tenant_id = ?
                            """, String.class, tenant);
                    assertThat(partition).isEqualTo("social_message_2026_10");
                } finally {
                    restrictedJdbc.execute("ROLLBACK");
                }
            }
        }

        @Test
        @DisplayName("app_user + GUC innego tenanta: INSERT z tenant_id tenanta A przez tabelę nadrzędną odrzucony (42501)")
        void crossTenantInsertThroughParent_isRejected() {
            String role = "cc_db065_rls_cross";
            String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
            UUID tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A RLS cross V100 " + UUID.randomUUID());
            UUID tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B RLS cross V100 " + UUID.randomUUID());

            try (HikariDataSource restrictedPool = PostgresTestDatabase.pool(role, password, 1)) {
                JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
                restrictedJdbc.execute("BEGIN");
                try {
                    restrictedJdbc.execute("SET LOCAL ROLE " + role);
                    restrictedJdbc.queryForObject(
                            "SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenantB.toString());

                    Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> restrictedJdbc.update("""
                            INSERT INTO social_message (tenant_id, platform, direction, external_message_id, sent_at)
                            VALUES (?, 'WHATSAPP', 'INBOUND', ?, '2026-10-15T10:00:00Z')
                            """, tenantA, "ext-rls-cross-" + UUID.randomUUID()));

                    assertThat(error).as("INSERT cross-tenant musi być odrzucony").isNotNull();
                    // Spring klasyfikuje SQLState 42501 (insufficient_privilege) generycznie jako
                    // BadSqlGrammarException (fallback SQLStateSQLExceptionTranslator, klasa "42" ==
                    // "syntax error or access rule violation") -- komunikat RLS jest w PRZYCZYNIE
                    // (PSQLException), nie w komunikacie zewnętrznego wyjątku Springa.
                    Throwable root = error;
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertThat(root.getMessage()).contains("row-level security policy");
                } finally {
                    restrictedJdbc.execute("ROLLBACK");
                }
            }
        }
    }

    // =========================================================================================
    // Kryterium akceptacji 5: create_social_message_partition / create_next_month_partitions /
    // brak drop_old_social_message_partitions wykonywalnej
    // =========================================================================================

    @Nested
    @DisplayName("funkcje rotacji partycji")
    class PartitionFunctions {

        @Test
        @DisplayName("create_social_message_partition(2027, 6) tworzy partycję z propagowanymi indeksami/CHECK/FK")
        void createSocialMessagePartition_createsPartitionWithIndexes() {
            jdbc.execute("SELECT create_social_message_partition(2027, 6)");

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE tablename = 'social_message_2027_06'", Integer.class))
                    .isEqualTo(1);

            String bound = jdbc.queryForObject("""
                    SELECT pg_get_expr(c.relpartbound, c.oid)
                    FROM pg_class c WHERE c.relname = 'social_message_2027_06'
                    """, String.class);
            assertThat(bound).contains("2027-06-01").contains("2027-07-01");

            Integer indexCount = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = 'social_message_2027_06'", Integer.class);
            assertThat(indexCount).as("PK + 4 indeksy wtórne propagowane z rodzica").isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("create_social_message_partition jest idempotentna (drugie wywołanie nie zgłasza błędu)")
        void createSocialMessagePartition_isIdempotent() {
            jdbc.execute("SELECT create_social_message_partition(2027, 7)");
            jdbc.execute("SELECT create_social_message_partition(2027, 7)");

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_tables WHERE tablename = 'social_message_2027_07'", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("create_next_month_partitions() obejmuje WSZYSTKIE 7 tabel partycjonowanych, w tym social_message")
        void createNextMonthPartitions_coversAllSevenPartitionedTables() {
            String body = jdbc.queryForObject(
                    "SELECT pg_get_functiondef('create_next_month_partitions()'::regprocedure)", String.class);

            assertThat(body)
                    .contains("create_audit_log_partition")
                    .contains("create_contact_partition")
                    .contains("create_plugin_invocation_log_partition")
                    .contains("create_contact_event_partition")
                    .contains("create_contact_transcription_partition")
                    .contains("create_contact_ai_summary_partition")
                    .contains("create_social_message_partition");

            long performCount = body.lines().filter(l -> l.trim().startsWith("PERFORM create_")).count();
            assertThat(performCount).as("7 tabel partycjonowanych po DB-065").isEqualTo(7);
        }

        @Test
        @DisplayName("brak wykonywalnej drop_old_social_message_partitions/rotate_social_message_partitions (DROP idzie przez PartitionReclaimJob, BE-133)")
        void dropAndRotateFunctions_doNotExist() {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_proc WHERE proname IN
                        ('drop_old_social_message_partitions', 'rotate_social_message_partitions')
                    """, Integer.class)).isZero();
        }
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private UUID insertMessage(UUID tenant, String platform, String externalMessageId, Instant sentAt) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO social_message
                            (message_id, tenant_id, platform, direction, external_message_id, sent_at)
                        VALUES (?, ?, CAST(? AS social_platform), 'INBOUND', ?, ?)
                        """,
                messageId, tenant, platform, externalMessageId, Timestamp.from(sentAt));
        return messageId;
    }
}
