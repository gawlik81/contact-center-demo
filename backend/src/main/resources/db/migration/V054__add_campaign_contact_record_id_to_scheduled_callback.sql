ALTER TABLE scheduled_callback
    ADD COLUMN campaign_contact_record_id UUID;

COMMENT ON COLUMN scheduled_callback.campaign_contact_record_id IS
    'UUID rekordu campaign_contact (campaign_contact.record_id) powiązanego z tym callbackiem. '
    'NULL dla INBOUND_CALLBACK i AGENT_MANUAL. '
    'Brak FK – campaign_contact ma composite PK (record_id, campaign_id).';

CREATE INDEX idx_scheduled_callback_cc_record
    ON scheduled_callback (campaign_contact_record_id)
    WHERE campaign_contact_record_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_cc_record IS
    'DB-033: Lookup callbacków kampanijnych po record_id rekordu campaign_contact.';
