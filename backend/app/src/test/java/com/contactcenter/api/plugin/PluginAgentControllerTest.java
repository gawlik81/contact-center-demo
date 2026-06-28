package com.contactcenter.api.plugin;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe {@link PluginAgentController} — widok agenta na pluginy
 * zainstalowane dla tenanta (EPIC-28, kontynuacja BE-100/BE-106 odkryta podczas
 * budowy frontendu, FE-100).
 *
 * <p>Wzorzec analogiczny do {@code PluginAdminControllerTest}: wywołanie metod kontrolera
 * bezpośrednio, {@code TenantContext} mockowany statycznie. Autoryzacja (@PreAuthorize)
 * nie jest testowana na tym poziomie — projekt nie ma {@code @WebMvcTest} dla pakietu
 * {@code api.plugin} (zweryfikowano w {@code PluginAdminControllerTest}), więc role są
 * weryfikowane deklaratywnie przez code review, nie testem jednostkowym.
 *
 * <p>Kryterium akceptacji: lista zwracana przez kontroler zawiera WYŁĄCZNIE instalacje
 * z {@code enabled=true}, niezależnie od tego, co zwraca {@code listInstallations}
 * (które zwraca wszystkie instalacje, włącznie z disabled — BE-100).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginAgentController – widok agenta na włączone pluginy")
class PluginAgentControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock private PluginRegistrationService pluginRegistrationService;

    private PluginAgentController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new PluginAgentController(pluginRegistrationService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private TenantPluginInstallationDto installationDto(UUID id, boolean enabled) {
        return new TenantPluginInstallationDto(
                id, TENANT_ID, UUID.randomUUID(), "acme-crm-sync", "Acme CRM Sync", "1.0.0",
                enabled, List.of(), "HEALTHY", 0, List.of(), List.of(), USER_ID, Instant.now(), Instant.now(),
                List.of());
    }

    @Test
    @DisplayName("KRYTERIUM AKCEPTACJI: filtruje wynik serwisu do enabled=true")
    void listEnabledInstallations_filtersOutDisabled() {
        UUID enabledId = UUID.randomUUID();
        UUID disabledId = UUID.randomUUID();
        when(pluginRegistrationService.listInstallations(TENANT_ID)).thenReturn(List.of(
                installationDto(enabledId, true),
                installationDto(disabledId, false)
        ));

        ResponseEntity<List<TenantPluginInstallationDto>> response = controller.listEnabledInstallations();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(TenantPluginInstallationDto::id)
                .containsExactly(enabledId);
    }

    @Test
    @DisplayName("zwraca pustą listę gdy tenant nie ma żadnej włączonej instalacji")
    void listEnabledInstallations_noneEnabled_returnsEmptyList() {
        when(pluginRegistrationService.listInstallations(TENANT_ID)).thenReturn(List.of(
                installationDto(UUID.randomUUID(), false)
        ));

        ResponseEntity<List<TenantPluginInstallationDto>> response = controller.listEnabledInstallations();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("deleguje do listInstallations z tenantId z TenantContext")
    void listEnabledInstallations_usesTenantContext() {
        when(pluginRegistrationService.listInstallations(TENANT_ID)).thenReturn(List.of());

        controller.listEnabledInstallations();

        org.mockito.Mockito.verify(pluginRegistrationService).listInstallations(TENANT_ID);
    }
}
