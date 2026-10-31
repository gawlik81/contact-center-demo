---
name: project-be157-active-contacts
description: BE-157 GET /api/agent/me/active-contacts - ACW as derived state, bulk name lookups, EXPLAIN result, test harness with restricted RLS role
metadata:
  type: project
---

BE-157 (EPIC-32): `AgentSelfController#getActiveContacts` -> `ContactService.findActiveContactsForAgent` -> `ContactRepository.findActiveContactsForAgent` (JPQL, one query, limit 20).

- ACW is derived: COMPLETED + disposition_code IS NULL + channel='PHONE' + ended_at >= now - `agent.active-contacts.wrap-up-window` (default 12h, `@Value` on a non-final field with default so `@InjectMocks` tests work).
- Names resolved in bulk: new `QueueService.findQueueNamesByIds`, `CustomerService.findDisplayNamesByIds` (anonymized/blank excluded). `ContactServiceImpl` now has `CustomerService` in its constructor -> `ContactReleaseIntegrationTest` constructs it manually and had to be updated.
- `channel` in response goes through `ContactChannels.toAgentDesktopType` (SOCIAL_* -> SOCIAL), like `assigned-contact`; the ticket text said "raw" but BE-155 already unified it.
- `contact.channel` CHECK (V025) allows only PHONE/EMAIL/SOCIAL_FACEBOOK/INSTAGRAM/WHATSAPP - CHAT is NOT insertable.
- `fn_contact_ref_integrity` trigger: tests must insert `app_user` rows in the SAME tenant before contacts with agent_id (same agent id across tenants is impossible).
- EXPLAIN (local demo DB, 12 partitions): no partition pruning (no started_at predicate), each partition uses `(tenant_id, agent_id, started_at)` idx_contact_agent_history then filters status; cost grows with agent's whole history. Candidate for DB-088 (partial index on (tenant_id, agent_id) WHERE status in open ones, or started_at bound). No migration made.
- Test harness: restricted role via `PostgresTestDatabase.createRestrictedLoginRole` + `pool(role,...)`, seeding through superuser pool, `set_tenant_context` is transaction-local (raw JDBC needs autoCommit=false).

## Round 2 (code review)
- Limit semantics: two queries (open contacts, then ACW newest-first up to remaining slots), merged and sorted in Java (contact_id by toString = PG bytewise). Open contacts are never dropped; oldest ACW by ended_at dropped.
- `findActiveContactsForAgent` / `resolveCustomerDisplayName` in `ContactServiceImpl` are deliberately NOT `@Transactional`: swallowing an exception from a `@Transactional` collaborator inside an outer tx gives `UnexpectedRollbackException` (SUPPORTS/REQUIRED both join and poison). Proven by `ContactActiveContactsLookupFallbackIntegrationTest` (wraps mocks in `ProxyFactory` + `TransactionInterceptor`; fails with UnexpectedRollbackException if outer `@Transactional` is restored).
- ACW window: String `@Value` + `@PostConstruct` parse via `DurationStyle`, rejects <=0; `phase` is enum `ContactPhase`.
