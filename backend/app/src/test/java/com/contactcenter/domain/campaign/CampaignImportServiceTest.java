package com.contactcenter.domain.campaign;

import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
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
 * Testy jednostkowe dla {@link CampaignImportService}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Walidację pliku CSV (rozmiar, rozszerzenie)</li>
 *   <li>Walidację formatu telefonu E.164</li>
 *   <li>Inicjowanie joba importu (QUEUED → Redis)</li>
 *   <li>Pobieranie statusu joba z Redis (404 gdy brak)</li>
 *   <li>Obsługę kampanii spoza tenanta (EntityNotFoundException)</li>
 * </ul>
 *
 * <p>Redis i repozytorium są mockowane – brak zależności od infrastruktury.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CampaignImportService – import CSV kontaktów kampanii")
class CampaignImportServiceTest {

    private static final UUID TENANT_ID   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CAMPAIGN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private CampaignRepository campaignRepository;
    @Mock private CampaignContactRepository campaignContactRepository;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    // CampaignImportService używa @InjectMocks – ObjectMapper nie jest mockiem
    private CampaignImportServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        TenantContext.setUserId(UUID.randomUUID());
        TenantContext.setUserRole("SUPERVISOR");

        // StringRedisTemplate.opsForValue() musi zwracać mock ValueOperations
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        // Tworzymy serwis ręcznie – ObjectMapper z modułem JavaTimeModule
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        service = new CampaignImportServiceImpl(
                campaignRepository,
                campaignContactRepository,
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
            MultipartFile file = new MockMultipartFile("file", "contacts.xlsx",
                    "application/vnd.ms-excel", "data".getBytes());
            assertThatThrownBy(() -> service.validateFile(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dozwolone są tylko pliki CSV");
        }

        @Test
        @DisplayName("Plik za duży (> 50 MB) – wyjątek IllegalArgumentException")
        void tooLargeFile_throwsIllegalArgument() {
            // Symulujemy plik 51 MB przez mock
            MultipartFile bigFile = mock(MultipartFile.class);
            when(bigFile.isEmpty()).thenReturn(false);
            when(bigFile.getOriginalFilename()).thenReturn("contacts.csv");
            when(bigFile.getSize()).thenReturn(51L * 1024 * 1024);

            assertThatThrownBy(() -> service.validateFile(bigFile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("za duży");
        }

        @Test
        @DisplayName("Poprawny plik .csv – brak wyjątku")
        void validFile_noException() {
            MultipartFile file = new MockMultipartFile("file", "contacts.csv",
                    "text/csv", "phone,first_name\n+48123456789,Jan".getBytes());
            assertThatCode(() -> service.validateFile(file))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Walidacja E.164
    // =========================================================================

    @Nested
    @DisplayName("isValidE164 – walidacja formatu telefonu E.164")
    class IsValidE164 {

        @ParameterizedTest(name = "Poprawny: {0}")
        @ValueSource(strings = {
                "+48123456789",
                "+1234567890",
                "+441234567890",
                "+12345678901234"  // 14 cyfr (max 15 łącznie z +)
        })
        @DisplayName("Poprawne numery E.164 – zwraca true")
        void validE164_returnsTrue(String phone) {
            assertThat(service.isValidE164(phone)).isTrue();
        }

        @ParameterizedTest(name = "Niepoprawny: {0}")
        @ValueSource(strings = {
                "48123456789",         // brak +
                "+48abc",              // litery
                "+",                   // tylko +
                "0048123456789",       // format stary
                "+0123456789",         // 0 na początku (nie E.164)
                "+123456789012345678", // za długi (> 15 cyfr z +)
                ""                     // pusty
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
    // initiateImport
    // =========================================================================

    @Nested
    @DisplayName("initiateImport – inicjowanie joba importu")
    class InitiateImport {

        @Test
        @DisplayName("Kampania nie istnieje – EntityNotFoundException")
        void campaignNotFound_throwsEntityNotFound() {
            when(campaignRepository.findById(CAMPAIGN_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            MultipartFile file = csvFile("phone\n+48123456789");

            assertThatThrownBy(() -> service.initiateImport(CAMPAIGN_ID, file, true, ",", "\"", null))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(CAMPAIGN_ID.toString());
        }

        @Test
        @DisplayName("Plik z błędnym rozszerzeniem – IllegalArgumentException przed sprawdzeniem kampanii")
        void wrongExtension_throwsBeforeCampaignCheck() {
            MultipartFile badFile = new MockMultipartFile("file", "data.xlsx",
                    "application/vnd.ms-excel", "data".getBytes());

            assertThatThrownBy(() -> service.initiateImport(CAMPAIGN_ID, badFile, true, ",", "\"", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Dozwolone są tylko pliki CSV");

            // Kampania nie powinna być sprawdzana gdy plik jest niepoprawny
            verifyNoInteractions(campaignRepository);
        }

        @Test
        @DisplayName("Poprawne wywołanie – zwraca UUID joba, zapisuje QUEUED w Redis")
        void validImport_returnsJobId_savesQueuedStatus() {
            Campaign campaign = Campaign.builder()
                    .campaignId(CAMPAIGN_ID)
                    .tenantId(TENANT_ID)
                    .name("Test Campaign")
                    .build();

            when(campaignRepository.findById(CAMPAIGN_ID, TENANT_ID))
                    .thenReturn(Optional.of(campaign));

            // Mock ValueOperations.set() – Redis zapis
            doNothing().when(valueOps).set(anyString(), anyString(), any(Duration.class));

            MultipartFile file = csvFile("phone,first_name\n+48123456789,Jan");

            UUID jobId = service.initiateImport(CAMPAIGN_ID, file, true, ",", "\"", null);

            assertThat(jobId).isNotNull();

            // Weryfikuj że status QUEUED został zapisany do Redis
            verify(valueOps, atLeastOnce()).set(
                    argThat(key -> key.startsWith("import:job:")),
                    argThat(json -> json.contains("QUEUED")),
                    eq(Duration.ofSeconds(CampaignImportServiceImpl.JOB_TTL_SECONDS))
            );
        }
    }

    // =========================================================================
    // getJobStatus
    // =========================================================================

    @Nested
    @DisplayName("getJobStatus – pobieranie statusu z Redis")
    class GetJobStatus {

        @Test
        @DisplayName("Job nie istnieje w Redis – zwraca null")
        void jobNotFound_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get("import:job:" + jobId)).thenReturn(null);

            ImportJobStatus result = service.getJobStatus(jobId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Job istnieje w Redis – deserializuje poprawnie")
        void jobExists_returnsDeserializedStatus() throws Exception {
            UUID jobId = UUID.randomUUID();

            // Przygotuj JSON z statusem
            ImportJobStatus status = ImportJobStatus.builder()
                    .jobId(jobId)
                    .campaignId(CAMPAIGN_ID)
                    .status(ImportJobStatus.Status.PROCESSING)
                    .totalRows(1000)
                    .processedRows(500)
                    .importedRows(490)
                    .rejectedRows(10)
                    .build();

            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            String json = objectMapper.writeValueAsString(status);

            when(valueOps.get("import:job:" + jobId)).thenReturn(json);

            ImportJobStatus result = service.getJobStatus(jobId);

            assertThat(result).isNotNull();
            assertThat(result.getJobId()).isEqualTo(jobId);
            assertThat(result.getStatus()).isEqualTo(ImportJobStatus.Status.PROCESSING);
            assertThat(result.getTotalRows()).isEqualTo(1000);
            assertThat(result.getProcessedRows()).isEqualTo(500);
            assertThat(result.getImportedRows()).isEqualTo(490);
            assertThat(result.getRejectedRows()).isEqualTo(10);
        }

        @Test
        @DisplayName("Uszkodzony JSON w Redis – zwraca null (obsługa błędu)")
        void corruptedJson_returnsNull() {
            UUID jobId = UUID.randomUUID();
            when(valueOps.get("import:job:" + jobId)).thenReturn("INVALID_JSON{{{");

            ImportJobStatus result = service.getJobStatus(jobId);

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // ImportJobStatus – model
    // =========================================================================

    @Nested
    @DisplayName("ImportJobStatus – model statusu joba")
    class ImportJobStatusModel {

        @Test
        @DisplayName("addRejectedSample – dodaje max 10 próbek")
        void addRejectedSample_capsAtTen() {
            ImportJobStatus status = new ImportJobStatus();

            for (int i = 1; i <= 15; i++) {
                status.addRejectedSample("row " + i + ": invalid phone");
            }

            assertThat(status.getRejectedSamples()).hasSize(10);
        }

        @Test
        @DisplayName("isFinished – COMPLETED i FAILED_PARTIAL zwracają true")
        void isFinished_completedAndFailedPartial_returnTrue() {
            ImportJobStatus completed = ImportJobStatus.builder()
                    .status(ImportJobStatus.Status.COMPLETED).build();
            ImportJobStatus failedPartial = ImportJobStatus.builder()
                    .status(ImportJobStatus.Status.FAILED_PARTIAL).build();
            ImportJobStatus queued = ImportJobStatus.builder()
                    .status(ImportJobStatus.Status.QUEUED).build();
            ImportJobStatus processing = ImportJobStatus.builder()
                    .status(ImportJobStatus.Status.PROCESSING).build();

            assertThat(completed.isFinished()).isTrue();
            assertThat(failedPartial.isFinished()).isTrue();
            assertThat(queued.isFinished()).isFalse();
            assertThat(processing.isFinished()).isFalse();
        }
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private MultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "contacts.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
