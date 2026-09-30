package com.contactcenter.domain.email;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny {@link EmailMessageService#purgeByContactIds} (BE-125) na PRAWDZIWYM PostgreSQL
 * (Testcontainers, pełny łańcuch Flyway) z prawdziwymi: {@code EmailMessageRepository},
 * {@code EmailMessageServiceImpl}, {@code EmailAttachmentStorageServiceImpl}, Hibernate,
 * {@code JpaTransactionManager} i proxy {@code @Transactional}. Zamockowany jest wyłącznie
 * {@code S3Client} (wzorzec {@code RecordingServiceTest}) — jako fałszywy S3 z konfigurowalnymi
 * awariami.
 *
 * <p>Dlaczego nie mocki {@code EntityManager}: nie łapały błędów natywnego SQL, mapowania Hibernate
 * i {@code TenantContext} (lekcje EPIC-29). Izolacja tenantów jest asertowana PO WARTOŚCIACH
 * (COUNT/ID w bazie), nie przez „nie rzuciło wyjątku".
 *
 * <p>Pula aplikacji ma rozmiar 1 — pozwala udowodnić, że w trakcie I/O do S3 nie jest trzymane żadne
 * połączenie z bazą (brak transakcji wokół S3).
 */
@DisplayName("EmailMessageService.purgeByContactIds – prawdziwa baza + S3 (mock) (BE-125)")
class EmailMessagePurgeIntegrationTest {

    private static final String BUCKET = "test-bucket";

    private static HikariDataSource superuserPool;   // seedowanie i asercje stanu (omija RLS)
    private static HikariDataSource appPool;         // pula aplikacji, rozmiar 1
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static EmailMessageService service;
    private static EmailMessageRepository repository;

    private FakeS3 s3;
    private UUID tenantA;
    private UUID tenantB;

    // =========================================================================
    // Infrastruktura
    // =========================================================================

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        appPool = PostgresTestDatabase.superuserPool(1);

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

        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – BE-125 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – BE-125 " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Fałszywy S3: zapamiętuje usunięte klucze, potrafi zawodzić dla wybranych kluczy i mierzy aktywne połączenia DB. */
    private final class FakeS3 {
        final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failingKeys = Collections.synchronizedSet(new HashSet<>());
        final AtomicInteger maxActiveDbConnectionsDuringS3 = new AtomicInteger();

        DeleteObjectResponse delete(DeleteObjectRequest request) {
            assertThat(request.bucket()).isEqualTo(BUCKET);
            maxActiveDbConnectionsDuringS3.accumulateAndGet(
                    appPool.getHikariPoolMXBean().getActiveConnections(), Math::max);
            deleteCalls.add(request.key());
            if (failingKeys.contains(request.key())) {
                throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
            }
            return DeleteObjectResponse.builder().build();
        }

        long calls(String key) {
            return deleteCalls.stream().filter(key::equals).count();
        }
    }

    // =========================================================================
    // Seedowanie i asercje
    // =========================================================================

    /** Wiadomość zaseedowana w bazie wraz z kluczami S3 zapisanymi w jej JSONB. */
    private record Seeded(UUID messageId, UUID contactId, List<String> keys) {
    }

    private UUID insertMessage(UUID tenant, UUID contact, String direction, String attachmentsJson) {
        return insertMessage(UUID.randomUUID(), tenant, contact, direction, attachmentsJson);
    }

    private UUID insertMessage(UUID messageId, UUID tenant, UUID contact, String direction, String attachmentsJson) {
        jdbc.update("""
                        INSERT INTO email_message
                            (message_id, tenant_id, contact_id, direction, from_address, to_address,
                             subject, body_text, attachments, received_at)
                        VALUES (?, ?, ?, ?, 'klient@example.com', 'biuro@example.com',
                                'Temat PII', 'Treść PII', CAST(? AS jsonb), now())
                        """,
                messageId, tenant, contact, direction, attachmentsJson);
        return messageId;
    }

    /** INBOUND z załącznikami pod {@code email-attachments/{tenant}/{messageId}/{plik}} (schemat zapisu z pollingu IMAP). */
    private Seeded seedInbound(UUID tenant, UUID contact, String... fileNames) {
        UUID messageId = UUID.randomUUID();
        List<String> keys = java.util.Arrays.stream(fileNames)
                .map(f -> EmailAttachmentKeys.inboundKey(tenant, messageId, f))
                .toList();
        insertMessage(messageId, tenant, contact, "INBOUND", attachments(keys.toArray(String[]::new)));
        return new Seeded(messageId, contact, keys);
    }

    /** OUTBOUND z podanymi kluczami (dane od klienta — mogą wskazywać dowolny obiekt). */
    private Seeded seedOutbound(UUID tenant, UUID contact, String... keys) {
        UUID messageId = insertMessage(tenant, contact, "OUTBOUND", attachments(keys));
        return new Seeded(messageId, contact, List.of(keys));
    }

    private static String attachments(String... keys) {
        return java.util.Arrays.stream(keys)
                .map(k -> "{\"filename\":\"f\",\"content_type\":\"application/pdf\",\"size_bytes\":1,\"s3_key\":\"" + k + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private boolean exists(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM email_message WHERE message_id = ?", Long.class, messageId) > 0;
    }

    private long count(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM email_message WHERE tenant_id = ?", Long.class, tenant);
    }

    // =========================================================================
    // S3 przed wierszem
    // =========================================================================

    @Nested
    @DisplayName("S3 przed wierszem")
    class S3BeforeRow {

        @Test
        @DisplayName("wiadomość z 2 załącznikami → oba obiekty usunięte, wiersz usunięty, liczniki zgodne")
        void twoAttachments_objectsAndRowDeleted() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf", "b.png");

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(s3.deleteCalls).containsExactlyInAnyOrderElementsOf(m.keys());
            assertThat(exists(m.messageId())).isFalse();
            assertThat(result).isEqualTo(new PurgedMessages(1, 2, 0, 0, Set.of()));
        }

        @Test
        @DisplayName("awaria S3 na 2. obiekcie → wiersz ZOSTAJE (JSONB nienaruszony), s3Failures=1, kontakt zablokowany; drugi przebieg (S3 sprawny) → sukces (idempotencja)")
        void s3FailureOnSecondObject_thenSecondPassSucceeds() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf", "b.png");
            String k1 = m.keys().get(0);
            String k2 = m.keys().get(1);
            s3.failingKeys.add(k2);

            PurgedMessages first = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(first).isEqualTo(new PurgedMessages(0, 1, 1, 0, Set.of(m.contactId())));
            assertThat(exists(m.messageId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT jsonb_array_length(attachments) FROM email_message WHERE message_id = ?",
                    Integer.class, m.messageId())).isEqualTo(2); // wskaźniki do obiektów nadal w bazie

            // S3 wraca do zdrowia; kolejny purge ponawia wiadomość
            s3.failingKeys.clear();
            PurgedMessages second = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(second).isEqualTo(new PurgedMessages(1, 2, 0, 0, Set.of()));
            assertThat(exists(m.messageId())).isFalse();
            assertThat(s3.calls(k1)).isEqualTo(2); // usunięty w pierwszym przebiegu i ponownie w drugim — idempotentnie
            assertThat(s3.calls(k2)).isEqualTo(2);
        }

        @Test
        @DisplayName("całkowita awaria S3 → żaden wiersz nie znika, wszystkie kontakty zablokowane, faza S3 przerwana po progu porażek")
        void totalS3Outage_keepsAllRowsAndStopsAfterThreshold() {
            List<Seeded> seeded = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Seeded m = seedInbound(tenantA, UUID.randomUUID(), "f" + i + ".pdf");
                s3.failingKeys.addAll(m.keys());
                seeded.add(m);
            }
            List<UUID> contacts = seeded.stream().map(Seeded::contactId).toList();

            PurgedMessages result = service.purgeByContactIds(tenantA, contacts);

            assertThat(result.deletedRows()).isZero();
            assertThat(result.s3Failures()).isEqualTo(EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD);
            assertThat(s3.deleteCalls).hasSize(EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD);
            assertThat(result.contactIdsBlocked()).containsExactlyInAnyOrderElementsOf(contacts);
            assertThat(seeded).allSatisfy(m -> assertThat(exists(m.messageId())).isTrue());
        }

        @Test
        @DisplayName("w trakcie I/O do S3 żadne połączenie z bazą nie jest trzymane (brak transakcji wokół S3)")
        void noDatabaseConnectionHeldDuringS3Calls() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf", "b.pdf");

            service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(s3.deleteCalls).hasSize(2);
            assertThat(s3.maxActiveDbConnectionsDuringS3.get())
                    .as("aktywne połączenia puli w chwili wywołania S3 (pula ma rozmiar 1)")
                    .isZero();
        }

        @Test
        @DisplayName("ten sam klucz S3 w >1 wiadomości → usunięty w S3 raz, obie wiadomości usunięte")
        void sameKeyInTwoMessages_deletedOnce() {
            String shared = EmailAttachmentKeys.pendingKey(tenantA, UUID.randomUUID(), "shared.pdf");
            Seeded m1 = seedOutbound(tenantA, UUID.randomUUID(), shared);
            Seeded m2 = seedOutbound(tenantA, UUID.randomUUID(), shared);

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(m1.contactId(), m2.contactId()));

            assertThat(s3.calls(shared)).isEqualTo(1);
            assertThat(result).isEqualTo(new PurgedMessages(2, 1, 0, 0, Set.of()));
            assertThat(exists(m1.messageId())).isFalse();
            assertThat(exists(m2.messageId())).isFalse();
        }

        @Test
        @DisplayName("ponowne wywołanie po pełnym sukcesie → wynik pusty, S3 nietknięte (idempotencja)")
        void secondCallAfterSuccess_isNoOp() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf");
            service.purgeByContactIds(tenantA, List.of(m.contactId()));
            s3.deleteCalls.clear();

            PurgedMessages again = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(again).isEqualTo(PurgedMessages.empty());
            assertThat(s3.deleteCalls).isEmpty();
        }
    }

    // =========================================================================
    // Allow-lista prefiksu i odporność na dane
    // =========================================================================

    @Nested
    @DisplayName("allow-lista prefiksu i odporność na dane")
    class AllowlistAndRobustness {

        @Test
        @DisplayName("obcy prefiks (inny tenant, nagranie, EML, '..') → pominięty (s3Rejected), S3 nietknięte, wiersz USUNIĘTY")
        void foreignKeys_areRejectedButRowIsDeleted() {
            String foreignTenant = EmailAttachmentKeys.pendingKey(tenantB, UUID.randomUUID(), "cudzy.pdf");
            String recording = tenantB + "/2026/09/" + UUID.randomUUID() + ".mp3";
            String eml = tenantA + "/2026/09/" + UUID.randomUUID() + ".eml";
            String traversal = "email-attachments/" + tenantA + "/../" + tenantB + "/x/a.pdf";
            Seeded m = seedOutbound(tenantA, UUID.randomUUID(), foreignTenant, recording, eml, traversal);

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(s3.deleteCalls).isEmpty();
            assertThat(exists(m.messageId())).isFalse();
            assertThat(result).isEqualTo(new PurgedMessages(1, 0, 0, 4, Set.of()));
        }

        @Test
        @DisplayName("mieszanka: własny klucz usunięty, cudzy pominięty, wiersz usunięty")
        void mixedOwnAndForeign_onlyOwnDeleted() {
            String own = EmailAttachmentKeys.pendingKey(tenantA, UUID.randomUUID(), "moj.pdf");
            String foreign = EmailAttachmentKeys.pendingKey(tenantB, UUID.randomUUID(), "cudzy.pdf");
            Seeded m = seedOutbound(tenantA, UUID.randomUUID(), own, foreign);

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(m.contactId()));

            assertThat(s3.deleteCalls).containsExactly(own);
            assertThat(result).isEqualTo(new PurgedMessages(1, 1, 0, 1, Set.of()));
            assertThat(exists(m.messageId())).isFalse();
        }

        @Test
        @DisplayName("[], brak s3_key, pusty s3_key, wpisy nieobiektowe, s3_key nietekstowy, stare s3_url → wiadomości usunięte bez wyjątku i bez S3")
        void oddButValidJsonb_isHandled() {
            UUID contact = UUID.randomUUID();
            List<UUID> ids = new ArrayList<>();
            for (String json : List.of(
                    "[]",
                    "[{\"filename\":\"x\"}]",
                    "[{\"s3_key\":\"\"}]",
                    "[1, \"a\", null, [\"nested\"], {\"s3_key\":5}, {\"s3_key\":null}]",
                    "[{\"s3_url\":\"legacy/key\"}]")) {
                ids.add(insertMessage(tenantA, contact, "INBOUND", json));
            }

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(contact));

            assertThat(s3.deleteCalls).isEmpty();
            assertThat(result).isEqualTo(new PurgedMessages(5, 0, 0, 0, Set.of()));
            assertThat(ids).allSatisfy(id -> assertThat(exists(id)).isFalse());
        }
    }

    // =========================================================================
    // Izolacja tenantów
    // =========================================================================

    @Nested
    @DisplayName("izolacja tenantów")
    class TenantIsolation {

        @Test
        @DisplayName("contactIds tenanta A nie usuwają wiadomości tenanta B — nawet gdy B używa TEGO SAMEGO contact_id; S3 B nietknięte")
        void purgeForTenantA_neverTouchesTenantB() {
            UUID sharedContactId = UUID.randomUUID();
            Seeded a = seedInbound(tenantA, sharedContactId, "a.pdf");
            Seeded b1 = seedInbound(tenantB, sharedContactId, "b.pdf");
            Seeded b2 = seedInbound(tenantB, UUID.randomUUID(), "c.pdf");

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(sharedContactId));

            assertThat(result).isEqualTo(new PurgedMessages(1, 1, 0, 0, Set.of()));
            assertThat(exists(a.messageId())).isFalse();
            assertThat(exists(b1.messageId())).isTrue();
            assertThat(exists(b2.messageId())).isTrue();
            assertThat(count(tenantB)).isEqualTo(2);
            assertThat(s3.deleteCalls).containsExactlyElementsOf(a.keys());
        }

        @Test
        @DisplayName("contact_id należący do tenanta B podany w purge tenanta A → wiadomości B zostają (COUNT po wartościach)")
        void foreignContactIdInList_isIgnored() {
            Seeded ofB = seedInbound(tenantB, UUID.randomUUID(), "b.pdf");
            Seeded ofA = seedInbound(tenantA, UUID.randomUUID(), "a.pdf");

            PurgedMessages result = service.purgeByContactIds(tenantA, List.of(ofA.contactId(), ofB.contactId()));

            assertThat(result.deletedRows()).isEqualTo(1);
            assertThat(exists(ofA.messageId())).isFalse();
            assertThat(exists(ofB.messageId())).isTrue();
            assertThat(count(tenantB)).isEqualTo(1);
            assertThat(s3.deleteCalls).doesNotContainAnyElementsOf(ofB.keys());
        }

        @Test
        @DisplayName("TenantContext = A, argument tenantId = B → CrossTenantAccessException; ani S3, ani baza nietknięte")
        void tenantMismatch_throwsAndTouchesNothing() {
            Seeded ofB = seedInbound(tenantB, UUID.randomUUID(), "b.pdf");

            assertThatThrownBy(() -> service.purgeByContactIds(tenantB, List.of(ofB.contactId())))
                    .isInstanceOf(CrossTenantAccessException.class);

            assertThat(s3.deleteCalls).isEmpty();
            assertThat(exists(ofB.messageId())).isTrue();
        }
    }

    // =========================================================================
    // TenantContext (WP-2)
    // =========================================================================

    @Nested
    @DisplayName("TenantContext (WP-2)")
    class TenantContextHandling {

        @Test
        @DisplayName("pusty TenantContext → IllegalStateException (serwis i obie metody repozytorium); ani S3, ani baza nietknięte")
        void emptyTenantContext_throwsIllegalState() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf");
            TenantContext.clear();

            assertThatThrownBy(() -> service.purgeByContactIds(tenantA, List.of(m.contactId())))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.findAttachmentsByContactIds(tenantA, List.of(m.contactId())))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> repository.deleteByIds(tenantA, List.of(m.messageId())))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(s3.deleteCalls).isEmpty();
            assertThat(exists(m.messageId())).isTrue();
        }

        @Test
        @DisplayName("purge NIGDY nie czyści TenantContext — ani po sukcesie, ani po awarii S3 (ścieżka żądania HTTP współdzieli kontekst)")
        void tenantContextSurvivesPurge() {
            Seeded m = seedInbound(tenantA, UUID.randomUUID(), "a.pdf");

            s3.failingKeys.addAll(m.keys());
            service.purgeByContactIds(tenantA, List.of(m.contactId()));
            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(tenantA);

            s3.failingKeys.clear();
            service.purgeByContactIds(tenantA, List.of(m.contactId()));
            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(tenantA);
        }

        @Test
        @DisplayName("pusta lista contactIds → no-op nawet bez TenantContext (bez dostępu do bazy)")
        void emptyList_isNoOp() {
            TenantContext.clear();

            assertThat(service.purgeByContactIds(tenantA, List.of())).isEqualTo(PurgedMessages.empty());
            assertThat(service.purgeByContactIds(tenantA, null)).isEqualTo(PurgedMessages.empty());
        }
    }

    // =========================================================================
    // Duże listy (podział IN na porcje)
    // =========================================================================

    @Nested
    @DisplayName("duże wejście")
    class LargeInput {

        @Test
        @DisplayName("2345 contactIds i 1205 wiadomości (ponad rozmiar porcji IN=1000 w SELECT i DELETE) → wszystkie znalezione i usunięte")
        void moreThanOneInChunk_allDeleted() {
            UUID tenant = tenantA;
            List<UUID> contacts = new ArrayList<>();
            IntStream.range(0, 2345).forEach(i -> contacts.add(UUID.randomUUID()));
            // 1205 wiadomości rozłożonych po całej liście, także za granicą porcji 1000 i na samym końcu
            List<UUID> withMessages = new ArrayList<>(contacts.subList(0, 1204));
            withMessages.add(contacts.get(2344));
            jdbc.batchUpdate("""
                            INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, attachments, received_at)
                            VALUES (gen_random_uuid(), ?, ?, 'INBOUND', 'a@a.pl', 'b@b.pl', '[]'::jsonb, now())
                            """,
                    withMessages, 500, (ps, contactId) -> {
                        ps.setObject(1, tenant);
                        ps.setObject(2, contactId);
                    });

            PurgedMessages result = service.purgeByContactIds(tenant, contacts);

            assertThat(result).isEqualTo(new PurgedMessages(1205, 0, 0, 0, Set.of()));
            assertThat(count(tenant)).isZero();
        }
    }

    // =========================================================================
    // Plan zapytań
    // =========================================================================

    @Nested
    @DisplayName("plan zapytań (EXPLAIN)")
    class QueryPlans {

        @Test
        @DisplayName("SELECT po contact_id IN korzysta z idx_email_message_contact, a DELETE po message_id IN z pk_email_message (bez Seq Scan)")
        void explain_usesContactIndexAndPrimaryKey() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address, attachments, received_at)
                                SELECT gen_random_uuid(), ?,
                                       ('00000000-0000-4000-8000-' || lpad((g / 2)::text, 12, '0'))::uuid,
                                       'INBOUND', 'a@a.pl', 'b@b.pl', '[]'::jsonb, now()
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE email_message");

                String contactsIn = "('00000000-0000-4000-8000-000000000007', '00000000-0000-4000-8000-000000000042', "
                        + "'00000000-0000-4000-8000-000000009999')";
                String select = EmailMessageRepository.FIND_ATTACHMENTS_SQL
                        .replace(":tenantId", "'" + tenant + "'")
                        .replace("(:contactIds)", contactsIn);
                String delete = EmailMessageRepository.DELETE_BY_IDS_SQL
                        .replace(":tenantId", "'" + tenant + "'")
                        .replace("(:messageIds)", "('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "')");

                String selectPlan = explain(select);
                String deletePlan = explain(delete);

                assertThat(selectPlan).contains("idx_email_message_contact").doesNotContain("Seq Scan");
                assertThat(deletePlan).contains("pk_email_message").doesNotContain("Seq Scan");
                System.out.println("[EXPLAIN SELECT contact_id IN]\n" + selectPlan);
                System.out.println("[EXPLAIN DELETE message_id IN]\n" + deletePlan);
            } finally {
                jdbc.update("DELETE FROM email_message WHERE tenant_id = ?", tenant);
            }
        }

        private String explain(String sql) {
            return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
        }

        @Test
        @DisplayName("SQL nie używa ctid (niebezpieczne na tabeli partycjonowanej) i identyfikuje wiersze pełnym PK")
        void sql_doesNotUseCtid() {
            // \b: samo „ctid" jako słowo — nazwa parametru :contactIds zawiera podciąg "ctId"
            assertThat(EmailMessageRepository.FIND_ATTACHMENTS_SQL).doesNotContainPattern("(?i)\\bctid\\b");
            assertThat(EmailMessageRepository.DELETE_BY_IDS_SQL).doesNotContainPattern("(?i)\\bctid\\b")
                    .contains("message_id IN").contains("tenant_id");
        }
    }

    // =========================================================================
    // Mapowanie wierszy z contact_id = NULL (BE125-03) — ścieżka dla BE-127
    // =========================================================================

    @Nested
    @DisplayName("mapowanie wiersza z contact_id = NULL na prawdziwej bazie (BE125-03; wiadomości osierocone dla BE-127)")
    class OrphanRowMapping {

        @Test
        @DisplayName("Hibernate zwraca null dla contact_id IS NULL, a toAttachmentsRow mapuje go na AttachmentsRow z contactId = null (bez wyjątku)")
        void nullContactId_isMappedToNullNotException() {
            UUID orphanId = insertMessage(tenantA, null, "INBOUND", attachments("k/orphan.pdf"));
            UUID contact = UUID.randomUUID();
            UUID linkedId = insertMessage(tenantA, contact, "INBOUND", "[]");
            // ta sama lista kolumn (projekcja) i ten sam filtr tenanta co w produkcyjnym SQL fazy 1 —
            // zmieniony jest wyłącznie warunek na contact_id, więc nie powstaje nowe zapytanie produkcyjne
            String orphanSelect = EmailMessageRepository.FIND_ATTACHMENTS_SQL
                    .replace("contact_id IN (:contactIds)", "contact_id IS NULL");
            assertThat(orphanSelect).isNotEqualTo(EmailMessageRepository.FIND_ATTACHMENTS_SQL);

            EntityManagerFactory emf = ctx.getBean(EntityManagerFactory.class);
            List<Object[]> rows;
            try (EntityManager em = emf.createEntityManager()) {
                @SuppressWarnings("unchecked")
                List<Object[]> result = em.createNativeQuery(orphanSelect)
                        .setParameter("tenantId", tenantA.toString())
                        .getResultList();
                rows = result;
            }

            // Hibernate oddaje w Object[] prawdziwe null dla kolumny NULL — to, na co polega toUuid(null)
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)[1]).isNull();

            List<EmailMessageRepository.AttachmentsRow> mapped = rows.stream()
                    .map(EmailMessageRepository::toAttachmentsRow)
                    .toList();

            assertThat(mapped).singleElement().satisfies(row -> {
                assertThat(row.messageId()).isEqualTo(orphanId);
                assertThat(row.contactId()).isNull();
                assertThat(row.attachmentsJson()).contains("k/orphan.pdf");
            });
            // wiersz powiązany z kontaktem nie jest osierocony i nie wpadł do wyniku
            assertThat(mapped).noneMatch(row -> row.messageId().equals(linkedId));
        }

        @Test
        @DisplayName("zapytanie fazy 1 (po contact_id IN) NIE zwraca osieroconych wierszy — zakres BE-125 bez zmian; wiersz osierocony zostaje nietknięty")
        void productionPhaseOneQuery_ignoresOrphans() {
            UUID orphanId = insertMessage(tenantA, null, "INBOUND", "[]");
            UUID contact = UUID.randomUUID();
            UUID linkedId = insertMessage(tenantA, contact, "INBOUND", "[]");

            List<EmailMessageRepository.AttachmentsRow> found = repository.findAttachmentsByContactIds(tenantA, List.of(contact));

            assertThat(found).singleElement().satisfies(row -> {
                assertThat(row.messageId()).isEqualTo(linkedId);
                assertThat(row.contactId()).isEqualTo(contact);
            });
            assertThat(exists(orphanId)).isTrue();
        }
    }
}
