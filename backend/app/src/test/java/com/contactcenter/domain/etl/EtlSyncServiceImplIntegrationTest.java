package com.contactcenter.domain.etl;

import com.contactcenter.infrastructure.etl.PostgresDwWriter;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla ścieżki
 * danych ETL {@code contact} → {@code contacts_dw} po BE-141.
 *
 * <p>W przeciwieństwie do {@link EtlSyncServiceImplTest} (czysto mockowy, weryfikuje orkiestrację:
 * {@code readAndLockSyncState}/{@code markDone}/{@code checkLagAndAlert}), ten test uruchamia
 * PRAWDZIWE {@code SELECT_CONTACTS_FOR_ETL} (przez pakietowo widoczną
 * {@link EtlSyncServiceImpl#fetchContactRows}) na rzeczywistym wierszu {@code contact} i
 * PRAWDZIWY {@link PostgresDwWriter#upsert}, dowodząc end-to-end, że:
 * <ol>
 *   <li>SQL zapytania nie odwołuje się już do {@code remote_address} (usunięte kolumna z SELECT –
 *       zapytanie mimo to zwraca poprawne wartości wszystkich pozostałych pól),</li>
 *   <li>{@link ContactDwRow} zmapowany z {@link java.sql.ResultSet} ma poprawne wartości,</li>
 *   <li>wiersz zapisany do {@code contacts_dw} ma te same wartości, a {@code remote_address}
 *       (PII źródłowego kontaktu – ustawiamy je explicite w seedzie) NIGDY nie trafia do DW.</li>
 * </ol>
 *
 * <p>Nie korzysta z globalnego {@code etl_sync_state} (dzielonego między klasami testowymi na
 * współdzielonym kontenerze) – zamiast pełnego {@code syncTable}, wywołuje bezpośrednio
 * {@code fetchContactRows} z punktem odcięcia ustawionym tuż przed seedem, więc wynik nie jest
 * zanieczyszczony danymi innych testów integracyjnych w tej samej JVM. Metoda jest pakietowo
 * widoczna celowo dla testów (Javadoc {@code EtlSyncServiceImpl}: „widoczne package dla testów").
 */
@DisplayName("ETL contact → contacts_dw – end-to-end bez remote_address (BE-141)")
class EtlSyncServiceImplIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static EtlSyncServiceImpl service;
    private static PostgresDwWriter dwWriter;

    private UUID contactIdToCleanUp;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        dwWriter = new PostgresDwWriter(jdbc);
        service = etl(dwWriter, false);
        // BE-213: existing tests insert and fetch immediately - disable the safety window for them
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
    }

    /**
     * Service on the shared DB. The BE-213 tests run with the open-transaction guard OFF (foreign open write
     * transactions of other tests on the shared DB would make them flaky); only the BE-216 tests turn it on.
     */
    private static EtlSyncServiceImpl etl(DataWarehouseWriter writer, boolean guard) {
        EtlSyncServiceImpl impl = new EtlSyncServiceImpl(jdbc, writer, mock(RabbitTemplate.class),
                mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(impl, "openTransactionGuardEnabled", guard);
        return impl;
    }

    /** All rows after the cut-off (no cursor), as the first CDC batch would see them. */
    private static List<ContactDwRow> fetchContacts(Instant cutoff) {
        return service.fetchContactRows(cutoff, cutoff, EtlSyncServiceImpl.MIN_UUID, 50_000).stream()
                .map(EtlSyncServiceImpl.CdcRow::row).toList();
    }

    @AfterAll
    static void stopContext() {
        pool.close();
    }

    @AfterEach
    void cleanUp() {
        if (contactIdToCleanUp != null) {
            jdbc.update("DELETE FROM contact WHERE contact_id = ?", contactIdToCleanUp);
            jdbc.update("DELETE FROM contacts_dw WHERE contact_id = ?", contactIdToCleanUp);
        }
    }

    @Test
    @DisplayName("kontakt w pełni wypełniony: fetchContactRows + upsert zapisują poprawne wartości, remote_address nie trafia do DW")
    void fullyPopulatedContact_etlRoundTrip_remoteAddressNeverReachesDw() {
        UUID contactId = UUID.randomUUID();
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-141 ETL " + UUID.randomUUID());
        UUID agentId = UUID.randomUUID();
        UUID queueId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        // fn_contact_ref_integrity (V016) wymaga, by agent_id/queue_id/campaign_id istniały
        // i należały do tego samego tenanta – minimalne wiersze nadrzędne.
        jdbc.update("INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) " +
                "VALUES (?, ?, 'AGENT', ?, 'x')", agentId, tenantId, "agent-be141-" + agentId + "@example.com");
        jdbc.update("INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, 'Kolejka BE-141')", queueId, tenantId);
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania BE-141')", campaignId, tenantId);

        Instant queuedAt = Instant.now().minus(90, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant startedAt = queuedAt.plusSeconds(15);
        Instant endedAt = startedAt.plusSeconds(245);
        contactIdToCleanUp = contactId;

        Instant cutoff = Instant.now().minusMillis(200);

        insertContact(contactId, tenantId, agentId, queueId, campaignId,
                "EMAIL", "OUTBOUND", "COMPLETED", "CALLBACK", 245,
                queuedAt, startedAt, endedAt, "klient-pii@example.com");

        List<ContactDwRow> fetched = fetchContacts(cutoff);
        ContactDwRow row = fetched.stream()
                .filter(r -> contactId.equals(r.contactId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seedowany kontakt nie został pobrany przez SELECT_CONTACTS_FOR_ETL"));

        assertThat(row.tenantId()).isEqualTo(tenantId);
        assertThat(row.agentId()).isEqualTo(agentId);
        assertThat(row.queueId()).isEqualTo(queueId);
        assertThat(row.campaignId()).isEqualTo(campaignId);
        assertThat(row.channel()).isEqualTo("EMAIL");
        assertThat(row.direction()).isEqualTo("OUTBOUND");
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.dispositionCode()).isEqualTo("CALLBACK");
        assertThat(row.durationSec()).isEqualTo(245);
        assertThat(row.startedAt()).isEqualTo(startedAt);
        assertThat(row.endedAt()).isEqualTo(endedAt);
        assertThat(row.queuedAt()).isEqualTo(queuedAt);
        // ContactDwRow (od BE-141) nie ma już komponentu remoteAddress — brak odpowiedniego
        // akcesora jest dowodem na poziomie kompilacji, że PII nie jest już niesione w DTO.

        dwWriter.upsert(List.of(row));

        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT * FROM contacts_dw WHERE contact_id = ?", contactId);
        assertThat(saved.get("channel")).isEqualTo("EMAIL");
        assertThat(saved.get("status")).isEqualTo("COMPLETED");
        assertThat(saved.get("disposition_code")).isEqualTo("CALLBACK");
        assertThat(saved.get("duration_sec")).isEqualTo(245);
        assertThat(((Timestamp) saved.get("started_at")).toInstant()).isEqualTo(startedAt);
        assertThat(((Timestamp) saved.get("ended_at")).toInstant()).isEqualTo(endedAt);
        assertThat(saved.get("remote_address"))
                .as("contact.remote_address źródłowy = 'klient-pii@example.com', ale ETL nigdy go nie kopiuje do DW")
                .isNull();
    }

    @Test
    @DisplayName("kontakt minimalny (ABANDONED, pola nullable puste): wartości nullable przechodzą jako NULL")
    void minimalAbandonedContact_nullableFieldsPassThroughAsNull() {
        UUID contactId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant queuedAt = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant startedAt = queuedAt.plusSeconds(5);
        contactIdToCleanUp = contactId;

        Instant cutoff = Instant.now().minusMillis(200);

        insertContact(contactId, tenantId, null, null, null,
                "PHONE", "INBOUND", "ABANDONED", null, null,
                queuedAt, startedAt, null, "+48500600700");

        List<ContactDwRow> fetched = fetchContacts(cutoff);
        ContactDwRow row = fetched.stream()
                .filter(r -> contactId.equals(r.contactId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seedowany kontakt nie został pobrany przez SELECT_CONTACTS_FOR_ETL"));

        assertThat(row.tenantId()).isEqualTo(tenantId);
        assertThat(row.agentId()).isNull();
        assertThat(row.queueId()).isNull();
        assertThat(row.campaignId()).isNull();
        assertThat(row.dispositionCode()).isNull();
        assertThat(row.durationSec()).isNull();
        assertThat(row.endedAt()).isNull();
        assertThat(row.status()).isEqualTo("ABANDONED");

        dwWriter.upsert(List.of(row));

        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT * FROM contacts_dw WHERE contact_id = ?", contactId);
        assertThat(saved.get("agent_id")).isNull();
        assertThat(saved.get("queue_id")).isNull();
        assertThat(saved.get("campaign_id")).isNull();
        assertThat(saved.get("ended_at")).isNull();
        assertThat(saved.get("duration_sec")).isNull();
        assertThat(saved.get("remote_address")).isNull();
    }

    @Test
    @DisplayName("BE-212: brak zmian po znaczniku CDC -> lag 0 (SQL lagu dziala na prawdziwym PG dla obu tabel)")
    void dataLag_noChangesAfterCdcPosition_isZero() {
        Instant futureCutoff = Instant.now().plus(1, ChronoUnit.DAYS);

        assertThat(service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, futureCutoff)).isZero();
        assertThat(service.computeDataLagMinutes(EtlSyncService.TABLE_CAMPAIGN_CONTACT, futureCutoff)).isZero();
    }

    /*
     * Isolation on the shared DB: the lag query is cross-tenant, so foreign rows cannot be excluded. Our changes are
     * placed 90 min in the past and the CDC cutoff 95 min in the past; rows of other tests are (practically) younger,
     * so "oldest pending" is our row when it counts, and strictly younger than 90 min when it must not count.
     */
    private static final long CHANGE_AGE_MIN = 90;
    private static final long CUTOFF_AGE_MIN = 95;

    private Timestamp changedAt() {
        return Timestamp.from(Instant.now().minus(CHANGE_AGE_MIN, ChronoUnit.MINUTES));
    }

    private Instant cutoff() {
        return Instant.now().minus(CUTOFF_AGE_MIN, ChronoUnit.MINUTES);
    }

    @Test
    @DisplayName("BE-212: zalegla zmiana kontaktu COMPLETED -> lag = jej wiek")
    void dataLag_completedContactCountsWithItsAge() {
        UUID contactId = UUID.randomUUID();
        contactIdToCleanUp = contactId;
        insertContactWithUpdatedAt(contactId, UUID.randomUUID(), "COMPLETED", Instant.now().minus(3, ChronoUnit.HOURS),
                changedAt());

        long lag = service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, cutoff());

        assertThat(lag).isBetween(CHANGE_AGE_MIN - 1, CHANGE_AGE_MIN + 6);
    }

    @Test
    @DisplayName("BE-212: kontakt w statusie roboczym (QUEUED/ACTIVE/ON_HOLD) nie liczy sie do lagu (jak fetch CDC)")
    void dataLag_workingStatusesAreIgnored() {
        for (String status : List.of("QUEUED", "ACTIVE", "ON_HOLD")) {
            UUID contactId = UUID.randomUUID();
            contactIdToCleanUp = contactId;
            insertContactWithUpdatedAt(contactId, UUID.randomUUID(), status, Instant.now().minus(3, ChronoUnit.HOURS),
                    changedAt());

            long lag = service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, cutoff());

            // without the status filter the lag would be >= 90; foreign rows are younger
            assertThat(lag).as("status %s", status).isLessThan(CHANGE_AGE_MIN - 1);
            jdbc.update("DELETE FROM contact WHERE contact_id = ?", contactId);
        }
    }

    @Test
    @DisplayName("BE-212: kontakt zanonimizowanego klienta nie liczy sie do lagu (jak fetch CDC)")
    void dataLag_anonymizedCustomerContactIsIgnored() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-212 anon " + UUID.randomUUID());
        UUID customerId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        contactIdToCleanUp = contactId;
        jdbc.update("INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email) "
                + "VALUES (?, ?, 'ANONYMIZED', 'X', '[]'::jsonb, '[]'::jsonb)", customerId, tenantId);
        try {
            jdbc.update("""
                    INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, queued_at, updated_at)
                    VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, ?)
                    """, contactId, tenantId, customerId,
                    Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS)), changedAt());

            long lag = service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, cutoff());

            assertThat(lag).isLessThan(CHANGE_AGE_MIN - 1);
        } finally {
            jdbc.update("DELETE FROM contact WHERE contact_id = ?", contactId);
            jdbc.update("DELETE FROM customer WHERE customer_id = ?", customerId);
        }
    }

    @Test
    @DisplayName("BE-212: realny wiersz campaign_contact (JOIN campaign) daje niezerowy lag = jego wiek")
    void dataLag_campaignContactRowCountsWithItsAge() {
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-212 cc " + UUID.randomUUID());
        UUID campaignId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania BE-212')",
                campaignId, tenantId);
        try {
            jdbc.update("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status, updated_at)
                    VALUES (?, ?, ?, '+48500100300', 'Jan', 'COMPLETED', ?)
                    """, recordId, campaignId, tenantId, changedAt());

            long lag = service.computeDataLagMinutes(EtlSyncService.TABLE_CAMPAIGN_CONTACT, cutoff());

            assertThat(lag).isBetween(CHANGE_AGE_MIN - 1, CHANGE_AGE_MIN + 6);
        } finally {
            jdbc.update("DELETE FROM campaign_contact WHERE record_id = ?", recordId);
            jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", campaignId);
        }
    }

    // =========================================================================
    // BE-213: CDC loop on a real database
    // =========================================================================

    /*
     * Isolation on the shared DB: etl_sync_state is global, so the tests below save/restore it and seed their rows
     * with updated_at in 2001 (older than anything else -> fetched first, in a deterministic order). contact is
     * partitioned by queued_at, hence queued_at stays "now"; the trigger trg_contact_on_update (V007) only fires on
     * UPDATE, so updated_at set on INSERT is preserved.
     */
    private static final Instant OLD_BASE = Instant.parse("2001-01-01T00:00:00Z");

    private Timestamp savedContactMarker;
    private Timestamp savedCampaignMarker;
    private UUID cdcTenant;
    private UUID cdcCampaign;

    private void saveMarkers() {
        savedContactMarker = jdbc.queryForObject(
                "SELECT last_synced_at FROM etl_sync_state WHERE table_name = 'contact'", Timestamp.class);
        savedCampaignMarker = jdbc.queryForObject(
                "SELECT last_synced_at FROM etl_sync_state WHERE table_name = 'campaign_contact'", Timestamp.class);
        cdcTenant = UUID.randomUUID();
    }

    private void setMarker(String table, Instant marker) {
        jdbc.update("UPDATE etl_sync_state SET last_synced_at = ?, status = 'DONE' WHERE table_name = ?",
                Timestamp.from(marker), table);
    }

    /** PostgreSQL timestamptz keeps microseconds and rounds (half up) - not truncates - sub-microsecond input. */
    private static Instant roundToMicros(Instant i) {
        return i.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    private Instant marker(String table) {
        return jdbc.queryForObject("SELECT last_synced_at FROM etl_sync_state WHERE table_name = ?",
                Timestamp.class, table).toInstant();
    }

    @AfterEach
    void restoreCdcState() {
        if (cdcTenant == null) {
            return;
        }
        jdbc.update("DELETE FROM contact WHERE tenant_id = ?", cdcTenant);
        jdbc.update("DELETE FROM contacts_dw WHERE tenant_id = ?", cdcTenant);
        if (cdcCampaign != null) {
            jdbc.update("DELETE FROM campaign_contact WHERE campaign_id = ?", cdcCampaign);
            jdbc.update("DELETE FROM campaign WHERE campaign_id = ?", cdcCampaign);
        }
        jdbc.update("UPDATE etl_sync_state SET last_synced_at = ?, status = 'DONE' WHERE table_name = 'contact'",
                savedContactMarker);
        jdbc.update("UPDATE etl_sync_state SET last_synced_at = ?, status = 'DONE' WHERE table_name = 'campaign_contact'",
                savedCampaignMarker);
        ReflectionTestUtils.setField(service, "fetchLimit", 5000);
        ReflectionTestUtils.setField(service, "maxBatchesPerCycle", 20);
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", false);
        cdcTenant = null;
        cdcCampaign = null;
    }

    /** n COMPLETED contacts; row i has updated_at = OLD_BASE + (tieGroupSeconds ? 0 : i) seconds. */
    private void seedContacts(int n, boolean sameTimestamp) {
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at, updated_at)
                SELECT gen_random_uuid(), ?, 'PHONE', 'INBOUND', 'COMPLETED', NOW(),
                       CAST(? AS TIMESTAMPTZ) + (CASE WHEN ? THEN 0 ELSE i END) * INTERVAL '1 second'
                FROM generate_series(1, ?) AS i
                """, cdcTenant, Timestamp.from(OLD_BASE), sameTimestamp, n);
    }

    /** Recent COMPLETED contacts (updated_at ~ now) - simulate rows left behind by other tests; cleaned in @AfterEach. */
    private void seedForeignRecentContacts(int n) {
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at, updated_at)
                SELECT gen_random_uuid(), ?, 'PHONE', 'INBOUND', 'COMPLETED', NOW(), NOW() - INTERVAL '5 minutes'
                FROM generate_series(1, ?) AS i
                """, cdcTenant, n);
    }

    private int dwCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contacts_dw WHERE tenant_id = ?", Integer.class, cdcTenant);
    }

    @Test
    @DisplayName("BE-213: backlog > limit - petla w cyklu zapisuje WSZYSTKIE wiersze, znacznik >= najnowszy zapisany")
    void backlogLargerThanLimit_allRowsReachDw() {
        saveMarkers();
        setMarker("contact", OLD_BASE.minusSeconds(10));
        ReflectionTestUtils.setField(service, "fetchLimit", 100);
        seedContacts(350, false);

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).isEqualTo(350);
        assertThat(marker("contact")).isAfterOrEqualTo(OLD_BASE.plusSeconds(350));
    }

    @Test
    @DisplayName("BE-213: limit batchy w cyklu - znacznik = ostatni kompletny timestamp, reszta schodzi w kolejnym cyklu")
    void batchBudget_markerAtLastCompleteTimestamp_restInNextCycle() {
        saveMarkers();
        setMarker("contact", OLD_BASE.minusSeconds(10));
        ReflectionTestUtils.setField(service, "fetchLimit", 100);
        ReflectionTestUtils.setField(service, "maxBatchesPerCycle", 2);
        seedContacts(350, false);

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).isEqualTo(200);
        // row 200 closes a full batch -> marker = ts of row 199
        assertThat(marker("contact")).isEqualTo(OLD_BASE.plusSeconds(199));

        ReflectionTestUtils.setField(service, "maxBatchesPerCycle", 100);
        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).isEqualTo(350);
    }

    @Test
    @DisplayName("BE-213: remis znacznika wiekszy niz limit (jedna transakcja) - brak pominiec i brak nieskonczonej petli")
    void tieGroupLargerThanLimit_noLossNoInfiniteLoop() {
        saveMarkers();
        setMarker("contact", OLD_BASE.minusSeconds(10));
        ReflectionTestUtils.setField(service, "fetchLimit", 100);
        ReflectionTestUtils.setField(service, "maxBatchesPerCycle", 1);
        seedContacts(250, true);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(60),
                () -> service.syncTable(EtlSyncService.TABLE_CONTACT));

        assertThat(dwCount()).isEqualTo(250);
        // the budget did not stop the loop inside the tie group; marker moved past it
        assertThat(marker("contact")).isAfterOrEqualTo(OLD_BASE);
    }

    @Test
    @DisplayName("BE-213: okno bezpieczenstwa - wiersze w oknie i 'spozniony commit' (starszy timestamp) trafiaja do DW, gdy okno minie")
    void rowInSafetyWindowAndLateCommit_syncedInNextCycle() {
        saveMarkers();
        // sub-microsecond nanos on purpose (CI JVM clock): PostgreSQL timestamptz ROUNDS to micros, so the expected
        // value is compared against the DB round-trip, not a Java truncation
        Instant start = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS).plusNanos(417_409_700);
        setMarker("contact", start);
        // large window and rows with timestamps in the past (no sleeping, no dependence on wall-clock pauses)
        ReflectionTestUtils.setField(service, "safetyWindowMs", 600_000L);

        UUID rowA = UUID.randomUUID();
        UUID rowB = UUID.randomUUID();
        Instant tsA = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        insertContactWithUpdatedAt(rowA, cdcTenant, "COMPLETED", Instant.now(), Timestamp.from(tsA));

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).as("row inside the safety window is not fetched yet").isZero();
        assertThat(marker("contact")).as("marker must not pass the window").isEqualTo(roundToMicros(start));

        // late commit: a transaction that started BEFORE row A (older updated_at) commits only now
        insertContactWithUpdatedAt(rowB, cdcTenant, "COMPLETED", Instant.now(),
                Timestamp.from(tsA.minusSeconds(30)));
        // the window elapses (simulated by shrinking it instead of sleeping)
        ReflectionTestUtils.setField(service, "safetyWindowMs", 1000L);

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).as("both A and the late-committed B reach the DW").isEqualTo(2);
        assertThat(marker("contact")).isAfterOrEqualTo(tsA);
    }

    @Test
    @DisplayName("BE-213: wyjatek DW w 3. batchu - znacznik zostaje na ostatnim udanym przesunieciu, status ERROR, kolejny cykl nie powtarza batchy 1-2")
    void failureAfterTwoBatches_markerKept_nextCycleContinuesFromIt() {
        saveMarkers();
        setMarker("contact", OLD_BASE.minusSeconds(10));
        seedContacts(5, false); // ts = OLD_BASE + 1..5 s
        // The CDC query is cross-tenant: on a shared DB other tests leave recent COMPLETED contacts behind (CI).
        // Foreign "now" rows must not influence this scenario -> seed some and exclude them with a 5-year window
        // (the 2001 rows are older than NOW() - 5y, the foreign rows are not).
        seedForeignRecentContacts(38);
        long fiveYearsMs = 5L * 365 * 24 * 3600 * 1000;

        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DataWarehouseWriter failing = new DataWarehouseWriter() {
            public void upsert(List<ContactDwRow> rows) {
                if (calls.incrementAndGet() == 3) {
                    throw new DataWarehouseException("DW down at batch 3");
                }
                dwWriter.upsert(rows);
            }
            public void upsertCampaigns(List<CampaignDwRow> rows) { }
            public void upsertAgentDim(List<AgentDimRow> rows) { }
            public void upsertQueueDim(List<QueueDimRow> rows) { }
        };
        EtlSyncServiceImpl failingService = etl(failing, false);
        ReflectionTestUtils.setField(failingService, "fetchLimit", 2);
        ReflectionTestUtils.setField(failingService, "safetyWindowMs", fiveYearsMs);

        failingService.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).as("batches 1 and 2 were written").isEqualTo(4);
        // batch 1 [1,2] -> marker ts1; batch 2 [3,4] -> marker ts3; batch 3 [5] failed
        assertThat(marker("contact")).isEqualTo(OLD_BASE.plusSeconds(3));
        assertThat(jdbc.queryForObject("SELECT status FROM etl_sync_state WHERE table_name = 'contact'", String.class))
                .isEqualTo("ERROR");

        List<Long> secondCycleSizes = new CopyOnWriteArrayList<>();
        DataWarehouseWriter counting = new DataWarehouseWriter() {
            public void upsert(List<ContactDwRow> rows) { secondCycleSizes.add((long) rows.size()); dwWriter.upsert(rows); }
            public void upsertCampaigns(List<CampaignDwRow> rows) { }
            public void upsertAgentDim(List<AgentDimRow> rows) { }
            public void upsertQueueDim(List<QueueDimRow> rows) { }
        };
        EtlSyncServiceImpl next = etl(counting, false);
        ReflectionTestUtils.setField(next, "fetchLimit", 2);
        ReflectionTestUtils.setField(next, "safetyWindowMs", fiveYearsMs);

        next.syncTable(EtlSyncService.TABLE_CONTACT);

        // continues after ts3: rows 4 and 5 only (2 rows), not 1..5
        assertThat(secondCycleSizes.stream().mapToLong(Long::longValue).sum()).isEqualTo(2);
        assertThat(dwCount()).isEqualTo(5);
        assertThat(marker("contact")).isEqualTo(OLD_BASE.plusSeconds(5));
    }

    @Test
    @DisplayName("BE-213: kontakt QUEUED -> COMPLETED (trigger V007 ustawia updated_at = NOW()) jest pobierany po zakonczeniu")
    void queuedContactBecomingCompleted_isSyncedAfterCompletion() {
        saveMarkers();
        setMarker("contact", Instant.now().minusSeconds(3600));
        UUID contactId = UUID.randomUUID();
        insertContactWithUpdatedAt(contactId, cdcTenant, "QUEUED", Instant.now(),
                Timestamp.from(Instant.now().minusSeconds(1800)));

        service.syncTable(EtlSyncService.TABLE_CONTACT);
        assertThat(dwCount()).as("QUEUED is never synced").isZero();

        jdbc.update("UPDATE contact SET status = 'COMPLETED' WHERE contact_id = ?", contactId);
        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-213: brak zmian po znaczniku - brak zapisu do DW, znacznik bez zmian, lag 0")
    void noChanges_noWrite_markerUnchanged_lagZero() {
        saveMarkers();
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        setMarker("contact", future);
        DataWarehouseWriter spyWriter = org.mockito.Mockito.spy(dwWriter);
        EtlSyncServiceImpl local = etl(spyWriter, false);

        local.syncTable(EtlSyncService.TABLE_CONTACT);

        org.mockito.Mockito.verifyNoInteractions(spyWriter);
        assertThat(marker("contact")).isEqualTo(future);
        assertThat(local.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, future)).isZero();
    }

    @Test
    @DisplayName("BE-213: campaign_contact - backlog > limit, wszystkie rekordy trafiaja do DW (petla + kursor id)")
    void campaignContactBacklogLargerThanLimit_allRowsReachDw() {
        saveMarkers();
        cdcCampaign = UUID.randomUUID();
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-213 cc " + cdcTenant);
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania BE-213')",
                cdcCampaign, tenantId);
        setMarker("campaign_contact", OLD_BASE.minusSeconds(10));
        ReflectionTestUtils.setField(service, "fetchLimit", 100);
        // 120 rows share one timestamp (tie group crossing the 100-row boundary), 130 distinct timestamps
        jdbc.update("""
                INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status, updated_at)
                SELECT gen_random_uuid(), ?, ?, '+48' || LPAD(i::text, 9, '0'), 'Jan', 'COMPLETED',
                       CAST(? AS TIMESTAMPTZ) + (CASE WHEN i <= 120 THEN 0 ELSE i END) * INTERVAL '1 second'
                FROM generate_series(1, 250) AS i
                """, cdcCampaign, tenantId, Timestamp.from(OLD_BASE));

        List<UUID> written = new CopyOnWriteArrayList<>();
        DataWarehouseWriter recording = new DataWarehouseWriter() {
            public void upsert(List<ContactDwRow> rows) { dwWriter.upsert(rows); }
            public void upsertCampaigns(List<CampaignDwRow> rows) {
                rows.stream().filter(r -> cdcCampaign.equals(r.campaignId())).forEach(r -> written.add(r.recordId()));
            }
            public void upsertAgentDim(List<AgentDimRow> rows) { }
            public void upsertQueueDim(List<QueueDimRow> rows) { }
        };
        EtlSyncServiceImpl local = etl(recording, false);
        ReflectionTestUtils.setField(local, "fetchLimit", 100);
        ReflectionTestUtils.setField(local, "safetyWindowMs", 0L);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(60), local::syncCampaignContactTable);

        assertThat(written).hasSize(250).doesNotHaveDuplicates();
        assertThat(marker("campaign_contact")).isAfterOrEqualTo(OLD_BASE.plusSeconds(250));
    }

    // =========================================================================
    // BE-216: transactions longer than the safety window
    // =========================================================================

    /*
     * Scenario (window = 0, i.e. the transaction is "longer than the window" by construction, no sleeping):
     *   1. transaction B starts at T0 and inserts a row; created_at DEFAULT NOW() = T0, so its effective timestamp
     *      is T0 - exactly like a row stamped by the V007/V002 triggers or a long import;
     *   2. a regular row A is committed AFTER T0 (timestamp > T0);
     *   3. an ETL cycle runs while B is still open;
     *   4. B commits; the next ETL cycle runs.
     * Before BE-216 (guard off) cycle 3 syncs A and moves the marker past T0, so B's row (T0 <= marker) is skipped
     * by every later cycle - lost, with lag = 0. With the guard the upper bound stays at T0, A waits, and both rows
     * arrive in cycle 4. Both rows use the same tenant, which is also what the assertions count by (foreign rows of
     * the shared DB are older/irrelevant: the checks never depend on them).
     */

    private UUID insertContactInOpenTransaction(Connection tx, UUID contactId) throws Exception {
        try (PreparedStatement ps = tx.prepareStatement("""
                INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at)
                VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', NOW())
                """)) {
            ps.setObject(1, contactId);
            ps.setObject(2, cdcTenant);
            ps.executeUpdate();
        }
        return contactId;
    }

    private Instant transactionStart(Connection tx) throws Exception {
        try (Statement st = tx.createStatement(); ResultSet rs = st.executeQuery("SELECT NOW()")) {
            rs.next();
            return rs.getTimestamp(1).toInstant();
        }
    }

    /** Commits a row A strictly after the (already open) transaction B started; its timestamp is > T0. */
    private void insertLaterCommittedRow(UUID contactId) {
        jdbc.execute("SELECT pg_sleep(0.01)"); // clock_timestamp() below is then strictly greater than T0
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at, updated_at)
                VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', NOW(), clock_timestamp())
                """, contactId, cdcTenant);
    }

    private boolean inDw(UUID contactId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM contacts_dw WHERE contact_id = ?", Integer.class, contactId) > 0;
    }

    @Test
    @DisplayName("BE-216: transakcja dluzsza niz okno (commit po cyklu ETL) - jej wiersze trafiaja do DW w kolejnym cyklu")
    void longTransaction_guardOn_rowsReachDwInCycleAfterCommit() throws Exception {
        saveMarkers();
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", true);
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        UUID rowB = UUID.randomUUID();
        UUID rowA = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            insertContactInOpenTransaction(tx, rowB);
            Instant t0 = transactionStart(tx);
            insertLaterCommittedRow(rowA);

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            assertThat(inDw(rowA)).as("A is newer than the open transaction - the cursor must not pass it yet").isFalse();
            assertThat(inDw(rowB)).isFalse();
            assertThat(marker("contact")).as("marker must not pass the start of the open write transaction")
                    .isBeforeOrEqualTo(t0);

            tx.commit();
        }

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(inDw(rowB)).as("the long transaction's row reaches the DW after its commit").isTrue();
        assertThat(inDw(rowA)).isTrue();
        assertThat(dwCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("BE-216 (kontrola 'przed'): bez straznika ten sam scenariusz GUBI wiersz dlugiej transakcji (zachowanie BE-213)")
    void longTransaction_guardOff_documentsTheLossBeforeTheFix() throws Exception {
        saveMarkers();
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", false);
        UUID rowB = UUID.randomUUID();
        UUID rowA = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            insertContactInOpenTransaction(tx, rowB);
            Instant t0 = transactionStart(tx);
            insertLaterCommittedRow(rowA);

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            assertThat(inDw(rowA)).isTrue();
            assertThat(marker("contact")).as("without the guard the marker passes the open transaction").isAfter(t0);

            tx.commit();
        }

        service.syncTable(EtlSyncService.TABLE_CONTACT);
        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contact WHERE contact_id = ?", Integer.class, rowB))
                .as("the row exists in the source").isEqualTo(1);
        assertThat(inDw(rowB)).as("... but was skipped for good: its timestamp T0 <= the advanced marker").isFalse();
        assertThat(service.computeDataLagMinutes(EtlSyncService.TABLE_CONTACT, marker("contact")))
                .as("and the lag stays low, so no alert would ever fire").isLessThan(EtlSyncService.LAG_ALERT_THRESHOLD_MINUTES);
    }

    @Test
    @DisplayName("BE-216: transakcja bez zapisu (sam SELECT, brak xid) nie wstrzymuje kursora")
    void readOnlyTransaction_doesNotHoldCursor() throws Exception {
        saveMarkers();
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", true);
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            transactionStart(tx); // read-only statement, no xid
            UUID rowA = UUID.randomUUID();
            insertLaterCommittedRow(rowA);

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            assertThat(inDw(rowA)).isTrue();
            tx.rollback();
        }
    }

    @Test
    @DisplayName("BE-216: straznik dziala tez dla campaign_contact (otwarta transakcja pisaca wstrzymuje kursor, po commicie wiersz dociera)")
    void longTransaction_campaignContact_guardHoldsCursor() throws Exception {
        saveMarkers();
        cdcCampaign = UUID.randomUUID();
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-216 cc " + cdcTenant);
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania BE-216')",
                cdcCampaign, tenantId);
        setMarker("campaign_contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        List<UUID> written = new CopyOnWriteArrayList<>();
        DataWarehouseWriter recording = new DataWarehouseWriter() {
            public void upsert(List<ContactDwRow> rows) { }
            public void upsertCampaigns(List<CampaignDwRow> rows) {
                rows.stream().filter(r -> cdcCampaign.equals(r.campaignId())).forEach(r -> written.add(r.recordId()));
            }
            public void upsertAgentDim(List<AgentDimRow> rows) { }
            public void upsertQueueDim(List<QueueDimRow> rows) { }
        };
        EtlSyncServiceImpl local = etl(recording, true);
        ReflectionTestUtils.setField(local, "safetyWindowMs", 0L);
        UUID importedInLongTx = UUID.randomUUID();
        UUID laterRow = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            try (PreparedStatement ps = tx.prepareStatement("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status)
                    VALUES (?, ?, ?, '+48500100416', 'Jan', 'PENDING')
                    """)) {
                ps.setObject(1, importedInLongTx);
                ps.setObject(2, cdcCampaign);
                ps.setObject(3, tenantId);
                ps.executeUpdate();
            }
            jdbc.execute("SELECT pg_sleep(0.01)");
            jdbc.update("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status, created_at)
                    VALUES (?, ?, ?, '+48500100417', 'Ewa', 'PENDING', clock_timestamp())
                    """, laterRow, cdcCampaign, tenantId);

            local.syncCampaignContactTable();
            assertThat(written).as("neither row passes while the write transaction is open").isEmpty();

            tx.commit();
        }

        local.syncCampaignContactTable();

        assertThat(written).containsExactlyInAnyOrder(importedInLongTx, laterRow);
    }

    @Test
    @DisplayName("BE-216: kilka otwartych transakcji pisacych - kursor trzyma MIN(xact_start), commit mlodszej niczego nie odblokowuje")
    void severalOpenWriteTransactions_cursorHeldAtTheOldest() throws Exception {
        saveMarkers();
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", true);
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        UUID oldest = UUID.randomUUID();
        UUID younger = UUID.randomUUID();
        UUID committed = UUID.randomUUID();

        HikariDataSource extra = PostgresTestDatabase.superuserPool(3);
        try {
            try (Connection tx1 = extra.getConnection(); Connection tx2 = extra.getConnection()) {
                tx1.setAutoCommit(false);
                tx2.setAutoCommit(false);
                insertContactInOpenTransaction(tx1, oldest);
                Instant t1 = transactionStart(tx1);
                jdbc.execute("SELECT pg_sleep(0.01)");
                insertContactInOpenTransaction(tx2, younger);
                insertLaterCommittedRow(committed);

                service.syncTable(EtlSyncService.TABLE_CONTACT);
                assertThat(dwCount()).isZero();
                assertThat(marker("contact")).isBeforeOrEqualTo(t1);

                tx2.commit(); // the younger one finishes; the oldest is still open -> still capped at its start
                service.syncTable(EtlSyncService.TABLE_CONTACT);
                assertThat(inDw(younger)).as("capped by the OLDEST open transaction, not by the youngest").isFalse();
                assertThat(inDw(committed)).isFalse();
                assertThat(marker("contact")).isBeforeOrEqualTo(t1);

                tx1.commit();
            }
        } finally {
            extra.close();
        }

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(inDw(oldest)).isTrue();
        assertThat(inDw(younger)).isTrue();
        assertThat(inDw(committed)).isTrue();
    }

    @Test
    @DisplayName("BE-216: zapis w SAVEPOINT (podtransakcja) tez trzyma kursor - xid nadrzednej transakcji jest widoczny")
    void writeInsideSavepoint_guardHoldsCursor() throws Exception {
        saveMarkers();
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", true);
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
        UUID inSubtransaction = UUID.randomUUID();
        UUID later = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            Instant t0 = transactionStart(tx); // transaction started, no xid yet
            try (Statement st = tx.createStatement()) {
                st.execute("SAVEPOINT s1");
            }
            insertContactInOpenTransaction(tx, inSubtransaction); // first write happens in the subtransaction
            insertLaterCommittedRow(later);

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            assertThat(inDw(later)).isFalse();
            assertThat(marker("contact")).isBeforeOrEqualTo(t0);
            tx.commit();
        }

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(inDw(inSubtransaction)).isTrue();
        assertThat(inDw(later)).isTrue();
    }

    @Test
    @DisplayName("BE-216: wiersz ze znacznikiem Javy nieco STARSZYM niz xact_start (w oknie) nie jest tracony - okno odjete takze od straznika")
    void javaStampedTimestampOlderThanXactStart_withinWindow_isDelivered() throws Exception {
        saveMarkers();
        ReflectionTestUtils.setField(service, "openTransactionGuardEnabled", true);
        setMarker("contact", Instant.now().minusSeconds(3600));
        ReflectionTestUtils.setField(service, "safetyWindowMs", 60_000L);
        UUID javaStamped = UUID.randomUUID();
        UUID committedBetween = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            Instant t0 = transactionStart(tx);
            // like CampaignImport/Contact insert: timestamp taken in Java 5 s BEFORE the transaction started
            try (PreparedStatement ps = tx.prepareStatement("""
                    INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at, updated_at)
                    VALUES (?, ?, 'PHONE', 'INBOUND', 'COMPLETED', NOW(), ?)
                    """)) {
                ps.setObject(1, javaStamped);
                ps.setObject(2, cdcTenant);
                ps.setTimestamp(3, Timestamp.from(t0.minusSeconds(5)));
                ps.executeUpdate();
            }
            // a committed row stamped between the Java timestamp and xact_start: without the window subtracted from
            // the guard the cursor would pass it (marker > javaStamped) and the Java-stamped row would be lost
            insertContactWithUpdatedAt(committedBetween, cdcTenant, "COMPLETED", Instant.now(),
                    Timestamp.from(t0.minusSeconds(2)));

            service.syncTable(EtlSyncService.TABLE_CONTACT);

            assertThat(inDw(committedBetween)).isFalse();
            assertThat(marker("contact")).isBefore(t0.minusSeconds(5));
            tx.commit();
        }
        ReflectionTestUtils.setField(service, "safetyWindowMs", 1000L); // the window elapses (no sleeping)

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(inDw(javaStamped)).isTrue();
        assertThat(inDw(committedBetween)).isTrue();
    }

    @Test
    @DisplayName("BE-222 (kontrakt PG, nie kod repozytorium): wiersz ze znacznikiem z bazy w transakcji dluzszej niz okno - dostarczony w kolejnym cyklu; kod repozytorium: CampaignContactDbTimestampsIntegrationTest")
    void dbStampedImportLongerThanWindow_deliveredWithoutRelyingOnTheWindow() throws Exception {
        saveMarkers();
        cdcCampaign = UUID.randomUUID();
        UUID tenantId = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-222 cc " + cdcTenant);
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, 'Kampania BE-222')",
                cdcCampaign, tenantId);
        setMarker("campaign_contact", Instant.now().minusSeconds(3600));
        List<UUID> written = new CopyOnWriteArrayList<>();
        DataWarehouseWriter recording = new DataWarehouseWriter() {
            public void upsert(List<ContactDwRow> rows) { }
            public void upsertCampaigns(List<CampaignDwRow> rows) {
                rows.stream().filter(r -> cdcCampaign.equals(r.campaignId())).forEach(r -> written.add(r.recordId()));
            }
            public void upsertAgentDim(List<AgentDimRow> rows) { }
            public void upsertQueueDim(List<QueueDimRow> rows) { }
        };
        EtlSyncServiceImpl local = etl(recording, true);
        ReflectionTestUtils.setField(local, "safetyWindowMs", 300L); // short window; the transaction outlives it
        UUID imported = UUID.randomUUID();

        try (Connection tx = pool.getConnection()) {
            tx.setAutoCommit(false);
            // the same statement shape as CampaignContactRepository#buildInsertSql after BE-222: no created_at
            try (PreparedStatement ps = tx.prepareStatement("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, phone, first_name, status)
                    VALUES (?, ?, ?, '+48500222100', 'Jan', 'PENDING')
                    """)) {
                ps.setObject(1, imported);
                ps.setObject(2, cdcCampaign);
                ps.setObject(3, tenantId);
                ps.executeUpdate();
            }
            Instant t0 = transactionStart(tx);
            try (Statement st = tx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT created_at, xact_start FROM campaign_contact, "
                         + "pg_stat_activity WHERE record_id = '" + imported + "' AND pid = pg_backend_pid()")) {
                rs.next();
                assertThat(rs.getTimestamp(1).toInstant()).as("row timestamp >= xact_start of the writing transaction")
                        .isAfterOrEqualTo(rs.getTimestamp(2).toInstant());
            }
            jdbc.execute("SELECT pg_sleep(0.6)"); // the transaction is now longer than the safety window

            local.syncCampaignContactTable();
            assertThat(written).as("not delivered while the transaction is open").isEmpty();
            assertThat(marker("campaign_contact")).as("the cursor is held at the open transaction's start")
                    .isBeforeOrEqualTo(t0);
            tx.commit();
        }

        local.syncCampaignContactTable();

        assertThat(written).containsExactly(imported);
    }

    @Test
    @DisplayName("BE-216 (b): pg_stat_activity maskuje sesje INNYCH rol (xact_start/backend_xid = NULL) bez pg_read_all_stats; sesje tej samej roli sa widoczne; start aplikacji ostrzega")
    void pgStatActivityVisibility_otherRolesMaskedUnlessPgReadAllStats() throws Exception {
        String role = "etl_probe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
        HikariDataSource probePool = PostgresTestDatabase.pool(role, password, 2);
        try {
            JdbcTemplate probe = new JdbcTemplate(probePool);
            String guardSql = "SELECT " + EtlSyncServiceImpl.OLDEST_OPEN_WRITE_XACT_START;
            EtlSyncServiceImpl probeService = new EtlSyncServiceImpl(probe, dwWriter, mock(RabbitTemplate.class),
                    mock(StringRedisTemplate.class));

            assertThat(etl(dwWriter, true).checkOpenTransactionGuardVisibility()).as("superuser role").isTrue();
            // the startup check is not tied to whether anything is open right now
            assertThat(probeService.checkOpenTransactionGuardVisibility())
                    .as("role without superuser / pg_read_all_stats: warning, guard blind").isFalse();

            try (Connection superWriter = pool.getConnection()) {
                superWriter.setAutoCommit(false);
                try (Statement st = superWriter.createStatement()) {
                    st.execute("SELECT txid_current()"); // forces an xid, i.e. a "write" transaction
                }

                assertThat(jdbc.queryForObject(guardSql, Timestamp.class))
                        .as("superuser reader sees the open write transaction").isNotNull();
                assertThat(probe.queryForObject(guardSql, Timestamp.class))
                        .as("a role without pg_read_all_stats sees NULL for a session of another role - the guard is blind")
                        .isNull();

                jdbc.execute("GRANT pg_read_all_stats TO " + role);
                assertThat(probe.queryForObject(guardSql, Timestamp.class))
                        .as("pg_read_all_stats restores the visibility").isNotNull();
                assertThat(probeService.checkOpenTransactionGuardVisibility()).isTrue();
                jdbc.execute("REVOKE pg_read_all_stats FROM " + role);
                superWriter.rollback();
            }

            // same role: the session is visible without any extra privilege
            try (Connection ownWriter = probePool.getConnection()) {
                ownWriter.setAutoCommit(false);
                try (Statement st = ownWriter.createStatement()) {
                    st.execute("SELECT txid_current()");
                }
                assertThat(probe.queryForObject(guardSql, Timestamp.class))
                        .as("a session of the SAME role is visible without pg_read_all_stats").isNotNull();
                ownWriter.rollback();
            }
        } finally {
            // cleanup must never mask the original assertion failure
            try {
                probePool.close();
            } catch (RuntimeException ignored) {
                // best effort
            }
            try {
                jdbc.execute("REVOKE pg_read_all_stats FROM " + role);
                jdbc.execute("DROP ROLE IF EXISTS " + role);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
    }

    private void insertContactWithUpdatedAt(UUID contactId, UUID tenantId, String status, Instant queuedAt,
                                            Timestamp updatedAt) {
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, channel, direction, status, queued_at, updated_at)
                VALUES (?, ?, 'PHONE', 'INBOUND', ?, ?, ?)
                """, contactId, tenantId, status, Timestamp.from(queuedAt), updatedAt);
    }

    private void insertContact(UUID contactId, UUID tenantId, UUID agentId, UUID queueId, UUID campaignId,
                                String channel, String direction, String status, String dispositionCode,
                                Integer durationSeconds, Instant queuedAt, Instant startedAt, Instant endedAt,
                                String remoteAddress) {
        jdbc.update("""
                INSERT INTO contact
                    (contact_id, tenant_id, agent_id, queue_id, campaign_id,
                     channel, direction, status, disposition_code, duration_seconds,
                     queued_at, started_at, ended_at, remote_address)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                contactId, tenantId, agentId, queueId, campaignId,
                channel, direction, status, dispositionCode, durationSeconds,
                Timestamp.from(queuedAt), Timestamp.from(startedAt),
                endedAt != null ? Timestamp.from(endedAt) : null,
                remoteAddress);
    }
}
