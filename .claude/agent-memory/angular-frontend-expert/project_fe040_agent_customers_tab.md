---
name: Agent customer search tab (FE-040)
description: AgentCustomersTabComponent – strona /agent/customers z wyszukiwaniem klientów, debounce 300ms, skeleton loader, drawer ze szczegółami
type: project
---

Zakładka „Klienci" dla agenta zaimplementowana jako osobna strona `/agent/customers` (nie zakładka ContactTabStore).

**Pliki:**
- `frontend/src/app/features/agent/models/customer-search.model.ts` – `CustomerSummary` + `CustomerSearchResponse`
- `frontend/src/app/features/agent/services/customer-search.service.ts` – GET /api/customers?search=...&limit=20
- `frontend/src/app/features/agent/pages/customers/agent-customers-tab.component.ts/html/scss` – główna strona
- `frontend/src/app/features/agent/pages/customers/agent-customer-card.component.ts` – karta klienta (inline template+styles)

**Kluczone decyzje:**
- Debounce 300ms przez `Subject` + `debounceTime` (nie `toObservable(signal)`)
- `CustomerSummary` to własny interfejs agenta (nie re-export z supervisora) bo endpoint BE może zwracać inne DTO
- Drawer ze szczegółami klienta wewnątrz komponentu (position: absolute), nie osobna trasa
- `@Output() scheduleCallback` emituje `CustomerSummary` – modal FE-041 dołączy w następnym kroku
- Placeholder `AgentCustomersPlaceholderComponent` pozostawiony (nieużywany przez router)

**Why:** Routing agenta (`AGENT_ROUTES`) już miał trasę `/agent/customers` z placeholderem – wystarczyło podmienić `loadComponent`.

**How to apply:** Przy FE-041 (modal oddzwonienia) podłączyć do `(scheduleCallback)` w szablonie nadrzędnym lub użyć serwisu.
