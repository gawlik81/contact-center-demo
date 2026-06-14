package com.contactcenter.domain.campaign;

import com.contactcenter.domain.repository.TenantAwareRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Repozytorium zarządzające przypisaniem agentów i grup do kampanii (BE-080).
 *
 * <p>Analogiczne do {@link QueueAssignmentRepository} – obsługuje relacje przypisania
 * ({@code campaign_agent}, {@code campaign_agent_group}, {@code agent_group_member})
 * oraz flagę {@code all_agents} w tabeli {@code campaign}.
 *
 * <p>Tabele (migracja V062):
 * <ul>
 *   <li>{@code campaign.all_agents} – flaga: TRUE = wszyscy agenci tenanta,
 *       FALSE = tylko jawnie przypisani</li>
 *   <li>{@code campaign_agent} – bezpośrednie przypisanie agenta do kampanii</li>
 *   <li>{@code campaign_agent_group} – przypisanie grupy do kampanii</li>
 *   <li>{@code agent_group_member} – przynależność agenta do grupy</li>
 * </ul>
 */
@Slf4j
@Repository
class CampaignAssignmentRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt – flaga all_agents
    // =========================================================================

    /**
     * Sprawdza flagę {@code all_agents} dla podanej kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return true gdy flaga all_agents = TRUE
     */
    @Transactional(readOnly = true)
    public boolean isAllAgents(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignAssignmentRepo] Odczyt flagi all_agents: campaignId={}, tenant={}",
                campaignId, tenantId);

        Number result = (Number) em.createNativeQuery("""
                SELECT CASE WHEN all_agents THEN 1 ELSE 0 END
                FROM campaign
                WHERE campaign_id = CAST(:campaignId AS uuid)
                  AND tenant_id   = CAST(:tenantId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return result != null && result.intValue() == 1;
    }

    // =========================================================================
    // Odczyt – bezpośrednio przypisani agenci (campaign_agent)
    // =========================================================================

    /**
     * Zwraca listę UUID agentów bezpośrednio przypisanych do kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista UUID agentów; pusta gdy brak przypisań
     */
    @Transactional(readOnly = true)
    public List<UUID> findDirectAgentIds(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignAssignmentRepo] Pobieranie bezpośrednich agentów: campaignId={}, tenant={}",
                campaignId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT agent_id
                FROM campaign_agent
                WHERE campaign_id = CAST(:campaignId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .getResultList();

        return rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Odczyt – przypisane grupy (campaign_agent_group)
    // =========================================================================

    /**
     * Zwraca listę UUID grup przypisanych do kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista UUID grup; pusta gdy brak przypisań
     */
    @Transactional(readOnly = true)
    public List<UUID> findGroupIds(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignAssignmentRepo] Pobieranie grup kampanii: campaignId={}, tenant={}",
                campaignId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT group_id
                FROM campaign_agent_group
                WHERE campaign_id = CAST(:campaignId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .getResultList();

        return rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toList());
    }

    // =========================================================================
    // Odczyt – UNION bezpośrednich i przez grupy (kluczowe dla dialera)
    // =========================================================================

    /**
     * Zwraca pełny zbiór agentów kwalifikujących się do obsługi kampanii.
     *
     * <p>Wykonuje UNION dwóch źródeł:
     * <ol>
     *   <li>Bezpośrednie przypisania: {@code SELECT agent_id FROM campaign_agent WHERE campaign_id = ?}</li>
     *   <li>Przypisania przez grupy:
     *       {@code campaign_agent_group JOIN agent_group_member ON group_id}</li>
     * </ol>
     *
     * <p>SQL UNION automatycznie deduplikuje wyniki.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return deduplikowany zbiór UUID agentów; pusty gdy kampania nie ma przypisań
     */
    @Transactional(readOnly = true)
    public Set<UUID> resolveEligibleAgentIds(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignAssignmentRepo] Resolving eligible agents: campaignId={}, tenant={}",
                campaignId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                SELECT agent_id
                FROM campaign_agent
                WHERE campaign_id = CAST(:campaignId AS uuid)
                UNION
                SELECT agm.agent_id
                FROM campaign_agent_group cag
                    JOIN agent_group_member agm ON agm.group_id = cag.group_id
                WHERE cag.campaign_id = CAST(:campaignId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .getResultList();

        Set<UUID> result = rows.stream()
                .map(row -> UUID.fromString(row.toString()))
                .collect(Collectors.toCollection(HashSet::new));

        log.debug("[CampaignAssignmentRepo] Znaleziono {} kwalifikujących się agentów dla campaignId={}",
                result.size(), campaignId);

        return result;
    }

    // =========================================================================
    // Odczyt – sprawdzenie czy grupa jest przypisana do kampanii
    // =========================================================================

    /**
     * Sprawdza czy podana grupa agentów jest przypisana do co najmniej jednej kampanii.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return true jeśli grupa jest przypisana do co najmniej jednej kampanii
     */
    @Transactional(readOnly = true)
    public boolean isGroupAssignedToAnyCampaign(UUID groupId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignAssignmentRepo] Sprawdzanie przypisania grupy do kampanii: groupId={}, tenant={}",
                groupId, tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*)
                FROM campaign_agent_group
                WHERE group_id = CAST(:groupId AS uuid)
                """)
                .setParameter("groupId", groupId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    // =========================================================================
    // Zapis – aktualizacja flagi all_agents
    // =========================================================================

    /**
     * Aktualizuje flagę {@code all_agents} w tabeli {@code campaign}.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @param allAgents  nowa wartość flagi
     */
    @Transactional
    public void setAllAgents(UUID campaignId, UUID tenantId, boolean allAgents) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[CampaignAssignmentRepo] Ustawianie all_agents={} dla campaignId={}, tenant={}",
                allAgents, campaignId, tenantId);

        em.createNativeQuery("""
                UPDATE campaign
                SET all_agents = :allAgents,
                    updated_at = NOW()
                WHERE campaign_id = CAST(:campaignId AS uuid)
                  AND tenant_id   = CAST(:tenantId AS uuid)
                """)
                .setParameter("allAgents", allAgents)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();
    }

    // =========================================================================
    // Zapis – atomowa podmiana bezpośrednich agentów
    // =========================================================================

    /**
     * Atomowo zastępuje listę bezpośrednio przypisanych agentów w kampanii.
     *
     * <p>Operacja: DELETE wszystkich wierszy dla {@code campaign_id}, następnie
     * batch INSERT nowych rekordów. Całość w jednej transakcji (@Transactional).
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @param agentIds   lista UUID agentów do przypisania (może być pusta)
     */
    @Transactional
    public void replaceDirectAgents(UUID campaignId, UUID tenantId, List<UUID> agentIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[CampaignAssignmentRepo] Podmiana bezpośrednich agentów: campaignId={}, tenant={}, count={}",
                campaignId, tenantId, agentIds != null ? agentIds.size() : 0);

        em.createNativeQuery("""
                DELETE FROM campaign_agent
                WHERE campaign_id = CAST(:campaignId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .executeUpdate();

        if (agentIds != null && !agentIds.isEmpty()) {
            for (UUID agentId : agentIds) {
                em.createNativeQuery("""
                        INSERT INTO campaign_agent (campaign_id, agent_id)
                        VALUES (CAST(:campaignId AS uuid), CAST(:agentId AS uuid))
                        ON CONFLICT DO NOTHING
                        """)
                        .setParameter("campaignId", campaignId.toString())
                        .setParameter("agentId", agentId.toString())
                        .executeUpdate();
            }
        }

        log.debug("[CampaignAssignmentRepo] Podmiana bezpośrednich agentów zakończona: campaignId={}", campaignId);
    }

    // =========================================================================
    // Zapis – atomowa podmiana grup
    // =========================================================================

    /**
     * Atomowo zastępuje listę grup przypisanych do kampanii.
     *
     * <p>Operacja: DELETE wszystkich wierszy dla {@code campaign_id} z tabeli
     * {@code campaign_agent_group}, następnie batch INSERT nowych rekordów.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @param groupIds   lista UUID grup do przypisania (może być pusta)
     */
    @Transactional
    public void replaceGroups(UUID campaignId, UUID tenantId, List<UUID> groupIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[CampaignAssignmentRepo] Podmiana grup kampanii: campaignId={}, tenant={}, count={}",
                campaignId, tenantId, groupIds != null ? groupIds.size() : 0);

        em.createNativeQuery("""
                DELETE FROM campaign_agent_group
                WHERE campaign_id = CAST(:campaignId AS uuid)
                """)
                .setParameter("campaignId", campaignId.toString())
                .executeUpdate();

        if (groupIds != null && !groupIds.isEmpty()) {
            for (UUID groupId : groupIds) {
                em.createNativeQuery("""
                        INSERT INTO campaign_agent_group (campaign_id, group_id)
                        VALUES (CAST(:campaignId AS uuid), CAST(:groupId AS uuid))
                        ON CONFLICT DO NOTHING
                        """)
                        .setParameter("campaignId", campaignId.toString())
                        .setParameter("groupId", groupId.toString())
                        .executeUpdate();
            }
        }

        log.debug("[CampaignAssignmentRepo] Podmiana grup zakończona: campaignId={}", campaignId);
    }

    // =========================================================================
    // Odczyt – liczba przypisań (dla badge w liście kampanii)
    // =========================================================================

    /**
     * Zwraca liczbę unikalnych agentów przypisanych do kampanii (bezpośrednio lub przez grupy).
     * Gdy {@code all_agents=true} metoda nie jest wywoływana — frontend wyświetla badge "Wszyscy agenci".
     *
     * @param campaignId UUID kampanii
     * @return liczba unikalnych agentów (deduplikacja: agent bezpośredni + w grupie liczony raz)
     */
    @Transactional(readOnly = true)
    public int countAssignments(UUID campaignId) {
        Number result = (Number) em.createNativeQuery("""
                SELECT COUNT(DISTINCT agent_id)
                FROM (
                    SELECT agent_id
                    FROM campaign_agent
                    WHERE campaign_id = CAST(:campaignId AS uuid)
                    UNION
                    SELECT agm.agent_id
                    FROM agent_group_member agm
                    JOIN campaign_agent_group cag ON agm.group_id = cag.group_id
                    WHERE cag.campaign_id = CAST(:campaignId AS uuid)
                ) unique_agents
                """)
                .setParameter("campaignId", campaignId.toString())
                .getSingleResult();
        return result != null ? result.intValue() : 0;
    }
}
