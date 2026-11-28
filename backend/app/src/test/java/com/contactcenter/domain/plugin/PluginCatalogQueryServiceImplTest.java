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
        @DisplayName("W3 (BE-245): nieczytelny tenant nie ukrywa instalacji pozostałych tenantów (wynik częściowy)")
        void unreadableTenantDoesNotHideOthers() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));
            TenantPluginInstallation installationB = installation(TENANT_B);
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_A))
                    .thenThrow(new InstallationConfigUnreadableException(2, 2, 0));
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_B))
                    .thenReturn(List.of(installationB));

            assertThat(service.findAllEnabledAcrossAllTenants()).containsExactly(installationB);
        }

        @Test
        @DisplayName("W3 (BE-245): nic nie da się odczytać nigdzie (rotacja klucza) -> wyjątek, nie pusta lista")
        void nothingReadableAnywhereFailsLoudly() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_A))
                    .thenThrow(new InstallationConfigUnreadableException(2, 2, 0));
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_B))
                    .thenThrow(new InstallationConfigUnreadableException(1, 1, 0));

            assertThatThrownBy(() -> service.findAllEnabledAcrossAllTenants())
                    .isInstanceOf(InstallationConfigUnreadableException.class);
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

    @Nested
    @DisplayName("findAllEnabledAcrossAllTenants()")
    class FindAllEnabledAcrossAllTenantsTests {

        @Test
        @DisplayName("zwraca instalacje enabled=true z WIELU tenantów, niezależnie od wersji pluginu")
        void returnsEnabledInstallationsAcrossMultipleTenants() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));

            TenantPluginInstallation installationA = installation(TENANT_A);
            TenantPluginInstallation installationB = installation(TENANT_B);

            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_A))
                    .thenReturn(List.of(installationA));
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_B))
                    .thenReturn(List.of(installationB));

            List<TenantPluginInstallation> result = service.findAllEnabledAcrossAllTenants();

            assertThat(result).containsExactlyInAnyOrder(installationA, installationB);
        }

        @Test
        @DisplayName("tenant bez instalacji enabled nie wpływa na wynik innych tenantów")
        void tenantWithNoEnabledInstallations_isSkippedWithoutError() {
            Tenant tenantA = Tenant.builder().id(TENANT_A).name("Tenant A").build();
            Tenant tenantB = Tenant.builder().id(TENANT_B).name("Tenant B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(tenantA, tenantB));

            TenantPluginInstallation installationA = installation(TENANT_A);

            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_A))
                    .thenReturn(List.of(installationA));
            when(tenantPluginInstallationRepository.findAllEnabledForTenant(TENANT_B))
                    .thenReturn(List.of());

            List<TenantPluginInstallation> result = service.findAllEnabledAcrossAllTenants();

            assertThat(result).containsExactly(installationA);
        }

        @Test
        @DisplayName("brak tenantów w systemie → lista pusta, nie rzuca")
        void noTenants_returnsEmptyList() {
            when(tenantService.getAllTenants()).thenReturn(List.of());

            List<TenantPluginInstallation> result = service.findAllEnabledAcrossAllTenants();

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findVersionsForTenant()")
    class FindVersionsForTenant {

        @Test
        @DisplayName("BE-110 per-tenant: deleguje do repozytorium, sortowane uploadedAt DESC")
        void delegatesToRepositorySortedByUploadedAtDesc() {
            PluginVersion newer = buildPluginVersion(PluginVersion.PluginVersionStatus.VALIDATED);
            PluginVersion older = buildPluginVersion(PluginVersion.PluginVersionStatus.REJECTED);
            when(pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(TENANT_A))
                    .thenReturn(List.of(newer, older));

            List<PluginVersion> result = service.findVersionsForTenant(TENANT_A);

            assertThat(result).containsExactly(newer, older);
        }

        @Test
        @DisplayName("zwraca wszystkie statusy, włącznie z REJECTED — bez filtrowania")
        void returnsAllStatusesIncludingRejected() {
            PluginVersion rejected = buildPluginVersion(PluginVersion.PluginVersionStatus.REJECTED);
            when(pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(TENANT_A))
                    .thenReturn(List.of(rejected));

            List<PluginVersion> result = service.findVersionsForTenant(TENANT_A);

            assertThat(result).extracting(PluginVersion::getStatus)
                    .containsExactly(PluginVersion.PluginVersionStatus.REJECTED);
        }

        @Test
        @DisplayName("katalog pusty dla tenanta → lista pusta, nie rzuca")
        void emptyCatalog_returnsEmptyList() {
            when(pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(TENANT_A))
                    .thenReturn(List.of());

            List<PluginVersion> result = service.findVersionsForTenant(TENANT_A);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("IZOLACJA: tenant A nie widzi wersji tenanta B")
        void tenantIsolation_tenantADoesNotSeeTenantBVersions() {
            PluginVersion versionA = buildPluginVersion(PluginVersion.PluginVersionStatus.VALIDATED);
            when(pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(TENANT_A))
                    .thenReturn(List.of(versionA));
            when(pluginVersionRepository.findByTenantIdOrderByUploadedAtDesc(TENANT_B))
                    .thenReturn(List.of());

            List<PluginVersion> resultA = service.findVersionsForTenant(TENANT_A);
            List<PluginVersion> resultB = service.findVersionsForTenant(TENANT_B);

            assertThat(resultA).containsExactly(versionA);
            assertThat(resultB).isEmpty();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @Nested
    @DisplayName("findEnabledInstallationAcrossTenants() - pętla po tenantach (BE-246)")
    class FindEnabledInstallationAcrossTenants {

        private static final UUID TENANT_C = UUID.fromString("44444444-4444-4444-4444-444444444444");

        // Justification for a unit (not Testcontainers) test: the RLS context is set INSIDE
        // TenantPluginInstallationRepository#findByIdAndTenantId per call (verified in TenantPluginInstallationRepositoryTest
        // with an id/tenant that differ from TenantContext), and a database-backed variant needs Docker; here the loop
        // contract is pinned: one repository call per tenant, with that tenant's id, until the first ENABLED match.
        @Test
        @DisplayName("3 tenanty: instalacja tenanta C znaleziona po pominięciu A i B; zapytanie per tenant z jego id")
        void findsInstallationOfTheThirdTenantAndQueriesEachTenantExplicitly() {
            Tenant a = Tenant.builder().id(TENANT_A).name("A").build();
            Tenant b = Tenant.builder().id(TENANT_B).name("B").build();
            Tenant c = Tenant.builder().id(TENANT_C).name("C").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(a, b, c));
            TenantPluginInstallation inC = installation(TENANT_C);
            UUID id = inC.getId();
            when(tenantPluginInstallationRepository.findByIdAndTenantId(id, TENANT_A)).thenReturn(Optional.empty());
            when(tenantPluginInstallationRepository.findByIdAndTenantId(id, TENANT_B)).thenReturn(Optional.empty());
            when(tenantPluginInstallationRepository.findByIdAndTenantId(id, TENANT_C)).thenReturn(Optional.of(inC));

            Optional<TenantPluginInstallation> result = service.findEnabledInstallationAcrossTenants(id);

            assertThat(result).containsSame(inC);
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(tenantPluginInstallationRepository);
            order.verify(tenantPluginInstallationRepository).findByIdAndTenantId(id, TENANT_A);
            order.verify(tenantPluginInstallationRepository).findByIdAndTenantId(id, TENANT_B);
            order.verify(tenantPluginInstallationRepository).findByIdAndTenantId(id, TENANT_C);
        }

        @Test
        @DisplayName("pętla kończy się na pierwszym dopasowaniu (kolejni tenanci nie są odpytywani)")
        void stopsAtTheFirstMatch() {
            Tenant a = Tenant.builder().id(TENANT_A).name("A").build();
            Tenant b = Tenant.builder().id(TENANT_B).name("B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(a, b));
            TenantPluginInstallation inA = installation(TENANT_A);
            when(tenantPluginInstallationRepository.findByIdAndTenantId(inA.getId(), TENANT_A))
                    .thenReturn(Optional.of(inA));

            assertThat(service.findEnabledInstallationAcrossTenants(inA.getId())).containsSame(inA);

            org.mockito.Mockito.verify(tenantPluginInstallationRepository, org.mockito.Mockito.never())
                    .findByIdAndTenantId(inA.getId(), TENANT_B);
        }

        @Test
        @DisplayName("wyłączona instalacja jest pomijana (enabled=false), brak dopasowania -> empty")
        void disabledInstallationIsSkippedAndUnknownIdIsEmpty() {
            Tenant a = Tenant.builder().id(TENANT_A).name("A").build();
            Tenant b = Tenant.builder().id(TENANT_B).name("B").build();
            when(tenantService.getAllTenants()).thenReturn(List.of(a, b));
            TenantPluginInstallation disabled = installation(TENANT_A);
            disabled.setEnabled(false);
            when(tenantPluginInstallationRepository.findByIdAndTenantId(disabled.getId(), TENANT_A))
                    .thenReturn(Optional.of(disabled));
            when(tenantPluginInstallationRepository.findByIdAndTenantId(disabled.getId(), TENANT_B))
                    .thenReturn(Optional.empty());

            assertThat(service.findEnabledInstallationAcrossTenants(disabled.getId())).isEmpty();
            assertThat(service.findEnabledInstallationAcrossTenants(UUID.randomUUID())).isEmpty();
        }
    }

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
