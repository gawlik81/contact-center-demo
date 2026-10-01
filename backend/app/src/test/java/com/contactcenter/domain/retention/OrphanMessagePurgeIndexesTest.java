package com.contactcenter.domain.retention;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla migracji
 * DB-059 (indeksy {@code (tenant_id, wiek wiadomości)} pod sweep wiadomości OSIEROCONYCH
 * — {@code contact_id IS NULL} — na potrzeby przyszłego {@code BE-127}).
 *
 * <p><strong>Zakres celowo węższy niż {@code ContactRefIntegrityNarrowingTest}/
 * {@code AnonymizeCustomerExtensionTest}</strong> (brak porównania baz „pre"/„post"): DB-059 to
 * czysta migracja DDL (2× {@code CREATE INDEX IF NOT EXISTS} + {@code COMMENT ON INDEX}), bez
 * zmiany zachowania istniejącej funkcji/triggera — wystarczy dowód, że po migracji (1) oba indeksy
 * istnieją z dokładną, oczekiwaną definicją (w tym częściowe {@code WHERE contact_id IS NULL}) i
 * (2) planner faktycznie ich używa dla zapytania w kształcie z BE-127 (Index/Bitmap Scan, bez
 * Seq Scan). Regresję istniejących zapytań purge kontaktu (BE-125/BE-126: {@code idx_email_message_
 * contact}/{@code idx_social_message_contact}) pokrywają już {@code EmailMessagePurgeIntegrationTest}
 * i {@code SocialMessagePurgeIntegrationTest} (nested {@code QueryPlans}/{@code PlanAndDeprecation})
 * — ta migracja jest na tym samym classpath co te testy, więc ich przejście jest już dowodem braku
 * regresji.
 *
 * <p>Weryfikacja na większym wolumenie (≥ 200 tys. wierszy, 60 tenantów, ~20% osieroconych, pod
 * {@code SET ROLE app_user} z GUC {@code app.current_tenant_id}) wykonana ręcznie na bazie scratch
 * (DB-059, notatka wykonania w {@code TASKS-DATABASE.md}) — tu wolumen jest celowo mniejszy
 * (jeden świeży losowy tenant, wzorzec z {@code EmailMessagePurgeIntegrationTest#QueryPlans}), bo
 * cel testu integracyjnego to dowód doboru planu, nie pomiar skali.
 */
@DisplayName("Indeksy sweepu wiadomości osieroconych (DB-059, V097)")
class OrphanMessagePurgeIndexesTest {

    private static final String EMAIL_INDEX = "idx_email_message_tenant_orphan_age";
    private static final String SOCIAL_INDEX = "idx_social_message_tenant_orphan_sent";

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
    // Istnienie i definicja indeksów (WP-3 AC: "COMMENT ON INDEX z definicją wieku", "WHERE
    // contact_id IS NULL w definicji")
    // =========================================================================================

    @Nested
    @DisplayName("definicja indeksów po migracji")
    class IndexDefinitions {

        @Test
        @DisplayName("idx_email_message_tenant_orphan_age: (tenant_id, COALESCE(received_at, sent_at, created_at)) WHERE contact_id IS NULL")
        void emailIndex_hasExpectedDefinition() {
            String def = indexDef(EMAIL_INDEX);

            assertThat(def)
                    .as("definicja %s", EMAIL_INDEX)
                    .contains("USING btree (tenant_id, COALESCE(received_at, sent_at, created_at))")
                    .contains("WHERE (contact_id IS NULL)")
                    .contains("ON public.email_message");
        }

        @Test
        @DisplayName("idx_social_message_tenant_orphan_sent: (tenant_id, sent_at) WHERE contact_id IS NULL")
        void socialIndex_hasExpectedDefinition() {
            String def = indexDef(SOCIAL_INDEX);

            assertThat(def)
                    .as("definicja %s", SOCIAL_INDEX)
                    .contains("USING btree (tenant_id, sent_at)")
                    .contains("WHERE (contact_id IS NULL)")
                    // "ON ONLY public.social_message" od DB-065/V100 (tabela partycjonowana) --
                    // "ONLY" nie pojawia się na nie-partycjonowanych tabelach (np. email_message,
                    // dopóki DB-067 nie partycjonuje jej też), więc dopuszczamy oba warianty.
                    .containsPattern("ON (ONLY )?public\\.social_message");
        }

        @Test
        @DisplayName("oba indeksy mają COMMENT ON INDEX odwołujący się do DB-059 / BE-127")
        void bothIndexes_haveComment() {
            assertThat(indexComment(EMAIL_INDEX)).contains("DB-059").contains("BE-127")
                    .as("komentarz musi udokumentować, że wyrażenie COALESCE to definicja \"wieku wiadomości\"")
                    .containsIgnoringCase("wiek");
            assertThat(indexComment(SOCIAL_INDEX)).contains("DB-059").contains("BE-127");
        }

        private String indexDef(String indexName) {
            String def = jdbc.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                    String.class, indexName);
            assertThat(def).as("indeks %s musi istnieć po pełnym łańcuchu Flyway", indexName).isNotNull();
            return def;
        }

        private String indexComment(String indexName) {
            String comment = jdbc.queryForObject(
                    "SELECT obj_description(?::regclass, 'pg_class')", String.class, indexName);
            assertThat(comment).as("COMMENT ON INDEX %s", indexName).isNotNull();
            return comment;
        }
    }

    // =========================================================================================
    // Plan zapytania sweepu osieroconych (kształt BE-127 — jeszcze niezaimplementowany; wyrażenie
    // COALESCE zgodne z BE-124 §7 / DESIGN §3 D1)
    // =========================================================================================

    @Nested
    @DisplayName("plan zapytania sweepu osieroconych (BE-127, jeszcze niezaimplementowany)")
    class OrphanSweepQueryPlan {

        /** Kształt przyszłego zapytania BE-127 dla email_message (DESIGN §3 D1 / BE-124 §7). */
        private static final String EMAIL_ORPHAN_SWEEP_SQL = """
                SELECT message_id
                FROM email_message
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND contact_id IS NULL
                  AND COALESCE(received_at, sent_at, created_at) < :cutoff
                """;

        /** Kształt przyszłego zapytania BE-127 dla social_message (sent_at NOT NULL, bez COALESCE). */
        private static final String SOCIAL_ORPHAN_SWEEP_SQL = """
                SELECT message_id
                FROM social_message
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND contact_id IS NULL
                  AND sent_at < :cutoff
                """;

        @Test
        @DisplayName("email_message: sweep po COALESCE(received_at, sent_at, created_at) używa idx_email_message_tenant_orphan_age, bez Seq Scan")
        void emailOrphanSweep_usesNewIndex() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN DB-059 email " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO email_message
                                    (message_id, tenant_id, contact_id, direction, from_address, to_address,
                                     attachments, received_at, sent_at, created_at)
                                SELECT gen_random_uuid(), ?,
                                       CASE WHEN g % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                       CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                       'a@a.pl', 'b@b.pl', '[]'::jsonb,
                                       CASE WHEN g % 2 = 0 THEN now() - ((g % 200) || ' days')::interval ELSE NULL END,
                                       CASE WHEN g % 2 = 1 THEN now() - ((g % 200) || ' days')::interval ELSE NULL END,
                                       now() - ((g % 200) || ' days')::interval
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE email_message");

                String sql = EMAIL_ORPHAN_SWEEP_SQL
                        .replace(":tenantId", "'" + tenant + "'")
                        .replace(":cutoff", "(now() - interval '30 days')");

                String plan = explain(sql);

                assertThat(plan).contains(EMAIL_INDEX).doesNotContain("Seq Scan");
                System.out.println("[EXPLAIN email orphan sweep — DB-059/BE-127]\n" + plan);
            } finally {
                jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("social_message: sweep po sent_at używa idx_social_message_tenant_orphan_sent, bez Seq Scan")
        void socialOrphanSweep_usesNewIndex() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN DB-059 social " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO social_message
                                    (message_id, tenant_id, contact_id, platform, direction, external_message_id,
                                     attachments, sent_at, created_at)
                                SELECT gen_random_uuid(), ?,
                                       CASE WHEN g % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                       CAST('WHATSAPP' AS social_platform),
                                       CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                       'db059.' || g, '[]'::jsonb,
                                       now() - ((g % 200) || ' days')::interval,
                                       now() - ((g % 200) || ' days')::interval
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE social_message");

                String sql = SOCIAL_ORPHAN_SWEEP_SQL
                        .replace(":tenantId", "'" + tenant + "'")
                        .replace(":cutoff", "(now() - interval '30 days')");

                String plan = explain(sql);

                // DB-065/V100: social_message jest teraz partycjonowana -- EXPLAIN wypisuje nazwę
                // fizycznego indeksu POTOMNEGO partycji, nie nazwę indeksu rodzica (patrz javadoc
                // PostgresTestDatabase#explainUsesIndexOrItsPartitionChildren).
                assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plan, SOCIAL_INDEX))
                        .as("plan używa %s albo jego indeksu potomnego partycji", SOCIAL_INDEX)
                        .isTrue();
                assertThat(plan).doesNotContain("Seq Scan");
                System.out.println("[EXPLAIN social orphan sweep — DB-059/BE-127]\n" + plan);
            } finally {
                jdbc.update("DELETE FROM social_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("pod SET ROLE app_user + GUC tenanta: sweep nadal używa idx_email_message_tenant_orphan_age (RLS nie wymusza Seq Scan)")
        void underAppUserRole_emailOrphanSweep_stillUsesIndex() {
            String role = "cc_db059_orphan";
            String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN DB-059 RLS " + UUID.randomUUID());
            jdbc.update("""
                            INSERT INTO email_message
                                (message_id, tenant_id, contact_id, direction, from_address, to_address,
                                 attachments, received_at, sent_at, created_at)
                            SELECT gen_random_uuid(), ?,
                                   CASE WHEN g % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                   CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                   'a@a.pl', 'b@b.pl', '[]'::jsonb,
                                   CASE WHEN g % 2 = 0 THEN now() - ((g % 200) || ' days')::interval ELSE NULL END,
                                   CASE WHEN g % 2 = 1 THEN now() - ((g % 200) || ' days')::interval ELSE NULL END,
                                   now() - ((g % 200) || ' days')::interval
                            FROM generate_series(1, 30000) g
                            """, tenant);
            jdbc.execute("ANALYZE email_message");

            try (HikariDataSource restrictedPool = PostgresTestDatabase.pool(role, password, 1)) {
                JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
                restrictedJdbc.execute("BEGIN");
                try {
                    restrictedJdbc.execute("SET LOCAL ROLE " + role);
                    restrictedJdbc.queryForObject(
                            "SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());

                    String sql = EMAIL_ORPHAN_SWEEP_SQL
                            .replace(":tenantId", "'" + tenant + "'")
                            .replace(":cutoff", "(now() - interval '30 days')");
                    String plan = String.join("\n", restrictedJdbc.queryForList("EXPLAIN " + sql, String.class));

                    assertThat(plan).contains(EMAIL_INDEX).doesNotContain("Seq Scan");
                    System.out.println("[EXPLAIN email orphan sweep pod app_user — DB-059]\n" + plan);
                } finally {
                    restrictedJdbc.execute("ROLLBACK");
                }
            } finally {
                jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenant);
            }
        }

        private String explain(String sql) {
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        }
    }
}
