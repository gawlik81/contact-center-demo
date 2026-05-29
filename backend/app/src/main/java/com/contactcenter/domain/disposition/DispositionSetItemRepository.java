package com.contactcenter.domain.disposition;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium elementów zestawów dyspozycji (CRUD).
 *
 * <p>Operuje na tabeli {@code disposition_set_item} (V095).
 * Wszystkie zapytania używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * z jawnym CAST dla UUID ({@code CAST(:param AS uuid)}).
 *
 * <p>Każde zapytanie odczytujące lub zapisujące poprzedzone jest wywołaniem
 * {@code setTenantContextInDb(tenantId)} w celu ustawienia kontekstu RLS w PostgreSQL.
 * Każdy zapis poprzedzony jest {@code assertSameTenant(entity.getTenantId())}.
 */
@Slf4j
@Repository
public class DispositionSetItemRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wszystkie elementy zestawu posortowane po ordinal ASC.
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return lista elementów posortowana po ordinal ASC
     */
    @Transactional(readOnly = true)
    public List<DispositionSetItem> findBySetId(UUID setId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[DispositionSetItemRepo] findBySetId: setId={}, tenant={}", setId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, set_id, tenant_id, disposition_code, label, tone, ordinal
                FROM disposition_set_item
                WHERE set_id    = CAST(:setId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                ORDER BY ordinal ASC
                """)
                .setParameter("setId", setId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    /**
     * Pobiera element zestawu po ID z weryfikacją przynależności do zestawu i tenanta.
     *
     * @param id       UUID elementu
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return Optional z elementem lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<DispositionSetItem> findByIdAndSetId(UUID id, UUID setId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, set_id, tenant_id, disposition_code, label, tone, ordinal
                FROM disposition_set_item
                WHERE id        = CAST(:id AS uuid)
                  AND set_id    = CAST(:setId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """)
                .setParameter("id", id.toString())
                .setParameter("setId", setId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Sprawdza czy kod dyspozycji już istnieje w danym zestawie.
     *
     * @param code     kod dyspozycji
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return true jeśli kod już istnieje w tym zestawie
     */
    @Transactional(readOnly = true)
    public boolean existsByCodeAndSetId(String code, UUID setId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM disposition_set_item
                WHERE disposition_code = CAST(:code AS TEXT)
                  AND set_id           = CAST(:setId AS uuid)
                  AND tenant_id        = CAST(:tenantId AS uuid)
                """)
                .setParameter("code", code)
                .setParameter("setId", setId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    /**
     * Zlicza elementy w danym zestawie (używane w DTO listy zestawów).
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return liczba elementów w zestawie
     */
    @Transactional(readOnly = true)
    public int countBySetId(UUID setId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM disposition_set_item
                WHERE set_id    = CAST(:setId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("setId", setId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.intValue();
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nowy element zestawu przez natywny INSERT ... RETURNING.
     *
     * <p>UUID generowany przez {@code gen_random_uuid()} w bazie danych.
     *
     * @param item encja elementu do zapisania
     * @return zapisana encja z uzupełnionym id
     */
    @Transactional
    public DispositionSetItem insert(DispositionSetItem item) {
        assertSameTenant(item.getTenantId());
        setTenantContextInDb(item.getTenantId());

        log.info("[DispositionSetItemRepo] INSERT element: tenant={}, code={}, setId={}",
                item.getTenantId(), item.getDispositionCode(), item.getSetId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO disposition_set_item
                    (id, set_id, tenant_id, disposition_code, label, tone, ordinal)
                VALUES (
                    gen_random_uuid(),
                    CAST(:setId AS uuid),
                    CAST(:tenantId AS uuid),
                    :dispositionCode,
                    :label,
                    :tone,
                    :ordinal
                )
                RETURNING id, set_id, tenant_id, disposition_code, label, tone, ordinal
                """)
                .setParameter("setId", item.getSetId().toString())
                .setParameter("tenantId", item.getTenantId().toString())
                .setParameter("dispositionCode", item.getDispositionCode())
                .setParameter("label", item.getLabel())
                .setParameter("tone", item.getTone())
                .setParameter("ordinal", item.getOrdinal())
                .getResultList();

        DispositionSetItem saved = mapRow(rows.get(0));

        log.info("[DispositionSetItemRepo] Element utworzony: id={}, setId={}", saved.getId(), saved.getSetId());

        return saved;
    }

    /**
     * Aktualizuje element zestawu przez natywny UPDATE ... RETURNING.
     *
     * <p>Aktualizuje pola: {@code label}, {@code tone}, {@code ordinal}.
     * {@code disposition_code} jest niezmienny po utworzeniu.
     *
     * <p>Zwraca {@code Optional.empty()} gdy rekord zniknął między odczytem a UPDATE
     * (np. concurrent DELETE) — zabezpiecza przed race condition.
     *
     * @param item encja elementu z wypełnionym id i zaktualizowanymi polami
     * @return Optional z zaktualizowaną encją lub empty gdy rekord nie istnieje
     */
    @Transactional
    public Optional<DispositionSetItem> update(DispositionSetItem item) {
        assertSameTenant(item.getTenantId());
        setTenantContextInDb(item.getTenantId());

        log.debug("[DispositionSetItemRepo] UPDATE element: id={}, tenant={}", item.getId(), item.getTenantId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                UPDATE disposition_set_item
                SET label   = :label,
                    tone    = :tone,
                    ordinal = :ordinal
                WHERE id        = CAST(:id AS uuid)
                  AND set_id    = CAST(:setId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                RETURNING id, set_id, tenant_id, disposition_code, label, tone, ordinal
                """)
                .setParameter("label", item.getLabel())
                .setParameter("tone", item.getTone())
                .setParameter("ordinal", item.getOrdinal())
                .setParameter("id", item.getId().toString())
                .setParameter("setId", item.getSetId().toString())
                .setParameter("tenantId", item.getTenantId().toString())
                .getResultList();

        if (rows.isEmpty()) {
            log.warn("[DispositionSetItemRepo] UPDATE nie znalazł rekordu (concurrent DELETE?): id={}", item.getId());
            return Optional.empty();
        }

        DispositionSetItem updated = mapRow(rows.get(0));

        log.debug("[DispositionSetItemRepo] Element zaktualizowany: id={}", updated.getId());

        return Optional.of(updated);
    }

    /**
     * Fizycznie usuwa element zestawu.
     *
     * @param id       UUID elementu
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return liczba usuniętych wierszy (0 = element nie istnieje)
     */
    @Transactional
    public int delete(UUID id, UUID setId, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[DispositionSetItemRepo] DELETE element: id={}, setId={}, tenant={}", id, setId, tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM disposition_set_item
                WHERE id        = CAST(:id AS uuid)
                  AND set_id    = CAST(:setId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("id", id.toString())
                .setParameter("setId", setId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[DispositionSetItemRepo] Usunięto {} wierszy dla id={}", deleted, id);
        return deleted;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz zwrócony przez natywne zapytanie na encję DispositionSetItem.
     *
     * <p>Kolejność kolumn: id, set_id, tenant_id, disposition_code, label, tone, ordinal.
     */
    private DispositionSetItem mapRow(Object[] row) {
        DispositionSetItem item = new DispositionSetItem();
        item.setId(UUID.fromString(row[0].toString()));
        item.setSetId(UUID.fromString(row[1].toString()));
        item.setTenantId(UUID.fromString(row[2].toString()));
        item.setDispositionCode(row[3].toString());
        item.setLabel(row[4].toString());
        item.setTone(row[5].toString());
        item.setOrdinal(((Number) row[6]).intValue());
        return item;
    }
}
