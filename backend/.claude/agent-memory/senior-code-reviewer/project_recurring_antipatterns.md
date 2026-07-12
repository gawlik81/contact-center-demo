---
name: project-recurring-antipatterns
description: Recurring anti-patterns observed across multiple EPIC reviews in this codebase
metadata:
  type: project
---

## Recurring Anti-Patterns Observed

### 1. Missing @Transactional on service CRUD methods
Observed in EPIC-27 DispositionSetService: only apply* methods have @Transactional; createSet, updateSet, deleteSet, addItem, updateItem, removeItem lack it. Transactional boundaries should be at service level, not repository level.

### 2. @Transactional + catch(Exception) = silent rollback-only
Observed in EPIC-27 applyToCampaign/applyToQueue: catching Exception inside @Transactional method while iterating inserts. JPA marks transaction as rollback-only after any EntityManager exception, even if caught. Fix: use Propagation.REQUIRES_NEW on the inner insert.

### 3. N+1 queries in list* service methods
Observed in EPIC-27 listSets: calls countBySetId for each set in a stream map. Each call also invokes setTenantContextInDb. Fix: use GROUP BY + COUNT(*) in a single JOIN query.

### 4. TOCTOU race condition in name uniqueness checks
Observed in EPIC-27 createSet/updateSet: existsByNameAndTenantId check + insert are not in a single transaction. DB UNIQUE constraint is the real guard, but exceptions are not mapped to 409 ConflictException. Fix: add @Transactional + catch DataIntegrityViolationException.

### 5. Frontend maxLength mismatch with backend constraints
Observed in EPIC-27: Validators.maxLength(200) in Angular form vs VARCHAR(100)/Size(max=100) in backend. This causes server-side validation failure with no inline feedback. Always cross-check frontend validators against backend DTO annotations.

### 6. Polish pluralization simplified
Multiple places use "1 item? singular : plural_genitive" ignoring the Polish 2-4 form. E.g., "3 dyspozycji" instead of "3 dyspozycje". Use i18nPlural or a helper function.

### 7. em.detach() in native-SQL-only repositories
Observed in DispositionSetRepository.update and DispositionSetItemRepository.update: em.detach(entity) is called even though the repository uses only native SQL (no em.merge/persist). The entity is never managed by JPA, so detach is a no-op copied from ORM-based repos.

### 8. Bulk import dedup checks are DB-only, not batch-aware (in-file duplicate bypass)
Observed in `CustomerImportServiceImpl.findExisting()` (shared by CSV and JSON customer import via `processRow`, reviewed 2026-07-06): dedup lookup only queries the real DB via `customerRepository.findByPhoneNumber`/`findByEmail`, never checking the in-memory `batch` list pending flush (`BATCH_SIZE=500`). Two rows in the same file with the same phone/email, landing in the same unflushed chunk, are BOTH treated as new (SKIP and OVERWRITE behave identically wrong) — creates duplicate `customer` rows since there's no unique DB constraint on phone/email (only on `external_id` via `uq_customer_tenant_external_id`, which IS protected by an in-memory `seenExternalIds` Set). For files under `BATCH_SIZE` rows (the common case), this affects ANY in-file duplicate, not just chunk-boundary edge cases. Pre-existing in CSV path, not introduced by the JSON-import diff, but worth checking whenever `processRow`/`findExisting` is touched again, or when reviewing OTHER bulk-import features in this codebase (e.g. campaign contact list import) for the same class of bug. Fix pattern: track seen phones/emails in-memory across the whole import (same pattern as `seenExternalIds`), not just via a DB query.

### 9. New user-facing strings inconsistently migrated to i18n/Transloco
Observed in `customer-import.component.ts` (2026-07-05 and 2026-07-06 reviews): the file predates the Transloco rollout and has hardcoded, diacritic-free Polish strings (`fileError` messages, `mappingError`). New features sometimes correctly add NEW strings through Transloco (e.g. `invalidJsonArrayError`, `jsonFormatHint` — both with correct diacritics in all 4 locale files) while leaving adjacent, only slightly-modified existing hardcoded strings untouched (e.g. `'Dozwolone sa tylko pliki CSV (.csv) lub JSON (.json).'` — still missing diacritics, extended from the old CSV-only message). Not a regression per se (consistent with documented pre-i18n convention for this file), but worth flagging each time as a nudge to finish the migration opportunistically, since `TranslocoService` is already injected in the component.

### 10. JSON bulk-import: `null` array element crashes the whole job instead of rejecting one row
Observed independently in `CustomerImportServiceImpl.doJsonImport`/`parseJsonRow` (BE-026) and `CampaignImportServiceImpl.doJsonImport`/`parseJsonRow` (BE-023 extension, reviewed 2026-07-12 — copied from the customer pattern per plan). Both read the whole file via `objectMapper.readTree` → `convertValue(root, TypeReference<List<Map<String,Object>>>)`, then loop `for (i...) { parseJsonRow(rows.get(i)); ... }` OUTSIDE the try/catch that wraps the tree-parsing step. Verified experimentally (Jackson 2.17.1): a JSON array containing a literal `null` element (e.g. `[{"phone":"+1"}, null]`) does NOT throw during `convertValue` — Jackson silently produces a Java `null` in the resulting list. `parseJsonRow(null)` then throws an uncaught NPE that propagates out of the per-row loop (no local try/catch there) up to the outer `processXxxImportAsync` catch-all, which marks the ENTIRE job `FAILED_PARTIAL` — unlike an invalid-phone row, which is cleanly rejected as a single row while the rest of the batch still imports. Non-object array elements that Jackson genuinely can't coerce (e.g. a JSON string or number) DO throw during `convertValue` (as a `JsonMappingException`, subclass of `IOException`) and so ARE handled correctly by the existing catch — only the `null`-element case slips through. On the campaign side this is largely mitigated by the Angular frontend's `isArrayOfObjects` check (`item !== null`, `campaign-import.component.ts`), which blocks such files from being submitted through the UI — but the backend endpoint is directly callable (Swagger etc.), so it should not rely on the frontend for this.

**Fix pattern:** in the per-row loop, either explicit-null-check `rows.get(i)` before calling `parseJsonRow` (treat as a rejected row, not a fatal error), or wrap the per-row parse+process call in its own try/catch that maps any exception to a rejected-row sample instead of letting it escape the loop.

### 11. JSON bulk-import: `customFields`/nested-metadata values silently corrupted via `String.valueOf()`
Same two files as #10 (`CustomerImportServiceImpl.parseJsonRow`, `CampaignImportServiceImpl.parseJsonRow`): non-primitive values inside `customFields` (a nested JSON object or array) are converted with `String.valueOf(entry.getValue())`, which calls Java's `Map.toString()`/`List.toString()` (e.g. `{"tags":["a","b"]}` → the Java string `"[a, b]"`, not valid JSON) rather than `objectMapper.writeValueAsString(value)`. Data is silently corrupted (unparseable back into structured JSON) with no error or rejection. Documented format only shows flat string values in both features' examples, so this is an edge case rather than a contract violation, but worth flagging/fixing each time this code is touched, ideally in both places at once.

**How to apply:** Flag all of these patterns in future reviews. For @Transactional + catch(Exception) — always ask whether inner exception can mark outer transaction as rollback-only. For bulk-import features, always check whether dedup/uniqueness lookups are batch-aware or DB-only, and whether the per-row JSON-array loop is defensive against `null`/malformed individual elements (see #10) and non-primitive nested metadata values (see #11).
