---
name: Project Design System State
description: Global CSS custom properties and design tokens now in styles.scss — added 2026-03-27
type: project
---

# Stan design systemu

**UPDATE 2026-03-27:** `styles.scss` teraz zawiera kompletne CSS custom properties i `prefers-reduced-motion` globalnie.

`frontend/src/styles.scss` zawiera:
- CSS custom properties: `--color-brand`, `--color-brand-600` (#1a56db), `--color-text-primary/secondary/muted`
- Surface tokens: `--color-surface`, `--color-surface-2` (#f8fafc), `--color-surface-3` (#f1f5f9), `--color-border` (#e2e8f0)
- Radius: `--radius-sm/md/lg/xl/full`
- Shadows: `--shadow-xs/sm/md/lg`
- Easing: `--ease-standard`, `--ease-spring`, `--ease-out` (cubic-bezier)
- Duration: `--duration-fast` (120ms), `--duration-normal` (200ms), `--duration-slow` (300ms)
- `prefers-reduced-motion` globalny reset (nie potrzeba powielać w komponentach)
- Globalny `:focus-visible` — 2px solid `--color-brand-600`, offset 3px

**Why:** Analiza 25 komponentów SCSS przeprowadzona 2026-03-26 ujawniła brak centralnego design systemu. Dodano globalne tokeny.

Każdy komponent SCSS nadal deklaruje lokalne `$zmienne` (SCSS aliases) dla autocomplete, ale ich wartości powinny odpowiadać tokenom globalnym.

## Zmienne SCSS zduplikowane w wielu plikach
- `$brand-blue: #1565c0` — pojawia się w: login, admin-dashboard, tenant-list, queue-list, user-list
- `$text-primary: #212121` lub `#1e293b` — różne wartości w różnych komponentach (niespójność)
- `$border-color: #e2e8f0` — spójne w większości
- `$radius: 6px` — spójne, ale brak wyższych wariantów jako zmiennych

## Niespójne border-radius
- `6px` — queue-list, tenant-list, login inputs
- `8px` — agent-desktop items, softphone
- `10px` — admin-dashboard kpi-cards
- `12px` — auth-card, IVR modal

## Brak tokenów CSS custom properties
Projekt nie używa CSS `--custom-properties` (poza IVR editor który używa kilku `var(--color-*)` lokalnie).

## How to apply
Przed każdą zmianą stylu: sprawdź czy dany komponent ma lokalne `$zmienne` które mogą kolidować z globalnymi. Przy okazji refaktorów dodawaj `var(--token)` zamiast hardcoded wartości. Plik `styles.scss` jest dobrym miejscem na globalne tokeny.

## Angular Material

Angular Material NIE jest zainstalowany w projekcie (brak w package.json). Nie uzywac `MatTooltipModule` ani zadnych importow z `@angular/material/*`. Zamiast tego stosowac:
- Natywny atrybut `title` dla dostepnosci
- CSS tooltip przez `data-tooltip` + `::after` pseudo-element (wzorzec uzywany w sidenav)

## Sidenav Collapsible Pattern (zaimplementowany 2026-03-27)

- `isCollapsed` signal w SidenavComponent, zapisywany do localStorage pod kluczem `cc_sidenav_collapsed`
- Output `collapsedChange` emituje stan do AppShellComponent
- CSS class `sidenav--collapsed` na `<nav>` — width przechodzi 240px → 60px przez `transition: width 0.25s cubic-bezier(0.4,0,0.2,1)`
- AppShell uzywa flexbox — sidenav jest `position: relative; flex-shrink: 0` wiec flex automatycznie dostosowuje szerokosc content area (nie potrzeba margin-left)
- BLAD NAPRAWIONY: stary app-shell.component.scss mial `margin-left: $sidenav-width` na desktopie co powodowalo podwojne przesuniecie (sidenav juz zajmowal miejsce w flex flow). Usunieto.

## AppShell layout chain (stan po 2026-03-27)

`shell` (height: 100vh, flex column)
  → `shell__navbar` (flex-shrink: 0)
  → `shell__body` (flex: 1, overflow: hidden, display: flex)
    → `shell__main` (flex: 1, display: flex column, overflow: hidden)
      → `shell__breadcrumbs` (flex-shrink: 0, min-height: 36px)
      → `shell__content` (flex: 1, min-height: 0, overflow-y: auto, padding: 1.5rem, **display: flex; flex-direction: column**)

`shell__content` jest teraz `display: flex; flex-direction: column` — umożliwia potomkom z `flex: 1` wypełnienie całej dostępnej wysokości.

## Agent desktop full-bleed pattern

`:host` agenta ma: `flex: 1; margin: -1.5rem; width: calc(100% + 3rem); min-height: 0; overflow: hidden`.
To ucieka z paddingu `shell__content` i wypełnia całą przestrzeń edge-to-edge.
`.desktop` wewnątrz ma `flex: 1; min-height: 0`.

Wzorzec do ponownego użycia dla każdego widoku wymagającego full-bleed (bez 1.5rem paddingu).

## Email 2-kolumnowy layout (proporcje)

Thread (lewa kolumna): `flex: 0 0 57%` — agenci czytają kontekst, potrzebują więcej miejsca.
Reply (prawa kolumna): `flex: 1` — compose area.
Separator: `border-right: 2px solid $border` na `.email-thread`.
