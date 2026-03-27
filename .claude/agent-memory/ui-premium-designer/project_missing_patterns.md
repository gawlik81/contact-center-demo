---
name: Missing Premium UI Patterns
description: Wzorce premium UI których brakuje w projekcie — prefers-reduced-motion, Angular Animations, cubic-bezier, page transitions
type: project
---

# Brakujące wzorce premium (stan 2026-03-26)

**Why:** Analiza 25 komponentów — projekt jest solidny technicznie ale brakuje kluczowych elementów premium UI.

## Krytyczne braki

### 1. prefers-reduced-motion — NAPRAWIONE 2026-03-27
`styles.scss` zawiera globalny reset `prefers-reduced-motion`. Nie trzeba go powielać w komponentach.

### 2. Angular Animations API — NIEUŻYWANE
`@angular/animations` nie jest importowane w żadnym analizowanym komponencie.
Wszystkie przejścia to CSS transitions na :hover.
Brak: page transitions, staggered lists, enter/leave animations.

### 3. cubic-bezier easing — BRAK
Wszystkie transitions używają `ease` lub `linear`.
Standard premium: `cubic-bezier(0.4, 0, 0.2, 1)` (Material) lub `cubic-bezier(0.16, 1, 0.3, 1)` (sprężysty).

## Dobre wzorce już w projekcie (referencja)

### Skeleton loaders
Zaimplementowane poprawnie w: admin-dashboard, tenant-list, queue-list, user-list.
Pattern: `.skeleton-wrapper > .skeleton-row > .skeleton-cell` z shimmer animation.
Użyć jako wzorzec dla nowych komponentów.

### Softphone — najlepszy komponent
`/features/agent/components/softphone/softphone.component.scss`
- Poprawny scale(1.07) hover + scale(0.96) active na przyciskach
- State-based gradients (ringing/active/hold/ended)
- Pulse animation na avatar
- Box-shadow z kolorowym tintowaniem
Używać jako referencję do innych przycisków akcji.

### Status pill button (agent-desktop)
`.status-btn` — border: 2px solid currentColor, border-radius: 9999px, font-weight: 600.
Dobry wzorzec dla status indicators.

## How to apply
Przy każdym nowym komponencie: (1) dodaj prefers-reduced-motion w SCSS, (2) używaj cubic-bezier zamiast ease, (3) rozważ czy potrzebna jest Angular Animation dla enter/leave.
