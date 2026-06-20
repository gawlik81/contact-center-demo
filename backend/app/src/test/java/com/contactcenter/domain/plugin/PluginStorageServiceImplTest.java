package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.infrastructure.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginStorageServiceImpl} — zapis zwalidowanego JAR-a pluginu do S3/MinIO
 * + globalnego katalogu (BE-099).
 *
 * <p>Brak wzorca testów integracyjnych S3/MinIO w projekcie (port 9000 MinIO jest tylko
 * {@code expose}d w docker-compose, niepublikowany na host w środowisku lokalnym/CI) —
 * {@link com.contactcenter.domain.recording.RecordingServiceTest} stosuje ten sam fallback:
 * mock {@link S3Client} + Mockito mocki repozytoriów JPA, bez Testcontainers. Ten test
 * stosuje identyczny wzorzec, świadomie bez weryfikacji end-to-end z prawdziwym MinIO.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginStorageService – zapis zwalidowanego JAR-a do storage + katalogu")
class PluginStorageServiceImplTest {

    private static final UUID UPLOADED_BY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String VALID_ENTRY_POINT = "com.acme.contactcenter.plugin.AcmeCrmPlugin";
    private static final String BUCKET = "contact-center-recordings";

    @Mock private S3Client s3Client;
    @Mock private PluginRepository pluginRepository;
    @Mock private PluginVersionRepository pluginVersionRepository;

    private S3Properties s3Properties;
    private PluginStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        s3Properties = new S3Properties();
        s3Properties.setBucket(BUCKET);

        service = new PluginStorageServiceImpl(s3Client, s3Properties, pluginRepository, pluginVersionRepository);
    }

    private byte[] buildValidJar() {
        return TestJarBuilder.newJar()
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .withManifest(TestJarBuilder.validManifestFields(VALID_ENTRY_POINT))
                .buildWithAutoChecksum();
    }

    // =========================================================================
    // Happy path – plugin nowy w katalogu
    // =========================================================================

    @Test
    @DisplayName("Zapisuje JAR do S3 i tworzy nowy wiersz Plugin + PluginVersion, gdy pluginKey jest nowy")
    void storesNewPluginAndVersion() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> {
            Plugin p = invocation.getArgument(0);
            p.setId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"));
            return p;
        });
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> {
            PluginVersion v = invocation.getArgument(0);
            v.setId(UUID.fromString("cccccccc-0000-0000-0000-000000000001"));
            return v;
        });
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        PluginVersionDto dto = service.storeValidatedJar(jarBytes, "acme-crm-sync-1.3.0.jar",
                validationResult, UPLOADED_BY);

        assertThat(dto.pluginKey()).isEqualTo("acme-crm-sync");
        assertThat(dto.version()).isEqualTo("1.3.0");
        assertThat(dto.sdkVersion()).isEqualTo("1.x");
        assertThat(dto.status()).isEqualTo("VALIDATED");
        assertThat(dto.uploadedByUserId()).isEqualTo(UPLOADED_BY);
        assertThat(dto.validationErrors()).isEmpty();

        ArgumentCaptor<Plugin> pluginCaptor = ArgumentCaptor.forClass(Plugin.class);
        verify(pluginRepository).save(pluginCaptor.capture());
        assertThat(pluginCaptor.getValue().getPluginKey()).isEqualTo("acme-crm-sync");
        assertThat(pluginCaptor.getValue().getDisplayName()).isEqualTo("Acme CRM Sync");
        assertThat(pluginCaptor.getValue().getVendor()).isEqualTo("Acme Sp. z o.o.");

        ArgumentCaptor<PluginVersion> versionCaptor = ArgumentCaptor.forClass(PluginVersion.class);
        verify(pluginVersionRepository).save(versionCaptor.capture());
        PluginVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getVersion()).isEqualTo("1.3.0");
        assertThat(savedVersion.getJarObjectKey()).isEqualTo("plugins/acme-crm-sync/1.3.0/acme-crm-sync-1.3.0.jar");
        assertThat(savedVersion.getStatus()).isEqualTo(PluginVersion.PluginVersionStatus.VALIDATED);
        assertThat(savedVersion.getManifestJson()).containsEntry("pluginKey", "acme-crm-sync");
    }

    @Test
    @DisplayName("Klucz S3 nie zawiera tenantId — katalog pluginów jest globalny")
    void s3KeyDoesNotContainTenantId() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, UPLOADED_BY);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(requestCaptor.getValue().key()).isEqualTo("plugins/acme-crm-sync/1.3.0/plugin.jar");
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
    }

    // =========================================================================
    // Plugin już istnieje w katalogu — reużycie
    // =========================================================================

    @Test
    @DisplayName("Reużywa istniejący wiersz Plugin (po pluginKey), nie tworzy duplikatu")
    void reusesExistingPluginByKey() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        Plugin existingPlugin = Plugin.builder()
                .id(UUID.fromString("dddddddd-0000-0000-0000-000000000001"))
                .pluginKey("acme-crm-sync")
                .displayName("Acme CRM Sync")
                .vendor("Acme Sp. z o.o.")
                .build();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.of(existingPlugin));
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, UPLOADED_BY);

        verify(pluginRepository, never()).save(any(Plugin.class));
        assertThat(dto.pluginId()).isEqualTo(existingPlugin.getId());
    }

    // =========================================================================
    // Guard: REJECTED nigdy nie trafia do storage
    // =========================================================================

    @Test
    @DisplayName("Rzuca wyjątek i nie zapisuje nic, gdy status walidacji jest REJECTED")
    void rejectsStorageWhenValidationRejected() {
        byte[] jarBytes = buildValidJar();
        ValidationResult rejected = ValidationResult.rejected("checksum mismatch");

        assertThatThrownBy(() -> service.storeValidatedJar(jarBytes, "plugin.jar", rejected, UPLOADED_BY))
                .isInstanceOf(IllegalArgumentException.class);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(pluginRepository, never()).save(any(Plugin.class));
        verify(pluginVersionRepository, never()).save(any(PluginVersion.class));
    }

    // =========================================================================
    // Błąd S3
    // =========================================================================

    @Test
    @DisplayName("Propaguje błąd jako PluginStorageException, gdy upload do S3 się nie powiedzie")
    void propagatesS3Failure() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("connection refused").build());

        assertThatThrownBy(() -> service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, UPLOADED_BY))
                .isInstanceOf(PluginStorageServiceImpl.PluginStorageException.class);

        verify(pluginVersionRepository, never()).save(any(PluginVersion.class));
    }

    // =========================================================================
    // validationErrors przekazywane do PluginVersion
    // =========================================================================

    @Test
    @DisplayName("Lista validationErrors jest pusta dla VALIDATED")
    void emptyValidationErrorsForValidated() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, UPLOADED_BY);

        assertThat(dto.validationErrors()).isEqualTo(List.of());
    }
}
