-- =============================================================================
-- V098__mask_audit_log_pii_on_anonymize_customer.sql
-- BE-142 (Zakres p.2): Maskowanie kluczy PII w migawkach `audit_log` PODMIOTU przy
-- anonimizacji klienta (RODO Art. 17). D10 potwierdzone formalnie 2026-09-30
-- (DESIGN-message-retention-and-partitioning.md Sec.3, blok D10): wiersz audytowy
-- ZOSTAJE (rozliczalnosc Art. 5(2)/30, wyjatek Art. 17(3)(b)/(e)) -- maskowane sa
-- WYLACZNIE wartosci wybranych kluczy PII, klucz JSONB zostaje (widac, ze pole
-- istnialo, bez ujawniania jego tresci).
--
-- Migracja: Flyway V098
-- Numeracja: V097 (DB-059) jest najwyzsza wersja -- sprawdzone `ls | sort -V | tail`
--            w katalogu migracji ORAZ flyway_schema_history zywej bazy contact_center
--            (konczy sie na V097, tylko-do-odczytu) ORAZ `git ls-tree` na wszystkich
--            galeziach lokalnych (appmod/java-upgrade-20260812100157,
--            chore/epic-30-zadania, develop, main, partycjonowanie-2) i zdalnych
--            (origin/develop, origin/feature-socialmedia,
--            origin/fix/drop-duplicate-contact-indexes, origin/main,
--            origin/release-please--branches--main) -- V098 nigdzie niezajete.
-- Zaleznosci: V096 (anonymize_customer RETURNS JSONB, DB-062 -- ROZSZERZANA tutaj
--             przez CREATE OR REPLACE, sygnatura BEZ ZMIAN wiec DROP nie jest
--             potrzebny), V012 (audit_log, polityka RLS `pol_audit_log_select`,
--             WYLACZNIE FOR SELECT)
-- Blokuje: brak (BE-142 Zakres p.1/p.4 -- Java, AuditAspect/GdprServiceImpl testy --
--          realizuje backend-dev-expert PO tej migracji, patrz sekcja "DLA
--          BACKEND-DEV-EXPERT" nizej)
--
-- KONTEKST (BE-142, `audit_log` 1227 wierszy live, DB-060 F7): CUSTOMER_CREATED (1)
-- i CUSTOMER_UPDATED (7) niosa pelne snapshoty firstName/lastName/phone[]/email[]/
-- customFields/gdprConsent/externalId w new_value i old_value; CONTACT_CREATED (263)/
-- AGENT_ASSIGNED (363)/DISPOSITION_SET (400)/ACCEPTED (28)/ABANDONED (14) niosa
-- remoteAddress/channelMetadata (zawiera zagniezdzone fromAddress/subject dla kanalu
-- EMAIL)/notes. RECORDING_URL_REQUESTED (presignedUrl, TTL 15 min) i inne encje
-- (TENANT/USER/QUEUE/EMAIL_TEMPLATE) SA POZA ZAKRESEM tej migracji (BE-142 Zakres p.2:
-- wylacznie CUSTOMER i CONTACT).
--
-- ROZWIAZANIE -- DWIE nowe funkcje + rozszerzenie anonymize_customer:
--
--   1. fn_mask_pii_jsonb_value(p_value JSONB) RETURNS JSONB -- czysty helper. Lista
--      kluczy PII (IDENTYCZNA z ta, ktora Java skopiuje 1:1 w BE-142 Zakres p.1,
--      AuditPiiKeys): firstName, lastName, phone, email, customFields, gdprConsent,
--      externalId, remoteAddress, channelMetadata, notes, recordingUrl, fromAddress,
--      subject. Dla kazdego klucza Z TEJ LISTY, ktory ISTNIEJE w obiekcie (operator
--      `?`, nie zaklada obecnosci), wartosc jest zastepowana literalem "[MASKED]"
--      (jsonb_set z create_missing = FALSE -- NIGDY nie dodaje klucza, ktorego nie
--      bylo, co potwierdzone dzialaniem: jsonb_set('{"b":2}', '{a}', ..., false) =
--      '{"b":2}' bez zmian). fromAddress/subject w danych CONTACT wystepuja DZIS
--      wylacznie ZAGNIEZDZONE wewnatrz channelMetadata (nie jako klucze najwyzszego
--      poziomu) -- maskowanie calego channelMetadata juz je pokrywa; sa w liscie dla
--      SYMETRII z lista Javy (ktora moze w przyszlosci audytowac encje z tymi polami
--      na najwyzszym poziomie) i dla przyszlych encji, NIE dla dzisiejszych danych
--      CONTACT. IMMUTABLE (czysta funkcja wejscie->wyjscie, bez odczytu stanu bazy).
--
--   2. mask_audit_log_pii(p_customer_id, p_tenant_id, p_contact_ids UUID[],
--      p_dry_run BOOLEAN DEFAULT FALSE) RETURNS INT -- UPDATE (albo, dla p_dry_run =
--      TRUE, wylacznie SELECT count(*) tym samym predykatem -- JEDNO miejsce prawdy
--      dla dopasowania, zero ryzyka rozjazdu miedzy trybem podgladu a rzeczywistym)
--      audit_log dla wierszy PODMIOTU. Dopasowanie NIGDY nie polega WYLACZNIE na
--      entity_id -- zawsze rowniez na tresci JSON (new_value/old_value):
--
--      **ODKRYCIE (nieoczywiste, zweryfikowane zapytaniem na zywych danych, NIE
--      zgadywane, wymog BE-142)**: `entity_id` jest BLEDNY dla akcji *_CREATED --
--      niesie `tenant_id`, NIE id wlasnej encji:
--        - CUSTOMER_CREATED: 1/1 wiersz live ma entity_id = new_value->>'tenantId',
--          0/1 ma entity_id = new_value->>'customerId'. CUSTOMER_UPDATED (7/7) ma
--          entity_id POPRAWNY = customerId -- tylko CREATED jest bledny.
--        - CONTACT_CREATED: 263/263 wierszy live ma entity_id = new_value->>
--          'tenantId', 0/263 ma entity_id = new_value->>'contactId'. CONTACT_
--          ABANDONED/ACCEPTED/AGENT_ASSIGNED/DISPOSITION_SET (14+28+363+400 wierszy)
--          maja entity_id POPRAWNY = contactId we WSZYSTKICH wierszach -- tylko
--          CREATED jest bledny.
--      Wzorzec jest spojny miedzy CUSTOMER i CONTACT -- prawdopodobny wspolny blad w
--      AuditAspect (poza zakresem tej migracji -- SQL, nie Java) dla akcji *_CREATED,
--      prawdopodobnie bo encja nie ma jeszcze przypisanego/odczytanego ID w momencie,
--      gdy aspekt przechwytuje wartosc dla CREATED (do potwierdzenia/naprawy przez
--      backend-dev-expert w BE-142 Zakres p.1 -- NIE naprawiane tutaj, poza zakresem
--      SQL). Poleganie WYLACZNIE na entity_id pomineloby WSZYSTKIE wiersze *_CREATED
--      podmiotu (1 CUSTOMER_CREATED + 263 CONTACT_CREATED na danych live) -- dlatego
--      OBA typy encji sa dopasowywane PRZEZ TRESC JSON (new_value/old_value ->>
--      'customerId'/'contactId') jako sciezka GLOWNA, z entity_id jako DODATKOWA,
--      tansza sciezka (poprawna dla pozostalych akcji). Rzutowanie tekstu na uuid
--      jest poprzedzone regex-em kanonicznego ksztaltu UUID (8-4-4-4-12 cyfr/liter szesnastkowych,
--      NAPRAWA CR-BACKEND.md BE142-01: wczesniejszy regex [0-9a-fA-F-]{36} akceptowal tez ciagi
--      36 znakow spoza ksztaltu 8-4-4-4-12, ktore i tak rzucalyby blad ::uuid) --
--      obrona przed ewentualnym zlym formatem w danych historycznych/przyszlych, bez
--      rzucania bledu castowania i przerywania calej anonimizacji.
--      Predykat dopasowania jest POLACZONY z predykatem "maskowanie faktycznie COS
--      zmienia" (fn_mask_pii_jsonb_value(new_value) IS DISTINCT FROM new_value OR
--      analogicznie old_value) -- IDEMPOTENCJA identyczna z wzorcem reszty
--      anonymize_customer (V096, pkt 6 tamtego naglowka): drugie wywolanie zwraca 0,
--      nie ponownie te same wiersze. Guard p_dry_run IS NULL -> RAISE EXCEPTION
--      (wzorzec DB062-01) -- funkcja jest bezposrednio wywolywalna/testowalna w
--      izolacji (nie tylko przez anonymize_customer), wiec zasluguje na ten sam
--      guard co anonymize_customer.
--
--   3. anonymize_customer (CREATE OR REPLACE, sygnatura BEZ ZMIAN -- DROP nie
--      potrzebny) -- DECYZJA (BE-142 Zakres p.2, wybor miedzy "osobna funkcja wolana
--      obok z Javy" a "wbudowana jako kolejny krok"): WBUDOWANA. Kontakty dopasowane
--      WYLACZNIE identyfikatorem (telefon/e-mail klienta) w fn_customer_subject_ids
--      zaleza od customer.phone/customer.email, ktore anonymize_customer ZERUJE w
--      OSTATNIM kroku mutujacym (krok 9, customer) -- gdyby mask_audit_log_pii bylo
--      wolane OSOBNO z Javy PO powrocie z anonymize_customer (osobne wywolanie SQL w
--      tej samej transakcji), musialoby PONOWNIE policzyc zbior CONTACT (np. przez
--      wlasne wywolanie fn_customer_subject_ids) -- a to widzialoby JUZ wyzerowane
--      customer.phone/email i POMINELOBY kontakty dopasowane wylacznie
--      identyfikatorem (dokladnie blad "progressive narrowing" opisany w V096 pkt 3,
--      empirycznie potwierdzony wzorzec w tym repo -- patrz tez pamiec agenta
--      feedback_freeze_subject_set_before_mutation). Jedynym bezpiecznym sposobem na
--      przekazanie ID kontaktow Javie do osobnego wywolania bylby nowy klucz w
--      kontrakcie JSONB anonymize_customer (lista UUID kontaktow) -- wieksza zmiana
--      kontraktu (ryzyko API) niz uzasadnia to ten Could-Have ticket. WBUDOWANIE
--      pozwala reuzyc JUZ zamrozony w kroku 1 zbior (v_entity_types/v_entity_ids,
--      filtrowany do CONTACT ponizej jako v_contact_ids) -- zero ryzyka rozjazdu,
--      zero zmiany kontraktu wywolania z Javy. Krok mask_audit_log_pii jest wolany
--      WYLACZNIE w galezi TRYB RZECZYWISTY (PO wczesnym RETURN dla p_dry_run = TRUE)
--      -- automatycznie respektuje p_dry_run bez dodatkowego IF (ten sam wzorzec co
--      INSERT INTO audit_log ponizej, ktory rowniez jest tylko w tej galezi).
--      Umieszczony PRZED nowym wpisem audytowym CUSTOMER_ANONYMIZED (ten wpis i tak
--      nie zawiera zadnego z kluczy PII z listy -- customer_id/anonymized_at/
--      legal_basis/counts/matched_by_* -- wiec kolejnosc nie ma znaczenia
--      funkcjonalnego, ale jest czytelniejsza: maskujemy STARE wpisy, POTEM dodajemy
--      NOWY). Nowy klucz 'audit_log' w obu galeziach jsonb_build_object('counts', ...)
--      -- W TRYBIE PODGLADU wywoluje TA SAMA funkcja z p_dry_run = TRUE (SELECT
--      count(*), bez mutacji) -- zero duplikacji predykatu dopasowania.
--
-- RLS pod app_user (zweryfikowane DZIALANIEM, NIE analogia/zgadywanie -- zgodnie z
-- wymogiem BE-142): audit_log ma WYLACZNIE polityke `pol_audit_log_select` (FOR
-- SELECT, V012). Test empiryczny (transakcja z ROLLBACK, na scratch danych zywej
-- bazy, SET LOCAL ROLE app_user + SET LOCAL app.current_tenant_id):
--   UPDATE audit_log SET new_value = new_value WHERE tenant_id = ... AND
--   entity_type = 'CUSTOMER';  ==>  "UPDATE 0" (CICHO, BEZ BLEDU).
-- Czyli UPDATE audit_log pod app_user zachowuje sie TAK SAMO jak UPDATE contact/
-- email_message/social_message pod app_user (V096 pkt 10: brak stosowalnej polityki
-- dla UPDATE = domyslna odmowa, widoczna jako WHERE zawsze false, NIE blad) -- W
-- ODROZNIENIU od INSERT INTO audit_log (V096, ta sama tabela), ktore pod app_user
-- KONCZY SIE TWARDYM BLEDEM (brak polityki WITH CHECK dla INSERT). To zamierzony,
-- juz udokumentowany w V096 kontrast SELECT/UPDATE/DELETE (cicho 0) vs INSERT
-- (twardy blad) dla tabel bez stosownej polityki -- teraz potwierdzony DZIALANIEM
-- rowniez dla UPDATE na SAMEJ audit_log (nie tylko przez analogie do innych tabel).
-- Konsekwencja: mask_audit_log_pii pod app_user w trybie rzeczywistym maskowalby 0
-- wierszy CICHO -- ale to i tak bez znaczenia, bo TA SAMA funkcja anonymize_customer
-- w trybie rzeczywistym pod app_user JUZ dzis konczy sie twardym bledem na
-- pozniejszym INSERT INTO audit_log (V096) i cofa CALA transakcje -- nowy krok
-- mask_audit_log_pii nie wprowadza REGRESJI ani NOWEGO ograniczenia (jest "ukryty"
-- za juz istniejacym twardym bledem w tej samej galezi kodu). W trybie podgladu
-- (p_dry_run = TRUE) obie funkcje (anonymize_customer i mask_audit_log_pii) NIGDY
-- nie docieraja do zadnej z tych instrukcji (wczesny RETURN) -- brak rozbieznosci.
-- W produkcji backend laczy sie jako wlasciciel bazy z BYPASSRLS (potwierdzone w
-- pamieci agenta, GdprRepository#anonymize) -- zaden z powyzszych przypadkow nie
-- wystepuje na zywo.
--
-- DLA BACKEND-DEV-EXPERT (BE-142 Zakres p.1/p.4): ZADNA zmiana w GdprRepository/
-- GdprServiceImpl NIE jest potrzebna dla Zakresu p.2 -- mask_audit_log_pii jest
-- wolane WEWNETRZNIE przez anonymize_customer (krok 10 ponizej), wiec kazde
-- istniejace wywolanie `SELECT anonymize_customer(?, ?, ?, ?)::text` z
-- GdprRepository#anonymize JUZ dzis (po zastosowaniu tej migracji) maskuje
-- historyczne wiersze audit_log podmiotu jako efekt uboczny. Kontrakt JSONB zyskuje
-- nowy klucz 'counts.audit_log' (liczba zamaskowanych/do zamaskowania wierszy) --
-- GdprServiceImpl#toPreviewResponse juz iteruje generycznie po wszystkich polach
-- `counts` (Map<String,Integer>), wiec pojawi sie automatycznie bez zmiany kodu.
-- Test spojnosci listy kluczy PII Java<->SQL (BE-142 AC) powinien porownac
-- AuditPiiKeys (Java, Zakres p.1) z lista w komentarzu fn_mask_pii_jsonb_value
-- ponizej -- 13 kluczy, identyczna kolejnosc nie jest wymagana, ale zawartosc TAK.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. fn_mask_pii_jsonb_value -- czysty helper, maskuje WARTOSCI kluczy PII
--    (klucz zostaje) w pojedynczym obiekcie JSONB. NULL/nie-obiekt -> bez zmian.
-- ---------------------------------------------------------------------------

CREATE FUNCTION fn_mask_pii_jsonb_value(p_value JSONB) RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_result JSONB := p_value;
    v_key    TEXT;
BEGIN
    IF p_value IS NULL OR jsonb_typeof(p_value) <> 'object' THEN
        RETURN p_value;
    END IF;

    FOREACH v_key IN ARRAY ARRAY[
        'firstName', 'lastName', 'phone', 'email', 'customFields', 'gdprConsent',
        'externalId', 'remoteAddress', 'channelMetadata', 'notes', 'recordingUrl',
        'fromAddress', 'subject'
    ]
    LOOP
        IF v_result ? v_key THEN
            v_result := jsonb_set(v_result, ARRAY[v_key], '"[MASKED]"'::jsonb, false);
        END IF;
    END LOOP;

    RETURN v_result;
END;
$$;

COMMENT ON FUNCTION fn_mask_pii_jsonb_value(JSONB) IS
    'BE-142/V098: maskuje wartosci kluczy PII (firstName, lastName, phone, email, '
    'customFields, gdprConsent, externalId, remoteAddress, channelMetadata, notes, '
    'recordingUrl, fromAddress, subject) w pojedynczym obiekcie JSONB na "[MASKED]" -- '
    'klucz zostaje (create_missing = false, nigdy nie dodaje klucza ktorego nie bylo). '
    'Lista MUSI byc identyczna z lista Java (AuditPiiKeys, BE-142 Zakres p.1) -- test '
    'spojnosci Java<->SQL w AC BE-142. IMMUTABLE, czysta funkcja, brak dostepu do bazy.';

-- ---------------------------------------------------------------------------
-- 2. mask_audit_log_pii -- maskuje wiersze audit_log PODMIOTU (CUSTOMER + CONTACT).
--    Wolane WYLACZNIE z anonymize_customer (krok 10 ponizej) z JUZ zamrozonym
--    zbiorem p_contact_ids -- patrz naglowek migracji (unikniecie "progressive
--    narrowing"). Rowniez bezposrednio wywolywalna/testowalna w izolacji.
-- ---------------------------------------------------------------------------

CREATE FUNCTION mask_audit_log_pii(
    p_customer_id UUID,
    p_tenant_id   UUID,
    p_contact_ids UUID[],
    p_dry_run     BOOLEAN DEFAULT FALSE
) RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_count INT;
BEGIN
    IF p_dry_run IS NULL THEN
        RAISE EXCEPTION
            'mask_audit_log_pii: p_dry_run nie moze byc NULL (uzyj TRUE albo FALSE '
            'jawnie) -- ten sam blad klasy co DB062-01 w anonymize_customer.';
    END IF;

    IF p_dry_run THEN
        SELECT count(*) INTO v_count
        FROM audit_log a
        WHERE a.tenant_id = p_tenant_id
          AND (
                (a.entity_type = 'CUSTOMER' AND (
                    a.entity_id = p_customer_id
                 OR (a.new_value ? 'customerId'
                     AND (a.new_value->>'customerId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                     AND (a.new_value->>'customerId')::uuid = p_customer_id)
                 OR (a.old_value ? 'customerId'
                     AND (a.old_value->>'customerId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                     AND (a.old_value->>'customerId')::uuid = p_customer_id)
                ))
             OR (a.entity_type = 'CONTACT' AND (
                    a.entity_id = ANY(p_contact_ids)
                 OR (a.new_value ? 'contactId'
                     AND (a.new_value->>'contactId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                     AND (a.new_value->>'contactId')::uuid = ANY(p_contact_ids))
                 OR (a.old_value ? 'contactId'
                     AND (a.old_value->>'contactId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                     AND (a.old_value->>'contactId')::uuid = ANY(p_contact_ids))
                ))
          )
          AND (
                fn_mask_pii_jsonb_value(a.new_value) IS DISTINCT FROM a.new_value
             OR fn_mask_pii_jsonb_value(a.old_value) IS DISTINCT FROM a.old_value
          );
        RETURN v_count;
    END IF;

    UPDATE audit_log a SET
        new_value = fn_mask_pii_jsonb_value(a.new_value),
        old_value = fn_mask_pii_jsonb_value(a.old_value)
    WHERE a.tenant_id = p_tenant_id
      AND (
            (a.entity_type = 'CUSTOMER' AND (
                a.entity_id = p_customer_id
             OR (a.new_value ? 'customerId'
                 AND (a.new_value->>'customerId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                 AND (a.new_value->>'customerId')::uuid = p_customer_id)
             OR (a.old_value ? 'customerId'
                 AND (a.old_value->>'customerId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                 AND (a.old_value->>'customerId')::uuid = p_customer_id)
            ))
         OR (a.entity_type = 'CONTACT' AND (
                a.entity_id = ANY(p_contact_ids)
             OR (a.new_value ? 'contactId'
                 AND (a.new_value->>'contactId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                 AND (a.new_value->>'contactId')::uuid = ANY(p_contact_ids))
             OR (a.old_value ? 'contactId'
                 AND (a.old_value->>'contactId') ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                 AND (a.old_value->>'contactId')::uuid = ANY(p_contact_ids))
            ))
      )
      AND (
            fn_mask_pii_jsonb_value(a.new_value) IS DISTINCT FROM a.new_value
         OR fn_mask_pii_jsonb_value(a.old_value) IS DISTINCT FROM a.old_value
      );
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

COMMENT ON FUNCTION mask_audit_log_pii(UUID, UUID, UUID[], BOOLEAN) IS
    'BE-142/V098 (D10): maskuje (UPDATE) wartosci kluczy PII w new_value/old_value '
    'wierszy audit_log podmiotu -- entity_type=CUSTOMER dopasowany entity_id LUB '
    'new_value/old_value->>''customerId'', entity_type=CONTACT dopasowany entity_id '
    'LUB new_value/old_value->>''contactId'' (entity_id dla akcji *_CREATED -- '
    'CUSTOMER_CREATED i CONTACT_CREATED -- jest BLEDNY na zywych danych: niesie '
    'tenant_id, nie id wlasnej encji -- odkryte i udokumentowane w naglowku tej '
    'migracji, poza zakresem naprawy w SQL). Wiersz zostaje, tylko wartosci kluczy sa '
    'maskowane (fn_mask_pii_jsonb_value). '
    'Idempotentna (drugie wywolanie = 0, warunek "maskowanie faktycznie cos zmienia"). '
    'p_dry_run = TRUE liczy bez mutacji (ten sam predykat, SELECT count zamiast '
    'UPDATE); p_dry_run = NULL jest ODRZUCANE (wzorzec DB062-01). p_contact_ids MUSI '
    'byc zbiorem ZAMROZONYM PRZED jakakolwiek mutacja customer.phone/email (patrz '
    'anonymize_customer, krok 10) -- NIE wywolywac fn_customer_subject_ids ponownie '
    'PO anonymize_customer w tej samej transakcji (progressive narrowing, patrz '
    'naglowek V098).';

-- ---------------------------------------------------------------------------
-- 3. anonymize_customer -- CREATE OR REPLACE, sygnatura bez zmian. Identyczna z
--    V096 + (a) v_contact_ids/v_n_audit_log w DECLARE, (b) wyliczenie v_contact_ids
--    zaraz po zamrozeniu zbioru podmiotu, (c) wolanie mask_audit_log_pii w obu
--    galeziach (podglad i rzeczywista), (d) nowy klucz 'audit_log' w obu v_counts.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION anonymize_customer(
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
    v_contact_ids  UUID[];
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
    v_n_audit_log              INT := 0;

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
    -- zanonimizowanego, patrz pkt 8 naglowka V096).
    -- ------------------------------------------------------------------
    SELECT array_agg(entity_type), array_agg(entity_id),
           count(*) FILTER (WHERE matched_by = 'link'),
           count(*) FILTER (WHERE matched_by = 'identifier')
    INTO v_entity_types, v_entity_ids, v_matched_link, v_matched_identifier
    FROM fn_customer_subject_ids(p_customer_id, p_tenant_id);

    -- BE-142/V098: podzbior CONTACT z JUZ zamrozonego zbioru podmiotu, do uzycia
    -- w mask_audit_log_pii (krok 10) -- NIE wolac fn_customer_subject_ids ponownie
    -- pozniej w tej funkcji (progressive narrowing, patrz naglowek migracji).
    SELECT array_agg(eid) INTO v_contact_ids
    FROM (SELECT unnest(v_entity_types) et, unnest(v_entity_ids) eid) s
    WHERE s.et = 'CONTACT';

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

        -- BE-142/V098: podglad liczby wierszy audit_log, ktore ZOSTALYBY
        -- zamaskowane -- ta sama funkcja, p_dry_run = TRUE (SELECT count, bez mutacji).
        v_n_audit_log := mask_audit_log_pii(p_customer_id, p_tenant_id, v_contact_ids, TRUE);

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
            'contacts_dw', v_n_contacts_dw,
            'audit_log', v_n_audit_log
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
    -- customer (pkt 5 naglowka V096). Kazdy warunek WHERE ma dwie czesci:
    -- dopasowanie do zbioru podmiotu (subj) ORAZ "jest jeszcze co
    -- zanonimizowac" (idempotencja, pkt 6 naglowka V096).
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

    -- 9) CUSTOMER -- OSTATNIA instrukcja mutujaca dane "zrodlowe" (pkt 5
    --    naglowka V096, zawsze bezwarunkowo dosanityzowywana (pkt 8 naglowka
    --    V096: brak filtra "is_deleted = FALSE", zeby poprawic custom_fields/
    --    gdpr_consent/external_id nawet u klienta zanonimizowanego wczesniej
    --    tylko przez Jave). Stary stan do audytu -- bez PII, tylko metadane
    --    (wzorzec V013).
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

    -- 10) BE-142/V098 -- audit_log: maskowanie kluczy PII w wierszach PODMIOTU
    --     (CUSTOMER + CONTACT), D10. Wolane PRZED nowym wpisem CUSTOMER_ANONYMIZED
    --     ponizej (ten wpis nie zawiera zadnego klucza z listy PII, kolejnosc bez
    --     znaczenia funkcjonalnego, ale czytelniejsza: najpierw maskujemy STARE,
    --     potem dodajemy NOWY wpis). v_contact_ids to zbior CONTACT zamrozony na
    --     starcie funkcji -- patrz naglowek migracji.
    v_n_audit_log := mask_audit_log_pii(p_customer_id, p_tenant_id, v_contact_ids, FALSE);

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
        'contacts_dw', v_n_contacts_dw,
        'audit_log', v_n_audit_log
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
    'Anonimizacja danych osobowych klienta zgodnie z RODO Art. 17 (DB-062/V096, '
    'rozszerzenie V013; BE-142/V098: krok 10 -- maskowanie PII w audit_log podmiotu, '
    'D10). Zbior podmiotu = fn_customer_subject_ids (D9 = A, V095, reuzywana bez zmian) -- '
    'link (powiazanie kluczem obcym) + identifier (znormalizowany telefon/e-mail klienta), '
    'ten sam zbior co export_customer_data (spojnosc podgladu Art. 15/17). Obejmuje: customer '
    '(+ external_id, gdpr_consent, custom_fields), contact (remote_address, notes, recording_url, '
    'channel_metadata), scheduled_callback (phone/imie/nazwisko/notes, PENDING/PROCESSING -> '
    'CANCELLED), campaign_contact + campaign_contact_archive (phone/imie/nazwisko/email/'
    'custom_fields, PENDING/NO_ANSWER/CALLBACK -> SKIPPED w campaign_contact), '
    'contact_transcription + contact_ai_summary (DELETE, D3 = A), email_message (+ cc_address/'
    'bcc_address, pomijane przez V013), social_message, contacts_dw.remote_address (guard '
    'istnienia kolumny -- znika w DB-078), audit_log (maskowanie wartosci kluczy PII w wierszach '
    'CUSTOMER/CONTACT podmiotu -- V098, mask_audit_log_pii, wiersz zostaje). RETURNS JSONB: '
    '{dry_run, counts: {<tabela>: n, ..., audit_log: n}, matched_by_link, matched_by_identifier, '
    's3_keys}. p_dry_run = TRUE liczy bez modyfikacji i bez wpisu audytowego (audit_log w counts '
    'to PODGLAD -- ile wierszy zostaloby zamaskowanych); p_dry_run = NULL jest ODRZUCANE (RAISE '
    'EXCEPTION, DB062-01) -- "IF NULL THEN" w PL/pgSQL zachowuje sie jak FALSE, wiec bez tego '
    'guardu jawny SQL NULL (np. boxed Boolean = null w Javie przez JDBC) wykonalby rzeczywista '
    'anonimizacje zamiast bezpiecznego podgladu. Idempotentna: kazda tabela oprocz customer ma '
    'warunek "jeszcze nie zanonimizowane"/"jeszcze nie zamaskowane", wiec drugie wywolanie zwraca '
    'liczniki 0 (customer = 1 zawsze, zawsze dosanityzowany bezwarunkowo). Kolejnosc: contact i '
    'pochodne PRZED customer.is_deleted = TRUE (obrona w glab, dziala dzieki V094/DB-079 '
    'niezaleznie od kolejnosci); audit_log maskowany PO customer, PRZED nowym wpisem '
    'CUSTOMER_ANONYMIZED. Guard DB061-08: RAISE EXCEPTION przy wykrytej kolizji record_id miedzy '
    'kampaniami tego samego tenanta (campaign_contact/campaign_contact_archive), zamiast ryzykowac '
    'dotkniecie cudzego rekordu. Nie jest SECURITY DEFINER -- dziala z uprawnieniami/RLS '
    'wywolujacego (V096 pkt 10 naglowka -- audit_log UPDATE pod app_user zweryfikowane DZIALANIEM '
    'jako ciche 0 wierszy, patrz naglowek V098).';
