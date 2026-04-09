---
name: verify
description: Runs full lint + format check + tests for both frontend and backend. Use before marking any implementation task as done.
---

Run the following verification steps for this contact-center-demo project. Report each result separately.

## Frontend (run from `frontend/`)

```bash
cd frontend && npm run lint
cd frontend && npm run format:check
cd frontend && npm test
```

## Backend (run from `backend/`)

```bash
cd backend && mvn verify -pl app
```

Report a summary: which checks passed, which failed, and the relevant error output for any failures. If all pass, say "All checks passed."
