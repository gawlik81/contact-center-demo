---
name: reference_review_logs
description: Where code review findings are stored/appended in this repo, and how past sessions are structured
metadata:
  type: reference
---

Code review findings are appended (never overwritten) to three files at the repo root:
- `CR-BACKEND.md`
- `CR-FRONTEND.md`
- `CR-DATABASE.md`

Each session appends a `## Review: <files> — <YYYY-MM-DD>` section with the fixed structure (Bugs/Critical, Security, Architecture/Pattern Violations, Improvements, Positive Observations, Summary with a star rating). Before writing a new review for an area, grep these files for the module/file name first — there is often a prior review of the same module (e.g. `BE-017` social media OAuth review from 2026-04-16) whose CRITICAL findings may have been fixed since, partially fixed, or silently reintroduced in new code paths. Referencing the prior review by name/date and noting what was fixed vs. what regressed makes findings much more concrete and credible than repeating them from scratch.

See also [[project_social_module_technical_debt]] for a concrete example of this (blocking-HTTP-in-transaction anti-pattern fixed once, reintroduced later in a new adapter).
