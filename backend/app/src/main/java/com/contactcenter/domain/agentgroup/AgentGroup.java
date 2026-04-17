package com.contactcenter.domain.agentgroup;

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
 * Encja domenowa reprezentująca grupę agentów.
 *
 * <p>Mapuje tabelę {@code agent_group} (V042). Grupy umożliwiają przypisanie
 * zestawu agentów do kolejki bez konieczności enumerowania każdego agenta osobno.
 *
 * <p>Izolacja multi-tenant przez RLS ({@code current_setting('app.current_tenant_id')}).
 * Unikalność nazwy grupy gwarantowana przez constraint {@code uq_agent_group_tenant_name}.
 *
 * <p>Zapis i aktualizacja wyłącznie przez natywny SQL w {@link AgentGroupRepository}.
 * Timestampy {@code createdAt}/{@code updatedAt} są zarządzane przez SQL (INSERT/UPDATE).
 */
@Entity
@Table(name = "agent_group")
@Getter
@Setter
@NoArgsConstructor
public class AgentGroup {

    @Id
    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
