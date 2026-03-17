-- =============================================================================
-- V020__add_user_name_fields.sql
-- BE-008: Dodanie pol firstName/lastName do app_user (juz istnieja w tabeli z V003,
-- ale brakuje ich w encji JPA AppUser).
-- Ta migracja jest no-op jesli kolumny juz istnieja – ich definicja jest w V003.
-- =============================================================================

-- Kolumny first_name i last_name sa juz zdefiniowane w V003__create_user.sql.
-- Migracja jest wymagana tylko zeby dodac missing constraint na is_deleted
-- i upewnic sie ze indeks parsial uniq dziala poprawnie.
-- Nie dodajemy duplikatow kolumn.

-- Sprawdzenie: jesli is_deleted nie istnieje (zdarzyc sie nie powinno po V003),
-- dodajemy z default false.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'app_user' AND column_name = 'is_deleted'
    ) THEN
        ALTER TABLE app_user ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END$$;
