---
name: App Shell infrastructure (FE-005)
description: AppShellComponent, TopNavbar, Sidenav, Breadcrumbs – layout system for all protected routes
type: project
---

## Key files

- `shared/components/app-shell/` – AppShellComponent (main layout, CSS Flex, skip-link)
- `shared/components/top-navbar/` – TopNavbarComponent (hamburger toggle, user info, role badge, logout)
- `shared/components/sidenav/` – SidenavComponent (context-aware per role, SVG inline icons, responsive)
- `shared/components/breadcrumbs/` – BreadcrumbsComponent (reads `data.breadcrumb` from route tree)
- `core/services/breadcrumb.service.ts` – BreadcrumbService (subscribes to NavigationEnd, builds breadcrumb array via signal)

## Architecture decisions

- AppShellComponent is used inside each feature shell (AdminShellComponent, SupervisorShellComponent, AgentShellComponent) via `<cc-app-shell />`
- Feature shells are thin wrappers: import only AppShellComponent, no logic
- AppShellComponent contains its own `<router-outlet>` – feature shell route is the parent, child routes render inside shell
- No Angular Material – pure SCSS + Angular signals

## Responsive breakpoints

- Mobile (<1024px): sidenav hidden, slides in as full-height overlay with backdrop
- Tablet (1024-1279px): sidenav hidden, slides in as drawer below navbar (top: 60px)
- Desktop (>=1280px): sidenav always visible (position: relative, 240px wide), main content has `margin-left: 240px`

## Sidenav state management

- `sidenavOpen = signal(false)` in AppShellComponent
- On init: if `window.innerWidth >= 1280` → set to true
- @HostListener('window:resize') re-checks breakpoint
- @HostListener('document:keydown.escape') closes drawer on mobile/tablet
- SidenavComponent receives `isOpen` as input(), emits `closeRequest` output()

## NavItem data

Defined as const arrays (ADMIN_NAV, SUPERVISOR_NAV, AGENT_NAV) inside sidenav.component.ts.
SidenavComponent uses `computed()` based on `auth.currentRole()` to return the correct array.

## Breadcrumbs

- BreadcrumbService uses `takeUntilDestroyed()` – no manual unsubscription needed
- Routes must have `data: { breadcrumb: 'Label' }` to appear in breadcrumb trail
- BreadcrumbsComponent uses `@if` + `@for` control flow, last item gets `aria-current="page"`

## WCAG

- Skip link `.skip-link` (absolute, moves to top on :focus)
- `role="banner"` on navbar, `role="navigation"` + `aria-label` on sidenav
- `aria-expanded` on hamburger button (bound to sidenavOpen signal)
- `aria-current="page"` on active sidenav link (via `#rla="routerLinkActive"`)
- Focus ring via `:focus-visible` on all interactive elements

## Known TypeScript gotcha

`@HostListener('window:resize', ['$event.target.innerWidth'])` fails compilation – `EventTarget` does not have `innerWidth`.
Use `@HostListener('window:resize')` and read `window.innerWidth` inside the method body.

## Why

FE-005 provides the shared layout shell for all three roles. All future protected feature components render inside AppShellComponent's router-outlet.
