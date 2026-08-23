---
name: project_epic29_data_retention
description: EPIC-29 data retention page (data-retention.component) — status, structure, and conventions established across FE-103..FE-107
metadata:
  type: project
---

EPIC-29 "Partycjonowanie i retencja danych" builds ONE page incrementally across tickets:
`frontend/src/app/features/supervisor/pages/settings/data-retention/data-retention.component.{ts,html,scss}`
(ADMIN-only, guarded by roleGuard in routing, not in the component itself).

Sections added so far, each ticket appends a new `<section class="dr-section">` to the SAME
component rather than creating new pages/routes — mirrors the EPIC-28 pattern (FE-098 →
FE-101/102 on plugins-page):
- Sekcja 1 (FE-104): policy table (4 rows, one per RetentionDataCategory), inline edit + save.
- Sekcja 2 (FE-105): "dane kwalifikujące się do usunięcia" summary card grid, reads ONLY from
  cache (`GET .../summary`), never computes live.
- Sekcja 3 (FE-106): "Usuń teraz" button per card → `purge-confirm-modal/` (purely presentational
  sub-component) → `triggerPurge` (202 async) → polling `getPurgeStatus` to terminal state. All
  trigger/polling logic lives in the parent component, not the modal.
- Sekcja 4 (FE-107, done 2026-08-13): paginated purge history table (`GET .../history`, backend
  ALWAYS sorts desc by startedAt, no sort control in UI). historyPageSize=10 (smaller than the
  20 used in UserListComponent — this is an operations log, not a business-entity list).

**Why this matters for future EPIC-29 tickets (FE-108, FE-109, etc.):** default to adding a new
section to this same file unless the ticket explicitly asks for a new page — simplicity over
premature abstraction is the established norm here, and TASKS-FRONTEND.md ticket text sometimes
gives a different/wrong path than the actual convention, so always verify the real location in
code first.

**CSS class prefix on this page:** everything uses `dr-*` (not the `pp-*` prefix from
plugins-page, not bare classes like `.pagination`/`.empty-state` from user-list) — when porting a
pattern from another page (pagination mechanism from UserListComponent, badge-per-status pattern
from PluginsPageComponent's `healthBadgeClass`), replicate the MECHANISM but rename classes to
`dr-*` for visual/naming consistency within this page.

**Status color tokens available in `frontend/src/styles.scss`:** `--success-soft/-text`,
`--warning-soft/-text`, `--danger-soft/-text`, `--accent-soft/-text`, `--violet-soft/-text`,
`--neutral-soft/-text`. There is NO dedicated "info" color. For a 3-state badge where one state
means "in progress" (e.g. purge history RUNNING/COMPLETED/FAILED), `--accent-soft/-text` (blue)
was chosen over `--warning-soft` for the "in progress" state — warning implies a problem, and
reusing `--success-soft` for both RUNNING and COMPLETED would make two different states look
identical, defeating the "color per state" requirement. See `historyStatusBadgeClass()` in
data-retention.component.ts for the precedent.

i18n: 4 files must stay in lockstep — `frontend/public/i18n/{pl,en,de,uk}.json`. A key missing in
just one language makes Transloco render the raw key instead of text (real bug hit and fixed
during FE-104 work in this same page). Always diff the key sets across all 4 files after editing
i18n JSON (e.g. `python3 -c "... set(...) == set(...) ..."` per language) before considering the
task done.
