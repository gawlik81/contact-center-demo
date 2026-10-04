---
name: feedback_seed_planner_and_catalog_pitfalls_2026_10
description: Pułapki z DB-067 (2026-10-04): LATERAL bez korelacji liczy random() raz, brak max(uuid), docker exec -i zjada stdin pętli while, EXPLAIN na małych partycjach jest niedeterministyczny, dwa indeksy z tym samym prefiksem dostają nazwy *_idx / *_idx1 na partycjach, typ char w konkatenacji
metadata:
  type: feedback
---

1. **`LATERAL (SELECT random() ...)` bez odwołania do zewnętrznej kolumny = InitPlan** — wartość liczona RAZ dla całego zapytania (wszystkie wiersze z tą samą datą, `is_orphan` identyczne). Do seedowania losowych danych: najpierw `CREATE TEMP TABLE src AS SELECT g, random() ... FROM generate_series(...)` (volatile w liście SELECT liczy się per wiersz), potem INSERT z JOIN. Zweryfikować `min/max` i `count(*) FILTER` po seedzie, zanim pójdzie na nim pomiar.
2. **PostgreSQL nie ma `max(uuid)` / `min(uuid)`** (`function max(uuid) does not exist`). Maksimum: `SELECT ... ORDER BY id DESC LIMIT 1`. W keyset-backfillu: `SELECT s.id FROM (SELECT id ... ORDER BY id LIMIT n) s ORDER BY s.id DESC LIMIT 1`.
3. **`docker exec -i` w pętli `while read ... done < plik` zjada stdin** — pętla kończy się po pierwszej iteracji. Bez `-i`, SQL przez `-c`.
4. **EXPLAIN na partycjach z 0–1 wierszem jest niedeterministyczny** (planner wybiera między równorzędnymi indeksami, np. `(tenant_id, message_at)` zamiast unikalności, `contact_id` zamiast częściowego indeksu). W testach Java NIE asertować nazwy indeksu w planie; zamiast tego sprawdzać, że każdy indeks rodzica ma potomka na każdej partycji (`pg_inherits`). Wybór planu mierzyć na scratch przy ≥500 tys. wierszy.
5. **Auto-nazwy indeksów na partycjach przy kolizji prefiksu**: pierwszy indeks dostaje `..._tenant_id_message_at_idx`, drugi `..._idx1`. Która nazwa odpowiada której definicji, zależy od kolejności tworzenia — nie wnioskować z samej nazwy partycji.
6. **`ERROR: operator is not unique: text || "char"`** — `relkind` to `"char"`; rzutować `relkind::text`. W funkcji plpgsql `BOOLEAN` przypisany wynikiem `COUNT(*)` daje `operator does not exist: boolean <> integer` — typuj zmienną jako INT.
7. **Porównanie katalogu przed/po migracji (dowód idempotencji)**: `md5(string_agg(relname||':'||relkind::text||':'||relfilenode::text ...))` z `pg_class`, `md5(string_agg(proname||':'||md5(prosrc)))` z `pg_proc`. Po ponownym uruchomieniu migracji hashe muszą być identyczne.

**Why:** każda z tych pułapek kosztowała przebieg (seed dawał jedną datę; migracja padła na `max(uuid)`; pętla po 1 iteracji). **How to apply:** przy każdym seedzie scratch, pomiarze EXPLAIN i pisaniu SQL-a dla PostgreSQL 16 w tym repo.

Powiązane: [[project_db067_email_message_partitioning]], [[feedback_scratch_and_catalog_gotchas]].
