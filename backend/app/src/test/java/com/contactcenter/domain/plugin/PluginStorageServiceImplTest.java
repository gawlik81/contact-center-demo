package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.infrastructure.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private static final UUID TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000099");
    private static final UUID EXISTING_PLUGIN_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID EXISTING_VERSION_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
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

    /**
     * Wariant {@link #buildValidJar()} z dodatkowym wpisem ZIP — ten sam {@code pluginKey}/
     * {@code version} (manifest niezmieniony), ale inna treść wykonywalna, więc checksum
     * obliczony przez {@code buildWithAutoChecksum()} faktycznie się różni. Symuluje "poprawkę
     * buga bez bumpu numeru wersji" — przypadek użycia {@code overwrite=true}.
     */
    private byte[] buildValidJarWithExtraEntry() {
        return TestJarBuilder.newJar()
                .withValidEntryPointClass(VALID_ENTRY_POINT)
                .withManifest(TestJarBuilder.validManifestFields(VALID_ENTRY_POINT))
                .withRawEntry("CHANGELOG.txt", "v2 - bugfix".getBytes(StandardCharsets.UTF_8))
                .buildWithAutoChecksum();
    }

    private Plugin existingPluginFixture() {
        return Plugin.builder()
                .id(EXISTING_PLUGIN_ID)
                .pluginKey("acme-crm-sync")
                .displayName("Acme CRM Sync")
                .vendor("Acme Sp. z o.o.")
                .build();
    }

    private PluginVersion existingVersionFixture(Plugin plugin, String s3Key) {
        return PluginVersion.builder()
                .id(EXISTING_VERSION_ID)
                .plugin(plugin)
                .tenantId(TENANT_ID)
                .version("1.3.0")
                .jarObjectKey(s3Key)
                .checksumSha256("0".repeat(64))
                .manifestJson(Map.of("pluginKey", "acme-crm-sync", "permissions", List.of()))
                .sdkVersion("1.x")
                .status(PluginVersion.PluginVersionStatus.VALIDATED)
                .validationErrors(List.of())
                .uploadedByUserId(UPLOADED_BY)
                .uploadedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
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
                validationResult, TENANT_ID, UPLOADED_BY);

        assertThat(dto.pluginKey()).isEqualTo("acme-crm-sync");
        assertThat(dto.displayName()).isEqualTo("Acme CRM Sync");
        assertThat(dto.vendor()).isEqualTo("Acme Sp. z o.o.");
        assertThat(dto.version()).isEqualTo("1.3.0");
        assertThat(dto.sdkVersion()).isEqualTo("1.x");
        assertThat(dto.status()).isEqualTo("VALIDATED");
        assertThat(dto.uploadedByUserId()).isEqualTo(UPLOADED_BY);
        assertThat(dto.validationErrors()).isEmpty();
        assertThat(dto.permissions())
                .containsExactly("customer:read", "contact:read", "http:egress:api.acme-crm.example");

        ArgumentCaptor<Plugin> pluginCaptor = ArgumentCaptor.forClass(Plugin.class);
        verify(pluginRepository).save(pluginCaptor.capture());
        assertThat(pluginCaptor.getValue().getPluginKey()).isEqualTo("acme-crm-sync");
        assertThat(pluginCaptor.getValue().getDisplayName()).isEqualTo("Acme CRM Sync");
        assertThat(pluginCaptor.getValue().getVendor()).isEqualTo("Acme Sp. z o.o.");

        ArgumentCaptor<PluginVersion> versionCaptor = ArgumentCaptor.forClass(PluginVersion.class);
        verify(pluginVersionRepository).save(versionCaptor.capture());
        PluginVersion savedVersion = versionCaptor.getValue();
        assertThat(savedVersion.getVersion()).isEqualTo("1.3.0");
        // Klucz S3 zawiera tenantId (EPIC-28, V078 — izolacja per-tenant w object storage)
        assertThat(savedVersion.getJarObjectKey())
                .isEqualTo("plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/acme-crm-sync-1.3.0.jar");
        assertThat(savedVersion.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(savedVersion.getStatus()).isEqualTo(PluginVersion.PluginVersionStatus.VALIDATED);
        assertThat(savedVersion.getManifestJson()).containsEntry("pluginKey", "acme-crm-sync");
    }

    @Test
    @DisplayName("FE-097: manifestJson zapisany w PluginVersion zawiera uiPanels/manualActions "
            + "(normalizowane do pustej listy, gdy manifest nie deklaruje żadnych)")
    void manifestJsonContainsEmptyUiPanelsAndManualActionsWhenAbsentFromManifest() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY);

        ArgumentCaptor<PluginVersion> versionCaptor = ArgumentCaptor.forClass(PluginVersion.class);
        verify(pluginVersionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getManifestJson())
                .containsEntry("uiPanels", List.of())
                .containsEntry("manualActions", List.of());
    }

    @Test
    @DisplayName("Klucz S3 zawiera tenantId — izolacja per-tenant w object storage (EPIC-28, V078)")
    void s3KeyContainsTenantId() {
        byte[] jarBytes = buildValidJar();
        ValidationResult validationResult = ValidationResult.validated();

        when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
        when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(requestCaptor.getValue().key())
                .isEqualTo("plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/plugin.jar");
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

        PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY);

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

        assertThatThrownBy(() -> service.storeValidatedJar(jarBytes, "plugin.jar", rejected, TENANT_ID, UPLOADED_BY))
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

        assertThatThrownBy(() -> service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY))
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

        PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY);

        assertThat(dto.validationErrors()).isEqualTo(List.of());
    }

    // =========================================================================
    // downloadJar (BE-101 — konsumowane przez PluginRuntimeManager)
    // =========================================================================

    @Test
    @DisplayName("downloadJar: pobiera bajty JAR-a z S3 dla podanego jarObjectKey")
    void downloadJarReturnsBytesFromS3() {
        byte[] expectedBytes = buildValidJar();
        String jarObjectKey = "plugins/acme-crm-sync/1.3.0/plugin.jar";

        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(expectedBytes)));

        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        byte[] actualBytes = service.downloadJar(jarObjectKey);

        assertThat(actualBytes).isEqualTo(expectedBytes);

        ArgumentCaptor<GetObjectRequest> requestCaptor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(requestCaptor.getValue().key()).isEqualTo(jarObjectKey);
    }

    @Test
    @DisplayName("downloadJar: błąd S3 propaguje się jako PluginStorageException")
    void downloadJarPropagatesS3Failure() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("object not found").build());

        assertThatThrownBy(() -> service.downloadJar("plugins/missing/1.0.0/plugin.jar"))
                .isInstanceOf(PluginStorageServiceImpl.PluginStorageException.class);
    }

    // =========================================================================
    // Overwrite istniejącej wersji (fix/plugin-version-overwrite, EPIC-28)
    // =========================================================================

    @Nested
    @DisplayName("storeValidatedJar(..., overwrite) — zastąpienie istniejącej wersji")
    class Overwrite {

        @Test
        @DisplayName("overwrite=false + wersja już wgrana dla tenanta: ConflictException, zero zmian w S3/DB, "
                + "stary wiersz nietknięty")
        void rejectsDuplicateVersionWhenOverwriteFalse() {
            byte[] jarBytes = buildValidJar();
            ValidationResult validationResult = ValidationResult.validated();
            Plugin existingPlugin = existingPluginFixture();
            String originalS3Key = "plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/plugin-v1.jar";
            PluginVersion existingVersion = existingVersionFixture(existingPlugin, originalS3Key);

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.of(existingPlugin));
            when(pluginVersionRepository.findByPluginIdAndVersionAndTenantId(EXISTING_PLUGIN_ID, "1.3.0", TENANT_ID))
                    .thenReturn(Optional.of(existingVersion));

            assertThatThrownBy(() -> service.storeValidatedJar(jarBytes, "plugin-v2.jar", validationResult,
                    TENANT_ID, UPLOADED_BY, false))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("1.3.0")
                    .hasMessageContaining("acme-crm-sync")
                    .hasMessageContaining("overwrite=true");

            verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
            verify(pluginVersionRepository, never()).save(any(PluginVersion.class));
            // Stary wiersz pozostaje dokładnie taki, jak przed wywołaniem (ten sam obiekt — brak mutacji).
            assertThat(existingVersion.getJarObjectKey()).isEqualTo(originalS3Key);
            assertThat(existingVersion.getChecksumSha256()).isEqualTo("0".repeat(64));
        }

        @Test
        @DisplayName("overwrite=true, ta sama nazwa pliku: UPDATE w miejscu (ten sam id), S3 nadpisany pod tym "
                + "samym kluczem, brak deleteObject")
        void overwritesExistingVersionInPlaceWithSameFilename() {
            byte[] newJarBytes = buildValidJarWithExtraEntry();
            ValidationResult validationResult = ValidationResult.validated();
            Plugin existingPlugin = existingPluginFixture();
            String s3Key = "plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/plugin.jar";
            PluginVersion existingVersion = existingVersionFixture(existingPlugin, s3Key);
            Instant originalUploadedAt = existingVersion.getUploadedAt();
            String originalChecksum = existingVersion.getChecksumSha256();

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.of(existingPlugin));
            when(pluginVersionRepository.findByPluginIdAndVersionAndTenantId(EXISTING_PLUGIN_ID, "1.3.0", TENANT_ID))
                    .thenReturn(Optional.of(existingVersion));
            when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            PluginVersionDto dto = service.storeValidatedJar(newJarBytes, "plugin.jar", validationResult,
                    TENANT_ID, UPLOADED_BY, true);

            assertThat(dto.id()).isEqualTo(EXISTING_VERSION_ID);

            ArgumentCaptor<PluginVersion> captor = ArgumentCaptor.forClass(PluginVersion.class);
            verify(pluginVersionRepository).save(captor.capture());
            PluginVersion saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(EXISTING_VERSION_ID);
            assertThat(saved.getJarObjectKey()).isEqualTo(s3Key);
            assertThat(saved.getChecksumSha256()).isNotEqualTo(originalChecksum);
            assertThat(saved.getUploadedAt()).isAfter(originalUploadedAt);

            // tenant_plugin_installation.plugin_version_id (FK ON DELETE RESTRICT, V075) — ten sam
            // id przed/po oznacza, że instalacje tenantów wskazujące na tę wersję wciąż są poprawne.
            verify(pluginVersionRepository, never()).deleteById(any());
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("overwrite=true, inna nazwa pliku: nowy obiekt S3 pod nowym kluczem, stary klucz usunięty")
        void overwriteWithDifferentFilenameDeletesOldS3Object() {
            byte[] newJarBytes = buildValidJarWithExtraEntry();
            ValidationResult validationResult = ValidationResult.validated();
            Plugin existingPlugin = existingPluginFixture();
            String oldS3Key = "plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/plugin-v1.jar";
            String newS3Key = "plugins/" + TENANT_ID + "/acme-crm-sync/1.3.0/plugin-v2.jar";
            PluginVersion existingVersion = existingVersionFixture(existingPlugin, oldS3Key);

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.of(existingPlugin));
            when(pluginVersionRepository.findByPluginIdAndVersionAndTenantId(EXISTING_PLUGIN_ID, "1.3.0", TENANT_ID))
                    .thenReturn(Optional.of(existingVersion));
            when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());
            when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

            service.storeValidatedJar(newJarBytes, "plugin-v2.jar", validationResult, TENANT_ID, UPLOADED_BY, true);

            ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
            assertThat(putCaptor.getValue().key()).isEqualTo(newS3Key);

            ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3Client).deleteObject(deleteCaptor.capture());
            assertThat(deleteCaptor.getValue().key()).isEqualTo(oldS3Key);
            assertThat(deleteCaptor.getValue().bucket()).isEqualTo(BUCKET);
        }

        @Test
        @DisplayName("overwrite=true, ale wersja NIE istnieje: zachowanie jak normalny nowy upload "
                + "(INSERT, brak wyjątku, brak deleteObject)")
        void overwriteTrueForNonExistingVersionBehavesAsNormalUpload() {
            byte[] jarBytes = buildValidJar();
            ValidationResult validationResult = ValidationResult.validated();

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
            when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> {
                Plugin p = invocation.getArgument(0);
                p.setId(EXISTING_PLUGIN_ID);
                return p;
            });
            when(pluginVersionRepository.findByPluginIdAndVersionAndTenantId(EXISTING_PLUGIN_ID, "1.3.0", TENANT_ID))
                    .thenReturn(Optional.empty());
            when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult,
                    TENANT_ID, UPLOADED_BY, true);

            assertThat(dto.version()).isEqualTo("1.3.0");
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("Sprawdzenie istnienia wersji jest zawężone do (pluginId, version, tenantId) bieżącego "
                + "tenanta — izolacja multi-tenant (CLAUDE.md)")
        void existenceCheckIsScopedToPluginVersionAndTenant() {
            byte[] jarBytes = buildValidJar();
            ValidationResult validationResult = ValidationResult.validated();
            Plugin existingPlugin = existingPluginFixture();

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.of(existingPlugin));
            when(pluginVersionRepository.findByPluginIdAndVersionAndTenantId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            service.storeValidatedJar(jarBytes, "plugin.jar", validationResult, TENANT_ID, UPLOADED_BY, false);

            verify(pluginVersionRepository).findByPluginIdAndVersionAndTenantId(EXISTING_PLUGIN_ID, "1.3.0", TENANT_ID);
        }

        @Test
        @DisplayName("Regresja: upload NOWEJ wersji (dziś działające) bez zmian — reużywa przeciążenie "
                + "5-argumentowe (overwrite domyślnie false)")
        void legacyFiveArgOverloadStillWorksForBrandNewVersion() {
            byte[] jarBytes = buildValidJar();
            ValidationResult validationResult = ValidationResult.validated();

            when(pluginRepository.findByPluginKey("acme-crm-sync")).thenReturn(Optional.empty());
            when(pluginRepository.save(any(Plugin.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(pluginVersionRepository.save(any(PluginVersion.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            // Wywołanie przez stare, 5-argumentowe przeciążenie interfejsu (bez overwrite) —
            // musi wciąż działać identycznie jak przed tym fixem.
            PluginVersionDto dto = service.storeValidatedJar(jarBytes, "plugin.jar", validationResult,
                    TENANT_ID, UPLOADED_BY);

            assertThat(dto.version()).isEqualTo("1.3.0");
            assertThat(dto.status()).isEqualTo("VALIDATED");
            verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        }
    }
}
