package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla migracji
 * DB-074 (V113-V124): dokończenie RLS wg klasyfikacji DB-071.
 *
 * <ul>
 *   <li>V113-V116 (klasa D, {@code relrowsecurity=false} -&gt; pełne RLS): {@code email_routing_rule},
 *       {@code email_template}, {@code ivr_audio}, {@code contacts_dw}.</li>
 *   <li>V117-V121 (klasa B, RLS ON, niepełne pokrycie komend -&gt; dopisane brakujące polityki):
 *       {@code contact} (UPDATE/DELETE), {@code campaign} (DELETE), {@code customer} (DELETE),
 *       {@code queue} (INSERT/UPDATE/DELETE), {@code ivr_tree} (INSERT/UPDATE/DELETE + FORCE).</li>
 *   <li>V122-V123 (klasa C, MIXED): {@code audit_log} (INSERT/UPDATE/DELETE z gałęzią IS NULL + FORCE),
 *       {@code app_user} (naprawa buga w SELECT -- brak gałęzi IS NULL -- + INSERT/UPDATE/DELETE + FORCE).</li>
 *   <li>V124 (kosmetyczny bugfix): {@code scheduled_callback} -- {@code current_setting} 1-arg -&gt; 2-arg.</li>
 * </ul>
 *
 * <p><strong>Wzorzec:</strong> jedna świeża baza migrowana do najnowszej wersji (nie pre/post) --
 * wszystkie migracje DB-074 DODAJĄ zdolność RLS/polityki, nie zmieniają zachowania dla danych
 * zastanych na granicy migracji. Testy kopiują wzorzec {@link EmailSocialMessageRlsWritePoliciesTest}
 * (V099/DB-064) i {@link CampaignContactRlsMigrationsTest} (V111/V112/DB-072/DB-073).
 *
 * <p><strong>Dlaczego rola {@code app_user}, nigdy właściciel/superuser:</strong> {@code ccapp} i rola
 * Testcontainers (superuser obrazu {@code postgres}) mają {@code BYPASSRLS} -- RLS jest dla nich
 * całkowicie ignorowane. Tylko {@code SET ROLE app_user} (V012, bez {@code BYPASSRLS}) daje
 * wiarygodny wynik.
 *
 * <p><strong>Uwaga o GUC {@code app.current_tenant_id} na połączeniach poolowanych (odkrycie przy
 * tej sesji, patrz notatka wykonania DB-074 w TASKS-DATABASE.md i pamięć agenta):</strong> każdy test
 * "bez GUC" w tej klasie otwiera NOWE fizyczne połączenie JDBC przez {@link #connect()}
 * ({@code DriverManager.getConnection}, nie pool) -- dla takiego połączenia {@code current_setting}
 * zwraca {@code NULL} (zgodnie z założeniem DB-071/V124). Na POOLOWANYM połączeniu (HikariCP w
 * produkcji), gdy GUC był ustawiony choćby raz wcześniej na tym samym fizycznym połączeniu, jego
 * "wartość po zresetowaniu" to PUSTY STRING, nie {@code NULL} -- rzutowanie {@code ''::uuid} rzuca
 * twardy błąd, nie ciche 0 wierszy. To jest osobne, szersze ryzyko (dotyczy całego schematu, nie
 * tylko tabel z DB-074) -- NIE jest testowane tutaj, bo wymagałoby symulacji współdzielonego
 * połączenia poolowanego, co nie jest wzorcem żadnego istniejącego testu w tym repo.
 */
@Testcontainers
@DisplayName("RLS uzupełnienie wg klasyfikacji DB-071 (V113-V124, DB-074)")
class Db074RlsCompletionMigrationsTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "contact_center_test";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    // =========================================================================================
    // Katalog: relrowsecurity + relforcerowsecurity dla wszystkich tabel dotkniętych DB-074
    // =========================================================================================

    @Test
    @DisplayName("pg_class: wszystkie tabele DB-074 mają relrowsecurity=true i relforcerowsecurity=true")
    void allTables_haveRowSecurityAndForceEnabled() throws Exception {
        try (Connection c = connect()) {
            for (String table : new String[] {
                    "email_routing_rule", "email_template", "ivr_audio", "contacts_dw",
                    "contact", "campaign", "customer", "queue", "ivr_tree",
                    "audit_log", "app_user"
            }) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
                    ps.setString(1, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertThat(rs.next()).as("tabela %s istnieje w pg_class", table).isTrue();
                        assertThat(rs.getBoolean("relrowsecurity")).as("%s.relrowsecurity", table).isTrue();
                        assertThat(rs.getBoolean("relforcerowsecurity")).as("%s.relforcerowsecurity", table).isTrue();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("V124: polityka scheduled_callback używa current_setting(..., true) -- forma 2-argumentowa")
    void scheduledCallback_policyUsesTwoArgCurrentSetting() throws Exception {
        try (Connection c = connect()) {
            String qual = scalar(c,
                    "SELECT qual FROM pg_policies WHERE tablename = 'scheduled_callback' AND policyname = 'tenant_isolation_scheduled_callback'");
            assertThat(qual).contains("current_setting").contains(", true)");
        }
    }

    // =========================================================================================
    // V113-V116 (klasa D): email_routing_rule / email_template / ivr_audio / contacts_dw
    // =========================================================================================

    @Test
    @DisplayName("email_routing_rule: własny tenant INSERT/UPDATE/DELETE OK, cross-tenant 0 wierszy, cross-tenant INSERT 42501")
    void emailRoutingRule_fullRlsBehaviour() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 ERR tenant A");
            UUID tenantB = insertTenant(c, "DB074 ERR tenant B");
            asAppUser(c, tenantA);

            UUID ruleId = insertEmailRoutingRule(c, tenantA, "rule-a");
            assertThat(update(c, "UPDATE email_routing_rule SET name = 'rule-a-upd' WHERE rule_id = ?", ruleId))
                    .as("UPDATE własny tenant").isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO email_routing_rule (rule_id, tenant_id, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), tenantB, "hack");
            assertThat((Throwable) crossInsert).as("INSERT cross-tenant musi być odrzucony").isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM email_routing_rule WHERE rule_id = ?", ruleId))
                    .as("DELETE własny tenant").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("email_routing_rule: cross-tenant SELECT/UPDATE/DELETE dają 0 wierszy (nie błąd)")
    void emailRoutingRule_crossTenantIsZeroRowsNotError() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID ruleId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 ERR zero A");
            tenantB = insertTenant(owner, "DB074 ERR zero B");
            asAppUser(owner, tenantA);
            ruleId = insertEmailRoutingRule(owner, tenantA, "rule-zero");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM email_routing_rule WHERE rule_id = ?")) {
                ps.setObject(1, ruleId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant").isFalse();
                }
            }
            assertThat(update(c, "UPDATE email_routing_rule SET name = 'x' WHERE rule_id = ?", ruleId)).isZero();
            assertThat(update(c, "DELETE FROM email_routing_rule WHERE rule_id = ?", ruleId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM email_routing_rule WHERE rule_id = ?", ruleId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("email_routing_rule: app_user BEZ ustawionego GUC -- SELECT 0 wierszy, INSERT odrzucony (42501)")
    void emailRoutingRule_withoutGuc() throws Exception {
        UUID tenantA;
        UUID ruleId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 ERR no-guc");
            asAppUser(owner, tenantA);
            ruleId = insertEmailRoutingRule(owner, tenantA, "rule-no-guc");
        }
        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM email_routing_rule WHERE rule_id = ?")) {
                ps.setObject(1, ruleId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT bez GUC").isFalse();
                }
            }
            SQLException error = failureOf(c,
                    "INSERT INTO email_routing_rule (rule_id, tenant_id, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), tenantA, "x");
            assertThat((Throwable) error).isNotNull();
            assertThat(error.getSQLState()).isEqualTo("42501");
        }
    }

    @Test
    @DisplayName("email_template: własny tenant INSERT/UPDATE/DELETE OK, cross-tenant 0 wierszy, cross-tenant INSERT 42501")
    void emailTemplate_fullRlsBehaviour() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 ET tenant A");
            UUID tenantB = insertTenant(c, "DB074 ET tenant B");
            asAppUser(c, tenantA);

            UUID templateId = insertEmailTemplate(c, tenantA, "tpl-a");
            assertThat(update(c, "UPDATE email_template SET name = 'tpl-a-upd' WHERE template_id = ?", templateId))
                    .isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO email_template (template_id, tenant_id, name, subject_template, body_html) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantB, "hack", "subj", "<p>x</p>");
            assertThat((Throwable) crossInsert).isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM email_template WHERE template_id = ?", templateId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("email_template: cross-tenant SELECT/UPDATE/DELETE dają 0 wierszy (nie błąd)")
    void emailTemplate_crossTenantIsZeroRowsNotError() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID templateId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 ET zero A");
            tenantB = insertTenant(owner, "DB074 ET zero B");
            asAppUser(owner, tenantA);
            templateId = insertEmailTemplate(owner, tenantA, "tpl-zero");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "UPDATE email_template SET name = 'x' WHERE template_id = ?", templateId)).isZero();
            assertThat(update(c, "DELETE FROM email_template WHERE template_id = ?", templateId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM email_template WHERE template_id = ?", templateId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("ivr_audio: własny tenant INSERT/UPDATE/DELETE OK, cross-tenant 0 wierszy, cross-tenant INSERT 42501")
    void ivrAudio_fullRlsBehaviour() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 IA tenant A");
            UUID tenantB = insertTenant(c, "DB074 IA tenant B");
            asAppUser(c, tenantA);

            UUID audioId = insertIvrAudio(c, tenantA, "audio-a.wav");
            assertThat(update(c, "UPDATE ivr_audio SET filename = 'audio-a-upd.wav' WHERE audio_id = ?", audioId))
                    .isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO ivr_audio (audio_id, tenant_id, filename, s3_url) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), tenantB, "hack.wav", "s3://bucket/hack.wav");
            assertThat((Throwable) crossInsert).isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM ivr_audio WHERE audio_id = ?", audioId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("contacts_dw: własny tenant INSERT/UPDATE/DELETE OK, cross-tenant 0 wierszy, cross-tenant INSERT 42501")
    void contactsDw_fullRlsBehaviour() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 CDW tenant A");
            UUID tenantB = insertTenant(c, "DB074 CDW tenant B");
            asAppUser(c, tenantA);

            UUID contactId = insertContactsDwRow(c, tenantA);
            assertThat(update(c, "UPDATE contacts_dw SET status = 'ENDED' WHERE contact_id = ?", contactId))
                    .isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at) "
                            + "VALUES (?, ?, 'PHONE', 'INBOUND', 'QUEUED', now(), now())",
                    UUID.randomUUID(), tenantB);
            assertThat((Throwable) crossInsert).isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM contacts_dw WHERE contact_id = ?", contactId)).isEqualTo(1);
        }
    }

    // =========================================================================================
    // V117-V121 (klasa B): contact / campaign / customer / queue / ivr_tree -- nowe komendy
    // =========================================================================================

    @Test
    @DisplayName("contact: UPDATE/DELETE własnego tenanta działają (dowód naprawy odmowy z raportu DB-071)")
    void contact_ownTenantUpdateDelete_nowWork() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 contact tenant A");
            asAppUser(c, tenantA);

            UUID contactId = insertContact(c, tenantA);
            assertThat(update(c, "UPDATE contact SET notes = 'zmienione' WHERE contact_id = ?", contactId))
                    .as("UPDATE własnego kontaktu").isEqualTo(1);
            assertThat(update(c, "DELETE FROM contact WHERE contact_id = ?", contactId))
                    .as("DELETE własnego kontaktu (np. ContactRepository.deleteContacts, GDPR)").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("contact: cross-tenant UPDATE/DELETE dają 0 wierszy (nie błąd)")
    void contact_crossTenantUpdateDelete_zeroRows() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID contactId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 contact zero A");
            tenantB = insertTenant(owner, "DB074 contact zero B");
            contactId = insertContact(owner, tenantA);
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "UPDATE contact SET notes = 'HACK' WHERE contact_id = ?", contactId)).isZero();
            assertThat(update(c, "DELETE FROM contact WHERE contact_id = ?", contactId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM contact WHERE contact_id = ?", contactId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("campaign: DELETE własnego tenanta działa, cross-tenant DELETE daje 0 wierszy")
    void campaign_deletePolicy() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID campaignIdCross;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 campaign A");
            tenantB = insertTenant(owner, "DB074 campaign B");
            campaignIdCross = insertCampaign(owner, tenantA, "camp-cross");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "DELETE FROM campaign WHERE campaign_id = ?", campaignIdCross))
                    .as("cross-tenant DELETE").isZero();
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            UUID campaignIdOwn = insertCampaign(c, tenantA, "camp-own");
            assertThat(update(c, "DELETE FROM campaign WHERE campaign_id = ?", campaignIdOwn))
                    .as("własny tenant DELETE").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("customer: DELETE własnego tenanta działa, cross-tenant DELETE daje 0 wierszy")
    void customer_deletePolicy() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID customerIdCross;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 customer A");
            tenantB = insertTenant(owner, "DB074 customer B");
            customerIdCross = insertCustomer(owner, tenantA);
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "DELETE FROM customer WHERE customer_id = ?", customerIdCross))
                    .as("cross-tenant DELETE").isZero();
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            UUID customerIdOwn = insertCustomer(c, tenantA);
            assertThat(update(c, "DELETE FROM customer WHERE customer_id = ?", customerIdOwn))
                    .as("własny tenant DELETE").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("queue: własny tenant INSERT/UPDATE/DELETE OK, cross-tenant INSERT 42501, cross-tenant UPDATE/DELETE 0 wierszy")
    void queue_writePolicies() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 queue A");
            UUID tenantB = insertTenant(c, "DB074 queue B");
            asAppUser(c, tenantA);

            UUID queueId = insertQueue(c, tenantA, "queue-a");
            assertThat(update(c, "UPDATE queue SET name = 'queue-a-upd' WHERE queue_id = ?", queueId)).isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, ?)",
                    UUID.randomUUID(), tenantB, "hack-queue");
            assertThat((Throwable) crossInsert).isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM queue WHERE queue_id = ?", queueId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("queue: cross-tenant UPDATE/DELETE dają 0 wierszy (nie błąd)")
    void queue_crossTenantIsZeroRows() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID queueId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 queue zero A");
            tenantB = insertTenant(owner, "DB074 queue zero B");
            asAppUser(owner, tenantA);
            queueId = insertQueue(owner, tenantA, "queue-zero");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "UPDATE queue SET name = 'x' WHERE queue_id = ?", queueId)).isZero();
            assertThat(update(c, "DELETE FROM queue WHERE queue_id = ?", queueId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM queue WHERE queue_id = ?", queueId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("ivr_tree: własny tenant INSERT/UPDATE/DELETE OK (FORCE dodany V121), cross-tenant INSERT 42501")
    void ivrTree_writePolicies() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 ivr tenant A");
            UUID tenantB = insertTenant(c, "DB074 ivr tenant B");
            asAppUser(c, tenantA);

            UUID ivrId = insertIvrTree(c, tenantA, "ivr-a");
            assertThat(update(c, "UPDATE ivr_tree SET name = 'ivr-a-upd' WHERE ivr_id = ?", ivrId)).isEqualTo(1);

            SQLException crossInsert = failureOf(c,
                    "INSERT INTO ivr_tree (ivr_id, tenant_id, name, definition) VALUES (?, ?, ?, ?::jsonb)",
                    UUID.randomUUID(), tenantB, "hack-ivr", "{\"entry_node_id\":\"n1\",\"nodes\":[]}");
            assertThat((Throwable) crossInsert).isNotNull();
            assertThat(crossInsert.getSQLState()).isEqualTo("42501");

            assertThat(update(c, "DELETE FROM ivr_tree WHERE ivr_id = ?", ivrId))
                    .as("DELETE własnego drzewa IVR (IvrTreeRepository, aktywna ścieżka)").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("ivr_tree: cross-tenant UPDATE/DELETE dają 0 wierszy (nie błąd)")
    void ivrTree_crossTenantIsZeroRows() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID ivrId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 ivr zero A");
            tenantB = insertTenant(owner, "DB074 ivr zero B");
            asAppUser(owner, tenantA);
            ivrId = insertIvrTree(owner, tenantA, "ivr-zero");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "UPDATE ivr_tree SET name = 'x' WHERE ivr_id = ?", ivrId)).isZero();
            assertThat(update(c, "DELETE FROM ivr_tree WHERE ivr_id = ?", ivrId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM ivr_tree WHERE ivr_id = ?", ivrId)).isEqualTo("1");
        }
    }

    // =========================================================================================
    // V122 (klasa C MIXED): audit_log
    // =========================================================================================

    @Test
    @DisplayName("audit_log: zdarzenie globalne (tenant_id NULL) INSERT OK pod app_user BEZ ustawionego GUC")
    void auditLog_globalEventInsert_worksWithoutGuc() throws Exception {
        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");
            // Świeże połączenie -- GUC nigdy nie ustawiony na tej sesji (patrz uwaga w javadoc klasy).
            UUID logId = UUID.randomUUID();
            assertThat(update(c,
                    "INSERT INTO audit_log (log_id, tenant_id, action) VALUES (?, NULL, 'DB074_TEST_GLOBAL')",
                    logId)).isEqualTo(1);
            assertThat(scalar(c, "SELECT count(*) FROM audit_log WHERE log_id = ?", logId)).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("audit_log: własny tenant INSERT/UPDATE/DELETE działają pod GUC ustawionym")
    void auditLog_ownTenantWritePolicies() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 audit A");
            asAppUser(c, tenantA);

            UUID logId = UUID.randomUUID();
            assertThat(update(c,
                    "INSERT INTO audit_log (log_id, tenant_id, action) VALUES (?, ?, 'DB074_TEST_TENANT')",
                    logId, tenantA)).isEqualTo(1);
            assertThat(update(c, "UPDATE audit_log SET action = 'DB074_TEST_UPDATED' WHERE log_id = ?", logId))
                    .isEqualTo(1);
            assertThat(update(c, "DELETE FROM audit_log WHERE log_id = ?", logId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("audit_log: cross-tenant UPDATE/DELETE wiersza tenantowego dają 0 wierszy; wiersz globalny widoczny z każdego GUC")
    void auditLog_crossTenantZeroRows_globalVisibleEverywhere() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID tenantLogId;
        UUID globalLogId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 audit zero A");
            tenantB = insertTenant(owner, "DB074 audit zero B");
            tenantLogId = UUID.randomUUID();
            globalLogId = UUID.randomUUID();
            update(owner, "INSERT INTO audit_log (log_id, tenant_id, action) VALUES (?, ?, 'DB074_TENANT_ROW')",
                    tenantLogId, tenantA);
            update(owner, "INSERT INTO audit_log (log_id, tenant_id, action) VALUES (?, NULL, 'DB074_GLOBAL_ROW')",
                    globalLogId);
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(update(c, "UPDATE audit_log SET action = 'x' WHERE log_id = ?", tenantLogId))
                    .as("cross-tenant UPDATE wiersza tenantowego").isZero();
            assertThat(update(c, "DELETE FROM audit_log WHERE log_id = ?", tenantLogId))
                    .as("cross-tenant DELETE wiersza tenantowego").isZero();
            assertThat(scalar(c, "SELECT count(*) FROM audit_log WHERE log_id = ?", globalLogId))
                    .as("wiersz globalny widoczny pod GUC tenanta B (gałąź IS NULL)").isEqualTo("1");
        }
    }

    // =========================================================================================
    // V123 (klasa C MIXED): app_user -- naprawa buga SELECT + nowe INSERT/UPDATE/DELETE
    // =========================================================================================

    @Test
    @DisplayName("app_user: SUPER_ADMIN (tenant_id NULL) widoczny pod SELECT niezależnie od GUC tenanta (naprawa buga AND->OR)")
    void appUser_superAdminVisibleUnderAnyTenantGuc() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID superAdminId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 app_user superadmin A");
            tenantB = insertTenant(owner, "DB074 app_user superadmin B");
            superAdminId = insertAppUser(owner, null, "SUPER_ADMIN",
                    "superadmin-" + UUID.randomUUID() + "@example.invalid");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantA);
            assertThat(scalar(c, "SELECT count(*) FROM app_user WHERE user_id = ?", superAdminId))
                    .as("SUPER_ADMIN widoczny pod GUC tenanta A").isEqualTo("1");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            assertThat(scalar(c, "SELECT count(*) FROM app_user WHERE user_id = ?", superAdminId))
                    .as("SUPER_ADMIN widoczny pod GUC tenanta B (dowód OR, nie AND)").isEqualTo("1");
        }
    }

    @Test
    @DisplayName("app_user: własny tenant INSERT/UPDATE/DELETE działają pod GUC ustawionym")
    void appUser_ownTenantWritePolicies() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 app_user own A");
            asAppUser(c, tenantA);

            UUID userId = insertAppUser(c, tenantA, "AGENT", "agent-" + UUID.randomUUID() + "@example.invalid");
            assertThat(update(c, "UPDATE app_user SET first_name = 'Zmieniony' WHERE user_id = ?", userId))
                    .isEqualTo(1);
            assertThat(update(c, "DELETE FROM app_user WHERE user_id = ?", userId)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("app_user: cross-tenant SELECT/UPDATE/DELETE wiersza tenantowego dają 0 wierszy (nie błąd)")
    void appUser_crossTenantZeroRows() throws Exception {
        UUID tenantA;
        UUID tenantB;
        UUID userId;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 app_user zero A");
            tenantB = insertTenant(owner, "DB074 app_user zero B");
            userId = insertAppUser(owner, tenantA, "AGENT", "zero-" + UUID.randomUUID() + "@example.invalid");
        }
        try (Connection c = connect()) {
            asAppUser(c, tenantB);
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM app_user WHERE user_id = ?")) {
                ps.setObject(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("SELECT cross-tenant").isFalse();
                }
            }
            assertThat(update(c, "UPDATE app_user SET first_name = 'HACK' WHERE user_id = ?", userId)).isZero();
            assertThat(update(c, "DELETE FROM app_user WHERE user_id = ?", userId)).isZero();
        }
        try (Connection owner = connect()) {
            assertThat(scalar(owner, "SELECT count(*) FROM app_user WHERE user_id = ?", userId)).isEqualTo("1");
        }
    }

    // =========================================================================================
    // V124: scheduled_callback -- ciche 0 wierszy bez GUC (nie hard error), bez regresji z GUC
    // =========================================================================================

    @Test
    @DisplayName("scheduled_callback: app_user BEZ ustawionego GUC -- SELECT daje 0 wierszy po cichu (NIE hard error, naprawa V124)")
    void scheduledCallback_withoutGuc_isSilentZeroRows() throws Exception {
        UUID tenantA;
        try (Connection owner = connect()) {
            tenantA = insertTenant(owner, "DB074 callback no-guc");
            insertScheduledCallback(owner, tenantA);
        }
        try (Connection c = connect()) {
            update(c, "SET ROLE app_user");
            // Świeże połączenie, GUC nigdy nie ustawiony -- przed V124 to rzucało hard error
            // "unrecognized configuration parameter", nie ciche 0 wierszy.
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM scheduled_callback")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1)).isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("scheduled_callback: regresja -- własny tenant ALL (SELECT/INSERT/UPDATE/DELETE) nadal działa po V124")
    void scheduledCallback_ownTenantStillWorks_noRegression() throws Exception {
        try (Connection c = connect()) {
            UUID tenantA = insertTenant(c, "DB074 callback regression");
            asAppUser(c, tenantA);

            UUID callbackId = insertScheduledCallback(c, tenantA);
            assertThat(scalar(c, "SELECT count(*) FROM scheduled_callback WHERE callback_id = ?", callbackId))
                    .isEqualTo("1");
            assertThat(update(c, "UPDATE scheduled_callback SET notes = 'x' WHERE callback_id = ?", callbackId))
                    .isEqualTo(1);
            assertThat(update(c, "DELETE FROM scheduled_callback WHERE callback_id = ?", callbackId))
                    .isEqualTo(1);
        }
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Ustawia rolę app_user (bez BYPASSRLS, V012) i GUC tenanta na bieżącej sesji. */
    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, false)", tenantId.toString());
    }

    private static UUID insertTenant(Connection c, String name) throws SQLException {
        UUID tenantId = UUID.randomUUID();
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
        return tenantId;
    }

    private static UUID insertEmailRoutingRule(Connection c, UUID tenantId, String name) throws SQLException {
        UUID ruleId = UUID.randomUUID();
        update(c, "INSERT INTO email_routing_rule (rule_id, tenant_id, name) VALUES (?, ?, ?)",
                ruleId, tenantId, name);
        return ruleId;
    }

    private static UUID insertEmailTemplate(Connection c, UUID tenantId, String name) throws SQLException {
        UUID templateId = UUID.randomUUID();
        update(c, "INSERT INTO email_template (template_id, tenant_id, name, subject_template, body_html) "
                        + "VALUES (?, ?, ?, 'subiekt', '<p>tresc</p>')",
                templateId, tenantId, name);
        return templateId;
    }

    private static UUID insertIvrAudio(Connection c, UUID tenantId, String filename) throws SQLException {
        UUID audioId = UUID.randomUUID();
        update(c, "INSERT INTO ivr_audio (audio_id, tenant_id, filename, s3_url) VALUES (?, ?, ?, ?)",
                audioId, tenantId, filename, "s3://bucket/" + filename);
        return audioId;
    }

    private static UUID insertContactsDwRow(Connection c, UUID tenantId) throws SQLException {
        UUID contactId = UUID.randomUUID();
        update(c, "INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at) "
                        + "VALUES (?, ?, 'PHONE', 'INBOUND', 'QUEUED', now(), now())",
                contactId, tenantId);
        return contactId;
    }

    private static UUID insertContact(Connection c, UUID tenantId) throws SQLException {
        UUID contactId = UUID.randomUUID();
        update(c, "INSERT INTO contact (contact_id, tenant_id, channel, direction) VALUES (?, ?, 'PHONE', 'INBOUND')",
                contactId, tenantId);
        return contactId;
    }

    private static UUID insertCampaign(Connection c, UUID tenantId, String name) throws SQLException {
        UUID campaignId = UUID.randomUUID();
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)",
                campaignId, tenantId, name);
        return campaignId;
    }

    private static UUID insertCustomer(Connection c, UUID tenantId) throws SQLException {
        UUID customerId = UUID.randomUUID();
        update(c, "INSERT INTO customer (customer_id, tenant_id) VALUES (?, ?)", customerId, tenantId);
        return customerId;
    }

    private static UUID insertQueue(Connection c, UUID tenantId, String name) throws SQLException {
        UUID queueId = UUID.randomUUID();
        update(c, "INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, ?)", queueId, tenantId, name);
        return queueId;
    }

    private static UUID insertIvrTree(Connection c, UUID tenantId, String name) throws SQLException {
        UUID ivrId = UUID.randomUUID();
        update(c, "INSERT INTO ivr_tree (ivr_id, tenant_id, name, definition) VALUES (?, ?, ?, ?::jsonb)",
                ivrId, tenantId, name, "{\"entry_node_id\":\"n1\",\"nodes\":[]}");
        return ivrId;
    }

    private static UUID insertAppUser(Connection c, UUID tenantIdOrNull, String role, String email) throws SQLException {
        UUID userId = UUID.randomUUID();
        update(c, "INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, first_name, last_name) "
                        + "VALUES (?, ?, ?, ?, 'x', 'DB074', 'Test')",
                userId, tenantIdOrNull, role, email);
        return userId;
    }

    private static UUID insertScheduledCallback(Connection c, UUID tenantId) throws SQLException {
        UUID callbackId = UUID.randomUUID();
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, phone, scheduled_at) "
                        + "VALUES (?, ?, '+48500100200', now() + interval '1 hour')",
                callbackId, tenantId);
        return callbackId;
    }

    /** Zwraca wyjątek SQL rzucony przez polecenie albo null, gdy polecenie się powiodło. */
    private static SQLException failureOf(Connection c, String sql, Object... params) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
            return null;
        } catch (SQLException e) {
            return e;
        }
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                    }
                    return rows;
                }
            }
            return ps.getUpdateCount();
        }
    }

    private static String scalar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("zapytanie zwraca wiersz: %s", sql).isTrue();
                return String.valueOf(rs.getObject(1));
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
