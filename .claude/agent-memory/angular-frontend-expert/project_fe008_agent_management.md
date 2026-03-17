---
name: Agent management UI (FE-008)
description: Supervisor feature – agent list with filters, create/edit modal with skills multi-select, delete modal (409 handling), force password reset modal
type: project
---

Agent management feature implemented under `features/supervisor/` for the SUPERVISOR role.

**Route:** `/supervisor/agents` → lazy-loads `UserListComponent`

**File structure:**
- `features/supervisor/models/user.model.ts` – `UserResponse`, `CreateUserRequest`, `UpdateUserRequest`, `UpdateStatusRequest`, `UserListParams`
- `features/supervisor/services/user.service.ts` – all API calls (`getUsers`, `createUser`, `updateUser`, `deleteUser`, `getSkills`, `updateStatus`, `forcePasswordReset`)
- `features/supervisor/pages/users/user-list/` – table with status/skill filters, debounce 300ms, skeleton loading, empty state, action buttons per row
- `features/supervisor/pages/users/user-form/` – native `<dialog>` modal, reactive form, skills multi-select with autocomplete dropdown + chip tags, password strength validator (only on create), edit disables email field
- `features/supervisor/pages/users/user-delete-modal/` – native `<dialog>`, 409 conflict shown as warning toast (not error)
- `features/supervisor/pages/users/user-reset-password-modal/` – native `<dialog>`, confirmation before `POST /api/auth/force-reset/{id}`

**API endpoints used:**
- `GET /api/users?page&size&status&skill`
- `POST /api/users`
- `PATCH /api/users/{id}`
- `DELETE /api/users/{id}` (409 = active contacts)
- `GET /api/users/skills`
- `PATCH /api/users/{id}/status`
- `POST /api/auth/force-reset/{id}`

**Status badge colors:** AVAILABLE=green, BUSY/AFTER_CONTACT=orange, BREAK=yellow, ACTIVE=blue, INACTIVE=grey

**Skills:** stored as `string[]`, multi-select via chip input with autocomplete from `GET /api/users/skills`, custom skill can be added by typing + Enter

**Password validation (create only):** min 8 chars, 1 uppercase, 1 digit – custom `passwordStrengthValidator` function

**Why:** Part of EPIC-02 supervisor persona features. Skills multi-select uses custom CSS chips, no Angular Material (consistent with project UI pattern).

**How to apply:** When adding more supervisor features, follow same folder layout `pages/{feature}/` and native dialog pattern from this feature.
