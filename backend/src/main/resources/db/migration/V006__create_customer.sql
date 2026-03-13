-- =============================================================================
-- V006__create_customer.sql
-- DB-006 (czesciowo): Tabela CUSTOMER – profil klienta, fuzzy search, RODO
--
-- Migracja: Flyway V006 (numeracja zgodna z TASKS-DATABASE zaleznosciami;
--           plik DB-006 w zadaniach obejmuje CONTACT + RECORDING,
--           jednak CUSTOMER musi istniesc PRZED CONTACT – stworzone tutaj)
--
-- Zaleznosci: V002 (tenant)
-- Odniesienie PRD: US-09-01 do US-09-06, EPIC-09, RODO Art. 17, Art. 20
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela CUSTOMER
-- ---------------------------------------------------------------------------

CREATE TABLE customer (
    customer_id     UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID            NOT NULL,

    -- Dane osobowe (PII) – podlegaja RODO (anonimizacja przez fn w DB-017)
    first_name      VARCHAR(100),
    last_name       VARCHAR(100),

    -- Wielokrotne numery telefonu i emaile jako tablice JSONB
    -- Przykladowa wartosc phone: ["+48501234567", "+48601234567"]
    -- Przykladowa wartosc email: ["jan@example.com", "jan.kowalski@work.com"]
    phone           JSONB           NOT NULL DEFAULT '[]',
    email           JSONB           NOT NULL DEFAULT '[]',

    -- Dowolne pola niestandardowe konfigurowane przez supervisora
    -- Przyklad: {"account_number": "123456", "vip_tier": "gold"}
    custom_fields   JSONB           NOT NULL DEFAULT '{}',

    -- Zarzadzanie zgoda RODO (NFR-RODO06 – weryfikacja przed outbound)
    -- Struktura: {
    --   "consent_given": true,
    --   "consent_date": "2026-01-15T10:00:00Z",
    --   "consent_source": "WEB_FORM",
    --   "marketing_consent": true,
    --   "data_processing_consent": true
    -- }
    gdpr_consent    JSONB           NOT NULL DEFAULT '{"consent_given":false}',

    -- Zrodlo danych klienta
    source          VARCHAR(50)     NOT NULL DEFAULT 'MANUAL',
    -- Mozliwe wartosci: MANUAL, CSV_IMPORT, INBOUND_PHONE, INBOUND_EMAIL,
    --                   INBOUND_SOCIAL, API

    -- Soft delete (RODO: zachowanie anonimizowanych statystyk po "usnieciu")
    -- Prawdziwa anonimizacja przez funkcje anonymize_customer() (DB-017)
    is_deleted      BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,

    CONSTRAINT pk_customer PRIMARY KEY (customer_id),

    CONSTRAINT fk_customer_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    -- Walidacja typow JSON
    CONSTRAINT chk_customer_phone_is_array  CHECK (jsonb_typeof(phone)  = 'array'),
    CONSTRAINT chk_customer_email_is_array  CHECK (jsonb_typeof(email)  = 'array'),
    CONSTRAINT chk_customer_fields_is_obj   CHECK (jsonb_typeof(custom_fields) = 'object'),
    CONSTRAINT chk_customer_gdpr_is_obj     CHECK (jsonb_typeof(gdpr_consent)  = 'object')
);

-- ---------------------------------------------------------------------------
-- 2. Indeksy
-- ---------------------------------------------------------------------------

-- Indeks trigram dla fuzzy search po pelnym imieniu i nazwisku (US-09-01)
-- Operator % (similarity) wymaga tego indeksu
-- Konkatencja first_name || ' ' || last_name umozliwia wyszukiwanie "Jan Kowalski"
CREATE INDEX idx_customer_name_trgm
    ON customer
    USING GIN ((COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')) gin_trgm_ops)
    WHERE is_deleted = FALSE;

-- Indeks GIN na phone – wyszukiwanie klienta po numerze telefonu
-- Przyklad: WHERE phone @> '"+48501234567"'
-- Uzywane przez CLI lookup przy przychodzacym polaczeniu (NFR-P03: < 1 s)
CREATE INDEX idx_customer_phone_gin
    ON customer
    USING GIN (phone jsonb_path_ops)
    WHERE is_deleted = FALSE;

-- Indeks GIN na email – wyszukiwanie klienta po adresie email
CREATE INDEX idx_customer_email_gin
    ON customer
    USING GIN (email jsonb_path_ops)
    WHERE is_deleted = FALSE;

-- Indeks dla listy klientow tenanta (paginacja, sortowanie)
CREATE INDEX idx_customer_tenant_created
    ON customer (tenant_id, created_at DESC)
    WHERE is_deleted = FALSE;

-- Indeks dla zrodel danych (statystyki importu, filtrowanie)
CREATE INDEX idx_customer_tenant_source
    ON customer (tenant_id, source)
    WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- 3. Trigger: updated_at
-- ---------------------------------------------------------------------------

CREATE TRIGGER trg_customer_updated_at
    BEFORE UPDATE ON customer
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- ---------------------------------------------------------------------------
-- 4. Funkcja fuzzy search (US-09-01 – wyniki < 1 s)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION search_customers(
    p_tenant_id  UUID,
    p_query      TEXT,
    p_limit      INT  DEFAULT 20
) RETURNS SETOF customer
LANGUAGE sql
STABLE
AS $$
    SELECT *
    FROM   customer
    WHERE  tenant_id   = p_tenant_id
      AND  is_deleted  = FALSE
      AND  (
              -- Trigram similarity na pelnym imieniu i nazwisku
              (COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')) % p_query
              -- Wyszukiwanie po numerze telefonu (exact match w tablicy)
              OR phone  @> to_jsonb(p_query)
              -- Wyszukiwanie po emailu (exact match w tablicy)
              OR email  @> to_jsonb(p_query)
           )
    ORDER BY
           -- Sortuj od najlepszego dopasowania
           similarity(
               (COALESCE(first_name,'') || ' ' || COALESCE(last_name,'')),
               p_query
           ) DESC
    LIMIT  p_limit;
$$;

COMMENT ON FUNCTION search_customers(UUID, TEXT, INT) IS
    'Wyszukiwanie klientow po imieniu/nazwisku (trigram fuzzy), numerze telefonu lub emailu. '
    'Wymaga indeksow idx_customer_name_trgm, idx_customer_phone_gin, idx_customer_email_gin. '
    'Obsluguje US-09-01 (wyniki < 1 s, p95).';

-- ---------------------------------------------------------------------------
-- 5. Komentarze
-- ---------------------------------------------------------------------------

COMMENT ON TABLE  customer                  IS 'Profil klienta konczowego (nie uzytkownik systemu). Dane PII podlegaja RODO. Anonimizacja przez anonymize_customer() (DB-017).';
COMMENT ON COLUMN customer.customer_id      IS 'UUID klienta. Uzywany w CONTACT, CAMPAIGN_CONTACT jako FK.';
COMMENT ON COLUMN customer.tenant_id        IS 'Izolacja multi-tenant. Klient nalezy do jednego tenanta.';
COMMENT ON COLUMN customer.phone            IS 'Tablica numerow telefonu klienta (JSONB). Format miedzynarodowy +48...';
COMMENT ON COLUMN customer.email            IS 'Tablica adresow email klienta (JSONB).';
COMMENT ON COLUMN customer.custom_fields    IS 'Dowolne pola niestandardowe. Schemat definiowany przez supervisora w konfiguracji tenanta.';
COMMENT ON COLUMN customer.gdpr_consent     IS 'Zarzadzanie zgoda RODO. Pole consent_given musi byc TRUE przed inicjacja kontaktu outbound marketingowego (NFR-RODO06).';
COMMENT ON COLUMN customer.source           IS 'Zrodlo utworzenia profilu: MANUAL, CSV_IMPORT, INBOUND_PHONE, INBOUND_EMAIL, INBOUND_SOCIAL, API.';
COMMENT ON COLUMN customer.is_deleted       IS 'Soft delete. Po anonimizacji RODO: TRUE. Dane zastapione wartoscia ANONYMIZED.';
