-- =============================================================================
-- V037__scheduled_callback_source_context.sql
-- DB-023: Rozszerzenie tabeli scheduled_callback o kontekst zrodlowy
--
-- Migracja: Flyway V037
-- Zaleznosci: V032 (scheduled_callback z is_deleted i RLS)
-- Epic: EPIC-13 Zaplanowane oddzwonienia
--
-- Dodaje:
--   1. Kolumne source_type: rozroznienie zrodla callbacku (kampania vs rozmowa przychodz.)
--   2. Kolumne origin_contact_id: powiazanie z kontaktem zrodlowym
--   3. Indeksy dla nowych przypadkow uzycia
-- =============================================================================

-- =============================================================================
-- 1. Kolumna source_type
-- =============================================================================

ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30) NOT NULL DEFAULT 'CAMPAIGN_CALLBACK';

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_source_type
        CHECK (source_type IN ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK'));

COMMENT ON COLUMN scheduled_callback.source_type
    IS 'Zrodlo callbacku: CAMPAIGN_CALLBACK (dyspozycja po kampanii) lub INBOUND_CALLBACK (zaplanowane przez agenta podczas rozmowy przychodz.).';

-- =============================================================================
-- 2. Kolumna origin_contact_id (plain UUID — bez FK)
-- =============================================================================
-- UWAGA: tabela contact ma composite PK (contact_id, started_at) — partycjonowanie.
-- PostgreSQL nie pozwala na FK do kolumny bez samodzielnego UNIQUE constraintu.
-- Integralnosc zapewniana przez warstwe aplikacji (ContactRepository.findById przed zapisem).

ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS origin_contact_id UUID;

COMMENT ON COLUMN scheduled_callback.origin_contact_id
    IS 'UUID kontaktu zrodlowego (INBOUND_CALLBACK). Brak FK — contact ma composite PK. NULL dla callbackow kampanijnych.';

-- =============================================================================
-- 3. Indeksy
-- =============================================================================

-- Widok supervisora: callbacki z rozmow przychodzacych
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_inbound
    ON scheduled_callback (tenant_id, source_type, scheduled_at)
    WHERE source_type = 'INBOUND_CALLBACK' AND status = 'PENDING' AND is_deleted = FALSE;

-- Lookup: wszystkie callbacki z danego kontaktu
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_origin_contact
    ON scheduled_callback (tenant_id, origin_contact_id)
    WHERE origin_contact_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_inbound
    IS 'DB-023: Callbacki z rozmow przychodzacych (INBOUND_CALLBACK, PENDING, nie usuniete).';

COMMENT ON INDEX idx_scheduled_callback_origin_contact
    IS 'DB-023: Lookup callbackow per kontakt zrodlowy (origin_contact_id).';
