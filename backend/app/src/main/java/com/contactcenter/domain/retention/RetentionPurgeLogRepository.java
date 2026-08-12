package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link RetentionPurgeLog} (tabela {@code retention_purge_log}, V084/DB-048).
 *
 * <p>Rozszerza {@link TenantAwareRepository} — wszystkie zapytania ustawiają kontekst RLS przez
 * {@code setTenantContextInDb(tenantId)} (wersja jawna — {@link RetentionPurgeServiceImpl#purgeAsync}
 * działa poza wątkiem HTTP, więc nie może polegać na {@code TenantContext} z ThreadLocal ustawionym
 * przez {@code TenantFilter}), a każdy zapis poprzedzony jest {@code assertSameTenant(tenantId)}.
 *
 * <p>Wzorzec identyczny do {@code TenantRetentionPolicyRepository}: odczyt jawną listą kolumn +
 * ręczne mapowanie {@code List<Object[]>} → encja przez {@link #mapRow}, zapis przez natywny SQL
 * z {@code EntityManager}. CELOWO NIE używamy {@code em.createNativeQuery(sql, RetentionPurgeLog.class)}
 * (mapowanie przez {@code resultClass}) — ta encja ma {@code @Enumerated(EnumType.STRING)} na
 * {@code dataCategory} i {@code triggerType} oraz Lombokowy {@code @AllArgsConstructor}; ta sama
 * kombinacja spowodowała {@code ClassCastException} (Hibernate {@code NativeQueryConstructorTransformer}
 * wywołujący konstruktor pozycyjnie z surową wartością JDBC) w {@code TenantRetentionPolicyRepository}
 * — patrz jego javadoc.
 */
@Slf4j
@Repository
class RetentionPurgeLogRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis – rozpoczęcie operacji (RUNNING)
    // =========================================================================

    /**
     * Wstawia nowy wiersz w stanie {@code RUNNING} — wywoływane synchronicznie z
     * {@link RetentionPurgeServiceImpl#purge} PRZED zwróceniem {@code purgeId} wywołującemu
     * (kryterium akceptacji BE-113).
     *
     * @param purgeId          UUID operacji, wygenerowane po stronie Javy przez wywołującego
     * @param tenantId         UUID tenanta
     * @param category         kategoria danych objęta purge
     * @param triggerType      MANUAL lub AUTO
     * @param triggeredByUserId UUID użytkownika (nullable — NULL dla AUTO)
     * @param cutoffDate       granica czasowa (wiersze starsze są usuwane)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public void insertRunning(UUID purgeId, UUID tenantId, RetentionDataCategory category,
                               PurgeTriggerType triggerType, UUID triggeredByUserId, LocalDate cutoffDate) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        em.createNativeQuery("""
                INSERT INTO retention_purge_log
                    (purge_id, tenant_id, data_category, triggered_by, trigger_type,
                     cutoff_date, status, started_at)
                VALUES (
                    CAST(:purgeId AS uuid), CAST(:tenantId AS uuid), :category,
                    CAST(:triggeredBy AS uuid), :triggerType, :cutoffDate, :status, NOW()
                )
                """)
                .setParameter("purgeId", purgeId.toString())
                .setParameter("tenantId", tenantId.toString())
                .setParameter("category", category.name())
                .setParameter("triggeredBy", triggeredByUserId != null ? triggeredByUserId.toString() : null)
                .setParameter("triggerType", triggerType.name())
                .setParameter("cutoffDate", cutoffDate)
                .setParameter("status", RetentionPurgeLog.STATUS_RUNNING)
                .executeUpdate();

        log.info("[RetentionPurgeLogRepo] Zapisano RUNNING: purgeId={}, tenant={}, category={}, trigger={}, cutoff={}",
                purgeId, tenantId, category, triggerType, cutoffDate);
    }

    // =========================================================================
    // Zapis – zakończenie operacji (COMPLETED / FAILED)
    // =========================================================================

    /**
     * Oznacza operację jako zakończoną sukcesem.
     *
     * @param purgeId     UUID operacji
     * @param tenantId    UUID tenanta (cross-tenant safety)
     * @param rowsDeleted suma usuniętych wierszy ze wszystkich batchy i tabel kategorii
     * @return liczba zaktualizowanych wierszy (0 = purgeId nie istnieje lub inny tenant)
     */
    @Transactional
    public int markCompleted(UUID purgeId, UUID tenantId, long rowsDeleted) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE retention_purge_log
                   SET status       = :status,
                       rows_deleted = :rowsDeleted,
                       completed_at = NOW()
                 WHERE purge_id  = CAST(:purgeId AS uuid)
                   AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("status", RetentionPurgeLog.STATUS_COMPLETED)
                .setParameter("rowsDeleted", rowsDeleted)
                .setParameter("purgeId", purgeId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[RetentionPurgeLogRepo] Zapisano COMPLETED: purgeId={}, tenant={}, rowsDeleted={}",
                purgeId, tenantId, rowsDeleted);
        return updated;
    }

    /**
     * Oznacza operację jako zakończoną błędem — job NIE pozostaje zawieszony w stanie
     * {@code RUNNING} na zawsze (kryterium akceptacji BE-113).
     *
     * @param purgeId            UUID operacji
     * @param tenantId           UUID tenanta (cross-tenant safety)
     * @param errorMessage       komunikat błędu (przycięty do 1000 znaków — wzorzec {@code EtlSyncServiceImpl.markError})
     * @param rowsDeletedSoFar   liczba wierszy usuniętych przed wystąpieniem błędu (batche zakończone przed wyjątkiem)
     * @return liczba zaktualizowanych wierszy (0 = purgeId nie istnieje lub inny tenant)
     */
    @Transactional
    public int markFailed(UUID purgeId, UUID tenantId, String errorMessage, long rowsDeletedSoFar) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        String truncated = errorMessage != null && errorMessage.length() > 1000
                ? errorMessage.substring(0, 1000)
                : errorMessage;

        int updated = em.createNativeQuery("""
                UPDATE retention_purge_log
                   SET status        = :status,
                       error_message = :errorMessage,
                       rows_deleted  = :rowsDeleted,
                       completed_at  = NOW()
                 WHERE purge_id  = CAST(:purgeId AS uuid)
                   AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("status", RetentionPurgeLog.STATUS_FAILED)
                .setParameter("errorMessage", truncated)
                .setParameter("rowsDeleted", rowsDeletedSoFar)
                .setParameter("purgeId", purgeId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.warn("[RetentionPurgeLogRepo] Zapisano FAILED: purgeId={}, tenant={}, error={}",
                purgeId, tenantId, truncated);
        return updated;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wiersz historii purge po ID — używane przez testy oraz przyszły
     * {@code GET .../purge/{purgeId}} (BE-118).
     *
     * @param purgeId  UUID operacji
     * @param tenantId UUID tenanta
     * @return Optional z wierszem lub empty gdy nie istnieje / inny tenant
     */
    @Transactional(readOnly = true)
    public Optional<RetentionPurgeLog> findById(UUID purgeId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT purge_id, tenant_id, data_category, triggered_by, trigger_type,
                               cutoff_date, rows_deleted, status, started_at, completed_at, error_message
                        FROM retention_purge_log
                        WHERE purge_id  = CAST(:purgeId AS uuid)
                          AND tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("purgeId", purgeId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Paginowana historia operacji purge tenanta, sortowana malejąco po {@code started_at} —
     * używane przez {@code RetentionPurgeServiceImpl#getPurgeHistory} (przyszły
     * {@code GET .../history}, BE-118).
     *
     * <p>Natywny SQL + ręczne mapowanie {@code Object[]} → encja przez {@link #mapRow} (spójne
     * z resztą tej klasy — zapis też przez natywny SQL, ta klasa celowo nie miesza JPQL i
     * natywnego SQL). Sortowanie jest
     * ZAWSZE {@code started_at DESC}, niezależnie od {@code pageable.getSort()} — analogicznie
     * do {@code PluginInvocationLogRepository.findByInstallation} (log chronologiczny operacji,
     * jeden sensowny porządek dla dashboardu admina).
     *
     * <p>Brak {@code assertSameTenant} — metoda odczytu, ten sam precedens co {@link #findById}
     * (tylko zapisy w tej klasie weryfikują {@code assertSameTenant}).
     *
     * @param tenantId UUID tenanta
     * @param pageable parametry paginacji (page, size); {@code pageable.getSort()} ignorowany
     * @return strona wyników z pełnymi metadanymi paginacji
     */
    @Transactional(readOnly = true)
    public Page<RetentionPurgeLog> findAllByTenantId(UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT purge_id, tenant_id, data_category, triggered_by, trigger_type,
                               cutoff_date, rows_deleted, status, started_at, completed_at, error_message
                        FROM retention_purge_log
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        ORDER BY started_at DESC
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        List<RetentionPurgeLog> content = rows.stream().map(this::mapRow).toList();

        Number total = (Number) em.createNativeQuery("""
                        SELECT COUNT(*) FROM retention_purge_log
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        log.debug("[RetentionPurgeLogRepo] Historia: tenant={}, page={}, size={}, total={}",
                tenantId, pageable.getPageNumber(), pageable.getPageSize(), total.longValue());

        return new PageImpl<>(content, pageable, total.longValue());
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz wynikowy natywnego SELECT na encję {@link RetentionPurgeLog}.
     *
     * <p>Kolejność kolumn musi być identyczna z jawną listą w {@code SELECT} w
     * {@link #findById} i {@link #findAllByTenantId}: purge_id, tenant_id, data_category,
     * triggered_by, trigger_type, cutoff_date, rows_deleted, status, started_at,
     * completed_at, error_message.
     */
    private RetentionPurgeLog mapRow(Object[] row) {
        return RetentionPurgeLog.builder()
                .purgeId(UUID.fromString(row[0].toString()))
                .tenantId(UUID.fromString(row[1].toString()))
                .dataCategory(RetentionDataCategory.valueOf(row[2].toString()))
                .triggeredBy(row[3] != null ? UUID.fromString(row[3].toString()) : null)
                .triggerType(PurgeTriggerType.valueOf(row[4].toString()))
                .cutoffDate(toLocalDate(row[5]))
                .rowsDeleted(row[6] != null ? ((Number) row[6]).longValue() : null)
                .status(row[7].toString())
                .startedAt(toInstant(row[8]))
                .completedAt(row[9] != null ? toInstant(row[9]) : null)
                .errorMessage(row[10] != null ? row[10].toString() : null)
                .build();
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        throw new IllegalArgumentException("Cannot convert to LocalDate: " + value.getClass());
    }

    private static java.time.Instant toInstant(Object value) {
        if (value instanceof java.time.Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
