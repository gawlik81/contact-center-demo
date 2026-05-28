package com.contactcenter.domain.disposition;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium zestawów dyspozycji wielokrotnego użytku (CRUD).
 *
 * <p>Operuje na tabeli {@code disposition_set} (V095).
 * Wszystkie zapytania używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * z jawnym CAST dla UUID ({@code CAST(:param AS uuid)}).
 *
 * <p>Każde zapytanie odczytujące lub zapisujące poprzedzone jest wywołaniem
 * {@code setTenantContextInDb(tenantId)} w celu ustawienia kontekstu RLS w PostgreSQL.
 * Każdy zapis poprzedzony jest {@code assertSameTenant(entity.getTenantId())}.
 */
@Slf4j
@Repository
public class DispositionSetRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wszystkie zestawy dyspozycji należące do tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista zestawów posortowana po name ASC
     */
    @Transactional(readOnly = true)
    public List<DispositionSet> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[DispositionSetRepo] findAllByTenantId: tenant={}", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, name, description, created_at, updated_at
                FROM disposition_set
                WHERE tenant_id = CAST(:tenantId AS uuid)
                ORDER BY name ASC
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    /**
     * Pobiera zestaw dyspozycji po ID z weryfikacją przynależności do tenanta.
     *
     * @param id       UUID zestawu
     * @param tenantId UUID tenanta
     * @return Optional z zestawem lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<DispositionSet> findByIdAndTenantId(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, name, description, created_at, updated_at
                FROM disposition_set
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
     * Sprawdza czy zestaw o podanej nazwie już istnieje dla tenanta.
     *
     * @param name     nazwa zestawu
     * @param tenantId UUID tenanta
     * @return true jeśli zestaw o tej nazwie już istnieje
     */
    @Transactional(readOnly = true)
    public boolean existsByNameAndTenantId(String name, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM disposition_set
                WHERE name      = CAST(:name AS TEXT)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("name", name)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    @Transactional(readOnly = true)
    public List<Object[]> findAllWithItemCountByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);
        log.debug("[DispositionSetRepo] findAllWithItemCountByTenantId: tenant={}", tenantId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.id, s.tenant_id, s.name, s.description, s.created_at, s.updated_at,
                       COUNT(i.id) AS item_count
                FROM disposition_set s
                LEFT JOIN disposition_set_item i
                       ON i.set_id = s.id AND i.tenant_id = CAST(:tenantId AS uuid)
                WHERE s.tenant_id = CAST(:tenantId AS uuid)
                GROUP BY s.id, s.tenant_id, s.name, s.description, s.created_at, s.updated_at
                ORDER BY s.name ASC
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();
        return rows;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nowy zestaw dyspozycji przez natywny INSERT ... RETURNING.
     *
     * <p>UUID generowany przez {@code gen_random_uuid()} w bazie danych.
     * Timestampy ustawiane przez {@code NOW()} w SQL.
     *
     * @param s encja zestawu do zapisania
     * @return zapisana encja z uzupełnionym id i timestamps
     */
    @Transactional
    public DispositionSet insert(DispositionSet s) {
        assertSameTenant(s.getTenantId());
        setTenantContextInDb(s.getTenantId());

        log.info("[DispositionSetRepo] INSERT zestaw: tenant={}, name={}", s.getTenantId(), s.getName());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO disposition_set
                    (id, tenant_id, name, description, created_at, updated_at)
                VALUES (
                    gen_random_uuid(),
                    CAST(:tenantId AS uuid),
                    :name,
                    :description,
                    NOW(),
                    NOW()
                )
                RETURNING id, tenant_id, name, description, created_at, updated_at
                """)
                .setParameter("tenantId", s.getTenantId().toString())
                .setParameter("name", s.getName())
                .setParameter("description", s.getDescription())
                .getResultList();

        DispositionSet saved = mapRow(rows.get(0));

        log.info("[DispositionSetRepo] Zestaw utworzony: id={}, tenant={}", saved.getId(), saved.getTenantId());

        return saved;
    }

    /**
     * Aktualizuje zestaw dyspozycji przez natywny UPDATE ... RETURNING.
     *
     * <p>Aktualizuje pola: {@code name}, {@code description}, {@code updated_at}.
     *
     * <p>Zwraca {@code Optional.empty()} gdy rekord zniknął między odczytem a UPDATE
     * (np. concurrent DELETE) — zabezpiecza przed race condition.
     *
     * @param s encja zestawu z wypełnionym id, tenantId i zaktualizowanymi polami
     * @return Optional z zaktualizowaną encją lub empty gdy rekord nie istnieje
     */
    @Transactional
    public Optional<DispositionSet> update(DispositionSet s) {
        assertSameTenant(s.getTenantId());
        setTenantContextInDb(s.getTenantId());

        log.debug("[DispositionSetRepo] UPDATE zestaw: id={}, tenant={}", s.getId(), s.getTenantId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                UPDATE disposition_set
                SET name        = :name,
                    description = :description,
                    updated_at  = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                RETURNING id, tenant_id, name, description, created_at, updated_at
                """)
                .setParameter("name", s.getName())
                .setParameter("description", s.getDescription())
                .setParameter("id", s.getId().toString())
                .setParameter("tenantId", s.getTenantId().toString())
                .getResultList();

        if (rows.isEmpty()) {
            log.warn("[DispositionSetRepo] UPDATE nie znalazł rekordu (concurrent DELETE?): id={}", s.getId());
            return Optional.empty();
        }

        DispositionSet updated = mapRow(rows.get(0));

        log.debug("[DispositionSetRepo] Zestaw zaktualizowany: id={}", updated.getId());

        return Optional.of(updated);
    }

    /**
     * Fizycznie usuwa zestaw dyspozycji.
     *
     * @param id       UUID zestawu
     * @param tenantId UUID tenanta
     * @return liczba usuniętych wierszy (0 = zestaw nie istnieje)
     */
    @Transactional
    public int delete(UUID id, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[DispositionSetRepo] DELETE zestaw: id={}, tenant={}", id, tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM disposition_set
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[DispositionSetRepo] Usunięto {} wierszy dla id={}", deleted, id);
        return deleted;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz zwrócony przez natywne zapytanie na encję DispositionSet.
     *
     * <p>Kolejność kolumn: id, tenant_id, name, description (nullable), created_at, updated_at.
     */
    private DispositionSet mapRow(Object[] row) {
        DispositionSet s = new DispositionSet();
        s.setId(UUID.fromString(row[0].toString()));
        s.setTenantId(UUID.fromString(row[1].toString()));
        s.setName(row[2].toString());
        s.setDescription(row[3] != null ? row[3].toString() : null);
        s.setCreatedAt(toInstant(row[4]));
        s.setUpdatedAt(row[5] != null ? toInstant(row[5]) : null);
        return s;
    }

    private static java.time.Instant toInstant(Object value) {
        if (value instanceof java.time.Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
