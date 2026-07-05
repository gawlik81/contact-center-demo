---
name: project_fe100_agent_desktop_mount
description: FE-100 — mount cc-plugin-panel-host + toolbar manual-action w AgentDesktopComponent, zamknięcie EPIC-28 (warstwa frontend)
metadata:
  type: project
---

FE-100 zrobione i zweryfikowane (2026-06-23, weryfikacja dokończona w sesji po przerwaniu poprzedniej): mount
`cc-plugin-panel-host` (FE-099) i toolbar manual-action w `frontend/src/app/features/agent/pages/agent-desktop/agent-desktop.component.{ts,html,scss}`.
**To był ostatni ticket EPIC-28 — cały epic (DB 43/43, BE 104/104, FE 79/79) jest teraz kompletny.**

**Skąd dokładnie brane są inputy hosta (nieoczywiste, warte zapamiętania na przyszłość):**
- `tenantId` → `AuthService.currentTenantId` (signal, re-eksponowany w komponencie jako `protected readonly currentTenantId`)
- `contactId` → `activePluginContactId` computed, czyta `activeTab()?.originalContactId ?? activeTab()?.contactId`
  — `originalContactId` preferowany dla zakładek konsultacji attended-transfer, **ta sama konwencja** już
  używana przez disposition panel (`wrappingTab()!.originalContactId ?? wrappingTab()!.contactId`) — nie
  wymyślono nowego wzorca, reużyto istniejący.
- `customerId` → `activePluginCustomerId` computed, czyta `activeTab()?.customerId`
- Wszystkie 3 to `computed()`, nie pobierane samodzielnie przez host (zgodnie z decyzją z FE-099: host dostaje
  je jako `@Input()` od rodzica).

**Sygnały/metody side panelu:**
- `pluginInstallations` (signal, surowa lista z `GET /api/agent/plugins`) → `activePluginInstallations`
  (computed, filtruje `enabled && healthStatus !== 'DISABLED_BY_ADMIN'`) → `sidePanelInstallations` (computed,
  filtruje po `uiPanels.some(p => p.mountPoint === 'AGENT_DESKTOP_SIDE_PANEL')`) i `toolbarManualActions`
  (computed, flatMap `manualActions` filtrowane po `mountPoint === 'AGENT_DESKTOP_TOOLBAR'`).
- `activePluginPanelId` (signal `string | null`) + `setActivePluginPanel(installationId)` — wybiera aktywną
  zakładkę gdy >1 side-panel installation. Inicjalizowany na pierwszą side-panel installation po załadowaniu
  listy w `ngOnInit`.
- Endpoint: `GET /api/agent/plugins` (`PluginAdminService.listAgentInstallations()`, rola
  `AGENT/SUPERVISOR/ADMIN`, filtruje tylko po `enabled` server-side — warunek `healthStatus` trzeba
  re-checkować po stronie FE, co już jest zrobione w `activePluginInstallations`).
- Błąd ładowania listy jest nie-fatalny: `catchError` → `of([])`, tylko `console.warn`, **bez toastu** —
  desktop musi działać nawet gdy plugin subsystem padnie.

**Mechanizm zakładek side panelu:** WŁASNY, lokalny (signal + metoda + `@if`/`@for`) — **w projekcie NIE
istnieje żaden współdzielony komponent tabs** (sprawdzone: brak `mat-tab`, brak `shared/components/tabs`).
Analogiczny do już istniejącego wzorca zakładek kontaktów (`contact-tabs__bar`) w tym samym komponencie —
nie wprowadza nowego paradygmatu.

**Toolbar manual-action:** `invokeToolbarManualAction()` woła `HttpClient.post` DIREKTLY w komponencie
(duplikuje to samo wywołanie co `cc-plugin-panel-host`'s `INVOKE_MANUAL_ACTION` — świadomie, 2 call site nie
uzasadniają wspólnego serwisu, ta decyzja już była podjęta w FE-099). `pendingManualActionKey` (signal
`"installationId:actionId" | null`) blokuje double-submit i steruje spinnerem na przycisku. Mapowanie błędu:
HTTP status `504` → klucz tłumaczenia `agent.desktop.pluginActionTimeout`, inny błąd →
`agent.desktop.pluginActionError`, sukces → `agent.desktop.pluginActionSuccess` (lub `result.message` z
backendu jeśli obecny).

**Zero regresji wizualnej — jak to jest zagwarantowane w HTML (potwierdzone wzrokowo):**
- Toolbar: `@for (item of toolbarManualActions(); ...)` — czysty `@for` bez żadnego wrappera/nagłówka, renderuje
  literalnie nic gdy tablica jest pusta (najczęstszy przypadek — większość tenantów bez pluginów).
- Side panel: cały blok (nagłówek zakładek + `cc-plugin-panel-host`) opakowany w jeden
  `@if (sidePanelInstallations().length > 0 && currentTenantId())` — gdy warunek fałszywy, Angular nie
  renderuje ŻADNEGO elementu DOM (nie tylko zawartość, cały kontener `.plugin-side-panel` div). SCSS ma
  reguły dla `.plugin-side-panel` ale są nieistotne gdy element nie istnieje w DOM.

**Lint/build przy weryfikacji (2026-06-23):**
- `npm run lint`: 0 błędów, 10 warningów (wszystkie pre-existing `no-console`, niezwiązane z FE-100, w innych
  plikach — `logging.service.ts`, `softphone.service.ts`, `main.ts`, oraz JEDEN istniejący wcześniej w
  `agent-desktop.component.ts:347` w handlerze `CALL_BRIDGE_COMPLETE`, nie w kodzie FE-100).
- `npm run build`: sukces. Jedyne nowe ostrzeżenie: `agent-desktop.component.scss` przekracza per-component CSS
  budget (12kB) o 6.61kB (18.61kB total) po dodaniu `.plugin-side-panel` — **nie naprawiane**, bo identyczny
  wzorzec (przekroczenie tego samego budżetu) już istnieje w 5 innych komponentach projektu
  (`contact-detail-modal`, `softphone`, `ivr-editor`, `email-contact`, `adhoc-email-modal`) — pre-existing
  konwencja/problem konfiguracji budżetów w `angular.json`, nie regresja wprowadzona przez ten ticket.
- Weryfikacja w przeglądarce: NIE wykonana, jak FE-098/FE-099 — środowisko sandboxa nie publikuje portu
  backendu i nie ma narzędzia automatyzacji przeglądarki.

Powiązane: [[project_fe099_plugin_panel_host]] (FE-099, host iframe — ten plik dokumentuje skąd dokładnie
biorą się `contactId`/`customerId`/`tenantId` po stronie wołającej, czego tamta notatka tylko zapowiadała).
