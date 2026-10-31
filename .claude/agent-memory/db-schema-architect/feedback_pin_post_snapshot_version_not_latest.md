---
name: feedback_pin_post_snapshot_version_not_latest
description: Testy Testcontainers typu "pre/post snapshot" (zrzut katalogu/zachowania przed i po migracji) musza pinowac POST do konkretnej wersji (ostatniej migracji WLASNEGO ticketu), nie do "latest"/Flyway.migrate() bez targetu -- inaczej kolejny, niezwiazany ticket na tej samej tabeli falszywie "psuje" test
metadata:
  type: feedback
---

**Reguła:** gdy test Testcontainers robi zrzut stanu (katalog `pg_policies`/`pg_class`, zachowanie
zapytań) PRZED pierwszą migracją własnego ticketu i PO ostatniej, a następnie porównuje
`.isEqualTo(...)` (dowodząc, że WŁASNE migracje nie zmieniły czegoś poza swoim zakresem) — wersja
"PO" musi być pinowana dynamicznie (przez `Flyway#info()`, po opisie/description, bez numerów na
sztywno — wzorzec już ustalony dla "PRZED" w [[feedback_migration_test_pre_post_db]]) do wersji TUŻ
PO ostatniej migracji TEGO ticketu, **NIE** do `Flyway.migrate()` bez `.target(...)` (czyli "latest
na branchu").

**Why:** `PartitionGrantsRevokeMigrationsTest` (DB-080, V105-V110: REVOKE na partycjach 6 tabel)
migrował snapshot "POST" do najnowszej wersji na branchu. Test
`throughParent_andRlsCatalog_identicalToPreMigrationSnapshot` porównywał CAŁY zrzut (polityki RLS,
flagi `relrowsecurity`/`relforcerowsecurity`, zachowanie zapytań) między PRE i POST, zakładając że
DB-080 (REVOKE na ACL partycji) nie zmienia TYCH rzeczy — prawda w chwili napisania testu. Przy
DB-074 (2026-10-08), DWA kolejne, zupełnie niezwiązane tickety (V117 `contact`, V122 `audit_log`)
UMYŚLNIE dodały nowe polityki RLS na 2 z tych samych 6 tabel — ponieważ POST był pinowany do
"latest", test fałszywie wykrył to jako regresję DB-080, mimo że DB-080 w ogóle nie dotyczyła
polityk (tylko ACL partycji).

**How to apply:** przy pisaniu nowego testu pre/post snapshot — policz `lastOwnVersion` (max wersji
migracji WŁASNEGO ticketu, analogicznie do istniejącego `preTarget`/`firstNew`) i użyj
`.target(lastOwnVersion)` dla migracji budującej snapshot POST, zamiast gołego `.migrate()` na
instancji Flyway bez targetu. Jeśli trafisz na JUŻ istniejący test tego typu, który łapie fałszywą
regresję po Twojej migracji na współdzielonej tabeli — to prawdopodobnie ten sam błąd projektowy
(POST pinowany do "latest"), NIE Twoja migracja. Naprawa jest w teście (zmiana targetu), nie w
Twojej migracji — zero zmian w logice asercji potrzebne.

Powiązane: [[feedback_migration_test_pre_post_db]], [[project_db074_rls_completion]],
[[project_db080_revoke_six_tenant_tables]]
