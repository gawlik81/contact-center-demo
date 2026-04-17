---
name: Email templates management (FE-031)
description: Supervisor page for CRUD management of email response templates at /supervisor/settings/email-templates
type: project
---

EmailTemplatesComponent at `frontend/src/app/features/supervisor/pages/settings/email-templates/`.

Key decisions:
- EmailService (agent/services/email.service.ts) extended with `createTemplate`, `updateTemplate`, `deleteTemplate` + `CreateTemplateRequest` / `UpdateTemplateRequest` interfaces
- Three native `<dialog>` elements in one component: form modal (create/edit), delete confirm modal, preview modal
- `curlyOpen = '{{'` / `curlyClose = '}}'` readonly fields used in template to display literal mustache syntax (avoids Angular parser errors)
- Placeholder strings in `<input placeholder="...">` must NOT contain `{{ }}` – Angular parser treats them as interpolation; use neutral text instead
- `<label>` without `for` on group-level labels causes `@angular-eslint/template/label-has-associated-control` error – use `<span class="et-label">` instead
- FormArray for dynamic variables list; previewForm built dynamically via `addControl`/`removeControl`
- Preview uses `<iframe sandbox="allow-same-origin" [srcdoc]="...">` for XSS-safe HTML rendering
- Route: `supervisor/settings/email-templates`, `roleGuard`, roles `['SUPERVISOR', 'ADMIN']`
- Sidenav: added to Konfiguracja children in SUPERVISOR_NAV

**Why:** Backend API fully ready; supervisors need self-service template management without developer involvement.
**How to apply:** When touching EmailService or email-templates route, remember that EmailService lives under `features/agent/services/` — not under supervisor.
