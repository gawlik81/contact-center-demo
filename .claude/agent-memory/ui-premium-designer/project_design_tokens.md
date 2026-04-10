---
name: Design tokens and brand colors
description: Global CSS custom properties, brand palette, and the correct brand blue accent for all modal components
type: project
---

Global tokens are defined in `frontend/src/styles.scss` as `:root` CSS custom properties.

Key values:
- Brand blue: `--color-brand: #1565c0` / `--color-brand-dark: #0d47a1` / `--color-brand-light: #e3f2fd`
- Brand 600: `--color-brand-600: #1a56db` (used for focus rings in global :focus-visible)
- Surface: `--color-surface`, `--color-surface-2: #f8fafc`, `--color-surface-3: #f1f5f9`
- Border: `--color-border: #e2e8f0`
- Text: primary `#1e293b`, secondary `#64748b`, muted `#94a3b8`
- Danger: `--color-danger: #c62828` / `--color-danger-bg: #ffebee`
- Success: `--color-success: #2e7d32` / `--color-success-bg: #e8f5e9`
- Radii: sm=4px, md=6px, lg=10px, xl=12px, full=9999px
- Easing: `--ease-spring: cubic-bezier(0.16,1,0.3,1)`, `--ease-standard: cubic-bezier(0.4,0,0.2,1)`
- Durations: fast=120ms, normal=200ms, slow=300ms

**Canonical modal accent: brand blue `#1565c0`** — all new and refactored modal components use
this as their primary color. Do NOT use indigo `#6366f1` — it was used in an earlier iteration
of schedule-inbound-callback-modal but was corrected for design consistency with all other modals
(customer-create-modal, user-delete-modal, disposition-panel, reschedule-callback-modal).

**How to apply:** Always alias global tokens as local SCSS variables at the top of each component SCSS file:
```scss
$primary: #1565c0;
$primary-dark: #0d47a1;
$primary-light: #e3f2fd;
$primary-ring: rgba(21, 101, 192, 0.18);
```
