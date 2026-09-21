---
name: project_progress_state
description: Stan ukończenia DB/BE/FE (2026-09-20) — DB 56/79, BE 120/142, FE 109/112 (285/333); EPIC-01..29 ukończone, EPIC-30 w toku (BE-124, DB-060 ✅); lekcje o rekoncyliacji PROGRESS.md
metadata:
  type: project
---

Stan na **2026-09-20**, przeliczony z pól `**Status:**` w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` (nie z wierszy PROGRESS.md):
**DB 56/79, BE 120/142, FE 109/112 — RAZEM 285/333 (86%)**. Ukończone: EPIC-01..EPIC-29, DB-055 (porządki indeksów `contact`, V093, poza epikiem) oraz BE-124 i DB-060 z EPIC-30. Nierozpoczęte: 48 ticketów **EPIC-30** (patrz `[[project-epic30-plan]]`).
Najwyższe numery: DB-079 / BE-142 / FE-112; następny epik zaczyna od DB-080 / BE-143 / FE-113. Najwyższa migracja na develop: V093 (V092 = `feature-socialmedia`).

**Why:** Ten plik to migawka — sprawdzaj bezpośrednio pliki TASKS-*.md przed poleganiem na liczbach.

**How to apply:** Przed dekonstrukcją nowego epiku zweryfikuj najwyższy numer ticketu (`grep -oE "^### (DB|BE|FE)-[0-9]+"`) i migracji (katalog + `flyway_schema_history` + `git ls-tree` gałęzi). Liczby w Podsumowaniu: BE liczone po unikalnych nagłówkach (`BE-001b`, `BE-030b` osobno; BE-108/109 nie mają nagłówków), BE-067 ma status `[x] Zrobione` (= ukończony).

## Lekcje o rekoncyliacji PROGRESS.md (powtórzone błędy)
- Przy dodawaniu epiku i przy zamykaniu ticketów przeliczaj „Podsumowanie" od zera z pól Status w TASKS-*.md — nigdy nie dopisuj delty do liczby w PROGRESS.md (błąd wystąpił przy EPIC-27, EPIC-28, EPIC-29).
- Wiersze PROGRESS.md potrafią być nieaktualne względem TASKS: EPIC-29 był ⬜ (25 wierszy) mimo ✅ w TASKS z notatkami 2026-08-09..13 — przełączone 2026-09-20; wiersze DB-046..054 tkwiły pod tabelą „Dodatkowe migracje" (przeniesione). BE-124/DB-060 zamknięte przez innych agentów nie miały aktualizacji w PROGRESS.md — sprawdzać przy każdej zmianie planu.
- PROGRESS.md nadal nie ma wierszy dla EPIC-27 (DB-040/041, BE-092..096, FE-090..096) i addendów EPIC-28 (BE-110, FE-101/102) — liczby w Podsumowaniu je uwzględniają, tabele nie.
- Pokrycie weryfikacji kod-po-tickecie z 2026-08-09 było nierówne: EPIC-12..16 nie dostał pełnego przeglądu (subagent padł na limicie API) — zalecane osobne `/update-progress`.
- BE-108 (EPIC-28) nie ma własnego nagłówka `### BE-108` (treść jako addendum w BE-106).

## Historia epików (skrót)
- EPIC-25 (Kampanie — refaktor i transfer), EPIC-26 (AI Summary), EPIC-27 (własne dyspozycje), EPIC-28 (Per-Tenant Plugin System) — ✅.
- EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów, DB-046..054, BE-111..119, FE-103..109) — ✅ (notatki implementacyjne 2026-08-09..2026-08-13), patrz `[[project_epic29_plan]]`.
- EPIC-30 (Retencja wiadomości, harmonogramy, partycjonowanie tabel wiadomości; 50 ticketów) — zaplanowany 2026-09-20, ✅ BE-124 i DB-060, reszta ⬜.
