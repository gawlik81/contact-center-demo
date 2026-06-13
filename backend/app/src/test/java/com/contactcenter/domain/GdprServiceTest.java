package com.contactcenter.domain;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.ContactId;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.customer.CustomerRepository;
import com.contactcenter.domain.service.AuditLogService;
import com.contactcenter.domain.service.GdprService;
import com.contactcenter.domain.service.RecordingService;
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
import java.util.*;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link GdprService}.
 *
 * <p>Weryfikuje logikę eksportu danych (Art. 20) i anonimizacji klienta (Art. 17).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GdprService – eksport danych i anonimizacja RODO")
class GdprServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID     = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private CustomerRepository customerRepository;
    @Mock private ContactRepository  contactRepository;
    @Mock private RecordingService   recordingService;
    @Mock private AuditLogService    auditLogService;

    private GdprService gdprService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("SUPERVISOR");

        // Ręczna konstrukcja – ObjectMapper nie jest mockiem
        gdprService = new GdprService(
                customerRepository,
                contactRepository,
                recordingService,
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
        @DisplayName("zwraca niepusty ZIP gdy klient istnieje")
        void returnsNonEmptyZip_whenCustomerExists() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 1000))
                    .thenReturn(Collections.emptyList());

            // when
            byte[] result = gdprService.exportCustomerData(CUSTOMER_ID);

            // then
            assertThat(result).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("archiwum ZIP zawiera customer.json, contacts.json, audit_log.json")
        void zipContainsRequiredEntries() throws Exception {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 1000))
                    .thenReturn(Collections.emptyList());

            // when
            byte[] result = gdprService.exportCustomerData(CUSTOMER_ID);

            // then – sprawdź wpisy w ZIP
            Set<String> entries = new HashSet<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(result))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    entries.add(entry.getName());
                    zis.closeEntry();
                }
            }
            assertThat(entries).containsExactlyInAnyOrder("customer.json", "contacts.json", "audit_log.json");
        }

        @Test
        @DisplayName("publikuje zdarzenie audytowe GDPR_EXPORT")
        void publishesAuditEvent() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 1000))
                    .thenReturn(Collections.emptyList());

            // when
            gdprService.exportCustomerData(CUSTOMER_ID);

            // then
            verify(auditLogService).publishAuditEvent(argThat(event ->
                    "GDPR_EXPORT".equals(event.action())
                    && "CUSTOMER".equals(event.entityType())
                    && CUSTOMER_ID.equals(event.entityId())
                    && TENANT_ID.equals(event.tenantId())
                    && USER_ID.equals(event.userId())
            ));
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy klient nie istnieje")
        void throwsEntityNotFoundException_whenCustomerNotFound() {
            // given
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> gdprService.exportCustomerData(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CUSTOMER_ID.toString());

            // brak audytu – błąd przed eksportem
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("ZIP zawiera historię kontaktów gdy klient ma kontakty")
        void zipContainsContacts_whenCustomerHasContacts() throws Exception {
            // given
            Customer customer = buildCustomer();
            List<Contact> contacts = List.of(buildContact(), buildContact());

            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findByCustomerId(CUSTOMER_ID, TENANT_ID, 0, 1000))
                    .thenReturn(contacts);

            // when
            byte[] result = gdprService.exportCustomerData(CUSTOMER_ID);

            // then – contacts.json musi istnieć i zawierać dane (nie pusta tablica)
            Map<String, byte[]> entriesMap = extractZipEntries(result);
            assertThat(entriesMap).containsKey("contacts.json");

            String contactsJson = new String(entriesMap.get("contacts.json"));
            assertThat(contactsJson).contains("contactId").isNotEqualTo("[]");
        }
    }

    // =========================================================================
    // anonymizeCustomer
    // =========================================================================

    @Nested
    @DisplayName("anonymizeCustomer")
    class AnonymizeCustomer {

        @Test
        @DisplayName("anonimizuje klienta i publikuje zdarzenie GDPR_ANONYMIZE")
        void anonymizesCustomerAndPublishesEvent() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findRecordingUrlsByCustomer(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Collections.emptyList());
            when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(1);

            // when
            gdprService.anonymizeCustomer(CUSTOMER_ID);

            // then
            verify(customerRepository).anonymize(CUSTOMER_ID, TENANT_ID);
            verify(auditLogService).publishAuditEvent(argThat(event ->
                    "GDPR_ANONYMIZE".equals(event.action())
                    && "CUSTOMER".equals(event.entityType())
                    && CUSTOMER_ID.equals(event.entityId())
                    && TENANT_ID.equals(event.tenantId())
                    && USER_ID.equals(event.userId())
            ));
        }

        @Test
        @DisplayName("usuwa nagrania z S3 przed anonimizacją")
        void deletesRecordingsFromS3() {
            // given
            String s3Key1 = "tenant-id/2025/01/contact1.mp3";
            String s3Key2 = "tenant-id/2025/02/contact2.mp3";

            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findRecordingUrlsByCustomer(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(List.of(s3Key1, s3Key2));
            when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(1);

            // when
            gdprService.anonymizeCustomer(CUSTOMER_ID);

            // then
            verify(recordingService).deleteFromS3(s3Key1);
            verify(recordingService).deleteFromS3(s3Key2);
        }

        @Test
        @DisplayName("kontynuuje anonimizację gdy usunięcie nagrania z S3 się nie powiedzie")
        void continuesAnonymization_whenS3DeletionFails() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findRecordingUrlsByCustomer(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(List.of("tenant-id/2025/01/contact1.mp3"));
            doThrow(new RuntimeException("S3 connection error"))
                    .when(recordingService).deleteFromS3(anyString());
            when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(1);

            // when / then – nie rzuca wyjątku, kontynuuje anonimizację
            assertThatNoException().isThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID));
            verify(customerRepository).anonymize(CUSTOMER_ID, TENANT_ID);
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy klient nie istnieje")
        void throwsEntityNotFoundException_whenCustomerNotFound() {
            // given
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CUSTOMER_ID.toString());

            verifyNoInteractions(auditLogService);
            verifyNoInteractions(recordingService);
        }

        @Test
        @DisplayName("rzuca EntityNotFoundException gdy anonymize() zwraca 0 (race condition)")
        void throwsEntityNotFoundException_whenAnonymizeReturnsZero() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findRecordingUrlsByCustomer(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Collections.emptyList());
            when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(0);

            // when / then
            assertThatThrownBy(() -> gdprService.anonymizeCustomer(CUSTOMER_ID))
                    .isInstanceOf(EntityNotFoundException.class);

            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("nie usuwa nagrań gdy klient nie ma kontaktów z nagraniami")
        void doesNotCallDeleteFromS3_whenNoRecordings() {
            // given
            Customer customer = buildCustomer();
            when(customerRepository.findById(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Optional.of(customer));
            when(contactRepository.findRecordingUrlsByCustomer(CUSTOMER_ID, TENANT_ID))
                    .thenReturn(Collections.emptyList());
            when(customerRepository.anonymize(CUSTOMER_ID, TENANT_ID)).thenReturn(1);

            // when
            gdprService.anonymizeCustomer(CUSTOMER_ID);

            // then
            verifyNoInteractions(recordingService);
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
                .phone(List.of("+48501234567"))
                .email(List.of("jan@example.com"))
                .customFields(new HashMap<>())
                .gdprConsent(new HashMap<>())
                .source("MANUAL")
                .deleted(false)
                .createdAt(Instant.now())
                .build();
    }

    private Contact buildContact() {
        UUID contactId = UUID.randomUUID();
        return Contact.builder()
                .contactId(contactId)
                .tenantId(TENANT_ID)
                .customerId(CUSTOMER_ID)
                .channel("PHONE")
                .direction("INBOUND")
                .status("COMPLETED")
                .startedAt(Instant.now())
                .build();
    }

    /**
     * Odczytuje wszystkie wpisy z archiwum ZIP i zwraca je jako mapę nazwa→bajty.
     */
    private Map<String, byte[]> extractZipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
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
