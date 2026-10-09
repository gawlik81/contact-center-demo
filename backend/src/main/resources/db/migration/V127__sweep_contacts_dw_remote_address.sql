-- =============================================================================
-- V127__sweep_contacts_dw_remote_address.sql
-- DB-078 / M1 (EPIC-30, DESIGN-message-retention-and-partitioning.md SS2 U9, DB-060 F6):
-- jednorazowy sweep PII -- wyzerowanie contacts_dw.remote_address.
--
-- Numeracja: V126 = ostatnia migracja (DB-056) na wszystkich galeziach lokalnych i
--   zdalnych oraz w flyway_schema_history; V127/V128 wolne. V104 zarezerwowane celowo;
--   db/seed/V999 to osobny katalog seedow.
--
-- Dlaczego OSOBNA migracja przed M2 (V128 DROP COLUMN):
--   (1) jedna zmiana = jedna migracja (dane vs DDL); (2) siatka bezpieczenstwa gdy M2
--   nie wchodzi w tym samym wydaniu co BE-141 (rolling deploy, srodowisko z PG-DW
--   zapisujacym jeszcze kolumne) -- PII znika od razu, DROP moze poczekac;
--   (3) UPDATE jest tani i idempotentny (WHERE ... IS NOT NULL), wiec koszt dodatkowego
--   pliku jest zerowy.
--
-- Zakres: tylko dane. Brak DDL, wiec bez lock_timeout (UPDATE bierze ROW EXCLUSIVE).
-- RLS: contacts_dw ma FORCE RLS (V116, GUC app.current_tenant_id). Migracja idzie jako
--   rola z BYPASSRLS (ccapp/superuser). SET LOCAL row_security = off sprawia, ze rola
--   BEZ BYPASSRLS dostanie GLOSNY blad zamiast cichego UPDATE 0 wierszy (brak GUC =
--   polityka nie widzi zadnego wiersza).
-- Idempotencja: gdy kolumny juz nie ma (np. V128 zastosowane na kopii), blok jest no-op.
-- =============================================================================

SET LOCAL row_security = off;

DO $$
DECLARE
    v_swept     BIGINT := 0;
    v_remaining BIGINT := 0;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'contacts_dw' AND column_name = 'remote_address'
    ) THEN
        RAISE NOTICE 'V127: contacts_dw.remote_address nie istnieje -- nic do zrobienia';
        RETURN;
    END IF;

    UPDATE contacts_dw SET remote_address = NULL WHERE remote_address IS NOT NULL;
    GET DIAGNOSTICS v_swept = ROW_COUNT;

    SELECT count(*) INTO v_remaining FROM contacts_dw WHERE remote_address IS NOT NULL;
    IF v_remaining <> 0 THEN
        RAISE EXCEPTION 'V127: po sweepie zostalo % wierszy contacts_dw z remote_address', v_remaining;
    END IF;

    RAISE NOTICE 'V127: wyzerowano contacts_dw.remote_address w % wierszach', v_swept;
END
$$;
