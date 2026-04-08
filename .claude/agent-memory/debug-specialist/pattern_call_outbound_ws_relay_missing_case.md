---
name: RabbitToWebSocketRelay — brak case call.outbound w switchu
description: call.outbound trafia do "Nieobsługiwany routing key" — softphone agenta nie dzwoni przy połączeniu wychodzącym kampanijnym
type: project
---

`RabbitToWebSocketRelay.onCallEvent()` miał switch obsługujący tylko `call.incoming`, `call.answered`, `call.hangup`. Brak case `call.outbound` skutkował cichym pominięciem eventu — agent nie dostawał powiadomienia WebSocket, softphone nie dzwonił.

**Zaimplementowane fixy (2026-04-07):**

Backend `WebSocketEvent`:
- Dodano stałą `TYPE_CALL_OUTBOUND = "CALL_OUTBOUND"`
- Dodano metodę fabryczną `callOutbound(CallEvent)` — używa `CallIncomingPayload.from()` (ta sama struktura payloadu)

Backend `RabbitToWebSocketRelay`:
- Dodano case `"call.outbound"` → `handleCallOutbound(callEvent)`
- `handleCallOutbound` wysyła unicast do agenta + broadcast do supervisorów

Frontend `ws-event.model.ts`:
- Dodano `'CALL_OUTBOUND'` do unii `WsEventType`
- Dodano interfejs `CallOutboundPayload` (identyczny z `CallIncomingPayload`)

Frontend `agent-desktop.component.ts`:
- Dodano subskrypcję na `CALL_OUTBOUND`: otwiera zakładkę + `softphoneService.incomingCall()` + toast "Polaczenie wychodzace do..."

**Why:** `CALL_OUTBOUND` jest generowany przez `TelephonyEventPublisher.publishOutbound()` wywoływane z `TwilioTelephonyAdapter.initiateCall()`. Routing key = `call.outbound` (z `EventType.CALL_OUTBOUND.toRoutingKeySuffix()` → `"outbound"`).

**How to apply:** Gdy dodawane są nowe typy `CallEvent.EventType` — zawsze sprawdzić switch w `RabbitToWebSocketRelay`, stałe w `WebSocketEvent` i unię `WsEventType` w modelu frontendowym.
