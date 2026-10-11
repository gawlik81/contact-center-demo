package com.contactcenter.domain.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Ustawienie PER TENANT: po ilu DNIACH od zakończenia kampanii {@code COMPLETED}/{@code STOPPED}
 * jej kontakty są przenoszone z {@code campaign_contact} do {@code campaign_contact_archive}
 * (BE-151, EPIC-30; tabela {@code tenant_campaign_archive_setting} z V136).
 *
 * <p>BRAK WIERSZA = domyślne {@link CampaignArchiveSettingService#DEFAULT_ARCHIVE_AFTER_DAYS} (180) —
 * tak samo jak w funkcji SQL {@code archive_completed_campaign_contacts()} (V138, {@code COALESCE}).
 * To NIE jest retencja {@code CAMPAIGN_DATA} ({@code retention_months}, MIESIĄCE od
 * {@code archived_at}) — to opóźnienie PRZED archiwizacją, w DNIACH.
 *
 * <p>Zapis i odczyt wyłącznie przez natywny SQL w {@link TenantCampaignArchiveSettingRepository}
 * ({@code @Id} = {@code tenant_id}, RLS FORCE po GUC {@code app.current_tenant_id}).
 */
@Entity
@Table(name = "tenant_campaign_archive_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantCampaignArchiveSetting {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Okres w DNIACH, CHECK 30..730 w bazie. */
    @Column(name = "archive_after_days", nullable = false)
    private int archiveAfterDays;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
