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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny {@link SocialMessageRepository#save} (BE-132, EPIC-30) na PRAWDZIWYM
 * PostgreSQL (Testcontainers, pełny Flyway — schemat PO V100/DB-065: klucz złożony {@code
 * (message_id, sent_at)}, unikalność złożona {@code (tenant_id, external_message_id, sent_at)}).
 *
 * <p><strong>Rola DB:</strong> repozytorium jest tu wołane POD ROLĄ APLIKACYJNĄ (restricted login
 * role utworzoną przez {@link PostgresTestDatabase#createRestrictedLoginRole}, członek {@code
 * app_user}, BEZ {@code BYPASSRLS}) — NIE superuserem {@code cc_test} — żeby natywny INSERT
 * faktycznie przechodził przez RLS (GUC {@code app.current_tenant_id} ustawiany przez {@code
 * setTenantContextInDb}), tak jak w produkcji (kryterium akceptacji BE-132: "zapis pod SET ROLE
 * app_user z GUC"). Asercje stanu bazy (tableoid, COUNT) czytają przez {@code superuserJdbc},
 * które bezpiecznie omija RLS niezależnie od tenanta zapytania.
 */
@DisplayName("SocialMessageRepository.save – klucz złożony + ON CONFLICT DO NOTHING pod rolą app_user (BE-132)")
class SocialMessageRepositorySaveIntegrationTest {

    private static HikariDataSource superuserPool;
    private static JdbcTemplate superuserJdbc;
    private static HikariDataSource appUserPool;
    private static AnnotationConfigApplicationContext ctx;
    private static SocialMessageRepository repository;

    private UUID tenant;

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(2);
        superuserJdbc = new JdbcTemplate(superuserPool);

        String password = PostgresTestDatabase.createRestrictedLoginRole(superuserJdbc, "cc_be132_app_user");
        appUserPool = PostgresTestDatabase.pool("cc_be132_app_user", password, 2);

        ctx = JpaTestContext.create(
                appUserPool,
                new Class<?>[]{SocialMessage.class},
                new Class<?>[]{SocialMessageRepository.class},
                null);
        repository = ctx.getBean(SocialMessageRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        appUserPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        tenant = PostgresTestDatabase.insertTenant(superuserJdbc, "Tenant BE-132 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Wiadomość bazowa -- bez integrationId (FK na social_integration, uniknięcie zbędnego seeda). */
    private SocialMessage.SocialMessageBuilder baseMessage() {
        return SocialMessage.builder()
                .tenantId(tenant)
                .contactId(UUID.randomUUID())
                .direction(SocialMessage.Direction.INBOUND.name())
                .senderExternalId("48123456789")
                .content("Treść testowa BE-132");
    }

    private String partitionOf(UUID messageId) {
        return superuserJdbc.queryForObject(
                "SELECT tableoid::regclass::text FROM social_message WHERE message_id = ?",
                String.class, messageId);
    }

    private long countByExternalId(String externalId) {
        return superuserJdbc.queryForObject(
                "SELECT count(*) FROM social_message WHERE external_message_id = ? AND tenant_id = ?",
                Long.class, externalId, tenant);
    }

    @Nested
    @DisplayName("zapis przez natywny INSERT, klucz złożony (message_id, sent_at)")
    class Save {

        @Test
        @DisplayName("brak @GeneratedValue -- messageId nadany w Javie jest zachowany po zapisie")
        void save_preservesJavaAssignedMessageId() {
            UUID messageId = UUID.randomUUID();
            SocialMessage message = baseMessage()
                    .messageId(messageId)
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-" + UUID.randomUUID())
                    .sentAt(Instant.parse("2026-10-15T10:00:00Z"))
                    .build();

            Optional<SocialMessage> saved = repository.save(message);

            assertThat(saved).isPresent();
            assertThat(saved.get().getMessageId()).isEqualTo(messageId);
        }

        @Test
        @DisplayName("INBOUND trafia do partycji miesięcznej (tableoid) odpowiadającej sent_at")
        void save_inbound_landsInMonthlyPartition() {
            SocialMessage message = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-" + UUID.randomUUID())
                    .sentAt(Instant.parse("2026-10-15T10:00:00Z"))
                    .build();

            repository.save(message);

            assertThat(partitionOf(message.getMessageId())).isEqualTo("social_message_2026_10");
        }

        @Test
        @DisplayName("OUTBOUND trafia do partycji miesięcznej (tableoid) odpowiadającej sent_at")
        void save_outbound_landsInMonthlyPartition() {
            SocialMessage message = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.FACEBOOK)
                    .direction(SocialMessage.Direction.OUTBOUND.name())
                    .externalMessageId("OUTBOUND-" + UUID.randomUUID())
                    .sentAt(Instant.parse("2026-11-05T08:30:00Z"))
                    .build();

            repository.save(message);

            assertThat(partitionOf(message.getMessageId())).isEqualTo("social_message_2026_11");
        }

        @Test
        @DisplayName("createdAt/attachments domyślne są wypełniane przez repository.save (zastępuje usunięty @PrePersist)")
        void save_fillsDefaults_whenNotSetExplicitly() {
            SocialMessage message = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-" + UUID.randomUUID())
                    .sentAt(Instant.parse("2026-10-16T00:00:00Z"))
                    .build();
            message.setCreatedAt(null);

            repository.save(message);

            Boolean createdAtIsNotNull = superuserJdbc.queryForObject(
                    "SELECT created_at IS NOT NULL FROM social_message WHERE message_id = ?",
                    Boolean.class, message.getMessageId());
            String attachments = superuserJdbc.queryForObject(
                    "SELECT attachments::text FROM social_message WHERE message_id = ?",
                    String.class, message.getMessageId());

            assertThat(createdAtIsNotNull).isTrue();
            assertThat(attachments).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("idempotentność -- DRUGA linia obrony (ON CONFLICT ON CONSTRAINT uq_social_message_external_id DO NOTHING)")
    class Idempotency {

        @Test
        @DisplayName("redelivery (RÓŻNY messageId, TEN SAM tenant+external_message_id+sent_at) -> druga save() = Optional.empty(), 1 wiersz w DB")
        void redelivery_sameExternalIdAndSentAt_secondSaveIsNoOp() {
            String externalId = "mid-redelivery-" + UUID.randomUUID();
            Instant sentAt = Instant.parse("2026-10-20T12:00:00Z");

            SocialMessage first = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.FACEBOOK)
                    .externalMessageId(externalId)
                    .sentAt(sentAt)
                    .build();
            // Symuluje redelivery tego samego zdarzenia Meta po naprawie BE-132: INNY messageId
            // (nowy UUID.randomUUID() przy każdym przetworzeniu, jak w SocialMessageServiceImpl),
            // ale TEN SAM tenant+external_message_id+sent_at -- sent_at jest teraz deterministyczny
            // dla FB/IG (czas zdarzenia z payloadu Meta, nie Instant.now() przy odbiorze webhooka).
            SocialMessage redelivered = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.FACEBOOK)
                    .externalMessageId(externalId)
                    .sentAt(sentAt)
                    .build();

            Optional<SocialMessage> firstResult = repository.save(first);
            Optional<SocialMessage> secondResult = repository.save(redelivered);

            assertThat(firstResult).isPresent();
            assertThat(secondResult)
                    .as("DRUGA linia obrony: constraint DB wycisza duplikat przez ON CONFLICT DO NOTHING, "
                            + "bez wyjątku (NIE błąd 500)")
                    .isEmpty();
            assertThat(countByExternalId(externalId)).isEqualTo(1);
        }

        @Test
        @DisplayName("sam external_message_id, RÓŻNY sent_at -> OBIE save() zapisują (ograniczenie z nagłówka V100 -- "
                + "pokrywane przez dedup aplikacyjny findByExternalMessageId w serwisie, nie przez ten constraint)")
        void sameExternalId_differentSentAt_bothPersist() {
            String externalId = "mid-weak-" + UUID.randomUUID();

            SocialMessage first = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.FACEBOOK)
                    .externalMessageId(externalId)
                    .sentAt(Instant.parse("2026-10-20T12:00:00Z"))
                    .build();
            SocialMessage second = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.FACEBOOK)
                    .externalMessageId(externalId)
                    .sentAt(Instant.parse("2026-10-20T12:00:05Z"))
                    .build();

            assertThat(repository.save(first)).isPresent();
            assertThat(repository.save(second)).isPresent();
            assertThat(countByExternalId(externalId)).isEqualTo(2);
        }

        @Test
        @DisplayName("PK (message_id, sent_at) nie jest użyty jako cel ON CONFLICT -- kolizja message_id (błąd aplikacji, "
                + "praktycznie niemożliwa z UUID.randomUUID()) wciąż rzuca wyjątek, nie jest po cichu wyciszana")
        void primaryKeyCollision_stillThrows_notSilencedByOnConflict() {
            UUID messageId = UUID.randomUUID();
            Instant sentAt = Instant.parse("2026-10-21T09:00:00Z");

            SocialMessage first = baseMessage()
                    .messageId(messageId)
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-pk-a-" + UUID.randomUUID())
                    .sentAt(sentAt)
                    .build();
            SocialMessage samePk = baseMessage()
                    .messageId(messageId)
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-pk-b-" + UUID.randomUUID()) // RÓŻNY externalId -- nie ten constraint
                    .sentAt(sentAt)
                    .build();

            assertThat(repository.save(first)).isPresent();
            assertThatThrownBy(() -> repository.save(samePk))
                    .as("kolizja PRIMARY KEY musi rzucić (ON CONFLICT celuje WYŁĄCZNIE w "
                            + "uq_social_message_external_id, nie w PK)")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("RLS pod rolą aplikacyjną (app_user, bez BYPASSRLS)")
    class RowLevelSecurityUnderAppUser {

        @Test
        @DisplayName("zapis własnego tenanta pod rolą app_user działa i trafia do właściwej partycji")
        void save_ownTenant_underAppUserRole_works() {
            SocialMessage message = baseMessage()
                    .messageId(UUID.randomUUID())
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId("mid-rls-" + UUID.randomUUID())
                    .sentAt(Instant.parse("2026-12-01T00:00:00Z"))
                    .build();

            Optional<SocialMessage> saved = repository.save(message);

            assertThat(saved).isPresent();
            assertThat(partitionOf(message.getMessageId())).isEqualTo("social_message_2026_12");
        }

        @Test
        @DisplayName("TenantContext = A, encja.tenantId = B -> CrossTenantAccessException, nic nie zapisane")
        void crossTenantMismatch_throwsAndSavesNothing() {
            UUID otherTenant = PostgresTestDatabase.insertTenant(superuserJdbc, "Tenant inny BE-132 " + UUID.randomUUID());
            String externalId = "mid-cross-" + UUID.randomUUID();
            SocialMessage message = baseMessage()
                    .messageId(UUID.randomUUID())
                    .tenantId(otherTenant)
                    .platform(SocialPlatform.WHATSAPP)
                    .externalMessageId(externalId)
                    .sentAt(Instant.now())
                    .build();

            assertThatThrownBy(() -> repository.save(message))
                    .isInstanceOf(CrossTenantAccessException.class);

            assertThat(superuserJdbc.queryForObject(
                    "SELECT count(*) FROM social_message WHERE external_message_id = ?",
                    Long.class, externalId)).isZero();
        }
    }
}
