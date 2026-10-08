package com.contactcenter.domain.retention;

import com.contactcenter.domain.email.EmailMessage;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.email.OrphanEmailPurgeBatch;
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
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny łańcucha Poziom 1 → Poziom 2 dla {@code email_message} na PRAWDZIWYM PostgreSQL
 * (Testcontainers, pełny łańcuch Flyway) z S3 zamockowanym — BE-135, WP-5.
 *
 * <p><strong>Reguła WP-5:</strong> {@code DROP} partycji NIE usuwa obiektów S3 wskazywanych przez
 * {@code attachments}. Dlatego {@link PartitionReclaimJob} dropuje partycję {@code email_message_*}
 * wyłącznie, gdy jest PUSTA, a wiersz jest usuwany przez purge (Poziom 1) dopiero PO usunięciu jego
 * obiektów S3. Ten test sprawdza KOLEJNOŚĆ zdarzeń: {@code S3:<klucz>} musi wystąpić przed
 * {@code DROP:<partycja>}. Zdarzenia rejestrują się w jednym, współdzielonym dzienniku — S3 (mock
 * {@code S3Client}) i {@link RecordingPartitionScanner} (podklasa realnego {@link PartitionScannerImpl}).
 *
 * <p><strong>Dlaczego klasy e-mail są rejestrowane przez nazwę:</strong> {@code EmailMessageServiceImpl},
 * {@code EmailMessageRepository} i {@code EmailAttachmentStorageServiceImpl} są package-private w pakiecie
 * {@code domain.email}, a ten test żyje w {@code domain.retention} (tam jest package-private
 * {@link PartitionReclaimJob}). Publiczna powierzchnia to {@link EmailMessageService} (interfejs).
 *
 * <p>Partycje używają dat WZGLĘDNYCH, odległych od innych testów na tej samej (współdzielonej per JVM)
 * bazie; każda partycja jest usuwana w {@link #cleanup()}, bo niepusta/resztkowa partycja e-mail psuje
 * asercje EXPLAIN w innych klasach (wzorzec BE-133/BE-145, {@code PartitionReclaimJobIntegrationTest}).
 */
@DisplayName("PartitionReclaimJob + EmailMessageService – prawdziwa baza, S3 (mock), kolejność S3 przed DROP (BE-135, WP-5)")
class PartitionReclaimEmailMessageIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final String PARTITION_PREFIX = "email_message_";

    /** Wspólny dziennik zdarzeń: {@code S3:<klucz>} i {@code DROP:<partycja>} w kolejności wystąpienia. */
    private static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    /** Klucze, których usunięcie w S3 ma się nie powieść (test awarii S3). */
    private static final Set<String> FAILING_KEYS = Collections.synchronizedSet(new HashSet<>());

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static PartitionReclaimJob job;
    private static EmailMessageService emailService;
    private static RetentionPolicyService retentionPolicyService;

    private UUID tenant;
    private final List<String> createdPartitions = new ArrayList<>();

    /** Podklasa realnego skanera: rejestruje DROP przed wykonaniem (SQL pozostaje prawdziwy). */
    static class RecordingPartitionScanner extends PartitionScannerImpl {

        // Jawna adnotacja: nadpisana metoda nie dziedziczy niezawodnie @Transactional z nadklasy w proxy CGLIB.
        @Override
        @Transactional
        public void dropPartition(String partitionTableName) {
            EVENTS.add("DROP:" + partitionTableName);
            super.dropPartition(partitionTableName);
        }
    }

    @BeforeAll
    static void startContext() throws ClassNotFoundException {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);

        S3Client s3Client = mock(S3Client.class);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenAnswer(inv -> fakeDelete(inv.getArgument(0)));
        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);
        retentionPolicyService = mock(RetentionPolicyService.class);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.CONTACT_INTERACTIONS)).thenReturn(60);
        when(retentionPolicyService.findMaxRetentionMonths(RetentionDataCategory.TRANSCRIPTS)).thenReturn(60);

        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{EmailMessage.class},
                new Class<?>[]{
                        Class.forName("com.contactcenter.domain.email.EmailMessageRepository"),
                        Class.forName("com.contactcenter.domain.email.EmailMessageServiceImpl"),
                        Class.forName("com.contactcenter.domain.email.EmailAttachmentStorageServiceImpl"),
                        RecordingPartitionScanner.class,
                        PlatformRetentionProperties.class,
                        PartitionReclaimJob.class},
                c -> {
                    c.getBeanFactory().registerSingleton("s3Client", s3Client);
                    c.getBeanFactory().registerSingleton("s3Presigner", mock(S3Presigner.class));
                    c.getBeanFactory().registerSingleton("s3Properties", s3Properties);
                    c.getBeanFactory().registerSingleton("retentionPolicyService", retentionPolicyService);
                });
        emailService = ctx.getBean(EmailMessageService.class);
        job = ctx.getBean(PartitionReclaimJob.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        EVENTS.clear();
        FAILING_KEYS.clear();
        tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant BE-135 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (String partition : createdPartitions) {
            jdbc.execute("DROP TABLE IF EXISTS \"" + partition + "\"");
        }
        createdPartitions.clear();
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private static DeleteObjectResponse fakeDelete(DeleteObjectRequest request) {
        assertThat(request.bucket()).isEqualTo(BUCKET);
        EVENTS.add("S3:" + request.key());
        if (FAILING_KEYS.contains(request.key())) {
            throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
        }
        return DeleteObjectResponse.builder().build();
    }

    /** Partycja miesięczna {@code email_message_YYYY_MM} (idempotentna funkcja SQL z V102). */
    private String createEmailPartition(LocalDate firstOfMonth) {
        jdbc.execute("SELECT create_email_message_partition(%d, %d)"
                .formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue()));
        String name = PARTITION_PREFIX + "%04d_%02d".formatted(firstOfMonth.getYear(), firstOfMonth.getMonthValue());
        createdPartitions.add(name);
        return name;
    }

    /** Wiersz osierocony (contact_id IS NULL) z jednym załącznikiem; message_at w podanym miesiącu. */
    private UUID insertOrphanWithAttachment(UUID tenantId, LocalDate month, String s3Key) {
        UUID messageId = UUID.randomUUID();
        Instant messageAt = month.withDayOfMonth(15).atTime(10, 0).toInstant(ZoneOffset.UTC);
        // Filtr resztkowy BE-127 (created_at < now - 1 dzień) — rekord musi być "stary" też wg created_at.
        Instant createdAt = Instant.now().minus(730, ChronoUnit.DAYS);
        String attachmentsJson = "[{\"filename\":\"a.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":1,\"s3_key\":\""
                + s3Key + "\"}]";
        jdbc.update("""
                        INSERT INTO email_message
                            (message_id, tenant_id, contact_id, direction, from_address, to_address,
                             subject, body_text, attachments, received_at, created_at, message_at)
                        VALUES (?, ?, NULL, 'INBOUND', 'klient@example.com', 'biuro@example.com',
                                'Temat', 'Treść', CAST(? AS jsonb), ?, ?, ?)
                        """,
                messageId, tenantId, attachmentsJson,
                Timestamp.from(messageAt), Timestamp.from(createdAt), Timestamp.from(messageAt));
        return messageId;
    }

    private boolean partitionExists(String partitionName) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                Integer.class, partitionName);
        return count != null && count > 0;
    }

    private boolean messageExists(UUID messageId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM email_message WHERE message_id = ?",
                Long.class, messageId);
        return count != null && count > 0;
    }

    private static String s3KeyFor(UUID tenantId) {
        // Format zgodny z allow-listą EmailAttachmentKeys: email-attachments/{tenantId}/{messageId}/{filename}
        return "email-attachments/" + tenantId + "/" + UUID.randomUUID() + "/a.pdf";
    }

    private static LocalDate monthsAgo(int months) {
        return LocalDate.now(ZoneOffset.UTC).minusMonths(months).withDayOfMonth(1);
    }

    private static Instant purgeCutoff() {
        return Instant.now().minus(180, ChronoUnit.DAYS);
    }

    // =========================================================================
    // Scenariusz 1 (WP-5, AC główne): S3 usunięte PRZED DROP; DROP dopiero po purge Poziomu 1
    // =========================================================================

    @Test
    @DisplayName("partycja z wierszem z załącznikiem po max retencji: DROP pominięty; po purge Poziomu 1 "
            + "(S3 PRZED wierszem) partycja pusta -> DROP; w dzienniku S3:<klucz> PRZED DROP:<partycja>")
    void s3ObjectDeletedBeforePartitionDrop_partitionDroppedOnlyAfterLevelOnePurge() {
        LocalDate month = monthsAgo(90);
        String partition = createEmailPartition(month);
        String key = s3KeyFor(tenant);
        UUID messageId = insertOrphanWithAttachment(tenant, month, key);

        // --- Przebieg 1: wiersz z załącznikiem wciąż jest -> DROP MUSI być pominięty ---
        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("przebieg 1: partycja %s z wierszem z załącznikiem NIE może zniknąć (WP-5)", partition)
                .isTrue();
        assertThat(EVENTS).as("przebieg 1: brak S3 i brak DROP dla tej partycji")
                .noneMatch(e -> e.equals("S3:" + key) || e.equals("DROP:" + partition));
        assertThat(messageExists(messageId)).isTrue();

        // --- Poziom 1 (BE-127 sweep osieroconych): S3 usuwane PRZED wierszem ---
        OrphanEmailPurgeBatch batch = emailService.purgeOrphansOlderThan(tenant, null, purgeCutoff(), 100);

        assertThat(batch.purgedMessages().deletedRows()).isEqualTo(1);
        assertThat(batch.purgedMessages().s3ObjectsDeleted()).isEqualTo(1);
        assertThat(messageExists(messageId)).isFalse();

        // --- Przebieg 2: partycja pusta -> DROP WYKONANY ---
        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("przebieg 2: partycja %s pusta po Poziomie 1 -> DROP (Poziom 2)", partition)
                .isFalse();
        createdPartitions.remove(partition);

        List<String> ours = EVENTS.stream()
                .filter(e -> e.equals("S3:" + key) || e.equals("DROP:" + partition))
                .toList();
        assertThat(ours)
                .as("kolejność: obiekt S3 usunięty PRZED dropem partycji (WP-5)")
                .containsExactly("S3:" + key, "DROP:" + partition);
    }

    // =========================================================================
    // Scenariusz 2 (WP-5, odporność): awaria S3 -> wiersz i partycja ZOSTAJĄ
    // =========================================================================

    @Test
    @DisplayName("awaria S3 w Poziomie 1: wiersz ZOSTAJE (nie ma osieroconego obiektu bez śladu), partycja NIE jest dropowana")
    void s3DeleteFailure_rowStays_partitionNotDropped() {
        LocalDate month = monthsAgo(91);
        String partition = createEmailPartition(month);
        String key = s3KeyFor(tenant);
        FAILING_KEYS.add(key);
        UUID messageId = insertOrphanWithAttachment(tenant, month, key);

        OrphanEmailPurgeBatch batch = emailService.purgeOrphansOlderThan(tenant, null, purgeCutoff(), 100);

        assertThat(batch.purgedMessages().s3Failures()).isEqualTo(1);
        assertThat(batch.purgedMessages().deletedRows()).isZero();
        assertThat(messageExists(messageId))
                .as("S3 nie usunięty -> wiersz z kluczem MUSI zostać (wskaźnik do ponowienia)")
                .isTrue();

        job.runReclaimJob();

        assertThat(partitionExists(partition))
                .as("partycja %s z wierszem po awarii S3 NIE może zniknąć", partition)
                .isTrue();
        assertThat(EVENTS).noneMatch(e -> e.equals("DROP:" + partition));
    }
}
