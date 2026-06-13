---
name: project-telecom-patterns
description: Twilio/telephony layer patterns, known issues, and conventions found in reviews of TwilioTelephonyAdapter, TwilioWebhookController, TwilioVoiceController, and SoftphoneService.
metadata:
  type: project
---

# Telecom Layer — Patterns and Known Issues (reviewed 2026-05-29)

## Architecture Overview
- TwilioTelephonyAdapter is @Primary @ConditionalOnProperty(twilio.enabled=true); MockTelephonyAdapter is fallback
- All call sessions stored in Redis (key: `call-session:{callSid}`, TTL 24h via RedisConfig.TTL_CALL_SESSION)
- Reverse index: `contact-session-index:{contactId}` → callSid (StringRedisTemplate, not Object)
- Per-tenant TwilioRestClient cached in Caffeine (max 100, TTL 15min), invalidated by TwilioConfigChangedEvent
- Conference naming convention: `contact-{contactId}` (deterministic, links Twilio conference to DB contact)
- Agent identity: `agent-{userId}` (Twilio Client SDK identity must match dialAgentIntoConference format)

## Known Bugs Found

### TenantContext clear/restore order in persistOutboundContact (CRIT-01)
In `TwilioTelephonyAdapter.persistOutboundContact()` and `persistContact()`, the finally block calls `TenantContext.clear()` THEN `TenantContext.restore(snapshot)`. The semantically correct order is restore first (which reverts to empty if snapshot was empty), or clear-then-restore (which also works but is verbose). The current code is correct in practice but confusing. Pattern used in the rest of the codebase: `finally { TenantContext.restore(snapshot); }` is preferred.

### HttpClient created per-call in setStatusCallbackEvents (CRIT-02)
`TwilioTelephonyAdapter.setStatusCallbackEvents()` creates `java.net.http.HttpClient.newHttpClient()` on every invocation — called for each active tenant at startup. Compare with `TwilioRecordingDownloadService` which correctly creates one shared HttpClient in constructor.

### TwilioRecordingDownloadService uses global Twilio credentials (IMP-06)
`buildBasicAuthCredentials()` always uses `twilioProperties.getAccountSid()` and `twilioProperties.getAuthToken()` (global). In multi-tenant BYOT, per-tenant credentials from `tenant_twilio_config` are needed. Will cause HTTP 401 from Twilio when downloading recordings for tenants with their own Twilio accounts.

## Security Patterns

### Signature validation config
- `twilio.signature-validation-enabled=true` in application.yml (default, ENV-overridable)
- `twilio.signature-validation-enabled=false` in application-dev.yml (dev only, intentional)
- Validation uses official Twilio `RequestValidator` SDK class — correct implementation
- `validateTwilioSignature()` called at top of EVERY webhook handler before payload processing

### Header Injection risk in holdMusic (IMP-02)
`TwilioVoiceController.buildSelfUrl()` trusts `Host` header for building `<Redirect>` TwiML. Should use `appBaseUrl` instead (@Value injected field used elsewhere in same controller).

### Public endpoint registration — both SecurityConfig AND JwtAuthFilter
`/api/telephony/webhook/**` and `/api/telephony/hold-music` are registered in:
1. SecurityConfig.java (requestMatchers)  
2. JwtAuthFilter.java (PUBLIC_PATH_PREFIXES set)
3. PublicPathsConfig.java (centralized list)
This three-place registration pattern is the project convention.

## TenantContext Async Pattern
`scheduleRecordingFallback()` is the canonical correct example:
```java
TenantContext.Snapshot snapshot = TenantContext.snapshot();
CompletableFuture.delayedExecutor(90, SECONDS).execute(() -> {
    TenantContext.restore(snapshot);
    try { ... }
    finally { TenantContext.clear(); }
});
```

## Frontend SoftphoneService Patterns
- Uses Angular signals for session state (`signal<CallSession | null>`)
- Token refresh: `interval(3_300_000)` (55 min) with `switchMap` to re-fetch token, then `device.updateToken()` — no full reinit
- `destroyTwilioDevice()` is missing `clearTimers()` call — potential race on reinit (IMP-04)
- Race condition mitigation: `handleIncomingCall()` uses 500ms setTimeout for WS event arrival — may be too short on slow connections
- Optimistic UI updates (hangup, hold, mute) with fire-and-forget HTTP — errors are swallowed

## Conference Status Callback Logic
Terminal statuses that prevent ABANDONED override: COMPLETED, ABANDONED, NOT_REACHED, ERROR, TRANSFERRED
The TRANSFERRED guard (added in fix #2) prevents race condition where OriginalConference ends after client redirect.

**Why:** Recorded during deep review of TwilioTelephonyAdapter.java (3038 lines), TwilioWebhookController.java, TwilioVoiceController.java, SoftphoneService.ts, TwilioRecordingDownloadService.java.
**How to apply:** When reviewing future changes to telephony layer, check these known weak points first. The recording download service is the most likely source of production bugs in BYOT scenarios.
