-- =============================================================================
-- V096__extend_anonymize_customer_gdpr_art17.sql
-- DB-062: Rozszerzenie anonymize_customer (RODO Art. 17 - prawo do bycia zapomnianym)
--
-- Migracja: Flyway V096
-- Numeracja: V095 (DB-061) jest najwyzsza wersja wszedzie -- sprawdzone 2026-09-24
--            przez git ls-tree na wszystkich galeziach lokalnych (appmod/java-upgrade-
--            20260812100157, chore/epic-30-zadania, develop, main, partycjonowanie-2,
--            biezaca) i zdalnych (origin/develop, origin/feature-socialmedia,
--            origin/fix/drop-duplicate-contact-indexes, origin/main,
--            origin/release-please--branches--main) oraz flyway_schema_history zywej
--            bazy contact_center (konczy sie na V093, tylko-do-odczytu) -- V096 nigdzie
--            niezajete.
-- Zaleznosci: V013 (anonymize_customer, stara sygnatura VOID),
--             V095 (fn_customer_subject_ids, fn_normalize_phone -- REUZYWANE bez zmian,
--             D9 = A), V094 (fn_contact_ref_integrity zawezona -- UPDATE contact bez
--             zmiany referencji nie jest juz walidowany, warunek dzialania tej funkcji
--             dla klienta z kontaktami), V009/V015 (campaign_contact, archive),
--             V032 (scheduled_callback RLS), V086/V087 (contact_transcription,
--             contact_ai_summary partycjonowane), V079 (customer.external_id)
-- Blokuje: BE-129
--
-- KONTEKST (DB-060/DB-061, pelne dowody w notatkach tamtych tickietow):
-- anonymize_customer (V013, RETURNS VOID) anonimizuje tylko customer, email_message,
-- social_message (WYLACZNIE przez contact_id z customer_id = ...) i contact.remote_address/
-- channel_metadata. Nie dotyka scheduled_callback, campaign_contact/_archive,
-- contact.notes/recording_url, transkrypcji/podsumowan AI, cc_address/bcc_address,
-- customer.external_id/gdpr_consent/custom_fields (drugi z trzech), contacts_dw. Zwraca
-- VOID -- wolajacy (Java) nie zna zakresu ani kluczy S3 do usuniecia. Predykat
-- "customer_id = ..." jest za waski (DB-060 F2/F5): campaign_contact.customer_id jest
-- NULL w 37/37 wierszy live. Funkcja dzis nie ma zadnego wolajacego w Javie (U2,
-- BE-129 ja podepnie).
--
-- ROZWIAZANIE:
--   1. DROP FUNCTION starej sygnatury (VOID, 3 argumenty) -- CREATE OR REPLACE nie
--      pozwala zmienic typu zwracanego.
--   2. CREATE FUNCTION anonymize_customer(p_customer_id, p_tenant_id, p_user_id,
--      p_dry_run DEFAULT FALSE) RETURNS JSONB. Reuzywa fn_customer_subject_ids (D9 = A,
--      V095) do wyznaczenia zbioru podmiotu -- TA SAMA funkcja co export_customer_data,
--      wiec zbior jest KONSTRUKCYJNIE spojny miedzy podgladem RODO Art. 15 i Art. 17
--      (test spojnosci w AnonymizeCustomerExtensionTest).
--   3. Zbior podmiotu jest liczony DOKLADNIE RAZ, na starcie funkcji, PRZED jakakolwiek
--      mutacja -- zapisany w trzech tablicach PL/pgSQL (entity_type[], entity_id[],
--      matched_by juz zagregowany do dwoch liczb) zamiast tabeli tymczasowej. Powod:
--      fn_customer_subject_ids czyta customer.phone/email (do zbudowania listy
--      znormalizowanych identyfikatorow) i contact.remote_address/scheduled_callback.phone/
--      campaign_contact.phone (do dopasowania identyfikatorowego) -- gdyby te kolumny byly
--      juz wyzerowane przez WCZESNIEJSZY krok TEJ SAMEJ funkcji, KOLEJNE wywolanie
--      fn_customer_subject_ids (np. osobno dla kazdej tabeli) zwrocilo by SUKCESYWNIE
--      KURCZACY sie zbior (dopasowanie identyfikatorowe znika w miare zerowania kolumn) --
--      klasyczny blad "progressive narrowing". Zamrozenie zbioru na starcie eliminuje to
--      ryzyko i gwarantuje, ze tryb podgladu (p_dry_run) i rzeczywiste wywolanie licza z
--      TEGO SAMEGO zbioru.
--   4. Ryzyko klucza zlozonego campaign_contact/_archive (record_id, campaign_id) --
--      code review DB-061 (DB061-08): entity_id z fn_customer_subject_ids niesie TYLKO
--      record_id, a PK jest zlozony (record_id, campaign_id); schemat NIE wymusza
--      globalnej unikalnosci record_id (dzis prawdziwe empirycznie dzieki
--      uuid_generate_v4(), ale niegwarantowane). DECYZJA (uzasadnienie): WARIANT
--      rozszerzony -- twardy GUARD wykrywajacy kolizje PRZED jakakolwiek mutacja: jesli
--      dla ktoregokolwiek record_id w zbiorze podmiotu istnieje WIECEJ NIZ JEDNA wartosc
--      campaign_id w tym samym tenancie, funkcja PRZERYWA sie RAISE EXCEPTION (zero zmian,
--      pelny ROLLBACK) zamiast zgadywac/ryzykowac dotkniecie cudzego rekordu z innej
--      kampanii. Odrzucony wariant "czysty (b)" (poleganie wylacznie na tenant_id, ktory
--      i tak jest juz wymagany): NIE przechodzi testu z DWOMA rekordami o tym samym
--      record_id w roznych kampaniach tego samego tenanta (jawny INSERT z tym samym UUID)
--      -- goly JOIN po record_id + tenant_id dotknalby OBU rekordow, co jest dokladnie
--      scenariuszem, ktoremu ticket kaze zapobiec. Guard jest praktycznie martwym kodem
--      (kolizja UUID z uuid_generate_v4() jest kryptograficznie nieprawdopodobna --
--      potwierdzone w DB-061 code review), ale zamienia CICHE ZEPSUCIE DANYCH w GLOSNY,
--      BEZPIECZNY BLAD wymagajacy recznej interwencji, gdyby zalozenie kiedykolwiek
--      przestalo byc prawdziwe (np. przyszly import z jawnie nadawanym record_id).
--   5. Kolejnosc instrukcji: WSZYSTKIE UPDATE/DELETE na contact i tabelach pochodnych
--      PRZED UPDATE customer SET is_deleted = TRUE (ostatnia instrukcja przed audytem) --
--      obrona w glab, niezalezna od V094 (DB-079), ktora i tak czyni dzialanie funkcji
--      bezpiecznym NIEZALEZNIE od kolejnosci / powtornego wywolania (zawezenie triggera
--      fn_contact_ref_integrity: UPDATE bez zmiany customer_id/agent_id/queue_id/
--      campaign_id/tenant_id konczy sie wczesnym RETURN, wiec kontakt klienta z
--      is_deleted = TRUE moze byc edytowany bez wyjatku).
--   6. Idempotencja: KAZDA tabela oprocz customer ma w WHERE dodatkowy warunek "czy
--      jeszcze jest cos do zanonimizowania" (test na literal 'ANONYMIZED'/pusta
--      wartosc/status operacyjny) -- bez tego drugie wywolanie ZNOWU dopasowaloby
--      rekordy powiazane kluczem obcym (customer_id/last_contact_id/most przez
--      campaign_contact_record_id sie NIE zmienia) i UPDATE zglosilby te same wiersze
--      jako "zmienione" (ROW_COUNT liczy dopasowane wiersze niezaleznie od tego, czy SET
--      faktycznie zmienia wartosc), lamiac AC "drugie wywolanie = zerowe liczniki".
--      customer.* jest WYJATKIEM -- bezwarunkowo dosanityzowywany (patrz pkt 8), licznik
--      "customer" w wyniku jest zawsze 1 (potwierdzenie przetworzenia, NIE delta).
--      DELETE (transkrypcje/podsumowania) sa idempotentne z natury (drugi DELETE po
--      usunietych wierszach = 0, bez dodatkowego warunku). s3_keys sa rowniez naturalnie
--      idempotentne (po pierwszym wywolaniu recording_url/attachments sa juz puste/NULL,
--      wiec zapytanie zbierajace klucze zwraca pusty zbior samo z siebie).
--   7. Wynik JSONB: {dry_run, counts: {customer, contact, scheduled_callback,
--      campaign_contact, campaign_contact_archive, contact_transcription,
--      contact_ai_summary, email_message, social_message, contacts_dw}, matched_by_link,
--      matched_by_identifier, s3_keys}. matched_by_link/matched_by_identifier to
--      ROZMIAR ZBIORU PODMIOTU (fn_customer_subject_ids), NIE delta faktycznie
--      zmienionych wierszy -- swiadoma decyzja: to samo pole w export_customer_data
--      (V095) ma DOKLADNIE te sama definicje (ta sama funkcja pomocnicza, ten sam
--      predykat), co pozwala na test spojnosci "podglad DB-062 == eksport DB-061" na
--      tym samym fixture bez rozjazdu semantyki.
--   8. customer: UWARUNKOWANY brak filtra "juz zanonimizowany" jest CELOWY (inaczej niz
--      pozostale tabele, patrz pkt 6) -- dosanityzowanie klienta zanonimizowanego dawniej
--      WYLACZNIE sciezka Javy (CustomerRepository#anonymize: first_name/last_name/phone/
--      email/is_deleted, ale BEZ custom_fields/gdpr_consent/external_id, DB-060 F8) musi
--      poprawic te trzy pola NIEZALEZNIE od aktualnego is_deleted -- warunek
--      "WHERE is_deleted = FALSE" zablokowalby wlasnie ten przypadek dosanityzowania.
--   9. s3_keys: zebrane PRZED jakimkolwiek wyzerowaniem, w OBU ksztaltach zalacznikow
--      e-mail/social (att->>'s3_key', z obrona jsonb_typeof(att->'s3_key') = 'string' --
--      DB061-06, nieobecna w V095, naprawiona tutaj w nowym kodzie) oraz
--      contact.recording_url (.mp3 i .eml).
--  10. RLS pod app_user (AC R1, DB-060 uzupelnienie + sprostowanie DB061-01): funkcja
--      NIE jest SECURITY DEFINER (dziala z uprawnieniami/RLS wywolujacego, jak
--      fn_customer_subject_ids). Zapis pod app_user (bez BYPASSRLS): customer ma
--      polityke UPDATE (dziala), scheduled_callback ma polityke ALL bez FORCE (dziala --
--      brak FORCE nie ma znaczenia dla roli innej niz wlasciciel tabeli), campaign_contact/
--      _archive/contacts_dw nie maja RLS w ogole (dzialaja, izolacja przez jawny
--      tenant_id = p_tenant_id w kazdym zapytaniu), contact_transcription/
--      contact_ai_summary maja polityke ALL + FORCE (dziala). contact ma WYLACZNIE
--      polityki SELECT/INSERT (brak UPDATE) -- UPDATE contact pod app_user dopasuje
--      ZERO wierszy CICHO (bez bledu, PostgreSQL: brak stosowalnej polityki dla komendy
--      = domyslna odmowa dla tej komendy, widoczna jako WHERE zawsze false, NIE blad).
--      email_message/social_message/audit_log maja WYLACZNIE polityke SELECT -- UPDATE
--      na pierwszych dwoch pod app_user rowniez CICHO dopasowuje zero wierszy (jak
--      contact), ale INSERT do audit_log pod app_user KONCZY SIE BLEDEM (nie cisza) --
--      "new row violates row-level security policy for table audit_log", bo dla
--      INSERT bez zadnej stosowalnej polityki (WITH CHECK) PostgreSQL odrzuca caly
--      wiersz, nie filtruje go po cichu jak przy SELECT/UPDATE/DELETE. W produkcji
--      backend laczy sie jako properien z BYPASSRLS (potwierdzone w pamieci agenta),
--      wiec zaden z powyzszych przypadkow nie wystepuje na zywo -- test pod app_user w
--      tej migracji ma na celu WYLACZNIE udokumentowanie/wykrycie tych ograniczen
--      (liczniki w JSONB ujawniaja niepelna anonimizacje), a NIE uczynienie funkcji
--      w pelni dzialajaca pod rola ograniczona. SECURITY DEFINER swiadomie odrzucony:
--      zmienilby model bezpieczenstwa (funkcja dzialalaby z uprawnieniami wlasciciela
--      niezaleznie od wywolujacego), co jest wieksza zmiana niz zakres tego tickieta.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. Usuniecie starej sygnatury (RETURNS VOID) -- zmiana typu zwracanego
--    wymaga DROP, CREATE OR REPLACE tego nie obsluguje. Zero wolajacych w Javie
--    (potwierdzone grepem: backend/, frontend/, voicebot/ -- DESIGN U2, DB-060).
-- ---------------------------------------------------------------------------

DROP FUNCTION IF EXISTS anonymize_customer(UUID, UUID, UUID);

-- ---------------------------------------------------------------------------
-- 1. Nowa funkcja anonymize_customer -- RETURNS JSONB, p_dry_run
-- ---------------------------------------------------------------------------

CREATE FUNCTION anonymize_customer(
    p_customer_id UUID,
    p_tenant_id   UUID,
    p_user_id     UUID DEFAULT NULL,
    p_dry_run     BOOLEAN DEFAULT FALSE
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    v_entity_types TEXT[];
    v_entity_ids   UUID[];
    v_matched_link       BIGINT;
    v_matched_identifier BIGINT;

    v_s3_keys JSONB;

    v_n_customer               INT := 0;
    v_n_contact                INT := 0;
    v_n_callback               INT := 0;
    v_n_campaign_contact       INT := 0;
    v_n_campaign_contact_arch  INT := 0;
    v_n_transcription          INT := 0;
    v_n_ai_summary             INT := 0;
    v_n_email                  INT := 0;
    v_n_social                 INT := 0;
    v_n_contacts_dw            INT := 0;

    v_has_contacts_dw_remote_address BOOLEAN;
    v_old_value JSONB;
    v_counts    JSONB;
BEGIN
    -- ------------------------------------------------------------------
    -- Guard (code review CR-DATABASE.md DB062-01, blocker): DEFAULT FALSE
    -- chroni wylacznie POMINIETY argument, nie jawny SQL NULL -- "IF NULL
    -- THEN" w PL/pgSQL zachowuje sie jak "IF FALSE" (blok pomijany), wiec
    -- wywolanie z p_dry_run = NULL przelecialoby do rzeczywistej,
    -- nieodwracalnej mutacji zamiast bezpiecznego podgladu. Zweryfikowane
    -- dzialaniem w recenzji: SELECT anonymize_customer(id, tenant, NULL,
    -- NULL::boolean) anonimizowalo klienta i zwrocilo "dry_run": false, bez
    -- bledu. Ten sam blad klasy co guard DB061-08 -- glosne odrzucenie
    -- zamiast cichego blednego zachowania.
    -- ------------------------------------------------------------------
    IF p_dry_run IS NULL THEN
        RAISE EXCEPTION
            'anonymize_customer: p_dry_run nie moze byc NULL (uzyj TRUE albo FALSE jawnie) -- '
            'NULL bylby po cichu traktowany jak FALSE przez PL/pgSQL i wykonalby rzeczywista, '
            'nieodwracalna anonimizacje zamiast bezpiecznego podgladu (DB062-01).';
    END IF;

    -- ------------------------------------------------------------------
    -- Zbior podmiotu (D9 = A) -- RAZ, przed jakakolwiek mutacja. Istnienie
    -- klienta w tenancie jest walidowane WEWNATRZ fn_customer_subject_ids
    -- (bez filtra is_deleted -- pozwala dosanityzowac klienta juz
    -- zanonimizowanego, patrz pkt 8 naglowka).
    -- ------------------------------------------------------------------
    SELECT array_agg(entity_type), array_agg(entity_id),
           count(*) FILTER (WHERE matched_by = 'link'),
           count(*) FILTER (WHERE matched_by = 'identifier')
    INTO v_entity_types, v_entity_ids, v_matched_link, v_matched_identifier
    FROM fn_customer_subject_ids(p_customer_id, p_tenant_id);

    -- ------------------------------------------------------------------
    -- Guard DB061-08: kolizja record_id miedzy kampaniami tego samego
    -- tenanta -- wykryta PRZED jakakolwiek mutacja, w OBU trybach (dry-run
    -- tez ma prawo ostrzec zamiast pokazac mylacy podglad).
    -- ------------------------------------------------------------------
    IF EXISTS (
        SELECT 1
        FROM campaign_contact cc
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CAMPAIGN_CONTACT' AND subj.eid = cc.record_id
        WHERE cc.tenant_id = p_tenant_id
        GROUP BY cc.record_id
        HAVING count(DISTINCT cc.campaign_id) > 1
    ) THEN
        RAISE EXCEPTION
            'anonymize_customer: DB061-08 -- wiecej niz jedna kampania tenanta % dzieli ten sam record_id campaign_contact dla klienta % -- wymagana reczna interwencja, zero zmian wykonano.',
            p_tenant_id, p_customer_id;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM campaign_contact_archive cca
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CAMPAIGN_CONTACT_ARCHIVE' AND subj.eid = cca.record_id
        WHERE cca.tenant_id = p_tenant_id
        GROUP BY cca.record_id
        HAVING count(DISTINCT cca.campaign_id) > 1
    ) THEN
        RAISE EXCEPTION
            'anonymize_customer: DB061-08 -- wiecej niz jedna kampania tenanta % dzieli ten sam record_id campaign_contact_archive dla klienta % -- wymagana reczna interwencja, zero zmian wykonano.',
            p_tenant_id, p_customer_id;
    END IF;

    -- ------------------------------------------------------------------
    -- contacts_dw.remote_address istnieje dzis -- guard na przyszlosc
    -- (DB-078 planuje usuniecie kolumny). Statyczne SQL w galezi IF nizej
    -- jest planowane leniwie przez PL/pgSQL dopiero przy wykonaniu tej
    -- galezi -- gdy guard jest FALSE, ta galaz nigdy nie jest osiagana,
    -- wiec brakujaca kolumna po DB-078 nie zepsuje tej funkcji.
    -- ------------------------------------------------------------------
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'contacts_dw' AND column_name = 'remote_address'
    ) INTO v_has_contacts_dw_remote_address;

    -- ------------------------------------------------------------------
    -- Klucze S3 -- zebrane PRZED jakimkolwiek wyzerowaniem, wspolne dla
    -- obu trybow. Naturalnie idempotentne: po pierwszym wywolaniu
    -- recording_url jest NULL i attachments = '[]', wiec drugie wywolanie
    -- zwraca pusty zbior samo z siebie (bez dodatkowego warunku).
    -- ------------------------------------------------------------------
    SELECT COALESCE(jsonb_agg(DISTINCT key ORDER BY key), '[]'::jsonb)
    INTO v_s3_keys
    FROM (
        SELECT c.recording_url AS key
        FROM contact c
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CONTACT' AND subj.eid = c.contact_id
        WHERE c.tenant_id = p_tenant_id
          AND c.recording_url IS NOT NULL AND c.recording_url <> ''
        UNION ALL
        SELECT (att->>'s3_key') AS key
        FROM email_message em
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'EMAIL_MESSAGE' AND subj.eid = em.message_id,
             jsonb_array_elements(em.attachments) att
        WHERE em.tenant_id = p_tenant_id
          AND jsonb_typeof(att->'s3_key') = 'string' AND (att->>'s3_key') <> ''
        UNION ALL
        SELECT (att->>'s3_key') AS key
        FROM social_message sm
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'SOCIAL_MESSAGE' AND subj.eid = sm.message_id,
             jsonb_array_elements(sm.attachments) att
        WHERE sm.tenant_id = p_tenant_id
          AND jsonb_typeof(att->'s3_key') = 'string' AND (att->>'s3_key') <> ''
    ) keys;

    -- ==================================================================
    -- TRYB PODGLADU: liczy DOKLADNIE te same predykaty co mutacje nizej,
    -- ale nie zmienia zadnego wiersza i nie zapisuje audytu.
    -- ==================================================================
    IF p_dry_run THEN
        SELECT count(*) INTO v_n_contact
        FROM contact c
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CONTACT' AND subj.eid = c.contact_id
        WHERE c.tenant_id = p_tenant_id
          AND (c.remote_address IS NOT NULL OR c.notes IS NOT NULL
               OR c.recording_url IS NOT NULL OR c.channel_metadata <> '{}'::jsonb);

        SELECT count(*) INTO v_n_callback
        FROM scheduled_callback sb
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'SCHEDULED_CALLBACK' AND subj.eid = sb.callback_id
        WHERE sb.tenant_id = p_tenant_id
          AND (sb.phone <> 'ANONYMIZED' OR sb.first_name IS DISTINCT FROM 'ANONYMIZED'
               OR sb.last_name IS DISTINCT FROM 'ANONYMIZED' OR sb.notes IS NOT NULL
               OR sb.status IN ('PENDING', 'PROCESSING'));

        SELECT count(*) INTO v_n_campaign_contact
        FROM campaign_contact cc
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CAMPAIGN_CONTACT' AND subj.eid = cc.record_id
        WHERE cc.tenant_id = p_tenant_id
          AND (cc.phone IS NOT NULL OR cc.first_name IS DISTINCT FROM 'ANONYMIZED'
               OR cc.last_name IS DISTINCT FROM 'ANONYMIZED' OR cc.email IS NOT NULL
               OR cc.custom_fields <> '{}'::jsonb OR cc.status IN ('PENDING', 'NO_ANSWER', 'CALLBACK'));

        SELECT count(*) INTO v_n_campaign_contact_arch
        FROM campaign_contact_archive cca
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CAMPAIGN_CONTACT_ARCHIVE' AND subj.eid = cca.record_id
        WHERE cca.tenant_id = p_tenant_id
          AND (cca.phone IS NOT NULL OR cca.first_name IS DISTINCT FROM 'ANONYMIZED'
               OR cca.last_name IS DISTINCT FROM 'ANONYMIZED' OR cca.email IS NOT NULL
               OR cca.custom_fields <> '{}'::jsonb);

        SELECT count(*) INTO v_n_transcription
        FROM contact_transcription t
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CONTACT' AND subj.eid = t.contact_id
        WHERE t.tenant_id = p_tenant_id;

        SELECT count(*) INTO v_n_ai_summary
        FROM contact_ai_summary a
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'CONTACT' AND subj.eid = a.contact_id
        WHERE a.tenant_id = p_tenant_id;

        SELECT count(*) INTO v_n_email
        FROM email_message em
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'EMAIL_MESSAGE' AND subj.eid = em.message_id
        WHERE em.tenant_id = p_tenant_id
          AND (em.from_address <> 'anonymized@example.com' OR em.to_address <> 'anonymized@example.com'
               OR em.cc_address IS NOT NULL OR em.bcc_address IS NOT NULL
               OR em.subject IS DISTINCT FROM '[ANONYMIZED]' OR em.body_html IS NOT NULL
               OR em.body_text IS DISTINCT FROM '[Dane osobowe usuniete zgodnie z RODO Art. 17]'
               OR em.attachments <> '[]'::jsonb);

        SELECT count(*) INTO v_n_social
        FROM social_message sm
        JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
          ON subj.et = 'SOCIAL_MESSAGE' AND subj.eid = sm.message_id
        WHERE sm.tenant_id = p_tenant_id
          AND (sm.content IS DISTINCT FROM '[Dane osobowe usuniete zgodnie z RODO Art. 17]'
               OR sm.attachments <> '[]'::jsonb OR sm.sender_external_id IS DISTINCT FROM 'ANONYMIZED');

        IF v_has_contacts_dw_remote_address THEN
            SELECT count(*) INTO v_n_contacts_dw
            FROM contacts_dw dw
            JOIN (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
              ON subj.et = 'CONTACT' AND subj.eid = dw.contact_id
            WHERE dw.tenant_id = p_tenant_id AND dw.remote_address IS NOT NULL;
        END IF;

        v_counts := jsonb_build_object(
            'customer', 1,
            'contact', v_n_contact,
            'scheduled_callback', v_n_callback,
            'campaign_contact', v_n_campaign_contact,
            'campaign_contact_archive', v_n_campaign_contact_arch,
            'contact_transcription', v_n_transcription,
            'contact_ai_summary', v_n_ai_summary,
            'email_message', v_n_email,
            'social_message', v_n_social,
            'contacts_dw', v_n_contacts_dw
        );

        RETURN jsonb_build_object(
            'dry_run', TRUE,
            'counts', v_counts,
            'matched_by_link', v_matched_link,
            'matched_by_identifier', v_matched_identifier,
            's3_keys', v_s3_keys
        );
    END IF;

    -- ==================================================================
    -- TRYB RZECZYWISTY: mutacje. Kolejnosc: contact i pochodne PRZED
    -- customer (pkt 5 naglowka). Kazdy warunek WHERE ma dwie czesci:
    -- dopasowanie do zbioru podmiotu (subj) ORAZ "jest jeszcze co
    -- zanonimizowac" (idempotencja, pkt 6 naglowka).
    -- ==================================================================

    -- 1) CONTACT: notes, recording_url (klucze juz zebrane), remote_address,
    --    channel_metadata.
    UPDATE contact c SET
        remote_address   = NULL,
        channel_metadata = '{}'::jsonb,
        notes            = NULL,
        recording_url    = NULL,
        updated_at       = NOW()
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'CONTACT' AND subj.eid = c.contact_id AND c.tenant_id = p_tenant_id
      AND (c.remote_address IS NOT NULL OR c.notes IS NOT NULL
           OR c.recording_url IS NOT NULL OR c.channel_metadata <> '{}'::jsonb);
    GET DIAGNOSTICS v_n_contact = ROW_COUNT;

    -- 2) SCHEDULED_CALLBACK: phone/imie/nazwisko -> 'ANONYMIZED', notes -> NULL,
    --    PENDING/PROCESSING -> CANCELLED (inaczej ScheduledCallbackExecutor
    --    wybralby "numer" ANONYMIZED).
    UPDATE scheduled_callback sb SET
        phone      = 'ANONYMIZED',
        first_name = 'ANONYMIZED',
        last_name  = 'ANONYMIZED',
        notes      = NULL,
        status     = CASE WHEN sb.status IN ('PENDING', 'PROCESSING') THEN 'CANCELLED' ELSE sb.status END,
        updated_at = NOW()
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'SCHEDULED_CALLBACK' AND subj.eid = sb.callback_id AND sb.tenant_id = p_tenant_id
      AND (sb.phone <> 'ANONYMIZED' OR sb.first_name IS DISTINCT FROM 'ANONYMIZED'
           OR sb.last_name IS DISTINCT FROM 'ANONYMIZED' OR sb.notes IS NOT NULL
           OR sb.status IN ('PENDING', 'PROCESSING'));
    GET DIAGNOSTICS v_n_callback = ROW_COUNT;

    -- 3) CAMPAIGN_CONTACT: phone -> NULL (indeks czesciowy idx_campaign_contact_
    --    phone_unique (campaign_id, phone) WHERE phone IS NOT NULL nie obejmuje
    --    NULL -- placeholder tekstowy zlamalby unikalnosc przy 2+ rekordach tej
    --    samej kampanii), imie/nazwisko -> 'ANONYMIZED', email -> NULL,
    --    custom_fields -> '{}', PENDING/NO_ANSWER/CALLBACK -> SKIPPED,
    --    next_attempt_at -> NULL (fetchNextPendingContact filtruje status IN
    --    ('PENDING','NO_ANSWER') bez warunku phone IS NOT NULL).
    UPDATE campaign_contact cc SET
        phone           = NULL,
        first_name      = 'ANONYMIZED',
        last_name       = 'ANONYMIZED',
        email           = NULL,
        custom_fields   = '{}'::jsonb,
        status          = CASE WHEN cc.status IN ('PENDING', 'NO_ANSWER', 'CALLBACK') THEN 'SKIPPED' ELSE cc.status END,
        next_attempt_at = CASE WHEN cc.status IN ('PENDING', 'NO_ANSWER', 'CALLBACK') THEN NULL ELSE cc.next_attempt_at END,
        updated_at      = NOW()
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'CAMPAIGN_CONTACT' AND subj.eid = cc.record_id AND cc.tenant_id = p_tenant_id
      AND (cc.phone IS NOT NULL OR cc.first_name IS DISTINCT FROM 'ANONYMIZED'
           OR cc.last_name IS DISTINCT FROM 'ANONYMIZED' OR cc.email IS NOT NULL
           OR cc.custom_fields <> '{}'::jsonb OR cc.status IN ('PENDING', 'NO_ANSWER', 'CALLBACK'));
    GET DIAGNOSTICS v_n_campaign_contact = ROW_COUNT;

    -- 4) CAMPAIGN_CONTACT_ARCHIVE: jak wyzej, bez next_attempt_at/status
    --    operacyjny -- archiwum nie ma next_attempt_at (V015), a status jest juz
    --    finalny w chwili archiwizacji (nie jest odczytywany przez zaden
    --    aktywny harmonogram/dialer -- DESIGN U11).
    UPDATE campaign_contact_archive cca SET
        phone         = NULL,
        first_name    = 'ANONYMIZED',
        last_name     = 'ANONYMIZED',
        email         = NULL,
        custom_fields = '{}'::jsonb,
        updated_at    = NOW()
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'CAMPAIGN_CONTACT_ARCHIVE' AND subj.eid = cca.record_id AND cca.tenant_id = p_tenant_id
      AND (cca.phone IS NOT NULL OR cca.first_name IS DISTINCT FROM 'ANONYMIZED'
           OR cca.last_name IS DISTINCT FROM 'ANONYMIZED' OR cca.email IS NOT NULL
           OR cca.custom_fields <> '{}'::jsonb);
    GET DIAGNOSTICS v_n_campaign_contact_arch = ROW_COUNT;

    -- 5) Transkrypcje i podsumowania AI (D3 = A -- traktowane jako PII, tresc
    --    rozmowy/e-maila). DELETE po contact_id, tabele partycjonowane --
    --    predykat contact_id dziala poprawnie na tabeli partycjonowanej (planner
    --    odpyta indeksy poszczegolnych partycji), bez ctid. Naturalnie
    --    idempotentne (drugi DELETE po usunietych wierszach = 0).
    DELETE FROM contact_transcription t
    USING (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'CONTACT' AND subj.eid = t.contact_id AND t.tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_n_transcription = ROW_COUNT;

    DELETE FROM contact_ai_summary a
    USING (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'CONTACT' AND subj.eid = a.contact_id AND a.tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_n_ai_summary = ROW_COUNT;

    -- 6) EMAIL_MESSAGE: jak V013 + cc_address/bcc_address (pomijane przez V013).
    --    message_id_header NIE jest czyszczony (klucz dedup IMAP, wzorzec V013).
    UPDATE email_message em SET
        from_address = 'anonymized@example.com',
        to_address   = 'anonymized@example.com',
        cc_address   = NULL,
        bcc_address  = NULL,
        subject      = '[ANONYMIZED]',
        body_html    = NULL,
        body_text    = '[Dane osobowe usuniete zgodnie z RODO Art. 17]',
        attachments  = '[]'::jsonb
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'EMAIL_MESSAGE' AND subj.eid = em.message_id AND em.tenant_id = p_tenant_id
      AND (em.from_address <> 'anonymized@example.com' OR em.to_address <> 'anonymized@example.com'
           OR em.cc_address IS NOT NULL OR em.bcc_address IS NOT NULL
           OR em.subject IS DISTINCT FROM '[ANONYMIZED]' OR em.body_html IS NOT NULL
           OR em.body_text IS DISTINCT FROM '[Dane osobowe usuniete zgodnie z RODO Art. 17]'
           OR em.attachments <> '[]'::jsonb);
    GET DIAGNOSTICS v_n_email = ROW_COUNT;

    -- 7) SOCIAL_MESSAGE: jak V013 (bez zmian zakresu -- juz kompletne tam).
    UPDATE social_message sm SET
        content            = '[Dane osobowe usuniete zgodnie z RODO Art. 17]',
        attachments        = '[]'::jsonb,
        sender_external_id = 'ANONYMIZED'
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
    WHERE subj.et = 'SOCIAL_MESSAGE' AND subj.eid = sm.message_id AND sm.tenant_id = p_tenant_id
      AND (sm.content IS DISTINCT FROM '[Dane osobowe usuniete zgodnie z RODO Art. 17]'
           OR sm.attachments <> '[]'::jsonb OR sm.sender_external_id IS DISTINCT FROM 'ANONYMIZED');
    GET DIAGNOSTICS v_n_social = ROW_COUNT;

    -- 8) contacts_dw.remote_address -- guard istnienia kolumny (DB-078).
    IF v_has_contacts_dw_remote_address THEN
        UPDATE contacts_dw dw SET remote_address = NULL
        FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) subj
        WHERE subj.et = 'CONTACT' AND subj.eid = dw.contact_id AND dw.tenant_id = p_tenant_id
          AND dw.remote_address IS NOT NULL;
        GET DIAGNOSTICS v_n_contacts_dw = ROW_COUNT;
    END IF;

    -- 9) CUSTOMER -- OSTATNIA instrukcja mutujaca (pkt 5 naglowka), zawsze
    --    bezwarunkowo dosanityzowywana (pkt 8 naglowka: brak filtra
    --    "is_deleted = FALSE", zeby poprawic custom_fields/gdpr_consent/
    --    external_id nawet u klienta zanonimizowanego wczesniej tylko przez
    --    Jave). Stary stan do audytu -- bez PII, tylko metadane (wzorzec V013).
    SELECT jsonb_build_object(
        'customer_id', customer_id,
        'tenant_id',   tenant_id,
        'had_phone',   (jsonb_array_length(phone) > 0),
        'had_email',   (jsonb_array_length(email) > 0),
        'was_already_deleted', is_deleted,
        'source',      source,
        'created_at',  created_at
    )
    INTO v_old_value
    FROM customer
    WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id;

    UPDATE customer SET
        first_name    = 'ANONYMIZED',
        last_name     = 'ANONYMIZED',
        phone         = '[]'::jsonb,
        email         = '[]'::jsonb,
        external_id   = NULL,
        custom_fields = '{}'::jsonb,
        gdpr_consent  = '{"consent_given": false, "marketing_consent": false, "anonymized": true}'::jsonb,
        is_deleted    = TRUE,
        updated_at    = NOW()
    WHERE customer_id = p_customer_id AND tenant_id = p_tenant_id;
    GET DIAGNOSTICS v_n_customer = ROW_COUNT;

    v_counts := jsonb_build_object(
        'customer', v_n_customer,
        'contact', v_n_contact,
        'scheduled_callback', v_n_callback,
        'campaign_contact', v_n_campaign_contact,
        'campaign_contact_archive', v_n_campaign_contact_arch,
        'contact_transcription', v_n_transcription,
        'contact_ai_summary', v_n_ai_summary,
        'email_message', v_n_email,
        'social_message', v_n_social,
        'contacts_dw', v_n_contacts_dw
    );

    -- Audit log: tylko gdy NIE dry-run (juz zagwarantowane przez wczesny RETURN
    -- powyzej), bez PII w old_value/new_value (wzorzec V013).
    INSERT INTO audit_log (
        tenant_id, user_id, action, entity_type, entity_id, old_value, new_value, created_at
    ) VALUES (
        p_tenant_id,
        p_user_id,
        'CUSTOMER_ANONYMIZED',
        'CUSTOMER',
        p_customer_id,
        v_old_value,
        jsonb_build_object(
            'customer_id',    p_customer_id,
            'anonymized_at',  NOW(),
            'legal_basis',    'GDPR_ART_17',
            'counts',         v_counts,
            'matched_by_link',       v_matched_link,
            'matched_by_identifier', v_matched_identifier
        ),
        NOW()
    );

    RETURN jsonb_build_object(
        'dry_run', FALSE,
        'counts', v_counts,
        'matched_by_link', v_matched_link,
        'matched_by_identifier', v_matched_identifier,
        's3_keys', v_s3_keys
    );

EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'Blad anonimizacji klienta %: %', p_customer_id, SQLERRM;
END;
$$;

COMMENT ON FUNCTION anonymize_customer(UUID, UUID, UUID, BOOLEAN) IS
    'Anonimizacja danych osobowych klienta zgodnie z RODO Art. 17 (DB-062, rozszerzenie V013). '
    'Zbior podmiotu = fn_customer_subject_ids (D9 = A, V095, reuzywana bez zmian) -- '
    'link (powiazanie kluczem obcym) + identifier (znormalizowany telefon/e-mail klienta), '
    'ten sam zbior co export_customer_data (spojnosc podgladu Art. 15/17). Obejmuje: customer '
    '(+ external_id, gdpr_consent, custom_fields), contact (remote_address, notes, recording_url, '
    'channel_metadata), scheduled_callback (phone/imie/nazwisko/notes, PENDING/PROCESSING -> '
    'CANCELLED), campaign_contact + campaign_contact_archive (phone/imie/nazwisko/email/'
    'custom_fields, PENDING/NO_ANSWER/CALLBACK -> SKIPPED w campaign_contact), '
    'contact_transcription + contact_ai_summary (DELETE, D3 = A), email_message (+ cc_address/'
    'bcc_address, pomijane przez V013), social_message, contacts_dw.remote_address (guard '
    'istnienia kolumny -- znika w DB-078). RETURNS JSONB: {dry_run, counts: {<tabela>: n, ...}, '
    'matched_by_link, matched_by_identifier, s3_keys}. p_dry_run = TRUE liczy bez modyfikacji '
    'i bez wpisu audytowego; p_dry_run = NULL jest ODRZUCANE (RAISE EXCEPTION, DB062-01) -- '
    '"IF NULL THEN" w PL/pgSQL zachowuje sie jak FALSE, wiec bez tego guardu jawny SQL NULL '
    '(np. boxed Boolean = null w Javie przez JDBC) wykonalby rzeczywista anonimizacje zamiast '
    'bezpiecznego podgladu. Idempotentna: kazda tabela oprocz customer ma warunek "jeszcze nie '
    'zanonimizowane", wiec drugie wywolanie zwraca liczniki 0 (customer = 1 zawsze, zawsze '
    'dosanityzowany bezwarunkowo). Kolejnosc: contact i pochodne PRZED customer.is_deleted = TRUE '
    '(obrona w glab, dziala dzieki V094/DB-079 niezaleznie od kolejnosci). Guard DB061-08: '
    'RAISE EXCEPTION przy wykrytej kolizji record_id miedzy kampaniami tego samego tenanta '
    '(campaign_contact/campaign_contact_archive), zamiast ryzykowac dotkniecie cudzego rekordu. '
    'Nie jest SECURITY DEFINER -- dziala z uprawnieniami/RLS wywolujacego.';
