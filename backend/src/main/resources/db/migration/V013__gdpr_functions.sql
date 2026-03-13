-- =============================================================================
-- V013__gdpr_functions.sql
-- DB-017: Procedury RODO – anonimizacja i eksport danych klienta
--
-- Migracja: Flyway V013
-- Zaleznosci: V006 (customer), V007 (contact), V010 (email/social)
-- Odniesienie PRD: US-09-06, NFR-RODO01, NFR-RODO02, RODO Art. 17, Art. 20
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Funkcja: anonymize_customer (RODO Art. 17 – prawo do bycia zapomnianym)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION anonymize_customer(
    p_customer_id  UUID,
    p_tenant_id    UUID,
    p_user_id      UUID  DEFAULT NULL  -- Uzytkownik inicjujacy anonimizacje (dla audit log)
) RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_old_value JSONB;
BEGIN
    -- Weryfikacja: klient musi nalezec do podanego tenanta (zabezpieczenie cross-tenant)
    IF NOT EXISTS (
        SELECT 1 FROM customer
        WHERE  customer_id = p_customer_id
          AND  tenant_id   = p_tenant_id
    ) THEN
        RAISE EXCEPTION 'Klient % nie istnieje w tenancie % lub brak uprawnien.',
            p_customer_id, p_tenant_id;
    END IF;

    -- Zachowaj stary stan do audit log (bez PII – tylko metadane)
    SELECT jsonb_build_object(
        'customer_id',  customer_id,
        'tenant_id',    tenant_id,
        'had_phone',    (jsonb_array_length(phone) > 0),
        'had_email',    (jsonb_array_length(email) > 0),
        'source',       source,
        'created_at',   created_at
    )
    INTO v_old_value
    FROM customer
    WHERE customer_id = p_customer_id;

    -- Anonimizacja danych osobowych (PII) – calkowita transakcja
    UPDATE customer
    SET
        first_name      = 'ANONYMIZED',
        last_name       = 'ANONYMIZED',
        phone           = '[]',
        email           = '[]',
        custom_fields   = '{}',
        gdpr_consent    = '{"consent_given": false, "anonymized": true}',
        is_deleted      = TRUE,
        updated_at      = NOW()
    WHERE customer_id = p_customer_id
      AND tenant_id   = p_tenant_id;

    -- Usuniecie/anonimizacja PII z EMAIL_MESSAGE (zachowanie watkow bez PII)
    UPDATE email_message
    SET
        from_address = 'anonymized@example.com',
        to_address   = 'anonymized@example.com',
        subject      = '[ANONYMIZED]',
        body_html    = NULL,
        body_text    = '[Dane osobowe usuniete zgodnie z RODO Art. 17]',
        attachments  = '[]'
    WHERE tenant_id  = p_tenant_id
      AND contact_id IN (
          SELECT contact_id
          FROM   contact
          WHERE  customer_id = p_customer_id
            AND  tenant_id   = p_tenant_id
      );

    -- CONTACT: wyzerowanie remote_address (moze zawierac numer telefonu)
    -- UWAGA: rekordy CONTACT sa zachowywane (statystyki) – tylko PII usuniete
    UPDATE contact
    SET
        remote_address   = NULL,
        channel_metadata = '{}',
        updated_at       = NOW()
    WHERE customer_id = p_customer_id
      AND tenant_id   = p_tenant_id;

    -- Usuniecie wiadomosci social media z trescia (PII w tresci konwersacji)
    UPDATE social_message
    SET
        content             = '[Dane osobowe usuniete zgodnie z RODO Art. 17]',
        attachments         = '[]',
        sender_external_id  = 'ANONYMIZED'
    WHERE tenant_id  = p_tenant_id
      AND contact_id IN (
          SELECT contact_id
          FROM   contact
          WHERE  customer_id = p_customer_id
            AND  tenant_id   = p_tenant_id
      );

    -- Audit log: rejestracja zdarzenia anonimizacji (wymagane przez RODO Art. 30)
    INSERT INTO audit_log (
        tenant_id, user_id, action, entity_type, entity_id,
        old_value, new_value, created_at
    ) VALUES (
        p_tenant_id,
        p_user_id,
        'CUSTOMER_ANONYMIZED',
        'CUSTOMER',
        p_customer_id,
        v_old_value,
        jsonb_build_object(
            'customer_id', p_customer_id,
            'anonymized_at', NOW(),
            'legal_basis', 'GDPR_ART_17'
        ),
        NOW()
    );

EXCEPTION WHEN OTHERS THEN
    -- Wycofanie transakcji przy bledzie
    RAISE EXCEPTION 'Blad anonimizacji klienta %: %', p_customer_id, SQLERRM;
END;
$$;

COMMENT ON FUNCTION anonymize_customer(UUID, UUID, UUID) IS
    'Anonimizacja danych osobowych klienta zgodnie z RODO Art. 17 (prawo do bycia zapomnianym). '
    'Dziala transakcyjnie – pelny ROLLBACK przy bledzie. '
    'Zachowuje rekordy CONTACT ze statystykami (bez PII). '
    'Rejestruje zdarzenie w audit_log.';

-- ---------------------------------------------------------------------------
-- 2. Funkcja: export_customer_data (RODO Art. 20 – przenosnosc danych)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION export_customer_data(
    p_customer_id  UUID,
    p_tenant_id    UUID
) RETURNS JSONB
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_customer      JSONB;
    v_contacts      JSONB;
    v_emails        JSONB;
    v_social_msgs   JSONB;
    v_result        JSONB;
BEGIN
    -- Weryfikacja tenant (zabezpieczenie cross-tenant)
    IF NOT EXISTS (
        SELECT 1 FROM customer
        WHERE  customer_id = p_customer_id
          AND  tenant_id   = p_tenant_id
          AND  is_deleted  = FALSE
    ) THEN
        RAISE EXCEPTION 'Klient % nie istnieje lub zostal zanonimizowany.', p_customer_id;
    END IF;

    -- 1. Dane profilu klienta
    SELECT jsonb_build_object(
        'customer_id',  customer_id,
        'first_name',   first_name,
        'last_name',    last_name,
        'phone',        phone,
        'email',        email,
        'custom_fields', custom_fields,
        'gdpr_consent', gdpr_consent,
        'source',       source,
        'created_at',   created_at
    )
    INTO v_customer
    FROM customer
    WHERE customer_id = p_customer_id
      AND tenant_id   = p_tenant_id;

    -- 2. Historia kontaktow (bez PII innych stron)
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'contact_id',       contact_id,
                'channel',          channel,
                'direction',        direction,
                'status',           status,
                'started_at',       started_at,
                'ended_at',         ended_at,
                'duration_seconds', duration_seconds,
                'disposition_code', disposition_code
            )
            ORDER BY started_at DESC
        ),
        '[]'
    )
    INTO v_contacts
    FROM contact
    WHERE customer_id = p_customer_id
      AND tenant_id   = p_tenant_id;

    -- 3. Historia emaili (ograniczona – bez BCC, bez pelnej tresci dla prywatnosci)
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'message_id',       message_id,
                'direction',        direction,
                'from_address',     from_address,
                'subject',          subject,
                'received_at',      received_at,
                'sent_at',          sent_at
            )
            ORDER BY COALESCE(received_at, sent_at) DESC
        ),
        '[]'
    )
    INTO v_emails
    FROM email_message em
    WHERE em.tenant_id  = p_tenant_id
      AND em.contact_id IN (
          SELECT contact_id FROM contact
          WHERE  customer_id = p_customer_id
            AND  tenant_id   = p_tenant_id
      );

    -- 4. Historia wiadomosci social media
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'message_id',   message_id,
                'platform',     platform,
                'direction',    direction,
                'content',      content,
                'sent_at',      sent_at
            )
            ORDER BY sent_at DESC
        ),
        '[]'
    )
    INTO v_social_msgs
    FROM social_message sm
    WHERE sm.tenant_id  = p_tenant_id
      AND sm.contact_id IN (
          SELECT contact_id FROM contact
          WHERE  customer_id = p_customer_id
            AND  tenant_id   = p_tenant_id
      );

    -- Kompilacja pelnego eksportu
    v_result := jsonb_build_object(
        'export_generated_at',  NOW(),
        'legal_basis',          'GDPR_ART_20',
        'customer',             v_customer,
        'contacts',             v_contacts,
        'email_messages',       v_emails,
        'social_messages',      v_social_msgs
    );

    -- Audit log: rejestracja eksportu (RODO Art. 30)
    INSERT INTO audit_log (
        tenant_id, action, entity_type, entity_id, new_value, created_at
    ) VALUES (
        p_tenant_id,
        'CUSTOMER_DATA_EXPORTED',
        'CUSTOMER',
        p_customer_id,
        jsonb_build_object(
            'export_at', NOW(),
            'legal_basis', 'GDPR_ART_20'
        ),
        NOW()
    );

    RETURN v_result;
END;
$$;

COMMENT ON FUNCTION export_customer_data(UUID, UUID) IS
    'Eksport wszystkich danych klienta w formacie JSONB (RODO Art. 20 – przenosnosc danych). '
    'Zwraca: customer, contacts, email_messages, social_messages. '
    'Nie eksportuje tresci email (prywatnosc) – tylko metadane. '
    'Rejestruje zdarzenie eksportu w audit_log.';

-- ---------------------------------------------------------------------------
-- 3. Rejestr przetwarzania danych (RODO Art. 30)
-- ---------------------------------------------------------------------------

CREATE TABLE gdpr_processing_register (
    register_id     UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID,           -- NULL = globalny rejestr platformy

    -- Kategoria przetwarzania
    processing_name VARCHAR(255)    NOT NULL,
    legal_basis     VARCHAR(100)    NOT NULL,
    -- Mozliwe: CONSENT, CONTRACT, LEGAL_OBLIGATION, VITAL_INTERESTS, PUBLIC_TASK, LEGITIMATE_INTERESTS

    data_categories TEXT[]          NOT NULL DEFAULT '{}',
    -- Przyklad: ARRAY['CONTACT_DATA', 'COMMUNICATION_HISTORY', 'VOICE_RECORDINGS']

    data_subjects   TEXT[]          NOT NULL DEFAULT '{}',
    -- Przyklad: ARRAY['CUSTOMERS', 'EMPLOYEES']

    recipients      TEXT[]          NOT NULL DEFAULT '{}',
    -- Podmioty, ktorym dane sa udostepniane

    -- Kraje trzecie (poza UE) do ktorych dane sa przekazywane
    third_countries TEXT[]          NOT NULL DEFAULT '{}',

    -- Okres retencji danych
    retention_policy TEXT           NOT NULL,
    -- Przyklad: "90 dni dla nagran, 5 lat dla historii kontaktow"

    -- Opis technicznych i organizacyjnych srodkow bezpieczenstwa
    security_measures TEXT,

    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,

    CONSTRAINT pk_gdpr_register PRIMARY KEY (register_id),

    CONSTRAINT fk_gdpr_register_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_gdpr_register_tenant
    ON gdpr_processing_register (tenant_id)
    WHERE is_active = TRUE;

CREATE TRIGGER trg_gdpr_register_updated_at
    BEFORE UPDATE ON gdpr_processing_register
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

-- Wpisy bazowe dla platformy (globalny rejestr)
INSERT INTO gdpr_processing_register (
    tenant_id, processing_name, legal_basis,
    data_categories, data_subjects, recipients,
    retention_policy, security_measures
) VALUES
(
    NULL,
    'Obsluga klientow przez Contact Center',
    'LEGITIMATE_INTERESTS',
    ARRAY['CONTACT_DATA', 'COMMUNICATION_HISTORY', 'VOICE_RECORDINGS'],
    ARRAY['CUSTOMERS'],
    ARRAY['AGENTS', 'SUPERVISORS'],
    'Nagrania: konfigurowalny per tenant (domyslnie 90 dni). Historia kontaktow: 5 lat.',
    'Szyfrowanie TLS w transmisji, AES-256 dla nagran, logiczny podzial danych per tenant.'
),
(
    NULL,
    'Zarzadzanie uzytkownikami systemu',
    'CONTRACT',
    ARRAY['IDENTIFICATION_DATA', 'AUTHENTICATION_DATA', 'ACTIVITY_LOGS'],
    ARRAY['EMPLOYEES', 'AGENTS', 'SUPERVISORS'],
    ARRAY['SYSTEM_ADMINISTRATORS'],
    'Dane kont aktywnych przez okres trwania umowy + 30 dni. Audit log: 2 lata.',
    'Bcrypt (12 rund) dla hasel, JWT z krótkim TTL, MFA dla ról uprzywilejowanych.'
);

COMMENT ON TABLE gdpr_processing_register IS
    'Rejestr czynnosci przetwarzania danych (RODO Art. 30). '
    'Dostepny dla Administratora platformy w dashboardzie technicznym.';
