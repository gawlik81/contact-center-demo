-- DB-031: Add caller_id to campaign table
-- Adds an optional caller ID (E.164 format) for outbound calls.
-- NULL means: fall back to the tenant's default number from tenant_twilio_config.phone_number.

ALTER TABLE campaign
    ADD COLUMN IF NOT EXISTS caller_id VARCHAR(30) NULL;

COMMENT ON COLUMN campaign.caller_id IS
    'Numer prezentacji (caller ID) dla połączeń wychodzących w formacie E.164. '
    'NULL = użyj domyślnego numeru tenanta z tenant_twilio_config lub konfiguracji globalnej.';

-- Partial index for fast lookup of campaigns with a custom caller_id.
CREATE INDEX IF NOT EXISTS idx_campaign_caller_id
    ON campaign (tenant_id, caller_id)
    WHERE caller_id IS NOT NULL;
