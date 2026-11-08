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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
        service = new EtlSyncServiceImpl(jdbc, dwWriter, mock(RabbitTemplate.class));
        // BE-213: existing tests insert and fetch immediately - disable the safety window for them
        ReflectionTestUtils.setField(service, "safetyWindowMs", 0L);
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
        Instant start = Instant.now().minusSeconds(3600);
        setMarker("contact", start);
        // large window and rows with timestamps in the past (no sleeping, no dependence on wall-clock pauses)
        ReflectionTestUtils.setField(service, "safetyWindowMs", 600_000L);

        UUID rowA = UUID.randomUUID();
        UUID rowB = UUID.randomUUID();
        Instant tsA = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        insertContactWithUpdatedAt(rowA, cdcTenant, "COMPLETED", Instant.now(), Timestamp.from(tsA));

        service.syncTable(EtlSyncService.TABLE_CONTACT);

        assertThat(dwCount()).as("row inside the safety window is not fetched yet").isZero();
        assertThat(marker("contact")).as("marker must not pass the window").isEqualTo(start.truncatedTo(ChronoUnit.MICROS));

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
        EtlSyncServiceImpl failingService = new EtlSyncServiceImpl(jdbc, failing, mock(RabbitTemplate.class));
        ReflectionTestUtils.setField(failingService, "fetchLimit", 2);
        ReflectionTestUtils.setField(failingService, "safetyWindowMs", 0L);

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
        EtlSyncServiceImpl next = new EtlSyncServiceImpl(jdbc, counting, mock(RabbitTemplate.class));
        ReflectionTestUtils.setField(next, "fetchLimit", 2);
        ReflectionTestUtils.setField(next, "safetyWindowMs", 0L);

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
        EtlSyncServiceImpl local = new EtlSyncServiceImpl(jdbc, spyWriter, mock(RabbitTemplate.class));

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
        EtlSyncServiceImpl local = new EtlSyncServiceImpl(jdbc, recording, mock(RabbitTemplate.class));
        ReflectionTestUtils.setField(local, "fetchLimit", 100);
        ReflectionTestUtils.setField(local, "safetyWindowMs", 0L);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(60), local::syncCampaignContactTable);

        assertThat(written).hasSize(250).doesNotHaveDuplicates();
        assertThat(marker("campaign_contact")).isAfterOrEqualTo(OLD_BASE.plusSeconds(250));
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
