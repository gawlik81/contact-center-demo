---
name: FE-112 GDPR anonymize preview (D9) + list/detail unification
description: AnonymizePreviewResponse DTO exact shape, gdpr-anonymize-modal preview gating, customer-list unified onto GdprAnonymizeModalComponent
type: project
---

FE-112 (EPIC-30) implemented 2026-09-30: expanded RODO scope messaging (BE-129/DB-062) + D9 preview
(`GET /api/customers/{id}/gdpr/anonymize/preview`) in `GdprAnonymizeModalComponent`, and unified
`customer-list` onto the same modal used by `customer-detail` (removed `customer-delete-modal`
entirely).

**`AnonymizePreviewResponse` exact shape (verify against
`backend/app/src/main/java/com/contactcenter/api/customer/dto/AnonymizePreviewResponse.java` if it
changes):**
```ts
interface AnonymizePreviewResponse {
  dryRun: boolean;
  counts: Record<string, number>; // keys: customer, contact, scheduled_callback,
                                   // campaign_contact, campaign_contact_archive,
                                   // contact_transcription, contact_ai_summary,
                                   // email_message, social_message, contacts_dw
  matchedByLink: number;
  matchedByIdentifier: number;
  s3ObjectsToDelete: number;
}
```
**Why the top-level fields are camelCase but `counts` keys are snake_case:** the outer DTO is a Java
record → Jackson serializes accessor names as-is (camelCase, no global `SNAKE_CASE` naming strategy
on the main Spring `ObjectMapper` — that override only exists locally in `AiSummaryClient`/
`VoicebotClientImpl` for the Python voicebot integration). `counts`, however, is a `Map<String,
Integer>` populated by copying JSONB keys verbatim from the SQL function `anonymize_customer`
(`GdprServiceImpl#toPreviewResponse`: `countsNode.fields().forEachRemaining(...)`) — those keys are
literal Postgres table names, snake_case, untouched by Jackson. Design docs/tickets that quote
`matched_by_link`/`matched_by_identifier` are describing the SQL/JSONB layer, not the REST contract —
the actual field is `matchedByLink`/`matchedByIdentifier`.

Model + order/label-mapping constants live in
`frontend/src/app/features/supervisor/pages/customers/gdpr.model.ts`
(`GDPR_PREVIEW_COUNT_ORDER`, `GDPR_PREVIEW_COUNT_LABEL_KEYS`) — UI renders `counts` dynamically
(`Object.keys` + sort by known order, unknown keys alphabetically appended, unknown key falls back to
raw key as its own i18n "label" so it degrades instead of throwing) rather than hardcoding a fixed
row list, since the ticket's own framing ("verify, don't guess the exact shape") implied the key set
could drift.

**Preview gating pattern in `GdprAnonymizeModalComponent`:** `previewState = signal<'loading'|
'loaded'|'error'>`, loaded via a plain `.subscribe()` in `ngOnInit()` (NOT `BehaviorSubject` — CLAUDE.md
reserves that for streaming/polling; this is a one-shot call per modal open). `isConfirmEnabled()`
now requires `previewState() === 'loaded'` in addition to the existing exact-phrase check. Added an
explicit RxJS `timeout(15000)` + `catchError(() => of(null))` around the preview call so a genuinely
hanging request (not just an HTTP error) also flips to `'error'` and blocks confirmation — this was
an explicit AC ("błąd/timeout podglądu też blokuje potwierdzenie"), plain HttpClient has no default
timeout.

**`POST /api/customers/{id}/gdpr/anonymize` returns `204 No Content`, no body.**
`GdprServiceImpl.anonymizeCustomer` computes a `failedKeys` list (S3 cleanup best-effort failures)
internally but only `log.error`s it — never returns it to the caller/controller. Confirmed no DTO
exists for "partial S3 cleanup failure" — the ticket's optional AC item for showing this in UI was
explicitly skipped for this reason (would require a backend change, out of scope: "zero zmian w
backendzie").

**Unification confirmed safe via source read, not assumption:**
`CustomerController.java` `DELETE /api/customers/{id}` (lines ~257-279) is a full delegation to
`GdprService#anonymizeCustomer` — identical DB effect to `POST .../gdpr/anonymize`. This meant
`customer-list.component.ts` could switch from its own `CustomerService#deleteCustomer` +
`CustomerDeleteModalComponent` flow to rendering the same `GdprAnonymizeModalComponent` +
`GdprService#anonymize` as `customer-detail.component.ts`, with the parent's `(confirmed)` handler
reduced to "close modal + reload list" (the modal itself owns the HTTP call and the
success/error toast — this is why `customer-list`'s own toast-related code disappeared entirely).
`customer-delete-modal/` directory deleted outright (3 files); all `supervisor.customerDelete.*` i18n
keys removed from all 4 language files (grep-confirmed zero remaining references before deleting).
`CustomerService#deleteCustomer` method itself was left in place (still a valid 1:1 wrapper over a
real, documented backend endpoint) even though nothing in the UI calls it anymore — a deliberate
choice to avoid touching the service's CRUD-parity contract beyond what the ticket asked; flagged in
the ticket's implementation note for the user to reconsider if they want it removed too.

See also [[project_local_demo_env_constraints]] for what happened when trying to visually verify this
in local-demo (browser network isolation, not a code issue).
