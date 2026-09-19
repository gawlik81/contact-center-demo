---
name: project_db055_duplicate_contact_indexes
description: DB-055/V093 (2026-09-19) — usunięcie 3 zduplikowanych indeksów z partycjonowanej tabeli contact; decyzje co zostaje, wynik audytu innych tabel partycjonowanych, metoda dowodowa EXPLAIN
metadata:
  type: project
---

V093__drop_duplicate_contact_indexes.sql (DB-055, working tree, NIE zastosowana na żywej bazie — deploy robi właściciel) usuwa z rodzica `contact` (propaguje na 11 partycji): `idx_contact_tenant_agent_date`, `idx_contact_tenant_disposition_date`, `idx_contact_tenant_channel_date` (wszystkie V011). Zostają nazwy V007: `idx_contact_agent_history`, `idx_contact_disposition`, `idx_contact_channel_date` (ASC). `contact`: 19 → 16 indeksów na rodzicu i na każdej partycji.

**Why (wybór V007):** nazwy V007 są w `documentation/tech/06-database.md`(+html), w tekście DB-0xx w TASKS-DATABASE.md i w nagłówku V089; nazw V011 nie referencuje nic. Zero referencji w Javie/pg_proc/pg_views. Pary 1-2 identyczne (pg_index: cols/indoption/opclass/collation/pred); para 3 różni się tylko `indoption` 0 vs 3 (DESC). Dowód dla pary 3: brak `Sort` przy pojedynczym indeksie w obu kierunkach (Merge Append + Index Scan [Backward]), te same bufory/czasy (~1% różnicy); kontrola bez indeksów pokazuje Sort (13955 buf. vs 55). Jedyna realna różnica: `ORDER BY channel, started_at DESC` (channel bez równości) — ASC potrzebuje Incremental Sort; kod tego nie używa.

**How to apply:**
- Nowe indeksy na `contact` (i innych partycjonowanych rodzicach) sprawdzaj przed dodaniem porównaniem z `pg_index` (kolumny/opcje/opclass/predykat) — indeks na rodzicu kosztuje × liczba partycji przy każdym INSERT/UPDATE. Rodzice audytowani 2026-09-19 (`contact_event`, `contact_transcription`, `contact_ai_summary`, `audit_log`, `plugin_invocation_log`, `campaign_contact`): zero duplikatów i zero redundancji prefiksowej.
- `create_contact_partition()` nie tworzy indeksów jawnie (tylko `PARTITION OF`) — usunięcie z rodzica wystarcza; żadna funkcja/job nie odwołuje się do indeksów `contact` po nazwie.
- `DROP INDEX` na indeksie partycjonowanym: ACCESS EXCLUSIVE na rodzicu + KAŻDEJ partycji (12 tabel, zweryfikowane pg_locks) do końca transakcji; `DROP INDEX CONCURRENTLY` niemożliwy ("cannot drop partitioned index ... concurrently"). Migracja ma `SET LOCAL lock_timeout='10s'` + guard DO $$ (survivors muszą istnieć) — pierwszy `lock_timeout` w tym repo.
- Numeracja/pułapka wdrożeniowa V092 vs V093: zob. [[feedback_migration_numbering_check]].

**Metoda dowodowa EXPLAIN (do reużycia, dane syntetyczne w bazie scratch — 500k wierszy, 60 tenantów, skew `power(random(),2)`; por. też uwaga o dziesiątkach tenantów w [[contact_center_project]]):** stany A (oba) / B / C (po jednym) / iso (+ DROP `idx_contact_tenant_started_at`, żeby nic innego nie dało kolejności) / kontrola (bez wszystkich) — każdy w `BEGIN; SET LOCAL enable_seqscan=off; DROP INDEX ...; EXPLAIN (ANALYZE,BUFFERS,COSTS OFF,TIMING OFF) ...; ROLLBACK;`. PUŁAPKI parsowania planu: linia `Sort Key:` pod `Merge Append` to NIE węzeł Sort (rozpoznawaj węzły po `(actual`), a `Incremental Sort` to osobny węzeł. W partycjach z domyślną partycją planner używa `Merge Append` (nie ordered Append) — to wystarcza, bez Sort. Pod `SET ROLE app_user` + GUC `app.current_tenant_id` plany mają `One-Time Filter` z RLS, indeksy nadal używane. Wynik `idx_scan` na żywej bazie NIE rozstrzyga między identycznymi indeksami (planner wybiera arbitralnie).
