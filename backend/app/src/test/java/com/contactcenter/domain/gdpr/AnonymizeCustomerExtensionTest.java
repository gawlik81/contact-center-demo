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
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla DB-062 --
 * rozszerzenia {@code anonymize_customer} (RODO Art. 17) migracją V096 (rozpoznawaną przez pełny
 * łańcuch Flyway -- najnowsza wersja na classpath).
 *
 * <p><strong>Dowód na STAREJ funkcji (V013, sprzed DB-079) jest już pokryty</strong> przez
 * {@link com.contactcenter.domain.contact.ContactRefIntegrityNarrowingTest#v093_anonymizeCustomer_failsBecauseOfTrigger()}
 * -- nie duplikujemy go tutaj (wskazówka z notatki wykonania DB-079 w TASKS-DATABASE.md). Ta klasa
 * testuje wyłącznie NAJNOWSZY łańcuch (V096 zastosowana), na bogatym fixture'cie budowanym raz w
 * {@link #migrateAndSeed()}; każdy test działa we własnej transakcji cofanej na końcu (stan bazy
 * między testami się nie zmienia).
 *
 * <p>Nie używamy mocków -- błędów SQL/triggerów/RLS nie złapie {@code EntityManager}/Mockito.
 */
@Testcontainers
@DisplayName("anonymize_customer -- RODO Art. 17 rozszerzony (V096, DB-062)")
class AnonymizeCustomerExtensionTest {

    static {
        TestcontainersSupport.ensureDockerApiVersion();
    }

    private static final String DB = "cc_post_db062";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- fixture: tenanty ----------------------------------------------------------------------

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    // ---- fixture: scenariusz 1 -- pelny zestaw danych klienta -----------------------------------

    private static final UUID CUSTOMER_MAIN = UUID.randomUUID();
    private static final String MAIN_PHONE = "+48500100200";
    private static final String MAIN_EMAIL = "jan.testowy@example.com";

    private static final UUID CONTACT_MP3 = UUID.randomUUID();
    private static final UUID CONTACT_EML = UUID.randomUUID();
    private static final String RECORDING_MP3 = "1111/aaaa/rec.mp3";
    private static final String RECORDING_EML = "1111/bbbb/thread.eml";

    private static final UUID CAMPAIGN_MAIN_1 = UUID.randomUUID();
    private static final UUID CAMPAIGN_MAIN_2 = UUID.randomUUID();
    /** CC_SAME_CAMPAIGN_1/2: DWA rekordy TEGO SAMEGO klienta w JEDNEJ kampanii -- test indeksu częściowego. */
    private static final UUID CC_SAME_CAMPAIGN_1 = UUID.randomUUID();
    private static final UUID CC_SAME_CAMPAIGN_2 = UUID.randomUUID();
    private static final UUID CC_OTHER_CAMPAIGN = UUID.randomUUID();
    private static final UUID CCA_1 = UUID.randomUUID();
    private static final UUID CCA_2 = UUID.randomUUID();

    private static final UUID CALLBACK_PENDING = UUID.randomUUID();
    private static final UUID CALLBACK_PROCESSING = UUID.randomUUID();

    private static final UUID EMAIL_INBOUND = UUID.randomUUID();
    private static final UUID EMAIL_OUTBOUND_PENDING = UUID.randomUUID();
    private static final UUID EMAIL_PLAIN = UUID.randomUUID();

    private static final UUID SOCIAL_1 = UUID.randomUUID();
    private static final UUID SOCIAL_2 = UUID.randomUUID();

    // ---- fixture: scenariusz 2 -- izolacja -------------------------------------------------------

    private static final UUID CUSTOMER_OTHER_SAME_TENANT = UUID.randomUUID();
    private static final UUID CONTACT_OTHER = UUID.randomUUID();
    private static final UUID CALLBACK_OTHER = UUID.randomUUID();
    private static final UUID CAMPAIGN_OTHER = UUID.randomUUID();
    private static final UUID CC_OTHER_CUSTOMER = UUID.randomUUID();
    private static final UUID EMAIL_UNRELATED_ORPHAN = UUID.randomUUID();

    private static final UUID CUSTOMER_FOREIGN_TENANT = UUID.randomUUID();

    // ---- fixture: scenariusz 3 -- regula mostu (identifier-only + campaign_contact_record_id) ---

    private static final UUID CUSTOMER_BRIDGE = UUID.randomUUID();
    private static final String BRIDGE_PHONE = "+48700800900";
    /** Ten sam numer co {@link #BRIDGE_PHONE}, z separatorami -- test normalizacji fn_normalize_phone. */
    private static final String BRIDGE_PHONE_WITH_SEPARATORS = "+48 700-800 (900)";
    private static final UUID CAMPAIGN_BRIDGE = UUID.randomUUID();
    /** Rekord kampanii z INNYM telefonem niz klient -- powiazany WYLACZNIE przez most. */
    private static final UUID CC_BRIDGE_RECORD = UUID.randomUUID();
    private static final UUID CONTACT_BRIDGE_IDENTIFIER = UUID.randomUUID();
    private static final UUID CALLBACK_BRIDGE = UUID.randomUUID();

    // ---- fixture: scenariusz 4 -- kolizja record_id miedzy kampaniami ---------------------------

    private static final UUID CUSTOMER_COLLISION = UUID.randomUUID();
    private static final UUID CAMPAIGN_COLLISION_A = UUID.randomUUID();
    private static final UUID CAMPAIGN_COLLISION_B = UUID.randomUUID();
    /** Ten sam record_id (jawnie nadany, NIE default) w DWOCH roznych kampaniach tego samego tenanta. */
    private static final UUID COLLIDING_RECORD_ID = UUID.randomUUID();

    // ---- fixture: scenariusz 6 -- dosanityzowanie klienta (stara sciezka Javy) ------------------

    private static final UUID CUSTOMER_JAVA_ONLY = UUID.randomUUID();
    private static final String JAVA_ONLY_PHONE = "+48611622633";
    private static final UUID CONTACT_JAVA_ONLY = UUID.randomUUID();
    private static final UUID CALLBACK_JAVA_ONLY = UUID.randomUUID();
    private static final UUID CAMPAIGN_JAVA_ONLY = UUID.randomUUID();
    private static final UUID CC_JAVA_ONLY = UUID.randomUUID();
    private static final UUID EMAIL_JAVA_ONLY = UUID.randomUUID();
    private static final UUID SOCIAL_JAVA_ONLY = UUID.randomUUID();

    // ---- fixture: scenariusz 8 -- wspolny numer dwoch klientow ------------------------------------

    private static final UUID CUSTOMER_SHARED_A = UUID.randomUUID();
    private static final UUID CUSTOMER_SHARED_B = UUID.randomUUID();
    private static final String SHARED_PHONE = "+48222333444";
    private static final UUID CONTACT_SHARED_ORPHAN = UUID.randomUUID();
    private static final UUID CONTACT_SHARED_B_OWN = UUID.randomUUID();

    // ---- fixture: scenariusz RLS -------------------------------------------------------------

    private static final UUID CUSTOMER_RLS = UUID.randomUUID();
    private static final String RLS_PHONE = "+48911922933";
    private static final UUID CONTACT_RLS = UUID.randomUUID();
    private static final UUID CALLBACK_RLS = UUID.randomUUID();
    private static final UUID CAMPAIGN_RLS = UUID.randomUUID();
    private static final UUID CC_RLS = UUID.randomUUID();
    private static final UUID CCA_RLS = UUID.randomUUID();
    private static final UUID EMAIL_RLS = UUID.randomUUID();
    private static final UUID SOCIAL_RLS = UUID.randomUUID();
    private static final UUID TRANSCRIPTION_RLS = UUID.randomUUID();
    private static final UUID AI_SUMMARY_RLS = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        flyway(DB).load().migrate();
        try (Connection c = connect(DB)) {
            seedFixture(c);
        }
    }

    // =========================================================================================
    // 1) Pelny zestaw danych -- kazda kolumna PII zanonimizowana, s3_keys kompletne, cc/bcc,
    //    stany operacyjne, indeks czesciowy nie peka
    // =========================================================================================

    @Test
    @DisplayName("pelny zestaw danych: kazda kolumna PII zanonimizowana, s3_keys komplet (mp3+eml+INBOUND+OUTBOUND), cc/bcc, statusy operacyjne, indeks czesciowy przezywa 2 NULL w tej samej kampanii")
    void fullDataSet_allPiiAnonymized_s3KeysComplete() throws Exception {
        inRolledBackTx(DB, c -> {
            JsonNode result = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            assertThat(result.get("dry_run").asBoolean()).isFalse();
            JsonNode counts = result.get("counts");
            assertThat(counts.get("customer").asInt()).isEqualTo(1);
            assertThat(counts.get("contact").asInt()).isEqualTo(2);
            assertThat(counts.get("scheduled_callback").asInt()).isEqualTo(2);
            assertThat(counts.get("campaign_contact").asInt()).isEqualTo(3);
            assertThat(counts.get("campaign_contact_archive").asInt()).isEqualTo(2);
            assertThat(counts.get("contact_transcription").asInt()).isEqualTo(1);
            assertThat(counts.get("contact_ai_summary").asInt()).isEqualTo(1);
            assertThat(counts.get("email_message").asInt()).isEqualTo(3);
            assertThat(counts.get("social_message").asInt()).isEqualTo(2);
            assertThat(counts.get("contacts_dw").asInt()).isEqualTo(1);

            List<String> s3Keys = jsonArrayToStrings(result.get("s3_keys"));
            assertThat(s3Keys).containsExactlyInAnyOrder(
                    RECORDING_MP3, RECORDING_EML,
                    "email-attachments/" + TENANT_A + "/msg-inbound/zal.pdf",
                    "email-attachments/" + TENANT_A + "/pending/pending-uuid/zal2.pdf");

            // --- contact
            assertThat(scalar(c, "SELECT remote_address IS NULL FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("true");
            assertThat(scalar(c, "SELECT notes IS NULL FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("true");
            assertThat(scalar(c, "SELECT recording_url IS NULL FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("true");
            assertThat(scalar(c, "SELECT channel_metadata::text FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("{}");
            assertThat(scalar(c, "SELECT recording_url IS NULL FROM contact WHERE contact_id = ?", CONTACT_EML)).isEqualTo("true");

            // --- scheduled_callback: PENDING i PROCESSING -> CANCELLED
            for (UUID callbackId : List.of(CALLBACK_PENDING, CALLBACK_PROCESSING)) {
                assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", callbackId)).isEqualTo("ANONYMIZED");
                assertThat(scalar(c, "SELECT first_name FROM scheduled_callback WHERE callback_id = ?", callbackId)).isEqualTo("ANONYMIZED");
                assertThat(scalar(c, "SELECT last_name FROM scheduled_callback WHERE callback_id = ?", callbackId)).isEqualTo("ANONYMIZED");
                assertThat(scalar(c, "SELECT notes IS NULL FROM scheduled_callback WHERE callback_id = ?", callbackId)).isEqualTo("true");
                assertThat(scalar(c, "SELECT status FROM scheduled_callback WHERE callback_id = ?", callbackId)).isEqualTo("CANCELLED");
            }

            // --- campaign_contact: phone NULL (nie placeholder!), status operacyjny -> SKIPPED, next_attempt_at NULL
            for (UUID recordId : List.of(CC_SAME_CAMPAIGN_1, CC_SAME_CAMPAIGN_2, CC_OTHER_CAMPAIGN)) {
                assertThat(scalar(c, "SELECT phone IS NULL FROM campaign_contact WHERE record_id = ?", recordId)).as("phone NULL dla %s", recordId).isEqualTo("true");
                assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("ANONYMIZED");
                assertThat(scalar(c, "SELECT email IS NULL FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("true");
                assertThat(scalar(c, "SELECT custom_fields::text FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("{}");
                assertThat(scalar(c, "SELECT status FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("SKIPPED");
                assertThat(scalar(c, "SELECT next_attempt_at IS NULL FROM campaign_contact WHERE record_id = ?", recordId)).isEqualTo("true");
            }
            // Indeks czesciowy idx_campaign_contact_phone_unique(campaign_id, phone) WHERE phone IS NOT NULL: 2 NULL w tej samej kampanii nie kolidują.
            assertThat(scalar(c, "SELECT count(*)::text FROM campaign_contact WHERE campaign_id = ? AND phone IS NULL", CAMPAIGN_MAIN_1))
                    .isEqualTo("2");

            // --- campaign_contact_archive: bez zmiany statusu, PII wyczyszczone
            for (UUID recordId : List.of(CCA_1, CCA_2)) {
                assertThat(scalar(c, "SELECT phone IS NULL FROM campaign_contact_archive WHERE record_id = ?", recordId)).isEqualTo("true");
                assertThat(scalar(c, "SELECT first_name FROM campaign_contact_archive WHERE record_id = ?", recordId)).isEqualTo("ANONYMIZED");
            }

            // --- transkrypcja i podsumowanie AI -- USUNIETE (DELETE, D3=A)
            assertThat(scalar(c, "SELECT count(*)::text FROM contact_transcription WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("0");
            assertThat(scalar(c, "SELECT count(*)::text FROM contact_ai_summary WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("0");

            // --- email: cc/bcc (pomijane przez V013), attachments wyzerowane
            assertThat(scalar(c, "SELECT from_address FROM email_message WHERE message_id = ?", EMAIL_INBOUND)).isEqualTo("anonymized@example.com");
            assertThat(scalar(c, "SELECT cc_address IS NULL FROM email_message WHERE message_id = ?", EMAIL_INBOUND)).isEqualTo("true");
            assertThat(scalar(c, "SELECT bcc_address IS NULL FROM email_message WHERE message_id = ?", EMAIL_INBOUND)).isEqualTo("true");
            assertThat(scalar(c, "SELECT attachments::text FROM email_message WHERE message_id = ?", EMAIL_INBOUND)).isEqualTo("[]");
            assertThat(scalar(c, "SELECT attachments::text FROM email_message WHERE message_id = ?", EMAIL_OUTBOUND_PENDING)).isEqualTo("[]");

            // --- social
            assertThat(scalar(c, "SELECT sender_external_id FROM social_message WHERE message_id = ?", SOCIAL_1)).isEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT attachments::text FROM social_message WHERE message_id = ?", SOCIAL_1)).isEqualTo("[]");

            // --- contacts_dw
            assertThat(scalar(c, "SELECT remote_address IS NULL FROM contacts_dw WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("true");

            // --- customer
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo("true");
            assertThat(scalar(c, "SELECT external_id IS NULL FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo("true");
            assertThat(scalar(c, "SELECT custom_fields::text FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo("{}");
            assertThat(scalar(c, "SELECT gdpr_consent::text FROM customer WHERE customer_id = ?", CUSTOMER_MAIN))
                    .isEqualTo("{\"anonymized\": true, \"consent_given\": false, \"marketing_consent\": false}");

            // --- audit_log: wpis CUSTOMER_ANONYMIZED bez PII w old_value
            assertThat(scalar(c, "SELECT count(*)::text FROM audit_log WHERE entity_id = ? AND action = 'CUSTOMER_ANONYMIZED'", CUSTOMER_MAIN))
                    .isEqualTo("1");
            String oldValue = scalar(c, "SELECT old_value::text FROM audit_log WHERE entity_id = ? AND action = 'CUSTOMER_ANONYMIZED'", CUSTOMER_MAIN);
            assertThat(oldValue).doesNotContain(MAIN_PHONE).doesNotContain(MAIN_EMAIL).doesNotContain("Jan");

            // --- stany operacyjne: zapytania warstwy aplikacji NIE zwracaja zanonimizowanych rekordow
            // (replika predykatow ProgressiveDialerServiceImpl#fetchNextPendingContact i
            // ScheduledCallbackRepository#findPendingByTenantId/countPendingByTenantId)
            assertThat(scalar(c, """
                    SELECT count(*)::text FROM campaign_contact
                    WHERE campaign_id = ? AND tenant_id = ? AND status IN ('PENDING', 'NO_ANSWER')
                    """, CAMPAIGN_MAIN_1, TENANT_A)).as("dialer: brak zanonimizowanych rekordow kampanii wsrod PENDING/NO_ANSWER").isEqualTo("0");
            assertThat(scalar(c, "SELECT count(*)::text FROM scheduled_callback WHERE tenant_id = ? AND status = 'PENDING' AND callback_id IN (?, ?)",
                    TENANT_A, CALLBACK_PENDING, CALLBACK_PROCESSING))
                    .as("ScheduledCallbackExecutor/Repository: brak zanonimizowanych callbackow wsrod PENDING").isEqualTo("0");
            return null;
        });
    }

    // =========================================================================================
    // 2) Izolacja -- inny klient tego samego tenanta i klient innego tenanta nietkniety
    // =========================================================================================

    @Test
    @DisplayName("izolacja: inny klient tego samego tenanta, klient innego tenanta i osierocony e-mail bez powiazania z klientem sa nietkniete po wartosciach")
    void isolation_otherCustomerAndForeignTenant_untouched() throws Exception {
        inRolledBackTx(DB, c -> {
            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            assertThat(scalar(c, "SELECT remote_address FROM contact WHERE contact_id = ?", CONTACT_OTHER)).isEqualTo("+48000000001");
            assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", CALLBACK_OTHER)).isEqualTo("+48000000002");
            assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ?", CC_OTHER_CUSTOMER)).isEqualTo("Inny");
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_OTHER_SAME_TENANT)).isEqualTo("false");

            // e-mail osierocony bez powiazania z zadnym klientem (adresy nie pasuja do MAIN)
            assertThat(scalar(c, "SELECT from_address FROM email_message WHERE message_id = ?", EMAIL_UNRELATED_ORPHAN))
                    .isEqualTo("random@unrelated.example.com");

            // klient innego tenanta z tym samym telefonem/e-mailem co CUSTOMER_MAIN -- caly profil nietkniety
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_FOREIGN_TENANT)).isEqualTo("false");
            assertThat(scalar(c, "SELECT first_name FROM customer WHERE customer_id = ?", CUSTOMER_FOREIGN_TENANT)).isEqualTo("Obcy");
            return null;
        });
    }

    // =========================================================================================
    // 3) Regula mostu: kontakt dopasowany WYLACZNIE identyfikatorem + rekord kampanii/callback
    //    dopiety przez campaign_contact_record_id (inny telefon niz klienta) -- MUSI byc zanonimizowany
    // =========================================================================================

    @Test
    @DisplayName("regula mostu: kontakt dopasowany WYLACZNIE przez identifier + rekord kampanii i callback dopiete przez campaign_contact_record_id (inny telefon, customer_id NULL) sa FAKTYCZNIE zanonimizowane")
    void bridgeRule_identifierOnlyContact_bridgedCampaignRecordAndCallback_areAnonymized() throws Exception {
        inRolledBackTx(DB, c -> {
            // przed: rekord i callback maja WLASNE dane, inny telefon niz klienta, bez customer_id
            assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ?", CC_BRIDGE_RECORD)).isEqualTo("Mostowy");
            assertThat(scalar(c, "SELECT customer_id IS NULL FROM campaign_contact WHERE record_id = ?", CC_BRIDGE_RECORD)).isEqualTo("true");

            JsonNode result = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_BRIDGE, TENANT_A, UUID.randomUUID());
            assertThat(result.get("counts").get("campaign_contact").asInt()).isEqualTo(1);
            assertThat(result.get("counts").get("scheduled_callback").asInt()).isEqualTo(1);
            assertThat(result.get("counts").get("contact").asInt()).isEqualTo(1);
            assertThat(result.get("matched_by_identifier").asInt()).isGreaterThanOrEqualTo(1);

            assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ?", CC_BRIDGE_RECORD)).isEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT phone IS NULL FROM campaign_contact WHERE record_id = ?", CC_BRIDGE_RECORD)).isEqualTo("true");
            assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", CALLBACK_BRIDGE)).isEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT remote_address IS NULL FROM contact WHERE contact_id = ?", CONTACT_BRIDGE_IDENTIFIER)).isEqualTo("true");
            return null;
        });
    }

    // =========================================================================================
    // 4) Kolizja record_id miedzy kampaniami (DB061-08 guard) -- funkcja przerywa sie bezpiecznie
    // =========================================================================================

    @Test
    @DisplayName("guard DB061-08: dwa rekordy campaign_contact z tym samym record_id w roznych kampaniach tego samego tenanta -- funkcja przerywa sie RAISE EXCEPTION, zero zmian")
    void recordIdCollisionAcrossCampaigns_raisesAndChangesNothing() throws Exception {
        inRolledBackTx(DB, c -> {
            SQLException error = failureOfWithSavepointRecovery(c,
                    "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_COLLISION, TENANT_A, UUID.randomUUID());

            assertThat((Throwable) error).as("kolizja record_id musi rzucic").isNotNull();
            assertThat(error.getMessage()).contains("DB061-08");

            // zero zmian -- zarowno rekord powiazany z CUSTOMER_COLLISION (kampania A), jak i "obcy"
            // rekord z tym samym record_id w kampanii B, sa nietkniete
            assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ? AND campaign_id = ?",
                    COLLIDING_RECORD_ID, CAMPAIGN_COLLISION_A)).isEqualTo("Kolidujacy");
            assertThat(scalar(c, "SELECT first_name FROM campaign_contact WHERE record_id = ? AND campaign_id = ?",
                    COLLIDING_RECORD_ID, CAMPAIGN_COLLISION_B)).isEqualTo("Obcy2");
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_COLLISION)).isEqualTo("false");

            // sam dry-run tez rzuca (guard dziala w obu trybach)
            SQLException dryRunError = failureOfWithSavepointRecovery(c,
                    "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_COLLISION, TENANT_A, UUID.randomUUID());
            assertThat((Throwable) dryRunError).isNotNull();
            assertThat(dryRunError.getMessage()).contains("DB061-08");
            return null;
        });
    }

    // =========================================================================================
    // 4b) Guard DB062-01 (code review, blocker): p_dry_run = SQL NULL musi byc ODRZUCANY, nie
    //     po cichu traktowany jak FALSE ("IF NULL THEN" w PL/pgSQL == "IF FALSE THEN") -- inaczej
    //     boxed Boolean = null w Javie (np. brakujace pole w DTO -> JDBC wysyla SQL NULL) wykonalby
    //     rzeczywista, nieodwracalna anonimizacje zamiast bezpiecznego podgladu. Zreprodukowane
    //     dzialaniem w code review (CR-DATABASE.md DB062-01) przed dodaniem tego guardu -- ten test
    //     dowodzi, ze guard faktycznie dziala, i chroni przed regresja.
    // =========================================================================================

    @Test
    @DisplayName("guard DB062-01: p_dry_run = SQL NULL (jawny, nie pominiety argument) jest odrzucany RAISE EXCEPTION, zero zmian -- NIE wykonuje cichej rzeczywistej anonimizacji")
    void dryRunNull_isRejected_changesNothing() throws Exception {
        inRolledBackTx(DB, c -> {
            // Jawny SQL NULL (nie pominiety argument -- ten drugi korzystalby z DEFAULT FALSE i jest
            // juz pokryty innymi testami wywolujacymi funkcje z 3 argumentami).
            SQLException error = failureOfWithSavepointRecovery(c,
                    "SELECT anonymize_customer(?, ?, ?, ?)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID(), null);

            assertThat((Throwable) error).as("p_dry_run = NULL musi byc odrzucone").isNotNull();
            assertThat(error.getMessage()).contains("p_dry_run");

            // Zero zmian -- ani rzeczywista anonimizacja (customer.is_deleted), ani zaden z
            // pochodnych efektow (remote_address kontaktu z pelnego fixture'u scenariusza 1).
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN))
                    .isEqualTo("false");
            assertThat(scalar(c, "SELECT remote_address FROM contact WHERE contact_id = ?", CONTACT_MP3))
                    .isEqualTo(MAIN_PHONE);
            return null;
        });
    }

    // =========================================================================================
    // 5) Atomowosc: wymuszony blad w polowie (poison trigger na customer, ostatnia instrukcja)
    //    -> wszystko wczesniejsze (contact, scheduled_callback, campaign_contact, DELETE
    //    transkrypcji/podsumowan, email, social) jest cofniete
    // =========================================================================================

    @Test
    @DisplayName("atomowosc: wymuszony blad przy OSTATNIEJ instrukcji (UPDATE customer) cofa WSZYSTKIE wczesniejsze zmiany tej samej transakcji (contact, callback, campaign_contact, DELETE transkrypcji, email, social)")
    void atomicity_forcedFailureAtFinalStep_rollsBackEverythingBefore() throws Exception {
        inRolledBackTx(DB, c -> {
            update(c, """
                    CREATE OR REPLACE FUNCTION trg_poison_customer_anonymize() RETURNS TRIGGER
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        IF NEW.customer_id = '""" + CUSTOMER_MAIN + """
                    ' AND NEW.is_deleted = TRUE THEN
                            RAISE EXCEPTION 'FORCED_FAILURE_FOR_ATOMICITY_TEST';
                        END IF;
                        RETURN NEW;
                    END;
                    $$;
                    """);
            update(c, "CREATE TRIGGER trg_poison BEFORE UPDATE ON customer FOR EACH ROW EXECUTE FUNCTION trg_poison_customer_anonymize()");

            SQLException error = failureOfWithSavepointRecovery(c,
                    "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat((Throwable) error).as("poison trigger musi wymusic blad").isNotNull();
            assertThat(error.getMessage()).contains("FORCED_FAILURE_FOR_ATOMICITY_TEST");

            update(c, "DROP TRIGGER trg_poison ON customer");
            update(c, "DROP FUNCTION trg_poison_customer_anonymize()");

            // WSZYSTKO cofniete -- customer jest ostatnia instrukcja, wiec dowodzi to pelnej atomowosci
            // wczesniejszych krokow (contact, callback, campaign_contact/archive, DELETE transkrypcji/
            // podsumowan, email, social) wykonanych w TEJ SAMEJ (nieudanej) transakcji.
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo("false");
            assertThat(scalar(c, "SELECT remote_address IS NOT NULL FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("true");
            assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", CALLBACK_PENDING)).isNotEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT phone IS NOT NULL FROM campaign_contact WHERE record_id = ?", CC_SAME_CAMPAIGN_1)).isEqualTo("true");
            assertThat(scalar(c, "SELECT count(*)::text FROM contact_transcription WHERE contact_id = ?", CONTACT_MP3)).isEqualTo("1");
            assertThat(scalar(c, "SELECT from_address FROM email_message WHERE message_id = ?", EMAIL_INBOUND)).isNotEqualTo("anonymized@example.com");
            return null;
        });
    }

    // =========================================================================================
    // 6) Dosanityzowanie: klient zanonimizowany dawniej WYLACZNIE sciezka Javy (is_deleted=TRUE,
    //    first/last/phone/email juz puste, ale custom_fields/gdpr_consent/external_id NIE, oraz
    //    kontakty/wiadomosci/callbacki/rekordy kampanii NIETKNIETE) -> wywolanie konczy
    //    anonimizacje reszty bez bledu (wymaga V094/DB-079); drugie wywolanie = zerowe liczniki
    // =========================================================================================

    @Test
    @DisplayName("dosanityzowanie: klient zanonimizowany dawniej tylko sciezka Javy (is_deleted=TRUE, ale custom_fields/gdpr_consent/external_id i dane potomne nietkniete) -- wywolanie konczy anonimizacje bez bledu; drugie wywolanie = zerowe liczniki oprocz customer=1")
    void dosanityzacja_customerAlreadyDeletedViaJavaPath_completesRemainingDataIdempotently() throws Exception {
        inRolledBackTx(DB, c -> {
            // przed: customer juz is_deleted=TRUE (Java), ale custom_fields/external_id/gdpr_consent NIE dotkniete
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_JAVA_ONLY)).isEqualTo("true");
            assertThat(scalar(c, "SELECT external_id FROM customer WHERE customer_id = ?", CUSTOMER_JAVA_ONLY)).isEqualTo("ERP-12345");
            assertThat(scalar(c, "SELECT remote_address FROM contact WHERE contact_id = ?", CONTACT_JAVA_ONLY)).isEqualTo(JAVA_ONLY_PHONE);

            JsonNode first = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_JAVA_ONLY, TENANT_A, UUID.randomUUID());
            assertThat(first.get("counts").get("customer").asInt()).isEqualTo(1);
            assertThat(first.get("counts").get("contact").asInt()).isEqualTo(1);
            assertThat(first.get("counts").get("scheduled_callback").asInt()).isEqualTo(1);
            assertThat(first.get("counts").get("campaign_contact").asInt()).isEqualTo(1);
            assertThat(first.get("counts").get("email_message").asInt()).isEqualTo(1);
            assertThat(first.get("counts").get("social_message").asInt()).isEqualTo(1);

            assertThat(scalar(c, "SELECT external_id IS NULL FROM customer WHERE customer_id = ?", CUSTOMER_JAVA_ONLY)).isEqualTo("true");
            assertThat(scalar(c, "SELECT custom_fields::text FROM customer WHERE customer_id = ?", CUSTOMER_JAVA_ONLY)).isEqualTo("{}");
            assertThat(scalar(c, "SELECT remote_address IS NULL FROM contact WHERE contact_id = ?", CONTACT_JAVA_ONLY)).isEqualTo("true");
            assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", CALLBACK_JAVA_ONLY)).isEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT from_address FROM email_message WHERE message_id = ?", EMAIL_JAVA_ONLY)).isEqualTo("anonymized@example.com");
            assertThat(scalar(c, "SELECT sender_external_id FROM social_message WHERE message_id = ?", SOCIAL_JAVA_ONLY)).isEqualTo("ANONYMIZED");

            // drugie wywolanie -- liczniki zero (oprocz customer=1, zawsze dosanityzowywany bezwarunkowo)
            JsonNode second = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_JAVA_ONLY, TENANT_A, UUID.randomUUID());
            assertThat(second.get("counts").get("customer").asInt()).isEqualTo(1);
            assertThat(second.get("counts").get("contact").asInt()).isZero();
            assertThat(second.get("counts").get("scheduled_callback").asInt()).isZero();
            assertThat(second.get("counts").get("campaign_contact").asInt()).isZero();
            assertThat(second.get("counts").get("campaign_contact_archive").asInt()).isZero();
            assertThat(second.get("counts").get("contact_transcription").asInt()).isZero();
            assertThat(second.get("counts").get("contact_ai_summary").asInt()).isZero();
            assertThat(second.get("counts").get("email_message").asInt()).isZero();
            assertThat(second.get("counts").get("social_message").asInt()).isZero();
            return null;
        });
    }

    // =========================================================================================
    // 7) Tryb podgladu (p_dry_run): dwa wywolania identyczne, baza nietknieta, liczniki = realnym
    // =========================================================================================

    @Test
    @DisplayName("p_dry_run=TRUE: dwa kolejne wywolania daja identyczny wynik, baza nietknieta, liczniki rowne rzeczywistemu przebiegowi")
    void dryRun_doesNotMutate_twoCallsIdentical_matchesRealRunCounts() throws Exception {
        inRolledBackTx(DB, c -> {
            String beforeContact = scalar(c, "SELECT remote_address FROM contact WHERE contact_id = ?", CONTACT_MP3);
            String beforeCustomer = scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN);
            long auditBefore = Long.parseLong(scalar(c, "SELECT count(*)::text FROM audit_log"));

            JsonNode first = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            JsonNode second = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(first).isEqualTo(second);
            assertThat(first.get("dry_run").asBoolean()).isTrue();

            JsonNode counts = first.get("counts");
            assertThat(counts.get("contact").asInt()).isEqualTo(2);
            assertThat(counts.get("scheduled_callback").asInt()).isEqualTo(2);
            assertThat(counts.get("campaign_contact").asInt()).isEqualTo(3);
            assertThat(counts.get("campaign_contact_archive").asInt()).isEqualTo(2);
            assertThat(counts.get("email_message").asInt()).isEqualTo(3);
            assertThat(counts.get("social_message").asInt()).isEqualTo(2);

            assertThat(scalar(c, "SELECT remote_address FROM contact WHERE contact_id = ?", CONTACT_MP3)).isEqualTo(beforeContact);
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo(beforeCustomer);
            assertThat(Long.parseLong(scalar(c, "SELECT count(*)::text FROM audit_log"))).isEqualTo(auditBefore);
            return null;
        });
    }

    @Test
    @DisplayName("numer wspolny dla dwoch klientow: podglad pokazuje trafienie identyfikatorowe na osieroconym kontakcie; klient B (i jego WLASNY, poprawnie powiazany kontakt) nietkniety po rzeczywistym wywolaniu dla klienta A")
    void dryRun_sharedPhoneBetweenTwoCustomers_previewShowsIdentifierMatch_customerBUntouched() throws Exception {
        inRolledBackTx(DB, c -> {
            JsonNode preview = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_SHARED_A, TENANT_A, UUID.randomUUID());
            assertThat(preview.get("matched_by_identifier").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(preview.get("counts").get("contact").asInt()).isEqualTo(1);

            scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_SHARED_A, TENANT_A, UUID.randomUUID());

            // osierocony kontakt dopasowany identyfikatorem -- zanonimizowany
            assertThat(scalar(c, "SELECT remote_address IS NULL FROM contact WHERE contact_id = ?", CONTACT_SHARED_ORPHAN)).isEqualTo("true");

            // klient B calkowicie nietkniety (jego WLASNY wiersz customer)
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_SHARED_B)).isEqualTo("false");
            // kontakt WLASNY klienta B (customer_id = B, INNY telefon niz wspolny) -- nietkniety
            assertThat(scalar(c, "SELECT remote_address IS NOT NULL FROM contact WHERE contact_id = ?", CONTACT_SHARED_B_OWN)).isEqualTo("true");
            return null;
        });
    }

    // =========================================================================================
    // 9) Spojnosc podgladu DB-062 z eksportem DB-061 (ta sama fn_customer_subject_ids)
    // =========================================================================================

    @Test
    @DisplayName("spojnosc: matched_by_link/matched_by_identifier z podgladu anonymize_customer sa rowne tym z export_customer_data na tym samym, niezmienionym fixture")
    void previewMatchesExportCustomerData_matchedByCountsConsistent() throws Exception {
        inRolledBackTx(DB, c -> {
            JsonNode exportResult = scalarJson(c, "SELECT export_customer_data(?, ?)", CUSTOMER_MAIN, TENANT_A);
            JsonNode dryRun = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());

            assertThat(dryRun.get("matched_by_link").asInt()).isEqualTo(exportResult.get("matched_by_link").asInt());
            assertThat(dryRun.get("matched_by_identifier").asInt()).isEqualTo(exportResult.get("matched_by_identifier").asInt());
            assertThat(dryRun.get("counts").get("contact").asInt()).isEqualTo(exportResult.get("contacts").size());
            assertThat(dryRun.get("counts").get("email_message").asInt()).isEqualTo(exportResult.get("email_messages").size());
            assertThat(dryRun.get("counts").get("social_message").asInt()).isEqualTo(exportResult.get("social_messages").size());
            assertThat(dryRun.get("counts").get("scheduled_callback").asInt()).isEqualTo(exportResult.get("scheduled_callbacks").size());
            return null;
        });
    }

    // =========================================================================================
    // 10) RLS pod SET ROLE app_user -- per tabela: ktore faktycznie sie zmieniaja, ktore cicho 0,
    //     ktore rzucaja twardy blad (audit_log INSERT)
    //
    //     AKTUALIZACJA DB-064/V099 (2026-10-01): email_message/social_message mialy WYLACZNIE
    //     polityke SELECT (bez FORCE) od V012 -- UPDATE pod app_user dopasowywal CICHO 0 wierszy,
    //     dokladnie jak dzis nadal contact (brak jakiejkolwiek polityki UPDATE). V099 zastapilo
    //     te polityki jedna FOR ALL + WITH CHECK + FORCE (wzorzec plugin_invocation_log/V077) --
    //     UPDATE wlasnego tenanta pod app_user TERAZ dziala dla obu tabel. Testy (A) i (C) ponizej
    //     zaktualizowane, zeby odzwierciedlac ten fakt (byly czescia dowodu luki DB-060/DB-064
    //     przed migracja -- dzis dokumentuja naprawe).
    //
    //     AKTUALIZACJA DB-072/DB-073 (V111/V112, 2026-10-08, D7 Opcja 1): campaign_contact i
    //     campaign_contact_archive mialy WCZESNIEJ brak RLS w ogole (relrowsecurity=false) --
    //     pelny dostep niezaleznie od GUC. Od V111/V112 obie maja polityke ALL + WITH CHECK +
    //     FORCE (ten sam wzorzec jak V099). Licznik w tescie (C) nizej NIE zmienia sie (nadal 1),
    //     bo UPDATE w anonymize_customer juz filtruje WHERE tenant_id = p_tenant_id, a w tym
    //     tescie GUC == p_tenant_id (TENANT_A) -- RLS jest wiec spelnione trywialnie. Zmienia sie
    //     TYLKO powod, dla ktorego zapis dziala (polityka dopuszcza, nie brak RLS) -- komentarz
    //     przy asercji ponizej zaktualizowany.
    //
    //     AKTUALIZACJA DB-074 (V113-V124, 2026-10-08, klasyfikacja DB-071): dwie zmiany dotykaja
    //     ten sam scenariusz testowy. (1) V117 dodalo brakujace polityki UPDATE/DELETE na contact
    //     (wczesniej WYLACZNIE SELECT+INSERT od V012) -- UPDATE contact pod app_user TERAZ DZIALA
    //     dla wlasnego tenanta, test (A) zaktualizowany z 0 na 1. (2) V122 dodalo polityke INSERT
    //     na audit_log (galaz "tenant_id IS NULL OR tenant_id = GUC") -- INSERT INTO audit_log w
    //     OSTATNIM kroku anonymize_customer uzywa tenant_id = p_tenant_id, a w tym tescie
    //     GUC == p_tenant_id (TENANT_A), wiec WITH CHECK jest TERAZ spelnione -- caly przebieg
    //     anonymize_customer pod app_user KONCZY SIE SUKCESEM, nie twardym bledem 42501/P0001 jak
    //     przed V122. Test (B) przeksztalcony z "dowodu bledu" w "dowod naprawy" (byla to
    //     przedistniejaca, znana od DB-062/DB-064 luka -- brak JAKIEJKOLWIEK polityki INSERT na
    //     audit_log od V012, poza zakresem tamtych ticketow, domknieta tutaj). Test (C) nie
    //     wymaga juz tymczasowego patcha polityki INSERT na audit_log (byl potrzebny WYLACZNIE do
    //     izolacji tej jednej, przedistniejacej luki od reszty warstwy zapisu) -- usuniety;
    //     licznik contact zmieniony z 0 na 1 z tego samego powodu co test (A).
    // =========================================================================================

    @Test
    @DisplayName("RLS/app_user (A): DB-074/V117 dodalo polityke UPDATE na contact -- UPDATE wlasnego tenanta TERAZ dziala (wczesniej: cicho 0 wierszy); UPDATE email_message/social_message dziala od DB-064/V099 (polityka ALL + WITH CHECK + FORCE)")
    void rlsUnderAppUser_contactNowWriteable_emailSocialWriteable() throws Exception {
        inRolledBackTx(DB, c -> {
            asAppUser(c, TENANT_A);
            assertThat(update(c, "UPDATE contact SET remote_address = NULL WHERE contact_id = ? AND tenant_id = ?", CONTACT_RLS, TENANT_A))
                    .as("contact: DB-074/V117 dodalo polityke UPDATE -- wlasny tenant dziala").isEqualTo(1);
            assertThat(update(c, "UPDATE email_message SET subject = '[ANONYMIZED]' WHERE message_id = ? AND tenant_id = ?", EMAIL_RLS, TENANT_A))
                    .as("email_message: DB-064/V099 dodalo polityke ALL+WITH CHECK+FORCE -- UPDATE wlasnego tenanta dziala").isEqualTo(1);
            assertThat(update(c, "UPDATE social_message SET content = 'x' WHERE message_id = ? AND tenant_id = ?", SOCIAL_RLS, TENANT_A))
                    .as("social_message: DB-064/V099 dodalo polityke ALL+WITH CHECK+FORCE -- UPDATE wlasnego tenanta dziala").isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("RLS/app_user (B): DB-074/V122 dodalo polityke INSERT na audit_log (galaz tenant_id = GUC) -- INSERT INTO audit_log w anonymize_customer TERAZ DZIALA dla wlasnego tenanta; caly przebieg konczy sie sukcesem (wczesniej: twardy blad 42501/P0001, caly call rollback -- przedistniejaca luka od V012, poza zakresem DB-062/DB-064, domknieta w DB-074)")
    void rlsUnderAppUser_auditLogInsertNowWorks_wholeCallSucceeds() throws Exception {
        inRolledBackTx(DB, c -> {
            asAppUser(c, TENANT_A);
            JsonNode result = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_RLS, TENANT_A, UUID.randomUUID());
            assertThat(result.get("counts").get("customer").asInt())
                    .as("caly przebieg konczy sie sukcesem (nie rollbackiem) -- audit_log INSERT juz nie blokuje").isEqualTo(1);

            update(c, "RESET ROLE");
            assertThat(scalar(c, "SELECT phone FROM scheduled_callback WHERE callback_id = ?", CALLBACK_RLS)).isEqualTo("ANONYMIZED");
            assertThat(scalar(c, "SELECT count(*)::text FROM contact_transcription WHERE contact_id = ?", CONTACT_RLS)).isEqualTo("0");
            assertThat(scalar(c, "SELECT count(*)::text FROM audit_log WHERE entity_id = ? AND action = 'CUSTOMER_ANONYMIZED'", CUSTOMER_RLS))
                    .as("audit_log: wiersz faktycznie wstawiony pod app_user (WITH CHECK tenant_id = GUC spelnione, bo p_tenant_id == GUC)")
                    .isEqualTo("1");
            return null;
        });
    }

    @Test
    @DisplayName("RLS/app_user (C): bez zadnego tymczasowego patcha polityk (DB-074 domyka ostatnia brakujaca -- audit_log INSERT) -- scheduled_callback/campaign_contact/campaign_contact_archive/contacts_dw/contact_transcription/contact_ai_summary/customer/contact/email_message/social_message WSZYSTKIE faktycznie sie zmieniaja pod app_user")
    void rlsUnderAppUser_noPatchNeeded_revealsPerTableCounters() throws Exception {
        inRolledBackTx(DB, c -> {
            asAppUser(c, TENANT_A);
            JsonNode result = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_RLS, TENANT_A, UUID.randomUUID());
            JsonNode counts = result.get("counts");

            assertThat(counts.get("customer").asInt()).as("customer: polityka UPDATE dziala").isEqualTo(1);
            assertThat(counts.get("scheduled_callback").asInt()).as("scheduled_callback: polityka ALL (bez FORCE) dziala mimo braku FORCE").isEqualTo(1);
            assertThat(counts.get("campaign_contact").asInt()).as("campaign_contact: DB-073/V112 polityka ALL+WITH CHECK+FORCE dziala (tenant_id = GUC)").isEqualTo(1);
            assertThat(counts.get("campaign_contact_archive").asInt()).as("campaign_contact_archive: DB-072/V111 polityka ALL+WITH CHECK+FORCE dziala (tenant_id = GUC)").isEqualTo(1);
            assertThat(counts.get("contacts_dw").asInt()).as("contacts_dw: DB-074/V116 dodalo polityke ALL+WITH CHECK+FORCE -- dziala (tenant_id = GUC)").isEqualTo(1);
            assertThat(counts.get("contact_transcription").asInt()).as("contact_transcription: polityka ALL + FORCE dziala").isEqualTo(1);
            assertThat(counts.get("contact_ai_summary").asInt()).as("contact_ai_summary: polityka ALL + FORCE dziala").isEqualTo(1);

            assertThat(counts.get("contact").asInt()).as("contact: DB-074/V117 dodalo polityke UPDATE -- dziala").isEqualTo(1);
            assertThat(counts.get("email_message").asInt()).as("email_message: DB-064/V099 polityka ALL+WITH CHECK+FORCE dziala").isEqualTo(1);
            assertThat(counts.get("social_message").asInt()).as("social_message: DB-064/V099 polityka ALL+WITH CHECK+FORCE dziala").isEqualTo(1);
            return null;
        });
    }

    // =========================================================================================
    // 11) Guard contacts_dw -- kolumna remote_address usunieta (symulacja DB-078) nie psuje funkcji
    // =========================================================================================

    @Test
    @DisplayName("guard contacts_dw: po ALTER TABLE contacts_dw DROP COLUMN remote_address (symulacja DB-078) funkcja dziala bez bledu, contacts_dw = 0 w obu trybach, reszta tabel zanonimizowana normalnie")
    void contactsDwGuard_survivesColumnAbsence() throws Exception {
        inRolledBackTx(DB, c -> {
            update(c, "ALTER TABLE contacts_dw DROP COLUMN remote_address");

            JsonNode dryRun = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, TRUE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(dryRun.get("counts").get("contacts_dw").asInt()).isZero();

            JsonNode real = scalarJson(c, "SELECT anonymize_customer(?, ?, ?, FALSE)", CUSTOMER_MAIN, TENANT_A, UUID.randomUUID());
            assertThat(real.get("counts").get("contacts_dw").asInt()).isZero();
            assertThat(real.get("counts").get("contact").asInt()).isEqualTo(2);
            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_MAIN)).isEqualTo("true");
            return null;
        });
    }

    // =========================================================================================
    // Fixture
    // =========================================================================================

    private static void seedFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, 'Tenant A - DB-062 IT'), (?, 'Tenant B - DB-062 IT')", TENANT_A, TENANT_B);

        seedMainCustomer(c);
        seedIsolationFixture(c);
        seedBridgeFixture(c);
        seedCollisionFixture(c);
        seedDosanityzacjaFixture(c);
        seedSharedPhoneFixture(c);
        seedRlsFixture(c);
    }

    private static void seedMainCustomer(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source, external_id, custom_fields, gdpr_consent) "
                        + "VALUES (?, ?, 'Jan', 'Testowy', ?::jsonb, ?::jsonb, 'MANUAL', 'ERP-001', '{\"vip\":true}'::jsonb, '{\"consent_given\":true}'::jsonb)",
                CUSTOMER_MAIN, TENANT_A, "[\"" + MAIN_PHONE + "\"]", "[\"" + MAIN_EMAIL + "\"]");

        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                        + "started_at, notes, recording_url, channel_metadata) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now() - interval '2 days', 'notatka agenta', ?, '{\"sip_call_id\":\"abc\"}'::jsonb)",
                CONTACT_MP3, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE, RECORDING_MP3);
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                        + "started_at, recording_url) "
                        + "VALUES (?, ?, ?, 'EMAIL', 'INBOUND', 'COMPLETED', ?, now() - interval '1 days', ?)",
                CONTACT_EML, TENANT_A, CUSTOMER_MAIN, MAIN_EMAIL, RECORDING_EML);

        update(c, "INSERT INTO contact_transcription (transcription_id, contact_id, tenant_id, content, language, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'tresc rozmowy', 'pl', now())", CONTACT_MP3, TENANT_A);
        update(c, "INSERT INTO contact_ai_summary (ai_summary_id, contact_id, tenant_id, summary, model, generated_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'podsumowanie', 'gpt-test', now())", CONTACT_MP3, TENANT_A);

        update(c, "INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at, remote_address) "
                        + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', now() - interval '2 days', now() - interval '2 days', ?)",
                CONTACT_MP3, TENANT_A, MAIN_PHONE);

        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Main 1')", CAMPAIGN_MAIN_1, TENANT_A);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Main 2')", CAMPAIGN_MAIN_2, TENANT_A);

        // dwa rekordy TEGO SAMEGO klienta w JEDNEJ kampanii, z ROZNYMI oryginalnymi telefonami (indeks
        // czesciowy dopuszcza to dzis) -- po anonimizacji OBA phone = NULL, indeks nie peka
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'PENDING', 0)",
                CC_SAME_CAMPAIGN_1, CAMPAIGN_MAIN_1, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE, MAIN_EMAIL);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'NO_ANSWER', 1)",
                CC_SAME_CAMPAIGN_2, CAMPAIGN_MAIN_1, TENANT_A, CUSTOMER_MAIN, "+48500100201", MAIN_EMAIL);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'CALLBACK', 2)",
                CC_OTHER_CAMPAIGN, CAMPAIGN_MAIN_2, TENANT_A, CUSTOMER_MAIN, "+48500100202", MAIN_EMAIL);

        update(c, "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                        + "last_name, email, custom_fields, status, attempt_count, created_at, archived_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'COMPLETED', 3, now(), now())",
                CCA_1, CAMPAIGN_MAIN_1, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE, MAIN_EMAIL);
        update(c, "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                        + "last_name, email, custom_fields, status, attempt_count, created_at, archived_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'Jan', 'Testowy', ?, '{}'::jsonb, 'NO_ANSWER', 4, now(), now())",
                CCA_2, CAMPAIGN_MAIN_2, TENANT_A, CUSTOMER_MAIN, "+48500100203", MAIN_EMAIL);

        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "scheduled_at, notes, status, source_type) VALUES (?, ?, ?, ?, 'Jan', 'Testowy', now() + interval '1 day', 'oddzwonic', 'PENDING', 'AGENT_MANUAL')",
                CALLBACK_PENDING, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE);
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "scheduled_at, notes, status, source_type) VALUES (?, ?, ?, ?, 'Jan', 'Testowy', now() + interval '2 day', 'notatka2', 'PROCESSING', 'AGENT_MANUAL')",
                CALLBACK_PROCESSING, TENANT_A, CUSTOMER_MAIN, MAIN_PHONE);

        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                        + "cc_address, bcc_address, subject, body_html, body_text, attachments, received_at, delivery_status) "
                        + "VALUES (?, ?, ?, 'INBOUND', ?, 'agent@firma.pl', 'kopia@firma.pl', 'ukryta@firma.pl', 'Temat', "
                        + "'<p>tresc</p>', 'tresc plain', "
                        + "('[{\"filename\":\"zal.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":1234,"
                        + "\"s3_key\":\"email-attachments/' || ? || '/msg-inbound/zal.pdf\"}]')::jsonb, now(), 'DELIVERED')",
                EMAIL_INBOUND, TENANT_A, CONTACT_MP3, MAIN_EMAIL, TENANT_A.toString());
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                        + "subject, body_text, attachments, sent_at, delivery_status) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'agent@firma.pl', ?, 'RE: Temat', 'odpowiedz', "
                        + "('[{\"filename\":\"zal2.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":55,"
                        + "\"s3_key\":\"email-attachments/' || ? || '/pending/pending-uuid/zal2.pdf\"}]')::jsonb, now(), 'SENT')",
                EMAIL_OUTBOUND_PENDING, TENANT_A, CONTACT_MP3, MAIN_EMAIL, TENANT_A.toString());
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, "
                        + "subject, body_text, sent_at, delivery_status) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'agent@firma.pl', ?, 'Trzeci', 'tresc', now(), 'SENT')",
                EMAIL_PLAIN, TENANT_A, CONTACT_EML, MAIN_EMAIL);

        update(c, "INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, "
                        + "sender_external_id, content, attachments, sent_at) VALUES (gen_random_uuid(), ?, ?, 'WHATSAPP', 'INBOUND', "
                        + "'wa-ext-db062-1', 'wa-sender-db062-1', 'tresc 1', "
                        + "'[{\"type\":\"IMAGE\",\"url\":\"https://x/1.png\",\"size_bytes\":10}]'::jsonb, now())",
                TENANT_A, CONTACT_MP3);
        update(c, "UPDATE social_message SET message_id = ? WHERE external_message_id = 'wa-ext-db062-1'", SOCIAL_1);
        update(c, "INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, "
                        + "sender_external_id, content, sent_at) VALUES (?, ?, ?, 'FACEBOOK', 'INBOUND', 'fb-ext-db062-1', "
                        + "'fb-sender-db062-1', 'tresc 2', now())",
                SOCIAL_2, TENANT_A, CONTACT_EML);
    }

    private static void seedIsolationFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Inny', 'Klient', '[\"+48000000001\"]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_OTHER_SAME_TENANT, TENANT_A);
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', '+48000000001', now())",
                CONTACT_OTHER, TENANT_A, CUSTOMER_OTHER_SAME_TENANT);
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, source_type) "
                        + "VALUES (?, ?, ?, '+48000000002', now() + interval '1 day', 'PENDING', 'AGENT_MANUAL')",
                CALLBACK_OTHER, TENANT_A, CUSTOMER_OTHER_SAME_TENANT);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Other')", CAMPAIGN_OTHER, TENANT_A);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, '+48000000003', 'Inny', 'Kontakt', '{}'::jsonb, 'PENDING', 0)",
                CC_OTHER_CUSTOMER, CAMPAIGN_OTHER, TENANT_A, CUSTOMER_OTHER_SAME_TENANT);

        // e-mail osierocony, adresy NIE pasuja do CUSTOMER_MAIN (kontrola: identyfikator nie zlapie przypadkowo)
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, subject, sent_at, delivery_status) "
                        + "VALUES (?, ?, NULL, 'OUTBOUND', 'random@unrelated.example.com', 'ktos@example.com', 'Nic wspolnego', now(), 'SENT')",
                EMAIL_UNRELATED_ORPHAN, TENANT_A);

        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Obcy', 'Klient', ?::jsonb, ?::jsonb, 'MANUAL')",
                CUSTOMER_FOREIGN_TENANT, TENANT_B, "[\"" + MAIN_PHONE + "\"]", "[\"" + MAIN_EMAIL + "\"]");
    }

    private static void seedBridgeFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Most', 'Klient', ?::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_BRIDGE, TENANT_A, "[\"" + BRIDGE_PHONE + "\"]");
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Bridge')", CAMPAIGN_BRIDGE, TENANT_A);

        // rekord kampanii BEZ customer_id, z INNYM telefonem niz klient -- powiazany WYLACZNIE przez most
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "email, custom_fields, status, attempt_count) VALUES (?, ?, ?, NULL, '+48999111222', 'Mostowy', 'Rekord', "
                        + "'mostowy@example.com', '{}'::jsonb, 'PENDING', 0)",
                CC_BRIDGE_RECORD, CAMPAIGN_BRIDGE, TENANT_A);

        // kontakt dopasowany WYLACZNIE identyfikatorem (customer_id NULL, remote_address = telefon klienta
        // ZAPISANY Z SEPARATORAMI -- fn_normalize_phone musi go znormalizowac do postaci BRIDGE_PHONE
        // klienta, zeby dopasowanie zadzialalo), niesie tez campaign_contact_record_id -- most
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, "
                        + "started_at, campaign_contact_record_id) VALUES (?, ?, NULL, 'PHONE', 'OUTBOUND', 'COMPLETED', ?, now(), ?)",
                CONTACT_BRIDGE_IDENTIFIER, TENANT_A, BRIDGE_PHONE_WITH_SEPARATORS, CC_BRIDGE_RECORD);

        // callback powiazany WYLACZNIE przez campaign_contact_record_id (inny telefon niz klienta, bez customer_id)
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, "
                        + "source_type, campaign_contact_record_id) VALUES (?, ?, NULL, '+48999111222', now() + interval '1 day', "
                        + "'PENDING', 'CAMPAIGN_CALLBACK', ?)",
                CALLBACK_BRIDGE, TENANT_A, CC_BRIDGE_RECORD);
    }

    private static void seedCollisionFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Kolizja', 'Klient', '[\"+48555000111\"]'::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_COLLISION, TENANT_A);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Collision A')", CAMPAIGN_COLLISION_A, TENANT_A);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 Collision B')", CAMPAIGN_COLLISION_B, TENANT_A);

        // ten sam record_id (jawnie nadany) w DWOCH roznych kampaniach tego samego tenanta -- PK zlozony
        // (record_id, campaign_id) na to pozwala, mimo ze schemat NIE wymusza globalnej unikalnosci record_id
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, '+48555000111', 'Kolidujacy', 'Rekord', '{}'::jsonb, 'PENDING', 0)",
                COLLIDING_RECORD_ID, CAMPAIGN_COLLISION_A, TENANT_A, CUSTOMER_COLLISION);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "custom_fields, status, attempt_count) VALUES (?, ?, ?, NULL, '+48555999888', 'Obcy2', 'Niepowiazany', '{}'::jsonb, 'PENDING', 0)",
                COLLIDING_RECORD_ID, CAMPAIGN_COLLISION_B, TENANT_A);
    }

    private static void seedDosanityzacjaFixture(Connection c) throws SQLException {
        // Symulacja starej sciezki Javy (CustomerRepository#anonymize, DB-060 F8): first/last/phone/email
        // juz puste, is_deleted=TRUE, ale custom_fields/gdpr_consent/external_id NIE dotkniete -- i
        // potomne dane (contact/callback/campaign_contact) w ogole NIE dotkniete. Kolejnosc jak w
        // produkcji (i jak w fixture ContactRefIntegrityNarrowingTest): klient tworzony ZYWY (is_deleted
        // = FALSE), kontakty/callbacki/rekordy kampanii tworzone PRZY zywym kliencie (trg_contact_ref_
        // integrity waliduje KAZDY INSERT bezwarunkowo -- wczesny RETURN z V094 dotyczy tylko UPDATE),
        // dopiero POTEM soft-delete + wyczyszczenie first/last/phone/email (symulacja Javy).
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source, is_deleted, "
                        + "external_id, custom_fields, gdpr_consent) VALUES (?, ?, 'Java', 'Only', ?::jsonb, "
                        + "'[]'::jsonb, 'MANUAL', FALSE, 'ERP-12345', '{\"vip\":true}'::jsonb, '{\"consent_given\":true}'::jsonb)",
                CUSTOMER_JAVA_ONLY, TENANT_A, "[\"" + JAVA_ONLY_PHONE + "\"]");
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now())",
                CONTACT_JAVA_ONLY, TENANT_A, CUSTOMER_JAVA_ONLY, JAVA_ONLY_PHONE);
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, source_type) "
                        + "VALUES (?, ?, ?, ?, now() + interval '1 day', 'PENDING', 'AGENT_MANUAL')",
                CALLBACK_JAVA_ONLY, TENANT_A, CUSTOMER_JAVA_ONLY, JAVA_ONLY_PHONE);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 JavaOnly')", CAMPAIGN_JAVA_ONLY, TENANT_A);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, ?, 'Stary', 'Rekord', '{}'::jsonb, 'PENDING', 0)",
                CC_JAVA_ONLY, CAMPAIGN_JAVA_ONLY, TENANT_A, CUSTOMER_JAVA_ONLY, JAVA_ONLY_PHONE);
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, subject, sent_at, delivery_status) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'agent@firma.pl', 'ktos@example.com', 'Stary temat', now(), 'SENT')",
                EMAIL_JAVA_ONLY, TENANT_A, CONTACT_JAVA_ONLY);
        update(c, "INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, "
                        + "sender_external_id, content, sent_at) VALUES (?, ?, ?, 'WHATSAPP', 'INBOUND', 'wa-ext-javaonly-1', "
                        + "'wa-sender-javaonly-1', 'stara tresc', now())",
                SOCIAL_JAVA_ONLY, TENANT_A, CONTACT_JAVA_ONLY);

        // Teraz -- PO utworzeniu wszystkich potomnych danych przy zywym kliencie -- symulacja starej
        // sciezki Javy: first/last/phone/email wyzerowane, is_deleted=TRUE, ale custom_fields/
        // gdpr_consent/external_id CELOWO NIETKNIETE (DB-060 F8: CustomerRepository#anonymize ich nie
        // czysci). To jest UPDATE customer bez zmiany referencji -- V094 (wczesny RETURN) nie ma tu
        // zastosowania (customer nie ma triggera fn_contact_ref_integrity), wiec dziala niezaleznie.
        update(c, "UPDATE customer SET first_name = 'ANONYMIZED', last_name = 'ANONYMIZED', phone = '[]'::jsonb, "
                        + "email = '[]'::jsonb, is_deleted = TRUE WHERE customer_id = ?", CUSTOMER_JAVA_ONLY);
    }

    private static void seedSharedPhoneFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Wspolny', 'A', ?::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_SHARED_A, TENANT_A, "[\"" + SHARED_PHONE + "\"]");
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'Wspolny', 'B', ?::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_SHARED_B, TENANT_A, "[\"" + SHARED_PHONE + "\"]");

        // kontakt osierocony (customer_id NULL) z numerem wspolnym -- dopasowany identyfikatorem do A
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, NULL, 'PHONE', 'INBOUND', 'COMPLETED', ?, now())",
                CONTACT_SHARED_ORPHAN, TENANT_A, SHARED_PHONE);

        // kontakt WLASNY klienta B, poprawnie powiazany (customer_id = B), z INNYM telefonem (aby test
        // nie zalezal od nierozstrzygnietego ryzyka R9 -- most/identyfikator nie moze go przypadkowo zlapac)
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', '+48000111222', now())",
                CONTACT_SHARED_B_OWN, TENANT_A, CUSTOMER_SHARED_B);
    }

    private static void seedRlsFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source) "
                        + "VALUES (?, ?, 'RLS', 'Klient', ?::jsonb, '[]'::jsonb, 'MANUAL')",
                CUSTOMER_RLS, TENANT_A, "[\"" + RLS_PHONE + "\"]");
        update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, remote_address, started_at, notes) "
                        + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now(), 'notatka rls')",
                CONTACT_RLS, TENANT_A, CUSTOMER_RLS, RLS_PHONE);
        update(c, "INSERT INTO contact_transcription (transcription_id, contact_id, tenant_id, content, language, created_at) "
                        + "VALUES (?, ?, ?, 'tresc rls', 'pl', now())", TRANSCRIPTION_RLS, CONTACT_RLS, TENANT_A);
        update(c, "INSERT INTO contact_ai_summary (ai_summary_id, contact_id, tenant_id, summary, model, generated_at) "
                        + "VALUES (?, ?, ?, 'podsumowanie rls', 'gpt-test', now())", AI_SUMMARY_RLS, CONTACT_RLS, TENANT_A);
        update(c, "INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, scheduled_at, status, source_type) "
                        + "VALUES (?, ?, ?, ?, now() + interval '1 day', 'PENDING', 'AGENT_MANUAL')",
                CALLBACK_RLS, TENANT_A, CUSTOMER_RLS, RLS_PHONE);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'DB-062 RLS')", CAMPAIGN_RLS, TENANT_A);
        update(c, "INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone, first_name, last_name, "
                        + "custom_fields, status, attempt_count) VALUES (?, ?, ?, ?, ?, 'RLS', 'Kontakt', '{}'::jsonb, 'PENDING', 0)",
                CC_RLS, CAMPAIGN_RLS, TENANT_A, CUSTOMER_RLS, RLS_PHONE);
        update(c, "INSERT INTO campaign_contact_archive (record_id, campaign_id, tenant_id, customer_id, phone, first_name, "
                        + "last_name, custom_fields, status, attempt_count, created_at, archived_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'RLS', 'Archiwum', '{}'::jsonb, 'COMPLETED', 1, now(), now())",
                CCA_RLS, CAMPAIGN_RLS, TENANT_A, CUSTOMER_RLS, RLS_PHONE);
        update(c, "INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, subject, sent_at, delivery_status) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'agent@firma.pl', 'rls@example.com', 'RLS temat', now(), 'SENT')",
                EMAIL_RLS, TENANT_A, CONTACT_RLS);
        update(c, "INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, "
                        + "sender_external_id, content, sent_at) VALUES (?, ?, ?, 'WHATSAPP', 'INBOUND', 'wa-ext-rls-1', "
                        + "'wa-sender-rls-1', 'tresc rls', now())",
                SOCIAL_RLS, TENANT_A, CONTACT_RLS);
        update(c, "INSERT INTO contacts_dw (contact_id, tenant_id, channel, direction, status, started_at, queued_at, remote_address) "
                        + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', now(), now(), ?)",
                CONTACT_RLS, TENANT_A, RLS_PHONE);
    }

    // =========================================================================================
    // Pomocnicze
    // =========================================================================================

    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET LOCAL ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString());
    }

    private static List<String> jsonArrayToStrings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
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
