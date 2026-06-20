package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;

import java.util.List;
import java.util.UUID;

/**
 * Warstwa domenowa instalacji pluginu per tenant (EPIC-28, BE-100).
 *
 * <p>Odpowiada wyłącznie za rejestrację i stan instalacji w bazie danych
 * ({@code tenant_plugin_installation}, V075) — <strong>nie</strong> ładuje bytecodu
 * pluginu do JVM. Classloading i instancjonowanie {@code PluginEntryPoint} jest
 * zadaniem {@code PluginRuntimeManager} (BE-101, poza zakresem tego serwisu).
 *
 * <p>{@code enable}/{@code disable} w tym serwisie tylko zmieniają flagę {@code enabled}
 * w DB — nie uruchamiają ani nie zatrzymują żadnego runtime'u pluginu.
 */
public interface PluginRegistrationService {

    /**
     * Instaluje wersję pluginu dla tenanta.
     *
     * <p>Zapisane {@code grantedPermissions} to przecięcie uprawnień żądanych przez
     * parametr {@code grantedPermissions} z uprawnieniami zadeklarowanymi w manifeście
     * wskazanej {@link PluginVersion} — żądanie uprawnienia niezadeklarowanego w manifeście
     * jest filtrowane (ignorowane), nie powoduje błędu. Instalacja jest tworzona
     * z {@code enabled=false}.
     *
     * @param tenantId            tenant instalujący plugin
     * @param pluginVersionId     wersja pluginu do zainstalowania
     * @param grantedPermissions  uprawnienia zaakceptowane przez admina tenanta (może być pusta/null)
     * @param installedByUserId   użytkownik wykonujący instalację
     * @return DTO nowo utworzonej instalacji
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy {@code pluginVersionId} nie istnieje
     * @throws org.springframework.dao.DataIntegrityViolationException gdy tenant ma już zainstalowaną tę wersję
     *         (duplikat {@code (tenant_id, plugin_version_id)}) — mapowane na HTTP 409
     */
    TenantPluginInstallationDto install(UUID tenantId, UUID pluginVersionId,
                                         List<String> grantedPermissions, UUID installedByUserId);

    /**
     * Włącza instalację (ustawia {@code enabled=true}).
     *
     * @param tenantId      tenant-właściciel instalacji
     * @param installationId instalacja do włączenia
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie istnieje
     *         dla tego tenanta (RLS odfiltruje wiersze innych tenantów)
     */
    void enable(UUID tenantId, UUID installationId);

    /**
     * Wyłącza instalację (ustawia {@code enabled=false}).
     *
     * @param tenantId      tenant-właściciel instalacji
     * @param installationId instalacja do wyłączenia
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie istnieje
     *         dla tego tenanta
     */
    void disable(UUID tenantId, UUID installationId);

    /**
     * Listuje wszystkie instalacje pluginów dla tenanta (włącznie z wyłączonymi —
     * widok administracyjny).
     *
     * @param tenantId tenant, dla którego listujemy instalacje
     * @return lista instalacji posortowana po dacie instalacji (najnowsze pierwsze)
     */
    List<TenantPluginInstallationDto> listInstallations(UUID tenantId);

    /**
     * Atomowo przełącza aktywną instalację (rollback do starszej wersji, ARCHITECTURE.md §11.11).
     *
     * <p>W jednej transakcji: {@code targetInstallationId} (starsza wersja) ustawiana jest na
     * {@code enabled=true}, a {@code currentInstallationId} na {@code enabled=false}. Żaden
     * wiersz nie jest usuwany. Obie instalacje muszą należeć do {@code tenantId}.
     *
     * @param tenantId               tenant-właściciel obu instalacji
     * @param currentInstallationId  aktualnie aktywna instalacja (zostanie wyłączona)
     * @param targetInstallationId   instalacja docelowa rollbacku (zostanie włączona)
     * @return DTO instalacji docelowej po włączeniu
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy którakolwiek
     *         instalacja nie istnieje dla tego tenanta
     */
    TenantPluginInstallationDto rollback(UUID tenantId, UUID currentInstallationId, UUID targetInstallationId);
}
