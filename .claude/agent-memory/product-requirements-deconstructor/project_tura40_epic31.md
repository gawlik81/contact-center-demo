---
name: project-tura40-epic31
description: Tura 40 (2026-10-17) - FE-113 done, DB-085/DB-086 added, EPIC-31 split from EPIC-30, counters 333/350
metadata:
  type: project
---

Tura 40: FE-113 done (81c5561e, WP-4 browser check left to owner), code review V136-V139 4/5 -> new tickets DB-085 (ended_at trigger on INSERT) and DB-086 (DELETE USING archive), both before DB-083. EPIC-31 "Hardening RLS (cc_app)" = BE-139, BE-148, DB-083, BE-149, BE-150 (5, all open). Counters 333/350, open 10, N/A 7. Next free: DB-087/BE-152 (BE-152 only a recommendation, unconfirmed), migrations V140+.

**Why:** owner split RLS hardening out of EPIC-30. **How to apply:** count EPIC-30 by PROGRESS convention (58); the script on `**Epic:**` fields gives 60 (constant +2 offset since before tura 40) - report the delta, not the absolute. Script: counts by `### XX-NNN` + first `**Status:**` per ticket.
