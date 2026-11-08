---
name: tura74-dopisek3
description: Tura 74 dopisek 3 (2026-11-08) — weryfikacja local-demo po PR #62; korekta diagnozy "agentów-duchów" (konta ADMIN/SUPERVISOR); nowy BE-221; liczniki 398/89/7=494
metadata:
  type: project
---

Weryfikacja local-demo 2026-11-08 (PR #62 w develop `3b030213`, tylko odczyty, niezacommitowane zmiany docs): potwierdzone BE-211 (brak INFO pollu), BE-212 (brak `[ETL][ALERT]`), start sweepera BE-210, V146–V148. "Duchy" z 2026-11-07 = konta ADMIN/SUPERVISOR AVAILABLE bez `queue_agent` -> nowy ⬜ BE-221 (filtr roli AGENT w `findAvailableAgents`). Odhaczony punkt local-demo w BE-212; BE-210 (auto-OFFLINE w przeglądarce) i BE-213 (drenowanie backlogu) zostają otwarte z adnotacjami. Liczniki: DB 87/8/3=98, BE 164/58/3=225, FE 147/23/1=171, RAZEM 398/89/7=494; następne wolne BE-222, FE-168, DB-099. Poprzednik: [[tura74-dopisek2]].

**Why:** diagnoza z tury 73 była błędna co do tożsamości kont; skutek funkcjonalny zerowy.
**How to apply:** REKONCYLIACJA 2026-11-08 zamknęła dryf: liczba nagłówków `### BE-` (z BE-T001..T004, BE-067 `[x]` jako ✅) = 225 = PROGRESS; BE-108/BE-109 (bez nagłówków) NIE są liczone — wcześniejsza notka "162 + 2" była błędna. Tabela "Przegląd wg EPIC" w TASKS-BACKEND: sumy wierszy 164/0/58/3=225, wiersz RAZEM bywa nieaktualny — zawsze sumuj wiersze. Konwencja epików: BE-212..216 (Epic: EPIC-10) liczone w EPIC-32 (21/23/44); BE-145/BE-146 (pole Epic: brak, w tekście wzmianka o EPIC-29/30) = "Bez epiku". Indeksy EPIC-32 (BE i FE) sprawdzaj skryptem status indeksu vs sekcji (BE-155/BE-158 ✅ świadomie poza indeksem). Skrypty edycji nie są idempotentne.
