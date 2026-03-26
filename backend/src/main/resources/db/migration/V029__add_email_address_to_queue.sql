-- =============================================================================
-- V029__add_email_address_to_queue.sql
-- Dodanie kolumny email_address do tabeli queue
--
-- Migracja: Flyway V029
-- Zaleznosci: V008 (tabela queue)
-- Cel: Umozliwienie przypisania adresu email do kolejki, tak aby przychodzace
--      wiadomosci email na ten adres byly automatycznie routowane do tej kolejki.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Kolumna email_address
--    NULL = kolejka nie obsluguje emaili (zachowanie domyslne, backward-compatible)
-- ---------------------------------------------------------------------------

ALTER TABLE queue
    ADD COLUMN email_address VARCHAR(255) NULL;

COMMENT ON COLUMN queue.email_address IS
    'Adres email przypisany do kolejki. NULL oznacza brak obslugi emaili. '
    'Unikalny w ramach tenanta (constraint uq_queue_tenant_email_address).';

-- ---------------------------------------------------------------------------
-- 2. UNIQUE constraint na (tenant_id, email_address)
--    PostgreSQL traktuje NULL jako rozne wartosci w indeksach UNIQUE,
--    wiec wiele kolejek moze miec email_address = NULL bez naruszenia constraintu.
-- ---------------------------------------------------------------------------

ALTER TABLE queue
    ADD CONSTRAINT uq_queue_tenant_email_address
        UNIQUE (tenant_id, email_address);

-- ---------------------------------------------------------------------------
-- 3. CHECK constraint: podstawowa walidacja formatu (musi zawierac '@')
--    Szczegolowa walidacja RFC 5322 pozostaje po stronie aplikacji.
--    Constraint jest NIE NULL-safe: NULL przechodzi bez sprawdzenia (IS NOT NULL
--    w warunku powoduje, ze NULL jest pomijany przez CHECK).
-- ---------------------------------------------------------------------------

ALTER TABLE queue
    ADD CONSTRAINT chk_queue_email_address_format
        CHECK (email_address IS NULL OR email_address LIKE '%@%');

-- ---------------------------------------------------------------------------
-- 4. Indeks dla szybkiego lookup po adresie email
--    EmailPollingService szuka kolejki po adresie, do ktorego przyszla wiadomosc.
--    Partial index (WHERE email_address IS NOT NULL) – pomija NULL-owe wiersze,
--    co zmniejsza rozmiar indeksu i przyspiesza wyszukiwanie aktywnych email-queue.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_queue_email_address
    ON queue (tenant_id, email_address)
    WHERE email_address IS NOT NULL;
