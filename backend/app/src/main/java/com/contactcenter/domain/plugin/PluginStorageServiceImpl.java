package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.infrastructure.config.S3Properties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implementacja zapisu zwalidowanego JAR-a pluginu do globalnego katalogu (BE-099).
 *
 * <p>Wywoływana wyłącznie z {@code jarBytes}, które już przeszły pełny pipeline
 * {@link PluginValidationService} (status {@code VALIDATED}/{@code PENDING_REVIEW}) —
 * ten serwis nie powtarza walidacji bezpieczeństwa (rozmiar, checksum, ASM scan), tylko
 * ponownie odczytuje manifest z tych samych bajtów (przez {@link PluginManifestValidator},
 * już sprawdzony w BE-098) w celu zbudowania wierszy {@link Plugin}/{@link PluginVersion}.
 *
 * <p>Reużywa istniejące beany {@link S3Client}/{@link S3Properties} skonfigurowane w
 * {@code S3Config} dla bucketu {@code contact-center-recordings} (jeden bucket, różne
 * prefiksy kluczy — wzorzec analogiczny do {@code EmailAttachmentStorageServiceImpl}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PluginStorageServiceImpl implements PluginStorageService {

    private static final String META_INF_MANIFEST_ENTRY = "META-INF/plugin-manifest.json";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final PluginRepository pluginRepository;
    private final PluginVersionRepository pluginVersionRepository;

    @Override
    @Transactional
    public PluginVersionDto storeValidatedJar(
            byte[] jarBytes,
            String originalFilename,
            ValidationResult validationResult,
            UUID uploadedByUserId) {

        if (!isStorable(validationResult)) {
            throw new IllegalArgumentException(
                    "storeValidatedJar wymaga statusu VALIDATED/PENDING_REVIEW, otrzymano: "
                            + validationResult.status());
        }

        PluginManifest manifest = extractManifest(jarBytes);

        Plugin plugin = findOrCreatePlugin(manifest);

        String s3Key = buildS3Key(manifest.pluginKey(), manifest.version(), originalFilename);
        uploadToS3(s3Key, jarBytes);

        PluginVersion.PluginVersionStatus versionStatus = toPluginVersionStatus(validationResult);

        PluginVersion pluginVersion = PluginVersion.builder()
                .plugin(plugin)
                .version(manifest.version())
                .jarObjectKey(s3Key)
                .checksumSha256(manifest.checksumSha256())
                .manifestJson(manifestToMap(manifest))
                .sdkVersion(manifest.sdkVersion())
                .status(versionStatus)
                .validationErrors(validationResult.validationErrors())
                .uploadedByUserId(uploadedByUserId)
                .uploadedAt(Instant.now())
                .build();

        pluginVersion = pluginVersionRepository.save(pluginVersion);

        log.info("[PluginStorage] Wersja pluginu zapisana: pluginKey={}, version={}, status={}, "
                        + "s3Key={}, uploadedBy={}",
                manifest.pluginKey(), manifest.version(), versionStatus, s3Key, uploadedByUserId);

        return toDto(pluginVersion, manifest);
    }

    @Override
    public byte[] downloadJar(String jarObjectKey) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(jarObjectKey)
                    .build();

            try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getRequest)) {
                byte[] jarBytes = response.readAllBytes();
                log.debug("[PluginStorage] Pobrano JAR z S3: key={}, size={}B", jarObjectKey, jarBytes.length);
                return jarBytes;
            }
        } catch (S3Exception e) {
            log.error("[PluginStorage] Błąd pobierania JAR-a z S3: key={}, error={}", jarObjectKey, e.getMessage(), e);
            throw new PluginStorageException("Pobranie JAR-a pluginu z object storage nie powiodło się: " + jarObjectKey, e);
        } catch (IOException e) {
            log.error("[PluginStorage] Błąd odczytu strumienia JAR-a: key={}, error={}", jarObjectKey, e.getMessage(), e);
            throw new PluginStorageException("Odczyt JAR-a pluginu z object storage nie powiódł się: " + jarObjectKey, e);
        }
    }

    // =========================================================================
    // Plugin (katalog) — znajdź lub utwórz
    // =========================================================================

    private Plugin findOrCreatePlugin(PluginManifest manifest) {
        return pluginRepository.findByPluginKey(manifest.pluginKey())
                .orElseGet(() -> {
                    Plugin newPlugin = Plugin.builder()
                            .pluginKey(manifest.pluginKey())
                            .displayName(manifest.displayName())
                            .vendor(manifest.vendor())
                            .vendorContact(manifest.vendorContact())
                            .createdAt(Instant.now())
                            .build();
                    Plugin saved = pluginRepository.save(newPlugin);
                    log.info("[PluginStorage] Nowy plugin w katalogu: pluginKey={}, displayName={}, vendor={}",
                            saved.getPluginKey(), saved.getDisplayName(), saved.getVendor());
                    return saved;
                });
    }

    // =========================================================================
    // Object storage (S3/MinIO)
    // =========================================================================

    /**
     * Klucz S3: {@code plugins/{pluginKey}/{version}/{encodedFilename}}.
     *
     * <p><strong>Bez {@code tenantId}</strong> — katalog pluginów jest globalny (bez
     * {@code tenant_id}/RLS, ADR-13), ten sam JAR jest współdzielony między tenantami.
     */
    private String buildS3Key(String pluginKey, String version, String originalFilename) {
        String encodedFilename = encodeFilename(originalFilename);
        return String.format("plugins/%s/%s/%s", pluginKey, version, encodedFilename);
    }

    private void uploadToS3(String s3Key, byte[] jarBytes) {
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(s3Key)
                    .contentType("application/java-archive")
                    .contentLength((long) jarBytes.length)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(jarBytes));

            log.debug("[PluginStorage] Upload JAR-a do S3 zakończony: key={}, size={}B", s3Key, jarBytes.length);
        } catch (S3Exception e) {
            log.error("[PluginStorage] Błąd uploadu JAR-a do S3: key={}, error={}", s3Key, e.getMessage(), e);
            throw new PluginStorageException("Upload JAR-a pluginu do object storage nie powiódł się: " + s3Key, e);
        }
    }

    private String encodeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "plugin.jar";
        }
        // Usuń ścieżki katalogów (path traversal), tak jak EmailAttachmentController.
        String sanitized = filename.replaceAll("[/\\\\]", "_");
        return URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "_");
    }

    // =========================================================================
    // Manifest — ponowny odczyt z JAR-a (te same bajty już zwalidowane w BE-098)
    // =========================================================================

    /**
     * Ponownie wyciąga i parsuje {@code META-INF/plugin-manifest.json} z bajtów JAR-a.
     *
     * <p>{@link ValidationResult} (kontrakt BE-098) niesie tylko {@code status} i
     * {@code validationErrors} — nie sparsowany manifest. Ponieważ te {@code jarBytes}
     * już przeszły pełny pipeline walidacji (rozmiar, MIME, checksum, schema, ASM scan),
     * ponowne parsowanie manifestu jest tanim, bezpiecznym odczytem JSON-a (reużywa
     * {@link PluginManifestValidator}, już sprawdzony w BE-098), nie duplikacją logiki
     * bezpieczeństwa.
     */
    private PluginManifest extractManifest(byte[] jarBytes) {
        byte[] manifestBytes = readZipEntry(jarBytes, META_INF_MANIFEST_ENTRY);
        if (manifestBytes == null) {
            // Nie powinno się zdarzyć — PluginValidationService już to sprawdził.
            throw new PluginStorageException(
                    "JAR nie zawiera " + META_INF_MANIFEST_ENTRY + " — niespodziewane po pozytywnej walidacji", null);
        }
        PluginManifestValidator.ManifestValidationResult result =
                PluginManifestValidator.validate(manifestBytes);
        if (!result.isValid()) {
            throw new PluginStorageException(
                    "Manifest nieprawidłowy po pozytywnej walidacji — niespodziewany stan: "
                            + result.schemaErrors(), null);
        }
        return result.manifest();
    }

    private byte[] readZipEntry(byte[] zipBytes, String entryName) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return zis.readAllBytes();
                }
            }
            return null;
        } catch (IOException e) {
            throw new PluginStorageException("Nie można odczytać archiwum JAR: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> manifestToMap(PluginManifest manifest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pluginKey", manifest.pluginKey());
        map.put("displayName", manifest.displayName());
        map.put("version", manifest.version());
        map.put("vendor", manifest.vendor());
        map.put("vendorContact", manifest.vendorContact());
        map.put("sdkVersion", manifest.sdkVersion());
        map.put("entryPointClass", manifest.entryPointClass());
        map.put("extensionPoints", manifest.extensionPoints());
        map.put("permissions", manifest.permissions());
        // uiPanels/manualActions konwertowane na surowe Map/List (nie zachowują typu rekordu) —
        // zgodnie z resztą manifestJson (Map<String,Object> JSONB), odczytywane z powrotem
        // przez PluginRegistrationServiceImpl#mapToDto (FE-097) przy budowie TenantPluginInstallationDto.
        // Pola opcjonalne w manifeście (JSON Schema) — null gdy nieobecne w JSON, normalizowane
        // na pustą listę, żeby manifestJson nigdy nie niósł literału JSON null dla tych kluczy.
        map.put("uiPanels", manifest.uiPanels() != null
                ? OBJECT_MAPPER.convertValue(manifest.uiPanels(), List.class) : List.of());
        map.put("manualActions", manifest.manualActions() != null
                ? OBJECT_MAPPER.convertValue(manifest.manualActions(), List.class) : List.of());
        map.put("checksumSha256", manifest.checksumSha256());
        return map;
    }

    // =========================================================================
    // Mapowanie status / DTO
    // =========================================================================

    private boolean isStorable(ValidationResult validationResult) {
        return switch (validationResult.status()) {
            case VALIDATED -> true;
            case REJECTED -> false;
        };
    }

    private PluginVersion.PluginVersionStatus toPluginVersionStatus(ValidationResult validationResult) {
        return switch (validationResult.status()) {
            case VALIDATED -> PluginVersion.PluginVersionStatus.VALIDATED;
            case REJECTED -> throw new IllegalStateException("REJECTED nie powinien dotrzeć do storage");
        };
    }

    private PluginVersionDto toDto(PluginVersion pluginVersion, PluginManifest manifest) {
        Plugin plugin = pluginVersion.getPlugin();
        return new PluginVersionDto(
                pluginVersion.getId(),
                plugin.getId(),
                manifest.pluginKey(),
                plugin.getDisplayName(),
                plugin.getVendor(),
                pluginVersion.getVersion(),
                pluginVersion.getSdkVersion(),
                pluginVersion.getStatus().name(),
                pluginVersion.getValidationErrors() != null ? pluginVersion.getValidationErrors() : List.of(),
                manifest.permissions() != null ? manifest.permissions() : List.of(),
                pluginVersion.getUploadedByUserId(),
                pluginVersion.getUploadedAt()
        );
    }

    /** Wyjątek operacji storage pluginów (S3 lub niespodziewany stan po walidacji). */
    static class PluginStorageException extends RuntimeException {
        PluginStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
