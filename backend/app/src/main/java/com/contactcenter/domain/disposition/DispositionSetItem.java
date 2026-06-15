package com.contactcenter.domain.disposition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Encja domenowa reprezentująca pojedynczy element zestawu dyspozycji (BE-095).
 *
 * <p>Mapuje tabelę {@code disposition_set_item} (V095). Element należy do zestawu
 * ({@link DispositionSet}) poprzez {@code set_id}.
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 * Zapis i aktualizacja wyłącznie przez natywny SQL w {@link DispositionSetItemRepository}.
 */
@Entity
@Table(name = "disposition_set_item")
@Getter
@Setter
@NoArgsConstructor
public class DispositionSetItem {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "set_id", nullable = false)
    private UUID setId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "disposition_code", nullable = false, length = 50)
    private String dispositionCode;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "tone", nullable = false, length = 20)
    private String tone;  // positive | negative | neutral | warning

    @Column(name = "ordinal", nullable = false)
    private int ordinal;
}
