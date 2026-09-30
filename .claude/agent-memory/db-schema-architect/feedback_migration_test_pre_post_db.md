---
name: feedback-migration-test-pre-post-db
description: Wzorzec testu Testcontainers dla migracji zmieniajacej zachowanie funkcji/triggera (baza pre = Flyway target = wersja tuz przed migracja wyznaczona dynamicznie z Flyway#info() + baza post = CREATE DATABASE TEMPLATE), plus pulapki pgjdbc/AssertJ/Maven z sesji DB-079
metadata:
  type: feedback
---

**Wzorzec (ContactRefIntegrityNarrowingTest, DB-079):** jeden kontener `postgres:16-alpine`; baza `pre` migrowana Flyway `target(<wersja tuz przed migracja>)`; fixture zasilany PRZED migracja (kontakty przy zywych klientach, potem soft-delete — kolejnosc jak w produkcji); potem `CREATE DATABASE post TEMPLATE pre` (najpierw `pg_terminate_backend` na sesje `pre`, polaczenie administracyjne do bazy `postgres`) i pelny Flyway na `post`. Testy `v093_*` dokumentuja blad na starym schemacie, reszta sprawdza nowe zachowanie na TYCH SAMYCH danych. Kazda proba w osobnej transakcji zawsze cofanej; negatywne asercje sprawdzaja SQLState `P0001` + tresc komunikatu triggera (inaczej odrzucenie moze byc z innego powodu — CHECK/RLS 42501).

**Wersje dynamicznie, zero numerow na sztywno (poprawka po CR DB079-01):** `Flyway.configure()…load().info().all()` (dziala na pustej bazie: wszystko PENDING) → posortuj po `MigrationVersion` → znajdz migracje po OPISIE (= nazwa pliku bez `V0xx__`, `_` → spacja, np. `narrow contact ref integrity on update`) → `target(MigrationVersion poprzednia)`. NIE uzywaj `version::int` (pekaja wersje z kropka) ani `max(...) > 93` (przechodzi po zgubieniu wlasnej migracji). Guard „migracja jest w lancuchu i SUCCESS na `post`, PENDING na `pre`, `info().current()` na `pre` = wersja przed" — jako zwykly `@Test` (nie `@BeforeAll`), a gdy migracji brak, `@BeforeAll` migruje `pre` do najnowszej (fallback), zeby przebieg RED dawal diagnostyke (guard + testy zachowania padaja) zamiast jednego bledu inicjalizacji klasy. Nazw metod `v093_*` i guardu NIE zmieniaj — odwoluja sie do nich TASKS-DATABASE.md/CR-DATABASE.md (DB-062). Wspolny helper: `TestcontainersSupport.ensureDockerApiVersion()` w bloku `static`.

**Dowod „test pada na starym schemacie":** tymczasowo ukryj plik migracji NIE tylko w `backend/src/main/resources/db/migration`, ale tez usun jego kopie z `backend/app/target/classes/db/migration` (Maven bez clean jej nie usuwa), wszystko w jednym skrypcie pod `flock` z `trap` przywracajacym plik (drugi agent nie uruchomi wtedy Mavena w oknie bez pliku). Plik publikuj atomowo: kopia do `backend/src/main/resources/db/.V0xx.tmp` (ten sam FS, poza katalogiem skanowanym) + `mv`.

**Pulapki:** (1) `assertThat(SQLException)` jest niejednoznaczne (SQLException jest `Iterable<Throwable>`) — rzutuj `(Throwable) e`; (2) pgjdbc `getString` na kolumnie boolean daje `"t"/"f"` — uzyj `String.valueOf(rs.getObject(1))`; (3) guard „post ma nowa migracje" wrzucony do `@BeforeAll` ubija cala klase i psuje diagnostyczny przebieg RED — zrob z niego zwykly `@Test`; (4) w skryptach scratch pomocnicza funkcja `pg_temp.probe(label, sql)` z sentinelem `RAISE … ERRCODE 'P0999'` w podtransakcji wycofuje kazda probe, wiec stan jest identyczny miedzy probami i miedzy przebiegami przed/po.

**Why:** w DB-079 dowod musial byc „dzialaniem, nie papierowo", a migracja ma dzialac na danych zastanych. **How to apply:** kazda migracja zmieniajaca zachowanie funkcji SQL/triggera/polityki (DB-062, DB-071/074 itp.).

Powiazane: [[project-db079-contact-ref-integrity-narrowing]], [[feedback_scratch_and_catalog_gotchas]]
