---
name: project-tura54-epic32
description: Tura 54 (2026-10-28) - FE-131/134, BE-155/158 done, BE-159 proposal counted as open, counters 349/390, next free numbers
metadata:
  type: project
---

Tura 54: FE-131, FE-134, BE-155, BE-158 -> done; BE-159 (propozycja, numerowana) liczona jako ⬜ w BE i EPIC-32. Liczniki: DB 82/87, BE 145/163, FE 122/140, RAZEM 349/390 (⬜ 34). Nastepne wolne: BE-160, FE-137, DB-088. FE-121 parasol ⬜ (brakuje FE-132, FE-133).

**Why:** numerowana propozycja wchodzi do licznikow (inaczej niz propozycje bez numeru). Stare wiersze RAZEM w indeksach TASKS-BACKEND/FRONTEND byly nieaktualne (157/134) - naprawione z grepa.

**How to apply:** skrypt liczacy po `### ID` widzi BE 145/11/3 i FE 122/13/1 - dodaj 4 BE-T00x / 4 FE-T00x jako ⬜. W naglowkach zalezych ticketow dopisuj ✅ tylko przy "Zalezy od". Zob. [[project-progress-state]].
