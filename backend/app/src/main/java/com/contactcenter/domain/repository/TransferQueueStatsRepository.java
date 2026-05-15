package com.contactcenter.domain.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Repozytorium statystyk kolejek na potrzeby listy transferu połączeń.
 *
 * <p>Realizuje trzy zapytania zbiorcze eliminujące N+1:
 * <ol>
 *   <li>Aktywne kolejki tenanta (queue_id, name)</li>
 *   <li>Liczba kontaktów w statusie QUEUED per kolejka (GROUP BY queue_id)</li>
 *   <li>Liczba agentów w statusie AVAILABLE per kolejka z uwzględnieniem
 *       trzech ścieżek przypisania: bezpośrednie, przez grupę, all_agents=TRUE</li>
 * </ol>
 */
@Slf4j
@Repository
public class TransferQueueStatsRepository extends TenantAwareRepository {

    /**
     * Pobiera aktywne kolejki tenanta (tylko is_active = true).
     *
     * @param tenantId UUID tenanta
     * @return lista par [queue_id::text, name] posortowanych po name
     */
    @Transactional(readOnly = true)
    public List<Object[]> findActiveQueues(UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[TransferQueueStatsRepo] Pobieranie aktywnych kolejek: tenant={}", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT q.queue_id::text, q.name
                FROM queue q
                WHERE q.tenant_id = CAST(:tenantId AS uuid)
                  AND q.is_active = true
                ORDER BY q.name
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[TransferQueueStatsRepo] Znaleziono {} aktywnych kolejek dla tenant={}",
                rows.size(), tenantId);
        return rows;
    }

    /**
     * Zlicza kontakty w statusie QUEUED per kolejka – jedno zapytanie GROUP BY.
     *
     * <p>Tabela {@code contact} jest partycjonowana, ale GROUP BY queue_id
     * działa poprawnie przez tabelę nadrzędną.
     *
     * @param tenantId UUID tenanta
     * @param queueIds lista UUID kolejek do zliczenia
     * @return mapa queue_id → liczba oczekujących kontaktów
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countWaitingContactsByQueueIds(UUID tenantId, List<UUID> queueIds) {
        if (queueIds == null || queueIds.isEmpty()) {
            return Collections.emptyMap();
        }

        setTenantContextInDb(tenantId);

        // PostgreSQL array literal: {uuid1,uuid2,...}
        String queueIdsLiteral = queueIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));

        log.debug("[TransferQueueStatsRepo] Zliczanie kontaktów QUEUED dla {} kolejek, tenant={}",
                queueIds.size(), tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.queue_id::text, COUNT(*) AS cnt
                FROM contact c
                WHERE c.tenant_id  = CAST(:tenantId AS uuid)
                  AND c.queue_id   = ANY(CAST(:queueIds AS uuid[]))
                  AND c.status     = 'QUEUED'
                  AND c.is_deleted = FALSE
                GROUP BY c.queue_id
                """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("queueIds", queueIdsLiteral)
                .getResultList();

        Map<UUID, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            UUID queueId = UUID.fromString(row[0].toString());
            int count    = ((Number) row[1]).intValue();
            result.put(queueId, count);
        }

        log.debug("[TransferQueueStatsRepo] Zliczono kontakty QUEUED: {} kolejek z wynikami", result.size());
        return result;
    }

    /**
     * Zlicza agentów w statusie AVAILABLE per kolejka – jedno zapytanie GROUP BY.
     *
     * <p>Uwzględnia trzy ścieżki przypisania agenta do kolejki (analogicznie do BE-075):
     * <ol>
     *   <li>Bezpośrednie: tabela {@code queue_agent}</li>
     *   <li>Przez grupę: {@code queue_agent_group} JOIN {@code agent_group_member}</li>
     *   <li>Flaga {@code all_agents = TRUE} na kolejce</li>
     * </ol>
     *
     * <p>Liczy tylko agentów: nieusunietych, aktywnych, z rolą AGENT i statusem AVAILABLE.
     *
     * @param tenantId UUID tenanta
     * @param queueIds lista UUID kolejek do zliczenia
     * @return mapa queue_id → liczba dostępnych agentów
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countAvailableAgentsByQueueIds(UUID tenantId, List<UUID> queueIds) {
        if (queueIds == null || queueIds.isEmpty()) {
            return Collections.emptyMap();
        }

        setTenantContextInDb(tenantId);

        String queueIdsLiteral = queueIds.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(",", "{", "}"));

        log.debug("[TransferQueueStatsRepo] Zliczanie agentów AVAILABLE dla {} kolejek, tenant={}",
                queueIds.size(), tenantId);

        /*
         * Zbiera pary (queue_id, agent_id) ze wszystkich trzech źródeł przypisania,
         * deduplikuje przez DISTINCT (agent może być przypisany wieloma ścieżkami),
         * a następnie grupuje po queue_id, licząc tylko agentów spełniających kryteria.
         */
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT src.src_queue_id::text, COUNT(DISTINCT src.src_agent_id) AS cnt
                FROM (
                    -- Źródło 1: bezpośrednie przypisanie
                    SELECT qa.queue_id AS src_queue_id, qa.agent_id AS src_agent_id
                    FROM queue_agent qa
                    WHERE qa.queue_id = ANY(CAST(:queueIds AS uuid[]))

                    UNION ALL

                    -- Źródło 2: przez grupę agentów
                    SELECT qag.queue_id AS src_queue_id, agm.agent_id AS src_agent_id
                    FROM queue_agent_group qag
                    JOIN agent_group_member agm ON agm.group_id = qag.group_id
                    WHERE qag.queue_id = ANY(CAST(:queueIds AS uuid[]))

                    UNION ALL

                    -- Źródło 3: kolejki otwarte dla wszystkich agentów tenanta (all_agents=TRUE)
                    SELECT q.queue_id AS src_queue_id, u.user_id AS src_agent_id
                    FROM queue q
                    JOIN app_user u ON u.tenant_id = q.tenant_id
                    WHERE q.queue_id    = ANY(CAST(:queueIds AS uuid[]))
                      AND q.tenant_id  = CAST(:tenantId AS uuid)
                      AND q.all_agents = TRUE
                      AND q.is_active  = TRUE
                ) src
                JOIN app_user u ON u.user_id   = src.src_agent_id
                               AND u.tenant_id = CAST(:tenantId AS uuid)
                               AND u.role      = 'AGENT'
                               AND u.status    = 'AVAILABLE'
                               AND u.is_active = TRUE
                               AND u.is_deleted = FALSE
                GROUP BY src.src_queue_id
                """)
                .setParameter("queueIds", queueIdsLiteral)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        Map<UUID, Integer> result = new HashMap<>();
        for (Object[] row : rows) {
            UUID queueId = UUID.fromString(row[0].toString());
            int count    = ((Number) row[1]).intValue();
            result.put(queueId, count);
        }

        log.debug("[TransferQueueStatsRepo] Zliczono agentów AVAILABLE: {} kolejek z wynikami", result.size());
        return result;
    }
}
