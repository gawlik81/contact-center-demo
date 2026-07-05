-- =============================================================================
-- V077__create_plugin_invocation_log.sql
-- DB-045: Audit log wywolan pluginow (EPIC-28). RANGE-partycjonowana miesiecznie.
--
-- Migracja: Flyway V077
-- Zaleznosci: V074 (plugin/plugin_version), V075 (tenant_plugin_installation)
-- Odniesienie: ARCHITECTURE.md SS11.12 (odrebny od audit_log – inny wolumen/kszalt danych)
--
-- Strategia partycjonowania: RANGE po invoked_at, miesiecznie – DOKLADNIE TEN SAM
-- mechanizm co audit_log (V004) i contact (V007): funkcja create_<table>_partition(),
-- funkcja rotate_<table>_partitions() wywolywana przez scheduled_job (pg_cron / fallback
-- aplikacyjny, V014). Nie tworzymy nowego mechanizmu partycjonowania - podlaczamy sie
-- do istniejacego (rozszerzamy create_next_month_partitions() i tabele scheduled_job).
--
-- UWAGA (korekta wzgledem DDL z TASKS-DATABASE.md): tenant_id REFERENCES tenant(id)
-- bylo blednym FK w specyfikacji ticketu (ten sam blad co DB-043/DB-044) – PK tabeli
-- tenant to tenant_id, nie id (zweryfikowane \d tenant). Poprawione na tenant(tenant_id).
-- FK do tenant_plugin_installation(id) bylo poprawne (ta tabela ma PK `id`, konwencja V069+).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela nadrzedna (partycjonowana)
-- ---------------------------------------------------------------------------

CREATE TABLE plugin_invocation_log (
    id                              UUID        NOT NULL DEFAULT gen_random_uuid(),
    invoked_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id                       UUID        NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    tenant_plugin_installation_id   UUID        REFERENCES tenant_plugin_installation(id) ON DELETE SET NULL,
    extension_point                 VARCHAR(30) NOT NULL,
    related_contact_id              UUID,
    status                          VARCHAR(20) NOT NULL,
    duration_ms                     INT,
    error_summary                   TEXT,
    request_payload_redacted        JSONB,

    PRIMARY KEY (id, invoked_at),
    CONSTRAINT chk_plugin_invocation_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'TIMED_OUT', 'CIRCUIT_OPEN', 'SKIPPED_DISABLED')
    )
) PARTITION BY RANGE (invoked_at);

COMMENT ON TABLE plugin_invocation_log IS
    'Log kazdego wywolania pluginu (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED). '
    'Odrebny od audit_log - inny wolumen/kszalt danych (ARCHITECTURE.md SS11.12). '
    'Tabela partycjonowana miesiecznie po invoked_at (ten sam mechanizm co audit_log/contact).';
COMMENT ON COLUMN plugin_invocation_log.related_contact_id IS
    'ON DELETE SET NULL - historia wywolan przetrwa usuniecie kontaktu (GDPR-safe FK, wzorzec contact.agent_id/customer_id). '
    'Brak fizycznego FK - contact jest partycjonowana, PostgreSQL nie wspiera FK do tabeli partycjonowanej ze strony rodzica w tym kontekscie odwrotnym; integralnosc egzekwowana aplikacyjnie.';
COMMENT ON COLUMN plugin_invocation_log.request_payload_redacted IS
    'Snapshot payloadu z usunietym PII, do debugowania - nigdy surowe dane klienta.';

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne (wzorzec audit_log/contact: biezacy + 2 nastepne miesiace)
-- ---------------------------------------------------------------------------

CREATE TABLE plugin_invocation_log_2026_06
    PARTITION OF plugin_invocation_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE plugin_invocation_log_2026_07
    PARTITION OF plugin_invocation_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE plugin_invocation_log_2026_08
    PARTITION OF plugin_invocation_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

-- Partycja domyslna dla wartosci spoza zakresu (bezpieczenstwo - nie gubimy rekordow)
CREATE TABLE plugin_invocation_log_default
    PARTITION OF plugin_invocation_log DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Indeksy (tworzone na tabeli nadrzednej - dziedziczone przez partycje)
-- ---------------------------------------------------------------------------

CREATE INDEX idx_plugin_invocation_log_installation
    ON plugin_invocation_log (tenant_plugin_installation_id, invoked_at DESC);

CREATE INDEX idx_plugin_invocation_log_contact
    ON plugin_invocation_log (related_contact_id)
    WHERE related_contact_id IS NOT NULL;

CREATE INDEX idx_plugin_invocation_log_status
    ON plugin_invocation_log (tenant_id, status, invoked_at DESC)
    WHERE status IN ('FAILED', 'TIMED_OUT', 'CIRCUIT_OPEN');

-- ---------------------------------------------------------------------------
-- 4. RLS
-- ---------------------------------------------------------------------------

ALTER TABLE plugin_invocation_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_invocation_log FORCE ROW LEVEL SECURITY;
CREATE POLICY plugin_invocation_log_isolation ON plugin_invocation_log
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ---------------------------------------------------------------------------
-- 5. Funkcja tworzenia partycji PLUGIN_INVOCATION_LOG
--    (identyczny wzorzec co create_audit_log_partition / create_contact_partition)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_plugin_invocation_log_partition(p_year INT, p_month INT)
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
    v_table_name := 'plugin_invocation_log_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF plugin_invocation_log FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );

        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_plugin_invocation_log_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli plugin_invocation_log dla podanego roku i miesiaca. '
    'Idempotentna - bezpieczna do wielokrotnego wywolania. '
    'Wywolywana przez pg_cron (rotate_plugin_invocation_log_partitions, scheduled_job) na poczatku kazdego miesiaca.';

-- ---------------------------------------------------------------------------
-- 6. Funkcja usuwania starych partycji (retencja 24 miesiace, wzorzec audit_log)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION drop_old_plugin_invocation_log_partitions(p_retention_months INT DEFAULT 24)
RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff_date   DATE;
    v_dropped_count INT := 0;
    v_rec           RECORD;
BEGIN
    v_cutoff_date := CURRENT_DATE - (p_retention_months || ' months')::INTERVAL;

    FOR v_rec IN
        SELECT tablename
        FROM   pg_tables
        WHERE  schemaname = 'public'
          AND  tablename  LIKE 'plugin_invocation_log_20%'
          AND  tablename  != 'plugin_invocation_log_default'
    LOOP
        DECLARE
            v_partition_date DATE;
        BEGIN
            v_partition_date := to_date(
                substring(v_rec.tablename FROM 'plugin_invocation_log_([0-9]{4}_[0-9]{2})'),
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

COMMENT ON FUNCTION drop_old_plugin_invocation_log_partitions(INT) IS
    'Usuwa partycje tabeli plugin_invocation_log starsze niz p_retention_months miesiecy (domyslnie 24). '
    'Wywolywana przez pg_cron (rotate_plugin_invocation_log_partitions) raz w miesiacu.';

-- ---------------------------------------------------------------------------
-- 7. Funkcja rotacji (create + drop), wzorzec rotate_audit_log_partitions (V014)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION rotate_plugin_invocation_log_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dropped INT;
BEGIN
    -- Tworzenie partycji na nastepne 2 miesiace
    PERFORM create_plugin_invocation_log_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_plugin_invocation_log_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    -- Usuniecie partycji starszych niz 24 miesiace
    v_dropped := drop_old_plugin_invocation_log_partitions(24);

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('rotate_plugin_invocation_log_partitions', NOW(), 'SUCCESS',
            'Usunieto starych partycji: ' || v_dropped, v_dropped);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_plugin_invocation_log_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- 8. Podlaczenie do istniejacego mechanizmu zbiorczego create_next_month_partitions()
--    (rozszerzenie funkcji z V014 o trzecia partycjonowana tabele - nie tworzymy
--    nowego mechanizmu, tylko CREATE OR REPLACE istniejacej funkcji)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_next_month_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_next_year  INT;
    v_next_month INT;
BEGIN
    v_next_year  := EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT;
    v_next_month := EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT;

    PERFORM create_audit_log_partition(v_next_year, v_next_month);
    PERFORM create_contact_partition(v_next_year, v_next_month);
    PERFORM create_plugin_invocation_log_partition(v_next_year, v_next_month);

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('create_next_month_partitions', NOW(), 'SUCCESS',
            format('Utworzono partycje na %s-%s', v_next_year, LPAD(v_next_month::TEXT, 2, '0')));

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'create_next_month_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- 9. Rejestracja zadania rotacyjnego w scheduled_job (wzorzec V014, sekcja 2)
-- ---------------------------------------------------------------------------

INSERT INTO scheduled_job
    (job_name, description, cron_expression, pg_function)
VALUES
(
    'rotate_plugin_invocation_log_partitions',
    'Tworzy partycje plugin_invocation_log na nastepne 2 miesiace i usuwa partycje starsze niz 24 miesiace.',
    '45 2 1 * *',  -- Pierwszego kazdego miesiaca o 02:45 UTC (po rotate_audit_log_partitions o 02:30)
    'rotate_plugin_invocation_log_partitions'
)
ON CONFLICT (job_name) DO NOTHING;
