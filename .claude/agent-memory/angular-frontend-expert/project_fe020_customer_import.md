---
name: Customer import from CSV (FE-020)
description: CustomerImportComponent – 4-step wizard for bulk customer import, CustomerImportStatus model, CustomerService import methods
type: project
---

CustomerImportComponent is a full-page standalone component (not a dialog) at route `/supervisor/customers/import`. It follows the same 4-step wizard pattern as CampaignImportComponent.

**Files created/modified:**
- `frontend/src/app/features/supervisor/pages/customers/customer-import.model.ts` – `DeduplicationMode`, `ImportJobStatus`, `CustomerImportStatus` interface
- `frontend/src/app/features/supervisor/pages/customers/customer-import/customer-import.component.ts` – wizard logic
- `frontend/src/app/features/supervisor/pages/customers/customer-import/customer-import.component.html`
- `frontend/src/app/features/supervisor/pages/customers/customer-import/customer-import.component.scss`
- `frontend/src/app/features/supervisor/pages/customers/services/customer.service.ts` – added `importCsv()`, `getImportStatus()`, `downloadImportErrors()`
- `frontend/src/app/features/supervisor/pages/customers/customer-list/customer-list.component.html` – "Importuj CSV" button in header + empty state
- `frontend/src/app/features/supervisor/pages/customers/customer-list/customer-list.component.ts` – added `navigateToImport()`
- `frontend/src/app/features/supervisor/supervisor.routes.ts` – `customers/import` route added BEFORE `customers/:id`

**Key design decisions:**
- Full page (not modal) unlike CampaignImportComponent which is a dialog – wizard has more fields so page layout is more appropriate
- `DeduplicationMode`: SKIP (default) | OVERWRITE – radio buttons in step 1
- `columnMapping` sent as JSON string in FormData field `columnMapping`; file in field `file`
- API params: `separator`, `quoteChar`, `deduplication` as query params
- `CustomerImportStatus` fields: `processed`, `total`, `imported`, `updated`, `skipped`, `failed`, `errorFileAvailable`
- Polling stops on `COMPLETED | FAILED_PARTIAL | FAILED`
- Error download: Blob → `URL.createObjectURL` → `<a>` click pattern
- Report shows 5 cards: total / imported / updated / skipped / failed

**Why:** **How to apply:** Use this as reference when building any future bulk import feature for the supervisor persona.
