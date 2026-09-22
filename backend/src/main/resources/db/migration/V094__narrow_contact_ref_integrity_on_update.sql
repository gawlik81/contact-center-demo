-- =============================================================================
-- V094__narrow_contact_ref_integrity_on_update.sql
-- DB-079: Zawezenie funkcji triggera fn_contact_ref_integrity() -- UPDATE tabeli
-- contact, ktory NIE zmienia zadnej kolumny referencyjnej (customer_id,
-- agent_id, queue_id, campaign_id, tenant_id), nie jest juz walidowany wzgledem
-- customer / app_user / queue / campaign. INSERT i kazda zmiana referencji sa
-- walidowane dokladnie jak dotad.
--
-- Migracja: Flyway V094
-- Numeracja: V093 jest najwyzsza wersja na develop, na wszystkich galeziach
--            (git ls-tree) i w flyway_schema_history zywej bazy (2026-09-21),
--            wiec ta migracja bierze nastepny wolny numer.
-- Zaleznosci: V016 (fn_contact_ref_integrity + trg_contact_ref_integrity),
--             V007 (contact, partycje), V013 (anonymize_customer)
-- Blokuje: DB-062 (naprawa anonymize_customer), BE-129
--
-- PROBLEM: trg_contact_ref_integrity (V016) to BEFORE INSERT OR UPDATE ... FOR
-- EACH ROW bez listy kolumn (12 kopii: rodzic + 11 partycji, tgenabled = 'O').
-- Dla NEW.customer_id IS NOT NULL wymaga klienta z is_deleted = FALSE,
-- analogicznie app_user.is_deleted = FALSE dla NEW.agent_id -- przy KAZDYM
-- UPDATE, nie tylko przy zmianie referencji. Skutek: po soft-delete /
-- anonimizacji klienta (customer.is_deleted = TRUE) lub dezaktywacji agenta
-- (app_user.is_deleted = TRUE) KAZDY UPDATE kontaktu, nawet niezwiazany z
-- referencjami (recording_url, notes, remote_address, channel_metadata),
-- konczy sie wyjatkiem "contact: customer_id ... nie istnieje ...".
--
-- DOWOD DZIALANIEM (baza scratch = pg_dump -s zywej bazy V093, dane syntetyczne,
-- kontakty w 4 partycjach: contact_2026_04, contact_2026_09, contact_default,
-- contact_2027_01 z create_contact_partition; klient C1 i agent D1 z
-- is_deleted = TRUE ustawionym PO utworzeniu kontaktow), funkcja V016:
--   * UPDATE contact SET recording_url = NULL ... (sciezka
--     ContactRepository#clearRecordingUrl / RecordingRetentionJob) dla
--     kontaktu C1, kazda z 4 partycji -> ERROR "contact: customer_id ... nie
--     istnieje lub nie nalezy do tenant ..."
--   * UPDATE notes / channel_metadata / remote_address (wzorzec
--     anonymize_customer, V013 l. 80) -> ERROR (tak samo)
--   * UPDATE notes kontaktu agenta D1, w tym ksztalt ContactRepository#update
--     (agent_id = ta sama wartosc) -> ERROR "contact: agent_id ... nie istnieje"
--   * SELECT anonymize_customer(C3, tenant, NULL) dla klienta z 2 kontaktami ->
--     ERROR "Blad anonimizacji klienta ...: contact: customer_id ... nie
--     istnieje ..." (V013 ustawia is_deleted = TRUE PRZED UPDATE contact;
--     EXCEPTION WHEN OTHERS cofa calosc) -- po raz pierwszy potwierdzone
--     uruchomieniem, dotad tylko dowod statyczny (DB-060 F1).
--   Po tej migracji (ten sam skrypt, ta sama baza): wszystkie powyzsze UPDATE
--   przechodza; INSERT z usunietym klientem/agentem, zmiana customer_id /
--   agent_id na usunietego lub cudzego (inny tenant), nieistniejace
--   agent_id/queue_id/campaign_id oraz zmiana tenant_id nadal sa odrzucane.
--
-- ROZWIAZANIE: wylacznie CREATE OR REPLACE FUNCTION -- zero DDL na tabelach
-- (bez ACCESS EXCLUSIVE, bez DROP/CREATE TRIGGER na rodzicu i 11 partycjach,
-- brak przebudowy indeksow). Funkcja jest wspolna dla wszystkich 12 kopii
-- triggera, wiec zmiana obowiazuje natychmiast wszedzie; przyszle partycje
-- (create_contact_partition / PartitionMaintenanceJob) dziedzicza trigger
-- rodzica i wskazuja te sama funkcje.
--
-- Wczesny RETURN NEW dla TG_OP = 'UPDATE', gdy customer_id, agent_id, queue_id,
-- campaign_id i tenant_id sa NIEZMIENIONE (IS NOT DISTINCT FROM -- bezpieczne
-- dla NULL). Zagniezdzone IF zamiast jednego "TG_OP = 'UPDATE' AND ...":
-- przy INSERT odwolanie do OLD.* nigdy nie jest wykonywane (kolejnosc ewaluacji
-- AND w wyrazeniu nie jest gwarantowana). tenant_id liczy sie jak referencja
-- (walidacja klucza obcego jest zawsze wzgledem NEW.tenant_id); zmiana
-- tenant_id nie wystepuje w kodzie aplikacji.
--
-- ODRZUCONY WARIANT: CREATE TRIGGER ... BEFORE INSERT OR UPDATE OF customer_id,
-- agent_id, queue_id, campaign_id, tenant_id. (a) Wymaga DROP + CREATE triggera
-- na tabeli partycjonowanej i na kazdej partycji (SET LOCAL lock_timeout,
-- ACCESS EXCLUSIVE, kazda przyszla partycja tez musi to odziedziczyc). (b) Jest
-- slabszy: "UPDATE OF kolumna" odpala sie, gdy kolumna wystepuje w SET, takze
-- przy SET agent_id = <ta sama wartosc>; ContactRepository#update ustawia
-- agent_id w KAZDYM UPDATE, wiec trigger i tak zadzialalby prawie zawsze, a
-- kontakt dezaktywowanego agenta nadal bylby blokowany. Wczesny RETURN
-- porownuje WARTOSCI, nie liste kolumn w SET.
--
-- SEMANTYKA I ZNANE OGRANICZENIA (swiadome):
--   1. Referencja jest walidowana w chwili zapisu; pozniejsze usuniecie
--      klienta / dezaktywacja agenta nie blokuje edycji innych kolumn kontaktu.
--      Konsekwencja: trigger przestaje byc okresowa kontrola integralnosci
--      przy dotknieciu wiersza -- kontakt, ktory JUZ wskazuje nieprawidlowa
--      referencje (np. skazenie danych historycznych albo zapis z wylaczonym
--      triggerem), moze byc edytowany w innych kolumnach bez ostrzezenia;
--      referencje sa walidowane wylacznie przy INSERT i przy zmianie referencji.
--   2. Gdy w jednym UPDATE zmienia sie CHOCIAZ JEDNA referencja, walidowane sa
--      nadal WSZYSTKIE cztery (cialo bez zmian, jak w V016): np. zmiana
--      queue_id kontaktu klienta z is_deleted = TRUE nadal konczy sie
--      wyjatkiem o kliencie. Walidacja "tylko zmienionych kolumn" jest mozliwym
--      dalszym zawezeniem, ale wykracza poza DB-079.
--   3. UPDATE zmieniajacy started_at (klucz partycji) tak, ze wiersz trafia do
--      INNEJ partycji, przenosi go jako DELETE + INSERT: BEFORE UPDATE na
--      partycji zrodlowej (wczesny RETURN), ale BEFORE INSERT na docelowej --
--      pelna walidacja jak dla INSERT (PG 16, sprawdzone instrumentowana
--      funkcja: kolejno op=UPDATE na zrodle i op=INSERT na celu, tez dla
--      contact_default w obie strony). Zmiana started_at w obrebie TEJ SAMEJ
--      partycji nie przenosi wiersza: to zwykly UPDATE (wczesny RETURN, bez
--      walidacji referencji). Kod aplikacji nie zmienia started_at. Pokryte
--      testem ContactRefIntegrityNarrowingTest (f).
--   4. Zdjecie referencji (SET customer_id / agent_id = NULL) przechodzilo juz
--      wczesniej (walidacja dotyczy tylko wartosci NOT NULL) -- bez zmian.
--   5. Funkcja NIE jest SECURITY DEFINER: czyta customer / app_user / queue /
--      campaign z uprawnieniami i RLS wywolujacego (polityki SELECT na tych
--      tabelach filtruja po app.current_tenant_id) -- bez zmian wzgledem V016;
--      wczesny RETURN tylko pomija te odczyty.
--
-- ROLLBACK (bez edycji zastosowanych migracji): nowa migracja z CREATE OR
-- REPLACE FUNCTION o ciele z V016 (l. 36-97); brak zmian schematu do cofniecia.
-- =============================================================================

CREATE OR REPLACE FUNCTION fn_contact_ref_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- DB-079: UPDATE, ktory nie zmienia zadnej kolumny referencyjnej, nie
    -- wymaga ponownej walidacji -- referencje zostaly sprawdzone przy zapisie.
    -- Bez tego kazdy UPDATE kontaktu klienta z is_deleted = TRUE lub agenta z
    -- is_deleted = TRUE (anonimizacja RODO, RecordingRetentionJob, edycja notatki)
    -- rzucalby wyjatek. Zagniezdzone IF: OLD.* nie jest odczytywane przy INSERT.
    IF TG_OP = 'UPDATE' THEN
        IF NEW.customer_id IS NOT DISTINCT FROM OLD.customer_id
           AND NEW.agent_id    IS NOT DISTINCT FROM OLD.agent_id
           AND NEW.queue_id    IS NOT DISTINCT FROM OLD.queue_id
           AND NEW.campaign_id IS NOT DISTINCT FROM OLD.campaign_id
           AND NEW.tenant_id   IS NOT DISTINCT FROM OLD.tenant_id
        THEN
            RETURN NEW;
        END IF;
    END IF;

    -- Walidacja customer_id (nullable - NULL = nieznany klient)
    IF NEW.customer_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM customer
            WHERE  customer_id = NEW.customer_id
              AND  tenant_id   = NEW.tenant_id
              AND  is_deleted  = FALSE
        ) THEN
            RAISE EXCEPTION
                'contact: customer_id % nie istnieje lub nie nalezy do tenant %',
                NEW.customer_id, NEW.tenant_id;
        END IF;
    END IF;

    -- Walidacja agent_id (nullable - NULL = brak agenta / kolejka)
    IF NEW.agent_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM app_user
            WHERE  user_id    = NEW.agent_id
              AND  tenant_id  = NEW.tenant_id
              AND  is_deleted = FALSE
        ) THEN
            RAISE EXCEPTION
                'contact: agent_id % nie istnieje lub nie nalezy do tenant %',
                NEW.agent_id, NEW.tenant_id;
        END IF;
    END IF;

    -- Walidacja queue_id (nullable)
    IF NEW.queue_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM queue
            WHERE  queue_id  = NEW.queue_id
              AND  tenant_id = NEW.tenant_id
        ) THEN
            RAISE EXCEPTION
                'contact: queue_id % nie istnieje lub nie nalezy do tenant %',
                NEW.queue_id, NEW.tenant_id;
        END IF;
    END IF;

    -- Walidacja campaign_id (nullable - NULL = kontakt inbound bez kampanii)
    IF NEW.campaign_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM campaign
            WHERE  campaign_id = NEW.campaign_id
              AND  tenant_id   = NEW.tenant_id
        ) THEN
            RAISE EXCEPTION
                'contact: campaign_id % nie istnieje lub nie nalezy do tenant %',
                NEW.campaign_id, NEW.tenant_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fn_contact_ref_integrity() IS
    'Trigger walidujacy integralnosc referencyjna tabeli CONTACT (BEFORE INSERT OR UPDATE, '
    'rodzic + kazda partycja). Zastepuje niedostepne FK na tabelach partycjonowanych. '
    'Sprawdza: customer_id (is_deleted = FALSE), agent_id (is_deleted = FALSE), queue_id, campaign_id '
    'vs. tenant_id. INSERT i kazda zmiana customer_id/agent_id/queue_id/campaign_id/tenant_id sa walidowane; '
    'UPDATE bez zmiany tych kolumn (recording_url, notes, remote_address, channel_metadata, ...) '
    'konczy sie wczesnym RETURN NEW (DB-079, V094) -- pozniejsze usuniecie klienta lub agenta '
    '(RODO, dezaktywacja) nie blokuje edycji kontaktu. Pierwotna wersja: V016.';
