package com.contactcenter.domain.email;

import com.contactcenter.domain.tenant.TenantService;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny sweepu porzuconych załączników {@code pending/} w S3 ({@link
 * PendingAttachmentSweepJob#sweepTenant}, BE-131, EPIC-30) na PRAWDZIWYM PostgreSQL (Testcontainers,
 * pełny łańcuch Flyway) z prawdziwymi: {@code EmailMessageRepository}, {@code EmailMessageServiceImpl}
 * (referencje {@code email_message.attachments[*].s3_key} — źródło prawdy czy obiekt jest porzucony),
 * {@code EmailAttachmentStorageServiceImpl}, Hibernate i {@code JpaTransactionManager}. Zamockowany
 * jest wyłącznie {@code S3Client} (wzorzec {@code EmailMessageOrphanPurgeIntegrationTest}) — fałszywy
 * S3 z listingiem po prefiksie i konfigurowalnymi awariami.
 *
 * <p>{@code TenantService} jest mockiem BEZ interakcji — testy wołają {@code sweepTenant(tenantId)}
 * bezpośrednio (nie {@code runSweep()}), więc lista tenantów nie jest potrzebna.
 */
@DisplayName("PendingAttachmentSweepJob.sweepTenant – prawdziwa baza + S3 (mock) (BE-131)")
class PendingAttachmentSweepJobIntegrationTest {

    private static final String BUCKET = "test-bucket";

    private static HikariDataSource superuserPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static EmailMessageService emailMessageService;
    private static EmailAttachmentStorageService attachmentStorageService;

    private FakeS3 s3;
    private PendingAttachmentSweepJob job;
    private UUID tenantA;
    private UUID tenantB;

    // =========================================================================
    // Infrastruktura
    // =========================================================================

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        appPool = PostgresTestDatabase.superuserPool(2);

        s3Client = mock(S3Client.class);
        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);

        ctx = JpaTestContext.create(
                appPool,
                new Class<?>[]{EmailMessage.class},
                new Class<?>[]{EmailMessageRepository.class, EmailMessageServiceImpl.class,
                        EmailAttachmentStorageServiceImpl.class},
                c -> {
                    c.getBeanFactory().registerSingleton("s3Client", s3Client);
                    c.getBeanFactory().registerSingleton("s3Presigner", mock(S3Presigner.class));
                    c.getBeanFactory().registerSingleton("s3Properties", s3Properties);
                });
        emailMessageService = ctx.getBean(EmailMessageService.class);
        attachmentStorageService = ctx.getBean(EmailAttachmentStorageService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        appPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        reset(s3Client);
        s3 = new FakeS3();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> s3.delete(inv.getArgument(0)));
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv -> s3.list(inv.getArgument(0)));

        job = new PendingAttachmentSweepJob(mock(TenantService.class), emailMessageService, attachmentStorageService);
        ReflectionTestUtils.setField(job, "pendingTtlHours", 72L);
        ReflectionTestUtils.setField(job, "deleteEnabled", true); // domyślne Java (false) testowane osobno

        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-131 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-131 " + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Fałszywy S3: listing po prefiksie (jak prawdziwy {@code ListObjectsV2}) + usuwanie z awariami. */
    private final class FakeS3 {
        final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failingKeys = Collections.synchronizedSet(new java.util.HashSet<>());
        final Map<String, Instant> objects = new LinkedHashMap<>();

        void put(String key, Instant lastModified) {
            objects.put(key, lastModified);
        }

        DeleteObjectResponse delete(DeleteObjectRequest request) {
            assertThat(request.bucket()).isEqualTo(BUCKET);
            deleteCalls.add(request.key());
            if (failingKeys.contains(request.key())) {
                throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
            }
            objects.remove(request.key());
            return DeleteObjectResponse.builder().build();
        }

        ListObjectsV2Response list(ListObjectsV2Request request) {
            assertThat(request.bucket()).isEqualTo(BUCKET);
            String prefix = request.prefix();
            List<S3Object> contents = objects.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .map(e -> S3Object.builder().key(e.getKey()).lastModified(e.getValue()).build())
                    .toList();
            return ListObjectsV2Response.builder().contents(contents).isTruncated(false).build();
        }
    }

    // =========================================================================
    // Seedowanie
    // =========================================================================

    private UUID insertOutboundMessage(UUID tenant, Instant createdAt, String... s3Keys) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO email_message
                            (message_id, tenant_id, contact_id, direction, from_address, to_address,
                             subject, body_text, attachments, sent_at, created_at)
                        VALUES (?, ?, NULL, 'OUTBOUND', 'biuro@example.com', 'klient@example.com',
                                'Re: PII', 'Treść PII', CAST(? AS jsonb), ?, ?)
                        """,
                messageId, tenant, attachments(s3Keys), Timestamp.from(createdAt), Timestamp.from(createdAt));
        return messageId;
    }

    private static String attachments(String... keys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"filename\":\"f.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":1,\"s3_key\":\"")
                    .append(keys[i]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private String pendingKey(UUID tenant) {
        return EmailAttachmentKeys.pendingKey(tenant, UUID.randomUUID(), "a.pdf");
    }

    private String inboundKey(UUID tenant) {
        return EmailAttachmentKeys.inboundKey(tenant, UUID.randomUUID(), "b.pdf");
    }

    // =========================================================================
    // AC BE-131
    // =========================================================================

    @Nested
    @DisplayName("mieszanka AC — TTL, referencje, izolacja prefiksu/tenanta, odporność na błędy")
    class AcMixture {

        @Test
        @DisplayName("pending/ starszy niż TTL i NIEreferencjonowany -> USUNIĘTY")
        void expiredAndUnreferenced_isDeleted() {
            String key = pendingKey(tenantA);
            s3.put(key, Instant.now().minus(100, ChronoUnit.HOURS));

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result.deleted()).isEqualTo(1);
            assertThat(result.keptReferenced()).isZero();
            assertThat(result.errors()).isZero();
            assertThat(s3.deleteCalls).containsExactly(key);
            assertThat(s3.objects).doesNotContainKey(key);
        }

        @Test
        @DisplayName("pending/ MŁODSZY niż TTL -> NIEusunięty (niezależnie od referencji)")
        void withinTtl_isNeverDeleted() {
            String key = pendingKey(tenantA);
            s3.put(key, Instant.now().minus(1, ChronoUnit.HOURS));

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result.deleted()).isZero();
            assertThat(s3.deleteCalls).isEmpty();
            assertThat(s3.objects).containsKey(key);
        }

        @Test
        @DisplayName("REGRESJA KRYTYCZNA (demo: 8 z 9): pending/ starszy niż TTL, ale wskazywany przez email_message wysłanej wiadomości -> NIGDY usunięty")
        void expiredButReferencedByOutboundMessage_isNeverDeleted() {
            String key = pendingKey(tenantA);
            s3.put(key, Instant.now().minus(1000, ChronoUnit.HOURS));
            insertOutboundMessage(tenantA, Instant.now().minus(999, ChronoUnit.HOURS), key);

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result.deleted()).isZero();
            assertThat(result.keptReferenced()).isEqualTo(1);
            assertThat(s3.deleteCalls).isEmpty();
            assertThat(s3.objects).containsKey(key);
        }

        @Test
        @DisplayName("obiekt POZA pending/ (klucz {messageId}/) nigdy nie wchodzi do listingu -> nigdy nieusunięty")
        void objectOutsidePendingPrefix_isNeverListedOrDeleted() {
            String pending = pendingKey(tenantA);
            String inbound = inboundKey(tenantA);
            s3.put(pending, Instant.now().minus(1000, ChronoUnit.HOURS));
            s3.put(inbound, Instant.now().minus(1000, ChronoUnit.HOURS)); // stary obiekt INBOUND, poza zakresem sweepu

            job.sweepTenant(tenantA);

            assertThat(s3.deleteCalls).containsExactly(pending);
            assertThat(s3.objects).containsKey(inbound); // nietknięty
        }

        @Test
        @DisplayName("błąd usunięcia JEDNEGO obiektu nie przerywa przetwarzania pozostałych w batchu")
        void singleObjectFailure_doesNotStopBatch() {
            String failing = pendingKey(tenantA);
            String ok = pendingKey(tenantA);
            s3.put(failing, Instant.now().minus(200, ChronoUnit.HOURS));
            s3.put(ok, Instant.now().minus(200, ChronoUnit.HOURS));
            s3.failingKeys.add(failing);

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result.deleted()).isEqualTo(1);
            assertThat(result.errors()).isEqualTo(1);
            assertThat(s3.deleteCalls).containsExactlyInAnyOrder(failing, ok);
            assertThat(s3.objects).containsKey(failing);   // awaria -> obiekt zostaje
            assertThat(s3.objects).doesNotContainKey(ok);  // sukces -> usunięty
        }

        @Test
        @DisplayName("izolacja tenantów: kandydat tenanta B nietknięty przy sweepie tenanta A")
        void isolatesByTenant() {
            String keyA = pendingKey(tenantA);
            String keyB = pendingKey(tenantB);
            s3.put(keyA, Instant.now().minus(200, ChronoUnit.HOURS));
            s3.put(keyB, Instant.now().minus(200, ChronoUnit.HOURS));

            job.sweepTenant(tenantA);

            assertThat(s3.deleteCalls).containsExactly(keyA);
            assertThat(s3.objects).containsKey(keyB);
        }

        @Test
        @DisplayName("granica TTL: tuż MŁODSZY niż TTL zostaje, tuż STARSZY niż TTL jest usuwany (semantyka <)")
        void aroundTtlBoundary_onlyOlderIsDeleted() {
            // Cutoff jest liczony WEWNĄTRZ sweepTenant (Instant.now() w chwili wywołania), więc test
            // zostawia margines (minuty), a nie sekundy/nanosekundy – inaczej czas wykonania testu
            // sam przesuwałby granicę i test byłby zależny od timingu (flaky).
            String justYounger = pendingKey(tenantA); // 71h < TTL(72h) -> zostaje
            String justOlder = pendingKey(tenantA);   // 73h > TTL(72h) -> usunięty
            s3.put(justYounger, Instant.now().minus(71, ChronoUnit.HOURS));
            s3.put(justOlder, Instant.now().minus(73, ChronoUnit.HOURS));

            job.sweepTenant(tenantA);

            assertThat(s3.objects).containsKey(justYounger);           // NIE usunięty (młodszy niż TTL)
            assertThat(s3.objects).doesNotContainKey(justOlder);       // usunięty (starszy niż TTL)
        }

        @Test
        @DisplayName("brak kandydatów pending -> no-op, zapytanie o referencje w DB pominięte")
        void noCandidates_isNoOp() {
            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result).isEqualTo(PendingAttachmentSweepJob.SweepResult.empty());
            assertThat(s3.deleteCalls).isEmpty();
        }
    }

    // =========================================================================
    // Bezpiecznik dry-run (email.attachments.pending-sweep-delete-enabled=false, domyślne)
    // =========================================================================

    @Nested
    @DisplayName("bezpiecznik dry-run (deleteEnabled=false)")
    class DryRunSafetySwitch {

        @Test
        @DisplayName("deleteEnabled=false -> kandydat logowany jako dry-run, S3 NIETKNIĘTY")
        void deleteDisabled_logsCandidateWithoutDeleting() {
            ReflectionTestUtils.setField(job, "deleteEnabled", false);
            String key = pendingKey(tenantA);
            s3.put(key, Instant.now().minus(200, ChronoUnit.HOURS));

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result.dryRunCandidates()).isEqualTo(1);
            assertThat(result.deleted()).isZero();
            assertThat(s3.deleteCalls).isEmpty();
            assertThat(s3.objects).containsKey(key);
        }
    }

    // =========================================================================
    // TenantContext
    // =========================================================================

    @Nested
    @DisplayName("TenantContext")
    class TenantContextHandling {

        @Test
        @DisplayName("sweepTenant ZAWSZE czyści TenantContext na końcu (wzorzec RecordingRetentionJob)")
        void sweepTenant_alwaysClearsTenantContext() {
            String key = pendingKey(tenantA);
            s3.put(key, Instant.now().minus(200, ChronoUnit.HOURS));

            job.sweepTenant(tenantA);

            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }

        @Test
        @DisplayName("błąd listowania S3 dla jednego tenanta nie przerywa (zwraca empty, czyści kontekst)")
        void listingFailure_isContainedAndContextCleared() {
            reset(s3Client);
            when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenThrow((S3Exception) S3Exception.builder().message("boom").statusCode(500).build());

            PendingAttachmentSweepJob.SweepResult result = job.sweepTenant(tenantA);

            assertThat(result).isEqualTo(PendingAttachmentSweepJob.SweepResult.empty());
            assertThat(TenantContext.getTenantIdOrNull()).isNull();
        }
    }
}
