package com.contactcenter.domain.plugin.dto;

import com.contactcenter.domain.plugin.PluginVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reprezentacja jednej wersji pluginu do ekspozycji przez API (panel administracyjny
 * tenanta/platformy). Niemutowalny DTO — nigdy encja JPA poza pakietem {@code domain.plugin}.
 *
 * <p>Tworzenie instancji (mapowanie z {@code PluginVersion}) jest zadaniem BE-099 — ten
 * ticket (BE-098) dostarcza wyłącznie kontrakt DTO gotowy na użycie.
 *
 * @param id                identyfikator wersji
 * @param pluginId           identyfikator pluginu (katalogu)
 * @param pluginKey          {@code pluginKey} z manifestu, dla wygody UI bez dodatkowego joina
 * @param displayName        nazwa wyświetlana pluginu, z {@code Plugin.displayName} (FE-097)
 * @param vendor             nazwa dostawcy pluginu, z {@code Plugin.vendor} (FE-097)
 * @param version            wersja semver
 * @param sdkVersion         wersja SDK, względem której skompilowano plugin
 * @param status             status wersji (UPLOADED/VALIDATED/PENDING_REVIEW/REJECTED/REVOKED)
 * @param validationErrors   opisowe błędy walidacji (puste gdy status != REJECTED)
 * @param permissions        deklarowane uprawnienia z manifestu (np. {@code "customer:read"}),
 *                            do prezentacji w dialogu instalacji jako checkboxy {@code grantedPermissions} (FE-098)
 * @param uploadedByUserId   identyfikator użytkownika, który wgrał wersję
 * @param uploadedAt         znacznik czasu wgrania
 */
public record PluginVersionDto(
        UUID id,
        UUID pluginId,
        String pluginKey,
        String displayName,
        String vendor,
        String version,
        String sdkVersion,
        String status,
        List<String> validationErrors,
        List<String> permissions,
        UUID uploadedByUserId,
        Instant uploadedAt
) {

    /**
     * Mapuje encję {@link PluginVersion} (+ jej {@code Plugin} rodzica, EAGER {@code @ManyToOne})
     * na DTO, czytając {@code permissions} z zapisanego {@code manifestJson} (BE-110, EPIC-28).
     *
     * <p>W odróżnieniu od mapowania używanego tuż po uploadzie ({@code PluginStorageServiceImpl}),
     * ta metoda nie zakłada dostępu do świeżo sparsowanego {@code PluginManifest} — operuje
     * wyłącznie na trwałych danych encji, więc jest właściwa dla każdego miejsca, które odczytuje
     * {@link PluginVersion} z bazy (katalog, listing), nie tylko bezpośrednio po zapisie.
     *
     * @param entity wersja pluginu wraz z załadowanym {@code Plugin}
     * @return DTO gotowy do zwrócenia przez API
     */
    public static PluginVersionDto from(PluginVersion entity) {
        return new PluginVersionDto(
                entity.getId(),
                entity.getPlugin().getId(),
                entity.getPlugin().getPluginKey(),
                entity.getPlugin().getDisplayName(),
                entity.getPlugin().getVendor(),
                entity.getVersion(),
                entity.getSdkVersion(),
                entity.getStatus().name(),
                entity.getValidationErrors() != null ? entity.getValidationErrors() : List.of(),
                extractManifestPermissions(entity),
                entity.getUploadedByUserId(),
                entity.getUploadedAt()
        );
    }

    /**
     * Wyciąga listę {@code permissions} z {@code PluginVersion.manifestJson} (surowa mapa JSONB
     * zapisana przez {@code PluginStorageServiceImpl#manifestToMap}) — wzorzec analogiczny do
     * {@code PluginRegistrationServiceImpl#extractManifestPermissions}.
     *
     * @return lista permissions z manifestu, lub pusta lista gdy pole nie istnieje/jest złego typu
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractManifestPermissions(PluginVersion entity) {
        Map<String, Object> manifest = entity.getManifestJson();
        if (manifest == null) {
            return List.of();
        }

        Object rawPermissions = manifest.get("permissions");
        if (!(rawPermissions instanceof List<?> list)) {
            return List.of();
        }

        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
