---
name: BE-059 per-tenant Twilio config in TwilioVoiceController
description: Refaktoryzacja getVoiceToken() – per-tenant credentials z fallbackiem do globalnych TwilioProperties
type: project
---

BE-059 zaimplementowany: `TwilioVoiceController.getVoiceToken()` pobiera per-tenant konfigurację Twilio przez `TenantTwilioConfigService.getDecryptedConfig(tenantId)` z fallbackiem do globalnych `TwilioProperties`. Metoda pomocnicza `resolve(perTenant, global)` oparta na `StringUtils.hasText()`.

**Why:** Każdy tenant może mieć własne konto Twilio (accountSid, apiKeySid, apiKeySecret, twimlAppSid). Bez tego wszyscy korzystali z jednego globalnego konta.

**How to apply:** Wzorzec resolve(perTenant, global) można stosować w innych miejscach gdzie wymagany jest per-tenant fallback do globalnej konfiguracji Twilio.

**Uwaga testowa:** Klucze apiKeySecret w testach muszą mieć >= 32 znaki (256 bitów) — wymóg HS256 używanego przez Twilio JWT SDK. Krótsze klucze rzucają `WeakKeyException`.
