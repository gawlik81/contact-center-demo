---
name: project_social_module_coverage
description: Test coverage status of com.contactcenter.{domain,api,infrastructure}.social (WhatsApp Business connect feature) as of 2026-08-29
metadata:
  type: project
---

As of 2026-08-29, `backend-dev-expert` shipped manual WhatsApp Business Cloud API connect
(`POST /api/integrations/WHATSAPP/connect`, `WhatsAppAdapter.sendMessage` real implementation,
`SocialIntegrationService.getDecryptedIntegration`), and the whole `domain.social`/`api.social`/
`infrastructure.social` module had **zero unit tests** beforehand (confirmed by grep). This session
(test-suite-expert) added:
- `backend/app/src/test/java/com/contactcenter/api/social/SocialOAuthControllerTest.java` — only
  `connectWhatsApp()` (happy path incl. platformConfig/businessAccountId edge cases, Bean Validation).
- `backend/app/src/test/java/com/contactcenter/domain/social/SocialIntegrationServiceImplTest.java`
  — only `getDecryptedIntegration()` (404/422/multi-tenant scoping).
- `backend/app/src/test/java/com/contactcenter/infrastructure/social/WhatsAppAdapterTest.java` —
  full `sendMessage()`/`getPlatform()`/`getConversationHistory()` coverage.

**Still untested (pre-existing debt, out of scope for that task, worth flagging if this area comes
up again):**
- `SocialIntegrationServiceImpl`: `saveIntegration()`, `deleteIntegration()` (+ its 3-stage
  split `loadIntegrationForDelete`/`deleteIntegrationFromDb`/`revokeTokenAtProvider`),
  `refreshExpiringTokens()`/`refreshToken()` (the `@Scheduled` job + its per-tenant
  `TenantContext.setTenantId`/`clear()` pattern), `exchangeForLongLivedToken()`.
- `SocialOAuthController`: `listIntegrations()`, `initiateOAuth()`, `oauthCallback()` (incl. the
  Redis OAuth-state CSRF flow), `deleteIntegration()`.
- `SocialWebhookController`, `SocialContactController`, `SocialAdapterRegistry`,
  `SocialMessagePublisherImpl`, `SocialMessageConsumer` — no test files found for any of these.
- `SocialMessageServiceImpl` already has decent coverage (`SocialMessageServiceTest`, pre-existing).

**How to apply:** if a future task touches this module again ("add Facebook/Instagram OAuth tests",
"test token refresh scheduler", etc.), treat the above list as the known gap map rather than
re-discovering it from scratch — but re-verify with a grep first, this list decays as soon as
someone adds tests for any of it.
