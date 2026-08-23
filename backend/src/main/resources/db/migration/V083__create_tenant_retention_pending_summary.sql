-- =============================================================================
-- V083__create_tenant_retention_pending_summary.sql
-- DB-047: Tabela-cache "danych oczekujacych na usuniecie" per tenant/kategoria
-- (EPIC-29 Partycjonowanie i retencja danych z obslugi kontaktow). Drugi ticket
-- implementacyjny epiku, zalezny od DB-046 (kategorie spojne z tenant_retention_policy).
--
-- Migracja: Flyway V083
-- Zaleznosci: DB-002 (tabela tenant), DB-046/V082 (tenant_retention_policy - zrodlo
-- listy dopuszczalnych kategorii danych)
-- Odniesienie: TASKS-DATABASE.md:2444-2488
--
-- Zakres: cache wypelniany PRZEZ PRZYSZLY job RetentionEvaluationJob (BE-112,
-- poza zakresem tego ticketu) metoda upsert (INSERT ... ON CONFLICT (tenant_id,
-- data_category) DO UPDATE), zeby dashboard admina (FE-105) czytal gotowy, tani
-- wiersz zamiast liczyc COUNT(*) na zadanie za kazdym razem.
--
-- PK zlozony (tenant_id, data_category) - brak osobnego surogatu, to czysty
-- cache 1:1 z kategoria. PK sam w sobie (jako unikalny b-tree index na tej parze
-- kolumn) wystarcza jako cel dla ON CONFLICT - nie potrzeba dodatkowego UNIQUE
-- constraint (zweryfikowane manualnie ponizej, patrz notatka weryfikacyjna).
--
-- Tabela ZACZYNA PUSTA - w odroznieniu od V082 (DB-046), TU NIE MA backfillu.
-- Semantyka: brak wiersza dla danego (tenant_id, data_category) = "jeszcze nie
-- policzone" (job jeszcze nie przebiegl dla tej pary), NIE "zero rekordow do
-- usuniecia". To rozroznienie jest krytyczne dla BE-112 i FE-105 - patrz
-- COMMENT ON TABLE ponizej.
--
-- GUC RLS: app.current_tenant_id (ustawiane przez set_tenant_context(), V023) -
-- ten sam poprawny wzorzec co V082, nie powtarzamy bledu app.tenant_id z
-- V059/V064/V067/V068.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_retention_pending_summary (
    tenant_id              UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category          VARCHAR(30) NOT NULL CHECK (data_category IN
                            ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    eligible_row_count     BIGINT NOT NULL DEFAULT 0,
    oldest_eligible_period DATE,
    newest_eligible_period DATE,
    computed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, data_category)
);

-- ---------------------------------------------------------------------------
-- 2. RLS - wzorzec identyczny do V082/V077/V076/V075/V070, GUC app.current_tenant_id
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_retention_pending_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_retention_pending_summary FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_retention_pending_summary_isolation ON tenant_retention_pending_summary
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE tenant_retention_pending_summary IS
    'Cache policzony przez RetentionEvaluationJob (BE-112) - dashboard admina (FE-105) czyta ten wiersz zamiast liczyc COUNT(*) na zywo. '
    'WAZNE: brak wiersza dla danego (tenant_id, data_category) oznacza "jeszcze nie policzone" (job jeszcze nie przebiegl dla tej pary), '
    'a NIE "zero rekordow kwalifikujacych sie do usuniecia" - te dwa stany trzeba rozrozniac po stronie BE/FE (LEFT JOIN / brak wiersza != eligible_row_count = 0).';
COMMENT ON COLUMN tenant_retention_pending_summary.eligible_row_count IS
    'Liczba rekordow kwalifikujacych sie do usuniecia wg biezacej tenant_retention_policy w momencie computed_at. Wazne tylko gdy wiersz istnieje - patrz COMMENT ON TABLE.';
COMMENT ON COLUMN tenant_retention_pending_summary.computed_at IS
    'Znacznik czasu ostatniego przebiegu RetentionEvaluationJob dla tej pary (tenant_id, data_category). Upsert: INSERT ... ON CONFLICT (tenant_id, data_category) DO UPDATE.';
