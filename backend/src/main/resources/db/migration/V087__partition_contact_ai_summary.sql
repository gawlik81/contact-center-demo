-- =============================================================================
-- V087__partition_contact_ai_summary.sql
-- DB-051: Partycjonowanie RANGE tabeli contact_ai_summary po generated_at (EPIC-29).
--
-- Migracja: Flyway V087
-- Zaleznosci: V068 (DB-039, contact_ai_summary istnieje jako zwykla tabela)
-- Blokuje: DB-052 (V088, rotacja partycji), DB-053 (V089), BE-117 (@IdClass w Javie)
--
-- KONTEKST: TRZECIA (po DB-049/V085 contact_event i DB-050/V086 contact_transcription)
-- online migracja partycjonujaca JUZ ISTNIEJACA tabele z danymi w tym repo. Wzorzec
-- strukturalny i mechanika online-swapu (tabela bliźniacza -> INSERT SELECT -> RENAME
-- -> odtworzenie obiektow -> weryfikacja COUNT -> DROP starej -> sprzatanie nazw
-- PK/indeksow do finalnych) jest 1:1 identyczna jak w V085 (patrz tam pelny opis
-- kroków i uzasadnienie kazdego z nich -- tutaj tylko roznice specyficzne dla tej
-- tabeli).
--
-- DECYZJA ARCHITEKTONICZNA (ticket explicite deleguje ja do db-schema-architect):
-- kolumna partycjonowania = generated_at (NIE created_at). Uzasadnienie:
--   1. Semantyka: generated_at to moment, w ktorym model AI faktycznie wyprodukowal
--      tresc podsumowania -- to jest biznesowo istotny "wiek" danych (kiedy ta
--      konkretna tresc powstala), analogicznie do started_at w contact_event/contact
--      (moment zdarzenia biznesowego, nie moment zapisu wiersza w bazie).
--      created_at to wylacznie techniczny znacznik "kiedy wiersz trafil do bazy" --
--      w tym przypadku niemal zawsze know w kilka sekund/minut po generated_at
--      (backend zapisuje wiersz od razu po otrzymaniu odpowiedzi z modelu AI), wiec
--      obie kolumny sa sobie bliskie w praktyce, ale generated_at jest tym, czego
--      dotyczyc beda przyszle polityki retencji/purge (DB-052/DB-053) -- retencja
--      podsumowan AI ma sens liczona od momentu wygenerowania tresci, nie od
--      przypadkowego opoznienia zapisu do bazy.
--   2. Rozbieznosc jest weryfikowalna w dev: MIN/MAX(generated_at) = 2026-05-24..
--      2026-07-29, MIN/MAX(created_at) = 2026-05-25..2026-07-29 -- co do dnia
--      zgodne, wiec wybor kolumny partycjonujacej nie zmienia liczby/zakresu
--      wymaganych partycji w tej migracji, ale ma znaczenie dla poprawnosci
--      semantycznej przyszlych zapytan/purge po tej granicy.
--   3. Alternatywa (created_at) bylaby rownie broniona -- jest to jedyna kolumna
--      czasowa w tabelach-siostrach bez wlasnego "momentu zdarzenia biznesowego"
--      (np. contact_transcription/V086 partycjonuje po created_at wlasnie dlatego,
--      ze nie ma tam odpowiednika generated_at). Tutaj jednak generated_at istnieje
--      i niesie wiecej informacji -- dlatego wybrano ja jako kolumne partycjonujaca.
-- PK zlozony spojny z ta decyzja: (ai_summary_id, generated_at).
--
-- SWIADOME DECYZJE PROJEKTOWE PRZENIESIONE 1:1 Z V085/V086 (nie pomylki -- nie
-- "poprawiac" bez osobnego ticketu):
--
-- 1. RLS: polityka odtworzona z TA SAMA, DZIS BLEDNA nazwa GUC `app.tenant_id`
--    (identycznie jak oryginalna V068) -- NIE `app.current_tenant_id`, mimo ze to
--    wlasnie ta druga nazwa jest faktycznie ustawiana przez aplikacje
--    (TenantAwareRepository.setTenantContextInDb() -> SELECT set_tenant_context(...)).
--    Naprawa tego GUC (razem z tenant_ai_config/V064, contact_transcription/V067,
--    contact_event/V059 -- te same 4 tabele z tym samym bledem) jest CELOWO
--    wydzielona do osobnego, opcjonalnego ticketu DB-054 (V090).
--
-- 2. FORCE ROW LEVEL SECURITY dodane, mimo ze dzisiejsza (nie partycjonowana) tabela
--    go NIE ma (relforcerowsecurity=f, zweryfikowane w dev przed ta migracja przez
--    `SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname=
--    'contact_ai_summary'` -> t/f) -- to jedyne miejsce, gdzie ta migracja swiadomie
--    zaostrza bezpieczenstwo wzgledem stanu obecnego, zgodnie z kryteriami akceptacji
--    DB-051.
--
-- 3. Funkcje rotacji partycji (create_contact_ai_summary_partition/drop_old_.../
--    rotate_contact_ai_summary_partitions, wpis w scheduled_job, rozszerzenie
--    create_next_month_partitions()) SWIADOMIE NIE sa tworzone w tej migracji --
--    to zakres DB-052 (V088), ktory dodaje ten mechanizm naraz dla 3 nowych tabel
--    partycjonowanych (contact_event, contact_transcription, contact_ai_summary).
--    Do czasu V088 miesiace poza utworzonymi tu partycjami trafiaja do partycji
--    DEFAULT (utworzonej ponizej) -- bezpieczny, ale nieoptymalny fallback.
--
-- 4. Brak FK w tej tabeli (potwierdzone `\d contact_ai_summary` i zapytaniem do
--    pg_constraint w dev -- jedyny constraint to PK, zero FK) -- contact_ai_summary
--    nigdy nie mialo FK do contact/tenant (contact jest partycjonowana, PostgreSQL
--    nie wspiera FK do partycji ze strony child -- zob. komentarz w oryginalnej
--    V068). Nic do odtworzenia w tym zakresie. Brak rowniez triggerow.
--
-- 5. Wolumen danych w dev (57 wierszy, zweryfikowane COUNT(*) przed ta migracja)
--    pozwala na pojedyncza transakcje INSERT...SELECT bez batchowania -- identycznie
--    jak w V085 (857 wierszy) i V086. Cala migracja Flyway na PostgreSQL jest
--    domyslnie jedna transakcja -- blok bezpieczenstwa RAISE EXCEPTION w sekcji 6
--    powoduje pelny ROLLBACK calej migracji, jesli liczba wierszy sie nie zgadza.
--
-- Kolejnosc kroków (online swap, identyczna jak V085/V086):
--   1. CREATE TABLE contact_ai_summary_new (identyczne kolumny, PK zlozony
--      (ai_summary_id, generated_at)) PARTITION BY RANGE (generated_at)
--   2. Partycje: istniejacy zakres danych (2026_05..2026_07) + biezacy miesiac
--      (2026_08) + 2 kolejne (2026_09, 2026_10) + DEFAULT
--   3. INSERT INTO contact_ai_summary_new SELECT ... FROM contact_ai_summary
--      (jawna lista kolumn, nie SELECT *)
--   4. RENAME: contact_ai_summary -> contact_ai_summary_old,
--      contact_ai_summary_new -> contact_ai_summary
--   5. Odtworzenie na nowej (juz przemianowanej) tabeli: indeks wtorny (budowany PO
--      zaladowaniu danych), RLS+FORCE+CREATE POLICY, COMMENT ON. Nic z tego NIE jest
--      dziedziczone przy RENAME.
--   6. Weryfikacja: COUNT(*) w contact_ai_summary == COUNT(*) w
--      contact_ai_summary_old (blok DO, RAISE EXCEPTION przy niezgodnosci)
--   7. DROP TABLE contact_ai_summary_old (dopiero po pozytywnej weryfikacji)
--   8. Uporzadkowanie nazw PK/indeksu do finalnych, konwencyjnych nazw (PK i indeks
--      buduja wlasna relacje w schemacie -- nazwa musi byc unikalna w calym
--      schemacie, wiec dopoki stara tabela istnieje pod nazwami
--      contact_ai_summary_pkey / idx_contact_ai_summary_contact, nowa tabela musi
--      uzywac tymczasowych nazw z sufiksem _new)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela blizniacza (partycjonowana) -- identyczne kolumny co contact_ai_summary
--    dzis (zweryfikowane \d contact_ai_summary w dev przed ta migracja)
-- ---------------------------------------------------------------------------

CREATE TABLE contact_ai_summary_new (
    ai_summary_id UUID         NOT NULL DEFAULT gen_random_uuid(),
    contact_id    UUID         NOT NULL,
    tenant_id     UUID         NOT NULL,
    summary       TEXT         NOT NULL,
    model         VARCHAR(100) NOT NULL,
    generated_at  TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Nazwa tymczasowa (_new) -- oryginalny PK tabeli byl autonazwany przez Postgres
    -- (contact_ai_summary_pkey, bo V068 uzylo inline PRIMARY KEY bez CONSTRAINT).
    -- Ta nazwa jest nadal zajeta przez dzisiejsza (jeszcze nie przemianowana) tabele.
    -- Sprzatniete do finalnej, konwencyjnej nazwy pk_contact_ai_summary w sekcji 8
    -- (ta sama konwencja jawnego nazewnictwa co pk_contact_event w V085).
    CONSTRAINT pk_contact_ai_summary_new PRIMARY KEY (ai_summary_id, generated_at)
) PARTITION BY RANGE (generated_at);

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne -- nazwy juz FINALNE (contact_ai_summary_YYYY_MM), zeby
--    przyszla funkcja create_contact_ai_summary_partition() z DB-052/V088 rozpoznala
--    je jako juz istniejace (idempotentny wzorzec z V077) i nie probowala tworzyc
--    duplikatow z nakladajacym sie zakresem.
--    Zakres: istniejace dane wg generated_at (2026-05-24 .. 2026-07-29, zweryfikowane
--    MIN/MAX(generated_at) przed pisaniem migracji) + biezacy miesiac (2026-08) +
--    2 kolejne (2026-09, 2026-10).
-- ---------------------------------------------------------------------------

CREATE TABLE contact_ai_summary_2026_05
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE contact_ai_summary_2026_06
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE contact_ai_summary_2026_07
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE contact_ai_summary_2026_08
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE contact_ai_summary_2026_09
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE contact_ai_summary_2026_10
    PARTITION OF contact_ai_summary_new
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

-- Partycja domyslna -- fallback dla generated_at spoza utworzonych zakresow
-- miesiecznych (bezpieczenstwo, nie gubimy rekordow do czasu V088).
CREATE TABLE contact_ai_summary_default
    PARTITION OF contact_ai_summary_new DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Migracja danych (jawna lista kolumn -- nie polegamy na kolejnosci z SELECT *)
-- ---------------------------------------------------------------------------

INSERT INTO contact_ai_summary_new
    (ai_summary_id, contact_id, tenant_id, summary, model, generated_at, created_at)
SELECT
    ai_summary_id, contact_id, tenant_id, summary, model, generated_at, created_at
FROM contact_ai_summary;

-- ---------------------------------------------------------------------------
-- 4. Online swap -- RENAME (nic z indeksow/RLS/constraintow starej tabeli nie
--    przechodzi na nowa; odtwarzamy jawnie w sekcji 5)
-- ---------------------------------------------------------------------------

ALTER TABLE contact_ai_summary RENAME TO contact_ai_summary_old;
ALTER TABLE contact_ai_summary_new RENAME TO contact_ai_summary;

-- ---------------------------------------------------------------------------
-- 5. Odtworzenie obiektow na nowej (juz przemianowanej) tabeli contact_ai_summary
-- ---------------------------------------------------------------------------

-- Indeks wtorny budowany PO zaladowaniu danych (szybciej niz w trakcie INSERT).
-- Nazwa tymczasowa (_new) -- "idx_contact_ai_summary_contact" jest nadal zajete
-- przez contact_ai_summary_old do czasu jej usuniecia w sekcji 7.
CREATE INDEX idx_contact_ai_summary_new_contact
    ON contact_ai_summary (contact_id, tenant_id);

-- RLS -- ENABLE + FORCE (swiadome zaostrzenie wzgledem stanu dzisiejszego, patrz
-- decyzja nr 2 w naglowku) + polityka z niezmieniona, dzis blledna nazwa GUC
-- app.tenant_id (swiadoma decyzja nr 1 w naglowku -- NIE app.current_tenant_id).
ALTER TABLE contact_ai_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_ai_summary FORCE ROW LEVEL SECURITY;
CREATE POLICY contact_ai_summary_isolation ON contact_ai_summary
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

-- Komentarze (tresc 1:1 z V068, dopisana wzmianka o partycjonowaniu)
COMMENT ON TABLE contact_ai_summary IS
    'Podsumowania kontaktów generowane przez AI (np. Claude, GPT). '
    'Wyodrębnione z tabeli contact do osobnej tabeli, by uniknąć rozrostu '
    'kolumn w partycjonowanej tabeli faktów. Brak FK — contact jest partycjonowana. '
    'Od V087 tabela partycjonowana RANGE po generated_at (PK zlozony '
    'ai_summary_id+generated_at, swiadomie NIE po created_at — generated_at to moment '
    'wygenerowania tresci przez model AI, biznesowo istotniejszy niz moment zapisu '
    'wiersza; uzasadnienie pelne w naglowku migracji V087); rotacja partycji '
    '(tworzenie kolejnych miesiecy / usuwanie starych) dostarczona w DB-052 (V088).';
COMMENT ON COLUMN contact_ai_summary.contact_id IS
    'Logiczne powiązanie z contact.contact_id. Brak FK constraint — tabela contact '
    'jest partycjonowana RANGE, PostgreSQL nie obsługuje FK do partycji ze strony child.';
COMMENT ON COLUMN contact_ai_summary.tenant_id IS
    'Identyfikator tenanta — wymagany przez RLS do izolacji danych.';
COMMENT ON COLUMN contact_ai_summary.summary IS
    'Pełne podsumowanie kontaktu wygenerowane przez model AI.';
COMMENT ON COLUMN contact_ai_summary.model IS
    'Nazwa modelu AI który wygenerował podsumowanie, np. claude-opus-4-7, gpt-4o.';
COMMENT ON COLUMN contact_ai_summary.generated_at IS
    'Timestamp wygenerowania podsumowania przez model AI. Od V087: kolumna '
    'partycjonowania (RANGE, miesięcznie) i część PK złożonego.';

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
    SELECT COUNT(*) INTO v_count_old FROM contact_ai_summary_old;
    SELECT COUNT(*) INTO v_count_new FROM contact_ai_summary;

    IF v_count_old != v_count_new THEN
        RAISE EXCEPTION
            'V087 contact_ai_summary partitioning aborted: row count mismatch (old=%, new=%)',
            v_count_old, v_count_new;
    END IF;

    RAISE NOTICE 'V087 contact_ai_summary partitioning: row count verified (% rows).', v_count_new;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Usuniecie starej tabeli -- dopiero po pozytywnej weryfikacji powyzej
-- ---------------------------------------------------------------------------

DROP TABLE contact_ai_summary_old;

-- ---------------------------------------------------------------------------
-- 8. Porzadkowanie nazw PK/indeksu do finalnych, konwencyjnych nazw -- dopiero
--    teraz "contact_ai_summary_pkey"/"idx_contact_ai_summary_contact" sa wolne
--    (stara tabela je zwolnila przez DROP w sekcji 7). PK dostaje konwencyjna
--    nazwe pk_contact_ai_summary (jawne nazewnictwo, ta sama konwencja co
--    pk_contact_event w V085), zamiast wracac do autonazwanej
--    contact_ai_summary_pkey z oryginalnej V068.
-- ---------------------------------------------------------------------------

ALTER TABLE contact_ai_summary RENAME CONSTRAINT pk_contact_ai_summary_new TO pk_contact_ai_summary;
ALTER INDEX idx_contact_ai_summary_new_contact RENAME TO idx_contact_ai_summary_contact;
