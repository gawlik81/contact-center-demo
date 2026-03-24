-- =============================================================================
-- V027__campaign_contact_phone_unique_index.sql
-- BE-023: Import CSV kontaktów kampanii – indeks unikalny dla deduplikacji
--
-- Dodaje unikalny indeks na (campaign_id, phone) w tabeli campaign_contact,
-- wymagany przez ON CONFLICT (campaign_id, phone) w batch INSERT.
--
-- UWAGA: Indeks jest partial (WHERE phone IS NOT NULL) – rekordy bez numeru
-- telefonu nie podlegają deduplikacji i mogą być dodawane wielokrotnie.
--
-- Migracja: Flyway V027
-- Zależności: V009 (campaign_contact)
-- =============================================================================

-- Unikalny indeks na (campaign_id, phone) – partial dla rekordów z telefonem.
-- Wymagany przez ON CONFLICT w CampaignContactRepository.batchInsert().
CREATE UNIQUE INDEX idx_campaign_contact_phone_unique
    ON campaign_contact (campaign_id, phone)
    WHERE phone IS NOT NULL;

COMMENT ON INDEX idx_campaign_contact_phone_unique
    IS 'Deduplikacja kontaktów kampanii po numerze telefonu (BE-023). '
       'Partial index – dotyczy tylko rekordów z niepustym phone.';
