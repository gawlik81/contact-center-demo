---
name: project-db082-cron-failure-log-java
description: DB-082 krok Java - CronFailureLogRepository (REQUIRES_NEW, nigdy nie rzuca) wpiety w CampaignArchiveJob, PartitionMaintenanceJob, purge CAMPAIGN_DATA
metadata:
  type: project
---

`domain.retention.CronFailureLogRepository` (package-private, bez TenantAware) woła `log_cron_failure` (V135) w osobnej tx; `rootCauseMessage(e)` statyczny helper. Wpięcie: catch w CampaignArchiveJob.run, catch wokół createNextMonthPartitions (NIE pętli bufora), w RetentionPurgeServiceImpl.purgeAsync wokół samego `purgeCampaignData` (nie w ogólnym catch — inaczej awaria markCompleted dałaby fałszywy ślad purge), message `tenant=<uuid>: ...`.
**Why:** wpis ERROR w V015 jest cofany przez RAISE. **How to apply:** nowy job cron = wołaj recordFailure PO catch. Testy kontekstu JpaTestContext potrzebują beana JdbcTemplate (prepare hook) dla CronFailureLogRepository.
