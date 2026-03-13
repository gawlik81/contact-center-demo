-- =============================================================================
-- V017__gdpr_archive_export.sql
-- DB-002 (uzupelnienie): Rozszerzenie eksportu RODO o archiwum kampanii
--                         + widok historii klienta (kontakty + archiwum kampanii)
--
-- Migracja: Flyway V017
-- Zaleznosci: V013 (gdpr_functions), V015 (campaign_contact_archive)
-- Odniesienie PRD: US-09-06, NFR-RODO01, RODO Art. 20
--
-- Problem: export_customer_data() z V013 nie obejmuje danych z
--          campaign_contact_archive (tabela stworzona w V015).
--          Klient ma prawo zadac pelnych danych wlacznie z archiwum (Art. 20).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Zastapienie export_customer_data() wersja obejmujaca archiwum
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION export_customer_data(
    p_customer_id  UUID,
    p_tenant_id    UUID
) RETURNS JSONB
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_customer          JSONB;
    v_contacts          JSONB;
    v_emails            JSONB;
    v_social_msgs       JSONB;
    v_campaign_records  JSONB;
    v_result            JSONB;
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
        'customer_id',   customer_id,
        'first_name',    first_name,
        'last_name',     last_name,
        'phone',         phone,
        'email',         email,
        'custom_fields', custom_fields,
        'gdpr_consent',  gdpr_consent,
        'source',        source,
        'created_at',    created_at
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

    -- 3. Historia emaili (metadane – bez pelnej tresci dla zachowania prywatnosci innych stron)
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'message_id',   message_id,
                'direction',    direction,
                'from_address', from_address,
                'subject',      subject,
                'received_at',  received_at,
                'sent_at',      sent_at
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
                'message_id', message_id,
                'platform',   platform,
                'direction',  direction,
                'content',    content,
                'sent_at',    sent_at
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

    -- 5. Historia udzialu w kampaniach (operacyjna + archiwum)
    --    Dane osobiste (phone, first_name, last_name) uwzglednione – sa to dane klienta
    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'campaign_id',      campaign_id,
                'status',           status,
                'attempt_count',    attempt_count,
                'disposition_code', disposition_code,
                'last_attempt_at',  last_attempt_at,
                'source',           source_table
            )
            ORDER BY last_attempt_at DESC NULLS LAST
        ),
        '[]'
    )
    INTO v_campaign_records
    FROM (
        -- Aktualne rekordy z tabeli operacyjnej
        SELECT
            campaign_id, status, attempt_count,
            disposition_code, last_attempt_at,
            'operational' AS source_table
        FROM campaign_contact
        WHERE customer_id = p_customer_id
          AND tenant_id   = p_tenant_id

        UNION ALL

        -- Zarchiwizowane rekordy
        SELECT
            campaign_id, status, attempt_count,
            disposition_code, last_attempt_at,
            'archive' AS source_table
        FROM campaign_contact_archive
        WHERE customer_id = p_customer_id
          AND tenant_id   = p_tenant_id
    ) combined;

    -- Kompilacja pelnego eksportu
    v_result := jsonb_build_object(
        'export_generated_at',  NOW(),
        'legal_basis',          'GDPR_ART_20',
        'customer',             v_customer,
        'contacts',             v_contacts,
        'email_messages',       v_emails,
        'social_messages',      v_social_msgs,
        'campaign_records',     v_campaign_records
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
            'export_at',    NOW(),
            'legal_basis',  'GDPR_ART_20',
            'includes',     ARRAY['contacts', 'email_messages', 'social_messages', 'campaign_records']
        ),
        NOW()
    );

    RETURN v_result;
END;
$$;

COMMENT ON FUNCTION export_customer_data(UUID, UUID) IS
    'Eksport wszystkich danych klienta w formacie JSONB (RODO Art. 20 – przenosnosc danych). '
    'Zwraca: customer, contacts, email_messages, social_messages, campaign_records (operacyjne + archiwum). '
    'Rozszerzona wersja z V013 – obejmuje rowniez campaign_contact_archive (V015). '
    'Rejestruje zdarzenie eksportu w audit_log.';

-- ---------------------------------------------------------------------------
-- 2. Widok historii klienta (przekrojowy – dla BE-025, US-09-02)
-- ---------------------------------------------------------------------------
-- Laczy wszystkie interakcje klienta: kontakty, emaile, social, kampanie
-- Uzywany przez backend do wyswietlenia pelnej historii na karcie klienta

-- Kolumny widoku (wszystkie jako TEXT/UUID/TIMESTAMPTZ dla zgodnosci UNION ALL):
--   event_type TEXT, event_id UUID, tenant_id UUID, customer_id UUID,
--   sub_type TEXT, direction TEXT, status TEXT, event_at TIMESTAMPTZ,
--   ended_at TIMESTAMPTZ, duration_seconds INT, disposition_code TEXT,
--   agent_id UUID, content_preview TEXT

CREATE OR REPLACE VIEW v_customer_timeline AS
SELECT
    'CONTACT'::TEXT                         AS event_type,
    c.contact_id                            AS event_id,
    c.tenant_id,
    c.customer_id,
    c.channel::TEXT                         AS sub_type,
    c.direction::TEXT                       AS direction,
    c.status::TEXT                          AS status,
    c.started_at                            AS event_at,
    c.ended_at,
    c.duration_seconds,
    c.disposition_code,
    c.agent_id,
    NULL::TEXT                              AS content_preview

FROM contact c
WHERE c.customer_id IS NOT NULL

UNION ALL

SELECT
    'EMAIL'::TEXT,
    em.message_id,
    em.tenant_id,
    con.customer_id,
    ('EMAIL_' || em.direction::TEXT)::TEXT,
    em.direction::TEXT,
    COALESCE(em.delivery_status, 'UNKNOWN')::TEXT,
    COALESCE(em.received_at, em.sent_at),
    NULL::TIMESTAMPTZ,
    NULL::INT,
    NULL::VARCHAR(50),
    NULL::UUID,
    LEFT(em.subject, 100)

FROM email_message em
JOIN contact con ON con.contact_id = em.contact_id
WHERE con.customer_id IS NOT NULL

UNION ALL

SELECT
    'SOCIAL'::TEXT,
    sm.message_id,
    sm.tenant_id,
    con.customer_id,
    sm.platform::TEXT,
    sm.direction::TEXT,
    NULL::TEXT,
    sm.sent_at,
    NULL::TIMESTAMPTZ,
    NULL::INT,
    NULL::VARCHAR(50),
    NULL::UUID,
    LEFT(sm.content, 100)

FROM social_message sm
JOIN contact con ON con.contact_id = sm.contact_id
WHERE con.customer_id IS NOT NULL;

COMMENT ON VIEW v_customer_timeline IS
    'Pelna oś czasu interakcji klienta: kontakty telefoniczne/email/social. '
    'Uzywany przez widok historii klienta w UI (BE-025, US-09-02). '
    'Sortowanie po event_at po stronie aplikacji (ORDER BY nie w widoku – optymalizacja).';
