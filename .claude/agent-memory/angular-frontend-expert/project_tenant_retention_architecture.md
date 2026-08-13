---
name: project_tenant_retention_architecture
description: tenant_retention_policy (per-tenant ADMIN, FE-104 page) is the sole source of truth for recording retention — tenant.config/TenantLimits must never carry a parallel retention field
metadata:
  type: project
---

EPIC-29 (data retention/partitioning) established `tenant_retention_policy`, managed by the
tenant's own ADMIN on the dedicated retention settings page (FE-104,
`frontend/src/app/features/settings/...retention...`), as the single source of truth for how long
recordings/data are kept per tenant.

As of FE-109 (2026-08-13), `recording_retention_days` was removed entirely from
`frontend/src/app/features/tenants/tenant.model.ts` (`TenantConfig`, `TenantLimits`,
`CreateTenantRequest.limits`). This field previously lived alongside SUPER_ADMIN-level tenant
config (`tenant.config`, edited via `tenant-edit-modal`/`tenant-add-modal` in
`features/tenants/`) and would have created two competing sources of truth for the same value.

**Why:** Explicit architectural decision — retention is a tenant-admin concern (FE-104), not a
platform SUPER_ADMIN concern (`features/tenants/`). The design doc had described the missing form
field as a gap to fill; the accepted decision went the opposite direction (delete the model field
instead of building UI for it).

**How to apply:** If asked to add any retention/recording-duration field to
`features/tenants/tenant.model.ts`, `tenant-edit-modal`, or `tenant-add-modal` (SUPER_ADMIN tenant
management), push back and point to `tenant_retention_policy` / FE-104's retention settings page
as the correct place instead. Also note the `PATCH /api/tenants/{id}` vs
`PATCH /api/tenants/{id}/config` split: `/config` is Twilio-config-only, general tenant fields
(name, status, limits) go through `/api/tenants/{id}`.

EPIC-29 is now fully complete on the frontend side — FE-109 was its last ticket.
