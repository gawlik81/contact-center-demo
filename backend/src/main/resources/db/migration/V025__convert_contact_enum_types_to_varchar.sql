-- =============================================================================
-- V025__convert_contact_enum_types_to_varchar.sql
-- Konwersja kolumn ENUM w tabeli CONTACT na VARCHAR + CHECK constraints
--
-- Problem: Hibernate 6 binduje wartosci Java enum jako VARCHAR (JDBC type = 12),
-- co powoduje blad "column X is of type contact_channel but expression is of
-- type character varying" przy INSERT/UPDATE.
--
-- Dotyczy kolumn:
--   contact.channel          (contact_channel  ENUM)
--   contact.direction        (contact_direction ENUM)
--   contact.status           (contact_status   ENUM)
--   email_message.direction  (contact_direction ENUM) -- V010
--   social_message.direction (contact_direction ENUM) -- V010
--
-- Rozwiazanie: konwersja do VARCHAR + CHECK constraint, identyczna jak
-- V019 dla tenant.status, app_user.role i app_user.status.
--
-- Tabela contact jest partycjonowana RANGE po started_at – ALTER TABLE
-- propaguje na wszystkie partycje automatycznie.
--
-- Widoki/materialized views zalezne (wymagaja DROP przed ALTER, odtwarzane na koncu):
--   mv_agent_daily_stats    (V011) – uzywa channel, status (GROUP BY channel)
--   v_active_contacts       (V016) – uzywa channel, direction, status
--   v_queue_realtime_stats  (V016/V019) – uzywa status
--   v_customer_timeline     (V017) – uzywa channel::TEXT, direction::TEXT, status::TEXT z tabeli contact
-- Indeksy zalezne (partial WHERE na ENUM / kolumny ENUM):
--   idx_contact_tenant_status     (V007) – partial WHERE status IN (...)
--   idx_contact_channel_date      (V007) – kolumna channel
--   idx_contact_queue_status      (V011) – kolumna status, partial WHERE queue_id IS NOT NULL
--   idx_contact_tenant_channel_date (V011) – kolumna channel
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Usuniecie materialized views i widokow zaleznych od zmienianych kolumn
-- ---------------------------------------------------------------------------

-- mv_agent_daily_stats (V011) – uzywa channel i status; posiada wlasne indeksy
-- ktore zostana usuniete automatycznie razem z DROP MATERIALIZED VIEW
DROP MATERIALIZED VIEW IF EXISTS mv_agent_daily_stats CASCADE;

DROP VIEW IF EXISTS v_active_contacts CASCADE;
DROP VIEW IF EXISTS v_queue_realtime_stats CASCADE;

-- v_customer_timeline (V017) – uzywa channel::TEXT, direction::TEXT, status::TEXT z contact
DROP VIEW IF EXISTS v_customer_timeline CASCADE;

-- ---------------------------------------------------------------------------
-- 2. Usuniecie indeksow zaleznych od zmienianych kolumn
--    (partial indexes z WHERE na ENUM / indeksy na kolumnach ENUM)
-- ---------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_contact_tenant_status;
DROP INDEX IF EXISTS idx_contact_channel_date;
-- Indeksy z V011 zalezne od channel / status:
DROP INDEX IF EXISTS idx_contact_queue_status;
DROP INDEX IF EXISTS idx_contact_tenant_channel_date;

-- ---------------------------------------------------------------------------
-- 3. contact.channel: contact_channel -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE contact
    ALTER COLUMN channel TYPE VARCHAR(50) USING channel::TEXT;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_channel
        CHECK (channel IN ('PHONE', 'EMAIL', 'SOCIAL_FACEBOOK', 'SOCIAL_INSTAGRAM', 'SOCIAL_WHATSAPP'));

DROP TYPE contact_channel;

-- ---------------------------------------------------------------------------
-- 4. contact.direction: contact_direction -> VARCHAR(20)
-- ---------------------------------------------------------------------------

ALTER TABLE contact
    ALTER COLUMN direction TYPE VARCHAR(20) USING direction::TEXT;

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'));

-- email_message.direction rowniez uzywa typu contact_direction (V010)
ALTER TABLE email_message
    ALTER COLUMN direction TYPE VARCHAR(20) USING direction::TEXT;

ALTER TABLE email_message
    ADD CONSTRAINT chk_email_message_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'));

-- social_message.direction rowniez uzywa typu contact_direction (V010)
ALTER TABLE social_message
    ALTER COLUMN direction TYPE VARCHAR(20) USING direction::TEXT;

ALTER TABLE social_message
    ADD CONSTRAINT chk_social_message_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'));

DROP TYPE contact_direction;

-- ---------------------------------------------------------------------------
-- 5. contact.status: contact_status -> VARCHAR(20)
-- ---------------------------------------------------------------------------

ALTER TABLE contact
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE contact
    ALTER COLUMN status TYPE VARCHAR(20) USING status::TEXT;

ALTER TABLE contact
    ALTER COLUMN status SET DEFAULT 'QUEUED';

ALTER TABLE contact
    ADD CONSTRAINT chk_contact_status
        CHECK (status IN ('QUEUED', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'ABANDONED'));

DROP TYPE contact_status;

-- ---------------------------------------------------------------------------
-- 6. Odtworzenie indeksow (teraz na kolumnach VARCHAR)
-- ---------------------------------------------------------------------------

-- Aktywne kontakty (dashboard RT): szybkie pobieranie statusu
CREATE INDEX idx_contact_tenant_status
    ON contact (tenant_id, status)
    WHERE status IN ('QUEUED', 'ACTIVE', 'ON_HOLD');

-- Indeks kanalowy dla raportow per kanal (V007)
CREATE INDEX idx_contact_channel_date
    ON contact (tenant_id, channel, started_at);

-- Szybkie zliczanie porzucen per kolejka – abandon rate KPI (V011)
CREATE INDEX idx_contact_queue_status
    ON contact (tenant_id, queue_id, status, started_at DESC)
    WHERE queue_id IS NOT NULL;

-- Raport AHT per kanal dla supervisora (V011)
CREATE INDEX idx_contact_tenant_channel_date
    ON contact (tenant_id, channel, started_at DESC);

-- ---------------------------------------------------------------------------
-- 7. Odtworzenie widoku v_active_contacts (oryginal z V016)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_active_contacts AS
SELECT
    c.contact_id,
    c.tenant_id,
    c.channel,
    c.direction,
    c.status,
    c.customer_id,
    c.agent_id,
    c.queue_id,
    c.remote_address,
    c.queued_at,
    c.assigned_at,
    c.started_at,

    -- Czas oczekiwania w sekundach (od queued_at do teraz, jesli jeszcze nie przypisany)
    CASE
        WHEN c.assigned_at IS NOT NULL THEN
            EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at))::INT
        ELSE
            EXTRACT(EPOCH FROM (NOW() - c.queued_at))::INT
    END                                                         AS wait_time_seconds,

    -- Czas trwania kontaktu (od started_at)
    EXTRACT(EPOCH FROM (NOW() - c.started_at))::INT            AS contact_age_seconds,

    -- Dane agenta (LEFT JOIN – moze byc NULL)
    u.first_name                                                AS agent_first_name,
    u.last_name                                                 AS agent_last_name,

    -- Nazwa kolejki
    q.name                                                      AS queue_name

FROM  contact    c
LEFT JOIN app_user u ON u.user_id   = c.agent_id
LEFT JOIN queue    q ON q.queue_id  = c.queue_id

WHERE c.status IN ('QUEUED', 'ACTIVE', 'ON_HOLD');

COMMENT ON VIEW v_active_contacts IS
    'Aktywne kontakty w czasie rzeczywistym (status QUEUED/ACTIVE/ON_HOLD). '
    'Uzywany przez dashboard supervisora (BE-029, US-10-01). '
    'Korzysta z indeksu idx_contact_tenant_status (V007/V025).';

-- ---------------------------------------------------------------------------
-- 8. Odtworzenie widoku v_queue_realtime_stats (oryginal z V016/V019)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_queue_realtime_stats AS
SELECT
    q.queue_id,
    q.tenant_id,
    q.name                                                          AS queue_name,
    q.routing_strategy,

    -- Kontakty w kolejce (oczekujace na agenta)
    COUNT(c.contact_id) FILTER (WHERE c.status = 'QUEUED')         AS contacts_waiting,

    -- Kontakty aktualnie obslugiwane
    COUNT(c.contact_id) FILTER (WHERE c.status = 'ACTIVE')         AS contacts_active,

    -- ON_HOLD
    COUNT(c.contact_id) FILTER (WHERE c.status = 'ON_HOLD')        AS contacts_on_hold,

    -- Najdluzej czekajacy kontakt (sekundy)
    COALESCE(
        MAX(
            EXTRACT(EPOCH FROM (NOW() - c.queued_at))::INT
        ) FILTER (WHERE c.status = 'QUEUED'),
        0
    )                                                               AS max_wait_seconds,

    -- Sredni czas oczekiwania
    COALESCE(
        ROUND(
            AVG(
                EXTRACT(EPOCH FROM (NOW() - c.queued_at))
            ) FILTER (WHERE c.status = 'QUEUED')
        )::INT,
        0
    )                                                               AS avg_wait_seconds,

    -- Dostepni agenci w tej kolejce
    COUNT(DISTINCT qa.agent_id) FILTER (
        WHERE u.status = 'AVAILABLE' AND u.is_deleted = FALSE
    )                                                               AS agents_available,

    -- Zajeci agenci
    COUNT(DISTINCT qa.agent_id) FILTER (
        WHERE u.status IN ('BUSY', 'AFTER_CONTACT') AND u.is_deleted = FALSE
    )                                                               AS agents_busy

FROM  queue       q
LEFT JOIN contact    c  ON c.queue_id = q.queue_id
                       AND c.status  IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
LEFT JOIN queue_agent qa ON qa.queue_id = q.queue_id
LEFT JOIN app_user    u  ON u.user_id   = qa.agent_id

WHERE q.is_active = TRUE

GROUP BY q.queue_id, q.tenant_id, q.name, q.routing_strategy;

COMMENT ON VIEW v_queue_realtime_stats IS
    'Statystyki kolejek w czasie rzeczywistym: oczekujace kontakty, dostepni agenci, '
    'maks. i sredni czas oczekiwania. Uzywany przez dashboard supervisora (US-07-04).';

-- ---------------------------------------------------------------------------
-- 9. Odtworzenie materialized view mv_agent_daily_stats (oryginal z V011)
--    Teraz kolumny channel i status sa VARCHAR – GROUP BY i FILTER dzialaja tak samo
-- ---------------------------------------------------------------------------

CREATE MATERIALIZED VIEW mv_agent_daily_stats AS
SELECT
    c.tenant_id,
    c.agent_id,
    (c.started_at AT TIME ZONE 'UTC')::DATE                         AS contact_date,
    c.channel,

    -- Liczniki
    COUNT(*)                                                         AS total_contacts,
    COUNT(*) FILTER (WHERE c.status = 'COMPLETED')                  AS completed_contacts,
    COUNT(*) FILTER (WHERE c.status = 'ABANDONED')                  AS abandoned_contacts,

    -- Czasy obslugi (AHT)
    ROUND(AVG(c.duration_seconds)
          FILTER (WHERE c.status = 'COMPLETED'))::INT               AS avg_handle_time_seconds,

    ROUND(SUM(c.duration_seconds)
          FILTER (WHERE c.status = 'COMPLETED'))::INT               AS total_handle_time_seconds,

    -- Czas oczekiwania (ASA)
    ROUND(AVG(EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at)))
          FILTER (WHERE c.assigned_at IS NOT NULL))::INT            AS avg_wait_time_seconds,

    -- Struktura dyspozycji
    COUNT(*) FILTER (WHERE c.disposition_code IS NOT NULL)          AS contacts_with_disposition

FROM  contact c
WHERE c.agent_id IS NOT NULL
GROUP BY
    c.tenant_id, c.agent_id,
    (c.started_at AT TIME ZONE 'UTC')::DATE,
    c.channel;

CREATE UNIQUE INDEX uq_mv_agent_daily_stats
    ON mv_agent_daily_stats (tenant_id, agent_id, contact_date, channel);

CREATE INDEX idx_mv_agent_daily_stats_tenant_date
    ON mv_agent_daily_stats (tenant_id, contact_date DESC);

COMMENT ON MATERIALIZED VIEW mv_agent_daily_stats IS
    'Preagregowane statystyki agentow per dzien i kanal. '
    'Odswiezane codziennie o 01:00 (DB-018 pg_cron). '
    'Pokrywa US-10-02: liczba kontaktow, AHT, ASA. '
    'REFRESH MATERIALIZED VIEW CONCURRENTLY – brak blokady podczas odswiezania.';

-- ---------------------------------------------------------------------------
-- 10. Odtworzenie widoku v_customer_timeline (oryginal z V017)
--     channel/direction/status sa teraz VARCHAR – rzutowanie ::TEXT zbedne,
--     ale zachowane dla zgodnosci z oryginalna definicja.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_customer_timeline AS
SELECT
    'CONTACT'::TEXT                         AS event_type,
    c.contact_id                            AS event_id,
    c.tenant_id,
    c.customer_id,
    c.channel::TEXT                         AS sub_type,
    c.direction::TEXT                       AS direction,
    c.status::TEXT                          AS status,
    c.started_at                            AS event_at,
    c.ended_at,
    c.duration_seconds,
    c.disposition_code,
    c.agent_id,
    NULL::TEXT                              AS content_preview

FROM contact c
WHERE c.customer_id IS NOT NULL

UNION ALL

SELECT
    'EMAIL'::TEXT,
    em.message_id,
    em.tenant_id,
    con.customer_id,
    ('EMAIL_' || em.direction::TEXT)::TEXT,
    em.direction::TEXT,
    COALESCE(em.delivery_status, 'UNKNOWN')::TEXT,
    COALESCE(em.received_at, em.sent_at),
    NULL::TIMESTAMPTZ,
    NULL::INT,
    NULL::VARCHAR(50),
    NULL::UUID,
    LEFT(em.subject, 100)

FROM email_message em
JOIN contact con ON con.contact_id = em.contact_id
WHERE con.customer_id IS NOT NULL

UNION ALL

SELECT
    'SOCIAL'::TEXT,
    sm.message_id,
    sm.tenant_id,
    con.customer_id,
    sm.platform::TEXT,
    sm.direction::TEXT,
    NULL::TEXT,
    sm.sent_at,
    NULL::TIMESTAMPTZ,
    NULL::INT,
    NULL::VARCHAR(50),
    NULL::UUID,
    LEFT(sm.content, 100)

FROM social_message sm
JOIN contact con ON con.contact_id = sm.contact_id
WHERE con.customer_id IS NOT NULL;

COMMENT ON VIEW v_customer_timeline IS
    'Pelna os czasu interakcji klienta: kontakty telefoniczne/email/social. '
    'Uzywany przez widok historii klienta w UI (BE-025, US-09-02). '
    'Sortowanie po event_at po stronie aplikacji (ORDER BY nie w widoku – optymalizacja).';
