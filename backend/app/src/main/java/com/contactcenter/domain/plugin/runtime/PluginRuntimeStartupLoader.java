package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Odbudowuje stan {@link PluginRegistry} w pamięci JVM po starcie aplikacji.
 *
 * <p><strong>Problem, który ten komponent rozwiązuje:</strong> {@link PluginRegistryImpl}
 * przechowuje aktywne instancje pluginów wyłącznie w pamięci procesu (mapa
 * {@code byTenantAndExtensionPoint}) — ta mapa jest wypełniana jedynie przez
 * {@link PluginRegistry#register}, wołane z {@link PluginRuntimeManagerImpl#load}. Bez tego
 * komponentu, jedynym wyzwalaczem {@code load()} byłaby akcja administracyjna
 * {@code POST /api/supervisor/plugins/installations/{id}/enable} — po każdym restarcie procesu
 * (deploy, crash, restart kontenera) wszystkie wcześniej aktywowane instalacje
 * ({@code enabled=true} w DB) przestałyby działać w runtime, mimo że w bazie danych nic się nie
 * zmieniło, dopóki administrator nie wywołałby ponownie {@code enable} dla każdej z nich.
 *
 * <p><strong>Sekwencja:</strong> po {@link ApplicationReadyEvent} (aplikacja w pełni
 * zainicjalizowana, łącznie z Flyway/DB pool) odczytuje wszystkie instalacje z
 * {@code enabled=true} wszystkich tenantów ({@link PluginCatalogQueryService
 * #findAllEnabledAcrossAllTenants}) i dla każdej wywołuje {@link PluginRuntimeManager#load}.
 *
 * <p><strong>Fault containment:</strong> błąd {@code load()} dla JEDNEJ instalacji (uszkodzony
 * JAR, timeout {@code onActivate}, wersja {@code REVOKED}) jest łapany i logowany — NIE
 * zatrzymuje reszty startupu ani odbudowy pozostałych instalacji, analogicznie do filozofii
 * fault-containment {@link ExtensionPointPublisherImpl} (BE-102): awaria jednego pluginu nigdy
 * nie może zablokować startu całej platformy.
 *
 * <p><strong>TenantContext per iteracja:</strong> ten listener działa na wątku startowym Spring
 * (nie HTTP, nie {@code @Async}) — mimo to {@link PluginRuntimeManagerImpl#load} oczekuje
 * {@code tenantId} zgodnego z {@code TenantContext.getTenantId()} wątku wywołującego (patrz
 * Javadoc {@link PluginContextImpl}), więc kontekst jest ustawiany jawnie przed każdym
 * {@code load()} i czyszczony w {@code finally} — zarówno żeby nie przeciekł do kolejnej
 * iteracji (inny tenant), jak i do reszty startupu aplikacji po zakończeniu tej metody.
 *
 * <p>Współistnieje bez konfliktu z {@code PluginAdminController#enable}: ten ostatni sprawdza
 * {@link PluginRuntimeManager#isLoaded} przed {@code load()} (idempotentność), a ten listener
 * robi to samo — jeśli administrator zdążył ręcznie włączyć instalację przed tym, jak ten
 * listener dojdzie do niej w iteracji, {@code load()} nie zostanie wywołane drugi raz.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PluginRuntimeStartupLoader {

    private final PluginCatalogQueryService pluginCatalogQueryService;
    private final PluginRuntimeManager pluginRuntimeManager;

    @EventListener(ApplicationReadyEvent.class)
    void reloadEnabledInstallationsOnStartup() {
        List<TenantPluginInstallation> enabledInstallations =
                pluginCatalogQueryService.findAllEnabledAcrossAllTenants();

        log.info("[PluginRuntimeStartupLoader] Reloading {} plugin installations after startup",
                enabledInstallations.size());

        int successCount = 0;
        for (TenantPluginInstallation installation : enabledInstallations) {
            if (loadOneInstallation(installation)) {
                successCount++;
            }
        }

        log.info("[PluginRuntimeStartupLoader] Reloaded {}/{} plugin installations successfully",
                successCount, enabledInstallations.size());
    }

    /**
     * Ładuje jedną instalację, izolując ewentualny błąd od pozostałych iteracji (fault
     * containment) i gwarantując czyszczenie {@link TenantContext} niezależnie od wyniku.
     *
     * @return {@code true} jeśli instalacja została poprawnie załadowana (lub była już
     *         załadowana wcześniej — idempotentność, guard {@code isLoaded})
     */
    private boolean loadOneInstallation(TenantPluginInstallation installation) {
        UUID tenantId = installation.getTenantId();
        UUID installationId = installation.getId();

        if (pluginRuntimeManager.isLoaded(installationId)) {
            log.debug("[PluginRuntimeStartupLoader] Instalacja {} już załadowana w tym węźle, "
                    + "pomijam ponowny load()", installationId);
            return true;
        }

        try {
            TenantContext.setTenantId(tenantId);
            pluginRuntimeManager.load(tenantId, installationId);
            return true;
        } catch (RuntimeException e) {
            log.error("[PluginRuntimeStartupLoader] load() nie powiodło się przy odbudowie "
                            + "registry po starcie: tenant={}, installationId={}, error={}",
                    tenantId, installationId, e.getMessage(), e);
            return false;
        } finally {
            TenantContext.clear();
        }
    }
}
