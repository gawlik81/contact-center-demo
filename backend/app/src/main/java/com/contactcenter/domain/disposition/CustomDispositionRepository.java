package com.contactcenter.domain.disposition;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium własnych dyspozycji kontaktowych (CRUD).
 *
 * <p>Operuje na tabeli {@code custom_disposition} (V092).
 * Wszystkie zapytania używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * z jawnym CAST dla UUID ({@code CAST(:param AS uuid)}).
 *
 * <p>Każde zapytanie odczytujące lub zapisujące poprzedzone jest wywołaniem
 * {@code setTenantContextInDb(tenantId)} w celu ustawienia kontekstu RLS w PostgreSQL.
 * Każdy zapis poprzedzony jest {@code assertSameTenant(entity.getTenantId())}.
 */
@Slf4j
@Repository
class CustomDispositionRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt — aktywne dyspozycje (dla agenta)
    // =========================================================================

    /**
     * Pobiera aktywne dyspozycje przypisane do kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista aktywnych dyspozycji posortowana po ordinal ASC
     */
    @Transactional(readOnly = true)
    public List<CustomDisposition> findByCampaignId(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomDispositionRepo] findByCampaignId: campaign={}, tenant={}", campaignId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                       ordinal, is_active, created_at, updated_at
                FROM custom_disposition
                WHERE campaign_id = CAST(:campaignId AS uuid)
                  AND tenant_id   = CAST(:tenantId AS uuid)
                  AND is_active   = TRUE
                ORDER BY ordinal ASC
                """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    /**
     * Pobiera aktywne dyspozycje przypisane do kolejki.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return lista aktywnych dyspozycji posortowana po ordinal ASC
     */
    @Transactional(readOnly = true)
    public List<CustomDisposition> findByQueueId(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomDispositionRepo] findByQueueId: queue={}, tenant={}", queueId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                       ordinal, is_active, created_at, updated_at
                FROM custom_disposition
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND is_active = TRUE
                ORDER BY ordinal ASC
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    // =========================================================================
    // Sprawdzanie istnienia (dla logiki resolucji)
    // =========================================================================

    /**
     * Sprawdza czy istnieją aktywne dyspozycje dla danej kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return true jeśli istnieje co najmniej jedna aktywna dyspozycja
     */
    @Transactional(readOnly = true)
    public boolean existsByCampaignId(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM custom_disposition
                WHERE campaign_id = CAST(:campaignId AS uuid)
                  AND tenant_id   = CAST(:tenantId AS uuid)
                  AND is_active   = TRUE
                """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    /**
     * Sprawdza czy istnieją aktywne dyspozycje dla danej kolejki.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return true jeśli istnieje co najmniej jedna aktywna dyspozycja
     */
    @Transactional(readOnly = true)
    public boolean existsByQueueId(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM custom_disposition
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND is_active = TRUE
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    /**
     * Pobiera dyspozycję po ID z weryfikacją przynależności do tenanta.
     *
     * @param id       UUID dyspozycji
     * @param tenantId UUID tenanta
     * @return Optional z dyspozycją lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<CustomDisposition> findByIdAndTenantId(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                       ordinal, is_active, created_at, updated_at
                FROM custom_disposition
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    // =========================================================================
    // Odczyt — wszystkie dyspozycje (dla supervisora)
    // =========================================================================

    /**
     * Pobiera wszystkie dyspozycje (włącznie z nieaktywnymi) dla kampanii — widok supervisora.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista wszystkich dyspozycji posortowana po ordinal ASC
     */
    @Transactional(readOnly = true)
    public List<CustomDisposition> findAllByCampaignId(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomDispositionRepo] findAllByCampaignId: campaign={}, tenant={}", campaignId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                       ordinal, is_active, created_at, updated_at
                FROM custom_disposition
                WHERE campaign_id = CAST(:campaignId AS uuid)
                  AND tenant_id   = CAST(:tenantId AS uuid)
                ORDER BY ordinal ASC
                """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    /**
     * Pobiera wszystkie dyspozycje (włącznie z nieaktywnymi) dla kolejki — widok supervisora.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return lista wszystkich dyspozycji posortowana po ordinal ASC
     */
    @Transactional(readOnly = true)
    public List<CustomDisposition> findAllByQueueId(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CustomDispositionRepo] findAllByQueueId: queue={}, tenant={}", queueId, tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                       ordinal, is_active, created_at, updated_at
                FROM custom_disposition
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                ORDER BY ordinal ASC
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    // =========================================================================
    // Sprawdzanie duplikatu kodu (dla walidacji CRUD)
    // =========================================================================

    /**
     * Sprawdza czy kod dyspozycji już istnieje dla danej kampanii (dowolny status).
     *
     * @param campaignId      UUID kampanii
     * @param dispositionCode kod dyspozycji
     * @param tenantId        UUID tenanta
     * @return true jeśli kod już istnieje
     */
    @Transactional(readOnly = true)
    public boolean existsCodeForCampaign(UUID campaignId, String dispositionCode, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM custom_disposition
                WHERE campaign_id      = CAST(:campaignId AS uuid)
                  AND tenant_id        = CAST(:tenantId AS uuid)
                  AND disposition_code = CAST(:code AS TEXT)
                """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("code", dispositionCode)
                .getSingleResult();

        return count.longValue() > 0;
    }

    /**
     * Sprawdza czy kod dyspozycji już istnieje dla danej kolejki (dowolny status).
     *
     * @param queueId         UUID kolejki
     * @param dispositionCode kod dyspozycji
     * @param tenantId        UUID tenanta
     * @return true jeśli kod już istnieje
     */
    @Transactional(readOnly = true)
    public boolean existsCodeForQueue(UUID queueId, String dispositionCode, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM custom_disposition
                WHERE queue_id         = CAST(:queueId AS uuid)
                  AND tenant_id        = CAST(:tenantId AS uuid)
                  AND disposition_code = CAST(:code AS TEXT)
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("code", dispositionCode)
                .getSingleResult();

        return count.longValue() > 0;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową dyspozycję przez natywny INSERT ... RETURNING.
     *
     * <p>UUID generowany przez {@code gen_random_uuid()} w bazie danych.
     * Timestampy ustawiane przez {@code NOW()} w SQL.
     *
     * @param d encja dyspozycji do zapisania
     * @return zapisana encja z uzupełnionym id i timestamps
     */
    @Transactional
    public CustomDisposition insert(CustomDisposition d) {
        assertSameTenant(d.getTenantId());
        setTenantContextInDb(d.getTenantId());

        log.info("[CustomDispositionRepo] INSERT dyspozycja: tenant={}, code={}, campaign={}, queue={}",
                d.getTenantId(), d.getDispositionCode(), d.getCampaignId(), d.getQueueId());

        String campaignIdParam = d.getCampaignId() != null ? d.getCampaignId().toString() : null;
        String queueIdParam    = d.getQueueId()    != null ? d.getQueueId().toString()    : null;

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO custom_disposition
                    (id, tenant_id, campaign_id, queue_id, disposition_code, label, tone, ordinal, is_active, created_at, updated_at)
                VALUES (
                    gen_random_uuid(),
                    CAST(:tenantId AS uuid),
                    CAST(:campaignId AS uuid),
                    CAST(:queueId AS uuid),
                    :dispositionCode,
                    :label,
                    :tone,
                    :ordinal,
                    TRUE,
                    NOW(),
                    NOW()
                )
                RETURNING id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                          ordinal, is_active, created_at, updated_at
                """)
                .setParameter("tenantId", d.getTenantId().toString())
                .setParameter("campaignId", campaignIdParam)
                .setParameter("queueId", queueIdParam)
                .setParameter("dispositionCode", d.getDispositionCode())
                .setParameter("label", d.getLabel())
                .setParameter("tone", d.getTone())
                .setParameter("ordinal", d.getOrdinal())
                .getResultList();

        CustomDisposition saved = mapRow(rows.get(0));

        log.info("[CustomDispositionRepo] Dyspozycja utworzona: id={}, tenant={}", saved.getId(), saved.getTenantId());

        return saved;
    }

    /**
     * Aktualizuje dyspozycję przez natywny UPDATE ... RETURNING.
     *
     * <p>Aktualizuje pola: {@code label}, {@code tone}, {@code ordinal}, {@code is_active}.
     * {@code disposition_code} jest niezmienny po utworzeniu.
     *
     * <p>Zwraca {@code Optional.empty()} gdy rekord zniknął między odczytem a UPDATE
     * (np. concurrent DELETE) — zabezpiecza przed race condition i {@code IndexOutOfBoundsException}.
     *
     * @param d encja dyspozycji z wypełnionym id, tenantId i zaktualizowanymi polami
     * @return Optional z zaktualizowaną encją lub empty gdy rekord nie istnieje
     */
    @Transactional
    public Optional<CustomDisposition> update(CustomDisposition d) {
        assertSameTenant(d.getTenantId());
        em.detach(d);
        setTenantContextInDb(d.getTenantId());

        log.debug("[CustomDispositionRepo] UPDATE dyspozycja: id={}, tenant={}", d.getId(), d.getTenantId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                UPDATE custom_disposition
                SET label      = :label,
                    tone       = :tone,
                    ordinal    = :ordinal,
                    is_active  = :isActive,
                    updated_at = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                RETURNING id, tenant_id, campaign_id, queue_id, disposition_code, label, tone,
                          ordinal, is_active, created_at, updated_at
                """)
                .setParameter("label", d.getLabel())
                .setParameter("tone", d.getTone())
                .setParameter("ordinal", d.getOrdinal())
                .setParameter("isActive", d.isActive())
                .setParameter("id", d.getId().toString())
                .setParameter("tenantId", d.getTenantId().toString())
                .getResultList();

        if (rows.isEmpty()) {
            log.warn("[CustomDispositionRepo] UPDATE nie znalazł rekordu (concurrent DELETE?): id={}", d.getId());
            return Optional.empty();
        }

        CustomDisposition updated = mapRow(rows.get(0));

        log.debug("[CustomDispositionRepo] Dyspozycja zaktualizowana: id={}", updated.getId());

        return Optional.of(updated);
    }

    /**
     * Fizycznie usuwa dyspozycję.
     *
     * @param id       UUID dyspozycji
     * @param tenantId UUID tenanta
     * @return liczba usuniętych wierszy (0 = dyspozycja nie istnieje)
     */
    @Transactional
    public int delete(UUID id, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[CustomDispositionRepo] DELETE dyspozycja: id={}, tenant={}", id, tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM custom_disposition
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[CustomDispositionRepo] Usunięto {} wierszy dla id={}", deleted, id);
        return deleted;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz zwrócony przez natywne zapytanie na encję CustomDisposition.
     *
     * <p>Kolejność kolumn: id, tenant_id, campaign_id, queue_id, disposition_code,
     * label, tone, ordinal, is_active, created_at, updated_at.
     *
     * <p>Kolumny {@code campaign_id} i {@code queue_id} są nullable.
     */
    private CustomDisposition mapRow(Object[] row) {
        CustomDisposition d = new CustomDisposition();
        d.setId(UUID.fromString(row[0].toString()));
        d.setTenantId(UUID.fromString(row[1].toString()));
        d.setCampaignId(row[2] != null ? UUID.fromString(row[2].toString()) : null);
        d.setQueueId(row[3] != null ? UUID.fromString(row[3].toString()) : null);
        d.setDispositionCode(row[4].toString());
        d.setLabel(row[5].toString());
        d.setTone(row[6].toString());
        d.setOrdinal(((Number) row[7]).intValue());
        d.setActive((Boolean) row[8]);
        d.setCreatedAt(toInstant(row[9]));
        d.setUpdatedAt(row[10] != null ? toInstant(row[10]) : null);
        return d;
    }

    private static java.time.Instant toInstant(Object value) {
        if (value instanceof java.time.Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
