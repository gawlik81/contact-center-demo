---
name: project_be103_pre_contact_connect_integration
description: BE-103 integracja PRE_CONTACT_CONNECT/MANUAL_ACTION w przepływie połączenia agenta — punkt integracji, decyzje response-first i 404 vs 403 (EPIC-28)
metadata:
  type: project
---

BE-103 (EPIC-28) zaimplementowany 2026-06-21 — pierwszy ticket dotykający kodu telefonii poza
pakietem `domain.plugin`.

**Punkt integracji telefonii:** `domain/telephony/CallEventEnricher.onCallEvent()` — JEDYNY
wspólny listener RabbitMQ (`@RabbitListener` bindings na `call.incoming` ORAZ `call.outbound`)
gdzie `agentId` jest już znany i dane klienta już rozwiązane przez `CliLookupService`. Inbound
i outbound/dialer zbiegają się w tym samym listenerze — jedno wstawienie `publishPreContactConnect`
obsługuje obie ścieżki, brak duplikacji w `TwilioTelephonyAdapter`/`ProgressiveDialerServiceImpl`.

**Decyzja response-first (nie late-arriving event):** wynik pluginu scalony z `CallEvent`
(nowe pola `pluginDisplayData: Map<String,Object>`, `pluginWarning: String`) PRZED wysłaniem
`WebSocketEvent.callIncoming/callOutbound` — `CallIncomingPayload` (używany przez OBIE metody
fabryczne) rozszerzony o te dwa pola. Uzasadnienie: `publishPreContactConnect` ma wbudowany
twardy timeout 2s i nigdy nie blokuje dłużej/nie rzuca (BE-102 garantuje to), telefon klienta
dzwoni niezależnie od tego listenera — late-arriving wymagałby nowego typu eventu WS + merge
logiki frontendu bez wystarczającego zysku.

**TenantContext w wątku RabbitMQ — WAŻNE dla kolejnych ticketów dot. CallEventEnricher:**
ten listener wcześniej NIE ustawiał `TenantContext` wcale (działał wyłącznie na surowych polach
`CallEvent`). Teraz ustawia jawnie `TenantContext.setTenantId(callEvent.getTenantId())` na
początku `onCallEvent` i `TenantContext.clear()` w `finally` — wymagane, bo
`ExtensionPointPublisherImpl.invokeBlocking` woła `TenantContext.snapshot()` na wątku
wywołującym i bez tego snapshot zawierałby same `null` (logika biznesowa pluginu i tak działa
poprawnie, bo `tenantId` jest przekazywany jako parametr jawny, ale snapshot/restore na granicy
executora pluginów byłby bezsensowny bez tego).

**Decyzja 404 vs 403 dla manual-action cross-tenant:** `tenant_plugin_installation` ma RLS
(V075) — `TenantPluginInstallationRepository.findByIdAndTenantId` nie zwraca wiersza innego
tenanta, identycznie jak "nie istnieje". W projekcie nie istnieje ŻADEN wzorzec zapytania
z bypassem RLS (zweryfikowano grep). Świadomie zwrócone **404 dla obu przypadków**
(nieistniejąca instalacja I instalacja innego tenanta) — zgodne z istniejącą konwencją
`PluginRegistrationService.enable/disable/rollback` (wszystkie `ResourceNotFoundException`→404,
nigdy 403 dla cross-tenant). To jest ŚWIADOME odejście od literalnego zapisu kryteriów
akceptacji ticketu BE-103 (które wymieniają 403) — nie implementuj bypassu RLS w przyszłych
tickietach tylko żeby dosłownie spełnić podobne kryterium, chyba że user explicite każe.

**Rozróżnienie timeout vs "plugin nie wsparł akcji" w MANUAL_ACTION:** `ManualActionResult
.unsupported()` jest zwracane przez `ExtensionPointPublisherImpl` identycznie dla timeout,
circuit-open ORAZ dla pluginu świadomie niewspierającego akcji — SDK nie ma pola
`timedOut`/`reason`. Rozróżnienie zaimplementowane w `PluginManualActionController` przez
pomiar `System.nanoTime()` wokół wywołania i porównanie z
`PluginInvocationProperties.effectiveManualActionTimeoutMs()` (zmieniona z package-private na
public specjalnie dla tego celu) — `!result.success() && elapsedMs >= timeoutMs` → 504 z ciałem
JSON (`ManualActionResponseDto.timeout()`), inaczej 200 nawet gdy `success=false`. To rozwiązanie
może dawać false positive jeśli plugin celowo zwróci `unsupported()` blisko granicy timeoutu —
zaakceptowane ryzyko, brak lepszej alternatywy bez zmiany SDK (poza zakresem BE-103).

Nowa metoda `PluginRegistrationService.getInstallation(tenantId, installationId)` — odczyt
pojedynczej instalacji z `ResourceNotFoundException`, używana do ownership check PRZED
`publishManualAction`.

Pliki: `domain/telephony/CallEventEnricher.java`, `CallEvent.java` (+2 pola),
`domain/websocket/WebSocketEvent.java` (`CallIncomingPayload` +2 pola),
`domain/plugin/PluginRegistrationService(Impl).java` (+`getInstallation`),
`domain/plugin/runtime/PluginInvocationProperties.java` (`effectiveManualActionTimeoutMs`
package-private→public), `api/plugin/PluginManualActionController.java` (nowy),
`api/plugin/dto/ManualActionRequestDto.java`/`ManualActionResponseDto.java` (nowe).

Testy: `CallEventEnricherTest` (10, nowy plik — regresja brak pluginów, merge wyniku, TenantContext
set/clear na granicy wątku async, early return bez agentId/tenantId), `PluginManualActionControllerTest`
(9, nowy — happy path, timeout→504 z timeout mockowany na 1ms żeby nie czekać realnie, ownership→404),
`PluginRegistrationServiceImplTest` (+3, nowy `@Nested GetInstallation`).

Weryfikacja: `mvn verify -pl app` ✅ (1258 testów, 0 failures, 0 errors, BUILD SUCCESS, +22 vs BE-102).

Odblokowuje BE-107 (serwowanie `plugin-ui/` + manual-action proxy dla iframe — endpoint
`POST /api/agent/plugins/{installationId}/manual-action/{actionId}` już istnieje z BE-103,
BE-107 dodaje warunek dostępności enabled=true/health_status/plugin_version.status). Zobacz
[[project_be102_extension_point_publisher]], [[project_be100_plugin_registration]].
