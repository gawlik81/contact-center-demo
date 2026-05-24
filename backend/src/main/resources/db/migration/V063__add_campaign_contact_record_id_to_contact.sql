-- =============================================================================
-- V063__add_campaign_contact_record_id_to_contact.sql
-- DB-037: Powiązanie kontaktu wychodzącego z rekordem listy kampanii.
--
-- Brak FK: campaign_contact ma composite PK (record_id, campaign_id) —
-- analogicznie do callback_id (V040) używamy UUID bez FK constraint.
-- =============================================================================

ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS campaign_contact_record_id UUID;

CREATE INDEX idx_contact_campaign_contact_record
    ON contact (campaign_contact_record_id)
    WHERE campaign_contact_record_id IS NOT NULL;

COMMENT ON COLUMN contact.campaign_contact_record_id IS
    'UUID rekordu campaign_contact (campaign_contact.record_id), z którego powstał ten kontakt. '
    'Nullable — wypełniany tylko dla kontaktów wychodzących z dialera kampanijnego. '
    'Brak FK ze względu na composite PK w campaign_contact (analogicznie do callback_id).';
