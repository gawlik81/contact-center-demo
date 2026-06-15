-- =============================================================================
-- V069__create_custom_disposition.sql
-- DB-040: Własne dyspozycje po kontakcie per kampania lub kolejka.
-- Gdy skonfigurowane dla danego zakresu, zastępują dyspozycje systemowe.
-- Zakres: dokładnie jeden z campaign_id / queue_id musi być ustawiony.
-- =============================================================================

CREATE TABLE custom_disposition (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenant(tenant_id)    ON DELETE CASCADE,

    -- Zakres: dokładnie jeden z poniższych musi być NOT NULL
    campaign_id      UUID        REFERENCES campaign(campaign_id)  ON DELETE CASCADE,
    queue_id         UUID        REFERENCES queue(queue_id)        ON DELETE CASCADE,

    -- Definicja dyspozycji
    disposition_code VARCHAR(50) NOT NULL,
    label            VARCHAR(100) NOT NULL,
    tone             VARCHAR(20) NOT NULL DEFAULT 'neutral',
    ordinal          INT         NOT NULL DEFAULT 0,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_custom_disposition_scope CHECK (
        (campaign_id IS NOT NULL AND queue_id IS NULL) OR
        (campaign_id IS NULL     AND queue_id IS NOT NULL)
    ),
    CONSTRAINT chk_custom_disposition_tone CHECK (
        tone IN ('positive', 'negative', 'neutral', 'warning')
    )
);

-- Partial unique indexes — obsługa NULL w UNIQUE dla PostgreSQL
CREATE UNIQUE INDEX uq_custom_disposition_code_per_campaign
    ON custom_disposition (tenant_id, campaign_id, disposition_code)
    WHERE campaign_id IS NOT NULL;

CREATE UNIQUE INDEX uq_custom_disposition_code_per_queue
    ON custom_disposition (tenant_id, queue_id, disposition_code)
    WHERE queue_id IS NOT NULL;

-- Indeksy wyszukiwania
CREATE INDEX idx_custom_disposition_campaign
    ON custom_disposition (tenant_id, campaign_id, ordinal)
    WHERE campaign_id IS NOT NULL AND is_active = TRUE;

CREATE INDEX idx_custom_disposition_queue
    ON custom_disposition (tenant_id, queue_id, ordinal)
    WHERE queue_id IS NOT NULL AND is_active = TRUE;

ALTER TABLE custom_disposition ENABLE ROW LEVEL SECURITY;
CREATE POLICY custom_disposition_isolation ON custom_disposition
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

COMMENT ON TABLE custom_disposition IS
    'Własne dyspozycje po kontakcie dla kampanii lub kolejki. Gdy istnieją dla danego zakresu, zastępują dyspozycje systemowe.';
COMMENT ON COLUMN custom_disposition.disposition_code IS
    'Unikalny kod dyspozycji w obrębie zakresu (kampania lub kolejka). Maks. 50 znaków.';
COMMENT ON COLUMN custom_disposition.tone IS
    'Ton wizualny w UI: positive (zielony), negative (czerwony), neutral (szary), warning (pomarańczowy).';
COMMENT ON COLUMN custom_disposition.ordinal IS
    'Kolejność wyświetlania na liście. Rosnąco, domyślnie 0.';
COMMENT ON COLUMN custom_disposition.campaign_id IS
    'Jeśli ustawiony: dyspozycja należy do tej kampanii. Wzajemnie wyklucza się z queue_id.';
COMMENT ON COLUMN custom_disposition.queue_id IS
    'Jeśli ustawiony: dyspozycja należy do tej kolejki. Wzajemnie wyklucza się z campaign_id.';
