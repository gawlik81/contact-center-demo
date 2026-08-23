package com.contactcenter.domain.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja domenowa polityki retencji danych per tenant i kategoria.
 *
 * <p>Mapuje tabelę {@code tenant_retention_policy} (V082, DB-046). Jeden wiersz per
 * kombinacja {@code (tenant_id, data_category)} — wymuszone unikalnym ograniczeniem
 * {@code uq_tenant_retention_policy} w bazie danych.
 *
 * <p>Retencja przechowywana WYŁĄCZNIE w miesiącach ({@code retention_months},
 * CHECK BETWEEN 1 AND 120) — kategorie skonfigurowane dziś gdzie indziej w dniach
 * (np. RECORDINGS domyślnie wg {@code S3Properties.retentionDays}, patrz
 * {@link RetentionPolicyServiceImpl#resolveRecordingsRetentionMonths}, od BE-116) są
 * przeliczane na miesiące przez {@code CEIL(dni / 30.0)}, zarówno w backfillu V082
 * jak i w {@link RetentionPolicyServiceImpl#seedDefaultPolicies}. Odwrotna konwersja
 * (miesiące → dni, {@code retentionMonths * 30}) dostępna przez
 * {@link RetentionPolicyService#getRetentionDays}, główny konsument:
 * {@code RecordingRetentionJob} (BE-116).
 *
 * <p>Izolacja multi-tenant przez PostgreSQL RLS (FORCE ROW LEVEL SECURITY,
 * GUC {@code app.current_tenant_id}). Zapis i odczyt wyłącznie przez natywny SQL
 * w {@link TenantRetentionPolicyRepository} — identyfikator generowany przez bazę
 * ({@code uuid_generate_v4()} w INSERT), tabela NIE jest partycjonowana.
 */
@Entity
@Table(name = "tenant_retention_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantRetentionPolicy {

    @Id
    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Kategoria danych — wartości zgodne z CHECK w DB, patrz {@link RetentionDataCategory}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_category", nullable = false, length = 30, updatable = false)
    private RetentionDataCategory dataCategory;

    /** Retencja w MIESIĄCACH (nie dniach) — CHECK BETWEEN 1 AND 120 w DB. */
    @Column(name = "retention_months", nullable = false)
    private int retentionMonths;

    @Column(name = "auto_purge_enabled", nullable = false)
    private boolean autoPurgeEnabled;

    /** UUID użytkownika (app_user), który jako ostatni zmienił politykę — null po seedowaniu. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
