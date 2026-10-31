package com.contactcenter.domain.social;

import com.contactcenter.domain.exception.CrossTenantAccessException;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny {@link SocialMessageRepository#purgeByContactIds} (BE-125) na PRAWDZIWYM
 * PostgreSQL (Testcontainers, pełny Flyway), z prawdziwym Hibernate, transakcjami i {@code TenantContext}.
 *
 * <p>Kontekst testu NIE zawiera żadnego beana S3 — domena social nie przechowuje obiektów w S3
 * (BE-124 §3), więc purge musi działać bez nich (kryterium „usunięcie bez operacji S3").
 */
@DisplayName("SocialMessageRepository.purgeByContactIds – prawdziwa baza (BE-125)")
class SocialMessagePurgeIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static SocialMessageRepository repository;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        ctx = JpaTestContext.create(
                pool,
                new Class<?>[]{SocialMessage.class},
                new Class<?>[]{SocialMessageRepository.class},
                null);
        repository = ctx.getBean(SocialMessageRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        pool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A – social BE-125 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B – social BE-125 " + UUID.randomUUID());
        TenantContext.setTenantId(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UUID insertMessage(UUID tenant, UUID contact, String platform, String attachmentsJson) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO social_message
                            (message_id, tenant_id, contact_id, platform, direction, external_message_id,
                             sender_external_id, content, attachments, sent_at)
                        VALUES (?, ?, ?, CAST(? AS social_platform), 'INBOUND', ?, '48123456789', 'Treść PII',
                                CAST(? AS jsonb), now())
                        """,
                messageId, tenant, contact, platform, "mid." + messageId, attachmentsJson);
        return messageId;
    }

    private boolean exists(UUID messageId) {
        return jdbc.queryForObject("SELECT count(*) FROM social_message WHERE message_id = ?", Long.class, messageId) > 0;
    }

    private long count(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM social_message WHERE tenant_id = ?", Long.class, tenant);
    }

    @Test
    @DisplayName("kontekst testu nie ma żadnego beana S3 — purge social działa bez operacji S3")
    void contextHasNoS3() {
        assertThat(ctx.getBeanNamesForType(S3Client.class)).isEmpty();
    }

    @Nested
    @DisplayName("usuwanie")
    class Deleting {

        @Test
        @DisplayName("usuwa wiadomości wskazanych kontaktów wszystkich platform i zwraca liczbę usuniętych wierszy; pozostałe kontakty zostają")
        void deletesOnlyGivenContacts() {
            UUID c1 = UUID.randomUUID();
            UUID c2 = UUID.randomUUID();
            UUID keep = UUID.randomUUID();
            UUID m1 = insertMessage(tenantA, c1, "WHATSAPP", "[]");
            UUID m2 = insertMessage(tenantA, c1, "FACEBOOK", "[{\"url\":\"https://cdn.example/x?token=abc\",\"type\":\"image\"}]");
            UUID m3 = insertMessage(tenantA, c2, "INSTAGRAM", "[]");
            UUID mKeep = insertMessage(tenantA, keep, "WHATSAPP", "[]");

            int deleted = repository.purgeByContactIds(tenantA, List.of(c1, c2));

            assertThat(deleted).isEqualTo(3);
            assertThat(exists(m1)).isFalse();
            assertThat(exists(m2)).isFalse();
            assertThat(exists(m3)).isFalse();
            assertThat(exists(mKeep)).isTrue();
            assertThat(count(tenantA)).isEqualTo(1);
        }

        @Test
        @DisplayName("ponowne wywołanie → 0 (idempotencja); kontakt bez wiadomości → 0")
        void isIdempotent() {
            UUID c = UUID.randomUUID();
            insertMessage(tenantA, c, "WHATSAPP", "[]");

            assertThat(repository.purgeByContactIds(tenantA, List.of(c))).isEqualTo(1);
            assertThat(repository.purgeByContactIds(tenantA, List.of(c))).isZero();
            assertThat(repository.purgeByContactIds(tenantA, List.of(UUID.randomUUID()))).isZero();
        }

        @Test
        @DisplayName("null i pusta lista → 0")
        void nullOrEmpty_returnsZero() {
            assertThat(repository.purgeByContactIds(tenantA, null)).isZero();
            assertThat(repository.purgeByContactIds(tenantA, List.of())).isZero();
        }

        @Test
        @DisplayName("2345 contactIds i 1205 wiadomości (ponad rozmiar porcji IN=1000) → wszystkie usunięte")
        void moreThanOneInChunk_allDeleted() {
            UUID tenant = tenantA;
            List<UUID> contacts = new ArrayList<>();
            IntStream.range(0, 2345).forEach(i -> contacts.add(UUID.randomUUID()));
            List<UUID> withMessages = new ArrayList<>(contacts.subList(0, 1204));
            withMessages.add(contacts.get(2344));
            jdbc.batchUpdate("""
                            INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, attachments, sent_at)
                            VALUES (gen_random_uuid(), ?, ?, CAST('WHATSAPP' AS social_platform), 'INBOUND', 'mid.' || gen_random_uuid(), '[]'::jsonb, now())
                            """,
                    withMessages, 500, (ps, contactId) -> {
                        ps.setObject(1, tenant);
                        ps.setObject(2, contactId);
                    });

            int deleted = repository.purgeByContactIds(tenant, contacts);

            assertThat(deleted).isEqualTo(1205);
            assertThat(count(tenant)).isZero();
        }
    }

    @Nested
    @DisplayName("izolacja tenantów")
    class TenantIsolation {

        @Test
        @DisplayName("purge tenanta A nie usuwa wiadomości tenanta B — nawet gdy B używa TEGO SAMEGO contact_id (COUNT po wartościach)")
        void purgeForTenantA_neverTouchesTenantB() {
            UUID shared = UUID.randomUUID();
            UUID a = insertMessage(tenantA, shared, "WHATSAPP", "[]");
            UUID b1 = insertMessage(tenantB, shared, "WHATSAPP", "[]");
            UUID b2 = insertMessage(tenantB, UUID.randomUUID(), "FACEBOOK", "[]");

            int deleted = repository.purgeByContactIds(tenantA, List.of(shared));

            assertThat(deleted).isEqualTo(1);
            assertThat(exists(a)).isFalse();
            assertThat(exists(b1)).isTrue();
            assertThat(exists(b2)).isTrue();
            assertThat(count(tenantB)).isEqualTo(2);
        }

        @Test
        @DisplayName("TenantContext = A, argument tenantId = B → CrossTenantAccessException; nic nie usunięte")
        void tenantMismatch_throwsAndDeletesNothing() {
            UUID contactOfB = UUID.randomUUID();
            UUID b = insertMessage(tenantB, contactOfB, "WHATSAPP", "[]");

            assertThatThrownBy(() -> repository.purgeByContactIds(tenantB, List.of(contactOfB)))
                    .isInstanceOf(CrossTenantAccessException.class);

            assertThat(exists(b)).isTrue();
        }
    }

    @Nested
    @DisplayName("TenantContext (WP-2)")
    class TenantContextHandling {

        @Test
        @DisplayName("pusty TenantContext → IllegalStateException; wiadomości nietknięte")
        void emptyTenantContext_throwsIllegalState() {
            UUID c = UUID.randomUUID();
            UUID m = insertMessage(tenantA, c, "WHATSAPP", "[]");
            TenantContext.clear();

            assertThatThrownBy(() -> repository.purgeByContactIds(tenantA, List.of(c)))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(exists(m)).isTrue();
        }

        @Test
        @DisplayName("purge NIGDY nie czyści TenantContext")
        void tenantContextSurvivesPurge() {
            UUID c = UUID.randomUUID();
            insertMessage(tenantA, c, "WHATSAPP", "[]");

            repository.purgeByContactIds(tenantA, List.of(c));

            assertThat(TenantContext.getTenantIdOrNull()).isEqualTo(tenantA);
        }
    }

    @Nested
    @DisplayName("plan zapytania i deprecjacja")
    class PlanAndDeprecation {

        @Test
        @DisplayName("DELETE po contact_id IN korzysta z idx_social_message_contact")
        void explain_usesContactIndex() {
            UUID tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EXPLAIN social " + UUID.randomUUID());
            try {
                jdbc.update("""
                                INSERT INTO social_message (message_id, tenant_id, contact_id, platform, direction, external_message_id, attachments, sent_at)
                                SELECT gen_random_uuid(), ?,
                                       ('00000000-0000-4000-8000-' || lpad((g / 2)::text, 12, '0'))::uuid,
                                       CAST('WHATSAPP' AS social_platform), 'INBOUND', 'mid.' || g, '[]'::jsonb, now()
                                FROM generate_series(1, 30000) g
                                """, tenant);
                jdbc.execute("ANALYZE social_message");

                String sql = SocialMessageRepository.DELETE_BY_CONTACT_IDS_SQL
                        .replace(":tenantId", "'" + tenant + "'")
                        .replace("(:contactIds)", "('00000000-0000-4000-8000-000000000007', "
                                + "'00000000-0000-4000-8000-000000000042', '00000000-0000-4000-8000-000000009999')");

                String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));

                // DB-065/V100: social_message jest teraz partycjonowana -- EXPLAIN wypisuje nazwę
                // fizycznego indeksu POTOMNEGO partycji, nie nazwę indeksu rodzica (patrz javadoc
                // PostgresTestDatabase#explainUsesIndexOrItsPartitionChildren).
                assertThat(PostgresTestDatabase.explainUsesIndexOrItsPartitionChildren(jdbc, plan, "idx_social_message_contact"))
                        .as("plan używa idx_social_message_contact albo jego indeksu potomnego partycji")
                        .isTrue();
                // UWAGA (DB-065): zapytanie filtruje WYŁĄCZNIE po contact_id (bez sent_at = kolumna
                // partycjonowania), więc Postgres NIE MOŻE przyciąć (prune) żadnej partycji -- musi
                // odwiedzić wszystkie, w tym te praktycznie puste dla tego zapytania (inne partycje
                // utworzone przez inne klasy testowe współdzielące ten sam kontener, np.
                // social_message_2027_06/07 z SocialMessagePartitioningTest). Seq Scan na takiej
                // prawie-pustej partycji jest POPRAWNĄ, tańszą decyzją plannera (koszt ~1.0), nie
                // regresją -- celowo NIE sprawdzamy już globalnie "doesNotContain(Seq Scan)" w całym
                // planie Append po wielu partycjach; dowodem braku regresji jest wyłącznie to, że
                // partycja z FAKTYCZNYMI danymi (2026_10, 30 000 wierszy) używa indeksu (asercja wyżej).
                System.out.println("[EXPLAIN DELETE social contact_id IN]\n" + plan);
            } finally {
                jdbc.update("DELETE FROM social_message WHERE tenant_id = ?", tenant);
            }
        }

        @Test
        @DisplayName("SQL nie używa ctid")
        void sql_doesNotUseCtid() {
            assertThat(SocialMessageRepository.DELETE_BY_CONTACT_IDS_SQL).doesNotContainPattern("(?i)\\bctid\\b");
        }

        @Test
        @DisplayName("detachContactReferences jest @Deprecated w repozytorium i w serwisie (zastąpione przez purgeByContactIds, usunięcie w BE-126)")
        void detachContactReferences_isDeprecated() throws NoSuchMethodException {
            Method onRepository = SocialMessageRepository.class.getMethod("detachContactReferences", UUID.class, List.class);
            Method onService = SocialMessageService.class.getMethod("detachContactReferences", UUID.class, List.class);

            assertThat(onRepository.isAnnotationPresent(Deprecated.class)).isTrue();
            assertThat(onService.isAnnotationPresent(Deprecated.class)).isTrue();
        }
    }
}
