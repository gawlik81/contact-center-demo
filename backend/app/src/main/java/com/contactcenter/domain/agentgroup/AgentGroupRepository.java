package com.contactcenter.domain.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium grup agentów (CRUD + zarządzanie członkostwem).
 *
 * <p>Operuje na tabelach {@code agent_group} i {@code agent_group_member} (V042).
 * Wszystkie zapytania używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * z jawnym CAST dla UUID ({@code CAST(:param AS uuid)}).
 *
 * <p>Każde zapytanie odczytujące lub zapisujące poprzedzone jest wywołaniem
 * {@code setTenantContextInDb(tenantId)} w celu ustawienia kontekstu RLS w PostgreSQL.
 * Każdy zapis poprzedzony jest {@code assertSameTenant(entity.getTenantId())}.
 */
@Slf4j
@Repository
public class AgentGroupRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera paginowaną listę grup agentów tenanta.
     *
     * <p>Opcjonalny filtr {@code name} (ILIKE – case-insensitive). Wyniki
     * sortowane alfabetycznie po nazwie grupy.
     *
     * @param tenantId UUID tenanta
     * @param name     opcjonalny fragment nazwy (ILIKE), null = bez filtru
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return strona wyników opakowana w {@link PagedResponse}
     */
    @Transactional(readOnly = true)
    public PagedResponse<AgentGroup> findAllByTenantId(UUID tenantId, String name, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[AgentGroupRepo] Lista grup: tenant={}, name={}, page={}, size={}",
                tenantId, name, page, size);

        boolean hasNameFilter = name != null && !name.isBlank();

        String dataSql;
        String countSql;

        if (hasNameFilter) {
            dataSql = """
                    SELECT * FROM agent_group
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')
                    ORDER BY name ASC
                    LIMIT :size OFFSET :offset
                    """;
            countSql = """
                    SELECT COUNT(*) FROM agent_group
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')
                    """;
        } else {
            dataSql = """
                    SELECT * FROM agent_group
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                    ORDER BY name ASC
                    LIMIT :size OFFSET :offset
                    """;
            countSql = """
                    SELECT COUNT(*) FROM agent_group
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                    """;
        }

        @SuppressWarnings("unchecked")
        List<AgentGroup> content;
        long total;

        if (hasNameFilter) {
            content = em.createNativeQuery(dataSql, AgentGroup.class)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("name", name)
                    .setParameter("size", size)
                    .setParameter("offset", offset)
                    .getResultList();

            total = ((Number) em.createNativeQuery(countSql)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        } else {
            content = em.createNativeQuery(dataSql, AgentGroup.class)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("size", size)
                    .setParameter("offset", offset)
                    .getResultList();

            total = ((Number) em.createNativeQuery(countSql)
                    .setParameter("tenantId", tenantId.toString())
                    .getSingleResult()).longValue();
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        boolean first = page == 0;
        boolean last = page >= totalPages - 1;

        log.debug("[AgentGroupRepo] Znaleziono {} grup (page={}, size={}, total={})",
                content.size(), page, size, total);

        return new PagedResponse<>(content, page, size, total, totalPages, first, last);
    }

    /**
     * Pobiera grupę agentów po ID z weryfikacją przynależności do tenanta.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return Optional z grupą lub empty gdy nie istnieje / należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public Optional<AgentGroup> findByIdAndTenantId(UUID groupId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<AgentGroup> results = em.createNativeQuery("""
                SELECT * FROM agent_group
                WHERE group_id  = CAST(:groupId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """, AgentGroup.class)
                .setParameter("groupId", groupId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Sprawdza czy w danym tenancie istnieje już grupa o podanej nazwie.
     *
     * <p>Używane przed INSERT do weryfikacji unikalności (uzupełnienie
     * constraint {@code uq_agent_group_tenant_name}).
     *
     * @param name     nazwa grupy (porównanie case-sensitive, jak w constraint DB)
     * @param tenantId UUID tenanta
     * @return true jeśli istnieje co najmniej jedna grupa o tej nazwie
     */
    @Transactional(readOnly = true)
    public boolean existsByNameAndTenantId(String name, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM agent_group
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND name = CAST(:name AS TEXT)
                """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("name", name)
                .getSingleResult();

        return count.longValue() > 0;
    }

    // =========================================================================
    // Zarządzanie członkostwem (agent_group_member)
    // =========================================================================

    /**
     * Pobiera listę UUID agentów należących do danej grupy.
     *
     * <p>Weryfikacja przynależności do tenanta przez JOIN z {@code agent_group},
     * który jest objęty RLS.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return lista UUID agentów (może być pusta)
     */
    @Transactional(readOnly = true)
    public List<UUID> findMemberIds(UUID groupId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object> rawResults = em.createNativeQuery("""
                SELECT agm.agent_id
                FROM agent_group_member agm
                JOIN agent_group ag ON ag.group_id = agm.group_id
                WHERE agm.group_id  = CAST(:groupId AS uuid)
                  AND ag.tenant_id  = CAST(:tenantId AS uuid)
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rawResults.stream()
                .map(r -> UUID.fromString(r.toString()))
                .toList();
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową grupę agentów przez natywny INSERT.
     *
     * <p>Gdy {@code group.groupId} jest null, baza danych generuje UUID
     * przez {@code gen_random_uuid()}. Po INSERT encja jest ponownie odczytana
     * i zwrócona ze wszystkimi polami uzupełnionymi przez DB.
     *
     * @param group encja grupy do zapisania
     * @return zapisana encja z wypełnionym groupId i timestamps
     */
    @Transactional
    public AgentGroup insert(AgentGroup group) {
        assertSameTenant(group.getTenantId());
        setTenantContextInDb(group.getTenantId());

        log.info("[AgentGroupRepo] Tworzenie grupy: tenant={}, name={}",
                group.getTenantId(), group.getName());

        String groupIdParam = group.getGroupId() != null
                ? group.getGroupId().toString()
                : null;

        // Używamy COALESCE – gdy groupId jest null, DB generuje go przez gen_random_uuid()
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO agent_group (group_id, tenant_id, name, created_at, updated_at)
                VALUES (
                    COALESCE(CAST(:groupId AS uuid), gen_random_uuid()),
                    CAST(:tenantId AS uuid),
                    :name,
                    NOW(),
                    NOW()
                )
                RETURNING group_id, tenant_id, name, created_at, updated_at
                """)
                .setParameter("groupId", groupIdParam)
                .setParameter("tenantId", group.getTenantId().toString())
                .setParameter("name", group.getName())
                .getResultList();

        Object[] row = rows.get(0);
        AgentGroup saved = mapRow(row);

        log.info("[AgentGroupRepo] Grupa utworzona: groupId={}, tenant={}",
                saved.getGroupId(), saved.getTenantId());

        return saved;
    }

    /**
     * Aktualizuje nazwę grupy agentów.
     *
     * <p>Aktualizuje tylko pole {@code name}; {@code updated_at} jest
     * ustawiane przez {@code NOW()} bezpośrednio w SQL.
     *
     * @param group encja grupy z wypełnionym groupId, tenantId i name
     * @return liczba zaktualizowanych wierszy (0 = grupa nie istnieje)
     */
    @Transactional
    public int update(AgentGroup group) {
        assertSameTenant(group.getTenantId());
        em.detach(group);
        setTenantContextInDb(group.getTenantId());

        log.debug("[AgentGroupRepo] Aktualizacja grupy: groupId={}, tenant={}",
                group.getGroupId(), group.getTenantId());

        int updated = em.createNativeQuery("""
                UPDATE agent_group
                SET name       = :name,
                    updated_at = NOW()
                WHERE group_id  = CAST(:groupId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("name", group.getName())
                .setParameter("groupId", group.getGroupId().toString())
                .setParameter("tenantId", group.getTenantId().toString())
                .executeUpdate();

        log.debug("[AgentGroupRepo] Zaktualizowano {} wierszy dla groupId={}", updated, group.getGroupId());

        em.flush();
        em.clear();

        return updated;
    }

    /**
     * Fizycznie usuwa grupę agentów.
     *
     * <p>Kaskada w DB (ON DELETE CASCADE) automatycznie usuwa powiązane
     * rekordy z {@code agent_group_member} i {@code queue_agent_group}.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return liczba usuniętych wierszy (0 = grupa nie istnieje)
     */
    @Transactional
    public int delete(UUID groupId, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[AgentGroupRepo] Usuwanie grupy: groupId={}, tenant={}", groupId, tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM agent_group
                WHERE group_id  = CAST(:groupId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[AgentGroupRepo] Usunięto {} wierszy dla groupId={}", deleted, groupId);
        return deleted;
    }

    // =========================================================================
    // Zarządzanie członkostwem – operacje zapisu
    // =========================================================================

    /**
     * Dodaje agenta do grupy.
     *
     * <p>Operacja idempotentna – {@code ON CONFLICT DO NOTHING} zapewnia,
     * że dwukrotne wywołanie nie rzuca wyjątku.
     *
     * @param groupId UUID grupy
     * @param agentId UUID agenta (app_user)
     */
    @Transactional
    public void addMember(UUID groupId, UUID agentId) {
        log.debug("[AgentGroupRepo] Dodawanie agenta do grupy: groupId={}, agentId={}",
                groupId, agentId);

        em.createNativeQuery("""
                INSERT INTO agent_group_member (group_id, agent_id, assigned_at)
                VALUES (
                    CAST(:groupId AS uuid),
                    CAST(:agentId AS uuid),
                    NOW()
                )
                ON CONFLICT DO NOTHING
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("agentId", agentId.toString())
                .executeUpdate();
    }

    /**
     * Usuwa agenta z grupy.
     *
     * @param groupId UUID grupy
     * @param agentId UUID agenta
     */
    @Transactional
    public void removeMember(UUID groupId, UUID agentId) {
        log.debug("[AgentGroupRepo] Usuwanie agenta z grupy: groupId={}, agentId={}",
                groupId, agentId);

        em.createNativeQuery("""
                DELETE FROM agent_group_member
                WHERE group_id = CAST(:groupId AS uuid)
                  AND agent_id = CAST(:agentId AS uuid)
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("agentId", agentId.toString())
                .executeUpdate();
    }

    /**
     * Atomowa podmiana wszystkich członków grupy.
     *
     * <p>Usuwa istniejące powiązania i wstawia nowy zestaw w jednej transakcji.
     * Weryfikacja przynależności grupy do tenanta odbywa się przez JOIN z
     * {@code agent_group} objętym RLS – zapobiega modyfikacji grup obcego tenanta.
     *
     * @param groupId   UUID grupy
     * @param tenantId  UUID tenanta
     * @param agentIds  nowy zestaw UUID agentów (może być pusty)
     */
    @Transactional
    public void replaceMembers(UUID groupId, UUID tenantId, List<UUID> agentIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[AgentGroupRepo] Podmiana członków grupy: groupId={}, tenant={}, count={}",
                groupId, tenantId, agentIds.size());

        // Usuń wszystkich istniejących członków (weryfikacja tenant przez podzapytanie)
        em.createNativeQuery("""
                DELETE FROM agent_group_member
                WHERE group_id = CAST(:groupId AS uuid)
                  AND EXISTS (
                      SELECT 1 FROM agent_group ag
                      WHERE ag.group_id  = CAST(:groupId AS uuid)
                        AND ag.tenant_id = CAST(:tenantId AS uuid)
                  )
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        // Wstaw nowy zestaw agentów
        for (UUID agentId : agentIds) {
            em.createNativeQuery("""
                    INSERT INTO agent_group_member (group_id, agent_id, assigned_at)
                    VALUES (
                        CAST(:groupId AS uuid),
                        CAST(:agentId AS uuid),
                        NOW()
                    )
                    ON CONFLICT DO NOTHING
                    """)
                    .setParameter("groupId", groupId.toString())
                    .setParameter("agentId", agentId.toString())
                    .executeUpdate();
        }

        log.info("[AgentGroupRepo] Podmiana zakończona: groupId={}, nowych członków={}",
                groupId, agentIds.size());
    }

    // =========================================================================
    // Zliczanie członków
    // =========================================================================

    /**
     * Zlicza liczbę agentów należących do danej grupy.
     *
     * <p>Weryfikacja przynależności grupy do tenanta odbywa się przez JOIN
     * z tabelą {@code agent_group} objętą RLS. Zapobiega to cross-tenant
     * odczytowi liczby członków grupy innego tenanta.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return liczba agentów w grupie
     */
    @Transactional(readOnly = true)
    public long countMembers(UUID groupId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*)
                FROM agent_group_member agm
                JOIN agent_group ag ON ag.group_id = agm.group_id
                WHERE agm.group_id  = CAST(:groupId AS uuid)
                  AND ag.tenant_id  = CAST(:tenantId AS uuid)
                """)
                .setParameter("groupId", groupId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz zwrócony przez RETURNING na encję AgentGroup.
     *
     * <p>Kolejność kolumn w RETURNING: group_id, tenant_id, name, created_at, updated_at.
     */
    private AgentGroup mapRow(Object[] row) {
        AgentGroup ag = new AgentGroup();
        ag.setGroupId(UUID.fromString(row[0].toString()));
        ag.setTenantId(UUID.fromString(row[1].toString()));
        ag.setName(row[2].toString());
        ag.setCreatedAt(((java.sql.Timestamp) row[3]).toInstant());
        ag.setUpdatedAt(((java.sql.Timestamp) row[4]).toInstant());
        return ag;
    }
}
