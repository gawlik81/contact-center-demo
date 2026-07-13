---
name: project_customer_external_id
description: externalId field added across customer UI (create/edit/detail/list/import/agent panel) on customer-refactor branch, plus a 2026-07-05 CR fix round
metadata:
  type: project
---

Backend added optional `externalId: string` (max 255, unique per tenant, HTTP 409 on duplicate) to
`CustomerResponse`. Frontend was extended to surface it everywhere customer fields are shown, on branch
`customer-refactor` (2026-07-05).

**CR fix round (2026-07-05, CR-FRONTEND.md "Review: externalId on Customer"):**
1. `customer-edit.component.ts` payload builder used `raw.externalId?.trim() || undefined`, so
   clearing the field (typing empty string) sent `undefined`, which `JSON.stringify` drops entirely —
   backend PATCH semantics treat an absent field as "no change", so externalId could never be cleared
   once set. Fixed to `?? undefined` (empty string is not nullish, so it survives and is sent as `""`,
   which the backend now treats as "clear to NULL"). Do NOT apply the same fix to
   `customer-create-modal.component.ts` — no existing value to clear there, `||` is fine.
2. Turned out `CustomerSummary` (agent search DTO) IS already returned with `externalId` by the backend
   — `CustomerSearchService.search()` calls the same `GET /api/customers?q=...` endpoint that returns
   `CustomerResponse`, which has had `externalId` since the original rollout. Added `externalId?: string`
   to `CustomerSummary` in `agent/models/customer-search.model.ts`, plus a conditional display block in
   `agent-customer-card.component.ts` (inline template, no separate .html file for this component) right
   after the name, before the phone/email meta rows — styled like the label:value pattern in
   `customer-panel.component.html` (`{{ 'agent.customers.externalId' | transloco }}: {{ customer.externalId }}`),
   not the icon+text pattern used for phone/email. New i18n key `agent.customers.externalId` added in all
   4 locale files (pl/en/de/uk), value "ID zewnętrzne"/"External ID"/"Externe ID"/"Зовнішній ID" — matches
   the wording already used for `customerDetail.externalId` / `customerPanel.externalId` / `externalIdColumn`.
   **This supersedes the "deliberate scope decision" below about CustomerSummary — that assumption was
   wrong and has been corrected.**
3. Both `customer-create-modal.component.ts` and `customer-edit.component.ts` catchError blocks now type
   the error as `HttpErrorResponse` (imported from `@angular/common/http`) and branch: if
   `err.status === 409 && err.error?.detail`, show `err.error.detail` (backend's Polish message from
   `ProblemDetail`) directly; otherwise fall back to the existing generic transloco error message.
   **This also supersedes the "no dedicated 409 message" scope decision below.**

No spec files exist for `customer-edit.component.ts` or `customer-create-modal.component.ts` — per
standing scope decision, did not create new spec files just for this fix round (matches the earlier
decision not to add specs for these form components). Verified via `npm run lint` (0 errors), `npm run
format:check` (had to run `prettier --write` on `customer-edit.component.ts` once — line-length wrapping
of the catchError block), and `npm test` (11 spec files, 136 tests, all still passing) from `frontend/`.

**Files touched:**
- `frontend/src/app/features/supervisor/models/customer.model.ts` – `CustomerResponse.externalId?`
- `frontend/src/app/core/models/customer-profile.model.ts` – `CustomerProfile.externalId?` (feeds agent lookup panel)
- `customer.service.ts` – `createCustomer`/`updateCustomer` payload types extended
- `customer-create-modal` (ts+html) – new field, `Validators.maxLength(255)`
- `customer-edit` (ts+html) – new field in `sectionBasic`, populated in `populateForm()`
- `customer-detail.component.html` – conditional `@if (c.externalId)` row in contact-data card
- `customer-list` (ts+html+spec) – new `getExternalId()` helper (dash fallback like `getFirstPhone`), new
  `hide-sm` column, skeleton row updated to 7 cells
- `customer-import.component.ts` – `SystemField` union gained `'external_id'`, added to `systemFieldOptions`
  (label "ID zewnetrzne", ASCII-only like siblings) and `AUTO_MAP` (external_id/externalid/crm_id/id_zewnetrzne)
- `customer-panel.component.html` (agent side) – shows externalId under customer name when set
- i18n: all 4 locale files (pl/en/de/uk) got `customerCreate.externalIdLabel`+`maxLength255`,
  `customerEdit.externalIdLabel`+`externalIdPlaceholder`+`externalIdMaxLength`, `customerDetail.externalId`,
  `customers.externalIdColumn`, `agent.customerPanel.externalId`

**Deliberate scope decisions (original rollout — first two corrected by the 2026-07-05 CR fix round above):**
- ~~`CustomerSummary` was NOT extended~~ — corrected: it now has `externalId?` (see CR fix round item 2).
- ~~No dedicated 409 message~~ — corrected: both create/edit now surface `err.error.detail` on 409 (see
  CR fix round item 3).
- `customer-import.component.ts` dropdown option labels (`systemFieldOptions`, `columnSeparatorOptions`, etc.)
  are hardcoded Polish strings, NOT transloco keys — this file predates the i18n rollout and wasn't
  retrofitted; followed the existing (non-transloco, ASCII-only) convention rather than introducing a
  mixed style.

Verified via `npm run lint` (0 errors, pre-existing console warnings only), `npm run format:check`, and
`npm test` (11 spec files, 136 tests, all passing) from `frontend/`.
