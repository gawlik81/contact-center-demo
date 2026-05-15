---
name: project-be077-transfer-endpoint
description: BE-077 — POST /api/telephony/calls/{callId}/transfer, logika w ContactService.initiateTransfer(), DTO TransferCallRequest
metadata:
  type: project
---

BE-077 zaimplementowany: endpoint `POST /api/telephony/calls/{callId}/transfer` dodany do `AgentCallController.java`.

Logika biznesowa w `ContactService.initiateTransfer()` — tam jest dostęp do `ContactRepository` i `ContactEventService`.

**Why:** Decyzja o umieszczeniu logiki w `ContactService` (nie nowym serwisie): weryfikacja ownership kontaktu + zapis zdarzenia wymagają tych zależności, a `ContactService` je już posiada. `TelephonyAdapter` dodany jako nowa zależność do `ContactService`.

**How to apply:** Dla przyszłych endpointów telefonicznych z walidacją kontaktu — logika w `ContactService`, nie w kontrolerze.

Nowe pliki:
- `api/telephony/dto/TransferCallRequest.java` — record z @NotNull na transferType i targetType

Zmodyfikowane pliki:
- `domain/service/ContactService.java` — nowa metoda `initiateTransfer()`, nowa zależność `TelephonyAdapter`, nowe importy
- `api/telephony/AgentCallController.java` — nowy endpoint + import `TransferCallRequest`

Mapowanie wyjątków:
- `IllegalArgumentException` (z `TransferRequest.validate()`) → HTTP 422
- `EntityNotFoundException` → HTTP 404
- `CrossTenantAccessException` → HTTP 403
- `InvalidOperationException` (nie właściciel) → HTTP 409
- `ConflictException` (nie ACTIVE) → HTTP 409

Nota: test `ContactServiceTest.updateContact_supervisorCanUpdateAnyContact` był wadliwy PRZED BE-077 (brakujący mock `ContactEventService`). Nie jest regresją.
