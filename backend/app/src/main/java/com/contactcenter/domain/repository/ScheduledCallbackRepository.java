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
     * Stronicowana lista callbacków przypisanych do konkretnego agenta z opcjonalnym filtrem statusu.
     *
     * <p>Używana przez endpoint GET /api/dialer/callbacks dla roli AGENT – agent widzi tylko swoje callbacki.
     *
     * @param tenantId      UUID tenanta
     * @param agentId       UUID agenta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param sortDirection kierunek sortowania po scheduled_at: "ASC" lub "DESC"
     * @param page          numer strony (0-based)
     * @param size          rozmiar strony
     * @return lista callbacków agenta
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScheduledCallback> findByAgentId(
            UUID tenantId, UUID agentId, String status, String sortDirection, int page, int size) {
        setTenantContextInDb(tenantId);

        String order = "DESC".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        int offset = page * size;

        String sql = status != null
                ? String.format("""
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND agent_id  = CAST(:agentId AS uuid)
                          AND status    = :status
                        ORDER BY scheduled_at %s
                        LIMIT :size OFFSET :offset
                        """, order)
                : String.format("""
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND agent_id  = CAST(:agentId AS uuid)
                        ORDER BY scheduled_at %s
                        LIMIT :size OFFSET :offset
                        """, order);

        var query = em.createNativeQuery(sql, ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("agentId", agentId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset);

        if (status != null) {
            query.setParameter("status", status);
        }

        return query.getResultList();
    }

    /**
     * Zlicza callbacki agenta (do metadanych paginacji).
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @param status   filtr statusu (null = wszystkie statusy)
     * @return liczba callbacków
     */
    @Transactional(readOnly = true)
    public long countByAgentId(UUID tenantId, UUID agentId, String status) {
        setTenantContextInDb(tenantId);

        String sql = status != null
                ? """
                    SELECT COUNT(*) FROM scheduled_callback
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND agent_id  = CAST(:agentId AS uuid)
                      AND status    = :status
                    """
                : """
                    SELECT COUNT(*) FROM scheduled_callback
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND agent_id  = CAST(:agentId AS uuid)
                    """;

        var query = em.createNativeQuery(sql)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("agentId", agentId.toString());

        if (status != null) {
            query.setParameter("status", status);
        }

        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Stronicowana lista wszystkich callbacków tenanta z opcjonalnymi filtrami statusu i agentId.
     *
     * <p>Używana przez endpoint GET /api/dialer/callbacks dla ról SUPERVISOR/ADMIN.
     *
     * @param tenantId      UUID tenanta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param agentIdFilter filtr po agentId (null = wszyscy agenci)
     * @param sortDirection kierunek sortowania po scheduled_at: "ASC" lub "DESC"
     * @param page          numer strony (0-based)
     * @param size          rozmiar strony
     * @return lista callbacków tenanta
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScheduledCallback> findByTenantIdWithFilters(
            UUID tenantId, String status, UUID agentIdFilter, String sortDirection, int page, int size) {
        setTenantContextInDb(tenantId);

        String order = "DESC".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        int offset = page * size;

        StringBuilder sqlBuilder = new StringBuilder(String.format("""
                SELECT * FROM scheduled_callback
                WHERE tenant_id = CAST(:tenantId AS uuid)
                """));

        if (status != null) {
            sqlBuilder.append("  AND status = :status\n");
        }
        if (agentIdFilter != null) {
            sqlBuilder.append("  AND agent_id = CAST(:agentId AS uuid)\n");
        }
        sqlBuilder.append(String.format("ORDER BY scheduled_at %s\nLIMIT :size OFFSET :offset\n", order));

        var query = em.createNativeQuery(sqlBuilder.toString(), ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset);

        if (status != null) {
            query.setParameter("status", status);
        }
        if (agentIdFilter != null) {
            query.setParameter("agentId", agentIdFilter.toString());
        }

        return query.getResultList();
    }

    /**
     * Zlicza callbacki tenanta z opcjonalnymi filtrami statusu i agentId (do metadanych paginacji).
     *
     * @param tenantId      UUID tenanta
     * @param status        filtr statusu (null = wszystkie statusy)
     * @param agentIdFilter filtr po agentId (null = wszyscy agenci)
     * @return liczba callbacków
     */
    @Transactional(readOnly = true)
    public long countByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter) {
        setTenantContextInDb(tenantId);

        StringBuilder sqlBuilder = new StringBuilder("""
                SELECT COUNT(*) FROM scheduled_callback
                WHERE tenant_id = CAST(:tenantId AS uuid)
                """);

        if (status != null) {
            sqlBuilder.append("  AND status = :status\n");
        }
        if (agentIdFilter != null) {
            sqlBuilder.append("  AND agent_id = CAST(:agentId AS uuid)\n");
        }

        var query = em.createNativeQuery(sqlBuilder.toString())
                .setParameter("tenantId", tenantId.toString());

        if (status != null) {
            query.setParameter("status", status);
        }
        if (agentIdFilter != null) {
            query.setParameter("agentId", agentIdFilter.toString());
        }

        return ((Number) query.getSingleResult()).longValue();
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

    /**
     * Zwraca listę callbacków agenta w podanym zakresie czasowym (kalendarz agenta, BE-051).
     *
     * <p>Filtruje po {@code agent_id} i {@code scheduled_at BETWEEN from AND to}.
     * Wyniki sortowane po {@code scheduled_at ASC}. Używa jawnych CAST dla UUID i TIMESTAMPTZ.
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @param from     początek zakresu (włącznie)
     * @param to       koniec zakresu (włącznie)
     * @return lista callbacków agenta w zakresie dat, posortowana po scheduled_at ASC
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScheduledCallback> findByAgentIdAndScheduledAtBetween(
            UUID tenantId, UUID agentId, Instant from, Instant to) {
        setTenantContextInDb(tenantId);

        log.debug("[CallbackRepo] Kalendarz agenta: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, from, to);

        List<ScheduledCallback> results = em.createNativeQuery(
                        """
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id    = CAST(:tenantId AS uuid)
                          AND agent_id     = CAST(:agentId AS uuid)
                          AND scheduled_at BETWEEN CAST(:from AS timestamptz) AND CAST(:to AS timestamptz)
                        ORDER BY scheduled_at ASC
                        """,
                        ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("agentId", agentId.toString())
                .setParameter("from", from.toString())
                .setParameter("to", to.toString())
                .getResultList();

        log.debug("[CallbackRepo] Znaleziono {} callbacków w kalendarzu agentId={}", results.size(), agentId);
        return results;
    }

    /**
     * Zwraca listę callbacków, dla których dany kontakt jest kontaktem źródłowym.
     *
     * <p>Callback ma {@code origin_contact_id} wskazujący na kontakt, podczas którego
     * klient poprosił o oddzwonienie. Metoda ta zwraca wszystkie takie callbacki –
     * używana przez endpoint GET /api/contacts/{id}/related do znalezienia "dzieci"
     * danego kontaktu.
     *
     * @param originContactId UUID kontaktu źródłowego
     * @param tenantId        UUID tenanta
     * @return lista callbacków powiązanych z danym kontaktem jako origin
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ScheduledCallback> findByOriginContactId(UUID originContactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[CallbackRepo] findByOriginContactId: originContactId={}, tenant={}",
                originContactId, tenantId);

        return em.createNativeQuery("""
                        SELECT * FROM scheduled_callback
                        WHERE tenant_id         = CAST(:tenantId AS uuid)
                          AND origin_contact_id = CAST(:originContactId AS uuid)
                        ORDER BY created_at DESC
                        """,
                        ScheduledCallback.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("originContactId", originContactId.toString())
                .getResultList();
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
     * Soft-delete callbacku przez zmianę statusu na CANCELLED.
     *
     * <p>Wiersz pozostaje w bazie (historia dla raportów). Callbacki w statusie PROCESSING
     * nie mogą być anulowane tą metodą – sprawdzenie statusu należy do warstwy serwisowej
     * (kontrolera). Metoda deleguje do {@link #updateStatus(UUID, String, UUID)}.
     *
     * @param callbackId UUID callbacku
     * @param tenantId   UUID tenanta (walidacja cross-tenant)
     * @return liczba zaktualizowanych wierszy (0 gdy callback nie istnieje lub inny tenant)
     */
    @Transactional
    public int cancelCallback(UUID callbackId, UUID tenantId) {
        log.info("[CallbackRepo] Anulowanie callbacku (soft-delete): callbackId={}, tenant={}", callbackId, tenantId);
        return updateStatus(callbackId, "CANCELLED", tenantId);
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
