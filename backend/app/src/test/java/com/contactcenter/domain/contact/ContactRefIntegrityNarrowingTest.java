package com.contactcenter.domain.contact;

import com.contactcenter.support.TestcontainersSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla zawężenia
 * triggera {@code trg_contact_ref_integrity} / funkcji {@code fn_contact_ref_integrity()} (V016)
 * wprowadzonego migracją V094 (DB-079).
 *
 * <p><strong>Problem (DB-060 F1):</strong> trigger jest {@code BEFORE INSERT OR UPDATE FOR EACH ROW}
 * bez listy kolumn. Dla {@code NEW.customer_id IS NOT NULL} wymagał klienta z {@code is_deleted = FALSE},
 * analogicznie agenta — przy KAŻDYM UPDATE, także niezwiązanym z referencjami. Po soft-delete /
 * anonimizacji klienta albo dezaktywacji agenta każdy {@code UPDATE contact} (w tym
 * {@code ContactRepository#clearRecordingUrl} wołany z {@code RecordingRetentionJob} oraz
 * {@code UPDATE contact} wewnątrz {@code anonymize_customer}) kończył się wyjątkiem
 * {@code contact: customer_id … nie istnieje …}.
 *
 * <p><strong>Metoda — dowód działaniem, nie papierowo:</strong> jeden kontener, dwie bazy o TYCH SAMYMI
 * danych. Baza „pre" jest migrowana do wersji BEZPOŚREDNIO PRZED migracją DB-079 (w chwili pisania testu
 * była to V093), zasilana fixture'em (kontakty tworzone, gdy klient/agent jeszcze istnieją, dopiero potem
 * {@code is_deleted = TRUE} — tak jak w produkcji) i na niej pozostaje: testy {@code v093_*} dokumentują
 * błąd na starej funkcji (prefiks {@code v093_} to nazwa historyczna, zachowana, bo odwołują się do niej
 * tickety — dziś oznacza „stan sprzed DB-079", nie stały numer). Baza „post" powstaje jako kopia bazy
 * „pre" ({@code CREATE DATABASE … TEMPLATE}) i jest migrowana do najnowszej wersji — czyli migracja
 * DB-079 jest stosowana do bazy z istniejącymi danymi. Testy bez prefiksu {@code v093_} sprawdzają
 * zachowanie po migracji.
 *
 * <p><strong>Odporność na renumerację:</strong> test nie zna żadnego numeru migracji. Migrację DB-079
 * rozpoznaje po opisie ({@link #NARROWING_MIGRATION_DESCRIPTION}), a wersję „przed" wyznacza przez
 * {@code Flyway#info()} jako wersję bezpośrednio poprzedzającą ją w łańcuchu. Gdy migracji DB-079 nie ma
 * w łańcuchu (zgubiony plik po błędnie rozwiązanym konflikcie numeracji, zmieniony opis), test PADA:
 * {@link #postDatabaseHasNarrowingMigrationApplied()} zgłasza brak migracji, a testy zachowania padają
 * na starej funkcji (baza „pre" jest wtedy migrowana do najnowszej wersji, żeby reszta klasy dała
 * diagnostyczny wynik zamiast jednego błędu inicjalizacji).
 *
 * <p>Każda próba zapisu wykonuje się w osobnej transakcji, która ZAWSZE jest wycofywana — baza
 * pozostaje w stanie fixture'a między testami. Negatywne asercje sprawdzają SQLState {@code P0001}
 * i treść komunikatu triggera, żeby odrzucenie nie było „z innego powodu" (CHECK, NOT NULL, RLS).
 *
 * <p>Nie używamy mocków: błędów SQL/triggerów nie łapie {@code EntityManager} ani {@code JdbcTemplate}
 * z Mockito (lekcja EPIC-29).
 */
@Testcontainers
@DisplayName("fn_contact_ref_integrity – UPDATE bez zmiany referencji nie jest blokowany (V094, DB-079)")
class ContactRefIntegrityNarrowingTest {

    static {
        // Wspólne obejście negocjacji wersji API Dockera (Testcontainers 1.20.4) — przed startem kontenera.
        TestcontainersSupport.ensureDockerApiVersion();
    }

    /**
     * Opis migracji DB-079 w Flyway = nazwa pliku {@code V0xx__narrow_contact_ref_integrity_on_update.sql}
     * bez numeru i prefiksu, z {@code _} zamienionymi na spacje. Po nim (a nie po numerze) test rozpoznaje
     * migrację — numer może się zmienić przy scalaniu gałęzi.
     */
    private static final String NARROWING_MIGRATION_DESCRIPTION = "narrow contact ref integrity on update";

    private static final String PRE_DB = "cc_pre_db079";
    private static final String POST_DB = "cc_post_db079";
    private static final String USER = "cc_test";
    private static final String PASSWORD = "cc_test";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName(PRE_DB)
                    .withUsername(USER)
                    .withPassword(PASSWORD);

    // ---- fixture -------------------------------------------------------------------------------

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    /** Klient z is_deleted = TRUE (ustawione PO utworzeniu jego kontaktów). */
    private static final UUID CUSTOMER_DELETED = UUID.randomUUID();
    private static final UUID CUSTOMER_ALIVE = UUID.randomUUID();
    private static final UUID CUSTOMER_OF_TENANT_B = UUID.randomUUID();
    /** Żywy klient z 2 kontaktami — dla anonymize_customer. */
    private static final UUID CUSTOMER_TO_ANONYMIZE = UUID.randomUUID();
    /** Agent z is_deleted = TRUE (ustawione PO utworzeniu jego kontaktów). */
    private static final UUID AGENT_DEACTIVATED = UUID.randomUUID();
    private static final UUID AGENT_ALIVE = UUID.randomUUID();
    private static final UUID QUEUE = UUID.randomUUID();
    private static final UUID CAMPAIGN = UUID.randomUUID();
    /** Identyfikator nieistniejący w żadnej tabeli referencyjnej. */
    private static final UUID MISSING = UUID.randomUUID();

    /** Dwie partycje miesięczne utworzone przez create_contact_partition oraz contact_default. */
    private static final OffsetDateTime STARTED_MONTH_1 = OffsetDateTime.of(2027, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime STARTED_MONTH_2 = OffsetDateTime.of(2027, 2, 15, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime STARTED_DEFAULT = OffsetDateTime.of(2001, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);

    private record Placed(UUID contactId, OffsetDateTime startedAt, String partition) {
    }

    private static final List<Placed> CONTACTS_OF_DELETED_CUSTOMER = List.of(
            new Placed(UUID.randomUUID(), STARTED_MONTH_1, "contact_2027_01"),
            new Placed(UUID.randomUUID(), STARTED_MONTH_2, "contact_2027_02"),
            new Placed(UUID.randomUUID(), STARTED_DEFAULT, "contact_default"));

    private static final List<Placed> CONTACTS_OF_DEACTIVATED_AGENT = List.of(
            new Placed(UUID.randomUUID(), STARTED_MONTH_1, "contact_2027_01"),
            new Placed(UUID.randomUUID(), STARTED_MONTH_2, "contact_2027_02"),
            new Placed(UUID.randomUUID(), STARTED_DEFAULT, "contact_default"));

    /** Kontakt bez referencji — cel prób „zmiany referencji". */
    private static final Placed PLAIN_CONTACT = new Placed(UUID.randomUUID(), STARTED_MONTH_1, "contact_2027_01");
    /** Kontakt żywego klienta — cel prób „zmiany customer_id na usuniętego". */
    private static final Placed CONTACT_OF_ALIVE_CUSTOMER = new Placed(UUID.randomUUID(), STARTED_MONTH_2, "contact_2027_02");

    /** Migracja DB-079 i wersja tuż przed nią; null, gdy migracji nie ma w łańcuchu (test wtedy pada — patrz Javadoc klasy). */
    private static NarrowingMigration narrowingMigration;

    @BeforeAll
    static void migrateAndSeed() throws Exception {
        narrowingMigration = findNarrowingMigration();

        // 1) Schemat sprzed DB-079: łańcuch Flyway do wersji bezpośrednio poprzedzającej DB-079 włącznie
        //    (gdy migracji DB-079 nie ma w łańcuchu — do najnowszej; guard w postDatabaseHasNarrowingMigrationApplied).
        FluentConfiguration pre = flyway(PRE_DB);
        if (narrowingMigration != null) {
            pre.target(narrowingMigration.previousVersion());
        }
        pre.load().migrate();

        // 2) Fixture — kontakty powstają przy żywych klientach/agentach; potem soft-delete.
        try (Connection c = connect(PRE_DB)) {
            seedFixture(c);
        }

        // 3) Baza 'post' = kopia bazy 'pre' (te same dane, schemat sprzed DB-079) + migracje do najnowszej.
        try (Connection admin = connect("postgres")) {
            update(admin, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = ? AND pid <> pg_backend_pid()", PRE_DB);
            update(admin, "CREATE DATABASE " + POST_DB + " TEMPLATE " + PRE_DB);
        }
        flyway(POST_DB).load().migrate();
    }

    // =========================================================================================
    // Dowód działaniem NA STARYM SCHEMACIE (sprzed DB-079): błąd DB-060 F1 / DB-079 (1)-(3)
    // =========================================================================================

    @Test
    @DisplayName("przed DB-079 (stara funkcja V016): UPDATE recording_url kontaktu usuniętego klienta (ścieżka clearRecordingUrl) rzuca 'contact: customer_id … nie istnieje' w każdej partycji")
    void v093_updateOfContactOfDeletedCustomer_isBlocked() throws Exception {
        for (Placed p : CONTACTS_OF_DELETED_CUSTOMER) {
            SQLException error = inRolledBackTx(PRE_DB, c -> failureOf(c,
                    "UPDATE contact SET recording_url = NULL, updated_at = NOW() WHERE contact_id = ? AND tenant_id = ?",
                    p.contactId(), TENANT_A));
            assertRejectedByTrigger(error, "customer_id", CUSTOMER_DELETED);
        }
    }

    @Test
    @DisplayName("przed DB-079 (stara funkcja V016): UPDATE notatki kontaktu dezaktywowanego agenta (agent_id bez zmiany) rzuca 'contact: agent_id … nie istnieje'")
    void v093_updateOfContactOfDeactivatedAgent_isBlocked() throws Exception {
        for (Placed p : CONTACTS_OF_DEACTIVATED_AGENT) {
            SQLException error = inRolledBackTx(PRE_DB, c -> failureOf(c,
                    "UPDATE contact SET notes = 'edycja' WHERE contact_id = ? AND tenant_id = ?",
                    p.contactId(), TENANT_A));
            assertRejectedByTrigger(error, "agent_id", AGENT_DEACTIVATED);
        }
    }

    @Test
    @DisplayName("przed DB-079 (stara funkcja V016): anonymize_customer (V013) nie działa dla klienta z kontaktami — trigger odrzuca UPDATE contact po is_deleted = TRUE")
    void v093_anonymizeCustomer_failsBecauseOfTrigger() throws Exception {
        SQLException error = inRolledBackTx(PRE_DB, c -> failureOf(c,
                "SELECT anonymize_customer(?, ?, NULL)", CUSTOMER_TO_ANONYMIZE, TENANT_A));

        assertThat((Throwable) error).as("anonymize_customer na schemacie sprzed DB-079 musi rzucić").isNotNull();
        assertThat(error.getMessage())
                .contains("Blad anonimizacji klienta " + CUSTOMER_TO_ANONYMIZE)
                .contains("contact: customer_id " + CUSTOMER_TO_ANONYMIZE + " nie istnieje");
    }

    // =========================================================================================
    // Po migracji DB-079 (najnowszy schemat, te same dane)
    // =========================================================================================

    @Test
    @DisplayName("łańcuch Flyway zawiera migrację DB-079 (rozpoznaną po opisie): zastosowana (SUCCESS) na bazie 'post', a baza 'pre' kończy się na wersji tuż przed nią")
    void postDatabaseHasNarrowingMigrationApplied() {
        assertThat(narrowingMigration)
                .as("migracja DB-079 (opis '%s') musi istnieć w łańcuchu Flyway na classpath, a nie być tylko 'jakąś nowszą' "
                        + "— brak oznacza zgubiony plik (np. po błędnie rozwiązanym konflikcie numeracji) albo zmieniony opis",
                        NARROWING_MIGRATION_DESCRIPTION)
                .isNotNull();

        MigrationInfo inPost = migration(POST_DB, narrowingMigration.version());
        assertThat(inPost.getDescription()).isEqualTo(NARROWING_MIGRATION_DESCRIPTION);
        assertThat(inPost.getState()).as("migracja DB-079 na bazie 'post'").isEqualTo(MigrationState.SUCCESS);

        MigrationInfo inPre = migration(PRE_DB, narrowingMigration.version());
        assertThat(inPre.getState()).as("migracja DB-079 NIE może być zastosowana na bazie 'pre'").isEqualTo(MigrationState.PENDING);
        MigrationInfo currentPre = flyway(PRE_DB).load().info().current();
        assertThat(currentPre).as("baza 'pre' ma zastosowane migracje").isNotNull();
        assertThat(currentPre.getVersion()).as("baza 'pre' kończy się na wersji bezpośrednio przed DB-079")
                .isEqualTo(narrowingMigration.previousVersion());
    }

    @Test
    @DisplayName("fixture obejmuje ≥ 2 partycje miesięczne i contact_default (test przez tabelę rodzica)")
    void fixtureSpansMonthlyPartitionsAndDefault() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            for (Placed p : concat(CONTACTS_OF_DELETED_CUSTOMER, CONTACTS_OF_DEACTIVATED_AGENT)) {
                assertThat(scalar(c, "SELECT tableoid::regclass::text FROM contact WHERE contact_id = ?", p.contactId()))
                        .isEqualTo(p.partition());
            }
            return null;
        });
    }

    @Test
    @DisplayName("(a) UPDATE kontaktu usuniętego klienta bez zmiany referencji przechodzi: recording_url, notes, remote_address, channel_metadata — w każdej partycji")
    void updateOfContactOfDeletedCustomer_passes() throws Exception {
        for (Placed p : CONTACTS_OF_DELETED_CUSTOMER) {
            inRolledBackTx(POST_DB, c -> {
                // ContactRepository#clearRecordingUrl
                assertThat(update(c, "UPDATE contact SET recording_url = NULL, updated_at = NOW() WHERE contact_id = ? AND tenant_id = ?",
                        p.contactId(), TENANT_A)).as("clearRecordingUrl w %s", p.partition()).isEqualTo(1);
                assertThat(scalar(c, "SELECT recording_url IS NULL FROM contact WHERE contact_id = ?", p.contactId())).isEqualTo("true");

                assertThat(update(c, "UPDATE contact SET notes = 'zmieniona notatka' WHERE contact_id = ? AND tenant_id = ?",
                        p.contactId(), TENANT_A)).isEqualTo(1);
                assertThat(scalar(c, "SELECT notes FROM contact WHERE contact_id = ?", p.contactId())).isEqualTo("zmieniona notatka");

                // wzorzec z anonymize_customer (V013): remote_address = NULL, channel_metadata = '{}'
                assertThat(update(c, "UPDATE contact SET remote_address = NULL, channel_metadata = '{}'::jsonb WHERE contact_id = ? AND tenant_id = ?",
                        p.contactId(), TENANT_A)).isEqualTo(1);
                assertThat(scalar(c, "SELECT remote_address IS NULL FROM contact WHERE contact_id = ?", p.contactId())).isEqualTo("true");
                assertThat(scalar(c, "SELECT channel_metadata::text FROM contact WHERE contact_id = ?", p.contactId())).isEqualTo("{}");
                return null;
            });
        }
    }

    @Test
    @DisplayName("(a) zbiorczy UPDATE po customer_id usuniętego klienta obejmuje wiersze ze wszystkich partycji")
    void bulkUpdateByDeletedCustomer_passesAcrossPartitions() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            assertThat(update(c, "UPDATE contact SET remote_address = NULL, channel_metadata = '{}'::jsonb WHERE customer_id = ? AND tenant_id = ?",
                    CUSTOMER_DELETED, TENANT_A)).isEqualTo(CONTACTS_OF_DELETED_CUSTOMER.size());
            assertThat(scalar(c, "SELECT count(*) FROM contact WHERE customer_id = ? AND remote_address IS NOT NULL", CUSTOMER_DELETED))
                    .isEqualTo("0");
            return null;
        });
    }

    @Test
    @DisplayName("(e) UPDATE kontaktu dezaktywowanego agenta bez zmiany agent_id przechodzi (także kształt ContactRepository#update: agent_id = ta sama wartość)")
    void updateOfContactOfDeactivatedAgent_passes() throws Exception {
        for (Placed p : CONTACTS_OF_DEACTIVATED_AGENT) {
            inRolledBackTx(POST_DB, c -> {
                assertThat(update(c, "UPDATE contact SET notes = 'edycja' WHERE contact_id = ? AND tenant_id = ?",
                        p.contactId(), TENANT_A)).as("notes w %s", p.partition()).isEqualTo(1);

                // ContactRepository#update ustawia agent_id w KAŻDYM UPDATE — także na tę samą wartość.
                assertThat(update(c, """
                        UPDATE contact SET
                            agent_id         = CAST(? AS uuid),
                            status           = CAST('COMPLETED' AS VARCHAR),
                            remote_address   = NULL,
                            notes            = 'po zakonczeniu',
                            channel_metadata = CAST('{}' AS jsonb)
                        WHERE contact_id = CAST(? AS uuid)
                          AND tenant_id  = CAST(? AS uuid)
                          AND started_at = ?
                        """, AGENT_DEACTIVATED.toString(), p.contactId().toString(), TENANT_A.toString(), p.startedAt()))
                        .as("ksztalt ContactRepository#update w %s", p.partition()).isEqualTo(1);
                assertThat(scalar(c, "SELECT notes FROM contact WHERE contact_id = ?", p.contactId())).isEqualTo("po zakonczeniu");
                return null;
            });
        }
        inRolledBackTx(POST_DB, c -> {
            assertThat(update(c, "UPDATE contact SET notes = 'x' WHERE agent_id = ? AND tenant_id = ?", AGENT_DEACTIVATED, TENANT_A))
                    .isEqualTo(CONTACTS_OF_DEACTIVATED_AGENT.size());
            return null;
        });
    }

    @Test
    @DisplayName("(b) INSERT kontaktu z customer_id usuniętego klienta nadal odrzucony (partycje miesięczne i default)")
    void insertWithDeletedCustomer_isRejected() throws Exception {
        for (OffsetDateTime startedAt : List.of(STARTED_MONTH_1, STARTED_MONTH_2, STARTED_DEFAULT)) {
            SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                    "INSERT INTO contact (tenant_id, customer_id, channel, direction, status, started_at) "
                            + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                    TENANT_A, CUSTOMER_DELETED, startedAt));
            assertRejectedByTrigger(error, "customer_id", CUSTOMER_DELETED);
        }
    }

    @Test
    @DisplayName("INSERT kontaktu z agent_id dezaktywowanego agenta nadal odrzucony")
    void insertWithDeactivatedAgent_isRejected() throws Exception {
        SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                "INSERT INTO contact (tenant_id, agent_id, channel, direction, status, started_at) "
                        + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                TENANT_A, AGENT_DEACTIVATED, STARTED_MONTH_1));
        assertRejectedByTrigger(error, "agent_id", AGENT_DEACTIVATED);
    }

    @Test
    @DisplayName("(c) zmiana customer_id istniejącego kontaktu na usuniętego klienta (oraz na klienta innego tenanta) nadal odrzucona")
    void updateChangingCustomerIdToDeletedOrForeignCustomer_isRejected() throws Exception {
        for (Placed target : List.of(PLAIN_CONTACT, CONTACT_OF_ALIVE_CUSTOMER)) {
            SQLException toDeleted = inRolledBackTx(POST_DB, c -> failureOf(c,
                    "UPDATE contact SET customer_id = ? WHERE contact_id = ? AND tenant_id = ?",
                    CUSTOMER_DELETED, target.contactId(), TENANT_A));
            assertRejectedByTrigger(toDeleted, "customer_id", CUSTOMER_DELETED);
        }

        SQLException toForeign = inRolledBackTx(POST_DB, c -> failureOf(c,
                "UPDATE contact SET customer_id = ? WHERE contact_id = ? AND tenant_id = ?",
                CUSTOMER_OF_TENANT_B, PLAIN_CONTACT.contactId(), TENANT_A));
        assertRejectedByTrigger(toForeign, "customer_id", CUSTOMER_OF_TENANT_B);
    }

    @Test
    @DisplayName("zmiana agent_id istniejącego kontaktu na dezaktywowanego agenta nadal odrzucona")
    void updateChangingAgentIdToDeactivatedAgent_isRejected() throws Exception {
        SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                "UPDATE contact SET agent_id = ? WHERE contact_id = ? AND tenant_id = ?",
                AGENT_DEACTIVATED, PLAIN_CONTACT.contactId(), TENANT_A));
        assertRejectedByTrigger(error, "agent_id", AGENT_DEACTIVATED);
    }

    @Test
    @DisplayName("(d) INSERT i UPDATE z nieistniejącym agent_id / queue_id / campaign_id nadal odrzucone")
    void missingReferences_areStillRejected() throws Exception {
        for (String column : List.of("agent_id", "queue_id", "campaign_id")) {
            SQLException insertError = inRolledBackTx(POST_DB, c -> failureOf(c,
                    "INSERT INTO contact (tenant_id, " + column + ", channel, direction, status, started_at) "
                            + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                    TENANT_A, MISSING, STARTED_MONTH_1));
            assertRejectedByTrigger(insertError, column, MISSING);

            SQLException updateError = inRolledBackTx(POST_DB, c -> failureOf(c,
                    "UPDATE contact SET " + column + " = ? WHERE contact_id = ? AND tenant_id = ?",
                    MISSING, PLAIN_CONTACT.contactId(), TENANT_A));
            assertRejectedByTrigger(updateError, column, MISSING);
        }
    }

    @Test
    @DisplayName("zmiana tenant_id traktowana jak zmiana referencji: kontakt klienta tenanta A przeniesiony do tenanta B jest odrzucany")
    void updateChangingTenantId_isValidatedAsReferenceChange() throws Exception {
        Placed p = CONTACT_OF_ALIVE_CUSTOMER;
        SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                "UPDATE contact SET tenant_id = ? WHERE contact_id = ? AND tenant_id = ?",
                TENANT_B, p.contactId(), TENANT_A));
        assertRejectedByTrigger(error, "customer_id", CUSTOMER_ALIVE);
    }

    @Test
    @DisplayName("(f) UPDATE started_at przenoszący wiersz do INNEJ partycji (DELETE + INSERT) jest walidowany w pełni jak INSERT: z żywymi referencjami przechodzi, dla klienta / agenta z is_deleted = TRUE jest odrzucany (P0001)")
    void updateMovingRowToAnotherPartition_isFullyValidatedLikeInsert() throws Exception {
        // Żywe referencje (wszystkie cztery): przeniesienie m1 -> m2 -> default -> m1 przechodzi, wiersz ląduje w partycji docelowej.
        inRolledBackTx(POST_DB, c -> {
            UUID contactId = UUID.randomUUID();
            assertThat(update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, agent_id, queue_id, campaign_id, channel, direction, status, started_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                    contactId, TENANT_A, CUSTOMER_ALIVE, AGENT_ALIVE, QUEUE, CAMPAIGN, STARTED_MONTH_1)).isEqualTo(1);
            assertThat(scalar(c, "SELECT tableoid::regclass::text FROM contact WHERE contact_id = ?", contactId)).isEqualTo("contact_2027_01");

            for (Placed destination : List.of(
                    new Placed(contactId, STARTED_MONTH_2, "contact_2027_02"),
                    new Placed(contactId, STARTED_DEFAULT, "contact_default"),
                    new Placed(contactId, STARTED_MONTH_1, "contact_2027_01"))) {
                assertThat(update(c, "UPDATE contact SET started_at = ? WHERE contact_id = ? AND tenant_id = ?",
                        destination.startedAt(), contactId, TENANT_A))
                        .as("przeniesienie do %s", destination.partition()).isEqualTo(1);
                assertThat(scalar(c, "SELECT tableoid::regclass::text FROM contact WHERE contact_id = ?", contactId))
                        .as("partycja po przeniesieniu").isEqualTo(destination.partition());
            }
            return null;
        });

        // Klient z is_deleted = TRUE / agent z is_deleted = TRUE: przeniesienie do KAŻDEJ innej partycji odrzuca
        // BEFORE INSERT partycji docelowej (BEFORE UPDATE partycji źródłowej kończy się wczesnym RETURN).
        assertEveryCrossPartitionMoveRejected(CONTACTS_OF_DELETED_CUSTOMER, "customer_id", CUSTOMER_DELETED);
        assertEveryCrossPartitionMoveRejected(CONTACTS_OF_DEACTIVATED_AGENT, "agent_id", AGENT_DEACTIVATED);
    }

    @Test
    @DisplayName("granica (f): UPDATE started_at w obrębie TEJ SAMEJ partycji nie przenosi wiersza (zwykły UPDATE z wczesnym RETURN) — kontakt usuniętego klienta / dezaktywowanego agenta przechodzi")
    void updateOfStartedAtWithinSamePartition_isPlainUpdate_passes() throws Exception {
        for (Placed p : concat(CONTACTS_OF_DELETED_CUSTOMER, CONTACTS_OF_DEACTIVATED_AGENT)) {
            inRolledBackTx(POST_DB, c -> {
                assertThat(update(c, "UPDATE contact SET started_at = ? WHERE contact_id = ? AND tenant_id = ?",
                        p.startedAt().plusDays(1), p.contactId(), TENANT_A))
                        .as("started_at w obrębie %s", p.partition()).isEqualTo(1);
                assertThat(scalar(c, "SELECT tableoid::regclass::text FROM contact WHERE contact_id = ?", p.contactId()))
                        .as("wiersz nie zmienił partycji").isEqualTo(p.partition());
                return null;
            });
        }
    }

    @Test
    @DisplayName("kontrola pozytywna: poprawne INSERT i zmiany referencji na żywe obiekty tenanta nadal przechodzą")
    void validWrites_stillPass() throws Exception {
        for (OffsetDateTime startedAt : List.of(STARTED_MONTH_1, STARTED_DEFAULT)) {
            inRolledBackTx(POST_DB, c -> {
                assertThat(update(c, "INSERT INTO contact (tenant_id, customer_id, agent_id, queue_id, campaign_id, channel, direction, status, started_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                        TENANT_A, CUSTOMER_ALIVE, AGENT_ALIVE, QUEUE, CAMPAIGN, startedAt)).isEqualTo(1);
                return null;
            });
        }
        inRolledBackTx(POST_DB, c -> {
            assertThat(update(c, "UPDATE contact SET customer_id = ?, agent_id = ?, queue_id = ?, campaign_id = ? "
                            + "WHERE contact_id = ? AND tenant_id = ?",
                    CUSTOMER_ALIVE, AGENT_ALIVE, QUEUE, CAMPAIGN, PLAIN_CONTACT.contactId(), TENANT_A)).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("partycje dziedziczą trigger: każda partycja (w tym create_contact_partition(2027,1) i contact_default) ma kopię z tą samą funkcją; funkcja bez SECURITY DEFINER")
    void everyPartitionInheritsTheTrigger() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            // Nowa partycja utworzona po migracji dziedziczy trigger rodzica (bez DDL na triggerze).
            update(c, "SELECT create_contact_partition(2027, 3)");
            assertThat(scalar(c, "SELECT count(*) FROM pg_inherits WHERE inhparent = 'contact'::regclass")).isNotEqualTo("0");

            assertThat(scalar(c, """
                    SELECT count(*) FROM pg_inherits i
                    JOIN pg_class p ON p.oid = i.inhrelid
                    WHERE i.inhparent = 'contact'::regclass
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_trigger t
                          WHERE t.tgrelid = p.oid
                            AND t.tgname = 'trg_contact_ref_integrity'
                            AND t.tgfoid = 'fn_contact_ref_integrity'::regproc
                            AND t.tgenabled = 'O'
                            AND NOT t.tgisinternal)
                    """)).as("partycje BEZ kopii triggera").isEqualTo("0");

            for (String partition : List.of("contact_2027_01", "contact_2027_03", "contact_default")) {
                assertThat(scalar(c, "SELECT tgfoid::regproc::text FROM pg_trigger WHERE tgrelid = to_regclass(?) AND tgname = 'trg_contact_ref_integrity'",
                        partition)).as("trigger w %s", partition).isEqualTo("fn_contact_ref_integrity");
            }
            assertThat(scalar(c, "SELECT prosecdef FROM pg_proc WHERE proname = 'fn_contact_ref_integrity'")).isEqualTo("false");
            return null;
        });
    }

    @Test
    @DisplayName("nowa partycja (create_contact_partition) od razu ma zawężone zachowanie: UPDATE kontaktu usuniętego klienta przechodzi, INSERT jest odrzucany")
    void newlyCreatedPartition_hasNarrowedBehaviour() throws Exception {
        UUID contactId = UUID.randomUUID();
        SQLException insertError = inRolledBackTx(POST_DB, c -> {
            update(c, "SELECT create_contact_partition(2028, 5)");
            OffsetDateTime startedAt = OffsetDateTime.of(2028, 5, 10, 10, 0, 0, 0, ZoneOffset.UTC);

            // kontakt trafia do świeżej partycji, gdy klient jeszcze żyje — potem soft-delete
            update(c, "INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, recording_url, started_at) "
                    + "VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', 's3://x.mp3', ?)", contactId, TENANT_A, CUSTOMER_ALIVE, startedAt);
            assertThat(scalar(c, "SELECT tableoid::regclass::text FROM contact WHERE contact_id = ?", contactId)).isEqualTo("contact_2028_05");
            update(c, "UPDATE customer SET is_deleted = TRUE WHERE customer_id = ?", CUSTOMER_ALIVE);

            assertThat(update(c, "UPDATE contact SET recording_url = NULL WHERE contact_id = ? AND tenant_id = ?", contactId, TENANT_A))
                    .isEqualTo(1);
            return failureOf(c, "INSERT INTO contact (tenant_id, customer_id, channel, direction, status, started_at) "
                    + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)", TENANT_A, CUSTOMER_ALIVE, startedAt);
        });
        assertRejectedByTrigger(insertError, "customer_id", CUSTOMER_ALIVE);
    }

    @Test
    @DisplayName("pod SET ROLE app_user + GUC tenanta: walidacja INSERT działa jak dotąd (funkcja czyta customer/app_user/queue/campaign pod RLS)")
    void underAppUserRole_insertValidationBehavesAsBefore() throws Exception {
        // poprawny INSERT — referencje tenanta A widoczne dla app_user przez polityki SELECT
        inRolledBackTx(POST_DB, c -> {
            asAppUser(c, TENANT_A);
            assertThat(update(c, "INSERT INTO contact (tenant_id, customer_id, agent_id, queue_id, campaign_id, channel, direction, status, started_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)",
                    TENANT_A, CUSTOMER_ALIVE, AGENT_ALIVE, QUEUE, CAMPAIGN, STARTED_MONTH_1)).isEqualTo(1);
            return null;
        });

        // usunięty klient / dezaktywowany agent — odrzucenie przez trigger (nie przez RLS)
        SQLException deletedCustomer = inRolledBackTx(POST_DB, c -> {
            asAppUser(c, TENANT_A);
            return failureOf(c, "INSERT INTO contact (tenant_id, customer_id, channel, direction, status, started_at) "
                    + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)", TENANT_A, CUSTOMER_DELETED, STARTED_MONTH_1);
        });
        assertRejectedByTrigger(deletedCustomer, "customer_id", CUSTOMER_DELETED);

        SQLException deactivatedAgent = inRolledBackTx(POST_DB, c -> {
            asAppUser(c, TENANT_A);
            return failureOf(c, "INSERT INTO contact (tenant_id, agent_id, channel, direction, status, started_at) "
                    + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)", TENANT_A, AGENT_DEACTIVATED, STARTED_MONTH_1);
        });
        assertRejectedByTrigger(deactivatedAgent, "agent_id", AGENT_DEACTIVATED);

        // GUC innego tenanta: żywy klient tenanta A jest niewidoczny pod RLS -> trigger zamyka się bezpiecznie
        SQLException foreignGuc = inRolledBackTx(POST_DB, c -> {
            asAppUser(c, TENANT_B);
            return failureOf(c, "INSERT INTO contact (tenant_id, customer_id, channel, direction, status, started_at) "
                    + "VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?)", TENANT_A, CUSTOMER_ALIVE, STARTED_MONTH_1);
        });
        assertRejectedByTrigger(foreignGuc, "customer_id", CUSTOMER_ALIVE);

        // GUC innego tenanta, kontakt bez referencji: odrzuca polityka RLS (WITH CHECK), SQLState 42501
        SQLException rls = inRolledBackTx(POST_DB, c -> {
            asAppUser(c, TENANT_B);
            return failureOf(c, "INSERT INTO contact (tenant_id, channel, direction, status, started_at) "
                    + "VALUES (?, 'PHONE', 'INBOUND', 'COMPLETED', ?)", TENANT_A, STARTED_MONTH_1);
        });
        assertThat((Throwable) rls).isNotNull();
        assertThat(rls.getSQLState()).isEqualTo("42501");
    }

    @Test
    @DisplayName("po DB-079 anonymize_customer działa dla klienta z kontaktami: klient zanonimizowany, kontakty zachowane z wyzerowanym remote_address")
    void anonymizeCustomer_worksForCustomerWithContacts() throws Exception {
        inRolledBackTx(POST_DB, c -> {
            assertThat(scalar(c, "SELECT count(*) FROM contact WHERE customer_id = ? AND remote_address IS NOT NULL", CUSTOMER_TO_ANONYMIZE))
                    .as("kontakty klienta z remote_address przed anonimizacją").isEqualTo("2");

            // DB-062 (V096): sygnatura zmieniona na (UUID, UUID, UUID, BOOLEAN p_dry_run DEFAULT FALSE),
            // typ zwracany VOID -> JSONB. p_dry_run podane JAWNIE jako FALSE (nie polegamy na DEFAULT w
            // wywołaniu natywnym). Funkcja wywołana RAZ — wynik odczytany z podzapytania, żeby nie
            // wywołać jej drugi raz (druga inwokacja byłaby idempotentna i pokazałaby zera, nie realne liczniki).
            String contactCount = scalar(c,
                    "SELECT (j -> 'counts' ->> 'contact') FROM (SELECT anonymize_customer(?, ?, NULL, FALSE) AS j) t",
                    CUSTOMER_TO_ANONYMIZE, TENANT_A);
            assertThat(contactCount).as("anonymize_customer po DB-079/DB-062 nie rzuca i zwraca JSONB z licznikiem contact = 2")
                    .isEqualTo("2");

            assertThat(scalar(c, "SELECT is_deleted FROM customer WHERE customer_id = ?", CUSTOMER_TO_ANONYMIZE)).isEqualTo("true");
            assertThat(scalar(c, "SELECT count(*) FROM contact WHERE customer_id = ?", CUSTOMER_TO_ANONYMIZE))
                    .as("kontakty zachowane (statystyki)").isEqualTo("2");
            assertThat(scalar(c, "SELECT count(*) FROM contact WHERE customer_id = ? AND remote_address IS NULL", CUSTOMER_TO_ANONYMIZE))
                    .as("remote_address wyzerowany").isEqualTo("2");
            return null;
        });
    }

    // =========================================================================================
    // Fixture i pomocnicze
    // =========================================================================================

    private static void seedFixture(Connection c) throws SQLException {
        update(c, "INSERT INTO tenant (tenant_id, name) VALUES (?, 'Tenant A - DB-079 IT'), (?, 'Tenant B - DB-079 IT')", TENANT_A, TENANT_B);
        for (UUID customer : List.of(CUSTOMER_DELETED, CUSTOMER_ALIVE, CUSTOMER_TO_ANONYMIZE)) {
            update(c, "INSERT INTO customer (customer_id, tenant_id) VALUES (?, ?)", customer, TENANT_A);
        }
        update(c, "INSERT INTO customer (customer_id, tenant_id) VALUES (?, ?)", CUSTOMER_OF_TENANT_B, TENANT_B);
        update(c, "INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) VALUES (?, ?, 'AGENT', 'deactivated@db079.test', 'x')",
                AGENT_DEACTIVATED, TENANT_A);
        update(c, "INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) VALUES (?, ?, 'AGENT', 'alive@db079.test', 'x')",
                AGENT_ALIVE, TENANT_A);
        update(c, "INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, 'Kolejka DB-079')", QUEUE, TENANT_A);
        update(c, "INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania DB-079')", CAMPAIGN, TENANT_A);

        update(c, "SELECT create_contact_partition(2027, 1)");
        update(c, "SELECT create_contact_partition(2027, 2)");

        for (Placed p : CONTACTS_OF_DELETED_CUSTOMER) {
            insertContact(c, p, CUSTOMER_DELETED, null);
        }
        for (Placed p : CONTACTS_OF_DEACTIVATED_AGENT) {
            insertContact(c, p, null, AGENT_DEACTIVATED);
        }
        insertContact(c, PLAIN_CONTACT, null, null);
        insertContact(c, CONTACT_OF_ALIVE_CUSTOMER, CUSTOMER_ALIVE, null);
        insertContact(c, new Placed(UUID.randomUUID(), STARTED_MONTH_1, "contact_2027_01"), CUSTOMER_TO_ANONYMIZE, null);
        insertContact(c, new Placed(UUID.randomUUID(), STARTED_MONTH_2, "contact_2027_02"), CUSTOMER_TO_ANONYMIZE, null);

        // Soft-delete PO utworzeniu kontaktów — kolejność jak w produkcji (RODO / dezaktywacja).
        update(c, "UPDATE customer SET is_deleted = TRUE WHERE customer_id = ?", CUSTOMER_DELETED);
        update(c, "UPDATE app_user SET is_deleted = TRUE WHERE user_id = ?", AGENT_DEACTIVATED);
    }

    private static void insertContact(Connection c, Placed p, UUID customerId, UUID agentId) throws SQLException {
        update(c, """
                INSERT INTO contact (contact_id, tenant_id, customer_id, agent_id, channel, direction, status,
                                     remote_address, recording_url, notes, channel_metadata, started_at)
                VALUES (?, ?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED',
                        '+48500100200', 's3://recordings/x.mp3', 'notatka', '{"sip_call_id":"abc"}'::jsonb, ?)
                """, p.contactId(), TENANT_A, customerId, agentId, p.startedAt());
    }

    private static void asAppUser(Connection c, UUID tenantId) throws SQLException {
        update(c, "SET LOCAL ROLE app_user");
        scalar(c, "SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString());
    }

    private static void assertRejectedByTrigger(SQLException error, String column, UUID value) {
        assertThat((Throwable) error).as("oczekiwano odrzucenia przez trigger (%s = %s)", column, value).isNotNull();
        assertThat(error.getSQLState()).as("SQLState (RAISE EXCEPTION = P0001), komunikat: %s", error.getMessage()).isEqualTo("P0001");
        assertThat(error.getMessage()).contains("contact: " + column + " " + value + " nie istnieje");
    }

    /**
     * Przeniesienie (UPDATE started_at) każdego kontaktu grupy do KAŻDEJ innej partycji grupy musi odrzucić trigger
     * (P0001 + komunikat o danej referencji). Docelowy started_at = dzień później niż fixture w partycji docelowej.
     */
    private static void assertEveryCrossPartitionMoveRejected(List<Placed> group, String column, UUID value) throws SQLException {
        for (Placed source : group) {
            for (Placed destination : group) {
                if (destination.partition().equals(source.partition())) {
                    continue;
                }
                SQLException error = inRolledBackTx(POST_DB, c -> failureOf(c,
                        "UPDATE contact SET started_at = ? WHERE contact_id = ? AND tenant_id = ?",
                        destination.startedAt().plusDays(3), source.contactId(), TENANT_A));
                assertThat((Throwable) error)
                        .as("przeniesienie %s -> %s musi odrzucić trigger (%s = %s)", source.partition(), destination.partition(), column, value)
                        .isNotNull();
                assertRejectedByTrigger(error, column, value);
            }
        }
    }

    @FunctionalInterface
    private interface TxBody<T> {
        T run(Connection c) throws SQLException;
    }

    /** Wykonuje ciało w transakcji, która jest ZAWSZE wycofywana — stan bazy nie zmienia się między próbami. */
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
                // getObject: boolean -> "true"/"false" (getString zwraca dla boolean "t"/"f")
                return String.valueOf(rs.getObject(1));
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Wersja migracji DB-079 i wersja BEZPOŚREDNIO ją poprzedzająca w łańcuchu Flyway. */
    private record NarrowingMigration(MigrationVersion version, MigrationVersion previousVersion) {
    }

    /**
     * Rozpoznaje migrację DB-079 po opisie w łańcuchu Flyway z classpath ({@code Flyway#info()} — bez żadnych
     * numerów na sztywno, wersje porównywane jako {@link MigrationVersion}, nie jako liczby) i zwraca jej wersję
     * oraz wersję bezpośrednio poprzednią. Zwraca null, gdy takiej migracji nie ma (albo jest pierwsza w łańcuchu).
     */
    private static NarrowingMigration findNarrowingMigration() {
        List<MigrationInfo> chain = Arrays.stream(flyway(PRE_DB).load().info().all())
                .filter(m -> m.getVersion() != null)
                .sorted(Comparator.comparing(MigrationInfo::getVersion))
                .toList();
        for (int i = 1; i < chain.size(); i++) {
            if (NARROWING_MIGRATION_DESCRIPTION.equals(chain.get(i).getDescription())) {
                return new NarrowingMigration(chain.get(i).getVersion(), chain.get(i - 1).getVersion());
            }
        }
        return null;
    }

    /** Stan migracji o danej wersji w bazie wg Flyway#info() (SUCCESS / PENDING / ...). */
    private static MigrationInfo migration(String db, MigrationVersion version) {
        return Arrays.stream(flyway(db).load().info().all())
                .filter(m -> version.equals(m.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("brak migracji " + version + " w Flyway#info() bazy " + db));
    }

    private static List<Placed> concat(List<Placed> a, List<Placed> b) {
        return Stream.concat(a.stream(), b.stream()).toList();
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
