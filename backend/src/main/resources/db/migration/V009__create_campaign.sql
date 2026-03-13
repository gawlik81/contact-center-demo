-- =============================================================================
-- V009__create_campaign.sql
-- DB-011: Tabele CAMPAIGN, CAMPAIGN_CONTACT, SCHEDULED_CALLBACK
--
-- Migracja: Flyway V009
-- Zaleznosci: V002 (tenant), V003 (app_user), V006 (customer)
-- Odniesienie PRD: US-08-01 do US-08-06, EPIC-08
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE campaign_type AS ENUM (
    'OUTBOUND_VOICE',   -- Kampania telefoniczna (progressive/predictive dialer)
    'OUTBOUND_EMAIL'    -- Kampania emailowa
);

CREATE TYPE dialer_type AS ENUM (
    'PROGRESSIVE',  -- Dzwoni po potwierdzeniu gotowosci agenta (MVP)
    'PREDICTIVE',   -- Predyktywne wybieranie (Faza 2)
    'MANUAL'        -- Reczne inicjowanie polaczen (preview dialer)
);

CREATE TYPE campaign_status AS ENUM (
    'DRAFT',        -- W przygotowaniu – nieaktywna
    'SCHEDULED',    -- Zaplanowana – czeka na czas startu
    'RUNNING',      -- Aktywna – dialer dzwoni
    'PAUSED',       -- Wstrzymana przez supervisora (US-08-06)
    'STOPPED',      -- Zatrzymana (nie mozna wznowic w tej samej sesji)
    'COMPLETED'     -- Wszystkie kontakty zrealizowane
);

CREATE TYPE campaign_contact_status AS ENUM (
    'PENDING',      -- Jeszcze nie probowano
    'DIALING',      -- W trakcie dzwonienia (blokada przed podwojnym dzwonieniem)
    'CONNECTED',    -- Polaczono z klientem
    'NO_ANSWER',    -- Brak odpowiedzi
    'FAILED',       -- Blad techniczny (np. nieprawidlowy numer)
    'COMPLETED',    -- Agent zarejestrował dyspozycje – koniec obslugi
    'SKIPPED'       -- Pominieto (np. numer na liscie DNC)
);

-- ---------------------------------------------------------------------------
-- 2. Tabela CAMPAIGN
-- ---------------------------------------------------------------------------

CREATE TABLE campaign (
    campaign_id         UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID                NOT NULL,
    name                VARCHAR(255)        NOT NULL,
    type                campaign_type       NOT NULL DEFAULT 'OUTBOUND_VOICE',
    dialer_type         dialer_type         NOT NULL DEFAULT 'PROGRESSIVE',

    -- Harmonogram kampanii (US-08-02)
    -- Schemat: {
    --   "start_date": "2026-03-15",
    --   "end_date": "2026-03-31",
    --   "active_hours": {"from": "09:00", "to": "18:00"},
    --   "active_days": ["MON","TUE","WED","THU","FRI"],
    --   "timezone": "Europe/Warsaw"
    -- }
    schedule            JSONB               NOT NULL DEFAULT '{}',

    status              campaign_status     NOT NULL DEFAULT 'DRAFT',

    -- Kolejka agentow dla tej kampanii
    queue_id            UUID,

    -- Dyspozycje dostepne dla tej kampanii (lista kodow wynikow, US-08-04)
    -- Przyklad: [{"code": "SALE", "label": "Sprzedaż"}, {"code": "DECLINED", ...}]
    disposition_codes   JSONB               NOT NULL DEFAULT '[]',

    -- Ustawienia retry
    max_attempts        INT                 NOT NULL DEFAULT 3,
    retry_delay_minutes INT                 NOT NULL DEFAULT 60,

    created_by          UUID,
    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_campaign PRIMARY KEY (campaign_id),

    CONSTRAINT fk_campaign_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_campaign_queue
        FOREIGN KEY (queue_id)
        REFERENCES queue (queue_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_campaign_created_by
        FOREIGN KEY (created_by)
        REFERENCES app_user (user_id)
        ON DELETE SET NULL,

    CONSTRAINT uq_campaign_tenant_name
        UNIQUE (tenant_id, name),

    CONSTRAINT chk_campaign_schedule_is_obj
        CHECK (jsonb_typeof(schedule) = 'object'),

    CONSTRAINT chk_campaign_max_attempts
        CHECK (max_attempts >= 1),

    CONSTRAINT chk_campaign_retry_delay
        CHECK (retry_delay_minutes >= 0)
);

-- Indeksy CAMPAIGN
CREATE INDEX idx_campaign_tenant_status
    ON campaign (tenant_id, status);

CREATE INDEX idx_campaign_status_scheduled
    ON campaign (status, (schedule->>'start_date'))
    WHERE status = 'SCHEDULED';

CREATE TRIGGER trg_campaign_updated_at
    BEFORE UPDATE ON campaign
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 3. Tabela CAMPAIGN_CONTACT (lista kontaktow kampanii)
-- ---------------------------------------------------------------------------
-- Ta tabela jest partycjonowana po campaign_id (LIST partitioning)
-- dla izolacji duzych list kontaktow (US-08-01: do 100 000 rekordow per kampania)

CREATE TABLE campaign_contact (
    record_id           UUID                        NOT NULL DEFAULT uuid_generate_v4(),
    campaign_id         UUID                        NOT NULL,
    tenant_id           UUID                        NOT NULL,

    -- Opcjonalny link do profilu klienta (moze byc NULL jesli zaimportowano bez dopasowania)
    customer_id         UUID,

    -- Dane kontaktowe (przechowywane bezposrednio dla odizolowania od zmian w CUSTOMER)
    phone               VARCHAR(30),
    first_name          VARCHAR(100),
    last_name           VARCHAR(100),
    email               VARCHAR(255),

    -- Niestandardowe pola importowane z CSV
    custom_fields       JSONB                       NOT NULL DEFAULT '{}',

    status              campaign_contact_status     NOT NULL DEFAULT 'PENDING',

    -- Licznik prob i harmonogram
    attempt_count       INT                         NOT NULL DEFAULT 0,
    last_attempt_at     TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ,

    -- Finalny wynik (dyspozycja agenta po ostatniej probie)
    disposition_code    VARCHAR(50),

    -- Link do ostatniego kontaktu (dla analizy rozmow)
    last_contact_id     UUID,

    created_at          TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_campaign_contact PRIMARY KEY (record_id, campaign_id),

    CONSTRAINT fk_cc_campaign
        FOREIGN KEY (campaign_id)
        REFERENCES campaign (campaign_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_cc_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_cc_customer
        FOREIGN KEY (customer_id)
        REFERENCES customer (customer_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_cc_attempt_count
        CHECK (attempt_count >= 0)

) PARTITION BY LIST (campaign_id);

-- UWAGA: Partycje list tworzone dynamicznie przez aplikacje przy tworzeniu kampanii.
-- Przyklad tworzenia partycji:
-- CREATE TABLE campaign_contact_<uuid> PARTITION OF campaign_contact
--   FOR VALUES IN ('<campaign_uuid>');

-- Partycja domyslna (zabezpieczenie)
CREATE TABLE campaign_contact_default
    PARTITION OF campaign_contact DEFAULT;

-- Krytyczny indeks dla dialera: nastepne rekordy do zadzwonienia (US-08-03)
-- Zapytanie: WHERE campaign_id = ? AND status = 'PENDING' AND next_attempt_at <= NOW()
-- ORDER BY next_attempt_at ASC LIMIT N
CREATE INDEX idx_campaign_contact_dialer
    ON campaign_contact (campaign_id, status, next_attempt_at)
    WHERE status = 'PENDING';

-- Statystyki kampanii: ile rekordow per status
CREATE INDEX idx_campaign_contact_status
    ON campaign_contact (campaign_id, status);

CREATE TRIGGER trg_campaign_contact_updated_at
    BEFORE UPDATE ON campaign_contact
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 4. Tabela SCHEDULED_CALLBACK (US-08-05)
-- ---------------------------------------------------------------------------

CREATE TABLE scheduled_callback (
    callback_id     UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID            NOT NULL,
    campaign_id     UUID,
    customer_id     UUID,
    agent_id        UUID,           -- Preferowany agent (sticky agent dla callbacku)

    -- Dane kontaktowe
    phone           VARCHAR(30)     NOT NULL,
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),

    -- Kiedy wykonac oddzwonienie
    scheduled_at    TIMESTAMPTZ     NOT NULL,

    -- Notatka agenta (np. "Klient prosil o kontakt po 15:00")
    notes           TEXT,

    -- Status callbacku
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    -- Mozliwe wartosci: PENDING, PROCESSING, COMPLETED, CANCELLED

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,

    CONSTRAINT pk_scheduled_callback PRIMARY KEY (callback_id),

    CONSTRAINT fk_callback_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_callback_campaign
        FOREIGN KEY (campaign_id)
        REFERENCES campaign (campaign_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_callback_customer
        FOREIGN KEY (customer_id)
        REFERENCES customer (customer_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_callback_agent
        FOREIGN KEY (agent_id)
        REFERENCES app_user (user_id)
        ON DELETE SET NULL
);

-- Pobieranie zaplanowanych callbackow do realizacji (pg_cron lub dialer)
CREATE INDEX idx_callback_scheduled
    ON scheduled_callback (tenant_id, scheduled_at, status)
    WHERE status = 'PENDING';

-- Callbacki przypisane do agenta (widok agenta)
CREATE INDEX idx_callback_agent
    ON scheduled_callback (agent_id, scheduled_at)
    WHERE status = 'PENDING' AND agent_id IS NOT NULL;

CREATE TRIGGER trg_callback_updated_at
    BEFORE UPDATE ON scheduled_callback
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 5. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON TABLE  campaign                          IS 'Kampanie wychodzace (telefon, email). Konfiguracja dialera, harmonogram, dyspozycje. US-08-01 do US-08-06.';
COMMENT ON COLUMN campaign.schedule                 IS 'Harmonogram aktywnosci kampanii (JSONB): daty, godziny, dni tygodnia, strefa czasowa.';
COMMENT ON COLUMN campaign.disposition_codes        IS 'Lista kodow wynikow dostepnych dla agentow tej kampanii. Konfigurowane przez supervisora.';
COMMENT ON COLUMN campaign.max_attempts             IS 'Maksymalna liczba prob dzwonienia do jednego rekordu przed oznaczeniem jako nieosiaglny.';

COMMENT ON TABLE  campaign_contact                  IS 'Rekordy listy kontaktow kampanii. Partycjonowane po campaign_id (LIST). Import CSV: US-08-01.';
COMMENT ON COLUMN campaign_contact.attempt_count    IS 'Liczba wykonanych prob polaczenia. Porownywana z campaign.max_attempts.';
COMMENT ON COLUMN campaign_contact.next_attempt_at  IS 'Czas kolejnej proby (ustawiany przez dialer po nieudanej probie). Indeks dialera.';

COMMENT ON TABLE  scheduled_callback                IS 'Zaplanowane oddzwonienia (US-08-05). Tworzone przez agenta przy dyspozycji CALLBACK.';
