---
name: project-tura71-be200
description: Tura 71 (2026-11-06) - BE-200 zamkniete (BusinessValidationException, IAE->500), liczniki 387/77/7 z 471, brak nowych ticketow
metadata:
  type: project
---

BE-200 ✅ 2026-11-06 (gałąź `fix/be-200-business-validation-exception`, niezacommitowane). Liczniki po turze 71: DB 85/7/3=95, BE 157/50/3=210, FE 145/20/1=166, RAZEM 387 ✅ / 77 ⬜ / 7 🚫 = 471. Następne wolne: BE-207, FE-163, DB-096.

**Why:** EPIC-32 jest zamknięty (tura 61), backlog zmienia tylko liczniki; w wierszu EPIC-32 w PROGRESS.md BE 14/29, BE ⬜ 15.
**How to apply:** skrypt liczący Status wg `### XX-NNN` daje BE 206 (bez 4 placeholderów BE-T00x i bez BE-108/109 liczonych osobno) - do wyniku dodaj 4 ⬜ BE i 4 ⬜ FE. BE-141/BE-146 są ✅ (PR#47, 78726d4) - nie cofać; w CR ślady uwag oznaczać `- **[RESOLVED data - opis]**`.
