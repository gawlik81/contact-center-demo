---
name: fe116-fe118-token-migration-review
description: EPIC-32 FE-116/FE-118 review (2026-10-23) - script-based border/token migrations leave hover-state and misclassification gaps; check these on any mass SCSS replace
metadata:
  type: project
---

Review 2026-10-23 scored 3.5/5. Pattern worth re-checking in every scripted SCSS migration here:
- Replacing a base `border` with a darker token but leaving `:hover { border-color: var(--border-strong|--border-2) }` makes hover LIGHTER than rest (border-control 3.3-3.9:1 vs strong ~1.9:1).
- Scripts classify by selector name: non-interactive `div` chips (navbar__user-chip) get hit, real buttons with variant names (`.btn-outline`, `.theme-switcher`) get missed. Check html element type.
- Pattern grep for `--danger-text` as background + `#fff` text was missed by the --on-danger script (dark theme ~1.5:1).
- `:focus-visible` added on non-focusable `<li role=option>` (no tabindex) is dead CSS.
- `--on-warning`, status tone mapping missing from DESIGN.md/design.json (sync gap recurs, see FE-115 review).
**How to apply:** after any bulk token swap, grep hover/focus/disabled siblings and verify element types; verify top-navbar KPI strip width (overflow:hidden clips silently).
