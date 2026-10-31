package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.infrastructure.config.S3Properties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
import java.util.Optional;
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
 *
 * <p><strong>Overwrite istniejącej wersji (fix/plugin-version-overwrite, EPIC-28):</strong>
 * domyślnie upload tej samej wersji tego samego pluginu dla tego samego tenanta jest odrzucany
 * ({@link ConflictException}, HTTP 409). Wołający może przekazać {@code overwrite=true}, żeby
 * zastąpić treść istniejącej wersji nowym JAR-em w miejscu (bez zmiany {@code id}) — zobacz
 * {@link #storeValidatedJar(byte[], String, ValidationResult, UUID, UUID, boolean)}.
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
            UUID tenantId,
            UUID uploadedByUserId,
            boolean overwrite) {

        if (!isStorable(validationResult)) {
            throw new IllegalArgumentException(
                    "storeValidatedJar wymaga statusu VALIDATED/PENDING_REVIEW, otrzymano: "
                            + validationResult.status());
        }

        PluginManifest manifest = extractManifest(jarBytes);

        Plugin plugin = findOrCreatePlugin(manifest);

        Optional<PluginVersion> existingVersion = pluginVersionRepository
                .findByPluginIdAndVersionAndTenantId(plugin.getId(), manifest.version(), tenantId);

        if (existingVersion.isPresent() && !overwrite) {
            log.warn("[PluginStorage] Odrzucono upload — wersja już istnieje i overwrite=false: "
                            + "pluginKey={}, version={}, tenant={}, uploadedBy={}",
                    manifest.pluginKey(), manifest.version(), tenantId, uploadedByUserId);
            throw new ConflictException(
                    "Wersja " + manifest.version() + " pluginu " + manifest.pluginKey()
                            + " jest już wgrana dla tego tenanta. Użyj overwrite=true, aby ją zastąpić, "
                            + "albo zwiększ numer wersji.");
        }

        // Upload nowej treści do S3 ZAWSZE przed modyfikacją DB (zarówno insert, jak i update
        // w miejscu) — jeśli DB zawiedzie po udanym uploadzie, skutkiem jest w najgorszym razie
        // osierocony nowy obiekt S3 (nieszkodliwy — nic go nie referencuje), nigdy odwrotnie
        // (wiersz DB wskazujący na nieistniejący/niekompletny obiekt).
        //
        // BLOCKER (code review, fix/plugin-version-overwrite): na ścieżce overwrite klucz S3
        // MUSI być zawsze nowy/unikalny — NIGDY ten sam co istniejący wiersz, nawet gdy
        // originalFilename się nie zmienił. buildS3Key(...) jest deterministyczny: przy tej
        // samej nazwie pliku PUT pod tym samym kluczem nadpisałby bajty W MIEJSCU, PRZED
        // commitem transakcji DB — gdyby ten commit later zawiódł (deadlock, constraint,
        // cokolwiek), ROLLBACK zwróciłby wiersz do starego checksumu/statusu, ale fizyczna
        // treść pod tym kluczem S3 byłaby już nieodwracalnie nowa (S3 nie jest transakcyjne,
        // bucket bez versioning, downloadJar nie rewaliduje checksumu przy odczycie).
        // buildOverwriteS3Key(...) rozwiązuje to, dopisując unikalny segment — PUT idzie więc
        // ZAWSZE pod świeży klucz, stary klucz (i jego treść) przetrwa nietknięty aż do udanego
        // commitu, kiedy {@link #scheduleOldS3ObjectCleanup} go usuwa.
        String s3Key = existingVersion.isPresent()
                ? buildOverwriteS3Key(tenantId, manifest.pluginKey(), manifest.version(), originalFilename)
                : buildS3Key(tenantId, manifest.pluginKey(), manifest.version(), originalFilename);
        uploadToS3(s3Key, jarBytes);

        PluginVersion.PluginVersionStatus versionStatus = toPluginVersionStatus(validationResult);

        PluginVersion pluginVersion;
        if (existingVersion.isPresent()) {
            pluginVersion = applyOverwrite(existingVersion.get(), s3Key, manifest, validationResult,
                    versionStatus, uploadedByUserId);
        } else {
            pluginVersion = PluginVersion.builder()
                    .plugin(plugin)
                    .tenantId(tenantId)
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
                            + "s3Key={}, tenant={}, uploadedBy={}",
                    manifest.pluginKey(), manifest.version(), versionStatus, s3Key, tenantId, uploadedByUserId);
        }

        return PluginVersionDto.from(pluginVersion);
    }

    /**
     * Aktualizuje istniejący wiersz {@link PluginVersion} W MIEJSCU (zachowuje {@code id}) —
     * ścieżka {@code overwrite=true} (fix/plugin-version-overwrite).
     *
     * <p>Zachowanie {@code id} jest wymagane przez FK {@code tenant_plugin_installation
     * .plugin_version_id REFERENCES plugin_version(id) ON DELETE RESTRICT} (V075): delete+insert
     * zamiast update zawiodłoby, gdyby jakakolwiek instalacja tenanta wskazywała już na tę wersję,
     * i w ogóle złamałoby intencję "zastąp treść, instalacje nadal wskazują na tę samą wersję".
     *
     * <p>{@code newS3Key} jest zawsze (patrz {@link #buildOverwriteS3Key}) RÓŻNY od
     * {@code previousS3Key} — stary obiekt S3 jest więc BEZWARUNKOWO planowany do usunięcia PO
     * commicie tej transakcji (patrz {@link #scheduleOldS3ObjectCleanup}) — nigdy przed, żeby nie
     * usunąć danych, do których wiersz nadal by wskazywał w razie rollbacku. Brak tu już
     * {@code if (!previousS3Key.equals(newS3Key))} — przy deterministycznym kluczu (przed
     * BLOCKER fixem) bywało to {@code false} dla tej samej nazwy pliku; teraz zawsze różne.
     *
     * <p><strong>Audit log (major finding, code review):</strong> {@code domain.plugin} nie
     * używa dziś {@code AuditAspect}/{@code AuditLogService} (zero innych klas w tym pakiecie je
     * woła) — overwrite jest pierwszą operacją w tym pakiecie, która nadpisuje treść wiersza W
     * MIEJSCU, bez możliwości odtworzenia "co działało wcześniej pod tym {@code
     * plugin_version.id}" inaczej niż przez logi. Stąd jawny wpis PRZED→PO (checksum/s3Key/status)
     * poniżej — trwały (pliki logów), minimalny substytut pełnego {@code @Audited}.
     */
    private PluginVersion applyOverwrite(
            PluginVersion existing,
            String newS3Key,
            PluginManifest manifest,
            ValidationResult validationResult,
            PluginVersion.PluginVersionStatus versionStatus,
            UUID uploadedByUserId) {

        String previousS3Key = existing.getJarObjectKey();
        String previousChecksum = existing.getChecksumSha256();
        PluginVersion.PluginVersionStatus previousStatus = existing.getStatus();

        existing.setJarObjectKey(newS3Key);
        existing.setChecksumSha256(manifest.checksumSha256());
        existing.setManifestJson(manifestToMap(manifest));
        existing.setSdkVersion(manifest.sdkVersion());
        existing.setStatus(versionStatus);
        existing.setValidationErrors(validationResult.validationErrors());
        existing.setUploadedByUserId(uploadedByUserId);
        existing.setUploadedAt(Instant.now());

        PluginVersion saved = pluginVersionRepository.save(existing);

        log.info("[PluginStorage][AUDIT] Wersja pluginu ZASTĄPIONA (overwrite): id={}, tenant={}, "
                        + "uploadedBy={}, checksum: {} -> {}, s3Key: {} -> {}, status: {} -> {}",
                saved.getId(), saved.getTenantId(), uploadedByUserId,
                previousChecksum, saved.getChecksumSha256(), previousS3Key, newS3Key,
                previousStatus, versionStatus);

        scheduleOldS3ObjectCleanup(previousS3Key);

        return saved;
    }

    /**
     * Usuwa stary obiekt S3 osierocony przez {@code overwrite} (zawsze — klucz nowego uploadu
     * jest teraz zawsze unikalny, patrz {@link #buildOverwriteS3Key}, więc stary klucz jest
     * zawsze różny od nowego) — PO commicie bieżącej transakcji, nie przed.
     *
     * <p>S3 nie jest transakcyjne — gdyby usuwanie nastąpiło przed commitem, a transakcja DB
     * zostałaby wycofana z jakiegokolwiek powodu, wiersz wróciłby do wskazywania na stary klucz,
     * który już by nie istniał (utrata danych gorsza niż osierocony obiekt). Rejestrujemy więc
     * {@link TransactionSynchronization#afterCommit()} — usunięcie następuje wyłącznie po udanym
     * commicie; w razie rollbacku {@code afterCommit()} nigdy nie jest wywoływane przez Springa,
     * więc stary obiekt przetrwa nietknięty.
     *
     * <p>Jeśli usunięcie po commicie się nie powiedzie, błąd jest logowany na ERROR, ale NIE
     * propagowany (transakcja już zacommitowana — rzucenie wyjątku nic by nie wycofało, Spring
     * i tak by go połknął w {@code afterCompletion}). Skutek porażki to trwale osierocony obiekt
     * S3 (koszt storage, zero wpływu funkcjonalnego/bezpieczeństwa — nic go nie referencuje) —
     * w projekcie nie ma dziś zadania sweep dla osieroconych obiektów katalogu pluginów
     * (odpowiednik {@code OrphanAttachmentSweepJob} istnieje tylko dla załączników e-mail,
     * BE-127); warte rozważenia jako follow-up, jeśli to się okaże częstym problemem w praktyce.
     */
    private void scheduleOldS3ObjectCleanup(String staleS3Key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Brak aktywnej transakcji (nie powinno się zdarzyć w produkcji — metoda jest
            // @Transactional — ale dotyczy np. testów jednostkowych wywołujących tę klasę
            // bezpośrednio, bez proxy AOP Springa): usuń natychmiast, nie ma commitu do czekania.
            deleteStaleS3Object(staleS3Key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStaleS3Object(staleS3Key);
            }
        });
    }

    private void deleteStaleS3Object(String staleS3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(staleS3Key)
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("[PluginStorage] Stary obiekt S3 (zastąpiony przez overwrite) usunięty: key={}", staleS3Key);
        } catch (S3Exception e) {
            log.error("[PluginStorage] Nie udało się usunąć osieroconego obiektu S3 po overwrite "
                            + "(wiersz DB jest poprawny, tylko storage ma dodatkowy, nieużywany obiekt): "
                            + "key={}, error={}",
                    staleS3Key, e.getMessage(), e);
        }
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
     * Klucz S3: {@code plugins/{tenantId}/{pluginKey}/{version}/{encodedFilename}}.
     *
     * <p>{@code tenantId} w ścieżce — per-tenant izolacja w object storage (EPIC-28, V078):
     * dwa różne tenanty mogą wgrać JAR-a o tej samej nazwie bez kolizji kluczy S3.
     */
    private String buildS3Key(UUID tenantId, String pluginKey, String version, String originalFilename) {
        String encodedFilename = encodeFilename(originalFilename);
        return String.format("plugins/%s/%s/%s/%s", tenantId, pluginKey, version, encodedFilename);
    }

    /**
     * Klucz S3 dla {@code overwrite=true} — jak {@link #buildS3Key}, ale z dopisanym unikalnym
     * segmentem ({@link UUID#randomUUID()}), żeby ZAWSZE różnił się od poprzedniego klucza tej
     * samej wersji, nawet gdy {@code originalFilename} się nie zmienił (BLOCKER fix, code
     * review, fix/plugin-version-overwrite — patrz komentarz w {@link #storeValidatedJar} przy
     * wywołaniu tej metody po pełne uzasadnienie: bez tego PUT nadpisywałby w miejscu treść, do
     * której DB wiersz jeszcze wskazuje PRZED commitem, co jest nieodwracalne, jeśli commit
     * potem zawiedzie).
     *
     * <p>Ścieżka pierwszego uploadu (insert) nadal używa deterministycznego {@link #buildS3Key}
     * — bez zmian, zero wpływu na istniejące testy/zachowanie dla nowych wersji.
     */
    private String buildOverwriteS3Key(UUID tenantId, String pluginKey, String version, String originalFilename) {
        String encodedFilename = encodeFilename(originalFilename);
        String uniqueSuffix = UUID.randomUUID().toString();
        return String.format("plugins/%s/%s/%s/%s-%s", tenantId, pluginKey, version, uniqueSuffix, encodedFilename);
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

    /** Wyjątek operacji storage pluginów (S3 lub niespodziewany stan po walidacji). */
    static class PluginStorageException extends RuntimeException {
        PluginStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
