---
name: project-be120-campaign-archive-job
description: BE-120 CampaignArchiveJob - flaga domyslnie false, pomiar RLS pod app_user, GUC '' rzuca blad uuid
metadata:
  type: project
---

CampaignArchiveJob + CampaignArchiveJobRepository (osobna klasa, JdbcTemplate+TransactionTemplate; CampaignArchiveRetentionRepository nietkniete). Flaga `retention.campaign-archive.enabled` (false), cron `retention.campaign-archive-cron`.

**Pomiar R5:** pod `SET ROLE app_user`: GUC nigdy nieustawiony -> 0 kampanii, cichy no-op (cron_log mimo to SUCCESS/0); GUC = '' (polaczenie z puli po set/clear) -> BLAD `invalid input syntax for type uuid`; GUC tenanta A -> tylko A.
**Perf (Testcontainers):** 30x10k wierszy, 1 tx = 17.7 s, 186 MB WAL (test `-Dbe120.perf=true`).

**Why:** blokuje DB-072/BE-139; funkcja V015 nie zmieniona (zakaz migracji w tym tickecie).
**How to apply:** test sesji GUC rob na swiezym DriverManager.getConnection, nie z puli (GUC sesji przecieka). [[feedback_jdbc_set_tenant_context]]
