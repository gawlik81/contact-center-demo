package com.contactcenter.domain.retention.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Podgląd skutku zmiany okresu archiwizacji (BE-151, tylko odczyt). Liczby opisują stan "na teraz":
 * ile kampanii/kontaktów zostałoby zarchiwizowanych przy najbliższym przebiegu joba dla danego okresu.
 */
@Schema(description = "Skutek zmiany okresu archiwizacji (podgląd, bez zapisu). Predykat identyczny z funkcją "
        + "archive_completed_campaign_contacts(): status COMPLETED/STOPPED i COALESCE(ended_at, updated_at, created_at) "
        + "starsze niż okres.")
public record CampaignArchivePreviewDto(

        @Schema(description = "Obecnie obowiązujący okres (dni).", example = "180")
        int currentDays,

        @Schema(description = "Okres podany w zapytaniu (dni).", example = "30")
        int newDays,

        @Schema(description = "Kampanie do zarchiwizowania przy okresie newDays (tylko te, które mają jeszcze kontakty w campaign_contact).", example = "4")
        long campaignsToArchive,

        @Schema(description = "Kontakty (campaign_contact) tych kampanii do przeniesienia do archiwum.", example = "1200")
        long contactsToArchive,

        @Schema(description = "Kampanie do zarchiwizowania przy okresie bieżącym.", example = "0")
        long campaignsToArchiveAtCurrentDays,

        @Schema(description = "Kontakty do zarchiwizowania przy okresie bieżącym.", example = "0")
        long contactsToArchiveAtCurrentDays,

        @Schema(description = "Różnica kampanii względem okresu bieżącego (newDays minus bieżący); ujemna przy wydłużeniu.",
                example = "4")
        long additionalCampaigns,

        @Schema(description = "Różnica kontaktów względem okresu bieżącego; ujemna przy wydłużeniu.", example = "1200")
        long additionalContacts,

        @Schema(description = "true gdy newDays < currentDays (skrócenie: masowa archiwizacja przy najbliższym przebiegu).")
        boolean shortensPeriod
) {}
