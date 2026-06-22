package com.contactcenter.api.plugin;

import com.contactcenter.api.plugin.dto.ManualActionRequestDto;
import com.contactcenter.api.plugin.dto.ManualActionResponseDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.Plugin;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.PluginVersion;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.domain.plugin.runtime.ExtensionPointPublisher;
import com.contactcenter.domain.plugin.runtime.PluginInvocationProperties;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PluginManualActionController} (EPIC-28, BE-103).
 *
 * <p>Wzorzec analogiczny do {@code PluginUploadControllerTest}: wywołanie metod kontrolera
 * bezpośrednio, {@code TenantContext} mockowany statycznie.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginManualActionController – POST /api/agent/plugins/{installationId}/manual-action/{actionId}")
class PluginManualActionControllerTest {

    private static final UUID TENANT_ID       = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID PLUGIN_VERSION_ID = UUID.randomUUID();
    private static final UUID CONTACT_ID      = UUID.randomUUID();
    private static final UUID CUSTOMER_ID     = UUID.randomUUID();
    private static final String ACTION_ID     = "open-in-crm";
    private static final long TIMEOUT_MS      = 5000L;

    @Mock private PluginRegistrationService pluginRegistrationService;
    @Mock private PluginCatalogQueryService pluginCatalogQueryService;
    @Mock private ExtensionPointPublisher extensionPointPublisher;
    @Mock private PluginInvocationProperties pluginInvocationProperties;

    private PluginManualActionController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new PluginManualActionController(
                pluginRegistrationService, pluginCatalogQueryService, extensionPointPublisher,
                pluginInvocationProperties);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        // Domyślnie: wersja pluginu istnieje i NIE jest REVOKED — testy weryfikujące 403 nadpisują
        // ten stub jawnie. "lenient" bo testy ownership (404 przed dotarciem do tej weryfikacji)
        // nigdy nie wywołują findVersionById.
        lenient().when(pluginCatalogQueryService.findVersionById(PLUGIN_VERSION_ID))
                .thenReturn(Optional.of(nonRevokedVersion()));
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private TenantPluginInstallationDto existingInstallation() {
        return existingInstallation(true, "HEALTHY");
    }

    private TenantPluginInstallationDto existingInstallation(boolean enabled, String healthStatus) {
        return new TenantPluginInstallationDto(
                INSTALLATION_ID, TENANT_ID, PLUGIN_VERSION_ID, enabled, List.of(),
                healthStatus, 0, UUID.randomUUID(), Instant.now(), Instant.now());
    }

    private PluginVersion nonRevokedVersion() {
        return pluginVersionWithStatus(PluginVersion.PluginVersionStatus.VALIDATED);
    }

    private PluginVersion pluginVersionWithStatus(PluginVersion.PluginVersionStatus status) {
        Plugin plugin = new Plugin();
        plugin.setPluginKey("acme-crm-sync");
        return PluginVersion.builder()
                .id(PLUGIN_VERSION_ID)
                .plugin(plugin)
                .version("1.0.0")
                .status(status)
                .build();
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("zwraca 200 z wynikiem pluginu gdy wywołanie zakończyło się w budżecie")
        void returns200WithResultWhenWithinBudget() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);

            ManualActionResult result = new ManualActionResult(true, Map.of("ticketUrl", "https://crm/123"), null);
            when(extensionPointPublisher.publishManualAction(eq(TENANT_ID), eq(INSTALLATION_ID), any()))
                    .thenReturn(result);

            ManualActionRequestDto request = new ManualActionRequestDto(CONTACT_ID, CUSTOMER_ID, Map.of("foo", "bar"));
            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();
            assertThat(response.getBody().resultData()).containsEntry("ticketUrl", "https://crm/123");
            assertThat(response.getBody().error()).isNull();
        }

        @Test
        @DisplayName("przekazuje actionId/contactId/customerId/payload do ManualActionRequest")
        void passesRequestFieldsToManualActionRequest() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenReturn(ManualActionResult.unsupported());

            ManualActionRequestDto request = new ManualActionRequestDto(CONTACT_ID, CUSTOMER_ID, Map.of("foo", "bar"));
            controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, request);

            ArgumentCaptor<ManualActionRequest> captor = ArgumentCaptor.forClass(ManualActionRequest.class);
            verify(extensionPointPublisher).publishManualAction(eq(TENANT_ID), eq(INSTALLATION_ID), captor.capture());

            ManualActionRequest captured = captor.getValue();
            assertThat(captured.actionId()).isEqualTo(ACTION_ID);
            assertThat(captured.contactId()).isEqualTo(CONTACT_ID);
            assertThat(captured.customerId()).isEqualTo(CUSTOMER_ID);
            assertThat(captured.parameters()).containsEntry("foo", "bar");
        }

        @Test
        @DisplayName("body null jest traktowane jako brak payloadu/contactId/customerId (pusta mapa, nie NPE)")
        void nullBodyHandledGracefully() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenReturn(ManualActionResult.unsupported());

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            ArgumentCaptor<ManualActionRequest> captor = ArgumentCaptor.forClass(ManualActionRequest.class);
            verify(extensionPointPublisher).publishManualAction(any(), any(), captor.capture());
            assertThat(captor.getValue().parameters()).isEmpty();
            assertThat(captor.getValue().contactId()).isNull();
        }

        @Test
        @DisplayName("plugin zwraca unsupported szybko (nie timeout) – wciąż 200, nie 504")
        void unsupportedWithinBudget_returns200() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);
            // Mock zwraca natychmiast – elapsedMs będzie bliskie 0, znacznie poniżej timeoutu 5000ms.
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenReturn(ManualActionResult.unsupported());

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().message()).isEqualTo("Action not supported by plugin");
            assertThat(response.getBody().error()).isNull();
        }
    }

    // =========================================================================
    // Timeout -> 504
    // =========================================================================

    @Nested
    @DisplayName("Przekroczenie budżetu czasowego")
    class TimeoutTests {

        @Test
        @DisplayName("publisher trwający >= timeout zwraca 504 z ciałem JSON (nie wyjątek)")
        void slowPublisherReturns504() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            // Symulujemy budżet bardzo mały (1ms), żeby test nie czekał realnie 5s.
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(1L);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenAnswer(invocation -> {
                        Thread.sleep(20);
                        return ManualActionResult.unsupported();
                    });

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).contains("1ms");
            assertThat(response.getBody().message()).isNull();
        }

        @Test
        @DisplayName("success=true mimo długiego czasu NIE jest mapowane na 504 (tylko success=false podlega regule)")
        void successfulSlowCallNeverTimesOut() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(1L);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenAnswer(invocation -> {
                        Thread.sleep(20);
                        return new ManualActionResult(true, Map.of(), "ok");
                    });

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // =========================================================================
    // Ownership – 404 dla nieistniejącej/cross-tenant instalacji
    // =========================================================================

    @Nested
    @DisplayName("Weryfikacja ownership instalacji")
    class OwnershipTests {

        @Test
        @DisplayName("instalacja nieistniejąca – ResourceNotFoundException propaguje się (404 przez GlobalExceptionHandler)")
        void nonExistentInstallation_propagatesResourceNotFound() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            assertThatThrownBy(() -> controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(extensionPointPublisher, never()).publishManualAction(any(), any(), any());
        }

        @Test
        @DisplayName("instalacja innego tenanta – RLS sprawia, że getInstallation rzuca identyczny "
                + "ResourceNotFoundException jak dla nieistniejącej (świadoma decyzja 404 dla obu przypadków)")
        void crossTenantInstallation_alsoResourceNotFound() {
            // RLS w TenantPluginInstallationRepository filtruje po tenant_id – instalacja innego
            // tenanta jest niewidoczna, getInstallation zwraca to samo zachowanie co "nie istnieje".
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            assertThatThrownBy(() -> controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(extensionPointPublisher, never()).publishManualAction(any(), any(), any());
        }

        @Test
        @DisplayName("ownership weryfikowany PRZED wywołaniem publishManualAction")
        void ownershipCheckedBeforePublisherInvocation() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenReturn(ManualActionResult.unsupported());

            controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            verify(pluginRegistrationService).getInstallation(TENANT_ID, INSTALLATION_ID);
            verify(extensionPointPublisher).publishManualAction(eq(TENANT_ID), eq(INSTALLATION_ID), any());
        }
    }

    // =========================================================================
    // BE-107: odmowa 403 dla instalacji disabled/DISABLED_BY_ADMIN/REVOKED
    // =========================================================================

    @Nested
    @DisplayName("BE-107 – odmowa wywołania (403)")
    class ForbiddenTests {

        @Test
        @DisplayName("enabled=false -> 403, publishManualAction nigdy wywołane")
        void disabledInstallation_returns403() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation(false, "HEALTHY"));

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).contains("enabled=false");
            verify(extensionPointPublisher, never()).publishManualAction(any(), any(), any());
        }

        @Test
        @DisplayName("health_status=DISABLED_BY_ADMIN -> 403, publishManualAction nigdy wywołane")
        void disabledByAdmin_returns403() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation(true,
                            TenantPluginInstallation.HealthStatus.DISABLED_BY_ADMIN));

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().error()).contains("DISABLED_BY_ADMIN");
            verify(extensionPointPublisher, never()).publishManualAction(any(), any(), any());
        }

        @Test
        @DisplayName("plugin_version.status=REVOKED -> 403, publishManualAction nigdy wywołane")
        void revokedVersion_returns403() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginCatalogQueryService.findVersionById(PLUGIN_VERSION_ID))
                    .thenReturn(Optional.of(pluginVersionWithStatus(PluginVersion.PluginVersionStatus.REVOKED)));

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().error()).contains("REVOKED");
            verify(extensionPointPublisher, never()).publishManualAction(any(), any(), any());
        }

        @Test
        @DisplayName("instalacja enabled, HEALTHY, wersja VALIDATED -> wywołanie dozwolone (regresja happy path)")
        void invokableInstallation_proceedsToPublisher() {
            when(pluginRegistrationService.getInstallation(TENANT_ID, INSTALLATION_ID))
                    .thenReturn(existingInstallation());
            when(pluginInvocationProperties.effectiveManualActionTimeoutMs()).thenReturn(TIMEOUT_MS);
            when(extensionPointPublisher.publishManualAction(any(), any(), any()))
                    .thenReturn(ManualActionResult.unsupported());

            ResponseEntity<ManualActionResponseDto> response =
                    controller.invokeManualAction(INSTALLATION_ID, ACTION_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(extensionPointPublisher).publishManualAction(eq(TENANT_ID), eq(INSTALLATION_ID), any());
        }
    }
}
