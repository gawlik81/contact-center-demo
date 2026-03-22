---
name: FE-022 Reports module implementation
description: Historical reports module for SUPERVISOR/ADMIN – files created, conventions used, and known decisions
type: project
---

FE-022 (Raporty historyczne) is fully implemented as of 2026-03-22.

**Files created/modified:**
- `frontend/src/app/features/supervisor/models/report.model.ts` – `AgentReportRow` + `AgentReportFilters` interfaces
- `frontend/src/app/features/supervisor/services/reports.service.ts` – HTTP service with `getAgentReport`, `exportCsv`, `exportXlsx`, private `buildParams`
- `frontend/src/app/features/supervisor/pages/reports/reports-placeholder.component.ts` – renamed class to `ReportsComponent`, selector `app-reports`
- `frontend/src/app/features/supervisor/pages/reports/reports-placeholder.component.html` – template (filters, skeleton, table, pagination, empty state)
- `frontend/src/app/features/supervisor/pages/reports/reports-placeholder.component.scss` – pure SCSS, no framework
- `frontend/src/app/features/supervisor/supervisor.routes.ts` – route updated from `ReportsPlaceholderComponent` to `ReportsComponent`

**Key decisions:**
- File name `reports-placeholder.component.*` kept as-is (spec requirement: change class/selector, keep file path)
- `PagedResponse<T>` reused from `user.model.ts` (already has `first`/`last` fields beyond spec)
- `dateRangeValidator` uses standard Angular form validator pattern (returns error object | null)
- URL sync via `router.navigate([], { replaceUrl: true })` on every search/page change
- Filters restored from `ActivatedRoute.snapshot.queryParams` in `ngOnInit`
- `ChangeDetectionStrategy.OnPush` + `signal()`-based state throughout
- Export uses Blob URL + anchor click pattern (no page reload)
- `UserService.getUsers({ page: 0, size: 200, role: 'AGENT' })` used to populate agent dropdown

**Why:** Backend endpoint BE-028 not yet implemented — frontend is UI-ready, awaiting backend.
**How to apply:** When BE-028 lands, no frontend changes needed — the service already points to correct endpoints.
