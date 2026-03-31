---
name: IVR Architecture
description: Architektura silnika IVR – tryby pracy, sesje Redis, integracja Twilio webhook
type: project
---

IvrEngineService działa w dwóch trybach:
- **Mock mode** (twimlMode=false): wewnętrzny TaskScheduler planuje timeout DTMF
- **TwiML mode** (twimlMode=true): Twilio zarządza timeoutem przez `<Gather timeout="N">`, wewnętrzny timer NIE jest planowany

Tryb zapisany jest w `IvrSessionData.twimlMode` (JSON: `twiml_mode`) i propagowany przez cały przepływ sesji.

`TwilioWebhookController.handleVoiceWebhook()` tworzy rekord `contact` w DB przed uruchomieniem IVR i przekazuje `contactId` do `startIvrSessionAndBuildTwiml()`. `contactId` zapisywany w `IvrSessionData.contactId` i używany przez `executeQueueTransfer` / `fallbackToDefaultQueue` zamiast deterministycznego UUID z callSid.

**Why:** Bez rekordu contact RoutingService nie może znaleźć kontaktu po jego ID. Bez wyłączenia wewnętrznego timera w trybie Twilio – dwa timery wyścigują i sesja znika po 10s.

**How to apply:** Każde nowe wywołanie IVR z Twilio webhook musi używać `twimlMode=true`. Mock adapter używa `twimlMode=false`.
