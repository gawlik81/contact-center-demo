-- =============================================================================
-- V085__partition_contact_event.sql
-- DB-049: Partycjonowanie RANGE tabeli contact_event po started_at (EPIC-29).
--
-- Migracja: Flyway V085
-- Zaleznosci: V059 (DB-035, contact_event istnieje jako zwykla tabela)
-- Blokuje: DB-052 (V088, rotacja partycji), DB-053 (V089), BE-117 (@IdClass w Javie)
--
-- KONTEKST: pierwsza w tym repo ONLINE migracja partycjonujaca JUZ ISTNIEJACA
-- tabele z danymi (857 wierszy w dev) -- w odroznieniu od V007/V077, gdzie tabela
-- byla partycjonowana od razu przy CREATE TABLE. Wzorzec strukturalny (RANGE po
-- kolumnie czasowej, partycja DEFAULT, styl indeksow/RLS) jest identyczny do
-- plugin_invocation_log (V077, najswiezszy przyklad w repo) -- ale mechanika
-- samego online-swapu (tabela bliźniacza -> INSERT SELECT -> RENAME -> odtworzenie
-- obiektow -> weryfikacja -> DROP starej) jest tu zastosowana po raz pierwszy.
--
-- SWIADOME DECYZJE PROJEKTOWE (nie pomylki -- nie "poprawiac" bez osobnego ticketu):
--
-- 1. RLS: polityka odtworzona z TA SAMA, DZIS BLEDNA nazwa GUC `app.tenant_id`
--    (identycznie jak oryginalna V059) -- NIE `app.current_tenant_id`, mimo ze to
--    wlasnie ta druga nazwa jest faktycznie ustawiana przez aplikacje
--    (TenantAwareRepository.setTenantContextInDb() -> SELECT set_tenant_context(...)).
--    Naprawa tego GUC dla contact_event (razem z 3 innymi tabelami majacymi ten sam
--    blad: tenant_ai_config V064, contact_transcription V067, contact_ai_summary V068)
--    jest CELOWO wydzielona do osobnego, opcjonalnego ticketu DB-054 (V090) -- zeby
--    dalo sie ja latwo wycofac/pominac niezaleznie od tej migracji partycjonujacej.
--
-- 2. FORCE ROW LEVEL SECURITY dodane, mimo ze dzisiejsza (nie partycjonowana) tabela
--    go NIE ma (relforcerowsecurity=f, zweryfikowane w dev przed ta migracja) -- to
--    jedyne miejsce, gdzie ta migracja swiadomie zaostrza bezpieczenstwo wzgledem
--    stanu obecnego, zgodnie z kryteriami akceptacji DB-049.
--
-- 3. Funkcje rotacji partycji (create_contact_event_partition/drop_old_.../
--    rotate_contact_event_partitions, wpis w scheduled_job, rozszerzenie
--    create_next_month_partitions()) SWIADOMIE NIE sa tworzone w tej migracji --
--    to zakres DB-052 (V088), ktory dodaje ten mechanizm naraz dla 3 nowych tabel
--    partycjonowanych (contact_event, contact_transcription, contact_ai_summary).
--    Do czasu V088 miesiace poza utworzonymi tu partycjami trafiaja do partycji
--    DEFAULT (utworzonej ponizej) -- bezpieczny, ale nieoptymalny fallback.
--
-- 4. Wolumen danych w dev (857 wierszy) pozwala na pojedyncza transakcje
--    INSERT...SELECT bez batchowania. Cala migracja Flyway na PostgreSQL jest domyslnie
--    jedna transakcja (brak w niej instrukcji niedzialajacych w transakcji, np.
--    CREATE INDEX CONCURRENTLY) -- blok bezpieczenstwa RAISE EXCEPTION w sekcji 6
--    powoduje pelny ROLLBACK calej migracji, jesli liczba wierszy sie nie zgadza.
--    UWAGA NA PRZYSZLOSC (produkcja/wiekszy wolumen): przy duzo wiekszej tabeli
--    pojedynczy INSERT...SELECT trzymalby dlugotrwala blokade i naduzywal WAL --
--    strategia batchowa analogiczna do tej opisanej w DB-052 dla contact_default
--    (INSERT+DELETE w petli po zakresach czasu, poza jedna transakcja) bylaby wlasciwa.
--    Ten ticket swiadomie NIE buduje takiego mechanizmu dla 857 wierszy w dev.
--
-- Kolejnosc kroków (online swap):
--   1. CREATE TABLE contact_event_new (identyczne kolumny, PK zlozony
--      (event_id, started_at)) PARTITION BY RANGE (started_at)
--   2. Partycje: istniejacy zakres danych (2026_05..2026_08) + biezacy miesiac
--      (2026_08, juz pokryty) + 2 kolejne (2026_09, 2026_10) + DEFAULT
--   3. INSERT INTO contact_event_new SELECT ... FROM contact_event (jawna lista kolumn)
--   4. RENAME: contact_event -> contact_event_old, contact_event_new -> contact_event
--   5. Odtworzenie na nowej (juz przemianowanej) tabeli: indeksy wtorne (budowane PO
--      zaladowaniu danych -- szybciej niz budowanie w trakcie INSERT), trigger,
--      RLS+FORCE+CREATE POLICY, COMMENT ON. Nic z tego NIE jest dziedziczone przy RENAME.
--   6. Weryfikacja: COUNT(*) w contact_event == COUNT(*) w contact_event_old (blok DO,
--      RAISE EXCEPTION przy niezgodnosci -- abortuje cala migracje)
--   7. DROP TABLE contact_event_old (dopiero po pozytywnej weryfikacji)
--   8. Uporzadkowanie nazw PK/indeksow do finalnych, konwencyjnych nazw (PK i indeksy
--      buduja wlasny obiekt-relacje w schemacie -- nazwy musza byc unikalne w calym
--      schemacie, wiec dopoki stara tabela istnieje pod nazwami pk_contact_event /
--      idx_contact_event_contact / idx_contact_event_tenant, nowa tabela musi uzywac
--      tymczasowych nazw z sufiksem _new; CHECK/trigger/policy NIE maja tego problemu --
--      ich nazwy sa unikalne tylko per-tabela, wiec od razu dostaja finalne nazwy)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela blizniacza (partycjonowana) -- identyczne kolumny co contact_event dzis
-- ---------------------------------------------------------------------------

CREATE TABLE contact_event_new (
    event_id         UUID         NOT NULL DEFAULT uuid_generate_v4(),
    contact_id       UUID         NOT NULL,
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    stage            VARCHAR(20)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at         TIMESTAMPTZ,
    duration_seconds INT,
    metadata         JSONB        NOT NULL DEFAULT '{}',
    -- Nazwa tymczasowa (_new) -- indeks PK musi miec nazwe unikalna w calym schemacie,
    -- a "pk_contact_event" jest nadal zajete przez dzisiejsza (jeszcze nie przemianowana)
    -- tabele. Sprzatniete do finalnej nazwy pk_contact_event w sekcji 8.
    CONSTRAINT pk_contact_event_new PRIMARY KEY (event_id, started_at),
    -- CHECK nie tworzy osobnej relacji w schemacie -- nazwa jest unikalna tylko
    -- per-tabela, wiec finalna nazwa jest bezpieczna od razu (bez kolizji z dzisiejsza
    -- contact_event, dopoki obie tabele istnieja rownolegle w tej migracji).
    CONSTRAINT chk_contact_event_stage CHECK (
        stage IN ('IVR', 'VOICEBOT', 'QUEUE', 'AGENT', 'ON_HOLD', 'CONSULTING', 'TRANSFER')
    ),
    CONSTRAINT chk_contact_event_times CHECK (
        ended_at IS NULL OR ended_at >= started_at
    )
) PARTITION BY RANGE (started_at);

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne -- nazwy juz FINALNE (contact_event_YYYY_MM), zeby
--    przyszla funkcja create_contact_event_partition() z DB-052/V088 rozpoznala
--    je jako juz istniejace (idempotentny wzorzec z V077: SELECT FROM pg_tables
--    WHERE tablename = 'contact_event_' || to_char(...)) i nie probowala tworzyc
--    duplikatow z nakladajacym sie zakresem.
--    Zakres: istniejace dane (2026-05-14 .. 2026-08-04) + biezacy miesiac (2026-08,
--    juz pokryty przez zakres danych) + 2 kolejne (2026-09, 2026-10).
-- ---------------------------------------------------------------------------

CREATE TABLE contact_event_2026_05
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE contact_event_2026_06
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE contact_event_2026_07
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE contact_event_2026_08
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE contact_event_2026_09
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE contact_event_2026_10
    PARTITION OF contact_event_new
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

-- Partycja domyslna -- fallback dla started_at spoza utworzonych zakresow
-- miesiecznych (bezpieczenstwo, nie gubimy rekordow do czasu V088).
CREATE TABLE contact_event_default
    PARTITION OF contact_event_new DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Migracja danych (jawna lista kolumn -- nie polegamy na kolejnosci z SELECT *)
-- ---------------------------------------------------------------------------

INSERT INTO contact_event_new
    (event_id, contact_id, tenant_id, stage, started_at, ended_at, duration_seconds, metadata)
SELECT
    event_id, contact_id, tenant_id, stage, started_at, ended_at, duration_seconds, metadata
FROM contact_event;

-- ---------------------------------------------------------------------------
-- 4. Online swap -- RENAME (nic z indeksow/trigera/RLS/constraintow starej
--    tabeli nie przechodzi na nowa; odtwarzamy jawnie w sekcji 5)
-- ---------------------------------------------------------------------------

ALTER TABLE contact_event RENAME TO contact_event_old;
ALTER TABLE contact_event_new RENAME TO contact_event;

-- ---------------------------------------------------------------------------
-- 5. Odtworzenie obiektow na nowej (juz przemianowanej) tabeli contact_event
-- ---------------------------------------------------------------------------

-- Indeksy wtorne budowane PO zaladowaniu danych (szybciej niz w trakcie INSERT).
-- Nazwy tymczasowe (_new) -- "idx_contact_event_contact"/"idx_contact_event_tenant"
-- sa nadal zajete przez contact_event_old do czasu jej usuniecia w sekcji 7.
CREATE INDEX idx_contact_event_new_contact
    ON contact_event (contact_id, started_at);

CREATE INDEX idx_contact_event_new_tenant
    ON contact_event (tenant_id, started_at DESC);

-- Trigger: nazwa finalna od razu (unikalnosc tylko per-tabela, brak kolizji).
-- Funkcja fn_contact_event_on_update() juz istnieje w bazie i jest w pelni
-- tabelo-agnostyczna (operuje na NEW/OLD) -- nie trzeba jej odtwarzac.
CREATE TRIGGER trg_contact_event_on_update
    BEFORE UPDATE ON contact_event
    FOR EACH ROW EXECUTE FUNCTION fn_contact_event_on_update();

-- RLS -- ENABLE + FORCE (swiadome zaostrzenie wzgledem stanu dzisiejszego, patrz
-- decyzja nr 2 w naglowku) + polityka z niezmieniona, dzis blledna nazwa GUC
-- app.tenant_id (swiadoma decyzja nr 1 w naglowku -- NIE app.current_tenant_id).
ALTER TABLE contact_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_event FORCE ROW LEVEL SECURITY;
CREATE POLICY contact_event_tenant_isolation ON contact_event
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);

-- Komentarze (tresc 1:1 z V059, dopisana wzmianka o partycjonowaniu)
COMMENT ON TABLE contact_event IS
    'Historia etapow kontaktu. Jeden rekord per zdarzenie: wejscie do IVR, '
    'obsluga przez bota VOICEBOT, oczekiwanie w kolejce, obsluga przez agenta, '
    'wstrzymanie (hold), faza konsultacji (attended transfer), przekazanie. '
    'Od V085 tabela partycjonowana RANGE po started_at (PK zlozony event_id+started_at); '
    'rotacja partycji (tworzenie kolejnych miesiecy / usuwanie starych) dostarczona w DB-052 (V088).';

COMMENT ON COLUMN contact_event.stage IS
    'IVR = obsluga w drzewie IVR (wezly MENU/DTMF/PLAY_AUDIO), '
    'VOICEBOT = obsluga przez bota ASR+NLU (wezel VOICEBOT), '
    'QUEUE = oczekiwanie w kolejce, '
    'AGENT = obsluga przez agenta, '
    'ON_HOLD = wstrzymanie polaczenia, '
    'CONSULTING = faza konsultacji przy attended transfer, '
    'TRANSFER = zdarzenie przekazania kontaktu (punkt w czasie, started_at = ended_at).';

COMMENT ON COLUMN contact_event.metadata IS
    'Kontekst etapu. '
    'IVR/VOICEBOT: {"ivr_tree_id":"...", "ivr_tree_name":"...", "outcome":"ESCALATED|COMPLETED|ERROR"}. '
    'QUEUE: {"queue_id":"...", "queue_name":"..."}. '
    'AGENT: {"agent_id":"...", "agent_name":"..."}. '
    'ON_HOLD: {}. '
    'CONSULTING: {"target":"+48...", "transfer_type":"ATTENDED"}. '
    'TRANSFER: {"target":"+48...", "transfer_type":"BLIND|ATTENDED", "target_agent_name":"..."}.';

-- ---------------------------------------------------------------------------
-- 6. Weryfikacja zero-utraty-danych PRZED usunieciem starej tabeli.
--    Cala migracja Flyway (PostgreSQL, brak instrukcji niedzialajacych w
--    transakcji powyzej) biegnie w jednej transakcji -- RAISE EXCEPTION tutaj
--    powoduje pelny ROLLBACK calej migracji (nic sie nie zmienia w bazie).
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    v_count_old BIGINT;
    v_count_new BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_count_old FROM contact_event_old;
    SELECT COUNT(*) INTO v_count_new FROM contact_event;

    IF v_count_old != v_count_new THEN
        RAISE EXCEPTION
            'V085 contact_event partitioning aborted: row count mismatch (old=%, new=%)',
            v_count_old, v_count_new;
    END IF;

    RAISE NOTICE 'V085 contact_event partitioning: row count verified (% rows).', v_count_new;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Usuniecie starej tabeli -- dopiero po pozytywnej weryfikacji powyzej
-- ---------------------------------------------------------------------------

DROP TABLE contact_event_old;

-- ---------------------------------------------------------------------------
-- 8. Porzadkowanie nazw PK/indeksow do finalnych, konwencyjnych nazw -- dopiero
--    teraz "pk_contact_event"/"idx_contact_event_contact"/"idx_contact_event_tenant"
--    sa wolne (stara tabela je zwolnila przez DROP w sekcji 7). Rowniez auto-nazwany
--    FK (utworzony na etapie CREATE TABLE contact_event_new jako
--    contact_event_new_tenant_id_fkey) wraca do oryginalnej nazwy dla spojnosci.
-- ---------------------------------------------------------------------------

ALTER TABLE contact_event RENAME CONSTRAINT pk_contact_event_new TO pk_contact_event;
ALTER TABLE contact_event RENAME CONSTRAINT contact_event_new_tenant_id_fkey TO contact_event_tenant_id_fkey;
ALTER INDEX idx_contact_event_new_contact RENAME TO idx_contact_event_contact;
ALTER INDEX idx_contact_event_new_tenant RENAME TO idx_contact_event_tenant;
