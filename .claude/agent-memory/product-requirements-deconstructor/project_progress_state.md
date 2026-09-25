---
name: project_progress_state
description: Stan ukończenia DB/BE/FE (2026-09-24, po turze 5) — DB 59/79, BE 124/144, FE 109/112 (292/335); EPIC-01..29 ukończone, EPIC-30 w toku (9/51: BE-124, DB-060, BE-125, DB-079, BE-126, BE-143, DB-061, DB-062, BE-129), BE-144 poza epikiem; lekcje o rekoncyliacji PROGRESS.md, symetrii zależności i o resetach scratchpada między turami
metadata:
  type: project
---

Stan na **2026-09-24 (tura 5)**, przeliczony z pól `**Status:**` w `TASKS-DATABASE.md`/`TASKS-BACKEND.md`/`TASKS-FRONTEND.md` (nie z wierszy PROGRESS.md):
**DB 59/79, BE 124/144, FE 109/112 — RAZEM 292/335 (87%)**. Ukończone: EPIC-01..EPIC-29, DB-055 (porządki indeksów `contact`, V093, poza epikiem) oraz z EPIC-30: BE-124, DB-060 (2026-09-20), BE-125, DB-079 (2026-09-21), BE-126, BE-143 (2026-09-22), DB-061, DB-062, BE-129 (2026-09-24). Nierozpoczęte: 42 tickety **EPIC-30** (DB 20, BE 19, FE 3; patrz `[[project-epic30-plan]]`) + BE-144 (porządkowy, `Epic: brak`, jak DB-055) = 43 ⬜. **Jedyny FE ticket zależny od BE-129 to FE-112** (jedyny wpis w polu `Zależy od` wymieniający BE-129/BE-129 ✅ w całym repo).
Najwyższe numery: DB-079 / BE-144 / FE-112 (DB-061/DB-062 nie podnoszą najwyższy numer ticketu DB, bo są < DB-079). Najwyższa migracja w repo: **V096** (DB-062; V094/V095/V096 NIEZASTOSOWANE na żywej bazie — Flyway zastosuje wszystkie trzy przy starcie po przebudowie obrazu); następna wolna V097 (sprawdzić refy + `flyway_schema_history`).
## Reset scratchpada między turami (2026-09-24, ważna lekcja)
Skrypty `verify_all.py`/`neg_test.py`/`check_epic30.py` zapisane w scratchpadzie NIE przetrwały do kolejnej sesji, mimo identycznej ścieżki katalogu (ta sama nazwa UUID w kolejnych wiadomościach koordynatora nie gwarantuje persystencji plików — scratchpad jest opisany jako "session-specific"). Przy każdej nowej sesji/turze: (1) sprawdź `ls` scratchpada PRZED założeniem, że skrypty tam są; (2) jeśli ich brak, odtwórz `verify_all.py` i `neg_test.py` z opisu w tym pliku pamięci (logika: parsowanie nagłówków `### XX-NNN`, pola Zależy od/Blokuje/Status/Czeka na BE/Epic, symetria A-błąd/B-warn z rozróżnieniem tranzytywne/bezpośrednie, cykle Kahna, liczniki PROGRESS.md, tabela EPIC-30 + "Bez epiku" + "Łącznie", znaczniki ✅); (3) `check_epic30.py` (oryginalny skrypt koordynatora) nie trzeba odtwarzać — `verify_all.py` go w pełni zastępuje (szerszy zakres: całe pliki, nie tylko EPIC-30). **Próg "HARDCODED MIGRATION" musi być DYNAMICZNY**, nie stałą liczbą: liczony jako `max(V-numer plików w backend/src/main/resources/db/migration)`, bo migracje wcześniej "jeszcze niezarezerwowane" (V095, V096) z czasem stają się prawdziwymi, ukończonymi migracjami i ich cytowanie w treści własnego ticketu przestaje być błędem.

## Zapomniane znaczniki (powtarzający się błąd, wart uwagi)
Przy każdej turze, w której ticket zostaje ukończony, ISTNIEJE WIĘCEJ NIŻ JEDNO miejsce z jego ID w grafach zależności (moduł-preambuła w TASKS-BACKEND.md/TASKS-DATABASE.md ORAZ osobno w DESIGN §4) — łatwo zaktualizować tylko jedno z nich. W turze 5 znaleziono i naprawiono zapomniany `DB-061`/`DB-062 ✅` w preambule grafu BE (TASKS-BACKEND.md linia "Grupa 1: ... DB-060 ✅, DB-061, DB-062, DB-079 ✅..."), który powinien był dostać znacznik w turze 4, ale tura 4 zaktualizowała tylko analogiczną linię w TASKS-DATABASE.md. **Przy każdej turze: `grep` PEŁNEGO ID ticketu (bez ✅) we WSZYSTKICH plikach (TASKS-BACKEND.md, TASKS-DATABASE.md, TASKS-FRONTEND.md, DESIGN), nie tylko w pliku "właściciela" ticketu** — każde wystąpienie w treści grafu/legendy to potencjalne miejsce do zaznaczenia.


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
- EPIC-30 (Retencja wiadomości, harmonogramy, partycjonowanie tabel wiadomości; 51 ticketów po dopisaniu BE-143 z code review BE-125) — zaplanowany 2026-09-20, ✅ BE-124, DB-060, BE-125, DB-079, BE-126, BE-143, DB-061, DB-062, BE-129; reszta ⬜ (Must nierozpoczęte: DB-059, BE-127).
