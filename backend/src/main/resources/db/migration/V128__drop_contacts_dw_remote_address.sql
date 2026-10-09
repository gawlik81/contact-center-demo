-- =============================================================================
-- V128__drop_contacts_dw_remote_address.sql
-- DB-078 / M2 (EPIC-30, DESIGN-message-retention-and-partitioning.md SS2 U9, DB-060 F6):
-- usuniecie kolumny contacts_dw.remote_address (PII bez retencji; numer CLI / e-mail).
--
-- Warunek wstepny: BE-141 wdrozone (PostgresDwWriter#UPSERT_SQL i ContactDwRow nie
--   wymieniaja kolumny; zweryfikowane grepem w backend/, frontend/, voicebot/, dw/).
--   Kolejnosc wdrozenia BE-141 -> DB-078 jest bezpieczna: writer dziala i przed, i po.
-- Zaleznosc: V127 (sweep). Nie edytuje V036/V096/V098/V116.
--
-- Funkcje anonymize_customer (V096, V098) maja guard istnienia kolumny (information_schema,
--   DB-078) -- po DROP licznik contacts_dw = 0 i nic nie rzuca (dowod: test).
--
-- Bezpieczenstwo DDL: DROP COLUMN = ACCESS EXCLUSIVE, ale zmiana tylko w katalogu (bez
--   przepisywania tabeli); SET LOCAL lock_timeout chroni przed kolejka za dlugim
--   zapytaniem. Guard (DO $$) przerywa migracje (ROLLBACK), gdy cos zalezy od kolumny:
--   (a) pg_depend -- widoki/reguly/indeksy/constrainty/defaulty/statystyki na kolumnie;
--   (b) funkcje PL/pgSQL (pg_depend ich nie sledzi) wspominajace contacts_dw i
--       remote_address BEZ guardu istnienia kolumny.
-- Nietkniete: pk_contacts_dw, idx_contacts_dw_*, polityka RLS contacts_dw_tenant_isolation
--   (nie odwoluje sie do kolumny), pozostale kolumny i dane.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

DO $$
DECLARE
    v_attnum   SMALLINT;
    v_deps     TEXT;
    v_funcs    TEXT;
BEGIN
    SELECT a.attnum INTO v_attnum
    FROM pg_attribute a
    WHERE a.attrelid = 'contacts_dw'::regclass
      AND a.attname = 'remote_address' AND NOT a.attisdropped;

    IF v_attnum IS NULL THEN
        RAISE NOTICE 'V128: contacts_dw.remote_address juz nie istnieje -- guard pominiety';
        RETURN;
    END IF;

    -- (a) obiekty zalezne od kolumny
    SELECT string_agg(pg_describe_object(d.classid, d.objid, d.objsubid), '; ') INTO v_deps
    FROM pg_depend d
    WHERE d.refclassid = 'pg_class'::regclass
      AND d.refobjid = 'contacts_dw'::regclass
      AND d.refobjsubid = v_attnum;
    IF v_deps IS NOT NULL THEN
        RAISE EXCEPTION 'V128: contacts_dw.remote_address ma zalezne obiekty: %', v_deps;
    END IF;

    -- (b) funkcje PL/pgSQL czytajace/piszace kolumne bez guardu istnienia
    SELECT string_agg(p.oid::regprocedure::text, '; ') INTO v_funcs
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = current_schema()
      AND p.prokind = 'f'
      AND p.prosrc ILIKE '%contacts_dw%'
      AND p.prosrc ILIKE '%remote_address%'
      AND p.prosrc NOT ILIKE '%v_has_contacts_dw_remote_address%';
    IF v_funcs IS NOT NULL THEN
        RAISE EXCEPTION 'V128: funkcje odwoluja sie do contacts_dw.remote_address bez guardu: %', v_funcs;
    END IF;
END
$$;

ALTER TABLE contacts_dw DROP COLUMN IF EXISTS remote_address;
