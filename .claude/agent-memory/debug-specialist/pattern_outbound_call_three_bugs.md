---
name: Outbound call — trzy powiązane błędy (CALL_INCOMING, brak sip_call_id, rozłączenie)
description: handleWebhookStatusUpdate nie odróżnia outbound od inbound → duplikat CALL_INCOMING; brak backfill sip_call_id → kontakt niewidoczny po callSid; contactId=null w sesji → dialAgentIntoConference nie wywoływane → rozłączenie
type: project
---

Połączenie wychodzące (dialer) ma trzy współzależne błędy:

**Bug A — CALL_INCOMING zamiast CALL_OUTBOUND**
`mapTwilioStatusToEventType("ringing")` zawsze zwracał `CALL_INCOMING` niezależnie od kierunku.
`initiateCall` publikował `CALL_OUTBOUND`, ale StatusCallback `ringing` nadpisywał to zdarzeniem
`CALL_INCOMING`. Fix: dodano `isOutbound` param — dla outbound ringing → `null` (brak duplikatu).
`isOutbound = sessions.get(callSid) != null` przed stworzeniem nowej sesji.

**Bug B — sip_call_id = NULL w channel_metadata**
`persistOutboundContact` tworzy contact PRZED wywołaniem Twilio API → callSid nieznany → `sip_call_id=NULL`.
Gałąź `else` w `handleWebhookStatusUpdate` (istniejąca sesja) nie uzupełniała sip_call_id.
Fix: nowa metoda `ContactRepository.backfillCallSidInMetadata(contactId, callSid, tenantId)` — idempotentna,
wywołana przy każdym webhooku dla istniejącej sesji z niepustym contactId.

**Bug C — rozłączenie po odebraniu przez klienta**
TwiML outbound: `<Conference startConferenceOnEnter="false">` — konferencja nie startuje bez moderatora.
`answerCall` wywołuje `dialAgentIntoConference` tylko gdy `contactId != null`.
Jeśli `persistOutboundContact` zwróciła null lub contactId nie odtworzone → klient czeka, agent nie dołącza → timeout → rozłączenie.
Fix: po backfill — jeśli `contactId == null` w sesji, spróbuj `findContactIdByCallSid` i zaktualizuj sesję przez `session.withContactId(recoveredContactId)`.

**Why:** Wszystkie trzy błędy wynikają z braku rozróżnienia outbound vs inbound w `handleWebhookStatusUpdate`.

**How to apply:** Przy każdym problemie z outbound: sprawdź `isOutbound` w handleWebhookStatusUpdate,
zawartość `channel_metadata.sip_call_id` w DB i czy `dialAgentIntoConference` jest wywoływane
(szukaj logu "Brak contactId lub agentId w sesji").
