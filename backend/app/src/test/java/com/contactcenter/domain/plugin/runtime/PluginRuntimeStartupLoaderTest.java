package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testy {@link PluginRuntimeStartupLoader} — odbudowa {@link PluginRegistry} po starcie procesu.
 *
 * <p>Pokrywa: ładowanie wielu instalacji wielu tenantów, fault containment (błąd jednej
 * instalacji nie blokuje pozostałych), idempotentność przez {@code isLoaded} guard, oraz
 * poprawne ustawianie/czyszczenie {@link TenantContext} per iteracja.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginRuntimeStartupLoader – odbudowa registry po restarcie procesu")
class PluginRuntimeStartupLoaderTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTALLATION_A = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID INSTALLATION_B = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private PluginCatalogQueryService pluginCatalogQueryService;
    @Mock private PluginRuntimeManager pluginRuntimeManager;

    private PluginRuntimeStartupLoader loader;

    @BeforeEach
    void setUp() {
        loader = new PluginRuntimeStartupLoader(pluginCatalogQueryService, pluginRuntimeManager);
    }

    @AfterEach
    void cleanupTenantContext() {
        // Bezpiecznik: gdyby implementacja nie wyczyściła kontekstu, nie chcemy przeciekania
        // do kolejnych testów uruchamianych na tym samym wątku JVM.
        TenantContext.clear();
    }

    @Test
    @DisplayName("Ładuje wszystkie instalacje enabled=true zwrócone przez catalog query service")
    void loadsAllEnabledInstallations() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                .thenReturn(List.of(installation(TENANT_A, INSTALLATION_A), installation(TENANT_B, INSTALLATION_B)));

        loader.reloadEnabledInstallationsOnStartup();

        verify(pluginRuntimeManager).load(TENANT_A, INSTALLATION_A);
        verify(pluginRuntimeManager).load(TENANT_B, INSTALLATION_B);
    }

    @Test
    @DisplayName("Ustawia i czyści TenantContext wokół load() dla każdej instalacji")
    void setsAndClearsTenantContextAroundEachLoad() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                .thenReturn(List.of(installation(TENANT_A, INSTALLATION_A)));

        // Weryfikacja "w trakcie" load(): TenantContext musi być ustawiony WEWNĄTRZ load(),
        // a po zwrocie metody loader musi go wyczyścić.
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_A);
            return null;
        }).when(pluginRuntimeManager).load(eq(TENANT_A), eq(INSTALLATION_A));

        loader.reloadEnabledInstallationsOnStartup();

        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("Błąd load() dla jednej instalacji nie blokuje ładowania pozostałych (fault containment)")
    void oneFailingInstallationDoesNotBlockOthers() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                .thenReturn(List.of(installation(TENANT_A, INSTALLATION_A), installation(TENANT_B, INSTALLATION_B)));

        org.mockito.Mockito.doThrow(new PluginActivationException("JAR uszkodzony"))
                .when(pluginRuntimeManager).load(TENANT_A, INSTALLATION_A);

        loader.reloadEnabledInstallationsOnStartup();

        verify(pluginRuntimeManager).load(TENANT_A, INSTALLATION_A);
        verify(pluginRuntimeManager).load(TENANT_B, INSTALLATION_B);
        // TenantContext nie przeciekł do drugiej iteracji mimo wyjątku w pierwszej.
        assertThat(TenantContext.isSet()).isFalse();
    }

    @Test
    @DisplayName("Pomija load() gdy instalacja jest już załadowana w tym węźle (idempotentność)")
    void skipsLoadWhenAlreadyLoaded() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                .thenReturn(List.of(installation(TENANT_A, INSTALLATION_A)));
        when(pluginRuntimeManager.isLoaded(INSTALLATION_A)).thenReturn(true);

        loader.reloadEnabledInstallationsOnStartup();

        verify(pluginRuntimeManager, never()).load(TENANT_A, INSTALLATION_A);
    }

    @Test
    @DisplayName("Sprawdza isLoaded PRZED load() dla każdej instalacji")
    void checksIsLoadedBeforeLoad() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants())
                .thenReturn(List.of(installation(TENANT_A, INSTALLATION_A)));
        when(pluginRuntimeManager.isLoaded(INSTALLATION_A)).thenReturn(false);

        loader.reloadEnabledInstallationsOnStartup();

        InOrder inOrder = inOrder(pluginRuntimeManager);
        inOrder.verify(pluginRuntimeManager).isLoaded(INSTALLATION_A);
        inOrder.verify(pluginRuntimeManager).load(TENANT_A, INSTALLATION_A);
    }

    @Test
    @DisplayName("Brak instalacji enabled=true – nie wywołuje load() ani isLoaded()")
    void noEnabledInstallationsIsNoOp() {
        when(pluginCatalogQueryService.findAllEnabledAcrossAllTenants()).thenReturn(List.of());

        loader.reloadEnabledInstallationsOnStartup();

        verify(pluginRuntimeManager, never()).load(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static TenantPluginInstallation installation(UUID tenantId, UUID installationId) {
        TenantPluginInstallation installation = new TenantPluginInstallation();
        installation.setId(installationId);
        installation.setTenantId(tenantId);
        installation.setEnabled(true);
        return installation;
    }
}
