package com.contactcenter.domain.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repozytorium zarządzające przypisaniem agentów i grup do kolejek.
 *
 * <p>Separacja odpowiedzialności od {@link QueueRepository} – obsługuje wyłącznie
 * relacje przypisania ({@code queue_agent}, {@code queue_agent_group},
 * {@code agent_group_member}) oraz flagę {@code all_agents} w tabeli {@code queue}.
 *
 * <p>Kluczowa operacja dla routingu: {@link #resolveEligibleAgentIds} wykonuje
 * UNION bezpośrednich przypisań i przypisań przez grupy, deduplikując wyniki.
 *
 * <p>Tabele (migracje V043, V044):
 * <ul>
 *   <li>{@code queue.all_agents} – flaga: TRUE = wszyscy agenci tenanta,
 *       FALSE = tylko jawnie przypisani</li>
 *   <li>{@code queue_agent} – bezpośrednie przypisanie agenta do kolejki</li>
 *   <li>{@code queue_agent_group} – przypisanie grupy do kolejki</li>
 *   <li>{@code agent_group_member} – przynależność agenta do grupy</li>
 * </ul>
 */
@Slf4j
@Repository
public class QueueAssignmentRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt – flaga all_agents
    // =========================================================================

    /**
     * Sprawdza flagę {@code all_agents} dla podanej kolejki.
     *
     * <p>Gdy {@code true} silnik routingu używa wszystkich agentów tenanta.
     * Gdy {@code false} routing jest ograniczony do agentów z {@code queue_agent}
     * i {@code queue_agent_group}.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return true gdy flaga all_agents = TRUE, false w pozostałych przypadkach
     */
    @Transactional(readOnly = true)
    public boolean isAllAgents(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[QueueAssignmentRepo] Odczyt flagi all_agents: queueId={}, tenant={}",
                queueId, tenantId);

        Number result = (Number) em.createNativeQuery("""
                SELECT CASE WHEN all_agents THEN 1 ELSE 0 END
                FROM queue
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return result != null && result.intValue() == 1;
    }

    // =========================================================================
    // Odczyt – bezpośrednio przypisani agenci (queue_agent)
    // =========================================================================

    /**
     * Zwraca listę UUID agentów bezpośrednio przypisanych do kolejki
     * (tabela {@code queue_agent}).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta (izolacja RLS)
     * @return lista UUID agentów; pusta gdy brak przypisań
     */
    @Transactional(readOnly = true)
    public List<UUID> findDirectAgentIds(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[QueueAssignmentRepo] Pobieranie bezpośrednich agentów: queueId={}, tenant={}",
                queueId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT agent_id
                FROM queue_agent
                WHERE queue_id = CAST(:queueId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .getResultList();

        return rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Odczyt – przypisane grupy (queue_agent_group)
    // =========================================================================

    /**
     * Zwraca listę UUID grup przypisanych do kolejki
     * (tabela {@code queue_agent_group}).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta (izolacja RLS)
     * @return lista UUID grup; pusta gdy brak przypisań
     */
    @Transactional(readOnly = true)
    public List<UUID> findGroupIds(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[QueueAssignmentRepo] Pobieranie grup kolejki: queueId={}, tenant={}",
                queueId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT group_id
                FROM queue_agent_group
                WHERE queue_id = CAST(:queueId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .getResultList();

        return rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Odczyt – UNION bezpośrednich i przez grupy (kluczowe dla routingu)
    // =========================================================================

    /**
     * Zwraca pełny zbiór agentów kwalifikujących się do obsługi kolejki.
     *
     * <p>Wykonuje UNION dwóch źródeł:
     * <ol>
     *   <li>Bezpośrednie przypisania: {@code SELECT agent_id FROM queue_agent WHERE queue_id = ?}</li>
     *   <li>Przypisania przez grupy:
     *       {@code queue_agent_group JOIN agent_group_member ON group_id}</li>
     * </ol>
     *
     * <p>SQL UNION automatycznie deduplikuje wyniki – agent przypisany zarówno
     * bezpośrednio jak i przez grupę pojawi się w wynikach tylko raz.
     *
     * <p>Izolacja cross-tenant: {@code setTenantContextInDb} ustawia RLS w PostgreSQL.
     * Tabela {@code queue_agent} nie ma kolumny {@code tenant_id} – ochronę zapewnia
     * FK do {@code queue.queue_id} w połączeniu z RLS na tabeli {@code queue}.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return deduplikowany zbiór UUID agentów; pusty gdy kolejka nie ma przypisań
     */
    @Transactional(readOnly = true)
    public Set<UUID> resolveEligibleAgentIds(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[QueueAssignmentRepo] Resolving eligible agents: queueId={}, tenant={}",
                queueId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT agent_id
                FROM queue_agent
                WHERE queue_id = CAST(:queueId AS uuid)
                UNION
                SELECT agm.agent_id
                FROM queue_agent_group qag
                    JOIN agent_group_member agm ON agm.group_id = qag.group_id
                WHERE qag.queue_id = CAST(:queueId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .getResultList();

        Set<UUID> result = rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toCollection(HashSet::new));

        log.debug("[QueueAssignmentRepo] Znaleziono {} kwalifikujących się agentów dla queueId={}",
                result.size(), queueId);

        return result;
    }

    // =========================================================================
    // Zapis – aktualizacja flagi all_agents
    // =========================================================================

    /**
     * Aktualizuje flagę {@code all_agents} w tabeli {@code queue}.
     *
     * @param queueId   UUID kolejki
     * @param tenantId  UUID tenanta
     * @param allAgents nowa wartość flagi
     */
    @Transactional
    public void setAllAgents(UUID queueId, UUID tenantId, boolean allAgents) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[QueueAssignmentRepo] Ustawianie all_agents={} dla queueId={}, tenant={}",
                allAgents, queueId, tenantId);

        em.createNativeQuery("""
                UPDATE queue
                SET all_agents = :allAgents,
                    updated_at = NOW()
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("allAgents", allAgents)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();
    }

    // =========================================================================
    // Zapis – atomowa podmiana bezpośrednich agentów
    // =========================================================================

    /**
     * Atomowo zastępuje listę bezpośrednio przypisanych agentów w kolejce.
     *
     * <p>Operacja: DELETE wszystkich wierszy dla {@code queue_id}, następnie
     * batch INSERT nowych rekordów. Całość w jednej transakcji (@Transactional).
     *
     * <p>INSERT ... ON CONFLICT DO NOTHING zapewnia idempotentność
     * (zduplikowane UUID na liście wejściowej są ignorowane).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @param agentIds lista UUID agentów do przypisania (może być pusta)
     */
    @Transactional
    public void replaceDirectAgents(UUID queueId, UUID tenantId, List<UUID> agentIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[QueueAssignmentRepo] Podmiana bezpośrednich agentów: queueId={}, tenant={}, count={}",
                queueId, tenantId, agentIds != null ? agentIds.size() : 0);

        // DELETE – usuń wszystkie aktualne przypisania
        em.createNativeQuery("""
                DELETE FROM queue_agent
                WHERE queue_id = CAST(:queueId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .executeUpdate();

        // INSERT – batch INSERT nowych przypisań
        if (agentIds != null && !agentIds.isEmpty()) {
            for (UUID agentId : agentIds) {
                em.createNativeQuery("""
                        INSERT INTO queue_agent (queue_id, agent_id)
                        VALUES (CAST(:queueId AS uuid), CAST(:agentId AS uuid))
                        ON CONFLICT DO NOTHING
                        """)
                        .setParameter("queueId", queueId.toString())
                        .setParameter("agentId", agentId.toString())
                        .executeUpdate();
            }
        }

        log.debug("[QueueAssignmentRepo] Podmiana bezpośrednich agentów zakończona: queueId={}", queueId);
    }

    // =========================================================================
    // Zapis – atomowa podmiana grup
    // =========================================================================

    /**
     * Atomowo zastępuje listę grup przypisanych do kolejki.
     *
     * <p>Operacja: DELETE wszystkich wierszy dla {@code queue_id} z tabeli
     * {@code queue_agent_group}, następnie batch INSERT nowych rekordów.
     * Całość w jednej transakcji (@Transactional).
     *
     * <p>INSERT ... ON CONFLICT DO NOTHING zapewnia idempotentność.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @param groupIds lista UUID grup do przypisania (może być pusta)
     */
    @Transactional
    public void replaceGroups(UUID queueId, UUID tenantId, List<UUID> groupIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[QueueAssignmentRepo] Podmiana grup kolejki: queueId={}, tenant={}, count={}",
                queueId, tenantId, groupIds != null ? groupIds.size() : 0);

        // DELETE – usuń wszystkie aktualne przypisania grup
        em.createNativeQuery("""
                DELETE FROM queue_agent_group
                WHERE queue_id = CAST(:queueId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .executeUpdate();

        // INSERT – batch INSERT nowych grup
        if (groupIds != null && !groupIds.isEmpty()) {
            for (UUID groupId : groupIds) {
                em.createNativeQuery("""
                        INSERT INTO queue_agent_group (queue_id, group_id)
                        VALUES (CAST(:queueId AS uuid), CAST(:groupId AS uuid))
                        ON CONFLICT DO NOTHING
                        """)
                        .setParameter("queueId", queueId.toString())
                        .setParameter("groupId", groupId.toString())
                        .executeUpdate();
            }
        }

        log.debug("[QueueAssignmentRepo] Podmiana grup zakończona: queueId={}", queueId);
    }
}
