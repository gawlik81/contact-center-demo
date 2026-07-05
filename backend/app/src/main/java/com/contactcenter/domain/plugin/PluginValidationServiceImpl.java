package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Implementacja pipeline'u walidacji JAR-a pluginu (ARCHITECTURE.md §11.4).
 *
 * <p>Każdy etap może zakończyć pipeline przedwcześnie z {@code REJECTED} — etapy późniejsze
 * (np. skan ASM) nigdy nie widzą bajtów, które nie przeszły wcześniejszej bramki (rozmiar/MIME,
 * checksum, schema manifestu), zgodnie z zasadą "gate przed dotknięciem klasy".
 *
 * <p><strong>Ten serwis tylko waliduje</strong> — nie zapisuje nic do bazy danych ani object
 * storage (to BE-099). {@link PluginRepository}/{@link PluginVersionRepository} są w tym
 * tickecie tylko mapowaniem JPA, jeszcze nieużywanym przez ten serwis.
 */
@Slf4j
@Service
class PluginValidationServiceImpl implements PluginValidationService {

    private static final long MAX_JAR_SIZE_BYTES = 50L * 1024 * 1024;
    private static final byte[] ZIP_MAGIC_BYTES = {0x50, 0x4B, 0x03, 0x04};
    private static final String META_INF_MANIFEST_ENTRY = "META-INF/plugin-manifest.json";

    @Override
    public ValidationResult validate(byte[] jarBytes, UUID uploadedByUserId) {
        log.info("[PluginValidationService] Walidacja JAR-a uploadByUserId={}, size={} bytes",
                uploadedByUserId, jarBytes == null ? 0 : jarBytes.length);

        ValidationResult sizeAndMimeResult = checkSizeAndMime(jarBytes);
        if (sizeAndMimeResult != null) {
            return sizeAndMimeResult;
        }

        Path tempJarFile;
        try {
            tempJarFile = writeToTempFile(jarBytes);
        } catch (IOException e) {
            log.error("[PluginValidationService] Nie można zapisać tymczasowego pliku JAR: {}", e.getMessage());
            return ValidationResult.rejected("Błąd wewnętrzny podczas przetwarzania JAR-a");
        }

        try (ZipFile zipFile = new ZipFile(tempJarFile.toFile())) {
            byte[] manifestBytes = readManifestEntry(zipFile);
            if (manifestBytes == null) {
                return ValidationResult.rejected(
                        "JAR nie zawiera wymaganego pliku " + META_INF_MANIFEST_ENTRY);
            }

            PluginManifestValidator.ManifestValidationResult manifestValidation =
                    PluginManifestValidator.validate(manifestBytes);
            if (!manifestValidation.isValid()) {
                List<String> errors = new ArrayList<>(manifestValidation.schemaErrors());
                if (errors.isEmpty()) {
                    errors.add("Manifest nie przeszedł walidacji schema");
                }
                return ValidationResult.rejected(prefixed("Manifest invalid", errors));
            }
            PluginManifest manifest = manifestValidation.manifest();

            ValidationResult checksumResult = checkChecksum(zipFile, manifest.checksumSha256());
            if (checksumResult != null) {
                return checksumResult;
            }

            List<String> enumViolations = checkEnumMembership(manifest);
            if (!enumViolations.isEmpty()) {
                return ValidationResult.rejected(enumViolations);
            }

            List<String> sdkVersionViolations = checkSdkVersion(manifest.sdkVersion());
            if (!sdkVersionViolations.isEmpty()) {
                return ValidationResult.rejected(sdkVersionViolations);
            }

            PluginBytecodeScanner.ScanResult scanResult =
                    PluginBytecodeScanner.scan(zipFile, manifest.entryPointClass());
            if (!scanResult.isClean()) {
                return ValidationResult.rejected(scanResult.violations());
            }

            // Krok 5 (ARCHITECTURE.md §11.4): hook na weryfikację podpisu kryptograficznego.
            // Świadomie no-op w tej implementacji (OQ-28-1, EPIC-28-PLAN.md) — logika podpisu
            // nie jest częścią zakresu BE-098.
            verifySignatureNoOp(manifest);

            log.info("[PluginValidationService] JAR zwalidowany pozytywnie: pluginKey={}, version={}",
                    manifest.pluginKey(), manifest.version());
            return ValidationResult.validated();

        } catch (IOException e) {
            log.warn("[PluginValidationService] Nie można otworzyć JAR-a jako ZIP: {}", e.getMessage());
            return ValidationResult.rejected("Plik nie jest poprawnym archiwum ZIP/JAR: " + e.getMessage());
        } finally {
            deleteQuietly(tempJarFile);
        }
    }

    // =========================================================================
    // Krok 1: guard rozmiaru i MIME (magic bytes)
    // =========================================================================

    private ValidationResult checkSizeAndMime(byte[] jarBytes) {
        if (jarBytes == null || jarBytes.length == 0) {
            return ValidationResult.rejected("Plik JAR jest pusty");
        }
        if (jarBytes.length > MAX_JAR_SIZE_BYTES) {
            return ValidationResult.rejected(
                    "Plik JAR przekracza maksymalny dozwolony rozmiar 50MB (otrzymano "
                            + jarBytes.length + " bajtów)");
        }
        if (jarBytes.length < ZIP_MAGIC_BYTES.length
                || !Arrays.equals(ZIP_MAGIC_BYTES, 0, ZIP_MAGIC_BYTES.length, jarBytes, 0, ZIP_MAGIC_BYTES.length)) {
            return ValidationResult.rejected(
                    "Plik nie jest poprawnym archiwum ZIP/JAR (nieprawidłowe magic bytes)");
        }
        return null;
    }

    // =========================================================================
    // Krok 2: checksum SHA-256
    // =========================================================================

    /**
     * Weryfikuje {@code manifest.checksumSha256} względem SHA-256 obliczonego z zawartości
     * JAR-a <strong>z wyłączeniem {@code META-INF/plugin-manifest.json}</strong> samego.
     *
     * <p>Liczenie hash całego, finalnego pliku — łącznie z manifestem, który ten sam hash
     * zawiera jako swoje własne pole — jest matematycznie niewykonalne do spełnienia przez
     * dostawcę pluginu (SHA-256 nie ma praktycznych punktów stałych): build tool nie może
     * wyprodukować pliku, którego hash sam siebie opisuje, bez ataku na funkcję skrótu.
     * Dlatego, analogicznie do standardowego {@code META-INF/MANIFEST.MF} w JAR-ach Javy
     * (który również nigdy nie zawiera checksumu samego siebie) i precedensów branżowych
     * (Maven {@code .sha256} jest plikiem zewnętrznym, Docker image digest nie jest polem
     * we własnym manifeście), interpretujemy "SHA-256 of the JAR, computed by the build tool"
     * (ARCHITECTURE.md §11.2) jako hash zawartości wykonywalnej (klasy + zasoby) PRZED
     * dopisaniem manifestu z tym checksumem — manifest jest metadaną dopisaną jako ostatni
     * krok budowy, podobnie jak podpis w wielu formatach pakietów.
     *
     * <p>To wciąż wykrywa przypadkowe uszkodzenie treści pluginu podczas transferu (cel
     * wyraźnie opisany w ARCHITECTURE.md: "detects accidental corruption; NOT a substitute
     * for signing") — manifest sam nie jest podatny na uszkodzenie w transferze bardziej niż
     * inne metadane, więc wyłączenie go z zakresu hashowanego nie obniża wartości tego etapu.
     *
     * <p>Wpisy są sortowane alfabetycznie po nazwie przed hashowaniem, by porządek odczytu
     * {@link ZipFile#entries()} (nieokreślony przez kontrakt {@code ZipFile}) nie wpływał na
     * wynik.
     */
    private ValidationResult checkChecksum(ZipFile zipFile, String expectedChecksumHex) throws IOException {
        String actualChecksumHex = sha256OfEntriesExcludingManifest(zipFile);
        if (!actualChecksumHex.equalsIgnoreCase(expectedChecksumHex)) {
            return ValidationResult.rejected(
                    "Checksum mismatch: manifest.checksumSha256=" + expectedChecksumHex
                            + ", obliczony=" + actualChecksumHex);
        }
        return null;
    }

    private String sha256OfEntriesExcludingManifest(ZipFile zipFile) throws IOException {
        List<String> entryNames = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && !META_INF_MANIFEST_ENTRY.equals(entry.getName())) {
                entryNames.add(entry.getName());
            }
        }
        entryNames.sort(String::compareTo);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 jest gwarantowany przez JDK; to nie powinno się nigdy zdarzyć.
            throw new IllegalStateException("Algorytm SHA-256 niedostępny w JVM", e);
        }
        for (String entryName : entryNames) {
            digest.update(entryName.getBytes(StandardCharsets.UTF_8));
            try (InputStream in = zipFile.getInputStream(zipFile.getEntry(entryName))) {
                digest.update(in.readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // =========================================================================
    // Krok 4a: podzbiór enumów platformy (extensionPoints / permissions)
    // =========================================================================

    private List<String> checkEnumMembership(PluginManifest manifest) {
        List<String> violations = new ArrayList<>();

        for (String extensionPoint : manifest.extensionPoints()) {
            if (!isKnownExtensionPoint(extensionPoint)) {
                violations.add("Nieznany extensionPoint: '" + extensionPoint
                        + "' — platforma rozpoznaje tylko: " + Arrays.toString(ExtensionPoint.values()));
            }
        }

        List<String> permissions = manifest.permissions() == null ? List.of() : manifest.permissions();
        for (String permission : permissions) {
            if (!PluginPermission.isAllowed(permission)) {
                violations.add("Nieznane lub niepoprawne uprawnienie: '" + permission + "'");
            }
        }

        return violations;
    }

    private boolean isKnownExtensionPoint(String value) {
        for (ExtensionPoint extensionPoint : ExtensionPoint.values()) {
            if (extensionPoint.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Krok 4b: zakres wersji SDK
    // =========================================================================

    /**
     * Wersja SDK obecnie wspierana przez platformę — zakres major. Sprawdzamy tylko, że
     * deklarowana wersja zaczyna się od wspieranej wersji majorowej (np. {@code "1.x"} lub
     * {@code "1"}), zgodnie z opisem ticketu — pełna semantyka zakresów semver jest poza
     * zakresem tego etapu.
     */
    private static final String SUPPORTED_SDK_MAJOR = "1";

    private List<String> checkSdkVersion(String sdkVersion) {
        if (sdkVersion == null || sdkVersion.isBlank()) {
            return List.of("Manifest nie deklaruje sdkVersion");
        }
        String major = sdkVersion.split("[.x]", 2)[0];
        if (!SUPPORTED_SDK_MAJOR.equals(major)) {
            return List.of("Niewspierana wersja SDK: '" + sdkVersion
                    + "' — platforma wspiera wyłącznie major " + SUPPORTED_SDK_MAJOR + ".x");
        }
        return List.of();
    }

    // =========================================================================
    // Krok 5: hook weryfikacji podpisu (no-op)
    // =========================================================================

    /**
     * Hook na weryfikację podpisu kryptograficznego JAR-a (ARCHITECTURE.md §11.4 krok 5,
     * OQ-28-1). Świadomie no-op — logika podpisu nie jest implementowana w tym tickecie.
     * Pozostawiona jako osobna metoda, by przyszły ticket mógł podłączyć logikę bez zmiany
     * kontraktu {@link #validate(byte[], UUID)}.
     */
    @SuppressWarnings("unused")
    private void verifySignatureNoOp(PluginManifest manifest) {
        log.debug("[PluginValidationService] Weryfikacja podpisu pominięta (no-op, OQ-28-1): pluginKey={}",
                manifest.pluginKey());
    }

    // =========================================================================
    // Pomocnicze: odczyt ZIP, pliki tymczasowe
    // =========================================================================

    private byte[] readManifestEntry(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry(META_INF_MANIFEST_ENTRY);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zipFile.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private Path writeToTempFile(byte[] jarBytes) throws IOException {
        Path tempFile = Files.createTempFile("plugin-validation-", ".jar");
        Files.write(tempFile, jarBytes);
        return tempFile;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[PluginValidationService] Nie można usunąć pliku tymczasowego {}: {}",
                    path, e.getMessage());
        }
    }

    private List<String> prefixed(String prefix, List<String> messages) {
        List<String> result = new ArrayList<>();
        for (String message : messages) {
            result.add(prefix + ": " + message);
        }
        return result;
    }
}
