---
name: project_twilio_transfer
description: Implementacja initiateTransfer dla AGENT i QUEUE w TwilioTelephonyAdapter
metadata:
  type: project
---

## Transfer AGENT

Transfer do agenta używa formatu `client:agent-{agentId}` — identyczny z `dialAgentIntoConference()`.
Pełna ścieżka: `transferToAgent()` → lookup agenta w `AppUserRepository.findByIdAndTenantIdAndDeletedFalse()`
→ buduj target string → deleguj do `transferCall(callId, target, transferType)`.
Obsługuje BLIND i ATTENDED.

**Why:** AppUser nie ma SIP identity — Twilio Client SDK tożsamość agenta to zawsze `client:agent-{UUID}`.

## Transfer QUEUE (tylko BLIND)

Używa redirect TwiML przez `Call.updater(callSid).setTwiml(new Twiml(queueTwiml)).update()`.
TwiML to `<Conference>` z nową nazwą `contact-{newContactId}` (losowy UUID), analogiczny format
do `IvrEngineService.buildWaitInConferenceTwiml()`. Parametry: `startConferenceOnEnter="false"`,
`endConferenceOnExit="true"`, `waitUrl=/api/telephony/hold-music?queueId={queueId}`, opcjonalne
nagrywanie i statusCallback z `buildRawWebhookBaseUrl()`.

**Why:** Queue nie ma Twilio Queue SID — kolejki są domenowe (tabela `queue`), nie synchronizowane z Twilio.
Wzorzec Conference jest architektonicznie spójny z resztą systemu (IVR, incoming calls).

## Zależności dodane do TwilioTelephonyAdapter

- `AppUserRepository appUserRepository` (final, @RequiredArgsConstructor)
- `QueueRepository queueRepository` (final, @RequiredArgsConstructor)
- `@Value("${app.base-url:http://localhost:8080}") String appBaseUrl`

**How to apply:** Gdy adapter wymaga nowych zależności — dodawaj `final` pola
(Spring wstrzykuje przez konstruktor Lombok). Dla `@Value` użyj osobnego non-final pola.
Test `TwilioTelephonyAdapterTest` tworzy adapter ręcznie — musi być zaktualizowany przy zmianach konstruktora.
