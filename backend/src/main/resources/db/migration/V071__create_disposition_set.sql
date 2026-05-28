-- =============================================================================
-- V071__create_disposition_set.sql
-- DB-041: Zestawy dyspozycji wielokrotnego użytku (szablony).
-- Przypisanie zestawu do kampanii/kolejki kopiuje elementy (snapshot).
-- =============================================================================

CREATE TABLE disposition_set (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_disposition_set_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE disposition_set_item (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    set_id           UUID         NOT NULL REFERENCES disposition_set(id) ON DELETE CASCADE,
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    disposition_code VARCHAR(50)  NOT NULL,
    label            VARCHAR(100) NOT NULL,
    tone             VARCHAR(20)  NOT NULL DEFAULT 'neutral',
    ordinal          INT          NOT NULL DEFAULT 0,

    CONSTRAINT uq_disposition_set_item_code UNIQUE (set_id, disposition_code),
    CONSTRAINT chk_disposition_set_item_tone CHECK (
        tone IN ('positive', 'negative', 'neutral', 'warning')
    )
);

-- Indeksy
CREATE INDEX idx_disposition_set_tenant
    ON disposition_set (tenant_id, name);

CREATE INDEX idx_disposition_set_item_set
    ON disposition_set_item (set_id, ordinal);

-- RLS disposition_set
ALTER TABLE disposition_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE disposition_set FORCE ROW LEVEL SECURITY;
CREATE POLICY disposition_set_isolation ON disposition_set
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- RLS disposition_set_item
ALTER TABLE disposition_set_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE disposition_set_item FORCE ROW LEVEL SECURITY;
CREATE POLICY disposition_set_item_isolation ON disposition_set_item
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

COMMENT ON TABLE disposition_set IS
    'Nazwane zestawy dyspozycji wielokrotnego użytku. Przypisanie do kampanii/kolejki kopiuje elementy (snapshot).';
COMMENT ON TABLE disposition_set_item IS
    'Elementy zestawu dyspozycji. Kopiowane do custom_disposition przy przypisaniu zestawu.';
COMMENT ON COLUMN disposition_set_item.disposition_code IS
    'Unikalny kod w obrębie zestawu. Maks. 50 znaków, tylko A-Z, 0-9, _.';
