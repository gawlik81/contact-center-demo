-- =============================================================================
-- V007__create_contact.sql
-- DB-006 (cz. 2): Tabela CONTACT – partycjonowanie, indeksy raportowe, nagrania
--
-- Migracja: Flyway V007
-- Zaleznosci: V002 (tenant), V003 (app_user), V006 (customer)
-- Odniesienie PRD: US-03-05, US-09-02, EPIC-03, EPIC-09
--
-- CONTACT = jeden kontakt/interakcja z klientem (polaczenie, email, chat).
-- Partycjonowany po started_at (RANGE miesiecznie) dla skalowalnosci historycznej.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE contact_channel AS ENUM (
    'PHONE',
    'EMAIL',
    'SOCIAL_FACEBOOK',
    'SOCIAL_INSTAGRAM',
    'SOCIAL_WHATSAPP'
);

CREATE TYPE contact_direction AS ENUM ('INBOUND', 'OUTBOUND');

CREATE TYPE contact_status AS ENUM (
    'QUEUED',       -- Oczekuje w kolejce na agenta
    'ACTIVE',       -- W trakcie obslugi przez agenta
    'ON_HOLD',      -- Wstrzymany (hold)
    'COMPLETED',    -- Zakonczony normalnie
    'ABANDONED'     -- Klient porzucil kolejke przed polaczeniem z agentem
);

COMMENT ON TYPE contact_channel   IS 'Kanal komunikacji kontaktu.';
COMMENT ON TYPE contact_direction IS 'Kierunek kontaktu: INBOUND = klient do nas, OUTBOUND = my do klienta.';
COMMENT ON TYPE contact_status    IS 'Stan cyklu zycia kontaktu.';

-- ---------------------------------------------------------------------------
-- 2. Tabela CONTACT (partycjonowana)
-- ---------------------------------------------------------------------------

CREATE TABLE contact (
    contact_id          UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID                NOT NULL,

    -- Klient (nullable – nieznany klient przed identyfikacja)
    -- ON DELETE SET NULL: anonimizacja RODO zachowuje rekord kontaktu bez PII
    customer_id         UUID,

    -- Agent obslugujacy (nullable – kontakt moze byc jeszcze w kolejce)
    -- ON DELETE SET NULL: jesli agent zostanie usuniety, historia pozostaje
    agent_id            UUID,

    -- Kolejka, z ktorej pochodzi kontakt
    queue_id            UUID,

    -- Kampania (tylko dla OUTBOUND, nullable)
    campaign_id         UUID,

    channel             contact_channel     NOT NULL,
    direction           contact_direction   NOT NULL,
    status              contact_status      NOT NULL DEFAULT 'QUEUED',

    -- Numer telefonu zadzwoniacy (CLI) lub email nadawcy
    -- Przechowywany osobno jako string dla szybkiego lookup (nawet jesli klient nieznany)
    remote_address      VARCHAR(255),

    -- Czas kolejkowania – dla KPI ASA (Average Speed of Answer)
    queued_at           TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    -- Czas przypisania do agenta – delta (queued_at, assigned_at) = czas oczekiwania
    assigned_at         TIMESTAMPTZ,

    -- Klucz partycjonowania i czas trwania
    started_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    ended_at            TIMESTAMPTZ,

    -- Czas trwania w sekundach (obliczany przy zakonczeniu, dla wydajnosci raportow)
    duration_seconds    INT,

    -- Wynik kontaktu rejestrowany przez agenta (np. SALE, DECLINED, NO_ANSWER, CALLBACK)
    -- Wartosci konfigurowane per kampania w DISPOSITION_CODE (lub wolny tekst dla inbound)
    disposition_code    VARCHAR(50),

    -- URL do nagrania w Object Storage (S3-compatible), NULL jesli brak nagrania
    -- Format: s3://bucket/tenant_id/contact_id/recording.mp3
    recording_url       TEXT,

    -- Dodatkowe metadane kanalowe (np. SIP Call-ID, email Message-ID)
    channel_metadata    JSONB               NOT NULL DEFAULT '{}',

    created_at          TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_contact PRIMARY KEY (contact_id, started_at),

    -- FK z ON DELETE SET NULL dla anonimizacji RODO
    -- (FK na partycjonowanej tabeli musi byc bez ON DELETE CASCADE do partycji zewnetrznych)

    CONSTRAINT chk_contact_duration CHECK (
        duration_seconds IS NULL OR duration_seconds >= 0
    ),
    CONSTRAINT chk_contact_times CHECK (
        ended_at IS NULL OR ended_at >= started_at
    )

) PARTITION BY RANGE (started_at);

COMMENT ON TABLE contact IS
    'Jeden kontakt (interakcja) z klientem konczowym. '
    'Partycjonowany miesiecznie po started_at dla historii wieloletniej. '
    'FK customer_id i agent_id z ON DELETE SET NULL – anonimizacja RODO.';

COMMENT ON COLUMN contact.contact_id        IS 'UUID kontaktu.';
COMMENT ON COLUMN contact.customer_id       IS 'FK do customer. NULL jesli klient nieznany lub zanonimizowany.';
COMMENT ON COLUMN contact.agent_id          IS 'FK do app_user (agent). NULL jesli kontakt porzucony lub jeszcze w kolejce.';
COMMENT ON COLUMN contact.queue_id          IS 'Kolejka, przez ktora przyszedl kontakt.';
COMMENT ON COLUMN contact.campaign_id       IS 'Kampania outbound. NULL dla kontaktow inbound.';
COMMENT ON COLUMN contact.remote_address    IS 'Numer CLI (telefon) lub email nadawcy. Dla szybkiego lookup bez JOIN do customer.';
COMMENT ON COLUMN contact.duration_seconds  IS 'Czas trwania w sekundach (ended_at - started_at). Denormalizacja dla wydajnosci raportow.';
COMMENT ON COLUMN contact.disposition_code  IS 'Wynik kontaktu: konfigurowalny per kampania lub wolny dla inbound.';
COMMENT ON COLUMN contact.recording_url     IS 'URL nagrania w S3-compatible storage. NULL jesli kanel nie obsluguje nagran lub nagranie nie istnieje.';
COMMENT ON COLUMN contact.channel_metadata  IS 'Metadane kanalowe: SIP Call-ID, email thread-id, social conversation_id itp.';

-- ---------------------------------------------------------------------------
-- 3. Partycje inicjalne
-- ---------------------------------------------------------------------------

CREATE TABLE contact_2026_03
    PARTITION OF contact
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE contact_2026_04
    PARTITION OF contact
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE contact_2026_05
    PARTITION OF contact
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE contact_default
    PARTITION OF contact DEFAULT;

-- ---------------------------------------------------------------------------
-- 4. Indeksy (dziedziczone przez partycje)
-- ---------------------------------------------------------------------------

-- Historia klienta (US-09-02): sortowana od najnowszych
CREATE INDEX idx_contact_customer_history
    ON contact (tenant_id, customer_id, started_at DESC)
    WHERE customer_id IS NOT NULL;

-- Raporty agenta (US-10-02): praca agenta w danym okresie
CREATE INDEX idx_contact_agent_history
    ON contact (tenant_id, agent_id, started_at DESC)
    WHERE agent_id IS NOT NULL;

-- Aktywne kontakty (dashboard RT): szybkie pobieranie statusu
CREATE INDEX idx_contact_tenant_status
    ON contact (tenant_id, status)
    WHERE status IN ('QUEUED', 'ACTIVE', 'ON_HOLD');

-- Raporty kampanii (US-10-03): statystyki outbound
CREATE INDEX idx_contact_campaign
    ON contact (tenant_id, campaign_id, started_at DESC)
    WHERE campaign_id IS NOT NULL;

-- Lookup po adresie zdalnym (CLI lookup dla przychodzacych polaczen)
CREATE INDEX idx_contact_remote_address
    ON contact (tenant_id, remote_address, started_at DESC)
    WHERE remote_address IS NOT NULL;

-- Indeks kanalowy dla raportow per kanal
-- Uwaga: started_at::DATE nie jest IMMUTABLE dla timestamptz (wynik zalezy od TimeZone GUC).
-- Indeks na surowej kolumnie timestamptz jest uzyty zamiast – planner moze go efektywnie
-- wykorzystac przez zakres dat (started_at >= x AND started_at < x+1).
CREATE INDEX idx_contact_channel_date
    ON contact (tenant_id, channel, started_at);

-- Dyspozycja dla raportow konwersji
CREATE INDEX idx_contact_disposition
    ON contact (tenant_id, disposition_code, started_at)
    WHERE disposition_code IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 5. Trigger: updated_at + obliczanie duration_seconds
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION fn_contact_on_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := NOW();

    -- Automatyczne obliczenie duration przy zakonczeniu kontaktu
    IF NEW.ended_at IS NOT NULL AND OLD.ended_at IS NULL THEN
        NEW.duration_seconds := EXTRACT(EPOCH FROM (NEW.ended_at - NEW.started_at))::INT;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contact_on_update
    BEFORE UPDATE ON contact
    FOR EACH ROW
    EXECUTE FUNCTION fn_contact_on_update();

-- ---------------------------------------------------------------------------
-- 6. Funkcja tworzenia partycji CONTACT
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_contact_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date DATE;
    v_end_date   DATE;
    v_table_name TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := v_start_date + INTERVAL '1 month';
    v_table_name := 'contact_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        RAISE NOTICE 'Utworzono partycje contact: %', v_table_name;
    END IF;
END;
$$;
