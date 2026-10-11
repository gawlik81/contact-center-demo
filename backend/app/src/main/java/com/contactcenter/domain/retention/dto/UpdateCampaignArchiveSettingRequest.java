package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.CampaignArchiveSettingService;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Żądanie ustawienia okresu archiwizacji kontaktów zakończonych kampanii (BE-151). Walidowane
 * tutaj (HTTP 422) oraz w serwisie (obrona w głąb) i CHECK w bazie (V136).
 */
@Schema(description = "Ustawienie okresu archiwizacji kontaktów zakończonych kampanii.")
public record UpdateCampaignArchiveSettingRequest(

        @Schema(description = "Po ilu DNIACH od zakończenia kampanii (COMPLETED/STOPPED) jej kontakty są "
                + "archiwizowane. Zakres 30-730. UWAGA: skrócenie okresu = masowa archiwizacja przy "
                + "najbliższym przebiegu; zarchiwizowane kontakty znikają z UI/statystyk/dialera.",
                example = "180", minimum = "30", maximum = "730")
        @NotNull(message = "Okres archiwizacji (dni) jest wymagany")
        @Min(value = CampaignArchiveSettingService.MIN_ARCHIVE_AFTER_DAYS,
                message = "Okres archiwizacji musi wynosić co najmniej {value} dni")
        @Max(value = CampaignArchiveSettingService.MAX_ARCHIVE_AFTER_DAYS,
                message = "Okres archiwizacji nie może przekraczać {value} dni")
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer archiveAfterDays
) {}
