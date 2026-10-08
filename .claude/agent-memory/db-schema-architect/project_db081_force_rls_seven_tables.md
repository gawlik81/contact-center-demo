---
name: project_db081_force_rls_seven_tables
description: DB-081/V125 (2026-10-08) — FORCE ROW LEVEL SECURITY dodany na 7 tabelach klasy TENANT A, które V012 nigdy objął (agent_break, agent_group, phone_number, phone_routing_rule, scheduled_callback, social_integration, tenant_twilio_config); zero regresji w RlsValidationServiceIntegrationTest (BE-138)
metadata:
  type: project
---

**Co:** V125 — 7× `ALTER TABLE ... FORCE ROW LEVEL SECURITY` + blok weryfikacyjny `DO $$ ... RAISE
EXCEPTION $$`. Jedna migracja, mechaniczna, bez zmiany polityk/`relrowsecurity`/GRANT. Tabele:
`agent_break`, `agent_group`, `phone_number`, `phone_routing_rule`, `scheduled_callback`,
`social_integration`, `tenant_twilio_config`.

**Dlaczego istniały (historia V012):** `V012__row_level_security.sql` włączył RLS na wielu
tabelach, ale FORCE ustawił tylko dla `customer`/`contact`/`campaign`/`queue` (linie 83-86 tego
pliku). Pozostałe tabele dostały FORCE w różnych późniejszych ticketach: `email_message`/
`social_message` → V099 (DB-064); `audit_log`/`app_user`/`ivr_tree` → V121-V123 (DB-074). Te 7
nigdy — raport klasyfikacyjny DB-071 sprawdzał TYLKO pokrycie komend w `pg_policies`, nie
`relforcerowsecurity`, więc luka nie była widoczna w tamtym raporcie. Odkryta przez agenta
backendowego podczas BE-138 (generalizacja [[project_db074_rls_completion]] → `RlsValidationService`).

**Dlaczego to w ogóle ma znaczenie:** FORCE kontroluje, czy RLS obowiązuje WŁAŚCICIELA tabeli
(gdy ten właściciel nie ma BYPASSRLS). Dziś `ccapp`/Testcontainers superuser mają BYPASSRLS, więc
zero efektu praktycznego — czysto defense-in-depth na przyszłość (analogiczne ryzyko do
[[feedback_partition_grants_revoke]], tylko na poziomie FORCE nie GRANT).

**Interakcja z `RlsValidationServiceIntegrationTest` (BE-138, równoległa tura) — WERYFIKACJA, nie
zgadywanie:** `findTenantClassTables()` liczy zakres dynamicznie z `tenant_id NOT NULL` (bez
hardkodowanej listy) — wszystkie 7 tabel MIAŁY `tenant_id NOT NULL`, więc już PRZED V125 były w
zakresie walidacji i już PRZED V125 zgłaszały naruszenie braku FORCE. Test
`commandCoverageViolations_onlyKnownDb074PendingTablesMayViolate` używa
`violatingTables.isSubsetOf(allowedToStillViolate)`, a `allowedToStillViolate` jawnie zawiera
tych 7 nazw z komentarzem "wymaga osobnego ticketu DB-XXX" — autor testu PRZEWIDZIAŁ ten ticket.
V125 tylko ZMNIEJSZA `violatingTables` (nadal podzbiór). Potwierdzone uruchomieniem:
`mvn test -Dtest=RlsValidationServiceIntegrationTest` po V125 → 12/12 zielone, log
pokazuje "Wszystkie tabele klasy TENANT (DB-071) mają pełne pokrycie komend RLS ... oraz
ENABLE+FORCE ROW LEVEL SECURITY" (czyli V125 faktycznie domyka WSZYSTKIE pozostałe naruszenia w
schemacie — nic już nie zostaje w `allowedToStillViolate`, ale `isSubsetOf` nie wymaga pełnego
zbioru, więc to nie był warunek zdania testu, tylko miły efekt dodatkowy).

**Test:** nowa klasa `Db081ForceRlsSevenTenantATablesMigrationTest` — wzorzec „jedna świeża baza do
najnowszej wersji" (jak [[project_db064_email_social_message_rls_write_policies]], nie pre/post,
bo migracja tylko DODAJE FORCE, nie zmienia zachowania app_user). 3 testy: katalog
(relrowsecurity+relforcerowsecurity dla 7), pokrycie komend niezmienione, `scheduled_callback`
pod `app_user` (CRUD własny tenant + cross-tenant 42501) bez regresji.

**Świadomie NIE zrobione:** dowód behawioralny z `ALTER TABLE ... OWNER TO` na rolę bez BYPASSRLS
(żeby pokazać RÓŻNICĘ przed/po FORCE) — nie jest wzorcem żadnego istniejącego testu RLS w repo
(ani `RlsValidationServiceIntegrationTest`, ani `Db074RlsCompletionMigrationsTest` tego nie robią
dla FORCE), a katalogowy dowód jest konsekwentny z całą resztą historii ticketów FORCE (V099,
V121-V123).

**mvn verify -pl app:** Tests run: 2461, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (+3 względem DB-074: 2458, wyłącznie nowa klasa testowa, zero regresji).
