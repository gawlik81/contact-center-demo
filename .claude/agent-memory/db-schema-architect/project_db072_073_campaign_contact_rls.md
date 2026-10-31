---
name: project_db072_073_campaign_contact_rls
description: DB-072/DB-073 (2026-10-08, V111/V112) D7 Opcja 1 — pelne RLS na campaign_contact_archive i campaign_contact; brak funkcji create_campaign_contact_partition (ADR DB-070); EXPLAIN dialera bez regresji; regresja AnonymizeCustomerExtensionTest naprawiona komentarzem (liczniki niezmienione)
metadata:
  type: project
---

**Fakt/decyzja:** DB-072 (V111, `campaign_contact_archive`) i DB-073 (V112, `campaign_contact`) wykonane
2026-10-08, w tej kolejności (zależność). Decyzja właściciela D7 (`DESIGN-message-retention-and-partitioning.md`
§3): **Opcja 1 — pełne RLS dla obu tabel** (`FOR ALL` + `WITH CHECK` + `FORCE`), bez wyjątku na DELETE,
symetrycznie z `contact`/`email_message`. Obie tabele miały `relrowsecurity=f` od zawsze (PII: phone,
first_name, last_name, email) — jedyna wcześniejsza izolacja to ręczny `WHERE tenant_id = ...` w zapytaniach/
funkcjach SQL. Status: ✅, pełny `mvn verify -pl app` (JDK 21): 2421/2421, BUILD SUCCESS.

**Why:** `campaign_contact` to ścieżka gorąca dialera (scheduler/RabbitMQ bez HTTP TenantContext w kilku
miejscach) — stąd AC wymagał przeglądu wszystkich ścieżek zapisu/odczytu przed włączeniem RLS, nie tylko
samej migracji. `campaign_contact_archive` blokowała `campaign_contact` (DB-072 → DB-073) bo wzorzec
weryfikacji i ryzyko dormant (archive_completed_campaign_contacts) jest dzielone między oba tickety.

**Ustalenia, których nie widać w samym kodzie migracji:**
- **Brak funkcji `create_campaign_contact_partition` w całym repo** (zweryfikowane grepem po wszystkich
  migracjach PRZED pisaniem V112) — potwierdza ADR DB-070: jedyna partycja to `campaign_contact_default`
  z V009 (LIST partitioning, PARTITION BY campaign_id), nic nigdy nie tworzy partycji per kampania. Różnica
  wobec V102/V103/V105-V110 (`contact`/`email_message`/`social_message`/`audit_log`/`contact_event`/
  `contact_transcription`/`contact_ai_summary`/`plugin_invocation_log`): **nie ma gałęzi "create" do
  poprawienia REVOKE-em w funkcji** — REVOKE w V112 jest wyłącznie pętla po `pg_inherits` (dziś 1 partycja).
  Jeśli ktoś kiedyś złamie ADR DB-070 i ręcznie doda `PARTITION OF campaign_contact`, musi RĘCZNIE dołożyć
  `REVOKE ALL ... FROM app_user` po `CREATE TABLE` — udokumentowane w nagłówku V112 jako przypomnienie, bez
  mechanizmu wymuszającego to automatycznie (bo nie ma funkcji do złapania tego w jednym miejscu).
- `campaign_contact_archive` NIE jest partycjonowana (V015, zwykła tabela) — V111 to tylko ENABLE+FORCE+
  CREATE POLICY, bez żadnego REVOKE (nie ma partycji).
- Partycja `campaign_contact_default` miała identyczne ACL jak rodzic (`SELECT/INSERT/UPDATE/DELETE` dla
  `app_user`, `ALTER DEFAULT PRIVILEGES` z V012) — potwierdzone przed migracją; REVOKE w V112 to zamyka,
  dostęp przez rodzica zweryfikowany bez regresji (wzorzec [[feedback_partition_grants_revoke]]).
- **`EXPLAIN` dialera przed/po** (`ProgressiveDialerServiceImpl.fetchNextPendingContact`, zapytanie już
  filtruje `WHERE tenant_id = ?` explicite — RLS dodaje redundantny, ale niezależny predykat): plan
  identyczny co do struktury (`Seq Scan on campaign_contact_default`), RLS dodaje JEDEN węzeł
  `Result -> One-Time Filter` (bo `current_setting(...)` jest `STABLE`, nie per-wiersz — planner ewaluuje
  go raz, nie w `Filter` wewnątrz skanu). Brak regresji. `idx_campaign_contact_dialer`
  (`WHERE status='PENDING'`, partial) NIE był używany ANI PRZED, ANI PO migracją — bo zapytanie filtruje
  `status IN ('PENDING','NO_ANSWER')`, co nie kwalifikuje się do indeksu partial na `status='PENDING'`.
  To jest pre-istniejący rozjazd (odnotowany już w ADR DB-070), niezależny od tej migracji — nie naprawiać
  tutaj, kandydat do osobnego ticketu indeksowego.
- **Dormant risk (udokumentowany, zaakceptowany, NIE naprawiony w tej migracji):**
  `archive_completed_campaign_contacts()` (V015) wstawia wiersze WIELU tenantów w JEDNYM wywołaniu (pętla
  `FOR v_campaign IN SELECT ... FROM campaign` bez filtra tenant_id) — pod rolą bez `BYPASSRLS` `WITH CHECK`
  odrzuciłby wiersze niezgodne z GUC sesji, więc funkcja przestałaby działać poprawnie dla >1 tenanta na
  wywołanie. Funkcja jest dziś martwa (pg_cron wyłączony, 0 wywołań z Javy, DB-070). Ryzyko aktywuje się
  TYLKO gdy (a) BE-120 przywróci wywoływanie I (b) zapadnie odłożona decyzja o przełączeniu roli połączenia
  na `app_user`. Udokumentowane w nagłówkach V111/V112 i w DESIGN §3 D7 — BE-120 musi to przeczytać przed
  przywróceniem funkcji (rozwiązanie: pętla per-tenant z `set_tenant_context` przed każdym wywołaniem, albo
  `SECURITY DEFINER`).
- **Regresja testów — ten sam wzorzec jak przy DB-064/V099:** `AnonymizeCustomerExtensionTest` (test grupy
  "RLS/app_user (C)") miał asercje z komentarzem "campaign_contact(_archive): brak RLS w ogóle" — liczniki
  (`1`) NIE zmieniły się po V111/V112, bo `anonymize_customer`/`purge_campaign_contact_archive` już filtrują
  `WHERE tenant_id = p_tenant_id` wewnętrznie, a test ustawia GUC na TĘ SAMĄ wartość (`asAppUser(c, TENANT_A)`
  + wywołanie z `p_tenant_id=TENANT_A`) — RLS spełnione trywialnie, zmienia się tylko POWÓD działania
  (polityka dopuszcza, nie brak RLS). Poprawiono WYŁĄCZNIE komentarze `.as(...)` i nagłówek sekcji, zero
  zmian w asercjach/wartościach. Analogicznie `CampaignContactArchivePurgeTenantIsolationTest` miał Javadoc
  twierdzący "NIE ma RLS wcale" — zaktualizowany (test sam pozostał bez zmian logiki: łączy się rolą
  `cc_test`, superuser Testcontainers, zawsze `BYPASSRLS`, więc FORCE RLS nie wpływa na wynik; wartość testu
  to weryfikacja klauzuli `WHERE` samej funkcji, nie RLS).
- **Metoda weryfikacji (reużyta z sesji DB-080/DB-064):** dry-run migracji w transakcji z `ROLLBACK`
  (`docker cp` do `cc-postgres` + `psql -f` wrapowany `BEGIN;...ROLLBACK;`) na żywej bazie demo PRZED
  napisaniem testu Java — złapał 0 błędów składniowych za pierwszym razem. Manualny test RLS
  (SAVEPOINT/ROLLBACK TO SAVEPOINT per przypadek, pod `SET ROLE app_user`) pokrył: izolację SELECT,
  cross-tenant INSERT (`42501`), cross-tenant UPDATE/DELETE (0 wierszy), dostęp wprost do
  `campaign_contact_default` (`permission denied`), dostęp przez rodzica (działa), `purge_campaign_contact_archive`
  pod GUC własnego tenanta.
- **Test Testcontainers:** nowa klasa `CampaignContactRlsMigrationsTest`
  (`backend/app/src/test/java/com/contactcenter/infrastructure/config/`) — wzorzec „jedna świeża baza do
  najnowszej wersji" (nie pre/post jak `PartitionGrantsRevokeMigrationsTest`), bo DB-072/073 DODAJĄ zdolność
  RLS, nie zmieniają zachowania na danych zastanych na granicy migracji. 14 testów: `pg_class`/`pg_policies`,
  własny tenant R/W, cross-tenant INSERT (`42501`), cross-tenant SELECT/UPDATE/DELETE (0 wierszy, nie błąd),
  bez GUC, dostęp wprost do partycji `_default` (SELECT+INSERT denied), superuser nadal widzi partycję
  wprost (REVOKE dotyczy tylko `app_user`).
- Numeracja: V111 (DB-072) → V112 (DB-073). V104 pozostaje zarezerwowane (przyszła migracja
  `message_at DROP DEFAULT`, jeszcze nie stworzona) — potwierdzone przed pisaniem migracji przez
  `git ls-tree` na WSZYSTKICH gałęziach + `flyway_schema_history` żywej bazy (najwyższy zastosowany: V110).

**How to apply:** przy DB-074 (reszta tabel z klasyfikacji DB-071) — ten sam wzorzec migracji
(ENABLE+FORCE+policy ALL+WITH CHECK, weryfikacja DO $$...RAISE EXCEPTION), ale pamiętać że NIE każda
tabela ma funkcję `create_*_partition` do patchowania REVOKE-em (sprawdzić grepem PRZED pisaniem, jak
tutaj) — jeśli nie ma, REVOKE jest tylko pętlą po `pg_inherits` bez drugiego kroku. Przy każdej przyszłej
migracji RLS na tabeli używanej przez istniejące testy Testcontainers pod `SET ROLE app_user` — grep
`SET ROLE` w `backend/app/src/test/java` PRZED pisaniem migracji, żeby złapać regresje w komentarzach/
asercjach zanim zrobi to CI (ten sam wzorzec już raz zadziałał przy DB-064/V099).

Powiązane: [[project_db070_campaign_contact_partition_adr]], [[project_db071_rls_classification_report]],
[[feedback_partition_grants_revoke]], [[feedback_rls_testing]], [[feedback_explain_rls_deny_proof]],
[[feedback_rls_insert_vs_update_semantics]], [[project_db064_email_social_message_rls_write_policies]]
