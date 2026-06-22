package com.contactcenter.api.plugin;

import com.contactcenter.api.plugin.dto.RevokePluginVersionResponseDto;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link PluginRevokeController} (EPIC-28, BE-106 — platform-level kill switch).
 *
 * <p>Pokrywa kryterium akceptacji kluczowe dla BE-106: <em>"test z 2 tenantami mającymi
 * enabled=true dla tej samej wersji — po revoke PluginRegistry.lookup dla obu zwraca puste,
 * NIEZALEŻNIE od ich enabled w DB"</em>. Na poziomie tego kontrolera weryfikujemy, że
 * {@code unload} jest wywołany dla KAŻDEJ znalezionej instalacji niezależnie od jej tenanta —
 * efekt na {@code PluginRegistry.lookup} jest pokryty przez {@code PluginRegistryImplTest}
 * (BE-101: {@code unregister} usuwa wpis z mapy lookup), więc to wystarcza do end-to-end
 * pokrycia kryterium bez duplikowania testu rejestru.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginRevokeController – platform-level kill switch REVOKED (BE-106)")
class PluginRevokeControllerTest {

    private static final UUID PLUGIN_VERSION_ID = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID INSTALLATION_A = UUID.randomUUID();
    private static final UUID INSTALLATION_B = UUID.randomUUID();

    @Mock private PluginCatalogQueryService pluginCatalogQueryService;
    @Mock private PluginRuntimeManager pluginRuntimeManager;

    private PluginRevokeController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginRevokeController(pluginCatalogQueryService, pluginRuntimeManager);
    }

    private PluginVersion revokedVersion() {
        PluginVersion pluginVersion = PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .version("1.0.0")
                .jarObjectKey("plugins/acme/1.0.0.jar")
                .checksumSha256("a".repeat(64))
                .manifestJson(java.util.Map.of())
                .sdkVersion("1.x")
                .status(PluginVersion.PluginVersionStatus.REVOKED)
                .uploadedAt(Instant.now())
                .build();
        return pluginVersion;
    }

    private TenantPluginInstallation installation(UUID tenantId, UUID installationId) {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(installationId);
        installation.setTenantId(tenantId);
        installation.setPluginVersionId(PLUGIN_VERSION_ID);
        installation.setEnabled(true);
        installation.setGrantedPermissions(List.of());
        installation.setHealthStatus(TenantPluginInstallation.HealthStatus.HEALTHY);
        installation.setInstalledAt(Instant.now());
        installation.setUpdatedAt(Instant.now());
        return installation;
    }

    @Test
    @DisplayName("KRYTERIUM AKCEPTACJI: 2 tenanty enabled=true → unload() wywołane dla OBU instalacji")
    void revoke_twoTenantsWithEnabledInstallations_unloadsBoth() {
        when(pluginCatalogQueryService.revokeVersion(PLUGIN_VERSION_ID)).thenReturn(revokedVersion());

        TenantPluginInstallation installationA = installation(TENANT_A, INSTALLATION_A);
        TenantPluginInstallation installationB = installation(TENANT_B, INSTALLATION_B);
        when(pluginCatalogQueryService.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID))
                .thenReturn(List.of(installationA, installationB));

        ResponseEntity<RevokePluginVersionResponseDto> response = controller.revoke(PLUGIN_VERSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("REVOKED");
        assertThat(response.getBody().deactivatedInstallationCount()).isEqualTo(2);

        verify(pluginRuntimeManager).unload(TENANT_A, INSTALLATION_A);
        verify(pluginRuntimeManager).unload(TENANT_B, INSTALLATION_B);
    }

    @Test
    @DisplayName("brak instalacji enabled żadnego tenanta → status REVOKED, 0 odładowanych, nie rzuca")
    void revoke_noEnabledInstallations_returnsZeroDeactivated() {
        when(pluginCatalogQueryService.revokeVersion(PLUGIN_VERSION_ID)).thenReturn(revokedVersion());
        when(pluginCatalogQueryService.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID))
                .thenReturn(List.of());

        ResponseEntity<RevokePluginVersionResponseDto> response = controller.revoke(PLUGIN_VERSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().deactivatedInstallationCount()).isZero();
    }

    @Test
    @DisplayName("unload() rzucający dla jednej instalacji nie blokuje odładowania pozostałych (best-effort)")
    void revoke_unloadFailsForOneInstallation_continuesWithOthers() {
        when(pluginCatalogQueryService.revokeVersion(PLUGIN_VERSION_ID)).thenReturn(revokedVersion());

        TenantPluginInstallation installationA = installation(TENANT_A, INSTALLATION_A);
        TenantPluginInstallation installationB = installation(TENANT_B, INSTALLATION_B);
        when(pluginCatalogQueryService.findAllEnabledAcrossTenantsByVersionId(PLUGIN_VERSION_ID))
                .thenReturn(List.of(installationA, installationB));

        doThrow(new RuntimeException("plugin zawieszony w onDeactivate"))
                .when(pluginRuntimeManager).unload(TENANT_A, INSTALLATION_A);

        ResponseEntity<RevokePluginVersionResponseDto> response = controller.revoke(PLUGIN_VERSION_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().deactivatedInstallationCount()).isEqualTo(1);
        verify(pluginRuntimeManager).unload(TENANT_B, INSTALLATION_B);
    }
}
