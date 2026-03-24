---
name: BE-023 Campaign CSV Import
description: Asynchroniczny import CSV kontaktów kampanii – kluczowe decyzje implementacyjne
type: project
---

BE-023 zaimplementowany: asynchroniczny import CSV kontaktów kampanii.

**Why:** Wydajny import do 100k rekordów w < 2 min, deduplikacja po telefonie, status przez Redis.

**Pliki:**
- `api/campaign/CampaignImportController.java` – POST /{id}/contacts/import + GET /{id}/import-status/{jobId}
- `api/campaign/dto/ImportJobStatusResponse.java` – DTO statusu
- `domain/model/ImportJobStatus.java` – model Redis (QUEUED/PROCESSING/COMPLETED/FAILED_PARTIAL)
- `domain/service/CampaignImportService.java` – walidacja + @Async processImportAsync
- `domain/repository/CampaignContactRepository.java` – JdbcTemplate batch INSERT (nie JPA)
- `db/migration/V027__campaign_contact_phone_unique_index.sql` – unique index (campaign_id, phone) WHERE phone IS NOT NULL
- `test/...CampaignImportServiceTest.java` – 25 testów jednostkowych

**Kluczowe decyzje:**
- `CampaignContactRepository` extends `TenantAwareRepository` ale używa `JdbcTemplate` (nie EntityManager) dla batch perf; RLS ustawiany przez natywne `SELECT set_tenant_context(...)` przez JdbcTemplate w tej samej transakcji
- Status joba w Redis jako JSON przez `StringRedisTemplate` + `ObjectMapper` (nie `RedisTemplate<String,Object>`) – unika problemów z GenericJackson2Json i `@class` dla klas spoza `com.contactcenter`
- `processImportAsync` → `TenantContext.snapshot()` PRZED wywołaniem `@Async`, `restore(snapshot)` na początku wątku, `clear()` w finally
- Pre-existing failures w RecordingServiceTest (3 testy SSE-AES256 na mock S3) – nie są związane z BE-023

**How to apply:** Przy kolejnym async job z CSV – ten sam wzorzec snapshot/restore, JdbcTemplate batch, Redis status.
