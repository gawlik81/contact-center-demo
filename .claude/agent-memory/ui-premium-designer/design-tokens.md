---
name: design-tokens
description: CSS custom properties (design tokens) used in this project — both old legacy tokens and new oklch-based system
metadata:
  type: project
---

## New design system tokens (primary — use these)

Defined in `frontend/src/styles.scss` under `:root` / `[data-theme='light']` / `[data-theme='dark']`.

### Backgrounds
- `--bg-app`, `--bg-sidebar`, `--bg-surface`, `--bg-elevated`, `--bg-subtle`, `--bg-input`

### Text
- `--text-1` (primary), `--text-2` (secondary), `--text-3` (muted/label), `--text-muted` (disabled)

### Borders
- `--border-1` (default), `--border-2` (stronger), `--border-strong` (emphasis/hover)

### Accent (brand blue)
- `--accent`, `--accent-soft`, `--accent-text`, `--accent-fg`

### Semantic colors
- `--success`, `--success-soft`, `--success-text`
- `--warning`, `--warning-soft`, `--warning-text`
- `--danger`, `--danger-soft`, `--danger-text`
- `--violet`, `--violet-soft`, `--violet-text`
- `--neutral`, `--neutral-soft`, `--neutral-text`

### Shadows
- `--shadow-sm`, `--shadow-md`, `--shadow-lg`, `--shadow-pop`

### Radii
- `--radius-sm` (6px), `--radius-md` (10px), `--radius-lg` (14px), `--radius-xl` (18px), `--radius-full` (9999px)

### Easings / durations
- `--ease-standard`: `cubic-bezier(0.4, 0, 0.2, 1)`
- `--ease-spring`: `cubic-bezier(0.16, 1, 0.3, 1)`
- `--ease-out`: `cubic-bezier(0, 0, 0.2, 1)`
- `--duration-fast`: 120ms, `--duration-normal`: 200ms, `--duration-slow`: 300ms

## Legacy tokens (avoid in new code — may appear in older components)
- `--color-brand`, `--color-text-primary`, `--color-text-secondary`, `--color-text-muted`
- `--color-surface`, `--color-surface-2`, `--color-surface-3`, `--color-border`
- `--color-success`, `--color-danger`, `--color-warning` (with `-bg` variants)

**Why:** The project migrated to oklch-based tokens but older components still use the legacy set. New work should use new tokens only.

**How to apply:** Always prefer `--bg-surface`, `--text-1`, `--border-1` etc. over `--color-surface`, `--color-text-primary`, `--color-border`. Check `styles.scss` for any token you are unsure about.
