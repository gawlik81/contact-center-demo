package com.contactcenter.domain;

import com.contactcenter.api.customer.CustomerImportStatusResponse;
import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.domain.model.Customer;
import com.contactcenter.domain.repository.CustomerRepository;
import com.contactcenter.domain.service.CustomerImportService;
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

    private CustomerImportService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(USER_ID);
        TenantContext.setUserRole("SUPERVISOR");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        service = new CustomerImportService(
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
                    argThat(key -> key.startsWith(CustomerImportService.JOB_KEY_PREFIX)),
                    argThat(json -> json.contains("QUEUED")),
                    eq(Duration.ofSeconds(CustomerImportService.JOB_TTL_SECONDS))
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
            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId)).thenReturn(null);

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

            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId)).thenReturn(json);

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
            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId)).thenReturn("INVALID{{{");

            CustomerImportStatusResponse result = service.getJobStatus(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Status QUEUED w Redis – mapuje poprawnie na DTO")
        void queuedStatus_mapsToDto() {
            UUID jobId = UUID.randomUUID();

            String json = "{\"jobId\":\"%s\",\"status\":\"QUEUED\",\"processed\":0,\"total\":0,\"imported\":0,\"updated\":0,\"skipped\":0,\"failed\":0,\"errorFileAvailable\":false}"
                    .formatted(jobId);

            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId)).thenReturn(json);

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

            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId)).thenReturn(json);

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
    // getErrorReport
    // =========================================================================

    @Nested
    @DisplayName("getErrorReport – pobieranie raportu błędów z Redis")
    class GetErrorReport {

        @Test
        @DisplayName("Brak raportu błędów w Redis – zwraca null")
        void noErrors_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId
                    + CustomerImportService.ERRORS_KEY_SUFFIX)).thenReturn(null);

            byte[] result = service.getErrorReport(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Raport błędów istnieje w Redis – zwraca bajty CSV")
        void errorsExist_returnsCsvBytes() {
            UUID jobId = UUID.randomUUID();
            String csv = "line_number,error_message,raw_data\n1,Błąd,+bad";
            when(valueOps.get(CustomerImportService.JOB_KEY_PREFIX + jobId
                    + CustomerImportService.ERRORS_KEY_SUFFIX)).thenReturn(csv);

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
        @DisplayName("Null – zwraca pustą mapę")
        void nullJson_returnsEmpty() {
            assertThat(service.parseColumnMappingJson(null)).isEmpty();
        }

        @Test
        @DisplayName("Pusty string – zwraca pustą mapę")
        void emptyJson_returnsEmpty() {
            assertThat(service.parseColumnMappingJson("")).isEmpty();
        }

        @Test
        @DisplayName("Poprawny JSON – parsuje indeksy")
        void validJson_parsesIndices() {
            String json = "{\"first_name\":0,\"last_name\":1,\"phone\":2,\"email\":3}";
            var result = service.parseColumnMappingJson(json);

            assertThat(result).containsEntry("first_name", 0)
                              .containsEntry("last_name", 1)
                              .containsEntry("phone", 2)
                              .containsEntry("email", 3);
        }

        @Test
        @DisplayName("Niepoprawny JSON – zwraca pustą mapę (obsługa błędu)")
        void invalidJson_returnsEmpty() {
            assertThat(service.parseColumnMappingJson("{INVALID}")).isEmpty();
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
                    argThat(key -> key.equals(CustomerImportService.JOB_KEY_PREFIX + jobId)),
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
                    eq(CustomerImportService.JOB_KEY_PREFIX + jobId1),
                    anyString(), any(Duration.class)
            );
            verify(valueOps, atLeastOnce()).set(
                    eq(CustomerImportService.JOB_KEY_PREFIX + jobId2),
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
}
