package com.contactcenter.domain.retention;

import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;

import java.util.UUID;

/**
 * Okres archiwizacji kontaktów zakończonych kampanii per tenant (BE-151, EPIC-30).
 *
 * <p>Jednostka: DNI. Brak wiersza w {@code tenant_campaign_archive_setting} = {@link
 * #DEFAULT_ARCHIVE_AFTER_DAYS}. Serwis NIE weryfikuje "własności" {@code tenantId} (tak jak
 * pozostałe serwisy retencji) — barierą jest kontroler; zapisy w repozytorium mają
 * {@code assertSameTenant}.
 *
 * <p>Domyślne 180 żyje w 3 miejscach: {@code DEFAULT} w DDL (V136), {@code COALESCE} w funkcji SQL
 * (V138) i ta stała — spójność DDL/funkcja pilnuje test migracyjny DB-084, spójność stałej z funkcją
 * test integracyjny BE-151.
 */
public interface CampaignArchiveSettingService {

    int DEFAULT_ARCHIVE_AFTER_DAYS = 180;
    int MIN_ARCHIVE_AFTER_DAYS = 30;
    int MAX_ARCHIVE_AFTER_DAYS = 730;

    /** Okres efektywny tenanta (180 + {@code isDefault=true} gdy brak jawnego ustawienia). */
    CampaignArchiveSettingDto get(UUID tenantId);

    /**
     * Zapisuje okres. Zwraca stan po zapisie. Audyt ({@code audit_log}) powstaje tylko, gdy
     * wartość EFEKTYWNA się zmienia (patrz implementacja).
     *
     * @throws IllegalArgumentException gdy {@code archiveAfterDays} jest null lub poza [30,730] (HTTP 422)
     */
    CampaignArchiveSettingDto update(UUID tenantId, Integer archiveAfterDays, UUID updatedByUserId);

    /**
     * Podgląd skutku zmiany okresu na {@code newDays} (tylko odczyt, bez efektów ubocznych).
     *
     * @throws IllegalArgumentException gdy {@code newDays} jest null lub poza [30,730] (HTTP 422)
     */
    CampaignArchivePreviewDto preview(UUID tenantId, Integer newDays);
}
