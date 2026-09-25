-- =============================================================================
-- V097__add_orphan_message_age_purge_indexes.sql
-- DB-059: Indeksy (tenant_id, wiek wiadomosci) pod purge wiadomosci osieroconych
-- email_message / social_message (EPIC-30, BE-127).
--
-- Migracja: Flyway V097
-- Zaleznosci: BE-124 (zamkniete 2026-09-20) -- definicja "wieku wiadomosci"
--             potwierdzona w BE-124 par.7: COALESCE(received_at, sent_at, created_at)
--             dla email_message (created_at jest NOT NULL, wiec wyrazenie jest
--             totalne), sent_at (NOT NULL) dla social_message.
-- Blokuje: BE-127 (sweep osieroconych wiadomosci wg wieku -- purge D1 = A), DB-065,
--          DB-067 (partycjonowanie email_message/social_message -- AC tamtych
--          ticketow odtwarza rownowazne indeksy na docelowej/partycjonowanej tabeli)
--
-- KONTEKST: BE-125/BE-126 juz usuwaja wiadomosci POWIAZANE Z KONTAKTEM (purge
-- kontaktu): DELETE/SELECT ... WHERE tenant_id = ? AND contact_id IN (...) --
-- patrz EmailMessageRepository#FIND_ATTACHMENTS_SQL/DELETE_BY_IDS_SQL,
-- SocialMessageRepository#DELETE_BY_CONTACT_IDS_SQL. Te zapytania juz korzystaja
-- z istniejacych idx_email_message_contact (contact_id, received_at DESC) /
-- idx_social_message_contact (contact_id, sent_at DESC) -- TA migracja ich NIE
-- dotyka i NIE ma prawa zmienic ich planu wykonania (regresja sprawdzona w
-- notatce wykonania DB-059 EXPLAIN-em na scratch).
--
-- BE-127 (jeszcze NIEZAIMPLEMENTOWANY w chwili tej migracji) ma sweepowac
-- wiadomosci OSIEROCONE (contact_id IS NULL -- np. przychodzace, ktorych routing
-- nigdy nie przypisal do kontaktu) wedlug wieku:
-- WHERE tenant_id = ? AND contact_id IS NULL AND <wiek wiadomosci> < cutoff.
-- Bez indeksu zlozonego (tenant_id, wiek) z czesciowym WHERE contact_id IS NULL
-- kazdy batch tego sweepu wykonywalby sekwencyjny skan calej tabeli. Audyt
-- istniejacych indeksow (potwierdzony \d przed napisaniem migracji; live:
-- email_message 55 wierszy, social_message 0 -- problem pojawi sie dopiero na
-- wolumenie, indeks jest tani teraz):
--
-- | Tabela         | (tenant_id, wiek) WHERE contact_id IS NULL istnieje? | Akcja |
-- |-----------------|--------------------------------------------------------|-------|
-- | email_message   | NIE -- idx_email_message_contact ma kolejnosc          | DODAJ |
-- |                 | (contact_id, received_at DESC), bez tenant_id, bez     |       |
-- |                 | czesciowego WHERE, bez COALESCE na 3 kolumnach         |       |
-- | social_message  | NIE -- idx_social_message_contact ma kolejnosc          | DODAJ |
-- |                 | (contact_id, sent_at DESC), analogicznie                |       |
--
-- DEFINICJA "WIEKU WIADOMOSCI" (BE-124 par.7; DESIGN-message-retention-and-
-- partitioning.md par.3 D1 = A przyjete robocze 2026-09-20 BEZ wyraznego
-- potwierdzenia PO -- patrz tez tabela wplywu D1 w tresci ticketu DB-059):
-- email_message.received_at bywa NULL dla OUTBOUND (INTERNALDATE serwera IMAP
-- ustawiane tylko dla INBOUND) -- COALESCE(received_at, sent_at, created_at)
-- jest wyrazeniem TOTALNYM, bo created_at jest NOT NULL DEFAULT now(). TO SAMO
-- wyrazenie stanie sie kolumna message_at w DB-067 (partycjonowanie
-- email_message) -- migracja konwersji ma odtworzyc rownowazny indeks na nowej
-- kolumnie zamiast na wyrazeniu. social_message.sent_at jest NOT NULL
-- DEFAULT now() -- prostszy predykat, bez COALESCE.
--
-- SWIADOMA DECYZJA -- CREATE INDEX zwykly (NIE CONCURRENTLY), wzorzec V089
-- (DB-053) zastosowany 1:1: obie tabele sa dzis mikroskopijne (55 / 0 wierszy
-- live) -- SHARE lock trzymany przez CREATE INDEX (blokuje INSERT/UPDATE/DELETE
-- na tabeli na czas budowy indeksu, NIE blokuje SELECT) to przy tym wolumenie
-- pojedyncze milisekundy, w praktyce niezauwazalne. Flyway w tym repo domyslnie
-- wykonuje kazda migracje SQL w jednej transakcji (potwierdzone m.in. w
-- komentarzach V085/V088/V089) -- CREATE INDEX CONCURRENTLY nie moze dzialac
-- wewnatrz bloku transakcyjnego ("ERROR: CREATE INDEX CONCURRENTLY cannot run
-- inside a transaction block"). Zeby go uzyc trzeba by wylaczyc transakcyjnosc
-- TEJ KONKRETNEJ migracji (Flyway >=7: plik konfiguracyjny per-migracja z
-- executeInTransaction=false) -- wzorzec, ktory NIE jest dzis nigdzie w tym
-- repo uzyty (zweryfikowane grepem po "CREATE INDEX CONCURRENTLY" w calym
-- katalogu migracji -- zero trafien, tylko wzmianki w komentarzach V085/V088/
-- V089 jako swiadomie odlozona kwestia).
--
-- Rekomendacja dla wdrozenia produkcyjnego: gdy wolumen email_message/
-- social_message urosnie na tyle, ze SHARE lock stanie sie odczuwalny (sygnal
-- progowy: partycjonowanie DB-065/DB-067 tych samych tabel), wykonac
-- rownowazny CREATE INDEX CONCURRENTLY jako osobny, reczny krok DBA POZA
-- standardowym przebiegiem Flyway (a nie jako kolejna migracje w tym
-- katalogu) -- DB-065/DB-067 i tak odtwarzaja te indeksy na nowej/
-- partycjonowanej tabeli w swoim wlasnym zakresie. Ta migracja SWIADOMIE nie
-- wprowadza dzis wzorca non-transactional-Flyway-migration dla 2 malych
-- indeksow -- byloby to over-engineering nieproporcjonalne do dzisiejszej
-- skali danych i niespojne z reszta katalogu migracji.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. email_message -- idx_email_message_tenant_orphan_age
--    (tenant_id, (COALESCE(received_at, sent_at, created_at))) WHERE contact_id IS NULL
--    Czesciowy indeks (tylko wiadomosci osierocone) -- maly, tani, celowy pod
--    dokladnie jeden wzorzec zapytania (BE-127 sweep).
--    Uzywany przez: SELECT ... FROM email_message WHERE tenant_id = :tenantId
--    AND contact_id IS NULL AND COALESCE(received_at, sent_at, created_at) < :cutoff
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_email_message_tenant_orphan_age
    ON email_message (tenant_id, (COALESCE(received_at, sent_at, created_at)))
    WHERE contact_id IS NULL;

COMMENT ON INDEX idx_email_message_tenant_orphan_age
    IS 'DB-059 / BE-127: sweep wiadomosci email OSIEROCONYCH (contact_id IS NULL) '
       'per-tenant wedlug wieku. Wyrazenie COALESCE(received_at, sent_at, '
       'created_at) to definicja "wieku wiadomosci" potwierdzona w BE-124 par.7 '
       '(received_at bywa NULL dla OUTBOUND, created_at NOT NULL wiec wyrazenie '
       'jest totalne) -- TA SAMA definicja stanie sie kolumna message_at w '
       'DB-067 (partycjonowanie email_message); migracja konwersji ma odtworzyc '
       'rownowazny indeks na nowej kolumnie zamiast na wyrazeniu. Uzywany przez: '
       'SELECT ... FROM email_message WHERE tenant_id = ? AND contact_id IS '
       'NULL AND COALESCE(received_at, sent_at, created_at) < ? (BE-127, '
       'jeszcze niezaimplementowany w chwili tej migracji).';

-- ---------------------------------------------------------------------------
-- 2. social_message -- idx_social_message_tenant_orphan_sent
--    (tenant_id, sent_at) WHERE contact_id IS NULL
--    sent_at jest NOT NULL -- brak potrzeby COALESCE (w odroznieniu od
--    email_message, ktore ma nullable received_at dla OUTBOUND).
--    Uzywany przez: SELECT ... FROM social_message WHERE tenant_id = :tenantId
--    AND contact_id IS NULL AND sent_at < :cutoff
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_social_message_tenant_orphan_sent
    ON social_message (tenant_id, sent_at)
    WHERE contact_id IS NULL;

COMMENT ON INDEX idx_social_message_tenant_orphan_sent
    IS 'DB-059 / BE-127: sweep wiadomosci social OSIEROCONYCH (contact_id IS '
       'NULL) per-tenant wedlug sent_at (NOT NULL -- bez COALESCE, w '
       'odroznieniu od email_message). Uzywany przez: SELECT ... FROM '
       'social_message WHERE tenant_id = ? AND contact_id IS NULL AND '
       'sent_at < ? (BE-127, jeszcze niezaimplementowany w chwili tej '
       'migracji).';
