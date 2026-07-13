---
name: project-super-admin-role-refactor
description: Context and outcome of the SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT role refactor (branch rule-refactor, reviewed 2026-07-12)
metadata:
  type: project
---

## What changed

Branch `rule-refactor` (plan: `linked-questing-sedgewick.md`) restructured the role model:
- New role `SUPER_ADMIN` — global platform admin, `app_user.tenant_id IS NULL` (migrations `V080__add_super_admin_role.sql`, `V081__refresh_token_nullable_tenant_id.sql`). Takes over the old cross-tenant scope of `ADMIN` (`/api/admin/**`, `/api/tenants/**`, metrics, audit log, ETL status, plugin revoke kill-switch). Created only via `SuperAdminBootstrapRunner` (`ApplicationRunner`, idempotent, fail-fast outside `dev` profile without `SUPER_ADMIN_EMAIL`/`SUPER_ADMIN_PASSWORD`) — never via UI/API.
- `ADMIN` (same enum name, new meaning) — became fully tenant-scoped, absorbed the old `SUPERVISOR` scope (full technical + business config within its own tenant).
- `SUPERVISOR` — kept, but lost 5 *technical* areas to `ADMIN`: email/SMTP settings, social media OAuth integrations, Twilio config, AI config, plugins. Kept queues/IVR/phone numbers/routing rules (reclassified as *business*, not technical, per an explicit user correction during planning — see plan doc).
- Login flow was *extended* (not duplicated) to support tenant-less login: `LoginRequest.tenantId` optional, `UserDetailsServiceImpl` recognizes a `GLOBAL:{email}` username-key prefix, JWT omits `tenant_id`/`tenant_name` claims entirely when absent (not empty-string), `/api/public/tenants-by-email` response shape changed to `{tenants, superAdminAccount}`.

## Review outcome (2026-07-12, full findings in CR-BACKEND.md / CR-FRONTEND.md / CR-DATABASE.md)

- DB layer (V080/V081): 5/5 — exemplary migration hygiene, exception to `tenant_id NOT NULL` is narrowly scoped and enforced by a CHECK constraint (`chk_super_admin_tenant_invariant`), not just documented.
- Backend: 4/5 — two IDOR-class bugs (see [[feedback-manual-security-fixes-verified]]) were already manually fixed by the orchestrating agent before this review and are correctly fixed + well tested. One new bug found: see [[project-recurring-antipatterns]] #12 (`CrossTenantAspect` false ERROR spam for every SUPER_ADMIN request). One critical but tangential issue: `.env.local-demo` (tracked file) had real-looking secrets in its uncommitted diff — see [[project-tracked-env-file-secrets]].
- Frontend: 4.5/5 — clean, matches backend 1:1, only a cosmetic breadcrumb label left over (`admin.routes.ts` still says `role.admin` instead of `role.superAdmin`).

**How to apply:** If asked to review further work on this same epic/branch, don't re-litigate the two already-fixed IDOR issues (`TenantServiceImpl.assertSameTenantUnlessSuperAdmin`, `AuthServiceImpl.forcePasswordReset`) — they were verified correct with dedicated regression tests. Do check whether the `CrossTenantAspect` false-ERROR-log bug and the `.env.local-demo` secrets got cleaned up.
