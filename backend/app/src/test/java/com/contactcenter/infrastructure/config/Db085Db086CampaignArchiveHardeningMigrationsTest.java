package com.contactcenter.infrastructure.config;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-085 (trigger {@code campaign.ended_at} na INSERT i terminal -> terminal, zegar
 * {@code COALESCE(ended_at, updated_at, created_at)}, hardening {@code tenant_campaign_archive_setting}) oraz
 * DB-086 ({@code DELETE} w {@code archive_completed_campaign_contacts()} tylko po wierszach obecnych w archiwum),
 * na prawdziwym PostgreSQL (Testcontainers, pelny lancuch Flyway). Migracja jest pinowana do OSTATNIEJ
 * migracji DB-086 (wersje wyznaczane po opisie, bez numerow na sztywno), wiec kolejne, niezwiazane tickety
 * (np. DB-083) nie moga zepsuc testu.
 *
 * <p>Testy sa niezalezne (kazdy tworzy wlasnych tenantow); funkcja archiwizacji dziala globalnie, wiec asercje
 * dotycza wylacznie kampanii danego testu. Scenariusze z {@code session_replication_role = replica} tworza
 * wiersze "jak sprzed triggera" (terminalna kampania z {@code ended_at IS NULL}) bez wylaczania triggera dla
 * innych polaczen.
 */
@Testcontainers
@DisplayName("DB-085/DB-086: trigger ended_at na INSERT, zegar archiwizacji, DELETE tylko zarchiwizowanych (V140-V141)")
class Db085Db086CampaignArchiveHardeningMigrationsTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "cc_db085_086";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";
    private static final String JOB = "archive_completed_campaign_contacts";
    private static final String V140_DESC = "campaign ended at trigger insert and archive function hardening";
    private static final String V141_DESC = "archive function delete only archived rows";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("postgres").withUsername(USER).withPassword(PASSWORD);

    private static JdbcTemplate jdbc;
    private static List<MigrationInfo> chain;

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection admin = connect("postgres"); Statement st = admin.createStatement()) {
            st.execute("CREATE DATABASE " + DB);
        }
        chain = Arrays.stream(flyway().load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        MigrationVersion last = versionOf(V141_DESC);
        flyway().target(last).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(DB), USER, PASSWORD));
    }

    // =============================================================================================
    // DB-085: trigger ended_at
    // =============================================================================================

    @Test
    @DisplayName("INSERT kampanii od razu COMPLETED/STOPPED -> ended_at = NOW(); INSERT RUNNING/DRAFT/PAUSED -> NULL")
    void trigger_insertSetsEndedAtOnlyForTerminal() {
        UUID t = insertTenant("DB-085 insert");
        for (String status : List.of("COMPLETED", "STOPPED")) {
            UUID id = insertCampaign(t, status, null);
            assertThat(jdbc.queryForObject("SELECT ended_at > now() - interval '1 minute' AND ended_at <= now() "
                    + "FROM campaign WHERE campaign_id = ?", Boolean.class, id)).as(status).isTrue();
        }
        for (String status : List.of("RUNNING", "DRAFT", "PAUSED")) {
            assertThat(endedAt(insertCampaign(t, status, null))).as(status).isNull();
        }
    }

    @Test
    @DisplayName("INSERT nieterminalny z JAWNYM ended_at -> NULL (ended_at <> NULL implikuje stan terminalny); terminal -> nieterminalny ignoruje jawny ended_at")
    void trigger_nonTerminalInsertAndExitIgnoreExplicitEndedAt() {
        UUID t = insertTenant("DB-085 nonterminal explicit");
        for (String status : List.of("RUNNING", "DRAFT", "PAUSED")) {
            assertThat(endedAt(insertCampaign(t, status, "now() - interval '30 days'"))).as(status).isNull();
        }
        UUID id = insertCampaign(t, "COMPLETED", "now() - interval '30 days'");
        jdbc.update("UPDATE campaign SET status = 'RUNNING', ended_at = now() WHERE campaign_id = ?", id);
        assertThat(endedAt(id)).as("jawny ended_at przy wyjsciu ze stanu terminalnego jest ignorowany").isNull();
    }

    @Test
    @DisplayName("INSERT terminalny z JAWNYM ended_at -> wartosc zachowana (seedy, importy)")
    void trigger_insertKeepsExplicitEndedAt() {
        UUID t = insertTenant("DB-085 explicit");
        UUID id = insertCampaign(t, "COMPLETED", "now() - interval '300 days'");
        assertThat(jdbc.queryForObject("SELECT ended_at < now() - interval '299 days' FROM campaign WHERE campaign_id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("INSERT terminalny + pozniejszy UPDATE (all_agents, status w SET bez zmiany) NIE zmienia ended_at, a updated_at restartuje")
    void trigger_laterUpdatesDoNotMoveEndedAt() {
        UUID t = insertTenant("DB-085 later update");
        UUID id = insertCampaign(t, "COMPLETED", "now() - interval '120 days'");
        Timestamp ended = endedAt(id);
        Timestamp updatedBefore = updatedAt(id);

        jdbc.update("UPDATE campaign SET all_agents = TRUE WHERE campaign_id = ?", id);                       // status poza SET
        jdbc.update("UPDATE campaign SET status = 'COMPLETED', name = name || '!' WHERE campaign_id = ?", id); // Hibernate: status bez zmiany
        jdbc.update("UPDATE campaign SET status = status WHERE campaign_id = ?", id);

        assertThat(endedAt(id)).isEqualTo(ended);
        assertThat(updatedAt(id)).as("trg_campaign_updated_at dziala").isAfter(updatedBefore);
    }

    @Test
    @DisplayName("INSERT terminalny bez ended_at + pozniejszy UPDATE: ended_at stabilne (R2 nie wraca do updated_at)")
    void trigger_insertTerminalThenUpdateKeepsAutoEndedAt() {
        UUID t = insertTenant("DB-085 auto then update");
        UUID id = insertCampaign(t, "STOPPED", null);
        Timestamp ended = endedAt(id);
        assertThat(ended).isNotNull();
        jdbc.update("UPDATE campaign SET all_agents = TRUE, status = 'STOPPED' WHERE campaign_id = ?", id);
        assertThat(endedAt(id)).isEqualTo(ended);
    }

    @Test
    @DisplayName("COMPLETED -> STOPPED: ustawia ended_at gdy byl NULL, zachowuje gdy byl ustawiony")
    void trigger_terminalToTerminal() throws Exception {
        UUID t = insertTenant("DB-085 t2t");
        UUID withNull = insertCampaignWithoutTriggers(t, "COMPLETED");
        assertThat(endedAt(withNull)).as("wiersz sprzed triggera").isNull();
        jdbc.update("UPDATE campaign SET status = 'STOPPED' WHERE campaign_id = ?", withNull);
        assertThat(endedAt(withNull)).as("NULL -> NOW()").isNotNull();

        UUID withValue = insertCampaign(t, "COMPLETED", "now() - interval '90 days'");
        Timestamp ended = endedAt(withValue);
        jdbc.update("UPDATE campaign SET status = 'STOPPED' WHERE campaign_id = ?", withValue);
        assertThat(endedAt(withValue)).as("terminal -> terminal zachowuje").isEqualTo(ended);
    }

    @Test
    @DisplayName("jawny ended_at w tym samym UPDATE co przejscie RUNNING -> STOPPED zostaje; bez jawnego -> NOW()")
    void trigger_explicitEndedAtInTransitionKept() {
        UUID t = insertTenant("DB-085 explicit transition");
        UUID explicit = insertCampaign(t, "RUNNING", null);
        UUID auto = insertCampaign(t, "RUNNING", null);
        jdbc.update("UPDATE campaign SET status = 'STOPPED', ended_at = now() - interval '45 days' WHERE campaign_id = ?", explicit);
        jdbc.update("UPDATE campaign SET status = 'STOPPED' WHERE campaign_id = ?", auto);
        assertThat(jdbc.queryForObject("SELECT ended_at < now() - interval '44 days' FROM campaign WHERE campaign_id = ?",
                Boolean.class, explicit)).isTrue();
        assertThat(jdbc.queryForObject("SELECT ended_at > now() - interval '1 minute' FROM campaign WHERE campaign_id = ?",
                Boolean.class, auto)).isTrue();
    }

    @Test
    @DisplayName("wyjscie ze stanu terminalnego zeruje ended_at, ponowne zakonczenie ustawia nowe NOW()")
    void trigger_reopenClearsAndReCompleteSetsNew() {
        UUID t = insertTenant("DB-085 reopen");
        UUID id = insertCampaign(t, "COMPLETED", "now() - interval '200 days'");
        jdbc.update("UPDATE campaign SET status = 'RUNNING' WHERE campaign_id = ?", id);
        assertThat(endedAt(id)).isNull();
        jdbc.update("UPDATE campaign SET status = 'COMPLETED' WHERE campaign_id = ?", id);
        assertThat(jdbc.queryForObject("SELECT ended_at > now() - interval '1 minute' FROM campaign WHERE campaign_id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("UPDATE bez kolumny status w SET nie odpala triggera ended_at (ended_at stare mimo zmiany updated_at)")
    void trigger_updateWithoutStatusLeavesEndedAt() {
        UUID t = insertTenant("DB-085 no status");
        UUID id = insertCampaign(t, "COMPLETED", "now() - interval '400 days'");
        Timestamp ended = endedAt(id);
        jdbc.update("UPDATE campaign SET name = name || '?', updated_at = now() WHERE campaign_id = ?", id);
        assertThat(endedAt(id)).isEqualTo(ended);
    }

    @Test
    @DisplayName("triggery: trg_campaign_ended_at (INSERT+UPDATE, wiersz, BEFORE) i trg_campaign_updated_at wlaczone ('O'), brak CHECK na ended_at")
    void trigger_structure() {
        assertThat(jdbc.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_ended_at'", String.class)).isEqualTo("O");
        assertThat(jdbc.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_updated_at'", String.class)).isEqualTo("O");
        // tgtype: 1 = ROW, 2 = BEFORE, 4 = INSERT, 16 = UPDATE
        int type = jdbc.queryForObject("SELECT tgtype::int FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_ended_at'", Integer.class);
        assertThat(type & 1).isEqualTo(1);
        assertThat(type & 2).isEqualTo(2);
        assertThat(type & 4).as("INSERT").isEqualTo(4);
        assertThat(type & 16).as("UPDATE").isEqualTo(16);
        assertThat(type & 8).as("brak DELETE").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'campaign'::regclass "
                + "AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%ended_at%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT obj_description('fn_campaign_set_ended_at()'::regprocedure, 'pg_proc')",
                String.class)).contains("INSERT").contains("terminal");
    }

    // =============================================================================================
    // DB-085: funkcja - zegar COALESCE(ended_at, updated_at, created_at)
    // =============================================================================================

    @Test
    @DisplayName("funkcja: updated_at IS NULL i ended_at IS NULL -> zegar created_at (stary) archiwizuje, swiezy nie")
    void function_clockFallsBackToCreatedAt() throws Exception {
        UUID t = insertTenant("DB-085 created_at");
        UUID old = insertCampaignWithoutTriggers(t, "COMPLETED", "now() - interval '300 days'", null, null);
        UUID fresh = insertCampaignWithoutTriggers(t, "STOPPED", "now() - interval '10 days'", null, null);
        insertContact(t, old);
        insertContact(t, fresh);

        runArchive();

        assertThat(archived(old)).as("created_at 300 dni, prog 180").isEqualTo(1);
        assertThat(live(old)).isZero();
        assertThat(live(fresh)).isEqualTo(1);
        assertThat(archived(fresh)).isZero();
    }

    @Test
    @DisplayName("funkcja: kampania wstawiona terminalna bez ended_at dostaje zegar NOW() - nie jest archiwizowana mimo starego updated_at")
    void function_insertedTerminalWithoutEndedAtStartsClockNow() {
        UUID t = insertTenant("DB-085 clock now");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'COMPLETED', now() - interval '400 days', now() - interval '399 days')", id, t, "DB-085 " + id);
        insertContact(t, id);
        runArchive();
        assertThat(live(id)).isEqualTo(1);
        assertThat(archived(id)).isZero();
    }

    @Test
    @DisplayName("funkcja: kontrakt - bez parametrow, RETURNS void, SECURITY INVOKER lub DEFINER z search_path; komunikat cron_log; zachowanie zamiast prosrc")
    void function_contract() {
        Map<String, Object> p = jdbc.queryForMap("SELECT pronargs, prorettype::regtype::text AS ret, prosecdef, proconfig "
                + "FROM pg_proc WHERE oid = 'archive_completed_campaign_contacts()'::regprocedure");
        assertThat(((Number) p.get("pronargs")).intValue()).isZero();
        assertThat(p.get("ret")).isEqualTo("void");
        // po DB-083 funkcja moze byc DEFINER - wtedy wymagany jest search_path w proconfig
        if (Boolean.TRUE.equals(p.get("prosecdef"))) {
            assertThat(String.valueOf(p.get("proconfig"))).contains("search_path");
        }

        UUID t = insertTenant("DB-085 contract");
        UUID c = insertCampaign(t, "STOPPED", "now() - interval '200 days'");
        insertContact(t, c);
        insertContact(t, c);
        runArchive();
        assertThat(archived(c)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT message FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                + "ORDER BY log_id DESC LIMIT 1", String.class, JOB))
                .contains("Kampanie:").contains("Rekordy:").contains("Usuniete:").contains("Okres archiwizacji");
        assertThat(jdbc.queryForObject("SELECT last_run_status FROM scheduled_job WHERE job_name = ?",
                String.class, JOB)).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("COMMENT ON FUNCTION: zegar z created_at, DB-085/DB-086, odeslanie do log_cron_failure")
    void function_comment() {
        String c = jdbc.queryForObject("SELECT obj_description('archive_completed_campaign_contacts()'::regprocedure, 'pg_proc')",
                String.class);
        assertThat(c).contains("created_at").contains("DB-085").contains("DB-086").contains("PER TENANT")
                .contains("log_cron_failure").contains("record_id").contains("rows_affected");
    }

    // =============================================================================================
    // DB-085 pkt 3: tenant_campaign_archive_setting
    // =============================================================================================

    @Test
    @DisplayName("tenant_campaign_archive_setting: trigger updated_at ('O'), UPDATE bez updated_at odswieza je, reczne updated_at = NOW() nie psuje")
    void settingTable_updatedAtTrigger() {
        UUID t = insertTenant("DB-085 setting trigger");
        jdbc.update("INSERT INTO tenant_campaign_archive_setting (tenant_id, archive_after_days, created_at, updated_at) "
                + "VALUES (?, 60, now() - interval '10 days', now() - interval '10 days')", t);
        assertThat(jdbc.queryForObject("SELECT tgenabled::text FROM pg_trigger WHERE tgrelid = "
                + "'tenant_campaign_archive_setting'::regclass AND NOT tgisinternal", String.class)).isEqualTo("O");

        jdbc.update("UPDATE tenant_campaign_archive_setting SET archive_after_days = 90 WHERE tenant_id = ?", t);
        assertThat(jdbc.queryForObject("SELECT updated_at > now() - interval '1 minute' FROM tenant_campaign_archive_setting "
                + "WHERE tenant_id = ?", Boolean.class, t)).isTrue();
        assertThat(jdbc.queryForObject("SELECT created_at < now() - interval '9 days' FROM tenant_campaign_archive_setting "
                + "WHERE tenant_id = ?", Boolean.class, t)).as("created_at nietkniete").isTrue();

        jdbc.update("UPDATE tenant_campaign_archive_setting SET archive_after_days = 120, updated_at = NOW() WHERE tenant_id = ?", t);
        assertThat(jdbc.queryForObject("SELECT archive_after_days FROM tenant_campaign_archive_setting WHERE tenant_id = ?",
                Integer.class, t)).isEqualTo(120);
    }

    @Test
    @DisplayName("tenant_campaign_archive_setting: jawny GRANT SELECT/INSERT/UPDATE/DELETE dla app_user, brak TRUNCATE/REFERENCES/TRIGGER; komentarz o wyjatku od is_deleted")
    void settingTable_grantAndComment() {
        for (String priv : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'tenant_campaign_archive_setting', ?)",
                    Boolean.class, priv)).as(priv).isTrue();
        }
        for (String priv : List.of("TRUNCATE", "REFERENCES", "TRIGGER")) {
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'tenant_campaign_archive_setting', ?)",
                    Boolean.class, priv)).as(priv).isFalse();
        }
        assertThat(jdbc.queryForObject("SELECT obj_description('tenant_campaign_archive_setting'::regclass, 'pg_class')",
                String.class)).contains("is_deleted").contains("BRAK WIERSZA = 180").contains("DNIACH");
    }

    @Test
    @DisplayName("idempotencja: ponowne wykonanie V140 i V141 na zmigrowanej bazie konczy sie bez bledu i nie zmienia zachowania")
    void migrations_areIdempotent() throws Exception {
        for (String description : List.of(V140_DESC, V141_DESC)) {
            MigrationInfo info = chain.stream().filter(m -> description.equals(m.getDescription())).findFirst().orElseThrow();
            String script = StreamUtils.copyToString(
                    new ClassPathResource("db/migration/V" + info.getVersion().getVersion() + "__"
                            + description.replace(' ', '_') + ".sql").getInputStream(), StandardCharsets.UTF_8);
            try (Connection c = connect(DB); Statement st = c.createStatement()) {
                st.execute(script);
                st.execute(script);
            }
        }
        UUID t = insertTenant("DB-085 idempotent");
        assertThat(endedAt(insertCampaign(t, "COMPLETED", null))).isNotNull();
        assertThat(endedAt(insertCampaign(t, "RUNNING", "now()"))).as("trigger po powtorce V140").isNull();
        // funkcja po powtorce V140+V141 ma zachowanie V141 (nie V140): wiersze juz w archiwum sa sprzatane
        UUID c = insertCampaign(t, "STOPPED", "now() - interval '200 days'");
        copyToArchive(insertContact(t, c));
        runArchive();
        assertThat(live(c)).isZero();
        assertThat(archived(c)).isEqualTo(1);
        assertThat(lastMessage()).contains("Usuniete: 1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'campaign'::regclass "
                + "AND tgname = 'trg_campaign_ended_at'", Integer.class)).isEqualTo(1);
    }

    // =============================================================================================
    // DB-086: DELETE tylko zarchiwizowanych wierszy
    // =============================================================================================

    @Test
    @DisplayName("wiersze JUZ w archiwum (ON CONFLICT wstawia 0) sa usuwane z campaign_contact; rows_affected = 0")
    void delete_rowsAlreadyInArchiveAreRemoved() {
        UUID t = insertTenant("DB-086 already archived");
        UUID c = insertCampaign(t, "STOPPED", "now() - interval '200 days'");
        List<UUID> records = List.of(insertContact(t, c), insertContact(t, c), insertContact(t, c));
        for (UUID record : records) {
            copyToArchive(record);
        }
        assertThat(live(c)).isEqualTo(3);
        assertThat(archived(c)).isEqualTo(3);

        runArchive();

        assertThat(live(c)).as("wszystkie sa w archiwum -> sprzatniete").isZero();
        assertThat(archived(c)).isEqualTo(3);
        assertThat(lastRowsAffected()).as("rows_affected = WSTAWIONE do archiwum: 0 (wszystko juz tam bylo)").isZero();
        assertThat(lastMessage()).as("liczba usunietych w message (DB85-R5)").contains("Usuniete: 3");

        runArchive();
        assertThat(live(c)).isZero();
        assertThat(archived(c)).as("bez duplikatow").isEqualTo(3);
        assertThat(lastMessage()).contains("Usuniete: 0");
    }

    @Test
    @DisplayName("wiersz dopisany do campaign_contact PO INSERT do archiwum a PRZED DELETE nie jest kasowany ani archiwizowany; nastepny przebieg go archiwizuje")
    void delete_rowAppendedBetweenInsertAndDeleteSurvives() {
        UUID t = insertTenant("DB-086 late row");
        UUID c = insertCampaign(t, "COMPLETED", "now() - interval '250 days'");
        insertContact(t, c);
        insertContact(t, c);

        // sekwencyjna symulacja wyscigu READ COMMITTED: row-level AFTER INSERT na archiwum (wykonuje sie w trakcie
        // INSERT ... SELECT, przed DELETE) dopisuje do campaign_contact "spozniony" rekord tej kampanii
        jdbc.execute("CREATE OR REPLACE FUNCTION db086_late_row() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM campaign_contact WHERE campaign_id = NEW.campaign_id AND first_name = 'Late') THEN "
                + "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                + "VALUES (gen_random_uuid(), NEW.campaign_id, NEW.tenant_id, '+48700000099', 'Late', 'COMPLETED'); "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER db086_late_row_trg AFTER INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.campaign_id = '" + c + "') EXECUTE FUNCTION db086_late_row()");
        try {
            runArchive();
            assertThat(archived(c)).as("2 zarchiwizowane, spozniony NIE").isEqualTo(2);
            assertThat(live(c)).as("spozniony rekord zostaje w campaign_contact").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ? AND first_name = 'Late'",
                    Integer.class, c)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ? AND first_name = 'Late'",
                    Integer.class, c)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS db086_late_row_trg ON campaign_contact_archive");
        }

        runArchive();   // trigger usuniety: nastepny przebieg archiwizuje spozniony rekord
        assertThat(live(c)).isZero();
        assertThat(archived(c)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ? AND first_name = 'Late'",
                Integer.class, c)).isEqualTo(1);
    }

    @Test
    @DisplayName("DB85-R1: wiersz ZMODYFIKOWANY miedzy INSERT a DELETE (okno) nie jest kasowany - zostaje w campaign_contact ze zmiana")
    void delete_rowModifiedInWindowSurvives() {
        UUID t = insertTenant("DB-086 modified row");
        UUID c = insertCampaign(t, "STOPPED", "now() - interval '250 days'");
        UUID stable = insertContact(t, c);
        UUID changed = insertContact(t, c);

        // AFTER INSERT na archiwum (w trakcie INSERT ... SELECT, przed DELETE) zmienia zywy wiersz tej samej transakcji
        jdbc.execute("CREATE OR REPLACE FUNCTION db086_modify_row() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "UPDATE campaign_contact SET attempt_count = attempt_count + 7, disposition_code = 'LATE_DISPO' "
                + "WHERE campaign_id = NEW.campaign_id AND record_id = NEW.record_id; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER db086_modify_row_trg AFTER INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.record_id = '" + changed + "') EXECUTE FUNCTION db086_modify_row()");
        try {
            runArchive();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS db086_modify_row_trg ON campaign_contact_archive");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE record_id = ?", Integer.class, stable))
                .as("niezmieniony wiersz zarchiwizowany i usuniety").isZero();
        assertThat(jdbc.queryForMap("SELECT attempt_count, disposition_code FROM campaign_contact WHERE record_id = ?", changed))
                .as("zmiana nie zginela: wiersz zostaje z nowym stanem")
                .containsEntry("attempt_count", 7).containsEntry("disposition_code", "LATE_DISPO");
        runArchive();   // rozjechana kopia nie jest nadpisywana ani usuwana: dane nie gina
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM campaign_contact WHERE record_id = ?", Integer.class, changed))
                .isEqualTo(7);
    }

    @Test
    @DisplayName("DB85-R1: wiersz z ROZJECHANA kopia w archiwum (wczesniejszy czesciowy przebieg) nie jest usuwany")
    void delete_rowWithStaleArchiveCopyIsKept() {
        UUID t = insertTenant("DB-086 stale copy");
        UUID c = insertCampaign(t, "COMPLETED", "now() - interval '250 days'");
        UUID record = insertContact(t, c);
        copyToArchive(record);
        jdbc.update("UPDATE campaign_contact SET attempt_count = 3 WHERE record_id = ?", record);   // zywy nowszy niz kopia

        runArchive();

        assertThat(live(c)).as("nie kasujemy wiersza, ktorego kopia jest starsza").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM campaign_contact WHERE record_id = ?", Integer.class, record)).isEqualTo(3);
    }

    @Test
    @DisplayName("DB85-R1: zrodlowe wiersze sa blokowane FOR UPDATE do konca przebiegu - rownolegly UPDATE czeka (lock_timeout 55P03)")
    void delete_sourceRowsAreLockedDuringRun() throws Exception {
        UUID t = insertTenant("DB-086 lock");
        UUID c = insertCampaign(t, "STOPPED", "now() - interval '250 days'");
        insertContact(t, c);
        insertContact(t, c);

        // wnetrze okna: AFTER INSERT na archiwum czeka na advisory lock trzymany przez test
        jdbc.execute("CREATE OR REPLACE FUNCTION db086_hold_window() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "PERFORM pg_advisory_lock(860601); PERFORM pg_advisory_unlock(860601); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER db086_hold_window_trg AFTER INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.campaign_id = '" + c + "') EXECUTE FUNCTION db086_hold_window()");
        CompletableFuture<Void> run = null;
        try (Connection holder = connect(DB); Statement hs = holder.createStatement();
             Connection other = connect(DB); Statement os = other.createStatement()) {
            hs.execute("SELECT pg_advisory_lock(860601)");
            run = CompletableFuture.runAsync(Db085Db086CampaignArchiveHardeningMigrationsTest::runArchive);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class) == 0) {
                assertThat(System.nanoTime()).as("funkcja nie weszla w okno").isLessThan(deadline);
                Thread.sleep(50);
            }
            os.execute("SET lock_timeout = '300ms'");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    os.execute("UPDATE campaign_contact SET attempt_count = attempt_count + 1 WHERE campaign_id = '" + c + "'"))
                    .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("55P03");
            hs.execute("SELECT pg_advisory_unlock(860601)");
            run.get(30, TimeUnit.SECONDS);
        } finally {
            if (run != null) {
                run.cancel(true);
            }
            jdbc.execute("DROP TRIGGER IF EXISTS db086_hold_window_trg ON campaign_contact_archive");
        }
        assertThat(archived(c)).isEqualTo(2);
        assertThat(live(c)).isZero();
    }

    @Test
    @DisplayName("DB85-R2: dedykowana partycja LIST jest DROP-owana gdy pusta po DELETE, ale NIE gdy zostal w niej wiersz (dopisany w oknie)")
    void drop_dedicatedPartitionOnlyWhenEmpty() {
        UUID t = insertTenant("DB-086 partition");
        UUID emptied = insertCampaign(t, "STOPPED", "now() - interval '250 days'");
        UUID late = insertCampaign(t, "STOPPED", "now() - interval '250 days'");
        for (UUID c : List.of(emptied, late)) {
            jdbc.execute("CREATE TABLE " + partitionName(c) + " PARTITION OF campaign_contact FOR VALUES IN ('" + c + "')");
            insertContact(t, c);
        }
        jdbc.execute("CREATE OR REPLACE FUNCTION db086_late_row_p() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NOT EXISTS (SELECT 1 FROM campaign_contact WHERE campaign_id = NEW.campaign_id AND first_name = 'Late') THEN "
                + "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                + "VALUES (gen_random_uuid(), NEW.campaign_id, NEW.tenant_id, '+48700000098', 'Late', 'COMPLETED'); "
                + "END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER db086_late_row_p_trg AFTER INSERT ON campaign_contact_archive "
                + "FOR EACH ROW WHEN (NEW.campaign_id = '" + late + "') EXECUTE FUNCTION db086_late_row_p()");
        try {
            runArchive();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS db086_late_row_p_trg ON campaign_contact_archive");
        }

        assertThat(partitionExists(emptied)).as("pusta po DELETE -> DROP").isFalse();
        assertThat(archived(emptied)).isEqualTo(1);
        assertThat(partitionExists(late)).as("zostal wiersz 'Late' -> partycja NIE jest dropowana").isTrue();
        assertThat(live(late)).isEqualTo(1);

        runArchive();   // trigger usuniety: wiersz zarchiwizowany, partycja pusta -> DROP
        assertThat(partitionExists(late)).isFalse();
        assertThat(archived(late)).isEqualTo(2);
    }

    @Test
    @DisplayName("parytet i idempotencja: usunieto dokladnie tyle, ile jest w archiwum; drugi przebieg niczego nie przenosi; inne kampanie nietkniete")
    void delete_parityAndIdempotence() {
        UUID t = insertTenant("DB-086 parity");
        UUID done = insertCampaign(t, "STOPPED", "now() - interval '220 days'");
        UUID fresh = insertCampaign(t, "STOPPED", "now() - interval '5 days'");
        UUID running = insertCampaign(t, "RUNNING", null);
        for (int i = 0; i < 5; i++) {
            insertContact(t, done);
        }
        insertContact(t, fresh);
        insertContact(t, running);

        runArchive();

        assertThat(archived(done)).isEqualTo(5);
        assertThat(live(done)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign_contact cc JOIN campaign_contact_archive a "
                + "ON a.record_id = cc.record_id AND a.campaign_id = cc.campaign_id WHERE cc.campaign_id = ?",
                Integer.class, done)).as("nic nie zostalo jednoczesnie w obu tabelach").isZero();
        assertThat(live(fresh)).isEqualTo(1);
        assertThat(live(running)).isEqualTo(1);

        runArchive();
        assertThat(archived(done)).isEqualTo(5);
        assertThat(live(done)).isZero();
        assertThat(archived(fresh)).isZero();
        assertThat(archived(running)).isZero();
    }

    @Test
    @DisplayName("komentarz funkcji i opis rows_affected zgodne z faktycznym warunkiem DELETE (zachowanie, nie prosrc)")
    void delete_commentDescribesBehaviour() {
        String c = jdbc.queryForObject("SELECT obj_description('archive_completed_campaign_contacts()'::regprocedure, 'pg_proc')",
                String.class);
        assertThat(c).contains("WYLACZNIE").contains("record_id + campaign_id").contains("WSTAWIONYCH");
    }

    // =============================================================================================
    // pomocnicze
    // =============================================================================================

    private static void runArchive() {
        jdbc.execute("SELECT archive_completed_campaign_contacts()");
    }

    private static String lastMessage() {
        return jdbc.queryForObject("SELECT message FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                + "ORDER BY log_id DESC LIMIT 1", String.class, JOB);
    }

    private static long lastRowsAffected() {
        return jdbc.queryForObject("SELECT rows_affected FROM cron_log WHERE job_name = ? AND status = 'SUCCESS' "
                + "ORDER BY log_id DESC LIMIT 1", Long.class, JOB);
    }

    private static String partitionName(UUID campaign) {
        return "campaign_contact_" + campaign.toString().replace('-', '_');
    }

    private static boolean partitionExists(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                Integer.class, partitionName(campaign)) > 0;
    }

    private static UUID insertTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", id, name + " " + id);
        return id;
    }

    /** Kampania; {@code endedAtExpr} = wyrazenie SQL jawnego ended_at albo null (wtedy decyduje trigger). */
    private static UUID insertCampaign(UUID tenant, String status, String endedAtExpr) {
        UUID id = UUID.randomUUID();
        if (endedAtExpr == null) {
            jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, now() - interval '500 days', now() - interval '1 day')",
                    id, tenant, "DB-085 " + id, status);
        } else {
            jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, ended_at) "
                    + "VALUES (?, ?, ?, ?, now() - interval '500 days', now() - interval '1 day', " + endedAtExpr + ")",
                    id, tenant, "DB-085 " + id, status);
        }
        return id;
    }

    private static UUID insertCampaignWithoutTriggers(UUID tenant, String status) throws SQLException {
        return insertCampaignWithoutTriggers(tenant, status, "now() - interval '500 days'", "now() - interval '1 day'", null);
    }

    /** Wiersz "jak sprzed triggera": trigger ended_at/updated_at wylaczone tylko na tym polaczeniu (replica). */
    private static UUID insertCampaignWithoutTriggers(UUID tenant, String status, String createdAtExpr, String updatedAtExpr,
                                                      String endedAtExpr) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(DB); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("INSERT INTO campaign (campaign_id, tenant_id, name, status, created_at, updated_at, ended_at) VALUES ('"
                    + id + "', '" + tenant + "', 'DB-085 raw " + id + "', '" + status + "', " + createdAtExpr + ", "
                    + (updatedAtExpr == null ? "NULL" : updatedAtExpr) + ", "
                    + (endedAtExpr == null ? "NULL" : endedAtExpr) + ")");
            st.execute("RESET session_replication_role");
        }
        return id;
    }

    private static UUID insertContact(UUID tenant, UUID campaign) {
        UUID record = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status) "
                + "VALUES (?, ?, ?, ?, 'Db085', 'COMPLETED')", record, campaign, tenant,
                "+48" + (700000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 99999999L)));
        return record;
    }

    private static void copyToArchive(UUID record) {
        jdbc.update("INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                + "last_name, email, custom_fields, status, attempt_count, last_attempt_at, disposition_code, last_contact_id, "
                + "created_at, updated_at) SELECT record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                + "last_name, email, custom_fields, status, attempt_count, last_attempt_at, disposition_code, last_contact_id, "
                + "created_at, updated_at FROM campaign_contact WHERE record_id = ?", record);
    }

    private static int live(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact WHERE campaign_id = ?", Integer.class, campaign);
    }

    private static int archived(UUID campaign) {
        return jdbc.queryForObject("SELECT count(*) FROM campaign_contact_archive WHERE campaign_id = ?", Integer.class, campaign);
    }

    private static Timestamp endedAt(UUID campaign) {
        return jdbc.queryForObject("SELECT ended_at FROM campaign WHERE campaign_id = ?", Timestamp.class, campaign);
    }

    private static Timestamp updatedAt(UUID campaign) {
        return jdbc.queryForObject("SELECT updated_at FROM campaign WHERE campaign_id = ?", Timestamp.class, campaign);
    }

    private static MigrationVersion versionOf(String description) {
        return chain.stream().filter(m -> description.equals(m.getDescription())).map(MigrationInfo::getVersion)
                .findFirst().orElseThrow(() -> new AssertionError("brak migracji '" + description + "'"));
    }

    private static FluentConfiguration flyway() {
        return Flyway.configure().dataSource(jdbcUrl(DB), USER, PASSWORD).locations("classpath:db/migration");
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
