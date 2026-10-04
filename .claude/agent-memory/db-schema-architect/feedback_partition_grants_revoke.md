---
name: feedback_partition_grants_revoke
description: ALTER DEFAULT PRIVILEGES (V012) daje app_user GRANT na KAŻDEJ nowej partycji -> RLS rodzica obchodzony po nazwie partycji. Wzorzec: REVOKE w pętli po pg_inherits + REVOKE w funkcji create_*_partition + asercja; zapytania przez rodzica NIE psują się. Plus pułapka SQLException/assertThat w testach.
metadata:
  type: feedback
---

**Reguła:** każda partycjonowana tabela tenantowa po `CREATE TABLE ... PARTITION OF` dostaje `SELECT/INSERT/UPDATE/DELETE` dla `app_user` z domyślnych uprawnień (V012). Partycje nie mają RLS, więc bezpośredni dostęp po nazwie partycji omija politykę rodzica. Naprawa: `REVOKE ALL ON TABLE <partycja> FROM app_user` (1) w migracji po swapie dla WSZYSTKICH partycji z `pg_inherits` (w tym `_default`), (2) w funkcji `create_*_partition` po `CREATE TABLE` (wewnątrz gałęzi IF NOT EXISTS), (3) asercja w migracji: `has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE')` = 0 dla każdej partycji.

**Why:** decyzja właściciela 2026-10-04 (partycje nie mają być dostępne wprost dla app_user). Zmierzone na scratch PG 16.13: przed REVOKE pod GUC tenanta T2 zapytanie po partycji zwraca wiersze T1 (3 zamiast 1).

**How to apply:**
- Zweryfikowane: REVOKE NIE psuje SELECT/INSERT(routing do partycji i _default)/UPDATE/DELETE przez tabelę nadrzędną. PostgreSQL nie sprawdza uprawnień partycji, gdy zapytanie idzie przez rodzica. Bezpośredni dostęp = `permission denied` (SQLState 42501).
- Zweryfikowane: nowa partycja bez REVOKE dostaje GRANT automatycznie (`has_table_privilege` = t), więc REVOKE w funkcji jest konieczny, nie opcjonalny.
- REVOKE nie zakłada blokady na relacji (`pg_locks` puste) — tylko zmiana ACL w katalogu.
- REVOKE w funkcji wymaga, by wywołujący był właścicielem partycji. Po `CREATE` jest, więc działa; REVOKE w gałęzi ELSE (partycja już istnieje) zmieniłby zachowanie dla nie-właściciela, dlatego zostaje tylko w gałęzi tworzenia.
- Dotyczy każdej rodziny partycji (`contact`, `contact_event`, `audit_log` itd.): przy kolejnych ticketach partycjonowania dopisywać REVOKE od razu.
- Uwaga: backend łączy się rolą z `DB_USERNAME` (nie `SET ROLE app_user`), więc REVOKE chroni sesje `app_user`, a nie samo połączenie aplikacji. Nie twierdzić, że REVOKE „zamyka" RLS dla backendu, dopóki właściciel połączenia ma BYPASSRLS.

**Pułapka testowa (Java/AssertJ):** `SQLException` implementuje `Iterable<Throwable>`, więc `assertThat(sqlException)` jest dwuznaczne (kompilacja pada z "reference to assertThat is ambiguous"). Rzutować na `(Throwable)` albo asertować `isNotNull()` na zmiennej typu `Throwable`.

**Pułapka kontroli RED:** żeby pokazać, że nowe asercje `permission denied` są realne, wyłączyć REVOKE w kopii migracji (`sed` na `NULL;` i `IF FALSE`), uruchomić test, a potem przywrócić z kopii i sprawdzić `sha256sum -c`. Pełny opis: [[project_db067_email_message_partitioning]].

Powiązane: [[feedback_rls_testing]] (testuj RLS pod SET ROLE app_user, nie pod właścicielem), [[project_db067_email_message_partitioning]], [[project_db065_social_message_partitioning]].
