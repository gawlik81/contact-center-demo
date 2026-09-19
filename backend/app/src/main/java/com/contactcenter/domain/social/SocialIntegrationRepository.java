package com.contactcenter.domain.social;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link SocialIntegration}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie operacje wywołują
 * {@code setTenantContextInDb()} przed zapytaniami, aby PostgreSQL RLS
 * mogło filtrować dane per tenant.
 *
 * <p>Przed każdym zapisem wywoływane jest {@code assertSameTenant()}.
 */
@Slf4j
@Repository
class SocialIntegrationRepository extends TenantAwareRepository {

    /**
     * Zwraca wszystkie integracje dla aktualnego tenanta.
     */
    @Transactional(readOnly = true)
    public List<SocialIntegration> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb();
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.tenantId = :tenantId ORDER BY si.createdAt DESC",
                        SocialIntegration.class)
                .setParameter("tenantId", tenantId)
                .getResultList();
    }

    /**
     * Szuka integracji dla danej platformy w obrębie tenanta.
     *
     * <p>Zwraca listę (jeden tenant może mieć wiele stron danej platformy,
     * np. kilka Facebook Pages).
     */
    @Transactional(readOnly = true)
    public List<SocialIntegration> findByTenantIdAndPlatform(UUID tenantId, SocialPlatform platform) {
        setTenantContextInDb();
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.tenantId = :tenantId AND si.platform = :platform ORDER BY si.createdAt DESC",
                        SocialIntegration.class)
                .setParameter("tenantId", tenantId)
                .setParameter("platform", platform)
                .getResultList();
    }

    /**
     * Szuka konkretnej integracji po integration_id i tenant_id.
     */
    @Transactional(readOnly = true)
    public Optional<SocialIntegration> findByTenantIdAndIntegrationId(UUID tenantId, UUID integrationId) {
        setTenantContextInDb();
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.tenantId = :tenantId AND si.integrationId = :integrationId",
                        SocialIntegration.class)
                .setParameter("tenantId", tenantId)
                .setParameter("integrationId", integrationId)
                .getResultStream()
                .findFirst();
    }

    /**
     * Szuka integracji po platformie i page_id (unikalna kombinacja per tenant).
     */
    @Transactional(readOnly = true)
    public Optional<SocialIntegration> findByTenantIdAndPlatformAndPageId(UUID tenantId, SocialPlatform platform, String pageId) {
        setTenantContextInDb();
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.tenantId = :tenantId AND si.platform = :platform AND si.pageId = :pageId",
                        SocialIntegration.class)
                .setParameter("tenantId", tenantId)
                .setParameter("platform", platform)
                .setParameter("pageId", pageId)
                .getResultStream()
                .findFirst();
    }

    /**
     * Zwraca wszystkie integracje (wszystkich tenantów) wygasające przed podaną datą.
     * Używane przez scheduled task do odświeżania tokenów – BYPASSES RLS.
     *
     * <p>Uwaga: ta metoda jest wywoływana z wątku @Scheduled bez TenantContext.
     * Dla każdego rekordu serwis ustawia TenantContext jawnie przed odświeżeniem.
     */
    @Transactional(readOnly = true)
    public List<SocialIntegration> findAllExpiringBefore(java.time.Instant threshold) {
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.tokenExpiresAt IS NOT NULL AND si.tokenExpiresAt < :threshold AND si.webhookStatus = 'ACTIVE'",
                        SocialIntegration.class)
                .setParameter("threshold", threshold)
                .getResultList();
    }

    /**
     * Szuka integracji po platformie i page_id we WSZYSTKICH tenantach.
     *
     * <p><strong>UWAGA – pomija RLS celowo.</strong> Używana wyłącznie przez webhook handler,
     * który nie posiada JWT (wywołanie pochodzi z zewnętrznej platformy social media).
     * Identyfikacja tenanta następuje przez parę (platform, pageId) – każda strona może należeć
     * tylko do jednego tenanta (constraint {@code uq_social_integration_global_platform_page},
     * migracja V092 – globalny, NIE per-tenant jak sugerowała wcześniejsza wersja tego komentarza).
     *
     * <p>Brak wywołania {@code setTenantContextInDb()} – operacja globalna.
     * Po znalezieniu integracji serwis ustawia TenantContext ręcznie.
     *
     * @param platform platforma social media
     * @param pageId   ID strony/konta na platformie
     * @return Optional z integrację lub empty gdy brak konfiguracji dla tej strony
     */
    @Transactional(readOnly = true)
    public Optional<SocialIntegration> findByPlatformAndPageId(SocialPlatform platform, String pageId) {
        // Celowo bez setTenantContextInDb() – cross-tenant lookup dla webhook handler
        return em.createQuery(
                        "SELECT si FROM SocialIntegration si WHERE si.platform = :platform AND si.pageId = :pageId",
                        SocialIntegration.class)
                .setParameter("platform", platform)
                .setParameter("pageId", pageId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }

    /**
     * Sprawdza, czy dana para (platform, page_id) jest już przypisana INNEMU tenantowi niż podany.
     *
     * <p>Używane przez {@code SocialIntegrationServiceImpl.saveIntegration()} PRZED zapisem –
     * zapobiega sytuacji, w której np. dwaj administratorzy różnych tenantów (przez pomyłkę lub
     * złośliwie) podłączą integrację z tym samym {@code phoneNumberId}/{@code pageId}, co przy
     * webhookach identyfikowanych wyłącznie po (platform, pageId) (patrz
     * {@link #findByPlatformAndPageId}) mogłoby trwale routować wiadomości klienta jednego tenanta
     * do innego. Naruszenie tej reguły jest też wymuszone na poziomie DB przez globalny constraint
     * {@code uq_social_integration_global_platform_page} (migracja V092) – to zapytanie pozwala
     * jednak zwrócić czytelny {@code 409 Conflict} zamiast surowego
     * {@code DataIntegrityViolationException} z bazy.
     *
     * <p>Celowo bez {@code setTenantContextInDb()} – zapytanie musi widzieć wiersze WSZYSTKICH
     * tenantów, żeby wykryć kolizję (analogicznie do {@link #findByPlatformAndPageId}).
     *
     * @param platform platforma social media
     * @param pageId   ID strony/konta na platformie
     * @param tenantId UUID tenanta wykonującego zapis – wykluczony z wyszukiwania (własne rekordy
     *                 tenanta nie są kolizją, to update-in-place)
     * @return {@code true} gdy (platform, pageId) należy już do innego tenanta niż {@code tenantId}
     */
    @Transactional(readOnly = true)
    public boolean existsByPlatformAndPageIdAndTenantIdNot(SocialPlatform platform, String pageId, UUID tenantId) {
        Long count = em.createQuery(
                        "SELECT COUNT(si) FROM SocialIntegration si "
                                + "WHERE si.platform = :platform AND si.pageId = :pageId AND si.tenantId <> :tenantId",
                        Long.class)
                .setParameter("platform", platform)
                .setParameter("pageId", pageId)
                .setParameter("tenantId", tenantId)
                .getSingleResult();
        return count != null && count > 0;
    }

    /**
     * Zapisuje (tworzy lub aktualizuje) integrację.
     * Weryfikuje przynależność do tenanta przed zapisem.
     */
    @Transactional
    public SocialIntegration save(SocialIntegration integration) {
        assertSameTenant(integration.getTenantId());
        setTenantContextInDb();
        return em.merge(integration);
    }

    /**
     * Usuwa integrację.
     * Weryfikuje przynależność do tenanta przed usunięciem.
     */
    @Transactional
    public void delete(SocialIntegration integration) {
        assertSameTenant(integration.getTenantId());
        setTenantContextInDb();
        SocialIntegration managed = em.contains(integration)
                ? integration
                : em.merge(integration);
        em.remove(managed);
        log.info("[SocialIntegrationRepo] Usunięto integrację: integrationId={}, platform={}, tenant={}",
                integration.getIntegrationId(), integration.getPlatform(), integration.getTenantId());
    }

    /**
     * Usuwa integrację po integration_id i tenant_id.
     *
     * <p>Używane w {@code deleteIntegrationFromDb()} – etap usuwania z DB jest osobną
     * transakcją (poza etapem odczytu i revoke), aby nie blokować puli HikariCP
     * podczas synchronicznego wywołania Graph API.
     */
    @Transactional
    public void deleteByTenantIdAndIntegrationId(UUID tenantId, UUID integrationId) {
        setTenantContextInDb(tenantId);
        int deleted = em.createQuery(
                        "DELETE FROM SocialIntegration si WHERE si.tenantId = :tenantId AND si.integrationId = :integrationId")
                .setParameter("tenantId", tenantId)
                .setParameter("integrationId", integrationId)
                .executeUpdate();
        log.info("[SocialIntegrationRepo] Usunięto integrację (bulk delete): integrationId={}, tenant={}, affected={}",
                integrationId, tenantId, deleted);
    }

    /**
     * Aktualizuje status webhooka bez zmiany pozostałych pól.
     * Używane po nieudanym odświeżeniu tokenu (EXPIRED_TOKEN) lub sukcesie (ACTIVE).
     */
    @Transactional
    public void updateWebhookStatus(UUID integrationId, UUID tenantId, String status) {
        setTenantContextInDb(tenantId);
        em.createQuery(
                        "UPDATE SocialIntegration si SET si.webhookStatus = :status, si.updatedAt = :now WHERE si.integrationId = :integrationId AND si.tenantId = :tenantId")
                .setParameter("status", status)
                .setParameter("now", java.time.Instant.now())
                .setParameter("integrationId", integrationId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }
}
