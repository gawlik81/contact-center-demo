---
name: TwilioWebhookController — brak endpointu Voice URL zwracającego TwiML
description: ErrorCode 12300 gdy TwilioWebhookController zwraca HTTP 204 zamiast TwiML XML na Voice URL; pusty Content-Type
type: project
---

`TwilioWebhookController` obsługuje tylko StatusCallback (`ResponseEntity<Void>`, HTTP 204). Brakuje dedykowanego endpointu `/api/telephony/webhook/twilio/voice` zwracającego TwiML (`ResponseEntity<String>` z `produces = "application/xml"`).

**Symptom:** Twilio ErrorCode 12300, `contentType= (pusty)` w konsoli Twilio.

**Root cause:** Ten sam URL skonfigurowany jako Voice URL (oczekuje TwiML) i StatusCallback URL (akceptuje puste 2xx). Połączenia przychodzące od klientów przerywane natychmiast.

**Wpływ uboczny:** `persistContact()` nigdy nie wywoływany dla połączeń przychodzących → brak rekordu contact w DB → RoutingService loguje ERROR "Kontakt nie istnieje w DB".

**Fix:** Dodaj `POST /api/telephony/webhook/twilio/voice` z `produces = MediaType.APPLICATION_XML_VALUE`, zwracający TwiML. Zaktualizuj SecurityConfig + TenantFilter. Rozdziel Voice URL i StatusCallback URL w konsoli Twilio i w `application.yml` (nowy klucz `twilio.voice-webhook-url`).

**Pułapka GlobalExceptionHandler:** Każdy wyjątek w metodzie zwracającej TwiML zostanie przechwycony przez GlobalExceptionHandler i zwrócony jako JSON → ponowny 12300. Obsługuj wyjątki wewnątrz metody i zwracaj fallback TwiML.

**Dotyczy pliku:** `backend/app/src/main/java/com/contactcenter/api/telephony/TwilioWebhookController.java`
