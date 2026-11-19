package com.contactcenter.domain.plugin.runtime;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Jądro mechanizmu izolacji wykonania pluginów (EPIC-28, BE-101, RT-10 — najbardziej krytyczny
 * i najbardziej ryzykowny ticket epiku).
 *
 * <p>Odpowiada za pełny cykl życia jednej instalacji pluginu w JVM: pobranie JAR-a z object
 * storage, utworzenie dedykowanego {@link PluginClassLoader}, instancjonowanie
 * {@code entryPointClass} konstruktorem bezargumentowym, wywołanie {@code onActivate}/
 * {@code onDeactivate} timeout-bounded, i rejestrację/wyrejestrowanie w {@link PluginRegistry}.
 *
 * <p><strong>Nie robi (poza zakresem BE-101):</strong> nie publikuje extension-point invocations
 * w trakcie normalnej pracy (to {@code ExtensionPointPublisher}, BE-102), nie implementuje
 * circuit breakera per installation (BE-102). Odbudowa stanu przy starcie aplikacji (ponowne
 * {@code load()} wszystkich {@code enabled=true} instalacji po restarcie procesu) jest
 * zrealizowana przez {@link PluginRuntimeStartupLoader}, konsumenta tego interfejsu — nie
 * logiki wewnątrz {@code PluginRuntimeManagerImpl}.
 */
public interface PluginRuntimeManager {

    /**
     * Ładuje i aktywuje jedną instalację pluginu.
     *
     * <p>Sekwencja: wczytaj {@code TenantPluginInstallation} (musi istnieć i należeć do
     * {@code tenantId}) i {@code PluginVersion}; pobierz bajty JAR-a z object storage i
     * zapisz do lokalnego cache na dysku; utwórz nową instancję {@link PluginClassLoader}
     * (parent = {@link PlatformApiClassLoader#INSTANCE}); wczytaj {@code entryPointClass} z
     * manifestu i zainstancjonuj wyłącznie konstruktorem bezargumentowym; zbuduj
     * {@link PluginContextImpl} z {@code tenantId} przekazanym jawnie przez wywołującego
     * (musi pochodzić z {@code TenantContext.getTenantId()} wątku wywołującego tę metodę —
     * patrz Javadoc {@link PluginContextImpl}); wywołaj {@code onActivate(context)} z
     * timeoutem; zarejestruj w {@link PluginRegistry}.
     *
     * @param tenantId        tenant instalujący/aktywujący plugin — musi być zgodny z
     *                        {@code TenantContext.getTenantId()} wątku wywołującego (nie
     *                        weryfikowane wewnątrz tej metody — odpowiedzialność wywołującego,
     *                        identycznie jak w innych serwisach domenowych projektu)
     * @param installationId  identyfikator {@code tenant_plugin_installation} do aktywacji
     * @return uchwyt nowo aktywowanej instancji
     * @throws com.contactcenter.domain.exception.ResourceNotFoundException gdy instalacja lub
     *         wersja pluginu nie istnieje
     * @throws PluginActivationException gdy {@code onActivate} rzuci wyjątek lub przekroczy
     *         timeout — instalacja pozostaje nieaktywowana (nic nie jest rejestrowane w
     *         {@link PluginRegistry}), lub gdy {@code PluginVersion.status == REVOKED}
     *         (BE-106, platform-level kill switch, ARCHITECTURE.md §11.11) — odmowa ładowania
     *         jest blokadą egzekwowaną tutaj, nie przez {@link PluginRegistry#lookup}
     */
    PluginInstanceHandle load(UUID tenantId, UUID installationId);

    /**
     * Sprawdza, czy dana instalacja ma aktualnie aktywny {@link PluginInstanceHandle}
     * (classloader + instancja {@code entryPoint}) w tym managerze.
     *
     * <p>Używana przez {@code PluginAdminController#enable} (BE-106) do idempotentnego
     * {@code enable} — jeśli instalacja jest już załadowana, {@code enable} nie wywołuje
     * ponownie {@link #load}, by nie zduplikować classloadera dla tej samej instalacji.
     *
     * @param installationId instalacja do sprawdzenia
     * @return {@code true} jeśli instalacja ma aktywny uchwyt w tym managerze
     */
    boolean isLoaded(UUID installationId);

    /**
     * Zwraca uchwyt aktywnej instalacji po samym {@code installationId}, bez wymogu
     * {@code tenantId} (BE-107, ARCHITECTURE.md §11.10).
     *
     * <p><strong>Dlaczego bez tenantId jest tu bezpieczne:</strong> ta metoda przeszukuje
     * wyłącznie mapę w pamięci tego procesu ({@code activeHandles}) — nie wykonuje żadnego
     * zapytania do bazy danych, więc nie ma tu możliwości bypassu RLS. Jedyny konsument
     * ({@code PluginAssetController}) używa wyniku tylko do zbudowania CSP (z manifestu) i
     * zlokalizowania katalogu statycznych, niesekretnych plików UI pluginu na dysku —
     * {@code installationId} jest globalnie unikalnym UUID, więc lookup po samym ID jest
     * wystarczający dla tego celu (analogicznie jak w przypadku publicznych zasobów CDN).
     *
     * @param installationId instalacja, dla której szukamy aktywnego uchwytu
     * @return uchwyt jeśli instalacja jest aktualnie załadowana w tym procesie, inaczej empty
     */
    Optional<PluginInstanceHandle> findActiveHandle(UUID installationId);

    /**
     * Dezaktywuje i zwalnia jedną instalację pluginu.
     *
     * <p>Wywołuje {@code onDeactivate()} best-effort, timeout-bounded — błąd lub timeout NIE
     * blokuje unload. Usuwa wpis z {@link PluginRegistry} i z wewnętrznej mapy aktywnych
     * uchwytów tego managera, tak by żadna silna referencja do {@link PluginClassLoader} (lub
     * instancji {@code entryPoint}) nie pozostała w aplikacji — warunek niezbędny, by GC mógł
     * odzyskać classloader.
     *
     * @param tenantId       tenant właściciel instalacji
     * @param installationId instalacja do dezaktywacji
     */
    void unload(UUID tenantId, UUID installationId);

    /**
     * Migawka uchwytów aktywnych w tym procesie (BE-178) — wejście rekoncyliacji z DB między instancjami.
     * Kopia: późniejsze {@code load}/{@code unload} nie zmieniają zwróconej kolekcji.
     *
     * @return uchwyty załadowane w tej instancji (może być pusta, nigdy {@code null})
     */
    Collection<PluginInstanceHandle> loadedHandles();

    /**
     * Natychmiast odwołuje (kill switch, BE-106/BE-178) wszystkie uchwyty danej wersji pluginu w tym procesie.
     *
     * <p>Dwufazowo: najpierw (szybko, bez czekania na pluginy) WSZYSTKIE pasujące uchwyty są usuwane z
     * {@link PluginRegistry} i mapy aktywnych uchwytów — od tego momentu żadne wywołanie ich nie zobaczy — dopiero
     * potem, osobno dla każdego, {@code onDeactivate} (timeout-bounded, best-effort) i zwolnienie classloadera.
     * Wersja trafia też do lokalnego zbioru "odwołanych", więc {@code load()} będący w toku w chwili odwołania
     * samodzielnie się wycofa. Wołać WYŁĄCZNIE po potwierdzeniu {@code REVOKED} w DB (lub z lokalnego endpointu
     * odwołania). Faza 2 ({@code onDeactivate}) biegnie asynchronicznie i równolegle per uchwyt - metoda zwraca po
     * fazie 1. Operacja jest nieodwracalna tak jak sam {@code REVOKED}.
     *
     * @param pluginVersionId odwoływana wersja pluginu
     * @return liczba odładowanych uchwytów
     */
    int unloadByVersion(UUID pluginVersionId);

    /**
     * Wariant {@link #unloadByVersion} dla odwołania NIEZWERYFIKOWANEGO w DB (awaria bazy w chwili zdarzenia z innej
     * instancji): uchwyty są zwalniane od razu (kill switch jest fail-safe), ale blokada ponownego załadowania wersji
     * jest TYMCZASOWA ({@code tombstoneTtlMillis}) i ma ograniczony rozmiar - podrobione zdarzenie nie zablokuje wersji
     * na stałe. Wpis wygasa WYŁĄCZNIE przez TTL (próba ładowania w tym oknie kończy się błędem i backoffem).
     */
    int unloadByVersionUnverified(UUID pluginVersionId, long tombstoneTtlMillis);
}
