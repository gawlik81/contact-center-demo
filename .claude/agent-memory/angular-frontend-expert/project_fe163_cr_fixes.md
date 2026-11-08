---
name: fe163-cr-fixes
description: FE-163 CR fixes (2026-11-08): recovery merge vs live parks, reconcileContact for 409, promotion revalidation, PHONE reserve decision
metadata:
  type: project
---

- `AgentRecoveryService.apply()` merges: a complete answer keeps entries parked after run start (`parkSeq`/`parkedAtSeq`) and rows in `returningIds`.
- 409 goes through `ContactConflictService.handle(key, contactId)` -> `reconcileContact` (GET /contacts/{id}); never join the shared recovery run for it.
- Promotion is async (revalidates via GET /contacts/{id}); tests must `await` microtasks after `TestBed.tick()`.
- Owner decision 2026-11-08: PHONE reserve 1 of 4 stays unconditional; FE has no phone-capability signal (JWT and /users/me carry no queues/channels).
**Why:** CR FE-163. **How to apply:** do not re-add sync promotion or a shared-run refresh after 409.
