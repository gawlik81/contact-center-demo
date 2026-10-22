---
name: project_db080_revoke_six_tenant_tables
description: DB-080 (2026-10-07) – REVOKE na partycjach audit_log/contact/contact_transcription/contact_ai_summary/contact_event/plugin_invocation_log (V105–V110); wzorzec negatywnego testu przez PUBLIC; ryzyko contact_event podniesione na WYSOKIE (phone w metadata.target, agent_name); joby retencji nie blokujące
metadata:
  type: project
---

DB-080 (EPIC-30, hardening) zamyka ostatni z sześciu tabel tenantowych bez REVOKE na partycjach
(`email_message`/`social_message` zrobione wcześniej jako V102/V103). Sześć osobnych migracji
**V105 `audit_log`, V106 `contact`, V107 `contact_transcription`, V108 `contact_ai_summary`,
V109 `contact_event`, V110 `plugin_invocation_log`** — ta sama kolejność co w TASKS-DATABASE.md.
**V104 pozostaje zarezerwowany, NIEUŻYTY** (pod przyszły `DROP DEFAULT` na `email_message`/BE-134).

**Wzorzec migracji (1:1 z V102/V103, [[feedback_partition_grants_revoke]]):** pętla REVOKE po
`pg_inherits` (w tym `_default`) + `CREATE OR REPLACE` funkcji `create_<tabela>_partition` z treścią
1:1 ze źródła (zweryfikowane diff-em po odfiltrowaniu wyłącznie dodanego REVOKE) + REVOKE w gałęzi
tworzenia + asercja końcowa `RAISE EXCEPTION`. Przy kopiowaniu treści funkcji WARTO zweryfikować
grepem, że nie ma później żadnego `ALTER FUNCTION`/kolejnego `CREATE OR REPLACE` tej samej funkcji —
tu wszystkie sześć miały dokładnie JEDNĄ definicję w całym łańcuchu (V004/V007/V077/V088×3).

**Test wzorcowy:** `PartitionGrantsRevokeMigrationsTest` (jeden plik, enum z sześcioma tabelami,
`@ParameterizedTest @EnumSource`) — jedna świeża baza Testcontainers migrowana do wersji tuż przed
V105 (dynamicznie przez opis migracji, jak w [[feedback_migration_test_pre_post_db]]), zasiew dwóch
tenantów w partycji `_2026_10` (środek miesiąca — odporne na przesunięcie strefy sesji), zrzut
zachowania przez rodzica + polityk RLS PRZED i PO pełnym `migrate()`, porównanie map.

**Pułapka AC „ręczny GRANT przed migracją → kontrolowany błąd":** GRANT wprost `TO app_user` jest
zdjęty przez samą pętlę REVOKE migracji — to NIE jest błąd, to zamierzone samo-naprawianie. Żeby
faktycznie wywołać asercję `RAISE EXCEPTION`, trzeba dać `GRANT ... TO PUBLIC` na partycji: `PUBLIC`
nie jest objęty `REVOKE ... FROM app_user`, a `has_table_privilege('app_user', ...)` liczy też
uprawnienia z PUBLIC. Ten wariant realnie przerywa migrację (SQLState `P0001`) i udowadnia, że
asercja działa. Przydatne do każdego kolejnego REVOKE-na-partycjach ticketu.

**Ryzyko PII, zweryfikowane w bazie demo (read-only, [[feedback_readonly_audit_technique]]):**
- `contact_event.metadata` (JSONB) — podniesione z ŚREDNIEGO na **WYSOKIE**: klucz `target` niesie
  **numer telefonu** przy transferze/konsultacji na kontakt zewnętrzny (`ContactServiceImpl`:
  `meta.put("target", req.phoneNumber())` dla `target_type=PHONE`), a `agent_name`/`target_agent_name`
  to imiona pracowników (PII pracownika, nie klienta). Brak mechanizmu redakcji na tej ścieżce.
- `plugin_invocation_log` — potwierdzone jako ŚREDNIE: `request_payload_redacted` przechodzi przez
  `PiiRedactor` (klasa `com.contactcenter.domain.plugin.runtime.PiiRedactor`, redakcja rekurencyjna
  kluczy phone/email/name/address/pesel/...), ale `error_summary` (`e.getMessage()`) NIE jest
  redagowany — wolny tekst wyjątku zewnętrznego pluginu, dziś krótki i nieszkodliwy w demo, ale bez
  gwarancji na przyszłość.

**Joby retencji / sesje app_user — NIEBLOKUJĄCE:** grep `backend/app/src/main/java` = zero
wystąpień `SET ROLE`/`SET LOCAL ROLE`. `PartitionScannerImpl`/`PartitionMaintenanceJob`/
`PartitionReclaimJob` działają przez połączenie aplikacji (owner), REVOKE dotyczy wyłącznie
`app_user` — ścieżki ownera (`FROM ONLY`, `DROP TABLE`, `create_*_partition`) zweryfikowane
działające po REVOKE w Testcontainers.

Powiązane: [[feedback_partition_grants_revoke]], [[project_db067_email_message_partitioning]],
[[feedback_rls_insert_vs_update_semantics]] (audit_log bez polityki INSERT dla app_user — REVOKE
tego nie zmienia, zweryfikowane w zrzucie pre/post).
