---
name: Admin Dashboard metrics RT (FE-007)
description: AdminDashboardComponent KPI cards, AdminMetricsService architecture (BehaviorSubject singleton, refCount:false), sidenav alert badge
type: project
---

## AdminMetricsService – przebudowana architektura (po audycie)

Serwis zbudowany jako **singleton state store** oparty na `BehaviorSubject`:

- `_metrics$: BehaviorSubject<GlobalMetrics>` – zawsze trzyma ostatnią wartość; nowe subskrypcje dostają ją natychmiast bez HTTP
- `_loading$: BehaviorSubject<boolean>` – true tylko podczas pierwszego fetcha
- `_error$: BehaviorSubject<boolean>` – true gdy ostatni poll zwrócił błąd
- `_poll$` uruchamiany w konstruktorze: `timer(0, 30s) → switchMap(http.get) → catchError(EMPTY) → shareReplay({refCount:false})`
- **`refCount:false`** – timer żyje przez cały czas istnienia serwisu, nawigacja nie restartuje pollingu
- Błędy obsługiwane **wewnątrz** `catchError` w inner observable – nie terminują outer timer stream
- Publiczne API: `globalMetrics$`, `loading$`, `error$`, `alertCount$` (derived count)
- Usunięto `getGlobalMetrics()` – duplikował HTTP poza shareReplay

**Why:** `refCount:true` + `catchError` w konsumencie powodował trwałe zatrzymanie pollingu po pierwszym błędzie HTTP (subscriber kończył się, refCount→0, timer odsubskrybowany).

## AdminDashboardComponent

Subskrybuje trzy oddzielne strumienie serwisu (`loading$`, `error$`, `globalMetrics$`) przez `takeUntilDestroyed`. Toast tylko przy przejściu error `false→true` (nie na każdym ticku). Signals: `loading`, `metrics`, `hasError`. Computed: `hasAlerts`, `tenants`, `hasTenants`.

## SidenavComponent – badge alertów

Tylko dla roli ADMIN. Subskrybuje `AdminMetricsService.alertCount$` przez `takeUntilDestroyed`. Signal `alertCount`. Metoda `showAlertBadge(item)` sprawdza rolę + route `/admin/dashboard` + count > 0. Limit wyświetlania: 99+. ARIA: `aria-label` linku zawiera liczbę alertów gdy badge widoczny. Styl `.sidenav__alert-badge` w sidenav.component.scss (czerwony pill 20px, min-width 20px, font 0.6875rem).

## eslint.config.js

Prefix selektorów rozszerzony do `['app', 'cc']` – `app` dla feature components, `cc` dla shared/shell components. Preistniejący dług techniczny naprawiony przy okazji audytu.

## admin.routes.ts – uwaga

Trasy `/admin/users` i `/admin/metrics` wskazują na `AdminDashboardComponent` jako stub (brak dedykowanych komponentów). To nie powoduje problemu z pollingiem po przebudowie serwisu (BehaviorSubject). Stub do zastąpienia przy implementacji tych widoków.

## Modele

- `GlobalMetrics` – totalActiveTenants, totalAgentsOnline, systemAlerts: string[], tenants: TenantMetricsSummary[], generatedAt
- `TenantMetricsSummary` – id, name, status, agentsOnline, agentsTotal
- `TenantMetricsDetail` – jak Summary + cpuUsage, memoryUsage, activeContacts

## Inne informacje (bez zmian)

- ADMIN guard na poziomie `app.routes.ts`, nie powtórzony w admin.routes.ts
- CSS progress bars dla agent utilization: zielony <50%, pomarańczowy 50-79%, czerwony >=80%
- KPI cards: totalActiveTenants (niebieski), totalAgentsOnline (zielony), alertCount (czerwony/szary)
- BE endpoint: GET `/api/admin/metrics`, GET `/api/admin/metrics/tenants/{id}`
