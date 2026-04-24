---
name: Tenant Admin Panel — Design System Tokens
description: SCSS variables, color palette, animation conventions, and component patterns used in the tenant management feature modals
type: project
---

## Color Palette (tenant modals — established standard)

- `$brand-blue: #1565c0` — primary action, focus rings, section accent
- `$brand-blue-dark: #0d47a1` — gradient end, hover darken
- `$brand-blue-light: #1976d2` — gradient start for buttons
- `$header-gradient-start / end: #1565c0 → #0d47a1` — modal header gradient
- `$text-primary: #1a202c` — body text (slightly richer than pure #212121)
- `$text-secondary: #64748b` — hints, labels, ghost button text
- `$text-on-dark: #ffffff` — text on gradient header
- `$border-color: #e2e8f0` — default input/section borders
- `$bg-section: #f1f5ff` / `$bg-section-border: #c7d7f5` — "Limity zasobów" section
- `$bg-twilio: #f0f7ff` / `$bg-twilio-border: #93c5fd` — Twilio VoIP section
- `$error: #c62828` — validation errors
- `$error-bg: rgba(#c62828, 0.06)` — error input background tint

## Border Radius

- `$radius-dialog: 12px` — modal outer corners
- `$radius-input: 8px` — form inputs and selects
- `$radius-btn: 8px` — buttons

## Shadows

- Dialog: 3-layer shadow `0 4px 6px… + 0 10px 15px… + 0 20px 48px…`
- Save button: layered `0 1px 2px + 0 4px 8px` with brand-blue-dark rgba

## Animation conventions

- Dialog entry: `dialog-enter` keyframe (fade + translateY(16px) + scale(0.98)), 280ms, `cubic-bezier(0.16, 1, 0.3, 1)`
- Mobile bottom sheet: `sheet-enter` keyframe (translateY(32px)), 320ms, same easing
- Button hover: `translateY(-1px)` + enhanced shadow, 180ms
- Input transitions: border-color + box-shadow + background, 200ms `cubic-bezier(0.4, 0, 0.2, 1)`
- Close button press: `scale(0.92)`, 100ms

## Component patterns

### Modal header
- Full-width gradient background (`linear-gradient(135deg, start → end)`)
- Shine overlay `::after` (linear-gradient 180deg, rgba white 8% → 0)
- Header icon: 36×36px, `rgba(255,255,255,0.15)` bg, 8px radius
- Close button: `rgba(white, 0.12)` bg, hover `rgba(white, 0.22)`, focus outline white

### Form sections (e.g. "Limity zasobów")
- Wrapped in `.form-section` with `$bg-section` tinted background + border
- Section label: 0.75rem, 700 weight, uppercase, letter-spacing 0.06em, brand-blue color
- Section icon: 22×22px, `rgba($brand-blue, 0.12)` bg

### Twilio VoIP section
- `$bg-twilio` background + `box-shadow: inset 3px 0 0 $bg-twilio-border` left accent
- Section header has bottom border separator within the section

### Error messages
- Inline SVG warning icon (info circle path) 14×14px at left of text
- `.form-error` uses `display: inline-flex; align-items: center; gap: 0.3rem`

### Buttons
- Cancel: ghost with `$border-color` border, hover `#f1f5f9` bg
- Save: gradient (`$brand-blue-light → $brand-blue-dark`), lift on hover
- Both: `scale(0.97)` on active state

## Backdrop
- `rgba(15, 23, 42, 0.55)` + `backdrop-filter: blur(4px)`

## Breakpoints
- `< 520px`: bottom sheet layout (fixed bottom, rounded top corners only)

**Why:** Established during tenant modal redesign (2026-04-23). Serves as the reference for future modal/dialog work in admin panel.

**How to apply:** Use these tokens and patterns for any new admin modals, dialogs, or form panels to maintain visual consistency.
