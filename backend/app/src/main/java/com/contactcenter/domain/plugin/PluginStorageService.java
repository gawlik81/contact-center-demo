package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;

import java.util.UUID;

/**
 * Serwis zapisu zwalidowanego JAR-a pluginu do katalogu per-tenant (EPIC-28, BE-099).
 *
 * <p>Wywoływany <strong>po</strong> pozytywnej walidacji przez {@link PluginValidationService}
 * (status {@code VALIDATED} lub {@code PENDING_REVIEW}) — ten serwis nie waliduje nic sam,
 * zakłada że {@code jarBytes} już przeszły pełny pipeline z {@code PluginValidationService}.
 *
 * <p>Odpowiedzialności:
 * <ol>
 *   <li>Zapis bajtów JAR-a do S3/MinIO pod kluczem {@code plugins/{tenantId}/{pluginKey}/{version}/{filename}}</li>
 *   <li>Znajdź-lub-utwórz wiersz {@code plugin} (katalog globalny, po {@code pluginKey})</li>
 *   <li>Wstawienie nowego wiersza {@code plugin_version} z {@code tenant_id} uploaderów (V078)</li>
 * </ol>
 *
 * <p>Tabela {@code plugin} pozostaje globalna (bez {@code tenant_id}); {@code plugin_version}
 * jest per-tenant od V078 (EPIC-28) — każdy upload JAR-a należy do tenanta, który go wgrał.
 * Klucz S3 zawiera {@code tenantId} — dwa różne tenanty mogą wgrać JAR-a o tej samej nazwie.
 */
public interface PluginStorageService {

    /**
     * Zapisuje zwalidowany JAR pluginu: upload do object storage + insert do katalogu per-tenant.
     *
     * @param jarBytes          surowe bajty JAR-a (już zwalidowane przez {@link PluginValidationService})
     * @param originalFilename  oryginalna nazwa wgranego pliku (do budowy klucza S3)
     * @param validationResult  wynik walidacji ({@code VALIDATED}/{@code PENDING_REVIEW}) — niesie
     *                          sparsowany manifest potrzebny do budowy {@code Plugin}/{@code PluginVersion}
     * @param tenantId          tenant wykonujący upload (zapisywany w {@code plugin_version.tenant_id})
     * @param uploadedByUserId  identyfikator użytkownika wykonującego upload
     * @return DTO nowo utworzonej wersji pluginu
     */
    PluginVersionDto storeValidatedJar(
            byte[] jarBytes,
            String originalFilename,
            ValidationResult validationResult,
            UUID tenantId,
            UUID uploadedByUserId);

    /**
     * Pobiera bajty JAR-a pluginu z object storage.
     *
     * <p>Używane przez {@code PluginRuntimeManager} (BE-101) do pobrania JAR-a, który następnie
     * jest zapisywany do lokalnego cache na dysku węzła i ładowany przez dedykowany
     * {@code PluginClassLoader}. Katalog jest globalny (bez {@code tenantId}/RLS, ADR-13) —
     * ten sam JAR jest współdzielony między tenantami, więc ta metoda nie przyjmuje
     * parametru tenanta.
     *
     * @param jarObjectKey klucz S3/MinIO ({@code PluginVersion.jarObjectKey})
     * @return surowe bajty JAR-a
     * @throws PluginStorageServiceImpl.PluginStorageException gdy obiekt nie istnieje lub
     *         pobranie z object storage się nie powiodło
     */
    byte[] downloadJar(String jarObjectKey);
}
