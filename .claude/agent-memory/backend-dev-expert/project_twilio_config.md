---
name: BE-056 TenantTwilioConfig serwis domenowy
description: Pakiet domain/service TenantTwilioConfigService – upsert, masking, decrypted DTO, delete z eventem, test połączenia Twilio
type: project
---

BE-056 zaimplementowany: serwis domenowy `TenantTwilioConfigService` + DTO + event.

**Why:** Zarządzanie konfiguracją Twilio per-tenant z maskingiem wrażliwych pól w REST i plaintext dla wewnętrznych adapterów.

**How to apply:** Przy kolejnych ticketach dotyczących Twilio korzystać z `TenantTwilioConfigService.getDecryptedConfig()` zamiast bezpośrednio z repozytorium.

Pliki:
- `api/supervisor/twilio/dto/TenantTwilioConfigRequest.java` — request DTO (brak adnotacji walidacyjnych, walidacja w serwisie)
- `api/supervisor/twilio/dto/TenantTwilioConfigResponse.java` — response z maskowaniem `authToken`/`apiKeySecret`
- `api/supervisor/twilio/dto/TwilioConnectionTestResult.java` — wynik testu połączenia
- `domain/event/TwilioConfigChangedEvent.java` — nowy katalog `domain/event/`
- `domain/service/TenantTwilioConfigDecrypted.java` — internal plaintext DTO (nie eksponować przez REST)
- `domain/service/TenantTwilioConfigService.java` — serwis z upsert, getConfig (masked), getDecryptedConfig, delete, testConnection
- `domain/repository/TenantTwilioConfigRepository.java` — dodana metoda `delete(TenantTwilioConfig)`

Wzorzec maskingu: `mask()` static w `TenantTwilioConfigResponse` — `null→null`, `<=4 znaki→"●●●●"`, dłuższe→`"●●●●●●●●...XXXX"`.
Pattern walidacji: `AC` + 32 znaki hex dla accountSid; E.164 `+[1-9]\d{7,14}` dla phoneNumber (pole opcjonalne).
`TwilioConfigChangedEvent` publikowany po każdym save i delete.
