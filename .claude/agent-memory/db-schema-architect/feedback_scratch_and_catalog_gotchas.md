---
name: feedback-scratch-and-catalog-gotchas
description: Pułapki techniczne przy eksperymentach na bazie scratch w cc-postgres i przy zapytaniach do katalogu pg_index
metadata:
  type: feedback
---

1. **`VACUUM (ANALYZE)` na większej tabeli w kontenerze `cc-postgres` kończy się `could not resize shared memory segment ... No space left on device`** (mały /dev/shm kontenera, nie brak dysku). Przed VACUUM/CREATE INDEX na scratch ustaw `SET max_parallel_maintenance_workers = 0; SET max_parallel_workers_per_gather = 0;`.
2. **`pg_index.indkey::int2[]` ma dolną granicę 0**, więc wycinek `[1:n]` jest przesunięty o jeden i zapytanie "FK bez indeksu" zwraca fałszywe trafienia (np. wskazało brak indeksu na `contact_event.tenant_id`, który istnieje). Używaj `string_to_array(indkey::text,' ')::int2[]` (1-bazowa).
3. **Rozmiary dev są zanieczyszczone poprzednimi symulacjami z ROLLBACK** (DB-053/055): puste tabele mają indeksy 1-1.3 MB (`campaign_contact_archive`, `retention_purge_log`), a `pg_class.reltuples` partycjonowanego rodzica `contact` pokazuje 100556 przy 426 realnych wierszach (in-place update statystyk nie jest cofany przez ROLLBACK). Do liczności używaj `count(*)`, nie `reltuples`.
5. **`pg_total_relation_size(rodzic)` na tabeli partycjonowanej zwraca 0** (rodzic nie ma storage) — rozmiar liczy się sumą po `pg_inherits` (`sum(pg_total_relation_size(inhrelid))`). Dotyczy `campaign_contact` (2026-10-04). Na read-only demo nie wykonuj `ANALYZE` (zapisuje statystyki) — plany są wtedy szacunkowe (`reltuples` rodzica −1, `last_analyze` NULL).
4. Eksperymenty: `pg_dump -s contact_center | psql scratch` działa bez błędów (107 tabel), `DROP DATABASE` na koniec; pomiar WAL przez `SELECT pg_current_wal_lsn() AS l0 \gset` i `pg_wal_lsn_diff`.

**Why:** każda z tych rzeczy kosztowała czas lub dała mylący wynik w sesji 2026-09-20. **How to apply:** przy każdym pomiarze/eksperymencie na scratch i przy audytach indeksów/FK.
