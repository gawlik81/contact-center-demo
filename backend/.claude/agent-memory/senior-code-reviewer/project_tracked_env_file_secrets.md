---
name: project-tracked-env-file-secrets
description: .env.local-demo is git-tracked with redacted placeholders committed, but working-tree edits sometimes fill in real-looking secrets — check before any commit
metadata:
  type: project
---

`.env.local-demo` (repo root) is tracked in git (committed at `ddeb9dd`, not in `.gitignore`) with `****`-redacted placeholder values for `DB_PASSWORD`, `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `JWT_SECRET`, `APP_ENCRYPTION_SECRET`, `EMAIL_ENCRYPTION_KEY`, `SOCIAL_TOKEN_ENCRYPTION_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`. `docker-compose.local-demo.yml` sets `SPRING_PROFILES_ACTIVE: prod` for this env (so it exercises prod-profile code paths, e.g. `SuperAdminBootstrapRunner`'s fail-fast branch, locally).

**Why this matters:** during the 2026-07-12 review (`rule-refactor` branch), the working-tree diff for this file had REAL-looking generated secrets in place of the placeholders (base64 keys, a Twilio Account SID/Auth Token matching the real format). If committed, this would leak real-looking credentials into git history permanently — worst case a real Twilio account (toll-fraud / takeover risk) if the repo is or becomes public.

**How to apply:** Any time `.env.local-demo` (or any tracked env file with a `.example`-less, checked-in "real" counterpart pattern) appears in a diff being reviewed or about to be committed, diff it against its last-committed version and check whether placeholder values (`****`) were replaced with plausible real secrets. Flag this prominently as a critical finding even when it's unrelated to the feature under review — recommend reverting the file before commit, or splitting into a gitignored real file + a checked-in `.example` with placeholders. Do not assume this is "just local dev convenience" without checking the diff.
