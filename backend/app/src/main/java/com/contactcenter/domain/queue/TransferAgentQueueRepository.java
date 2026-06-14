package com.contactcenter.domain.queue;

import com.contactcenter.domain.repository.TenantAwareRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Repozytorium do pobierania nazw kolejek dla listy agentów w jednym zapytaniu SQL.
 *
 * <p>Rozwiązuje problem N+1 przy budowaniu listy transferu: zamiast N zapytań
 * (jedno na agenta), wykonuje jedno zbiorcze zapytanie JOIN.
 *
 * <p>Logika przypisania agenta do kolejki:
 * <ol>
 *   <li>Bezpośrednie przypisanie: tabela {@code queue_agent}</li>
 *   <li>Przez grupę: {@code queue_agent_group} JOIN {@code agent_group_member}</li>
 *   <li>Flaga {@code all_agents = TRUE} na kolejce – agent z tego tenanta należy do wszystkich</li>
 * </ol>
 *
 * <p>Parametr {@code agentIds} jest przekazywany jako PostgreSQL array literal (np.
 * {@code {uuid1,uuid2,...}}), który JDBC mapuje na {@code uuid[]} przez {@code CAST}.
 * To jest idiomatyczny wzorzec dla natywnych zapytań JPA z listą UUID w tym projekcie.
 */
@Slf4j
@Repository
public class TransferAgentQueueRepository extends TenantAwareRepository {

    /**
     * Pobiera nazwy kolejek dla wielu agentów jednym zapytaniem SQL.
     *
     * <p>Źródła przypisania (UNION):
     * <ol>
     *   <li>queue_agent – bezpośrednie przypisanie agenta do kolejki</li>
     *   <li>queue_agent_group JOIN agent_group_member – przez grupy agentów</li>
     *   <li>queue WHERE all_agents = TRUE – kolejki otwarte dla wszystkich agentów tenanta</li>
     * </ol>
     *
     * <p>Wyniki deduplikowane per (agent_id, queue_id) przez DISTINCT w SQL.
     * Kolejki nieaktywne ({@code is_active = false}) są wykluczone.
     *
     * @param tenantId UUID tenanta (izolacja RLS)
     * @param agentIds lista UUID agentów
     * @return mapa agent_id → posortowana lista nazw kolejek; agenci bez kolejek nie są w mapie
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> findQueueNamesByAgentIds(UUID tenantId, List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Collections.emptyMap();
        }

        setTenantContextInDb(tenantId);

        log.debug("[TransferAgentQueueRepo] Pobieranie kolejek dla {} agentów, tenant={}",
                agentIds.size(), tenantId);

        // PostgreSQL array literal: {uuid1,uuid2,...} – kompatybilne z CAST(:param AS uuid[])
        String agentIdsLiteral = agentIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));

        /*
         * Zapytanie UNION trzech źródeł przypisania agenta do kolejki.
         * Kolumny wyniku: agent_id (text), queue_name (text)
         *
         * Źródło 1: bezpośrednie przypisanie przez queue_agent
         * Źródło 2: przypisanie przez grupę (queue_agent_group → agent_group_member)
         * Źródło 3: kolejki z all_agents=TRUE (agent należy do tenanta → należy do kolejki)
         *
         * DISTINCT eliminuje duplikaty gdy agent jest przypisany kilkoma ścieżkami do tej samej kolejki.
         */
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT
                    src_agent_id::text,
                    q.name
                FROM (
                    -- Źródło 1: bezpośrednie przypisanie (queue_agent)
                    SELECT
                        qa.agent_id AS src_agent_id,
                        qa.queue_id AS src_queue_id
                    FROM queue_agent qa
                    WHERE qa.agent_id = ANY(CAST(:agentIds AS uuid[]))

                    UNION

                    -- Źródło 2: przypisanie przez grupę agentów
                    SELECT
                        agm.agent_id AS src_agent_id,
                        qag.queue_id AS src_queue_id
                    FROM queue_agent_group qag
                    JOIN agent_group_member agm ON agm.group_id = qag.group_id
                    WHERE agm.agent_id = ANY(CAST(:agentIds AS uuid[]))

                    UNION

                    -- Źródło 3: kolejki otwarte dla wszystkich agentów tenanta (all_agents=TRUE)
                    SELECT
                        unnested.agent_id AS src_agent_id,
                        q_all.queue_id   AS src_queue_id
                    FROM queue q_all
                    CROSS JOIN LATERAL (
                        SELECT UNNEST(CAST(:agentIds AS uuid[])) AS agent_id
                    ) unnested
                    WHERE q_all.tenant_id = CAST(:tenantId AS uuid)
                      AND q_all.all_agents = TRUE
                      AND q_all.is_active  = TRUE
                ) src
                JOIN queue q ON q.queue_id   = src.src_queue_id
                             AND q.tenant_id = CAST(:tenantId AS uuid)
                             AND q.is_active  = TRUE
                ORDER BY src_agent_id, q.name
                """)
                .setParameter("agentIds", agentIdsLiteral)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        // Grupowanie wyników: agent_id → lista nazw kolejek (już posortowanych przez ORDER BY)
        Map<UUID, List<String>> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID agentId = UUID.fromString(row[0].toString());
            String queueName = row[1].toString();
            result.computeIfAbsent(agentId, k -> new ArrayList<>()).add(queueName);
        }

        log.debug("[TransferAgentQueueRepo] Pobrano dane kolejek dla {} agentów z wynikami",
                result.size());

        return result;
    }
}
