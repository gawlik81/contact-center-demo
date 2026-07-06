package com.contactcenter.domain.customer;

import com.contactcenter.api.customer.dto.CustomerImportStatusResponse;
import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link CustomerImportService} (BE-026).
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Walidację pliku CSV (rozmiar, rozszerzenie)</li>
 *   <li>Walidację formatu telefonu E.164</li>
 *   <li>Inicjowanie joba importu (QUEUED → Redis)</li>
 *   <li>Pobieranie statusu joba z Redis (null gdy brak)</li>
 *   <li>Deduplikację SKIP i OVERWRITE</li>
 *   <li>Parsowanie wielokrotnych wartości phone/email</li>
 *   <li>Izolację tenantId (TenantContext)</li>
 * </ul>
 *
 * <p>Redis, repozytorium i JdbcTemplate są mockowane – brak zależności od infrastruktury.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CustomerImportService – import CSV klientów (BE-026)")
class CustomerImportServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID   = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private CustomerRepository  customerRepository;
    @Mock private JdbcTemplate        jdbcTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private CustomerImportServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("SUPERVISOR");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        service = new CustomerImportServiceImpl(
                customerRepository,
                jdbcTemplate,
                stringRedisTemplate,
                objectMapper
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // Walidacja pliku
    // =========================================================================

    @Nested
    @DisplayName("validateFile – walidacja pliku CSV")
    class ValidateFile {

        @Test
        @DisplayName("Plik null – wyjątek IllegalArgumentException")
        void nullFile_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.validateFile(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Plik CSV jest wymagany");
        }

        @Test
        @DisplayName("Pusty plik – wyjątek IllegalArgumentException")
        void emptyFile_throwsIllegalArgument() {
            MultipartFile emptyFile = new MockMultipartFile("file", "test.csv",
                    "text/csv", new byte[0]);
            assertThatThrownBy(() -> service.validateFile(emptyFile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Plik CSV jest wymagany");
        }

        @Test
        @DisplayName("Plik bez rozszerzenia .csv – wyjątek IllegalArgumentException")
        void wrongExtension_throwsIllegalArgument() {
            MultipartFile file = new MockMultipartFile("file", "customers.xlsx",
                    "application/vnd.ms-excel", "data".getBytes());
            assertThatThrownBy(() -> service.validateFile(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dozwolone są tylko pliki CSV");
        }

        @Test
        @DisplayName("Plik za duży (> 50 MB) – wyjątek IllegalArgumentException")
        void tooLargeFile_throwsIllegalArgument() {
            MultipartFile bigFile = mock(MultipartFile.class);
            when(bigFile.isEmpty()).thenReturn(false);
            when(bigFile.getOriginalFilename()).thenReturn("customers.csv");
            when(bigFile.getSize()).thenReturn(51L * 1024 * 1024);

            assertThatThrownBy(() -> service.validateFile(bigFile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("za duży");
        }

        @Test
        @DisplayName("Poprawny plik .csv – brak wyjątku")
        void validFile_noException() {
            MultipartFile file = csvFile("first_name,phone\nJan,+48123456789");
            assertThatCode(() -> service.validateFile(file)).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Walidacja E.164
    // =========================================================================

    @Nested
    @DisplayName("isValidE164 – walidacja formatu telefonu")
    class IsValidE164 {

        @ParameterizedTest(name = "Poprawny: {0}")
        @ValueSource(strings = {
                "+48123456789",
                "+1234567890",
                "+441234567890",
                "+12345678901234"
        })
        @DisplayName("Poprawne numery E.164 – zwraca true")
        void validE164_returnsTrue(String phone) {
            assertThat(service.isValidE164(phone)).isTrue();
        }

        @ParameterizedTest(name = "Niepoprawny: {0}")
        @ValueSource(strings = {
                "48123456789",
                "+48abc",
                "+",
                "0048123456789",
                "+0123456789",
                "+123456789012345678",
                ""
        })
        @DisplayName("Niepoprawne numery – zwraca false")
        void invalidPhone_returnsFalse(String phone) {
            assertThat(service.isValidE164(phone)).isFalse();
        }

        @Test
        @DisplayName("Null – zwraca false")
        void nullPhone_returnsFalse() {
            assertThat(service.isValidE164(null)).isFalse();
        }
    }

    // =========================================================================
    // initiateImport – inicjowanie joba
    // =========================================================================

    @Nested
    @DisplayName("initiateImport – inicjowanie joba importu")
    class InitiateImport {

        @Test
        @DisplayName("Plik z błędnym rozszerzeniem – IllegalArgumentException przed zapisem do Redis")
        void wrongExtension_throwsBeforeRedis() {
            MultipartFile badFile = new MockMultipartFile("file", "data.xlsx",
                    "application/vnd.ms-excel", "data".getBytes());

            assertThatThrownBy(() -> service.initiateImport(badFile, DeduplicationMode.SKIP, ",", "\"", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dozwolone są tylko pliki CSV");

            verifyNoInteractions(customerRepository);
        }

        @Test
        @DisplayName("Poprawne wywołanie – zwraca UUID joba, zapisuje QUEUED w Redis")
        void validImport_returnsJobId_savesQueuedStatus() {
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

            MultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,+48123456789");

            UUID jobId = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            assertThat(jobId).isNotNull();

            // Weryfikuj że status QUEUED został zapisany do Redis
            verify(valueOps, atLeastOnce()).set(
                    argThat(key -> key.startsWith(CustomerImportServiceImpl.JOB_KEY_PREFIX)),
                    argThat(json -> json.contains("QUEUED")),
                    eq(Duration.ofSeconds(CustomerImportServiceImpl.JOB_TTL_SECONDS))
            );
        }

        @Test
        @DisplayName("Dwa wywołania initiateImport – zwracają różne jobId")
        void twoImports_differentJobIds() {
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

            MultipartFile file = csvFile("phone\n+48123456789");

            // Pierwsze wywołanie
            UUID jobId1 = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            // Przywróć TenantContext (wątek async może go wyczyścić)
            TenantContext.setTenantId(TENANT_ID);
            TenantContext.setUserId(USER_ID);
            TenantContext.setUserRole("SUPERVISOR");

            UUID jobId2 = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            assertThat(jobId1).isNotEqualTo(jobId2);
        }
    }

    // =========================================================================
    // getJobStatus – pobieranie statusu z Redis
    // =========================================================================

    @Nested
    @DisplayName("getJobStatus – pobieranie statusu z Redis")
    class GetJobStatus {

        @Test
        @DisplayName("Job nie istnieje w Redis – zwraca null")
        void jobNotFound_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)).thenReturn(null);

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Job istnieje w Redis – deserializuje poprawnie do DTO")
        void jobExists_returnsDeserializedDto() {
            UUID jobId = UUID.randomUUID();

            String json = """
                    {
                      "jobId":"%s","status":"PROCESSING",
                      "processed":200,"total":1000,
                      "imported":180,"updated":10,"skipped":5,"failed":5,
                      "errorFileAvailable":true
                    }
                    """.formatted(jobId);

            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)).thenReturn(json);

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNotNull();
            assertThat(result.jobId()).isEqualTo(jobId.toString());
            assertThat(result.status()).isEqualTo("PROCESSING");
            assertThat(result.processed()).isEqualTo(200);
            assertThat(result.total()).isEqualTo(1000);
            assertThat(result.imported()).isEqualTo(180);
            assertThat(result.updated()).isEqualTo(10);
            assertThat(result.skipped()).isEqualTo(5);
            assertThat(result.failed()).isEqualTo(5);
            assertThat(result.errorFileAvailable()).isTrue();
        }

        @Test
        @DisplayName("Uszkodzony JSON w Redis – zwraca null (obsługa błędu)")
        void corruptedJson_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)).thenReturn("INVALID{{{");

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Status QUEUED w Redis – mapuje poprawnie na DTO")
        void queuedStatus_mapsToDto() {
            UUID jobId = UUID.randomUUID();

            String json = "{\"jobId\":\"%s\",\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
                    .formatted(jobId);

            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)).thenReturn(json);

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo("QUEUED");
            assertThat(result.processed()).isZero();
            assertThat(result.errorFileAvailable()).isFalse();
        }

        @Test
        @DisplayName("Status COMPLETED w Redis – mapuje poprawnie na DTO")
        void completedStatus_mapsToDto() {
            UUID jobId = UUID.randomUUID();

            String json = "{\"jobId\":\"%s\",\"status\":\"COMPLETED\",\"processed\":50,\"total\":50,\"imported\":45,\"updated\":3,\"skipped\":2,\"failed\":0,\"errorFileAvailable\":false}"
                    .formatted(jobId);

            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)).thenReturn(json);

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.imported()).isEqualTo(45);
            assertThat(result.updated()).isEqualTo(3);
            assertThat(result.skipped()).isEqualTo(2);
            assertThat(result.failed()).isZero();
        }
    }

    // =========================================================================
    // Deduplikacja SKIP – processImportAsync (wywołanie synchroniczne w teście)
    // =========================================================================

    @Nested
    @DisplayName("Deduplikacja – SKIP i OVERWRITE")
    class Deduplication {

        @Test
        @DisplayName("SKIP – klient z takim telefonem już istnieje – batchInsert nie wywoływany")
        void skip_existingPhone_noBatchInsert() {
            // Klient już istnieje pod tym numerem
            Customer existing = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByPhoneNumber("+48123456789", TENANT_ID))
                    .thenReturn(Optional.of(existing));

            MultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,+48123456789");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            // Tryb SKIP: klient istnieje → batchInsert nie powinna być wywołana
            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
        }

        @Test
        @DisplayName("OVERWRITE – klient z takim emailem już istnieje – batchUpdate wywoływany")
        void overwrite_existingEmail_batchUpdateCalled() {
            Customer existing = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail("jan@example.com", TENANT_ID))
                    .thenReturn(Optional.of(existing));

            MultipartFile file = csvFile("first_name,last_name,phone,email\nJan,Kowalski,+48123456789,jan@example.com");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE,
                    ",", "\"", null, snapshot);

            // OVERWRITE: klient istnieje → UPDATE
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("UPDATE customer")),
                    anyList()
            );
        }
    }

    // =========================================================================
    // Parsowanie wielokrotnych wartości phone/email
    // =========================================================================

    @Nested
    @DisplayName("Parsowanie wielokrotnych wartości phone/email rozdzielonych ;")
    class MultiValueParsing {

        @Test
        @DisplayName("Wiersz z 2 telefonami – oba poprawne E.164 – INSERT z oboma numerami")
        void multiplePhones_bothValid_insertWithBoth() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile("first_name,phone\nJan,+48111111111;+48222222222");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            // INSERT powinien być wywołany (oba numery poprawne)
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    anyList()
            );
        }

        @Test
        @DisplayName("Wiersz z telefonem i emailem – oba wielokrotne – INSERT z listami")
        void multiplePhoneAndEmail_INSERT() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile("phone,email\n+48111111111;+48222222222,a@b.com;c@d.com");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    anyList()
            );
        }
    }

    // =========================================================================
    // Kolumna external_id
    // =========================================================================

    @Nested
    @DisplayName("Import CSV z kolumną external_id")
    class ExternalIdColumn {

        @Test
        @DisplayName("Wiersz z kolumną external_id – INSERT zawiera external_id w parametrach SQL")
        void rowWithExternalId_insertIncludesExternalId() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile(
                    "first_name,last_name,phone,email,external_id\n"
                            + "Jan,Kowalski,+48123456789,jan@example.com,CRM-42");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer") && sql.contains("external_id")),
                    paramsCaptor.capture()
            );

            List<Object[]> capturedParams = paramsCaptor.getValue();
            assertThat(capturedParams).hasSize(1);
            assertThat(capturedParams.get(0)).contains("CRM-42");
        }

        @Test
        @DisplayName("Wiersz bez kolumny external_id (stary format CSV) – import nie rzuca błędu")
        void rowWithoutExternalIdColumn_legacyCsv_noError() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            // Stary format CSV bez kolumny external_id – getColumn() musi bezpiecznie zwrócić null
            MultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,+48123456789");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            assertThatCode(() -> service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot)).doesNotThrowAnyException();

            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    anyList()
            );
        }

        @Test
        @DisplayName("Dwa wiersze w tym samym pliku z tym samym external_id – pierwszy zaimportowany, drugi failed")
        void duplicateExternalIdWithinFile_secondRowFailed() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByExternalId("CRM-100", TENANT_ID))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile(
                    "first_name,last_name,phone,email,external_id\n"
                            + "Jan,Kowalski,+48111111111,jan@example.com,CRM-100\n"
                            + "Anna,Nowak,+48222222222,anna@example.com,CRM-100");

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(jobId, file, DeduplicationMode.SKIP, ",", "\"", null, snapshot);

            // Tylko pierwszy wiersz trafia do batcha INSERT
            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture()
            );
            assertThat(paramsCaptor.getValue()).hasSize(1);

            // Liczniki finalne: imported=1, failed=1 (nie 2 zaimportowane)
            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"imported\":1").contains("\"failed\":1");

            // Raport błędów zawiera komunikat o duplikacie w pliku
            ArgumentCaptor<String> errorReportCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId + CustomerImportServiceImpl.ERRORS_KEY_SUFFIX),
                    errorReportCaptor.capture(), any(Duration.class));
            assertThat(errorReportCaptor.getValue()).contains("Duplikat identyfikatora zewnętrznego");
        }

        @Test
        @DisplayName("external_id należący już do innego istniejącego klienta – wiersz failed, nie trafia do batcha")
        void externalIdBelongsToDifferentExistingCustomer_rowFailed_notBatched() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            // findByExternalId zwraca INNEGO klienta niż ten dopasowany przez phone/email (który nie istnieje)
            Customer otherCustomer = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByExternalId("CRM-200", TENANT_ID))
                    .thenReturn(Optional.of(otherCustomer));

            MultipartFile file = csvFile(
                    "first_name,last_name,phone,email,external_id\n"
                            + "Jan,Kowalski,+48333333333,jan2@example.com,CRM-200");

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(jobId, file, DeduplicationMode.SKIP, ",", "\"", null, snapshot);

            // Wiersz nie trafia do żadnego batcha (ani INSERT, ani UPDATE)
            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"failed\":1").contains("\"imported\":0");
        }

        @Test
        @DisplayName("OVERWRITE – external_id w pliku identyczny z externalId TEGO SAMEGO klienta (dopasowanego przez phone) – sukces bez fałszywego konfliktu")
        void overwriteMode_externalIdMatchesSameCustomerFoundByPhone_noFalseConflict() {
            UUID existingCustomerId = UUID.randomUUID();
            Customer existing = Customer.builder()
                    .customerId(existingCustomerId)
                    .tenantId(TENANT_ID)
                    .externalId("CRM-300")
                    .build();

            when(customerRepository.findByPhoneNumber("+48444444444", TENANT_ID))
                    .thenReturn(Optional.of(existing));
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            // findByExternalId znajduje TEGO SAMEGO klienta, dopasowanego już przez phone – nie konflikt
            when(customerRepository.findByExternalId("CRM-300", TENANT_ID))
                    .thenReturn(Optional.of(existing));

            MultipartFile file = csvFile(
                    "first_name,last_name,phone,email,external_id\n"
                            + "Jan,Kowalski,+48444444444,jan@example.com,CRM-300");

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(jobId, file, DeduplicationMode.OVERWRITE, ",", "\"", null, snapshot);

            // UPDATE wykonany – brak fałszywego konfliktu
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("UPDATE customer")),
                    anyList()
            );

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"updated\":1").contains("\"failed\":0");
        }
    }

    // =========================================================================
    // Walidacja telefonu – odrzucenie wierszy bez poprawnego numeru
    // =========================================================================

    @Nested
    @DisplayName("Walidacja telefonu – odrzucenie wierszy bez poprawnego E.164")
    class PhoneValidation {

        @Test
        @DisplayName("Wiersz bez telefonu – nie trafia do batcha INSERT")
        void noPhone_rowRejected_noInsert() {
            MultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,niepoprawny");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            // Brak poprawnego telefonu → brak INSERT
            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
        }

        @Test
        @DisplayName("Wiersz z jednym poprawnym i jednym niepoprawnym telefonem – INSERT z jednym numerem")
        void oneValidOneBadPhone_insertWithOnePhone() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            // +48111111111 OK, "numer_zly" – odrzucony; jest przynajmniej 1 poprawny → INSERT
            MultipartFile file = csvFile("first_name,phone\nJan,+48111111111;numer_zly");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();

            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    anyList()
            );
        }
    }

    // =========================================================================
    // Nowy format columnMapping: wiele kolumn phone/email, named custom_fields, zgody RODO
    // =========================================================================

    @Nested
    @DisplayName("columnMapping – wiele kolumn phone/email, named custom_fields, zgody RODO")
    class ColumnMappingAndConsent {

        @Test
        @DisplayName("Explicit mapping – dwie kolumny zmapowane na phone – oba numery w JSONB phone")
        void explicitMapping_twoPhoneColumns_bothNumbersImported() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String columnMapping = "{\"first_name\":0,\"phone\":[1,2]}";
            MultipartFile file = csvFile("Jan,+48111111111,+48222222222");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", columnMapping, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            // insertParams: [tenant_id, first_name, last_name, phone, email, custom_fields, external_id, gdpr_consent, created_at]
            String phoneJson = (String) paramsCaptor.getValue().get(0)[3];
            assertThat(phoneJson).contains("+48111111111").contains("+48222222222");
        }

        @Test
        @DisplayName("Explicit mapping – dwie kolumny zmapowane na email – oba adresy w JSONB email")
        void explicitMapping_twoEmailColumns_bothAddressesImported() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String columnMapping = "{\"phone\":[0],\"email\":[1,2]}";
            MultipartFile file = csvFile("+48123456789,a@example.com,b@example.com");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", columnMapping, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String emailJson = (String) paramsCaptor.getValue().get(0)[4];
            assertThat(emailJson).contains("a@example.com").contains("b@example.com");
        }

        @Test
        @DisplayName("Explicit mapping – custom_fields jako obiekt {nazwa:indeks} – nazwane pola w customFields")
        void explicitMapping_customFieldsObject_buildsNamedMap() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String columnMapping = "{\"phone\":[0],\"custom_fields\":{\"vip\":1,\"segment\":2}}";
            MultipartFile file = csvFile("+48111111111,true,gold");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", columnMapping, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String customFieldsJson = (String) paramsCaptor.getValue().get(0)[5];
            assertThat(customFieldsJson).contains("\"vip\":\"true\"").contains("\"segment\":\"gold\"");
        }

        @Test
        @DisplayName("Nowy klient z consent_given/marketing_consent – gdpr_consent zawiera boolean + consent_source + consent_date")
        void newCustomer_consentColumnsMapped_gdprConsentPopulated() throws Exception {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            MultipartFile file = csvFile(
                    "first_name,phone,consent_given,marketing_consent\n"
                            + "Jan,+48123456789,tak,nie");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String gdprJson = (String) paramsCaptor.getValue().get(0)[7];

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> gdpr = mapper.readValue(gdprJson, Map.class);

            assertThat(gdpr.get("consent_given")).isEqualTo(true);
            assertThat(gdpr.get("marketing_consent")).isEqualTo(false);
            assertThat(gdpr.get("consent_source")).isEqualTo("CSV_IMPORT");
            assertThat(gdpr.get("consent_date")).isNotNull();
        }

        @Test
        @DisplayName("Regresja: nowy klient ze zmapowaną TYLKO kolumną marketing_consent (bez consent_given) – "
                + "gdpr_consent zawiera domyślne consent_given=false ORAZ prawdziwe marketing_consent")
        void newCustomer_onlyMarketingConsentMapped_consentGivenDefaultsToFalse() throws Exception {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            MultipartFile file = csvFile(
                    "first_name,phone,marketing_consent\n"
                            + "Jan,+48123456789,tak");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String gdprJson = (String) paramsCaptor.getValue().get(0)[7];

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> gdpr = mapper.readValue(gdprJson, Map.class);

            // consent_given musi być obecny mimo że CSV nie zmapował tej kolumny w ogóle
            assertThat(gdpr.get("consent_given")).isEqualTo(false);
            assertThat(gdpr.get("marketing_consent")).isEqualTo(true);
            assertThat(gdpr.get("consent_source")).isEqualTo("CSV_IMPORT");
            assertThat(gdpr.get("consent_date")).isNotNull();
        }

        @Test
        @DisplayName("Nowy klient – brak zmapowanych kolumn zgody – gdpr_consent domyślnie {consent_given: false} (bez zmian)")
        void newCustomer_noConsentColumnsMapped_defaultsToConsentGivenFalse() throws Exception {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            MultipartFile file = csvFile("first_name,phone\nJan,+48123456789");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String gdprJson = (String) paramsCaptor.getValue().get(0)[7];

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> gdpr = mapper.readValue(gdprJson, Map.class);

            assertThat(gdpr).containsExactly(entry("consent_given", false));
        }

        @Test
        @DisplayName("Regresja bezpieczeństwa: OVERWRITE bez zmapowanych kolumn zgód – UPDATE SQL NIE zawiera gdpr_consent")
        void overwrite_noConsentMapping_updateSqlExcludesGdprConsent() {
            Customer existing = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.of(existing));
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile("first_name,last_name,phone\nJan,Kowalski,+48123456789");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), anyList());

            String updateSql = sqlCaptor.getAllValues().stream()
                    .filter(sql -> sql.contains("UPDATE customer"))
                    .findFirst()
                    .orElseThrow();

            assertThat(updateSql).doesNotContain("gdpr_consent");
        }

        @Test
        @DisplayName("OVERWRITE ze zmapowanymi kolumnami zgód – UPDATE SQL zawiera merge JSONB (nie pełne nadpisanie)")
        void overwrite_withConsentMapping_updateSqlMergesGdprConsent() {
            Customer existing = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.of(existing));
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID)))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile(
                    "first_name,last_name,phone,consent_given\n"
                            + "Jan,Kowalski,+48123456789,true");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE,
                    ",", "\"", null, snapshot);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), anyList());

            String updateSql = sqlCaptor.getAllValues().stream()
                    .filter(sql -> sql.contains("UPDATE customer"))
                    .findFirst()
                    .orElseThrow();

            assertThat(updateSql.replaceAll("\\s+", " "))
                    .contains("gdpr_consent = gdpr_consent || CAST(? AS jsonb)");
        }

        @Test
        @DisplayName("Wsteczna zgodność – {\"phone\": 2} (pojedyncza liczba, nie tablica) nadal działa")
        void backwardCompat_singleNumberPhoneMapping_stillWorks() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String columnMapping = "{\"first_name\":0,\"last_name\":1,\"phone\":2}";
            MultipartFile file = csvFile("Jan,Kowalski,+48123456789");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "\"", columnMapping, snapshot);

            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    anyList());
        }

        @Test
        @DisplayName("Wsteczna zgodność – {\"custom_fields\": 5} (pojedyncza kolumna z surowym JSON-em) nadal działa")
        void backwardCompat_singleNumberCustomFieldsMapping_parsesJsonInCell() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String columnMapping = "{\"first_name\":0,\"phone\":1,\"custom_fields\":2}";
            // quoteChar="" (ignoreQuotations) – komórka może zawierać surowy JSON bez CSV-owego cytowania,
            // ponieważ nie zawiera separatora ','.
            MultipartFile file = csvFile("Jan,+48123456789,{\"vip\":\"true\"}");

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP,
                    ",", "", columnMapping, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String customFieldsJson = (String) paramsCaptor.getValue().get(0)[5];
            assertThat(customFieldsJson).contains("\"vip\":\"true\"");
        }
    }

    // =========================================================================
    // Import JSON
    // =========================================================================

    @Nested
    @DisplayName("Import JSON – tablica obiektów camelCase")
    class JsonImport {

        @Test
        @DisplayName("Import podstawowy – tablica 2 obiektów – oba wstawione (INSERT)")
        void basicImport_twoObjects_bothInserted() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [
                      {"firstName":"Jan","lastName":"Kowalski","phone":"+48111111111"},
                      {"firstName":"Anna","lastName":"Nowak","phone":"+48222222222"}
                    ]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            assertThat(paramsCaptor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("phone/email jako tablica JSON – oba numery/adresy trafiają do JSONB")
        void arrayPhoneAndEmail_bothValuesImported() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","phone":["+48111111111","+48222222222"],"email":["a@example.com","b@example.com"]}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            // insertParams: [tenant_id, first_name, last_name, phone, email, custom_fields,
            //                external_id, gdpr_consent, source, created_at]
            Object[] row = paramsCaptor.getValue().get(0);
            assertThat((String) row[3]).contains("+48111111111").contains("+48222222222");
            assertThat((String) row[4]).contains("a@example.com").contains("b@example.com");
        }

        @Test
        @DisplayName("customFields jako obiekt – nazwane pola trafiają do custom_fields")
        void customFieldsObject_buildsNamedMap() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","phone":"+48111111111","customFields":{"vip":"true","segment":"gold"}}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String customFieldsJson = (String) paramsCaptor.getValue().get(0)[5];
            assertThat(customFieldsJson).contains("\"vip\":\"true\"").contains("\"segment\":\"gold\"");
        }

        @Test
        @DisplayName("gdprConsent z wartościami boolean – gdpr_consent zawiera consent_given/marketing_consent "
                + "+ consent_source=JSON_IMPORT")
        void gdprConsentBooleans_populatesConsent() throws Exception {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","phone":"+48111111111","gdprConsent":{"consent_given":true,"marketing_consent":false}}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String gdprJson = (String) paramsCaptor.getValue().get(0)[7];

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> gdpr = mapper.readValue(gdprJson, Map.class);

            assertThat(gdpr.get("consent_given")).isEqualTo(true);
            assertThat(gdpr.get("marketing_consent")).isEqualTo(false);
            assertThat(gdpr.get("consent_source")).isEqualTo("JSON_IMPORT");
            assertThat(gdpr.get("consent_date")).isNotNull();
        }

        @Test
        @DisplayName("gdprConsent z wartościami string (\"tak\"/\"nie\") – parsowane przez parseBoolean")
        void gdprConsentStrings_parsedViaParseBoolean() throws Exception {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","phone":"+48111111111","gdprConsent":{"consent_given":"tak","marketing_consent":"nie"}}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());

            String gdprJson = (String) paramsCaptor.getValue().get(0)[7];

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> gdpr = mapper.readValue(gdprJson, Map.class);

            assertThat(gdpr.get("consent_given")).isEqualTo(true);
            assertThat(gdpr.get("marketing_consent")).isEqualTo(false);
            assertThat(gdpr.get("consent_source")).isEqualTo("JSON_IMPORT");
        }

        @Test
        @DisplayName("Wiersz bez telefonu – odrzucony, nie trafia do batcha")
        void noPhone_rowRejected() {
            String json = """
                    [{"firstName":"Jan","lastName":"Kowalski"}]
                    """;
            MultipartFile file = jsonFile(json);

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(jobId, file, DeduplicationMode.SKIP, snapshot);

            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"failed\":1");
        }

        @Test
        @DisplayName("Duplikat externalId WEWNĄTRZ pliku JSON – pierwszy zaimportowany, drugi odrzucony")
        void duplicateExternalIdWithinFile_secondRowRejected() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByExternalId("CRM-500", TENANT_ID)).thenReturn(Optional.empty());

            String json = """
                    [
                      {"firstName":"Jan","phone":"+48111111111","externalId":"CRM-500"},
                      {"firstName":"Anna","phone":"+48222222222","externalId":"CRM-500"}
                    ]
                    """;
            MultipartFile file = jsonFile(json);

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(jobId, file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<List<Object[]>> paramsCaptor = ArgumentCaptor.forClass(List.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("INSERT INTO customer")),
                    paramsCaptor.capture());
            assertThat(paramsCaptor.getValue()).hasSize(1);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"imported\":1").contains("\"failed\":1");
        }

        @Test
        @DisplayName("externalId koliduje z innym istniejącym klientem w bazie – wiersz odrzucony")
        void externalIdCollidesWithExistingCustomer_rowRejected() {
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            Customer otherCustomer = Customer.builder()
                    .customerId(UUID.randomUUID())
                    .tenantId(TENANT_ID)
                    .build();
            when(customerRepository.findByExternalId("CRM-600", TENANT_ID)).thenReturn(Optional.of(otherCustomer));

            String json = """
                    [{"firstName":"Jan","phone":"+48333333333","externalId":"CRM-600"}]
                    """;
            MultipartFile file = jsonFile(json);

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(jobId, file, DeduplicationMode.SKIP, snapshot);

            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"failed\":1").contains("\"imported\":0");
        }

        @Test
        @DisplayName("SKIP – klient z takim telefonem już istnieje – brak operacji batch")
        void skip_existingPhone_noBatchOperation() {
            Customer existing = Customer.builder().customerId(UUID.randomUUID()).tenantId(TENANT_ID).build();
            when(customerRepository.findByPhoneNumber("+48123456789", TENANT_ID)).thenReturn(Optional.of(existing));

            String json = """
                    [{"firstName":"Jan","phone":"+48123456789"}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.SKIP, snapshot);

            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());
        }

        @Test
        @DisplayName("OVERWRITE – klient z takim telefonem już istnieje – batchUpdate wywoływany")
        void overwrite_existingPhone_batchUpdateCalled() {
            Customer existing = Customer.builder().customerId(UUID.randomUUID()).tenantId(TENANT_ID).build();
            when(customerRepository.findByPhoneNumber("+48123456789", TENANT_ID)).thenReturn(Optional.of(existing));

            String json = """
                    [{"firstName":"Jan","phone":"+48123456789"}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE, snapshot);

            verify(jdbcTemplate, atLeastOnce()).batchUpdate(
                    argThat(sql -> sql.contains("UPDATE customer")),
                    anyList());
        }

        @Test
        @DisplayName("Regresja RODO: import BEZ gdprConsent w żadnym wierszu, OVERWRITE – UPDATE SQL NIE zawiera gdpr_consent")
        void overwrite_noConsentInAnyRow_updateSqlExcludesGdprConsent() {
            Customer existing = Customer.builder().customerId(UUID.randomUUID()).tenantId(TENANT_ID).build();
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.of(existing));
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","lastName":"Kowalski","phone":"+48123456789"}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE, snapshot);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), anyList());

            String updateSql = sqlCaptor.getAllValues().stream()
                    .filter(sql -> sql.contains("UPDATE customer"))
                    .findFirst()
                    .orElseThrow();

            assertThat(updateSql).doesNotContain("gdpr_consent");
        }

        @Test
        @DisplayName("Import Z gdprConsent w co najmniej jednym wierszu – OVERWRITE – UPDATE SQL zawiera merge JSONB")
        void overwrite_withConsentInAtLeastOneRow_updateSqlMergesGdprConsent() {
            Customer existing = Customer.builder().customerId(UUID.randomUUID()).tenantId(TENANT_ID).build();
            when(customerRepository.findByPhoneNumber(anyString(), eq(TENANT_ID))).thenReturn(Optional.of(existing));
            when(customerRepository.findByEmail(anyString(), eq(TENANT_ID))).thenReturn(Optional.empty());

            String json = """
                    [{"firstName":"Jan","lastName":"Kowalski","phone":"+48123456789","gdprConsent":{"consent_given":true}}]
                    """;
            MultipartFile file = jsonFile(json);

            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(UUID.randomUUID(), file, DeduplicationMode.OVERWRITE, snapshot);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate, atLeastOnce()).batchUpdate(sqlCaptor.capture(), anyList());

            String updateSql = sqlCaptor.getAllValues().stream()
                    .filter(sql -> sql.contains("UPDATE customer"))
                    .findFirst()
                    .orElseThrow();

            assertThat(updateSql.replaceAll("\\s+", " "))
                    .contains("gdpr_consent = gdpr_consent || CAST(? AS jsonb)");
        }

        @Test
        @DisplayName("JSON root nie jest tablicą (pojedynczy obiekt) – job kończy się FAILED_PARTIAL z błędem FATAL")
        void rootIsObject_notArray_failsWithFatalError() {
            MultipartFile file = jsonFile("{\"firstName\":\"Jan\"}");

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(jobId, file, DeduplicationMode.SKIP, snapshot);

            verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"status\":\"FAILED_PARTIAL\"");
        }

        @Test
        @DisplayName("JSON root nie jest tablicą (liczba) – job kończy się FAILED_PARTIAL z błędem FATAL")
        void rootIsNumber_notArray_failsWithFatalError() {
            MultipartFile file = jsonFile("42");

            UUID jobId = UUID.randomUUID();
            TenantContext.Snapshot snapshot = TenantContext.snapshot();
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));
            when(valueOps.get(anyString())).thenReturn(
                    "{\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
            );

            service.processJsonImportAsync(jobId, file, DeduplicationMode.SKIP, snapshot);

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId),
                    jsonCaptor.capture(), any(Duration.class));
            String finalJson = jsonCaptor.getAllValues().get(jsonCaptor.getAllValues().size() - 1);
            assertThat(finalJson).contains("\"status\":\"FAILED_PARTIAL\"");
        }

        @Test
        @DisplayName("validateJsonFile – zła ekstensja – IllegalArgumentException")
        void validateJsonFile_wrongExtension_throws() {
            MultipartFile file = new MockMultipartFile("file", "customers.csv", "text/csv", "data".getBytes());
            assertThatThrownBy(() -> service.validateJsonFile(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dozwolone są tylko pliki JSON");
        }

        @Test
        @DisplayName("validateJsonFile – pusty plik – IllegalArgumentException")
        void validateJsonFile_emptyFile_throws() {
            MultipartFile file = new MockMultipartFile("file", "customers.json",
                    "application/json", new byte[0]);
            assertThatThrownBy(() -> service.validateJsonFile(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Plik JSON jest wymagany");
        }

        @Test
        @DisplayName("validateJsonFile – plik za duży (> 50 MB) – IllegalArgumentException")
        void validateJsonFile_tooLarge_throws() {
            MultipartFile bigFile = mock(MultipartFile.class);
            when(bigFile.isEmpty()).thenReturn(false);
            when(bigFile.getOriginalFilename()).thenReturn("customers.json");
            when(bigFile.getSize()).thenReturn(51L * 1024 * 1024);

            assertThatThrownBy(() -> service.validateJsonFile(bigFile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("za duży");
        }
    }

    // =========================================================================
    // getErrorReport
    // =========================================================================

    @Nested
    @DisplayName("getErrorReport – pobieranie raportu błędów z Redis")
    class GetErrorReport {

        @Test
        @DisplayName("Brak raportu błędów w Redis – zwraca null")
        void noErrors_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId
                    + CustomerImportServiceImpl.ERRORS_KEY_SUFFIX)).thenReturn(null);

            byte[] result = service.getErrorReport(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Raport błędów istnieje w Redis – zwraca bajty CSV")
        void errorsExist_returnsCsvBytes() {
            UUID jobId = UUID.randomUUID();
            String csv = "line_number,error_message,raw_data\n1,Błąd,+bad";
            when(valueOps.get(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId
                    + CustomerImportServiceImpl.ERRORS_KEY_SUFFIX)).thenReturn(csv);

            byte[] result = service.getErrorReport(jobId);

            assertThat(result).isNotNull();
            assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo(csv);
        }
    }

    // =========================================================================
    // parseColumnMappingJson
    // =========================================================================

    @Nested
    @DisplayName("parseColumnMappingJson – parsowanie mapowania kolumn")
    class ParseColumnMapping {

        @Test
        @DisplayName("Null – zwraca puste mapowanie")
        void nullJson_returnsEmpty() {
            var result = service.parseColumnMappingJson(null);
            assertThat(result.single()).isEmpty();
            assertThat(result.multi()).isEmpty();
            assertThat(result.customFields()).isEmpty();
            assertThat(result.legacyCustomFieldsColumn()).isNull();
        }

        @Test
        @DisplayName("Pusty string – zwraca puste mapowanie")
        void emptyJson_returnsEmpty() {
            var result = service.parseColumnMappingJson("");
            assertThat(result.single()).isEmpty();
            assertThat(result.multi()).isEmpty();
            assertThat(result.customFields()).isEmpty();
        }

        @Test
        @DisplayName("Poprawny JSON – pola skalarne trafiają do single, phone/email do multi (jednoelementowe)")
        void validJson_parsesIndices() {
            String json = "{\"first_name\":0,\"last_name\":1,\"phone\":2,\"email\":3}";
            var result = service.parseColumnMappingJson(json);

            assertThat(result.single()).containsEntry("first_name", 0)
                                        .containsEntry("last_name", 1);
            assertThat(result.multi()).containsEntry("phone", List.of(2))
                                       .containsEntry("email", List.of(3));
        }

        @Test
        @DisplayName("phone jako tablica indeksów – wiele kolumn w jednej liście")
        void arrayPhone_parsesMultipleIndices() {
            String json = "{\"phone\":[2,5]}";
            var result = service.parseColumnMappingJson(json);

            assertThat(result.multi()).containsEntry("phone", List.of(2, 5));
        }

        @Test
        @DisplayName("custom_fields jako obiekt {nazwa: indeks} – parsowane do customFields")
        void objectCustomFields_parsesNamedIndices() {
            String json = "{\"custom_fields\":{\"vip\":8,\"segment\":9}}";
            var result = service.parseColumnMappingJson(json);

            assertThat(result.customFields()).containsEntry("vip", 8).containsEntry("segment", 9);
            assertThat(result.legacyCustomFieldsColumn()).isNull();
        }

        @Test
        @DisplayName("custom_fields jako pojedyncza liczba – wsteczna zgodność (legacyCustomFieldsColumn)")
        void numberCustomFields_backwardCompatLegacyColumn() {
            String json = "{\"custom_fields\":5}";
            var result = service.parseColumnMappingJson(json);

            assertThat(result.legacyCustomFieldsColumn()).isEqualTo(5);
            assertThat(result.customFields()).isEmpty();
        }

        @Test
        @DisplayName("Niepoprawny JSON – zwraca puste mapowanie (obsługa błędu)")
        void invalidJson_returnsEmpty() {
            var result = service.parseColumnMappingJson("{INVALID}");
            assertThat(result.single()).isEmpty();
            assertThat(result.multi()).isEmpty();
            assertThat(result.customFields()).isEmpty();
            assertThat(result.legacyCustomFieldsColumn()).isNull();
        }
    }

    // =========================================================================
    // Izolacja tenantId
    // =========================================================================

    @Nested
    @DisplayName("Izolacja tenantId – TenantContext")
    class TenantIsolation {

        @Test
        @DisplayName("initiateImport używa tenantId z TenantContext – klucz Redis zawiera jobId")
        void initiateImport_usesTenantIdFromContext_redisKeyContainsJobId() {
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

            MultipartFile file = csvFile("phone\n+48123456789");
            UUID jobId = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            // Klucz Redis powinien być import:customer:{jobId}
            verify(valueOps, atLeastOnce()).set(
                    argThat(key -> key.equals(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId)),
                    anyString(),
                    any(Duration.class)
            );
        }

        @Test
        @DisplayName("Inny tenant – inny jobId w Redis (izolacja kluczy)")
        void differentTenants_differentRedisKeys() {
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

            UUID tenant1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID tenant2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

            TenantContext.setTenantId(tenant1);
            MultipartFile file = csvFile("phone\n+48123456789");
            UUID jobId1 = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            // Przywróć kontekst dla drugiego tenanta
            TenantContext.setTenantId(tenant2);
            TenantContext.setUserId(USER_ID);
            TenantContext.setUserRole("SUPERVISOR");

            UUID jobId2 = service.initiateImport(file, DeduplicationMode.SKIP, ",", "\"", null);

            assertThat(jobId1).isNotEqualTo(jobId2);
            // Każdy job ma własny klucz Redis
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId1),
                    anyString(), any(Duration.class)
            );
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportServiceImpl.JOB_KEY_PREFIX + jobId2),
                    anyString(), any(Duration.class)
            );
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private MultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "customers.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private MultipartFile jsonFile(String content) {
        return new MockMultipartFile("file", "customers.json", "application/json",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
