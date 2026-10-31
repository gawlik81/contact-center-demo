package com.contactcenter.domain.gdpr;

import com.contactcenter.support.TestcontainersSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla DB-061 --
 * naprawy i rozszerzenia {@code export_customer_data} (RODO Art. 15/20) oraz nowej funkcji
 * pomocniczej {@code fn_customer_subject_ids} (decyzja D9 = A, reużywana przez DB-062).
 *
 * <p><strong>U3 (DB-060):</strong> {@code export_customer_data} (V017) jest {@code STABLE} i kończy się
 * {@code INSERT INTO audit_log} — PostgreSQL odrzuca DML w funkcji nie-VOLATILE. Baza „pre" (Flyway
 * {@code target} = wersja BEZPOŚREDNIO przed migracją DB-061, wyznaczana dynamicznie po opisie migracji,
 * zgodnie z wzorcem {@code ContactRefIntegrityNarrowingTest}/DB-079) dokumentuje ten błąd działaniem.
 * Baza „post" (pełny łańcuch, ta sama migracja rozpoznana po opisie) dowodzi naprawy i rozszerzonego
 * zakresu na bogatym fixture'cie: klient z kontaktami (w tym bez {@code customer_id}, powiązany
 * wyłącznie identyfikatorem — telefon/e-mail znormalizowany), e-mailami z załącznikami, wiadomością
 * social, callbackami, rekordami kampanii operacyjnymi i w archiwum, transkrypcją i podsumowaniem AI —
 * oraz izolację między klientami/tenantami dzielącymi ten sam numer/adres.
 *
 * <p>Nie używamy mocków — błędów SQL (volatility, RLS, CHECK) nie złapie {@code EntityManager}/Mockito
 * (lekcja EPIC-29/WP-1).
 */
@Testcontainers
@DisplayName("export_customer_data + fn_customer_subject_ids (V095, DB-061)")
class ExportCustomerDataSubjectHelperTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    /**
     * Opis migracji DB-061 w Flyway = nazwa pliku bez numeru/prefiksu, {@code _} -> spacja. Rozpoznanie
     * po opisie (nie po numerze V095) — numer może się zmienić przy scalaniu gałęzi.
     */
    private static final String EXPORT_FIX_MIGRATION_DESCRIPTION = "fix export customer data and add subject helper";

    private static final String PRE_DB = "cc_pre_db061";
    private static final String POST_DB = "cc_post_db061";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(PRE_DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- fixture ---------------------------------------------------------------------------

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    /** Klient podmiotu — pełny zestaw danych, w tym powiązania wyłącznie identyfikatorowe. */
    private static final UUID CUSTOMER = UUID.randomUUID();
    /** Inny klient tego samego tenanta — jego dane nie mogą wyciec do eksportu CUSTOMER. */
    private static final UUID CUSTOMER_OTHER_SAME_TENANT = UUID.randomUUID();
    /** Klient innego tenanta z TYM SAMYM telefonem/e-mailem — test izolacji cross-tenant przy współdzielonym identyfikatorze. */
    private static final UUID CUSTOMER_FOREIGN_TENANT = UUID.randomUUID();

    private static final String PHONE = "+48500100200";
    private static final String PHONE_WITH_SEPARATORS = "+48 500-100 (200)";
    private static final String EMAIL = "jan.testowy@example.com";
    private static final String EMAIL_UPPER = "JAN.TESTOWY@EXAMPLE.COM";
    private static final String RECORDING_KEY = "1111/aaaa/rec.mp3";

    private static final UUID CONTACT_LINKED = UUID.randomUUID();
    private static final UUID CONTACT_IDENTIFIER = UUID.randomUUID();
    private static final UUID CONTACT_OTHER = UUID.randomUUID();

    private static final UUID CAMPAIGN_1 = UUID.randomUUID();
    private static final UUID CAMPAIGN_2 = UUID.randomUUID();
    private static final UUID CAMPAIGN_CONTACT_LINKED = UUID.randomUUID();
    private static final UUID CAMPAIGN_CONTACT_IDENTIFIER = UUID.randomUUID();
    private static final UUID CAMPAIGN_CONTACT_BRIDGE = UUID.randomUUID();
    private static final UUID CAMPAIGN_CONTACT_ARCHIVE_LINKED = UUID.randomUUID();

    private static final UUID CALLBACK_LINKED = UUID.randomUUID();
    private static final UUID CALLBACK_IDENTIFIER = UUID.randomUUID();
    private static final UUID CALLBACK_BRIDGE = UUID.randomUUID();

    /** Kontakt powstały z rekordu kampanii CAMPAIGN_CONTACT_BRIDGE (dialer) — testuje regułę "mostu". */
    private static final UUID CONTACT_FROM_BRIDGE_RECORD = UUID.randomUUID();

    /** Wersja migracji DB-061 i wersja BEZPOŚREDNIO ją poprzedzająca w łańcuchu Flyway. */
    private static ExportFixMigration exportFixMigration;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        exportFixMigration = findExportFixMigration();

        FluentConfiguration pre = flyway(PRE_DB);
        if (exportFixMigration != null) {
            pre.target(exportFixMigration.previousVersion());
        }
        pre.load().migrate();

        try (Connection c = connect(PRE_DB)) {
            seedMinimalCustomer(c);
        }

        try (Connection admin = connect("postgres")) {
            update(admin, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = ? AND pid <> pg_backend_pid()", PRE_DB);
            update(admin, "CREATE DATABASE " + POST_DB + " TEMPLATE " + PRE_DB);
        }
        flyway(POST_DB).load().migrate();

        try (Connection c = connect(POST_DB)) {
            seedRichFixture(c);
        }
    }

    // =========================================================================================
    // U3 -- dowód działaniem: przed DB-061 funkcja rzuca, po DB-061 zwraca JSONB
    // =========================================================================================

    @Test
    @DisplayName("łańcuch Flyway zawiera migrację DB-061 (rozpoznaną po opisie): SUCCESS na 'post', PENDING na 'pre'")
    void chainHasExportFixMigrationApplied() {
        assertThat(exportFixMigration)
                .as("migracja DB-061 (opis '%s') musi istnieć w łańcuchu Flyway na classpath",
                        EXPORT_FIX_MIGRATION_DESCRIPTION)
                .isNotNull();

        MigrationInfo inPost = migration(POST_DB, exportFixMigration.version());
        assertThat(inPost.getDescription()).isEqualTo(EXPORT_FIX_MIGRATION_DESCRIPTION);
        assertThat(inPost.getState().isApplied()).as("DB-061 zastosowana na 'post'").isTrue();

        MigrationInfo inPre = migration(PRE_DB, exportFixMigration.version());
        assertThat(inPre.getState().isApplied()).as("DB-061 NIE może być zastosowana na 'pre'").isFalse();
    }

    @Test
    @DisplayName("U3 przed DB-061: export_customer_data (STABLE + INSERT INTO audit_log) rzuca 'INSERT is not allowed in a non-volatile function'")
    void u3_beforeMigration_stableFunctionWithInsertFails() throws Exception {
        SQLException error = inRolledBackTx(PRE_DB, c -> failureOf(c,
                "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A));

        assertThat((Throwable) error).as("export_customer_data przed DB-061 musi rzucić").isNotNull();
        assertThat(error.getMessage()).containsIgnoringCase("not allowed in a non-volatile function");
    }

    @Test
    @DisplayName("po DB-061: export_customer_data na tym samym kliencie zwraca JSONB bez błędu")
    void afterMigration_exportSucceedsForSameCustomer() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            JsonNode result = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);
            assertThat(result.get("legal_basis").asText()).isEqualTo("GDPR_ART_15_20");
            return null;
        });
    }

    // =========================================================================================
    // Kompletność zakresu (macierz DB-060) + izolacja
    // =========================================================================================

    @Test
    @DisplayName("eksport zawiera pełny zestaw danych klienta: kontakty (link + identifier), e-maile z załącznikami, social, callbacki, rekordy kampanii (operacyjne + archiwum), transkrypcję, podsumowanie AI")
    void exportContainsFullSubjectDataset() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            JsonNode result = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);

            // --- contacts: 3 -- linked, identifier, i kontakt "mostu" (powiazany przez campaign_contact_record_id)
            assertThat(idsOf(result.get("contacts"), "contact_id")).containsExactlyInAnyOrder(
                    CONTACT_LINKED.toString(), CONTACT_IDENTIFIER.toString(), CONTACT_FROM_BRIDGE_RECORD.toString());
            JsonNode linkedContact = elementWithId(result.get("contacts"), "contact_id", CONTACT_LINKED);
            assertThat(linkedContact.get("matched_by").asText()).isEqualTo("link");
            assertThat(linkedContact.get("notes").asText()).isEqualTo("notatka agenta");
            assertThat(linkedContact.get("recording_url").asText()).isEqualTo(RECORDING_KEY);
            JsonNode identifierContact = elementWithId(result.get("contacts"), "contact_id", CONTACT_IDENTIFIER);
            assertThat(identifierContact.get("matched_by").asText()).isEqualTo("identifier");

            // --- transcriptions / ai_summaries -- tylko dla kontaktu z customer_id
            assertThat(result.get("transcriptions")).hasSize(1);
            assertThat(result.get("transcriptions").get(0).get("content").asText()).isEqualTo("tresc transkrypcji");
            assertThat(result.get("ai_summaries")).hasSize(1);
            assertThat(result.get("ai_summaries").get(0).get("summary").asText()).isEqualTo("podsumowanie AI");

            // --- email_messages: link + identifier, z metadanymi zalacznikow
            assertThat(result.get("email_messages")).hasSize(2);
            JsonNode linkedEmail = elementWithText(result.get("email_messages"), "matched_by", "link");
            JsonNode attachment = linkedEmail.get("attachments").get(0);
            assertThat(attachment.get("filename").asText()).isEqualTo("zal.pdf");
            assertThat(attachment.get("content_type").asText()).isEqualTo("application/pdf");
            assertThat(attachment.get("size_bytes").asInt()).isEqualTo(1234);
            assertThat(attachment.get("s3_key").asText()).isEqualTo("email-attachments/" + TENANT_A + "/msg1/zal.pdf");
            JsonNode identifierEmail = elementWithText(result.get("email_messages"), "matched_by", "identifier");
            assertThat(identifierEmail.get("to_address").asText()).isEqualTo(EMAIL_UPPER);

            // --- social_messages
            assertThat(result.get("social_messages")).hasSize(1);
            assertThat(result.get("social_messages").get(0).get("sender_external_id").asText()).isEqualTo("wa-sender-1");

            // --- scheduled_callbacks: link + identifier + bridge
            assertThat(idsOf(result.get("scheduled_callbacks"), "callback_id")).containsExactlyInAnyOrder(
                    CALLBACK_LINKED.toString(), CALLBACK_IDENTIFIER.toString(), CALLBACK_BRIDGE.toString());
            assertThat(elementWithId(result.get("scheduled_callbacks"), "callback_id", CALLBACK_BRIDGE)
                    .get("matched_by").asText()).as("callback powiazany wylacznie przez campaign_contact_record_id (regula mostu)").isEqualTo("link");

            // --- campaign_records: operational link + operational identifier + operational bridge + archive link
            assertThat(idsOf(result.get("campaign_records"), "record_id")).containsExactlyInAnyOrder(
                    CAMPAIGN_CONTACT_LINKED.toString(), CAMPAIGN_CONTACT_IDENTIFIER.toString(),
                    CAMPAIGN_CONTACT_BRIDGE.toString(), CAMPAIGN_CONTACT_ARCHIVE_LINKED.toString());
            JsonNode archiveRecord = elementWithId(result.get("campaign_records"), "record_id", CAMPAIGN_CONTACT_ARCHIVE_LINKED);
            assertThat(archiveRecord.get("source").asText()).isEqualTo("archive");
            assertThat(archiveRecord.get("phone").asText()).isEqualTo(PHONE);
            assertThat(archiveRecord.get("first_name").asText()).isEqualTo("Jan");
            JsonNode bridgeRecord = elementWithId(result.get("campaign_records"), "record_id", CAMPAIGN_CONTACT_BRIDGE);
            assertThat(bridgeRecord.get("matched_by").asText())
                    .as("rekord kampanii powiazany wylacznie przez kontakt utworzony z tego rekordu (regula mostu)").isEqualTo("link");

            // --- s3_keys: nagranie + zalacznik e-mail linked (identifier email bez s3_key ustawionego)
            List<String> s3Keys = StreamSupport.stream(result.get("s3_keys").spliterator(), false)
                    .map(JsonNode::asText).toList();
            assertThat(s3Keys).containsExactlyInAnyOrder(
                    RECORDING_KEY,
                    "email-attachments/" + TENANT_A + "/msg1/zal.pdf",
                    "email-attachments/" + TENANT_A + "/pending/uuid123/zal2.pdf");

            // --- liczniki matched_by
            assertThat(result.get("matched_by_link").asInt()).isEqualTo(9);
            assertThat(result.get("matched_by_identifier").asInt()).isEqualTo(4);

            // --- customer.external_id / gdpr_consent obecne w profilu
            assertThat(result.get("customer").get("customer_id").asText()).isEqualTo(CUSTOMER.toString());
            return null;
        });
    }

    @Test
    @DisplayName("dane innego klienta tego samego tenanta oraz klienta innego tenanta (ten sam telefon/e-mail) nie wyciekają do eksportu")
    void exportDoesNotLeakOtherCustomersOrForeignTenant() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            JsonNode result = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);

            Set<String> allContactIds = idsOf(result.get("contacts"), "contact_id");
            assertThat(allContactIds).doesNotContain(CONTACT_OTHER.toString());

            // klient innego tenanta ma identyczny telefon/e-mail -- eksport CUSTOMER nie widzi jego wierszy
            JsonNode foreignExport = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER_FOREIGN_TENANT, TENANT_B);
            assertThat(foreignExport.get("contacts")).isEmpty();
            assertThat(foreignExport.get("email_messages")).isEmpty();
            assertThat(foreignExport.get("scheduled_callbacks")).isEmpty();
            assertThat(foreignExport.get("campaign_records")).isEmpty();
            assertThat(foreignExport.get("matched_by_link").asInt()).isZero();
            assertThat(foreignExport.get("matched_by_identifier").asInt()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("wywołanie z p_tenant_id innego tenanta rzuca wyjątek (klient nie istnieje w tym tenancie)")
    void wrongTenantIsRejected() throws Exception {
        SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_B));
        assertThat((Throwable) error).isNotNull();
        assertThat(error.getMessage()).contains("nie istnieje lub zostal zanonimizowany");
    }

    // =========================================================================================
    // Brak efektów ubocznych / spójność
    // =========================================================================================

    @Test
    @DisplayName("funkcja jest czysto odczytowa: dwa wywołania w jednej transakcji dają identyczny wynik i nie zmieniają audit_log")
    void functionIsReadOnly_noSideEffectsAndConsistentAcrossCalls() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            String before = scalar(c, "SELECT count(*)::text FROM audit_log");
            JsonNode first = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);
            JsonNode second = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);
            String after = scalar(c, "SELECT count(*)::text FROM audit_log");

            assertThat(after).as("audit_log niezmieniony przez export_customer_data").isEqualTo(before);
            for (String field : List.of("customer", "contacts", "scheduled_callbacks", "campaign_records",
                    "email_messages", "social_messages", "transcriptions", "ai_summaries", "s3_keys",
                    "matched_by_link", "matched_by_identifier")) {
                assertThat(first.get(field)).as("pole '%s' identyczne miedzy dwoma wywolaniami", field)
                        .isEqualTo(second.get(field));
            }
            return null;
        });
    }

    @Test
    @DisplayName("funkcja jest STABLE i SELECT-only w pg_proc (provolatile = 's', prosrc bez INSERT/UPDATE/DELETE)")
    void functionMetadataIsStableAndReadOnly() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            assertThat(scalar(c, "SELECT provolatile::text FROM pg_proc WHERE proname = 'export_customer_data'"))
                    .isEqualTo("s");
            String body = scalar(c, "SELECT prosrc FROM pg_proc WHERE proname = 'export_customer_data'");
            assertThat(body).doesNotContainIgnoringCase("insert into")
                    .doesNotContainIgnoringCase("update ")
                    .doesNotContainIgnoringCase("delete from");
            assertThat(scalar(c, "SELECT provolatile::text FROM pg_proc WHERE proname = 'fn_customer_subject_ids'"))
                    .isEqualTo("s");
            return null;
        });
    }

    // =========================================================================================
    // RLS (WP-4)
    // =========================================================================================

    @Test
    @DisplayName("pod SET ROLE app_user + GUC app.current_tenant_id: export_customer_data działa bez SECURITY DEFINER i zwraca ten sam zbiór")
    void underAppUserRole_worksWithoutSecurityDefiner() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            assertThat(scalar(c, "SELECT prosecdef::text FROM pg_proc WHERE proname = 'export_customer_data'")).isEqualTo("false");
            assertThat(scalar(c, "SELECT prosecdef::text FROM pg_proc WHERE proname = 'fn_customer_subject_ids'")).isEqualTo("false");

            asAppUser(c, TENANT_A);
            JsonNode result = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);
            assertThat(result.get("matched_by_link").asInt()).isEqualTo(9);
            assertThat(result.get("matched_by_identifier").asInt()).isEqualTo(4);
            return null;
        });
    }

    @Test
    @DisplayName("pod SET ROLE app_user bez ustawionego GUC: RLS na customer (FORCE) ukrywa wiersz -- funkcja kończy się czytelnym wyjątkiem, nie cichym pustym wynikiem")
    void underAppUserRole_withoutGuc_failsSafelyOnMissingCustomer() throws Exception {
        SQLException error = inRolledBackTx(POST_DB, c -> {
            update(c, "SET LOCAL ROLE app_user");
            return failureOf(c, "SELECT export_customer_data(?, ?)", CUSTOMER, TENANT_A);
        });
        assertThat((Throwable) error).isNotNull();
        assertThat(error.getMessage()).contains("nie istnieje lub zostal zanonimizowany");
    }

    // =========================================================================================
    // Fixture i pomocnicze
    // =========================================================================================

    /** Klient minimalny na bazie 'pre' -- U3 nie zależy od bogactwa danych, tylko od istnienia klienta. */
    private static void seedMinimalCustomer(Connection c) throws SQLException {
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, 'Tenant A - DB-061 IT')", TENANT_A);
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Jan', 'Testowy', ?::jsonb, ?::jsonb, 'MANUAL')",
                CUSTOMER, TENANT_A, "[\"" + PHONE + "\"]", "[\"" + EMAIL + "\"]");
    }

    private static void seedRichFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, 'Tenant B - DB-061 IT')", TENANT_B);
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Ktos', 'Inny', '[]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_OTHER_SAME_TENANT, TENANT_A);
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Obcy', 'Klient', ?::jsonb, ?::jsonb, 'MANUAL')",
                CUSTOMER_FOREIGN_TENANT, TENANT_B, "[\"" + PHONE + "\"]", "[\"" + EMAIL + "\"]");

        // kontakt niepowiazany z CUSTOMER (kontrola negatywna izolacji)
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', '+48000000000', now())",
                CONTACT_OTHER, TENANT_A, CUSTOMER_OTHER_SAME_TENANT);

        // kontakt powiazany bezposrednio (customer_id)
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                        + "started_at, ended_at, duration_seconds, notes, recording_url, channel_metadata) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now() - interval '2 days', "
                        + "now() - interval '2 days' + interval '5 minutes', 300, 'notatka agenta', ?, '{}')",
                CONTACT_LINKED, TENANT_A, CUSTOMER, PHONE, RECORDING_KEY);

        // kontakt powiazany WYLACZNIE identyfikatorem (customer_id NULL, telefon z separatorami)
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, NULL, 'PHONE', 'OUTBOUND', 'COMPLETED', ?, now() - interval '1 days')",
                CONTACT_IDENTIFIER, TENANT_A, PHONE_WITH_SEPARATORS);

        update(c, "INSERT INTO contact_transcription (transcription_id, contact_id, tenant_id, content, language, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'tresc transkrypcji', 'pl', now())",
                CONTACT_LINKED, TENANT_A);
        update(c, "INSERT INTO contact_ai_summary (ai_summary_id, contact_id, tenant_id, summary, model, generated_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'podsumowanie AI', 'gpt-test', now())",
                CONTACT_LINKED, TENANT_A);

        // e-mail powiazany przez contact_id, z zalacznikiem
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                        + "cc_address, bcc_address, subject, body_html, body_text, attachments, received_at, delivery_status) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'INBOUND', ?, 'agent@firma.pl', 'kopia@firma.pl', 'ukryta@firma.pl', "
                        + "'Temat', '<p>tresc html</p>', 'tresc plain', "
                        + "('[{\"filename\":\"zal.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":1234,"
                        + "\"s3_key\":\"email-attachments/' || ? || '/msg1/zal.pdf\"}]')::jsonb, now(), 'DELIVERED')",
                TENANT_A, CONTACT_LINKED, EMAIL, TENANT_A.toString());

        // e-mail osierocony, powiazany wylacznie identyfikatorem (to_address = e-mail klienta, wielkie litery)
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                        + "subject, attachments, sent_at, delivery_status) "
                        + "VALUES (gen_random_uuid(), ?, NULL, 'OUTBOUND', 'support@firma.pl', ?, 'Odpowiedz', "
                        + "('[{\"filename\":\"zal2.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":55,"
                        + "\"s3_key\":\"email-attachments/' || ? || '/pending/uuid123/zal2.pdf\"}]')::jsonb, now(), 'SENT')",
                TENANT_A, EMAIL_UPPER, TENANT_A.toString());

        // social message (WhatsApp) powiazany przez contact_id
        update(c, "INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, "
                        + "sender_external_id, content, sent_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'WHATSAPP', 'INBOUND', 'wa-ext-1', 'wa-sender-1', 'tresc wiadomosci', now())",
                TENANT_A, CONTACT_LINKED);

        // callback powiazany przez customer_id
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "scheduled_at, notes, status, source_type) "
                        + "VALUES (?, ?, ?, ?, 'Jan', 'Testowy', now() + interval '1 day', 'oddzwonic', 'PENDING', 'AGENT_MANUAL')",
                CALLBACK_LINKED, TENANT_A, CUSTOMER, PHONE);

        // callback powiazany wylacznie identyfikatorem (telefon z separatorami)
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, source_type) "
                        + "VALUES (?, ?, NULL, ?, now() + interval '2 days', 'PENDING', 'INBOUND_CALLBACK')",
                CALLBACK_IDENTIFIER, TENANT_A, PHONE_WITH_SEPARATORS);

        // kampanie + rekordy
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name, type, dialer_type, status, caller_id) "
                        + "VALUES (?, ?, 'Kampania DB-061 A', 'OUTBOUND_VOICE', 'PROGRESSIVE', 'DRAFT', '+48500000000')",
                CAMPAIGN_1, TENANT_A);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name, type, dialer_type, status, caller_id) "
                        + "VALUES (?, ?, 'Kampania DB-061 B', 'OUTBOUND_VOICE', 'PROGRESSIVE', 'DRAFT', '+48500000000')",
                CAMPAIGN_2, TENANT_A);

        // rekord kampanii powiazany przez customer_id
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'PENDING', 0)",
                CAMPAIGN_CONTACT_LINKED, CAMPAIGN_1, TENANT_A, CUSTOMER, PHONE, EMAIL);

        // rekord kampanii powiazany wylacznie identyfikatorem (inna kampania, zeby ominac unique(campaign_id, phone))
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) "
                        + "VALUES (?, ?, ?, NULL, ?, 'X', 'Y', 'other@example.com', '{}'::jsonb, 'COMPLETED', 1)",
                CAMPAIGN_CONTACT_IDENTIFIER, CAMPAIGN_2, TENANT_A, PHONE);

        // rekord kampanii bez customer_id/last_contact_id i z INNYM telefonem -- powiazany WYLACZNIE
        // przez kontakt CONTACT_FROM_BRIDGE_RECORD (contact.campaign_contact_record_id = ten rekord)
        UUID bridgeCampaign = UUID.randomUUID();
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name, type, dialer_type, status, caller_id) "
                        + "VALUES (?, ?, 'Kampania DB-061 Bridge', 'OUTBOUND_VOICE', 'PROGRESSIVE', 'DRAFT', '+48500000000')",
                bridgeCampaign, TENANT_A);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) "
                        + "VALUES (?, ?, ?, NULL, '+48999999999', 'Bridge', 'Record', 'bridge@example.com', '{}'::jsonb, 'COMPLETED', 1)",
                CAMPAIGN_CONTACT_BRIDGE, bridgeCampaign, TENANT_A);
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                        + "started_at, campaign_contact_record_id) "
                        + "VALUES (?, ?, ?, 'PHONE', 'OUTBOUND', 'COMPLETED', '+48999999999', now(), ?)",
                CONTACT_FROM_BRIDGE_RECORD, TENANT_A, CUSTOMER, CAMPAIGN_CONTACT_BRIDGE);

        // callback powiazany WYLACZNIE przez campaign_contact_record_id = CAMPAIGN_CONTACT_BRIDGE
        // (inny telefon niz klienta, customer_id NULL -- regula mostu jest jedynym powiazaniem)
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, "
                        + "source_type, campaign_contact_record_id) "
                        + "VALUES (?, ?, NULL, '+48999999999', now() + interval '3 days', 'PENDING', 'CAMPAIGN_CALLBACK', ?)",
                CALLBACK_BRIDGE, TENANT_A, CAMPAIGN_CONTACT_BRIDGE);

        // archiwum kampanii powiazane przez customer_id
        update(c, "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                        + "last_name, email, custom_fields, status, attempt_count, created_at, archived_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'COMPLETED', 2, now(), now())",
                CAMPAIGN_CONTACT_ARCHIVE_LINKED, CAMPAIGN_1, TENANT_A, CUSTOMER, PHONE, EMAIL);
    }

    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET LOCAL ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString());
    }

    private static Set<String> idsOf(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(n -> n.get(field).asText())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static JsonNode elementWithId(JsonNode array, String field, UUID id) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get(field).asText().equals(id.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("brak elementu " + field + "=" + id + " w " + array));
    }

    private static JsonNode elementWithText(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(n -> n.get(field).asText().equals(value))
                .findFirst()
                .orElseThrow(() -> new AssertionError("brak elementu " + field + "=" + value + " w " + array));
    }

    @FunctionalInterface
    private interface TxBody<T> {
        T run(Connection c) throws SQLException;
    }

    private static <T> T inRolledBackTx(String db, TxBody<T> body) throws SQLException {
        try (Connection c = connect(db)) {
            c.setAutoCommit(false);
            try {
                return body.run(c);
            } finally {
                c.rollback();
            }
        }
    }

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

    private static JsonNode scalarJson(Connection c, String sql, Object... params) throws SQLException {
        String text = scalar(c, "SELECT (" + sql.substring("SELECT ".length()) + ")::text", params);
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("nie udalo sie sparsowac JSON: " + text, e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private record ExportFixMigration(MigrationVersion version, MigrationVersion previousVersion) {
    }

    private static ExportFixMigration findExportFixMigration() {
        List<MigrationInfo> chain = Arrays.stream(flyway(PRE_DB).load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        for (int i = 1; i < chain.size(); i++) {
            if (EXPORT_FIX_MIGRATION_DESCRIPTION.equals(chain.get(i).getDescription())) {
                return new ExportFixMigration(chain.get(i).getVersion(), chain.get(i - 1).getVersion());
            }
        }
        return null;
    }

    private static MigrationInfo migration(String db, MigrationVersion version) {
        return Arrays.stream(flyway(db).load().info().all())
                .filter(m -> version.equals(m.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("brak migracji " + version + " w Flyway#info() bazy " + db));
    }

    private static FluentConfiguration flyway(String db) {
        return Flyway.configure()
                .dataSource(jdbcUrl(db), USER, PASSWORD)
                .locations("classpath:db/migration");
    }

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
