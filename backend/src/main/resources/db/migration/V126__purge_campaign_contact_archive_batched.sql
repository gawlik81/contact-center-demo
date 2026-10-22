-- =============================================================================
-- V126__purge_campaign_contact_archive_batched.sql
-- DB-056 (EPIC-30, DESIGN-message-retention-and-partitioning.md SS2 U12):
-- zbatchowana purge_campaign_contact_archive -- pojedynczy DELETE -> partie.
--
-- Migracja: Flyway V126 (nastepna wolna wersja: V125 = ostatnia zastosowana i ostatnia
--           w repo na wszystkich galeziach; V104 zarezerwowane celowo; V999 to osobny
--           plik db/seed/V999__dev_seed.sql, poza lokalizacja db/migration).
-- Zaleznosci:
--   V091  obecna sygnatura (UUID, TIMESTAMPTZ) -- jawnie usuwana tutaj
--   V089  idx_cca_tenant_archived_at (tenant_id, archived_at) -- indeks pod wybor partii
--   V111  RLS + FORCE na campaign_contact_archive (polityka po GUC app.current_tenant_id)
-- Blokuje: BE-121 (petla w Javie), DB-069, DB-075
--
-- UWAGA WDROZENIOWA (kryterium DB-056): ZMIANA SEMANTYKI WARTOSCI ZWRACANEJ.
--   Dotad funkcja zwracala liczbe WSZYSTKICH usunietych wierszy (jeden DELETE).
--   Od V126 usuwa NAJWYZEJ p_batch_size wierszy (domyslnie 10000) i zwraca liczbe
--   usunietych w TEJ partii. Do czasu petli w Javie (BE-121:
--   CampaignArchiveRetentionRepository#purgeEligible wolajaca funkcje do wyniku 0)
--   POJEDYNCZE WYWOLANIE USUWA TYLKO PIERWSZA PARTIE. Dlatego V126 i BE-121 musza
--   isc w jednym wydaniu. Jedyny wolajacy: CampaignArchiveRetentionRepository.
--
-- Zmiany wzgledem V091:
--   * p_batch_size INT DEFAULT 10000, walidacja 1..100000 (poza zakresem -> 22023)
--   * partie identyfikowane pelnym PK (record_id, campaign_id), nie ctid -- odporne na
--     przyszla konwersje na tabele partycjonowana (DB-069)
--   * kolejnosc ORDER BY archived_at zgodna z idx_cca_tenant_archived_at (najstarsze
--     pierwsze; remisy nie szkodza, bo usuwamy po PK)
--   * FOR UPDATE SKIP LOCKED w wyborze partii: rownolegle wywolania (np. dwa nody)
--     nie blokuja sie ani nie zakleszczaja, tylko biora rozlaczne wiersze
--   * cron_log: wpis TYLKO gdy usunieto > 0 wierszy. Wynik 0 konczy petle Javy, wiec
--     wpis przy 0 byl by czystym spamem (1 zbedny wiersz na tenanta na przebieg).
--
-- WYBOR cron_log: jeden wpis NA PARTIE (a nie agregat). Uzasadnienie: (1) funkcja nie
--   zna sumy calego przebiegu -- agregat musialby liczyc Java (BE-121) i pisac osobno;
--   (2) wpis per partia daje wpis audytowy z rows_affected i finished_at kazdej
--   transakcji, co ma znaczenie przy diagnozie przerwanego/dlugiego purge (widac, ile
--   partii przeszlo przed awaria); (3) wpisy powstaja w tej samej transakcji co DELETE,
--   wiec sa spojne z faktycznie usunietymi wierszami. Koszt: przy bardzo duzym archiwum
--   (np. 1 mln wierszy / 10000) ~100 wpisow na tenanta na przebieg -- akceptowalne,
--   cron_log ma swoja retencje.
--
-- RLS / TENANT: sposob obslugi tenanta IDENTYCZNY jak w V091 -- funkcja jest SECURITY
--   INVOKER, filtruje jawnie po p_tenant_id i nie dotyka GUC. Pod rola z BYPASSRLS
--   (ccapp, migracyjna) dziala bez GUC; pod app_user (V111: FORCE RLS) wymaga
--   app.current_tenant_id == p_tenant_id, inaczej DELETE widzi 0 wierszy (cichy wynik 0,
--   bez wpisu cron_log). Wywolujacy odpowiada za ustawienie kontekstu tenanta.
--
-- Nie zmienia: tabel, indeksow, danych, GRANT-ow (nie wymaga SET LOCAL lock_timeout --
--   brak DDL na tabeli).
-- =============================================================================

-- Jawny DROP starej sygnatury (UUID, TIMESTAMPTZ): nie zostawiamy dwoch przeciazen,
-- bo wywolanie dwuargumentowe byloby niejednoznaczne przy DEFAULT trzeciego argumentu.
-- Idempotentne (IF EXISTS).
DROP FUNCTION IF EXISTS purge_campaign_contact_archive(UUID, TIMESTAMPTZ);

CREATE FUNCTION purge_campaign_contact_archive(
    p_tenant_id   UUID,
    p_cutoff_date TIMESTAMPTZ,
    p_batch_size  INT DEFAULT 10000
) RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_deleted_count INT;
BEGIN
    IF p_batch_size IS NULL OR p_batch_size < 1 OR p_batch_size > 100000 THEN
        RAISE EXCEPTION 'purge_campaign_contact_archive: p_batch_size musi byc w zakresie 1..100000, otrzymano %',
            p_batch_size
            USING ERRCODE = 'invalid_parameter_value';
    END IF;

    WITH batch AS (
        SELECT record_id, campaign_id
        FROM campaign_contact_archive
        WHERE tenant_id   = p_tenant_id
          AND archived_at < p_cutoff_date
        ORDER BY archived_at
        LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    DELETE FROM campaign_contact_archive a
    USING batch b
    WHERE a.record_id   = b.record_id
      AND a.campaign_id = b.campaign_id;

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    IF v_deleted_count > 0 THEN
        INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
        VALUES (
            'purge_campaign_contact_archive',
            NOW(),
            'SUCCESS',
            format('Usunieto %s rekordow (partia, limit %s) tenanta %s starszych niz %s',
                   v_deleted_count, p_batch_size, p_tenant_id, p_cutoff_date),
            v_deleted_count
        );
    END IF;

    RETURN v_deleted_count;
END;
$$;

COMMENT ON FUNCTION purge_campaign_contact_archive(UUID, TIMESTAMPTZ, INT) IS
    'Usuwa NAJWYZEJ p_batch_size (domyslnie 10000, zakres 1..100000) najstarszych rekordow '
    'archiwum kampanii (CAMPAIGN_DATA) JEDNEGO tenanta z archived_at < p_cutoff_date i zwraca '
    'liczbe usunietych w tej partii. Wolac w petli (BE-121) do wyniku 0; pojedyncze wywolanie '
    'usuwa tylko pierwsza partie. Wpis cron_log (jeden na partie) tylko gdy wynik > 0. '
    'SECURITY INVOKER: pod app_user wymaga GUC app.current_tenant_id = p_tenant_id (RLS V111). '
    'NIGDY globalnie. Zastepuje wersje 2-argumentowa z V091 (DB-056).';

-- ---------------------------------------------------------------------------
-- Weryfikacja koncowa: w pg_proc wylacznie sygnatura 3-argumentowa. Blad = ROLLBACK.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_count INT;
    v_args  TEXT;
BEGIN
    SELECT COUNT(*), MAX(pg_get_function_identity_arguments(oid))
      INTO v_count, v_args
    FROM pg_proc
    WHERE proname = 'purge_campaign_contact_archive'
      AND pronamespace = 'public'::regnamespace;

    IF v_count <> 1 OR v_args <> 'p_tenant_id uuid, p_cutoff_date timestamp with time zone, p_batch_size integer' THEN
        RAISE EXCEPTION 'V126: oczekiwano dokladnie jednej sygnatury 3-argumentowej purge_campaign_contact_archive, znaleziono % (%)',
            v_count, v_args;
    END IF;
END;
$$;
