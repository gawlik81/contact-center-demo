---
name: project-tura47-epic32
description: EPIC-32 UX i dostępność stanowiska agenta (FE-114..FE-125), założony 2026-10-23, tylko FE; liczniki 338/372
metadata:
  type: project
---

EPIC-32 założony w turze 47 (2026-10-23): 12 ticketów FE (FE-114 ✅ wstecznie, commit `322db0c1` na `fix/agent-incoming-call-hardening`; FE-115..FE-125 ⬜). Liczniki: FE 113/0/15/1/129, RAZEM 338/0/27/7/372. Skrypt FE (bez FE-T00x) daje 113/11/1.

**Why:** krytyka Impeccable 17/40 i audyt 12/20; cel zamknięcia audyt >=16/20, krytyka >=28/40.
**How to apply:** kolejność faz: FE-115+FE-117 -> FE-116, FE-118 -> FE-119, FE-120 -> FE-121 (po /impeccable shape) -> FE-122 -> FE-123 -> FE-124 -> FE-125. Otwarte: D1 KPI (FE-119), pytanie o semantykę AFTER_CONTACT przy wielu kontaktach (FE-122, bez zakładania BE). Mapuje [[project-tura43-epic30-closure]] jako poprzedni stan.
