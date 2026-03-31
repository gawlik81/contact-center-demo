---
name: TwilioVoiceWebhook — brak sesji adaptera przy odbieraniu połączenia
description: handleVoiceWebhook tworzy contact w DB i sesję IVR w Redis, ale nie rejestruje sesji w TwilioTelephonyAdapter.sessions; answerCall rzuca TelephonyException
type: feedback
---

`TwilioWebhookController.handleVoiceWebhook` tworzy rekord contact i uruchamia IVR, ale nie wywołuje `twilioAdapter.registerIncomingCall()`. Sesja w `TwilioTelephonyAdapter.sessions` (ConcurrentHashMap w JVM) istnieje TYLKO gdy dotrze StatusCallback (`handleWebhookStatusUpdate`) lub gdy wywołano `initiateCall`. Jeśli agent kliknie "odbierz" przed pierwszym StatusCallback – `requireSession(callSid)` rzuca `TelephonyException`.

**Dwa odrębne magazyny sesji — kluczowe rozróżnienie:**
- `TwilioTelephonyAdapter.sessions` – ConcurrentHashMap w JVM, klucz: Twilio CallSid (CA...)
- Redis `ivr:session:{callSid}` – sesja IVR usuwana celowo po QUEUE_TRANSFER

**Why:** Błąd wynikał z braku wywołania adaptera w Voice URL handler — nowy endpoint tworzył DB record i IVR session, ale pominął rejestrację sesji adaptera.

**How to apply:** Gdy analizujesz błąd `Sesja połączenia nie istnieje` przy `answerCall` — sprawdź czy `handleVoiceWebhook` wywołuje `twilioAdapter.registerIncomingCall()`. Rozwiązanie: dodać tę metodę do adaptera (`computeIfAbsent` – idempotentne) i wywołać ją w Voice URL handlerze po utworzeniu rekordu contact. Opcjonalnie: `tryRestoreSessionFromDb` jako fallback w `requireSession`.
