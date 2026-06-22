package com.contactcenter.api.plugin;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.plugin.dto.PluginInvocationLogDto;
import com.contactcenter.domain.plugin.runtime.InvocationStatus;
import com.contactcenter.domain.plugin.runtime.PluginInvocationLogService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy jednostkowe dla {@link PluginInvocationLogController}
 * (GET /api/supervisor/plugins/{installationId}/invocations, EPIC-28, BE-105).
 *
 * <p>Wzorzec analogiczny do {@code PluginManualActionControllerTest}: wywołanie metod
 * kontrolera bezpośrednio, {@code TenantContext} mockowany statycznie. Walidacja
 * {@code @PreAuthorize}/binding parametrów Spring MVC (np. enum nierozpoznany w query param ->
 * 400) jest odpowiedzialnością frameworka i nie jest pokrywana przy wywołaniu bezpośrednim —
 * pokrywa ją konfiguracja {@code SecurityConfig}/Spring MVC, poza zakresem testu jednostkowego
 * kontrolera (zgodnie z konwencją reszty modułu {@code api.plugin}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginInvocationLogController – GET /api/supervisor/plugins/{installationId}/invocations")
class PluginInvocationLogControllerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();

    @Mock private PluginInvocationLogService pluginInvocationLogService;

    private PluginInvocationLogController controller;
    private MockedStatic<TenantContext> tenantContextMock;

    @BeforeEach
    void setUp() {
        controller = new PluginInvocationLogController(pluginInvocationLogService);
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        tenantContextMock.close();
    }

    private static PluginInvocationLogDto sampleDto(String status) {
        return new PluginInvocationLogDto(
                UUID.randomUUID(), Instant.now(), INSTALLATION_ID, "PRE_CONTACT_CONNECT",
                null, status, 42, null, null);
    }

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("zwraca 200 z Page<PluginInvocationLogDto> zwróconą przez serwis")
        void returns200WithPageFromService() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<PluginInvocationLogDto> page = new PageImpl<>(List.of(sampleDto("SUCCESS")), pageable, 1);
            when(pluginInvocationLogService.findByInstallation(eq(TENANT_ID), eq(INSTALLATION_ID), isNull(), eq(pageable)))
                    .thenReturn(page);

            ResponseEntity<Page<PluginInvocationLogDto>> response =
                    controller.getInvocationHistory(INSTALLATION_ID, 0, 20, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getTotalElements()).isEqualTo(1);
            assertThat(response.getBody().getContent().get(0).status()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("przekazuje page/size jako PageRequest, status jako InvocationStatus do serwisu")
        void passesPageSizeStatusToService() {
            when(pluginInvocationLogService.findByInstallation(any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getInvocationHistory(INSTALLATION_ID, 2, 10, InvocationStatus.FAILED);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(pluginInvocationLogService).findByInstallation(
                    eq(TENANT_ID), eq(INSTALLATION_ID), eq(InvocationStatus.FAILED), pageableCaptor.capture());

            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("status == null -> przekazany jako null do serwisu (brak filtra)")
        void nullStatus_passedAsNullFilter() {
            when(pluginInvocationLogService.findByInstallation(any(), any(), isNull(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            controller.getInvocationHistory(INSTALLATION_ID, 0, 20, null);

            verify(pluginInvocationLogService).findByInstallation(
                    eq(TENANT_ID), eq(INSTALLATION_ID), isNull(), any());
        }
    }

    @Nested
    @DisplayName("Ownership – 404 dla nieistniejącej/cross-tenant instalacji")
    class OwnershipTests {

        @Test
        @DisplayName("instalacja nieistniejąca – ResourceNotFoundException propaguje się (404 przez GlobalExceptionHandler)")
        void nonExistentInstallation_propagatesResourceNotFound() {
            when(pluginInvocationLogService.findByInstallation(eq(TENANT_ID), eq(INSTALLATION_ID), any(), any()))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            assertThatThrownBy(() -> controller.getInvocationHistory(INSTALLATION_ID, 0, 20, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("instalacja innego tenanta – RLS sprawia, że serwis rzuca identyczny "
                + "ResourceNotFoundException jak dla nieistniejącej (świadoma decyzja 404 dla obu przypadków, "
                + "konwencja ustalona w BE-103)")
        void crossTenantInstallation_alsoResourceNotFound() {
            when(pluginInvocationLogService.findByInstallation(eq(TENANT_ID), eq(INSTALLATION_ID), any(), any()))
                    .thenThrow(new ResourceNotFoundException("Instalacja nie istnieje: " + INSTALLATION_ID));

            assertThatThrownBy(() -> controller.getInvocationHistory(INSTALLATION_ID, 0, 20, null))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
