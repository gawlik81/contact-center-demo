---
name: Backend Java/Spring Boot patterns and known issues
description: Spring Boot 3.3.5 / Java 21 backend: critical bugs, security issues, architecture violations, and positive patterns. Updated 2026-03-20 after BE-027 review.
type: project
---

First full backend review completed 2026-03-17. BE-027 (Contact API) reviewed 2026-03-20.

**Why:** These are recurring patterns that will appear in future incremental reviews of new features.

**How to apply:** In future incremental reviews: check new repositories for `TenantAwareRepository` extension (or document why not), verify all `@Modifying` JPQL have `clearAutomatically=true`, verify new public endpoints appear in both SecurityConfig and TenantFilter.PUBLIC_PATH_PREFIXES, check any new async methods for snapshot/restore/clear. For partitioned tables — verify native INSERT/UPDATE, no @PrePersist/@PreUpdate reliance, em.flush()+em.clear() after native UPDATE before re-read.

---

## Status of previous issues (as of 2026-03-20)

**FIXED:**
- N+1 in `deactivateTenant` — replaced with single `@Modifying` JPQL UPDATE.
- Blacklist TTL uses config instead of `exp` claim — now uses `claims.expiresAt()`.
- `AuthService.refresh` grants `mfaVerified=true` without TOTP — now always `false`.
- TOTP replay attack — single-use codes stored in Redis with TTL 90s.
- `redisTemplate.keys()` blocking call — replaced with iterative SCAN.
- `LaissezFaireSubTypeValidator` — replaced with `BasicPolymorphicTypeValidator`.
- `TenantFilter.generateRequestId` substring bug — now uses sanitized string length.
- `UserController.listUsers` discards pagination metadata — now returns `PagedResponse<UserResponse>`.
- Most `@Modifying` queries now have `clearAutomatically=true`.

**STILL OPEN (not fixed as of 2026-03-20):**
- `AdminMetricsService.countOnlineAgentsForTenant` always returns 0 — `UserService.updateStatus` stores plain String in Redis, not Map with tenantId. Logika działa ale nigdy nie trafi w branch Map.
- `AuditAspect.captureOldValue` causes 2× DB read per audited operation (double-read pattern).
- `AuditLogConsumer` — `@Transactional` + manual AMQP ack mismatch.
- `TenantService` circular dependency with `AdminMetricsService` resolved via `@Lazy`.
- `GlobalExceptionHandler` maps `IllegalStateException` → HTTP 409 (too broad).
- `UserDetailsServiceImpl` loads deleted users via `findByTenantIdAndEmail`.
- Swagger UI / api-docs not disabled in `application-prod.yml`.
- `spring.threads.virtual.enabled` not set — virtual thread + InheritableThreadLocal risk dormant.

---

## BE-027 (Contact API) — new issues found 2026-03-20

**Critical — fixed in review:**
- `ContactService.getContact` lacked AGENT ownership check — any AGENT could read any contact in tenant. Fixed: new signature `getContact(UUID, UUID, UUID, boolean)`.
- Hibernate L1 cache stale after native UPDATE in `update()` — `em.flush()+em.clear()` added to `ContactRepository.update()`. Without this, `updateContact` and `setDisposition` returned pre-update data (trigger-computed `duration_seconds`, `updated_at` never reflected).
- `clearRecordingUrl` missing `assertSameTenant()` before UPDATE.
- `softDeleteUser` missing `clearAutomatically=true` on `@Modifying`.

**Remaining issues in BE-027:**
- `channelMetadataToJson` — manual JSON serialization; does not handle nested Map/List, does not escape Unicode control chars. Should use `ObjectMapper.writeValueAsString()`.
- `@PrePersist`/`@PreUpdate` on `Contact` entity are dead code — partitioned table uses native SQL, JPA lifecycle callbacks never fire.
- `status` and `channel` filter params not validated in `ContactController` — invalid enum value causes PostgreSQL error mapped as HTTP 500 instead of 400.
- `DispositionRequest.dispositionCode` has no enum whitelist — open string, no CHECK constraint in DB.

---

## Architectural patterns observed in BE-027

**Partitioned table pattern (new in BE-027):**
- Partitioned table `contact` (RANGE by `started_at`) uses composite `@IdClass(ContactId)` with `(contact_id, started_at)`.
- `ContactRepository extends TenantAwareRepository` — correct. Uses `em.createNativeQuery()` for INSERT/UPDATE, JPQL for SELECT (Hibernate queries parent table which delegates to partitions).
- After native UPDATE, always call `em.flush() + em.clear()` before re-reading if trigger side-effects are expected.
- `@PrePersist`/`@PreUpdate` do NOT fire on partitioned tables accessed via native SQL — do not rely on them.

**AGENT isolation pattern (confirmed/reinforced):**
- All read and write methods that accept AGENT role must enforce `effectiveAgentId = isAgent ? userId : requestedAgentId`.
- Not just list/update — also single-entity GET endpoints must verify ownership.

**Positive patterns in BE-027:**
- `assertSameTenant()` called in all write methods of `ContactRepository`.
- `setTenantContextInDb(tenantId)` called before every DB operation.
- Dynamic WHERE with parameterized binding — no SQL injection risk.
- `MAX_PAGE_SIZE = 100` enforced server-side.
- Full `PagedResponse` with metadata returned.
- OpenAPI `@Operation`/`@ApiResponse` on all endpoints including 409 cases.
