-- =============================================================================
-- V016__contact_referential_integrity.sql
-- DB-002 (uzupelnienie): Integralnosc referencyjna tabeli CONTACT
--                         + widoki diagnostyczne schematu
--
-- Migracja: Flyway V016
-- Zaleznosci: V007 (contact), V006 (customer), V003 (app_user),
--             V008 (queue), V009 (campaign), V012 (rls)
-- Odniesienie PRD: NFR-SEC05, US-03-05, EPIC-03
--
-- Problem: Tabela CONTACT jest partycjonowana (PARTITION BY RANGE).
--   PostgreSQL NIE pozwala deklarowac FK na tabelach partycjonowanych jako
--   referencje DO tych tabel (np. email_message.contact_id -> contact.contact_id
--   bez podania klucza partycjonowania). Komentarz w V007 to odnotowuje.
--
--   Rozwiazanie: trigger BEFORE INSERT/UPDATE na CONTACT walidujacy referencje
--   do customer, app_user, queue i campaign. Dla tabel odwolujacych sie DO contact
--   (email_message, social_message) – walidacja po stronie aplikacji (Spring).
--
-- Dodatkowo: widoki operacyjne dla monitorowania zdrowia schematu i RLS.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Trigger integralnosci referencyjnej dla tabeli CONTACT
-- ---------------------------------------------------------------------------
-- Dlaczego trigger a nie FK:
--   * CONTACT jest PARTITION BY RANGE (started_at)
--   * PostgreSQL 16 NIE obsluguje FK referencujacych tabele partycjonowane
--     bez podania klucza partycji w kluczu glownym docelowej tabeli
--     (pk_contact = (contact_id, started_at) – aplikacja musi podac oba pola)
--   * FK z email_message/social_message na contact.contact_id (tylko UUID) sa
--     technicznie niemozliwe bez zmiany PK – wybrano walidacje aplikacyjna
--   * FK z CONTACT na customer/app_user/queue/campaign mozna egzekwowac
--     przez trigger BEFORE INSERT/UPDATE

CREATE OR REPLACE FUNCTION fn_contact_ref_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Walidacja customer_id (nullable – NULL = nieznany klient)
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

    -- Walidacja agent_id (nullable – NULL = brak agenta / kolejka)
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

    -- Walidacja campaign_id (nullable – NULL = kontakt inbound bez kampanii)
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
    'Trigger walidujacy integralnosc referencyjana tabeli CONTACT. '
    'Zastepuje niedostepne FK na tabelach partycjonowanych. '
    'Sprawdza: customer_id, agent_id, queue_id, campaign_id vs. tenant_id.';

CREATE TRIGGER trg_contact_ref_integrity
    BEFORE INSERT OR UPDATE ON contact
    FOR EACH ROW
    EXECUTE FUNCTION fn_contact_ref_integrity();

-- ---------------------------------------------------------------------------
-- 2. Widok operacyjny: aktywne kontakty real-time (dashboard supervisora)
-- ---------------------------------------------------------------------------
-- Uzywany przez BE-029 (Real-Time Dashboard) – US-10-01
-- Zapytanie optymalizowane przez indeks idx_contact_tenant_status (V007)

CREATE OR REPLACE VIEW v_active_contacts AS
SELECT
    c.contact_id,
    c.tenant_id,
    c.channel,
    c.direction,
    c.status,
    c.customer_id,
    c.agent_id,
    c.queue_id,
    c.remote_address,
    c.queued_at,
    c.assigned_at,
    c.started_at,

    -- Czas oczekiwania w sekundach (od queued_at do teraz, jesli jeszcze nie przypisany)
    CASE
        WHEN c.assigned_at IS NOT NULL THEN
            EXTRACT(EPOCH FROM (c.assigned_at - c.queued_at))::INT
        ELSE
            EXTRACT(EPOCH FROM (NOW() - c.queued_at))::INT
    END                                                         AS wait_time_seconds,

    -- Czas trwania kontaktu (od started_at)
    EXTRACT(EPOCH FROM (NOW() - c.started_at))::INT            AS contact_age_seconds,

    -- Dane agenta (LEFT JOIN – moze byc NULL)
    u.first_name                                                AS agent_first_name,
    u.last_name                                                 AS agent_last_name,

    -- Nazwa kolejki
    q.name                                                      AS queue_name

FROM  contact    c
LEFT JOIN app_user u ON u.user_id   = c.agent_id
LEFT JOIN queue    q ON q.queue_id  = c.queue_id

WHERE c.status IN ('QUEUED', 'ACTIVE', 'ON_HOLD');

COMMENT ON VIEW v_active_contacts IS
    'Aktywne kontakty w czasie rzeczywistym (status QUEUED/ACTIVE/ON_HOLD). '
    'Uzywany przez dashboard supervisora (BE-029, US-10-01). '
    'Korzysta z indeksu idx_contact_tenant_status (V007).';

-- ---------------------------------------------------------------------------
-- 3. Widok: podsumowanie kolejek real-time (KPI supervisora)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_queue_realtime_stats AS
SELECT
    q.queue_id,
    q.tenant_id,
    q.name                                                          AS queue_name,
    q.routing_strategy,

    -- Kontakty w kolejce (oczekujace na agenta)
    COUNT(c.contact_id) FILTER (WHERE c.status = 'QUEUED')         AS contacts_waiting,

    -- Kontakty aktualnie obsługiwane
    COUNT(c.contact_id) FILTER (WHERE c.status = 'ACTIVE')         AS contacts_active,

    -- ON_HOLD
    COUNT(c.contact_id) FILTER (WHERE c.status = 'ON_HOLD')        AS contacts_on_hold,

    -- Najdluzej czekajacy kontakt (sekundy)
    COALESCE(
        MAX(
            EXTRACT(EPOCH FROM (NOW() - c.queued_at))::INT
        ) FILTER (WHERE c.status = 'QUEUED'),
        0
    )                                                               AS max_wait_seconds,

    -- Sredni czas oczekiwania
    COALESCE(
        ROUND(
            AVG(
                EXTRACT(EPOCH FROM (NOW() - c.queued_at))
            ) FILTER (WHERE c.status = 'QUEUED')
        )::INT,
        0
    )                                                               AS avg_wait_seconds,

    -- Dostepni agenci w tej kolejce
    COUNT(DISTINCT qa.agent_id) FILTER (
        WHERE u.status = 'AVAILABLE' AND u.is_deleted = FALSE
    )                                                               AS agents_available,

    -- Zajeci agenci
    COUNT(DISTINCT qa.agent_id) FILTER (
        WHERE u.status IN ('BUSY', 'AFTER_CONTACT') AND u.is_deleted = FALSE
    )                                                               AS agents_busy

FROM  queue       q
LEFT JOIN contact    c  ON c.queue_id = q.queue_id
                       AND c.status  IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
LEFT JOIN queue_agent qa ON qa.queue_id = q.queue_id
LEFT JOIN app_user    u  ON u.user_id   = qa.agent_id

WHERE q.is_active = TRUE

GROUP BY q.queue_id, q.tenant_id, q.name, q.routing_strategy;

COMMENT ON VIEW v_queue_realtime_stats IS
    'Statystyki kolejek w czasie rzeczywistym: oczekujace kontakty, dostepni agenci, '
    'maks. i sredni czas oczekiwania. Uzywany przez dashboard supervisora (US-07-04).';

-- ---------------------------------------------------------------------------
-- 4. Widok diagnostyczny: stan konfiguracji RLS
-- ---------------------------------------------------------------------------
-- Pozwala administratorowi szybko sprawdzic czy RLS jest wlaczone
-- na wszystkich wymaganych tabelach (pomocny przy wdrozeniu i audycie)

CREATE OR REPLACE VIEW v_rls_status AS
SELECT
    c.relname                                           AS table_name,
    c.relrowsecurity                                    AS rls_enabled,
    c.relforcerowsecurity                               AS rls_forced,
    COUNT(p.polname)                                    AS policy_count,
    STRING_AGG(p.polname, ', ' ORDER BY p.polname)     AS policy_names
FROM  pg_class c
LEFT JOIN pg_policy p ON p.polrelid = c.oid
WHERE c.relkind    = 'r'          -- tylko zwykle tabele (nie widoki, nie partycje)
  AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')
  AND c.relname IN (
      'customer', 'contact', 'campaign', 'queue', 'app_user',
      'ivr_tree', 'audit_log', 'email_message', 'social_message',
      'social_integration'
  )
GROUP BY c.relname, c.relrowsecurity, c.relforcerowsecurity
ORDER BY c.relname;

COMMENT ON VIEW v_rls_status IS
    'Diagnostyka konfiguracji Row Level Security na kluczowych tabelach. '
    'Uzywany przez administratora do weryfikacji izolacji tenant-ow (NFR-SEC05). '
    'Wszystkie wiersze powinny miec rls_enabled = TRUE i policy_count >= 1.';

-- ---------------------------------------------------------------------------
-- 5. Widok diagnostyczny: zdrowie indeksow (bloated lub nieuzywane)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_index_health AS
SELECT
    schemaname                                          AS schema_name,
    relname                                             AS table_name,
    indexrelname                                        AS index_name,
    idx_scan                                            AS scans,
    idx_tup_read                                        AS tuples_read,
    idx_tup_fetch                                       AS tuples_fetched,
    pg_size_pretty(pg_relation_size(indexrelid))        AS index_size,

    -- Indeksy z 0 skanow sa kandydatami do usuniecia
    CASE WHEN idx_scan = 0 THEN 'UNUSED' ELSE 'ACTIVE' END AS usage_status

FROM  pg_stat_user_indexes
WHERE schemaname = 'public'
ORDER BY
    CASE WHEN idx_scan = 0 THEN 0 ELSE 1 END,  -- nieuzywane na gorze
    pg_relation_size(indexrelid) DESC;

COMMENT ON VIEW v_index_health IS
    'Statystyki uzycia indeksow z pg_stat_user_indexes. '
    'Indeksy z scans = 0 (UNUSED) sa kandydatami do przegladu. '
    'UWAGA: statystyki resetuja sie po ANALYZE lub restarcie PostgreSQL.';
