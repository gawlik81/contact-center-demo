package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.CampaignArchiveSettingService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Okres archiwizacji kontaktów zakończonych kampanii tenanta (BE-151).
 */
@Schema(description = "Okres archiwizacji kontaktów zakończonych kampanii (COMPLETED/STOPPED) tenanta. "
        + "JEDNOSTKA: DNI od zakończenia kampanii. UWAGA: po archiwizacji kontakty kampanii znikają z "
        + "listy kontaktów, statystyk i dialera (trafiają do campaign_contact_archive).")
public record CampaignArchiveSettingDto(

        @Schema(description = "Efektywny okres w dniach (zakres 30-730).", example = "180")
        int archiveAfterDays,

        @Schema(description = "true = brak jawnego ustawienia tenanta, obowiązuje wartość domyślna.", example = "true")
        @JsonProperty("isDefault")
        boolean isDefault,

        @Schema(description = "Wartość domyślna (dni).", example = "180")
        int defaultDays,

        @Schema(description = "Minimalny dozwolony okres (dni).", example = "30")
        int minDays,

        @Schema(description = "Maksymalny dozwolony okres (dni).", example = "730")
        int maxDays,

        @Schema(description = "Użytkownik, który ostatnio zmienił ustawienie (null: domyślne lub usunięty użytkownik).",
                nullable = true)
        UUID updatedBy,

        @Schema(description = "Moment ostatniej zmiany (null dla wartości domyślnej).", nullable = true)
        Instant updatedAt,

        @Schema(description = "Ostrzeżenie: skrócenie okresu powoduje masową archiwizację przy najbliższym przebiegu "
                + "CampaignArchiveJob (kontakty znikną z UI). Pole stałe, informacyjne.")
        String warning
) {
    public static final String WARNING = "Skrócenie okresu powoduje, że przy najbliższym przebiegu archiwizacji "
            + "(codziennie 04:00 UTC, o ile włączona) kontakty wszystkich kampanii zakończonych dawniej niż "
            + "nowy okres zostaną przeniesione do archiwum i znikną z listy kontaktów, statystyk i dialera. "
            + "Użyj GET .../campaign-archive/preview, aby zobaczyć skutek przed zapisem.";

    public static CampaignArchiveSettingDto defaults() {
        return new CampaignArchiveSettingDto(
                CampaignArchiveSettingService.DEFAULT_ARCHIVE_AFTER_DAYS, true,
                CampaignArchiveSettingService.DEFAULT_ARCHIVE_AFTER_DAYS,
                CampaignArchiveSettingService.MIN_ARCHIVE_AFTER_DAYS,
                CampaignArchiveSettingService.MAX_ARCHIVE_AFTER_DAYS,
                null, null, WARNING);
    }

    public static CampaignArchiveSettingDto explicit(int days, UUID updatedBy, Instant updatedAt) {
        return new CampaignArchiveSettingDto(
                days, false,
                CampaignArchiveSettingService.DEFAULT_ARCHIVE_AFTER_DAYS,
                CampaignArchiveSettingService.MIN_ARCHIVE_AFTER_DAYS,
                CampaignArchiveSettingService.MAX_ARCHIVE_AFTER_DAYS,
                updatedBy, updatedAt, WARNING);
    }
}
