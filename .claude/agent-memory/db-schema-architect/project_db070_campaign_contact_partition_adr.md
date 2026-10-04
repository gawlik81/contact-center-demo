---
name: project-db070-campaign-contact-partition-adr
description: DB-070 (2026-10-04) ADR dla dekoracyjnego LIST(campaign_id) na campaign_contact — rekomendacja A (zostawić, bez zmian schematu), czeka na potwierdzenie właściciela; dowody demo, plan dialera, lista poprawek dla DB-077
metadata:
  type: project
---

**Fakt/decyzja:** `campaign_contact` jest `PARTITION BY LIST (campaign_id)` z jedyną partycją `campaign_contact_default` (demo: 37 wierszy, wszystkie w DEFAULT). Żaden kod nie tworzy partycji per kampania (grep `PARTITION OF` w `backend/app/src/main` = 0; jedyny DDL to V009:193). Rekomendacja ADR: **A — zostawić, bez zmian schematu**. Status: **czeka na potwierdzenie właściciela** (ticket DB-070 nadal ⬜; notatka ADR w `TASKS-DATABASE.md` sekcja „Notatka z wykonania (2026-10-04)").

**Why:** zysk z pruningu dziś = 0 (jedna partycja, brak węzła Append); B (zwykła tabela) i C (HASH 16–32) niosą ryzyko na ścieżce gorącej dialera bez pomiaru; próg C ~50 mln wierszy odległy. Dokumentacja (`06-database.md:282`, `ARCHITECTURE.md:503`) twierdziła, że aplikacja tworzy partycje — fałsz.

**Ustalenia, których nie widać w kodzie:**
- Partycja DEFAULT ma GRANT SELECT/INSERT/UPDATE/DELETE dla `app_user` (ACL = ACL rodzica), a `relrowsecurity=f` → po DB-073 (RLS na rodzicu) dostęp wprost do `campaign_contact_default` omija politykę. DB-073 musi REVOKE na potomkach albo RLS na partycji.
- Plan dialera (`fetchNextPendingContact`, `ORDER BY created_at`): indeks `idx_campaign_contact_dialer` jest używalny (potomek `campaign_contact_default_campaign_id_status_next_attempt_at_idx`), ale sort po `created_at` nie jest wspierany przez indeks → sort wszystkich kwalifikujących się wierszy kampanii (przypuszczenie, niezmierzone). Dokumentacja (`06-database.md:292`) mówi `ORDER BY next_attempt_at` — rozjazd.
- `idx_campaign_contact_dialer_tenant` (`WHERE status='PENDING'`) nie kwalifikuje się do zapytania `IN ('PENDING','NO_ANSWER')` — kandydat do audytu (osobny ticket po pomiarze).
- Nazwy indeksów potomnych w EXPLAIN to nazwy auto-generowane (`campaign_contact_default_<kolumny>_idx`), nie `idx_campaign_contact_dialer`.
- Statystyki demo nieanalizowane (`reltuples` rodzica −1, partycji 29; `last_analyze` NULL) — plany szacunkowe.

**Lista poprawek dla DB-077** jest w notatce ADR (§6); najważniejsze: `06-database.md:277, 282–284, 292, 635, 638–639`; `ARCHITECTURE.md:502–504, 1071`; wersje HTML regenerować `node documentation/build-html.js`; V009:186–188 — nie edytować; `DESIGN-data-retention-partitioning.md:14,129` (poza zakresem DB-077) — do decyzji właściciela.

**How to apply:** przy każdym ticketcie dotyczącym `campaign_contact` (DB-073, DB-077, przyszły indeks pod `ORDER BY created_at`) sprawdź najpierw, czy A nadal obowiązuje (skrypt progu: count, suma rozmiaru partycji, liczba partycji > 1 = naruszenie ADR). Jeśli właściciel zatwierdzi B/C — ADR wymaga pomiaru na scratch (ACCESS EXCLUSIVE, pruning, EXPLAIN przy wolumenie) przed implementacją.

Powiązane: [[project_partitioning_candidates_2026_09]], [[feedback_rls_testing]], [[project_contact_center]], [[feedback_scratch_and_catalog_gotchas]]
