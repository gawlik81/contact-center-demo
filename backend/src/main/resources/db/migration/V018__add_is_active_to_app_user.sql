-- =============================================================================
-- V018__add_is_active_to_app_user.sql
-- BE-003: Dostosowanie schematu do implementacji aplikacyjnej
--
-- Migracja: Flyway V018
-- Zależności: V003 (app_user, refresh_token)
--
-- Kontekst:
--   BE-003 (AuthService, UserDetailsServiceImpl) używa pola is_active (boolean)
--   do sprawdzania czy konto jest aktywne. Oryginalny schemat V003 używał tylko
--   kolumny status (ENUM) i is_deleted (boolean) do tego celu.
--   Ta migracja dodaje dedykowaną kolumnę is_active dla uproszczenia logiki
--   autentykacji w warstwie Java.
--
-- Semantyka:
--   is_active = TRUE  → konto aktywne, użytkownik może się zalogować
--   is_active = FALSE → konto zablokowane (nie to samo co is_deleted)
--
-- Wartość domyślna:
--   Istniejące konta z status != 'INACTIVE' i is_deleted = FALSE → is_active = TRUE
--   Konta z status = 'INACTIVE' lub is_deleted = TRUE → is_active = FALSE
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Dodaj kolumnę is_active do tabeli app_user
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- ---------------------------------------------------------------------------
-- 2. Ustaw wartość is_active na podstawie istniejących danych
--    (konta INACTIVE lub soft-deleted traktujemy jako nieaktywne)
-- ---------------------------------------------------------------------------

UPDATE app_user
SET is_active = FALSE
WHERE status = 'INACTIVE'
   OR is_deleted = TRUE;

-- ---------------------------------------------------------------------------
-- 3. Indeks dla zapytania findByTenantIdAndEmailAndActiveTrue
--    (używanego przez UserDetailsServiceImpl)
-- ---------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_app_user_tenant_email_active
    ON app_user (tenant_id, email)
    WHERE is_active = TRUE AND is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- 4. Komentarz
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN app_user.is_active IS
    'Czy konto jest aktywne i może się zalogować. '
    'FALSE = konto zablokowane przez admina. '
    'Używane przez Spring Security UserDetailsService (BE-003).';
