---
name: Social Media integrations panel (FE-023)
description: Panel konfiguracji integracji social media – OAuth flow dla FACEBOOK/INSTAGRAM/WHATSAPP
type: project
---

OAuth flow zaimplementowany jako redirect (nie popup) przez `window.location.href = authorizationUrl`.

Pliki:
- `src/app/features/integrations/models/social-integration.model.ts` – typy SocialPlatform, WebhookStatus, SocialIntegration, OAuthInitiateResponse
- `src/app/features/integrations/services/social-integration.service.ts` – getIntegrations, initiateOAuth, deleteIntegration
- `src/app/features/integrations/pages/social-integrations/social-integrations.component.ts` – 3 karty platform, dialog rozłączenia (native <dialog>)
- `src/app/features/integrations/pages/oauth-callback/oauth-callback.component.ts` – inline template, obsługa ?error param, redirect po 2s
- `src/app/features/integrations/integrations.routes.ts` – INTEGRATIONS_ROUTES

Routing: załadowany jako `loadChildren` pod `supervisor/settings/integrations` w supervisor.routes.ts.
Callback URL dev: `http://localhost:4200/supervisor/settings/integrations/oauth/callback/:platform`

Nawigacja: dodana pozycja "Social Media" jako dziecko "Konfiguracja" w SUPERVISOR_NAV w sidenav.component.ts.
SVG icon path (share): `M18 16.08c-.76 0-1.44.3...`

**Why:** FE-023 – integracja z BE-017 (OAuth flow + zarządzanie tokenami social media).
**How to apply:** Przy rozszerzaniu o nowe platformy dodaj do PLATFORM_CARDS i SocialPlatform union type.
