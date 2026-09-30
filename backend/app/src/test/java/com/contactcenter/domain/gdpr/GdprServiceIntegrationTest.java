package com.contactcenter.domain.gdpr;

import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.email.EmailAttachmentStorageService;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.security.TenantContext;
import com.contactcenter.support.JpaTestContext;
import com.contactcenter.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test integracyjny {@link GdprServiceImpl} (BE-129) na PRAWDZIWYM PostgreSQL (Testcontainers,
 * pełny łańcuch Flyway) z prawdziwym {@link GdprRepository} — a przez nią z rzeczywistymi funkcjami
 * SQL {@code anonymize_customer}/{@code export_customer_data} (DB-061/DB-062), Hibernate,
 * {@code JpaTransactionManager} i proxy {@code @Transactional}.
 *
 * <p>{@code CustomerService}/{@code RecordingService}/{@code EmailAttachmentStorageService}/
 * {@code AuditLogService} są zwykłymi mockami Mockito wstrzykniętymi ręcznie do
 * {@link GdprServiceImpl} (ta klasa nie jest beanem Springa — nie ma własnych metod
 * {@code @Transactional}, więc nie musi być zarządzana przez kontener; granica S3 jest już
 * wyczerpująco pokryta testami {@code EmailAttachmentStorageServiceImplTest}/BE-125 oraz
 * allow-listy w {@code GdprServiceTest} — tutaj mock wystarcza, bo przedmiotem testu jest
 * poprawność przepływu Java ↔ prawdziwa funkcja SQL, nie sam klient AWS SDK).
 *
 * <p><strong>Zakres tych testów:</strong> WYŁĄCZNIE logika DODANA przez BE-129 w warstwie Javy —
 * istnienie klienta niezależnie od {@code is_deleted} (dosanityzowanie), guard rekordów w toku,
 * przekazanie kluczy S3 z wyniku funkcji do {@code EmailAttachmentStorageService#delete}, kolejność
 * DB→S3, brak podwójnego audytu, brak ucięcia eksportu. Wyczerpujące pokrycie PII/RLS/idempotencji
 * samych funkcji SQL jest w {@code AnonymizeCustomerExtensionTest}/
 * {@code ExportCustomerDataSubjectHelperTest} (DB-061/DB-062) — nie dublowane tutaj.
 */
@DisplayName("GdprServiceImpl – integracja z anonymize_customer/export_customer_data (BE-129)")
class GdprServiceIntegrationTest {

    private static HikariDataSource superuserPool;
    private static HikariDataSource appPool;
    private static JdbcTemplate jdbc;
    private static AnnotationConfigApplicationContext ctx;
    private static GdprRepository gdprRepository;

    private CustomerService customerService;
    private RecordingService recordingService;
    private EmailAttachmentStorageService emailAttachmentStorageService;
    private AuditLogService auditLogService;
    private GdprServiceImpl gdprService;

    private UUID tenantA;
    private UUID tenantB;

    // =========================================================================
    // Infrastruktura
    // =========================================================================

    @BeforeAll
    static void startContext() {
        superuserPool = PostgresTestDatabase.superuserPool(4);
        jdbc = new JdbcTemplate(superuserPool);
        appPool = PostgresTestDatabase.superuserPool(4);

        ctx = JpaTestContext.create(
                appPool,
                new Class<?>[]{Customer.class},
                new Class<?>[]{GdprRepository.class},
                null);
        gdprRepository = ctx.getBean(GdprRepository.class);
    }

    @AfterAll
    static void stopContext() {
        JpaTestContext.close(ctx);
        appPool.close();
        superuserPool.close();
    }

    @BeforeEach
    void setUp() {
        tenantA = PostgresTestDatabase.insertTenant(jdbc, "Tenant A - BE-129 " + UUID.randomUUID());
        tenantB = PostgresTestDatabase.insertTenant(jdbc, "Tenant B - BE-129 " + UUID.randomUUID());

        customerService = mock(CustomerService.class);
        recordingService = mock(RecordingService.class);
        emailAttachmentStorageService = mock(EmailAttachmentStorageService.class);
        auditLogService = mock(AuditLogService.class);

        gdprService = new GdprServiceImpl(
                gdprRepository,
                customerService,
                recordingService,
                emailAttachmentStorageService,
                new com.contactcenter.infrastructure.config.S3Properties(),
                auditLogService,
                new ObjectMapper());

        TenantContext.setTenantId(tenantA);
        TenantContext.setUserId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // anonymizeCustomer
    // =========================================================================

    @Nested
    @DisplayName("anonymizeCustomer")
    class AnonymizeCustomer {

        @Test
        @DisplayName("pełny zestaw danych: klucze S3 z wyniku funkcji SQL przekazane do EmailAttachmentStorageService#delete, PII zanonimizowane")
        void fullDataSet_passesS3KeysFromSqlResultToDeletion_andAnonymizesPii() {
            UUID customerId = insertCustomer(tenantA, "Jan", "Kowalski", "+48500100200", "jan@example.com", false);
            UUID contactId = insertContact(tenantA, customerId, "+48500100200", recordingKey(tenantA));
            insertEmail(tenantA, contactId, attachmentsJson(emailAttachmentKey(tenantA)));

            gdprService.anonymizeCustomer(customerId);

            assertThat(customerIsDeleted(customerId)).isTrue();
            verify(emailAttachmentStorageService).delete(recordingKey(tenantA));
            verify(emailAttachmentStorageService).delete(emailAttachmentKey(tenantA));
            verify(emailAttachmentStorageService, times(2)).delete(anyString());
        }

        @Test
        @DisplayName("awaria S3 PO commit nie cofa anonimizacji – pozostałe klucze nadal próbowane")
        void s3FailureAfterCommit_doesNotRollbackAnonymization_continuesRemainingKeys() {
            UUID customerId = insertCustomer(tenantA, "Anna", "Nowak", "+48500100201", "anna@example.com", false);
            UUID contactId = insertContact(tenantA, customerId, "+48500100201", recordingKey(tenantA));
            insertEmail(tenantA, contactId, attachmentsJson(emailAttachmentKey(tenantA)));

            doThrow(new RuntimeException("S3 unreachable (test)"))
                    .when(emailAttachmentStorageService).delete(recordingKey(tenantA));

            assertThatNoException().isThrownBy(() -> gdprService.anonymizeCustomer(customerId));

            // PII w bazie usunięte mimo awarii S3 (kolejność DB -> S3, DESIGN R8).
            assertThat(customerIsDeleted(customerId)).isTrue();
            // Drugi klucz nadal usunięty – awaria pierwszego nie przerywa pętli.
            verify(emailAttachmentStorageService).delete(emailAttachmentKey(tenantA));
        }

        @Test
        @DisplayName("błąd bazy (guard DB061-08: kolizja record_id między kampaniami) NIE wywołuje usuwania S3, zero zmian")
        void dbErrorBeforeCommit_doesNotCallS3Delete_noChanges() {
            UUID customerId = insertCustomer(tenantA, "Ewa", "Kolizja", "+48500100202", null, false);
            UUID collidingRecordId = UUID.randomUUID();
            UUID campaignA = insertCampaign(tenantA, "Kampania A");
            UUID campaignB = insertCampaign(tenantA, "Kampania B");
            insertCampaignContact(collidingRecordId, campaignA, tenantA, customerId, "+48500100202", "Ewa");
            insertCampaignContact(collidingRecordId, campaignB, tenantA, null, "+48999999999", "Obcy");

            assertThatThrownBy(() -> gdprService.anonymizeCustomer(customerId))
                    .isInstanceOf(RuntimeException.class);

            assertThat(customerIsDeleted(customerId)).isFalse();
            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("klient innego tenanta -> EntityNotFoundException, zero zmian, S3 nietknięte")
        void foreignTenant_throwsEntityNotFoundException_noChanges() {
            UUID customerIdInTenantA = insertCustomer(tenantA, "Piotr", "Obcy", "+48500100203", null, false);

            TenantContext.setTenantId(tenantB);
            try {
                assertThatThrownBy(() -> gdprService.anonymizeCustomer(customerIdInTenantA))
                        .isInstanceOf(EntityNotFoundException.class)
                        .hasMessageContaining(customerIdInTenantA.toString());
            } finally {
                TenantContext.setTenantId(tenantA);
            }

            assertThat(customerIsDeleted(customerIdInTenantA)).isFalse();
            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("rekord w toku: campaign_contact w statusie DIALING -> ConflictException (409), zero zmian")
        void recordInProgress_dialingCampaignContact_throwsConflict_noChanges() {
            UUID customerId = insertCustomer(tenantA, "Marek", "Dzwoni", "+48500100204", null, false);
            UUID recordId = UUID.randomUUID();
            UUID campaignId = insertCampaign(tenantA, "Kampania dialing");
            jdbc.update("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone,
                        first_name, last_name, custom_fields, status, attempt_count)
                    VALUES (?, ?, ?, ?, ?, 'Marek', 'Dzwoni', '{}'::jsonb, 'DIALING', 1)
                    """, recordId, campaignId, tenantA, customerId, "+48500100204");

            assertThatThrownBy(() -> gdprService.anonymizeCustomer(customerId))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(customerId.toString());

            assertThat(customerIsDeleted(customerId)).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM campaign_contact WHERE record_id = ?", String.class, recordId))
                    .isEqualTo("DIALING");
            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("rekord w toku: scheduled_callback w statusie PROCESSING -> ConflictException (409), zero zmian")
        void recordInProgress_processingScheduledCallback_throwsConflict_noChanges() {
            UUID customerId = insertCustomer(tenantA, "Kasia", "Callback", "+48500100205", null, false);
            UUID callbackId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name,
                        last_name, scheduled_at, status, source_type)
                    VALUES (?, ?, ?, ?, 'Kasia', 'Callback', now() + interval '1 hour', 'PROCESSING', 'AGENT_MANUAL')
                    """, callbackId, tenantA, customerId, "+48500100205");

            assertThatThrownBy(() -> gdprService.anonymizeCustomer(customerId))
                    .isInstanceOf(ConflictException.class);

            assertThat(customerIsDeleted(customerId)).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM scheduled_callback WHERE callback_id = ?", String.class, callbackId))
                    .isEqualTo("PROCESSING");
            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("dosanityzowanie: klient is_deleted=TRUE (dawna ścieżka Javy) -> kończy anonimizację reszty bez błędu")
        void dosanityzacja_customerAlreadyDeletedViaJavaPath_completesWithoutError() {
            UUID customerId = insertCustomer(tenantA, "ANONYMIZED", "ANONYMIZED", null, null, true);
            jdbc.update("UPDATE customer SET external_id = 'ERP-999', custom_fields = '{\"vip\":true}'::jsonb "
                    + "WHERE customer_id = ?", customerId);

            assertThatNoException().isThrownBy(() -> gdprService.anonymizeCustomer(customerId));

            assertThat(jdbc.queryForObject(
                    "SELECT external_id IS NULL FROM customer WHERE customer_id = ?", Boolean.class, customerId))
                    .isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT custom_fields::text FROM customer WHERE customer_id = ?", String.class, customerId))
                    .isEqualTo("{}");
        }

        @Test
        @DisplayName("dokładnie jeden wpis audytowy CUSTOMER_ANONYMIZED (z funkcji SQL) – Java nie dubluje audytu")
        void exactlyOneAuditEntry_writtenBySqlFunction_javaDoesNotDuplicate() {
            UUID customerId = insertCustomer(tenantA, "Tomasz", "Audyt", "+48500100206", null, false);

            gdprService.anonymizeCustomer(customerId);

            Long auditCount = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'CUSTOMER_ANONYMIZED'",
                    Long.class, customerId);
            assertThat(auditCount).isEqualTo(1L);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("po anonimizacji predykaty ProgressiveDialerServiceImpl/ScheduledCallbackRepository nie widzą zanonimizowanych rekordów")
        void afterAnonymize_dialerAndCallbackPredicates_returnZeroRows() {
            UUID customerId = insertCustomer(tenantA, "Zofia", "Kolejka", "+48500100207", null, false);
            UUID campaignId = insertCampaign(tenantA, "Kampania predykat");
            UUID recordId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone,
                        first_name, last_name, custom_fields, status, attempt_count)
                    VALUES (?, ?, ?, ?, ?, 'Zofia', 'Kolejka', '{}'::jsonb, 'PENDING', 0)
                    """, recordId, campaignId, tenantA, customerId, "+48500100207");
            UUID callbackId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO scheduled_callback (callback_id, tenant_id, customer_id, phone, first_name,
                        last_name, scheduled_at, status, source_type)
                    VALUES (?, ?, ?, ?, 'Zofia', 'Kolejka', now() + interval '1 hour', 'PENDING', 'AGENT_MANUAL')
                    """, callbackId, tenantA, customerId, "+48500100207");

            gdprService.anonymizeCustomer(customerId);

            // Replika dokładnego predykatu ProgressiveDialerServiceImpl#fetchNextPendingContact.
            Long dialerHits = jdbc.queryForObject("""
                    SELECT count(*) FROM campaign_contact
                    WHERE campaign_id = ? AND tenant_id = ? AND status IN ('PENDING', 'NO_ANSWER')
                      AND record_id = ?
                    """, Long.class, campaignId, tenantA, recordId);
            assertThat(dialerHits).isZero();

            // Replika dokładnego predykatu ScheduledCallbackRepository#findPendingByTenantId/countPendingByTenantId.
            Long callbackHits = jdbc.queryForObject("""
                    SELECT count(*) FROM scheduled_callback
                    WHERE tenant_id = ? AND status = 'PENDING' AND callback_id = ?
                    """, Long.class, tenantA, callbackId);
            assertThat(callbackHits).isZero();
        }
    }

    // =========================================================================
    // previewAnonymizeCustomer
    // =========================================================================

    @Nested
    @DisplayName("previewAnonymizeCustomer")
    class PreviewAnonymizeCustomer {

        @Test
        @DisplayName("podgląd niczego nie zmienia w bazie ani w S3, dwa wywołania identyczne")
        void doesNotMutateDbOrS3_twoCallsIdentical() {
            UUID customerId = insertCustomer(tenantA, "Robert", "Podglad", "+48500100208", null, false);
            insertContact(tenantA, customerId, "+48500100208", recordingKey(tenantA));

            AnonymizePreviewResponse first = gdprService.previewAnonymizeCustomer(customerId);
            AnonymizePreviewResponse second = gdprService.previewAnonymizeCustomer(customerId);

            assertThat(first).isEqualTo(second);
            assertThat(first.dryRun()).isTrue();
            assertThat(first.counts().get("contact")).isEqualTo(1);
            assertThat(first.s3ObjectsToDelete()).isEqualTo(1);

            assertThat(customerIsDeleted(customerId)).isFalse();
            verifyNoInteractions(emailAttachmentStorageService);
        }
    }

    // =========================================================================
    // exportCustomerData
    // =========================================================================

    @Nested
    @DisplayName("exportCustomerData")
    class ExportCustomerData {

        @Test
        @DisplayName("brak ucięcia: klient z > 1000 kontaktami -> komplet w contacts.json")
        void noTruncation_over1000Contacts() throws Exception {
            UUID customerId = insertCustomer(tenantA, "Wielu", "Kontaktow", "+48500100209", null, false);
            when(customerService.findById(customerId, tenantA)).thenReturn(Optional.of(mockCustomerEntity(customerId)));

            int total = 1050;
            List<Object[]> batchArgs = new java.util.ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                batchArgs.add(new Object[]{UUID.randomUUID(), tenantA, customerId});
            }
            jdbc.batchUpdate("""
                    INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status, started_at)
                    VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', now())
                    """, batchArgs);

            byte[] zip = gdprService.exportCustomerData(customerId);

            Map<String, byte[]> entries = extractZipEntries(zip);
            JsonNode contacts = new ObjectMapper().readTree(entries.get("contacts.json"));
            assertThat(contacts.isArray()).isTrue();
            assertThat(contacts.size()).isEqualTo(total);
        }

        @Test
        @DisplayName("manifest.json niesie presigned URL dla obu schematów kluczy, ZIP nie zawiera plików audio/załączników")
        void manifestHasPresignedUrlsForBothSchemas_noRawFilesInZip() throws Exception {
            UUID customerId = insertCustomer(tenantA, "Manifest", "Test", "+48500100210", null, false);
            when(customerService.findById(customerId, tenantA)).thenReturn(Optional.of(mockCustomerEntity(customerId)));

            UUID contactId = insertContact(tenantA, customerId, "+48500100210", recordingKey(tenantA));
            insertEmail(tenantA, contactId, attachmentsJson(emailAttachmentKey(tenantA)));

            when(emailAttachmentStorageService.presignedDownloadUrl(emailAttachmentKey(tenantA)))
                    .thenReturn("https://minio.test/attachment-presigned");
            when(recordingService.generatePresignedUrlForKey(eq(recordingKey(tenantA)), any()))
                    .thenReturn("https://minio.test/recording-presigned");

            byte[] zip = gdprService.exportCustomerData(customerId);

            Map<String, byte[]> entries = extractZipEntries(zip);
            assertThat(entries).doesNotContainKeys(
                    recordingKey(tenantA), emailAttachmentKey(tenantA), "recording.mp3", "attachment.pdf");

            String manifestJson = new String(entries.get("manifest.json"));
            assertThat(manifestJson)
                    .contains(recordingKey(tenantA)).contains("https://minio.test/recording-presigned").contains("RECORDING")
                    .contains(emailAttachmentKey(tenantA)).contains("https://minio.test/attachment-presigned").contains("EMAIL_ATTACHMENT");
        }

        @Test
        @DisplayName("publikuje dokładnie jeden wpis audytowy GDPR_EXPORT")
        void publishesExactlyOneAuditEvent() {
            UUID customerId = insertCustomer(tenantA, "Audyt", "Export", "+48500100211", null, false);
            when(customerService.findById(customerId, tenantA)).thenReturn(Optional.of(mockCustomerEntity(customerId)));

            gdprService.exportCustomerData(customerId);

            verify(auditLogService, times(1)).publishAuditEvent(argThat(event ->
                    "GDPR_EXPORT".equals(event.action()) && customerId.equals(event.entityId())));
        }
    }

    // =========================================================================
    // Seedowanie
    // =========================================================================

    private UUID insertCustomer(UUID tenant, String firstName, String lastName, String phone, String email, boolean deleted) {
        UUID customerId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, source, is_deleted)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), 'MANUAL', ?)
                """,
                customerId, tenant, firstName, lastName,
                phone != null ? "[\"" + phone + "\"]" : "[]",
                email != null ? "[\"" + email + "\"]" : "[]",
                deleted);
        return customerId;
    }

    private UUID insertContact(UUID tenant, UUID customerId, String remoteAddress, String recordingUrl) {
        UUID contactId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO contact (contact_id, tenant_id, customer_id, channel, direction, status,
                    remote_address, started_at, recording_url)
                VALUES (?, ?, ?, 'PHONE', 'INBOUND', 'COMPLETED', ?, now(), ?)
                """, contactId, tenant, customerId, remoteAddress, recordingUrl);
        return contactId;
    }

    private void insertEmail(UUID tenant, UUID contactId, String attachmentsJson) {
        jdbc.update("""
                INSERT INTO email_message (message_id, tenant_id, contact_id, direction, from_address,
                    to_address, subject, attachments, received_at, delivery_status)
                VALUES (?, ?, ?, 'INBOUND', 'klient@example.com', 'biuro@example.com', 'Temat',
                    CAST(? AS jsonb), now(), 'DELIVERED')
                """, UUID.randomUUID(), tenant, contactId, attachmentsJson);
    }

    private UUID insertCampaign(UUID tenant, String name) {
        UUID campaignId = UUID.randomUUID();
        jdbc.update("INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, ?)", campaignId, tenant, name);
        return campaignId;
    }

    private void insertCampaignContact(UUID recordId, UUID campaignId, UUID tenant, UUID customerId, String phone, String firstName) {
        jdbc.update("""
                INSERT INTO campaign_contact (record_id, campaign_id, tenant_id, customer_id, phone,
                    first_name, last_name, custom_fields, status, attempt_count)
                VALUES (?, ?, ?, ?, ?, ?, 'Test', '{}'::jsonb, 'PENDING', 0)
                """, recordId, campaignId, tenant, customerId, phone, firstName);
    }

    private String recordingKey(UUID tenant) {
        return tenant + "/2026/09/recording-be129.mp3";
    }

    private String emailAttachmentKey(UUID tenant) {
        return "email-attachments/" + tenant + "/msg-be129/zalacznik.pdf";
    }

    private String attachmentsJson(String s3Key) {
        return "[{\"filename\":\"zal.pdf\",\"content_type\":\"application/pdf\",\"size_bytes\":10,\"s3_key\":\"" + s3Key + "\"}]";
    }

    private boolean customerIsDeleted(UUID customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_deleted FROM customer WHERE customer_id = ?", Boolean.class, customerId));
    }

    /** Encja minimalna, wystarczająca do przejścia guardu istnienia w {@code exportCustomerData} (mock CustomerService). */
    private Customer mockCustomerEntity(UUID customerId) {
        return Customer.builder()
                .customerId(customerId)
                .tenantId(tenantA)
                .firstName("Test")
                .lastName("Test")
                .phone(List.of())
                .email(List.of())
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("MANUAL")
                .deleted(false)
                .build();
    }

    private Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }
}
