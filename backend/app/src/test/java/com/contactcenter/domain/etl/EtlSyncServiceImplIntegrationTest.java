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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Test integracyjny na prawdziwym PostgreSQL (Testcontainers, pełny łańcuch Flyway) dla ścieżki
 * danych ETL {@code contact} → {@code contacts_dw} po BE-141.
 *
 * <p>W przeciwieństwie do {@link EtlSyncServiceImplTest} (czysto mockowy, weryfikuje orkiestrację:
 * {@code readAndLockSyncState}/{@code markDone}/{@code checkLagAndAlert}), ten test uruchamia
 * PRAWDZIWE {@code SELECT_CONTACTS_FOR_ETL} (przez pakietowo widoczną
 * {@link EtlSyncServiceImpl#fetchContactsForEtl}) na rzeczywistym wierszu {@code contact} i
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
 * {@code fetchContactsForEtl} z punktem odcięcia ustawionym tuż przed seedem, więc wynik nie jest
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
    @DisplayName("kontakt w pełni wypełniony: fetchContactsForEtl + upsert zapisują poprawne wartości, remote_address nie trafia do DW")
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

        List<ContactDwRow> fetched = service.fetchContactsForEtl(cutoff, 50_000);
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

        List<ContactDwRow> fetched = service.fetchContactsForEtl(cutoff, 50_000);
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
