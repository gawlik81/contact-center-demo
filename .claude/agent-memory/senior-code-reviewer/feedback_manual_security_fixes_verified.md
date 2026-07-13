---
name: feedback-manual-security-fixes-verified
description: Two IDOR fixes hand-written by the orchestrating (main) agent into the SUPER_ADMIN role refactor were verified correct on 2026-07-12 — don't re-litigate them
metadata:
  type: project
---

During the SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT role refactor (`rule-refactor` branch, see [[project-super-admin-role-refactor]]), the main orchestrating agent (not `backend-dev-expert`, which had flagged these as unresolved technical debt) hand-wrote two fixes before requesting this review, specifically asking for extra scrutiny since "this review sees them for the first time":

1. `TenantServiceImpl.getTenantConfig()`/`updateTwilioConfig()` — added private `assertSameTenantUnlessSuperAdmin(tenantId, action)`, called at the top of both methods. Fixes: GET previously asserted tenant match only for SUPERVISOR (ADMIN had unrestricted cross-tenant read); PATCH had no tenant assertion at all for any role (IDOR — any allowed role could overwrite any tenant's Twilio config by changing the UUID in the URL).
2. `AuthServiceImpl.forcePasswordReset()` + `AuthController` `@PreAuthorize` — added `SUPER_ADMIN` to the allowed roles, and changed the tenant check to `!"SUPER_ADMIN".equals(callerRole) && !Objects.equals(target.getTenantId(), callerTenantId)` (previously only checked `"SUPERVISOR".equals(callerRole)`, so ADMIN had unrestricted cross-tenant force-reset — this was even documented as "intended" in a test named `forcePasswordReset_adminCanResetAnyUser`).

**Verification result (2026-07-12 review):** both fixes are correct, null-safe (`Objects.equals` protects against NPE when either side's `tenantId` is null — relevant now that SUPER_ADMIN accounts have `tenantId == null`), and covered by explicit regression tests named to call out the IDOR (`TenantServiceTest.UpdateTwilioConfig.adminCannotUpdateOtherTenantConfig` — comment says "regresja IDOR", `AuthServiceTest.ForcePasswordResetTests.forcePasswordReset_adminCannotResetOtherTenantUser_crossTenantBlocked`). Full detail in `CR-BACKEND.md` (2026-07-12 entry).

**How to apply:** If reviewing further work on this branch/epic, do not re-flag these two spots as vulnerable — they were fixed and tested correctly. Do still spot-check they weren't reverted or weakened by later changes.
