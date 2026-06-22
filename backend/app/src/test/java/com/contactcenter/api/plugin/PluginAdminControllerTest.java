package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.InstallPluginRequest;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.plugin.runtime.PluginRuntimeManager;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link PluginAdminController} (EPIC-28, BE-106).
 *
 * <p>Wzorzec analogiczny do {@code PluginManualActionControllerTest}: wywołanie metod
 * kontrolera bezpośrednio, {@code TenantContext} mockowany statycznie. Autoryzacja
 * (@PreAuthorize) nie jest testowana na tym poziomie — projekt nie ma {@code @WebMvcTest}
 * dla pakietu {@code api.plugin} (zweryfikowano), więc role są weryfikowane deklaratywnie
 * przez code review, nie testem jednostkowym.
 *
 * <p>Pokrywa kryteria akceptacji BE-106:
 * <ul>
 *   <li>enable: woła {@code PluginRuntimeManager#load} TYLKO jeśli {@code isLoaded}==false</li>
 *   <li>disable: {@code unload} wywołane PRZED {@code disable} (kolejność, weryfikowana {@code InOrder})</li>
 *   <li>rollback: load targetu (jeśli nie załadowany) + unload current, PO delegacji do serwisu</li>
 *   <li>uninstall: unload best-effort (nie blokuje na wyjątku) + DELETE w serwisie</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginAdminController – install/enable/disable/rollback/uninstall (BE-106)")
class PluginAdminControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID TARGET_INSTALLATION_ID = UUID.randomUUID();
    private static final UUID PLUGIN_VERSION_ID = UUID.randomUUID();

    @Mock private PluginRegistrationService pluginRegistrationService;
    @Mock private PluginRuntimeManager pluginRuntimeManager;

    private PluginAdminController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new PluginAdminController(pluginRegistrationService, pluginRuntimeManager);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::getUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private TenantPluginInstallationDto installationDto(UUID id, boolean enabled) {
        return new TenantPluginInstallationDto(
                id, TENANT_ID, PLUGIN_VERSION_ID, enabled, List.of(),
                "HEALTHY", 0, USER_ID, Instant.now(), Instant.now());
    }

    // =========================================================================
    // GET listInstallations
    // =========================================================================

    @Test
    @DisplayName("listInstallations: deleguje do serwisu z tenantId z TenantContext")
    void listInstallations_delegatesToService() {
        List<TenantPluginInstallationDto> expected = List.of(installationDto(INSTALLATION_ID, true));
        when(pluginRegistrationService.listInstallations(TENANT_ID)).thenReturn(expected);

        ResponseEntity<List<TenantPluginInstallationDto>> response = controller.listInstallations();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    // =========================================================================
    // POST install
    // =========================================================================

    @Nested
    @DisplayName("install()")
    class Install {

        @Test
        @DisplayName("201 z DTO instalacji, request body opcjonalny")
        void install_withNullRequest_usesEmptyPermissions() {
            TenantPluginInstallationDto created = installationDto(INSTALLATION_ID, false);
            when(pluginRegistrationService.install(TENANT_ID, PLUGIN_VERSION_ID, List.of(), USER_ID))
                    .thenReturn(created);

            ResponseEntity<TenantPluginInstallationDto> response = controller.install(PLUGIN_VERSION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isEqualTo(created);
        }

        @Test
        @DisplayName("201 przekazuje grantedPermissions z request body")
        void install_withPermissions_passesThemToService() {
            InstallPluginRequest request = new InstallPluginRequest(PLUGIN_VERSION_ID, List.of("customer:read"));
            TenantPluginInstallationDto created = installationDto(INSTALLATION_ID, false);
            when(pluginRegistrationService.install(TENANT_ID, PLUGIN_VERSION_ID, List.of("customer:read"), USER_ID))
                    .thenReturn(created);

            ResponseEntity<TenantPluginInstallationDto> response = controller.install(PLUGIN_VERSION_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // =========================================================================
    // POST enable
    // =========================================================================

    @Nested
    @DisplayName("enable()")
    class Enable {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: woła load() gdy instalacja NIE jest jeszcze załadowana")
        void enable_notLoaded_callsLoad() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, false));
            when(pluginRuntimeManager.isLoaded(INSTALLATION_ID)).thenReturn(false);

            ResponseEntity<Void> response = controller.enable(INSTALLATION_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(pluginRuntimeManager).load(TENANT_ID, INSTALLATION_ID);
            verify(pluginRegistrationService).enable(TENANT_ID, INSTALLATION_ID);
        }

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI (idempotentność): NIE woła load() ponownie gdy już załadowana")
        void enable_alreadyLoaded_doesNotCallLoadAgain() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, false));
            when(pluginRuntimeManager.isLoaded(INSTALLATION_ID)).thenReturn(true);

            controller.enable(INSTALLATION_ID);

            verify(pluginRuntimeManager, never()).load(any(), any());
            verify(pluginRegistrationService).enable(TENANT_ID, INSTALLATION_ID);
        }

        @Test
        @DisplayName("ownership weryfikowany PRZED jakąkolwiek mutacją runtime")
        void enable_verifiesOwnershipBeforeRuntimeMutation() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, false));
            when(pluginRuntimeManager.isLoaded(INSTALLATION_ID)).thenReturn(false);

            controller.enable(INSTALLATION_ID);

            InOrder order = inOrder(pluginRegistrationService, pluginRuntimeManager);
            order.verify(pluginRegistrationService).getInstallation(TENANT_ID, INSTALLATION_ID);
            order.verify(pluginRuntimeManager).load(TENANT_ID, INSTALLATION_ID);
            order.verify(pluginRegistrationService).enable(TENANT_ID, INSTALLATION_ID);
        }
    }

    // =========================================================================
    // POST disable
    // =========================================================================

    @Nested
    @DisplayName("disable()")
    class Disable {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: unload() wywołane PRZED disable() (kolejność krytyczna)")
        void disable_unloadsRuntimeBeforeUpdatingDbFlag() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            ResponseEntity<Void> response = controller.disable(INSTALLATION_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            InOrder order = inOrder(pluginRuntimeManager, pluginRegistrationService);
            order.verify(pluginRuntimeManager).unload(TENANT_ID, INSTALLATION_ID);
            order.verify(pluginRegistrationService).disable(TENANT_ID, INSTALLATION_ID);
        }
    }

    // =========================================================================
    // POST rollback
    // =========================================================================

    @Nested
    @DisplayName("rollback()")
    class Rollback {

        @Test
        @DisplayName("KRYTERIUM AKCEPTACJI: deleguje do serwisu, ładuje target jeśli nie aktywny, odładowuje current")
        void rollback_loadsTargetUnloadsCurrent() {
            TenantPluginInstallationDto targetDto = installationDto(TARGET_INSTALLATION_ID, true);
            when(pluginRegistrationService.rollback(TENANT_ID, INSTALLATION_ID, TARGET_INSTALLATION_ID))
                    .thenReturn(targetDto);
            when(pluginRuntimeManager.isLoaded(TARGET_INSTALLATION_ID)).thenReturn(false);

            ResponseEntity<TenantPluginInstallationDto> response =
                    controller.rollback(INSTALLATION_ID, TARGET_INSTALLATION_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(targetDto);
            verify(pluginRuntimeManager).load(TENANT_ID, TARGET_INSTALLATION_ID);
            verify(pluginRuntimeManager).unload(TENANT_ID, INSTALLATION_ID);
        }

        @Test
        @DisplayName("nie duplikuje load() gdy target jest już aktywny w tym węźle")
        void rollback_targetAlreadyLoaded_doesNotReload() {
            when(pluginRegistrationService.rollback(TENANT_ID, INSTALLATION_ID, TARGET_INSTALLATION_ID))
                    .thenReturn(installationDto(TARGET_INSTALLATION_ID, true));
            when(pluginRuntimeManager.isLoaded(TARGET_INSTALLATION_ID)).thenReturn(true);

            controller.rollback(INSTALLATION_ID, TARGET_INSTALLATION_ID);

            verify(pluginRuntimeManager, never()).load(any(), any());
            verify(pluginRuntimeManager).unload(TENANT_ID, INSTALLATION_ID);
        }
    }

    // =========================================================================
    // DELETE uninstall
    // =========================================================================

    @Nested
    @DisplayName("uninstall()")
    class Uninstall {

        @Test
        @DisplayName("204, unload best-effort wywołane przed DELETE w serwisie")
        void uninstall_unloadsThenDeletes() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));

            ResponseEntity<Void> response = controller.uninstall(INSTALLATION_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            InOrder order = inOrder(pluginRuntimeManager, pluginRegistrationService);
            order.verify(pluginRuntimeManager).unload(TENANT_ID, INSTALLATION_ID);
            order.verify(pluginRegistrationService).uninstall(TENANT_ID, INSTALLATION_ID);
        }

        @Test
        @DisplayName("KRYTERIUM: unload() rzucający wyjątek NIE blokuje uninstall (best-effort)")
        void uninstall_unloadThrows_stillDeletesRow() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(installationDto(INSTALLATION_ID, true));
            org.mockito.Mockito.doThrow(new RuntimeException("plugin zawieszony w onDeactivate"))
                    .when(pluginRuntimeManager).unload(TENANT_ID, INSTALLATION_ID);

            ResponseEntity<Void> response = controller.uninstall(INSTALLATION_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(pluginRegistrationService).uninstall(TENANT_ID, INSTALLATION_ID);
        }
    }
}
