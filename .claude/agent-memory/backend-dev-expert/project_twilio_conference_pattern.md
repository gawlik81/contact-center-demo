---
name: Twilio Conference Audio Pattern
description: Wzorzec połączenia klient-agent przez nazwaną konferencję Twilio z nagrywaniem
type: project
---

Zestawianie audio między klientem a agentem realizowane przez nazwaną konferencję Twilio:

1. Klient wchodzi do konferencji `contact-{contactId}` z `startConferenceOnEnter="false"` (czeka na agenta)
2. Agent dołącza przez Twilio REST API `Call.creator("client:agent-{agentId}", ...)` z `startConferenceOnEnter="true"` (moderator startujący konferencję)
3. Nagranie: `record="record-from-start"` na `<Conference>`, callback do `/api/telephony/webhook/twilio/recording`

**Why:** Bez konferencji klient był rozłączany przez `<Hangup/>` po QUEUE_TRANSFER, a `answerCall()` nie wysyłał żadnego TwiML do Twilio — brak audio.

**How to apply:** Przy każdej zmianie w IVR flow lub answerCall — utrzymaj spójność nazw konferencji (zawsze `contact-{contactId}`).

Kluczowe klasy:
- `IvrEngineService.buildWaitInConferenceTwiml()` — TwiML dla klienta
- `IvrEngineService.pendingConferenceContactId` — ConcurrentHashMap przechowująca contactId między executeQueueTransfer a handleDtmfAndBuildTwiml
- `TwilioTelephonyAdapter.dialAgentIntoConference()` — Twilio REST API call do agenta
- `TwilioWebhookController.handleRecordingCallback()` — endpoint `/recording` odbierający RecordingUrl od Twilio
