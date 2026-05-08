-- 1. campaign_contact – rozszerzenie CHECK constraint
ALTER TABLE campaign_contact
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_status;

ALTER TABLE campaign_contact
    ADD CONSTRAINT chk_campaign_contact_status
        CHECK (status IN (
            'PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER',
            'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR',
            'NOT_REACHED', 'CALLBACK'
        ));

-- 2. campaign_contact_archive – analogicznie
ALTER TABLE campaign_contact_archive
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_archive_status;

ALTER TABLE campaign_contact_archive
    ADD CONSTRAINT chk_campaign_contact_archive_status
        CHECK (status IN (
            'PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER',
            'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR',
            'NOT_REACHED', 'CALLBACK'
        ));

-- 3. Aktualizacja indeksu dialera – teraz obejmuje też NO_ANSWER (retry)
DROP INDEX IF EXISTS idx_campaign_contact_dialer;
CREATE INDEX idx_campaign_contact_dialer
    ON campaign_contact (campaign_id, status, next_attempt_at)
    WHERE status IN ('PENDING', 'NO_ANSWER');

-- 4. Odświeżenie mv_campaign_stats – dodanie kolumn dla nowych statusów
DROP MATERIALIZED VIEW IF EXISTS mv_campaign_stats;
CREATE MATERIALIZED VIEW mv_campaign_stats AS
SELECT
    cc.campaign_id,
    c.tenant_id,
    c.name                                                              AS campaign_name,
    c.type                                                              AS campaign_type,
    COUNT(*)                                                            AS total_records,
    COUNT(*) FILTER (WHERE cc.status = 'PENDING')                      AS pending_records,
    COUNT(*) FILTER (WHERE cc.status = 'DIALING')                      AS dialing_records,
    COUNT(*) FILTER (WHERE cc.status = 'CONNECTED')                    AS connected_records,
    COUNT(*) FILTER (WHERE cc.status = 'NO_ANSWER')                    AS no_answer_records,
    COUNT(*) FILTER (WHERE cc.status = 'NOT_REACHED')                  AS not_reached_records,
    COUNT(*) FILTER (WHERE cc.status = 'CALLBACK')                     AS callback_records,
    COUNT(*) FILTER (WHERE cc.status = 'COMPLETED')                    AS completed_records,
    COUNT(*) FILTER (WHERE cc.status = 'FAILED')                       AS failed_records,
    COUNT(*) FILTER (WHERE cc.status = 'ERROR')                        AS error_records,
    ROUND(AVG(cc.attempt_count), 2)                                     AS avg_attempt_count,
    SUM(cc.attempt_count)                                               AS total_attempts,
    COUNT(*) FILTER (WHERE cc.disposition_code IS NOT NULL)             AS contacts_with_disposition,
    MAX(cc.last_attempt_at)                                             AS last_activity_at
FROM  campaign_contact cc
JOIN  campaign          c  ON c.campaign_id = cc.campaign_id
GROUP BY cc.campaign_id, c.tenant_id, c.name, c.type;

CREATE UNIQUE INDEX uq_mv_campaign_stats ON mv_campaign_stats (campaign_id);
CREATE INDEX idx_mv_campaign_stats_tenant ON mv_campaign_stats (tenant_id);

-- 5. Aktualizacja komentarza dokumentacyjnego
COMMENT ON COLUMN campaign_contact.status IS
    'Status rekordu kampanii. '
    'PENDING = oczekuje na polaczenie, '
    'DIALING = w trakcie wybierania (attempt_count zostal zinkrementowany), '
    'CONNECTED = polaczono z agentem, '
    'NO_ANSWER = brak odpowiedzi – zaplanowana kolejna proba (next_attempt_at), '
    'NOT_REACHED = wyczerpano max_attempts bez odpowiedzi (finalny – niedodzwoniony), '
    'CALLBACK = agent zaplanował oddzwonienie (scheduled_callback powiazan), '
    'COMPLETED = zakonczono z dyspozycja agenta, '
    'FAILED = blad polaczenia (np. numer niedostepny), '
    'SKIPPED = pominieto recznie, '
    'ERROR = blad techniczny adaptera telefonii.';
