-- =============================================================================
-- V079__add_external_id_to_customer.sql
-- Kolumna techniczna external_id na tabeli CUSTOMER
-- =============================================================================

ALTER TABLE customer ADD COLUMN external_id VARCHAR(255);

-- Zewnętrzny identyfikator klienta (np. z systemu CRM) — unikalny w obrębie tenanta.
-- Partial unique index ignoruje NULL i zanonimizowane rekordy (RODO), wzorem
-- uq_user_tenant_email z V003__create_user.sql.
CREATE UNIQUE INDEX uq_customer_tenant_external_id
    ON customer (tenant_id, external_id)
    WHERE external_id IS NOT NULL AND is_deleted = FALSE;
