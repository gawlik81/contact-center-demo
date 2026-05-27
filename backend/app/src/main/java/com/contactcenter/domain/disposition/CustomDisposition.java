package com.contactcenter.domain.disposition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja domenowa reprezentująca własną dyspozycję po kontakcie.
 *
 * <p>Mapuje tabelę {@code custom_disposition} (V092). Dyspozycje mogą być
 * przypisane do kampanii ({@code campaign_id}) lub kolejki ({@code queue_id}).
 * Oba pola są nullable — wzajemnie się wykluczają w kontekście biznesowym.
 *
 * <p>Priorytet resolucji: kampania → kolejka → systemowe domyślne.
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 * Zapis i aktualizacja wyłącznie przez natywny SQL w {@link CustomDispositionRepository}.
 */
@Entity
@Table(name = "custom_disposition")
@Getter
@Setter
@NoArgsConstructor
public class CustomDisposition {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "campaign_id")
    private UUID campaignId;   // nullable — zakres kampania

    @Column(name = "queue_id")
    private UUID queueId;      // nullable — zakres kolejka

    @Column(name = "disposition_code", nullable = false, length = 50)
    private String dispositionCode;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "tone", nullable = false, length = 20)
    private String tone;  // positive | negative | neutral | warning

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
