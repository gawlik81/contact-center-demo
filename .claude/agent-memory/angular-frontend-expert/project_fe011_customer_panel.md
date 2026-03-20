---
name: Customer panel during contact (FE-011)
description: CustomerPanelComponent, CustomerLookupService, CustomerProfile model – panel boczny z profilem klienta w Agent Desktop
type: project
---

CustomerPanelComponent i CustomerLookupService zaimplementowane w ramach FE-011.

**Why:** Agent potrzebuje danych klienta (imię/nazwisko, historia kontaktów) podczas obsługi kontaktu; BE-011 (GET /api/customers/lookup) w trakcie implementacji – serwis już integruje się z prawdziwym API.

**How to apply:**
- Model: `frontend/src/app/core/models/customer-profile.model.ts` (CustomerProfile, ContactHistoryItem)
- Serwis: `frontend/src/app/features/agent/services/customer-lookup.service.ts` – singleton, Map-cache 5 min, 404→null (z cache), 5xx/sieć→toast + rethrow (aby panel mógł ustawić stan 'error'), metoda `evict(cli)`
- Komponent: `frontend/src/app/features/agent/components/customer-panel/customer-panel.component.ts` – standalone, OnPush, input `cli: string`, 5 stanów (empty/loading/known/unknown/error), pure CSS skeleton (bez Angular Material), ikony SVG inline

**CR-FRONTEND poprawki (2026-03-20):**
- Dodano stan `'error'` do PanelState; CustomerLookupService.lookupByPhone() rethrows dla 5xx zamiast of(null) – panel pokazuje "Nie można pobrać danych klienta (błąd serwera)"
- Przycisk "Zobacz pełny profil" ukryty dla AGENT (tylko SUPERVISOR/ADMIN mają dostęp do /supervisor/**)
- AuthService wstrzyknięty (protected) do sprawdzania roli w szablonie przez `authService.currentRole()`
- Integracja: AgentDesktopComponent – `activeCli` computed (tylko dla PHONE tabów), `<cc-customer-panel>` w `desktop__customer-panel` aside po prawej (280px, ukrywany poniżej 800px)
- Testy: 9 testów Vitest async/await (bez fakeAsync – zoneless Angular 21 nie ma zone-testing)
- Wzorzec testów: `firstValueFrom()` + `HttpTestingController` + `notifySpy.error` mock przez DI
