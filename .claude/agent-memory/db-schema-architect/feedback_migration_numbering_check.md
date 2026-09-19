---
name: feedback_migration_numbering_check
description: Przed wyborem numeru migracji Flyway sprawdź nie tylko katalog na develop, ale też flyway_schema_history na żywej bazie i wszystkie lokalne gałęzie — V092 był zajęty przez niezmergowaną gałąź
metadata:
  type: feedback
---

Numer nowej migracji wybieraj po TRZECH źródłach, nie po samym `ls backend/src/main/resources/db/migration/`:
1. katalog na bieżącej gałęzi,
2. `SELECT version, description FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5` na żywej bazie (`docker exec cc-postgres psql -U ccapp -d contact_center`),
3. `git ls-tree -r --name-only <branch> -- backend/src/main/resources/db/migration` dla KAŻDEJ gałęzi z `git branch -a` (lokalne gałęzie feature bywają niepushowane).

**Why:** Sesja DB-055 (2026-09-19): zlecenie mówiło "V092 jest wolne (V091 najwyższe na develop)", a w rzeczywistości V092 (`social_integration_global_unique_page`) było już ZASTOSOWANE w żywej bazie (2026-08-29) i istniało na lokalnej gałęzi `feature-socialmedia` (niepushowanej). Użycie V092 dałoby duplikat wersji po merge i niezgodność checksum na żywej bazie.

**How to apply:** Gdy najwyższy numer w bazie/innej gałęzi jest wyższy niż na `develop`, weź następny wolny (tu V093) i ZGŁOŚ właścicielowi pułapkę wdrożeniową: build z samego `develop` (V091 + V093 bez pliku V092) na bazie z zastosowanym V092 nie przejdzie walidacji Flyway (`V092 MISSING_SUCCESS`, "Detected applied migration not resolved locally: 092") — V092 przestaje być `*:future` (domyślnie ignorowane), gdy lokalnie rozpoznane jest wyższe V093. Najpierw trzeba wprowadzić plik V092 na develop (merge gałęzi), potem wdrażać nową migrację. Zweryfikowane Flyway API (10.20.1) na kopii `flyway_schema_history`. Uwaga: `backend/app/target/classes/db/migration` może zawierać NIEAKTUALNE pliki z wcześniejszych buildów innych gałęzi (maven nie czyści) — `mvn verify` bez `clean` może więc testować łańcuch z cudzym V092; wariant "czysty develop" sprawdzaj osobno (Flyway API na `filesystem:` z kopią katalogu migracji).
