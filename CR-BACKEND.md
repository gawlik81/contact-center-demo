# Backend Code Review — CR-BACKEND.md

## Review: Java/Spring Boot Backend (Full Codebase) — 2026-03-17

---

## Executive Summary

The backend is well-structured, demonstrates solid knowledge of Spring Boot 3 / Java 21 patterns, and handles most multi-tenancy and security concerns correctly. Filter chain ordering is correct, JWT blacklisting is properly SHA-256 hashed, TenantContext lifecycle is managed correctly in the HTTP filter, and DTOs are properly separated from entities. However, several significant issues exist: a critical N+1 / full-table-scan bug in `TenantService.deactivateTenant`, a blacklist TTL calculation that uses config defaults instead of the actual token expiry, a missing `@Modifying(clearAutomatically=true)` on bulk JPQL updates, a TOTP replay window that can be exploited, and a `redisTemplate.keys()` call in a production hot path. These must be fixed before production deployment.

Overall: **3 / 5** — solid foundation with several production-blocking issues.

---

## Critical Issues (must fix)

### 1. Full table scan + N+1 queries in `deactivateTenant`

**File:** `domain/service/TenantService.java:242–253`

`appUserRepository.findAll()` loads every user across **all tenants** into memory, then filters in Java with a stream predicate. For a platform with 50 tenants and 10 000 users this means loading 10 000 JPA entities per deactivation call. The follow-up loop then fires one `UPDATE` per active user (N+1).

```java
// Current — loads ALL users from ALL tenants
List<AppUser> tenantUsers = appUserRepository.findAll().stream()
        .filter(u -> tenantId.equals(u.getTenantId()))
        .toList();

for (AppUser user : tenantUsers) {
    if (user.isActive()) {
        user.setActive(false);
        appUserRepository.save(user);   // one UPDATE per user
        disabledCount++;
    }
}
```

**Suggested fix:** Add a single `@Modifying` JPQL query to `AppUserRepository`:

```java
@Modifying
@Query("UPDATE AppUser u SET u.active = false WHERE u.tenantId = :tenantId AND u.active = true")
int deactivateAllByTenantId(@Param("tenantId") UUID tenantId);
```

Then call it as a single statement inside `deactivateTenant`. This replaces O(N) round-trips with O(1).

---

### 2. Blacklist TTL uses config default instead of actual token expiry

**File:** `domain/service/AuthService.java:464–467`

`blacklistAccessToken` calls `jwtParser.parseQuiet(accessToken)` but then ignores the claims and instead computes `Instant.now().plusSeconds(jwtService.getAccessTokenTtlSeconds())` as the expiry. If a long-lived access token (e.g. one issued 14 minutes ago with 1 minute left) is blacklisted at logout, the Redis entry is set with a 15-minute TTL instead of a 1-minute TTL. The result is that Redis holds the blacklist entry 14 minutes longer than necessary. More importantly, the inverse problem: if the TTL config is later changed to be shorter, tokens issued before the change could be blacklisted with an insufficient TTL and become usable again.

The real `exp` claim is available via the JJWT `Claims` object but `JwtParser.parse()` does not expose it. `JwtClaims` record does not carry the expiry.

**Suggested fix:** Add `Instant expiresAt` to the `JwtClaims` record and return `Claims.getExpiration().toInstant()` from `JwtParser.parse()`. Use that value in `blacklistAccessToken`.

---

### 3. Missing `clearAutomatically = true` on `@Modifying` bulk JPQL updates

**File:** `domain/repository/AppUserRepository.java:47, 55, 68, 81`

`RefreshTokenRepository.java:31, 39`

All `@Modifying` JPQL UPDATE queries execute directly in the database, bypassing the Hibernate first-level cache. Any entity loaded in the same `EntityManager` before these updates (which is typical — services load an entity, then call these methods within the same `@Transactional` boundary) will have a **stale cached state** for the remainder of the transaction. This can silently produce incorrect audit snapshots, wrong MFA-enabled flag values in the return DTO, etc.

For example in `AuthService.verifyMfa` (line 306): `appUserRepository.findById(userId)` loads the user, then `appUserRepository.enableMfa(userId)` fires the JPQL UPDATE. The local `user` object still has `mfaEnabled=false`, and `user.setMfaEnabled(true)` is manually patched on line 307 — but this is a fragile workaround, not a fix.

**Suggested fix:** Annotate all `@Modifying` JPQL methods with `@Modifying(clearAutomatically = true)`. This forces Hibernate to flush and clear the first-level cache after the bulk operation.

---

### 4. `AdminMetricsService` uses `redisTemplate.keys()` instead of SCAN in production

**File:** `domain/service/AdminMetricsService.java:209`

Despite the Javadoc saying "SCAN is safer than KEYS", `scanOnlineAgentKeys()` calls `redisTemplate.keys(AGENT_SESSION_KEY_PATTERN)` which maps directly to the Redis `KEYS` command. `KEYS *` blocks the Redis event loop for the entire duration of the scan. On a Redis instance with tens of thousands of keys this causes latency spikes for all other operations (JWT blacklist checks, rate limiter, etc.) during every admin dashboard refresh (TTL 30 s).

**Suggested fix:** Use `redisTemplate.execute(connection -> connection.scan(ScanOptions.scanOptions().match(...).count(200).build()))` or Spring Data's `RedisTemplate.scan()` equivalent to iterate without blocking.

---

## Major Issues (should fix)

### 5. TOTP codes are not single-use — replay attack within the tolerance window

**File:** `security/MfaService.java:144–156`, `domain/service/AuthService.java:299`

`mfaService.verifyCode()` validates the TOTP code but does not mark used codes. The `allowedTimePeriodDiscrepancy = 1` window means the same 6-digit code is valid for up to 90 seconds (t-1, t0, t+1). An attacker who observes or intercepts the code (e.g. over the shoulder, phishing) can reuse it within that window. RFC 6238 §5.2 states that implementations should prevent reuse of a step within the same window.

**Suggested fix:** After successful verification, store the used code in Redis with a key like `mfa:used:{userId}:{code}` with a TTL of 90 seconds. Reject codes already in this set.

---

### 6. `AppUserRepository` does not extend `TenantAwareRepository` — RLS is not activated for JPA-managed queries

**File:** `domain/repository/AppUserRepository.java:23`

`AppUserRepository extends JpaRepository<AppUser, UUID>`. It is a Spring Data JPA interface, not an extension of `TenantAwareRepository`. This means `setTenantContextInDb()` is **never called** for any of its generated queries (`findById`, `findAllByTenantIdAndDeletedFalse`, `save`, etc.), so PostgreSQL's Row-Level Security policy on `app_user` is not activated for those queries.

The multi-tenancy is partially compensated by always passing `tenantId` as a query parameter, but the defense-in-depth RLS layer is absent for this critical table.

`RefreshTokenRepository`, `TenantRepository`, and `AuditLogRepository` have the same pattern. `TenantRepository` has a documented justification (ADMIN-only access to a non-RLS table) but the user repositories do not.

**Note:** The project architectural rule states "Every repository extends `TenantAwareRepository`". The current design uses Spring Data JPA interfaces which cannot extend a non-interface base. The standard approach is to extend `JpaRepository` **and** declare `TenantAwareRepository` as a custom fragment. The current mismatch between the stated architectural rule and the implementation should be explicitly documented or the rule revised.

---

### 7. `UserController.listUsers` discards pagination metadata

**File:** `api/user/UserController.java:108`

```java
return ResponseEntity.ok(userService.listUsers(tenantId, pageable).getContent());
```

`Page.getContent()` strips the `Page` wrapper and returns only the list elements. The caller receives no information about `totalElements`, `totalPages`, `number`, or `size`. Frontend cannot implement proper pagination controls without this metadata.

**Suggested fix:** Return `Page<UserResponse>` directly or wrap the result in a standard paged response DTO containing content + page metadata.

---

### 8. `AuditAspect.captureOldValue` executes a database read inside an `@Around` advice before the business operation

**File:** `infrastructure/aspect/AuditAspect.java:129–155`

`captureOldValue` invokes the getter method on the target service via reflection (line 147: `getter.invoke(target, entityId)`). For `UserService.updateUser`, this calls `getUser(userId, tenantId)` which issues a DB query. The business operation then calls `findUserOrThrow` again inside `updateUser` — a duplicate query on every update. As the audited operation set grows, this pattern scales to 2× the DB reads.

**Suggested fix:** Use the entity already loaded within the transactional method (e.g. pass it as part of a dedicated return type or use Hibernate `@PreUpdate` listener to capture state). Alternatively, document this as a known cost and ensure `@Transactional(readOnly = true)` is on the getter so both reads share the same transaction and Hibernate's L1 cache can serve the second one.

---

### 9. `InheritableThreadLocal` combined with virtual threads can cause context leaks

**File:** `security/TenantContext.java:49–52`

Java 21 virtual threads (`Thread.ofVirtual()`) do **not** inherit `InheritableThreadLocal` values from their parent in the way platform threads do — each virtual thread starts with a copy of the parent's inheritable context. If the application is later migrated to use virtual threads for the HTTP request pool (a common Java 21 upgrade), the `TenantContext` values will be inherited at thread creation time rather than set by `TenantFilter`. This means a stale tenant context from the parent thread could leak to the virtual thread if `TenantFilter` does not run first on that thread.

Spring Boot 3.2+ supports virtual threads via `spring.threads.virtual.enabled=true`. This flag is not present in any config file currently, but the risk should be acknowledged.

**Suggested fix:** Document this limitation explicitly. When virtual threads are enabled, the application must ensure `TenantContext.clear()` is called at the start of each request (already done in `TenantFilter`'s `finally` block), and that `TenantFilter` always executes on the same thread as the rest of the request processing chain.

---

### 10. `LaissezFaireSubTypeValidator` in `RedisConfig` accepts all types for deserialization

**File:** `infrastructure/config/RedisConfig.java:159–163`

```java
mapper.activateDefaultTyping(
        LaissezFaireSubTypeValidator.instance,   // no allowlist — accepts any class
        ObjectMapper.DefaultTyping.NON_FINAL,
        JsonTypeInfo.As.PROPERTY
);
```

`LaissezFaireSubTypeValidator` performs no validation on which Java types can be deserialized. If an attacker can write arbitrary JSON to Redis (e.g. via a Redis authentication bypass, SSRF to the Redis port, or a misconfigured internal network), they can achieve remote code execution via gadget chains in the Jackson polymorphic deserialization. This is the same class of vulnerability as CVE-2017-7525 (Jackson polymorphic deserialization RCE).

**Suggested fix:** Replace `LaissezFaireSubTypeValidator.instance` with a `BasicPolymorphicTypeValidator` that restricts allowed base packages:

```java
PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfBaseType("com.contactcenter")
        .allowIfSubType("java.util")
        .build();
mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
```

---

### 11. `UserController.listUsers` returns `List` not `Page` — `Pageable` parameter accepted but metadata dropped (duplicate, see #7 — separate security angle)

**File:** `api/user/UserController.java:104–109`

An ADMIN or SUPERVISOR with access to a tenant that has 100 000 soft-deleted or active users can pass `?size=100000` to retrieve the entire user table in one call. `@PageableDefault(size = 20)` sets the default but does not enforce a maximum page size.

**Suggested fix:** Add `@PageableDefault(size = 20, max = 100)` or configure `spring.data.web.pageable.max-page-size` globally.

---

### 12. `AuthService.refresh` issues new token with `mfaVerified = user.isMfaEnabled()`

**File:** `domain/service/AuthService.java:188`

```java
String newAccessToken = jwtService.issueAccessToken(user, user.isMfaEnabled());
```

When `user.isMfaEnabled() == true`, this issues a token with `mfaVerified = true` **without the user having provided their TOTP code in this session**. The intent was probably `false` (same as the initial login token), forcing MFA re-verification. The comment on line 187 says "mfaVerified state from previous session is preserved (refresh does not reset MFA)" — but this logic elevates `mfaVerified` unconditionally for MFA-enabled users.

Compare with `login` (line 124): `jwtService.issueAccessToken(user, !user.isMfaEnabled())` — a user with MFA enabled gets `mfaVerified=false` at login. But after a token refresh, they get `mfaVerified=true` without verifying the TOTP code.

**Suggested fix:** Issue the refreshed token with `mfaVerified = false` and require MFA re-verification, or embed the original `mfaVerified` value in the refresh token record and propagate it.

---

## Minor Issues (nice to fix)

### 13. `X-Request-Id` sanitization bug — length truncation operates on wrong string

**File:** `security/TenantFilter.java:198–200`

```java
return requestId.replaceAll("[^a-zA-Z0-9\\-_]", "").substring(
        0, Math.min(requestId.length(), 36)   // <-- uses original length, not sanitized length
);
```

After the `replaceAll` removes characters, the sanitized string may be shorter than `requestId.length()`. The `substring` end index is computed from the **original** string length, not the sanitized string, which will throw `StringIndexOutOfBoundsException` if the original `X-Request-Id` contains many special characters.

**Suggested fix:**
```java
String sanitized = requestId.replaceAll("[^a-zA-Z0-9\\-_]", "");
return sanitized.substring(0, Math.min(sanitized.length(), 36));
```

---

### 14. `AppUser.passwordHash` column length is 60 — too short for bcrypt cost > 12

**File:** `domain/model/AppUser.java:54`

```java
@Column(name = "password_hash", nullable = false, length = 60)
```

BCrypt hashes are always 60 characters. `length = 60` is correct for bcrypt cost 12 today. However, increasing the cost factor in the future (cost 13+ produces the same 60-character string but the comment says "cost=12 ~500ms") is not a concern. This is a documentation note: the column length is tight. If the hash algorithm is ever migrated (Argon2, scrypt), the column definition will need a migration. Worth noting in a TODO.

---

### 15. `AdminMetricsService.countOnlineAgentsForTenant` string-contains check is unreliable

**File:** `domain/service/AdminMetricsService.java:259–262`

```java
} else if (value instanceof String stringValue
        && stringValue.contains(tenantIdStr)) {
    count++;
}
```

`UserService.updateStatus` stores only the status string (e.g. `"AVAILABLE"`) in the Redis key `session:agent:{userId}` (line 315 in `UserService.java`). A plain status string never contains a UUID. The `Map`-based branch also appears unreachable because `UserService` stores `String`, not `Map`. The fallback string-contains branch will never match, and the entire `countOnlineAgentsForTenant` will always return 0 — the admin metrics dashboard always shows 0 agents online regardless of actual status.

**Suggested fix:** Either store a structured object (e.g. `Map.of("tenantId", tenantId, "status", status)`) in Redis from `UserService.updateStatus`, or look up the agent-to-tenant mapping differently (e.g. from the DB or a separate `session:agent:{userId}:tenant` key).

---

### 16. `AuditLogConsumer` is not `@Transactional` safe — mismatches with `@Transactional` at listener level

**File:** `domain/service/AuditLogConsumer.java:50–51`

`@Transactional` on a `@RabbitListener` method does not integrate with RabbitMQ's acknowledge-mode `manual` (configured in `application-prod.yml`). Spring AMQP with manual acks requires the acknowledgment to be sent explicitly via `Channel`. The `@Transactional` annotation will not call `channel.basicAck` automatically. If the method commits the DB transaction successfully but the AMQP ack is never sent (due to an exception after the transaction boundary), the message will be redelivered and the audit entry will be duplicated.

**Suggested fix:** Either use `acknowledge-mode: auto` for the audit queue (acceptable since audit writes are idempotent with a UUID log_id), or switch to `Channel`-based manual acknowledgment.

---

### 17. `TenantService` has a circular dependency resolved with `@Lazy` — this is a design smell

**File:** `domain/service/TenantService.java:49–62`

The `@Lazy` setter injection for `AdminMetricsService` indicates a circular dependency: `TenantService → AdminMetricsService → TenantRepository ← TenantService`. This is a symptom of placing `evictGlobalMetricsCache()` in `AdminMetricsService` while calling it from `TenantService`. The cleaner solution is to use a Spring `ApplicationEvent` for cache invalidation, or to move the eviction to a `@CacheEvict` on the `TenantService` methods directly.

---

### 18. `GlobalExceptionHandler` maps `IllegalStateException` to HTTP 409 — too broad

**File:** `api/GlobalExceptionHandler.java:302–313`

`IllegalStateException` is a general-purpose JVM exception. Mapping it to HTTP 409 means any library code that throws `IllegalStateException` (e.g. calling a method on a closed stream, Spring Framework internal assertions) will return HTTP 409 to the client rather than HTTP 500. This could mask real server errors as business logic conflicts.

**Suggested fix:** Create a specific `InvalidOperationException extends RuntimeException` for domain state violations (MFA already active, etc.) and map that to 409. Let `IllegalStateException` fall through to the generic 500 handler.

---

### 19. `UserDetailsServiceImpl.loadUserByUsername` loads deleted/inactive users

**File:** `security/UserDetailsServiceImpl.java:62`

```java
AppUser user = appUserRepository.findByTenantIdAndEmail(tenantId, email)
```

This finds any user regardless of `is_deleted` or `is_active`. Deleted users (`is_deleted=true`) will be found, loaded, and returned as valid `UserDetails`. The `AppUserDetails` wraps the entity and sets `isEnabled()` based on `user.isActive()`, so authentication will fail — but a deleted user's record is still queried and the `AppUserDetails` object is constructed. The preferred method `findByTenantIdAndEmailAndActiveTrue` already exists in the same repository. Using it would reduce the scope and avoid the explicit deleted-user path.

**Suggested fix:** Use `findByTenantIdAndEmailAndActiveTrue` here, or add `AND is_deleted = FALSE` to the query. Document the choice.

---

### 20. `swagger-ui` and `api-docs` are enabled and publicly accessible in production

**File:** `application.yml:143–149`, `application-prod.yml` (no override)

`springdoc.swagger-ui.enabled=true` and `springdoc.api-docs.enabled=true` are set in `application.yml` and are not overridden in `application-prod.yml`. Both are correctly added to `SecurityConfig.permitAll()` and `TenantFilter.PUBLIC_PATH_PREFIXES`, but they should be disabled in production to avoid exposing API schema to unauthenticated users.

**Suggested fix:** Add to `application-prod.yml`:
```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

---

## Positive Observations

- **Filter chain order is correct and well-documented.** `JwtAuthFilter` is registered before `TenantFilter`, which is before `UsernamePasswordAuthenticationFilter`. The code comments match the implementation.

- **JWT blacklisting uses SHA-256 hash stored in Redis with the correct namespace** (`jwt:blacklist:{hash}`), not raw tokens. `TokenBlacklistService` uses `HexFormat.of().formatHex()` (Java 17+ idiomatic) and handles the expired-before-blacklist case gracefully.

- **TenantContext lifecycle is properly managed.** `TenantFilter` clears both `TenantContext` and MDC in a `finally` block, preventing cross-tenant context leakage on thread pool reuse.

- **`TenantContext.snapshot()/restore()/clear()` pattern is implemented** and documented with code examples, fulfilling the async propagation requirement.

- **Multi-tenancy is enforced at the query level** via explicit `tenantId` parameters on every repository method, providing defense-in-depth beyond RLS.

- **Sensitive fields are excluded from `toString()` and audit serialization.** `AppUser` has `@ToString(exclude = {"passwordHash", "mfaSecret"})` and `AuditAspect.SENSITIVE_FIELDS` removes them from JSON snapshots.

- **`@Modifying` queries in `AppUserRepository` include tenant_id** in the WHERE clause (`softDeleteUser`, `setPasswordResetRequired`), preventing cross-tenant writes even without RLS activation.

- **`BCryptPasswordEncoder(12)` cost factor is appropriate** (~500ms per hash, effective against offline brute-force) and is documented.

- **Login rate limiter uses Redis INCR + EXPIRE** correctly for atomicity, with TTL set only on the first increment (sliding window from first failed attempt).

- **RFC 7807 Problem Details** is used consistently across the entire API, including in `TenantFilter`'s direct HTTP error writes.

- **`application-prod.yml` does not contain `clean-on-validation-error` or `clean-disabled: false`** — the dev-only danger flags are absent from production config.

---

## Summary

⭐⭐⭐ (3/5) — The security fundamentals (JWT, blacklisting, filter chain, RLS approach) are sound, but the `refresh` endpoint grants `mfaVerified=true` without TOTP verification, the TOTP window has no replay protection, `deactivateTenant` will cause an outage on any real-scale dataset, and the admin metrics dashboard always shows 0 agents online due to a Redis value format mismatch. These issues collectively block production readiness.
