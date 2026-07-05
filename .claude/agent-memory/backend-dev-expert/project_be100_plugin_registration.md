---
name: be100-plugin-registration
description: BE-100 TenantPluginInstallation + PluginRegistrationService (install/enable/disable/rollback per tenant) — EPIC-28
metadata:
  type: project
---

BE-100 dodał warstwę domenową instalacji pluginu per tenant w `domain/plugin/`: encja `TenantPluginInstallation` (tabela `tenant_plugin_installation`, V075, RLS), `TenantPluginInstallationRepository` (package-private, natywny SQL przez `EntityManager` rozszerzający `TenantAwareRepository`), `PluginRegistrationService`/`Impl`, DTO `TenantPluginInstallationDto`/`InstallPluginRequest`.

**Decyzja architektoniczna — repo z RLS vs bez RLS w tym samym pakiecie:** `domain/plugin/` ma teraz dwa style repozytoriów współistniejące: `PluginRepository`/`PluginVersionRepository` (zwykły `JpaRepository`, tabele globalne bez `tenant_id`/RLS, z BE-098/099) vs `TenantPluginInstallationRepository` (natywny SQL + `TenantAwareRepository`, bo `tenant_plugin_installation` ma RLS). Wzorzec wyboru repo zależy od tego, czy konkretna tabela ma `tenant_id`+RLS, nie od pakietu.

**Logika `install`:** `grantedPermissions` zapisane = przecięcie żądanych (parametr) ∩ `PluginVersion.manifestJson.get("permissions")` (raw `Map<String,Object>`, nie sparsowany `PluginManifest` record — ten jest package-private i używany tylko w BE-098 walidacji). Żądanie permission poza manifestem jest filtrowane, NIE rzuca błędu.

**Duplikat unique constraint → 409:** `tenant_plugin_installation` ma `UNIQUE(tenant_id, plugin_version_id)`. Insert przez natywny SQL w klasie oznaczonej `@Repository` automatycznie korzysta z `PersistenceExceptionTranslationPostProcessor` Spring — `PSQLException` unique violation → `DataIntegrityViolationException` → już istniejący globalny handler w `GlobalExceptionHandler` (fallback generyczny, linia ~432) mapuje na HTTP 409. Nie trzeba dodawać własnego catch/throw `ConflictException` w serwisie dla tego przypadku.

**`rollback` atomowość:** wzorzec to "weryfikuj obie strony PRZED jakimkolwiek UPDATE" — `findByIdAndTenantId` na `currentInstallationId` I `targetInstallationId` najpierw (oba muszą się powieść), potem dwa `updateEnabled()`. Jeśli walidacja drugiej instalacji zawiedzie, żaden wiersz nie jest jeszcze zmodyfikowany — nie wymaga ręcznego rollbacku transakcji, tylko właściwej kolejności operacji w metodzie `@Transactional`.

Blokuje BE-101 (`PluginRuntimeManager`, XL — classloading, NIE realizowany w BE-100) i BE-106. Zobacz [[project_be097_plugin_sdk]], [[project_be098_plugin_validation]].
