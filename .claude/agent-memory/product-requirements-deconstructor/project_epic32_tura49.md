---
name: project-epic32-tura49
description: EPIC-32 po turze 49 - FE-116/FE-118 done, nowy FE-126, liczniki 342/373, faza 1 zamknieta
metadata:
  type: project
---

Tura 49 (2026-10-23): EPIC-32 FE 5/13 (done: FE-114..118; open: FE-119..126). Liczniki: FE 117/0/12/1/130, RAZEM 342/0/24/7/373 (skrypt FE bez FE-T00x: 117 done / 8 todo / 1 N/A). Faza 1 (tokeny) zamknieta; zmiany niezacommitowane na `feature/epic-32-agent-ux-a11y`.

Nowy FE-126 (klawiatura `.skill-dropdown` w admin-user-form/queue-form/user-form) z niespelnionego kryterium (d) FE-116; faza 2 obok FE-119/FE-120; blokuje FE-125. FE-118: weryfikacja pasa KPI w `de` przeniesiona do FE-119 (etykiety KPI na sztywno PL). FE-124 dostal: rgba w email-thread-message/adhoc-email-modal, rozmiary 0.7/0.72/0.71875rem, fallbacki hex.

**Why:** ticket zamykany ze statusem done, gdy niespelnione kryterium trafia do nowego ticketu (wzorzec FE-115 -> FE-116 -> FE-126).
**How to apply:** nastepne kroki: commit, FE-119/120/126, potem /impeccable shape -> FE-121/122. WP-5 nie objal supervisor/admin (MFA), toastow, social-contact, kategorii kalendarza. Poprzedni stan: [[project-epic32-tura48]].
