---
name: Outbound hangup — agent nie rozłączany, rekord kampanii w DIALING
description: Klient rozłącza outbound call; agent softphone pozostaje ACTIVE; campaign_contact nie wychodzi z DIALING
type: project
---

Backend prawidłowo wysyła CALL_HANGUP przez WebSocket, ale frontend go nie odbierał bo brakowało dwóch ogniw:
1. `'CALL_HANGUP'` nieobecne w `WsEventType` union (ws-event.model.ts) → TypeScript silent error, filter nie działał.
2. Brak subskrypcji na CALL_HANGUP w AgentDesktopComponent.ngOnInit().
3. Brak metody `remoteHangup()` w SoftphoneService (jedyna ścieżka do ENDED to hangupCall() agent-side, które dodatkowo strzela HTTP request do backendu).

Rekord campaign_contact utknął w DIALING bo DialerCallbackHandler nie miał żadnego @RabbitListener — handleCompleted() istniała ale wywoływana była wyłącznie ręcznie z endpointu dyspozycji.

**Wzorzec naprawy:**
- Backend: dodaj @RabbitListener(cc.queue.dialer-hangup / call.hangup) w DialerCallbackHandler; odczytuje dialer:call:{callSid} z Redis (CSV: recordId,campaignId,agentId,tenantId); jeśli istnieje → updateCampaignContact COMPLETED + cleanupRedisKeys.
- Frontend model: każdy nowy event backendowy musi być dodany do WsEventType PRZED próbą subskrypcji.
- Frontend service: remoteHangup() = set ENDED + disconnect Twilio call leg, BEZ HTTP request (backend już zakończył połączenie).
- Frontend component: subskrypcja → softphoneService.remoteHangup().

**Why:** consumer competition nie ma tu miejsca bo kolejka dialer-hangup jest dedykowana i niezależna od ws-relay-calls; oba konsumenty dostają ten sam event.
