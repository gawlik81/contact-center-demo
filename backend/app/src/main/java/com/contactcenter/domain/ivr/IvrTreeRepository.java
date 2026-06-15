package com.contactcenter.domain.ivr;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium drzew IVR (tabela {@code ivr_tree}).
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie ustawia kontekst RLS.
 * Zapis przez natywny SQL (spójność z pozostałymi repozytoriami).
 */
@Slf4j
@Repository
public class IvrTreeRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera drzewo IVR po identyfikatorze z weryfikacją tenanta.
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return Optional z drzewem IVR lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<IvrTree> findByIvrIdAndTenantId(UUID ivrId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<IvrTree> results = em.createQuery(
                        "SELECT t FROM IvrTree t WHERE t.ivrId = :ivrId AND t.tenantId = :tenantId",
                        IvrTree.class)
                .setParameter("ivrId", ivrId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .setHint("jakarta.persistence.query.timeout", 5000)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera wszystkie drzewa IVR dla tenanta (aktywne i nieaktywne).
     *
     * @param tenantId UUID tenanta
     * @return lista drzew IVR posortowana po nazwie
     */
    @Transactional(readOnly = true)
    public List<IvrTree> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        return em.createQuery(
                        "SELECT t FROM IvrTree t WHERE t.tenantId = :tenantId ORDER BY t.name ASC",
                        IvrTree.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * Sprawdza czy drzewo IVR o podanym identyfikatorze jest aktywne.
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return {@code true} gdy drzewo istnieje i ma {@code is_active = true}
     */
    @Transactional(readOnly = true)
    public boolean existsActiveByIvrId(UUID ivrId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createQuery(
                        "SELECT COUNT(t) FROM IvrTree t WHERE t.ivrId = :ivrId AND t.tenantId = :tenantId AND t.active = true")
                .setParameter("ivrId", ivrId)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return count.longValue() > 0;
    }

    /**
     * Pobiera aktywne drzewo IVR dla tenanta.
     *
     * <p>Wiele drzew IVR może być aktywnych jednocześnie per tenant –
     * które drzewo obsługuje ruch decydują reguły {@code phone_routing_rule}.
     *
     * @param tenantId UUID tenanta
     * @return Optional z jednym aktywnym drzewem IVR lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<IvrTree> findActiveByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        List<IvrTree> results = em.createQuery(
                        "SELECT t FROM IvrTree t WHERE t.tenantId = :tenantId AND t.active = true",
                        IvrTree.class)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .setHint("jakarta.persistence.query.timeout", 5000)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nowe drzewo IVR przez natywny INSERT.
     *
     * <p>Pole {@code definition} musi zawierać węzeł entry_node_id –
     * constraint {@code chk_ivr_definition_has_entry} w DB zweryfikuje integralność.
     *
     * @param ivr encja drzewa IVR do zapisania (ivrId musi być ustawiony)
     * @return zapisana encja
     */
    @Transactional
    public IvrTree insert(IvrTree ivr) {
        assertSameTenant(ivr.getTenantId());
        setTenantContextInDb(ivr.getTenantId());

        log.info("[IvrRepo] Tworzenie drzewa IVR: tenant={}, name={}", ivr.getTenantId(), ivr.getName());

        em.createNativeQuery("""
                INSERT INTO ivr_tree (
                    ivr_id, tenant_id, name, definition, version,
                    is_active, created_by, created_at, updated_at
                ) VALUES (
                    CAST(:ivrId AS uuid),
                    CAST(:tenantId AS uuid),
                    :name,
                    CAST(:definition AS jsonb),
                    1,
                    :isActive,
                    CAST(:createdBy AS uuid),
                    :createdAt,
                    :updatedAt
                )
                """)
                .setParameter("ivrId", ivr.getIvrId().toString())
                .setParameter("tenantId", ivr.getTenantId().toString())
                .setParameter("name", ivr.getName())
                .setParameter("definition", definitionToJson(ivr))
                .setParameter("isActive", ivr.isActive())
                .setParameter("createdBy", ivr.getCreatedBy() != null ? ivr.getCreatedBy().toString() : null)
                .setParameter("createdAt", ivr.getCreatedAt())
                .setParameter("updatedAt", ivr.getUpdatedAt())
                .executeUpdate();

        log.info("[IvrRepo] Drzewo IVR utworzone: ivrId={}, tenant={}", ivr.getIvrId(), ivr.getTenantId());
        return ivr;
    }

    /**
     * Aktualizuje istniejące drzewo IVR przez natywny UPDATE.
     *
     * <p>Trigger {@code trg_ivr_version_increment} automatycznie inkrementuje
     * wersję przy zmianie kolumny {@code definition}.
     *
     * @param ivr encja z wypełnionym ivrId i tenantId
     * @return liczba zaktualizowanych wierszy
     */
    @Transactional
    public int update(IvrTree ivr) {
        assertSameTenant(ivr.getTenantId());
        setTenantContextInDb(ivr.getTenantId());

        log.debug("[IvrRepo] Aktualizacja drzewa IVR: ivrId={}, tenant={}", ivr.getIvrId(), ivr.getTenantId());

        int updated = em.createNativeQuery("""
                UPDATE ivr_tree SET
                    name       = :name,
                    definition = CAST(:definition AS jsonb),
                    is_active  = :isActive,
                    updated_at = NOW()
                WHERE ivr_id   = CAST(:ivrId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("name", ivr.getName())
                .setParameter("definition", definitionToJson(ivr))
                .setParameter("isActive", ivr.isActive())
                .setParameter("ivrId", ivr.getIvrId().toString())
                .setParameter("tenantId", ivr.getTenantId().toString())
                .executeUpdate();

        em.flush();
        em.clear();

        log.debug("[IvrRepo] Zaktualizowano {} wierszy dla ivrId={}", updated, ivr.getIvrId());
        return updated;
    }

    /**
     * Dezaktywuje wszystkie drzewa IVR dla tenanta.
     *
     * @param tenantId UUID tenanta
     */
    @Transactional
    public void deactivateAll(UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int count = em.createNativeQuery("""
                UPDATE ivr_tree SET
                    is_active  = false,
                    updated_at = NOW()
                WHERE tenant_id = CAST(:tenantId AS uuid)
                  AND is_active = true
                """)
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.debug("[IvrRepo] Dezaktywowano {} drzew IVR dla tenant={}", count, tenantId);
    }

    /**
     * Usuwa drzewo IVR (fizyczne usunięcie – tabela nie ma is_deleted).
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return liczba usuniętych wierszy
     */
    @Transactional
    public int delete(UUID ivrId, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[IvrRepo] Usuwanie drzewa IVR: ivrId={}, tenant={}", ivrId, tenantId);

        int deleted = em.createNativeQuery("""
                DELETE FROM ivr_tree
                WHERE ivr_id    = CAST(:ivrId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("ivrId", ivrId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[IvrRepo] Usunięto {} wierszy dla ivrId={}", deleted, ivrId);
        return deleted;
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Serializuje definicję IVR do JSON dla natywnych zapytań.
     *
     * <p>Hibernate zarządza deserializacją przez {@code @JdbcTypeCode(SqlTypes.JSON)},
     * ale przy natywnym INSERT/UPDATE potrzebujemy ręcznej serializacji do String.
     * Używamy ObjectMapper wstrzykniętego przez EntityManager lub prostą refleksję.
     *
     * <p>Uproszczenie: pobieramy JSON przez em.createNativeQuery SELECT – unikamy
     * powielania ObjectMapper. Zamiast tego korzystamy z Jackson bezpośrednio.
     */
    private String definitionToJson(IvrTree ivr) {
        if (ivr.getDefinition() == null) {
            return "{\"nodes\":[], \"entry_node_id\":\"\"}";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(ivr.getDefinition());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[IvrRepo] Błąd serializacji definition do JSON dla ivrId={}: {}",
                    ivr.getIvrId(), e.getMessage());
            return "{\"nodes\":[], \"entry_node_id\":\"\"}";
        }
    }
}
