---
name: project_attended_transfer_refactor
description: Attended transfer refactor do Wariantu A – każdy transfer tworzy oddzielną konferencję Twilio i własne nagranie
metadata:
  type: project
---

Attended transfer (bridgeCalls) przeszedł na Wariant A: klient i Agent2 są przekierowywani do nowej konferencji `contact-{newContactId}` przez `Call.updater().setTwiml()`, analogicznie do blind transfer. Każdy kontakt po transferze ma własne nagranie (R-A i R-B).

**Kluczowe zmiany:**
- `CallSession` ma nowe pole `customerCallSid` (propagowane przez łańcuch transferu)
- `TelephonyAdapter.bridgeCalls()` ma nową sygnaturę: `bridgeCalls(String, String, UUID newContactId)`
- `TwilioTelephonyAdapter.bridgeCalls()` – redirect klienta + Agent2 do nowej konferencji, zakończenie nogi Agent1, aktualizacja Redis
- `ContactService.bridgeCalls()` – generuje `newContactId` PRZED wywołaniem adaptera, tworzy kontakt PRZED bridge
- `propagateRecordingToTransferChain()` usunięta – niepotrzebna przy oddzielnych konferencjach
- `updateSessionContact()` to no-op (adapter sam aktualizuje sesję w bridgeCalls)
- `MockCallController.bridgeCalls()` wywołanie – dodany trzeci parametr `UUID.randomUUID()`

**Fallback dla starych sesji Redis (bez customerCallSid):**
- INBOUND/null direction: używa `session.getCallId()` jako fallback (log WARN)
- OUTBOUND bez customerCallSid: rzuca TelephonyException

**Why:** Ujednolicenie zachowania wszystkich transferów (attended = blind = queue) – każdy kontakt dostaje własne nagranie Twilio z oddzielnej konferencji.

**How to apply:** Przy każdej nowej funkcji tworzącej sesję Twilio (incoming/outbound) ustawiaj `customerCallSid` w builderze `CallSession`. Przy propagacji przez łańcuch (attended 2nd leg) kopiuj z sesji rodzica.

Powiązane: [[project_twilio_transfer]]
