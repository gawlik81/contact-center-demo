---
name: BE-017 Social Media OAuth i zarządzanie tokenami
description: OAuth flow i szyfrowanie tokenów dla Facebook Messenger, Instagram, WhatsApp Business
type: project
---

Implementacja OAuth flow i zarządzania tokenami social media (BE-017).

**Pliki:**
- `domain/model/SocialPlatform.java` – enum FACEBOOK/INSTAGRAM/WHATSAPP
- `domain/model/SocialIntegration.java` – encja JPA, pole `accessTokenEncrypted` jako `byte[]` (BYTEA), platform jako `social_platform` ENUM (nie VARCHAR)
- `domain/repository/SocialIntegrationRepository.java` – rozszerza TenantAwareRepository
- `domain/service/SocialTokenEncryptionService.java` – AES-256-GCM, klucz z `social.token-encryption-key`
- `domain/service/SocialIntegrationService.java` – CRUD, scheduled refresh co 1h
- `api/social/SocialOAuthController.java` – endpointy GET/POST/DELETE
- `api/social/dto/` – SocialIntegrationDto, SocialIntegrationListResponse, OAuthInitiateResponse

**Konfiguracja `application.yml`:** sekcja `social:` z kluczem szyfrowania i OAuth credentials per-platforma.

**Publiczny endpoint:** `/api/oauth/{platform}/callback` – dodany do SecurityConfig (`requestMatchers`) i TenantFilter (`PUBLIC_PATH_PREFIXES`).

**Wzorzec szyfrowania BYTEA:** `encrypt(String) → byte[]`, `decrypt(byte[]) → String`. Format: `[IV 12B][ciphertext+GCM-tag]`. Różni się od EmailEncryptionService który zwraca Base64 String (tam kolumna JSONB, tutaj BYTEA).

**Scheduled refresh:** wątek @Scheduled bez TenantContext → jawne `TenantContext.setTenantId(tenantId)` + `TenantContext.clear()` w finally. WhatsApp pomijany (tokeny nie wygasają).

**Revoke:** DELETE na `https://graph.facebook.com/v19.0/{pageId}/permissions?access_token={token}`. Błąd nie blokuje usunięcia z DB.

**Why:** wymaganie PRD US-06-02 – autoryzacja platform social media per tenant z bezpiecznym przechowywaniem tokenów.

**How to apply:** przy kolejnych integracjach zewnętrznych wymagających OAuth: stosuj ten sam wzorzec szyfrowania (BYTEA + AES-GCM) i publiczny callback endpoint w SecurityConfig + TenantFilter.
