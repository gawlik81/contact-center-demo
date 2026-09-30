package com.contactcenter.domain.gdpr;

import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.email.EmailAttachmentException;
import com.contactcenter.domain.email.EmailAttachmentStorageService;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link GdprServiceImpl} (BE-129).
 *
 * <p>Od BE-129 cała logika RODO (istnienie klienta, zbiór podmiotu D9, guard rekordów w toku,
 * mutacje) żyje w funkcjach SQL wywoływanych przez {@link GdprRepository} — te testy mockują
 * repozytorium (zwraca surowy JSONB jak funkcja SQL) i weryfikują WYŁĄCZNIE logikę tej klasy:
 * mapowanie wyniku na wyjątki domenowe, budowę ZIP-a eksportu, sprzątanie S3 po commit i brak
 * podwójnego audytu. Zachowanie samych funkcji SQL jest pokryte testami Testcontainers w
 * {@code AnonymizeCustomerExtensionTest}/{@code ExportCustomerDataSubjectHelperTest} (DB-061/062)
 * oraz {@code GdprServiceAnonymizeIntegrationTest}/{@code GdprServiceExportIntegrationTest} (BE-129,
 * na prawdziwej bazie, z S3 mockowanym na granicy).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GdprServiceImpl – orkiestracja RODO (BE-129)")
class GdprServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID     = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String EMAIL_ATTACHMENT_KEY =
            "email-attachments/" + TENANT_ID + "/msg-1/zalacznik.pdf";
    private static final String RECORDING_KEY = TENANT_ID + "/2026/09/contact-1.mp3";

    @Mock private GdprRepository gdprRepository;
    @Mock private CustomerService customerService;
    @Mock private RecordingService recordingService;
    @Mock private EmailAttachmentStorageService emailAttachmentStorageService;
    @Mock private AuditLogService auditLogService;

    private GdprServiceImpl gdprService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("SUPERVISOR");

        // Ręczna konstrukcja – ObjectMapper/S3Properties nie są mockami.
        gdprService = new GdprServiceImpl(
                gdprRepository,
                customerService,
                recordingService,
                emailAttachmentStorageService,
                new S3Properties(),
                auditLogService,
                new ObjectMapper().registerModule(new JavaTimeModule())
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // exportCustomerData
    // =========================================================================

    @Nested
    @DisplayName("exportCustomerData")
    class ExportCustomerData {

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy klient nie istnieje (przed wywołaniem funkcji SQL)")
        void throwsEntityNotFoundException_whenCustomerNotFound() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> gdprService.exportCustomerData(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CUSTOMER_ID.toString());

            verifyNoInteractions(gdprRepository, auditLogService);
        }

        @Test
        @DisplayName("zwraca ZIP z kompletem zbiorów danych (bez plików audio/załączników) i manifestem")
        void returnsZipWithAllDataSetsAndManifest() throws Exception {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildCustomer()));
            when(gdprRepository.exportCustomerData(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(fullExportJson());
            when(emailAttachmentStorageService.presignedDownloadUrl(EMAIL_ATTACHMENT_KEY))
                    .thenReturn("https://s3.example/attachment-presigned");
            when(recordingService.generatePresignedUrlForKey(eq(RECORDING_KEY), any()))
                    .thenReturn("https://s3.example/recording-presigned");

            byte[] zipBytes = gdprService.exportCustomerData(CUSTOMER_ID);

            Map<String, byte[]> entries = extractZipEntries(zipBytes);
            assertThat(entries).containsOnlyKeys(
                    "customer.json", "contacts.json", "scheduled_callbacks.json", "campaign_records.json",
                    "email_messages.json", "social_messages.json", "transcriptions.json", "ai_summaries.json",
                    "manifest.json");

            String manifestJson = new String(entries.get("manifest.json"));
            assertThat(manifestJson)
                    .contains("\"matchedByLink\" : 2")
                    .contains("\"matchedByIdentifier\" : 1")
                    .contains(EMAIL_ATTACHMENT_KEY)
                    .contains("https://s3.example/attachment-presigned")
                    .contains(RECORDING_KEY)
                    .contains("https://s3.example/recording-presigned")
                    .contains("EMAIL_ATTACHMENT")
                    .contains("RECORDING");
        }

        @Test
        @DisplayName("publikuje dokładnie jeden wpis audytowy GDPR_EXPORT")
        void publishesExactlyOneAuditEvent() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildCustomer()));
            when(gdprRepository.exportCustomerData(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(minimalExportJson());

            gdprService.exportCustomerData(CUSTOMER_ID);

            verify(auditLogService, times(1)).publishAuditEvent(argThat(event ->
                    "GDPR_EXPORT".equals(event.action())
                    && "CUSTOMER".equals(event.entityType())
                    && CUSTOMER_ID.equals(event.entityId())
                    && TENANT_ID.equals(event.tenantId())
                    && USER_ID.equals(event.userId())
            ));
        }

        @Test
        @DisplayName("BE-142 Zakres p.4: wpis audytowy GDPR_EXPORT nie niesie żadnego PII eksportowanego klienta – oldValue/newValue są null")
        void auditEvent_carriesNoCustomerPii_oldAndNewValueAreNull() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildCustomer()));
            when(gdprRepository.exportCustomerData(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(fullExportJson());

            gdprService.exportCustomerData(CUSTOMER_ID);

            ArgumentCaptor<com.contactcenter.domain.audit.AuditLogEvent> captor =
                    ArgumentCaptor.forClass(com.contactcenter.domain.audit.AuditLogEvent.class);
            verify(auditLogService).publishAuditEvent(captor.capture());
            assertThat(captor.getValue().oldValue()).isNull();
            assertThat(captor.getValue().newValue()).isNull();
        }

        @Test
        @DisplayName("nie ucina eksportu – deleguje w całości do export_customer_data bez limitu w Javie")
        void doesNotTruncate_delegatesEntirelyToSqlFunction() {
            when(customerService.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildCustomer()));
            when(gdprRepository.exportCustomerData(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(minimalExportJson());

            gdprService.exportCustomerData(CUSTOMER_ID);

            // Jedyne wywołanie repozytorium – żadnej paginacji/limitu po stronie Javy.
            verify(gdprRepository, times(1)).exportCustomerData(CUSTOMER_ID, TENANT_ID);
        }
    }

    // =========================================================================
    // anonymizeCustomer
    // =========================================================================

    @Nested
    @DisplayName("anonymizeCustomer")
    class AnonymizeCustomer {

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy klient nie istnieje w tenancie")
        void throwsEntityNotFoundException_whenCustomerNotFound() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(false, false, null));

            assertThatThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CUSTOMER_ID.toString());

            verifyNoInteractions(emailAttachmentStorageService, auditLogService);
        }

        @Test
        @DisplayName("rzuca ConflictException (409) gdy klient ma rekord w toku (DIALING/PROCESSING)")
        void throwsConflictException_whenRecordInProgress() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(true, true, null));

            assertThatThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(CUSTOMER_ID.toString());

            verifyNoInteractions(emailAttachmentStorageService, auditLogService);
        }

        // BE-142 Zakres p.4: ten test (pre-existing, BE-129) już potwierdza dokładnie AC BE-142 –
        // "GdprServiceImpl nie wkłada PII do własnych wpisów audytu" dla anonimizacji jest spełnione
        // przez PROSTSZY fakt: anonymizeCustomer w ogóle NIE publikuje żadnego własnego wpisu audytowego
        // (jedyny wpis CUSTOMER_ANONYMIZED zapisuje SQL, bez kluczy z AuditPiiKeys – patrz V098 nagłówek,
        // sekcja 3, akapit o kolejności kroku 10). Regresja tego stanu (np. przyszłe dodanie @Audited na
        // GdprServiceImpl) zostanie natychmiast wykryta przez verifyNoInteractions poniżej.
        @Test
        @DisplayName("nie publikuje własnego wpisu audytowego – funkcja SQL już zapisała CUSTOMER_ANONYMIZED")
        void doesNotPublishOwnAuditEvent() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(true, false, resultJson(java.util.List.of())));

            gdprService.anonymizeCustomer(CUSTOMER_ID);

            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("usuwa obiekty S3 (załącznik e-mail i nagranie) po commit, klasyfikując klucz do właściwej allow-listy")
        void deletesS3ObjectsAfterCommit_classifiedByAllowList() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(
                            true, false, resultJson(java.util.List.of(EMAIL_ATTACHMENT_KEY, RECORDING_KEY))));

            gdprService.anonymizeCustomer(CUSTOMER_ID);

            verify(emailAttachmentStorageService).delete(EMAIL_ATTACHMENT_KEY);
            verify(emailAttachmentStorageService).delete(RECORDING_KEY);
            verify(emailAttachmentStorageService, times(2)).delete(anyString());
        }

        @Test
        @DisplayName("błąd usuwania S3 nie cofa anonimizacji – kontynuuje pozostałe klucze")
        void continuesAnonymization_whenS3DeletionFails() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(
                            true, false, resultJson(java.util.List.of(EMAIL_ATTACHMENT_KEY, RECORDING_KEY))));
            doThrow(new EmailAttachmentException("S3 unreachable", new RuntimeException("boom")))
                    .when(emailAttachmentStorageService).delete(EMAIL_ATTACHMENT_KEY);

            assertThatNoException().isThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID));

            // Drugi klucz nadal próbowany – awaria pierwszego nie przerywa pętli.
            verify(emailAttachmentStorageService).delete(RECORDING_KEY);
        }

        @Test
        @DisplayName("pomija usuwanie i loguje ostrzeżenie gdy klucz S3 nie pasuje do żadnej allow-listy tenanta")
        void skipsDeletion_whenKeyDoesNotMatchAnyAllowList() {
            String foreignKey = "email-attachments/" + UUID.randomUUID() + "/msg/zal.pdf";
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(
                            true, false, resultJson(java.util.List.of(foreignKey))));

            gdprService.anonymizeCustomer(CUSTOMER_ID);

            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("brak kluczy S3 – nie wywołuje w ogóle storage'u")
        void noS3Keys_doesNotCallStorage() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(true, false, resultJson(java.util.List.of())));

            gdprService.anonymizeCustomer(CUSTOMER_ID);

            verifyNoInteractions(emailAttachmentStorageService);
        }

        @Test
        @DisplayName("przekazuje dryRun=false do repozytorium (rzeczywista anonimizacja)")
        void passesDryRunFalseToRepository() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(true, false, resultJson(java.util.List.of())));

            gdprService.anonymizeCustomer(CUSTOMER_ID);

            verify(gdprRepository).anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, false);
        }
    }

    // =========================================================================
    // previewAnonymizeCustomer
    // =========================================================================

    @Nested
    @DisplayName("previewAnonymizeCustomer")
    class PreviewAnonymizeCustomer {

        @Test
        @DisplayName("przekazuje dryRun=true do repozytorium i niczego nie usuwa z S3")
        void passesDryRunTrue_neverTouchesS3() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, true))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(
                            true, false, resultJson(true, java.util.List.of(EMAIL_ATTACHMENT_KEY))));

            gdprService.previewAnonymizeCustomer(CUSTOMER_ID);

            verify(gdprRepository).anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, true);
            verifyNoInteractions(emailAttachmentStorageService, auditLogService);
        }

        @Test
        @DisplayName("mapuje liczniki, matched_by i liczbę obiektów S3 z wyniku JSONB")
        void mapsCountsAndMatchedByAndS3Count() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, true))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(
                            true, false, resultJson(true, java.util.List.of(EMAIL_ATTACHMENT_KEY, RECORDING_KEY))));

            AnonymizePreviewResponse preview = gdprService.previewAnonymizeCustomer(CUSTOMER_ID);

            assertThat(preview.dryRun()).isTrue();
            assertThat(preview.counts()).containsEntry("contact", 2).containsEntry("customer", 1);
            assertThat(preview.matchedByLink()).isEqualTo(2);
            assertThat(preview.matchedByIdentifier()).isEqualTo(1);
            assertThat(preview.s3ObjectsToDelete()).isEqualTo(2);
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy klient nie istnieje")
        void throwsEntityNotFoundException_whenCustomerNotFound() {
            when(gdprRepository.anonymize(CUSTOMER_ID, TENANT_ID, USER_ID, true))
                    .thenReturn(new GdprRepository.AnonymizeAttempt(false, false, null));

            assertThatThrownBy(() -> gdprService.previewAnonymizeCustomer(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Customer buildCustomer() {
        return Customer.builder()
                .customerId(CUSTOMER_ID)
                .tenantId(TENANT_ID)
                .firstName("Jan")
                .lastName("Kowalski")
                .phone(java.util.List.of("+48501234567"))
                .email(java.util.List.of("jan@example.com"))
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("MANUAL")
                .deleted(false)
                .createdAt(Instant.now())
                .build();
    }

    /** JSONB minimalny – tylko pola wymagane do zbudowania ZIP-a bez wyjątków. */
    private String minimalExportJson() {
        return """
                {
                  "export_generated_at": "2026-09-24T10:00:00Z",
                  "legal_basis": "GDPR_ART_15_20",
                  "customer": {"customer_id": "%s"},
                  "contacts": [],
                  "scheduled_callbacks": [],
                  "campaign_records": [],
                  "email_messages": [],
                  "social_messages": [],
                  "transcriptions": [],
                  "ai_summaries": [],
                  "s3_keys": [],
                  "matched_by_link": 0,
                  "matched_by_identifier": 0
                }
                """.formatted(CUSTOMER_ID);
    }

    /** JSONB pełny – z kluczami S3 obu schematów i licznikami matched_by. */
    private String fullExportJson() {
        return """
                {
                  "export_generated_at": "2026-09-24T10:00:00Z",
                  "legal_basis": "GDPR_ART_15_20",
                  "customer": {"customer_id": "%s"},
                  "contacts": [{"contact_id": "c1"}],
                  "scheduled_callbacks": [],
                  "campaign_records": [],
                  "email_messages": [],
                  "social_messages": [],
                  "transcriptions": [],
                  "ai_summaries": [],
                  "s3_keys": ["%s", "%s"],
                  "matched_by_link": 2,
                  "matched_by_identifier": 1
                }
                """.formatted(CUSTOMER_ID, EMAIL_ATTACHMENT_KEY, RECORDING_KEY);
    }

    /** JSONB wyniku {@code anonymize_customer} (dryRun=false) z podanymi kluczami S3 (kontrakt DB-062). */
    private String resultJson(java.util.List<String> s3Keys) {
        return resultJson(false, s3Keys);
    }

    /** JSONB wyniku {@code anonymize_customer} z podanymi kluczami S3 (kontrakt DB-062). */
    private String resultJson(boolean dryRun, java.util.List<String> s3Keys) {
        String keysJson = s3Keys.stream()
                .map(k -> "\"" + k + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return """
                {
                  "dry_run": %s,
                  "counts": {"customer": 1, "contact": 2, "scheduled_callback": 0, "campaign_contact": 0,
                             "campaign_contact_archive": 0, "contact_transcription": 0, "contact_ai_summary": 0,
                             "email_message": 0, "social_message": 0, "contacts_dw": 0},
                  "matched_by_link": 2,
                  "matched_by_identifier": 1,
                  "s3_keys": [%s]
                }
                """.formatted(dryRun, keysJson);
    }

    private Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }
}
