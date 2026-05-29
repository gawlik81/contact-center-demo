---
name: Frontend Angular patterns and known issues
description: Observed patterns, anti-patterns, and known issues in the Angular 21 frontend — updated after FE-011/FE-017 review on 2026-03-20
type: project
---

The frontend is Angular 21 with standalone components, Signals, OnPush everywhere, functional guards/interceptors, and lazy loading at all feature boundaries. No NgModules.

**Known critical issues (still open as of 2026-03-20):**
- Access token written to `localStorage` (XSS risk) in `token.service.ts:14` — should be memory-only.
- `authInterceptor` uses file-level (`let`) `isRefreshing` and `refreshTokenSubject` — module-level state survives logout and can deadlock queued requests across user sessions in the same tab.
- Login form hardcoded tenant UUIDs: FIXED (now uses PublicTenantService dynamic API call).

**Known major issues (still open as of 2026-03-20):**
- `TenantListComponent` requests `size: 1000` — no real pagination.
- `supervisor.routes.ts`: queues/campaigns/reports/settings routes still load SupervisorDashboardComponent as stub (customers route now fixed — loads CustomerListComponent).
- Modal components use `document.addEventListener('keydown')` bypassing Angular Zone.js — should use `@HostListener` or native `(cancel)` event.
- Unit tests: only `customer-lookup.service.spec.ts` exists with real value (8 cases, vitest). All other components/services/guards untested.

**Recurring anti-pattern: missing ngOnDestroy for setInterval.**
- DispositionPanelComponent (FE-017) had setInterval started in ngOnInit but no ngOnDestroy — interval leaked. Fixed in review 2026-03-20.
- When reviewing components with setInterval/setTimeout stored in instance fields, always verify ngOnDestroy clears them.

**Recurring anti-pattern: bare .subscribe() in ngOnChanges/OnChanges lifecycle.**
- CustomerPanelComponent (FE-011) subscribed to HTTP observable in ngOnChanges without unsubscribe or takeUntilDestroyed — caused race condition when CLI changed rapidly and memory leak after destroy. Fixed in review 2026-03-20.
- Pattern to enforce: in OnChanges hooks, always cancel previous subscription before issuing new one; use takeUntilDestroyed for destroy protection.

**Recurring anti-pattern: missing Polish diacritics in notification strings.**
- Found in disposition-panel, agent-desktop, agent-status.service, customer-lookup.service, disposition.model (FE-017/FE-011).
- Also found in user-list.component (first review, FE-006).
- Flag all bare .error()/.success() toast strings for diacritic completeness.

**Architecture decision — dialog modals:**
- <dialog open> (non-modal attribute) is used in DispositionPanelComponent — does NOT activate native focus trap. Should use showModal() instead.
- Other modals (user-form, deactivate etc.) correctly use showModal(). This is an inconsistency introduced in FE-017.

**Agent role boundary issue:**
- CustomerPanelComponent (FE-011) has "View full profile" button navigating to /supervisor/customers/:id — AGENT role will get 403 from RoleGuard. Button should be hidden for agents or route should be added for agents.

**Positive patterns (new in FE-017/FE-011):**
- ContactTabStore is a clean signal store with controlled mutation methods and limit checking.
- CustomerLookupService has in-memory cache (5 min TTL), evict() method, correct 404 vs 5xx handling.
- ACW timer implemented with ReturnType<typeof setInterval> (portable) and reset on both save-success and save-error.
- softphoneEndedEffect uses effect() as class field (preferred Angular 21 pattern), with WRAPPING guard to prevent double-transition.
- canSave computed() correctly combines code selection + isSaving for double-submit prevention.

## FE-027 (Manual Dialer Panel) — new findings 2026-04-08

**Bugs:**
- `ManualCampaignPanelComponent.refresh()` does not reset `error` signal before calling API — after an error, manual refresh shows stale error message until response arrives.
- `refresh()` method duplicates `ngOnInit` switchMap logic — DRY violation and inconsistent error handling paths.

**Architecture violations:**
- `DialerService` is in `supervisor/services/` but is used exclusively by agent component (`ManualCampaignPanelComponent`). Correct location: `agent/services/` or `core/services/`.

**Recurring anti-pattern confirmed:**
- Missing Polish diacritics in all user-facing strings (TS and HTML): "rekordow" → "rekordów", "Zmien" → "Zmień", "Dostepny" → "Dostępny", "polaczenia" → "połączenia", "Nie udalo sie" → "Nie udało się". **Fourth occurrence across reviews.**

**Minor:**
- Notification message for success call uses `.trim()` but leaves double-space when firstName/lastName both null. Should use `[name, phone].filter(Boolean).join(' ')` pattern.

**Positive patterns (new in FE-027):**
- OnPush + signals throughout.
- `takeUntilDestroyed(this.destroyRef)` on all subscriptions.
- Preserved `expanded` state on poll refresh — good agent UX.
- Optimistic record removal after manualCall success.
- Full WCAG AA: aria-label, aria-expanded, aria-controls, aria-busy, role="list/status/alert", aria-live on error.
- Skeleton loading for initial state.
- HTTP error status discrimination (409 vs 404 vs generic).

## FE-019 (Customer Detail) — new findings 2026-03-21

**Architecture violations:**
- `CustomerDetailComponent` imports `ContactResponse` from `features/agent/models/contact.model` — cross-feature dependency (supervisor importing from agent). `ContactResponse` is a shared domain model and should live in `core/models/` or `shared/models/`.
- `supervisor.routes.ts` route `customers/:id` lacks explicit `canActivate: [RoleGuard]` + `data.roles` — inconsistent with other routes in the project.

**Minor issues:**
- `contactsLoadState` has no `'error'` state (unlike `loadState`) — user cannot distinguish "empty" from "load error" in contacts history section.
- `customerService.getCustomerContacts` has no max-size guard on `size` parameter — callers could pass large values.
- `customerId` stored as separate signal redundant with `customer()?.customerId` — two sources of truth for same ID.
- `.status-badge--wrap_up` CSS class uses underscore in BEM modifier (non-standard); built dynamically from `contact.status.toLowerCase()`.

**Positive patterns (FE-019):**
- `switchMap` on `paramMap` correctly cancels in-flight requests on route param change.
- `loadContacts()` correctly uses `takeUntilDestroyed(this.destroyRef)` — no leak on pagination clicks.
- Full skeleton UI for both page load and contacts table load.
- ARIA attributes well implemented: `scope="col"`, `aria-live="polite"` on pagination info, `aria-label` on buttons, `aria-current="page"`.
- `formatDuration` guards against negative diff (endedAt before startedAt).
- `trackByContactId` defined and used in `@for`.

## FE-024 (Queue Configuration Panel) — new findings 2026-03-21

**Bugs:**
- `queue-form.component.html` and `queue-delete-modal.component.html`: backdrop click uses `$event.target === dialogEl` where `dialogEl` is `ElementRef`, but `$event.target` is `HTMLElement` — comparison always false. Backdrop close never works. Fix: use `$event.target === $event.currentTarget`.
- `QueueListComponent.loadQueues()` creates new subscription on every call (no switchMap/exhaustMap) — concurrent HTTP requests on fast pagination. Should use Subject + switchMap.
- `onDeleteConfirmed()` calls `closeDeleteModal()` in `finalize()` — closes modal on error, preventing user retry. Should close only in `next` (success) branch.

**Security:**
- `supervisor.routes.ts` route `queues` has no `canActivate: [roleGuard]` with `data.roles` — AGENT can navigate to `/supervisor/queues` directly and get series of 403s instead of proper redirect to `/forbidden`.

**Architecture violations:**
- `QueueService` imports `PagedResponse` from `user.model` — cross-module type dependency. `PagedResponse<T>` is a shared contract and should live in `core/models/paged-response.model.ts`.

**Minor:**
- `setTimeout(..., 150)` in `onSkillInputBlur` — no cleanup on destroy, signal update on destroyed component possible.
- `firstItemIndex` and `lastItemIndex` declared as arrow function fields, not `computed()` — misleading pattern, no memoization.
- Redundant `if (!isEditMode())` block setting `isActive=true` — already the FormGroup default value.
- `aria-live="polite"` on `<table>` element — screen reader may announce entire table contents on any change. Should be on summary/status text only.
- `loadingOptions` not reset in `finalize()` — remains `true` if forkJoin errors before subscribe. Fix: add `finalize(() => this.loadingOptions.set(false))`.

**Positive patterns (new in FE-024):**
- `showModal()` used correctly via `viewChild` in `ngAfterViewInit` — lesson from FE-017 applied immediately.
- Escape key handling via `host: { '(document:keydown.escape)' }` — correct, avoids `document.addEventListener` anti-pattern.
- `forkJoin` for parallel options loading — good performance practice.
- `autofocus` on "Anuluj" in delete modal — correct UX (non-destructive default focus).
- ARIA on skills combobox: `role="combobox"`, `aria-autocomplete="list"`, `aria-expanded`, `aria-controls` — solid a11y.

**Why:** This context loads before any future review session so findings are not rediscovered from scratch.
**How to apply:** When reviewing new frontend PRs, check against these known issues to see if they've been resolved or if new code repeats the patterns.

## EPIC-24 (Call Transfer) — new findings 2026-05-15

**Critical bug:**
- `softphone.service.ts` attended transfer methods (`initiateAttendedTransferToAgent`, `initiateAttendedTransfer`): `catchError(() => of(null))` + session set to TRANSFERRING before HTTP — on HTTP error, sesja stays TRANSFERRING, `attendedConnected.set(true)` fires, agent is stuck. Fix: `catchError` must restore `session.set({...s, state: 'ACTIVE'})` and return `EMPTY`.

**Type mismatch:**
- `TransferAgentItem.status` missing `AFTER_CONTACT`, `ACTIVE`, `INACTIVE`; has `ON_CALL` which doesn't exist in backend. Recurring pattern: frontend enums not kept in sync with backend.

**Hardcoded Polish strings (recurring, fourth+ occurrence):**
- Tab labels `'Telefon'`, `'Agent'`, `'Kolejka'` hardcoded in `softphone.component.ts` transferTargetTabs array.
- All strings in `transfer-agent-list.component.html` and `transfer-queue-list.component.html` are Polish literals without Transloco.

**Architecture:**
- Missing `[disabled]="isTransferring"` on agent/queue buttons inside transfer list components — parent guards state but child components don't. Keyboard users can still trigger double-click.
- Dead code: `@else { completeAttended }` block in PHONE attended section of transfer panel — unreachable because session goes TRANSFERRING before `attendedConnected=true`.
- `completeAttendedTransfer` silently skips bridge when `secondLegCallId` is null — session moved to ENDED without actual bridge.

**Positive patterns:**
- `takeUntilDestroyed(this.destroyRef)` in both `TransferAgentListComponent` and `TransferQueueListComponent` — no subscription leaks.
- `ChangeDetectionStrategy.OnPush` on all three new components.
- `isTransferring` signal correctly blocks duplicate HTTP calls from component layer.
- Skeleton loading states and error states in both new components.
- `@for ... track agent.agentId` and `track queue.queueId` — correct trackBy usage.
- `searchQuery` computed filter via `filteredAgents` signal — correct, no pipe overhead.

**Check in future softphone-related reviews:**
- Does `catchError` in attended transfer restore session to ACTIVE on error?
- Are frontend TypeScript union types for agent/user statuses in sync with backend enum values?
- Are all user-visible strings using Transloco, not hardcoded Polish literals?

## EPIC-27 (Custom Dispositions) — new findings 2026-05-27

**Major bugs:**
- `DispositionListEditorComponent` loads data only in `ngOnInit()` — no `effect()` on `campaignId`/`queueId` signals. If parent changes context without destroying component, list never refreshes. Fix: use `effect()` in constructor to react to input signal changes.
- `DispositionPanelComponent.ngOnInit` fallback uses `d.code` as label (`label: d.code`) instead of `this.transloco.translate(d.labelKey)` — agent sees `"NO_INTEREST"` instead of `"Brak zainteresowania"` when API fails.

**Minor issues:**
- `DispositionTone` in `disposition.model.ts` defines `'accent' | 'success' | 'danger' | 'violet'` — values incompatible with API `DispositionToneApi` and `toneClass()` map. Old model is deprecated but the type discrepancy can cause confusion.
- `onDeleteExecute()` and `onSubmit()` in `DispositionListEditorComponent` use `queueId!` non-null assertion when both campaignId and queueId could be undefined — API called with `undefined` in URL path.
- Multiple `loadDispositions()` calls (after save, after delete) each create new subscriptions — no switchMap to cancel in-flight requests; last response wins.

**Positive patterns (EPIC-27):**
- `DispositionListEditorComponent`: `standalone: true`, `OnPush`, `signal()` for state, `takeUntilDestroyed` on all subscriptions, `catchError` + EMPTY for all HTTP ops — correct across the board.
- 409 error handled user-friendly (specific message vs generic error).
- Form disables `dispositionCode` field during edit (code is immutable after create) — correct UX.
- `TONE_CSS_CLASS: Record<DispositionToneApi, string>` is type-safe, exhaustive mapping.
- Fallback agent panel uses `console.warn` (not `console.error`) for graceful degradation — correct severity.
- `CampaignDispositionsComponent` and `QueueDispositionsComponent` are minimal wrappers with `input.required<string>()` — clean interface.
- Dispositions section in campaign/queue forms shown only in edit mode (`@if campaignId()`) — correct.

**Check in future disposition-related reviews:**
- Does `DispositionListEditorComponent` use `effect()` to react to `campaignId`/`queueId` changes?
- Does agent panel fallback use `transloco.translate(d.labelKey)` for labels, not `d.code`?
- Are both `campaignId` and `queueId` guarded before using `!` assertion in HTTP calls?
