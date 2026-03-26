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

## BE-020 (Queue API) — new issues found 2026-03-21

**Critical — not fixed at time of review:**
- `skillsToJson` (manual JSON serialization) in `QueueRepository` does not handle control chars (`\n`, `\r`, `\t`), only backslash and quote. Same bug that existed in `ContactRepository.channelMetadataToJson` before BE-027 fix. Requires `ObjectMapper.writeValueAsString()`.
- `@PrePersist`/`@PreUpdate` in `Queue` are dead code — entity uses native INSERT/UPDATE, lifecycle callbacks never fire. Pattern fixed in `Contact` (BE-027 CR) but not applied to `Queue`.
- Missing `@Size(max=255)` on `name` in `CreateQueueRequest` — DB constraint can cause HTTP 500 instead of 400.
- `UpdateQueueRequest.name` accepts empty string `""` on PATCH (no `@NotBlank` guard for non-null values).

**Architectural notes:**
- `em.clear()` after native UPDATE in `QueueRepository.update()` clears entire L1 cache — risky if Queue entity gains lazy associations. Monitor when Queue entity is extended.
- `findAllByTenantId` returns only `is_active=true` queues — no way to list inactive queues for audit. Intentional product decision (not documented).
- `ROUTING_STRATEGIES` static list in `QueueService` duplicates DB ENUM — must stay in sync.

**Test gaps:**
- No tests for `updateQueue` (PATCH null-field logic, EntityNotFound when update=0).
- No test for `deleteQueue` when `softDelete` returns 0 (race condition / TOCTOU).
- `@MockitoSettings(strictness = LENIENT)` masks unused stubs — should use default STRICT_STUBS.

## BE-019 (Routing Engine) — new issues found 2026-03-21

**Critical architecture errors:**
- `RoutingService.publishQueuedEvent` publishes `contact.queued` when no agents available — `onContactQueued` listener immediately receives it, creating an infinite retry loop. TTL=30s limits duration but causes tight loop for 30s. Fix: remove `publishQueuedEvent` from "no agents" branch; use external scheduled job for retry.
- `routeContact` is `@Async` but called from `@RabbitListener`. `CompletableFuture` returned by `@Async` is not awaited by `onContactQueued` — exceptions in async thread cause silent AMQP ACK (message consumed, routing failed). Fix: remove `@Async` from `routeContact` — AMQP thread blocking is acceptable for fast routing operations.
- `@Async` + `@Transactional` without `TenantContext.snapshot()/restore()/clear()` — breaks architectural invariant. No current data leak (explicit tenantId in all queries), but fragile: any new code using `TenantContext.getTenantId()` in `routeContact` will throw ISE at runtime.

**Security:**
- Sticky agent: `belongsToTenant()` verified only from Redis data. When `requiredSkills` is empty, no DB cross-check is performed — tenant isolation depends on Redis correctness. Should always call `findByIdAndTenantIdAndDeletedFalse` for sticky agent, regardless of skills.

**Performance:**
- `findAgentWithLeastActiveContacts` runs N individual SQL queries per routing call (one per candidate agent). For SKILL_BASED strategy this is up to 50 queries. Need batch query: `COUNT(*) GROUP BY agent_id WHERE agent_id IN (:ids)`.
- Redis SCAN uses `session:agent:*` (all tenants) — filters by tenantId in Java. At 5000 keys, routing for one tenant scans all 5000. Should use per-tenant key pattern `session:agent:{tenantId}:*`.

**Minor:**
- ROUND_ROBIN fallback counter `0L` selects index 1 (not 0) when list size >= 2 — fallback should use `1L`.
- `ContactQueuedMessage` (and `ContactAssignedEvent`) missing `@JsonIgnoreProperties(ignoreUnknown = true)` — rigid to schema evolution.

**Positive patterns:**
- Redis SCAN (iterative, count=200) — correct, not KEYS. Documented.
- `AgentSessionData.belongsToTenant()` guards against NPE.
- Deterministic tie-breaking in ROUND_ROBIN and FIRST_AVAILABLE via UUID string sort — consistent across app instances.
- `@Primary` on `DefaultRoutingEngine` follows established MockTelephonyAdapter pattern.

## Email routing / Queue email address (V029 + EmailRoutingService) — 2026-03-26

**Critical bugs found — not fixed at time of review:**
- `QueueRepository.insert()` and `update()` — native SQL does NOT include `email_address` column. Despite V029 migration adding the column and `Queue.emailAddress` existing in entity, every queue INSERT and UPDATE silently drops the email address. The field is never persisted to the database. This makes the entire feature non-functional end-to-end.
- `EmailRoutingService.findMatchingQueue()` — split by comma does not extract email from RFC 5322 format `"Name <email@domain.com>"`. Real email clients (Gmail, Outlook) always produce this format in `To:` headers. Routing by queue email address will silently fail in production for typical SMTP traffic.

**Pattern to check in future:** When native SQL INSERT/UPDATE is extended for a new column, always cross-check that ALL columns in entity are represented. QueueRepository is particularly risky because it uses fully manual native SQL (not JPA save).

**New ObjectMapper issue (recurring pattern):** `matchesRule()` creates `new ObjectMapper()` per invocation instead of injecting shared instance. Same anti-pattern was in `QueueRepository.skillsToJson` (BE-020). When reviewing new services — check for `new ObjectMapper()` inside methods.

**Test gap pattern:** Missing test for RFC 5322 address format `"Name <email>"` — a format universally produced by real email servers. Any email routing test suite must include this case.

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
