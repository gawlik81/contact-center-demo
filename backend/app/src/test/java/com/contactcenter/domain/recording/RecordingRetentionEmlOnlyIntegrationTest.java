package com.contactcenter.domain.recording;

import com.contactcenter.domain.contact.ContactRecordingEntry;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.email.EmailMessage;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPolicyService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-196 on a REAL PostgreSQL (Testcontainers + full Flyway chain): {@link RecordingRetentionJob} cleans the EML
 * objects of replies of an EMAIL contact whose {@code recording_url} is NULL. Real: Hibernate, the e-mail
 * repository/service/attachment storage (package-private, registered by name), the job. Stand-ins: S3 client
 * (fake with failures), {@code ContactService} (delegates to plain JDBC with the production SQL of the
 * recording branch) and {@code RetentionPolicyService} (fixed 90 days).
 *
 * <p>The application pool runs as a restricted login role (NOSUPERUSER NOBYPASSRLS, member of {@code app_user}), so
 * the tenant-scoped queries are really subject to RLS ({@code set_tenant_context}); seeding/asserting uses the
 * superuser pool.
 */
@DisplayName("RecordingRetentionJob - EML odpowiedzi kontaktu bez recording_url (BE-196)")
class RecordingRetentionEmlOnlyIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final int RETENTION_DAYS = 90;

    private static HikariDataSource superuserPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static RecordingService recordingService;
    private static ContactService contactService;
    private static RetentionPolicyService retentionPolicyService;
    private static RecordingRetentionJob job;
    private static EmailMessageService emailService;

    private final List<String> deletedKeys = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failingKeys = Collections.synchronizedSet(new HashSet<>());
    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void start() throws Exception {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        String role = "be196_app";
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
        appPool = PostgresTestDatabase.pool(role, password, 2);

        s3Client = mock(S3Client.class);
        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);

        Class<?>[] beans = {
                Class.forName("com.contactcenter.domain.email.EmailMessageRepository"),
                Class.forName("com.contactcenter.domain.email.EmailMessageServiceImpl"),
                Class.forName("com.contactcenter.domain.email.EmailAttachmentStorageServiceImpl")};
        ctx = JpaTestContext.create(appPool, new Class<?>[]{EmailMessage.class}, beans, c -> {
            c.getBeanFactory().registerSingleton("s3Client", s3Client);
            c.getBeanFactory().registerSingleton("s3Presigner", mock(S3Presigner.class));
            c.getBeanFactory().registerSingleton("s3Properties", s3Properties);
        });

        recordingService = mock(RecordingService.class);
        contactService = mock(ContactService.class);
        retentionPolicyService = mock(RetentionPolicyService.class);
        // Real e-mail service; only the cross-tenant discovery is stubbed (it needs an RLS-bypassing role, see setUp()).
        emailService = mock(EmailMessageService.class, org.mockito.AdditionalAnswers.delegatesTo(
                ctx.getBean(EmailMessageService.class)));
        org.mockito.Mockito.doReturn(List.of()).when(emailService).findTenantIdsWithEml();
        job = new RecordingRetentionJob(recordingService, contactService, retentionPolicyService, emailService);
    }

    @AfterAll
    static void stop() {
        JpaTestContext.close(ctx);
        appPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        deletedKeys.clear();
        failingKeys.clear();
        reset(s3Client, recordingService, contactService, retentionPolicyService);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            String key = ((DeleteObjectRequest) inv.getArgument(0)).key();
            if (failingKeys.contains(key)) {
                throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
            }
            deletedKeys.add(key);
            return DeleteObjectResponse.builder().build();
        });
        when(retentionPolicyService.getRetentionDays(any(), eq(RetentionDataCategory.RECORDINGS)))
                .thenReturn(RETENTION_DAYS);

        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A BE-196 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B BE-196 " + UUID.randomUUID());

        // Tenant discovery: the real cross-tenant SQL needs an RLS-bypassing role (production model), which this
        // class deliberately does not use for the application pool; it is covered against the real SQL in
        // ContactRepositoryTenantsWithRecordingsIntegrationTest / EmailMessageTenantDiscoveryIntegrationTest.
        // Here the list of tenants is just given (the per-tenant work below runs under RLS).
        when(contactService.findTenantsWithRecordings()).thenReturn(List.of(tenantA, tenantB));
        // Recording branch stand-ins: the job reads candidates / clears recording_url through ContactService.
        when(contactService.findExpiredRecordings(any(), any(), anyInt())).thenAnswer(inv -> {
            UUID tenant = inv.getArgument(0);
            Instant cutoff = inv.getArgument(1);
            return jdbc.query("SELECT contact_id, recording_url FROM contact WHERE tenant_id = ? "
                            + "AND recording_url IS NOT NULL AND ended_at IS NOT NULL AND ended_at < ?",
                    (rs, i) -> new ContactRecordingEntry(UUID.fromString(rs.getString(1)), rs.getString(2)),
                    tenant, java.sql.Timestamp.from(cutoff));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            UUID contactId = inv.getArgument(0);
            UUID tenantId = inv.getArgument(1);
            jdbc.update("UPDATE contact SET recording_url = NULL WHERE contact_id = ? AND tenant_id = ?",
                    contactId, tenantId);
            return null;
        }).when(contactService).clearRecordingUrl(any(), any());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // -------------------------------------------------------------------------
    // Seeding
    // -------------------------------------------------------------------------

    private UUID contact(UUID tenant, String channel, String recordingUrl, Instant endedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, channel, direction, status, remote_address, "
                        + "queued_at, started_at, ended_at, recording_url) VALUES (?, ?, ?, 'INBOUND', 'COMPLETED', "
                        + "'x@y.pl', CAST(? AS timestamptz) - interval '1 hour', "
                        + "CAST(? AS timestamptz) - interval '1 hour', CAST(? AS timestamptz), ?)",
                id, tenant, channel, endedAt.toString(), endedAt.toString(), endedAt.toString(), recordingUrl);
        return id;
    }

    private UUID message(UUID tenant, UUID contact, Instant messageAt, String emlKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, "
                        + "to_address, subject, body_text, attachments, received_at, message_at, eml_s3_key) "
                        + "VALUES (?, ?, ?, 'OUTBOUND', 'biuro@example.com', 'klient@example.com', 's', 'b', "
                        + "'[]'::jsonb, now(), CAST(? AS timestamptz), ?)",
                id, tenant, contact, messageAt.toString(), emlKey);
        return id;
    }

    private static String emlKey(UUID tenant) {
        return tenant + "/2026/01/" + UUID.randomUUID() + ".eml";
    }

    private String emlKeyOf(UUID messageId) {
        return jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id = ?", String.class, messageId);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("kontakt EMAIL bez recording_url, wygasla retencja: EML odpowiedzi usuniete z S3, eml_s3_key = NULL; 2. przebieg nic nie robi")
    void emlOnlyContactIsCleanedAndSecondPassIsNoOp() {
        UUID contact = contact(tenantA, "EMAIL", null, daysAgo(100));
        String k1 = emlKey(tenantA);
        String k2 = emlKey(tenantA);
        UUID m1 = message(tenantA, contact, daysAgo(101), k1);
        UUID m2 = message(tenantA, contact, daysAgo(100), k2);

        job.runRetentionJob();

        assertThat(deletedKeys).containsExactlyInAnyOrder(k1, k2);
        assertThat(emlKeyOf(m1)).isNull();
        assertThat(emlKeyOf(m2)).isNull();

        deletedKeys.clear();
        job.runRetentionJob();
        assertThat(deletedKeys).isEmpty();
        assertThat(emlKeyOf(m1)).isNull();
        assertThat(emlKeyOf(m2)).isNull();
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class)); // only the 2 deletes of the first pass
    }

    @Test
    @DisplayName("retencja jeszcze nie minela (kontakt zakonczony 10 dni temu) -> EML nietkniety")
    void notExpiredContactIsUntouched() {
        UUID contact = contact(tenantA, "EMAIL", null, daysAgo(10));
        String key = emlKey(tenantA);
        UUID m = message(tenantA, contact, daysAgo(11), key);

        job.runRetentionJob();

        assertThat(deletedKeys).isEmpty();
        assertThat(emlKeyOf(m)).isEqualTo(key);
    }

    @Test
    @DisplayName("kontakt z recording_url (.eml) - zachowanie bez zmian: obiekty (recording_url + odpowiedzi) usuniete, klucze NULL, 1 raz")
    void contactWithRecordingUrlKeepsExistingBehaviour() {
        String first = emlKey(tenantA);
        UUID contact = contact(tenantA, "EMAIL", first, daysAgo(100));
        String reply = emlKey(tenantA);
        UUID m = message(tenantA, contact, daysAgo(100), reply);
        org.mockito.Mockito.doAnswer(inv -> {
            String key = inv.getArgument(0);
            deletedKeys.add(key);
            return null;
        }).when(recordingService).deleteFromS3(any());

        job.runRetentionJob();

        assertThat(deletedKeys).containsExactlyInAnyOrder(first, reply);
        assertThat(emlKeyOf(m)).isNull();
        assertThat(jdbc.queryForObject("SELECT recording_url FROM contact WHERE contact_id = ?", String.class, contact))
                .isNull();

        deletedKeys.clear();
        job.runRetentionJob();
        assertThat(deletedKeys).isEmpty();
    }

    @Test
    @DisplayName("porazka S3: klucz zostaje (ponowienie); po powrocie S3 drugi przebieg domyka; brak obiektu w S3 = sukces")
    void s3FailureIsRetriedNextRun() {
        UUID contact = contact(tenantA, "EMAIL", null, daysAgo(100));
        String ok = emlKey(tenantA);
        String bad = emlKey(tenantA);
        UUID mOk = message(tenantA, contact, daysAgo(100), ok);
        UUID mBad = message(tenantA, contact, daysAgo(100), bad);
        failingKeys.add(bad);

        job.runRetentionJob();

        assertThat(emlKeyOf(mOk)).isNull();
        assertThat(emlKeyOf(mBad)).isEqualTo(bad);

        failingKeys.clear();
        job.runRetentionJob();
        assertThat(emlKeyOf(mBad)).isNull();
        assertThat(deletedKeys).contains(bad);
    }

    @Test
    @DisplayName("izolacja tenantow: kontakt tenanta B nie jest ruszany przez wybor tenanta A i odwrotnie (kazdy z wlasnym kontekstem RLS)")
    void tenantsAreProcessedIndependentlyUnderRls() {
        UUID cA = contact(tenantA, "EMAIL", null, daysAgo(100));
        UUID cB = contact(tenantB, "EMAIL", null, daysAgo(100));
        String kA = emlKey(tenantA);
        String kB = emlKey(tenantB);
        UUID mA = message(tenantA, cA, daysAgo(100), kA);
        UUID mB = message(tenantB, cB, daysAgo(100), kB);

        job.runRetentionJob();

        assertThat(deletedKeys).containsExactlyInAnyOrder(kA, kB);
        assertThat(emlKeyOf(mA)).isNull();
        assertThat(emlKeyOf(mB)).isNull();
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("kontakt nie-EMAIL i kontakt bez wiadomosci z EML nie sa kandydatami")
    void nonEmailAndEmlLessContactsAreNotCandidates() {
        UUID voice = contact(tenantA, "PHONE", null, daysAgo(100));
        UUID noEml = contact(tenantA, "EMAIL", null, daysAgo(100));
        message(tenantA, noEml, daysAgo(100), null);

        job.runRetentionJob();

        assertThat(deletedKeys).isEmpty();
        assertThat(voice).isNotNull();
    }

    @Test
    @DisplayName("kontakt EMAIL otwarty (ended_at IS NULL) ze starymi odpowiedziami -> nietkniety")
    void openContactIsUntouched() {
        UUID id = UUID.randomUUID();
        Instant started = daysAgo(120);
        jdbc.update("INSERT INTO contact (contact_id, tenant_id, channel, direction, status, remote_address, "
                + "queued_at, started_at) VALUES (?, ?, 'EMAIL', 'INBOUND', 'ACTIVE', 'x@y.pl', "
                + "CAST(? AS timestamptz), CAST(? AS timestamptz))", id, tenantA, started.toString(), started.toString());
        String key = emlKey(tenantA);
        UUID m = message(tenantA, id, daysAgo(110), key);

        job.runRetentionJob();

        assertThat(deletedKeys).isEmpty();
        assertThat(emlKeyOf(m)).isEqualTo(key);
    }

    @Test
    @DisplayName("kontakt swiezy (ended_at < cutoff nie zachodzi) ze stara wiadomoscia -> nietkniety")
    void freshContactWithOldMessageIsUntouched() {
        UUID contact = contact(tenantA, "EMAIL", null, daysAgo(10));
        String key = emlKey(tenantA);
        UUID m = message(tenantA, contact, daysAgo(100), key);

        job.runRetentionJob();

        assertThat(deletedKeys).isEmpty();
        assertThat(emlKeyOf(m)).isEqualTo(key);
    }

    @Test
    @DisplayName("kontakt wygasly z wiadomoscia starsza i NOWSZA niz cutoff: kwalifikuje przez stara, usuwane sa WSZYSTKIE klucze kontaktu")
    void expiredContactWithMessageNewerThanCutoffLosesAllKeys() {
        UUID contact = contact(tenantA, "EMAIL", null, daysAgo(100));
        String oldKey = emlKey(tenantA);
        String newKey = emlKey(tenantA);
        UUID mOld = message(tenantA, contact, daysAgo(101), oldKey);
        UUID mNew = message(tenantA, contact, daysAgo(50), newKey);

        job.runRetentionJob();

        assertThat(deletedKeys).containsExactlyInAnyOrder(oldKey, newKey);
        assertThat(emlKeyOf(mOld)).isNull();
        assertThat(emlKeyOf(mNew)).isNull();
    }

    @Test
    @DisplayName("kontakt z recording_url nie-.eml (nagranie): nagranie usuniete, a po wyzerowaniu recording_url EML odpowiedzi sprzatniete w tym samym przebiegu")
    void nonEmlRecordingThenRepliesCleanedSameRun() {
        UUID contact = contact(tenantA, "EMAIL", tenantA + "/2026/01/rec.mp3", daysAgo(100));
        String reply = emlKey(tenantA);
        UUID m = message(tenantA, contact, daysAgo(100), reply);

        job.runRetentionJob();

        assertThat(emlKeyOf(m)).isNull();
        assertThat(deletedKeys).contains(reply);
    }

    @Test
    @DisplayName("stronicowanie keysetem na prawdziwej bazie: >100 kontaktow jest sprzatnietych w jednym przebiegu")
    void morePagesThanOneBatchAreDrained() {
        List<UUID> messages = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            UUID c = contact(tenantA, "EMAIL", null, daysAgo(100));
            messages.add(message(tenantA, c, daysAgo(100), emlKey(tenantA)));
        }

        job.runRetentionJob();

        assertThat(messages).allSatisfy(m -> assertThat(emlKeyOf(m)).isNull());
        assertThat(deletedKeys).hasSize(130);
    }

    @Test
    @DisplayName("trwale wadliwe kontakty nie blokuja kolejnych w przebiegu (keyset): 100 wadliwych + 1 dobry -> dobry sprzatniety")
    void permanentlyFailingContactsDoNotStarveOthers() {
        List<UUID> contacts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            UUID c = contact(tenantA, "EMAIL", null, daysAgo(100));
            String k = emlKey(tenantA);
            failingKeys.add(k);
            message(tenantA, c, daysAgo(100), k);
            contacts.add(c);
        }
        UUID good = contact(tenantA, "EMAIL", null, daysAgo(100));
        String goodKey = emlKey(tenantA);
        UUID goodMsg = message(tenantA, good, daysAgo(100), goodKey);

        job.runRetentionJob();

        // Pages are keyset-ordered by contact_id; whichever page the good contact lands in, failing contacts are
        // passed over (not retried within the run) and one fully failed page does not stop the tenant.
        assertThat(emlKeyOf(goodMsg)).isNull();
        assertThat(contacts).hasSize(100);
    }

    /** Production SQL of the candidate query (package-private constant of the e-mail repository). */
    private static String expiredEmlContactsSql() {
        try {
            java.lang.reflect.Field f = Class.forName("com.contactcenter.domain.email.EmailMessageRepository")
                    .getDeclaredField("EXPIRED_EML_CONTACTS_SQL");
            f.setAccessible(true);
            return (String) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("EXPLAIN zapytania kandydatow: partycje email_message nowsze niz cutoff sa przyciete (plan nie dotyka biezacego miesiaca), starsza partycja z danymi jest skanowana")
    void candidatePlanPrunesPartitionsNewerThanCutoff() {
        Instant cutoff = daysAgo(RETENTION_DAYS);
        String sql = expiredEmlContactsSql()
                .replace(":tenantId", "'" + tenantA + "'")
                .replace(":cutoff", "'" + cutoff + "'::timestamptz")
                .replace(":after", "'00000000-0000-0000-0000-000000000000'")
                .replace(":limit", "100");
        String plan = String.join("\n", jdbc.queryForList("EXPLAIN (COSTS OFF) " + sql, String.class));

        java.time.YearMonth current = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        String currentPartition = "email_message_" + current.getYear() + "_"
                + String.format("%02d", current.getMonthValue());
        String nextPartition = "email_message_" + current.plusMonths(1).getYear() + "_"
                + String.format("%02d", current.plusMonths(1).getMonthValue());
        // Both partitions exist (create_email_message_partition: current + 2 following months) ...
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN (?, ?)", Long.class,
                currentPartition, nextPartition)).isEqualTo(2L);
        // ... but lie entirely after the cutoff, so the planner must drop them.
        assertThat(plan).doesNotContain(currentPartition).doesNotContain(nextPartition);
        assertThat(plan).contains("email_message_");
    }

    private static final String EML_INDEX = "idx_email_message_eml_contact";

    @Test
    @DisplayName("V150: indeks czesciowy (tenant_id, contact_id, message_at) WHERE eml_s3_key IS NOT NULL istnieje na rodzicu i na kazdej partycji")
    void emlPartialIndexExistsOnParentAndAllPartitions() {
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_indexdef(indexrelid) FROM pg_index
                WHERE indexrelid = ?::regclass AND indrelid = 'email_message'::regclass
                """, String.class, EML_INDEX))
                .contains("(tenant_id, contact_id, message_at)")
                .contains("WHERE (eml_s3_key IS NOT NULL)");
        Long partitions = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits WHERE inhparent = 'email_message'::regclass", Long.class);
        Long attached = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits WHERE inhparent = ?::regclass", Long.class, EML_INDEX);
        assertThat(partitions).isGreaterThan(0L);
        // every partition (incl. DEFAULT and future ones created by create_email_message_partition) has a child index
        assertThat(attached).isEqualTo(partitions);
    }

    @Test
    @DisplayName("V150: plan EXPLAIN zapytania kandydatow i findTenantIdsWithEml uzywa indeksu eml (seqscan wylaczony - deterministycznie na malych partycjach), przycinanie partycji zachowane")
    void candidateAndDiscoveryPlansUseEmlIndex() {
        String candidateSql = expiredEmlContactsSql()
                .replace(":tenantId", "'" + tenantA + "'")
                .replace(":cutoff", "'" + daysAgo(RETENTION_DAYS) + "'::timestamptz")
                .replace(":after", "'00000000-0000-0000-0000-000000000000'")
                .replace(":limit", "100");
        String discoverySql = "SELECT DISTINCT tenant_id FROM email_message WHERE eml_s3_key IS NOT NULL";
        String[] plans = jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try {
                    return new String[]{explain(st, candidateSql), explain(st, discoverySql)};
                } finally {
                    st.execute("RESET enable_seqscan");
                }
            }
        });
        assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plans[0], EML_INDEX))
                .as("candidate plan:%n%s", plans[0]).isTrue();
        assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plans[1], EML_INDEX))
                .as("discovery plan:%n%s", plans[1]).isTrue();
    }

    private static String explain(java.sql.Statement st, String sql) throws java.sql.SQLException {
        StringBuilder sb = new StringBuilder();
        try (java.sql.ResultSet rs = st.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('\n');
            }
        }
        return sb.toString();
    }
}
