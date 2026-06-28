package com.contactcenter.domain.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.dto.PluginConfigEntryDto;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link PluginRegistrationServiceImpl} (BE-100, EPIC-28).
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>install: sukces z przecięciem grantedPermissions ∩ manifest.permissions</li>
 *   <li>install: duplikat (tenant_id, plugin_version_id) propaguje DataIntegrityViolationException</li>
 *   <li>install: nieistniejąca PluginVersion → ResourceNotFoundException</li>
 *   <li>enable/disable: zmiana flagi + guard "nie istnieje dla tego tenanta"</li>
 *   <li>rollback: sukces (atomowo dwa UPDATE) oraz brak modyfikacji gdy któraś instalacja
 *       nie należy do tenanta (atomowość — żaden wiersz nie jest zmieniany przed weryfikacją obu)</li>
 *   <li>listInstallations: mapowanie na DTO</li>
 * </ul>
 *
 * <p>Repozytoria są mockowane — {@code assertSameTenant}/RLS w prawdziwym
 * {@code TenantPluginInstallationRepository} nie są wywoływane w tym teście (to odpowiedzialność
 * testów repozytorium/integracyjnych), tutaj testujemy wyłącznie logikę serwisu.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginRegistrationService – instalacja, enable/disable, rollback pluginu per tenant")
class PluginRegistrationServiceImplTest {

    private static final UUID TENANT_ID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID PLUGIN_VERSION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTALLED_BY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID INSTALLATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TARGET_INSTALLATION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock private TenantPluginInstallationRepository installationRepository;
    @Mock private PluginVersionRepository pluginVersionRepository;

    private PluginRegistrationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PluginRegistrationServiceImpl(installationRepository, pluginVersionRepository);
    }

    // =========================================================================
    // install()
    // =========================================================================

    @Nested
    @DisplayName("install()")
    class Install {

        @Test
        @DisplayName("sukces: zapisane permissions to przecięcie żądanych ∩ manifestu")
        void install_withPartialOverlap_savesIntersectionOnly() {
            PluginVersion pluginVersion = buildPluginVersionWithPermissions(
                    List.of("customer:read", "contact:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(pluginVersion));

            TenantPluginInstallation saved = buildInstallation(
                    INSTALLATION_ID, TENANT_ID, false, List.of("customer:read"));
            when(installationRepository.insert(any())).thenReturn(saved);

            // żądane: customer:read (w manifeście) + contact:update (NIE w manifeście, powinno być zignorowane)
            TenantPluginInstallationDto dto = service.install(
                    TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read", "contact:update"), INSTALLED_BY);

            assertThat(dto.id()).isEqualTo(INSTALLATION_ID);
            assertThat(dto.enabled()).isFalse();
            assertThat(dto.grantedPermissions()).containsExactly("customer:read");

            var captor = org.mockito.ArgumentCaptor.forClass(TenantPluginInstallation.class);
            verify(installationRepository).insert(captor.capture());
            TenantPluginInstallation toInsert = captor.getValue();
            assertThat(toInsert.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(toInsert.getPluginVersionId()).isEqualTo(PLUGIN_VERSION_ID);
            assertThat(toInsert.isEnabled()).isFalse();
            assertThat(toInsert.getGrantedPermissions()).containsExactly("customer:read");
            assertThat(toInsert.getHealthStatus()).isEqualTo(TenantPluginInstallation.HealthStatus.HEALTHY);
            assertThat(toInsert.getInstalledByUserId()).isEqualTo(INSTALLED_BY);
        }

        @Test
        @DisplayName("permission niezadeklarowane w manifeście jest filtrowane, nie powoduje błędu")
        void install_withPermissionNotInManifest_filtersWithoutError() {
            PluginVersion pluginVersion = buildPluginVersionWithPermissions(List.of("customer:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(pluginVersion));
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of()));

            TenantPluginInstallationDto dto = service.install(
                    TENANT_ID, PLUGIN_VERSION_ID, List.of("http:egress:evil.example.com"), INSTALLED_BY);

            assertThat(dto.grantedPermissions()).isEmpty();
        }

        @Test
        @DisplayName("grantedPermissions=null traktowane jak pusta lista (brak NPE)")
        void install_withNullGrantedPermissions_treatedAsEmpty() {
            PluginVersion pluginVersion = buildPluginVersionWithPermissions(List.of("customer:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(pluginVersion));
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of()));

            TenantPluginInstallationDto dto = service.install(TENANT_ID, PLUGIN_VERSION_ID, null, INSTALLED_BY);

            assertThat(dto.grantedPermissions()).isEmpty();
        }

        @Test
        @DisplayName("nieistniejąca PluginVersion → ResourceNotFoundException")
        void install_withMissingPluginVersion_throwsResourceNotFound() {
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of(), INSTALLED_BY))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).insert(any());
        }

        @Test
        @DisplayName("duplikat (tenant_id, plugin_version_id) propaguje DataIntegrityViolationException (→ 409)")
        void install_withDuplicateInstallation_propagatesDataIntegrityViolation() {
            PluginVersion pluginVersion = buildPluginVersionWithPermissions(List.of("customer:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(pluginVersion));
            when(installationRepository.insert(any()))
                    .thenThrow(new DataIntegrityViolationException(
                            "duplicate key value violates unique constraint \"uq_tenant_plugin_installation_version\""));

            assertThatThrownBy(() -> service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        // =====================================================================
        // Dziedziczenie configu przy upgrade tego samego pluginu (BE-111)
        // =====================================================================

        @Test
        @DisplayName("BE-111: druga wersja tego samego pluginu dziedziczy config z poprzedniej instalacji tenanta")
        void install_secondVersionOfSamePlugin_inheritsConfigFromPreviousInstallation() {
            UUID otherVersionId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID previousInstallationId = UUID.fromString("88888888-8888-8888-8888-888888888888");
            String previousConfigJson = "{\"googleApiKey\":\"secret-key\"}";

            PluginVersion newVersion = buildPluginVersionWithPlugin(
                    PLUGIN_VERSION_ID, "customer-google-lookup", "2.0.0", List.of("customer:read"));
            PluginVersion oldVersion = buildPluginVersionWithPlugin(
                    otherVersionId, "customer-google-lookup", "1.0.0", List.of("customer:read"));

            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(newVersion));

            TenantPluginInstallation previousInstallation =
                    buildInstallation(previousInstallationId, TENANT_ID, true, List.of("customer:read"));
            previousInstallation.setPluginVersionId(otherVersionId);
            previousInstallation.setInstallationConfig(previousConfigJson);
            previousInstallation.setInstalledAt(Instant.now().minusSeconds(3600));

            when(installationRepository.findAllByTenantId(TENANT_ID)).thenReturn(List.of(previousInstallation));
            when(pluginVersionRepository.findAllById(List.of(otherVersionId))).thenReturn(List.of(oldVersion));

            TenantPluginInstallation saved = buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of("customer:read"));
            saved.setInstallationConfig(previousConfigJson);
            when(installationRepository.insert(any())).thenReturn(saved);

            service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY);

            var captor = org.mockito.ArgumentCaptor.forClass(TenantPluginInstallation.class);
            verify(installationRepository).insert(captor.capture());
            assertThat(captor.getValue().getInstallationConfig()).isEqualTo(previousConfigJson);
        }

        @Test
        @DisplayName("BE-111: pierwsza instalacja pluginu (brak wcześniejszej instalacji tenanta) — config pusty")
        void install_firstInstallationOfPlugin_configRemainsNull() {
            PluginVersion newVersion = buildPluginVersionWithPlugin(
                    PLUGIN_VERSION_ID, "customer-google-lookup", "1.0.0", List.of("customer:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(newVersion));
            when(installationRepository.findAllByTenantId(TENANT_ID)).thenReturn(List.of());
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of("customer:read")));

            service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY);

            var captor = org.mockito.ArgumentCaptor.forClass(TenantPluginInstallation.class);
            verify(installationRepository).insert(captor.capture());
            assertThat(captor.getValue().getInstallationConfig()).isNull();
        }

        @Test
        @DisplayName("BE-111: poprzednia instalacja tego samego pluginu miała config pusty/null — nowa też ma null, bez wyjątku")
        void install_previousInstallationHadNullConfig_newInstallationConfigStaysNull() {
            UUID otherVersionId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID previousInstallationId = UUID.fromString("88888888-8888-8888-8888-888888888888");

            PluginVersion newVersion = buildPluginVersionWithPlugin(
                    PLUGIN_VERSION_ID, "customer-google-lookup", "2.0.0", List.of("customer:read"));
            PluginVersion oldVersion = buildPluginVersionWithPlugin(
                    otherVersionId, "customer-google-lookup", "1.0.0", List.of("customer:read"));

            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(newVersion));

            TenantPluginInstallation previousInstallation =
                    buildInstallation(previousInstallationId, TENANT_ID, false, List.of());
            previousInstallation.setPluginVersionId(otherVersionId);
            previousInstallation.setInstallationConfig(null);

            when(installationRepository.findAllByTenantId(TENANT_ID)).thenReturn(List.of(previousInstallation));
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of("customer:read")));

            service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY);

            var captor = org.mockito.ArgumentCaptor.forClass(TenantPluginInstallation.class);
            verify(installationRepository).insert(captor.capture());
            assertThat(captor.getValue().getInstallationConfig()).isNull();
        }

        @Test
        @DisplayName("BE-111: instalacja innego pluginu (inny pluginKey) tenanta nie jest źródłem dziedziczenia configu")
        void install_existingInstallationOfDifferentPlugin_doesNotInheritConfig() {
            UUID otherVersionId = UUID.fromString("77777777-7777-7777-7777-777777777777");
            UUID unrelatedInstallationId = UUID.fromString("88888888-8888-8888-8888-888888888888");

            PluginVersion newVersion = buildPluginVersionWithPlugin(
                    PLUGIN_VERSION_ID, "customer-google-lookup", "1.0.0", List.of("customer:read"));
            PluginVersion unrelatedVersion = buildPluginVersionWithPlugin(
                    otherVersionId, "acme-crm-sync", "3.1.0", List.of("customer:read"));

            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(newVersion));

            TenantPluginInstallation unrelatedInstallation =
                    buildInstallation(unrelatedInstallationId, TENANT_ID, true, List.of("customer:read"));
            unrelatedInstallation.setPluginVersionId(otherVersionId);
            unrelatedInstallation.setInstallationConfig("{\"crmToken\":\"unrelated-secret\"}");

            when(installationRepository.findAllByTenantId(TENANT_ID)).thenReturn(List.of(unrelatedInstallation));
            when(pluginVersionRepository.findAllById(List.of(otherVersionId))).thenReturn(List.of(unrelatedVersion));
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of("customer:read")));

            service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY);

            var captor = org.mockito.ArgumentCaptor.forClass(TenantPluginInstallation.class);
            verify(installationRepository).insert(captor.capture());
            assertThat(captor.getValue().getInstallationConfig()).isNull();
        }

        @Test
        @DisplayName("BE-111: izolacja multi-tenant — findAllByTenantId jest wołane tylko z tenantId instalującego, "
                + "nigdy z innym tenantem (brak cross-tenant leak configu)")
        void install_neverQueriesOtherTenantForInheritedConfig() {
            PluginVersion newVersion = buildPluginVersionWithPlugin(
                    PLUGIN_VERSION_ID, "customer-google-lookup", "1.0.0", List.of("customer:read"));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID)).thenReturn(Optional.of(newVersion));
            when(installationRepository.findAllByTenantId(TENANT_ID)).thenReturn(List.of());
            when(installationRepository.insert(any()))
                    .thenReturn(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of("customer:read")));

            service.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), INSTALLED_BY);

            verify(installationRepository).findAllByTenantId(TENANT_ID);
            verify(installationRepository, never()).findAllByTenantId(OTHER_TENANT);
        }
    }

    // =========================================================================
    // enable() / disable()
    // =========================================================================

    @Nested
    @DisplayName("enable() / disable()")
    class EnableDisable {

        @Test
        @DisplayName("enable: instalacja istnieje dla tenanta → updateEnabled(true)")
        void enable_withExistingInstallation_updatesEnabledTrue() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, false, List.of())));

            service.enable(TENANT_ID, INSTALLATION_ID);

            verify(installationRepository).updateEnabled(INSTALLATION_ID, TENANT_ID, true);
        }

        @Test
        @DisplayName("disable: instalacja istnieje dla tenanta → updateEnabled(false)")
        void disable_withExistingInstallation_updatesEnabledFalse() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));

            service.disable(TENANT_ID, INSTALLATION_ID);

            verify(installationRepository).updateEnabled(INSTALLATION_ID, TENANT_ID, false);
        }

        @Test
        @DisplayName("enable: instalacja nie istnieje dla tenanta (RLS odfiltrował) → ResourceNotFoundException")
        void enable_withMissingInstallation_throwsResourceNotFound() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enable(OTHER_TENANT, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).updateEnabled(eq(INSTALLATION_ID), eq(OTHER_TENANT), eq(true));
        }

        @Test
        @DisplayName("disable: instalacja nie istnieje dla tenanta → ResourceNotFoundException, brak UPDATE")
        void disable_withMissingInstallation_throwsResourceNotFoundAndSkipsUpdate() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disable(OTHER_TENANT, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).updateEnabled(eq(INSTALLATION_ID), eq(OTHER_TENANT), eq(false));
        }
    }

    // =========================================================================
    // getInstallation() (EPIC-28, BE-103)
    // =========================================================================

    @Nested
    @DisplayName("getInstallation()")
    class GetInstallation {

        @Test
        @DisplayName("instalacja istnieje dla tenanta → DTO zmapowane poprawnie")
        void existingInstallation_returnsDto() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of("customer:read"))));

            TenantPluginInstallationDto dto = service.getInstallation(TENANT_ID, INSTALLATION_ID);

            assertThat(dto.id()).isEqualTo(INSTALLATION_ID);
            assertThat(dto.tenantId()).isEqualTo(TENANT_ID);
            assertThat(dto.enabled()).isTrue();
            assertThat(dto.grantedPermissions()).containsExactly("customer:read");
        }

        @Test
        @DisplayName("FE-097: wzbogaca DTO o pluginKey/displayName/version/manualActions/uiPanels "
                + "z manifestu zainstalowanej wersji")
        void existingInstallation_enrichesDtoFromManifest() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));
            when(pluginVersionRepository.findById(PLUGIN_VERSION_ID))
                    .thenReturn(Optional.of(buildPluginVersionWithManifestEnrichment()));

            TenantPluginInstallationDto dto = service.getInstallation(TENANT_ID, INSTALLATION_ID);

            assertThat(dto.pluginKey()).isEqualTo("acme-crm-sync");
            assertThat(dto.displayName()).isEqualTo("Acme CRM Sync");
            assertThat(dto.version()).isEqualTo("1.3.0");
            assertThat(dto.manualActions()).hasSize(1);
            assertThat(dto.uiPanels()).hasSize(1);
        }

        @Test
        @DisplayName("instalacja nie istnieje dla tenanta → ResourceNotFoundException (404)")
        void missingInstallation_throwsResourceNotFound() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("instalacja innego tenanta jest niewidoczna – ten serwis nie rozróżnia "
                + "od 'nie istnieje wcale' (RLS filtruje wiersz, repo zwraca Optional.empty() "
                + "tak samo jak dla braku rekordu)")
        void crossTenantInstallation_behavesLikeMissing() {
            // Symuluje RLS: zapytanie repozytorium dla OTHER_TENANT na instalację należącą
            // faktycznie do TENANT_ID nigdy nie zwróci wiersza – stąd ta sama metoda mocka
            // (findByIdAndTenantId z OTHER_TENANT) zwraca Optional.empty(), identycznie jak
            // dla zupełnie nieistniejącego UUID.
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getInstallation(OTHER_TENANT, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // rollback()
    // =========================================================================

    @Nested
    @DisplayName("rollback()")
    class Rollback {

        @Test
        @DisplayName("sukces: target enabled=true, current enabled=false, atomowo w jednej transakcji")
        void rollback_withBothInstallationsOwnedByTenant_switchesEnabledFlags() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));
            when(installationRepository.findByIdAndTenantId(TARGET_INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(TARGET_INSTALLATION_ID, TENANT_ID, false, List.of("customer:read"))));

            TenantPluginInstallationDto result = service.rollback(TENANT_ID, INSTALLATION_ID, TARGET_INSTALLATION_ID);

            assertThat(result.id()).isEqualTo(TARGET_INSTALLATION_ID);
            assertThat(result.enabled()).isTrue();

            verify(installationRepository).updateEnabled(TARGET_INSTALLATION_ID, TENANT_ID, true);
            verify(installationRepository).updateEnabled(INSTALLATION_ID, TENANT_ID, false);
        }

        @Test
        @DisplayName("current instalacja obcego tenanta → ResourceNotFoundException, brak jakiegokolwiek UPDATE")
        void rollback_withCurrentInstallationNotOwnedByTenant_throwsAndSkipsBothUpdates() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rollback(TENANT_ID, INSTALLATION_ID, TARGET_INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            // Atomowość: weryfikacja przynależności OBU instalacji następuje przed jakąkolwiek
            // modyfikacją — żaden wiersz nie jest zmieniany gdy walidacja current zawiedzie.
            verify(installationRepository, never()).updateEnabled(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("target instalacja obcego tenanta → ResourceNotFoundException, brak jakiegokolwiek UPDATE")
        void rollback_withTargetInstallationNotOwnedByTenant_throwsAndSkipsBothUpdates() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));
            when(installationRepository.findByIdAndTenantId(TARGET_INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rollback(TENANT_ID, INSTALLATION_ID, TARGET_INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            // current ZNALEZIONY, ale walidacja target zawodzi PRZED jakimkolwiek UPDATE —
            // żaden z dwóch wierszy nie zmienia stanu enabled (atomowość, ARCHITECTURE.md §11.11).
            verify(installationRepository, never()).updateEnabled(any(), any(), anyBoolean());
        }
    }

    // =========================================================================
    // listInstallations()
    // =========================================================================

    @Nested
    @DisplayName("listInstallations()")
    class ListInstallations {

        @Test
        @DisplayName("mapuje wszystkie instalacje tenanta na DTO, w tym wyłączone")
        void listInstallations_returnsAllMappedToDto() {
            TenantPluginInstallation enabledOne = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of("customer:read"));
            TenantPluginInstallation disabledOne = buildInstallation(TARGET_INSTALLATION_ID, TENANT_ID, false, List.of());

            when(installationRepository.findAllByTenantId(TENANT_ID))
                    .thenReturn(List.of(enabledOne, disabledOne));

            List<TenantPluginInstallationDto> result = service.listInstallations(TENANT_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).enabled()).isTrue();
            assertThat(result.get(1).enabled()).isFalse();
        }

        @Test
        @DisplayName("FE-097: wzbogaca DTO o pluginKey/displayName/version/manualActions/uiPanels "
                + "z manifestu zainstalowanej wersji, batch fetch zamiast N+1")
        void listInstallations_enrichesDtoFromManifest_withBatchFetch() {
            TenantPluginInstallation installation =
                    buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of("customer:read"));
            PluginVersion versionWithManifest = buildPluginVersionWithManifestEnrichment();

            when(installationRepository.findAllByTenantId(TENANT_ID))
                    .thenReturn(List.of(installation));
            when(pluginVersionRepository.findAllById(List.of(PLUGIN_VERSION_ID)))
                    .thenReturn(List.of(versionWithManifest));

            List<TenantPluginInstallationDto> result = service.listInstallations(TENANT_ID);

            assertThat(result).hasSize(1);
            TenantPluginInstallationDto dto = result.get(0);
            assertThat(dto.pluginKey()).isEqualTo("acme-crm-sync");
            assertThat(dto.displayName()).isEqualTo("Acme CRM Sync");
            assertThat(dto.version()).isEqualTo("1.3.0");
            assertThat(dto.manualActions()).extracting("actionId", "label", "mountPoint")
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(
                            "open-in-crm", "Otwórz w CRM", "AGENT_DESKTOP_TOOLBAR"));
            assertThat(dto.uiPanels()).extracting("panelId", "mountPoint", "url")
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(
                            "crm-side-panel", "AGENT_DESKTOP_SIDE_PANEL", "classpath:/plugin-ui/index.html"));

            // findById per-instalacja NIE jest wołane — wyłącznie batch findAllById.
            verify(pluginVersionRepository, never()).findById(any());
        }

        @Test
        @DisplayName("instalacja z brakującą/usuniętą wersją pluginu nie powoduje błędu — "
                + "pola wzbogacenia pozostają null/puste, nie 500")
        void listInstallations_missingPluginVersion_returnsNullEnrichmentWithoutError() {
            TenantPluginInstallation installation =
                    buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());

            when(installationRepository.findAllByTenantId(TENANT_ID))
                    .thenReturn(List.of(installation));
            when(pluginVersionRepository.findAllById(List.of(PLUGIN_VERSION_ID)))
                    .thenReturn(List.of());

            List<TenantPluginInstallationDto> result = service.listInstallations(TENANT_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).pluginKey()).isNull();
            assertThat(result.get(0).displayName()).isNull();
            assertThat(result.get(0).version()).isNull();
            assertThat(result.get(0).manualActions()).isEmpty();
            assertThat(result.get(0).uiPanels()).isEmpty();
        }
    }

    @Nested
    @DisplayName("uninstall() (BE-106)")
    class Uninstall {

        @Test
        @DisplayName("sukces: weryfikuje ownership, potem DELETE")
        void uninstall_existingInstallation_deletesRow() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            service.uninstall(TENANT_ID, INSTALLATION_ID);

            verify(installationRepository).delete(INSTALLATION_ID, TENANT_ID);
        }

        @Test
        @DisplayName("instalacja nieistniejąca dla tenanta → ResourceNotFoundException, DELETE nie wywołane")
        void uninstall_nonExistentInstallation_throwsAndDoesNotDelete() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.uninstall(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).delete(any(), any());
        }

        @Test
        @DisplayName("instalacja innego tenanta jest niewidoczna (RLS) → ResourceNotFoundException")
        void uninstall_otherTenantInstallation_throwsResourceNotFoundException() {
            // findByIdAndTenantId(id, TENANT_ID) zwraca empty, bo wiersz istnieje tylko dla OTHER_TENANT —
            // identycznie jak w enable/disable/getInstallation (konwencja "nie ujawniaj istnienia zasobu").
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.uninstall(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).delete(any(), any());
        }
    }

    // =========================================================================
    // updateConfig() (BE-108)
    // =========================================================================

    @Nested
    @DisplayName("updateConfig()")
    class UpdateConfig {

        @Test
        @DisplayName("sukces: weryfikuje ownership, serializuje config do JSON i woła repo.updateInstallationConfig")
        void updateConfig_existingInstallation_serializesAndDelegatesToRepository() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));

            Map<String, String> config = Map.of("googleApiKey", "secret-value", "searchEngineId", "cx-1");
            service.updateConfig(TENANT_ID, INSTALLATION_ID, config);

            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(installationRepository).updateInstallationConfig(eq(INSTALLATION_ID), eq(TENANT_ID), captor.capture());

            String serialized = captor.getValue();
            assertThat(serialized).contains("googleApiKey", "secret-value", "searchEngineId", "cx-1");
        }

        @Test
        @DisplayName("config=null traktowane jak pusta mapa (czyści konfigurację, brak NPE)")
        void updateConfig_nullConfig_treatedAsEmptyMap() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of())));

            service.updateConfig(TENANT_ID, INSTALLATION_ID, null);

            verify(installationRepository).updateInstallationConfig(INSTALLATION_ID, TENANT_ID, "{}");
        }

        @Test
        @DisplayName("instalacja nie istnieje dla tenanta → ResourceNotFoundException, brak zapisu")
        void updateConfig_missingInstallation_throwsAndSkipsWrite() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateConfig(TENANT_ID, INSTALLATION_ID, Map.of("k", "v")))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).updateInstallationConfig(any(), any(), any());
        }

        @Test
        @DisplayName("instalacja innego tenanta jest niewidoczna (RLS) → ResourceNotFoundException")
        void updateConfig_otherTenantInstallation_throwsResourceNotFoundException() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateConfig(OTHER_TENANT, INSTALLATION_ID, Map.of("k", "v")))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(installationRepository, never()).updateInstallationConfig(any(), any(), any());
        }
    }

    // =========================================================================
    // getConfigEntries()
    // =========================================================================

    @Nested
    @DisplayName("getConfigEntries()")
    class GetConfigEntries {

        @Test
        @DisplayName("klucze tajne mają value=null, klucze jawne mają odszyfrowaną wartość")
        void getConfigEntries_mixedKeys_secretsHaveNullValue() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            // googleApiKey → tajny (zawiera "key"); searchEngineId → jawny
            installation.setInstallationConfig("{\"googleApiKey\":\"secret-value\",\"searchEngineId\":\"cx-1\"}");
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            List<PluginConfigEntryDto> result = service.getConfigEntries(TENANT_ID, INSTALLATION_ID);

            assertThat(result).hasSize(2);
            // wynik posortowany alfabetycznie: googleApiKey < searchEngineId
            PluginConfigEntryDto apiKeyEntry = result.stream()
                    .filter(e -> "googleApiKey".equals(e.key())).findFirst().orElseThrow();
            assertThat(apiKeyEntry.secret()).isTrue();
            assertThat(apiKeyEntry.value()).isNull();

            PluginConfigEntryDto engineEntry = result.stream()
                    .filter(e -> "searchEngineId".equals(e.key())).findFirst().orElseThrow();
            assertThat(engineEntry.secret()).isFalse();
            assertThat(engineEntry.value()).isEqualTo("cx-1");
        }

        @Test
        @DisplayName("wynik posortowany alfabetycznie po kluczu")
        void getConfigEntries_sortedAlphabetically() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            installation.setInstallationConfig("{\"zUrl\":\"http://z\",\"aEndpoint\":\"http://a\",\"mPath\":\"/m\"}");
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            List<PluginConfigEntryDto> result = service.getConfigEntries(TENANT_ID, INSTALLATION_ID);

            assertThat(result).extracting(PluginConfigEntryDto::key)
                    .containsExactly("aEndpoint", "mPath", "zUrl");
        }

        @Test
        @DisplayName("heurystyka tajności: token, secret, password, key (case-insensitive) → secret=true, value=null")
        void getConfigEntries_secretHeuristicMatchesAllPatterns() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            installation.setInstallationConfig(
                    "{\"authToken\":\"t\",\"clientSecret\":\"s\",\"adminPassword\":\"p\",\"apiKey\":\"k\",\"endpoint\":\"http://x\"}");
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            List<PluginConfigEntryDto> result = service.getConfigEntries(TENANT_ID, INSTALLATION_ID);

            // authToken, clientSecret, adminPassword, apiKey → tajne; endpoint → jawny
            assertThat(result.stream().filter(PluginConfigEntryDto::secret).map(PluginConfigEntryDto::key))
                    .containsExactlyInAnyOrder("authToken", "clientSecret", "adminPassword", "apiKey");
            assertThat(result.stream().filter(e -> !e.secret()).map(PluginConfigEntryDto::key))
                    .containsExactly("endpoint");
            assertThat(result.stream().filter(e -> !e.secret()).map(PluginConfigEntryDto::value))
                    .containsExactly("http://x");
        }

        @Test
        @DisplayName("installationConfig null → pusta lista, brak wyjątku")
        void getConfigEntries_nullConfig_returnsEmptyList() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            installation.setInstallationConfig(null);
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            List<PluginConfigEntryDto> result = service.getConfigEntries(TENANT_ID, INSTALLATION_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("installationConfig blank → pusta lista, brak wyjątku")
        void getConfigEntries_blankConfig_returnsEmptyList() {
            TenantPluginInstallation installation = buildInstallation(INSTALLATION_ID, TENANT_ID, true, List.of());
            installation.setInstallationConfig("   ");
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.of(installation));

            List<PluginConfigEntryDto> result = service.getConfigEntries(TENANT_ID, INSTALLATION_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("instalacja nie istnieje dla tenanta → ResourceNotFoundException (404)")
        void getConfigEntries_missingInstallation_throwsResourceNotFound() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, TENANT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConfigEntries(TENANT_ID, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("instalacja innego tenanta jest niewidoczna (RLS) → ResourceNotFoundException")
        void getConfigEntries_otherTenantInstallation_throwsResourceNotFound() {
            when(installationRepository.findByIdAndTenantId(INSTALLATION_ID, OTHER_TENANT))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConfigEntries(OTHER_TENANT, INSTALLATION_ID))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    /**
     * Fixture dla testów dziedziczenia configu (BE-111) — w odróżnieniu od
     * {@link #buildPluginVersionWithPermissions}, ustawia {@link Plugin} z konkretnym
     * {@code pluginKey}, bo {@link PluginRegistrationServiceImpl#findInheritedConfig}
     * identyfikuje "ten sam plugin" wyłącznie przez {@code Plugin.pluginKey}.
     */
    private static PluginVersion buildPluginVersionWithPlugin(UUID pluginVersionId, String pluginKey,
                                                                String version, List<String> permissions) {
        Plugin plugin = Plugin.builder()
                .id(UUID.randomUUID())
                .pluginKey(pluginKey)
                .displayName(pluginKey)
                .vendor("Test Vendor")
                .build();

        return PluginVersion.builder()
                .id(pluginVersionId)
                .tenantId(TENANT_ID)  // wymagane od V078: install() weryfikuje per-tenant ownership
                .plugin(plugin)
                .version(version)
                .jarObjectKey("plugins/" + pluginKey + "/" + version + ".jar")
                .checksumSha256("a".repeat(64))
                .manifestJson(Map.of("permissions", permissions))
                .sdkVersion("1.x")
                .uploadedAt(Instant.now())
                .build();
    }

    private static PluginVersion buildPluginVersionWithPermissions(List<String> permissions) {
        return PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .tenantId(TENANT_ID)  // wymagane od V078: install() weryfikuje per-tenant ownership
                .version("1.0.0")
                .jarObjectKey("plugins/acme/1.0.0.jar")
                .checksumSha256("a".repeat(64))
                .manifestJson(Map.of("permissions", permissions))
                .sdkVersion("1.x")
                .uploadedAt(Instant.now())
                .build();
    }

    /**
     * Wersja pluginu z {@link Plugin} powiązanym (displayName/pluginKey) i manifestem niosącym
     * {@code uiPanels}/{@code manualActions} — fixture dla testów wzbogacenia
     * {@link TenantPluginInstallationDto} (FE-097).
     */
    private static PluginVersion buildPluginVersionWithManifestEnrichment() {
        Plugin plugin = Plugin.builder()
                .id(UUID.fromString("66666666-6666-6666-6666-666666666666"))
                .pluginKey("acme-crm-sync")
                .displayName("Acme CRM Sync")
                .vendor("Acme Sp. z o.o.")
                .build();

        Map<String, Object> manifestJson = Map.of(
                "permissions", List.of("customer:read"),
                "manualActions", List.of(Map.of(
                        "actionId", "open-in-crm",
                        "label", "Otwórz w CRM",
                        "mountPoint", "AGENT_DESKTOP_TOOLBAR")),
                "uiPanels", List.of(Map.of(
                        "panelId", "crm-side-panel",
                        "mountPoint", "AGENT_DESKTOP_SIDE_PANEL",
                        "url", "classpath:/plugin-ui/index.html",
                        "sandbox", "allow-scripts")));

        return PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .plugin(plugin)
                .version("1.3.0")
                .jarObjectKey("plugins/acme/1.3.0.jar")
                .checksumSha256("a".repeat(64))
                .manifestJson(manifestJson)
                .sdkVersion("1.x")
                .uploadedAt(Instant.now())
                .build();
    }

    private static TenantPluginInstallation buildInstallation(UUID id, UUID tenantId, boolean enabled,
                                                                List<String> grantedPermissions) {
        TenantPluginInstallation i = new TenantPluginInstallation();
        i.setId(id);
        i.setTenantId(tenantId);
        i.setPluginVersionId(PLUGIN_VERSION_ID);
        i.setEnabled(enabled);
        i.setGrantedPermissions(grantedPermissions);
        i.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        i.setConsecutiveFailureCount(0);
        i.setInstallationConfig(null);
        i.setInstalledByUserId(INSTALLED_BY);
        i.setInstalledAt(Instant.now());
        i.setUpdatedAt(Instant.now());
        return i;
    }
}
