---
name: project_be108_plugin_installation_config_encryption
description: BE-108 — szyfrowanie tenant_plugin_installation.installation_config na natywnym SQL (nie @Convert)
metadata:
  type: project
---

BE-108 (2026-06-23): dodano `PATCH /api/supervisor/plugins/installations/{id}/config` (REPLACE
semantyka) do zapisu zaszyfrowanej konfiguracji instalacji pluginu (np. Google API key dla
przykładowego pluginu `examples/plugins/customer-google-lookup/`).

**Why:** `TenantPluginInstallation.installationConfig` było od BE-100 zawsze `null` — żaden
endpoint nie zapisywał wartości. Plugin przykładowy wymagał miejsca na sekret tenanta.

**Wzorzec szyfrowania, różny od `TenantAiConfig`/`TenantTwilioConfig`:** te dwie encje używają
standardowego Spring Data JPA + `@Convert(converter = EncryptedStringConverter.class)` — działa
automatycznie przy load/save. `TenantPluginInstallationRepository` (BE-100) używa WYŁĄCZNIE
natywnego SQL (`EntityManager.createNativeQuery`, wzorzec jak `CustomDispositionRepository`,
EPIC-27) — Hibernate NIE aplikuje `@Convert` przy ręcznym mapowaniu wierszy natywnego SQL, więc
`@Convert` na polu encji byłby martwym kodem (wyglądałby jak działa, a nie działałby). Zamiast
tego: `EncryptedStringConverter` wstrzyknięty jako zwykły konstruktorowy Spring bean, wołany
RĘCZNIE — `encryptInstallationConfig()` przy każdym zapisie (`insert()` ORAZ
`updateInstallationConfig()` — patrz finding niżej), `decryptInstallationConfig()` w jednym
miejscu (`mapRow()`), więc automatycznie konsekwentny we wszystkich SELECT-ach.

**Format kolumny `jsonb`:** ciphertext Base64 zawijany w `{"encrypted": "<base64>"}`, NIE goły
skalar JSON — pozwala na zwykły `CAST(:json AS jsonb)` i `row[N].toString()` + Jackson, bez
ręcznego escapingu cytowania PostgreSQL.

**Finding code review (naprawiony przed merge):** pierwsza wersja `insert()` zapisywała
`installation.getInstallationConfig()` 1:1 bez szyfrowania — latentny bug (nieszkodliwy dziś,
bo `install()` zawsze ustawia `null`, ale przyszły kod ustawiający initial config zapisałby
plaintext niezgodny z formatem oczekiwanym przez `decryptInstallationConfig`, co przy odczycie
rzuciłoby `IllegalStateException` dla całej instalacji). Naprawione: `insert()` woła teraz
`encryptInstallationConfig()` identycznie jak `updateInstallationConfig()`.

**How to apply:** Przy każdej kolejnej zmianie w `TenantPluginInstallationRepository` dotyczącej
`installation_config` — sprawdzić, że WSZYSTKIE ścieżki zapisu (nie tylko ta, którą aktualnie
zmieniasz) przechodzą przez `encryptInstallationConfig()`, i że WSZYSTKIE ścieżki odczytu
przechodzą przez `mapRow()`/`decryptInstallationConfig()`. Ten wzorzec (konwerter jako
wstrzykiwalny bean wołany ręcznie, nie `@Convert`) jest specyficzny dla repozytoriów natywnego
SQL w tym projekcie — sprawdzić czy nie trzeba go powtórzyć przy innych encjach
package-private/natywny-SQL, które w przyszłości dostaną pole szyfrowane.

Zobacz też [[feedback_jdbc_set_tenant_context]], [[feedback_partitioned_table_jpa]] (inne
pułapki natywnego SQL w tym projekcie).
