---
name: BE-048 Manual Callback Endpoint
description: POST /api/callbacks/manual – manualne planowanie oddzwonień przez agenta, sourceType=AGENT_MANUAL, ManualCallbackController
type: project
---

Nowy dedykowany kontroler `ManualCallbackController` w pakiecie `api/dialer/`.

**Why:** Agenci potrzebują możliwości zaplanowania oddzwonienia do klienta bez aktywnej rozmowy telefonicznej.

**How to apply:** Przy rozbudowie callbacków – encja `ScheduledCallback` obsługuje już sourceType=AGENT_MANUAL (kolumna `notes TEXT` i rozszerzony CHECK constraint dodane przez migrację V047).

## Kluczowe decyzje

- Osobny kontroler `ManualCallbackController` (nie dołączano do `DialerController` – ten jest już duży)
- Endpoint: `POST /api/callbacks/manual` (pod `/api/callbacks`, nie `/api/dialer/callbacks`)
- `@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR')")` – ADMIN nie ma dostępu (biznesowe)
- `assigned_agent_id` zawsze z JWT – ochrona IDOR
- Walidacja scheduledAt min. 5 minut w przyszłości → `ResponseStatusException(HttpStatus.BAD_REQUEST)` (wzorzec z SocialOAuthController)
- Cross-tenant guard: `CustomerRepository.findById()` filtruje po tenantId → `CrossTenantAccessException` → HTTP 403 (nie 404 – nie ujawniamy istnienia zasobu)
- `campaign_id` i `origin_contact_id` zawsze null dla AGENT_MANUAL

## Pliki

- `api/dialer/ManualCallbackController.java` – nowy kontroler
- `api/dialer/dto/ManualCallbackRequest.java` – record z @NotNull, @NotBlank, @Pattern(E.164)
- `api/dialer/dto/ManualCallbackResponse.java` – record z metodą fabryczną `from(callback, customerName)`
