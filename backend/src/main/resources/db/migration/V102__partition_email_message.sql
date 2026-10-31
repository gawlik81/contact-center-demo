-- =============================================================================
-- V102__partition_email_message.sql
-- DB-067 (krok M2 z 2): partycjonowanie RANGE tabeli email_message po message_at
-- (EPIC-30). Trzeci w kolejnosci ticket lancucha DB-064 -> DB-065 -> DB-067 -> BE-134.
-- Decyzja D2 zamknieta przez wlasciciela 2026-10-04: partycjonujemy PRZED wdrozeniem
-- produkcyjnym (produkcji jeszcze nie ma). Decyzja D4 = A przyjeta jako zalozenie
-- robocze (NIEpotwierdzone formalnie przez PO): unikalnosc (tenant_id,
-- message_id_header, message_at) DEFERRABLE; ten sam naglowek z innym message_at
-- PRZECHODZI (udokumentowane ograniczenie, obrona = dedup aplikacyjny BE-134).
--
-- Migracja: Flyway V102
-- Wymaga: V101 (kolumna message_at NOT NULL, DEFAULT now()).
-- Zaleznosci (zweryfikowane w katalogu pg_depend / pg_constraint / pg_policy na scratch):
--   V010  tabela, pk_email_message, uq_email_message_id_header (DEFERRABLE),
--         idx_email_message_contact (contact_id, received_at DESC),
--         idx_email_message_delivery (WHERE delivery_status IN PENDING/FAILED),
--         COMMENT-y tabeli i kolumn message_id_header, in_reply_to, attachments
--   V025  direction -> VARCHAR(20) + chk_email_message_direction
--   V028  contact_id nullable (brak FK -- contact jest partycjonowany)
--   V012  ENABLE RLS; pol_email_message_select usunieta w V099
--   V097  idx_email_message_tenant_orphan_age (DB-059) na WYRAZENIU COALESCE
--         -> odtworzony na (tenant_id, message_at) WHERE contact_id IS NULL
--   V099  polityka email_message_tenant_isolation (ALL + WITH CHECK, FORCE)
--   V100  v_customer_timeline ZALEZY od tabeli (UNION ALL ... FROM email_message em)
--         przez pg_rewrite (zaleznosc 'n'): DROP TABLE _old bez DROP VIEW konczy sie
--         bledem 2BP01 -> DROP VIEW + CREATE OR REPLACE (tresc 1:1 z V100)
--   Brak: FK PRZYCHODZACYCH do email_message, triggerow, widokow zmaterializowanych
--         (mv_* nie czytaja tabeli), funkcji z %ROWTYPE/SETOF email_message.
--         Funkcje plpgsql (anonymize_customer, export_customer_data,
--         fn_customer_subject_ids) odwoluja sie po nazwie -- bez zaleznosci katalogowej.
--         v_rls_status wymienia 'email_message' jako literal tekstowy (pg_class), nie
--         zaleznosc -- bez zmian.
--
-- SWIADOME DECYZJE (nie pomylki):
--
-- 1. Blokada ACCESS EXCLUSIVE na CALY czas swapu (LOCK TABLE na poczatku). Stare
--    instancje aplikacji w rolling deploy moga pisac do tabeli; bez blokady wiersz
--    zapisany po SELECT kopiujacym zginalby bez sladu. Blokada = okno serwisowe:
--    zablokowane sa rowniez ODCZYTY (w tym v_customer_timeline) do COMMIT.
--    lock_timeout 10s: jesli tabela jest zajeta dluzej, migracja pada od razu
--    (ROLLBACK, start aplikacji sie nie udaje -- bezpieczniej niz cicha utrata).
--
-- 2. Migracja jednorazowa (online swap). Ponowne reczne zastosowanie tego pliku na
--    juz partycjonowanej tabeli JEST BLEDEM KONTROLOWANYM: guard na poczatku
--    przerywa przed jakakolwiek zmiana (zero zmian w pg_class/pg_proc). Flyway
--    zapisuje wykonanie w flyway_schema_history, wiec w normalnym przebiegu
--    migracja nie jest ponawiana.
--
-- 3. Partycje miesieczne wyznaczane DYNAMICZNIE z danych (MIN/MAX message_at):
--    od min(data, biezacy miesiac) do max(data, biezacy miesiac + 2). Plus
--    email_message_default. Nazwy email_message_YYYY_MM (konwencja PartitionScanner).
--    Granice zapisane z jawnym offsetem +00 i SET LOCAL timezone = 'UTC' -- wynik
--    nie zalezy od strefy sesji (pulapka opisana w feedback_flyway_manual_timezone).
--
-- 4. message_at NIEMODYFIKOWALNE: trigger BEFORE UPDATE OF message_at (kolumna
--    partycjonujaca; zmiana przeniosłaby wiersz miedzy partycjami i zlamalaby
--    unikalnosc D4). BEFORE UPDATE OF (nie BEFORE UPDATE) -- zapisy statusu
--    dostarczenia (hot path) nie odpalaja triggera.
--
-- 5. BRAK triggera BEFORE INSERT jako sieci bezpieczenstwa (sprawdzone na PG 16.13):
--    trigger BEFORE ROW na tabeli partycjonowanej odpala sie po routingu i przy NULL
--    w kluczu konczy sie bledem "moving row to another partition during a BEFORE
--    FOR EACH ROW trigger is not supported". Siecia jest DEFAULT now() z V101.
--
-- 6. Funkcja create_email_message_partition(p_year, p_month) -- idempotentna,
--    granice z jawnym +00. BEZ drop_old_email_message_partitions(): SQL nie zna
--    obiektow S3 w kluczach attachments (WP-5/WP-6); usuwanie partycji dotyczy
--    wylacznie przyszlego PartitionReclaimJob (tylko puste partycje) -- BE-134.
--    create_next_month_partitions(): CREATE OR REPLACE, tresc 1:1 z V100 + jedna
--    linia PERFORM dla email_message.
--
-- 7. Indeksy: idx_email_message_contact (bez zmian), idx_email_message_delivery
--    (bez zmian), idx_email_message_tenant_orphan_age ODTWORZONY na message_at
--    (DB-059 -> DB-067: wyrazenie COALESCE zastapione kolumna; zob. AC BE-127),
--    NOWY idx_email_message_tenant_message_at (tenant_id, message_at) pod purge
--    per tenant batchami (wzorzec DB-065 / idx_social_message_tenant_sent_at).
--    UWAGA dla BE-134: zapytania BE-127 (ORPHAN_AGE_EXPR = COALESCE(...)) przestaja
--    pasowac do indeksu -- kod musi przejsc na message_at, inaczej planner zrobi
--    Seq Scan (zmierzone na scratch, zob. notatke wykonania DB-067).
--
-- 8. COMMENT ON INDEX/COLUMN/TABLE/VIEW odtworzone (RENAME i przebudowa tabeli NIE
--    przenosza komentarzy -- lekcja z V100/DB-065). Komentarz attachments poprawiony
--    na klucz s3_key (zamiast s3_url), zgodnie z TASKS-DATABASE.md DB-062/BE-124.
--
-- 9. ANALYZE na koniec: swiezo przepisana tabela nie ma statystyk; bez tego
--    pierwsze zapytania po migracji dostana domyslne oszacowania planera.
--
-- ROLLBACK (plan wycofania):
--   * W trakcie: wszystko w JEDNEJ transakcji Flyway. Blad dowolnej weryfikacji
--     (RAISE EXCEPTION) = ROLLBACK, tabela email_message bez zmian (zweryfikowane
--     na scratch: wymuszony blad przed DROP -- zob. notatke DB-067).
--   * Po COMMIT: email_message_old zostaje usuniete w tej samej transakcji, wiec
--     RENAME z powrotem NIE jest mozliwe. Wycofanie = przywrocenie z backupu
--     (pg_dump -Fc PRZED wdrozeniem, wymagany) albo reczna procedura odwrotna
--     (przepisanie partycji do tabeli niepartycjonowanej i ponowny swap) poza Flyway.
--   * Jezeli PO wdrozeniu ma byc mozliwy RENAME z powrotem, DROP email_message_old
--     trzeba przeniesc do osobnej migracji wykonywanej po okresie obserwacji --
--     decyzja dla wlasciciela (poza zakresem tych dwoch numerow).
--
-- RYZYKA WDROZENIOWE (BE-134 i ops; szczegoly w raporcie DB-067):
--   * Okno serwisowe = czas kopii + budowy indeksow (ACCESS EXCLUSIVE). Dla dev/demo
--     (55 wierszy) pomijalne; przy wolumenie produkcyjnym zmierzyc na stage.
--   * Zdarzenia RabbitMQ / zapisy w locie: wiersze w kolejce podczas blokady
--     (i zapisy stare instancji) koncza sie bledem "relation does not exist" albo
--     wykonaja sie po swapie z message_at = now() (DEFAULT). BE-134 musi byc wdrozony
--     w tym samym wydaniu i z ponowieniem obsluzonym po stronie konsumenta.
--   * DEFAULT now() jest PRZEJSCIOWY (zob. V101): po wdrozeniu BE-134 usunac
--     (ALTER TABLE email_message ALTER COLUMN message_at DROP DEFAULT), bo inaczej
--     zapomniane ustawienie message_at w kodzie da cicho now() zamiast INTERNALDATE.
--   * Obejscie RLS przez partycje -- ROZWIAZANE w tej migracji (decyzja wlasciciela
--     2026-10-04). GRANT-y domyslne (ALTER DEFAULT PRIVILEGES ccapp, V012) daja app_user
--     bezposredni dostep do KAZDEJ partycji email_message_YYYY_MM, a partycje nie maja
--     wlasnego RLS -- zapytanie po nazwie partycji omijaloby polityke tenanta (zmierzone
--     na scratch: GUC tenanta T2 zwracal wiersze tenanta T1). Dlatego:
--       (a) REVOKE ALL na istniejacych partycjach, w tym _default (sekcja 9b, petla po pg_inherits),
--       (b) REVOKE w create_email_message_partition na nowo tworzonej partycji (sekcja 10),
--           bo ALTER DEFAULT PRIVILEGES nadaje GRANT automatycznie przy kazdym CREATE TABLE,
--       (c) asercja koncowa (sekcja 12).
--     Dostep do danych PRZEZ TABELE NADRZEDNA (z polityka RLS) ZOSTAJE: PostgreSQL nie sprawdza
--     uprawnien partycji, gdy zapytanie idzie przez rodzica (zweryfikowane na PG 16.13).
--     Bezposredni dostep po nazwie partycji daje "permission denied".
--     Ten sam stan mial social_message (V100) -- naprawiany osobna migracja V103.
--     Zakres DB-073 (campaign_contact) pozostaje osobny; decyzja w TASKS-DATABASE.md.
-- =============================================================================

SET LOCAL lock_timeout = '10s';
SET LOCAL timezone = 'UTC';

-- ---------------------------------------------------------------------------
-- 0. Guard: migracja jednorazowa (patrz decyzja 2) + brak pozostalosci po
--    nieudanej probie recznej.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_class
        WHERE oid = 'public.email_message'::regclass AND relkind = 'p'
    ) THEN
        RAISE EXCEPTION 'V102 aborted: public.email_message is already partitioned (relkind = p). '
                        'This migration is a one-shot table swap; no changes were made.';
    END IF;

    IF to_regclass('public.email_message_old') IS NOT NULL THEN
        RAISE EXCEPTION 'V102 aborted: public.email_message_old already exists (leftover from a previous attempt). '
                        'No changes were made.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. Blokada na caly swap (decyzja 1). Kopia nie moze gubic zapisow w locie.
-- ---------------------------------------------------------------------------
LOCK TABLE email_message IN ACCESS EXCLUSIVE MODE;

-- ---------------------------------------------------------------------------
-- 2. Tabela blizniacza (partycjonowana). Nazwa _new: finalna nazwa 'email_message'
--    jest zajeta przez tabele zrodlowa do RENAME (sekcja 5).
-- ---------------------------------------------------------------------------
CREATE TABLE email_message_new (
    message_id          UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID            NOT NULL,
    contact_id          UUID,
    direction           VARCHAR(20)     NOT NULL,

    from_address        VARCHAR(255)    NOT NULL,
    to_address          TEXT            NOT NULL,
    cc_address          TEXT,
    bcc_address         TEXT,

    subject             VARCHAR(998),

    body_html           TEXT,
    body_text           TEXT,

    message_id_header   VARCHAR(255),
    in_reply_to         VARCHAR(255),

    attachments         JSONB           NOT NULL DEFAULT '[]',

    received_at         TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,

    delivery_status     VARCHAR(30),

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- DB-067 / D4: klucz partycjonowania. DEFAULT = siec bezpieczenstwa (decyzja 5, V101).
    message_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Nazwy _new: PK/UNIQUE tworza relacje-indeks w schemacie (unikalnej globalnie),
    -- dopoki email_message (zrodlowa) istnieje. Sprzatane w sekcji 8.
    CONSTRAINT pk_email_message_new PRIMARY KEY (message_id, message_at),

    CONSTRAINT fk_email_message_tenant_new
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    -- D4 = A (zalozenie robocze): ten sam naglowek + ten sam message_at = duplikat;
    -- ten sam naglowek z INNYM message_at przechodzi (ograniczenie udokumentowane).
    CONSTRAINT uq_email_message_id_header_new
        UNIQUE (tenant_id, message_id_header, message_at)
        DEFERRABLE INITIALLY DEFERRED,

    -- CHECK-i: nazwa unikalna per tabela, wiec finalna nazwa jest bezpieczna od razu.
    CONSTRAINT chk_email_attachments_is_array
        CHECK (jsonb_typeof(attachments) = 'array'),

    CONSTRAINT chk_email_message_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'))
) PARTITION BY RANGE (message_at);

-- ---------------------------------------------------------------------------
-- 3. Partycje: miesieczne dla zakresu danych + biezacy miesiac + 2 kolejne,
--    plus DEFAULT. Zakres liczony z tabeli zrodlowej (zablokowanej w sekcji 1).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_min_at   TIMESTAMPTZ;
    v_max_at   TIMESTAMPTZ;
    v_now      TIMESTAMPTZ := now();
    v_month    DATE;
    v_first    DATE;
    v_last     DATE;
    v_created  INT := 0;
BEGIN
    SELECT MIN(message_at), MAX(message_at) INTO v_min_at, v_max_at
    FROM email_message;

    v_first := date_trunc('month', LEAST(COALESCE(v_min_at, v_now), v_now))::date;
    v_last  := date_trunc('month', GREATEST(COALESCE(v_max_at, v_now), v_now) + INTERVAL '2 months')::date;

    v_month := v_first;
    WHILE v_month <= v_last LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF email_message_new FOR VALUES FROM (%L) TO (%L)',
            'email_message_' || to_char(v_month, 'YYYY_MM'),
            to_char(v_month, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char((v_month + INTERVAL '1 month')::date, 'YYYY-MM-DD') || ' 00:00:00+00'
        );
        v_created := v_created + 1;
        v_month := (v_month + INTERVAL '1 month')::date;
    END LOOP;

    RAISE NOTICE 'V102 email_message: created % monthly partitions (% .. %).', v_created, v_first, v_last;
END $$;

CREATE TABLE email_message_default
    PARTITION OF email_message_new DEFAULT;

-- ---------------------------------------------------------------------------
-- 4. Kopia danych (jawna lista kolumn; zablokowana tabela zrodlowa = pelna migawka).
-- ---------------------------------------------------------------------------
INSERT INTO email_message_new (
    message_id, tenant_id, contact_id, direction,
    from_address, to_address, cc_address, bcc_address, subject,
    body_html, body_text, message_id_header, in_reply_to,
    attachments, received_at, sent_at, delivery_status, created_at, message_at
)
SELECT
    message_id, tenant_id, contact_id, direction,
    from_address, to_address, cc_address, bcc_address, subject,
    body_html, body_text, message_id_header, in_reply_to,
    attachments, received_at, sent_at, delivery_status, created_at, message_at
FROM email_message;

-- ---------------------------------------------------------------------------
-- 5. Swap nazw. Od tego momentu 'email_message' = tabela partycjonowana.
-- ---------------------------------------------------------------------------
ALTER TABLE email_message RENAME TO email_message_old;
ALTER TABLE email_message_new RENAME TO email_message;

-- ---------------------------------------------------------------------------
-- 6. Weryfikacja zero-utraty PRZED DROP: liczby oraz pelna zawartosc w obie strony
--    (EXCEPT po jawnej liscie kolumn). Blad = ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_count_old     BIGINT;
    v_count_new     BIGINT;
    v_missing_new   BIGINT;
    v_missing_old   BIGINT;
    v_null_at       BIGINT;
    c_cols CONSTANT TEXT :=
        'message_id, tenant_id, contact_id, direction, from_address, to_address, cc_address, '
        'bcc_address, subject, body_html, body_text, message_id_header, in_reply_to, '
        'attachments, received_at, sent_at, delivery_status, created_at, message_at';
BEGIN
    SELECT COUNT(*) INTO v_count_old FROM email_message_old;
    SELECT COUNT(*) INTO v_count_new FROM email_message;

    IF v_count_old <> v_count_new THEN
        RAISE EXCEPTION 'V102 aborted: row count mismatch (old=%, new=%)', v_count_old, v_count_new;
    END IF;

    EXECUTE format('SELECT COUNT(*) FROM (SELECT %s FROM email_message_old EXCEPT SELECT %s FROM email_message) d', c_cols, c_cols)
        INTO v_missing_new;
    EXECUTE format('SELECT COUNT(*) FROM (SELECT %s FROM email_message EXCEPT SELECT %s FROM email_message_old) d', c_cols, c_cols)
        INTO v_missing_old;

    IF v_missing_new <> 0 OR v_missing_old <> 0 THEN
        RAISE EXCEPTION 'V102 aborted: content mismatch (old-only=%, new-only=%)', v_missing_new, v_missing_old;
    END IF;

    SELECT COUNT(*) INTO v_null_at FROM email_message WHERE message_at IS NULL;
    IF v_null_at <> 0 THEN
        RAISE EXCEPTION 'V102 aborted: % rows with NULL message_at in partitioned table', v_null_at;
    END IF;

    RAISE NOTICE 'V102 email_message: row count and content verified (% rows).', v_count_new;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Obiekty na przemianowanej tabeli. Indeksy wtorne z nazwami _new (finalne
--    nazwy sa zajete przez _old do sekcji 9). COMMENT ON INDEX ustawiany raz na
--    nazwie _new -- przezywa pozniejszy RENAME (wiazanie z OID).
-- ---------------------------------------------------------------------------
CREATE INDEX idx_email_message_contact_new
    ON email_message (contact_id, received_at DESC);

CREATE INDEX idx_email_message_delivery_new
    ON email_message (tenant_id, delivery_status)
    WHERE delivery_status IN ('PENDING', 'FAILED');

-- DB-059 / V097 odtworzony na kolumnie (decyzja 7). Sweep osieroconych wg wieku.
CREATE INDEX idx_email_message_tenant_orphan_age_new
    ON email_message (tenant_id, message_at)
    WHERE contact_id IS NULL;

COMMENT ON INDEX idx_email_message_tenant_orphan_age_new
    IS 'DB-059 / BE-127 (przeniesiony do message_at w DB-067/V102): sweep wiadomosci email '
       'OSIEROCONYCH (contact_id IS NULL) per-tenant wedlug message_at (NOT NULL, bez COALESCE). '
       'Uzywany przez: SELECT ... FROM email_message WHERE tenant_id = ? AND contact_id IS NULL '
       'AND message_at < ? (BE-127/BE-134 -- zapytanie musi przejsc z wyrazenia COALESCE na message_at).';

-- DB-067 (D1/WP-5): indeks pod purge per tenant batchami (wzorzec DB-065 / V100).
CREATE INDEX idx_email_message_tenant_message_at_new
    ON email_message (tenant_id, message_at);

COMMENT ON INDEX idx_email_message_tenant_message_at_new
    IS 'DB-067: purge retencyjny per-tenant batchami po message_at. Uzywany przez '
       'RetentionPurgeService/BE-134: DELETE FROM email_message WHERE tenant_id = ? AND message_at < ?';

-- RLS: identyczna polityka jak V099 (DB-064): ALL + WITH CHECK + FORCE, GUC app.current_tenant_id.
ALTER TABLE email_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message FORCE ROW LEVEL SECURITY;

CREATE POLICY email_message_tenant_isolation ON email_message
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- Trigger ochronny klucza partycjonowania (decyzja 4).
CREATE OR REPLACE FUNCTION fn_email_message_forbid_message_at_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.message_at IS DISTINCT FROM OLD.message_at THEN
        RAISE EXCEPTION 'email_message.message_at is immutable (partition key, DB-067)'
            USING ERRCODE = 'check_violation',
                  HINT = 'Changing message_at would move the row between partitions and break '
                         'UNIQUE (tenant_id, message_id_header, message_at).';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_email_message_forbid_message_at_update
    BEFORE UPDATE OF message_at ON email_message
    FOR EACH ROW
    EXECUTE FUNCTION fn_email_message_forbid_message_at_update();

-- Komentarze (RENAME i przebudowa tabeli NIE przenosza komentarzy -- lekcja z V100).
COMMENT ON TABLE email_message IS
    'Wiadomosci email w ramach kontaktow. Od V102 tabela partycjonowana RANGE po message_at '
    '(PK zlozony message_id + message_at). Partycje miesieczne email_message_YYYY_MM tworzone przez '
    'create_email_message_partition()/create_next_month_partitions(); usuwanie starych (pustych) '
    'partycji WYLACZNIE przez PartitionReclaimJob (BE-134) -- brak SQL-owego drop_old_*. '
    'Unikalnosc naglowka Message-ID: UNIQUE (tenant_id, message_id_header, message_at) DEFERRABLE '
    '(D4 = A, zalozenie robocze) -- ten sam naglowek z innym message_at przechodzi.';

COMMENT ON COLUMN email_message.message_id_header IS
    'RFC 2822 Message-ID. Uzytkowany do deduplikacji przy wielokrotnym pobraniu IMAP. '
    'Unikalnosc tylko razem z message_at (patrz UNIQUE uq_email_message_id_header).';

COMMENT ON COLUMN email_message.in_reply_to IS
    'RFC 2822 In-Reply-To. Powiazanie odpowiedzi z poprzednia wiadomoscia w watku.';

COMMENT ON COLUMN email_message.attachments IS
    'Metadane zalacznikow (JSONB, tablica obiektow). Klucz pliku w S3-compatible storage to '
    's3_key (prefiks email-attachments/{tenantId}/ -- nie s3_url). Pliki usuwane przez purge '
    'razem z wierszem (BE-125/BE-134).';

COMMENT ON COLUMN email_message.message_at IS
    'DB-067 / D4 (zalozenie robocze A): czas zaobserwowany przez system -- klucz partycjonowania '
    'RANGE. INBOUND: INTERNALDATE serwera IMAP (Message#getReceivedDate()), fallback now() gdy brak; '
    'OUTBOUND: sentAt ustawiany raz. NIE jest naglowkiem Date nadawcy. Backfill (V101): '
    'COALESCE(received_at, sent_at, created_at). Niemodyfikowalne (trigger). DEFAULT now() jest '
    'przejsciowy -- do usuniecia po wdrozeniu BE-134.';

-- ---------------------------------------------------------------------------
-- 8. DROP starej tabeli -- dopiero po pozytywnej weryfikacji (sekcja 6).
--    Najpierw widok zalezny (pg_rewrite, zaleznosc normalna); potem odtworzenie
--    widoku 1:1 z V100 (odpytuje tabele po NAZWIE, wiec tresc SQL bez zmian).
-- ---------------------------------------------------------------------------
DROP VIEW IF EXISTS v_customer_timeline;

DROP TABLE email_message_old;

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
    'Pelna os czasu interakcji klienta: kontakty telefoniczne/email/social. '
    'Uzywany przez widok historii klienta w UI (BE-025, US-09-02). '
    'Sortowanie po event_at po stronie aplikacji (ORDER BY nie w widoku - optymalizacja). '
    'Od V100 social_message i od V102 email_message sa partycjonowane -- widok odpytuje tabele '
    'nadrzedne, PostgreSQL routuje przez partycje automatycznie.';

-- ---------------------------------------------------------------------------
-- 9. Porzadkowanie nazw (PK/FK/UNIQUE/indeksow) z _new do finalnych -- dopiero
--    teraz, gdy stara tabela je zwolnila.
-- ---------------------------------------------------------------------------
ALTER TABLE email_message RENAME CONSTRAINT pk_email_message_new TO pk_email_message;
ALTER TABLE email_message RENAME CONSTRAINT fk_email_message_tenant_new TO fk_email_message_tenant;
ALTER TABLE email_message RENAME CONSTRAINT uq_email_message_id_header_new TO uq_email_message_id_header;

ALTER INDEX idx_email_message_contact_new RENAME TO idx_email_message_contact;
ALTER INDEX idx_email_message_delivery_new RENAME TO idx_email_message_delivery;
ALTER INDEX idx_email_message_tenant_orphan_age_new RENAME TO idx_email_message_tenant_orphan_age;
ALTER INDEX idx_email_message_tenant_message_at_new RENAME TO idx_email_message_tenant_message_at;

-- ---------------------------------------------------------------------------
-- 9b. REVOKE bezposredniego dostepu app_user do KAZDEJ partycji (decyzja PO 2026-10-04,
--     zob. naglowek). Petla po pg_inherits obejmuje wszystkie partycje biezace, w tym
--     email_message_default. Zmiana ACL dotyczy katalogu (pg_locks nie pokazuje blokady
--     na relacji). Dostep przez tabele nadrzedna nie zalezy od tych uprawnien.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_part  TEXT;
    v_count INT := 0;
BEGIN
    FOR v_part IN
        SELECT c.oid::regclass::text
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'public.email_message'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE %s FROM app_user', v_part);
        v_count := v_count + 1;
    END LOOP;

    RAISE NOTICE 'V102 email_message: REVOKE ALL FROM app_user na % partycjach.', v_count;
END $$;

-- ---------------------------------------------------------------------------
-- 10. create_email_message_partition(p_year, p_month) -- wzorzec V100
--     (create_social_message_partition). Granice z jawnym +00 (niezalezne od strefy).
--     SWIADOMIE BEZ drop_old_email_message_partitions() (decyzja 6).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_email_message_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date  DATE;
    v_end_date    DATE;
    v_table_name  TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := (v_start_date + INTERVAL '1 month')::date;
    v_table_name := 'email_message_' || to_char(v_start_date, 'YYYY_MM');

    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF email_message FOR VALUES FROM (%L) TO (%L)',
            v_table_name,
            to_char(v_start_date, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(v_end_date, 'YYYY-MM-DD') || ' 00:00:00+00'
        );
        -- DB-067 / decyzja PO 2026-10-04: ALTER DEFAULT PRIVILEGES (V012) nadaje app_user GRANT
        -- na kazdej nowo utworzonej partycji -- to obchodzi RLS tabeli nadrzednej. Wolamy REVOKE
        -- od razu po CREATE (wywolujacy jest wlascicielem nowej partycji, wiec REVOKE sie uda).
        EXECUTE format('REVOKE ALL ON TABLE %I FROM app_user', v_table_name);
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_email_message_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli email_message dla podanego roku i miesiaca. Idempotentna. '
    'Granice w UTC (jawny offset +00). Wywolywana z create_next_month_partitions(). '
    'NIE ma drop_old_email_message_partitions(): usuwanie pustych partycji idzie przez PartitionReclaimJob (BE-134).';

-- ---------------------------------------------------------------------------
-- 11. create_next_month_partitions() -- CREATE OR REPLACE, tresc 1:1 z V100
--     (ostatnia definicja w katalogu, zweryfikowane grepem) + jedna linia PERFORM.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION create_next_month_partitions()
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_next_year  INT;
    v_next_month INT;
BEGIN
    v_next_year  := EXTRACT(YEAR  FROM CURRENT_DATE + INTERVAL '1 month')::INT;
    v_next_month := EXTRACT(MONTH FROM CURRENT_DATE + INTERVAL '1 month')::INT;

    PERFORM create_audit_log_partition(v_next_year, v_next_month);
    PERFORM create_contact_partition(v_next_year, v_next_month);
    PERFORM create_plugin_invocation_log_partition(v_next_year, v_next_month);
    PERFORM create_contact_event_partition(v_next_year, v_next_month);
    PERFORM create_contact_transcription_partition(v_next_year, v_next_month);
    PERFORM create_contact_ai_summary_partition(v_next_year, v_next_month);
    PERFORM create_social_message_partition(v_next_year, v_next_month);
    PERFORM create_email_message_partition(v_next_year, v_next_month);

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('create_next_month_partitions', NOW(), 'SUCCESS',
            format('Utworzono partycje na %s-%s', v_next_year, LPAD(v_next_month::TEXT, 2, '0')));

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'create_next_month_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- 12. Weryfikacja strukturalna koncowa. Blad = ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_relkind          CHAR;
    v_partstrat        CHAR;
    v_partkey          TEXT;
    v_pk_cols          TEXT;
    v_uq_cols          TEXT;
    v_uq_deferrable    BOOLEAN;
    v_force_rls        BOOLEAN;
    v_policy_count     INT;
    v_message_at_notnull BOOLEAN;
    v_default_exists   INT;
    v_partition_count  INT;
    v_index_count      INT;
    v_trigger_count    INT;
    v_drop_fn_count    INT;
    v_next_month_count INT;
    v_view_exists      INT;
    v_old_exists       BOOLEAN;
    v_app_user_grants  INT;
BEGIN
    SELECT relkind, relforcerowsecurity INTO v_relkind, v_force_rls
    FROM pg_class WHERE oid = 'public.email_message'::regclass;

    IF v_relkind <> 'p' THEN
        RAISE EXCEPTION 'V102: email_message nie jest tabela partycjonowana (relkind=%)', v_relkind;
    END IF;

    SELECT partstrat, pg_get_partkeydef(partrelid) INTO v_partstrat, v_partkey
    FROM pg_partitioned_table WHERE partrelid = 'public.email_message'::regclass;

    IF v_partstrat <> 'r' OR v_partkey <> 'RANGE (message_at)' THEN
        RAISE EXCEPTION 'V102: oczekiwano RANGE (message_at), znaleziono % / %', v_partstrat, v_partkey;
    END IF;

    SELECT string_agg(a.attname, ',' ORDER BY k.ord) INTO v_pk_cols
    FROM pg_constraint c
    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
    WHERE c.conrelid = 'public.email_message'::regclass AND c.contype = 'p';

    IF v_pk_cols <> 'message_id,message_at' THEN
        RAISE EXCEPTION 'V102: oczekiwano PK (message_id, message_at), znaleziono (%)', v_pk_cols;
    END IF;

    SELECT string_agg(a.attname, ',' ORDER BY k.ord), BOOL_AND(c.condeferrable AND c.condeferred)
    INTO v_uq_cols, v_uq_deferrable
    FROM pg_constraint c
    JOIN unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
    WHERE c.conrelid = 'public.email_message'::regclass AND c.conname = 'uq_email_message_id_header';

    IF v_uq_cols <> 'tenant_id,message_id_header,message_at' OR NOT v_uq_deferrable THEN
        RAISE EXCEPTION 'V102: UNIQUE uq_email_message_id_header: kolumny=(%), deferrable=%', v_uq_cols, v_uq_deferrable;
    END IF;

    IF NOT v_force_rls THEN
        RAISE EXCEPTION 'V102: email_message nie ma FORCE ROW LEVEL SECURITY';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'email_message'
      AND policyname = 'email_message_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V102: oczekiwano 1 polityki email_message_tenant_isolation (ALL+WITH CHECK), znaleziono %', v_policy_count;
    END IF;

    SELECT attnotnull INTO v_message_at_notnull
    FROM pg_attribute WHERE attrelid = 'public.email_message'::regclass AND attname = 'message_at';

    IF NOT v_message_at_notnull THEN
        RAISE EXCEPTION 'V102: message_at nie jest NOT NULL';
    END IF;

    SELECT COUNT(*) INTO v_default_exists
    FROM pg_inherits WHERE inhparent = 'public.email_message'::regclass
      AND inhrelid = 'public.email_message_default'::regclass;

    IF v_default_exists <> 1 THEN
        RAISE EXCEPTION 'V102: brak partycji email_message_default';
    END IF;

    SELECT COUNT(*) INTO v_partition_count
    FROM pg_inherits WHERE inhparent = 'public.email_message'::regclass;

    IF v_partition_count < 4 THEN
        RAISE EXCEPTION 'V102: zbyt malo partycji email_message (%)', v_partition_count;
    END IF;

    SELECT COUNT(*) INTO v_index_count
    FROM pg_indexes
    WHERE schemaname = 'public' AND tablename = 'email_message'
      AND indexname IN ('idx_email_message_contact', 'idx_email_message_delivery',
                        'idx_email_message_tenant_orphan_age', 'idx_email_message_tenant_message_at');

    IF v_index_count <> 4 THEN
        RAISE EXCEPTION 'V102: oczekiwano 4 indeksow wtornych na email_message, znaleziono %', v_index_count;
    END IF;

    SELECT COUNT(*) INTO v_trigger_count
    FROM pg_trigger
    WHERE tgrelid = 'public.email_message'::regclass
      AND tgname = 'trg_email_message_forbid_message_at_update' AND NOT tgisinternal;

    IF v_trigger_count <> 1 THEN
        RAISE EXCEPTION 'V102: brak triggera trg_email_message_forbid_message_at_update';
    END IF;

    SELECT COUNT(*) INTO v_drop_fn_count
    FROM pg_proc WHERE proname = 'drop_old_email_message_partitions';

    IF v_drop_fn_count <> 0 THEN
        RAISE EXCEPTION 'V102: istnieje drop_old_email_message_partitions() -- zakazane (WP-5/WP-6)';
    END IF;

    SELECT COUNT(*) INTO v_next_month_count
    FROM pg_proc WHERE proname = 'create_next_month_partitions'
      AND prosrc LIKE '%create_email_message_partition%';

    IF v_next_month_count <> 1 THEN
        RAISE EXCEPTION 'V102: create_next_month_partitions() nie wola create_email_message_partition()';
    END IF;

    SELECT COUNT(*) INTO v_view_exists
    FROM pg_class WHERE relname = 'v_customer_timeline' AND relkind = 'v';

    IF v_view_exists <> 1 THEN
        RAISE EXCEPTION 'V102: brak widoku v_customer_timeline po migracji';
    END IF;

    v_old_exists := to_regclass('public.email_message_old') IS NOT NULL;
    IF v_old_exists THEN
        RAISE EXCEPTION 'V102: email_message_old nadal istnieje';
    END IF;

    -- DB-067 / decyzja PO 2026-10-04: zadna partycja nie daje app_user uprawnien (obejscie RLS).
    SELECT COUNT(*) INTO v_app_user_grants
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = 'public.email_message'::regclass
      AND has_table_privilege('app_user', c.oid, 'SELECT, INSERT, UPDATE, DELETE');

    IF v_app_user_grants <> 0 THEN
        RAISE EXCEPTION 'V102: % partycji email_message daje app_user uprawnienia (obejscie RLS)', v_app_user_grants;
    END IF;

    RAISE NOTICE 'V102: OK -- email_message partycjonowana RANGE(message_at), % partycji, PK(message_id,message_at), RLS ALL+WITH CHECK+FORCE.', v_partition_count;
END $$;

-- Statystyki dla planera po przepisaniu tabeli (decyzja 9).
ANALYZE email_message;
