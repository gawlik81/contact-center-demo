---
name: Campaign JSON import (frontend half of BE-023/FE-016 JSON extension)
description: Added JSON import as an alternative to CSV for outbound campaign contacts, mirroring the customer-import JSON pattern
type: project
---

Full plan lived at `/home/pawelm/.claude/plans/staged-yawning-raven.md` (branch `customer-refactor`,
implemented 2026-07-12). Backend added `POST /api/campaigns/{id}/contacts/import/json`
(`skipDuplicates` only — no dedup mode, no email/externalId/gdprConsent, campaign_contact JSON rows
are flat `{ phone, firstName, lastName, customFields }`).

**Files changed:**
- `frontend/src/app/features/supervisor/services/campaign.service.ts` — added
  `importContactsJson(campaignId, file, skipDuplicates)`.
- `frontend/src/app/features/supervisor/pages/campaigns/campaign-import/campaign-import.component.ts` —
  added `importFormat` signal, converted static `allSteps` array to `computed()`, fixed
  `getStepNumber`/`isStepCompleted` to read `this.allSteps()`, added `parseJsonPreview`/
  `setInvalidJsonState`/`stringifyPreviewCell`, `goToImportOrMapping()`, `submitJsonImport()`.
- `.html`/`.scss` — `accept=".csv,.json"`, separator-config wrapped in `@if (importFormat() ===
  'csv')`, new `.mapping-hint` JSON-format hint block, upload-step button now calls
  `goToImportOrMapping()` with a format-conditional label.
- i18n (`pl`/`en`/`de`/`uk`) — new `supervisor.campaignImport.jsonFormatHint`, extended
  `errors.csvOnly`, updated `dropZoneAriaLabel`/`fileInputAriaLabel`.
- New `campaign-import.component.spec.ts` (first spec ever written for this component; see
  [[project_testing_patterns]] for the jsdom `<dialog>` polyfill and `vi.useFakeTimers()` polling
  pattern this file established).

**Deliberate deviation from the `customer-import.component.ts` reference pattern:** did NOT rename
`csvHeaders`/`csvPreviewRows` to `previewHeaders`/`previewRows` even though customer-import uses the
renamed form — the campaign-import plan only called for the specific listed changes, not a rename,
so `csvHeaders()`/`csvPreviewRows()` now serve as the generic preview signals for both CSV and JSON
in campaign-import (name is a pre-existing minor mismatch, not worth the unrelated diff).

**Reused `customerImport.*` i18n keys for JSON validation** (`invalidJsonArrayError`,
`emptyJsonArrayError`) rather than duplicating them under `campaignImport.*` — this matches the
pre-existing convention already visible in this component's HTML (stepFile/stepMapping/dragText/
mappingTitle/etc. are all pulled from `supervisor.customerImport.*`; `campaignImport.*` only holds
campaign-specific overrides).

**`CampaignImportComponent` is a modal dialog** (`input.required<Campaign>()`, `output<boolean>()`,
native `<dialog>` shown via `ngAfterViewInit`), unlike `CustomerImportComponent` which is a full
routed page — this is why campaign-import has no `Router`/`navigateToList()` and instead emits
`closed` and calls `dialog.close()`.
