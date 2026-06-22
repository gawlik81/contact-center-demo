package com.contactcenter.domain.plugin;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Repozytorium dziennika wywołań pluginu (DB-045, V077, BE-105).
 *
 * <p><strong>Zapis:</strong> przez natywny SQL ({@link #insert}), ponieważ {@code
 * plugin_invocation_log} jest tabelą partycjonowaną z kluczem głównym złożonym obejmującym
 * kolumnę partycjonowania ({@code invoked_at}) — JPA nie obsługuje poprawnie standardowych
 * INSERT-ów w takim przypadku (wzorzec identyczny do {@code AuditLogRepository#insertAuditLog}).
 *
 * <p><strong>Odczyt:</strong> przez JPQL — Hibernate odpytuje tabelę nadrzędną, która
 * automatycznie przekierowuje do właściwych partycji (wzorzec identyczny do {@code AuditLog}).
 *
 * <p><strong>RLS:</strong> w przeciwieństwie do {@code audit_log} (globalny, tylko ADMIN, brak
 * RLS), {@code plugin_invocation_log} ma RLS+FORCE RLS (tenant-scoped, V077) — każde zapytanie
 * jest poprzedzone {@code setTenantContextInDb(tenantId)}, a zapis {@code assertSameTenant(...)}.
 *
 * <p><strong>Widoczność publiczna (w odróżnieniu od {@code TenantPluginInstallationRepository},
 * package-private):</strong> wymagana, bo jedyny konsument — {@code
 * domain.plugin.runtime.PluginInvocationLogServiceImpl} — leży w innym pakiecie niż ta klasa
 * (struktura plików ticketu BE-105 umieszcza repo w {@code domain.plugin}, a serwis w {@code
 * domain.plugin.runtime}, razem z resztą logiki dispatchu). Metody pozostają widoczne tylko dla
 * kodu wewnątrz modułu {@code app} (brak modyfikatora {@code public} na samych metodach nie jest
 * możliwy przy publicznej klasie implementującej kontrakt przez Spring DI bez interfejsu — tu
 * nie ma osobnego publicznego interfejsu repozytorium, bo jedyny konsument jest jeden, znany i
 * kontrolowany w tym samym epiku).
 */
@Slf4j
@Repository
public class PluginInvocationLogRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Wstawia wpis dziennika wywołań przez natywny SQL (wymagane dla tabel partycjonowanych
     * PostgreSQL z kluczem głównym obejmującym kolumnę partycjonowania).
     *
     * @param entry encja do zapisania — {@code id}/{@code invokedAt} mogą być {@code null},
     *               wtedy DB ustawi je przez {@code DEFAULT gen_random_uuid()}/{@code NOW()}
     */
    @Transactional
    public void insert(PluginInvocationLog entry) {
        assertSameTenant(entry.getTenantId());
        setTenantContextInDb(entry.getTenantId());

        em.createNativeQuery("""
                INSERT INTO plugin_invocation_log
                    (id, invoked_at, tenant_id, tenant_plugin_installation_id, extension_point,
                     related_contact_id, status, duration_ms, error_summary,
                     request_payload_redacted)
                VALUES (
                    COALESCE(CAST(:id AS uuid), gen_random_uuid()),
                    COALESCE(:invokedAt, NOW()),
                    CAST(:tenantId AS uuid),
                    CAST(:installationId AS uuid),
                    :extensionPoint,
                    CAST(:relatedContactId AS uuid),
                    :status,
                    :durationMs,
                    :errorSummary,
                    CAST(:requestPayloadRedacted AS jsonb)
                )
                """)
                .setParameter("id", entry.getId() != null ? entry.getId().toString() : null)
                .setParameter("invokedAt", entry.getInvokedAt())
                .setParameter("tenantId", entry.getTenantId().toString())
                .setParameter("installationId",
                        entry.getTenantPluginInstallationId() != null
                                ? entry.getTenantPluginInstallationId().toString() : null)
                .setParameter("extensionPoint", entry.getExtensionPoint())
                .setParameter("relatedContactId",
                        entry.getRelatedContactId() != null ? entry.getRelatedContactId().toString() : null)
                .setParameter("status", entry.getStatus())
                .setParameter("durationMs", entry.getDurationMs())
                .setParameter("errorSummary", entry.getErrorSummary())
                .setParameter("requestPayloadRedacted", entry.getRequestPayloadRedacted())
                .executeUpdate();

        log.debug("[PluginInvocationLogRepo] INSERT: tenant={}, installation={}, extensionPoint={}, status={}",
                entry.getTenantId(), entry.getTenantPluginInstallationId(),
                entry.getExtensionPoint(), entry.getStatus());
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Strona wpisów dziennika dla jednej instalacji, opcjonalnie filtrowana po statusie.
     *
     * <p>{@code installationId} musi należeć do {@code tenantId} — weryfikacja ownership
     * (404 dla nieistniejącej instalacji i instalacji innego tenanta, zob.
     * {@code PluginInvocationLogServiceImpl}) odbywa się w warstwie serwisu PRZED wywołaniem
     * tej metody, nie tutaj — to repozytorium tylko filtruje po RLS.
     *
     * @param installationId instalacja, której historia jest przeglądana
     * @param tenantId        tenant z kontekstu żądania
     * @param status           opcjonalny filtr statusu ({@code null} = wszystkie statusy)
     * @param pageable          parametry paginacji (page, size) — sortowanie zawsze
     *                          {@code invoked_at DESC} niezależnie od {@code pageable.getSort()},
     *                          bo to jest jedyny sensowny porządek dla logu chronologicznego
     * @return strona wyników
     */
    @Transactional(readOnly = true)
    public Page<PluginInvocationLog> findByInstallation(
            UUID installationId, UUID tenantId, String status, Pageable pageable) {
        setTenantContextInDb(tenantId);

        List<PluginInvocationLog> content = em.createQuery("""
                        SELECT p FROM PluginInvocationLog p
                        WHERE p.tenantPluginInstallationId = :installationId
                          AND p.tenantId = :tenantId
                          AND (:status IS NULL OR p.status = :status)
                        ORDER BY p.invokedAt DESC
                        """, PluginInvocationLog.class)
                .setParameter("installationId", installationId)
                .setParameter("tenantId", tenantId)
                .setParameter("status", status)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery("""
                        SELECT COUNT(p) FROM PluginInvocationLog p
                        WHERE p.tenantPluginInstallationId = :installationId
                          AND p.tenantId = :tenantId
                          AND (:status IS NULL OR p.status = :status)
                        """, Long.class)
                .setParameter("installationId", installationId)
                .setParameter("tenantId", tenantId)
                .setParameter("status", status)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
