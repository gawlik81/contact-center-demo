---
name: tura80-epic33
description: Tura 80 (2026-11-19) EPIC-33 - BE-177/178/236 i FE-171 zamkniete, nowe BE-244..248 i FE-172, liczniki 430/91/7=528
metadata:
  type: project
---

Tura 80: BE-177, BE-178 (P0), BE-236, FE-171 zamkniete; nowe BE-244..BE-246 (follow-upy BE-178), BE-247 (S5, decyzja wlasciciela, ewent. DB-101), BE-248 (flaky test dialera), FE-172. Liczniki: DB 90/100, BE 187/252, FE 153/176, RAZEM 430 / 91 / 7 = 528. EPIC-33: DB 2/5, BE 13/46, FE 3/5. Nastepne wolne BE-249, FE-173, DB-101 (V152 wolna).

**Why:** bramka ">1 replika" zamknieta, przed prod zostaja BE-233 i BE-228 (Should). FE-171 zamkniety z jawnym odstepstwem (kryterium 3 -> FE-172).

**How to apply:** PROGRESS.md ma wiele miejsc do aktualizacji (naglowek l.4, tabela, "Liczniki przeliczone", wiersz EPIC-33, "Postep tury N" w sekcji EPIC-32 i EPIC-33, tabela ticketow EPIC-33, "Stan fazy 0", "Nastepne kroki" pkt 6). Weryfikacja liczb: porownanie statusow HEAD vs working tree skryptem (regex po `### ID` + `**Status:**`), bo wykonawca moze juz miec niezacommitowane znaczniki w TASKS. Zob. [[project_tura74_dopisek3]].
