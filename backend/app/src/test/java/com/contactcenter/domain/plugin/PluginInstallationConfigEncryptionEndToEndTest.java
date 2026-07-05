package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.runtime.PluginConfigTestAccessor;
import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import com.contactcenter.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Test end-to-end zapis→odczyt konfiguracji instalacji pluginu (BE-108): weryfikuje cały
 * łańcuch {@code PluginRegistrationService#updateConfig} (serializacja + szyfrowanie) →
 * {@code TenantPluginInstallationRepository} (zapis natywnym SQL, potem odczyt + deszyfrowanie
 * przy mapowaniu wiersza) → {@code PluginConfigImpl} (warstwa udostępniana pluginowi przez
 * {@code PluginContext.config()}).
 *
 * <p>Bez Testcontainers/H2 (projekt nie ma ich skonfigurowanych dla testów jednostkowych,
 * zob. {@code TenantTwilioConfigRepositoryTest}) — {@link EntityManager} jest mockowany, ale
 * {@link EncryptedStringConverter} i {@code TenantPluginInstallationRepository} są realnymi
 * instancjami (nie mockami) — cały łańcuch serializacja→szyfrowanie→zapis SQL→odczyt
 * SQL→deszyfrowanie→{@code PluginConfigImpl} jest wykonywany naprawdę, jedynie "baza" jest
 * symulowana przechwyceniem/odtworzeniem parametru SQL między zapisem i odczytem.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BE-108 – end-to-end: updateConfig() -> repo -> PluginConfig.get() zwraca odszyfrowane wartości")
class PluginInstallationConfigEncryptionEndToEndTest {

    private static final String TEST_SECRET_BASE64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTALLATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PLUGIN_VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private EntityManager entityManager;
    @Mock private Query mockQuery;
    @Mock private PluginVersionRepository pluginVersionRepository;

    private EncryptedStringConverter encryptedStringConverter;
    private TenantPluginInstallationRepository installationRepository;
    private PluginRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        encryptedStringConverter = new EncryptedStringConverter(TEST_SECRET_BASE64);
        installationRepository = new TenantPluginInstallationRepository(encryptedStringConverter);
        ReflectionTestUtils.setField(installationRepository, "em", entityManager);
        service = new PluginRegistrationServiceImpl(installationRepository, pluginVersionRepository);

        TenantContext.setTenantId(TENANT_ID);

        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.setParameter(anyString(), any())).thenReturn(mockQuery);
        lenient().when(mockQuery.executeUpdate()).thenReturn(1);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("zapis przez service.updateConfig(), potem odczyt przez repo.findByIdAndTenantId() "
            + "i przekazanie do PluginConfigImpl, zwraca PRAWIDŁOWE odszyfrowane wartości")
    void writeThenRead_throughPluginConfig_returnsDecryptedValues() {
        // Ownership check w PluginRegistrationServiceImpl#updateConfig woła
        // installationRepository.findByIdAndTenantId(...) — symulujemy istniejącą instalację
        // (installation_config jeszcze nieustawiony) przez SELECT zwracający jeden wiersz.
        when(mockQuery.getResultList()).thenReturn(rowsOf(buildRow(null)));

        // 1) ZAPIS: admin tenanta wysyła config przez PluginRegistrationService#updateConfig
        //    (DTO/kontroler poza zakresem tego testu — to samo wejście co PluginAdminController
        //    przekazuje z UpdateInstallationConfigRequest#config()).
        Map<String, String> configToSave = Map.of(
                "googleApiKey", "AIzaSyTestSecretValue",
                "googleSearchEngineId", "cx-test-123");
        service.updateConfig(TENANT_ID, INSTALLATION_ID, configToSave);

        // Przechwytujemy ZASZYFROWANY parametr SQL faktycznie zapisany — symulacja "bazy danych"
        // (kolumna installation_config) między zapisem i kolejnym odczytem.
        ArgumentCaptor<Object> sqlParamCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockQuery).setParameter(eq("installationConfig"), sqlParamCaptor.capture());
        String encryptedColumnValueInDb = (String) sqlParamCaptor.getValue();

        // Kryterium akceptacji: surowa wartość kolumny w DB NIE zawiera plaintext.
        assertThat(encryptedColumnValueInDb).doesNotContain("AIzaSyTestSecretValue");
        assertThat(encryptedColumnValueInDb).doesNotContain("googleApiKey");
        assertThat(encryptedColumnValueInDb).doesNotContain("cx-test-123");

        // 2) ODCZYT: symulujemy SELECT zwracający tę samą (zaszyfrowaną) wartość kolumny.
        when(mockQuery.getResultList()).thenReturn(rowsOf(buildRow(encryptedColumnValueInDb)));

        Optional<TenantPluginInstallation> reloaded =
                installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID);
        assertThat(reloaded).isPresent();

        // installationConfig na encji odczytanej z repo jest już PLAINTEXT (deszyfrowanie
        // nastąpiło w mapRow/decryptInstallationConfig) — kontrakt wymagany przez PluginConfigImpl,
        // które sam nie wie nic o szyfrowaniu (zob. Javadoc PluginConfigImpl).
        String plaintextInstallationConfig = reloaded.get().getInstallationConfig();
        assertThat(plaintextInstallationConfig).doesNotContain("encrypted");

        // 3) PluginContext.config(): wartości muszą być dostępne dla pluginu, odszyfrowane.
        var pluginConfig = PluginConfigTestAccessor.newPluginConfig(plaintextInstallationConfig);
        assertThat(pluginConfig.get("googleApiKey")).contains("AIzaSyTestSecretValue");
        assertThat(pluginConfig.get("googleSearchEngineId")).contains("cx-test-123");
    }

    /**
     * Kolejność kolumn zgodna z SELECT w {@link TenantPluginInstallationRepository}: id,
     * tenant_id, plugin_version_id, enabled, granted_permissions, health_status,
     * consecutive_failure_count, installation_config, installed_by_user_id, installed_at,
     * updated_at.
     */
    private Object[] buildRow(String installationConfigRaw) {
        return new Object[]{
                INSTALLATION_ID.toString(),
                TENANT_ID.toString(),
                PLUGIN_VERSION_ID.toString(),
                Boolean.TRUE,
                "[]",
                TenantPluginInstallation.HealthStatus.HEALTHY,
                0,
                installationConfigRaw,
                null,
                java.time.Instant.now(),
                java.time.Instant.now()
        };
    }

    /**
     * Wraca {@code List<Object[]>} z jednym wierszem bez przepuszczania {@code row} przez
     * varargs {@code List.of(T...)} — {@code List.of(row)} z {@code Object[] row} zawierającym
     * {@code null} elementy rozwija tablicę jako wieloargumentowe wywołanie i rzuca NPE.
     */
    private List<Object[]> rowsOf(Object[] row) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(row);
        return rows;
    }
}
