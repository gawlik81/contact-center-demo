---
name: Twilio Webhook – async pattern i walidacja podpisu
description: Wzorzec dla webhooków Twilio: natychmiastowy 204, logika w @Async; walidacja X-Twilio-Signature; HttpClient jako pole serwisu
type: feedback
---

Każda logika wymagająca wywołania Twilio REST API (np. Conference.fetcher) MUSI być w wątku @Async, nie w wątku Tomcata obsługującym webhook.

**Why:** Timeout Twilio SDK = 30s. Przy obciążeniu synchroniczne wywołanie blokuje wszystkie wątki Tomcata.

**How to apply:**
- handleRecordingCallback i inne handlery webhooka zwracają 204 NATYCHMIAST
- parametry (callSid, conferenceSid, tenantId) są przekazywane do metody @Async jako argumenty
- TwilioRecordingDownloadService.downloadAndStore() jest @Async i zawiera całą logikę rozwiązywania contactId (przez findContactIdByCallSid lub resolveContactIdFromConference)
- Walidacja X-Twilio-Signature przez RequestValidator (Twilio SDK) wywoływana przez helper validateTwilioSignature() na początku KAŻDEGO handlera – zwraca 403 gdy nieprawidłowy
- twilio.signature-validation-enabled: true (default w application.yml), false (application-dev.yml)
- HttpClient jako private final – inicjowany w konstruktorze, nie per-call (kosztowne zasoby NIO)
