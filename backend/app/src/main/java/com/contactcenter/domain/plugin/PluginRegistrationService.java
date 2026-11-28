package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.PluginConfigEntryDto;
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
     * Circuit breaker opened: sets {@code health_status = DEGRADED}, {@code degraded_reason = CIRCUIT_OPEN} and the
     * failure counter in ONE statement (V153). Overwrites {@code CONFIG_LOAD_FAILED} (a live plugin failing
     * repeatedly is the fresher signal, see {@link DegradedReason}).
     *
     * <p>Called by {@code CircuitBreakerState} (BE-102, ARCHITECTURE.md section 11.7). Best-effort: does not throw
     * for a missing installation - logs a warning and returns {@code false}.
     *
     * @param tenantId         tenant that owns the installation
     * @param installationId   installation whose status is updated
     * @param consecutiveFailureCount current value of the failure counter (a pure counter, not a cause marker)
     * @return {@code true} when the row was updated, {@code false} when the installation does not exist for the tenant or is {@code DISABLED_BY_ADMIN} (never overwritten)
     */
    boolean markDegradedByCircuit(UUID tenantId, UUID installationId, int consecutiveFailureCount);

    /**
     * BE-245 W2: {@code HEALTHY -> DEGRADED} (CAS) po porażce załadowania wtyczki z nową konfiguracją. Nie nadpisuje
     * {@code DEGRADED} obwodu ani {@code DISABLED_BY_ADMIN}; idempotentne. Ustawia {@code degraded_reason = CONFIG_LOAD_FAILED}
     * w tym samym UPDATE-cie, nie rusza licznika błędów. Best-effort: nie rzuca przy braku instalacji.
     *
     * @return {@code true} gdy status faktycznie zmieniono
     */
    boolean markDegradedByConfigLoad(UUID tenantId, UUID installationId);

    /**
     * BE-245 W2: zdejmuje {@code DEGRADED} (CAS na przyczynie). {@code includeCircuit=false} - tylko {@code DEGRADED}
     * po porażce ładowania/konfiguracji; {@code true} - także obwodu (po resecie obwodu przez zmianę instalacji).
     *
     * @return {@code true} gdy status faktycznie zdjęto
     */
    boolean clearDegraded(UUID tenantId, UUID installationId, boolean includeCircuit);

    /**
     * BE-245 W2: powrót obwodu na {@code HEALTHY} - zdejmuje WYŁĄCZNIE {@code DEGRADED} z przyczyną
     * {@code CIRCUIT_OPEN} (nie rusza {@code CONFIG_LOAD_FAILED} ani {@code DISABLED_BY_ADMIN}), czyści przyczynę i
     * zeruje zalegający licznik.
     *
     * @return {@code true} gdy cokolwiek zmieniono
     */
    boolean clearCircuitDegraded(UUID tenantId, UUID installationId);

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
     * Usuwa wersję pluginu z katalogu tenanta.
     *
     * <p>Weryfikuje:
     * <ol>
     *   <li>Wersja istnieje i należy do {@code tenantId} — 404 w przeciwnym razie.</li>
     *   <li>Brak aktywnych instalacji tej wersji przez tenanta — 409 w przeciwnym razie.</li>
     * </ol>
     *
     * <p>Jeśli warunki spełnione — fizycznie usuwa wiersz {@code plugin_version}. JAR w S3 pozostaje
     * (brak SK do natychmiastowego czyszczenia, orphan jest akceptowalny).
     *
     * @param tenantId        tenant wykonujący operację
     * @param pluginVersionId wersja pluginu do usunięcia z katalogu
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy wersja nie istnieje lub należy do innego tenanta
     * @throws com.contactcenter.domain.exception.ConflictException gdy istnieje instalacja tej wersji
     */
    void deleteCatalogVersion(UUID tenantId, UUID pluginVersionId);

    /**
     * Zwraca listę wpisów konfiguracji instalacji z wartościami dla kluczy jawnych.
     *
     * <p>Klucze uznane za tajne (nazwa zawiera case-insensitive: {@code key}, {@code token},
     * {@code secret}, {@code password}) mają {@code value=null} — wartość nigdy nie jest
     * ujawniana przez API dla kluczy tajnych. Klucze jawne zwracają odszyfrowaną wartość.
     *
     * <p>Wynik jest posortowany alfabetycznie po kluczu.
     *
     * @param tenantId       tenant-właściciel instalacji
     * @param installationId instalacja, której konfigurację odczytujemy
     * @return posortowana alfabetycznie lista wpisów konfiguracji; pusta lista gdy konfiguracja
     *         jest pusta lub {@code null}
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja nie
     *         istnieje dla tego tenanta
     */
    List<PluginConfigEntryDto> getConfigEntries(UUID tenantId, UUID installationId);

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
