-- =============================================================================
-- V088__fix_partition_rotation_functions.sql
-- DB-052 (FUNDAMENT): Naprawa rotacji partycji contact/audit_log + rozszerzenie
-- mechanizmu rotacji o contact_event/contact_transcription/contact_ai_summary (EPIC-29).
--
-- Migracja: Flyway V088
-- Zaleznosci: V004 (audit_log + create_audit_log_partition/drop_old_audit_log_partitions),
--             V007 (contact + create_contact_partition), V014 (create_next_month_partitions,
--             scheduled_job, cron_log), V077 (wzorzec 1:1 dla create/drop/rotate +
--             rejestracja scheduled_job - plugin_invocation_log), V085 (contact_event
--             spartycjonowany, DB-049), V086 (contact_transcription, DB-050),
--             V087 (contact_ai_summary, DB-051)
-- Blokuje: BE-112, BE-114, BE-115
--
-- KONTEKST (dlaczego to jest fundament epiku): cron.schedule(...) z V014 jest
-- zakomentowany, pg_cron NIE jest wlaczony w obrazie postgres:16-alpine tego
-- srodowiska, i zaden Java @Scheduled job go dzis nie zastepuje (naprawiane w BE-114,
-- ktore korzysta z fundamentu SQL dostarczonego tutaj). Skutek: partycje miesieczne
-- contact/audit_log konczyly sie na 2026_05 (V007/V004) - od czerwca 2026 WSZYSTKIE
-- nowe rekordy tych dwoch tabel ladowaly sie do partycji *_default (bez gornej
-- granicy, poza zasiegiem logiki retencji "skanuj partycje od najstarszej, zatrzymaj
-- sie gdy gorna granica jest mlodsza niz najkrotsza retencja").
--
-- Migracja ma dwie logicznie niezalezne czesci:
--
-- CZESC A - jednorazowa naprawa danych (NIE jest testem/dry-run - dziala na
-- prawdziwych danych, trwale): backfill brakujacych partycji miesiecznych
-- contact/audit_log (2026_06..2026_09) przy uzyciu ISTNIEJACYCH funkcji
-- create_contact_partition/create_audit_log_partition (V007/V004), nastepnie
-- przeniesienie wierszy z contact_default/audit_log_default do wlasciwych partycji.
-- Wolumen w dev jest maly (122 wiersze contact_default, 287 audit_log_default) -
-- zgodnie z jawna uwaga w tresci ticketu NIE budujemy mechanizmu batchowania,
-- pojedyncza transakcja per tabela wystarczy (cala migracja Flyway na PostgreSQL
-- to i tak jedna transakcja - brak w niej instrukcji niedzialajacych w transakcji,
-- np. CREATE INDEX CONCURRENTLY). Bezpiecznik: blok DO z RAISE EXCEPTION przy
-- niezgodnosci sumy wierszy - pelny ROLLBACK calej migracji w razie problemu.
-- UWAGA NA PRZYSZLOSC (produkcja/wiekszy wolumen): przy duzo wiekszej tabeli
-- pojedynczy INSERT...SELECT trzymalby dlugotrwala blokade i naduzywal WAL -
-- strategia batchowa (INSERT+DELETE w petli po zakresach czasu, poza jedna
-- transakcja) bylaby wlasciwa. Ten ticket swiadomie NIE buduje takiego mechanizmu
-- dla garsci wierszy dev (patrz analogiczna decyzja w naglowku V085 sekcja 4).
--
-- CZESC B - rozszerzenie mechanizmu rotacji o 3 tabele spartycjonowane w
-- DB-049/050/051 (contact_event, contact_transcription, contact_ai_summary), ktore
-- swiadomie NIE dostaly wlasnych funkcji rotacji w tamtych migracjach (patrz
-- naglowek V085 sekcja 3) - to zakres tego ticketu. Kazda z 3 tabel dostaje
-- create_<table>_partition/drop_old_<table>_partitions/rotate_<table>_partitions
-- wzorcem 1:1 z plugin_invocation_log (V077), wpis w scheduled_job, i podlaczenie
-- do zbiorczej create_next_month_partitions() (CREATE OR REPLACE, reszta logiki
-- zachowana 1:1).
--
-- UWAGA: wpisy w scheduled_job dodane tutaj sa dzis wylacznie infrastruktura pod
-- pg_cron (NIEAKTYWNY w tym srodowisku) - ich faktyczne, cykliczne wywolywanie
-- z poziomu aplikacji to zakres przyszlego PartitionMaintenanceJob/BE-114
-- (Spring @Scheduled), NIE tej migracji.
-- =============================================================================


-- =============================================================================
-- CZESC A: Backfill brakujacych partycji miesiecznych + przeniesienie danych
--          z *_default do wlasciwych partycji (contact, audit_log)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- A.1-A.5 Backfill partycji + przeniesienie danych, per tabela (CONTACT,
--     AUDIT_LOG) - kazda tabela w JEDNYM bloku DO $$ ... $$.
--
--     UWAGA #1 (odkryte przy weryfikacji tej migracji w dry-run przez psql):
--     PostgreSQL ODMAWIA utworzenia nowej partycji zakresowej, jesli istniejaca
--     partycja DEFAULT zawiera juz wiersze pasujace do nowego zakresu (ERROR:
--     "updated partition constraint for default partition would be violated by
--     some row") - a dokladnie taka jest tu sytuacja (contact_default/
--     audit_log_default maja dzis wiersze z czerwca/lipca/sierpnia 2026, czyli
--     dokladnie w zakresach partycji, ktore probujemy utworzyc). Rozwiazanie:
--     standardowy wzorzec Postgres dla tego scenariusza - najpierw DETACH
--     PARTITION defaultu (staje sie zwykla, samodzielna tabela z danymi
--     nietknietymi), potem tworzenie nowych partycji (juz bez konfliktu, bo
--     default nie jest podlaczony), potem przeniesienie pasujacych wierszy z
--     odlaczonej tabeli do rodzica (trafiaja poprawnie do nowych partycji), a
--     na koniec ponowne ATTACH PARTITION ... DEFAULT (z ewentualnymi,
--     nieobslugiwanymi tu wierszami, jesli takie by sie znalazly).
--
--     UWAGA #2 (odkryte przy weryfikacji przez rzeczywisty Flyway/JDBC, NIE
--     przez psql): gdy DETACH PARTITION i nastepujace po nim CREATE TABLE ...
--     PARTITION OF (wewnatrz create_contact_partition/create_audit_log_partition)
--     sa wysylane jako DWA ODREBNE, top-level statementy SQL w tym samym
--     skrypcie migracji, Flyway (przez sterownik JDBC, extended query protocol)
--     odtworzyl blad "partition would overlap" mimo ze IDENTYCZNA sekwencja
--     dziala bezblednie przez psql (zarowno w jednej transakcji, jak i jako
--     dwa oddzielne, autocommitowane polaczenia - zweryfikowane recznie obiema
--     metodami). Rozwiazanie: DETACH + tworzenie partycji + przeniesienie
--     danych + ATTACH sa tu polaczone w JEDEN atomowy blok DO $$ ... $$ na
--     tabele (DETACH/ATTACH przez dynamiczny EXECUTE) - Postgres wykonuje cala
--     zawartosc bloku PL/pgSQL po stronie serwera bez posrednich round-tripow
--     klienta, co eliminuje ryzyko jakiejkolwiek niezgodnosci w sposobie
--     grupowania/wysylania statementow przez sterownik JDBC. Zweryfikowane
--     w tej finalnej postaci bezposrednio przez flyway-maven-plugin (nie tylko
--     przez psql) przed wprowadzeniem do repo.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    v_contact_total_before  BIGINT;
    v_contact_total_after   BIGINT;
    v_default_before        BIGINT;
    v_default_moved         BIGINT;
    v_default_remaining     BIGINT;
BEGIN
    -- A.1 DETACH defaultu + backfill brakujacych partycji miesiecznych
    -- 2026_06..2026_09 (biezacy miesiac + zapas). Istniejace partycje siegaly
    -- tylko do 2026_05 (V007). create_contact_partition (V007) jest idempotentna.
    EXECUTE 'ALTER TABLE contact DETACH PARTITION contact_default';

    PERFORM create_contact_partition(2026, 6);
    PERFORM create_contact_partition(2026, 7);
    PERFORM create_contact_partition(2026, 8);
    PERFORM create_contact_partition(2026, 9);

    -- A.3 Przeniesienie: odlaczona tabela contact_default -> partycje wlasciwe
    -- (przez INSERT do rodzica contact, ktory po utworzeniu nowych partycji
    -- powyzej poprawnie routuje wiersze do wlasciwego miesiaca). Zakres danych
    -- w contact_default zweryfikowany przed migracja: started_at 2026-06-09 ..
    -- 2026-08-04, 122 wiersze - w calosci pokryty nowo utworzonymi partycjami.
    -- Filtr [2026-06-01, 2026-10-01) ogranicza operacje wylacznie do zakresu
    -- objetego nowo utworzonymi partycjami; ewentualne wiersze SPOZA tego
    -- zakresu (np. bledne dane sprzed marca 2026 lub z przyszlosci) swiadomie
    -- NIE sa tu ruszane, pozostana w odlaczonej tabeli i wroca do niej jako
    -- partycja DEFAULT w A.5 - zostana wykryte ponizej (RAISE WARNING, nie
    -- EXCEPTION - to nie jest scenariusz blokujacy migracje, tylko sygnal do
    -- recznej analizy poza tym tickietem).
    SELECT COUNT(*) INTO v_contact_total_before FROM contact;
    SELECT COUNT(*) INTO v_default_before FROM contact_default;

    INSERT INTO contact (
        contact_id, tenant_id, customer_id, agent_id, queue_id, campaign_id,
        channel, direction, status, remote_address, queued_at, assigned_at,
        started_at, ended_at, duration_seconds, disposition_code, recording_url,
        channel_metadata, created_at, updated_at, callback_id, notes,
        transferred_from_contact_id, campaign_contact_record_id
    )
    SELECT
        contact_id, tenant_id, customer_id, agent_id, queue_id, campaign_id,
        channel, direction, status, remote_address, queued_at, assigned_at,
        started_at, ended_at, duration_seconds, disposition_code, recording_url,
        channel_metadata, created_at, updated_at, callback_id, notes,
        transferred_from_contact_id, campaign_contact_record_id
    FROM contact_default
    WHERE started_at >= '2026-06-01' AND started_at < '2026-10-01';

    GET DIAGNOSTICS v_default_moved = ROW_COUNT;

    DELETE FROM contact_default
    WHERE started_at >= '2026-06-01' AND started_at < '2026-10-01';

    SELECT COUNT(*) INTO v_contact_total_after FROM contact;
    SELECT COUNT(*) INTO v_default_remaining FROM contact_default;

    IF v_contact_total_after != v_contact_total_before + v_default_moved THEN
        RAISE EXCEPTION
            'V088 CZESC A (contact): niezgodnosc liczby wierszy - przed=%, po=%, przeniesiono=%. Rollback calej migracji.',
            v_contact_total_before, v_contact_total_after, v_default_moved;
    END IF;

    -- A.5 Ponowne podlaczenie contact_default jako partycji DEFAULT. Po
    -- powyzszym przeniesieniu tabela zawiera co najwyzej wiersze SPOZA zakresu
    -- [2026-06-01, 2026-10-01) - o ile takie w ogole istnieja (patrz RAISE
    -- WARNING ponizej) - wiec ATTACH ... DEFAULT przejdzie bez konfliktu z
    -- zakresami juz istniejacych partycji (2026_03..2026_09 po tej migracji).
    EXECUTE 'ALTER TABLE contact ATTACH PARTITION contact_default DEFAULT';

    IF v_default_remaining > 0 THEN
        RAISE WARNING
            'V088 CZESC A (contact): contact_default zawiera % wierszy SPOZA obslugiwanego zakresu 2026-06..2026-09 po migracji - wymaga recznej analizy (nie blokuje migracji).',
            v_default_remaining;
    ELSE
        RAISE NOTICE
            'V088 CZESC A (contact): przeniesiono % wierszy z contact_default (bylo %), contact_default pusty.',
            v_default_moved, v_default_before;
    END IF;
END $$;

DO $$
DECLARE
    v_audit_total_before  BIGINT;
    v_audit_total_after   BIGINT;
    v_default_before      BIGINT;
    v_default_moved       BIGINT;
    v_default_remaining   BIGINT;
BEGIN
    -- A.2 DETACH defaultu + backfill brakujacych partycji miesiecznych
    -- AUDIT_LOG 2026_06..2026_09 (analogicznie do A.1, funkcja
    -- create_audit_log_partition, V004; ten sam DETACH z tego samego powodu).
    EXECUTE 'ALTER TABLE audit_log DETACH PARTITION audit_log_default';

    PERFORM create_audit_log_partition(2026, 6);
    PERFORM create_audit_log_partition(2026, 7);
    PERFORM create_audit_log_partition(2026, 8);
    PERFORM create_audit_log_partition(2026, 9);

    -- A.4 Przeniesienie: audit_log_default -> partycje wlasciwe (analogicznie
    -- do A.3). Zakres danych w audit_log_default zweryfikowany przed migracja:
    -- created_at 2026-06-09 .. 2026-08-04, 287 wierszy.
    SELECT COUNT(*) INTO v_audit_total_before FROM audit_log;
    SELECT COUNT(*) INTO v_default_before FROM audit_log_default;

    INSERT INTO audit_log (
        log_id, tenant_id, user_id, action, entity_type, entity_id,
        old_value, new_value, ip_address, user_agent, created_at
    )
    SELECT
        log_id, tenant_id, user_id, action, entity_type, entity_id,
        old_value, new_value, ip_address, user_agent, created_at
    FROM audit_log_default
    WHERE created_at >= '2026-06-01' AND created_at < '2026-10-01';

    GET DIAGNOSTICS v_default_moved = ROW_COUNT;

    DELETE FROM audit_log_default
    WHERE created_at >= '2026-06-01' AND created_at < '2026-10-01';

    SELECT COUNT(*) INTO v_audit_total_after FROM audit_log;
    SELECT COUNT(*) INTO v_default_remaining FROM audit_log_default;

    IF v_audit_total_after != v_audit_total_before + v_default_moved THEN
        RAISE EXCEPTION
            'V088 CZESC A (audit_log): niezgodnosc liczby wierszy - przed=%, po=%, przeniesiono=%. Rollback calej migracji.',
            v_audit_total_before, v_audit_total_after, v_default_moved;
    END IF;

    -- A.5 Ponowne podlaczenie audit_log_default jako partycji DEFAULT.
    EXECUTE 'ALTER TABLE audit_log ATTACH PARTITION audit_log_default DEFAULT';

    IF v_default_remaining > 0 THEN
        RAISE WARNING
            'V088 CZESC A (audit_log): audit_log_default zawiera % wierszy SPOZA obslugiwanego zakresu 2026-06..2026-09 po migracji - wymaga recznej analizy (nie blokuje migracji).',
            v_default_remaining;
    ELSE
        RAISE NOTICE
            'V088 CZESC A (audit_log): przeniesiono % wierszy z audit_log_default (bylo %), audit_log_default pusty.',
            v_default_moved, v_default_before;
    END IF;
END $$;


-- =============================================================================
-- CZESC B: Rozszerzenie mechanizmu rotacji o 3 tabele spartycjonowane w
--          DB-049/050/051 - wzorzec 1:1 z plugin_invocation_log (V077)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- B.1 CONTACT_EVENT - create / drop / rotate
--     Partycjonowana po started_at (V085). Kolumna czasowa = zdarzenie biznesowe
--     (etap kontaktu), analogicznie do contact.started_at.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_contact_event_partition(p_year INT, p_month INT)
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
    v_table_name := 'contact_event_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje (partycje 2026_05..2026_10 juz
    -- utworzone bezposrednio w V085 z finalnymi nazwami - patrz komentarz tam).
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact_event FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_contact_event_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli contact_event dla podanego roku i miesiaca. '
    'Idempotentna - bezpieczna do wielokrotnego wywolania. Wzorzec 1:1 z '
    'create_plugin_invocation_log_partition (V077). Wywolywana przez '
    'rotate_contact_event_partitions oraz create_next_month_partitions.';

CREATE OR REPLACE FUNCTION drop_old_contact_event_partitions(p_retention_months INT DEFAULT 120)
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
          AND  tablename  LIKE 'contact_event_20%'
          AND  tablename  != 'contact_event_default'
    LOOP
        DECLARE
            v_partition_date DATE;
        BEGIN
            v_partition_date := to_date(
                substring(v_rec.tablename FROM 'contact_event_([0-9]{4}_[0-9]{2})'),
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

COMMENT ON FUNCTION drop_old_contact_event_partitions(INT) IS
    'Usuwa partycje tabeli contact_event starsze niz p_retention_months miesiecy '
    '(domyslnie 120 = 10 lat, gorna granica CHECK w tenant_retention_policy.retention_months, '
    'BETWEEN 1 AND 120, V082). DECYZJA: contact_event to dane operacyjne powiazane z '
    'retencja tenanta (kategoria CONTACT_INTERACTIONS, dzis platformowy default 60 '
    'miesiecy, konfigurowalna do 120), NIE audytem platformowym (odwrotnie niz '
    'audit_log/plugin_invocation_log, ktore maja stały, techniczny default 24). Realna, '
    'per-tenantowa egzekucja retencji dzieje sie na poziomie WIERSZA przez '
    'RetentionPurgeService (BE-113) - ta funkcja to WYLACZNIE backstop na poziomie '
    'calej partycji (miesiaca), dlatego domyslny retention_months jest celowo ustawiony '
    'na maksimum dopuszczalne przez tenant_retention_policy, zeby DROP TABLE calej '
    'partycji nigdy nie wyprzedzil najdluzszej legalnie skonfigurowanej retencji tenanta.';

CREATE OR REPLACE FUNCTION rotate_contact_event_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dropped INT;
BEGIN
    PERFORM create_contact_event_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_contact_event_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    v_dropped := drop_old_contact_event_partitions(120);

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('rotate_contact_event_partitions', NOW(), 'SUCCESS',
            'Usunieto starych partycji: ' || v_dropped, v_dropped);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_contact_event_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- B.2 CONTACT_TRANSCRIPTION - create / drop / rotate
--     Partycjonowana po created_at (V086).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_contact_transcription_partition(p_year INT, p_month INT)
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
    v_table_name := 'contact_transcription_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact_transcription FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_contact_transcription_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli contact_transcription dla podanego roku i miesiaca. '
    'Idempotentna - bezpieczna do wielokrotnego wywolania. Wzorzec 1:1 z '
    'create_plugin_invocation_log_partition (V077). Wywolywana przez '
    'rotate_contact_transcription_partitions oraz create_next_month_partitions.';

CREATE OR REPLACE FUNCTION drop_old_contact_transcription_partitions(p_retention_months INT DEFAULT 120)
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
          AND  tablename  LIKE 'contact_transcription_20%'
          AND  tablename  != 'contact_transcription_default'
    LOOP
        DECLARE
            v_partition_date DATE;
        BEGIN
            v_partition_date := to_date(
                substring(v_rec.tablename FROM 'contact_transcription_([0-9]{4}_[0-9]{2})'),
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

COMMENT ON FUNCTION drop_old_contact_transcription_partitions(INT) IS
    'Usuwa partycje tabeli contact_transcription starsze niz p_retention_months miesiecy '
    '(domyslnie 120 = 10 lat, gorna granica CHECK w tenant_retention_policy.retention_months, '
    'V082). DECYZJA: contact_transcription nalezy do kategorii TRANSCRIPTS w '
    'tenant_retention_policy - dzis platformowy default to zaledwie 3 miesiace '
    '(CEIL(90 dni / 30.0)), ale kazdy tenant moze skonfigurowac az do 120 miesiecy '
    '(CHECK). Realna, per-tenantowa egzekucja retencji dzieje sie na poziomie WIERSZA '
    'przez RetentionPurgeService (BE-113) - ta funkcja to WYLACZNIE backstop na '
    'poziomie calej partycji (miesiaca), dlatego domyslny retention_months MUSI byc '
    'ustawiony na maksimum dopuszczalne (120), a nie na dzisiejszy platformowy default '
    '(3 miesiace) - w przeciwnym razie DROP TABLE calej partycji moglby usunac '
    'transkrypcje tenanta, ktory swiadomie skonfigurowal dluzsza retencje.';

CREATE OR REPLACE FUNCTION rotate_contact_transcription_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dropped INT;
BEGIN
    PERFORM create_contact_transcription_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_contact_transcription_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    v_dropped := drop_old_contact_transcription_partitions(120);

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('rotate_contact_transcription_partitions', NOW(), 'SUCCESS',
            'Usunieto starych partycji: ' || v_dropped, v_dropped);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_contact_transcription_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- B.3 CONTACT_AI_SUMMARY - create / drop / rotate
--     Partycjonowana po generated_at (V087).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_contact_ai_summary_partition(p_year INT, p_month INT)
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
    v_table_name := 'contact_ai_summary_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF contact_ai_summary FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_contact_ai_summary_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli contact_ai_summary dla podanego roku i miesiaca. '
    'Idempotentna - bezpieczna do wielokrotnego wywolania. Wzorzec 1:1 z '
    'create_plugin_invocation_log_partition (V077). Wywolywana przez '
    'rotate_contact_ai_summary_partitions oraz create_next_month_partitions.';

CREATE OR REPLACE FUNCTION drop_old_contact_ai_summary_partitions(p_retention_months INT DEFAULT 120)
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
          AND  tablename  LIKE 'contact_ai_summary_20%'
          AND  tablename  != 'contact_ai_summary_default'
    LOOP
        DECLARE
            v_partition_date DATE;
        BEGIN
            v_partition_date := to_date(
                substring(v_rec.tablename FROM 'contact_ai_summary_([0-9]{4}_[0-9]{2})'),
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

COMMENT ON FUNCTION drop_old_contact_ai_summary_partitions(INT) IS
    'Usuwa partycje tabeli contact_ai_summary starsze niz p_retention_months miesiecy '
    '(domyslnie 120 = 10 lat, gorna granica CHECK w tenant_retention_policy.retention_months, '
    'V082). DECYZJA: contact_ai_summary nie ma dzis wlasnej dedykowanej kategorii w '
    'tenant_retention_policy (najblizsza semantycznie: TRANSCRIPTS, bo streszczenie AI '
    'jest danymi pochodnymi transkrypcji) - w braku jednoznacznego mapowania przyjeto '
    'ten sam, bezpieczny konserwatywny default co dla contact_transcription. Realna, '
    'per-tenantowa egzekucja retencji dzieje sie na poziomie WIERSZA przez '
    'RetentionPurgeService (BE-113) - ta funkcja to WYLACZNIE backstop na poziomie '
    'calej partycji (miesiaca), dlatego domyslny retention_months jest celowo ustawiony '
    'na maksimum dopuszczalne przez tenant_retention_policy, zeby DROP TABLE calej '
    'partycji nigdy nie wyprzedzil najdluzszej legalnie skonfigurowanej retencji tenanta.';

CREATE OR REPLACE FUNCTION rotate_contact_ai_summary_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_dropped INT;
BEGIN
    PERFORM create_contact_ai_summary_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT
    );
    PERFORM create_contact_ai_summary_partition(
        EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '2 months')::INT,
        EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '2 months')::INT
    );

    v_dropped := drop_old_contact_ai_summary_partitions(120);

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES ('rotate_contact_ai_summary_partitions', NOW(), 'SUCCESS',
            'Usunieto starych partycji: ' || v_dropped, v_dropped);

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'rotate_contact_ai_summary_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- B.4 Rozszerzenie zbiorczej funkcji create_next_month_partitions() (V014,
--     rozszerzonej juz raz w V077 o plugin_invocation_log) - CREATE OR REPLACE,
--     reszta logiki zachowana 1:1, dodane wylacznie 3 nowe wywolania PERFORM.
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
    PERFORM create_contact_event_partition(v_next_year, v_next_month);
    PERFORM create_contact_transcription_partition(v_next_year, v_next_month);
    PERFORM create_contact_ai_summary_partition(v_next_year, v_next_month);

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('create_next_month_partitions', NOW(), 'SUCCESS',
            format('Utworzono partycje na %s-%s', v_next_year, LPAD(v_next_month::TEXT, 2, '0')));

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'create_next_month_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- B.5 Rejestracja 3 nowych zadan rotacyjnych w scheduled_job (wzorzec V014/V077).
--     Godziny rozlozone tak, by nie kolidowac z istniejacymi wpisami (przejrzano
--     cala tabele scheduled_job przed wyborem): rotate_audit_log_partitions
--     30 2 1 * *, rotate_plugin_invocation_log_partitions 45 2 1 * *,
--     rotate_contact_partitions 0 3 1 * *, purge_campaign_contact_archive
--     0 3 1 1 * (tylko styczen), archive_completed_campaign_contacts 0 4 * * *.
--     Nowe 3 zadania umieszczone tuz po rotate_contact_partitions (03:00) i przed
--     archive_completed_campaign_contacts (04:00): 03:15, 03:30, 03:45.
-- ---------------------------------------------------------------------------

INSERT INTO scheduled_job
    (job_name, description, cron_expression, pg_function)
VALUES
(
    'rotate_contact_event_partitions',
    'Tworzy partycje contact_event na nastepne 2 miesiace i usuwa partycje starsze niz 120 miesiecy (backstop - realna retencja per-tenant przez RetentionPurgeService/BE-113).',
    '15 3 1 * *',  -- Pierwszego kazdego miesiaca o 03:15 UTC
    'rotate_contact_event_partitions'
),
(
    'rotate_contact_transcription_partitions',
    'Tworzy partycje contact_transcription na nastepne 2 miesiace i usuwa partycje starsze niz 120 miesiecy (backstop - realna retencja per-tenant przez RetentionPurgeService/BE-113).',
    '30 3 1 * *',  -- Pierwszego kazdego miesiaca o 03:30 UTC
    'rotate_contact_transcription_partitions'
),
(
    'rotate_contact_ai_summary_partitions',
    'Tworzy partycje contact_ai_summary na nastepne 2 miesiace i usuwa partycje starsze niz 120 miesiecy (backstop - realna retencja per-tenant przez RetentionPurgeService/BE-113).',
    '45 3 1 * *',  -- Pierwszego kazdego miesiaca o 03:45 UTC
    'rotate_contact_ai_summary_partitions'
)
ON CONFLICT (job_name) DO NOTHING;
