---
name: project-rls-inconsistency
description: V069 custom_disposition has dead RLS policy using wrong setting name 'app.tenant_id' instead of 'app.current_tenant_id'
metadata:
  type: project
---

V069__create_custom_disposition.sql line 55 uses `current_setting('app.tenant_id', TRUE)::UUID` in its RLS USING clause. The correct setting name set by `set_tenant_context()` function (V023) is `app.current_tenant_id`. This means V069's RLS policy is effectively dead — it never matches. V070 was intended as a fix; check if it corrects this.

**Why:** Discovered during EPIC-27 code review (2026-05-28). The inconsistency was not caught in the V069 review.

**How to apply:** When reviewing any new migration that builds on or references `custom_disposition`, verify whether V070 has fixed the RLS policy name. If not, flag this as a pre-existing critical bug. Also, use this as a reminder to always verify `current_setting('app.current_tenant_id', TRUE)` (not `app.tenant_id`) in new policies.

Related: [[project-architecture-conventions]]
