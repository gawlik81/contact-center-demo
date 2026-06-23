---
name: project_fe099_plugin_panel_host
description: cc-plugin-panel-host (FE-099, EPIC-28) — iframe sandboxed + PluginUiSdk postMessage protocol, decyzje bezpieczeństwa RT-11
metadata:
  type: project
---

FE-099 zrobione (2026-06-23): `frontend/src/app/shared/components/plugin-panel-host/` (`cc-plugin-panel-host`,
selektor `cc-` mimo że większość `shared/components/` używa `app-` — ticket explicite wymagał `cc-`, zachowane)
+ `frontend/src/app/shared/plugin-ui-sdk/plugin-ui-sdk-message.model.ts` (typy + type guardy postMessage,
1:1 zgodne z `backend/.../static/plugin-ui-sdk.js`).

**Why:** RT-11 (ARCHITECTURE.md §11.10/ADR-12) — plugin UI renderowany w sandboxed iframe musi być
odizolowany od JWT agenta i od reszty SPA; jedyny kanał komunikacji to typowany postMessage z walidacją
originy i shape po stronie hosta.

**Decyzje nieoczywiste:**
- `contactId`/`customerId`/`tenantId` to `@Input()` ustawiane przez RODZICA, host nie pobiera ich sam z
  serwisów głębi (prostsze, testowalne, mniej coupling — explicit w specyfikacji ticketu). FE-100 (mount
  w agent desktop) musi przekazać te inputy z `AgentDesktopComponent`/`AuthService` (gdzie `tenantId` siedzi
  w obiekcie user, sprawdzone w `auth.service.ts`).
- `INVOKE_MANUAL_ACTION` woła `HttpClient.post` DIREKTLY w komponencie (nie nowy serwis) — to czysty proxy
  1:1 do jednego istniejącego endpointu `POST /api/agent/plugins/{installationId}/manual-action/{actionId}`
  (BE-103/BE-107), bez dodatkowej logiki biznesowej uzasadniającej osobny serwis.
- Walidacja originy: `event.origin === window.location.origin` (NIE hardkodowany URL) + DODATKOWA walidacja
  `event.source === iframe.contentWindow` (niewymagana explicite w specyfikacji, ale chroni przed
  wiadomościami z innych ramek/komponentów na tej samej stronie, gdy wiele paneli pluginów jest otwartych
  naraz — istotne bo `window.addEventListener` nasłuchuje globalnie, nie per-iframe).
- Odpowiedzi host→plugin wysyłane `iframe.contentWindow.postMessage(response, expectedOrigin)` — nigdy `'*'`
  (asymetria świadoma: SDK pluginu używa `'*'` bo nie zna originy hosta z wnętrza sandboxa bez
  `allow-same-origin`, ale host zna swoją originę i MUSI jej użyć).
- `REQUEST_RESIZE` clamp 100–800px (`MIN_PANEL_HEIGHT_PX`/`MAX_PANEL_HEIGHT_PX` w komponencie) — plugin nie
  może rozdąć panelu w nieskończoność.
- `NotificationService` jest w `core/services/notification.service.ts` (NIE `shared/components/toast/`,
  mimo że `ToastContainerComponent` jest tam) — uwaga przy przyszłych komponentach.
- Backend `ManualActionResponseDto` ma pola `success`/`resultData`/`message`/`error` (nie `resultData` jako
  `unknown` generyczny — `Map<String,Object>` więc TS `Record<string, unknown>`).

**Testy:** 20 testów w `plugin-panel-host.component.spec.ts`, wzorzec `HttpTestingController` +
`provideHttpClient()`/`provideHttpClientTesting()` (zgodny z `agent-calendar.service.spec.ts`), symulacja
`MessageEvent` przez `window.dispatchEvent(new MessageEvent('message', {data, origin, source}))` z
`source: iframe.contentWindow`.

**Nie zweryfikowane w przeglądarce** — jak FE-098, środowisko sandboxa nie ma publikowanego portu backendu
ani narzędzia automatyzacji. Tylko `npm run lint` (czysto) + `npm run build` (czysto, komponent jeszcze nie
wpięty do żadnej strony — mount to zakres FE-100, stąd brak nowego lazy chunka).

Powiązane: [[project_fe098_plugins_page]] (FE-097/FE-098, modele `plugin.model.ts` w `features/plugins/`).
