package com.contactcenter.domain.email;

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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny sweepu wiadomości e-mail OSIEROCONYCH ({@code contact_id IS NULL}) wg wieku
 * ({@link EmailMessageService#purgeOrphansOlderThan}/{@link EmailMessageService#countOrphansOlderThan},
 * BE-127, EPIC-30) na PRAWDZIWYM PostgreSQL (Testcontainers, pełny łańcuch Flyway — indeksy DB-059/V097
 * już tam są), z prawdziwym {@code EmailMessageRepository}, {@code EmailMessageServiceImpl},
 * {@code EmailAttachmentStorageServiceImpl}, Hibernate i {@code JpaTransactionManager}. Zamockowany
 * jest wyłącznie {@code S3Client} (wzorzec {@link EmailMessagePurgeIntegrationTest}).
 *
 * <p><strong>EXPLAIN na wolumenie ≥ 200 tys. wierszy pod {@code SET ROLE app_user}</strong> (AC BE-127)
 * wykonano RĘCZNIE na bazie scratch {@code scratch_be127} (210 000 wierszy/tabela, 60 tenantów,
 * ~20% osieroconych, wzorzec generatora z DB-059 poprawiony o niezależne hashe tenant/orphan — patrz
 * notatka wykonania BE-127 w {@code TASKS-BACKEND.md}); wszystkie trzy kształty zapytań
 * ({@code COUNT_ORPHANS_SQL}, {@code FIND_ORPHANS_FIRST_PAGE_SQL}, {@code FIND_ORPHANS_NEXT_PAGE_SQL})
 * użyły {@code idx_email_message_tenant_orphan_age}, bez Seq Scan. {@link QueryPlans} w tej klasie
 * odtwarza ten sam dowód na mniejszym wolumenie (30 tys., jak {@code OrphanMessagePurgeIndexesTest}
 * z DB-059) — cel testu automatycznego to dowód doboru planu na KAŻDYM przebiegu CI, nie pomiar skali.
 */
@DisplayName("EmailMessageService.purgeOrphansOlderThan/countOrphansOlderThan – prawdziwa baza + S3 (mock) (BE-127)")
class EmailMessageOrphanPurgeIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final String EMAIL_ORPHAN_INDEX = "idx_email_message_tenant_orphan_age";

    private static HikariDataSource superuserPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static EmailMessageService service;
    private static EmailMessageRepository repository;

    private FakeS3 s3;
    private UUID tenantA;
    private UUID tenantB;

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
        service = ctx.getBean(EmailMessageService.class);
        repository = ctx.getBean(EmailMessageRepository.class);
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

        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-127 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-127 " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private final class FakeS3 {
        final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failingKeys = Collections.synchronizedSet(new HashSet<>());

        DeleteObjectResponse delete(DeleteObjectRequest request) {
            assertThat(request.bucket()).isEqualTo(BUCKET);
            deleteCalls.add(request.key());
            if (failingKeys.contains(request.key())) {
                throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
            }
            return DeleteObjectResponse.builder().build();
        }
    }

    // =========================================================================
    // Seedowanie i asercje
    // =========================================================================

    private UUID insertMessage(UUID tenant, UUID contact, Instant receivedAt, Instant sentAt,
            Instant createdAt, String attachmentsJson) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO email_message
                            (message_id, tenant_id, contact_id, direction, from_address, to_address,
                             subject, body_text, attachments, received_at, sent_at, created_at)
                        VALUES (?, ?, ?, ?, 'klient@example.com', 'biuro@example.com',
                                'Temat PII', 'Treść PII', CAST(? AS jsonb), ?, ?, ?)
                        """,
                messageId, tenant, contact, receivedAt != null ? "INBOUND" : "OUTBOUND", attachmentsJson,
                receivedAt != null ? Timestamp.from(receivedAt) : null,
                sentAt != null ? Timestamp.from(sentAt) : null,
                Timestamp.from(createdAt));
        return messageId;
    }

    private static String attachments(String... keys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"filename\":\"f\",\"content_type\":\"application/pdf\",\"size_bytes\":1,\"s3_key\":\"")
                    .append(keys[i]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private boolean exists(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM email_message WHERE message_id = ?", Long.class, messageId) > 0;
    }

    /** Wstawia bare-bones {@code contact} (BE-128: kontakt „kwalifikujący się" / „nie kwalifikujący się"). */
    private UUID insertContact(UUID tenant, Instant startedAt) {
        UUID contactId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO contact (contact_id, tenant_id, channel, direction, status, started_at, queued_at)
                        VALUES (?, ?, 'EMAIL', 'INBOUND', 'COMPLETED', ?, ?)
                        """,
                contactId, tenant, Timestamp.from(startedAt), Timestamp.from(startedAt));
        return contactId;
    }

    // =========================================================================
    // Mieszanka AC (WP-1): stara osierocona / świeża osierocona / powiązana / stara osierocona tenanta B
    // =========================================================================

    @Nested
    @DisplayName("mieszanka AC — kryterium wieku, filtr resztkowy, izolacja tenantów, S3")
    class AcMixture {

        @Test
        @DisplayName("osierocona stara USUNIĘTA (+S3), świeża osierocona ZOSTAJE (filtr resztkowy), powiązana ZOSTAJE, stara osierocona tenanta B ZOSTAJE")
        void mixture_onlyOldOrphanOfTenantAIsPurged() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            String key = EmailAttachmentKeys.inboundKey(tenantA, UUID.randomUUID(), "a.pdf");

            // Osierocona stara: received_at dawno przed cutoff, created_at też dawno (nie w oknie resztkowym).
            UUID oldOrphan = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), null,
                    cutoff.minus(10, ChronoUnit.DAYS), attachments(key));

            // Osierocona ŚWIEŻA: received_at (INTERNALDATE) sugeruje "starą" wiadomość (np. skrzynka
            // zmigrowana z historycznymi datami), ale created_at (zapis do bazy) jest SPRZED chwili —
            // filtr resztkowy (created_at < now() - 1 dzień) musi ją ochronić przed usunięciem w trakcie routingu.
            UUID freshOrphan = insertMessage(tenantA, null, cutoff.minus(400, ChronoUnit.DAYS), null,
                    Instant.now(), "[]");

            // Powiązana z kontaktem: nie jest sierotą, niezależnie od wieku.
            UUID linked = insertMessage(tenantA, UUID.randomUUID(), cutoff.minus(10, ChronoUnit.DAYS), null,
                    cutoff.minus(10, ChronoUnit.DAYS), "[]");

            // Stara osierocona TENANTA B: izolacja — nie może zniknąć przy sweepie tenanta A.
            UUID oldOrphanTenantB = insertMessage(tenantB, null, cutoff.minus(10, ChronoUnit.DAYS), null,
                    cutoff.minus(10, ChronoUnit.DAYS), "[]");

            OrphanEmailPurgeBatch batch = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(batch.candidatesFound()).isEqualTo(1);
            assertThat(batch.purgedMessages().deletedRows()).isEqualTo(1);
            assertThat(batch.purgedMessages().s3ObjectsDeleted()).isEqualTo(1);
            assertThat(s3.deleteCalls).containsExactly(key);

            assertThat(exists(oldOrphan)).isFalse();
            assertThat(exists(freshOrphan)).isTrue();
            assertThat(exists(linked)).isTrue();
            assertThat(exists(oldOrphanTenantB)).isTrue();
        }

        @Test
        @DisplayName("granica cutoff: wiek DOKŁADNIE na cutoff jest WYKLUCZONY (semantyka <)")
        void cutoffBoundary_isExclusive() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            Instant longAgo = cutoff.minus(400, ChronoUnit.DAYS);
            UUID atCutoff = insertMessage(tenantA, null, cutoff, null, longAgo, "[]");
            UUID beforeCutoff = insertMessage(tenantA, null, cutoff.minusSeconds(1), null, longAgo, "[]");

            OrphanEmailPurgeBatch batch = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(batch.candidatesFound()).isEqualTo(1);
            assertThat(exists(atCutoff)).isTrue();
            assertThat(exists(beforeCutoff)).isFalse();
        }
    }

    // =========================================================================
    // countOrphansOlderThan – dry-run
    // =========================================================================

    @Nested
    @DisplayName("countOrphansOlderThan (dry-run)")
    class CountOrphans {

        @Test
        @DisplayName("liczy DOKŁADNIE te same wiadomości, które purgeOrphansOlderThan by usunął (te same kryteria)")
        void count_matchesPurgeCandidates() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            Instant longAgo = cutoff.minus(400, ChronoUnit.DAYS);
            for (int i = 0; i < 5; i++) {
                insertMessage(tenantA, null, cutoff.minus(i + 1, ChronoUnit.DAYS), null, longAgo, "[]");
            }
            insertMessage(tenantA, null, cutoff.minus(1, ChronoUnit.DAYS), null, Instant.now(), "[]"); // świeża, w oknie resztkowym
            insertMessage(tenantA, UUID.randomUUID(), longAgo, null, longAgo, "[]"); // powiązana

            assertThat(service.countOrphansOlderThan(tenantA, cutoff)).isEqualTo(5);
        }

        @Test
        @DisplayName("brak kandydatów -> 0, nie wyjątek")
        void noCandidates_returnsZero() {
            assertThat(service.countOrphansOlderThan(tenantA, Instant.now())).isZero();
        }
    }

    // =========================================================================
    // Idempotencja i awaria S3
    // =========================================================================

    @Nested
    @DisplayName("idempotencja i awaria S3")
    class IdempotencyAndS3Failure {

        @Test
        @DisplayName("ponowne wywołanie po pełnym sukcesie -> candidatesFound=0, S3 nietknięte")
        void secondCallAfterSuccess_isNoOp() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), null, cutoff.minus(10, ChronoUnit.DAYS), "[]");

            service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);
            s3.deleteCalls.clear();

            OrphanEmailPurgeBatch second = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(second.candidatesFound()).isZero();
            assertThat(second.purgedMessages()).isEqualTo(PurgedMessages.empty());
            assertThat(s3.deleteCalls).isEmpty();
        }

        @Test
        @DisplayName("awaria S3 -> wiadomość ZOSTAJE, s3Failures=1; drugi przebieg (S3 sprawny) usuwa ją (idempotencja)")
        void s3Failure_rowStays_thenSucceedsOnRetry() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            String key = EmailAttachmentKeys.inboundKey(tenantA, UUID.randomUUID(), "a.pdf");
            UUID orphan = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), null,
                    cutoff.minus(10, ChronoUnit.DAYS), attachments(key));
            s3.failingKeys.add(key);

            OrphanEmailPurgeBatch first = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(first.candidatesFound()).isEqualTo(1);
            assertThat(first.purgedMessages().deletedRows()).isZero();
            assertThat(first.purgedMessages().s3Failures()).isEqualTo(1);
            assertThat(exists(orphan)).isTrue();

            s3.failingKeys.clear();
            OrphanEmailPurgeBatch second = service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(second.purgedMessages().deletedRows()).isEqualTo(1);
            assertThat(exists(orphan)).isFalse();
        }

        @Test
        @DisplayName("awaria S3 trwała + stronicowanie: kursor przesuwa się mimo braku usunięć -> pętla wołającego się kończy (brak nieskończonej pętli)")
        void permanentS3Failure_cursorStillAdvances_terminatesLoop() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            Instant longAgo = cutoff.minus(400, ChronoUnit.DAYS);
            String key1 = EmailAttachmentKeys.inboundKey(tenantA, UUID.randomUUID(), "a.pdf");
            String key2 = EmailAttachmentKeys.inboundKey(tenantA, UUID.randomUUID(), "b.pdf");
            insertMessage(tenantA, null, cutoff.minus(2, ChronoUnit.DAYS), null, longAgo, attachments(key1));
            insertMessage(tenantA, null, cutoff.minus(1, ChronoUnit.DAYS), null, longAgo, attachments(key2));
            s3.failingKeys.add(key1);
            s3.failingKeys.add(key2);

            // batchSize=1: symuluje pętlę wołającego (RetentionPurgeServiceImpl) strona po stronie.
            EmailOrphanCursor cursor = null;
            int iterations = 0;
            OrphanEmailPurgeBatch batch;
            do {
                batch = service.purgeOrphansOlderThan(tenantA, cursor, cutoff, 1);
                if (batch.candidatesFound() > 0) {
                    cursor = batch.nextCursor();
                }
                iterations++;
                assertThat(iterations).as("pętla musi się skończyć — brak nieskończonej pętli mimo 0 usunięć na każdej stronie")
                        .isLessThanOrEqualTo(10);
            } while (batch.candidatesFound() == 1);

            assertThat(iterations).isEqualTo(3); // 2 strony z kandydatem (po 1) + 1 pusta strona kończąca
            assertThat(batch.candidatesFound()).isZero();
        }
    }

    // =========================================================================
    // Stronicowanie keyset (H-1)
    // =========================================================================

    @Nested
    @DisplayName("stronicowanie keyset")
    class KeysetPagination {

        @Test
        @DisplayName("druga strona z kursorem = ostatni kandydat pierwszej strony nie powtarza ani nie gubi wierszy")
        void keysetPagination_noOverlapNoGaps() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            Instant longAgo = cutoff.minus(400, ChronoUnit.DAYS);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                ids.add(insertMessage(tenantA, null, cutoff.minus(i + 1, ChronoUnit.MINUTES), null, longAgo, "[]"));
            }

            OrphanEmailPurgeBatch page1 = service.purgeOrphansOlderThan(tenantA, null, cutoff, 10);
            assertThat(page1.candidatesFound()).isEqualTo(10);

            OrphanEmailPurgeBatch page2 = service.purgeOrphansOlderThan(tenantA, page1.nextCursor(), cutoff, 10);
            assertThat(page2.candidatesFound()).isEqualTo(10);

            OrphanEmailPurgeBatch page3 = service.purgeOrphansOlderThan(tenantA, page2.nextCursor(), cutoff, 10);
            assertThat(page3.candidatesFound()).isEqualTo(5);

            // Wszystkie 25 usunięte, dokładnie raz każda (deletedRows sumuje się do 25, bez duplikatów).
            int totalDeleted = page1.purgedMessages().deletedRows() + page2.purgedMessages().deletedRows()
                    + page3.purgedMessages().deletedRows();
            assertThat(totalDeleted).isEqualTo(25);
            assertThat(ids).allSatisfy(id -> assertThat(exists(id)).isFalse());
        }
    }

    // =========================================================================
    // countLinkedToContactsOlderThan (BE-128) – wiadomości POWIĄZANE z kontaktem kwalifikującym się
    // =========================================================================

    @Nested
    @DisplayName("countLinkedToContactsOlderThan (BE-128)")
    class CountLinkedToContacts {

        @Test
        @DisplayName("liczy WYŁĄCZNIE wiadomości powiązane z kontaktem starszym niż cutoff — młodszy kontakt i sierota wykluczone")
        void countsOnlyMessagesOfEligibleContacts() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID oldContact = insertContact(tenantA, cutoff.minus(10, ChronoUnit.DAYS));
            UUID youngContact = insertContact(tenantA, cutoff.plus(10, ChronoUnit.DAYS));

            // 2 wiadomości powiązane ze starym (kwalifikującym się) kontaktem — obie liczone,
            // niezależnie od WŁASNEGO wieku wiadomości (kryterium jest wiek KONTAKTU, nie wiadomości).
            insertMessage(tenantA, oldContact, Instant.now(), null, Instant.now(), "[]");
            insertMessage(tenantA, oldContact, cutoff.minus(5, ChronoUnit.DAYS), null,
                    cutoff.minus(5, ChronoUnit.DAYS), "[]");
            // Powiązana z młodym kontaktem — NIE liczona.
            insertMessage(tenantA, youngContact, cutoff.minus(5, ChronoUnit.DAYS), null,
                    cutoff.minus(5, ChronoUnit.DAYS), "[]");
            // Sierota (contact_id IS NULL) — poza zakresem tej metody (liczy ją countOrphansOlderThan).
            insertMessage(tenantA, null, cutoff.minus(5, ChronoUnit.DAYS), null,
                    cutoff.minus(5, ChronoUnit.DAYS), "[]");

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(2);
        }

        @Test
        @DisplayName("granica cutoff: started_at kontaktu DOKŁADNIE na cutoff jest WYKLUCZONY (semantyka <, jak ContactRepository#findContactIdsOlderThan)")
        void contactCutoffBoundary_isExclusive() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID atCutoff = insertContact(tenantA, cutoff);
            UUID beforeCutoff = insertContact(tenantA, cutoff.minusSeconds(1));
            insertMessage(tenantA, atCutoff, Instant.now(), null, Instant.now(), "[]");
            insertMessage(tenantA, beforeCutoff, Instant.now(), null, Instant.now(), "[]");

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(1);
        }

        @Test
        @DisplayName("izolacja tenantów: kontakt+wiadomość tenanta B nie wchodzą do liczby tenanta A")
        void isolatesByTenant() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID contactA = insertContact(tenantA, cutoff.minus(10, ChronoUnit.DAYS));
            UUID contactB = insertContact(tenantB, cutoff.minus(10, ChronoUnit.DAYS));
            insertMessage(tenantA, contactA, Instant.now(), null, Instant.now(), "[]");
            insertMessage(tenantB, contactB, Instant.now(), null, Instant.now(), "[]");

            assertThat(service.countLinkedToContactsOlderThan(tenantA, cutoff)).isEqualTo(1);
        }

        @Test
        @DisplayName("brak kandydatów -> 0, nie wyjątek")
        void noCandidates_returnsZero() {
            assertThat(service.countLinkedToContactsOlderThan(tenantA, Instant.now())).isZero();
        }
    }

    // =========================================================================
    // TenantContext (WP-2)
    // =========================================================================

    @Nested
    @DisplayName("TenantContext (WP-2)")
    class TenantContextHandling {

        @Test
        @DisplayName("pusty TenantContext -> IllegalStateException (serwis i repozytorium); baza nietknięta")
        void emptyTenantContext_throwsIllegalState() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            UUID m = insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), null,
                    cutoff.minus(10, ChronoUnit.DAYS), "[]");
            TenantContext.clear();

            assertThatThrownBy(() -> service.purgeOrphansOlderThan(tenantA, null, cutoff, 100))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.countOrphansOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.findOrphansOlderThan(tenantA, cutoff, null, 100))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(exists(m)).isTrue();
            assertThat(s3.deleteCalls).isEmpty();
        }

        @Test
        @DisplayName("(BE-128) pusty TenantContext -> IllegalStateException dla countLinkedToContactsOlderThan (serwis i repozytorium) — regresja BE-112")
        void emptyTenantContext_throwsIllegalState_countLinkedToContacts() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            TenantContext.clear();

            assertThatThrownBy(() -> service.countLinkedToContactsOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.countLinkedToContactsOlderThan(tenantA, cutoff))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("purgeOrphansOlderThan NIGDY nie czyści TenantContext")
        void tenantContextSurvivesPurge() {
            Instant cutoff = Instant.now().minus(180, ChronoUnit.DAYS);
            insertMessage(tenantA, null, cutoff.minus(10, ChronoUnit.DAYS), null, cutoff.minus(10, ChronoUnit.DAYS), "[]");

            service.purgeOrphansOlderThan(tenantA, null, cutoff, 100);

            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(tenantA);
        }
    }

    // =========================================================================
    // Plan zapytań (EXPLAIN) — na mniejszym wolumenie; ≥200 tys. zweryfikowane ręcznie na scratch
    // =========================================================================

    @Nested
    @DisplayName("plan zapytań (EXPLAIN)")
    class QueryPlans {

        @Test
        @DisplayName("COUNT_ORPHANS_SQL / FIND_ORPHANS_FIRST_PAGE_SQL / FIND_ORPHANS_NEXT_PAGE_SQL używają idx_email_message_tenant_orphan_age, bez Seq Scan")
        void explain_usesOrphanIndex() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN BE-127 " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO email_message
                                    (message_id, tenant_id, contact_id, direction, from_address, to_address,
                                     attachments, received_at, sent_at, created_at)
                                SELECT gen_random_uuid(), ?,
                                       CASE WHEN abs(hashtext('orphan-' || g)) % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                       CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                       'a@a.pl', 'b@b.pl', '[]'::jsonb,
                                       CASE WHEN g % 2 = 0 THEN now() - ((g % 400) || ' days')::interval ELSE NULL END,
                                       CASE WHEN g % 2 = 1 THEN now() - ((g % 400) || ' days')::interval ELSE NULL END,
                                       now() - ((g % 400) || ' days')::interval - interval '2 days'
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE email_message");

                String tenantLiteral = "'" + tenant + "'";
                String residual = "(now() - interval '1 day')";
                String cutoff = "(now() - interval '180 days')";

                String countSql = EmailMessageRepository.COUNT_ORPHANS_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff).replace(":residualCutoff", residual);
                String firstPageSql = EmailMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff).replace(":residualCutoff", residual)
                        .replace(":batchSize", "100");
                String nextPageSql = EmailMessageRepository.FIND_ORPHANS_NEXT_PAGE_SQL
                        .replace(":tenantId", tenantLiteral).replace(":cutoff", cutoff).replace(":residualCutoff", residual)
                        .replace(":cursorMessageAt", "(now() - interval '190 days')")
                        .replace(":cursorMessageId", "'00000000-0000-0000-0000-000000000000'")
                        .replace(":batchSize", "100");

                String countPlan = explain(countSql);
                String firstPagePlan = explain(firstPageSql);
                String nextPagePlan = explain(nextPageSql);

                assertThat(countPlan).contains(EMAIL_ORPHAN_INDEX).doesNotContain("Seq Scan");
                assertThat(firstPagePlan).contains(EMAIL_ORPHAN_INDEX).doesNotContain("Seq Scan");
                assertThat(nextPagePlan).contains(EMAIL_ORPHAN_INDEX).doesNotContain("Seq Scan");
                System.out.println("[EXPLAIN BE-127 COUNT]\n" + countPlan);
                System.out.println("[EXPLAIN BE-127 FIRST PAGE]\n" + firstPagePlan);
                System.out.println("[EXPLAIN BE-127 NEXT PAGE]\n" + nextPagePlan);
            } finally {
                jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("pod SET ROLE app_user + GUC tenanta: nadal idx_email_message_tenant_orphan_age (RLS nie wymusza Seq Scan)")
        void underAppUserRole_stillUsesIndex() {
            String role = "cc_be127_orphan";
            String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, role);
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN BE-127 RLS " + UUID.randomUUID());
            jdbc.update("""
                            INSERT INTO email_message
                                (message_id, tenant_id, contact_id, direction, from_address, to_address,
                                 attachments, received_at, sent_at, created_at)
                            SELECT gen_random_uuid(), ?,
                                   CASE WHEN abs(hashtext('orphan-' || g)) % 5 = 0 THEN NULL ELSE gen_random_uuid() END,
                                   CASE WHEN g % 2 = 0 THEN 'INBOUND' ELSE 'OUTBOUND' END,
                                   'a@a.pl', 'b@b.pl', '[]'::jsonb,
                                   CASE WHEN g % 2 = 0 THEN now() - ((g % 400) || ' days')::interval ELSE NULL END,
                                   CASE WHEN g % 2 = 1 THEN now() - ((g % 400) || ' days')::interval ELSE NULL END,
                                   now() - ((g % 400) || ' days')::interval - interval '2 days'
                            FROM generate_series(1, 30000) g
                            """, tenant);
            jdbc.execute("ANALYZE email_message");

            try (HikariDataSource restrictedPool = PostgresTestDatabase.pool(role, password, 1)) {
                JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
                restrictedJdbc.execute("BEGIN");
                try {
                    restrictedJdbc.execute("SET LOCAL ROLE " + role);
                    restrictedJdbc.queryForObject(
                            "SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());

                    String sql = EmailMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL
                            .replace(":tenantId", "'" + tenant + "'")
                            .replace(":cutoff", "(now() - interval '180 days')")
                            .replace(":residualCutoff", "(now() - interval '1 day')")
                            .replace(":batchSize", "100");
                    String plan = String.join("\n", restrictedJdbc.queryForList("EXPLAIN " + sql, String.class));

                    assertThat(plan).contains(EMAIL_ORPHAN_INDEX).doesNotContain("Seq Scan");
                    System.out.println("[EXPLAIN BE-127 pod app_user]\n" + plan);
                } finally {
                    restrictedJdbc.execute("ROLLBACK");
                }
            } finally {
                jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("SQL nie używa ctid")
        void sql_doesNotUseCtid() {
            assertThat(EmailMessageRepository.COUNT_ORPHANS_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(EmailMessageRepository.FIND_ORPHANS_FIRST_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(EmailMessageRepository.FIND_ORPHANS_NEXT_PAGE_SQL).doesNotContainPattern("(?i)\\bctid\\b");
        }

        private String explain(String sql) {
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        }
    }
}
