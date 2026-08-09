-- =============================================================================
-- V086__partition_contact_transcription.sql
-- DB-050: Partycjonowanie RANGE tabeli contact_transcription po created_at (EPIC-29).
--
-- Migracja: Flyway V086
-- Zaleznosci: V067 (contact_transcription istnieje jako zwykla tabela)
-- Blokuje: DB-052 (V088, rotacja partycji), DB-053 (V089), BE-117 (@IdClass w Javie)
--
-- KONTEKST: DRUGA online migracja partycjonujaca JUZ ISTNIEJACA tabele z danymi
-- w tym repo (po V085/contact_event/DB-049). Ten sam wzorzec swap: tabela
-- blizniacza -> INSERT SELECT -> RENAME -> odtworzenie obiektow -> weryfikacja
-- COUNT -> DROP starej. Prostsza niz V085: brak FK (tenant_id/contact_id bez
-- REFERENCES, potwierdzone \d w dev), brak triggera, brak CHECK constraints --
-- tylko PK zlozony, jeden indeks wtorny i RLS.
--
-- SWIADOME DECYZJE PROJEKTOWE (nie pomylki -- nie "poprawiac" bez osobnego ticketu):
--
-- 1. RLS: polityka odtworzona z TA SAMA, DZIS BLEDNA nazwa GUC `app.tenant_id`
--    (identycznie jak oryginalna V067) -- NIE `app.current_tenant_id`. Naprawa
--    tego GUC (razem z tenant_ai_config V064, contact_event V059/V085,
--    contact_ai_summary V068) jest CELOWO wydzielona do osobnego, opcjonalnego
--    ticketu DB-054 (V090) -- zeby dalo sie ja latwo wycofac/pominac niezaleznie
--    od tej migracji partycjonujacej.
--
-- 2. FORCE ROW LEVEL SECURITY dodane, mimo ze dzisiejsza (nie partycjonowana)
--    tabela go NIE ma (relforcerowsecurity=f, zweryfikowane w dev przed ta
--    migracja) -- to jedyne miejsce, gdzie ta migracja swiadomie zaostrza
--    bezpieczenstwo wzgledem stanu obecnego, zgodnie z kryteriami akceptacji DB-050.
--
-- 3. Indeks idx_contact_transcription_contact odtworzony DOKLADNIE w tej samej
--    kolejnosci kolumn (contact_id, tenant_id) co dzisiaj, mimo ze nie pokrywa
--    efektywnie zapytan retencji WHERE tenant_id = ? AND created_at < ? --
--    nowy indeks (tenant_id, created_at) dochodzi OSOBNO w DB-053 (V089).
--    Ten ticket swiadomie NIE "ulepsza" tego indeksu teraz.
--
-- 4. Funkcje rotacji partycji (create_contact_transcription_partition/
--    drop_old_.../rotate_..., wpis w scheduled_job, rozszerzenie
--    create_next_month_partitions()) SWIADOMIE NIE sa tworzone w tej migracji --
--    to zakres DB-052 (V088), ktory dodaje ten mechanizm naraz dla contact_event,
--    contact_transcription i contact_ai_summary. Do czasu V088 miesiace poza
--    utworzonymi tu partycjami trafiaja do partycji DEFAULT (utworzonej ponizej).
--
-- 5. Wolumen danych w dev (50 wierszy) pozwala na pojedyncza transakcje
--    INSERT...SELECT bez batchowania. Cala migracja Flyway na PostgreSQL jest
--    domyslnie jedna transakcja -- blok bezpieczenstwa RAISE EXCEPTION w sekcji 6
--    powoduje pelny ROLLBACK calej migracji, jesli liczba wierszy sie nie zgadza.
--
-- 6. contact_transcription NIE ma encji JPA (czysty JdbcTemplate --
--    ContactTranscriptionRepository) -- ta migracja jest wylacznie SQL. Zmiana
--    zapytan repozytorium (dodanie created_at do adresowania wiersza po PK)
--    jest w zakresie BE-117, poza tym ticketem.
--
-- Kolejnosc krokow (online swap, identyczna jak V085):
--   1. CREATE TABLE contact_transcription_new (identyczne kolumny, PK zlozony
--      (transcription_id, created_at)) PARTITION BY RANGE (created_at)
--   2. Partycje: istniejacy zakres danych (2026_05..2026_07) + biezacy miesiac
--      (2026_08) + 2 kolejne (2026_09, 2026_10) + DEFAULT
--   3. INSERT INTO contact_transcription_new SELECT ... FROM contact_transcription
--      (jawna lista kolumn)
--   4. RENAME: contact_transcription -> contact_transcription_old,
--      contact_transcription_new -> contact_transcription
--   5. Odtworzenie na nowej (juz przemianowanej) tabeli: indeks wtorny (budowany
--      PO zaladowaniu danych), RLS+FORCE+CREATE POLICY, COMMENT ON. Brak triggera
--      i brak FK do odtworzenia (tabela ich nie ma).
--   6. Weryfikacja: COUNT(*) w contact_transcription == COUNT(*) w
--      contact_transcription_old (blok DO, RAISE EXCEPTION przy niezgodnosci)
--   7. DROP TABLE contact_transcription_old (dopiero po pozytywnej weryfikacji)
--   8. Uporzadkowanie nazw PK/indeksu do finalnych, konwencyjnych nazw (PK i
--      indeks buduja wlasny obiekt-relacje w schemacie -- nazwy musza byc unikalne
--      w calym schemacie, wiec dopoki stara tabela istnieje pod nazwami
--      contact_transcription_pkey / idx_contact_transcription_contact, nowa
--      tabela musi uzywac tymczasowych nazw z sufiksem _new)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela blizniacza (partycjonowana) -- identyczne kolumny co
--    contact_transcription dzis. PK bez jawnej nazwy CONSTRAINT (identycznie
--    jak w V067) -- Postgres auto-nazwie backing index jako
--    "contact_transcription_new_pkey", sprzatniete do finalnej nazwy w sekcji 8.
-- ---------------------------------------------------------------------------

CREATE TABLE contact_transcription_new (
    transcription_id  UUID        NOT NULL DEFAULT gen_random_uuid(),
    contact_id        UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    content           TEXT        NOT NULL,
    language          VARCHAR(10),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (transcription_id, created_at)
) PARTITION BY RANGE (created_at);

-- ---------------------------------------------------------------------------
-- 2. Partycje inicjalne -- nazwy juz FINALNE (contact_transcription_YYYY_MM),
--    zeby przyszla funkcja create_contact_transcription_partition() z DB-052
--    (V088) rozpoznala je jako juz istniejace i nie probowala tworzyc
--    duplikatow z nakladajacym sie zakresem.
--    Zakres: istniejace dane (2026-05-24 .. 2026-07-29, zweryfikowane
--    MIN/MAX(created_at) przed pisaniem migracji) + biezacy miesiac (2026-08)
--    + 2 kolejne (2026-09, 2026-10).
-- ---------------------------------------------------------------------------

CREATE TABLE contact_transcription_2026_05
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE TABLE contact_transcription_2026_06
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');

CREATE TABLE contact_transcription_2026_07
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

CREATE TABLE contact_transcription_2026_08
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');

CREATE TABLE contact_transcription_2026_09
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE contact_transcription_2026_10
    PARTITION OF contact_transcription_new
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');

-- Partycja domyslna -- fallback dla created_at spoza utworzonych zakresow
-- miesiecznych (bezpieczenstwo, nie gubimy rekordow do czasu V088).
CREATE TABLE contact_transcription_default
    PARTITION OF contact_transcription_new DEFAULT;

-- ---------------------------------------------------------------------------
-- 3. Migracja danych (jawna lista kolumn -- nie polegamy na kolejnosci z SELECT *)
-- ---------------------------------------------------------------------------

INSERT INTO contact_transcription_new
    (transcription_id, contact_id, tenant_id, content, language, created_at)
SELECT
    transcription_id, contact_id, tenant_id, content, language, created_at
FROM contact_transcription;

-- ---------------------------------------------------------------------------
-- 4. Online swap -- RENAME (nic z indeksow/RLS/constraintow starej tabeli
--    nie przechodzi na nowa; odtwarzamy jawnie w sekcji 5)
-- ---------------------------------------------------------------------------

ALTER TABLE contact_transcription RENAME TO contact_transcription_old;
ALTER TABLE contact_transcription_new RENAME TO contact_transcription;

-- ---------------------------------------------------------------------------
-- 5. Odtworzenie obiektow na nowej (juz przemianowanej) tabeli
--    contact_transcription. Brak triggera i brak FK -- tabela ich nie miala
--    (potwierdzone \d contact_transcription w dev przed ta migracja).
-- ---------------------------------------------------------------------------

-- Indeks wtorny budowany PO zaladowaniu danych (szybciej niz w trakcie INSERT).
-- Nazwa tymczasowa (_new) -- "idx_contact_transcription_contact" jest nadal
-- zajeta przez contact_transcription_old do czasu jej usuniecia w sekcji 7.
-- Kolejnosc kolumn (contact_id, tenant_id) zachowana DOKLADNIE 1:1 ze stanem
-- dzisiejszym -- swiadomie nie "ulepszana" tutaj (patrz decyzja nr 3 w naglowku).
CREATE INDEX idx_contact_transcription_new_contact
    ON contact_transcription (contact_id, tenant_id);

-- RLS -- ENABLE + FORCE (swiadome zaostrzenie wzgledem stanu dzisiejszego, patrz
-- decyzja nr 2 w naglowku) + polityka z niezmieniona, dzis blledna nazwa GUC
-- app.tenant_id (swiadoma decyzja nr 1 w naglowku -- NIE app.current_tenant_id).
ALTER TABLE contact_transcription ENABLE ROW LEVEL SECURITY;
ALTER TABLE contact_transcription FORCE ROW LEVEL SECURITY;
CREATE POLICY contact_transcription_isolation ON contact_transcription
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);

-- Komentarze (tresc 1:1 z V067, dopisana wzmianka o partycjonowaniu)
COMMENT ON TABLE contact_transcription IS
    'Transkrypcje rozmow telefonicznych generowane przez Whisper po zapisaniu nagrania w S3. '
    'Odseparowane od pola notes tabeli contact (notes sluzy do recznych notatek agentow). '
    'Od V086 tabela partycjonowana RANGE po created_at (PK zlozony transcription_id+created_at); '
    'rotacja partycji (tworzenie kolejnych miesiecy / usuwanie starych) dostarczona w DB-052 (V088).';
COMMENT ON COLUMN contact_transcription.content IS
    'Pelna transkrypcja rozmowy jako plain text.';
COMMENT ON COLUMN contact_transcription.language IS
    'Wykryty jezyk transkrypcji w formacie ISO 639-1 (np. "pl", "en"). NULL gdy voicebot nie zwrocil jezyka.';

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
    SELECT COUNT(*) INTO v_count_old FROM contact_transcription_old;
    SELECT COUNT(*) INTO v_count_new FROM contact_transcription;

    IF v_count_old != v_count_new THEN
        RAISE EXCEPTION
            'V086 contact_transcription partitioning aborted: row count mismatch (old=%, new=%)',
            v_count_old, v_count_new;
    END IF;

    RAISE NOTICE 'V086 contact_transcription partitioning: row count verified (% rows).', v_count_new;
END $$;

-- ---------------------------------------------------------------------------
-- 7. Usuniecie starej tabeli -- dopiero po pozytywnej weryfikacji powyzej
-- ---------------------------------------------------------------------------

DROP TABLE contact_transcription_old;

-- ---------------------------------------------------------------------------
-- 8. Porzadkowanie nazw PK/indeksu do finalnych, konwencyjnych nazw -- dopiero
--    teraz "contact_transcription_pkey"/"idx_contact_transcription_contact"
--    sa wolne (stara tabela je zwolnila przez DROP w sekcji 7).
-- ---------------------------------------------------------------------------

ALTER TABLE contact_transcription RENAME CONSTRAINT contact_transcription_new_pkey TO contact_transcription_pkey;
ALTER INDEX idx_contact_transcription_new_contact RENAME TO idx_contact_transcription_contact;
