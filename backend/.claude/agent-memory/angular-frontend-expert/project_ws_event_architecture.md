---
name: project_ws_event_architecture
description: WS event routing for Agent persona — which service handles which event type, dispatch pattern
metadata:
  type: project
---

# WebSocket Event Architecture – Agent persona

## Core services

- `WebSocketService` (`core/services/websocket.service.ts`) — STOMP client, emits all events on `events$` (Subject). Subscribes to `/topic/user/{userId}/events` (unicast) and `/topic/tenant/{tenantId}/agents` (broadcast).
- `IncomingCallAlertService` (`agent/services/incoming-call-alert.service.ts`) — Subscribes early (constructor) to `CALL_INCOMING`, `CALL_OUTBOUND`, `CALL_TRANSFER_CONSULT`, `CALL_CONSULT_CANCELLED`, `CONTACT_ASSIGNED` (type=PHONE). Manages ringtone, system notification, pendingAlert signal, and opens tabs.
- `AgentDesktopComponent` (`agent/pages/agent-desktop/agent-desktop.component.ts`) — Subscribes in `ngOnInit()` to `CONTACT_ASSIGNED` (non-PHONE), `CALL_HANGUP`, `CALL_BRIDGE_COMPLETE`, `CALL_CONSULT_CANCELLED`.

## WsEventType union (ws-event.model.ts)
All recognized event types must be listed in the `WsEventType` union. Adding a new backend event requires adding it there first.

## Key dispatch rules

| Event | Handler | Side effect |
|-------|---------|-------------|
| `CALL_INCOMING` | IncomingCallAlertService | Opens PHONE tab, sets session RINGING, alert |
| `CALL_OUTBOUND` | IncomingCallAlertService | Opens PHONE tab, sets session RINGING, alert |
| `CALL_TRANSFER_CONSULT` | IncomingCallAlertService | Opens PHONE tab with contactId=secondLegCallId (CA_...), session RINGING |
| `CALL_CONSULT_CANCELLED` | IncomingCallAlertService (dismissAlert) + AgentDesktopComponent | Clears alert/audio; clears session via cancelConsultSession() (no ACW); closes PHONE tab |
| `CALL_HANGUP` | AgentDesktopComponent | Calls remoteHangup() → ENDED → softphoneEndedEffect → AFTER_CONTACT + ACW panel |
| `CALL_BRIDGE_COMPLETE` | AgentDesktopComponent | Updates session.contactId + tab.contactId to newContactId, strips [Konsultacja] prefix |
| `CONTACT_ASSIGNED` | IncomingCallAlertService (PHONE) + AgentDesktopComponent (non-PHONE) | |

**Why:** `IncomingCallAlertService` is instantiated at root level and subscribes in constructor — it must handle events before AgentDesktopComponent is loaded.
