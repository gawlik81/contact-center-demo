---
name: project_be138_rls_validation_service
description: BE-138 RlsValidationService — lista tabel TENANT z zapytania SQL (nie twarda lista), pokrycie komend + FORCE, flaga rls.validation.fail-on-bypass, odkryty FORCE-gap na 7 tabelach poza DB-071/DB-074
metadata:
  type: project
---

**BE-138 (EPIC-30, 2026-10-08).** Przebudowa `RlsValidationService`
(`backend/app/src/main/java/com/contactcenter/infrastructure/config/RlsValidationService.java`) —
poprzednia wersja sprawdzała tylko czy 11 zahardkodowanych tabel ma JAKĄKOLWIEK politykę w
`pg_policies` (polityka tylko-SELECT przechodziła, mimo że zapisy pod rolą ograniczoną były po
cichu odrzucane).

**Lista tabel = zapytanie SQL, nie twarda lista.** `findTenantClassTables()` replikuje metodologię
DB-071: `pg_class` ⋈ `pg_namespace` ⋈ `information_schema.columns` (`column_name='tenant_id'`,
`is_nullable='NO'`), partycje potomne wykluczone przez `NOT EXISTS (… pg_inherits …)`, GLOBAL
(`tenant`, `plugin_version`) wykluczone po nazwie. Klasa MIXED (`audit_log`, `app_user`,
`refresh_token`, `gdpr_processing_register` — `tenant_id` nullable) wykluczona NATURALNIE przez
`is_nullable='NO'`, bez wymieniania po nazwie — to jest ten sam heurystyk, którym DB-071 odróżniał
TENANT od MIXED.

**Pokrycie komend + FORCE + relrowsecurity.** `findCommandCoverageViolations(tables)`: `cmd='ALL'`
ALBO komplet `SELECT/INSERT/UPDATE/DELETE` w `pg_policies`, plus `relrowsecurity` I
`relforcerowsecurity` z `pg_class` (AC wymieniał tylko FORCE — `relrowsecurity` dodane świadomie,
żeby odróżnić „zero RLS wcale" od „RLS ON, niepełne komendy"). Zob.
[[feedback_jdbc_query_overload_ambiguous_lambda]] dla pułapki kompilacji przy bindowaniu `ANY(?)`.

**Rola połączenia + `rls.validation.fail-on-bypass`.** `SELECT rolname, rolsuper, rolbypassrls FROM
pg_roles WHERE rolname = current_user`. Decyzja (AC zostawiał otwartą): `fail-on-bypass=true` + rola
omija RLS → `IllegalStateException`, PRZERYWA start — jedyny świadomy wyjątek od „nie blokuj startu"
w tej klasie, bo flaga bez realnego efektu (tylko zmiana poziomu logu) nie miałaby sensu jako
przełącznik produkcyjny. Pozostałe naruszenia (komendy, FORCE) NIGDY nie rzucają, nawet przy
`fail-on-bypass=true`. Nowy klucz `application.yml`: `rls.validation.fail-on-bypass:
${RLS_VALIDATION_FAIL_ON_BYPASS:false}`.

**ODKRYCIE — nowy ticket DB potrzebny (NIE naprawiane w BE-138, poza zakresem, zero `.sql`
dotkniętych):** 7 tabel klasyfikacji DB-071 jako TENANT klasa A („pełne pokrycie komend") NIE MAJĄ
`FORCE ROW LEVEL SECURITY` (`relforcerowsecurity=false`) — `agent_break`, `agent_group`,
`phone_number`, `phone_routing_rule`, `scheduled_callback`, `social_integration`,
`tenant_twilio_config`. Przyczyna: V012 ustawia FORCE tylko dla `customer`/`contact`/`campaign`/
`queue`; `email_message`/`social_message` dostają FORCE później w V099; te 7 nigdzie. DB-071 nigdy
tego nie sprawdzał (tylko pokrycie komend). Fix: `ALTER TABLE <tabela> FORCE ROW LEVEL SECURITY;`
×7 — do zgłoszenia `db-schema-architect` jako nowy ticket DB.

**Testy:** `RlsValidationServiceIntegrationTest`
(`backend/app/src/test/java/com/contactcenter/infrastructure/config/`) — Testcontainers + pełny
Flyway, BEZ `JpaTestContext` (serwis ma tylko `JdbcTemplate`, konstruowany bezpośrednio
`new RlsValidationService(jdbc)`; `ReflectionTestUtils.setField(service, "failOnBypass", …)` do
testowania flagi, bo pole `@Value` nie jest field-injected poza kontenerem Springa). Kluczowy wzorzec
testu odpornego na stan równoległej migracji (DB-074 lądował W TEJ SAMEJ turze, V113→V124 podczas
pisania tego testu): `commandCoverageViolations_matchesIndependentlyComputedLiveCatalogState` liczy
oczekiwany wynik NIEZALEŻNYM zapytaniem w samym teście (ta sama metodologia, inny kod — wykrywa
regresję logiki serwisu niezależnie od stanu schematu), a
`commandCoverageViolations_onlyKnownDb074PendingTablesMayViolate` pilnuje że naruszenia na żywym
schemacie nie wychodzą poza znany/udokumentowany zbiór (zakres DB-074 + 7 tabel odkrycia FORCE) —
każda inna tabela = prawdziwa regresja. Test negatywny/pozytywny używa tabel `be138_test_*`
tworzonych WEWNĄTRZ testu (`CREATE TABLE`/`DROP TABLE` w `finally`), całkowicie niezależnych od
czasowania równoległego agenta.

Zob. też [[project_epic30_be123_rls_classification]] (jeśli istnieje) dla kontekstu DB-071/DB-074.
