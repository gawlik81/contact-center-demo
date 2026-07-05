---
name: project_be106_plugin_admin_controller
description: BE-106 PluginAdminController/PluginRevokeController — enable/disable/rollback/uninstall + platform REVOKED kill switch (EPIC-28)
metadata:
  type: project
---

BE-106 (EPIC-28) zaimplementowany 2026-06-22: dwa nowe kontrolery w `api.plugin`.

**`PluginAdminController`** (`/api/supervisor/plugins/**`, `hasAnyRole('SUPERVISOR','ADMIN')`):
GET listInstallations, POST install, POST `installations/{id}/enable`, POST
`installations/{id}/disable`, POST `installations/{id}/rollback/{targetId}`, DELETE
`installations/{id}`. Jest jedynym miejscem w aplikacji, gdzie `PluginRegistrationService`
(BE-100, DB) i `PluginRuntimeManager` (BE-101, JVM classloading) są świadomie spinane w jednej
operacji — obie warstwy same o sobie nie wiedzą.

**Kolejność krytyczna w `disable`:** `pluginRuntimeManager.unload()` PRZED
`pluginRegistrationService.disable()` (DB flag) — wymóg testowalny: "wywołanie
ExtensionPointPublisher zaraz po disable nie zwraca już tej instalacji", niezależnie od stanu
DB w danym momencie. Odwrotna kolejność zostawiłaby okno czasowe, gdzie DB mówi `enabled=false`,
ale `PluginRegistry.lookup` wciąż zwraca handle.

**`enable` idempotentny:** nowa metoda `PluginRuntimeManager.isLoaded(installationId)` (prosty
`activeHandles.containsKey`) — kontroler woła `load()` TYLKO gdy `!isLoaded()`, żeby nie
zduplikować classloadera dla już aktywnej instalacji. Decyzja: guard w kontrolerze (nie
wewnątrz `PluginRuntimeManagerImpl.load()`), bo `isLoaded` jest reużywalny też przez `rollback`
(target może być już aktywny, current zawsze odładowywany).

**`rollback`:** deleguje DB-ową atomowość do `PluginRegistrationService.rollback` (BE-100, bez
zmian), kontroler DODATKOWO: `load()` targetu jeśli `!isLoaded(targetId)`, `unload()` current
zawsze (current zawsze przechodzi enabled=true→false, więc zawsze trzeba go odładować).

**`uninstall` (DELETE) = fizyczny DELETE wiersza**, NIE soft-disable — rozstrzygnięte przez
ARCHITECTURE.md §11.11 ("deletes the TENANT_PLUGIN_INSTALLATION + bindings; PLUGIN_INVOCATION_LOG
history is retained") + FK `plugin_invocation_log.tenant_plugin_installation_id ON DELETE SET
NULL` (V077) zaprojektowane dokładnie na ten przypadek. Nowa metoda
`PluginRegistrationService.uninstall(tenantId, installationId)` →
`TenantPluginInstallationRepository.delete(id, tenantId)` (natywny DELETE, assertSameTenant +
setTenantContextInDb jak resztą repo). Bindingi `tenant_plugin_extension_binding` usuwane
automatycznie przez `ON DELETE CASCADE` (V076) — brak dodatkowego zapytania. Kontroler: `unload()`
best-effort w try/catch (nie blokuje DELETE na błędzie runtime — sam DELETE w DB jest operacją
krytyczną, unload to tylko "czyszczenie po sobie").

**`PluginRevokeController`** (`/api/admin/plugins/versions/{id}/revoke`,
**`@PreAuthorize("hasRole('ADMIN')")`** — **decyzja świadoma użytkownika, nie renegocjować bez
nowego ticketu**: projekt NIE ma roli "administratora systemowego" odrębnej od tenant `ADMIN`
(`UserRole` ma tylko `ADMIN, SUPERVISOR, AGENT`; potwierdzone że `AdminMetricsController`/
`POST /api/tenants`, jedyne inne operacje cross-tenant w projekcie, też używają zwykłego
`hasRole('ADMIN')`). Skutek: każdy tenantowy ADMIN może globalnie wycofać wersję pluginu innym
tenantom. Known limitation udokumentowany w Javadoc klasy + `TASKS-BACKEND.md`.

**Cross-tenant zapytanie dla `revoke` — wzorzec do reużycia:** `tenant_plugin_installation` ma
`FORCE ROW LEVEL SECURITY` (V075), projekt nie ma ANI JEDNEGO wzorca bypassu RLS (zweryfikowano
grep całego `domain/`). Rozwiązanie: `PluginCatalogQueryServiceImpl
.findAllEnabledAcrossTenantsByVersionId` iteruje po `TenantService.getAllTenants()` (tabela
`tenant` jest globalna, bez RLS — wzorzec już używany przez `AdminMetricsServiceImpl`), dla
KAŻDEGO tenanta woła nową metodę repo `findAllEnabledByPluginVersionIdForTenant` która jawnie
ustawia `setTenantContextInDb(tenantId)` przed SELECT. N zapytań (N=liczba tenantów), zero
bypassu bezpieczeństwa — akceptowalne, bo `revoke` jest rzadką operacją administracyjną.
**Jeśli przyszły ticket potrzebuje podobnego cross-tenant query na tabeli z RLS, reużyj ten
wzorzec** (iteracja + jawny `set_tenant_context` per tenant), nie wprowadzaj nowego mechanizmu
bypassu.

**Platform-level kill switch — gdzie egzekwowana blokada:** `PluginVersion.status=REVOKED`
ustawiane przez `PluginCatalogQueryServiceImpl.revokeVersion` (nowa metoda, prosty
`findById`+`setStatus`+`save`, encja globalna bez RLS). Blokada PONOWNEGO ładowania
egzekwowana w `PluginRuntimeManagerImpl.load()` (sprawdzenie `pluginVersion.getStatus() ==
REVOKED` → `PluginActivationException`, PRZED jakimkolwiek classloadingiem) — NIE w
`PluginRegistry.lookup` (zostawiony niezmieniony, BE-101). Logika: `unload()` już usuwa z
registry wszystkie aktywne instalacje w momencie revoke; jeśli `load()` odmawia REVOKED wersji,
nic nie zostanie tam ponownie zarejestrowane — `lookup` nie musi nic dodatkowo sprawdzać.

**Testy nowe:** `PluginRuntimeManagerImplTest$IsLoadedTests`/`$RevokedKillSwitchTests` (+3),
`PluginCatalogQueryServiceImplTest` (nowy plik, 7 — revokeVersion + cross-tenant lookup z 2+
tenantami), `PluginRegistrationServiceImplTest$Uninstall` (+3), `PluginAdminControllerTest`
(nowy, 11 — w tym `InOrder` weryfikujący kolejność unload-przed-disable i
ownership-przed-runtime-mutation), `PluginRevokeControllerTest` (nowy, 3 — kryterium akceptacji
kluczowe: 2 tenanty enabled=true → `unload()` wywołane dla OBU niezależnie od tenanta, best-effort
gdy unload jednej instalacji rzuca). Brak `@WebMvcTest` w projekcie dla `api.plugin` — `403`
dla SUPERVISOR na `/api/admin/**` weryfikowany deklaratywnie (adnotacja + `SecurityConfig`
`requestMatchers("/api/admin/**").hasRole("ADMIN")`), nie testem jednostkowym (konwencja
istniejąca, `PluginUploadControllerTest`/`PluginManualActionControllerTest` też nie testują
ról przez Spring Security context).

Weryfikacja: `mvn verify -pl app` ✅ (1299 testów, 0 failures, 0 errors, BUILD SUCCESS, +25 vs
BE-104). Odblokowuje BE-107.

Zobacz [[project_be100_plugin_registration]], [[project_be101_plugin_runtime]],
[[project_be102_extension_point_publisher]].
