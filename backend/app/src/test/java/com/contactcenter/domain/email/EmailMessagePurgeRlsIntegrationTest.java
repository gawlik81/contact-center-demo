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
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Purge wiadomości e-mail pod rolą bazodanową BEZ {@code BYPASSRLS} (członek {@code app_user} z V012)
 * — odtwarza to, co dzieje się na produkcji, gdy aplikacja nie łączy się jako superuser (DESIGN EPIC-30
 * §2 U8/R1: polityki RLS wiadomości to dziś tylko {@code FOR SELECT}, więc {@code DELETE} pod taką rolą
 * usuwa 0 wierszy BEZ błędu).
 *
 * <p>Test celowo asertuje INWARIANT, a nie bieżący stan polityk: niezależnie od tego, czy {@code DELETE}
 * jest dozwolony (po DB-064) czy nie (dziś), (1) {@code deletedRows} jest równe faktycznie zniknąć
 * wierszom, a (2) każdy kontakt, któremu została choć jedna wiadomość, jest w {@code contactIdsBlocked}
 * — inaczej BE-126 usunąłby kontakt, zostawiając wiadomość z PII i wskaźnikami do usuniętych obiektów.
 * Dzięki temu test nie pęknie po DB-064, a dziś dowodzi, że „ciche 0 wierszy" nie jest raportowane
 * jako sukces.
 */
@DisplayName("EmailMessageService.purgeByContactIds – pod rolą bez BYPASSRLS (BE-125)")
class EmailMessagePurgeRlsIntegrationTest {

    private static final String ROLE = "cc_be125_rls";

    private static HikariDataSource superuserPool;
    private static HikariDataSource restrictedPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static EmailMessageService service;

    private UUID tenant;

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(superuserPool);
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, ROLE);
        restrictedPool = PostgresTestDatabase.pool(ROLE, password, 2);

        s3Client = mock(S3Client.class);
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());
        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket("test-bucket");

        ctx = JpaTestContext.create(
                restrictedPool,
                new Class<?>[]{EmailMessage.class},
                new Class<?>[]{EmailMessageRepository.class, EmailMessageServiceImpl.class,
                        EmailAttachmentStorageServiceImpl.class},
                c -> {
                    c.getBeanFactory().registerSingleton("s3Client", s3Client);
                    c.getBeanFactory().registerSingleton("s3Presigner", mock(S3Presigner.class));
                    c.getBeanFactory().registerSingleton("s3Properties", s3Properties);
                });
        service = ctx.getBean(EmailMessageService.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        restrictedPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant RLS – BE-125 " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("rola jest ograniczona (nie superuser, bez BYPASSRLS) — sanity check środowiska testu")
    void roleIsActuallyRestricted() {
        Boolean bypass = jdbc.queryForObject(
                "SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, ROLE);

        assertThat(bypass).isFalse();
    }

    @Test
    @DisplayName("deletedRows = liczba faktycznie usuniętych wierszy, a każdy kontakt z pozostałą wiadomością jest w contactIdsBlocked")
    void rlsSilentZeroDeletes_areNeverReportedAsSuccess() {
        List<UUID> contacts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID contact : contacts) {
            UUID messageId = UUID.randomUUID();
            String key = EmailAttachmentKeys.inboundKey(tenant, messageId, "a.pdf");
            jdbc.update("""
                            INSERT INTO email_message
                                (message_id, tenant_id, contact_id, direction, from_address, to_address, attachments, received_at)
                            VALUES (?, ?, ?, 'INBOUND', 'a@a.pl', 'b@b.pl', CAST(? AS jsonb), now())
                            """,
                    messageId, tenant, contact,
                    "[{\"filename\":\"a.pdf\",\"s3_key\":\"" + key + "\"}]");
        }
        long before = jdbc.queryForObject("SELECT count(*) FROM email_message WHERE tenant_id = ?", Long.class, tenant);

        PurgedMessages result = service.purgeByContactIds(tenant, contacts);

        long after = jdbc.queryForObject("SELECT count(*) FROM email_message WHERE tenant_id = ?", Long.class, tenant);
        Set<UUID> contactsWithRemainingMessages = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT contact_id FROM email_message WHERE tenant_id = ?", UUID.class, tenant));

        assertThat(result.deletedRows()).isEqualTo((int) (before - after));
        assertThat(result.contactIdsBlocked()).isEqualTo(contactsWithRemainingMessages);
        // obiekty S3 zostały usunięte niezależnie od wyniku DELETE — kolejność „S3 przed wierszem"
        assertThat(result.s3ObjectsDeleted()).isEqualTo(3);
        System.out.println("[RLS] pod rolą bez BYPASSRLS: przed=" + before + ", po=" + after
                + ", deletedRows=" + result.deletedRows() + ", zablokowane kontakty=" + result.contactIdsBlocked().size());
    }
}
