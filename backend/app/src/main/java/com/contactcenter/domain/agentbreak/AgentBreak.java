package com.contactcenter.domain.agentbreak;

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
 * Encja domenowa reprezentująca przerwę agenta.
 *
 * <p>Mapuje tabelę {@code agent_break} (V048). Przerwy umożliwiają planowanie
 * i śledzenie nieobecności agentów (lunch, krótka przerwa, szkolenie itp.).
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 *
 * <p>Pola {@code breakType} i {@code status} przechowywane są jako {@code String}
 * (VARCHAR w DB). Konwersja na enumy {@link BreakType} i {@link BreakStatus}
 * odbywa się w warstwie serwisowej.
 *
 * <p>Zapis i aktualizacja wyłącznie przez natywny SQL w {@link AgentBreakRepository}.
 * Timestamp {@code createdAt} jest zarządzany przez SQL (INSERT); {@code updatedAt}
 * ustawiany przez SQL przy każdym UPDATE.
 */
@Entity
@Table(name = "agent_break")
@Getter
@Setter
@NoArgsConstructor
public class AgentBreak {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    /** Typ przerwy – przechowywany jako VARCHAR(50), wartości z {@link BreakType}. */
    @Column(name = "break_type", nullable = false, length = 50)
    private String breakType;

    @Column(name = "notes")
    private String notes;

    /** Status przerwy – przechowywany jako VARCHAR(20), wartości z {@link BreakStatus}. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
