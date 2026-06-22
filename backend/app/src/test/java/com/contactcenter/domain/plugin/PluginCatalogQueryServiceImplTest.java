package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.tenant.Tenant;
import com.contactcenter.domain.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link PluginCatalogQueryServiceImpl} (BE-106, EPIC-28).
 *
 * <p>Pokrywa kryterium akceptacji BE-106 dla {@code revoke}: "test z 2 tenantami mającymi
 * enabled=true dla tej samej wersji — po revoke PluginRegistry.lookup dla obu zwraca puste,
 * NIEZALEŻNIE od ich enabled w DB". Tu testowana jest tylko warstwa zapytania cross-tenant
 * ({@code findAllEnabledAcrossTenantsByVersionId}) — pełny przepływ end-to-end (unload obu
 * instalacji) jest testowany w {@code PluginRevokeControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginCatalogQueryServiceImpl – revoke wersji, cross-tenant lookup enabled installations")
class PluginCatalogQueryServiceImplTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLUGIN_VERSION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private PluginVersionRepository pluginVersionRepository;
    @Mock private TenantPluginInstallationRepository tenantPluginInstallationRepository;
    @Mock private TenantService tenantService;

    private PluginCatalogQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PluginCatalogQueryServiceImpl(
                pluginVersionRepository, tenantPluginInstallationRepository, tenantService);
    }

    @Nested
    @DisplayName("revokeVersion()")
    class RevokeVersion {

        @Test
        @DisplayName("sukces: ustawia status REVOKED i zapisuje")
        void setsStatusToRevoked() {
            PluginVersion pluginVersion = buildPluginVersion(PluginVersion.PluginVersionStatus.VALIDATED);
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(pluginVersion));
            when(pluginVersionRepository.save(pluginVersion)).thenReturn(pluginVersion);

            PluginVersion result = service.revokeVersion(PLUGIN_VERSION_ID);

            assertThat(result.getStatus()).isEqualTo(PluginVersion.PluginVersionStatus.REVOKED);
            verify(pluginVersionRepository).save(pluginVersion);
        }

        @Test
        @DisplayName("wersja nieistniejąca → ResourceNotFoundException")
        void nonExistentVersion_throwsResourceNotFoundException() {
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeVersion(PLUGIN_VERSION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findAllEnabledAcrossTenantsByVersionId()")
    class FindAllEnabledAcrossTenants {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: zwraca instalacje enabled=true z WIELU tenantów (2 tenanty)")
        void returnsEnabledInstallationsAcrossMultipleTenants() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));

            TenantPluginInstallation installationA = installation(TENANT_A);
            TenantPluginInstallation installationB = installation(TENANT_B);

            when(tenantPluginInstallationRepository
                    .findAllEnabledByPluginVersionIdForTenant(PLUGIN_VERSION_ID, TENANT_A))
                    .thenReturn(List.of(installationA));
            when(tenantPluginInstallationRepository
                    .findAllEnabledByPluginVersionIdForTenant(PLUGIN_VERSION_ID, TENANT_B))
                    .thenReturn(List.of(installationB));

            List<TenantPluginInstallation> result =
                    service.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID);

            assertThat(result).containsExactlyInAnyOrder(installationA, installationB);
        }

        @Test
        @DisplayName("tenant bez instalacji enabled tej wersji nie wpływa na wynik innych tenantów")
        void tenantWithNoEnabledInstallations_isSkippedWithoutError() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));

            TenantPluginInstallation installationA = installation(TENANT_A);

            when(tenantPluginInstallationRepository
                    .findAllEnabledByPluginVersionIdForTenant(PLUGIN_VERSION_ID, TENANT_A))
                    .thenReturn(List.of(installationA));
            when(tenantPluginInstallationRepository
                    .findAllEnabledByPluginVersionIdForTenant(PLUGIN_VERSION_ID, TENANT_B))
                    .thenReturn(List.of());

            List<TenantPluginInstallation> result =
                    service.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID);

            assertThat(result).containsExactly(installationA);
        }

        @Test
        @DisplayName("brak tenantów w systemie → lista pusta, nie rzuca")
        void noTenants_returnsEmptyList() {
            when(tenantService.getAllTenants()).thenReturn(List.of());

            List<TenantPluginInstallation> result =
                    service.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID);

            assertThat(result).isEmpty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PluginVersion buildPluginVersion(PluginVersion.PluginVersionStatus status) {
        Plugin plugin = Plugin.builder()
                .id(UUID.randomUUID())
                .pluginKey("acme-crm-sync")
                .displayName("Acme CRM Sync")
                .vendor("Acme")
                .createdAt(Instant.now())
                .build();

        return PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .plugin(plugin)
                .version("1.0.0")
                .jarObjectKey("plugins/acme-crm-sync/1.0.0/plugin.jar")
                .checksumSha256("0".repeat(64))
                .manifestJson(java.util.Map.of())
                .sdkVersion("1.x")
                .status(status)
                .uploadedAt(Instant.now())
                .build();
    }

    private TenantPluginInstallation installation(UUID tenantId) {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(UUID.randomUUID());
        installation.setTenantId(tenantId);
        installation.setPluginVersionId(PLUGIN_VERSION_ID);
        installation.setEnabled(true);
        installation.setGrantedPermissions(List.of());
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setInstalledAt(Instant.now());
        installation.setUpdatedAt(Instant.now());
        return installation;
    }
}
