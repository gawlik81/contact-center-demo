package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.Campaign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link Campaign}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie zapytania wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb(tenantId)}.
 *
 * <p>Używa natywnego SQL zamiast JPQL dla zapytań z parametrami UUID
 * (spójność z wzorcem CustomerRepository).
 */
@Slf4j
@Repository
public class CampaignRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera kampanię po ID z zabezpieczeniem cross-tenant.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return Optional z kampanią lub empty gdy nie istnieje lub należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public Optional<Campaign> findById(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Campaign campaign = em.find(Campaign.class, campaignId);
        if (campaign == null || !campaign.getTenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(campaign);
    }

    /**
     * Lista kampanii tenanta z paginacją offset-based.
     *
     * <p>Sortowanie: created_at DESC (najnowsze pierwsze).
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return lista kampanii tenanta
     */
    @Transactional(readOnly = true)
    public List<Campaign> findByTenantId(UUID tenantId, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[CampaignRepo] Lista kampanii: tenant={}, page={}, size={}, offset={}",
                tenantId, page, size, offset);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        ORDER BY created_at DESC
                        LIMIT :size OFFSET :offset
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("size", size)
                .setParameter("offset", offset)
                .getResultList();

        log.debug("[CampaignRepo] Znaleziono {} kampanii (page={}, size={})", results.size(), page, size);
        return results;
    }

    /**
     * Zlicza wszystkie kampanie tenanta – do metadanych paginacji.
     *
     * @param tenantId UUID tenanta
     * @return łączna liczba kampanii tenanta
     */
    @Transactional(readOnly = true)
    public long countByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue();
    }

    /**
     * Zlicza kontakty przypisane do kampanii w tabeli {@code campaign_contact}.
     *
     * <p>Używane przy walidacji startu kampanii: kampania bez kontaktów nie może
     * zostać uruchomiona (kryterium akceptacji BE-022).
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return liczba rekordów w campaign_contact dla tej kampanii
     */
    @Transactional(readOnly = true)
    public long countContacts(UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM campaign_contact
                        WHERE campaign_id = CAST(:campaignId AS uuid)
                          AND tenant_id = CAST(:tenantId AS uuid)
                        """)
                .setParameter("campaignId", campaignId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue();
    }

    /**
     * Pobiera wszystkie kampanie w statusie RUNNING dla danego tenanta.
     *
     * <p>Używane przez {@link com.contactcenter.api.dialer.DialerController} (widok stanu dialera)
     * oraz legacy. Zwraca kampanie wszystkich typów dialera.
     * Indeks {@code idx_campaign_running_tenant} (V031) zapewnia wydajność.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii w statusie RUNNING (wszystkie typy dialera)
     */
    @Transactional(readOnly = true)
    public List<Campaign> findRunningByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'RUNNING'
                        ORDER BY created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Kampanie RUNNING (wszystkie typy): tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera kampanie RUNNING z typem dialera PROGRESSIVE dla danego tenanta.
     *
     * <p>Używane wyłącznie przez {@link com.contactcenter.domain.service.ProgressiveDialerService}
     * przy automatycznym wybieraniu. Kampanie MANUAL i PREDICTIVE są świadomie wykluczone:
     * MANUAL wymaga ręcznej inicjacji połączenia przez agenta (endpoint POST /api/dialer/manual/call),
     * a PREDICTIVE nie jest jeszcze zaimplementowany.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii RUNNING z dialer_type = 'PROGRESSIVE'
     */
    @Transactional(readOnly = true)
    public List<Campaign> findRunningProgressiveByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'RUNNING'
                          AND dialer_type = 'PROGRESSIVE'
                        ORDER BY created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Kampanie RUNNING+PROGRESSIVE: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera kampanie RUNNING z typem dialera MANUAL dla danego tenanta.
     *
     * <p>Używane przez endpoint GET /api/dialer/manual/records – widok agenta w panelu
     * desktopowym pokazujący kampanie manualne z rekordami PENDING do ręcznego wybierania.
     * Kampanie PROGRESSIVE i PREDICTIVE są świadomie wykluczone (obsługiwane automatycznie).
     *
     * <p>Indeks {@code idx_campaign_running_tenant} (V031) zapewnia wydajność.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii RUNNING z dialer_type = 'MANUAL'
     */
    @Transactional(readOnly = true)
    public List<Campaign> findRunningManualByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'RUNNING'
                          AND dialer_type = 'MANUAL'
                        ORDER BY created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Kampanie RUNNING+MANUAL: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera kampanie powiązane z agentem przez kolejkę (kalendarz agenta, BE-051).
     *
     * <p>Agent widzi kampanię jeśli spełniony jest dowolny z warunków:
     * <ol>
     *   <li>jest jawnie przypisany do kolejki przez {@code queue_agent},</li>
     *   <li>kolejka ma flagę {@code all_agents = TRUE} (wszyscy agenci tenanta),</li>
     *   <li>jest członkiem grupy przypisanej do kolejki przez {@code queue_agent_group}.</li>
     * </ol>
     *
     * <p>Wyklucza kampanie w statusach COMPLETED i CANCELLED.
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @return lista kampanii widocznych dla agenta (bez COMPLETED/CANCELLED)
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Campaign> findByAgentIdViaQueue(UUID tenantId, UUID agentId) {
        setTenantContextInDb(tenantId);

        log.debug("[CampaignRepo] Kalendarz agenta – kampanie via kolejka: tenant={}, agentId={}",
                tenantId, agentId);

        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT c.* FROM campaign c
                        JOIN queue q ON c.queue_id = q.queue_id
                        WHERE c.tenant_id = CAST(:tenantId AS uuid)
                          AND c.status NOT IN ('COMPLETED', 'CANCELLED')
                          AND (
                            q.all_agents = TRUE
                            OR EXISTS (
                              SELECT 1 FROM queue_agent qa
                              WHERE qa.queue_id = c.queue_id
                                AND qa.agent_id = CAST(:agentId AS uuid)
                            )
                            OR EXISTS (
                              SELECT 1 FROM queue_agent_group qag
                              JOIN agent_group_member agm ON qag.group_id = agm.group_id
                              WHERE qag.queue_id = c.queue_id
                                AND agm.agent_id = CAST(:agentId AS uuid)
                            )
                          )
                        ORDER BY c.created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("agentId", agentId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Znaleziono {} kampanii dla agentId={}", results.size(), agentId);
        return results;
    }

    /**
     * Pobiera wszystkie kampanie w statusach RUNNING lub PAUSED dla danego tenanta.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.CampaignWindowActivator}
     * do automatycznego kończenia kampanii po upłynięciu end_date.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii w statusach RUNNING lub PAUSED
     */
    @Transactional(readOnly = true)
    public List<Campaign> findRunningOrPausedByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status IN ('RUNNING', 'PAUSED')
                        ORDER BY created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Kampanie RUNNING/PAUSED: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera wszystkie kampanie w statusie SCHEDULED dla danego tenanta.
     *
     * <p>Używane przez {@link com.contactcenter.domain.service.CampaignWindowActivator}
     * do automatycznego przejścia SCHEDULED → RUNNING gdy kampania wchodzi w okno czasowe.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii w statusie SCHEDULED
     */
    @Transactional(readOnly = true)
    public List<Campaign> findScheduledByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Campaign> results = em.createNativeQuery(
                        """
                        SELECT * FROM campaign
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND status = 'SCHEDULED'
                        ORDER BY created_at ASC
                        """,
                        Campaign.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[CampaignRepo] Kampanie SCHEDULED: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje kampanię.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta ({@code assertSameTenant}).
     *
     * @param campaign encja kampanii do zapisu
     * @return zapisana encja (ze wygenerowanym ID jeśli nowa)
     */
    @Transactional
    public Campaign save(Campaign campaign) {
        assertSameTenant(campaign.getTenantId());
        setTenantContextInDb(campaign.getTenantId());

        if (campaign.getCampaignId() == null) {
            em.persist(campaign);
            log.info("[CampaignRepo] Utworzono kampanię: name='{}', tenant={}",
                    campaign.getName(), campaign.getTenantId());
            return campaign;
        }

        Campaign merged = em.merge(campaign);
        log.debug("[CampaignRepo] Zaktualizowano kampanię: campaignId={}, tenant={}",
                merged.getCampaignId(), merged.getTenantId());
        return merged;
    }
}
