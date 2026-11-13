-- =============================================================================
-- V149__create_shedlock.sql
-- DB-090 (EPIC-33 faza 0): tabela `shedlock` dla ShedLock JDBC -- rozproszone blokady jobow
-- @Scheduled (22 metody w 19 klasach uruchamiaja sie dotad na KAZDEJ instancji backendu).
-- Konsument: JdbcTemplateLockProvider(...).usingDbTime() (BE-171,
-- net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.10.0).
--
-- TYPY CZASOWE -- TIMESTAMP(3) (bez strefy), NIE TIMESTAMPTZ:
--   Z zrodel providera 6.10.0 (PostgresSqlServerTimeStatementsSource) przy usingDbTime() wszystkie
--   porownania i zapisy ida przez `timezone('utc', CURRENT_TIMESTAMP)`, czyli wartosc typu
--   TIMESTAMP WITHOUT TIME ZONE w UTC. Gdyby kolumny byly TIMESTAMPTZ, PostgreSQL
--   zinterpretowalby ten naiwny UTC wg strefy sesji (TimeZone) -- przy sesji innej niz UTC
--   lock_until byloby przesuniete o offset strefy (blokada wygasa za wczesnie/za pozno), a hosty
--   deweloperskie maja Europe/Warsaw (zob. pulapka TIMESTAMPTZ w Flyway/JVM). TIMESTAMP(3) jest
--   zgodny z oficjalnym DDL ShedLock dla PostgreSQL i odporny na strefe sesji; semantyka "zawsze
--   UTC" jest tu kontraktem providera, nie kolumny. Odstepstwo od preferencji TIMESTAMPTZ z opisu
--   DB-090 -- swiadome, patrz wyzej.
--
-- TABELA PLATFORMOWA: bez tenant_id, bez RLS (blokady dotycza jobow platformy, nie danych
--   tenantow). RlsValidationService wykrywa klase TENANT po kolumnie tenant_id NOT NULL, wiec
--   `shedlock` jest z definicji poza walidacja -- nie wymaga wpisu na liscie wyjatkow.
--   Zadna wczesniejsza migracja nie wlacza RLS/FORCE masowo (petle po pg_tables) -- R2 sprawdzone.
--
-- UPRAWNIENIA: REVOKE ALL FROM PUBLIC. ALTER DEFAULT PRIVILEGES (V012) nadaje app_user DML
--   i admin_user ALL automatycznie przy CREATE TABLE (dla roli, ktora uruchamia migracje);
--   jawny GRANT ponizej czyni to niezalezne od tego mechanizmu. Rola `cc_app` nie istnieje w tym
--   repo -- GRANT warunkowy (DO-blok), zadnych rol nie tworzymy.
--
-- Idempotentna (IF NOT EXISTS, REVOKE/GRANT/COMMENT powtarzalne). Pierwszy wolny numer: V149
-- (flyway_schema_history zywej bazy: 148, V148 = ostatni plik na wszystkich galeziach).
-- =============================================================================

SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT shedlock_pkey PRIMARY KEY (name)
);

COMMENT ON TABLE shedlock IS
    'Platformowa (bez tenant_id, bez RLS) tabela blokad ShedLock JDBC (DB-090/BE-171): jeden wiersz na nazwe joba @Scheduled, zapewnia wykonanie na jednej instancji. Czasy w UTC jako TIMESTAMP bez strefy (kontrakt providera przy usingDbTime: timezone(''utc'', CURRENT_TIMESTAMP)). Nie edytowac recznie, wiersze sa nadpisywane przez biblioteke.';
COMMENT ON COLUMN shedlock.name IS 'Nazwa blokady = nazwa joba (@SchedulerLock(name)), max 64 znaki.';
COMMENT ON COLUMN shedlock.lock_until IS 'Blokada wazna do (UTC, naiwny TIMESTAMP).';
COMMENT ON COLUMN shedlock.locked_at IS 'Moment zalozenia blokady (UTC, naiwny TIMESTAMP).';
COMMENT ON COLUMN shedlock.locked_by IS 'Identyfikator wlasciciela blokady (host instancji).';

REVOKE ALL ON TABLE shedlock FROM PUBLIC;

DO $$
DECLARE
    r TEXT;
BEGIN
    FOREACH r IN ARRAY ARRAY['app_user', 'cc_app'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE shedlock TO %I', r);
        END IF;
    END LOOP;
END
$$;
