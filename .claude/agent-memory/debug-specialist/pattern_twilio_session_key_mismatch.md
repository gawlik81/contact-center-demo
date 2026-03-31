---
name: TwilioTelephonyAdapter — niezgodność klucza sesji callSid vs contactId
description: sessions indeksowane po callSid (CA...), ale frontend wysyła contactId (UUID) jako callId do AgentCallController
type: feedback
---

TwilioTelephonyAdapter.sessions (ConcurrentHashMap) używa Twilio callSid (format CA...) jako klucza.
ContactAssignedEvent wysyłany przez RabbitMQ do WebSocket relay zawiera TYLKO contactId (UUID z DB), nie callSid.
Frontend odbiera contactId przez WebSocket i używa go jako {callId} w URL: POST /api/telephony/calls/{contactId}/answer.
AgentCallController przekazuje odebrany callId bezpośrednio do telephonyAdapter.answerCall/hangupCall.
requireSession(callId) szuka sessions.get(contactId-UUID) → null; UUID-scan po sessions.values() też zwraca null gdy sesja adaptera jeszcze nie istnieje (StatusCallback nie dotarł) lub istnieje z innym contactId (duplikat z persistContact()).

**Why:** Twilio CallSid nie jest propagowany do frontendu w żadnym WS evencie. Frontend zna tylko contactId z bazy. Brakuje warstwy translacji callId→callSid w AgentCallController (przez ContactRepository.channelMetadata->>'sip_call_id').

**Dodatkowy problem:** Gdy StatusCallback dociera po /voice webhooku, handleWebhookStatusUpdate() woła persistContact() ponownie → duplikat rekordu contact. Sesja adaptera (sessions[callSid]) może mieć inny contactId niż sesja IVR (Redis).

**How to apply:** Gdy w logach "Sesja połączenia nie istnieje: {UUID-format}" – frontend wysyła contactId zamiast callSid. Naprawa priorytetu: AgentCallController.resolveCallSid() transluje UUID contactId → callSid przez ContactRepository.findCallSidByContactId() (channelMetadata->>'sip_call_id'). Alternatywa: dołącz callSid do eventu contact.assigned w RoutingService.
