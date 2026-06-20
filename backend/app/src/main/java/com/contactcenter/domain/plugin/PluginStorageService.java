package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;

import java.util.UUID;

/**
 * Serwis zapisu zwalidowanego JAR-a pluginu do globalnego katalogu (EPIC-28, BE-099).
 *
 * <p>Wywoływany <strong>po</strong> pozytywnej walidacji przez {@link PluginValidationService}
 * (status {@code VALIDATED} lub {@code PENDING_REVIEW}) — ten serwis nie waliduje nic sam,
 * zakłada że {@code jarBytes} już przeszły pełny pipeline z {@code PluginValidationService}.
 *
 * <p>Odpowiedzialności:
 * <ol>
 *   <li>Zapis bajtów JAR-a do S3/MinIO pod kluczem {@code plugins/{pluginKey}/{version}/{filename}}</li>
 *   <li>Znajdź-lub-utwórz wiersz {@code plugin} (katalog globalny, po {@code pluginKey})</li>
 *   <li>Wstawienie nowego, niemutowalnego wiersza {@code plugin_version}</li>
 * </ol>
 *
 * <p>Tabele {@code plugin}/{@code plugin_version} są globalne (bez {@code tenant_id}, bez RLS,
 * ADR-13) — ten sam JAR jest współdzielony między tenantami, dlatego klucz S3 nie zawiera
 * {@code tenantId}.
 */
public interface PluginStorageService {

    /**
     * Zapisuje zwalidowany JAR pluginu: upload do object storage + insert do katalogu.
     *
     * @param jarBytes          surowe bajty JAR-a (już zwalidowane przez {@link PluginValidationService})
     * @param originalFilename  oryginalna nazwa wgranego pliku (do budowy klucza S3)
     * @param validationResult  wynik walidacji ({@code VALIDATED}/{@code PENDING_REVIEW}) — niesie
     *                          sparsowany manifest potrzebny do budowy {@code Plugin}/{@code PluginVersion}
     * @param uploadedByUserId  identyfikator użytkownika wykonującego upload
     * @return DTO nowo utworzonej wersji pluginu
     */
    PluginVersionDto storeValidatedJar(
            byte[] jarBytes,
            String originalFilename,
            ValidationResult validationResult,
            UUID uploadedByUserId);
}
