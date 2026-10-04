---
name: reference_tasks_backend_count_quirks
description: TASKS-BACKEND.md ma nietypowe/duplikowane nagłówki ticketów i stare statusy — naiwny grep "### BE-" lub "**Status:**" nie da liczby zgodnej z oficjalnym licznikiem w PROGRESS.md.
metadata:
  type: reference
---

Zweryfikowane 2026-10-01 na gałęzi `feature/epic-30-social-message-partitioning`: `grep -c "^### BE-" TASKS-BACKEND.md` daje **149**, a `PROGRESS.md` (metodologia z tury 9, "przeliczone od zera") liczy BE jako **145** ticketów łącznie. Różnica pochodzi z:
- Dwóch zduplikowanych nagłówków sekcji: `### BE-001` i `### BE-030` pojawiają się po dwa razy w pliku (prawdopodobnie stare/historyczne podsekcje, nie nowe tickety).
- 4 ticketów ze statusem `🔲 Do zrobienia` (stary symbol, sprzed konwencji ⬜/✅/🚫) — prawdopodobnie bardzo wczesne/historyczne tickety poza główną numeracją BE-120...BE-145.
- `BE-067` ma nietypowy zapis statusu `[x] Zrobione` (nie `✅`) — PROGRESS.md explicite liczy go jako ukończony (odnotowane w narracji "Podsumowanie" pliku).

**Why:** `TASKS-DATABASE.md` nie ma tego problemu — `grep "^\*\*Status:\*\*"` tam daje dokładnie 79, zgodnie z `### DB-` (79) i z liczbami w PROGRESS.md. Problem jest specyficzny dla `TASKS-BACKEND.md`.

**How to apply:** przy aktualizacji liczników PROGRESS.md dla warstwy BE **nie licz od zera przez grep** — stosuj deltę względem ostatniego potwierdzonego stanu w PROGRESS.md (tak jak robią to tury 10/11/12 w tym pliku), albo jeśli robisz pełne przeliczenie, najpierw ręcznie zidentyfikuj i wyklucz zduplikowane nagłówki `### BE-001`/`### BE-030` oraz rozstrzygnij, czy 4 tickety `🔲 Do zrobienia` wchodzą do głównej numeracji 145 czy są poza nią — inaczej wynik będzie fałszywie wyższy o ~4-5.
