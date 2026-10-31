-- =============================================================================
-- V093__drop_duplicate_contact_indexes.sql
-- DB-055: Usuniecie 3 zduplikowanych indeksow z partycjonowanej tabeli
-- contact (V007 vs V011). Kazdy INSERT/UPDATE na contact utrzymuje kazdy
-- indeks na tabeli nadrzednej w KAZDEJ partycji (11 dzis + kazda kolejna
-- tworzona przez PartitionMaintenanceJob) -- redundantny indeks to czysty koszt
-- zapisu i miejsca, bez zadnej korzysci dla odczytu.
--
-- Migracja: Flyway V093
-- Numeracja: V092 jest zajete (V092__social_integration_global_unique_page.sql
--            -- juz zastosowane w lokalnej bazie dev, obecne na galezi
--            feature-socialmedia), wiec ta migracja bierze nastepny wolny numer.
-- Zaleznosci: V007 (contact + pierwszy zestaw indeksow),
--             V011 (drugi zestaw indeksow, te same kolumny, inne nazwy),
--             V025 (DROP + CREATE indeksow channel po konwersji enum->varchar)
--
-- KONTEKST: V007 zadeklarowal indeksy raportowe z sufiksami _history/_date/
-- _disposition, a V011 (sekcja 3, "dodatkowe indeksy kompozytowe") dodal drugi
-- zestaw z innymi nazwami i tymi samymi kolumnami. Zaden z tych indeksow nie
-- jest referencowany po nazwie w kodzie Javy, hintach @Query ani w funkcjach
-- SQL (zweryfikowane grepem po backend/ i pg_proc) -- planner wybiera je sam.
--
-- Audyt (zweryfikowany na zywej bazie PG 16 przez pg_index: kolumny, indoption,
-- opclass, collation, predykat, AM):
--
-- | Para | Indeks A (V007)             | Indeks B (V011)                      | Roznica          |
-- |------|------------------------------|---------------------------------------|-------------------|
-- | 1    | idx_contact_agent_history    | idx_contact_tenant_agent_date         | BRAK (identyczne) |
-- |      | (tenant_id, agent_id,        | (tenant_id, agent_id,                 |                   |
-- |      |  started_at DESC)            |  started_at DESC)                     |                   |
-- |      | WHERE agent_id IS NOT NULL   | WHERE agent_id IS NOT NULL            |                   |
-- | 2    | idx_contact_disposition      | idx_contact_tenant_disposition_date   | BRAK (identyczne) |
-- |      | (tenant_id, disposition_code,| (tenant_id, disposition_code,         |                   |
-- |      |  started_at)                 |  started_at)                          |                   |
-- |      | WHERE disposition_code       | WHERE disposition_code IS NOT NULL    |                   |
-- |      |   IS NOT NULL                |                                       |                   |
-- | 3    | idx_contact_channel_date     | idx_contact_tenant_channel_date       | tylko kierunek    |
-- |      | (tenant_id, channel,         | (tenant_id, channel,                  | started_at:       |
-- |      |  started_at)                 |  started_at DESC)                     | ASC vs DESC       |
--
-- DECYZJA: zostaja indeksy V007 (kolumna A), usuwane sa indeksy V011 (B).
--   (a) Referencje: nazwy V007 wystepuja w dokumentacji
--       (documentation/tech/06-database.md + html), w tekscie ticketu w
--       TASKS-DATABASE.md i w naglowku V089; nazwy V011 nie wystepuja nigdzie
--       poza V011/V025 -- usuniecie ich nie wymaga zmian w dokumentacji.
--   (b) Nazewnictwo: po tej migracji 13 z 15 indeksow nie-PK na contact ma
--       forme idx_contact_<przeznaczenie> (history, campaign, remote_address,
--       ...); infiks "tenant_" nie niesie informacji, bo KAZDY indeks contact
--       (poza idx_contact_campaign_contact_record) zaczyna sie od tenant_id.
--   (c) V025 nie dotknal pary 1 i 2 (kolumny agent_id/disposition_code nie sa
--       typu enum); pary 3 dotknal symetrycznie (DROP + CREATE obu indeksow
--       channel, sekcje 2 i 6) -- nie rozstrzyga.
--
-- PARA 3 (ASC vs DESC) -- decyzja oparta na dowodzie, nie na domysle:
--   1. Kod: zapytania z filtrem channel (ContactRepository.findContacts /
--      countContacts / findAgentReportRows / countAgentReportRows /
--      findActiveSocialContact; getContactCountsByChannelInRange uzywa channel
--      tylko w GROUP BY) traktuja channel WYLACZNIE jako rownosc lub GROUP BY.
--      Jedyny ORDER BY zwiazany z tym indeksem to "ORDER BY started_at DESC
--      LIMIT/OFFSET" (findContacts) -- przy rownosci na tenant_id i channel
--      btree obsluguje go skanem wstecz. Brak ORDER BY zawierajacego channel
--      w calym backend/, voicebot/ i migracjach (grep).
--   2. EXPLAIN (ANALYZE, BUFFERS) na bazie scratch (500 tys. wierszy, 60
--      tenantow, 7 zapelnionych partycji, SET enable_seqscan = off), najwiekszy
--      tenant (64,6 tys. wierszy), kanaly rzadkie i dominujace; w transakcji z
--      ROLLBACK, po usunieciu wskazanego indeksu I dodatkowo po usunieciu
--      idx_contact_tenant_started_at (zeby zaden inny indeks nie mogl dac
--      kolejnosci po started_at):
--        - tylko ASC (ten, ktory zostaje): DESC LIMIT 20 -> Limit > Merge
--          Append > Index Scan BACKWARD po idx_contact_channel_date, BRAK
--          wezla Sort, 55-57 buforow, 0,6-1,0 ms; ASC LIMIT 20 -> Index Scan
--          (forward), identycznie.
--        - tylko DESC (ten, ktory jest usuwany): lustrzanie (forward dla DESC,
--          backward dla ASC), te same bufory i czasy.
--        - glebokie strony (OFFSET 1500): 1564 vs 1580 buforow, ~4,7 ms w
--          obu wariantach (roznica ~1%, w granicach szumu).
--        - zakres dat + channel, COUNT(*), raport agentow (GROUP BY):
--          identyczne plany i bufory w obu wariantach.
--        - KONTROLA (usuniete oba indeksy channel + tenant_started_at): plan
--          zmienia sie na Limit > Sort > Append > Bitmap Heap Scan, 13955
--          buforow, ~35-45 ms -- test WYKRYWA brak indeksu, wiec brak Sort w
--          wariantach z pojedynczym indeksem jest dowodem, nie artefaktem.
--        - rozmiar: przy monotonicznym naplywie wierszy (700 tys., kolejnosc
--          czasowa) oba warianty ~60 MB (roznica <1%), po REINDEX po 34 MB.
--   3. Jedyna zmierzona roznica dotyczy ksztaltu ORDER BY, ktorego kod NIE
--      uzywa: "WHERE tenant_id = ? ORDER BY channel, started_at DESC" (channel
--      bez rownosci) -- indeks DESC obsluguje go skanem w przod (55 buforow),
--      indeks ASC wymaga Incremental Sort (16 tys. buforow, ~20-60 ms).
--      Symetrycznie ASC obsluguje "ORDER BY channel, started_at" (oba ASC),
--      a DESC nie. Jesli takie zapytanie kiedys powstanie, nalezy wtedy
--      swiadomie dodac indeks odpowiadajacy jego ORDER BY.
--   Wniosek: dla WSZYSTKICH istniejacych sciezek zapytan pojedynczy indeks
--   wystarcza (dowod jednoznaczny) -- zostaje idx_contact_channel_date z tych
--   samych powodow (a)/(b) co w parach 1 i 2.
--
-- Nie usuwamy (jedyne obslugujace swoje sciezki, sprawdzone): pk_contact,
-- idx_contact_recording_retention (V022), idx_contact_tenant_started_at (V089),
-- idx_contact_queue_date / idx_contact_queue_status (rozne kolumny, nie
-- prefiksy siebie nawzajem), idx_contact_customer_history i pozostale.
-- Audyt prefiksowej redundancji (A.kolumny jest poczatkiem B.kolumn, ten sam
-- predykat/opclass/kierunek) na wszystkich partycjonowanych rodzicach
-- (contact, contact_event, contact_transcription, contact_ai_summary,
-- audit_log, plugin_invocation_log, campaign_contact) nie znalazl niczego
-- wiecej -- poza opisanymi 3 parami brak duplikatow.
--
-- NOWE PARTYCJE NIE ODTWARZAJA DUPLIKATOW: create_contact_partition() (V007;
-- V014/V077/V088 redefiniuja wylacznie funkcje zbiorcze/rotacyjne, np.
-- create_next_month_partitions(), rotate_contact_partitions()) tworzy
-- partycje wylacznie przez CREATE TABLE ... PARTITION OF contact, a partycja
-- dziedziczy dokladnie te indeksy, ktore istnieja na tabeli nadrzednej w
-- momencie tworzenia. Zadna funkcja SQL (pg_proc), job Javy
-- (PartitionMaintenanceJob) ani event trigger nie tworzy indeksow contact
-- jawnie po nazwie -- usuniecie z rodzica wystarcza i nic sie nie wysypie.
-- Zweryfikowane empirycznie: SELECT create_contact_partition(2027, 1) po tej
-- migracji tworzy partycje z 16 indeksami, bez duplikatow.
--
-- BLOKADA: DROP INDEX na indeksie partycjonowanym zaklada ACCESS EXCLUSIVE na
-- tabeli contact ORAZ na kazdej jej partycji (zweryfikowane w pg_locks: 12
-- tabel) do konca transakcji Flyway. Operacja jest metadata-only (usuniecie
-- plikow indeksow, bez skanowania danych), wiec trwa milisekundy -- ale musi
-- poczekac na zakonczenie wszystkich trwajacych transakcji dotykajacych
-- contact, a w tym czasie nowe zapytania ustawiaja sie za nia w kolejce.
-- Dlatego SET LOCAL lock_timeout: lepiej przerwac migracje (Flyway zglosi
-- blad, wdrozenie mozna powtorzyc) niz zablokowac caly ruch na contact za
-- dlugo trwajaca transakcja. DROP INDEX CONCURRENTLY nie jest mozliwy na
-- indeksie partycjonowanym (ERROR: cannot drop partitioned index ...
-- concurrently) -- zwykly DROP INDEX jest jedyna opcja. Zalecenie wdrozenia:
-- poza szczytem ruchu.
--
-- IDEMPOTENTNOSC: DROP INDEX IF EXISTS; guard na poczatku przerywa migracje
-- (zamiast po cichu usunac jedyny indeks), gdyby na jakims srodowisku
-- indeks-zwyciezca zostal wczesniej usuniety recznie.
--
-- ODWRACALNOSC (jesli kiedys potrzebny): DDL identyczny z V011 --
--   CREATE INDEX idx_contact_tenant_agent_date
--       ON contact (tenant_id, agent_id, started_at DESC) WHERE agent_id IS NOT NULL;
--   CREATE INDEX idx_contact_tenant_disposition_date
--       ON contact (tenant_id, disposition_code, started_at) WHERE disposition_code IS NOT NULL;
--   CREATE INDEX idx_contact_tenant_channel_date
--       ON contact (tenant_id, channel, started_at DESC);
-- (przez propagacje na partycje; na duzym wolumenie -- jako reczny krok DBA
-- z CREATE INDEX CONCURRENTLY na kazdej partycji + ATTACH, poza Flyway).
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 0. Guard: indeksy-zwyciezcy MUSZA istniec, zanim usuniemy ich blizniaki.
--    Chroni przed dryfem srodowiska (reczne DROP INDEX) -- inaczej migracja
--    zostawilaby sciezke zapytan bez zadnego indeksu.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_missing TEXT;
BEGIN
    SELECT string_agg(w.name, ', ')
      INTO v_missing
      FROM unnest(ARRAY['idx_contact_agent_history',
                        'idx_contact_disposition',
                        'idx_contact_channel_date']) AS w(name)
     WHERE NOT EXISTS (
         SELECT 1
           FROM pg_indexes i
          WHERE i.schemaname = current_schema()
            AND i.tablename  = 'contact'
            AND i.indexname  = w.name
     );

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V093: brak indeksu-zwyciezcy na contact (%). Przerwano, zeby nie '
            'usunac jedynego indeksu obslugujacego sciezke zapytan.', v_missing;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 1. Para 1: idx_contact_tenant_agent_date (V011) -- identyczny z
--    idx_contact_agent_history (V007). Propaguje sie na wszystkie partycje.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_contact_tenant_agent_date;

-- ---------------------------------------------------------------------------
-- 2. Para 2: idx_contact_tenant_disposition_date (V011) -- identyczny z
--    idx_contact_disposition (V007).
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_contact_tenant_disposition_date;

-- ---------------------------------------------------------------------------
-- 3. Para 3: idx_contact_tenant_channel_date (V011, started_at DESC) --
--    zastapiony przez idx_contact_channel_date (V007, started_at ASC), ktory
--    przy rownosci na tenant_id i channel obsluguje ORDER BY started_at
--    zarowno ASC (skan w przod), jak i DESC (skan wstecz) bez wezla Sort.
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_contact_tenant_channel_date;

-- ---------------------------------------------------------------------------
-- 4. Dokumentacja na obiektach: indeksy-zwyciezcy (komentarz na rodzicu).
-- ---------------------------------------------------------------------------
COMMENT ON INDEX idx_contact_agent_history
    IS 'Historia/raporty agenta (US-10-02). DB-055 / V093: jedyny indeks '
       '(tenant_id, agent_id, started_at DESC) WHERE agent_id IS NOT NULL -- '
       'duplikat idx_contact_tenant_agent_date (V011) usuniety.';

COMMENT ON INDEX idx_contact_disposition
    IS 'Raporty konwersji po dyspozycji. DB-055 / V093: jedyny indeks '
       '(tenant_id, disposition_code, started_at) WHERE disposition_code IS '
       'NOT NULL -- duplikat idx_contact_tenant_disposition_date (V011) '
       'usuniety.';

COMMENT ON INDEX idx_contact_channel_date
    IS 'Filtr/raporty per kanal. DB-055 / V093: jedyny indeks '
       '(tenant_id, channel, started_at); przy rownosci na tenant_id+channel '
       'obsluguje ORDER BY started_at ASC i DESC (skan wstecz), NIE obsluguje '
       'ORDER BY channel, started_at DESC. Wariant DESC '
       '(idx_contact_tenant_channel_date, V011) usuniety.';
