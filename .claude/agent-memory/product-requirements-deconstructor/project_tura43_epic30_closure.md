---
name: tura43-epic30-closure
description: Tura 43 (2026-10-22) — EPIC-30 zamknięty warunkowo, DB-087 (V142), liczniki 337/352, decyzje właściciela
metadata:
  type: project
---

EPIC-30 = "ZAMKNIĘTY WARUNKOWO" (nie "w pełni zakończony"): DB-069/BE-137 zostają ⬜ jako "odłożone z wyzwalaczem" (≥50–100 mln wierszy archiwum / WAL-lag od purge / DELETE ogona >30 s); WP-4 opcjonalne; BE-144 poza epikiem; EPIC-31 wstrzymany; rekomendacja handlera HttpMessageNotReadableException ODRZUCONA.
DB-087 ✅ (V142, commit 9c800c81) — V142 NIEZASTOSOWANA na żywej bazie, bez code review i bez 06-database.md. Liczniki 337/352, ⬜ 8, N/A 7.

**Why:** właściciel chciał uczciwego statusu bez "zrealizowane" dla odłożonych ticketów.
**How to apply:** w kolejnych turach liczyć DB-069/BE-137 osobno; skrypt po `**Epic:** EPIC-30` = 61 vs tabela 60 (różnica +1, nie +2 jak w starszych notach); status pola brać z pierwszego `**Status:**` w nagłówku `###` (późniejsze linie w notatkach fałszują liczenie). Zob. [[feedback_verify_live_db_state]].
