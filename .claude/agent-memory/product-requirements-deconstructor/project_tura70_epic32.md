---
name: project-tura70-epic32
description: Tura 70 (2026-11-06) - BE-165/166/168 zamkniete, nowe BE-204..206, liczniki 386/78/7 z 471
metadata:
  type: project
---

BE-165, BE-166, BE-168 ✅ (gałąź fix/agent-status-events-and-social-join, CR 4/5). Nowe ⬜: BE-204 (Redis session:agent:* po dialerze), BE-205 (opcjonalny @TransactionalEventListener), BE-206 (test e2e dialer->Rabbit->WS). Liczniki: DB 85/7/3=95, BE 156/51/3=210, FE 145/20/1=166, RAZEM 386/78/7=471. Następne wolne: BE-207, FE-163, DB-096.

**Why:** backlog EPIC-32 (epik zamknięty w turze 61) zmienia tylko liczniki.
**How to apply:** grep Status w TASKS-*.md daje BE 155 ✅ (BE-067 "[x]" i placeholdery BE-T/FE-T zaniżają grep) - liczyć deltą od PROGRESS. Nowe tickety wstawione przed BE-169 w TASKS-BACKEND.md. Zob. [[project-tura69-epic32]].
