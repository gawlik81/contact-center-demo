package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;

import java.util.List;
import java.util.Map;
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
     * <p><strong>Dziedziczenie configu przy upgrade (BE-111):</strong> jeśli tenant ma już
     * jakąkolwiek inną instalację (enabled lub disabled) tego samego {@code pluginKey}
     * (porównanie przez {@code PluginVersion.plugin.pluginKey}, nie {@code pluginVersionId}),
     * nowa instalacja automatycznie dziedziczy {@code installationConfig} z najnowszej takiej
     * instalacji z niepustym configiem — admin nie musi ponownie wpisywać sekretów (np. API key)
     * po każdym uploadzie nowej wersji JAR-a tego samego pluginu. Dziedziczenie jest zawsze
     * ograniczone do tego samego {@code tenantId} (multi-tenancy, RLS). Pierwsza instalacja
     * danego pluginu dla tenanta dostaje config pusty, jak dotychczas.
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
     * Wyszukuje jedną instalację po identyfikatorze, ograniczoną do tenanta wywołującego.
     *
     * <p>RLS (tabela {@code tenant_plugin_installation}, V075) sprawia, że zapytanie nie
     * zwróci wiersza innego tenanta — z punktu widzenia tej metody instalacja innego tenanta
     * jest niewidoczna, więc zachowuje się identycznie jak instalacja, która nie istnieje
     * wcale. Wołający (np. {@code PluginManualActionController}, BE-103) świadomie mapuje to
     * na HTTP 404 w obu przypadkach (konwencja "nie ujawniaj istnienia zasobu innego tenanta"),
     * a nie na rozróżnienie 403/404.
     *
     * @param tenantId       tenant-właściciel instalacji
     * @param installationId instalacja do odnalezienia
     * @return DTO instalacji
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie
     *         istnieje dla tego tenanta (włącznie z przypadkiem, gdy istnieje dla innego tenanta)
     */
    TenantPluginInstallationDto getInstallation(UUID tenantId, UUID installationId);

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

    /**
     * Aktualizuje {@code health_status}/{@code consecutive_failure_count} jednej instalacji.
     *
     * <p>Wołane wyłącznie przez {@code ExtensionPointPublisherImpl}/{@code CircuitBreakerState}
     * (BE-102, ARCHITECTURE.md §11.7) — przejście na {@code DEGRADED} po N kolejnych
     * {@code TIMED_OUT}/{@code FAILED} wywołaniach, lub powrót na {@code HEALTHY} po pierwszym
     * {@code SUCCESS}. Nie rzuca przy braku instalacji (best-effort — wołający nie powinien
     * blokować ścieżki invocation na błędzie aktualizacji metadanych zdrowia) — zamiast tego
     * loguje ostrzeżenie i zwraca {@code false}.
     *
     * @param tenantId               tenant-właściciel instalacji
     * @param installationId        instalacja, której status jest aktualizowany
     * @param healthStatus            jedna z wartości {@code TenantPluginInstallation.HealthStatus}
     * @param consecutiveFailureCount aktualna wartość licznika kolejnych błędów do zapisania
     * @return {@code true} jeśli wiersz został zaktualizowany, {@code false} gdy instalacja nie
     *         istnieje dla tego tenanta
     */
    boolean updateHealthStatus(UUID tenantId, UUID installationId, String healthStatus, int consecutiveFailureCount);

    /**
     * Odinstalowuje (fizycznie usuwa) instalację pluginu (BE-106, ARCHITECTURE.md §11.11:
     * "Uninstall: ... deletes the TENANT_PLUGIN_INSTALLATION + bindings; PLUGIN_INVOCATION_LOG
     * history is retained").
     *
     * <p><strong>Wyłącznie operacja na DB</strong> — nie wywołuje {@code onDeactivate()} ani nie
     * zwalnia {@code PluginClassLoader}; to odpowiedzialność wołającego
     * ({@code PluginAdminController}, BE-106), analogicznie do podziału odpowiedzialności
     * {@code enable}/{@code disable} (ten serwis = DB, {@code PluginRuntimeManager} = JVM
     * runtime). Wołający musi odładować runtime PRZED wywołaniem tej metody (lub best-effort
     * po niej — nie ma znaczenia z punktu widzenia tej metody, bo ona tylko usuwa wiersz).
     *
     * <p>Bindingi {@code tenant_plugin_extension_binding} są usuwane automatycznie przez
     * {@code ON DELETE CASCADE} (V076). Wpisy {@code plugin_invocation_log} przetrwają
     * (FK {@code ON DELETE SET NULL}, V077) — historia audytowa nie jest tracona.
     *
     * @param tenantId       tenant-właściciel instalacji
     * @param installationId instalacja do odinstalowania
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie
     *         istnieje dla tego tenanta
     */
    void uninstall(UUID tenantId, UUID installationId);

    /**
     * Zastępuje (REPLACE, nie merge) konfigurację tenanta instalacji pluginu (BE-108) —
     * np. API key zewnętrznego systemu wymagany przez plugin (przykład: {@code customer-google-lookup}).
     *
     * <p>Wartości {@code config} są zserializowane do plaintext JSON, zaszyfrowane AES-256-GCM
     * (wzorzec {@code tenant_ai_config}/{@code tenant_twilio_config}, {@code EncryptedStringConverter})
     * i zapisane w {@code tenant_plugin_installation.installation_config}. Ponieważ to repozytorium
     * używa wyłącznie natywnego SQL (nie standardowego JPA repository), szyfrowanie/deszyfrowanie
     * jest wołane ręcznie w {@link TenantPluginInstallationRepository} — {@code @Convert} na polu
     * encji NIE zadziałałby tutaj (Hibernate aplikuje konwertery tylko przez JPQL/Criteria/
     * {@code EntityManager.find}, nigdy przy ręcznym mapowaniu wierszy natywnego SQL).
     *
     * <p>Kolejny odczyt instalacji (np. przez {@code PluginCatalogQueryService}, używany przez
     * {@code PluginRuntimeManagerImpl#load} do skonstruowania {@code PluginContext}) zwraca
     * {@code installationConfig} już odszyfrowany (plaintext JSON) — deszyfrowanie następuje
     * przy odczycie wiersza w repozytorium, nie w tym serwisie.
     *
     * @param tenantId      tenant-właściciel instalacji
     * @param installationId instalacja, której konfiguracja jest zastępowana
     * @param config        nowy, kompletny zestaw kluczy konfiguracji (może być pusta mapa —
     *                       czyści całą dotychczasową konfigurację; nie może być {@code null})
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie
     *         istnieje dla tego tenanta
     */
    void updateConfig(UUID tenantId, UUID installationId, Map<String, String> config);
}
