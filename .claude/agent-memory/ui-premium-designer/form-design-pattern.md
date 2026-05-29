---
name: form-design-pattern
description: Canonical form field and button classes used across the project — derived from campaign-form as the reference implementation
metadata:
  type: project
---

## Reference implementation
`frontend/src/app/features/supervisor/pages/campaigns/campaign-form/campaign-form.component.scss`

## Form field anatomy
```html
<div class="form-field" [class.form-field--error]="hasError">
  <label class="form-label" for="fieldId">Label <span class="required" aria-hidden="true">*</span></label>
  <input id="fieldId" class="form-input" ... />
  <span class="form-hint">Hint text</span>
  <span class="form-error" role="alert">Error message</span>
</div>
```

## Key classes
- `.form-field` — flex column, gap 0.375rem
- `.form-field--error` — turns border `--danger`, focus shadow `--danger 20%`
- `.form-label` — 0.875rem, font-weight 500, `--text-1`
- `.form-input` / `.form-select` — 1.5px solid `--border-1`, `--radius-sm`, focus: `--accent` + `--accent-soft` glow
- `.form-input--number` — max-width 160px (campaign-form) / 120px (disposition-editor)
- `.form-hint` — 0.8125rem, `--text-2`
- `.form-error` — 0.8125rem, `--danger-text`
- `.form-row` — flex row, gap 1rem; stacks at 480px
- `.required` — color `--danger-text`

## Button classes (from campaign-form)
- `.btn` — base: inline-flex, 0.5625rem 1.125rem, `--radius-sm`, font-weight 500
- `.btn-save` — `--accent` bg, `--accent-fg` text, min-width 100px, hover opacity 0.88
- `.btn-cancel` — transparent, 1.5px border `--border-1`, hover `--neutral-soft`
- `.btn-sm` — 0.3125rem 0.75rem, 0.8125rem font
- `.btn-ghost` — transparent, border `--border-1`, hover `--bg-elevated`
- `.btn-danger` — transparent, border + text `--danger`, hover `--danger-soft` bg
- `.btn-secondary` — transparent, border + text `--accent-text`, hover `--accent-soft`

## Section header pattern (schedule-section legend)
```scss
font-size: 0.875rem;
font-weight: 600;
color: var(--text-2);
text-transform: uppercase;
letter-spacing: 0.04em;
```
Used in campaign-dispositions section-title to match.

**How to apply:** Always copy this exact pattern when adding new form sections. Do NOT use Angular Material form fields — project uses plain inputs/selects with these custom classes.
