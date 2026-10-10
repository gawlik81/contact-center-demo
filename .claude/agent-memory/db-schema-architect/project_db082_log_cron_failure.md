---
name: project-db082-log-cron-failure
description: DB-082/V135 (2026-10-10) log_cron_failure helper - trwaly slad ERROR zadan cyklicznych; tylko archive_completed ma handler EXCEPTION (martwy przez RAISE)
metadata:
  type: project
---

V135 dodaje `log_cron_failure(job, msg, started_at)` -> bigint (INSERT cron_log ERROR + UPDATE scheduled_job). Wolac z Javy w osobnej transakcji PO rollbacku. Funkcje zadan NIE zmienione (kontrakt/wyjatek bez zmian). Tylko archive_completed_campaign_contacts ma EXCEPTION block (martwy: RAISE cofa wpis); create_next_month_partitions i purge_campaign_contact_archive w ogole nie zapisuja ERROR. Fałszywy SUCCESS pod RLS bez GUC poza zakresem (DB-072/BE-139). Test: Db082LogCronFailureMigrationTest (PostgresTestDatabase, trigger wymuszajacy awarie). Migracje: backend/src/main/resources/db/migration (nie backend/app). Odrzucone: B (usuniecie RAISE - Java czytalaby stary SUCCESS z cron_log), dblink.
**Why:** CR 2026-10-10 / BE-120. **How to apply:** przy kolejnym zadaniu cron dodaj wywolanie helpera w catch Javy.
