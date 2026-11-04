package com.contactcenter.domain.email;

import com.contactcenter.domain.exception.CrossTenantAccessException;
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
 * Per-message EML ({@code email_message.eml_s3_key}, V145) on a REAL PostgreSQL (Testcontainers + Flyway):
 * whole-conversation read model (order, cap, truncated, tenant/contact isolation), the key UPDATE, and S3
 * cleanup of per-message EML objects in the retention paths (purge by contact, orphan sweep, per-contact
 * EML cleanup used by the recording retention job). Only {@code S3Client} is a fake.
 */
@DisplayName("email_message.eml_s3_key: rozmowa, UPDATE klucza, sprzatanie S3 (V145)")
class EmailMessageEmlIntegrationTest {

    private static final String BUCKET = "test-bucket";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static S3Client s3Client;
    private static EmailMessageService service;
    private static EmailMessageRepository repository;

    private final List<String> deleteCalls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> failingKeys = Collections.synchronizedSet(new HashSet<>());
    private UUID tenant;
    private UUID otherTenant;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(pool);
        s3Client = mock(S3Client.class);
        S3Properties s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);
        ctx = JpaTestContext.create(pool,
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
        pool.close();
    }

    @BeforeEach
    void setUp() {
        reset(s3Client);
        deleteCalls.clear();
        failingKeys.clear();
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            DeleteObjectRequest request = inv.getArgument(0);
            deleteCalls.add(request.key());
            if (failingKeys.contains(request.key())) {
                throw (S3Exception) S3Exception.builder().message("InternalError (test)").statusCode(500).build();
            }
            return DeleteObjectResponse.builder().build();
        });
        tenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EML " + UUID.randomUUID());
        otherTenant = PostgresTestDatabase.insertTenant(jdbc, "Tenant EML other " + UUID.randomUUID());
        TenantContext.setTenantId(tenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbc.update("DELETE FROM email_message WHERE tenant_id IN (?, ?)", tenant, otherTenant);
    }

    // =========================================================================
    // Odczyt rozmowy
    // =========================================================================

    @Test
    @DisplayName("rozmowa: kolejnosc chronologiczna ASC (message_at, potem created_at), niezaleznie od kolejnosci wstawienia")
    void threadIsChronological() {
        UUID contact = UUID.randomUUID();
        UUID third = insert(tenant, contact, T0.plusSeconds(120), "2026-10-01T10:00:00Z", null);
        UUID first = insert(tenant, contact, T0, "2026-10-01T10:00:00Z", null);
        // ten sam message_at: rozstrzyga created_at
        UUID secondLaterCreated = insert(tenant, contact, T0.plusSeconds(60), "2026-10-01T10:00:02Z", null);
        UUID secondEarlierCreated = insert(tenant, contact, T0.plusSeconds(60), "2026-10-01T10:00:01Z", null);

        EmailThread thread = service.findThreadByContactId(contact, tenant, 100);

        assertThat(thread.messages()).extracting(EmailMessage::getId)
                .containsExactly(first, secondEarlierCreated, secondLaterCreated, third);
        assertThat(thread.total()).isEqualTo(4);
        assertThat(thread.truncated()).isFalse();
        assertThat(service.findEarliestMessageId(contact, tenant)).contains(first);
    }

    @Test
    @DisplayName("limit: zwraca NAJNOWSZE wiadomosci (nadal ASC), total = wszystkie, truncated = true")
    void threadIsCappedToNewestMessages() {
        UUID contact = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insert(tenant, contact, T0.plusSeconds(i * 60L), "2026-10-01T10:00:00Z", null));
        }

        EmailThread thread = service.findThreadByContactId(contact, tenant, 3);

        assertThat(thread.messages()).extracting(EmailMessage::getId).containsExactly(ids.get(2), ids.get(3), ids.get(4));
        assertThat(thread.total()).isEqualTo(5);
        assertThat(thread.truncated()).isTrue();

        EmailThread exact = service.findThreadByContactId(contact, tenant, 5);
        assertThat(exact.messages()).hasSize(5);
        assertThat(exact.truncated()).isFalse();
        assertThat(exact.total()).isEqualTo(5);
    }

    @Test
    @DisplayName("izolacja: wiadomosci innego tenanta o tym samym contact_id nie wchodza do rozmowy ani lookupu")
    void threadIsTenantAndContactScoped() {
        UUID contact = UUID.randomUUID();
        UUID mine = insert(tenant, contact, T0, "2026-10-01T10:00:00Z", null);
        UUID foreign = insert(otherTenant, contact, T0.plusSeconds(10), "2026-10-01T10:00:00Z", null);
        UUID otherContactMsg = insert(tenant, UUID.randomUUID(), T0.plusSeconds(20), "2026-10-01T10:00:00Z", null);

        assertThat(service.findThreadByContactId(contact, tenant, 100).messages())
                .extracting(EmailMessage::getId).containsExactly(mine);
        assertThat(service.findByIdAndContactId(mine, contact, tenant)).isPresent();
        assertThat(service.findByIdAndContactId(foreign, contact, tenant)).as("inny tenant").isEmpty();
        assertThat(service.findByIdAndContactId(otherContactMsg, contact, tenant)).as("inny kontakt").isEmpty();
    }

    // =========================================================================
    // UPDATE klucza
    // =========================================================================

    @Test
    @DisplayName("updateEmlS3Key: ustawia klucz po pelnym kluczu (message_id, message_at); odczyt mapuje emlS3Key; inny tenant -> wyjatek")
    void updateEmlS3KeyStoresKeyAndEntityReadsIt() {
        UUID contact = UUID.randomUUID();
        UUID id = insert(tenant, contact, T0, "2026-10-01T10:00:00Z", null);
        String key = tenant + "/2026/10/" + contact + "/" + id + ".eml";

        assertThat(repository.updateEmlS3Key(id, T0, tenant, key)).isTrue();
        assertThat(repository.updateEmlS3Key(id, T0.plusSeconds(1), tenant, "x")).as("zly message_at -> 0 wierszy").isFalse();

        assertThat(jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id = ?", String.class, id))
                .isEqualTo(key);
        assertThat(repository.findById(id, T0).orElseThrow().getEmlS3Key()).isEqualTo(key);
        assertThat(service.findThreadByContactId(contact, tenant, 10).messages().get(0).getEmlS3Key()).isEqualTo(key);

        assertThatThrownBy(() -> repository.updateEmlS3Key(id, T0, otherTenant, "x"))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    @DisplayName("update(message) (powiazanie kontaktu) NIE nadpisuje eml_s3_key")
    void genericUpdateKeepsEmlKey() {
        UUID id = insert(tenant, null, T0, "2026-10-01T10:00:00Z", tenant + "/eml.eml");
        EmailMessage m = repository.findById(id, T0).orElseThrow();
        m.setContactId(UUID.randomUUID());

        repository.update(m);

        assertThat(jdbc.queryForObject("SELECT eml_s3_key FROM email_message WHERE message_id = ?", String.class, id))
                .isEqualTo(tenant + "/eml.eml");
    }

    // =========================================================================
    // Sprzatanie S3: purge po kontaktach, sieroty, retencja nagran/EML
    // =========================================================================

    @Test
    @DisplayName("purgeByContactIds: usuwa obiekt EML wiadomosci (klucz wspoldzielony z recording_url tez), potem wiersz")
    void purgeByContactDeletesEmlObjects() {
        UUID contact = UUID.randomUUID();
        String firstKey = tenant + "/2026/10/" + contact + ".eml";
        UUID first = insert(tenant, contact, T0, "2026-10-01T10:00:00Z", firstKey);
        UUID follow = UUID.randomUUID();
        String followKey = tenant + "/2026/10/" + contact + "/" + follow + ".eml";
        insert(follow, tenant, contact, T0.plusSeconds(60), "2026-10-01T10:00:00Z", followKey);

        PurgedMessages result = service.purgeByContactIds(tenant, List.of(contact));

        assertThat(deleteCalls).containsExactlyInAnyOrder(firstKey, followKey);
        assertThat(result.deletedRows()).isEqualTo(2);
        assertThat(result.contactIdsBlocked()).isEmpty();
        assertThat(count(contact)).isZero();
        assertThat(first).isNotNull();
    }

    @Test
    @DisplayName("purgeByContactIds: porazka S3 dla EML blokuje wiersz i kontakt (S3 przed wierszem)")
    void emlS3FailureBlocksRowAndContact() {
        UUID contact = UUID.randomUUID();
        String key = tenant + "/2026/10/" + contact + ".eml";
        insert(tenant, contact, T0, "2026-10-01T10:00:00Z", key);
        failingKeys.add(key);

        PurgedMessages result = service.purgeByContactIds(tenant, List.of(contact));

        assertThat(result.deletedRows()).isZero();
        assertThat(result.contactIdsBlocked()).containsExactly(contact);
        assertThat(result.s3Failures()).isEqualTo(1);
        assertThat(count(contact)).isEqualTo(1);
    }

    @Test
    @DisplayName("purgeByContactIds: klucz EML spoza prefiksu tenanta jest odrzucony (obiekt nietkniety), wiersz usuniety")
    void foreignEmlKeyIsRejectedButRowDeleted() {
        UUID contact = UUID.randomUUID();
        String foreignKey = otherTenant + "/2026/10/someone-else.eml";
        insert(tenant, contact, T0, "2026-10-01T10:00:00Z", foreignKey);

        PurgedMessages result = service.purgeByContactIds(tenant, List.of(contact));

        assertThat(deleteCalls).doesNotContain(foreignKey);
        assertThat(result.s3Rejected()).isEqualTo(1);
        assertThat(result.deletedRows()).isEqualTo(1);
        assertThat(count(contact)).isZero();
    }

    @Test
    @DisplayName("sweep sierot: usuwa tez obiekt EML osieroconej wiadomosci")
    void orphanSweepDeletesEmlObject() {
        String key = tenant + "/2026/10/orphan.eml";
        // created_at starszy niz margines rezydualny (1 dzien), message_at starszy niz cutoff
        UUID orphan = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address,
                    subject, attachments, received_at, message_at, created_at, eml_s3_key)
                VALUES (?, ?, NULL, 'INBOUND', 'a@a.pl', 'b@b.pl', 's', '[]'::jsonb, now(),
                        CAST(? AS timestamptz), now() - interval '10 days', ?)
                """, orphan, tenant, T0.toString(), key);

        OrphanEmailPurgeBatch batch = service.purgeOrphansOlderThan(tenant, null, T0.plusSeconds(3600), 100);

        assertThat(deleteCalls).containsExactly(key);
        assertThat(batch.purgedMessages().deletedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("deleteEmlObjectsByContact (retencja nagran/EML): usuwa obiekty i zeruje kolumny; wiersze zostaja")
    void deleteEmlObjectsByContactClearsKeys() {
        UUID contact = UUID.randomUUID();
        String k1 = tenant + "/2026/10/" + contact + ".eml";
        String k2 = tenant + "/2026/10/" + contact + "/m2.eml";
        insert(tenant, contact, T0, "2026-10-01T10:00:00Z", k1);
        insert(tenant, contact, T0.plusSeconds(60), "2026-10-01T10:00:00Z", k2);
        insert(tenant, contact, T0.plusSeconds(120), "2026-10-01T10:00:00Z", null);

        assertThat(service.deleteEmlObjectsByContact(tenant, contact)).isEqualTo(2);

        assertThat(deleteCalls).containsExactlyInAnyOrder(k1, k2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_message WHERE contact_id = ? AND eml_s3_key IS NOT NULL",
                Integer.class, contact)).isZero();
        assertThat(count(contact)).isEqualTo(3);
        // idempotentne
        assertThat(service.deleteEmlObjectsByContact(tenant, contact)).isZero();
    }

    @Test
    @DisplayName("deleteEmlObjectsByContact: porazka S3 -> wyjatek; klucz nieusuniety zostaje do ponowienia, usuniety wyzerowany")
    void deleteEmlObjectsByContactKeepsFailedKeys() {
        UUID contact = UUID.randomUUID();
        String ok = tenant + "/2026/10/" + contact + ".eml";
        String bad = tenant + "/2026/10/" + contact + "/bad.eml";
        insert(tenant, contact, T0, "2026-10-01T10:00:00Z", ok);
        insert(tenant, contact, T0.plusSeconds(60), "2026-10-01T10:00:00Z", bad);
        failingKeys.add(bad);

        assertThatThrownBy(() -> service.deleteEmlObjectsByContact(tenant, contact))
                .isInstanceOf(EmailAttachmentException.class);

        assertThat(jdbc.queryForList("SELECT eml_s3_key FROM email_message WHERE contact_id = ? AND eml_s3_key IS NOT NULL",
                String.class, contact)).containsExactly(bad);
    }

    // =========================================================================
    // Seedowanie
    // =========================================================================

    private UUID insert(UUID tenantId, UUID contactId, Instant messageAt, String createdAt, String emlKey) {
        return insert(UUID.randomUUID(), tenantId, contactId, messageAt, createdAt, emlKey);
    }

    private UUID insert(UUID messageId, UUID tenantId, UUID contactId, Instant messageAt, String createdAt,
                        String emlKey) {
        jdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address, to_address,
                    subject, body_text, attachments, received_at, message_at, created_at, eml_s3_key)
                VALUES (?, ?, ?, 'INBOUND', 'klient@example.com', 'biuro@example.com', 'Temat', 'Tresc',
                        '[]'::jsonb, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz), ?)
                """, messageId, tenantId, contactId, messageAt.toString(), messageAt.toString(), createdAt, emlKey);
        return messageId;
    }

    private int count(UUID contact) {
        return jdbc.queryForObject("SELECT count(*) FROM email_message WHERE contact_id = ?", Integer.class, contact);
    }
}
