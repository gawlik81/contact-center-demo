-- =============================================================================
-- V089__add_tenant_scoped_retention_indexes.sql
-- DB-053: Indeksy (tenant_id, kolumna_czasowa) pod przyszly RetentionPurgeService
-- (BE-113, EPIC-29 Partycjonowanie i retencja danych z obslugi kontaktow).
--
-- Migracja: Flyway V089
-- Zaleznosci: DB-049/V085 (contact_event partycjonowany), DB-050/V086
--             (contact_transcription partycjonowany), DB-051/V087
--             (contact_ai_summary partycjonowany, kolumna generated_at) --
--             kolumny czasowe uzyte ponizej musza juz istniec na docelowych
--             tabelach.
-- Blokuje: BE-113 (RetentionPurgeService -- purge per-tenant batchami:
--          DELETE FROM <tabela> WHERE tenant_id = :tenantId AND
--          <kolumna_czasowa> < :cutoff)
--
-- KONTEKST: bez indeksu zlozonego (tenant_id, kolumna_czasowa) kazdy batch
-- purge wykonywalby sekwencyjny skan calej (aktywnej) partycji/tabeli, zamiast
-- Index/Bitmap Index Scan zawezonego do wierszy jednego tenanta i przedzialu
-- czasu. Audyt istniejacych indeksow (wykonany podczas dekompozycji epiku,
-- potwierdzony ponownie \d-em przed napisaniem tej migracji):
--
-- | Tabela                    | (tenant_id, czas) juz istnieje?           | Akcja |
-- |----------------------------|-------------------------------------------|-------|
-- | contact                    | NIE -- istniejace indeksy tenant-owe maja  | DODAJ |
-- |                             | dodatkowa kolumne posrodku (np.            |       |
-- |                             | idx_contact_channel_date: tenant_id,       |       |
-- |                             | channel, started_at) -- started_at nie     |       |
-- |                             | jest tam sargable bez rownosci na channel  |       |
-- | contact_event               | TAK -- idx_contact_event_tenant            | POMIN |
-- |                             | (tenant_id, started_at DESC), od V059      |       |
-- | contact_transcription       | NIE -- idx_contact_transcription_contact   | DODAJ |
-- |                             | ma kolejnosc (contact_id, tenant_id), bez  |       |
-- |                             | kolumny czasowej                           |       |
-- | contact_ai_summary          | NIE -- analogicznie do transcription       | DODAJ |
-- |                             | (kolumna generated_at potwierdzona w       |       |
-- |                             | DB-051/V087 jako biznesowy "wiek" wiersza) |       |
-- | campaign_contact_archive    | NIE -- idx_cca_archived_at istnieje, ale   | DODAJ |
-- |                             | bez tenant_id (kazdy purge skanowalby      |       |
-- |                             | wszystkich tenantow po dacie, potem        |       |
-- |                             | filtrowal tenant_id w pamieci)             |       |
--
-- SWIADOMA DECYZJA ARCHITEKTONICZNA -- CREATE INDEX zwykly (nie CONCURRENTLY):
-- Flyway na PostgreSQL w tym repo domyslnie wykonuje kazda migracje SQL w
-- jednej transakcji (potwierdzone m.in. w komentarzach V085/V088) --
-- CREATE INDEX CONCURRENTLY nie moze dzialac wewnatrz bloku transakcyjnego
-- ("ERROR: CREATE INDEX CONCURRENTLY cannot run inside a transaction block").
-- Zeby uzyc CONCURRENTLY trzeba by wylaczyc transakcyjnosc TEJ KONKRETNEJ
-- migracji (Flyway >=7: plik konfiguracyjny per-migracja z
-- executeInTransaction=false) -- wzorzec, ktory NIE jest dzis nigdzie w tym
-- repo uzyty (zweryfikowane grepem po CREATE INDEX CONCURRENTLY w calym
-- katalogu migracji -- zero trafien, tylko wzmianki w komentarzach V085/V088
-- jako swiadomie odlozona kwestia).
--
-- Przy obecnym wolumenie (dev: 556 wierszy contact / 50 transcription /
-- 57 ai_summary / 0 campaign_contact_archive, rozlozone na partycje
-- miesieczne dla pierwszych trzech) czas budowy kazdego indeksu to
-- pojedyncze milisekundy -- SHARE lock trzymany przez CREATE INDEX (blokuje
-- INSERT/UPDATE/DELETE na danej partycji/tabeli na czas budowy indeksu, nie
-- blokuje SELECT) jest w praktyce niezauwazalny.
--
-- NA PRODUKCJI, przy duzym wolumenie (partycje contact/contact_transcription
-- licza sie docelowo w setkach tysiecy do milionow wierszy) ta kalkulacja
-- sie zmienia -- SHARE lock trzymany przez sekundy/minuty na aktywnie
-- zapisywanej (biezacy miesiac) partycji blokowalby zapis nowych kontaktow
-- w tym oknie. Rekomendacja dla wdrozenia produkcyjnego: gdy wolumen
-- partycji contact/contact_transcription urosnie na tyle, ze SHARE lock
-- stanie sie odczuwalny, wykonac rownowazny CREATE INDEX CONCURRENTLY per
-- partycja jako osobny, reczny krok DBA POZA standardowym przebiegiem
-- Flyway (a nie jako kolejna migracje w tym katalogu) -- PostgreSQL 11+
-- wspiera CONCURRENTLY bezposrednio na partycjonowanej tabeli nadrzednej
-- (buduje indeks na kazdej partycji, oznacza indeks rodzica jako valid
-- dopiero po ukonczeniu wszystkich), wiec nie trzeba tego robic recznie per
-- partycja. Ta migracja SWIADOMIE nie wprowadza dzis wzorca
-- non-transactional-Flyway-migration dla 4 malych indeksow -- byloby to
-- over-engineering nieproporcjonalne do dzisiejszej skali danych i
-- niespojne z reszta katalogu migracji.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. contact -- idx_contact_tenant_started_at (tenant_id, started_at)
--    Tabela partycjonowana RANGE po started_at (V007) -- indeks tworzony na
--    tabeli nadrzednej propaguje sie automatycznie do wszystkich istniejacych
--    i przyszlych partycji (PostgreSQL 11+).
--    Uzywany przez: DELETE FROM contact WHERE tenant_id = :tenantId AND
--    started_at < :cutoff (RetentionPurgeService / BE-113).
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_contact_tenant_started_at
    ON contact (tenant_id, started_at);

COMMENT ON INDEX idx_contact_tenant_started_at
    IS 'DB-053 / BE-113: Purge retencyjny per-tenant batchami po started_at. '
       'Uzywany przez RetentionPurgeService: DELETE FROM contact WHERE '
       'tenant_id = ? AND started_at < ?';

-- ---------------------------------------------------------------------------
-- 2. contact_event -- SWIADOMIE POMINIETE, NIC DO ZROBIENIA.
--    idx_contact_event_tenant (tenant_id, started_at DESC) juz istnieje od
--    V059 (DB-035) i pokrywa dokladnie wzorzec zapytania purge:
--    DELETE FROM contact_event WHERE tenant_id = :tenantId AND
--    started_at < :cutoff -- kierunek DESC w indeksie nie przeszkadza
--    rownosci/zakresowi na tenant_id/started_at (btree jest przeszukiwalny
--    w obie strony). To NIE jest przeoczenie tej migracji -- potwierdzone
--    \d contact_event przed napisaniem V089, zob. tabela audytu w naglowku.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 3. contact_transcription -- idx_contact_transcription_tenant_created
--    (tenant_id, created_at)
--    Tabela partycjonowana RANGE po created_at (V086) -- indeks propaguje sie
--    do wszystkich partycji. Istniejacy idx_contact_transcription_contact ma
--    kolejnosc (contact_id, tenant_id) -- zaprojektowany pod inny wzorzec
--    dostepu (lookup po kontakcie), bez przydatnej kolumny czasowej na
--    poczatku, wiec nie nadaje sie do purge.
--    Uzywany przez: DELETE FROM contact_transcription WHERE
--    tenant_id = :tenantId AND created_at < :cutoff.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_contact_transcription_tenant_created
    ON contact_transcription (tenant_id, created_at);

COMMENT ON INDEX idx_contact_transcription_tenant_created
    IS 'DB-053 / BE-113: Purge retencyjny per-tenant batchami po created_at. '
       'Uzywany przez RetentionPurgeService: DELETE FROM '
       'contact_transcription WHERE tenant_id = ? AND created_at < ?';

-- ---------------------------------------------------------------------------
-- 4. contact_ai_summary -- idx_contact_ai_summary_tenant_generated
--    (tenant_id, generated_at)
--    Tabela partycjonowana RANGE po generated_at (V087, DB-051) --
--    generated_at to moment faktycznego wygenerowania tresci przez model AI
--    (biznesowy "wiek" danych), NIE created_at (techniczny znacznik zapisu
--    wiersza) -- ta sama kolumna, ktora jest kolumna partycjonowania, zeby
--    purge byl spojny z granicami partycji.
--    Uzywany przez: DELETE FROM contact_ai_summary WHERE
--    tenant_id = :tenantId AND generated_at < :cutoff.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_contact_ai_summary_tenant_generated
    ON contact_ai_summary (tenant_id, generated_at);

COMMENT ON INDEX idx_contact_ai_summary_tenant_generated
    IS 'DB-053 / BE-113: Purge retencyjny per-tenant batchami po '
       'generated_at. Uzywany przez RetentionPurgeService: DELETE FROM '
       'contact_ai_summary WHERE tenant_id = ? AND generated_at < ?';

-- ---------------------------------------------------------------------------
-- 5. campaign_contact_archive -- idx_cca_tenant_archived_at
--    (tenant_id, archived_at)
--    Tabela NIE jest partycjonowana (zwykla tabela, PK zlozony
--    (record_id, campaign_id)). Istniejacy idx_cca_archived_at (archived_at)
--    nie jest tenant-scoped -- purge musialby skanowac wszystkich tenantow
--    po dacie i filtrowac tenant_id w pamieci (albo, przy RLS wymuszonym
--    przez sesje aplikacji, dostawac Filter zamiast Index Cond na
--    tenant_id po stronie plannera).
--    Uzywany przez: DELETE FROM campaign_contact_archive WHERE
--    tenant_id = :tenantId AND archived_at < :cutoff.
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_cca_tenant_archived_at
    ON campaign_contact_archive (tenant_id, archived_at);

COMMENT ON INDEX idx_cca_tenant_archived_at
    IS 'DB-053 / BE-113: Purge retencyjny per-tenant batchami po '
       'archived_at. Uzywany przez RetentionPurgeService: DELETE FROM '
       'campaign_contact_archive WHERE tenant_id = ? AND archived_at < ?';
