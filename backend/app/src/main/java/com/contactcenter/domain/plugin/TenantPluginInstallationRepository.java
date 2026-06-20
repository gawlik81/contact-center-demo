package com.contactcenter.domain.plugin;

import com.contactcenter.domain.repository.TenantAwareRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium instalacji pluginów per tenant (CRUD + zmiana stanu enabled/disabled).
 *
 * <p>Operuje na tabeli {@code tenant_plugin_installation} (V075). Wszystkie zapytania
 * używają natywnego SQL przez {@link jakarta.persistence.EntityManager} z jawnym CAST
 * dla UUID ({@code CAST(:param AS uuid)}) — wzorzec analogiczny do
 * {@code CustomDispositionRepository} (EPIC-27).
 *
 * <p>Każde zapytanie poprzedzone jest wywołaniem {@code setTenantContextInDb(tenantId)}
 * w celu ustawienia kontekstu RLS w PostgreSQL. Każdy zapis poprzedzony jest
 * {@code assertSameTenant(entity.getTenantId())}.
 *
 * <p><strong>Widoczność package-private:</strong> dostępne wyłącznie wewnątrz pakietu
 * {@code domain.plugin}, przez {@link PluginRegistrationService}.
 */
@Slf4j
@Repository
class TenantPluginInstallationRepository extends TenantAwareRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    // =========================================================================
    // Odczyt
    // =========================================================================

    @Transactional(readOnly = true)
    Optional<TenantPluginInstallation> findByIdAndTenantId(UUID id, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at
                FROM tenant_plugin_installation
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                LIMIT 1
                """)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.isEmpty() ? Optional.empty() : Optional.of(mapRow(rows.get(0)));
    }

    @Transactional(readOnly = true)
    List<TenantPluginInstallation> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[TenantPluginInstallationRepo] findAllByTenantId: tenant={}", tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, tenant_id, plugin_version_id, enabled, granted_permissions,
                       health_status, consecutive_failure_count, installation_config,
                       installed_by_user_id, installed_at, updated_at
                FROM tenant_plugin_installation
                WHERE tenant_id = CAST(:tenantId AS uuid)
                ORDER BY installed_at DESC
                """)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        return rows.stream().map(this::mapRow).toList();
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową instalację przez natywny INSERT ... RETURNING.
     *
     * <p>UUID generowany przez {@code gen_random_uuid()} w bazie. Naruszenie unikalnego
     * indeksu {@code uq_tenant_plugin_installation_version} (duplikat tenant_id+plugin_version_id)
     * propaguje się jako {@link org.springframework.dao.DataIntegrityViolationException}
     * (translacja Spring na klasie oznaczonej {@code @Repository}) — mapowane globalnie
     * na HTTP 409 przez {@code GlobalExceptionHandler}.
     *
     * @param installation encja do zapisania (id/installedAt/updatedAt mogą być null — ustawi je DB)
     * @return zapisana encja z uzupełnionym id i timestamps
     */
    @Transactional
    TenantPluginInstallation insert(TenantPluginInstallation installation) {
        assertSameTenant(installation.getTenantId());
        setTenantContextInDb(installation.getTenantId());

        log.info("[TenantPluginInstallationRepo] INSERT instalacja: tenant={}, pluginVersion={}",
                installation.getTenantId(), installation.getPluginVersionId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                INSERT INTO tenant_plugin_installation
                    (id, tenant_id, plugin_version_id, enabled, granted_permissions,
                     health_status, consecutive_failure_count, installation_config,
                     installed_by_user_id, installed_at, updated_at)
                VALUES (
                    gen_random_uuid(),
                    CAST(:tenantId AS uuid),
                    CAST(:pluginVersionId AS uuid),
                    :enabled,
                    CAST(:grantedPermissions AS jsonb),
                    :healthStatus,
                    :consecutiveFailureCount,
                    CAST(:installationConfig AS jsonb),
                    CAST(:installedByUserId AS uuid),
                    NOW(),
                    NOW()
                )
                RETURNING id, tenant_id, plugin_version_id, enabled, granted_permissions,
                          health_status, consecutive_failure_count, installation_config,
                          installed_by_user_id, installed_at, updated_at
                """)
                .setParameter("tenantId", installation.getTenantId().toString())
                .setParameter("pluginVersionId", installation.getPluginVersionId().toString())
                .setParameter("enabled", installation.isEnabled())
                .setParameter("grantedPermissions", writeJsonList(installation.getGrantedPermissions()))
                .setParameter("healthStatus", installation.getHealthStatus())
                .setParameter("consecutiveFailureCount", installation.getConsecutiveFailureCount())
                .setParameter("installationConfig", installation.getInstallationConfig())
                .setParameter("installedByUserId",
                        installation.getInstalledByUserId() != null
                                ? installation.getInstalledByUserId().toString() : null)
                .getResultList();

        TenantPluginInstallation saved = mapRow(rows.get(0));

        log.info("[TenantPluginInstallationRepo] Instalacja utworzona: id={}, tenant={}",
                saved.getId(), saved.getTenantId());

        return saved;
    }

    /**
     * Zmienia flagę {@code enabled} jednej instalacji.
     *
     * @return liczba zaktualizowanych wierszy (0 = instalacja nie istnieje dla tego tenanta)
     */
    @Transactional
    int updateEnabled(UUID id, UUID tenantId, boolean enabled) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        int updated = em.createNativeQuery("""
                UPDATE tenant_plugin_installation
                SET enabled    = :enabled,
                    updated_at = NOW()
                WHERE id        = CAST(:id AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("enabled", enabled)
                .setParameter("id", id.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[TenantPluginInstallationRepo] UPDATE enabled={}: id={}, tenant={}, wierszy={}",
                enabled, id, tenantId, updated);

        return updated;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Mapuje wiersz zwrócony przez natywne zapytanie na encję {@link TenantPluginInstallation}.
     *
     * <p>Kolejność kolumn: id, tenant_id, plugin_version_id, enabled, granted_permissions,
     * health_status, consecutive_failure_count, installation_config, installed_by_user_id,
     * installed_at, updated_at.
     */
    private TenantPluginInstallation mapRow(Object[] row) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(UUID.fromString(row[0].toString()));
        i.setTenantId(UUID.fromString(row[1].toString()));
        i.setPluginVersionId(UUID.fromString(row[2].toString()));
        i.setEnabled((Boolean) row[3]);
        i.setGrantedPermissions(readJsonList(row[4] != null ? row[4].toString() : null));
        i.setHealthStatus(row[5].toString());
        i.setConsecutiveFailureCount(((Number) row[6]).intValue());
        i.setInstallationConfig(row[7] != null ? row[7].toString() : null);
        i.setInstalledByUserId(row[8] != null ? UUID.fromString(row[8].toString()) : null);
        i.setInstalledAt(toInstant(row[9]));
        i.setUpdatedAt(toInstant(row[10]));
        return i;
    }

    private static String writeJsonList(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values != null ? values : List.of());
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] Błąd serializacji grantedPermissions: {}", e.getMessage());
            throw new IllegalArgumentException("Nie można serializować grantedPermissions do JSON", e);
        }
    }

    private static List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.error("[TenantPluginInstallationRepo] Błąd deserializacji grantedPermissions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert to Instant: " + value.getClass());
    }
}
