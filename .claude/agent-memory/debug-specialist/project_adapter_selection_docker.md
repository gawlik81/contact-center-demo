---
name: adapter-selection-docker-demo
description: Pułapka selekcji adaptera telefonii w Docker local-demo — profil prod aktywuje Twilio zamiast Mock
metadata:
  type: project
---

W środowisku `docker-compose.local-demo.yml` backend uruchamiany jest z `SPRING_PROFILES_ACTIVE=prod`.
Domyślne wartości w `application.yml` to `telephony.provider=twilio` i `twilio.enabled=true`.
Przy profilu `prod` (brak `application-prod.yml` nadpisania tych właściwości) Spring aktywuje
`TwilioTelephonyAdapter` (`@Primary` + `@ConditionalOnProperty(name="twilio.enabled", havingValue="true")`)
zamiast `MockTelephonyAdapter` (`@ConditionalOnProperty(name="telephony.provider", havingValue="mock", matchIfMissing=true)`).

**Why:** `matchIfMissing=true` na Mock dotyczy braku property, ale gdy property jest jawnie ustawione
na "twilio" (default w application.yml), Mock nie jest tworzony. Twilio adapter ma `@Primary`, więc
wygrywa gdy oba są aktywne — ale gdy `twilio.enabled=true`, Mock w ogóle nie jest rejestrowany.

**Naprawa:** W `.env.local-demo` dodano `TELEPHONY_PROVIDER=mock` i `TWILIO_ENABLED=false`.
Plik: `/home/pawelm/contact-center/.env.local-demo`

**How to apply:** Gdy ktoś zgłasza że `TwilioTelephonyAdapter` jest wywoływany w środowisku,
które powinno używać Mocka — sprawdź aktywny profil Spring w kontenerze i wartości
`TELEPHONY_PROVIDER` + `TWILIO_ENABLED` w ENV vars / env_file.
