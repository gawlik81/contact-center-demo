-- V028: Zmiana contact_id w email_message i social_message na nullable
--
-- Uzasadnienie: przychodzące wiadomości email/social są zapisywane przez polling IMAP
-- zanim zostaną przypisane do kontaktu przez EmailRoutingService. Kolumna contact_id
-- musi dopuszczać NULL w momencie pierwszego zapisu, a routing wypełnia ją asynchronicznie.

ALTER TABLE email_message
    ALTER COLUMN contact_id DROP NOT NULL;

ALTER TABLE social_message
    ALTER COLUMN contact_id DROP NOT NULL;
