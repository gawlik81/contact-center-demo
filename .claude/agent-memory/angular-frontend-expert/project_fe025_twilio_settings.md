---
name: Twilio settings panel per tenant (FE-025)
description: TwilioSettingsComponent – konfiguracja numeru Twilio i webhooka per tenant dla SUPERVISOR/ADMIN
type: project
---

TwilioSettingsComponent zaimplementowany pod `/supervisor/settings/twilio` (FE-025, 2026-04-02).

**Why:** Panel pozwala supervisorom konfigurować numer Twilio i URL webhooka per tenant, z fallbackiem na numer globalny gdy brak konfiguracji per-tenant.

**How to apply:** Przy modyfikacjach ustawień telefonia VoIP sięgaj po ten komponent i TwilioConfigService jako wzorzec.

Pliki:
- `frontend/src/app/features/supervisor/services/twilio-config.service.ts` – GET `/api/tenants/{id}` + PATCH `/api/tenants/{id}/config`
- `frontend/src/app/features/supervisor/pages/settings/twilio-settings.component.{ts,html,scss}`
- `frontend/src/app/features/supervisor/supervisor.routes.ts` – dodany route `settings/twilio`
- `frontend/src/app/shared/components/sidenav/sidenav.component.ts` – dodany wpis "Twilio VoIP" w sekcji Konfiguracja

Wzorce:
- Badge `badge--active` (zielony) gdy `hasPerTenantConfig()`, `badge--fallback` (żółty) gdy nie
- E.164 regex walidacja po stronie klienta: `^\+[1-9]\d{6,14}$`
- `autoCallbackUrlPreview` computed z `window.location.origin + tenantId`
- Przycisk "Usun konfiguracje per-tenant" widoczny tylko gdy `hasPerTenantConfig()`
- Blad 400 z serwera → wyciagniecie `err.error.message` i toast z tym komunikatem
- `TenantService` NIE ma metody `getTenant(id)` – dlatego stworzono oddzielny `TwilioConfigService`
