-- =============================================================================
-- V100__partition_social_message.sql
-- DB-065: Partycjonowanie RANGE tabeli social_message po sent_at (EPIC-30).
--
-- Migracja: Flyway V100
-- Zaleznosci: V010 (social_message istnieje jako zwykla tabela), V025 (direction
--             -> VARCHAR + chk_social_message_direction), V028 (contact_id
--             nullable), V097 (DB-059, idx_social_message_tenant_orphan_sent),
--             V099 (DB-064, polityka social_message_tenant_isolation
--             FOR ALL + WITH CHECK + FORCE -- MUSI byc odtworzona 1:1 ponizej)
-- Blokuje: BE-132 (nowy PK zlozony w encji JPA + naprawa zrodla sent_at dla
--          FB/IG -- patrz OSTRZEZENIE ponizej)
--
-- KONTEKST: trzeci ticket lancucha DB-064 -> DB-065 -> BE-132 -> BE-133. Live
-- social_message ma 0 wierszy (0 wierszy = najtansze okno na zmiane klucza,
-- TASKS-DATABASE.md). Wzorzec strukturalny (RANGE po kolumnie czasowej,
-- partycja DEFAULT, online-swap tabela blizniacza -> INSERT SELECT -> RENAME
-- -> odtworzenie obiektow -> weryfikacja -> DROP starej -> porzadkowanie nazw)
-- jest identyczny z V085/V086/V087 (DB-049/050/051, EPIC-29) -- V085
-- (contact_event) jest najblizszym analogiem (FK do tenant, CHECK-i, indeksy
-- wtorne).
--
-- =============================================================================
-- OSTRZEZENIE DLA BE-132 (nie pomijac przy wdrozeniu) -- WARUNEK WEJSCIA
-- =============================================================================
-- Dzisiejszy GLOBALNY UNIQUE (tenant_id, external_message_id) w pelni chroni
-- przed redelivery webhookow niezaleznie od platformy. Po tej migracji
-- unikalnosc staje sie UNIQUE (tenant_id, external_message_id, sent_at) --
-- wymog PostgreSQL dla tabel partycjonowanych ("unique constraint on
-- partitioned table must include all partitioning columns"). Ta zmiana
-- OSLABIA ochrone idempotentnosci, JEZELI sent_at nie jest deterministyczny:
--
--   * WhatsApp (SocialWebhookController.parseWhatsAppMessage) -- sent_at
--     pochodzi z pola "timestamp" payloadu platformy (Unix epoch sekundy,
--     fallback Instant.now() tylko gdy timestamp <= 0) -- DETERMINISTYCZNY,
--     redelivery dostaje TEN SAM sent_at, wiec zlozona unikalnosc nadal lapie
--     duplikat.
--   * Facebook (parseFacebookEvent) i Instagram (parseInstagramEvent) -- OBA
--     dzis ustawiaja sent_at = Instant.now() W CHWILI PRZETWORZENIA WEBHOOKA
--     (zweryfikowane w kodzie zrodlowym tuz przed napisaniem tej migracji,
--     2026-10-01) -- NIEDETERMINISTYCZNY. Redelivery tego samego zdarzenia
--     (ten sam "mid") dostanie INNY sent_at przy kazdym odebraniu, wiec
--     UNIQUE (tenant_id, external_message_id, sent_at) NIE wykryje duplikatu
--     dla FB/IG -- przestaje byc siecia bezpieczenstwa dla tych dwoch platform
--     (dedup aplikacyjny SocialMessageServiceImpl#processIncomingMessage ->
--     findByExternalMessageId pozostaje GLOWNA, ale juz JEDYNA realna obrona).
--
-- DECYZJA (zapisana w TASKS-DATABASE.md DB-065, "KRYTYCZNA KOREKTA"): ta
-- migracja WCHODZI TERAZ (0 wierszy = odroczenie nic by nie kosztowalo, ale
-- ticket swiadomie wybral realizacje teraz, nie odroczenie), pod warunkiem ze
-- BE-132 (kolejny ticket tego samego lancucha, w TYM SAMYM wydaniu) naprawi
-- zrodlo sent_at dla FB/IG na czas z payloadu platformy (analogicznie do
-- WhatsApp) ZANIM to wydanie trafi na srodowisko z realnym ruchem FB/IG.
-- TA MIGRACJA SAMA W SOBIE NIE JEST BEZPIECZNA PRODUKCYJNIE DLA FACEBOOKA I
-- INSTAGRAMA, DOPOKI BE-132 NIE NAPRAWI ZRODLA sent_at -- wdrozenie na
-- produkcje musi zawierac OBA tickety (DB-065 + BE-132) razem, nigdy samo
-- DB-065. Jezeli pole czasu w payloadzie Meta okaze sie niedostepne/niepewne
-- (nazwa pola niezweryfikowana -- BE-124 par.11), BE-132 musi PRZED wdrozeniem
-- wybrac zastepczy mechanizm dedup (np. osobna tabela dedup) zamiast polegac
-- na tej unikalnosci zlozonej dla FB/IG.
-- =============================================================================
--
-- SWIADOME DECYZJE PROJEKTOWE (nie pomylki):
--
-- 1. RLS odtworzona Z IDENTYCZNA polityka co V099 (ALL + WITH CHECK + FORCE,
--    GUC app.current_tenant_id, nazwa social_message_tenant_isolation) --
--    social_message jest JUZ na poprawnym GUC od V099, wiec (w odroznieniu od
--    V085/086/087 z EPIC-29, ktore swiadomie zachowaly wtedy jeszcze bledny
--    app.tenant_id) tu nie ma nic do "swiadomego niepoprawiania" -- proste 1:1
--    odtworzenie najnowszego stanu polityki.
--
-- 2. Funkcja create_social_message_partition() jest tworzona, ALE
--    drop_old_social_message_partitions()/rotate_social_message_partitions()
--    NIE (w odroznieniu od V088/contact_event i braci) -- DROP calej partycji
--    dla wiadomosci social idzie WYLACZNIE przez przyszly PartitionReclaimJob
--    (BE-133, reguła "tylko puste partycje"), nie przez backstopowy
--    DROP-po-wieku na poziomie SQL. create_social_message_partition() jest
--    wywolywana BEZPOSREDNIO z create_next_month_partitions() (bez
--    posredniego rotate_*) -- tworzenie kolejnego miesiaca jest wiec juz dzis
--    pokryte istniejacym zadaniem scheduled_job 'create_next_month_partitions'
--    (cron '0 0 20 * *'), bez potrzeby nowego wpisu scheduled_job w tej
--    migracji.
--
-- 3. Wolumen danych w dev (0 wierszy) -- Guard na poczatku migracji przerywa
--    (RAISE EXCEPTION) jesli COUNT(*) > 10000, zeby przypadkowe uruchomienie
--    tej migracji na srodowisku z realnymi danymi nie trzymalo dlugotrwalej
--    blokady/nie naduzywalo WAL pojedynczym INSERT...SELECT w jednej
--    transakcji -- analogicznie do uwagi w naglowkach V085/V088 (tam bez
--    twardego guarda, bo wolumen byl juz zmierzony i maly; tu dodany jawny
--    guard, bo spolecznosciowy kanal moze urosnac szybciej niz dev-seed).
--
-- Kolejnosc krokow (online swap, identyczna z V085):
--   1. Guard wolumenu + SET LOCAL lock_timeout
--   2. CREATE TABLE social_message_new (PK zlozony (message_id, sent_at),
--      UNIQUE (tenant_id, external_message_id, sent_at)) PARTITION BY RANGE (sent_at)
--   3. Partycje: 0 wierszy -> zakres danych = biezacy miesiac (2026-10) +
--      2 kolejne (2026-11, 2026-12) + DEFAULT
--   4. INSERT INTO social_message_new SELECT ... FROM social_message (jawna lista kolumn)
--   5. RENAME: social_message -> social_message_old, social_message_new -> social_message
--   6. Odtworzenie na przemianowanej tabeli: indeksy (idx_social_message_contact,
--      idx_social_message_sender, idx_social_message_tenant_orphan_sent -- DB-059,
--      NOWY idx_social_message_tenant_sent_at -- DB-053-wzorzec), RLS (identyczna
--      z V099), COMMENT
--   7. Weryfikacja COUNT(*) stara == nowa (RAISE EXCEPTION przy niezgodnosci -> ROLLBACK calosci)
--   8. DROP TABLE social_message_old
--   9. Porzadkowanie nazw PK/FK/UNIQUE/indeksow z _new do finalnych
--  10. create_social_message_partition(p_year, p_month) (wzorzec create_contact_event_partition, V088)
--  11. CREATE OR REPLACE create_next_month_partitions() -- kopia AKTUALNEJ (V088)
--      definicji + jedna nowa linia PERFORM dla social_message (6 -> 7 tabel)
--  12. Koncowa weryfikacja strukturalna (RLS/FORCE/partycje/PK) -- RAISE EXCEPTION przy niezgodnosci
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard wolumenu -- powyzej progu migracja online-swap w jednej transakcji
--    nie jest bezpieczna strategia (patrz decyzja projektowa nr 3 w naglowku).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_existing_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_existing_count FROM social_message;

    IF v_existing_count > 10000 THEN
        RAISE EXCEPTION
            'V100 social_message partitioning aborted: % existing rows exceed the safe '
            'single-transaction online-swap threshold (10000). At this volume a single '
            'INSERT...SELECT would hold a long-lived lock and bloat WAL -- use a batched '
            'backfill strategy instead (see V088 header, part A) and do NOT run this '
            'migration unmodified.',
            v_existing_count;
    END IF;

    RAISE NOTICE 'V100 social_message partitioning: guard passed (% existing rows).', v_existing_count;
END $$;

-- ---------------------------------------------------------------------------
-- 1. Tabela blizniacza (partycjonowana) -- identyczne kolumny co social_message
--    dzis (V010 + V025 direction->VARCHAR + V028 contact_id nullable).
-- ---------------------------------------------------------------------------

CREATE TABLE social_message_new (
    message_id              UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id               UUID                NOT NULL,
    contact_id              UUID,
    integration_id          UUID,

    platform                social_platform     NOT NULL,
    direction                VARCHAR(20)        NOT NULL,

    external_message_id     VARCHAR(255)        NOT NULL,
    sender_external_id      VARCHAR(255),
    content                  TEXT,
    attachments              JSONB              NOT NULL DEFAULT '[]',

    sent_at                  TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
    received_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ       NOT NULL DEFAULT NOW(),

    -- Nazwa tymczasowa (_new) -- PK/UNIQUE tworza wlasna relacje-indeks w
    -- schemacie, ktorej nazwa musi byc unikalna globalnie, dopoki
    -- social_message (dzis, jeszcze nie przemianowana) nadal istnieje.
    -- Sprzatniete do finalnych nazw w sekcji 9.
    CONSTRAINT pk_social_message_new PRIMARY KEY (message_id, sent_at),

    CONSTRAINT fk_social_message_tenant_new
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_social_message_integration_new
        FOREIGN KEY (integration_id)
        REFERENCES social_integration (integration_id)
        ON DELETE SET NULL,

    -- DB-065: unikalnosc zlozona wymagana przez PostgreSQL dla tabel
    -- partycjonowanych (musi zawierac kolumne partycjonowania sent_at) --
    -- patrz OSTRZEZENIE DLA BE-132 w naglowku tego pliku o oslabieniu ochrony
    -- idempotentnosci dla Facebooka/Instagrama, dopoki BE-132 nie naprawi
    -- zrodla sent_at.
    CONSTRAINT uq_social_message_external_id_new
        UNIQUE (tenant_id, external_message_id, sent_at),

    -- CHECK nie tworzy osobnej relacji w schemacie -- nazwa jest unikalna
    -- tylko per-tabela, wiec finalna nazwa jest bezpieczna od razu (bez
    -- kolizji z dzisiejsza social_message, dopoki obie tabele istnieja
    -- rownolegle w tej migracji) -- wzorzec 1:1 z V085.
    CONSTRAINT chk_social_attachments_is_array
        CHECK (jsonb_typeof(attachments) = 'array'),

    CONSTRAINT chk_social_message_direction
        CHECK (direction IN ('INBOUND', 'OUTBOUND'))
) PARTITION BY RANGE (sent_at);

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne -- nazwy juz FINALNE (social_message_YYYY_MM), zeby
--    create_social_message_partition() (sekcja 10) rozpoznala je jako juz
--    istniejace (idempotentny wzorzec z V077/V085/V088: SELECT FROM pg_tables
--    WHERE tablename = ...) i nie probowala tworzyc duplikatow z nakladajacym
--    sie zakresem.
--    Zakres: 0 wierszy live -> "zakres danych" = biezacy miesiac (2026-10) +
--    2 kolejne (2026-11, 2026-12), zgodnie z zakresem DB-065.
-- ---------------------------------------------------------------------------

CREATE TABLE social_message_2026_10
    PARTITION OF social_message_new
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

CREATE TABLE social_message_2026_11
    PARTITION OF social_message_new
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

CREATE TABLE social_message_2026_12
    PARTITION OF social_message_new
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

-- Partycja domyslna -- fallback dla sent_at spoza utworzonych zakresow
-- miesiecznych (np. wiadomosci wstecznie zdatowane przez platforme, albo
-- miesiace jeszcze nieutworzone przez create_next_month_partitions()).
CREATE TABLE social_message_default
    PARTITION OF social_message_new DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Migracja danych (jawna lista kolumn -- nie polegamy na kolejnosci z SELECT *).
--    Live = 0 wierszy, ale kod jest ogolny (dziala tez przy > 0, do progu guarda).
-- ---------------------------------------------------------------------------

INSERT INTO social_message_new
    (message_id, tenant_id, contact_id, integration_id, platform, direction,
     external_message_id, sender_external_id, content, attachments,
     sent_at, received_at, created_at)
SELECT
    message_id, tenant_id, contact_id, integration_id, platform, direction,
    external_message_id, sender_external_id, content, attachments,
    sent_at, received_at, created_at
FROM social_message;

-- ---------------------------------------------------------------------------
-- 4. Online swap -- RENAME (nic z indeksow/RLS/constraintow starej tabeli nie
--    przechodzi na nowa; odtwarzamy jawnie w sekcji 5).
-- ---------------------------------------------------------------------------

ALTER TABLE social_message RENAME TO social_message_old;
ALTER TABLE social_message_new RENAME TO social_message;

-- ---------------------------------------------------------------------------
-- 5. Odtworzenie obiektow na nowej (juz przemianowanej) tabeli social_message.
-- ---------------------------------------------------------------------------

-- Indeksy wtorne budowane PO zaladowaniu danych. Nazwy tymczasowe (_new) --
-- finalne nazwy sa nadal zajete przez social_message_old do czasu jej
-- usuniecia w sekcji 8.
CREATE INDEX idx_social_message_contact_new
    ON social_message (contact_id, sent_at DESC);

CREATE INDEX idx_social_message_sender_new
    ON social_message (tenant_id, platform, sender_external_id);

-- DB-059 (V097): indeks czesciowy pod sweep wiadomosci OSIEROCONYCH wg wieku
-- (contact_id IS NULL), uzywany przez BE-127 (SocialMessageRepository
-- COUNT_ORPHANS_SQL / FIND_ORPHANS_*_SQL).
CREATE INDEX idx_social_message_tenant_orphan_sent_new
    ON social_message (tenant_id, sent_at)
    WHERE contact_id IS NULL;

-- COMMENT tresc 1:1 z oryginalu DB-059/V097 -- odtworzona tutaj, bo RENAME nie
-- dziedziczy automatycznie ze starej tabeli (nic z obiektow social_message_old
-- nie przechodzi przez RENAME, patrz naglowek sekcji 5); komentarz "podazy" za
-- OID-em indeksu przez pozniejszy ALTER INDEX ... RENAME w sekcji 8, wiec
-- wystarczy go ustawic raz, na nazwie _new.
COMMENT ON INDEX idx_social_message_tenant_orphan_sent_new
    IS 'DB-059 / BE-127: sweep wiadomosci social OSIEROCONYCH (contact_id IS '
       'NULL) per-tenant wedlug sent_at (NOT NULL -- bez COALESCE, w '
       'odroznieniu od email_message). Uzywany przez: SELECT ... FROM '
       'social_message WHERE tenant_id = ? AND contact_id IS NULL AND '
       'sent_at < ? (BE-127, jeszcze niezaimplementowany w chwili tej '
       'migracji).';

-- DB-065 (NOWY, wzorzec DB-053/V089): indeks pod purge per-tenant batchami
-- (RetentionPurgeService/BE-113), analogiczny do idx_contact_tenant_started_at
-- / idx_contact_transcription_tenant_created / idx_contact_ai_summary_tenant_generated.
-- Brak konfliktu nazwy ze stara tabela -- nazwa finalna od razu, bez sufiksu _new.
CREATE INDEX idx_social_message_tenant_sent_at
    ON social_message (tenant_id, sent_at);

COMMENT ON INDEX idx_social_message_tenant_sent_at
    IS 'DB-065 / BE-113: Purge retencyjny per-tenant batchami po sent_at. '
       'Uzywany przez RetentionPurgeService: DELETE FROM social_message WHERE '
       'tenant_id = ? AND sent_at < ?';

-- RLS -- ENABLE + FORCE + polityka IDENTYCZNA z V099 (DB-064): ALL + WITH
-- CHECK, GUC app.current_tenant_id (juz poprawny od V099, nic do "swiadomego
-- niepoprawiania" jak w V085/086/087).
ALTER TABLE social_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE social_message FORCE ROW LEVEL SECURITY;
CREATE POLICY social_message_tenant_isolation ON social_message
    FOR ALL
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- Komentarze (tresc 1:1 z V010, dopisana wzmianka o partycjonowaniu i o
-- oslabionej -- dla FB/IG do czasu BE-132 -- ochronie idempotentnosci).
COMMENT ON TABLE social_message IS
    'Wiadomosci z platform social media. Od V100 tabela partycjonowana RANGE '
    'po sent_at (PK zlozony message_id+sent_at); rotacja partycji (tworzenie '
    'kolejnych miesiecy) przez create_social_message_partition()/'
    'create_next_month_partitions() -- DROP starych partycji WYLACZNIE przez '
    'przyszly PartitionReclaimJob (BE-133, tylko puste partycje), nie przez '
    'SQL-owy backstop (w odroznieniu od contact_event/contact_transcription/'
    'contact_ai_summary). Idempotentnosc przez UNIQUE (tenant_id, '
    'external_message_id, sent_at) -- patrz COMMENT ON CONSTRAINT '
    'uq_social_message_external_id.';

COMMENT ON COLUMN social_message.external_message_id IS
    'ID wiadomosci z platformy social media. Gwarantuje idempotentnosc przy '
    'wielokrotnym delivery webhooka -- TYLKO gdy sent_at jest deterministyczny '
    '(patrz COMMENT ON CONSTRAINT uq_social_message_external_id).';

-- Odtworzone PO RENAME, wiec nazwa finalna docelowa (nie _new) jest juz
-- bezpieczna dla CONSTRAINT COMMENT (COMMENT ON CONSTRAINT nie tworzy wlasnej
-- relacji w schemacie -- odwoluje sie do nazwy biezacej w tej chwili migracji,
-- czyli jeszcze _new; finalny komentarz po przemianowaniu w sekcji 9 nie jest
-- potrzebny bo tresc komentarza "podazy" za obiektem przy RENAME CONSTRAINT).
COMMENT ON CONSTRAINT uq_social_message_external_id_new ON social_message IS
    'DB-065: unikalnosc zlozona wymagana przez PostgreSQL na tabeli '
    'partycjonowanej (musi zawierac kolumne partycjonowania sent_at). '
    'OSTRZEZENIE: ten constraint jest siecia bezpieczenstwa przed redelivery '
    'webhooka TYLKO gdy sent_at jest deterministyczny. WhatsApp: tak (czas z '
    'payloadu platformy). Facebook/Instagram: NIE, dopoki BE-132 nie naprawi '
    'SocialWebhookController#parseFacebookEvent/parseInstagramEvent (dzis '
    'Instant.now() w chwili przetworzenia webhooka, inny przy kazdym '
    'redelivery) -- dla tych dwoch platform jedyna realna obrona jest dzis '
    'dedup aplikacyjny SocialMessageServiceImpl#processIncomingMessage -> '
    'findByExternalMessageId.';

-- ---------------------------------------------------------------------------
-- 6. Weryfikacja zero-utraty-danych PRZED usunieciem starej tabeli. Cala
--    migracja Flyway (PostgreSQL) biegnie w jednej transakcji -- RAISE
--    EXCEPTION tutaj powoduje pelny ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    v_count_old BIGINT;
    v_count_new BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_count_old FROM social_message_old;
    SELECT COUNT(*) INTO v_count_new FROM social_message;

    IF v_count_old != v_count_new THEN
        RAISE EXCEPTION
            'V100 social_message partitioning aborted: row count mismatch (old=%, new=%)',
            v_count_old, v_count_new;
    END IF;

    RAISE NOTICE 'V100 social_message partitioning: row count verified (% rows).', v_count_new;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Usuniecie starej tabeli -- dopiero po pozytywnej weryfikacji powyzej.
--
--    ODKRYTE W DRY-RUNIE (nie przewidziane w tresci ticketu): widok
--    v_customer_timeline (V017, zmodyfikowany w V025) ma UNION ALL z
--    "FROM social_message sm" -- PostgreSQL rejestruje to jako twarda
--    zaleznosc (pg_depend) widoku od KONKRETNEGO OID tabeli. Po RENAME
--    (sekcja 4) widok "podazyl" za OID-em starej tabeli i dzis zalezy od
--    social_message_old -- DROP TABLE social_message_old bez wczesniejszego
--    usuniecia widoku konczy sie bledem 2BP01 ("cannot drop table ... because
--    other objects depend on it"). Zweryfikowane pg_depend na scratch: to
--    JEDYNY zalezny obiekt (zero funkcji/innych widokow/FK). Rozwiazanie --
--    ten sam wzorzec co V025 (ktora z tego samego powodu usuwala i odtwarzala
--    v_customer_timeline przy zmianie typow kolumn contact): DROP VIEW przed
--    DROP TABLE, CREATE OR REPLACE VIEW (tresc 1:1 z V025, teraz odnosi sie
--    do finalnej nazwy "social_message" = juz przemianowana NOWA, partycjonowana
--    tabela) od razu po.
-- ---------------------------------------------------------------------------

DROP VIEW IF EXISTS v_customer_timeline;

DROP TABLE social_message_old;

-- Odtworzenie widoku (tresc 1:1 z V025 -- jedyna roznica to fakt, ze
-- "social_message" odnosi sie teraz do nowej, partycjonowanej tabeli;
-- SQL samego widoku nie wymaga ZADNEJ zmiany, bo odpytuje tabele po nazwie,
-- nie po OID).
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
    'Od V100 social_message jest partycjonowana RANGE po sent_at -- widok '
    'odpytuje tabele nadrzedna, PostgreSQL routuje przez partycje automatycznie.';

-- ---------------------------------------------------------------------------
-- 8. Porzadkowanie nazw PK/FK/UNIQUE/indeksow do finalnych, konwencyjnych
--    nazw -- dopiero teraz sa wolne (stara tabela je zwolnila przez DROP
--    w sekcji 7).
-- ---------------------------------------------------------------------------

ALTER TABLE social_message RENAME CONSTRAINT pk_social_message_new TO pk_social_message;
ALTER TABLE social_message RENAME CONSTRAINT fk_social_message_tenant_new TO fk_social_message_tenant;
ALTER TABLE social_message RENAME CONSTRAINT fk_social_message_integration_new TO fk_social_message_integration;
ALTER TABLE social_message RENAME CONSTRAINT uq_social_message_external_id_new TO uq_social_message_external_id;

ALTER INDEX idx_social_message_contact_new RENAME TO idx_social_message_contact;
ALTER INDEX idx_social_message_sender_new RENAME TO idx_social_message_sender;
ALTER INDEX idx_social_message_tenant_orphan_sent_new RENAME TO idx_social_message_tenant_orphan_sent;

-- ---------------------------------------------------------------------------
-- 9. create_social_message_partition(p_year, p_month) -- wzorzec 1:1 z
--    create_contact_event_partition (V088). SWIADOMIE BEZ
--    drop_old_social_message_partitions()/rotate_social_message_partitions()
--    -- patrz decyzja projektowa nr 2 w naglowku tego pliku.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION create_social_message_partition(p_year INT, p_month INT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    v_start_date  DATE;
    v_end_date    DATE;
    v_table_name  TEXT;
BEGIN
    v_start_date := make_date(p_year, p_month, 1);
    v_end_date   := v_start_date + INTERVAL '1 month';
    v_table_name := 'social_message_' || to_char(v_start_date, 'YYYY_MM');

    -- Idempotentne: nie tworzy jesli juz istnieje (partycje 2026_10..2026_12
    -- juz utworzone bezposrednio w tej migracji z finalnymi nazwami).
    IF NOT EXISTS (
        SELECT FROM pg_tables
        WHERE schemaname = 'public' AND tablename = v_table_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF social_message FOR VALUES FROM (%L) TO (%L)',
            v_table_name, v_start_date, v_end_date
        );
        RAISE NOTICE 'Utworzono partycje: %', v_table_name;
    ELSE
        RAISE NOTICE 'Partycja % juz istnieje - pomijam.', v_table_name;
    END IF;
END;
$$;

COMMENT ON FUNCTION create_social_message_partition(INT, INT) IS
    'Tworzy miesieczna partycje tabeli social_message dla podanego roku i '
    'miesiaca. Idempotentna -- bezpieczna do wielokrotnego wywolania. Wzorzec '
    '1:1 z create_contact_event_partition (V088). Wywolywana WYLACZNIE przez '
    'create_next_month_partitions() -- w odroznieniu od contact_event/'
    'contact_transcription/contact_ai_summary/audit_log/plugin_invocation_log '
    'NIE ma wlasnej funkcji rotate_social_message_partitions()/'
    'drop_old_social_message_partitions(): usuwanie starych (pustych) partycji '
    'social_message idzie wylacznie przez przyszly PartitionReclaimJob (BE-133).';

-- ---------------------------------------------------------------------------
-- 10. Rozszerzenie zbiorczej funkcji create_next_month_partitions() -- CREATE
--     OR REPLACE, kopia AKTUALNEJ definicji z V088 (ostatnia, najnowsza wersja
--     w katalogu migracji tej galezi -- zweryfikowane grepem po
--     'create_next_month_partitions' przed napisaniem tej migracji: V093
--     wspomina ja tylko w komentarzu, nie redefiniuje), reszta logiki
--     zachowana 1:1, dodana WYLACZNIE jedna nowa linia PERFORM dla
--     social_message (6 -> 7 tabel partycjonowanych objetych funkcja
--     zbiorcza: audit_log, contact, plugin_invocation_log, contact_event,
--     contact_transcription, contact_ai_summary, social_message).
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

    INSERT INTO cron_log (job_name, finished_at, status, message)
    VALUES ('create_next_month_partitions', NOW(), 'SUCCESS',
            format('Utworzono partycje na %s-%s', v_next_year, LPAD(v_next_month::TEXT, 2, '0')));

    UPDATE scheduled_job
    SET last_run_at = NOW(), last_run_status = 'SUCCESS'
    WHERE job_name = 'create_next_month_partitions';
END;
$$;

-- ---------------------------------------------------------------------------
-- 11. Weryfikacja strukturalna koncowa -- RLS/FORCE/polityka, PK zlozony,
--     liczba partycji. Blad tutaj powoduje ROLLBACK calej migracji.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    v_force_rls           BOOLEAN;
    v_policy_count         INT;
    v_pk_column_count      INT;
    v_partition_count      INT;
BEGIN
    SELECT relforcerowsecurity INTO v_force_rls
    FROM pg_class WHERE relname = 'social_message';

    IF NOT v_force_rls THEN
        RAISE EXCEPTION 'V100: social_message nie ma FORCE ROW LEVEL SECURITY po migracji.';
    END IF;

    SELECT COUNT(*) INTO v_policy_count
    FROM pg_policies
    WHERE tablename = 'social_message'
      AND policyname = 'social_message_tenant_isolation'
      AND cmd = 'ALL'
      AND qual LIKE '%app.current_tenant_id%'
      AND with_check LIKE '%app.current_tenant_id%';

    IF v_policy_count <> 1 THEN
        RAISE EXCEPTION 'V100: oczekiwano 1 polityki social_message_tenant_isolation (ALL+WITH CHECK, app.current_tenant_id), znaleziono %', v_policy_count;
    END IF;

    SELECT COUNT(*) INTO v_pk_column_count
    FROM pg_constraint
    WHERE conrelid = 'social_message'::regclass AND contype = 'p';

    IF v_pk_column_count <> 1 THEN
        RAISE EXCEPTION 'V100: oczekiwano dokladnie 1 PRIMARY KEY na social_message, znaleziono %', v_pk_column_count;
    END IF;

    SELECT COUNT(*) INTO v_partition_count
    FROM pg_inherits
    WHERE inhparent = 'social_message'::regclass;

    IF v_partition_count <> 4 THEN
        RAISE EXCEPTION 'V100: oczekiwano 4 partycji social_message (3 miesieczne + default), znaleziono %', v_partition_count;
    END IF;

    RAISE NOTICE 'V100: OK -- social_message partycjonowana (% partycji), RLS ALL+WITH CHECK+FORCE (app.current_tenant_id).', v_partition_count;
END $$;
