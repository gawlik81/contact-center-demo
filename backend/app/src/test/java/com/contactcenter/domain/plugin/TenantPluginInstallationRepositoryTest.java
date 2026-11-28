package com.contactcenter.domain.plugin;

import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link TenantPluginInstallationRepository} skupione na szyfrowaniu/deszyfrowaniu
 * {@code installation_config} (BE-108).
 *
 * <p>Projekt nie ma Testcontainers/H2 skonfigurowanych do testów jednostkowych repozytoriów
 * (zob. {@code TenantTwilioConfigRepositoryTest}) — {@link EntityManager} jest mockowany
 * (Mockito + {@code ReflectionTestUtils}), ale {@link EncryptedStringConverter} jest UŻYWANY
 * REALNIE (z testowym kluczem 32 zer Base64, identycznym jak {@code application-test.yml})
 * — pozwala to zweryfikować pełen round-trip szyfrowania/deszyfrowania bez prawdziwej bazy:
 * przechwytujemy realny parametr SQL przekazany do {@code setParameter("installationConfig", ...)}
 * i sprawdzamy, że NIE zawiera plaintext (odpowiednik kryterium akceptacji "surowa kolumna
 * w DB nie zawiera plaintext" — tu weryfikowane na granicy repozytorium/SQL, najbliższym
 * miejscu zapisu do bazy).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantPluginInstallationRepository – szyfrowanie installation_config (BE-108)")
class TenantPluginInstallationRepositoryTest {

    private static final String TEST_SECRET_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTALLATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query mockQuery;

    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private EncryptedStringConverter encryptedStringConverter;
    private TenantPluginInstallationRepository repository;

    @BeforeEach
    void setUp() {
        encryptedStringConverter = new EncryptedStringConverter(TEST_SECRET_BASE64);
        repository = new TenantPluginInstallationRepository(encryptedStringConverter, meters);
        ReflectionTestUtils.setField(repository, "em", entityManager);
        TenantContext.setTenantId(TENANT_ID);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.executeUpdate()).thenReturn(1);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("updateInstallationConfig()")
    class UpdateInstallationConfig {

        @Test
        @DisplayName("parametr SQL nie zawiera plaintext wartości wysłanych przez wołającego")
        void updateInstallationConfig_doesNotLeakPlaintextInSqlParameter() {
            String plaintextSecretValue = "sk-super-secret-google-api-key-12345";
            String plaintextJson = "{\"googleApiKey\":\"" + plaintextSecretValue + "\"}";

            repository.updateInstallationConfig(INSTALLATION_ID, TENANT_ID, plaintextJson);

            ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
            verify(mockQuery).setParameter(eq("installationConfig"), paramCaptor.capture());

            String sqlParam = (String) paramCaptor.getValue();
            assertThat(sqlParam).doesNotContain(plaintextSecretValue);
            assertThat(sqlParam).doesNotContain("googleApiKey");
            assertThat(sqlParam).contains("\"encrypted\":");
        }

        @Test
        @DisplayName("wrapper JSON zawiera ciphertext odszyfrowywalny do oryginalnego plaintextu")
        void updateInstallationConfig_wrapperIsDecryptableBackToOriginalPlaintext() {
            String plaintextJson = "{\"apiKey\":\"abc123\",\"searchEngineId\":\"xyz789\"}";

            repository.updateInstallationConfig(INSTALLATION_ID, TENANT_ID, plaintextJson);

            ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
            verify(mockQuery).setParameter(eq("installationConfig"), paramCaptor.capture());
            String wrapperJson = (String) paramCaptor.getValue();

            // Rozpakowanie wrappera {"encrypted": "<base64>"} ręcznie (bez zależności od
            // prywatnej metody repo) – weryfikuje round-trip end-to-end.
            String ciphertextBase64 = wrapperJson.replaceAll(".*\"encrypted\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            assertThat(Base64.getDecoder().decode(ciphertextBase64)).isNotEmpty();

            String decrypted = encryptedStringConverter.convertToEntityAttribute(ciphertextBase64);
            assertThat(decrypted).isEqualTo(plaintextJson);
        }

        @Test
        @DisplayName("KRYTERIUM REGRESJI (code review): insert() z niepustym installationConfig "
                + "szyfruje go tak samo jak updateInstallationConfig() — insert() nigdy nie zapisuje "
                + "encji 1:1 do kolumny, inaczej kolejny odczyt rzuciłby IllegalStateException "
                + "(brak wrappera {\"encrypted\":...})")
        void insert_withNonNullInstallationConfig_encryptsBeforeSaving() {
            String plaintextSecretValue = "AIzaInsertPathSecret";
            String plaintextJson = "{\"apiKey\":\"" + plaintextSecretValue + "\"}";

            TenantPluginInstallation toInsert = new TenantPluginInstallation();
            toInsert.setTenantId(TENANT_ID);
            toInsert.setPluginVersionId(UUID.randomUUID());
            toInsert.setEnabled(false);
            toInsert.setGrantedPermissions(List.of());
            toInsert.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
            toInsert.setConsecutiveFailureCount(0);
            toInsert.setInstallationConfig(plaintextJson);

            when(mockQuery.getResultList()).thenReturn(rowsOf(buildRow(null)));

            repository.insert(toInsert);

            ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
            verify(mockQuery).setParameter(eq("installationConfig"), paramCaptor.capture());
            String sqlParam = (String) paramCaptor.getValue();

            assertThat(sqlParam).doesNotContain(plaintextSecretValue);
            assertThat(sqlParam).doesNotContain("apiKey");
            assertThat(sqlParam).contains("\"encrypted\":");
        }

        @Test
        @DisplayName("null config zapisuje SQL NULL, nie wrapper")
        void updateInstallationConfig_nullConfig_storesSqlNull() {
            repository.updateInstallationConfig(INSTALLATION_ID, TENANT_ID, null);

            ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
            verify(mockQuery).setParameter(eq("installationConfig"), paramCaptor.capture());
            assertThat(paramCaptor.getValue()).isNull();
        }
    }

    @Nested
    @DisplayName("mapRow() – deszyfrowanie przy odczycie (przez findByIdAndTenantId)")
    class DecryptOnRead {

        @Test
        @DisplayName("odczyt zaszyfrowanego wiersza zwraca odszyfrowany plaintext JSON na encji")
        void findByIdAndTenantId_withEncryptedConfig_returnsDecryptedPlaintextOnEntity() {
            String plaintextJson = "{\"googleApiKey\":\"AIzaSecret\",\"googleSearchEngineId\":\"cx-123\"}";
            String ciphertextBase64 = encryptedStringConverter.convertToDatabaseColumn(plaintextJson);
            String storedWrapperJson = "{\"encrypted\":\"" + ciphertextBase64 + "\"}";

            Object[] row = buildRow(storedWrapperJson);
            when(mockQuery.getResultList()).thenReturn(rowsOf(row));

            Optional<TenantPluginInstallation> result =
                    repository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getInstallationConfig()).isEqualTo(plaintextJson);
        }

        @Test
        @DisplayName("kolumna NULL w DB → installationConfig=null na encji (brak NPE)")
        void findByIdAndTenantId_withNullConfig_returnsNullOnEntity() {
            Object[] row = buildRow(null);
            when(mockQuery.getResultList()).thenReturn(rowsOf(row));

            Optional<TenantPluginInstallation> result =
                    repository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getInstallationConfig()).isNull();
        }
    }

    @Nested
    @DisplayName("findAllEnabledForTenant() – izolacja uszkodzonego wiersza (BE-245)")
    class CorruptRowIsolation {

        @Test
        @DisplayName("jeden wiersz z niedeszyfrowalną konfiguracją jest pomijany, pozostałe instalacje nadal się zwracają")
        void corruptRowDoesNotAbortTheList() {
            String plaintext = "{\"k\":\"v\"}";
            Object[] good = buildRow("{\"encrypted\":\"" + encryptedStringConverter.convertToDatabaseColumn(plaintext) + "\"}");
            Object[] badWrapper = buildRow("{\"unexpected\":\"x\"}");
            badWrapper[0] = UUID.randomUUID().toString();
            Object[] good2 = buildRow("{\"encrypted\":\"" + encryptedStringConverter.convertToDatabaseColumn(plaintext) + "\"}");
            good2[0] = UUID.randomUUID().toString();
            List<Object[]> rows = new ArrayList<>();
            rows.add(badWrapper);
            rows.add(good);
            rows.add(good2);
            when(mockQuery.getResultList()).thenReturn(rows);

            List<TenantPluginInstallation> result = repository.findAllEnabledForTenant(TENANT_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getInstallationConfig()).isEqualTo(plaintext);
            assertThat(meters.get(TenantPluginInstallationRepository.METRIC_SKIPPED).tag("reason", "format")
                    .counter().count()).isEqualTo(1);
        }

        private List<Object[]> rowsList(Object[]... rows) {
            return new ArrayList<>(java.util.Arrays.asList(rows));
        }

        private Object[] rowEncryptedWithOtherKey() {
            EncryptedStringConverter otherKey = new EncryptedStringConverter(
                    Base64.getEncoder().encodeToString("another-32-byte-secret-key-for-test!".getBytes()));
            Object[] row = buildRow("{\"encrypted\":\"" + otherKey.convertToDatabaseColumn("{\"k\":\"v\"}") + "\"}");
            row[0] = UUID.randomUUID().toString();
            return row;
        }

        @Test
        @DisplayName("W3: rotacja/zły klucz - WSZYSTKIE wiersze nieodszyfrowane -> wyjątek (nie pusta lista) + metryka decrypt")
        void allRowsUndecryptableFailsLoudly() {
            List<Object[]> rows = rowsList(rowEncryptedWithOtherKey(), rowEncryptedWithOtherKey(),
                    rowEncryptedWithOtherKey());
            when(mockQuery.getResultList()).thenReturn(rows);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findAllEnabledForTenant(TENANT_ID))
                    .isInstanceOf(InstallationConfigUnreadableException.class)
                    .hasMessageContaining("3 z 3")
                    .hasMessageContaining("app.encryption.secret");
            assertThat(meters.get(TenantPluginInstallationRepository.METRIC_SKIPPED).tag("reason", "decrypt")
                    .counter().count()).isEqualTo(3);
        }

        @Test
        @DisplayName("W3: pojedyncza instalacja, której nie da się odczytać (pominięto wszystkie, >= 1) -> wyjątek")
        void singleRowThatIsSkippedFailsLoudly() {
            List<Object[]> rows = rowsList(rowEncryptedWithOtherKey());
            when(mockQuery.getResultList()).thenReturn(rows);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findAllEnabledForTenant(TENANT_ID))
                    .isInstanceOf(InstallationConfigUnreadableException.class);
        }

        @Test
        @DisplayName("W3: większość wierszy nieodszyfrowana (2 z 3) -> wyjątek; mniejszość (1 z 3) -> pominięcie")
        void majorityFailsMinorityIsSkipped() {
            String plaintext = "{\"k\":\"v\"}";
            Object[] good = buildRow("{\"encrypted\":\"" + encryptedStringConverter.convertToDatabaseColumn(plaintext) + "\"}");
            when(mockQuery.getResultList()).thenReturn(rowsList(
                    rowEncryptedWithOtherKey(), rowEncryptedWithOtherKey(), good));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.findAllEnabledForTenant(TENANT_ID))
                    .isInstanceOf(InstallationConfigUnreadableException.class);

            Object[] good2 = buildRow("{\"encrypted\":\"" + encryptedStringConverter.convertToDatabaseColumn(plaintext) + "\"}");
            good2[0] = UUID.randomUUID().toString();
            when(mockQuery.getResultList()).thenReturn(rowsList(
                    rowEncryptedWithOtherKey(), good, good2));
            assertThat(repository.findAllEnabledForTenant(TENANT_ID)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("W2 (BE-245): warunkowe UPDATE-y health_status (CAS z rozróżnieniem przyczyny)")
    class HealthStatusCas {

        private String lastSql() {
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(entityManager, org.mockito.Mockito.atLeastOnce()).createNativeQuery(sql.capture());
            return sql.getAllValues().get(sql.getAllValues().size() - 1);
        }

        @Test
        @DisplayName("markDegradedByConfigLoad: tylko z HEALTHY, ustawia CONFIG_LOAD_FAILED w tym samym UPDATE, nie rusza licznika")
        void markOnlyFromHealthy() {
            assertThat(repository.markDegradedByConfigLoad(INSTALLATION_ID, TENANT_ID)).isEqualTo(1);

            assertThat(lastSql()).contains("health_status = 'HEALTHY'").contains("'DEGRADED'")
                    .contains("degraded_reason = 'CONFIG_LOAD_FAILED'").doesNotContain("consecutive_failure_count");
        }

        @Test
        @DisplayName("clearDegraded: z DEGRADED, czyści przyczynę; includeCircuit=false warunkuje na CONFIG_LOAD_FAILED (+ fallback legacy)")
        void clearOnlyFromDegradedWithCauseGuard() {
            repository.clearDegraded(INSTALLATION_ID, TENANT_ID, false);

            assertThat(lastSql()).contains("health_status = 'DEGRADED'").contains("degraded_reason           = NULL")
                    .contains(":includeCircuit = TRUE OR")
                    .contains("degraded_reason = 'CONFIG_LOAD_FAILED'")
                    .contains("degraded_reason IS NULL AND consecutive_failure_count < :threshold");
            verify(mockQuery).setParameter("includeCircuit", false);
            verify(mockQuery).setParameter("threshold", TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD);
        }

        @Test
        @DisplayName("clearCircuitDegraded: tylko CIRCUIT_OPEN (+ fallback legacy), czyści przyczynę, nie rusza DISABLED_BY_ADMIN")
        void clearCircuitOnlyCircuitCaused() {
            repository.clearCircuitDegraded(INSTALLATION_ID, TENANT_ID);

            assertThat(lastSql()).contains("degraded_reason = 'CIRCUIT_OPEN'")
                    .contains("degraded_reason IS NULL AND consecutive_failure_count >= :threshold")
                    .contains("degraded_reason           = NULL")
                    .doesNotContain("DISABLED_BY_ADMIN");
        }

        @Test
        @DisplayName("markDegradedByCircuit: jeden UPDATE z para (DEGRADED, CIRCUIT_OPEN) i warunkiem CAS wobec DISABLED_BY_ADMIN")
        void markDegradedByCircuitIsConditional() {
            repository.markDegradedByCircuit(INSTALLATION_ID, TENANT_ID, 5);

            assertThat(lastSql()).contains("health_status              = 'DEGRADED'")
                    .contains("degraded_reason            = 'CIRCUIT_OPEN'")
                    .contains("health_status <> 'DISABLED_BY_ADMIN'");
            verify(mockQuery).setParameter("consecutiveFailureCount", 5);
        }

        @Test
        @DisplayName("insert: DEGRADED bez przyczyny -> IllegalArgumentException (para jak na sciezkach update), bez dotykania bazy")
        void insertDegradedWithoutReasonIsRejected() {
            TenantPluginInstallation installation = new TenantPluginInstallation();
            installation.setTenantId(TENANT_ID);
            installation.setPluginVersionId(UUID.randomUUID());
            installation.setEnabled(true);
            installation.setGrantedPermissions(List.of());
            installation.setHealthStatus("DEGRADED");
            installation.setConsecutiveFailureCount(0);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insert(installation))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(entityManager, org.mockito.Mockito.never()).createNativeQuery(anyString());
        }

        @Test
        @DisplayName("mapRow: degraded_reason odczytany z kolumny 11 (surowa wartość zachowana)")
        void mapsDegradedReasonColumn() {
            Object[] row = buildRow(null);
            row[5] = "DEGRADED";
            row[11] = "CONFIG_LOAD_FAILED";
            when(mockQuery.getResultList()).thenReturn(rowsOf(row));

            TenantPluginInstallation i = repository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID).orElseThrow();

            assertThat(i.getDegradedReason()).isEqualTo(DegradedReason.CONFIG_LOAD_FAILED);
            assertThat(i.effectiveDegradedReason()).isEqualTo(DegradedReason.CONFIG_LOAD_FAILED);
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    /**
     * Konstruuje wiersz zgodny z kolejnością kolumn SELECT w repozytorium: id, tenant_id,
     * plugin_version_id, enabled, granted_permissions, health_status,
     * consecutive_failure_count, installation_config, installed_by_user_id, installed_at,
     * updated_at.
     */
    private static Object[] buildRow(String installationConfigRaw) {
        return new Object[]{
                INSTALLATION_ID.toString(),
                TENANT_ID.toString(),
                UUID.randomUUID().toString(),
                Boolean.TRUE,
                "[]",
                TenantPluginInstallation.HealthStatus.HEALTHY,
                0,
                installationConfigRaw,
                null,
                java.time.Instant.now(),
                java.time.Instant.now(),
                null // degraded_reason
        };
    }

    /**
     * Wraca {@code List<Object[]>} z jednym wierszem bez przepuszczania {@code row} przez
     * varargs {@code List.of(T...)} — {@code List.of(row)} z {@code Object[] row} zawierającym
     * {@code null} elementy rozwija tablicę jako wieloargumentowe wywołanie i rzuca NPE
     * (pułapka znana z innych testów repozytoriów w tym projekcie).
     */
    private static List<Object[]> rowsOf(Object[] row) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }
    @Nested
    @DisplayName("findByIdAndTenantId() - kontekst RLS z jawnego tenantId, nie z TenantContext (pętla cross-tenant, BE-246)")
    class ExplicitTenantContext {

        private static final UUID OTHER_TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");

        @Test
        @DisplayName("set_tenant_context dostaje tenantId z argumentu (nie z TenantContext), więc pętla po tenantach jest bezpieczna")
        void setsRlsContextFromTheArgument() {
            when(mockQuery.getResultList()).thenReturn(List.of());

            Optional<TenantPluginInstallation> result =
                    repository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT); // TenantContext holds TENANT_ID

            assertThat(result).isEmpty();
            verify(mockQuery, org.mockito.Mockito.atLeast(2)).setParameter("tenantId", OTHER_TENANT.toString());
            verify(mockQuery, org.mockito.Mockito.never()).setParameter("tenantId", TENANT_ID.toString());
        }
    }

}
