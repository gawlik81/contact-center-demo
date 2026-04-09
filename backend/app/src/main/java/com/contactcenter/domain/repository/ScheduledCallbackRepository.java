package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.ScheduledCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla tabeli {@code scheduled_callback}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie operacje wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb(tenantId)}.
 *
 * <p>Używa kombinacji EntityManager (dla operacji na encji JPA) i JdbcTemplate
 * (dla zapytań natywnych) – zgodnie z wzorcem stosowanym w projekcie.
 */
@Slf4j
@Repository
public class ScheduledCallbackRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScheduledCallbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera callback po ID z weryfikacją tenanta.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta
     * @return Optional z callbackiem lub empty gdy nie istnieje lub należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public Optional<ScheduledCallback> findById(UUID callbackId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        ScheduledCallback callback = em.find(ScheduledCallback.class, callbackId);
        if (callback == null || !callback.getTenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(callback);
    }

    /**
     * Zwraca stronicowaną listę callbacków tenanta w statusie PENDING.
     *
     * <p>Sortowanie: scheduledAt ASC (najwcześniejsze pierwsze).
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return lista callbacków
     */
    @Transactional(readOnly = true)
    public List<ScheduledCallback> findPendingByTenantId(UUID tenantId, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;

        @SuppressWarnings("unchecked")
        List<ScheduledCallback> results = em.createNativeQuery(
                        """
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'PENDING'
                        ORDER BY scheduled_at ASC
                        LIMIT :size OFFSET :offset
                        """,
                        ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset)
                .getResultList();

        log.debug("[CallbackRepo] Znaleziono {} callbacków PENDING (tenant={}, page={}, size={})",
                results.size(), tenantId, page, size);
        return results;
    }

    /**
     * Zlicza callbacky tenanta w statusie PENDING – do metadanych paginacji.
     *
     * @param tenantId UUID tenanta
     * @return liczba callbacków PENDING
     */
    @Transactional(readOnly = true)
    public long countPendingByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM scheduled_callback
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'PENDING'
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue();
    }

    /**
     * Pobiera listę callbacków gotowych do realizacji (scheduledAt <= teraz).
     *
     * <p>Używane przez dialer lub pg_cron do iniciowania zaplanowanych połączeń.
     *
     * @param tenantId UUID tenanta
     * @param limit    maksymalna liczba rekordów
     * @return lista callbacków do realizacji
     */
    @Transactional(readOnly = true)
    public List<ScheduledCallback> findDueCallbacks(UUID tenantId, int limit) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<ScheduledCallback> results = em.createNativeQuery(
                        """
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'PENDING'
                          AND scheduled_at <= NOW()
                        ORDER BY scheduled_at ASC
                        LIMIT :limit
                        """,
                        ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("limit", limit)
                .getResultList();

        return results;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje callback.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta.
     *
     * @param callback encja callbacku do zapisu
     * @return zapisana encja
     */
    @Transactional
    public ScheduledCallback save(ScheduledCallback callback) {
        assertSameTenant(callback.getTenantId());
        setTenantContextInDb(callback.getTenantId());

        if (callback.getCallbackId() == null) {
            em.persist(callback);
            log.info("[CallbackRepo] Utworzono callback: callbackId=<nowy>, phone={}, scheduledAt={}, tenant={}",
                    callback.getPhone(), callback.getScheduledAt(), callback.getTenantId());
            return callback;
        }

        ScheduledCallback merged = em.merge(callback);
        log.debug("[CallbackRepo] Zaktualizowano callback: callbackId={}, status={}, tenant={}",
                merged.getCallbackId(), merged.getStatus(), merged.getTenantId());
        return merged;
    }

    /**
     * Atomowa zmiana statusu callbacku z PENDING na podany status.
     *
     * <p>Używane przez {@code ScheduledCallbackExecutor} do ochrony przed double-processing.
     * Klauzula {@code AND status = 'PENDING'} gwarantuje, że tylko jeden wątek/węzeł
     * przejmie callback – pozostałe otrzymają 0 i pominą rekord.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta
     * @param newStatus  nowy status (np. PROCESSING)
     * @return liczba zaktualizowanych wierszy: 1 = sukces, 0 = inny wątek już przejął
     */
    @Transactional
    public int updateStatusIfPending(UUID callbackId, UUID tenantId, String newStatus) {
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        int updated = jdbcTemplate.update(
                """
                UPDATE scheduled_callback
                SET status = ?, updated_at = NOW()
                WHERE callback_id = ?::uuid AND tenant_id = ?::uuid AND status = 'PENDING'
                """,
                newStatus,
                callbackId.toString(),
                tenantId.toString()
        );

        if (updated > 0) {
            log.debug("[CallbackRepo] updateStatusIfPending: callbackId={}, newStatus={}", callbackId, newStatus);
        } else {
            log.debug("[CallbackRepo] updateStatusIfPending: pominięto (już przetworzone) callbackId={}", callbackId);
        }

        return updated;
    }

    /**
     * Aktualizuje status callbacku przez natywny SQL (bez potrzeby ładowania encji).
     *
     * <p>Używane przez dialer przy zmianie statusu PENDING → PROCESSING lub COMPLETED.
     *
     * @param callbackId UUID callbacku
     * @param newStatus  nowy status (PENDING, PROCESSING, COMPLETED, CANCELLED)
     * @param tenantId   UUID tenanta (walidacja cross-tenant)
     * @return liczba zaktualizowanych wierszy (0 gdy callback nie istnieje lub inny tenant)
     */
    @Transactional
    public int updateStatus(UUID callbackId, String newStatus, UUID tenantId) {
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        int updated = jdbcTemplate.update(
                """
                UPDATE scheduled_callback
                SET status = ?, updated_at = NOW()
                WHERE callback_id = ?::uuid AND tenant_id = ?::uuid
                """,
                newStatus,
                callbackId.toString(),
                tenantId.toString()
        );

        if (updated > 0) {
            log.debug("[CallbackRepo] Status callbacku zaktualizowany: callbackId={}, newStatus={}", callbackId, newStatus);
        } else {
            log.warn("[CallbackRepo] Nie znaleziono callbacku do aktualizacji: callbackId={}, tenant={}", callbackId, tenantId);
        }

        return updated;
    }
}
