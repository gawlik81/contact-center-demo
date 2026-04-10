---
name: Premium UI patterns established in the project
description: Reusable visual patterns, animation conventions, and component techniques already validated in the codebase
type: project
---

## Dialog / Modal pattern (`<dialog>` native element)

- Native `<dialog>` element with `showModal()` / `close()` called via `viewChild.required<ElementRef<HTMLDialogElement>>`
- Backdrop: fixed inset, `rgba(15,23,42,0.6)` + `backdrop-filter: blur(4px)`, fade-in animation
- Panel: `border-radius: 16px`, multi-layer box-shadow, spring animation `translateY(16px) scale(0.96)` → identity
- Left accent bar on header: `position:absolute; left:0; width:3px; background: linear-gradient(indigo)`
- Header icon: `border-radius: 10px`, gradient background, colored box-shadow for depth
- Close button in header: ghost style, `scale(0.92)` on active

## Form field patterns

- Label: small (0.8125rem), weight 600, with an inline SVG icon colored `$accent`
- Input wrapper: flex container holds prefix + input; border/shadow on the wrapper, not the inner input
- Phone prefix: `+48` hardcoded badge separated by a right border, accent-colored, `user-select: none`
- Focus state: `border-color: $accent` + `box-shadow: 0 0 0 3px $accent-ring`
- Hover (unfocused): `border-color: #cbd5e1`
- Error state: red border + ring; inline hint with icon SVG + `role="alert"`
- Datetime-local: overlay icon via CSS `mask-image`, transparent calendar indicator via `::-webkit-calendar-picker-indicator { opacity:0 }`
- Notes textarea: warning state (orange) at 400+ chars, `--near-limit` modifier class
- Section divider between field groups: flex row with `::before/::after` borders and uppercase label

## Button patterns

- Primary: `linear-gradient(135deg, #6366f1 0%, #4f46e5 100%)`, colored box-shadow, `translateY(-1px)` on hover
- Ghost/cancel: transparent bg, `border: 1.5px solid $color-border`, hover fills `$color-surface-2`
- Loading state: `--loading` modifier; inline spinner `border-top-color: #fff` spinning 0.65s linear
- Active press: `scale(0.97)` on all buttons

## Animations

- `panel-in`: `translateY(16px) scale(0.96)` → identity, 320ms `cubic-bezier(0.16,1,0.3,1)` (spring)
- `backdrop-in`: opacity 0→1, 220ms standard ease
- `error-shake`: multi-step translateX oscillation, 500ms
- `spin`: 0.65s linear infinite for button spinner
- All animations wrapped in `@media (prefers-reduced-motion: reduce)` override block
