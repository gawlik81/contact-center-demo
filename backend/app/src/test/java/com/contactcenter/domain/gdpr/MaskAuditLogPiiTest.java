package com.contactcenter.domain.gdpr;

import com.contactcenter.support.TestcontainersSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Savepoint;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla BE-142
 * Zakres p.2 (DB-062/V098) -- maskowania kluczy PII w wierszach {@code audit_log} PODMIOTU przy
 * anonimizacji klienta (RODO Art. 17, decyzja D10: wiersz zostaje, tylko wartości kluczy PII są
 * maskowane).
 *
 * <p><strong>Wiersze audit_log są seedowane BEZPOŚREDNIO SQL-em</strong> (nie przez
 * {@code AuditAspect}/Javę -- to jest test warstwy SQL, BE-142 Zakres p.1 realizuje
 * backend-dev-expert osobno) -- kształt JSON odtwarza DOKŁADNIE to, co potwierdzono zapytaniem na
 * żywych danych dev (grep w notatce wykonania BE-142/V098, {@code TASKS-DATABASE.md}), w tym
 * odkryty błąd danych: {@code entity_id} dla akcji {@code CUSTOMER_CREATED}/{@code CONTACT_CREATED}
 * niesie {@code tenant_id}, NIE id własnej encji -- {@code mask_audit_log_pii} musi je mimo to
 * dopasować przez treść JSON ({@code new_value/old_value ->> 'customerId'/'contactId'}).
 *
 * <p>Nie używamy mocków -- błędów SQL/RLS nie złapie {@code EntityManager}/Mockito.
 */
@Testcontainers
@DisplayName("mask_audit_log_pii -- maskowanie PII w audit_log podmiotu (V098, BE-142 Zakres p.2, D10)")
class MaskAuditLogPiiTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "cc_post_be142";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private static final UUID CUSTOMER_MAIN = UUID.randomUUID();
    private static final String MAIN_PHONE = "+48500700100";
    private static final String MAIN_EMAIL = "test.be142@example.com";

    private static final UUID CONTACT_CREATED_BUGGY = UUID.randomUUID();
    private static final UUID CONTACT_DISPOSITION = UUID.randomUUID();
    private static final UUID CONTACT_RECORDING_UNRELATED = UUID.randomUUID();

    private static final UUID CUSTOMER_OTHER_SAME_TENANT = UUID.randomUUID();
    private static final UUID CUSTOMER_FOREIGN_TENANT = UUID.randomUUID();

    // ---- fixture scenariusz 7: wywolanie mask_audit_log_pii W IZOLACJI (bez anonymize_customer) ---

    private static final UUID CUSTOMER_STANDALONE = UUID.randomUUID();
    private static final UUID CONTACT_STANDALONE = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        flyway(DB).load().migrate();
        try (Connection c = connect(DB)) {
            seedFixture(c);
        }
    }

    // =========================================================================================
    // 1) CUSTOMER: CUSTOMER_CREATED (entity_id BLEDNY = tenant_id) i CUSTOMER_UPDATED (entity_id
    //    POPRAWNY) -- oba zamaskowane; pola nie-PII (customerId, source, dispositionCode-analog)
    //    nietkniete; wiersz NIE usuniety.
    // =========================================================================================

    @Test
    @DisplayName("CUSTOMER: CUSTOMER_CREATED (entity_id bledny = tenant_id) dopasowany przez JSON customerId, CUSTOMER_UPDATED (entity_id poprawny) dopasowany bezposrednio -- oba zamaskowane, pola nie-PII i wiersze nietkniete")
    void realAnonymize_masksCustomerRows_bothCorrectAndBuggyEntityId() throws Exception {
        inRolledBackTx(DB, c -> {
            long before = countAuditLogRows(c, CUSTOMER_MAIN, TENANT_A);

            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            long after = countAuditLogRows(c, CUSTOMER_MAIN, TENANT_A);
            assertThat(after).as("wiersze audit_log NIE sa usuwane, tylko maskowane (D10)").isEqualTo(before);

            JsonNode created = jsonColumn(c,
                    "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_CREATED' AND new_value->>'customerId' = ?",
                    CUSTOMER_MAIN.toString());
            assertThat(created.get("firstName").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("lastName").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("phone").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("email").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("customFields").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("gdprConsent").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("externalId").asText()).isEqualTo("[MASKED]");
            // pola NIE-PII zostaja nietkniete
            assertThat(created.get("customerId").asText()).isEqualTo(CUSTOMER_MAIN.toString());
            assertThat(created.get("tenantId").asText()).isEqualTo(TENANT_A.toString());
            assertThat(created.get("source").asText()).isEqualTo("MANUAL");
            assertThat(created.get("deleted").asBoolean()).isFalse();

            JsonNode updatedOld = jsonColumn(c, "SELECT old_value::text FROM audit_log WHERE action = 'CUSTOMER_UPDATED' AND entity_id = ?", CUSTOMER_MAIN);
            JsonNode updatedNew = jsonColumn(c, "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_UPDATED' AND entity_id = ?", CUSTOMER_MAIN);
            assertThat(updatedOld.get("firstName").asText()).isEqualTo("[MASKED]");
            assertThat(updatedNew.get("firstName").asText()).isEqualTo("[MASKED]");
            assertThat(updatedNew.get("externalId").asText()).isEqualTo("[MASKED]");
            assertThat(updatedNew.get("source").asText()).isEqualTo("MANUAL");
            assertThat(updatedNew.get("customerId").asText()).isEqualTo(CUSTOMER_MAIN.toString());
            return null;
        });
    }

    // =========================================================================================
    // 2) CONTACT: CONTACT_CREATED (entity_id BLEDNY = tenant_id) i CONTACT_DISPOSITION_SET
    //    (entity_id POPRAWNY) -- oba zamaskowane przez JSON contactId; dispositionCode/status/
    //    durationSeconds nietkniete.
    // =========================================================================================

    @Test
    @DisplayName("CONTACT: CONTACT_CREATED (entity_id bledny = tenant_id) dopasowany przez JSON contactId, CONTACT_DISPOSITION_SET (entity_id poprawny) -- oba zamaskowane (remoteAddress, channelMetadata, notes), dispositionCode/status/durationSeconds nietkniete")
    void realAnonymize_masksContactRows_bothCorrectAndBuggyEntityId() throws Exception {
        inRolledBackTx(DB, c -> {
            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            JsonNode created = jsonColumn(c,
                    "SELECT new_value::text FROM audit_log WHERE action = 'CONTACT_CREATED' AND new_value->>'contactId' = ?",
                    CONTACT_CREATED_BUGGY.toString());
            assertThat(created.get("remoteAddress").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("channelMetadata").asText()).isEqualTo("[MASKED]");
            assertThat(created.get("contactId").asText()).isEqualTo(CONTACT_CREATED_BUGGY.toString());
            assertThat(created.get("customerId").asText()).isEqualTo(CUSTOMER_MAIN.toString());
            assertThat(created.get("status").asText()).isEqualTo("QUEUED");
            assertThat(created.get("channel").asText()).isEqualTo("PHONE");

            JsonNode dispositionOld = jsonColumn(c, "SELECT old_value::text FROM audit_log WHERE action = 'CONTACT_DISPOSITION_SET' AND entity_id = ?", CONTACT_DISPOSITION);
            JsonNode dispositionNew = jsonColumn(c, "SELECT new_value::text FROM audit_log WHERE action = 'CONTACT_DISPOSITION_SET' AND entity_id = ?", CONTACT_DISPOSITION);
            assertThat(dispositionOld.get("remoteAddress").asText()).isEqualTo("[MASKED]");
            assertThat(dispositionNew.get("remoteAddress").asText()).isEqualTo("[MASKED]");
            assertThat(dispositionNew.get("notes").asText()).isEqualTo("[MASKED]");
            assertThat(dispositionNew.get("channelMetadata").asText()).isEqualTo("[MASKED]");
            // pola operacyjne NIE-PII zostaja nietkniete
            assertThat(dispositionNew.get("dispositionCode").asText()).isEqualTo("OTHER");
            assertThat(dispositionNew.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(dispositionNew.get("durationSeconds").asInt()).isEqualTo(42);
            assertThat(dispositionNew.get("contactId").asText()).isEqualTo(CONTACT_DISPOSITION.toString());
            return null;
        });
    }

    // =========================================================================================
    // 3) RECORDING_URL_REQUESTED -- POZA ZAKRESEM (BE-142 Zakres p.2 wylacza presignedUrl/tej
    //    akcji) -- brak klucza 'contactId' w JSON, entity_id nie pasuje do zadnego kontaktu
    //    podmiotu -- wiersz calkowicie nietkniety mimo entity_type = CONTACT.
    // =========================================================================================

    @Test
    @DisplayName("RECORDING_URL_REQUESTED (entity_type=CONTACT, ale POZA ZAKRESEM BE-142 p.2) -- brak klucza contactId w JSON i entity_id niepowiazany -- wiersz calkowicie nietkniety")
    void realAnonymize_recordingUrlRequested_outOfScope_untouched() throws Exception {
        inRolledBackTx(DB, c -> {
            String before = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'RECORDING_URL_REQUESTED' AND entity_id = ?", CONTACT_RECORDING_UNRELATED);

            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            String after = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'RECORDING_URL_REQUESTED' AND entity_id = ?", CONTACT_RECORDING_UNRELATED);
            assertThat(after).as("RECORDING_URL_REQUESTED nie ma klucza contactId ani entity_id powiazanego z podmiotem -- musi byc bit-identyczny").isEqualTo(before);
            assertThat(after).contains("presignedUrl");
            return null;
        });
    }

    // =========================================================================================
    // 4) Izolacja: inny klient tego samego tenanta i klient innego tenanta -- audit_log nietkniety
    // =========================================================================================

    @Test
    @DisplayName("izolacja: audit_log innego klienta tego samego tenanta i klienta innego tenanta pozostaje NIEZAMASKOWANY po anonimizacji CUSTOMER_MAIN")
    void realAnonymize_isolation_otherCustomerAndForeignTenant_untouched() throws Exception {
        inRolledBackTx(DB, c -> {
            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            JsonNode other = jsonColumn(c, "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_CREATED' AND new_value->>'customerId' = ?", CUSTOMER_OTHER_SAME_TENANT.toString());
            assertThat(other.get("firstName").asText()).isEqualTo("Inny");

            JsonNode foreign = jsonColumn(c, "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_CREATED' AND new_value->>'customerId' = ?", CUSTOMER_FOREIGN_TENANT.toString());
            assertThat(foreign.get("firstName").asText()).isEqualTo("Obcy");
            return null;
        });
    }

    // =========================================================================================
    // 5) p_dry_run=TRUE: audit_log calkowicie nietkniety (bit-identyczny), counts.audit_log > 0
    //    (przewidywanie), rowne liczbie faktycznie zamaskowanych wierszy w trybie rzeczywistym
    // =========================================================================================

    @Test
    @DisplayName("p_dry_run=TRUE: audit_log calkowicie nietkniety, counts.audit_log w podgladzie rowny faktycznej liczbie zamaskowanych wierszy w trybie rzeczywistym")
    void dryRun_doesNotMutateAuditLog_previewCountMatchesRealRun() throws Exception {
        inRolledBackTx(DB, c -> {
            String beforeCreated = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_CREATED' AND new_value->>'customerId' = ?", CUSTOMER_MAIN.toString());

            JsonNode preview = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            int previewAuditLogCount = preview.get("counts").get("audit_log").asInt();
            assertThat(previewAuditLogCount).isGreaterThan(0);

            String afterPreview = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'CUSTOMER_CREATED' AND new_value->>'customerId' = ?", CUSTOMER_MAIN.toString());
            assertThat(afterPreview).as("dry_run nie moze zmienic zadnego wiersza audit_log").isEqualTo(beforeCreated);

            JsonNode real = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(real.get("counts").get("audit_log").asInt()).isEqualTo(previewAuditLogCount);
            return null;
        });
    }

    // =========================================================================================
    // 6) Idempotencja: drugie wywolanie rzeczywiste = 0 zamaskowanych wierszy (juz zamaskowane)
    // =========================================================================================

    @Test
    @DisplayName("idempotencja: drugie wywolanie rzeczywiste anonymize_customer zwraca counts.audit_log = 0 (wiersze juz zamaskowane, maskowanie niczego wiecej nie zmienia)")
    void realAnonymize_secondCall_idempotent_zeroAuditLogCount() throws Exception {
        inRolledBackTx(DB, c -> {
            JsonNode first = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(first.get("counts").get("audit_log").asInt()).isGreaterThan(0);

            JsonNode second = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(second.get("counts").get("audit_log").asInt()).isZero();
            return null;
        });
    }

    // =========================================================================================
    // 7) mask_audit_log_pii wolana BEZPOSREDNIO, w izolacji od anonymize_customer -- dowod na
    //    latwa testowalnosc osobnej funkcji (BE-142 Zakres p.2, uzasadnienie decyzji).
    // =========================================================================================

    @Test
    @DisplayName("mask_audit_log_pii wolana BEZPOSREDNIO (bez anonymize_customer) z jawnym p_contact_ids -- maskuje poprawnie, dowod testowalnosci w izolacji")
    void maskAuditLogPii_calledDirectly_withoutFullAnonymizeFlow() throws Exception {
        inRolledBackTx(DB, c -> {
            String beforeMasking = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'CONTACT_CREATED' AND new_value->>'contactId' = ?", CONTACT_STANDALONE.toString());
            assertThat(beforeMasking).doesNotContain("[MASKED]");

            int maskedCount = update(c, "SELECT mask_audit_log_pii(?, ?, ARRAY[?]::uuid[], FALSE)", CUSTOMER_STANDALONE, TENANT_A, CONTACT_STANDALONE);
            assertThat(maskedCount).isGreaterThanOrEqualTo(0); // funkcja zwraca INT (SELECT, nie UPDATE) -- ROW_COUNT poprzez update() to 1 (jeden wiersz wyniku SELECT), sprawdzamy realny efekt ponizej

            JsonNode masked = jsonColumn(c, "SELECT new_value::text FROM audit_log WHERE action = 'CONTACT_CREATED' AND new_value->>'contactId' = ?", CONTACT_STANDALONE.toString());
            assertThat(masked.get("remoteAddress").asText()).isEqualTo("[MASKED]");
            assertThat(masked.get("customerId").asText()).isEqualTo(CUSTOMER_STANDALONE.toString());

            // customer sam nie byl wolany anonymize_customer -- customer.phone/email nietkniete
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_STANDALONE)).isEqualTo("false");
            return null;
        });
    }

    @Test
    @DisplayName("mask_audit_log_pii: p_dry_run = SQL NULL jest ODRZUCANE (wzorzec DB062-01), zero zmian")
    void maskAuditLogPii_dryRunNull_isRejected() throws Exception {
        inRolledBackTx(DB, c -> {
            SQLException error = failureOfWithSavepointRecovery(c,
                    "SELECT mask_audit_log_pii(?, ?, ARRAY[]::uuid[], ?)", CUSTOMER_STANDALONE, TENANT_A, null);
            assertThat((Throwable) error).as("p_dry_run = NULL musi byc odrzucone").isNotNull();
            assertThat(error.getMessage()).contains("p_dry_run");

            String unchanged = scalar(c, "SELECT new_value::text FROM audit_log WHERE action = 'CONTACT_CREATED' AND new_value->>'contactId' = ?", CONTACT_STANDALONE.toString());
            assertThat(unchanged).doesNotContain("[MASKED]");
            return null;
        });
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private static void seedFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, 'Tenant A - BE-142 IT'), (?, 'Tenant B - BE-142 IT')", TENANT_A, TENANT_B);

        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source, external_id, custom_fields, gdpr_consent) "
                        + "VALUES (?, ?, 'Jan', 'Kowalski', ?::jsonb, ?::jsonb, 'MANUAL', 'ERP-142', '{\"vip\":true}'::jsonb, '{\"consent_given\":true}'::jsonb)",
                CUSTOMER_MAIN, TENANT_A, "[\"" + MAIN_PHONE + "\"]", "[\"" + MAIN_EMAIL + "\"]");
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now())",
                CONTACT_CREATED_BUGGY, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE);
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now())",
                CONTACT_DISPOSITION, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE);

        // --- audit_log: CUSTOMER_CREATED z BLEDNYM entity_id (= tenant_id, odtwarza odkryty blad danych live)
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CUSTOMER_CREATED', 'CUSTOMER', ?, NULL, ?::jsonb, now())",
                TENANT_A, TENANT_A,
                "{\"email\":[\"" + MAIN_EMAIL + "\"],\"phone\":[\"" + MAIN_PHONE + "\"],\"source\":\"MANUAL\",\"deleted\":false,"
                        + "\"lastName\":\"Kowalski\",\"tenantId\":\"" + TENANT_A + "\",\"firstName\":\"Jan\","
                        + "\"customerId\":\"" + CUSTOMER_MAIN + "\",\"externalId\":\"ERP-142\","
                        + "\"gdprConsent\":{\"consent_given\":true},\"customFields\":{\"vip\":true}}");

        // --- audit_log: CUSTOMER_UPDATED z POPRAWNYM entity_id
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CUSTOMER_UPDATED', 'CUSTOMER', ?, ?::jsonb, ?::jsonb, now())",
                TENANT_A, CUSTOMER_MAIN,
                "{\"firstName\":\"Jan\",\"lastName\":\"Kowalski\",\"source\":\"MANUAL\",\"customerId\":\"" + CUSTOMER_MAIN + "\"}",
                "{\"firstName\":\"Janusz\",\"lastName\":\"Kowalski\",\"source\":\"MANUAL\",\"customerId\":\"" + CUSTOMER_MAIN + "\",\"externalId\":\"ERP-142-v2\"}");

        // --- audit_log: CONTACT_CREATED z BLEDNYM entity_id (= tenant_id)
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CONTACT_CREATED', 'CONTACT', ?, NULL, ?::jsonb, now())",
                TENANT_A, TENANT_A,
                "{\"status\":\"QUEUED\",\"channel\":\"PHONE\",\"tenantId\":\"" + TENANT_A + "\",\"contactId\":\"" + CONTACT_CREATED_BUGGY + "\","
                        + "\"customerId\":\"" + CUSTOMER_MAIN + "\",\"remoteAddress\":\"" + MAIN_PHONE + "\","
                        + "\"channelMetadata\":{\"sip_call_id\":\"abc\"}}");

        // --- audit_log: CONTACT_DISPOSITION_SET z POPRAWNYM entity_id
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CONTACT_DISPOSITION_SET', 'CONTACT', ?, ?::jsonb, ?::jsonb, now())",
                TENANT_A, CONTACT_DISPOSITION,
                "{\"status\":\"IN_PROGRESS\",\"contactId\":\"" + CONTACT_DISPOSITION + "\",\"customerId\":\"" + CUSTOMER_MAIN + "\","
                        + "\"remoteAddress\":\"" + MAIN_PHONE + "\",\"channelMetadata\":{\"sip_call_id\":\"xyz\"}}",
                "{\"status\":\"COMPLETED\",\"dispositionCode\":\"OTHER\",\"durationSeconds\":42,\"contactId\":\"" + CONTACT_DISPOSITION + "\","
                        + "\"customerId\":\"" + CUSTOMER_MAIN + "\",\"remoteAddress\":\"" + MAIN_PHONE + "\","
                        + "\"channelMetadata\":{\"sip_call_id\":\"xyz\"},\"notes\":\"agent notatka\"}");

        // --- audit_log: RECORDING_URL_REQUESTED (entity_type=CONTACT, POZA ZAKRESEM) -- entity_id
        //     NIEPOWIAZANY (losowy, tak jak na zywych danych -- 0/100 dopasowan do contactId/tenantId),
        //     brak klucza contactId w JSON.
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'RECORDING_URL_REQUESTED', 'CONTACT', ?, NULL, ?::jsonb, now())",
                TENANT_A, CONTACT_RECORDING_UNRELATED,
                "{\"fileName\":\"rec.mp3\",\"expiresAt\":\"2026-09-30T12:00:00Z\",\"contentType\":\"audio/mpeg\","
                        + "\"presignedUrl\":\"http://minio:9000/rec.mp3?X-Amz-Signature=abc\",\"durationSeconds\":22}");

        // --- izolacja: inny klient tego samego tenanta
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Inny', 'Klient', '[]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_OTHER_SAME_TENANT, TENANT_A);
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CUSTOMER_CREATED', 'CUSTOMER', ?, NULL, ?::jsonb, now())",
                TENANT_A, CUSTOMER_OTHER_SAME_TENANT,
                "{\"firstName\":\"Inny\",\"lastName\":\"Klient\",\"source\":\"MANUAL\",\"customerId\":\"" + CUSTOMER_OTHER_SAME_TENANT + "\"}");

        // --- izolacja: klient innego tenanta
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Obcy', 'Klient', '[]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_FOREIGN_TENANT, TENANT_B);
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CUSTOMER_CREATED', 'CUSTOMER', ?, NULL, ?::jsonb, now())",
                TENANT_B, CUSTOMER_FOREIGN_TENANT,
                "{\"firstName\":\"Obcy\",\"lastName\":\"Klient\",\"source\":\"MANUAL\",\"customerId\":\"" + CUSTOMER_FOREIGN_TENANT + "\"}");

        // --- scenariusz 7/8: wywolanie mask_audit_log_pii W IZOLACJI, bez anonymize_customer
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Solo', 'Test', '[]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_STANDALONE, TENANT_A);
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', '+48000999888', now())",
                CONTACT_STANDALONE, TENANT_A, CUSTOMER_STANDALONE);
        update(c, "INSERT INTO audit_log (log_id, tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at) "
                        + "VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'CONTACT_CREATED', 'CONTACT', ?, NULL, ?::jsonb, now())",
                TENANT_A, TENANT_A,
                "{\"status\":\"QUEUED\",\"contactId\":\"" + CONTACT_STANDALONE + "\",\"customerId\":\"" + CUSTOMER_STANDALONE + "\","
                        + "\"remoteAddress\":\"+48000999888\"}");
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static long countAuditLogRows(Connection c, UUID customerId, UUID tenantId) throws SQLException {
        return Long.parseLong(scalar(c,
                "SELECT count(*)::text FROM audit_log WHERE tenant_id = ? AND ("
                        + "(entity_type = 'CUSTOMER' AND new_value->>'customerId' = ?) "
                        + "OR (entity_type = 'CONTACT' AND new_value->>'customerId' = ?))",
                tenantId, customerId.toString(), customerId.toString()));
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
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // spodziewane, gdy transakcja jest juz w stanie aborted po nieobsluzonym bledzie
                }
            }
        }
    }

    /** Zwraca wyjatek SQL rzucony przez polecenie (albo null gdy sukces) -- przez SAVEPOINT, zeby pozwolic kontynuowac zapytania w TEJ SAMEJ transakcji po bledzie. */
    private static SQLException failureOfWithSavepointRecovery(Connection c, String sql, Object... params) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
            c.releaseSavepoint(sp);
            return null;
        } catch (SQLException e) {
            c.rollback(sp);
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

    /** Dla zapytan postaci "SELECT &lt;pojedyncze_wyrazenie&gt;" (np. wywolanie funkcji) -- owija w rzutowanie na tekst. */
    private static JsonNode scalarJson(Connection c, String sql, Object... params) throws SQLException {
        String text = scalar(c, "SELECT (" + sql.substring("SELECT ".length()) + ")::text", params);
        return parseJson(text);
    }

    /** Dla pelnych zapytan "SELECT kolumna FROM tabela WHERE ..." zwracajacych kolumne JSONB -- BEZ przepisywania SQL. */
    private static JsonNode jsonColumn(Connection c, String sql, Object... params) throws SQLException {
        return parseJson(scalar(c, sql, params));
    }

    private static JsonNode parseJson(String text) {
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

    private static Connection connect(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), USER, PASSWORD);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String db) {
        return Flyway.configure()
                .dataSource(jdbcUrl(db), USER, PASSWORD)
                .locations("classpath:db/migration");
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db;
    }
}
