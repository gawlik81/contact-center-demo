---
name: project_be121_campaign_archive_purge_loop
description: BE-121 petla partii purgeEligible (campaign_contact_archive) - warunek konca = wynik 0, TransactionTemplate REQUIRES_NEW, guard purge-max-batches
metadata:
  type: project
---

`CampaignArchiveRetentionRepository#purgeEligible` woła `purge_campaign_contact_archive(..., batchSize)` (V126) w pętli do wyniku 0 (NIE `n == batchSize`: SKIP LOCKED może dać partię niepełną). Każda partia: `TransactionTemplate` REQUIRES_NEW + `setTenantContextInDb` WEWNĄTRZ (set_config jest transaction-local). Metoda bez `@Transactional`. Właściwości: `retention.campaign-archive.purge-batch-size` (10000, walidacja 1..100000 w konstruktorze) i `purge-max-batches` (10000, guard -> WARN + przerwanie).

**Why:** bez pętli purge usuwał tylko pierwsze 10 000 wierszy (RODO).
**How to apply:** test integracyjny `CampaignArchiveRetentionRepositoryBatchIntegrationTest` (JpaTestContext + trigger-trucizna dla awarii w 2. partii; rola bez BYPASSRLS dowodzi GUC per partia). Javadoc `RetentionPurgeServiceImpl#purgeCampaignData` nadal opisuje "jedno wywołanie" - do poprawy poza BE-121.
