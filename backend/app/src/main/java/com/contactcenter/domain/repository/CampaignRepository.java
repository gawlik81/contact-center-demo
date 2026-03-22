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
