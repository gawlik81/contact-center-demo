package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.RetentionDataCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Żądanie aktualizacji (lub utworzenia — upsert) polityki retencji dla jednej kategorii danych.
 *
 * <p>Konsumowane przez przyszły REST endpoint (BE-118). {@code retentionMonths} jest
 * walidowany zarówno tutaj (Bean Validation, HTTP 422 czytelny dla klienta API) jak
 * i dodatkowo w {@link com.contactcenter.domain.retention.RetentionPolicyServiceImpl}
 * (obrona w głąb — serwis może być wywołany spoza kontrolera, np. z przyszłego panelu
 * SUPER_ADMIN lub joba, gdzie Bean Validation z warstwy HTTP nie zadziała).
 *
 * <p>{@code updatedByUserId} celowo NIE jest polem tego DTO — pochodzi z kontekstu
 * uwierzytelnionego użytkownika (JWT / {@code TenantContext}), nie z ciała żądania.
 *
 * @param dataCategory     kategoria danych, której dotyczy zmiana
 * @param retentionMonths  nowa retencja w miesiącach, zgodna z CHECK w DB ([1,120])
 * @param autoPurgeEnabled czy włączyć automatyczne usuwanie po upływie retencji
 */
public record UpdateRetentionPolicyRequest(

        @NotNull(message = "Kategoria danych jest wymagana")
        RetentionDataCategory dataCategory,

        @NotNull(message = "Retencja w miesiącach jest wymagana")
        @Min(value = 1, message = "Retencja musi wynosić co najmniej 1 miesiąc")
        @Max(value = 120, message = "Retencja nie może przekraczać 120 miesięcy")
        Integer retentionMonths,

        boolean autoPurgeEnabled

) {}
