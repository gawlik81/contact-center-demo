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
 * Encja domenowa reprezentująca wielokrotnie używalny zestaw dyspozycji (BE-095).
 *
 * <p>Mapuje tabelę {@code disposition_set} (V095). Zestaw grupuje elementy
 * ({@link DispositionSetItem}) i może być jednorazowo zastosowany do kampanii
 * lub kolejki poprzez skopiowanie elementów jako {@link CustomDisposition}.
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 * Zapis i aktualizacja wyłącznie przez natywny SQL w {@link DispositionSetRepository}.
 */
@Entity
@Table(name = "disposition_set")
@Getter
@Setter
@NoArgsConstructor
public class DispositionSet {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
