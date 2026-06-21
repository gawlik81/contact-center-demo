package com.contactcenter.domain.plugin.runtime;

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
 * <p><strong>Nie robi (poza zakresem BE-101, przyszłe tickety):</strong> nie publikuje
 * extension-point invocations w trakcie normalnej pracy (to {@code ExtensionPointPublisher},
 * BE-102), nie implementuje circuit breakera per installation (BE-102), nie odbudowuje stanu
 * przy starcie aplikacji (przyszły ticket integracyjny).
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
     *         {@link PluginRegistry})
     */
    PluginInstanceHandle load(UUID tenantId, UUID installationId);

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
}
