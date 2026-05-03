-- =============================================================================
-- V050__add_preferred_language_to_app_user.sql
-- Dodanie kolumny preferred_language do tabeli app_user
--
-- Migracja: Flyway V050
-- Zależności: V003 (app_user)
--
-- STRATEGIA:
-- 1. Kolumna NOT NULL z domyślną wartością 'pl' – bezpieczne dodanie do istniejących rekordów
-- 2. Brak RLS – dostęp kontrolowany przez logikę aplikacji (każdy użytkownik widzi/edytuje
--    tylko własne preferencje językowe za pośrednictwem warstwy serwisowej)
-- 3. Format: kod języka ISO 639-1 (2 znaki), VARCHAR(10) z zapasem na warianty (np. 'pt-BR')
-- =============================================================================

ALTER TABLE app_user
    ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'pl';

COMMENT ON COLUMN app_user.preferred_language IS 'ISO 639-1 language code for UI locale preference';
