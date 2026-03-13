-- =============================================================================
-- V003__create_user.sql
-- DB-003: Tabela USER (app_user), role, statusy, tabela REFRESH_TOKEN
--
-- Migracja: Flyway V003
-- Zaleznosci: V002 (tenant)
-- Odniesienie PRD: US-02-01, US-02-02, US-02-03, EPIC-02
--
-- UWAGA: Uzywamy nazwy tabeli "app_user" poniewaz "user" jest slowem
--        zarezerwowanym w PostgreSQL.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE user_role AS ENUM ('ADMIN', 'SUPERVISOR', 'AGENT');

COMMENT ON TYPE user_role IS
    'Rola uzytkownika w systemie. '
    'ADMIN – globalny administrator platformy (wszystkie tenanty); '
    'SUPERVISOR – menedzer w ramach jednego tenanta; '
    'AGENT – wykonawca obslugi klienta.';

CREATE TYPE user_status AS ENUM (
    'ACTIVE',           -- Konto aktywne, uzytkownik moze sie zalogowac
    'INACTIVE',         -- Konto dezaktywowane przez supervisora/admina
    'AVAILABLE',        -- Agent dostepny do przyjecia kontaktu
    'BUSY',             -- Agent aktualnie obsluguje kontakt
    'BREAK',            -- Agent na przerwie
    'AFTER_CONTACT'     -- Agent w czasie po-kontaktowym (wrap-up)
);

COMMENT ON TYPE user_status IS
    'Polaczony status konta i dostepnosci agenta. '
    'ACTIVE/INACTIVE – status konta (logowanie). '
    'AVAILABLE/BUSY/BREAK/AFTER_CONTACT – status dostepnosci agenta (routing).';

-- ---------------------------------------------------------------------------
-- 2. Tabela APP_USER
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    user_id                  UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id                UUID            NOT NULL,
    role                     user_role       NOT NULL,
    email                    VARCHAR(255)    NOT NULL,

    -- Haslo przechowywane jako hash bcrypt (zawsze 60 znakow dla bcrypt)
    -- Nigdy nie przechowujemy plaintext hasla
    password_hash            VARCHAR(60)     NOT NULL,

    first_name               VARCHAR(100),
    last_name                VARCHAR(100),

    -- Lista skills agenta jako tablica stringow JSONB, np. ["SALES","TECH_SUPPORT","BILLING"]
    -- Uzywana przez silnik routingu (DB-010) do dopasowania agent <-> kolejka
    skills                   JSONB           NOT NULL DEFAULT '[]',

    status                   user_status     NOT NULL DEFAULT 'ACTIVE',

    -- MFA (Multi-Factor Authentication) – wymagane dla ADMIN i SUPERVISOR (NFR-SEC04)
    -- Przechowuje base32-encoded TOTP secret (RFC 6238)
    mfa_secret               VARCHAR(32),
    mfa_enabled              BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Wymuszony reset hasla (np. po pierwszym logowaniu lub decyzji admina)
    password_reset_required  BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Ostatnie logowanie – uzywane do audytu i bezpieczenstwa
    last_login_at            TIMESTAMPTZ,

    is_deleted               BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ,

    CONSTRAINT pk_app_user PRIMARY KEY (user_id),

    CONSTRAINT fk_user_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,  -- Nie mozna usunac tenanta z uzytkownikami

    -- Walidacja: skills musi byc tablica JSON
    CONSTRAINT chk_user_skills_is_array CHECK (jsonb_typeof(skills) = 'array'),

    -- ADMIN nie jest przypisany do tenanta w sensie biznesowym,
    -- ale rejestrowany jest w specjalnym "admin tenant" lub z wyroznieniem przez role
    -- Supervisor i Agent MUSZĄ miec tenant_id (egzekwowane przez aplikacje)

    -- Email musi miec format z @
    CONSTRAINT chk_user_email_format CHECK (email ~* '^[^@]+@[^@]+\.[^@]+$')
);

-- ---------------------------------------------------------------------------
-- 3. Indeksy
-- ---------------------------------------------------------------------------

-- Podstawowy klucz dostepu: logowanie uzytkownika po emailu w ramach tenanta
-- UNIQUE gwarantuje jeden email per tenant (agenci roznych tenantow moga miec ten sam email)
CREATE UNIQUE INDEX uq_user_tenant_email
    ON app_user (tenant_id, email)
    WHERE is_deleted = FALSE;

-- Routing: pobieranie agentow dostepnych w danym tenancie (US-07-01)
-- Krytyczny indeks dla silnika routingu – zapytanie: WHERE tenant_id = ? AND status = 'AVAILABLE'
CREATE INDEX idx_user_tenant_status
    ON app_user (tenant_id, status)
    WHERE is_deleted = FALSE;

-- Indeks GIN na skills dla zapytan skill-matching (operator @> lub ?|)
-- Przyklad: WHERE skills @> '["SALES"]'
CREATE INDEX idx_user_skills_gin
    ON app_user USING GIN (skills);

-- Filtrowanie po roli w ramach tenanta (np. lista agentow dla supervisora)
CREATE INDEX idx_user_tenant_role
    ON app_user (tenant_id, role)
    WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- 4. Trigger: updated_at
-- ---------------------------------------------------------------------------

CREATE TRIGGER trg_app_user_updated_at
    BEFORE UPDATE ON app_user
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 5. Tabela REFRESH_TOKEN
-- ---------------------------------------------------------------------------

CREATE TABLE refresh_token (
    token_id    UUID            NOT NULL DEFAULT uuid_generate_v4(),
    user_id     UUID            NOT NULL,
    tenant_id   UUID            NOT NULL,

    -- Hash tokenu (SHA-256 w hex) – nigdy nie przechowujemy raw tokenu
    token_hash  VARCHAR(64)     NOT NULL,

    -- Czas wygasniecia (ustawiany przez aplikacje zgodnie z konfiguracją JWT)
    expires_at  TIMESTAMPTZ     NOT NULL,

    -- Odwołanie tokenu (logout, wymuszony reset, podejrzana aktywnosc)
    is_revoked  BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Device/session fingerprint – do audytu bezpieczenstwa
    user_agent  TEXT,
    ip_address  INET,

    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_refresh_token PRIMARY KEY (token_id),

    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id)
        REFERENCES app_user (user_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_refresh_token_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE CASCADE
);

-- Lookup po hashu tokenu (podstawowa operacja przy refresh)
CREATE UNIQUE INDEX uq_refresh_token_hash
    ON refresh_token (token_hash);

-- Czyszczenie wygaslych/odwolanych tokenow (pg_cron w DB-018)
-- Predykat WHERE z NOW() jest niedozwolony w indeksach (STABLE, nie IMMUTABLE).
-- Zwykly indeks kompozytowy wystarczy do efektywnego skanowania przez pg_cron.
CREATE INDEX idx_refresh_token_cleanup
    ON refresh_token (expires_at, is_revoked);

-- Tokeny konkretnego uzytkownika (logout all sessions)
CREATE INDEX idx_refresh_token_user_id
    ON refresh_token (user_id);

-- ---------------------------------------------------------------------------
-- 6. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON TABLE  app_user                          IS 'Uzytkownicy systemu: Administratorzy, Supervisorzy i Agenci. Tabela nazwana app_user (user jest slowem zarezerwowanym PostgreSQL).';
COMMENT ON COLUMN app_user.user_id                  IS 'Klucz glowny UUID.';
COMMENT ON COLUMN app_user.tenant_id                IS 'FK do tenant. Dla roli ADMIN – specjalny system-tenant lub NULL (egzekwowane przez aplikacje).';
COMMENT ON COLUMN app_user.role                     IS 'Rola biznesowa: ADMIN (platforma), SUPERVISOR (tenant), AGENT (tenant).';
COMMENT ON COLUMN app_user.email                    IS 'Email logowania. Unikalny w ramach tenanta (nie globalnie).';
COMMENT ON COLUMN app_user.password_hash            IS 'Hash bcrypt (60 znakow, min. 12 rund zgodnie z NFR-SEC02). Nigdy plaintext.';
COMMENT ON COLUMN app_user.skills                   IS 'Lista umiejetnosci agenta jako JSON array string. Uzywana przez routing engine.';
COMMENT ON COLUMN app_user.status                   IS 'Status konta (ACTIVE/INACTIVE) i dostepnosc agenta (AVAILABLE/BUSY/BREAK/AFTER_CONTACT).';
COMMENT ON COLUMN app_user.mfa_secret               IS 'TOTP secret w formacie base32 (RFC 6238). NULL jesli MFA nie skonfigurowane.';
COMMENT ON COLUMN app_user.mfa_enabled              IS 'Czy MFA jest aktywne. Wymagane dla ADMIN i SUPERVISOR (NFR-SEC04).';
COMMENT ON COLUMN app_user.password_reset_required  IS 'Flaga wymuszajaca zmiane hasla przy nastepnym logowaniu.';
COMMENT ON COLUMN app_user.is_deleted               IS 'Soft delete – konto usuniete logicznie. Uzytkownik nie moze sie zalogowac.';

COMMENT ON TABLE  refresh_token                     IS 'Tokeny odswieza JWT. Przechowywane jako hashe SHA-256. Wygasle/odwolane czyszczone przez cron (DB-018).';
COMMENT ON COLUMN refresh_token.token_hash          IS 'SHA-256 hex hash raw refresh tokenu. Umozliwia lookup bez ujawniania wartosci tokenu.';
COMMENT ON COLUMN refresh_token.is_revoked          IS 'TRUE po wylogowaniu lub wymuszonym uniewazneniu sesji (np. zmiana hasla).';
