---
name: project_be_plugin_startup_reload
description: PluginRuntimeStartupLoader — odbudowa PluginRegistry po restarcie procesu (ApplicationReadyEvent)
metadata:
  type: project
---

Fix bugu: `PluginRegistryImpl` trzymał aktywne instancje pluginów tylko w pamięci JVM — po
restarcie backendu wszystkie `enabled=true` instalacje przestawały działać w runtime, dopóki
admin nie wywołał ponownie `enable` per instalacja.

**Rozwiązanie:** nowy `PluginRuntimeStartupLoader` (`domain.plugin.runtime`, `@Component` +
`@EventListener(ApplicationReadyEvent.class)` — konwencja jak `StartupInfoLogger` w
`infrastructure.config`, NIE `ApplicationRunner`).

**Przepływ:**
1. `PluginCatalogQueryService.findAllEnabledAcrossAllTenants()` (nowa metoda) — analogiczna do
   istniejącego BE-106 wzorca `findAllEnabledAcrossTenantsByVersionId`: iteruje
   `TenantService.getAllTenants()`, dla każdego ustawia RLS jawnie i woła nową
   `TenantPluginInstallationRepository.findAllEnabledForTenant(tenantId)` (bez filtra po wersji,
   w odróżnieniu od `findAllEnabledByPluginVersionIdForTenant`).
2. Per instalacja: `pluginRuntimeManager.isLoaded(id)` guard (idempotentność, jak w
   `PluginAdminController#enable`) → jeśli nie, `TenantContext.setTenantId(tenantId)` →
   `pluginRuntimeManager.load(tenantId, installationId)` w try/catch(RuntimeException) z
   log.error → `finally { TenantContext.clear(); }`.
3. Fault containment: błąd jednej instalacji (catch RuntimeException) nie zatrzymuje pętli —
   tylko log.error z tenantId+installationId, kontynuacja do następnej.
4. Logi podsumowujące: "Reloading N plugin installations after startup" na początku,
   "Reloaded X/N plugin installations successfully" na końcu.

**Pliki zmienione:**
- `TenantPluginInstallationRepository.java` — `findAllEnabledForTenant(UUID tenantId)`
- `PluginCatalogQueryService.java` + `PluginCatalogQueryServiceImpl.java` —
  `findAllEnabledAcrossAllTenants()`
- `PluginRegistryImpl.java`, `PluginRuntimeManager.java` — Javadoc zaktualizowany (usunięte
  "poza zakresem tego ticketu")
- Nowy: `PluginRuntimeStartupLoader.java`
- Testy: `PluginRuntimeStartupLoaderTest.java` (nowy, 6 testów), `PluginCatalogQueryServiceImplTest.java`
  (nowy `@Nested FindAllEnabledAcrossAllTenantsTests`, 3 testy)

Współistnieje bez konfliktu z `PluginAdminController#enable` — ten sam idempotency guard
(`isLoaded`), więc nie ma ryzyka duplikacji classloadera niezależnie od kolejności wykonania.
