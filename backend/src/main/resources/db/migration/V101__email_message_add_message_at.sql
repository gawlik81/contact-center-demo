-- =============================================================================
-- V101__email_message_add_message_at.sql
-- DB-067 (krok M1 z 2): kolumna message_at TIMESTAMPTZ NOT NULL w email_message
-- (EPIC-30) -- klucz partycjonowania RANGE tabeli w V102. Decyzja D4 = A
-- (zalozenie robocze, NIEpotwierdzone formalnie przez PO): message_at = czas
-- zaobserwowany przez system, backfill = COALESCE(received_at, sent_at, created_at).
--
-- Migracja: Flyway V101
-- Zaleznosci: V010 (tabela email_message: received_at, sent_at, created_at NOT NULL),
--             V028 (contact_id nullable -- bez wplywu na te migracje),
--             V097 (DB-059: idx_email_message_tenant_orphan_age na WYRAZENIU
--                   COALESCE -- odtwarzany na message_at dopiero w V102).
-- Blokuje: V102 (przepisanie tabeli na partycjonowana; oba pliki w tym samym
--          wydaniu, zaraz po sobie), BE-134 (kod ustawia message_at jawnie).
--
-- CO ROBI TA MIGRACJA:
--   1. ADD COLUMN message_at TIMESTAMPTZ -- BEZ DEFAULT w definicji kolumny
--      (ADD COLUMN z DEFAULT volatile przepisalby tabele; DEFAULT ustawiamy
--      osobno, patrz krok 2).
--   2. SET DEFAULT now() PRZED backfillem. Zmiana DEFAULT nie dotyka wierszy
--      istniejacych. Powod: w rolling deploy stare instancje aplikacji (bez
--      message_at w INSERT) moga pisac do tabeli w trakcie backfillu; z DEFAULT
--      ich nowe wiersze dostaja wartosc od razu i nie ma wyscigu z SET NOT NULL.
--   3. Backfill message_at = COALESCE(received_at, sent_at, created_at) partiami
--      (keyset po PK message_id, batch 5000). Idempotentny: filtr message_at IS NULL.
--   4. Weryfikacja: 0 NULL-i, 100 % zgodnosci z definicja, liczba wierszy przed == po
--      (RAISE EXCEPTION => ROLLBACK calej migracji).
--   5. SET NOT NULL (pelny skan pod ACCESS EXCLUSIVE; lock_timeout ogranicza
--      CZEKANIE na blokade, nie czas skanu).
--   6. COMMENT ON COLUMN.
--
-- DLACZEGO DEFAULT, A NIE TRIGGER BEFORE INSERT (zweryfikowane na PG 16.13,
-- scratch, DB-067): trigger BEFORE ROW na tabeli partycjonowanej odpala sie na
-- PARTYCJI PO routingu. Wiersz z NULL w kluczu trafia do partycji DEFAULT, a
-- trigger uzupelniajacy klucz konczy sie bledem "moving row to another partition
-- during a BEFORE FOR EACH ROW trigger is not supported". Column DEFAULT jest
-- liczony PRZED routingiem i dziala poprawnie.
--
-- KONSEKWENCJA (do swiadomej akceptacji): dla wiersza bez jawnego message_at
-- (stara instancja aplikacji) message_at = now() = czas zaobserwowany przez system,
-- a NIE COALESCE(received_at, ...). Dla INBOUND z opoznionym pobraniem (skrzynka
-- z historycznym INTERNALDATE) roznica bywa duza. Obrona: BE-134 (nowy kod ustawia
-- message_at = Message#getReceivedDate() jawnie) oraz BE-127 (created_at < now()-1d).
-- DEFAULT jest PRZEJSCIOWY: po wdrozeniu BE-134 w calym klastrze nalezy go usunac
-- (ALTER TABLE email_message ALTER COLUMN message_at DROP DEFAULT) -- zob. ryzyka
-- w naglowku V102.
-- =============================================================================

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------
-- 1. Kolumna bez DEFAULT w definicji (zmiana w katalogu, bez przepisywania tabeli).
-- ---------------------------------------------------------------------------
ALTER TABLE email_message ADD COLUMN IF NOT EXISTS message_at TIMESTAMPTZ;

-- ---------------------------------------------------------------------------
-- 2. DEFAULT jako siec bezpieczenstwa dla wierszy wstawianych w trakcie migracji
--    i przez stare instancje aplikacji (patrz naglowek, pkt 2).
-- ---------------------------------------------------------------------------
ALTER TABLE email_message ALTER COLUMN message_at SET DEFAULT now();

-- ---------------------------------------------------------------------------
-- 3. Backfill partiami (keyset po PK). Kazda partia: zakres message_id
--    (v_last, v_next_last], filtr message_at IS NULL -> ponowne uruchomienie
--    nie zmienia wierszy juz wypelnionych. Cala migracja Flyway to jedna
--    transakcja, wiec partie ograniczaja pamiec i czas pojedynczego UPDATE,
--    ale blokady na zmienionych wierszach trzymane sa do COMMIT.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    c_batch_size    CONSTANT INT := 5000;
    v_rows_before   BIGINT;
    v_rows_updated  BIGINT := 0;
    v_batch_rows    BIGINT;
    v_last          UUID := '00000000-0000-0000-0000-000000000000';
    v_next_last     UUID;
BEGIN
    SELECT COUNT(*) INTO v_rows_before FROM email_message;

    LOOP
        SELECT s.message_id INTO v_next_last
        FROM (
            SELECT message_id
            FROM email_message
            WHERE message_id > v_last
            ORDER BY message_id
            LIMIT c_batch_size
        ) s
        ORDER BY s.message_id DESC
        LIMIT 1;

        EXIT WHEN v_next_last IS NULL;

        UPDATE email_message
        SET message_at = COALESCE(received_at, sent_at, created_at)
        WHERE message_id > v_last
          AND message_id <= v_next_last
          AND message_at IS NULL;

        GET DIAGNOSTICS v_batch_rows = ROW_COUNT;
        v_rows_updated := v_rows_updated + v_batch_rows;
        v_last := v_next_last;
    END LOOP;

    RAISE NOTICE 'V101 email_message backfill: % rows updated (% rows total before).',
        v_rows_updated, v_rows_before;
END $$;

-- ---------------------------------------------------------------------------
-- 4. Weryfikacja przed SET NOT NULL: zero NULL-i i 100 % zgodnosci z definicja.
--    Blad tutaj = ROLLBACK calej migracji (tabela bez zmian).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_null_count     BIGINT;
    v_mismatch_count BIGINT;
    v_total_count    BIGINT;
BEGIN
    SELECT COUNT(*) INTO v_total_count FROM email_message;

    SELECT COUNT(*) INTO v_null_count
    FROM email_message
    WHERE message_at IS NULL;

    IF v_null_count > 0 THEN
        RAISE EXCEPTION 'V101 aborted: % of % email_message rows still have message_at IS NULL after backfill',
            v_null_count, v_total_count;
    END IF;

    SELECT COUNT(*) INTO v_mismatch_count
    FROM email_message
    WHERE message_at IS DISTINCT FROM COALESCE(received_at, sent_at, created_at);

    IF v_mismatch_count > 0 THEN
        RAISE EXCEPTION 'V101 aborted: % email_message rows have message_at different from COALESCE(received_at, sent_at, created_at)',
            v_mismatch_count;
    END IF;

    RAISE NOTICE 'V101 email_message: message_at verified for % rows (0 NULL, 0 mismatches).', v_total_count;
END $$;

-- ---------------------------------------------------------------------------
-- 5. NOT NULL (po backfillu i weryfikacji). Pelny skan pod ACCESS EXCLUSIVE.
-- ---------------------------------------------------------------------------
ALTER TABLE email_message ALTER COLUMN message_at SET NOT NULL;

COMMENT ON COLUMN email_message.message_at IS
    'DB-067 / D4 (zalozenie robocze A): czas zaobserwowany przez system -- klucz '
    'partycjonowania RANGE (V102). INBOUND: INTERNALDATE serwera IMAP '
    '(Message#getReceivedDate()), fallback now() gdy brak; OUTBOUND: sentAt '
    'ustawiany raz. NIE jest naglowkiem Date nadawcy (kontrolowany przez nadawce). '
    'Backfill w V101: COALESCE(received_at, sent_at, created_at). Niemodyfikowalne '
    '(trigger w V102). Definicja wieku = idx_email_message_tenant_orphan_age (DB-059).';
