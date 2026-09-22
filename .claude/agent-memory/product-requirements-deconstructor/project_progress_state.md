---
name: project_progress_state
description: Stan ukończenia DB/BE/FE (2026-09-22, po turze 3) — DB 57/79, BE 123/144, FE 109/112 (289/335); EPIC-01..29 ukończone, EPIC-30 w toku (6/51: BE-124, DB-060, BE-125, DB-079, BE-126, BE-143), BE-144 poza epikiem; lekcje o rekoncyliacji PROGRESS.md i symetrii zależności
metadata:
  type: project
---

Stan na **2026-09-21**, przeliczony z pól `**Status:**` w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` (nie z wierszy PROGRESS.md):
**DB 57/79, BE 123/144, FE 109/112 — RAZEM 289/335 (86%)**. Ukończone: EPIC-01..EPIC-29, DB-055 (porządki indeksów `contact`, V093, poza epikiem) oraz z EPIC-30: BE-124, DB-060 (2026-09-20), BE-125, DB-079 (2026-09-21), BE-126, BE-143 (2026-09-22). Nierozpoczęte: 45 ticketów **EPIC-30** (DB 22, BE 20, FE 3; patrz `[[project-epic30-plan]]`) + BE-144 (porządkowy, `Epic: brak`, jak DB-055) = 46 ⬜.
Najwyższe numery: DB-079 / BE-144 / FE-112; następny epik zaczyna od DB-080 / BE-145 / FE-113 (BE-143 = EPIC-30, BE-144 = poza epikiem). Najwyższa migracja w repo: **V094** (DB-079, NIEZASTOSOWANA na żywej bazie — Flyway zastosuje przy starcie po przebudowie obrazu); następna wolna V095 (sprawdzić refy + `flyway_schema_history`).

**Why:** Ten plik to migawka — sprawdzaj bezpośrednio pliki TASKS-*.md przed poleganiem na liczbach.

**How to apply:** Przed dekonstrukcją nowego epiku zweryfikuj najwyższy numer ticketu (`grep -oE "^### (DB|BE|FE)-[0-9]+"`) i migracji (katalog + `flyway_schema_history` + `git ls-tree` gałęzi). Liczby w Podsumowaniu: tabela „Nie rozpoczęte wg EPIC" ma wiersz EPIC-30, wiersz „Bez epiku (porządkowe)" i „Łącznie" (= wszystkie ⬜; skrypt sprawdza sumę); opis „DB x + y, BE …" = spoza EPIC-30 + w EPIC-30 (BE 120 + 24, bo BE-144 jest poza epikiem). BE liczone po unikalnych nagłówkach numerycznych (`BE-001b`, `BE-030b` osobno; BE-108/109 nie mają nagłówków; ticketów `BE-T001..004`/`FE-T001..004` NIE liczymy — status „🔲 Do zrobienia", placeholdery w zależnościach), BE-067 ma status `[x] Zrobione` (= ukończony).

## Lekcje o rekoncyliacji PROGRESS.md (powtórzone błędy)
- Przy dodawaniu epiku i przy zamykaniu ticketów przeliczaj „Podsumowanie" od zera z pól Status w TASKS-*.md — nigdy nie dopisuj delty do liczby w PROGRESS.md (błąd wystąpił przy EPIC-27, EPIC-28, EPIC-29). Pomaga skrypt `verify_all.py` z scratchpadu sesji (liczniki, nagłówek „Stan:", tabela EPIC-30, wiersze vs Status) — trzeba go odtworzyć, scratchpad jest jednorazowy.
- Wiersze PROGRESS.md potrafią być nieaktualne względem TASKS: EPIC-29 był ⬜ mimo ✅ w TASKS; BE-124/DB-060/BE-125/DB-079 zamykane przez innych agentów nie miały aktualizacji w PROGRESS.md — sprawdzać przy każdej zmianie planu.
- PROGRESS.md nadal nie ma wierszy dla 17 ticketów: EPIC-27 (DB-040/041, BE-092..096, FE-090..096) i addendów EPIC-28 (BE-110, FE-101/102) — liczby w Podsumowaniu je uwzględniają, tabele nie (BE-001b/BE-030b są tylko w starszych sekcjach). Świadomie NIE naprawiane (poza zakresem zleceń).
- 103 ukończone tickety ze starszych epików mają niezaznaczone checkboxy AC (konwencja starych ticketów) — to NIE jest rozjazd statusu; status = pole `**Status:**`.
- Pokrycie weryfikacji kod-po-tickecie z 2026-08-09 było nierówne: EPIC-12..16 nie dostał pełnego przeglądu (subagent padł na limicie API) — zalecane osobne `/update-progress`.
- BE-108 (EPIC-28) nie ma własnego nagłówka `### BE-108` (treść jako addendum w BE-106).
- Tickety BE-T002/BE-T003 (`🔲 Do zrobienia`) mają pasujące pliki testowe (`AdminUserServiceImplTest`, `EmailSendServiceTest`) — status prawdopodobnie nieaktualny; NIEZWERYFIKOWANE względem AC, niezmieniane.

## Zależności po 2026-09-21
Pola `Zależy od`/`Blokuje` są symetryczne w całych trzech plikach (FE: `Czeka na BE` liczy się jako zależność) poza **31 wpisami `Blokuje` bez odpowiednika w `Zależy od`** (25 tranzytywnych; 6 bezpośrednich: BE-001b→BE-028, BE-002→BE-003/BE-015, BE-015→BE-016, BE-027→BE-029, DB-044→DB-045) — świadomie niezmienione do decyzji użytkownika. Zakresy `DB-019 (DB-001 do DB-017)` i `DB-005 Blokuje: brak (BE-006 i dalej)` pominięte jako niejednoznaczne. Tickety EPIC-30 mają znaczniki ✅ przy spełnionych zależnościach (`DB-060 ✅`).

## Historia epików (skrót)
- EPIC-25 (Kampanie — refaktor i transfer), EPIC-26 (AI Summary), EPIC-27 (własne dyspozycje), EPIC-28 (Per-Tenant Plugin System) — ✅.
- EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów, DB-046..054, BE-111..119, FE-103..109) — ✅ (notatki implementacyjne 2026-08-09..2026-08-13), patrz `[[project_epic29_plan]]`.
- EPIC-30 (Retencja wiadomości, harmonogramy, partycjonowanie tabel wiadomości; 51 ticketów po dopisaniu BE-143 z code review BE-125) — zaplanowany 2026-09-20, ✅ BE-124, DB-060, BE-125, DB-079, BE-126, BE-143; reszta ⬜.
