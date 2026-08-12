package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.RetentionDataCategory;
import jakarta.validation.constraints.NotNull;

/**
 * Żądanie ręcznego uruchomienia usuwania danych (BE-118,
 * {@code POST /api/tenants/{tenantId}/retention/purge}).
 *
 * <p>{@code tenantId} celowo NIE jest polem tego DTO — pochodzi ze ścieżki URL, weryfikowany
 * przez {@code RetentionController} przeciw {@code TenantContext.getTenantId()} przed
 * jakimkolwiek wywołaniem serwisu (patrz Javadoc {@code RetentionController}). Analogicznie
 * {@code triggeredByUserId} pochodzi z {@code TenantContext.getUserId()}, nie z ciała żądania —
 * ten sam wzorzec co {@link UpdateRetentionPolicyRequest}.
 *
 * @param dataCategory kategoria danych do usunięcia. Wyłącznie {@code CONTACT_INTERACTIONS}
 *                      i {@code TRANSCRIPTS} są dziś obsługiwane przez
 *                      {@code RetentionPurgeService} (BE-113) — {@code RECORDINGS}/
 *                      {@code CAMPAIGN_DATA} rzucają {@link UnsupportedOperationException},
 *                      zmapowane globalnie na HTTP 501 (patrz {@code GlobalExceptionHandler}),
 *                      bez potrzeby dodatkowej walidacji tutaj.
 */
public record PurgeRequestDto(

        @NotNull(message = "Kategoria danych jest wymagana")
        RetentionDataCategory dataCategory

) {}
