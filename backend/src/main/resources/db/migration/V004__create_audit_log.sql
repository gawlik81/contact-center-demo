-- =============================================================================
-- V004__create_audit_log.sql
-- DB-004: Tabela AUDIT_LOG – partycjonowanie po dacie, indeksy, retencja
--
-- Migracja: Flyway V004
-- Zaleznosci: V002 (tenant), V003 (app_user)
-- Odniesienie PRD: NFR-SEC06, RODO Art. 30, przekrojowe
--
-- Strategia partycjonowania: RANGE po created_at, miesiecznie.
-- Partycje tworzone automatycznie przez funkcje create_audit_log_partition().
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela nadrzedna (partycjonowana)
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    log_id          UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID,           -- NULL dla operacji globalnych (np. tworzenie tenanta)
    user_id         UUID,           -- NULL dla operacji systemowych (np. automatyczna anonimizacja)

    -- Co sie stalo
    action          VARCHAR(100)    NOT NULL,  -- np. CUSTOMER_CREATED, USER_DEACTIVATED, TENANT_CONFIG_UPDATED
    entity_type     VARCHAR(100),              -- np. CUSTOMER, USER, CAMPAIGN, TENANT
    entity_id       UUID,                      -- ID modyfikowanej encji

    -- Stan przed i po zmianie (JSONB umozliwia przeszukiwanie)
    old_value       JSONB,          -- NULL dla operacji CREATE
    new_value       JSONB,          -- NULL dla operacji DELETE

    -- Kontekst zdalny
    ip_address      INET,
    user_agent      TEXT,

    -- Klucz partycjonowania – MUSI byc NOT NULL
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_log PRIMARY KEY (log_id, created_at),

    -- FK z odroczonym sprawdzaniem (FK nie mozna dodac do tabeli partycjonowanej
    -- bezposrednio w PostgreSQL – egzekwowane przez aplikacje)
    CONSTRAINT chk_audit_log_action_not_empty CHECK (action <> '')

) PARTITION BY RANGE (created_at);

COMMENT ON TABLE audit_log IS
    'Rejestr audytowy wszystkich operacji administracyjnych i zmian danych (NFR-SEC06, RODO Art. 30). '
    'Tabela partycjonowana miesiecznie po created_at. '
    'Retencja: partycje starsze niz 2 lata usuwane przez cron (DB-018).';

COMMENT ON COLUMN audit_log.log_id      IS 'UUID zdarzenia audytowego.';
COMMENT ON COLUMN audit_log.tenant_id   IS 'Tenant kontekstu zdarzenia. NULL dla operacji globalnych.';
COMMENT ON COLUMN audit_log.user_id     IS 'Uzytkownik wywolujacy akcje. NULL dla zdarzen systemowych.';
COMMENT ON COLUMN audit_log.action      IS 'Kod akcji (SNAKE_UPPER_CASE), np. CUSTOMER_ANONYMIZED, CAMPAIGN_STARTED.';
COMMENT ON COLUMN audit_log.entity_type IS 'Typ modyfikowanej encji domenowej.';
COMMENT ON COLUMN audit_log.entity_id   IS 'UUID encji domenowej (np. customer_id, campaign_id).';
COMMENT ON COLUMN audit_log.old_value   IS 'Stan encji PRZED zmiana (JSON). NULL przy tworzeniu.';
COMMENT ON COLUMN audit_log.new_value   IS 'Stan encji PO zmianie (JSON). NULL przy usuwaniu.';

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne (biezacy i dwa nastepne miesiace)
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log_2026_03
    PARTITION OF audit_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE audit_log_2026_04
    PARTITION OF audit_log
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE TABLE audit_log_2026_05
    PARTITION OF audit_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

-- Partycja domyslna dla wartosci spoza zakresu (bezpieczenstwo – nie gubimy rekordow)
CREATE TABLE audit_log_default
    PARTITION OF audit_log DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Indeksy (tworzone na tabeli nadrzednej – dziedziczone przez partycje)
-- ---------------------------------------------------------------------------

-- Podstawowy indeks dostepowy: historia dla tenanta per typ encji (sortowana od najnowszych)
CREATE INDEX idx_audit_log_tenant_entity
    ON audit_log (tenant_id, entity_type, created_at DESC);

-- Linia czasu operacji uzytkownika (np. co robil konkretny admin)
CREATE INDEX idx_audit_log_user_created
    ON audit_log (user_id, created_at DESC);

-- Wyszukiwanie po ID encji (np. pelna historia kampanii)
CREATE INDEX idx_audit_log_entity_id
    ON audit_log (entity_id, created_at DESC)
    WHERE entity_id IS NOT NULL;

-- Indeks GIN na old_value i new_value – zapytania do wartosci JSONB
-- (np. znajdz wszystkie rekordy gdzie zmieniono status z ACTIVE na INACTIVE)
CREATE INDEX idx_audit_log_old_value_gin
    ON audit_log USING GIN (old_value)
    WHERE old_value IS NOT NULL;

CREATE INDEX idx_audit_log_new_value_gin
    ON audit_log USING GIN (new_value)
    WHERE new_value IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. Funkcja automatycznego tworzenia partycji
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_audit_log_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date  DATE;
    v_end_date    DATE;
    v_table_name  TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := v_start_date + INTERVAL '1 month';
    v_table_name := 'audit_log_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );

        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje – pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_audit_log_partition(INT, INT) IS
    'Tworzy miesiczna partycje tabeli audit_log dla podanego roku i miesiaca. '
    'Idempotentna – bezpieczna do wielokrotnego wywolania. '
    'Wywolywana przez pg_cron (DB-018) na poczatku kazdego miesiaca.';

-- ---------------------------------------------------------------------------
-- 5. Funkcja usuwania starych partycji (retencja 2 lata)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION drop_old_audit_log_partitions(p_retention_months INT DEFAULT 24)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff_date   DATE;
    v_table_name    TEXT;
    v_dropped_count INT := 0;
    v_rec           RECORD;
BEGIN
    v_cutoff_date := CURRENT_DATE - (p_retention_months || ' months')::INTERVAL;

    FOR v_rec IN
        SELECT tablename
        FROM   pg_tables
        WHERE  schemaname = 'public'
          AND  tablename  LIKE 'audit_log_20%'  -- tylko partycje z rokiem
          AND  tablename  != 'audit_log_default'
    LOOP
        -- Parsowanie daty z nazwy tabeli (format: audit_log_YYYY_MM)
        DECLARE
            v_partition_date DATE;
        BEGIN
            v_partition_date := to_date(
                substring(v_rec.tablename FROM 'audit_log_([0-9]{4}_[0-9]{2})'),
                'YYYY_MM'
            );

            IF v_partition_date < v_cutoff_date THEN
                EXECUTE format('DROP TABLE IF EXISTS %I', v_rec.tablename);
                v_dropped_count := v_dropped_count + 1;
                RAISE NOTICE 'Usunieto stara partycje: %', v_rec.tablename;
            END IF;
        EXCEPTION
            WHEN OTHERS THEN
                RAISE WARNING 'Blad parsowania daty z nazwy partycji: %', v_rec.tablename;
        END;
    END LOOP;

    RETURN v_dropped_count;
END;
$$;

COMMENT ON FUNCTION drop_old_audit_log_partitions(INT) IS
    'Usuwa partycje tabeli audit_log starsze niz p_retention_months miesiecy (domyslnie 24). '
    'Wywolywana przez pg_cron (DB-018) raz w miesiacu.';

-- ---------------------------------------------------------------------------
-- 6. Tabela logow zadania cron (uzywana rowniez przez DB-018)
-- ---------------------------------------------------------------------------

CREATE TABLE cron_log (
    log_id      BIGSERIAL       PRIMARY KEY,
    job_name    VARCHAR(100)    NOT NULL,
    started_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ,
    status      VARCHAR(20)     NOT NULL DEFAULT 'RUNNING',  -- RUNNING | SUCCESS | ERROR
    message     TEXT,
    rows_affected INT
);

CREATE INDEX idx_cron_log_job_started
    ON cron_log (job_name, started_at DESC);

COMMENT ON TABLE cron_log IS
    'Log wykonan zaplanowanych zadan (pg_cron lub zewnetrzny scheduler). '
    'Umozliwia monitorowanie i diagnozowanie problemow z zadaniami maintenance.';
