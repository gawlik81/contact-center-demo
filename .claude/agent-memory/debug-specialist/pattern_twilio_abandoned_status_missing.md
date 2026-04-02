---
name: TwilioTelephonyAdapter — brak logiki ABANDONED dla klientów rozłączających się w kolejce
description: handleWebhookStatusUpdate zawsze zapisuje COMPLETED do DB, nigdy ABANDONED; brak ABANDONED w całym systemie mimo istnienia statusu w schemacie DB
type: project
---

Status ABANDONED istnieje w schemacie DB (V007, V025, V030) i jest zdefiniowany jako "klient porzucił kolejkę przed połączeniem z agentem" — ale żaden komponent systemu go nie ustawia.

**Lokalizacja problemu:**
- `TwilioTelephonyAdapter.handleWebhookStatusUpdate()`, linie 587–590: hardkodowane `"COMPLETED"` w wywołaniu `contactRepository.updateContactStatusOnTelephonyEvent(...)`, bez rozróżnienia `canceled`/`no-answer` od `completed`.
- `mapTwilioStatus()` (linie 869–871): wszystkie statusy końcowe (`completed`, `busy`, `failed`, `no-answer`, `canceled`) mapowane na jeden `CallSession.CallStatus.ENDED`.
- `ContactService.terminateStaleQueuedContacts()` ustawia `ERROR` zamiast `ABANDONED` dla przeterminowanych kontaktów.

**Warunek odróżnienia ABANDONED od COMPLETED:**
`answeredAt == null` w sesji połączenia w chwili zakończenia → klient rozłączył się przed odbiorem przez agenta → ABANDONED.

**Dodatkowy problem:** StatusCallback od Twilio dla połączeń przychodzących w fazie `<Conference startConferenceOnEnter="false">` może nie być wysyłany z `callStatus=canceled` bez jawnej konfiguracji `StatusCallbackEvent=canceled` w Twilio Console.

**Why:** Odkryto przy analizie zdarzenia 2026-04-02 15:40, contactId=d54e1193-4215-4755-9146-a79961b44544.

**How to apply:** Przy każdej zmianie w `handleWebhookStatusUpdate` lub logice statusu kontaktu — upewnić się, że rozróżnienie ABANDONED/COMPLETED jest zachowane. Przy konfiguracji Twilio Console dla nowych numerów — dodawać `canceled` do StatusCallbackEvent.
