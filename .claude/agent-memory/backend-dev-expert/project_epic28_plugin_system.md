---
name: project_epic28_plugin_system
description: EPIC-28 system pluginów — szyfrowanie installation_config bez AAD, dziedziczenie configu przy upgrade (BE-111), struktura encji plugin/plugin_version/tenant_plugin_installation
metadata:
  type: project
---

## Struktura domeny (package `com.contactcenter.domain.plugin`)

- `Plugin` (tabela `plugin`, V074) — globalny katalog, BEZ tenant_id/RLS. `pluginKey` unique.
- `PluginVersion` (tabela `plugin_version`, V074) — `@ManyToOne(optional=false)` do `Plugin`
  (EAGER domyślnie), `manifestJson` jako `Map<String,Object>` (JSONB). Upgrade = nowy wiersz,
  nigdy edycja istniejącego.
- `TenantPluginInstallation` (tabela `tenant_plugin_installation`, V075) — per-tenant, RLS.
  Upgrade pluginu = nowy wiersz instalacji (stara `enabled=false` zostaje jako punkt rollbacku,
  zob. `PluginRegistrationService#rollback`).
- `TenantPluginInstallationRepository` — wyłącznie natywny SQL (nie JPA/Criteria), bo
  `installation_config` wymaga ręcznego szyfrowania/deszyfrowania przy każdym I/O (Hibernate
  `@Convert` nie działa przy natywnym SQL). Encja świadomie NIE ma `@Convert` na tym polu.

## Szyfrowanie installation_config — BRAK AAD (ważne dla przyszłych zmian)

`EncryptedStringConverter` (AES-256-GCM) używa losowego IV per wywołanie, ale **nie wiąże
ciphertextu z żadnym identyfikatorem wiersza** (brak additional authenticated data). Kolumna
w DB: `{"encrypted": "<base64 IV+ciphertext>"}`.

**Konsekwencja:** kryptograficznie nic nie zabraniałoby skopiowania surowego ciphertextu
między wierszami `tenant_plugin_installation` tego samego tenanta. Mimo to przy implementacji
dziedziczenia configu (BE-111, `PluginRegistrationServiceImpl#findInheritedConfig`) wybrano
przejście przez plaintext (odczyt przez `findAllByTenantId`, który i tak deszyfruje każdy wiersz
— jedyna droga odczytu w tym repo) — każdy nowy wiersz dostaje świeży IV przy `insert()`,
zamiast kopiować blob 1:1. To naturalny efekt istniejącego kontraktu repozytorium, nie
dodatkowy koszt.

Jeśli kiedyś dodane zostanie AAD wiążące ciphertext z `installationId` — `findInheritedConfig`
i tak będzie działać bez zmian, bo operuje na plaintext, nie na surowym blobie.

## BE-111: dziedziczenie config przy instalacji nowej wersji tego samego pluginu

`PluginRegistrationServiceImpl.install()` przed `insert()` woła `findInheritedConfig(tenantId,
pluginVersion)`: szuka wśród WSZYSTKICH instalacji tenanta (`findAllByTenantId`, enabled i
disabled) tej z najnowszym `installedAt`, która ma ten sam `Plugin.pluginKey` (NIE
`pluginVersionId`) i niepusty config — i kopiuje jej plaintext config do nowej instalacji.
Brak takiej instalacji lub `plugin == null` na wersji → config zostaje `null` (zachowanie
historyczne). Izolacja multi-tenant gwarantowana przez `findAllByTenantId(tenantId)` (RLS +
jawny WHERE) — nigdy nie sięga po dane innego tenanta.

Testy: `PluginRegistrationServiceImplTest$Install` — sekcja "BE-111" (5 testów: dziedziczenie
działa, pierwsza instalacja brak configu, poprzednia instalacja miała null, inny pluginKey nie
dziedziczy, izolacja multi-tenant). Fixture `buildPluginVersionWithPlugin` (z `Plugin`
ustawionym) odróżnia się od starszej `buildPluginVersionWithPermissions` (plugin=null — używana
przez testy niezwiązane z dziedziczeniem, zostawiona nietknięta).
