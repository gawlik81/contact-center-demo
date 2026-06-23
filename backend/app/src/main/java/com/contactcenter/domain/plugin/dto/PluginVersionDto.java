package com.contactcenter.domain.plugin.dto;

import java.time.Instant;
import java.util.List;
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
        UUID uploadedByUserId,
        Instant uploadedAt
) {
}
