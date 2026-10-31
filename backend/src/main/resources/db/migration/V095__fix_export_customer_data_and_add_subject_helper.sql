-- =============================================================================
-- V095__fix_export_customer_data_and_add_subject_helper.sql
-- DB-061: Naprawa i rozszerzenie export_customer_data (RODO Art. 15/20)
--
-- Migracja: Flyway V095
-- Numeracja: V094 jest najwyzsza wersja na tej galezi; sprawdzone git ls-tree
--            na wszystkich galeziach lokalnych/zdalnych (develop/main/inne
--            feature) i flyway_schema_history zywej bazy (2026-09-24, zywa
--            baza na V093) -- zaden inny plik V095 nie istnieje nigdzie.
-- Zaleznosci: V006 (customer), V007 (contact), V010 (email_message,
--             social_message), V013 (anonymize_customer, export_customer_data),
--             V014/V015 (scheduled_callback, campaign_contact_archive),
--             V017 (export_customer_data + campaign_records), V067/V068
--             (contact_transcription, contact_ai_summary)
-- Blokuje: DB-062 (rozszerzenie anonymize_customer, reuzywa
--          fn_customer_subject_ids), BE-129
--
-- PROBLEM 1 (U3, DB-060): export_customer_data (V017) jest STABLE
-- (pg_proc.provolatile = 's') i konczy sie INSERT INTO audit_log.
-- PostgreSQL odrzuca DML w funkcji nie-VOLATILE: "INSERT is not allowed in
-- a non-volatile function". Zreprodukowane na scratch_db061 (pelny lancuch
-- V001..V094, klient syntetyczny, BEGIN; SELECT export_customer_data(...);
-- ROLLBACK;) -- funkcja NIGDY nie zwraca wyniku, zawsze konczy sie tym
-- bledem. Java (GdprServiceImpl#exportCustomerData) i tak juz zapisuje wpis
-- audytowy GDPR_EXPORT niezaleznie od tej funkcji (ktorej dzis NIKT nie
-- wola -- DESIGN U2) -- INSERT jest wiec usuniety z funkcji zamiast zmiany
-- volatility na VOLATILE (unika podwojnego wpisu audytowego po przyszlym
-- podlaczeniu przez BE-129).
--
-- PROBLEM 2 (D9, DB-060 F2/F5): predykaty "customer_id = ..." nie
-- wystarczaja do wyznaczenia zbioru danych klienta -- campaign_contact.
-- customer_id jest NULL w 37/37 wierszy live (import go nie ustawia), a
-- czesc kontaktow/callbackow/e-maili niesie telefon/e-mail klienta bez
-- zadnego powiazania kluczem obcym (56/64 kontaktow, 20/55 callbackow,
-- 14/23 osieroconych e-maili w danych live). Decyzja D9 = A (dopasowanie
-- takze po znormalizowanym identyfikatorze) wymaga wspolnej reguly, ktora
-- reuzyje przyszly DB-062 (anonymize_customer) -- stad nowa funkcja
-- pomocnicza fn_customer_subject_ids w tej migracji (eksport jest
-- tylko-do-odczytu, wiec wchodzi jako pierwszy).
--
-- PROBLEM 3 (DB-060 uzupelnienie): zakres eksportu byl niepelny --
-- brakowalo scheduled_callback, transkrypcji/podsumowan AI, contact.notes/
-- remote_address/recording_url/channel_metadata, tresci e-mail (to/cc/bcc/
-- body_*), metadanych zalacznikow (w tym s3_key), customer.external_id,
-- email_message.cc_address/bcc_address, social_messages.sender_external_id.
-- campaign_records mial tylko ID -- komentarz V017 ("Dane osobiste ...
-- uwzglednione") byl niezgodny z DDL (phone/first_name/last_name/email/
-- custom_fields nigdy nie byly w SELECT).
--
-- ROZWIAZANIE:
--   0. fn_normalize_phone(TEXT) -- prosta normalizacja numeru telefonu do
--      porownan identyfikatorowych (brak istniejacej funkcji normalizujacej
--      w repo -- zweryfikowane grepem po "normalize_phone"/podobnych nazwach
--      w backend/ i migracjach). Usuwa wszystko oprocz cyfr i wiodacego '+'.
--      Nie jest to pelna walidacja E.164 (brak wnioskowania kodu kraju) --
--      dane live pokazuja, ze >90% numerow jest juz zapisanych w postaci
--      "+<cyfry>" bez separatorow (kontakt.remote_address ma najwiecej
--      wyjatkow: spacje/myslniki/nawiasy w 28/426 wierszy).
--   1. fn_customer_subject_ids(p_customer_id, p_tenant_id) RETURNS TABLE
--      (entity_type, entity_id, matched_by) -- patrz COMMENT ON FUNCTION
--      nizej dla pelnego kontraktu. STABLE, SELECT-only.
--   2. export_customer_data: CREATE OR REPLACE (sygnatura (UUID, UUID) bez
--      zmian -- potwierdzone, nie trzeba DROP FUNCTION), STABLE zachowane,
--      INSERT INTO audit_log usuniety, zakres rozszerzony wg macierzy
--      DB-060, wynik niesie matched_by per rekord + liczniki
--      matched_by_link/matched_by_identifier oraz s3_keys (nagrania, EML,
--      zalaczniki e-mail/social wlacznie z pending/ wiadomosci OUTBOUND --
--      to sa klucze DOCELOWE, nie tymczasowe, BE-124 par.3) jako dane
--      wejsciowe dla przyszlego BE-129 (manifest + presigned URL, bez
--      plikow w ZIP). Brak ucinania wyniku -- paginacja/strumieniowanie to
--      zakres BE-129.
--
-- WYDAJNOSC: zmierzone na scratch_db061 (1 klient, 10 002 wiadomosci e-mail
-- powiazanych przez contact_id, ANALYZE po bulk-insercie): ~650-830 ms na
-- wywolanie (wymog < 2 s spelniony). Indeksy uzywane w planie: pk_email_message
-- (message_id) jako strona sterujaca joina z malym zbiorem "subject" (szybsze
-- niz skan po idx_email_message_contact, ktory rowniez istnieje i jest
-- uzywany przez inne zapytania warstwy Java). Bez nowego indeksu -- istniejace
-- (idx_email_message_contact, idx_social_message_contact, pk_email_message,
-- pk_social_message) wystarczaja. UWAGA: pierwsze wywolanie zaraz po
-- syntetycznym bulk-inserzie 10 tys. wierszy (przed ANALYZE/autovacuum)
-- zmierzone na ~6.7 s -- planner ze stale statystykami wybral gorszy plan;
-- po ANALYZE (lub naturalnym autoanalyze) czas spada do <1 s. W produkcji
-- wiersze przybywaja stopniowo (nie jednym bulk-insertem), wiec statystyki
-- sa na biezaco aktualizowane przez autovacuum -- nie jest to realne ryzyko
-- poza syntetycznym testem obciazeniowym, ale warto o tym pamietac przy
-- interpretacji wynikow po masowych migracjach/importach danych.
--
-- RLS: sprawdzone empirycznie pod SET ROLE app_user z
-- app.current_tenant_id ustawionym na tenanta klienta -- obie funkcje
-- dzialaja bez SECURITY DEFINER (customer/contact/contact_transcription/
-- contact_ai_summary/scheduled_callback maja polityki obejmujace SELECT;
-- email_message/social_message/audit_log maja polityke tylko SELECT, co
-- wystarcza funkcji tylko-do-odczytu; campaign_contact/campaign_contact_
-- archive nie maja RLS w ogole -- izolacja tenanta w tej migracji polega
-- wylacznie na jawnym WHERE tenant_id = p_tenant_id w kazdym zapytaniu).
-- Bez GUC (sesja bez ustawionego app.current_tenant_id) RLS na customer
-- (FORCE) ukrywa wiersz klienta -- funkcja konczy sie czytelnym wyjatkiem
-- "Klient ... nie istnieje", a nie cichym pustym wynikiem ani bledem SQL.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. fn_normalize_phone
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_normalize_phone(p_phone TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT NULLIF(regexp_replace(COALESCE(p_phone, ''), '[^0-9+]', '', 'g'), '')
$$;

COMMENT ON FUNCTION fn_normalize_phone(TEXT) IS
    'Prosta normalizacja numeru telefonu do porownan identyfikatorowych (DB-061/DB-062, decyzja D9): '
    'usuwa spacje/myslniki/nawiasy, zachowuje cyfry i wiodacy znak +. Nie jest to pelna walidacja/'
    'normalizacja E.164 (brak wnioskowania kodu kraju z numeru krajowego) -- w tej bazie wiekszosc '
    'numerow jest juz zapisana w postaci +<cyfry> bez separatorow (zweryfikowane DB-061).';

-- ---------------------------------------------------------------------------
-- 1. fn_customer_subject_ids -- wspolna regula zbioru podmiotu (D9 = A)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_customer_subject_ids(
    p_customer_id UUID,
    p_tenant_id   UUID
) RETURNS TABLE (
    entity_type TEXT,
    entity_id   UUID,
    matched_by  TEXT
)
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_phones TEXT[];
    v_emails TEXT[];
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM customer
        WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id
    ) THEN
        RAISE EXCEPTION 'Klient % nie istnieje w tenancie %.', p_customer_id, p_tenant_id;
    END IF;

    SELECT array_agg(DISTINCT np) INTO v_phones
    FROM customer, jsonb_array_elements_text(phone) elem
    CROSS JOIN LATERAL (SELECT fn_normalize_phone(elem) AS np) n
    WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id
      AND np IS NOT NULL;

    SELECT array_agg(DISTINCT lower(elem)) INTO v_emails
    FROM customer, jsonb_array_elements_text(email) elem
    WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id
      AND elem IS NOT NULL;

    RETURN QUERY
    WITH subject_contacts AS (
        SELECT
            c.contact_id,
            CASE WHEN c.customer_id = p_customer_id THEN 'link' ELSE 'identifier' END AS matched_by
        FROM contact c
        WHERE c.tenant_id = p_tenant_id
          AND (
                c.customer_id = p_customer_id
             OR (fn_normalize_phone(c.remote_address) IS NOT NULL
                 AND fn_normalize_phone(c.remote_address) = ANY (COALESCE(v_phones, ARRAY[]::TEXT[])))
             OR (c.remote_address IS NOT NULL
                 AND lower(c.remote_address) = ANY (COALESCE(v_emails, ARRAY[]::TEXT[])))
          )
    ),
    subject_contact_campaign_records AS (
        SELECT DISTINCT c.campaign_contact_record_id AS record_id
        FROM contact c
        JOIN subject_contacts sc ON sc.contact_id = c.contact_id
        WHERE c.campaign_contact_record_id IS NOT NULL
    ),
    subject_callbacks AS (
        SELECT
            sb.callback_id,
            CASE
                WHEN sb.customer_id = p_customer_id THEN 'link'
                WHEN sb.origin_contact_id IN (SELECT contact_id FROM subject_contacts) THEN 'link'
                WHEN sb.campaign_contact_record_id IN (SELECT record_id FROM subject_contact_campaign_records) THEN 'link'
                ELSE 'identifier'
            END AS matched_by
        FROM scheduled_callback sb
        WHERE sb.tenant_id = p_tenant_id
          AND (
                sb.customer_id = p_customer_id
             OR sb.origin_contact_id IN (SELECT contact_id FROM subject_contacts)
             OR sb.campaign_contact_record_id IN (SELECT record_id FROM subject_contact_campaign_records)
             OR (fn_normalize_phone(sb.phone) IS NOT NULL
                 AND fn_normalize_phone(sb.phone) = ANY (COALESCE(v_phones, ARRAY[]::TEXT[])))
          )
    ),
    subject_campaign_contacts AS (
        SELECT
            cc.record_id,
            CASE
                WHEN cc.customer_id = p_customer_id THEN 'link'
                WHEN cc.last_contact_id IN (SELECT contact_id FROM subject_contacts) THEN 'link'
                WHEN cc.record_id IN (SELECT record_id FROM subject_contact_campaign_records) THEN 'link'
                ELSE 'identifier'
            END AS matched_by
        FROM campaign_contact cc
        WHERE cc.tenant_id = p_tenant_id
          AND (
                cc.customer_id = p_customer_id
             OR cc.last_contact_id IN (SELECT contact_id FROM subject_contacts)
             OR cc.record_id IN (SELECT record_id FROM subject_contact_campaign_records)
             OR (fn_normalize_phone(cc.phone) IS NOT NULL
                 AND fn_normalize_phone(cc.phone) = ANY (COALESCE(v_phones, ARRAY[]::TEXT[])))
          )
    ),
    subject_campaign_contact_archive AS (
        SELECT
            cca.record_id,
            CASE
                WHEN cca.customer_id = p_customer_id THEN 'link'
                WHEN cca.last_contact_id IN (SELECT contact_id FROM subject_contacts) THEN 'link'
                WHEN cca.record_id IN (SELECT record_id FROM subject_contact_campaign_records) THEN 'link'
                ELSE 'identifier'
            END AS matched_by
        FROM campaign_contact_archive cca
        WHERE cca.tenant_id = p_tenant_id
          AND (
                cca.customer_id = p_customer_id
             OR cca.last_contact_id IN (SELECT contact_id FROM subject_contacts)
             OR cca.record_id IN (SELECT record_id FROM subject_contact_campaign_records)
             OR (fn_normalize_phone(cca.phone) IS NOT NULL
                 AND fn_normalize_phone(cca.phone) = ANY (COALESCE(v_phones, ARRAY[]::TEXT[])))
          )
    ),
    subject_emails AS (
        SELECT
            em.message_id,
            CASE
                WHEN em.contact_id IN (SELECT contact_id FROM subject_contacts) THEN 'link'
                ELSE 'identifier'
            END AS matched_by
        FROM email_message em
        WHERE em.tenant_id = p_tenant_id
          AND (
                em.contact_id IN (SELECT contact_id FROM subject_contacts)
             OR lower(em.from_address) = ANY (COALESCE(v_emails, ARRAY[]::TEXT[]))
             OR EXISTS (
                  SELECT 1 FROM unnest(string_to_array(em.to_address, ',')) addr
                  WHERE lower(btrim(addr)) = ANY (COALESCE(v_emails, ARRAY[]::TEXT[]))
                )
          )
    ),
    subject_social AS (
        SELECT
            sm.message_id,
            'link'::TEXT AS matched_by
        FROM social_message sm
        WHERE sm.tenant_id = p_tenant_id
          AND sm.contact_id IN (SELECT contact_id FROM subject_contacts)
    )
    SELECT 'CONTACT'::TEXT, subject_contacts.contact_id, subject_contacts.matched_by FROM subject_contacts
    UNION ALL
    SELECT 'SCHEDULED_CALLBACK'::TEXT, subject_callbacks.callback_id, subject_callbacks.matched_by FROM subject_callbacks
    UNION ALL
    SELECT 'CAMPAIGN_CONTACT'::TEXT, subject_campaign_contacts.record_id, subject_campaign_contacts.matched_by FROM subject_campaign_contacts
    UNION ALL
    SELECT 'CAMPAIGN_CONTACT_ARCHIVE'::TEXT, subject_campaign_contact_archive.record_id, subject_campaign_contact_archive.matched_by FROM subject_campaign_contact_archive
    UNION ALL
    SELECT 'EMAIL_MESSAGE'::TEXT, subject_emails.message_id, subject_emails.matched_by FROM subject_emails
    UNION ALL
    SELECT 'SOCIAL_MESSAGE'::TEXT, subject_social.message_id, subject_social.matched_by FROM subject_social;
END;
$$;

COMMENT ON FUNCTION fn_customer_subject_ids(UUID, UUID) IS
    'Wspolna regula zbioru podmiotu RODO Art. 15/17 (DB-060 F2/F5, decyzja D9 = A). Kontrakt (reuzywany '
    'przez DB-062 anonymize_customer, tryb podgladu p_dry_run): jeden wiersz na kazda encje powiazana '
    'z klientem -- entity_type in (CONTACT, SCHEDULED_CALLBACK, CAMPAIGN_CONTACT, '
    'CAMPAIGN_CONTACT_ARCHIVE, EMAIL_MESSAGE, SOCIAL_MESSAGE), entity_id = PK encji (contact_id / '
    'callback_id / record_id / message_id), matched_by in (link, identifier). link = powiazanie '
    'kluczem obcym (customer_id, last_contact_id, origin_contact_id, campaign_contact_record_id -- '
    'zob. tresc funkcji dla dokladnej reguly per typ encji). identifier = brak powiazania kluczowego, '
    'dopasowanie po znormalizowanym telefonie (fn_normalize_phone) lub adresie e-mail (lower()) '
    'klienta w obrebie tego samego tenanta -- WYLACZNIE dla CONTACT (remote_address), '
    'SCHEDULED_CALLBACK/CAMPAIGN_CONTACT/CAMPAIGN_CONTACT_ARCHIVE (phone) i EMAIL_MESSAGE '
    '(from_address/to_address); SOCIAL_MESSAGE nie ma dopasowania identyfikatorowego (sender_external_id '
    'to identyfikator platformy, nie znormalizowany telefon/e-mail -- swiadome ograniczenie, '
    'udokumentowane w notatce DB-061). Ryzyko wspolnego numeru/adresu (fasz. dopasowania, R9 '
    'DESIGN-message-retention-and-partitioning.md par.7) jest zamierzone -- wywolujacy (DB-061 eksport, '
    'DB-062 podglad/anonimizacja) musi pokazac matched_by operatorowi. STABLE, SELECT-only -- bezpieczna '
    'do wywolania z funkcji STABLE (export_customer_data) bez ryzyka bledu "INSERT/UPDATE is not '
    'allowed in a non-volatile function". Dziala pod SET ROLE app_user z app.current_tenant_id '
    'ustawionym na p_tenant_id, bez SECURITY DEFINER (zweryfikowane DB-061) -- wymaga SELECT na '
    'customer/contact/scheduled_callback/campaign_contact/campaign_contact_archive/email_message/'
    'social_message pod rola wywolujaca.';

-- ---------------------------------------------------------------------------
-- 2. export_customer_data -- naprawa U3 + rozszerzenie zakresu (DB-060)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION export_customer_data(
    p_customer_id UUID,
    p_tenant_id   UUID
) RETURNS JSONB
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_result JSONB;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM customer
        WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id AND is_deleted = FALSE
    ) THEN
        RAISE EXCEPTION 'Klient % nie istnieje lub zostal zanonimizowany.', p_customer_id;
    END IF;

    WITH subject AS MATERIALIZED (
        SELECT * FROM fn_customer_subject_ids(p_customer_id, p_tenant_id)
    )
    SELECT jsonb_build_object(
        'export_generated_at', NOW(),
        'legal_basis',         'GDPR_ART_15_20',
        'customer', (
            SELECT jsonb_build_object(
                'customer_id',   customer_id,
                'first_name',    first_name,
                'last_name',     last_name,
                'phone',         phone,
                'email',         email,
                'external_id',   external_id,
                'custom_fields', custom_fields,
                'gdpr_consent',  gdpr_consent,
                'source',        source,
                'created_at',    created_at
            )
            FROM customer
            WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id
        ),
        'contacts', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'contact_id',       c.contact_id,
                'channel',          c.channel,
                'direction',        c.direction,
                'status',           c.status,
                'remote_address',   c.remote_address,
                'started_at',       c.started_at,
                'ended_at',         c.ended_at,
                'duration_seconds', c.duration_seconds,
                'disposition_code', c.disposition_code,
                'notes',            c.notes,
                'recording_url',    c.recording_url,
                'channel_metadata', c.channel_metadata,
                'matched_by',       s.matched_by
            ) ORDER BY c.started_at DESC)
            FROM contact c
            JOIN subject s ON s.entity_type = 'CONTACT' AND s.entity_id = c.contact_id
            WHERE c.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        'scheduled_callbacks', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'callback_id',   sb.callback_id,
                'campaign_id',   sb.campaign_id,
                'agent_id',      sb.agent_id,
                'phone',         sb.phone,
                'first_name',    sb.first_name,
                'last_name',     sb.last_name,
                'scheduled_at',  sb.scheduled_at,
                'notes',         sb.notes,
                'status',        sb.status,
                'source_type',   sb.source_type,
                'created_at',    sb.created_at,
                'matched_by',    s.matched_by
            ) ORDER BY sb.scheduled_at DESC)
            FROM scheduled_callback sb
            JOIN subject s ON s.entity_type = 'SCHEDULED_CALLBACK' AND s.entity_id = sb.callback_id
            WHERE sb.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        'campaign_records', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'record_id',         combined.record_id,
                'campaign_id',       combined.campaign_id,
                'status',            combined.status,
                'attempt_count',     combined.attempt_count,
                'disposition_code',  combined.disposition_code,
                'last_attempt_at',   combined.last_attempt_at,
                'phone',             combined.phone,
                'first_name',        combined.first_name,
                'last_name',         combined.last_name,
                'email',             combined.email,
                'custom_fields',     combined.custom_fields,
                'source',            combined.source_table,
                'matched_by',        combined.matched_by
            ) ORDER BY combined.last_attempt_at DESC NULLS LAST)
            FROM (
                SELECT cc.record_id, cc.campaign_id, cc.status, cc.attempt_count, cc.disposition_code,
                       cc.last_attempt_at, cc.phone, cc.first_name, cc.last_name, cc.email, cc.custom_fields,
                       'operational' AS source_table, s.matched_by
                FROM campaign_contact cc
                JOIN subject s ON s.entity_type = 'CAMPAIGN_CONTACT' AND s.entity_id = cc.record_id
                WHERE cc.tenant_id = p_tenant_id
                UNION ALL
                SELECT cca.record_id, cca.campaign_id, cca.status, cca.attempt_count, cca.disposition_code,
                       cca.last_attempt_at, cca.phone, cca.first_name, cca.last_name, cca.email, cca.custom_fields,
                       'archive' AS source_table, s.matched_by
                FROM campaign_contact_archive cca
                JOIN subject s ON s.entity_type = 'CAMPAIGN_CONTACT_ARCHIVE' AND s.entity_id = cca.record_id
                WHERE cca.tenant_id = p_tenant_id
            ) combined
        ), '[]'::jsonb),
        'email_messages', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'message_id',      em.message_id,
                'direction',       em.direction,
                'from_address',    em.from_address,
                'to_address',      em.to_address,
                'cc_address',      em.cc_address,
                'bcc_address',     em.bcc_address,
                'subject',         em.subject,
                'body_html',       em.body_html,
                'body_text',       em.body_text,
                'attachments',     COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'filename',     att->>'filename',
                        'content_type', att->>'content_type',
                        'size_bytes',   att->'size_bytes',
                        's3_key',       att->>'s3_key'
                    ))
                    FROM jsonb_array_elements(em.attachments) att
                ), '[]'::jsonb),
                'received_at',     em.received_at,
                'sent_at',         em.sent_at,
                'delivery_status', em.delivery_status,
                'matched_by',      s.matched_by
            ) ORDER BY COALESCE(em.received_at, em.sent_at) DESC)
            FROM email_message em
            JOIN subject s ON s.entity_type = 'EMAIL_MESSAGE' AND s.entity_id = em.message_id
            WHERE em.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        'social_messages', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'message_id',         sm.message_id,
                'platform',           sm.platform,
                'direction',          sm.direction,
                'sender_external_id', sm.sender_external_id,
                'content',            sm.content,
                'attachments',        COALESCE((
                    SELECT jsonb_agg(jsonb_build_object(
                        'filename',     att->>'filename',
                        'content_type', att->>'content_type',
                        'size_bytes',   att->'size_bytes',
                        's3_key',       att->>'s3_key'
                    ))
                    FROM jsonb_array_elements(sm.attachments) att
                ), '[]'::jsonb),
                'sent_at',     sm.sent_at,
                'received_at', sm.received_at,
                'matched_by',  s.matched_by
            ) ORDER BY sm.sent_at DESC)
            FROM social_message sm
            JOIN subject s ON s.entity_type = 'SOCIAL_MESSAGE' AND s.entity_id = sm.message_id
            WHERE sm.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        'transcriptions', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'transcription_id', t.transcription_id,
                'contact_id',       t.contact_id,
                'content',          t.content,
                'language',         t.language,
                'created_at',       t.created_at
            ) ORDER BY t.created_at DESC)
            FROM contact_transcription t
            JOIN subject s ON s.entity_type = 'CONTACT' AND s.entity_id = t.contact_id
            WHERE t.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        'ai_summaries', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'ai_summary_id', a.ai_summary_id,
                'contact_id',    a.contact_id,
                'summary',       a.summary,
                'model',         a.model,
                'generated_at',  a.generated_at
            ) ORDER BY a.generated_at DESC)
            FROM contact_ai_summary a
            JOIN subject s ON s.entity_type = 'CONTACT' AND s.entity_id = a.contact_id
            WHERE a.tenant_id = p_tenant_id
        ), '[]'::jsonb),
        's3_keys', (
            SELECT COALESCE(jsonb_agg(DISTINCT key ORDER BY key), '[]'::jsonb)
            FROM (
                SELECT c.recording_url AS key
                FROM contact c
                JOIN subject s ON s.entity_type = 'CONTACT' AND s.entity_id = c.contact_id
                WHERE c.tenant_id = p_tenant_id AND c.recording_url IS NOT NULL AND c.recording_url <> ''
                UNION ALL
                SELECT (att->>'s3_key') AS key
                FROM email_message em
                JOIN subject s ON s.entity_type = 'EMAIL_MESSAGE' AND s.entity_id = em.message_id,
                     jsonb_array_elements(em.attachments) att
                WHERE em.tenant_id = p_tenant_id
                  AND (att->>'s3_key') IS NOT NULL AND (att->>'s3_key') <> ''
                UNION ALL
                SELECT (att->>'s3_key') AS key
                FROM social_message sm
                JOIN subject s ON s.entity_type = 'SOCIAL_MESSAGE' AND s.entity_id = sm.message_id,
                     jsonb_array_elements(sm.attachments) att
                WHERE sm.tenant_id = p_tenant_id
                  AND (att->>'s3_key') IS NOT NULL AND (att->>'s3_key') <> ''
            ) keys
        ),
        'matched_by_link',       (SELECT count(*) FROM subject WHERE matched_by = 'link'),
        'matched_by_identifier', (SELECT count(*) FROM subject WHERE matched_by = 'identifier')
    )
    INTO v_result;

    RETURN v_result;
END;
$$;

COMMENT ON FUNCTION export_customer_data(UUID, UUID) IS
    'Eksport wszystkich danych klienta w formacie JSONB (RODO Art. 15/20). DB-061: usunieto INSERT INTO '
    'audit_log -- funkcja STABLE nie moze modyfikowac danych (PostgreSQL "INSERT is not allowed in a '
    'non-volatile function", zreprodukowane przed ta migracja); wpis GDPR_EXPORT zapisuje Java '
    '(GdprServiceImpl#exportCustomerData). Zbior podmiotu wyznacza fn_customer_subject_ids (D9 = A: '
    'link = powiazanie kluczem obcym, identifier = dopasowanie po znormalizowanym telefonie/e-mailu '
    'klienta) -- kazdy rekord w wyniku niesie wlasne pole matched_by, a caly wynik dodatkowo liczniki '
    'matched_by_link/matched_by_identifier. Klucze najwyzszego poziomu (kontrakt dla BE-129): '
    'export_generated_at, legal_basis, customer, contacts, scheduled_callbacks, campaign_records '
    '(operacyjne + archiwum campaign_contact_archive, Z PELNYM PII klienta -- poprawka komentarza V017, '
    'ktory PII obiecywal w opisie, ale nie zwracal go w SELECT), email_messages (z tresc/to/cc/bcc/'
    'attachments), social_messages, transcriptions, ai_summaries, s3_keys (nagrania i EML z '
    'contact.recording_url, zalaczniki e-mail/social z attachments[*].s3_key -- w tym pending/ '
    'wiadomosci OUTBOUND, ktore sa kluczami DOCELOWYMI, nie tymczasowymi), matched_by_link, '
    'matched_by_identifier. Brak ucinania wyniku -- paginacja/strumieniowanie duzych eksportow to zakres '
    'BE-129 (manifest + presigned URL, bez plikow w ZIP).';
