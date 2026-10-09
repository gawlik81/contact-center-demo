---
name: project-db076-077-scheduled-job-docs
description: DB-076/V133 (2026-10-09) scheduled_job uzgodniony z wykonawcami Java + DB-077 dokumentacja (06-database.md, ARCHITECTURE.md, CR-DATABASE, DESIGN U10/U13); tabela job->wykonawca
metadata:
  type: project
---

**V133** (data-only, idempotentna): 4 wpisy z wykonawcą Java dostają realny cron i opis "Wykonawca: X" (is_active=TRUE):
create_next_month_partitions=PartitionMaintenanceJob `30 0 * * *`; purge_campaign_contact_archive=RetentionEvaluationJob `0 1 * * *`;
cleanup_expired_refresh_tokens=RefreshTokenCleanupJob `30 3 * * *`; archive_completed_campaign_contacts=CampaignArchiveJob `0 4 * * *`
(domyślnie wyłączony flagą). 6 wpisów rotate_* = "brak (backstop SQL, nieaktywny)", is_active=FALSE (cron nominalny bez zmian).
refresh_materialized_views = DELETE. Plus COMMENT ON FUNCTION (11 funkcji) bez zmiany definicji. Test: Db076ScheduledJobReconciliationMigrationTest (8 testów, w tym cron wpisu == @Scheduled default ze źródeł).

**Why:** pg_cron nigdy nie był zainstalowany; nic w Javie nie czyta scheduled_job, ale funkcje SQL create_next_month_partitions/archive_* aktualizują last_run_at po job_name — te wiersze MUSZĄ zostać.

**How to apply:** Db058DropMaterializedViewsMigrationTest musiał dostać pin `target(migration)` w teście pełnego łańcucha (V133 usuwa wpis, który DB-058 test oczekuje) — wzorzec z [[feedback-pin-post-snapshot-version-not-latest]]. COMMENT ON COLUMN email_message.attachments (s3_key) był już w V102 — nowa migracja nie była potrzebna. Joby bez wpisu w scheduled_job (RecordingRetentionJob, PendingAttachmentSweepJob, PartitionReclaimJob) nie dodane (zakres: bez nowych wpisów).
