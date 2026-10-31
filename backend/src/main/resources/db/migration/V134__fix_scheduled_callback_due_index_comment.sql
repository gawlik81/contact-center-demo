-- =============================================================================
-- V134__fix_scheduled_callback_due_index_comment.sql
-- DB-R2 (code review V126-V133, 2026-10-10): korekta trwalego komentarza
-- COMMENT ON INDEX idx_scheduled_callback_due ustawionego w V129.
--
-- V129 (zastosowana -- NIE edytowac, checksum) opisala indeks jako obslugujacy
-- findDueCallbacks. To nieprawda: ScheduledCallbackRepository#findDueCallbacks
-- NIE filtruje po is_deleted, wiec planista nie moze uzyc indeksu z predykatem
-- is_deleted = false; zapytanie korzysta z idx_callback_scheduled
-- (potwierdza to Db057IndexCleanupMigrationsTest#explainDueCallbacksJavaShape).
--
-- Zakres: WYLACZNIE COMMENT ON INDEX (zadnej zmiany struktury, danych ani
-- blokad poza krotkim ShareUpdateExclusive na metadanych). Idempotentna.
-- Numeracja: nastepny wolny numer po V133 (git ls-tree wszystkich galezi +
-- flyway_schema_history zywej bazy: max zastosowana = 133).
-- =============================================================================

DO $$
BEGIN
    IF to_regclass('public.idx_scheduled_callback_due') IS NULL THEN
        RAISE EXCEPTION
            'V134: brak indeksu idx_scheduled_callback_due (V129) -- nie ma czego komentowac.';
    END IF;
END
$$;

COMMENT ON INDEX idx_scheduled_callback_due
    IS 'Czesciowy indeks (tenant_id, scheduled_at) WHERE status = ''PENDING'' AND '
       'is_deleted = false. Zwyciezca duplikatu z DB-057 / V129 (idx_callback_ready '
       'usuniety); komentarz skorygowany w V134. UWAGA: findDueCallbacks go NIE '
       'uzywa (zapytanie nie filtruje is_deleted, korzysta z idx_callback_scheduled); '
       'indeks obsluguje tylko zapytania z is_deleted = false, ktorych zaden kod '
       'Javy dzis nie wysyla. Kandydat do osobnej oceny (obok idx_callback_scheduled).';
