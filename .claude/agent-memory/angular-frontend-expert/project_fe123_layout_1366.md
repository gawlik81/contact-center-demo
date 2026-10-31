---
name: project-fe123-layout-1366
description: FE-123 - skala breakpointow w _breakpoints.scss, tokeny wysokosci chrome, rezerwa dla bannera ACW, domyslny rail sidenavu agenta
metadata:
  type: project
---

Breakpointy: `frontend/src/app/shared/styles/_breakpoints.scss` (`@use ... as bp; @include bp.down(sm)`, `bp.compact-height`); literal px w `@media` w agent/shell zabroniony. Tokeny chrome w `styles.scss` (`--navbar-h`, `--desktop-toolbar-h`, `--desktop-tabbar-h`, kolumny `--desktop-sidebar-w`/`--desktop-customer-w`). Baner ACW ustawia klase `has-acw-banner` na `<html>` -> `--shell-reserve-bottom` -> padding-bottom `.shell__content`. Sidenav: domyslny rail tylko dla AGENT <=1440px, zapis localStorage dopiero w `toggleCollapsed()` (efekt tylko emituje).

**Why:** `var()` nie dziala w `@media`; sidenav zapisywal stan w efekcie, co zamrazalo domyslna wartosc.
**How to apply:** nowy breakpoint = nowy krok w skali + DESIGN.md + design.json. Wspolny top-navbar: kompaktowanie tylko przez `compact-height`/`down(lg)`.
