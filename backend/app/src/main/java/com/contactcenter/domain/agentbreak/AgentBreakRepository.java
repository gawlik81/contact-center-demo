package com.contactcenter.domain.agentbreak;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium przerw agentów (CRUD + zmiana statusu).
 *
 * <p>Operuje na tabeli {@code agent_break} (V048).
 * Wszystkie zapytania używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * z jawnym CAST dla UUID ({@code CAST(:param AS uuid)}).
 *
 * <p>Każde zapytanie odczytujące lub zapisujące poprzedzone jest wywołaniem
 * {@code setTenantContextInDb(tenantId)} w celu ustawienia kontekstu RLS w PostgreSQL.
 * Każdy zapis poprzedzony jest {@code assertSameTenant(entity.getTenantId())}.
 */
@Slf4j
@Repository
public class AgentBreakRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera przerwę po ID z weryfikacją przynależności do tenanta.
     *
     * @param id       UUID przerwy
     * @param tenantId UUID tenanta
     * @return Optional z przerwą lub empty gdy nie istnieje / należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public Optional<AgentBreak> findByIdAndTenantId(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, agent_id, start_time, end_time,
                       break_type, notes, status, created_at, updated_at
                FROM agent_break
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Pobiera listę przerw agenta w podanym zakresie czasowym.
     *
     * <p>Filtruje przerwy, których {@code start_time} mieści się w przedziale
     * {@code [from, to]}. Wyniki sortowane po {@code start_time ASC}.
     *
     * @param agentId  UUID agenta
     * @param tenantId UUID tenanta
     * @param from     początek zakresu (włącznie)
     * @param to       koniec zakresu (włącznie)
     * @return lista przerw agenta posortowana po start_time ASC (może być pusta)
     */
    @Transactional(readOnly = true)
    public List<AgentBreak> findByAgentIdAndStartTimeBetween(
            UUID agentId, UUID tenantId, Instant from, Instant to) {
        setTenantContextInDb(tenantId);

        log.debug("[AgentBreakRepo] Lista przerw agenta: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, from, to);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, agent_id, start_time, end_time,
                       break_type, notes, status, created_at, updated_at
                FROM agent_break
                WHERE agent_id  = CAST(:agentId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND start_time BETWEEN CAST(:from AS timestamptz) AND CAST(:to AS timestamptz)
                ORDER BY start_time ASC
                """)
                .setParameter("agentId", agentId.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("from", from.toString())
                .setParameter("to", to.toString())
                .getResultList();

        List<AgentBreak> result = rows.stream().map(this::mapRow).toList();

        log.debug("[AgentBreakRepo] Znaleziono {} przerw dla agentId={}", result.size(), agentId);

        return result;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową przerwę agenta przez natywny INSERT z RETURNING.
     *
     * <p>Identyfikator UUID jest generowany przez bazę danych ({@code uuid_generate_v4()}).
     * Po INSERT encja jest zwracana ze wszystkimi polami uzupełnionymi przez DB.
     *
     * @param agentBreak encja przerwy do zapisania (id może być null – DB wygeneruje)
     * @return zapisana encja z wypełnionym id i created_at
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenant_id encji != kontekst
     */
    @Transactional
    public AgentBreak insert(AgentBreak agentBreak) {
        assertSameTenant(agentBreak.getTenantId());
        setTenantContextInDb(agentBreak.getTenantId());

        log.info("[AgentBreakRepo] Tworzenie przerwy: tenant={}, agentId={}, type={}, status={}",
                agentBreak.getTenantId(), agentBreak.getAgentId(),
                agentBreak.getBreakType(), agentBreak.getStatus());

        String idParam = agentBreak.getId() != null ? agentBreak.getId().toString() : null;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO agent_break (id, tenant_id, agent_id, start_time, end_time,
                                        break_type, notes, status, created_at)
                VALUES (
                    COALESCE(CAST(:id AS uuid), uuid_generate_v4()),
                    CAST(:tenantId AS uuid),
                    CAST(:agentId AS uuid),
                    CAST(:startTime AS timestamptz),
                    CAST(:endTime AS timestamptz),
                    :breakType,
                    :notes,
                    :status,
                    NOW()
                )
                RETURNING id, tenant_id, agent_id, start_time, end_time,
                          break_type, notes, status, created_at, updated_at
                """)
                .setParameter("id", idParam)
                .setParameter("tenantId", agentBreak.getTenantId().toString())
                .setParameter("agentId", agentBreak.getAgentId().toString())
                .setParameter("startTime", agentBreak.getStartTime().toString())
                .setParameter("endTime", agentBreak.getEndTime().toString())
                .setParameter("breakType", agentBreak.getBreakType())
                .setParameter("notes", agentBreak.getNotes())
                .setParameter("status", agentBreak.getStatus())
                .getResultList();

        AgentBreak saved = mapRow(rows.get(0));

        log.info("[AgentBreakRepo] Przerwa utworzona: id={}, tenant={}",
                saved.getId(), saved.getTenantId());

        return saved;
    }

    /**
     * Aktualizuje pola edytowalne przerwy agenta.
     *
     * <p>Aktualizuje: {@code start_time}, {@code end_time}, {@code break_type},
     * {@code notes}, {@code status}. Pole {@code updated_at} ustawiane przez {@code NOW()}.
     *
     * @param agentBreak encja przerwy z wypełnionym id i tenantId
     * @return liczba zaktualizowanych wierszy (0 = przerwa nie istnieje)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenant_id encji != kontekst
     */
    @Transactional
    public int update(AgentBreak agentBreak) {
        assertSameTenant(agentBreak.getTenantId());
        em.detach(agentBreak);
        setTenantContextInDb(agentBreak.getTenantId());

        log.debug("[AgentBreakRepo] Aktualizacja przerwy: id={}, tenant={}",
                agentBreak.getId(), agentBreak.getTenantId());

        int updated = em.createNativeQuery("""
                UPDATE agent_break
                SET start_time = CAST(:startTime AS timestamptz),
                    end_time   = CAST(:endTime AS timestamptz),
                    break_type = :breakType,
                    notes      = :notes,
                    status     = :status,
                    updated_at = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("startTime", agentBreak.getStartTime().toString())
                .setParameter("endTime", agentBreak.getEndTime().toString())
                .setParameter("breakType", agentBreak.getBreakType())
                .setParameter("notes", agentBreak.getNotes())
                .setParameter("status", agentBreak.getStatus())
                .setParameter("id", agentBreak.getId().toString())
                .setParameter("tenantId", agentBreak.getTenantId().toString())
                .executeUpdate();

        log.debug("[AgentBreakRepo] Zaktualizowano {} wierszy dla id={}", updated, agentBreak.getId());

        em.flush();
        em.clear();

        return updated;
    }

    /**
     * Atomowa zmiana statusu przerwy agenta.
     *
     * <p>Używana przez logikę biznesową do przejść statusowych
     * (np. PLANNED → ACTIVE, ACTIVE → COMPLETED, * → CANCELLED).
     * Ustawia również {@code updated_at = NOW()}.
     *
     * @param id        UUID przerwy
     * @param tenantId  UUID tenanta
     * @param newStatus nowy status (wartość tekstowa z {@link BreakStatus})
     * @return liczba zaktualizowanych wierszy (0 = przerwa nie istnieje)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public int updateStatus(UUID id, UUID tenantId, String newStatus) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[AgentBreakRepo] Zmiana statusu przerwy: id={}, tenant={}, newStatus={}",
                id, tenantId, newStatus);

        int updated = em.createNativeQuery("""
                UPDATE agent_break
                SET status     = :newStatus,
                    updated_at = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("newStatus", newStatus)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.debug("[AgentBreakRepo] Zmiana statusu: zaktualizowano {} wierszy dla id={}", updated, id);

        return updated;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz wynikowy na encję {@link AgentBreak}.
     *
     * <p>Kolejność kolumn (zgodna z SELECT/RETURNING w metodach repozytorium):
     * id, tenant_id, agent_id, start_time, end_time, break_type, notes, status, created_at, updated_at.
     */
    private AgentBreak mapRow(Object[] row) {
        AgentBreak ab = new AgentBreak();
        ab.setId(UUID.fromString(row[0].toString()));
        ab.setTenantId(UUID.fromString(row[1].toString()));
        ab.setAgentId(UUID.fromString(row[2].toString()));
        ab.setStartTime(toInstant(row[3]));
        ab.setEndTime(toInstant(row[4]));
        ab.setBreakType(row[5].toString());
        ab.setNotes(row[6] != null ? row[6].toString() : null);
        ab.setStatus(row[7].toString());
        ab.setCreatedAt(toInstant(row[8]));
        ab.setUpdatedAt(row[9] != null ? toInstant(row[9]) : null);
        return ab;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
