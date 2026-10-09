-- =============================================================================
-- V133__reconcile_scheduled_job_with_java_executors.sql
-- DB-076 (EPIC-30): uzgodnienie rejestru scheduled_job z rzeczywistymi wykonawcami.
--
-- PROBLEM: scheduled_job (V014/V015/V077/V088) opisuje zadania "pg_cron", z ktorych zadne
-- nie jest wykonywane przez pg_cron (rozszerzenie niedostepne w postgres:16-alpine, wywolania
-- cron.schedule w V014 sa tylko w komentarzu). Zadania wykonuja dzis klasy Java @Scheduled;
-- czesc wpisow nie ma wykonawcy w ogole. Nic w Javie nie CZYTA scheduled_job (tylko funkcje SQL
-- create_next_month_partitions / archive_completed_campaign_contacts / rotate_* AKTUALIZUJA
-- last_run_at / last_run_status wlasnego wiersza po job_name).
--
-- TABELA job -> wykonawca (stan kodu 2026-10-09, crony UTC):
--   create_next_month_partitions        PartitionMaintenanceJob  codziennie 00:30  (woluje funkcje SQL)
--   purge_campaign_contact_archive      RetentionEvaluationJob   codziennie 01:00  (-> RetentionPurgeService, per tenant, V126)
--   cleanup_expired_refresh_tokens      RefreshTokenCleanupJob   codziennie 03:30  (DELETE przez repozytorium, nie funkcja SQL)
--   archive_completed_campaign_contacts CampaignArchiveJob       codziennie 04:00  (flaga retention.campaign-archive.enabled=false)
--   rotate_audit_log_partitions         brak (backstop SQL)      zastapione: PartitionMaintenanceJob + PartitionReclaimJob
--   rotate_plugin_invocation_log_...    brak (backstop SQL)      jw.
--   rotate_contact_partitions           brak (backstop SQL)      jw.
--   rotate_contact_event_partitions     brak (backstop SQL)      jw.
--   rotate_contact_transcription_...    brak (backstop SQL)      jw.
--   rotate_contact_ai_summary_...       brak (backstop SQL)      jw.
--   refresh_materialized_views          USUNIETY (funkcja i widoki usuniete w V132 / DB-058)
-- Inne joby Java (RecordingRetentionJob 02:00, PendingAttachmentSweepJob 02:30, PartitionReclaimJob
-- niedziela 03:00 itd.) nigdy nie mialy wpisu w scheduled_job i nie sa tu dodawane (bez nowych wpisow).
--
-- ZAKRES: tylko dane (UPDATE/DELETE) i COMMENT ON FUNCTION. Definicje funkcji, kolumny i
-- last_run_* NIE sa zmieniane -- funkcje dalej aktualizuja last_run_at po job_name, wiec wiersze
-- create_next_month_partitions i archive_completed_campaign_contacts MUSZA zostac.
--
-- is_active: TRUE = istnieje wykonawca Java (CampaignArchiveJob jest dodatkowo domyslnie wylaczony
-- flaga -- opisane w description); FALSE = wpis bez wykonawcy (backstop SQL).
--
-- IDEMPOTENCJA: UPDATE ustawia stale wartosci, DELETE i COMMENT sa powtarzalne. Wpis, ktorego brak
-- (np. baza bez jednego z zadan), jest pomijany bez bledu.
-- =============================================================================

-- 1. Joby z wykonawca Java: realny cron (5 pol, UTC) + opis wykonawcy
UPDATE scheduled_job sj
SET cron_expression = v.cron_expression,
    description     = v.description,
    is_active       = TRUE
FROM (VALUES
    ('create_next_month_partitions',
     '30 0 * * *',
     'Wykonawca: PartitionMaintenanceJob (Java @Scheduled, codziennie 00:30 UTC, retention.partition-maintenance-cron). Pre-tworzy partycje miesieczne 8 tabel (contact, audit_log, plugin_invocation_log, contact_event, contact_transcription, contact_ai_summary, social_message, email_message); job wola funkcje SQL, ktora aktualizuje last_run_at tego wpisu.'),
    ('purge_campaign_contact_archive',
     '0 1 * * *',
     'Wykonawca: RetentionEvaluationJob (Java @Scheduled, codziennie 01:00 UTC, retention.evaluation-cron) -> RetentionPurgeService, per tenant wg polityki CAMPAIGN_DATA, partiami (V126). Funkcja SQL nie jest wolana jako jedno zadanie globalne; last_run_at nie jest tu aktualizowane.'),
    ('cleanup_expired_refresh_tokens',
     '30 3 * * *',
     'Wykonawca: RefreshTokenCleanupJob (Java @Scheduled, codziennie 03:30 UTC, auth.refresh-token-cleanup.cron). Usuwa wygasle i uniewaznione refresh tokeny po karencji; funkcja SQL cleanup_expired_refresh_tokens() pozostaje jako backstop i nie jest wolana. last_run_at nie jest tu aktualizowane.'),
    ('archive_completed_campaign_contacts',
     '0 4 * * *',
     'Wykonawca: CampaignArchiveJob (Java @Scheduled, codziennie 04:00 UTC, retention.campaign-archive-cron); DOMYSLNIE WYLACZONY flaga retention.campaign-archive.enabled=false (D8, wymaga zgody wlasciciela produktu). Wola funkcje SQL, ktora przenosi kontakty zakonczonych kampanii (COMPLETED/STOPPED, > 30 dni) do campaign_contact_archive i aktualizuje last_run_at tego wpisu. campaign_contact ma tylko partycje DEFAULT (ADR DB-070).')
) AS v(job_name, cron_expression, description)
WHERE sj.job_name = v.job_name;

-- 2. Wpisy bez wykonawcy: backstop SQL, nieaktywny (cron_expression = nominalny harmonogram, nigdy nie uruchamiany)
UPDATE scheduled_job sj
SET is_active   = FALSE,
    description = 'brak (backstop SQL, nieaktywny) - pg_cron nieobecny, nikt nie wola funkcji ' || sj.pg_function
                  || '(). Zastapione przez PartitionMaintenanceJob (tworzenie partycji, codziennie 00:30 UTC) i PartitionReclaimJob (DROP partycji, niedziela 03:00 UTC).'
WHERE sj.job_name IN (
    'rotate_audit_log_partitions',
    'rotate_plugin_invocation_log_partitions',
    'rotate_contact_partitions',
    'rotate_contact_event_partitions',
    'rotate_contact_transcription_partitions',
    'rotate_contact_ai_summary_partitions'
);

-- 3. refresh_materialized_views: funkcja i widoki usuniete w V132 (DB-058); wpis wskazywalby na nieistniejaca funkcje
DELETE FROM scheduled_job
WHERE job_name = 'refresh_materialized_views';

-- 4. Komentarze funkcji (sam komentarz katalogu, nie definicja): usuniecie falszywych twierdzen o pg_cron
COMMENT ON FUNCTION archive_completed_campaign_contacts() IS
    'Archiwizuje CAMPAIGN_CONTACT zakonczonych kampanii (status COMPLETED/STOPPED, > 30 dni): przenosi rekordy do campaign_contact_archive i usuwa z tabeli operacyjnej; dodatkowo drop-uje dedykowana partycje LIST, jesli istnieje (dzis nie istnieje zadna - campaign_contact ma tylko partycje DEFAULT, ADR DB-070). Idempotentna. Wolana przez CampaignArchiveJob (Java, 04:00 UTC, domyslnie wylaczony flaga retention.campaign-archive.enabled); pg_cron nie jest uzywany.';

COMMENT ON FUNCTION create_next_month_partitions() IS
    'Pre-tworzy partycje miesieczne na miesiac teraz+1 dla 8 tabel i aktualizuje scheduled_job.last_run_at. Wolana przez PartitionMaintenanceJob (Java, codziennie 00:30 UTC); pg_cron nie jest uzywany.';

COMMENT ON FUNCTION cleanup_expired_refresh_tokens() IS
    'Backstop SQL (nieaktywny): usuwa wygasle/uniewaznione refresh tokeny. Realnie zastapiona przez RefreshTokenCleanupJob (Java, 03:30 UTC, BE-122); nic jej nie wola, pg_cron nie jest uzywany.';

COMMENT ON FUNCTION rotate_audit_log_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje audit_log i usuwa stare. Realnie zastapiona przez PartitionMaintenanceJob (tworzenie) i PartitionReclaimJob (DROP, BE-123); nic jej nie wola.';

COMMENT ON FUNCTION rotate_plugin_invocation_log_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje plugin_invocation_log i usuwa stare. Realnie zastapiona przez PartitionMaintenanceJob (tworzenie) i PartitionReclaimJob (DROP, BE-123); nic jej nie wola.';

COMMENT ON FUNCTION rotate_contact_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje contact. Realnie zastapiona przez PartitionMaintenanceJob (tworzenie) i PartitionReclaimJob (DROP); nic jej nie wola.';

COMMENT ON FUNCTION rotate_contact_event_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje contact_event i usuwa stare. Realnie zastapiona przez PartitionMaintenanceJob i PartitionReclaimJob; nic jej nie wola.';

COMMENT ON FUNCTION rotate_contact_transcription_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje contact_transcription i usuwa stare. Realnie zastapiona przez PartitionMaintenanceJob i PartitionReclaimJob; nic jej nie wola.';

COMMENT ON FUNCTION rotate_contact_ai_summary_partitions() IS
    'Backstop SQL (nieaktywny): tworzy partycje contact_ai_summary i usuwa stare. Realnie zastapiona przez PartitionMaintenanceJob i PartitionReclaimJob; nic jej nie wola.';

COMMENT ON FUNCTION drop_old_audit_log_partitions(INT) IS
    'Backstop SQL (nieaktywny): usuwa partycje audit_log starsze niz p_retention_months (domyslnie 24). Realnie DROP wykonuje PartitionReclaimJob (BE-123, konfigurowalny horyzont); pg_cron nie jest uzywany.';

COMMENT ON FUNCTION drop_old_plugin_invocation_log_partitions(INT) IS
    'Backstop SQL (nieaktywny): usuwa partycje plugin_invocation_log starsze niz p_retention_months (domyslnie 24). Realnie DROP wykonuje PartitionReclaimJob (BE-123); pg_cron nie jest uzywany.';
