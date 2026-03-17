---
name: Backend Java/Spring Boot patterns and known issues
description: Spring Boot 3.3.5 / Java 21 backend: critical bugs, security issues, architecture violations, and positive patterns found in the first full review 2026-03-17.
type: project
---

First full backend review completed 2026-03-17. Key findings to carry forward:

**Why:** These are recurring patterns that will appear in future incremental reviews of new features.

**Critical bugs confirmed:**
- `TenantService.deactivateTenant` calls `appUserRepository.findAll()` — full table scan + N+1. Fix: single `@Modifying` JPQL UPDATE per tenantId.
- `AuthService.blacklistAccessToken` uses config TTL (15 min) instead of actual token `exp` claim. `JwtClaims` record does not carry `expiresAt`. Fix: add `Instant expiresAt` to `JwtClaims`.
- `AuthService.refresh` issues token with `mfaVerified = user.isMfaEnabled()` — grants `mfaVerified=true` after token refresh without TOTP re-verification. Should be `false`.
- `AdminMetricsService.countOnlineAgentsForTenant` always returns 0 because `UserService` stores a plain String (status name) in Redis `session:agent:{userId}`, not a Map with `tenantId`.

**Security issues confirmed:**
- TOTP codes have no single-use protection — replay attack possible within 90-second window.
- `RedisConfig` uses `LaissezFaireSubTypeValidator` for Jackson polymorphic deserialization — potential RCE gadget chain if Redis is compromised. Fix: `BasicPolymorphicTypeValidator` with package allowlist.
- Swagger/api-docs not disabled in production config.

**Architecture violations confirmed:**
- `AppUserRepository`, `RefreshTokenRepository`, `AuditLogRepository` do NOT extend `TenantAwareRepository`. They use Spring Data JPA interfaces. RLS `set_tenant_context()` is never called for their JPA-generated queries. Multi-tenancy relies solely on explicit `tenantId` params. This contradicts the documented rule but has a design reason (Spring Data interface limitation). The rule needs to be revised or a custom repository fragment pattern adopted.
- All `@Modifying` JPQL bulk updates (AppUserRepository lines 47, 55, 68, 81; RefreshTokenRepository lines 31, 39) are missing `@Modifying(clearAutomatically = true)`, causing Hibernate L1 cache staleness within the same transaction.

**Minor confirmed:**
- `TenantFilter.generateRequestId` sanitization bug: `substring` uses original string length after `replaceAll` shortens it — can throw `StringIndexOutOfBoundsException`.
- `AdminMetricsService.scanOnlineAgentKeys` calls `redisTemplate.keys()` (blocking Redis `KEYS` command) despite comment saying SCAN is used.
- `UserController.listUsers` calls `Page.getContent()` — discards all pagination metadata sent to frontend.
- `GlobalExceptionHandler` maps `IllegalStateException` → 409 (too broad — catches JVM internal exceptions).
- `UserDetailsServiceImpl` loads deleted users via `findByTenantIdAndEmail`; `findByTenantIdAndEmailAndActiveTrue` exists and is preferred.

**Positive patterns to reinforce in review:**
- Filter chain order is correct and documented: JwtAuthFilter → TenantFilter → UsernamePasswordAuthenticationFilter.
- SHA-256 hashing of JWT tokens in Redis blacklist (never raw tokens) correctly implemented.
- TenantContext.clear() always called in `finally` block in TenantFilter.
- Snapshot/restore/clear pattern is implemented for async propagation.
- Sensitive fields excluded from toString() and audit JSON serialization.
- @Modifying native queries include tenant_id in WHERE clause.
- BCryptPasswordEncoder(12) documented and appropriate.
- RFC 7807 Problem Details used consistently.

**How to apply:** In future incremental reviews: check new repositories for `TenantAwareRepository` extension (or document why not), verify all `@Modifying` JPQL have `clearAutomatically=true`, verify new public endpoints appear in both SecurityConfig and TenantFilter.PUBLIC_PATH_PREFIXES, check any new async methods for snapshot/restore/clear.
