---
name: project_be145_partition_reclaim_drop_guard
description: BE-145 — PartitionReclaimJob blokuje DROP TABLE niepustej partycji (contact*); BE-123 (horyzont platformowy audit_log/plugin_invocation_log) zaimplementowane 2026-10-08, patrz [[project_be123_platform_horizon_reclaim]]
metadata:
  type: project
---

**AKTUALIZACJA 2026-10-08:** BE-123 ukończone — patrz [[project_be123_platform_horizon_reclaim]]
dla pełnego opisu refaktoru `TABLE_CATEGORIES` → `ReclaimTarget`/`ThresholdSource`/`DropMode`.
Poniższa notatka (oryginalna, z 2026-09-26) jest zostawiona jako historyczny opis stanu PRZED
tym refaktorem — przydatna do zrozumienia DLACZEGO `ThresholdSource`/`DropMode` nie istniały
wcześniej i co BE-145 świadomie nie budowało "na zapas".

BE-145 (2026-09-26, ✅, poza epikiem — jak DB-055/BE-144) naprawia `PartitionReclaimJob`
(`backend/app/src/main/java/com/contactcenter/domain/retention/PartitionReclaimJob.java`):
`warnIfStillHasRows` zmienione z `void` (tylko `log.warn`, DROP kontynuowany bezwarunkowo) na
`boolean` — `reclaimTable` teraz robi `continue` (pomija `dropPartition`) gdy partycja-kandydat
do DROP wciąż ma ≥1 wiersz. Trigger: code review BE-127 (BE127-01) — `DROP TABLE` partycji był
TRZECIM, nieobsłużonym mechanizmem usuwania wierszy `contact` (poza `deleteContacts`/
`deleteBatchOlderThan`), mogącym osierocić `email_message`/`social_message` (brak FK do
`contact`) bez żadnej ścieżki ich późniejszego usunięcia.

**Stan `TABLE_CATEGORIES` w chwili tej pracy (potwierdzone grepem):** WYŁĄCZNIE 4 wpisy
per-tenant (`contact`, `contact_event` → `CONTACT_INTERACTIONS`; `contact_transcription`,
`contact_ai_summary` → `TRANSCRIPTS`). BE-123 (refaktor `TABLE_CATEGORIES` → `ReclaimTarget`/
`ThresholdSource`/`DropMode`, dodanie wpisu platformowego `audit_log`/`plugin_invocation_log`)
było ⬜ NIEZAIMPLEMENTOWANE — więc blokada DROP wprowadzona przez BE-145 dotyczy dziś
BEZWARUNKOWO wszystkich wpisów mapy, bez potrzeby `ThresholdSource`. Świadomie NIE zbudowano
infrastruktury `ThresholdSource`/`DropMode` na zapas (kolizja z przyszłym refaktorem BE-123).

**Jeśli w przyszłości implementujesz BE-123:** musisz dodać analogiczny wyjątek — DROP mimo
niepustej partycji (INFO, nie blokada) — TYLKO dla nowego wpisu platformowego. NIE usuwać
blokady dla `contact*` (opis/AC BE-123 mówiący „`DropMode.AFTER_CUTOFF` dla `contact*` — bez
zmian" jest NIEAKTUALNY po BE-145 — skorygowane w `TASKS-BACKEND.md`, sekcja BE-123, uwaga
BE127-01). Javadoc klasy ma sekcję „BE-145 vs. przyszły BE-123" z tą samą wskazówką.

**Decyzja: sweep dangling po FK (`NOT EXISTS`) dla `email_message`/`social_message` — POMINIĘTY**
(opcja Could z BE-127/BE127-01), z tym samym uzasadnieniem co `[[project_epic30_be127_orphan_message_purge]]`:
blokada DROP u źródła zamyka JEDYNĄ znaną ścieżkę tworzenia dangling; koszt `NOT EXISTS` bez
`started_at` = probing PK każdej partycji, bez partition pruning. Rewizja potrzebna TYLKO przy
odkryciu INNEJ ścieżki usuwania wierszy `contact`.

**Testowanie — wzorzec „mock + real-DB dowód":** `PartitionReclaimJobTest` (Mockito,
`PartitionScanner` mockowany) nie może udowodnić, że pominięty DROP faktycznie nie usunął
partycji z `pg_class` — dodano `PartitionReclaimJobIntegrationTest` (Testcontainers, PEŁNY
Flyway, `PartitionScannerImpl` REALNA + `RetentionPolicyService` mockowany), sprawdzający
`pg_tables`/`FROM ONLY <partycja>` po przebiegu jobu. Partycje testowe tworzone przez prawdziwą
funkcję SQL `create_contact_partition(year, month)` (V007) wołaną przez
`jdbc.execute("SELECT create_contact_partition(%d, %d)".formatted(...))` (nie `jdbc.update` —
funkcja `RETURNS VOID` zwraca ResultSet z pustą kolumną, `execute()` go obsługuje,
`executeUpdate()` może się na tym wyłożyć). Daty partycji testowych WZGLĘDNE
(`LocalDate.now().minusMonths(N)`), nie hardkodowane roczniki — unikaj kolizji z innymi testami
na tej samej współdzielonej bazie (`ContactRefIntegrityNarrowingTest` używa 2001/2027/2028,
migracje V007/V085-088 używają 2026).

Powiązane: [[project_epic30_be127_orphan_message_purge]], [[feedback_jpa_real_db_integration_test_harness]].
