-- =============================================================================
-- V139__scheduled_job_archive_description_per_tenant.sql
-- BE-151 (EPIC-30): scheduled_job.description wpisu archive_completed_campaign_contacts
-- (V133) twierdzi "> 30 dni", a od DB-084 (V136-V138) prog archiwizacji jest ustawieniem
-- PER TENANT (domyslnie 180 dni, zakres 30-730). V133/V135 NIE sa edytowane (zastosowane) --
-- poprawka nowa migracja.
--
-- Idempotentna: REPLACE zmienia tylko fragment "> 30 dni"; po poprawce fragmentu juz nie ma,
-- wiec ponowne wykonanie nic nie zmienia (0 wierszy realnie zmienionych tresciowo).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

UPDATE scheduled_job
SET    description = REPLACE(
                         description,
                         '(COMPLETED/STOPPED, > 30 dni)',
                         '(COMPLETED/STOPPED), gdy od zakonczenia kampanii minal okres archiwizacji TENANTA (domyslnie 180 dni, zakres 30-730; tabela tenant_campaign_archive_setting, API retention/campaign-archive)')
WHERE  job_name = 'archive_completed_campaign_contacts'
  AND  description LIKE '%> 30 dni%';
