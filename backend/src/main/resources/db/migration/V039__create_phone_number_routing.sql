-- V039__create_phone_number_routing.sql
-- DB-021: Tabele PHONE_NUMBER i PHONE_ROUTING_RULE
-- Numery telefonów tenanta i harmonogram routingu do IVR/kolejki.
-- Blokuje: BE-033, BE-034

-- ---------------------------------------------------------------------------
-- 1. Tabela phone_number
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS phone_number (
    phone_number_id  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id),
    number           VARCHAR(20)  NOT NULL,
    display_name     VARCHAR(100),
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    is_deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_phone_number_tenant UNIQUE (tenant_id, number),
    CONSTRAINT chk_phone_number_e164  CHECK (number ~ '^\+[1-9][0-9]{6,14}$')
);

CREATE INDEX IF NOT EXISTS idx_phone_number_tenant
    ON phone_number (tenant_id)
    WHERE NOT is_deleted;

ALTER TABLE phone_number ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'phone_number'
          AND policyname = 'phone_number_tenant_isolation'
    ) THEN
        CREATE POLICY phone_number_tenant_isolation ON phone_number
            USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 2. Tabela phone_routing_rule
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS phone_routing_rule (
    rule_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id),
    phone_number_id  UUID         NOT NULL REFERENCES phone_number(phone_number_id),
    ivr_tree_id      UUID         REFERENCES ivr_tree(ivr_id),    -- NULL gdy target = queue
    queue_id         UUID         REFERENCES queue(queue_id),      -- NULL gdy target = IVR
    days_of_week     INTEGER[]    NOT NULL,  -- ISO: 1=Pon … 7=Nie; min 1 element
    time_start       TIME         NOT NULL,
    time_end         TIME         NOT NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT chk_routing_rule_time   CHECK (time_start < time_end),
    CONSTRAINT chk_routing_rule_target CHECK (
        (ivr_tree_id IS NOT NULL AND queue_id IS NULL) OR
        (ivr_tree_id IS NULL     AND queue_id IS NOT NULL)
    ),
    CONSTRAINT chk_routing_rule_days   CHECK (array_length(days_of_week, 1) >= 1)
);

CREATE INDEX IF NOT EXISTS idx_routing_rule_phone
    ON phone_routing_rule (phone_number_id)
    WHERE is_active;

CREATE INDEX IF NOT EXISTS idx_routing_rule_tenant
    ON phone_routing_rule (tenant_id)
    WHERE is_active;

ALTER TABLE phone_routing_rule ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'phone_routing_rule'
          AND policyname = 'phone_routing_rule_tenant_isolation'
    ) THEN
        CREATE POLICY phone_routing_rule_tenant_isolation ON phone_routing_rule
            USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. Trigger wykrywania kolizji reguł
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION check_routing_rule_collision() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM phone_routing_rule
        WHERE phone_number_id = NEW.phone_number_id
          AND rule_id        != COALESCE(NEW.rule_id, '00000000-0000-0000-0000-000000000000'::UUID)
          AND is_active       = TRUE
          AND days_of_week   && NEW.days_of_week
          AND time_start      < NEW.time_end
          AND time_end        > NEW.time_start
    ) THEN
        RAISE EXCEPTION 'routing_rule_collision'
            USING DETAIL = 'Regula naklada sie na istniejaca regule dla tego numeru.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_routing_rule_collision'
    ) THEN
        CREATE CONSTRAINT TRIGGER trg_routing_rule_collision
            AFTER INSERT OR UPDATE ON phone_routing_rule
            DEFERRABLE INITIALLY IMMEDIATE
            FOR EACH ROW EXECUTE FUNCTION check_routing_rule_collision();
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Seed dev: numery i reguły dla tenanta Acme Corporation
--    Tenant: aaaaaaaa-0000-0000-0000-000000000001
--    IVR:    dddddddd-0000-0000-0000-000000000001 (Menu glowne Acme)
--    Queue:  cccccccc-0000-0000-0000-000000000002 (Wsparcie techniczne – after hours)
-- ---------------------------------------------------------------------------

INSERT INTO phone_number (phone_number_id, tenant_id, number, display_name, is_active)
VALUES
(
    'eeeeeeee-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    '+48221234567',
    'Linia główna Acme',
    TRUE
),
(
    'eeeeeeee-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    '+48221234568',
    'Linia sprzedaży Acme',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Reguła 1: pon-pt 08:00-17:00 → IVR Menu główne
INSERT INTO phone_routing_rule (rule_id, tenant_id, phone_number_id, ivr_tree_id, queue_id, days_of_week, time_start, time_end, is_active)
VALUES
(
    'ffffffff-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'eeeeeeee-0000-0000-0000-000000000001',
    'dddddddd-0000-0000-0000-000000000001',  -- IVR Menu glowne
    NULL,
    ARRAY[1,2,3,4,5],  -- pon-pt
    '08:00',
    '17:00',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Reguła 2: pon-pt 17:00-20:00 → kolejka Wsparcie techniczne (after hours)
INSERT INTO phone_routing_rule (rule_id, tenant_id, phone_number_id, ivr_tree_id, queue_id, days_of_week, time_start, time_end, is_active)
VALUES
(
    'ffffffff-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'eeeeeeee-0000-0000-0000-000000000001',
    NULL,
    'cccccccc-0000-0000-0000-000000000002',  -- Wsparcie techniczne
    ARRAY[1,2,3,4,5],  -- pon-pt
    '17:00',
    '20:00',
    TRUE
)
ON CONFLICT DO NOTHING;
