package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.TenantRetentionPolicy;

import java.time.Instant;
import java.util.UUID;

/**
 * Reprezentacja polityki retencji do ekspozycji przez API (BE-118).
 *
 * <p>Niemutowalny DTO — encja JPA {@link TenantRetentionPolicy} nigdy nie opuszcza
 * pakietu {@code domain.retention}.
 *
 * @param policyId         identyfikator polityki
 * @param tenantId         identyfikator tenanta-właściciela
 * @param dataCategory     kategoria danych
 * @param retentionMonths  retencja w miesiącach ([1,120])
 * @param autoPurgeEnabled czy automatyczne usuwanie po upływie retencji jest włączone
 * @param updatedBy        UUID użytkownika, który jako ostatni zmienił politykę (null po seedowaniu)
 * @param createdAt        znacznik czasu utworzenia
 * @param updatedAt        znacznik czasu ostatniej modyfikacji
 */
public record RetentionPolicyDto(
        UUID policyId,
        UUID tenantId,
        RetentionDataCategory dataCategory,
        int retentionMonths,
        boolean autoPurgeEnabled,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static RetentionPolicyDto from(TenantRetentionPolicy entity) {
        return new RetentionPolicyDto(
                entity.getPolicyId(),
                entity.getTenantId(),
                entity.getDataCategory(),
                entity.getRetentionMonths(),
                entity.isAutoPurgeEnabled(),
                entity.getUpdatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
