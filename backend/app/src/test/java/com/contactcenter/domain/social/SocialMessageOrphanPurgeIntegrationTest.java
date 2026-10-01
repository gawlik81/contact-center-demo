package com.contactcenter.domain.social;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.infrastructure.social.SocialAdapterRegistry;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny sweepu wiadomości social OSIEROCONYCH ({@code contact_id IS NULL}) wg wieku
 * ({@link SocialMessageService#purgeOrphansOlderThan}/{@link SocialMessageService#countOrphansOlderThan},
 * BE-127, EPIC-30) na PRAWDZIWYM PostgreSQL (Testcontainers, pełny łańcuch Flyway — indeksy DB-059/V097
 * już tam są). Bez S3 (domena social nie ma obiektów w S3 — jak {@link SocialMessagePurgeIntegrationTest}).
 *
 * <p>EXPLAIN na wolumenie ≥ 200 tys. wierszy pod {@code SET ROLE app_user} wykonano RĘCZNIE na bazie
 * scratch {@code scratch_be127} — patrz Javadoc {@code EmailMessageOrphanPurgeIntegrationTest} i
 * notatka wykonania BE-127 w {@code TASKS-BACKEND.md}. {@link QueryPlans} tutaj odtwarza dowód na
 * mniejszym wolumenie (30 tys.), jak {@code OrphanMessagePurgeIndexesTest} z DB-059.
 */
@DisplayName("SocialMessageService.purgeOrphansOlderThan/countOrphansOlderThan – prawdziwa baza (BE-127)")
class SocialMessageOrphanPurgeIntegrationTest {

    private static final String SOCIAL_ORPHAN_INDEX = "idx_social_message_tenant_orphan_sent";

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static SocialMessageService service;
    private static SocialMessageRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        // SocialMessageServiceImpl ma 5 zależności konstruktorowych (@RequiredArgsConstructor); dla
        // metod BE-127 (countOrphansOlderThan/purgeOrphansOlderThan) używana jest WYŁĄCZNIE
        // socialMessageRepository — pozostałe 4 rejestrujemy jako mocki Mockito, nigdy niewywoływane
        // w tych testach (wzorzec: prawdziwy bean tam, gdzie test sprawdza logikę; mock tam, gdzie
        // Spring wymaga tylko obecności zależności do skonstruowania beana).
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{SocialMessage.class},
                new Class<?>[]{SocialMessageRepository.class, SocialMessageServiceImpl.class},
                c -> {
                    c.getBeanFactory().registerSingleton("socialIntegrationRepository", mock(SocialIntegrationRepository.class));
                    c.getBeanFactory().registerSingleton("contactService", mock(ContactService.class));
                    c.getBeanFactory().registerSingleton("socialAdapterRegistry", mock(SocialAdapterRegistry.class));
                    c.getBeanFactory().registerSingleton("rabbitTemplate", mock(RabbitTemplate.class));
                });
        service = ctx.getBean(SocialMessageService.class);
        repository = ctx.getBean(SocialMessageRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – social BE-127 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – social BE-127 " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID insertMessage(UUID tenant, UUID contact, Instant sentAt, Instant createdAt) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO social_message
                            (message_id, tenant_id, contact_id, platform, direction, external_message_id,
                             sender_external_id, content, attachments, sent_at, created_at)
                        VALUES (?, ?, ?, CAST('WHATSAPP' AS social_platform), 'INBOUND', ?, '48123456789',
                                'Treść PII', '[]'::jsonb, ?, ?)
                        """,
                messageId, tenant, contact, "mid." + messageId, Timestamp.from(sentAt), Timestamp.from(createdAt));
        return messageId;
    }

    private boolean exists(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM social_message WHERE message_id = ?", Long.class, messageId) > 0;
    }

    /** Wstawia bare-bones {@code contact} (BE-128: kontakt „kwalifikujący się" / „nie kwalifikujący się"). */
    private UUID insertContact(UUID tenant, Instant startedAt) {
        UUID contactId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, channel, direction, status, started_at, queued_at)
                        VALUES (?, ?, 'SOCIAL_WHATSAPP', 'INBOUND', 'COMPLETED', ?, ?)
                        """,
                contactId, tenant, Timestamp.from(startedAt), Timestamp.from(startedAt));
        return contactId;
    }

    // =========================================================================
    // Mieszanka AC (WP-1)
    // =========================================================================

    @Nested
    @DisplayName("mieszanka AC — kryterium wieku, izolacja tenantów")
    class AcMixture {

        @Test
        @DisplayName("osierocona stara USUNIĘTA, powiązana ZOSTAJE, stara osierocona tenanta B ZOSTAJE (izolacja)")
        void mixture_onlyOldOrphanOfTenantAIsPurged() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID oldOrphan = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));
            UUID linked = insertMessage(tenantA, UUID.randomUUID(), cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));
            UUID oldOrphanTenantB = insertMessage(tenantB, null, cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));

            OrphanSocialPurgeBatch batch = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(batch.candidatesFound()).isEqualTo(1);
            assertThat(batch.deletedRows()).isEqualTo(1);
            assertThat(exists(oldOrphan)).isFalse();
            assertThat(exists(linked)).isTrue();
            assertThat(exists(oldOrphanTenantB)).isTrue();
        }

        @Test
        @DisplayName("granica cutoff: sent_at DOKŁADNIE na cutoff jest WYKLUCZONY (semantyka <)")
        void cutoffBoundary_isExclusive() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID atCutoff = insertMessage(tenantA, null, cutoff, cutoff);
            UUID beforeCutoff = insertMessage(tenantA, null, cutoff.minusSeconds(1), cutoff.minusSeconds(1));

            OrphanSocialPurgeBatch batch = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(batch.candidatesFound()).isEqualTo(1);
            assertThat(exists(atCutoff)).isTrue();
            assertThat(exists(beforeCutoff)).isFalse();
        }

        @Test
        @DisplayName("BEZ filtra resztkowego: sierota z created_at świeżym (< 1 dzień) ale sent_at przed cutoff JEST usuwana (w odróżnieniu od e-mail)")
        void noResidualFilter_freshlyInsertedOldSentAt_isStillPurged() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID orphan = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), Instant.now());

            OrphanSocialPurgeBatch batch = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(batch.candidatesFound()).isEqualTo(1);
            assertThat(exists(orphan)).isFalse();
        }
    }

    // =========================================================================
    // countOrphansOlderThan
    // =========================================================================

    @Nested
    @DisplayName("countOrphansOlderThan (dry-run)")
    class CountOrphans {

        @Test
        @DisplayName("liczy dokładnie kandydatów sierocych starszych niż cutoff")
        void count_matchesPurgeCandidates() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            for (int i = 0; i < 4; i++) {
                insertMessage(tenantA, null, cutoff.minus(i + 1, ChronoUnit.DAYS), cutoff.minus(i + 1, ChronoUnit.DAYS));
            }
            insertMessage(tenantA, UUID.randomUUID(), cutoff.minus(1, ChronoUnit.DAYS), cutoff.minus(1, ChronoUnit.DAYS));

            assertThat(service.countOrphansOlderThan(tenantA, cutoff)).isEqualTo(4);
        }

        @Test
        @DisplayName("brak kandydatów -> 0")
        void noCandidates_returnsZero() {
            assertThat(service.countOrphansOlderThan(tenantA, Instant.now())).isZero();
        }
    }

    // =========================================================================
    // Idempotencja
    // =========================================================================

    @Nested
    @DisplayName("idempotencja")
    class Idempotency {

        @Test
        @DisplayName("ponowne wywołanie po sukcesie -> candidatesFound=0")
        void secondCallAfterSuccess_isNoOp() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));

            service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);
            OrphanSocialPurgeBatch second = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(second.candidatesFound()).isZero();
            assertThat(second.deletedRows()).isZero();
        }
    }

    // =========================================================================
    // Stronicowanie keyset
    // =========================================================================

    @Nested
    @DisplayName("stronicowanie keyset")
    class KeysetPagination {

        @Test
        @DisplayName("druga i trzecia strona nie powtarzają ani nie gubią wierszy")
        void keysetPagination_noOverlapNoGaps() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                Instant t = cutoff.minus(i + 1, ChronoUnit.MINUTES);
                ids.add(insertMessage(tenantA, null, t, t));
            }

            OrphanSocialPurgeBatch page1 = service.purgeOrphansOlderThan(tenantA, null, cutoff, 10);
            assertThat(page1.candidatesFound()).isEqualTo(10);
            OrphanSocialPurgeBatch page2 = service.purgeOrphansOlderThan(tenantA, page1.nextCursor(), cutoff, 10);
            assertThat(page2.candidatesFound()).isEqualTo(10);
            OrphanSocialPurgeBatch page3 = service.purgeOrphansOlderThan(tenantA, page2.nextCursor(), cutoff, 10);
            assertThat(page3.candidatesFound()).isEqualTo(5);

            int totalDeleted = page1.deletedRows() + page2.deletedRows() + page3.deletedRows();
            assertThat(totalDeleted).isEqualTo(25);
            assertThat(ids).allSatisfy(id -> assertThat(exists(id)).isFalse());
        }
    }

    // =========================================================================
    // countLinkedToContactsOlderThan (BE-128) – wiadomości POWIĄZANE z kontaktem kwalifikującym się
    // =========================================================================

    @Nested
    @DisplayName("countLinkedToContactsOlderThan (BE-128)")
    class CountLinkedToContacts {

        @Test
        @DisplayName("liczy WYŁĄCZNIE wiadomości powiązane z kontaktem starszym niż cutoff — młodszy kontakt i sierota wykluczone")
        void countsOnlyMessagesOfEligibleContacts() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID oldContact = insertContact(tenantA, cutoff.minus(10, ChronoUnit.DAYS));
            UUID youngContact = insertContact(tenantA, cutoff.plus(10, ChronoUnit.DAYS));

            insertMessage(tenantA, oldContact, Instant.now(), Instant.now());
            insertMessage(tenantA, oldContact, cutoff.minus(5, ChronoUnit.DAYS), cutoff.minus(5, ChronoUnit.DAYS));
            insertMessage(tenantA, youngContact, cutoff.minus(5, ChronoUnit.DAYS), cutoff.minus(5, ChronoUnit.DAYS));
            insertMessage(tenantA, null, cutoff.minus(5, ChronoUnit.DAYS), cutoff.minus(5, ChronoUnit.DAYS));

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(2);
        }

        @Test
        @DisplayName("granica cutoff: started_at kontaktu DOKŁADNIE na cutoff jest WYKLUCZONY (semantyka <)")
        void contactCutoffBoundary_isExclusive() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID atCutoff = insertContact(tenantA, cutoff);
            UUID beforeCutoff = insertContact(tenantA, cutoff.minusSeconds(1));
            insertMessage(tenantA, atCutoff, Instant.now(), Instant.now());
            insertMessage(tenantA, beforeCutoff, Instant.now(), Instant.now());

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(1);
        }

        @Test
        @DisplayName("izolacja tenantów: kontakt+wiadomość tenanta B nie wchodzą do liczby tenanta A")
        void isolatesByTenant() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID contactA = insertContact(tenantA, cutoff.minus(10, ChronoUnit.DAYS));
            UUID contactB = insertContact(tenantB, cutoff.minus(10, ChronoUnit.DAYS));
            insertMessage(tenantA, contactA, Instant.now(), Instant.now());
            insertMessage(tenantB, contactB, Instant.now(), Instant.now());

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(1);
        }

        @Test
        @DisplayName("brak kandydatów -> 0, nie wyjątek")
        void noCandidates_returnsZero() {
            assertThat(service.countLinkedToContactsOlderThan(tenantA, Instant.now())).isZero();
        }
    }

    // =========================================================================
    // TenantContext (WP-2)
    // =========================================================================

    @Nested
    @DisplayName("TenantContext (WP-2)")
    class TenantContextHandling {

        @Test
        @DisplayName("pusty TenantContext -> IllegalStateException; wiadomości nietknięte")
        void emptyTenantContext_throwsIllegalState() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID m = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));
            TenantContext.clear();

            assertThatThrownBy(() -> service.purgeOrphansOlderThan(tenantA, null, cutoff, 100))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.countOrphansOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.findOrphansOlderThan(tenantA, cutoff, null, 100))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(exists(m)).isTrue();
        }

        @Test
        @DisplayName("(BE-128) pusty TenantContext -> IllegalStateException dla countLinkedToContactsOlderThan (serwis i repozytorium) — regresja BE-112")
        void emptyTenantContext_throwsIllegalState_countLinkedToContacts() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            TenantContext.clear();

            assertThatThrownBy(() -> service.countLinkedToContactsOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.countLinkedToContactsOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("purgeOrphansOlderThan NIGDY nie czyści TenantContext")
        void tenantContextSurvivesPurge() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), cutoff.minus(10, ChronoUnit.DAYS));

            service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(tenantA);
        }
    }

    // =========================================================================
    // Plan zapytań (EXPLAIN)
    // =========================================================================

    @Nested
    @DisplayName("plan zapytań (EXPLAIN)")
    class QueryPlans {

        @Test
        @DisplayName("COUNT_ORPHANS_SQL / FIND_ORPHANS_FIRST_PAGE_SQL / FIND_ORPHANS_NEXT_PAGE_SQL używają idx_social_message_tenant_orphan_sent, bez Seq Scan")
        void explain_usesOrphanIndex() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN BE-127 social " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO social_message
                                    (message_id, tenant_id, contact_id, platform, direction, external_message_id,
                                     attachments, sent_at, created_at)
                                SELECT gen_random_uuid(), ?,
                                       CASE WHEN abs(hashtext('orphan-' || g)) % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                       CAST('WHATSAPP' AS social_platform),
                                       CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                       'be127.' || g, '[]'::jsonb,
                                       now() - ((g % 400) || ' days')::interval,
                                       now() - ((g % 400) || ' days')::interval
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE social_message");

                String tenantLiteral = "'" + tenant + "'";
                String cutoff = "(now() - interval '180 days')";
                String countSql = SocialMessageRepository.COUNT_ORPHANS_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff);
                String firstPageSql = SocialMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff).replace(":batchSize", "100");
                String nextPageSql = SocialMessageRepository.FIND_ORPHANS_NEXT_PAGE_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff)
                        .replace(":cursorMessageAt", "(now() - interval '190 days')")
                        .replace(":cursorMessageId", "'00000000-0000-0000-0000-000000000000'")
                        .replace(":batchSize", "100");

                String countPlan = explain(countSql);
                String firstPagePlan = explain(firstPageSql);
                String nextPagePlan = explain(nextPageSql);

                // DB-065/V100: social_message jest teraz partycjonowana -- EXPLAIN wypisuje nazwę
                // fizycznego indeksu POTOMNEGO partycji, nie nazwę indeksu rodzica (patrz javadoc
                // PostgresTestDatabase#explainUsesIndexOrItsPartitionChildren).
                assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, countPlan, SOCIAL_ORPHAN_INDEX))
                        .as("COUNT plan używa %s albo jego indeksu potomnego partycji", SOCIAL_ORPHAN_INDEX).isTrue();
                assertThat(countPlan).doesNotContain("Seq Scan");
                assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, firstPagePlan, SOCIAL_ORPHAN_INDEX))
                        .as("FIRST PAGE plan używa %s albo jego indeksu potomnego partycji", SOCIAL_ORPHAN_INDEX).isTrue();
                assertThat(firstPagePlan).doesNotContain("Seq Scan");
                assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, nextPagePlan, SOCIAL_ORPHAN_INDEX))
                        .as("NEXT PAGE plan używa %s albo jego indeksu potomnego partycji", SOCIAL_ORPHAN_INDEX).isTrue();
                assertThat(nextPagePlan).doesNotContain("Seq Scan");
                System.out.println("[EXPLAIN BE-127 social COUNT]\n" + countPlan);
                System.out.println("[EXPLAIN BE-127 social FIRST PAGE]\n" + firstPagePlan);
                System.out.println("[EXPLAIN BE-127 social NEXT PAGE]\n" + nextPagePlan);
            } finally {
                jdbc.update("DELETE FROM social_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("pod SET ROLE app_user + GUC tenanta: nadal idx_social_message_tenant_orphan_sent")
        void underAppUserRole_stillUsesIndex() {
            String role = "cc_be127_orphan_social";
            String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN BE-127 social RLS " + UUID.randomUUID());
            jdbc.update("""
                            INSERT INTO social_message
                                (message_id, tenant_id, contact_id, platform, direction, external_message_id,
                                 attachments, sent_at, created_at)
                            SELECT gen_random_uuid(), ?,
                                   CASE WHEN abs(hashtext('orphan-' || g)) % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                   CAST('WHATSAPP' AS social_platform),
                                   CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                   'be127rls.' || g, '[]'::jsonb,
                                   now() - ((g % 400) || ' days')::interval,
                                   now() - ((g % 400) || ' days')::interval
                            FROM generate_series(1, 30000) g
                            """, tenant);
            jdbc.execute("ANALYZE social_message");

            try (HikariDataSource restrictedPool = PostgresTestDatabase.pool(role, password, 1)) {
                JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
                restrictedJdbc.execute("BEGIN");
                try {
                    restrictedJdbc.execute("SET LOCAL ROLE " + role);
                    restrictedJdbc.queryForObject(
                            "SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());

                    String sql = SocialMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL
                            .replace(":tenantId", "'" + tenant + "'")
                            .replace(":cutoff", "(now() - interval '180 days')")
                            .replace(":batchSize", "100");
                    String plan = String.join("\n", restrictedJdbc.queryForList("EXPLAIN " + sql, String.class));

                    // Katalog (pg_inherits/pg_class) odpytany przez połączenie superusera (jdbc,
                    // pole klasy) -- te tabele systemowe nie są objęte RLS na danych domenowych,
                    // więc nie trzeba do tego sesji ograniczonej roli, z której pochodzi plan.
                    assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plan, SOCIAL_ORPHAN_INDEX))
                            .as("plan pod app_user używa %s albo jego indeksu potomnego partycji", SOCIAL_ORPHAN_INDEX)
                            .isTrue();
                    assertThat(plan).doesNotContain("Seq Scan");
                    System.out.println("[EXPLAIN BE-127 social pod app_user]\n" + plan);
                } finally {
                    restrictedJdbc.execute("ROLLBACK");
                }
            } finally {
                jdbc.update("DELETE FROM social_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("SQL nie używa ctid")
        void sql_doesNotUseCtid() {
            assertThat(SocialMessageRepository.COUNT_ORPHANS_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(SocialMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(SocialMessageRepository.FIND_ORPHANS_NEXT_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(SocialMessageRepository.DELETE_ORPHANS_BY_IDS_SQL).doesNotContainPattern("(?i)\\bctid\\b");
        }

        private String explain(String sql) {
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        }
    }
}
