---
name: BE-026 Customer Import CSV
description: Async import klientów z CSV – endpointy, serwis, testy
type: project
---

Implementacja async importu klientów z CSV (BE-026).

**Nowe pliki:**
- `api/customer/DeduplicationMode.java` – enum SKIP/OVERWRITE
- `api/customer/CustomerImportStatusResponse.java` – record DTO
- `api/customer/CustomerImportController.java` – POST/GET/GET-errors na `/api/customers/import`
- `domain/service/CustomerImportService.java` – async serwis z Redis, OpenCSV, JdbcTemplate batch
- `test/domain/CustomerImportServiceTest.java` – 24 testy jednostkowe (mocki)

**Modyfikacje:**
- `domain/repository/CustomerRepository.java` – dodano `findByEmail()` (JSONB `@>` + GIN index)

**Wzorzec Redis:** klucz `import:customer:{jobId}`, raport błędów `import:customer:{jobId}:errors`, TTL 3600s

**Wzorzec deduplikacji:** szukaj po każdym phone (E.164), potem po każdym email; SKIP → skipped++, OVERWRITE → batchUpdate

**Batch insert:** `jdbcTemplate.batchUpdate`, chunk 500, INSERT z `gen_random_uuid()`, UPDATE przez osobny SQL. NULL w row[0] = INSERT, non-null = UPDATE.

**Wielokrotne wartości:** phone i email rozdzielone `;` w jednej komórce CSV; filtrowne przez `filterValidPhones()` (co najmniej 1 poprawny E.164 wymagany)

**Stałe publiczne:** `JOB_KEY_PREFIX`, `ERRORS_KEY_SUFFIX`, `JOB_TTL_SECONDS`, `MAX_FILE_SIZE_BYTES`, `BATCH_SIZE` – wszystkie `public static final` (dostęp z testów)

**Znana pułapka z testami:** `@Async processImportAsync` czyści `TenantContext` (InheritableThreadLocal) przez `finally { TenantContext.clear() }`, co może wyczyścić kontekst głównego wątku testu gdy wątek async dziedziczy ThreadLocal. Rozwiązanie: po wywołaniu `initiateImport` w teście przywróć `TenantContext.setTenantId()` przed kolejnym wywołaniem.

**Why:** zadanie BE-026 z TASKS-BACKEND.md
**How to apply:** wzorzec do naśladowania dla kolejnych importów CSV w systemie
