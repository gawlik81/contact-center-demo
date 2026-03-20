---
name: Customer panel during contact (FE-011)
description: CustomerPanelComponent, CustomerLookupService, CustomerProfile model – panel boczny z profilem klienta w Agent Desktop
type: project
---

CustomerPanelComponent i CustomerLookupService zaimplementowane w ramach FE-011.

**Why:** Agent potrzebuje danych klienta (imię/nazwisko, historia kontaktów) podczas obsługi kontaktu; BE-011 (GET /api/customers/lookup) w trakcie implementacji – serwis już integruje się z prawdziwym API.

**How to apply:**
- Model: `frontend/src/app/core/models/customer-profile.model.ts` (CustomerProfile, ContactHistoryItem)
- Serwis: `frontend/src/app/features/agent/services/customer-lookup.service.ts` – singleton, Map-cache 5 min, 404→null, non-404→toast error, metoda `evict(cli)`
- Komponent: `frontend/src/app/features/agent/components/customer-panel/customer-panel.component.ts` – standalone, OnPush, input `cli: string`, 4 stany (empty/loading/known/unknown), pure CSS skeleton (bez Angular Material), ikony SVG inline
- Integracja: AgentDesktopComponent – `activeCli` computed (tylko dla PHONE tabów), `<cc-customer-panel>` w `desktop__customer-panel` aside po prawej (280px, ukrywany poniżej 800px)
- Testy: 9 testów Vitest async/await (bez fakeAsync – zoneless Angular 21 nie ma zone-testing)
- Wzorzec testów: `firstValueFrom()` + `HttpTestingController` + `notifySpy.error` mock przez DI
