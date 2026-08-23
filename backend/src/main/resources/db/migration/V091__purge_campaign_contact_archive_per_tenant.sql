-- =============================================================================
-- V091__purge_campaign_contact_archive_per_tenant.sql
-- BE-119 (EPIC-29): purge_campaign_contact_archive() per-tenant, cutoff jawny
--
-- Migracja: Flyway V091
-- Zaleznosci: V015 (funkcja oryginalna), V082 (tenant_retention_policy, kategoria
--             CAMPAIGN_DATA), V089 (idx_cca_tenant_archived_at -- indeks pod dokladnie
--             ten wzorzec zapytania)
-- Odniesienie: TASKS-BACKEND.md BE-119, EPIC-29
--
-- Problem: purge_campaign_contact_archive(p_retention_years INT DEFAULT 5) z V015
--          usuwa WSZYSTKIE wiersze campaign_contact_archive starsze niz cutoff,
--          globalnie, dla wszystkich tenantow naraz -- brak filtracji po tenant_id.
--          campaign_contact_archive NIE ma wlaczonego RLS (w odroznieniu od
--          wiekszosci tabel domenowych -- patrz V012), wiec ten brak filtra WHERE
--          byl JEDYNYM brakujacym elementem izolacji -- nie istnieje zaden inny
--          mechanizm chroniacy dane innych tenantow przed tym DELETE.
--
-- Rozwiazanie (Opcja A, patrz ticket BE-119): funkcja przyjmuje teraz
-- p_tenant_id UUID (obowiazkowy, filtruje DELETE) oraz p_cutoff_date TIMESTAMPTZ
-- (jawna granica czasowa zamiast lat) -- RetentionPurgeServiceImpl juz wylicza
-- Instant cutoff jednolicie dla wszystkich kategorii PRZED dyspatchem do metody
-- per-kategoria (patrz purgeAsync), wiec przekazanie go wprost jest spojniejsze
-- niz przeliczanie retentionMonths (tenant_retention_policy) -> lata w dwie strony.
--
-- Stara sygnatura purge_campaign_contact_archive(INT) jest jawnie usuwana (DROP),
-- NIE pozostawiana jako przeciazenie -- pozostawienie jej dzialajacej rownolegle
-- byloby niebezpiecznym reliktem: reczne wywolanie starej wersji po tej migracji
-- usunieloby dane WSZYSTKICH tenantow naraz.
-- =============================================================================

DROP FUNCTION IF EXISTS purge_campaign_contact_archive(INT);

CREATE FUNCTION purge_campaign_contact_archive(
    p_tenant_id   UUID,
    p_cutoff_date TIMESTAMPTZ
) RETURNS INT
LANGUAGE plpgsql
AS $$
DECLARE
    v_deleted_count INT;
BEGIN
    DELETE FROM campaign_contact_archive
    WHERE tenant_id  = p_tenant_id
      AND archived_at < p_cutoff_date;

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    INSERT INTO cron_log (job_name, finished_at, status, message, rows_affected)
    VALUES (
        'purge_campaign_contact_archive',
        NOW(),
        'SUCCESS',
        format('Usunieto %s rekordow tenanta %s starszych niz %s',
               v_deleted_count, p_tenant_id, p_cutoff_date),
        v_deleted_count
    );

    RETURN v_deleted_count;
END;
$$;

COMMENT ON FUNCTION purge_campaign_contact_archive(UUID, TIMESTAMPTZ) IS
    'Usuwa rekordy archiwum kampanii (kategoria CAMPAIGN_DATA) JEDNEGO tenanta '
    'starsze niz p_cutoff_date. Wywolywana per-tenant przez RetentionPurgeService '
    '(BE-119) -- NIGDY globalnie. Zastepuje purge_campaign_contact_archive(INT) '
    'z V015, ktora usuwala dane wszystkich tenantow jednym wywolaniem.';

-- ---------------------------------------------------------------------------
-- Aktualizacja opisu w scheduled_job -- pg_cron jest wylaczony (patrz V014),
-- wiec zmiana sygnatury nie lamie zadnego dzialajacego automatycznego wywolania.
-- Aktualizujemy jedynie opis, zeby metadane odzwierciedlaly rzeczywistosc: funkcja
-- wymaga teraz tenant_id, wiec nie moze byc juz wywolana jednym globalnym zadaniem.
-- ---------------------------------------------------------------------------

UPDATE scheduled_job
SET description = 'Usuwa rekordy z campaign_contact_archive starsze niz polityka retencji '
                  'CAMPAIGN_DATA tenanta (tenant_retention_policy). Od V091/BE-119 wywolywana '
                  'per-tenant przez RetentionPurgeService, nie jako pojedyncze zadanie globalne.'
WHERE job_name = 'purge_campaign_contact_archive';
