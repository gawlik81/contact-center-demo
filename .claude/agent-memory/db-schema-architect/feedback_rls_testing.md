---
name: feedback_rls_testing
description: Test izolacji RLS w tym projekcie musi być wykonany pod SET ROLE app_user, nie pod ccapp — ccapp ma BYPASSRLS
metadata:
  type: feedback
---

Przy weryfikacji manualnej RLS (test izolacji między tenantami) NIGDY nie testuj pod rolą `ccapp` w psql tego środowiska dev — wynik będzie fałszywie pozytywny/negatywny niezależnie od poprawności polityki.

**Why:** Rola `ccapp` ma `rolbypassrls=true` (potwierdzone w `pg_roles`), co oznacza że RLS i nawet FORCE ROW LEVEL SECURITY są wobec niej ignorowane (to dokumentowane zachowanie PostgreSQL — BYPASSRLS nadrzędne wobec FORCE). Odkryte podczas weryfikacji DB-043 (V075, tenant_plugin_installation): test pod `ccapp` pokazał że tenant B widzi wiersz tenanta A, co wyglądało jak błąd migracji. Powtórzenie tego samego testu na już zaakceptowanej i działającej na produkcji tabeli `custom_disposition` (V070) dało identyczny "błędny" wynik — co potwierdziło, że problem jest w metodzie testowania, nie w polityce RLS.

W tej bazie istnieje rola `app_user` (`rolbypassrls=false`, `rolsuper=false`, ale `Cannot login` — używana tylko przez `SET ROLE app_user;` z sesji `ccapp`, ma GRANT SELECT/INSERT/UPDATE/DELETE na tabele domenowe). Test pod tą rolą daje poprawny, wiarygodny wynik izolacji.

**How to apply:** Każdy test manualny RLS (nowa tabela albo regresja istniejącej) wykonuj wewnątrz transakcji jako:
```sql
BEGIN;
SET ROLE app_user;
SELECT set_config('app.current_tenant_id', '<tenant_a>', false);
-- ... insert/select tenant A ...
SELECT set_config('app.current_tenant_id', '<tenant_b>', false);
-- ... select powinien dać 0 wierszy tenanta A; insert z tenant_id=tenant_a powinien być odrzucony przez WITH CHECK ...
RESET ROLE;
ROLLBACK;
```
Sam fakt że `relrowsecurity=t` i `relforcerowsecurity=t` w `pg_class` NIE jest wystarczającym dowodem działającej izolacji — trzeba przeprowadzić faktyczny test pod nie-bypass rolą.

**Dodatkowa pułapka odkryta w DB-045 (2026-06-20) — RLS na partycjach potomnych:**
`pg_class.relrowsecurity`/`relforcerowsecurity` na partycjach potomnych tabeli partycjonowanej są ZAWSZE `f` (false), niezależnie od ENABLE/FORCE RLS na tabeli nadrzędnej i niezależnie od kolejności (partycja utworzona przed czy po ENABLE RLS rodzica). To jest udokumentowane zachowanie silnika PostgreSQL, nie błąd migracji — zweryfikowane na izolowanym minimalnym przykładzie oraz potwierdzone identycznym zachowaniem na już produkcyjnej `contact`/`contact_2026_03`.

Skutek praktyczny: zapytanie PRZEZ tabelę nadrzędną (`SELECT ... FROM plugin_invocation_log`) poprawnie egzekwuje RLS na każdej partycji, w tym nowo utworzonej. Zapytanie bezpośrednio po nazwie partycji potomnej (`SELECT ... FROM plugin_invocation_log_2027_01`) OMIJA RLS rodzica całkowicie — nawet pod `app_user` (rolbypassrls=false).

**How to apply:** Przy testowaniu RLS na tabeli partycjonowanej zawsze testuj PRZEZ tabelę nadrzędną (to jest właściwy test akceptacyjny — tak właśnie odpytuje ją aplikacja przez JPA). Test bezpośredni na partycji po nazwie da fałszywy negatywny wynik i nie świadczy o błędzie — to jest oczekiwane. Zweryfikuj też (`grep` przez `backend/src/main/java/`) że aplikacja nigdy nie konstruuje zapytań SQL z nazwą partycji wprost (`<table>_YYYY_MM`) — jeśli kiedyś się to zdarzy (np. w raporcie ad-hoc czy skrypcie maintenance), to będzie realna dziura w izolacji multi-tenant.

Powiązane: [[contact_center_project]], [[feedback_pk_naming]]
