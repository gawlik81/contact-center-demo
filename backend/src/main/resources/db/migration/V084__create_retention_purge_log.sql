-- =============================================================================
-- V084__create_retention_purge_log.sql
-- DB-048: Tabela audytu operacji usuwania (purge) - historia manualnych i
-- automatycznych operacji (EPIC-29 Partycjonowanie i retencja danych z obslugi
-- kontaktow). Trzeci ticket implementacyjny epiku, po DB-046/V082 i DB-047/V083.
--
-- Migracja: Flyway V084
-- Zaleznosci: DB-002 (tabela tenant), DB-003 (tabela app_user - triggered_by)
-- Odniesienie: TASKS-DATABASE.md:2490-2539
--
-- Zakres: kazda operacja purge zapisuje TU wiersz w stanie RUNNING, nastepnie
-- aktualizowany do COMPLETED/FAILED (rows_deleted, completed_at, ew. error_message).
-- Odrebna od genericznego audit_log - potrzebne ustrukturyzowane liczby
-- (rows_deleted, status) do UI historii (FE-107), nie tylko tekstowy wpis.
-- Rownolegly zapis do audit_log (entity_type='RETENTION_PURGE') to swiadoma
-- decyzja poza zakresem tej migracji (logika w BE-113).
--
-- PK surogat purge_id UUID - w odroznieniu od V083 (tenant_retention_pending_summary,
-- PK zlozony (tenant_id, data_category) jako cel ON CONFLICT dla cache 1:1), ta
-- tabela ma WIELE wierszy per (tenant, kategoria) w czasie - kazda operacja purge
-- to nowy wiersz, nie upsert.
--
-- triggered_by celowo NULLABLE (FK RESTRICT/domyslne, bez ON DELETE) - auto-purge
-- uruchamiany przez system (scheduled_job/pg_cron) nie ma uzytkownika-sprawcy.
--
-- data_category: CHECK dodany dla spojnosci z tenant_retention_policy (V082) i
-- tenant_retention_pending_summary (V083), mimo ze DDL w tickecie DB-048 go nie
-- zawieral. Decyzja architektoniczna (do weryfikacji przez czlowieka przy review):
-- ta tabela jest scisle sprzezona z tym samym zamknietym zbiorem 4 kategorii (kazdy
-- purge dotyczy dokladnie jednej z nich, egzekwowanej przez logike BE-113 wywolujaca
-- purge per kategoria z tenant_retention_policy) - CHECK lapie literowki/bledy w
-- kodzie aplikacyjnym od razu przy zapisie, zamiast pozwolic im utrwalic sie w
-- danych audytowych bezpowrotnie. Koszt przyszlego dodania nowej kategorii jest
-- i tak identyczny jak dla V082/V083 (ALTER TABLE ... DROP/ADD CONSTRAINT w nowej
-- migracji) - spojnosc nie zwieksza kosztu krancowego. Jesli w przyszlosci pojawi
-- sie potrzeba logowania kategorii spoza tego zbioru (np. kategoria zdenormalizowana
-- lub usunieta z tenant_retention_policy, a audyt ma jej dotyczyc historycznie),
-- CHECK nalezy poluzowac osobna migracja - nie przewidujemy dzis takiego przypadku.
--
-- GUC RLS: app.current_tenant_id (ustawiane przez set_tenant_context(), V023) -
-- ten sam poprawny wzorzec co V082/V083.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela
-- ---------------------------------------------------------------------------

CREATE TABLE retention_purge_log (
    purge_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category  VARCHAR(30) NOT NULL CHECK (data_category IN
                    ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    triggered_by   UUID REFERENCES app_user(user_id),
    trigger_type   VARCHAR(10) NOT NULL CHECK (trigger_type IN ('MANUAL','AUTO')),
    cutoff_date    DATE NOT NULL,
    rows_deleted   BIGINT,
    status         VARCHAR(15) NOT NULL DEFAULT 'RUNNING'
                   CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMPTZ,
    error_message  TEXT
);

CREATE INDEX idx_retention_purge_log_tenant ON retention_purge_log (tenant_id, started_at DESC);

-- ---------------------------------------------------------------------------
-- 2. RLS - wzorzec identyczny do V082/V083, GUC app.current_tenant_id
-- ---------------------------------------------------------------------------

ALTER TABLE retention_purge_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_purge_log FORCE ROW LEVEL SECURITY;
CREATE POLICY retention_purge_log_isolation ON retention_purge_log
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE retention_purge_log IS
    'Audyt historii operacji usuwania (purge) danych - manualnych i automatycznych. Odrebna od genericznego audit_log '
    '(dostarcza ustrukturyzowane rows_deleted/status dla UI historii FE-107). Kazda operacja zapisuje rowniez do audit_log '
    '(entity_type=''RETENTION_PURGE'') dla spojnosci z istniejacym mechanizmem audytu - podwojny zapis to swiadoma decyzja (BE-113), nie duplikacja do usuniecia.';
COMMENT ON COLUMN retention_purge_log.triggered_by IS
    'NULL gdy purge wywolany automatycznie przez system (scheduled_job/pg_cron) - brak uzytkownika-sprawcy. Ustawione na user_id gdy MANUAL.';
COMMENT ON COLUMN retention_purge_log.status IS
    'RUNNING przy insercie, aktualizowane do COMPLETED lub FAILED po zakonczeniu operacji purge (BE-113).';
