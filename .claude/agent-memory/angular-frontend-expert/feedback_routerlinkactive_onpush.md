---
name: RouterLinkActive.isActive zawodne przy OnPush i stub routes
description: Nie używaj rla.isActive jako parametru metody do warunkowego renderowania przy OnPush – używaj Router events + toSignal
type: feedback
---

Nie przekazuj `RouterLinkActive.isActive` (`#rla="routerLinkActive"`) jako parametru do metody komponentu w celu warunkowego renderowania elementów przy `ChangeDetectionStrategy.OnPush`.

**Why:** Dwa niezależne mechanizmy awarii:

1. **Timing szablonu**: `#rla` jest deklarowane po atrybucie który go odczytuje (`[attr.aria-label]` w linii 28, `#rla` w linii 30). Angular ewaluuje wyrażenia szablonowe sekwencyjnie w ramach jednego cyklu CD – `rla.isActive` może być odczytane przed aktualizacją przez `RouterLinkActive`.

2. **Stub routes + komponent reużytkowy**: Gdy wiele tras (np. `/admin/users`, `/admin/metrics`) ładuje ten sam komponent co target trasy (`/admin/dashboard`), Angular Router optymalizuje przez reużycie instancji komponentu. `RouterLinkActive` dostaje mylące sygnały o aktywności linku bo komponent nie jest niszczony i tworzony na nowo przy nawigacji między tymi trasami.

**How to apply:** Gdy potrzebna jest informacja o aktualnym URL w logice komponentu przy `OnPush`, zawsze używaj `Router` + `toSignal`:

```ts
private readonly router = inject(Router);

private readonly currentUrl = toSignal(
  this.router.events.pipe(
    filter((e): e is NavigationEnd => e instanceof NavigationEnd),
    map((e) => e.urlAfterRedirects),
  ),
  { initialValue: this.router.url },
);

readonly isTargetRouteActive = computed(() => this.currentUrl() === '/target/route');
```

`urlAfterRedirects` obsługuje redirecty (np. `/admin` → `/admin/dashboard`). `toSignal` z `initialValue: this.router.url` zapewnia poprawną wartość przy pierwszym renderze bez czekania na `NavigationEnd`. Computed signal jest automatycznie reaktywny i bezpieczny przy `OnPush`.
