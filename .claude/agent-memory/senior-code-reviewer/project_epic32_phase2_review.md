---
name: epic32-phase2-review
description: EPIC-32 phase 2 (FE-119/120/126) review 2026-10-23, 4/5; open items to re-check
metadata:
  type: project
---

Review in CR-FRONTEND.md (2026-10-23). 4/5. Open: add-break defaults go stale (canSubmit uses statusChanges signal, validator not re-run -> past start submitted); confirm-dialog (shared, 9 consumers) has no spec; KPI strip @1366 de unmeasured; break timer is client-side only (resets on reload); ListboxNavDirective option ids use caller prefix (dup risk); 3 skill-dropdown forms lack mousedown preventDefault. Confirm-dialog Esc->onCancel change verified safe for all consumers.
**How to apply:** re-check these when the next EPIC-32 phase touches add-break-modal, confirm-dialog or top-navbar. Use `npm test` (ng test), not bare `npx vitest`.
