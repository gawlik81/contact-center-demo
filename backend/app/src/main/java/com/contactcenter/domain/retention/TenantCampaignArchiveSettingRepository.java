package com.contactcenter.domain.retention;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium {@link TenantCampaignArchiveSetting} oraz zapytanie podglądu skutku zmiany okresu
 * archiwizacji (BE-151).
 *
 * <p>Każda metoda ustawia kontekst RLS przez {@code setTenantContextInDb(tenantId)} (GUC
 * {@code app.current_tenant_id}) PRZED zapytaniem — bez tego pierwsze użycie połączenia z puli
 * kończyłoby się błędem {@code 22P02} przy {@code ''::uuid} w polityce RLS. Dodatkowo KAŻDĄ metodę
 * (zapisy i odczyty) poprzedza {@code assertSameTenant} — GUC pochodzi z parametru, więc bez tego RLS
 * nie chroniłby ścieżki odczytu. Mapowanie ręczne z {@code List<Object[]>} (wzorzec
 * {@link TenantRetentionPolicyRepository}), bez {@code resultClass}.
 */
@Slf4j
@Repository
class TenantCampaignArchiveSettingRepository extends TenantAwareRepository {

    /** Wynik zliczenia kampanii i kontaktów kwalifikujących się do archiwizacji. */
    record ArchivableCounts(long campaigns, long contacts) {
    }

    /**
     * Wynik {@link #upsertReturningPrevious}.
     *
     * @param current      stan wiersza po operacji
     * @param previousDays jawna wartość SPRZED operacji ({@code null} = tenant nie miał wiersza, czyli
     *                     obowiązywało domyślne)
     * @param written      {@code false} = wiersz istniał z tą samą wartością, nic nie zapisano
     */
    record UpsertOutcome(TenantCampaignArchiveSetting current, Integer previousDays, boolean written) {
    }

    @Transactional(readOnly = true)
    public Optional<TenantCampaignArchiveSetting> findByTenantId(UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT tenant_id, archive_after_days, updated_by, created_at, updated_at
                        FROM tenant_campaign_archive_setting
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    /**
     * Zapis okresu wraz z ATOMOWYM odczytem wartości poprzedniej, w jednej transakcji.
     *
     * <p>Kolejność (dlaczego poprawna przy równoległych PUT, READ COMMITTED):
     * <ol>
     *   <li>{@code INSERT ... ON CONFLICT (tenant_id) DO NOTHING RETURNING} — gdy wiersza nie ma,
     *       wstawiamy go od razu z nową wartością (poprzednia = brak/domyślna). Gdy konkurent trzyma
     *       niezacommitowany INSERT, ta instrukcja czeka na jego commit i wtedy nic nie wstawia.</li>
     *   <li>Gdy wiersz istnieje: {@code SELECT ... FOR UPDATE} blokuje go i czyta NAJNOWSZĄ
     *       zacommitowaną wersję (konkurent z UPDATE zostanie najpierw dokończony).</li>
     *   <li>Wartość taka sama: bez zapisu ({@code written=false}); inna: {@code UPDATE ... RETURNING}.</li>
     * </ol>
     * Dzięki temu {@code previousDays} jest zawsze wartością tuż przed NASZYM zapisem, a transakcje
     * konkurentów serializują się na wierszu — audyt i flaga {@code shortened} liczone przez serwis
     * nie są błędne. Wybrano to zamiast {@code @Transactional} na serwisie (publikacja audytu musiałaby
     * czekać na {@code afterCommit}) i zamiast CTE z podzapytaniem (snapshot instrukcji nie widzi
     * zapisu konkurenta zacommitowanego w trakcie jej oczekiwania na blokadę). Commit następuje przy
     * wyjściu z tej metody, więc serwis publikuje audyt już po commicie.
     *
     * @param archiveAfterDays okres w dniach, już zwalidowany przez serwis ([30,730])
     * @param updatedByUserId  użytkownik wykonujący zmianę (nullable)
     */
    @Transactional
    public UpsertOutcome upsertReturningPrevious(UUID tenantId, int archiveAfterDays, UUID updatedByUserId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);
        String updatedBy = updatedByUserId != null ? updatedByUserId.toString() : null;

        @SuppressWarnings("unchecked")
        List<Object[]> inserted = em.createNativeQuery(
                        """
                        INSERT INTO tenant_campaign_archive_setting
                            (tenant_id, archive_after_days, updated_by, created_at, updated_at)
                        VALUES (CAST(:tenantId AS uuid), :days, CAST(:updatedBy AS uuid), NOW(), NOW())
                        ON CONFLICT (tenant_id) DO NOTHING
                        RETURNING tenant_id, archive_after_days, updated_by, created_at, updated_at
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("days", archiveAfterDays)
                .setParameter("updatedBy", updatedBy)
                .getResultList();
        if (!inserted.isEmpty()) {
            log.info("[CampaignArchiveSettingRepo] Insert: tenant={}, archiveAfterDays={}", tenantId, archiveAfterDays);
            return new UpsertOutcome(mapRow(inserted.get(0)), null, true);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> locked = em.createNativeQuery(
                        """
                        SELECT tenant_id, archive_after_days, updated_by, created_at, updated_at
                        FROM tenant_campaign_archive_setting
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        FOR UPDATE
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();
        if (locked.isEmpty()) {
            // Wiersz istniał (konflikt), a teraz go nie ma — nie ma ścieżki usuwania, więc to anomalia.
            throw new IllegalStateException("tenant_campaign_archive_setting zniknął w trakcie zapisu: " + tenantId);
        }
        TenantCampaignArchiveSetting existing = mapRow(locked.get(0));
        int previous = existing.getArchiveAfterDays();
        if (previous == archiveAfterDays) {
            return new UpsertOutcome(existing, previous, false);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> updated = em.createNativeQuery(
                        """
                        UPDATE tenant_campaign_archive_setting
                           SET archive_after_days = :days,
                               updated_by         = CAST(:updatedBy AS uuid),
                               updated_at         = NOW()
                         WHERE tenant_id = CAST(:tenantId AS uuid)
                        RETURNING tenant_id, archive_after_days, updated_by, created_at, updated_at
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("days", archiveAfterDays)
                .setParameter("updatedBy", updatedBy)
                .getResultList();
        log.info("[CampaignArchiveSettingRepo] Update: tenant={}, {} -> {} dni", tenantId, previous, archiveAfterDays);
        return new UpsertOutcome(mapRow(updated.get(0)), previous, true);
    }

    /**
     * Liczy kampanie {@code COMPLETED}/{@code STOPPED} tenanta kwalifikujące się do archiwizacji przy
     * okresie {@code days} oraz ich kontakty w {@code campaign_contact}. Tylko odczyt.
     *
     * <p>Predykat IDENTYCZNY z pętlą funkcji {@code archive_completed_campaign_contacts()} (V138):
     * {@code status IN ('COMPLETED','STOPPED') AND COALESCE(ended_at, updated_at) < NOW() -
     * make_interval(days => N)}. Liczone są tylko kampanie, które mają jeszcze kontakty w
     * {@code campaign_contact} (funkcja przetwarza także kampanie już zarchiwizowane, bez kontaktów,
     * ale nie mają one nic do przeniesienia — liczenie ich dawałoby wiecznie niezerowy podgląd).
     * Dwa zliczenia w jednej transakcji widzą to samo {@code NOW()}.
     */
    @Transactional(readOnly = true)
    public ArchivableCounts countArchivable(UUID tenantId, int days) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        """
                        SELECT (SELECT COUNT(*)
                                  FROM campaign c
                                 WHERE c.tenant_id = CAST(:tenantId AS uuid)
                                   AND c.status IN ('COMPLETED', 'STOPPED')
                                   AND COALESCE(c.ended_at, c.updated_at) < NOW() - make_interval(days => :days)
                                   AND EXISTS (SELECT 1 FROM campaign_contact cc WHERE cc.campaign_id = c.campaign_id)),
                               (SELECT COUNT(*)
                                  FROM campaign_contact cc
                                  JOIN campaign c ON c.campaign_id = cc.campaign_id
                                 WHERE c.tenant_id = CAST(:tenantId AS uuid)
                                   AND c.status IN ('COMPLETED', 'STOPPED')
                                   AND COALESCE(c.ended_at, c.updated_at) < NOW() - make_interval(days => :days))
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("days", days)
                .getResultList();

        Object[] row = rows.get(0);
        return new ArchivableCounts(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    private TenantCampaignArchiveSetting mapRow(Object[] row) {
        return TenantCampaignArchiveSetting.builder()
                .tenantId(UUID.fromString(row[0].toString()))
                .archiveAfterDays(((Number) row[1]).intValue())
                .updatedBy(row[2] != null ? UUID.fromString(row[2].toString()) : null)
                .createdAt(toInstant(row[3]))
                .updatedAt(toInstant(row[4]))
                .build();
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
