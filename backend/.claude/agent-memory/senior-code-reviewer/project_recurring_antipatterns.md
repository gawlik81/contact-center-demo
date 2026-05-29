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

**How to apply:** Flag all of these patterns in future reviews. For @Transactional + catch(Exception) — always ask whether inner exception can mark outer transaction as rollback-only.
