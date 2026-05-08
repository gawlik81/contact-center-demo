---
name: BE-057 TenantTwilioConfig REST API (kontroler)
description: Kontroler + SecurityConfig + walidacja DTO dla konfiguracji Twilio per-tenant
type: project
---

BE-057 zaimplementowany: `TenantTwilioConfigController` w `api/supervisor/twilio/`.

**Why:** Supervisor musi móc zarządzać konfiguracją Twilio przez REST API bez dostępu admina.

**How to apply:** Przy kolejnych endpointach supervisor-only — wzorzec `@PreAuthorize("hasRole('SUPERVISOR')")` na poziomie klasy + reguła w SecurityConfig przed `.anyRequest().authenticated()`.

Szczegóły:
- 4 endpointy: GET/PUT/DELETE `/api/supervisor/twilio-config` + POST `/test`
- GET zwraca 204 (nie 404) gdy brak konfiguracji — `Optional.map(...).orElse(noContent())`
- POST `/test` zawsze zwraca 200 — wynik testu w polu `success` (nie rzuca wyjątku)
- Walidacja Jakarta na `TenantTwilioConfigRequest`: `@NotBlank` na accountSid+authToken, `@Pattern` na accountSid (AC+32 hex) i phoneNumber (E.164)
- SecurityConfig: reguła `/api/supervisor/twilio-config/**` dodana po bloku `/api/tenants/**`
- Test: `@ExtendWith(MockitoExtension.class)` + `TenantContext.setTenantId/clear` w `@BeforeEach/@AfterEach`
