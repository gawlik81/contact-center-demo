---
name: fe108-retention-badge
description: FE-108 global "data pending deletion" badge in sidenav — where the polling stream lives, gating decisions, and the two documented deviations from the ticket text
metadata:
  type: project
---

FE-108 (EPIC-29, closes the FE sequence) added a global badge on the "Konfiguracja" nav
section (`sidenav.component.ts`) that lights up when any retention category has
`eligibleRowCount > 0`. See [[project_fe103_retention_service]] (if/when written) and
[[project_fe007_admin_dashboard]] for the badge pattern this was modeled on.

**Where the polling stream lives:** `RetentionService.pendingDeletionCategoryCount$`
(`frontend/src/app/features/supervisor/services/retention.service.ts`), not a new service.
Decision: `RetentionService` already owns `getSummary()` and is `providedIn: 'root'` — one
service per data domain. Pattern copied 1:1 from `AdminMetricsService._poll$`:
`toObservable(auth.currentRole)` + `switchMap` + `timer(0, 30_000)` + `shareReplay({refCount:true})`,
gated by role `'ADMIN'` (tenant admin, not `'SUPER_ADMIN'`). Extra gate needed here that
`AdminMetricsService` doesn't have: `RetentionService.getSummary()` throws *synchronously*
(via private `requireTenantId()`) when `authService.currentTenantId()` is null, so the timer's
inner `switchMap` re-checks `currentTenantId()` on every tick before calling `getSummary()`,
rather than trusting the outer gate alone (closes a race where tenant context could vanish
between ticks, e.g. mid-logout).

**Two deviations from the ticket text, both documented in `TASKS-FRONTEND.md` FE-108 notes:**
1. Ticket says badge goes on "Ustawienia" — that nav item doesn't exist. `nav.settings` i18n key
   is an orphaned/unused key. The actual item containing the retention sub-page
   (`/supervisor/settings/data-retention`, FE-104) is the "Konfiguracja" section
   (`nav.configuration`, route `/supervisor/settings`) — badge was attached there instead.
2. Ticket's cited badge pattern (`showAlertBadge` in `SidenavComponent`, FE-007) gates visibility
   by current URL too (only shows on `/admin/dashboard`). The new `showRetentionBadge` deliberately
   drops that gate — ticket is explicitly named "Globalny badge", so visibility must not depend
   on which page the admin is currently viewing.

**Badge content:** count of categories (0-4) with `eligibleRowCount > 0`, not a bare dot and not
the raw summed row count (could be huge/unreadable as "99+"). Reused the exact
`sidenav__alert-badge` CSS class unchanged — no new SCSS.

**Known accepted limitation (not fixed here):** `RECORDINGS` category always has
`computed === false` and `eligibleRowCount === 0` until BE-116 ships (see
`RetentionSummaryDto.computed` field doc in `retention.model.ts`) — badge sums
`eligibleRowCount` literally per the acceptance criteria, so it won't reflect real pending data
in that category yet.

i18n: new key `nav.retentionPendingBadge` (with `{{ count }}` param) added identically to all 4
locale files. Verified programmatically that the key set (not just this one key) matches across
locales before committing — found a **pre-existing, unrelated** gap in de/uk
(`supervisor.customerDetail.contactStatusLabels.{ASSIGNED,TRANSFERRED,NOT_REACHED,ERROR}` missing
in both) that predates this ticket and was left alone (out of scope).
