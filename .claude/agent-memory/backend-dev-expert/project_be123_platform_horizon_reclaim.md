---
name: project_be123_platform_horizon_reclaim
description: BE-123 (EPIC-30, 2026-10-08) — ReclaimTarget/ThresholdSource/DropMode refaktor PartitionReclaimJob, horyzont platformowy audit_log/plugin_invocation_log, poprawka NPE countRowsByTenant
metadata:
  type: project
---

BE-123 ukończone 2026-10-08. Refaktoruje `PartitionReclaimJob.TABLE_CATEGORIES`
(`Map<String, RetentionDataCategory>`) → `RECLAIM_TARGETS` (`List<ReclaimTarget>`), domykając
długo odkładaną zależność z [[project_be145_partition_reclaim_drop_guard]]/
[[project_be133_social_message_partition_retention]] (obie te prace świadomie NIE czekały na
BE-123 i NIE budowały jego infrastruktury na zapas).

**Model (`PartitionReclaimJob`, nested types):**
- `sealed interface ThresholdSource` z dwoma rekordami: `CategoryMaxRetention(RetentionDataCategory
  category)` (dzisiejsza ścieżka, `RetentionPolicyService#findMaxRetentionMonths`) i
  `PlatformHorizon(String propertyKey)` (nowość — `propertyKey` to klucz logiczny
  `"audit-log-months"`/`"plugin-invocation-log-months"`, rozwiązywany przez
  `PlatformRetentionProperties#monthsFor`).
- `enum DropMode { ONLY_IF_EMPTY, AFTER_CUTOFF }` — `ONLY_IF_EMPTY` to dzisiejsze zachowanie BE-145
  dla WSZYSTKICH 6 tabel per-tenant (`contact`, `contact_event`, `social_message`, `email_message`,
  `contact_transcription`, `contact_ai_summary`) — **BEZ ZMIANY semantyki**, tylko przeniesione do
  nowego modelu. `AFTER_CUTOFF` to NOWOŚĆ, WYŁĄCZNIE dla `audit_log`/`plugin_invocation_log` — DROP
  wykonywany mimo niepustej partycji, log INFO (nie WARN).
- **Korekta względem oryginalnego opisu ticketu w `TASKS-BACKEND.md`:** opis zakresu (napisany przed
  BE-145) sugerował `DropMode.AFTER_CUTOFF` dla `contact*` "bez zmian" — to było NIEAKTUALNE już w
  chwili startu tej pracy (BE-145 wdrożone pierwsze, 2026-09-26, wprowadziło blokadę DROP niepustej
  partycji dla `contact*`). Zaimplementowano zgodnie ze skorygowaną uwagą BE127-01 w
  `TASKS-BACKEND.md`: `contact*` = `ONLY_IF_EMPTY`.

**`PlatformRetentionProperties`** (nowy plik, `@ConfigurationProperties(prefix =
"retention.platform")`, wzorzec `PluginInvocationProperties`) — `auditLogMonths`/
`pluginInvocationLogMonths`, domyślnie 24 (D5, ZAŁOŻENIE DESIGN EPIC-29/30 NIE potwierdzone
prawnie — już dziś zakładane przez `drop_old_audit_log_partitions`/
`drop_old_plugin_invocation_log_partitions`, `p_retention_months DEFAULT 24`, V004/V077).
`@PostConstruct validate()`: wartość `< 1` → fallback 24 + WARN, **NIE blokuje startu aplikacji**
(decyzja świadoma — w odróżnieniu od np. `JwtService`, błąd tej konfiguracji dotyczy wyłącznie
cotygodniowego joba, nie całej aplikacji).

**`PartitionScanner#countRows(String partitionTableName)`** (nowa metoda, BEZ grupowania po
tenancie) — dwa miejsca użycia: (1) ścieżka `AFTER_CUTOFF` (liczba wierszy do logu INFO, DROP i tak
wykonany), (2) sprawdzenie pustości `<tabela>_default` dla WSZYSTKICH 8 tabel (nie tylko
platformowych) — sygnał awarii rotacji, EPIC-29/DB-052. Sprawdza istnienie partycji w `pg_tables`
PRZED `FROM ONLY` (zwraca 0 defensywnie, bez wyjątku).

**Bug NPE (`countRowsByTenant`, `row[0] == null` dla `audit_log.tenant_id` nullable — zdarzenia
globalne, V004) naprawiony DWOMA sposobami:** (a) produkcyjna ścieżka platformowa używa WYŁĄCZNIE
nowego `countRows` (nigdy nie dotyka kodu parsującego UUID per tenant — zgodnie z explicite
zleceniem "dla ścieżki platformowej potrzebne jest tylko countRows, nie countRowsByTenant"); (b)
`countRowsByTenant` naprawiona NIEZALEŻNIE/defensywnie (`row[0] == null` → `TenantRowCount(null,
count)`), bo to generyczne, współdzielone narzędzie. Dowód bugu PRZED poprawką udokumentowany w
`@DisplayName` testu jednostkowego (`PartitionScannerImplTest`) + w teście Testcontainers
(`PartitionReclaimPlatformHorizonIntegrationTest`), który woła `countRowsByTenant` DYREKTNIE na
realnej partycji z wierszem `tenant_id IS NULL`.

**`application.yml`:** `retention.platform.audit-log-months`/`plugin-invocation-log-months`,
komentarz w stylu `retention.purge.delete-messages`/`email.attachments.pending-sweep-delete-enabled`
(D5 wymaga potwierdzenia prawnego).

**Testy:** 21 nowych (baza 2386 → 2407, `mvn clean verify -pl app` zielony) — 7 w
`PartitionReclaimJobTest` (`PlatformHorizonReclaim`), 3 w `PartitionReclaimJobTest`
(`DefaultPartitionPollutedWarns`), 4 w `PartitionScannerImplTest` (1 NPE regression + 3 `CountRows`),
7 w nowym `PartitionReclaimPlatformHorizonIntegrationTest` (Testcontainers, wzorzec
`PartitionReclaimJobIntegrationTest`). Zobacz [[feedback_mockito_when_thenreturn_executes_stubbed_call]]
dla pułapki odkrytej przy pisaniu testu "niezależność od RetentionPolicyService" na współdzielonym
statycznym mocku.

**WP-4 (local-demo) jawnie NIEUKOŃCZONE** — destrukcyjne, odłożone zgodnie z briefem (wzorzec
BE-133/BE-135).

Powiązane: [[project_be145_partition_reclaim_drop_guard]], [[project_be133_social_message_partition_retention]],
[[feedback_jpa_real_db_integration_test_harness]].
