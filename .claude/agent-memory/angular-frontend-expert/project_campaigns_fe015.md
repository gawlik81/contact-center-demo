---
name: FE-015 Campaigns implementation
description: Campaign list and creation form implemented for Supervisor role - file locations and patterns used
type: project
---

FE-015 completed: Campaign management (list + creation form) implemented for SUPERVISOR role.

**Why:** Part of Contact Center SaaS - supervisors need to manage outbound campaigns (voice/email).

**How to apply:** When extending campaign features, follow the same patterns below.

Files created:
- `frontend/src/app/features/supervisor/models/campaign.model.ts` – Campaign, CampaignSchedule, CreateCampaignRequest, UpdateCampaignRequest, PagedResponse types
- `frontend/src/app/features/supervisor/services/campaign.service.ts` – HTTP service with 7 methods
- `frontend/src/app/features/supervisor/pages/campaigns/campaign-list/campaign-list.component.{ts,html,scss}` – list with polling every 10s
- `frontend/src/app/features/supervisor/pages/campaigns/campaign-form/campaign-form.component.{ts,html,scss}` – creation/edit modal using native `<dialog>`

Route updated in `supervisor.routes.ts`: campaigns path now points to `CampaignListComponent` with `roleGuard` (SUPERVISOR, ADMIN).

Key patterns:
- Polling via `interval(10000).pipe(switchMap(...))` with `takeUntilDestroyed`
- Status transitions: DRAFT/SCHEDULED/PAUSED → start; RUNNING → pause/stop; STOPPED/COMPLETED → no actions
- Status badge colors: DRAFT=gray, SCHEDULED=blue, RUNNING=green, PAUSED=yellow, STOPPED=red, COMPLETED=teal
- Destructive actions (pause/stop) use `window.confirm` (matching project pattern – no MatDialog available)
- Form uses native `<dialog>` element with `showModal()` (same as user-form)
- Cross-field validators: endDate >= startDate, timeTo > timeFrom
- Active days use signal-based `Set<ActiveDay>` (separate from reactive form)
- `CampaignFormComponent` emits `saved: Campaign` (typed), parent list refreshes
