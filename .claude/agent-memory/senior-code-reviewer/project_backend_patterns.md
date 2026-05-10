---
name: Backend Java/Spring Boot patterns and known issues
description: Spring Boot 3.3.5 / Java 21 backend: critical bugs, security issues, architecture violations, and positive patterns. Updated 2026-04-01 after Twilio Recording Pipeline review.
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

## BE-021 (Wait Time Estimation / EWT) — new issues found 2026-03-26

**Critical bugs — must fix before merge:**
- `countWaitingByQueueId` and `getAvgHandleTimeSeconds` in `ContactRepository` do not filter `is_deleted = false`. Soft-deleted contacts with status QUEUED inflate `waitingCount`, producing incorrect EWT. Same risk for AVG handle time if deleted contacts have anomalous durations.
- `QueueController.getQueueStats` reconstructs a partial `Queue` entity from DTO fields (only 3 of ~10 fields set). Passed to `WaitTimeEstimationService.getQueueStats`. If service is ever extended to access `routingStrategy` or other fields on the partial entity, NPE at runtime. Anti-pattern: controller should pass UUIDs, let service load entity from repository.
- `GET /api/queues/{id}/stats` triggers full Redis SCAN on every HTTP request without caching — potential DoS vector. Cache result with short TTL (5–10s) using Redis key `cache:queue:stats:{queueId}`.

**Architecture violations:**
- `broadcastWaitTimeUpdates` uses `fixedRate` (not `fixedDelay`) — second invocation can start before first completes if processing takes > 30s. No ShedLock for multi-instance deployments. Two pod instances would double-broadcast EWT every 30s.
- `tenantRepository.findAllByOrderByNameAsc()` fetches ALL tenants to Java, then filters ACTIVE in stream. Should use `findAllByStatusOrderByNameAsc(ACTIVE)` for DB-side filtering.
- Hardcoded `1000` queue limit per tenant in `processTenant` — silent truncation with no WARNING log when limit is hit.

**Pattern to check:** new ContactRepository query methods for `is_deleted = false` filter — three new queries in this PR all skip it. Establish rule: every new SQL query on `contact` table must include `AND is_deleted = false` unless explicitly querying deleted records.

**Redis SCAN per-tenant pattern:** Both `DefaultRoutingEngine` (BE-019) and `WaitTimeEstimationService` (BE-021) scan `session:agent:*` (all tenants) and filter in Java. Root fix: change key namespace to `session:agent:{tenantId}:{userId}` in `UserService.updateStatus`. Both consumers would then SCAN `session:agent:{tenantId}:*`. Track as known tech debt.

**Positive patterns in BE-021:**
- Single Redis SCAN shared across all tenants in one scheduler tick — good N→1 optimization.
- Resilient try/catch per-queue and per-tenant in scheduler — errors in one queue don't affect others.
- EWT formula documented in Javadoc with all edge cases clearly stated.
- `countAvailableAgents` cross-tenant isolation via `tenantId` field check in Redis session map.

## Twilio Recording Pipeline — issues found 2026-04-01

**Critical — must fix before production deploy:**
- `resolveContactIdFromConference` in `TwilioWebhookController` makes a synchronous blocking Twilio REST API call (Conference.fetcher().fetch()) in the webhook HTTP thread. Default Twilio SDK timeout = 30s. Can exhaust Tomcat thread pool under load, blocking all webhooks including voice (error 12100). Must be moved to async method.
- No HMAC-SHA256 signature verification (`X-Twilio-Signature`) on ANY webhook endpoint. Allows anyone to send fake recording callbacks, triggering arbitrary HTTP downloads in `TwilioRecordingDownloadService` (SSRF vector). Twilio `RequestValidator` (SDK) must be added.
- `findContactIdByConferenceSid` in `ContactRepository` is dead code — controller never calls it, uses Twilio API call instead. Inconsistency: either the DB lookup is sufficient (use it, delete `resolveContactIdFromConference`) or document clearly why DB lookup is unreliable (and delete `findContactIdByConferenceSid`).
- `updateConferenceSidInMetadata` in `ContactRepository` missing `assertSameTenant()` before native UPDATE — same pattern as C3 (clearRecordingUrl) fixed in BE-027 CR. Recurring violation.

**Architecture violations:**
- `saveRecordingUrlToContact` in `RecordingService` uses simplified `setTenantId` + try/finally pattern instead of project-standard `snapshot()/restore()/clear()` for async boundaries. Functionally correct (fresh async thread starts clean), but deviates from documented project standard.
- `HttpClient` created per-call in `TwilioRecordingDownloadService.downloadToTempFile` — heavy object (thread pool + connection pool) instantiated for each recording download. Should be `private final` field.

**Minor issues:**
- `buildS3Key` in `TwilioRecordingDownloadService` passes `timestamp=null`, using `Instant.now()` instead of contact's `startedAt`. Edge case: calls crossing midnight will be stored in wrong month folder. S3 key saved to DB after upload, so data is not lost, but folder structure diverges from contact timestamp.
- `recordingSid` used unvalidated in `Files.createTempFile("twilio_rec_" + recordingSid + "_", ".mp3")` — path component from untrusted input. JDK sanitizes in practice, but should be sanitized explicitly.
- `recordingUrl.endsWith(".mp3")` check fails if URL has query string — should check `URI.getPath().endsWith(".mp3")`.
- Hardcoded ngrok URL as default `app.base-url` and `status-callback-url` in `application.yml` — staging/CI without APP_BASE_URL set will route Twilio webhooks to developer's tunnel.

**Positive patterns:**
- Temp file + streaming download (no byte[] buffering) — correct approach for large audio files.
- `@Async` for download task — webhook returns 204 immediately.
- `buildBasicAuthCredentials()` validates non-blank credentials before encoding.
- All endpoints correctly call `TenantContext.clear()` in `finally` blocks.

**Check in future Twilio-related reviews:**
- HMAC signature verification present? (`RequestValidator` from Twilio SDK)
- Any synchronous Twilio API calls in webhook thread paths?
- `HttpClient` / external HTTP clients instantiated as beans, not per-call?
- `assertSameTenant()` present before every native UPDATE in `ContactRepository` and similar repos?

## EPIC-21 (Retry/Callback in Outbound Campaigns) — issues found 2026-05-08

**Critical — must fix before production deploy:**
- `ScheduledCallbackExecutor.processCallback`: `markAsDialingForCallback()` is called BEFORE `telephonyAdapter.initiateCall()` inside the `try` block, but outside of it. On `TelephonyException`, the `campaign_contact` stays in `DIALING` forever — no rollback to `CALLBACK`. Requires adding rollback in `catch` block.
- `DialerCallbackHandler.onCallHangup`: Redis `cleanupRedisKeys()` is NOT called in the `catch` block for the NO_ANSWER path. If `handleNoAnswer()` throws, the `dialer:agent:{agentId}` lock is never released, permanently blocking the agent. `cleanupRedisKeys()` must be moved inside the `finally` block for all paths.
- `isPastEndDate` in `CampaignWindowActivator` calls `LocalDate.parse(endDateStr)` without try/catch for `DateTimeParseException` — malformed `end_date` in the `schedule` JSONB field throws unchecked exception that escapes the `try` block in `processRunningCampaignsForTenant`, disrupting ALL campaigns for the tenant for that scheduler tick.
- `CampaignContactRepository.markAsDialingForCallback`: calls `setTenantContextInDb(tenantId)` AND then immediately calls `jdbcTemplate.execute("SELECT set_tenant_context(...)` again — double set_tenant_context. Second call is unreachable no-op but adds latency and confusion. Remove the duplicate.

**Architecture violations:**
- `CampaignWindowActivator.processRunningCampaignsForTenant`: calls `campaignRepository.save(campaign)` which calls `assertSameTenant()`. `TenantContext` is set via `TenantContext.setTenantId()` but never via `TenantContext.setUserId()` — `assertSameTenant()` only checks tenantId (not userId) so this works, but pattern is documented as "set full context before write". Acceptable here as scheduler has no userId; document explicitly.
- `handleCallbackDisposition` Javadoc still says "Aktualizuje status rekordu campaign_contact na COMPLETED" — stale after BE-064 changed status to CALLBACK. Should be fixed to avoid misunderstanding.
- Redis `dialer:call:*` TTL is 1800s in `ProgressiveDialerService.CALL_STATE_TTL_SECONDS` and hardcoded 1800 in `ScheduledCallbackExecutor`. Not using the constant. Should reference the same source.

**Minor issues:**
- `isNoAnswerOutcome` handles null explicitly (returns false = treated as COMPLETED). This is documented in `CallEvent.callOutcome` Javadoc, correct. However, a `failed` Twilio outcome (network error) is treated as `COMPLETED` rather than `FAILED`. Business decision should be documented.
- `DEFAULT_RETRY_DELAY_MINUTES = 60` in `DialerCallbackHandler` is the fallback when campaign is not loadable. This fallback is silently applied — only a WARN log. In production, if `campaignRepository.findById` fails, retries will use 60min regardless of campaign config. Acceptable but should be noted.
- `CampaignWindowActivator.completePastDeadlineCampaigns` iterates active tenants and calls `processRunningCampaignsForTenant` which iterates RUNNING+PAUSED campaigns. There is a risk of completing PAUSED campaigns that the user manually paused, which might not be intended. Business logic gap — should consider only RUNNING.

**Positive patterns in EPIC-21:**
- `dialer:callback-attempt:{callSid}` marker key correctly separates callback-attempt from normal dialer flow in `handleNoAnswer` — clean two-path design.
- `updateStatusIfPending` optimistic lock correctly prevents double-processing in multi-node deployment.
- `NOT_REACHED` status (vs old `FAILED`) correctly distinguishes "exhausted retries" from "technical error" — good semantic clarity.
- Removal of `isCalledTooRecently` hardcoded 4h guard in favor of database-authoritative `next_attempt_at <= NOW()` — correct simplification.
- Test coverage of edge cases (callback-attempt marker present/absent, attempt_count boundaries) — solid.
- New migration `V054` explicitly documents lack of FK with explanation about composite PK — honest and clear.

**Check in future Dialer/Callback related reviews:**
- Does `processCallback` rollback `campaign_contact` to CALLBACK status on telephony failure?
- Is `cleanupRedisKeys` called in `finally` (not only in success path)?
- Are all `LocalDate.parse` calls in scheduler wrapped with `DateTimeParseException` catch?
- Does `markAsDialingForCallback` call `setTenantContextInDb` exactly once?

## BE-024 (Progressive Dialer) — issues found 2026-04-08

**Critical:**
- `@Transactional` self-invocation bug: `ProgressiveDialerService.initiateDialForAgent` is `@Transactional` but called directly (line 131: `this.initiateDialForAgent(...)`) — Spring AOP proxy bypassed, transaction never starts. `FOR UPDATE SKIP LOCKED` lock is immediately released, race condition protection is non-functional. Fix: call through self-injected proxy or extract to separate `@Service` bean.
- `TenantContext.clear()` in `DialerCallbackHandler.handleCallbackDisposition` `finally` block clears context set by `TenantFilter` for HTTP thread — `handleCallbackDisposition` is called from `DialerController` (HTTP) and from potential RabbitMQ paths. Clearing HTTP context corrupts request lifecycle.
- N+1 queries in `DialerController.getDialerStatus`: 3×N separate `countContactsByStatus` calls (each with `set_tenant_context`) per running campaign. Fix: single GROUP BY query.
- String concatenation `"SELECT set_tenant_context('" + tenantId + "'::uuid)"` in 6+ places: `DialerController`, `ScheduledCallbackRepository`, `ProgressiveDialerService`, `DialerCallbackHandler`. **Recurring violation from previous reviews** — never fixed. Must use prepared statement or `setTenantContextInDb()`.

**Security:**
- `POST /api/dialer/manual/call` allows ADMIN and SUPERVISOR despite being agent-only operation — SUPERVISOR has no softphone, allocates DIALING record with no agent to answer. Should be `hasRole('AGENT')` only.
- `POST /api/dialer/callbacks` allows any AGENT to set `agentId` from request body to another agent's UUID — missing tenant validation of `request.agentId()`.

**Architecture violations:**
- `JdbcTemplate` injected directly in `DialerController` — controller executes raw SQL. Violates layered architecture, makes controller untestable without DB. Move to `CampaignContactRepository`.
- Business logic (record grouping, campaign filtering) in `DialerController.getManualCampaignRecords` — should be in service layer.
- `cc.queue.dialer-hangup` queue declared inline in `@QueueBinding` annotation, not in `RabbitMQConfig` — breaks centralized queue management pattern.
- `CreateCampaignRequest.dialerType` and `type` are unvalidated strings — should have `@Pattern`.

**Minor:**
- Redis call state stored as CSV (`a,b,c,d`) — should be JSON for robustness.
- `DEFAULT_ZONE` hardcoded as `Europe/Warsaw` — should be configurable per tenant.
- `findPendingByCampaignIds` has no result LIMIT — can return 10k+ rows for large manual campaigns.
- `isCalledTooRecently` redundant with `next_attempt_at <= NOW()` SQL filter in same method.

**Positive:**
- Separate `cc.queue.dialer-agent-status` queue correctly solves consumer competition (each event consumed by both routing and dialer).
- Redis agent lock with TTL 60s — correct race condition guard for multi-instance deployment.
- `FOR UPDATE SKIP LOCKED` in `fetchNextPendingContact` — correct job queue pattern (when transaction works).
- Rollback DIALING→PENDING on telephony error in manual call flow.
- `@ConditionalOnProperty(name = "dialer.enabled")` on both beans — environment-controlled feature flag.
- `ScheduledCallbackRepository` correctly extends `TenantAwareRepository` and calls `assertSameTenant()` before write.
- Phone number masking in logs.

**Check in future Dialer-related reviews:**
- Does `initiateDialForAgent` run inside a real Spring-managed transaction?
- Is `set_tenant_context` called via prepared statement or string concat?
- Is `DialerController` free of `JdbcTemplate` calls?

## BE-017 (OAuth / Social Media Tokens) — issues found 2026-04-16

**Critical — must fix before production deploy:**
- OAuth `state` parameter generated in `initiateOAuth()` but NEVER persisted (Redis/session) nor verified in `oauthCallback()` — OAuth CSRF protection is completely non-functional. state must be stored in Redis with TTL and consumed once in callback.
- Access token in URL query string in `revokeTokenAtProvider()`: `access_token=%s` — token leaks into proxy/nginx access logs and load balancer logs. Must use `Authorization: Bearer` header instead.
- Blocking `HttpClient.send()` inside `@Transactional` method `deleteIntegration()` — holds DB connection/lock while waiting for external Graph API response. Move revoke call outside transaction.
- `oauthCallback()` is PUBLIC (no JWT) but calls `saveIntegration()` which reads `TenantContext.getTenantId()` — TenantContext is empty for public endpoints, so getTenantId() returns null. Entire save flow always fails. tenantId must be embedded in `state` parameter.
- `InterruptedException` swallowed in `catch (Exception e)` in `revokeTokenAtProvider()` — must re-interrupt thread.

**Architecture violations:**
- `refreshToken()` uses `TenantContext.setTenantId()` directly instead of project-standard `snapshot()/restore()/clear()` pattern. Also sets only tenantId, not tenantName — downstream consumers of TenantContext.getTenantName() get null.
- `findAllExpiringBefore()` is `public` but bypasses RLS (no setTenantContextInDb) — should be package-private or guarded by assertion that TenantContext is empty.
- `exchangeForLongLivedToken()` stub silently returns same token but writes 60-day future expiry — scheduler logs success and updates DB despite doing nothing. Should throw `UnsupportedOperationException`.
- `exchangeCodeForToken()` stub returns authorization code as token — saves invalid token to DB without error.

**Database issues:**
- `social_integration` table missing `is_deleted` column — hard delete used, violates project soft-delete convention.
- V012 RLS policy for `social_integration` is SELECT-only — no INSERT/UPDATE/DELETE policies. Write isolation relies only on application-level `assertSameTenant()`. Fix: new migration V041 with write policies.

**Minor / recurring:**
- `facebookRedirectUri` and `instagramRedirectUri` not URL-encoded in `buildAuthorizationUrl()` — special characters in redirect URI break OAuth URL parsing.
- `HttpClient` created as field without `connectTimeout` — unbounded wait on Graph API unavailability.
- Error message from exception appended to audit log details — potential info leakage.

**Positive patterns:**
- AES-256-GCM implementation is textbook-correct: 12-byte random IV per encryption, 128-bit GCM tag, `[IV|ciphertext+tag]` format, `SecureRandom`, `SecretKeySpec`.
- No token in any log line — all logs explicitly redact or omit access tokens.
- `SocialIntegrationRepository` extends `TenantAwareRepository`, `assertSameTenant()` called before every write, `setTenantContextInDb()` before every read.
- `finally { TenantContext.clear() }` present in scheduler loop.
- DTO never exposes `accessTokenEncrypted` field.
- SecurityConfig + TenantFilter.PUBLIC_PATH_PREFIXES both updated for `/api/oauth/*/callback`.
- `SocialTokenEncryptionServiceTest` covers round-trip, IV uniqueness, Unicode, tampering, null/empty — solid unit test coverage.

**Check in future social/OAuth related reviews:**
- Is `state` stored in Redis and consumed once in callback?
- Is `tenantId` propagated through `state` parameter for public OAuth callbacks?
- Are tokens never in URL query strings (must use Authorization header)?
- Is `exchangeForLongLivedToken()` fully implemented (not stub)?
- Are RLS write policies present for any new table with `ENABLE ROW LEVEL SECURITY`?

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
