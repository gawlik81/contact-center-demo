---
name: Admin cross-tenant user management (FE-009 / BE-009 + FE-010)
description: AdminUserListComponent and AdminUserFormComponent for cross-tenant user management – create, edit, delete, force-password-reset
type: project
---

Admin cross-tenant user management implemented across backend and frontend.

**Why:** Admin needs to see users from ALL tenants, not just their own, and perform full CRUD including force-password-reset.

**How to apply:** When building admin-scoped features that operate across tenants, use `/api/admin/**` endpoints (ADMIN role only, no RLS) and a dedicated service/component — do not reuse supervisor-scoped components.

## Backend – /api/admin/users endpoints

| Method | Path | Action |
|--------|------|--------|
| GET    | /api/admin/users | List (optional ?tenantId) |
| POST   | /api/admin/users | Create in any tenant |
| PATCH  | /api/admin/users/{id} | Update (firstName, lastName, email, role, skills, active) |
| DELETE | /api/admin/users/{id} | Soft delete (is_deleted=true, is_active=false) |
| POST   | /api/admin/users/{id}/force-password-reset | Set passwordResetRequired=true |

- New DTO: `AdminCreateUserRequest` — includes `tenantId` field (required)
- New DTO: `AdminUpdateUserRequest` — all fields optional (PATCH semantics); allows email + role change (unlike supervisor UpdateUserRequest)
- `AdminUserService` — `listUsers`, `createUser`, `updateUser`, `deleteUser`, `forcePasswordReset`
- `AppUserRepository` — `findAllByDeletedFalse(Pageable)` cross-tenant, `softDeleteUser` already existed from FE-008

## Frontend files

- `admin-user.model.ts` — added `AdminUpdateUserRequest` interface
- `admin-user.service.ts` — added `updateUser()`, `deleteUser()`, `forcePasswordReset()`
- `admin-user-list/` — added Actions column with Edit (pencil), Force-Reset (lock), Delete (trash) icon buttons; native inline confirm dialogs (no route change); signals for delete/forceReset modal state
- `admin-user-form/` — extended with `user = input<AdminUserResponse | null>()` for edit mode; `isEditMode = computed()`; password field hidden in edit mode; tenantId selector hidden in edit mode; `active` checkbox shown only in edit mode

## Tenant list – edit feature

- `tenant.model.ts` — added `UpdateTenantRequest` interface
- `tenant.service.ts` — added `updateTenant(id, req)`, `checkNameAvailabilityForUpdate(tenantId, name)` (GET /api/tenants/{id}/check-name)
- New component: `tenant-edit-modal/` — `TenantEditModalComponent` (native dialog, same pattern as deactivate modal); fields: name (with async name-availability-for-update validator), status, maxAgents, maxQueues, maxCampaigns
- `tenant-list/` — added Edit button per row; `editingTenant` signal + `showEditModal` signal; imports `TenantEditModalComponent`

## Key patterns

- Confirm dialogs for destructive actions (delete, force-reset) are implemented inline in the list template as `<dialog open>` with a backdrop div — not separate components (keeps code minimal)
- `AdminUserFormComponent` uses `isEditMode = computed(() => this.user() !== null)` to switch between create/edit modes
- Edit mode: tenantId control is disabled, password field is hidden, active checkbox is shown
- `TenantEditModalComponent` uses `nameAvailabilityForUpdateValidator` (not the create validator) to allow keeping the current name
