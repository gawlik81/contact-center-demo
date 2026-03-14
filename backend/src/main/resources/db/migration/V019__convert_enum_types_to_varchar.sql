-- =============================================================================
-- V019__convert_enum_types_to_varchar.sql
-- Konwersja kolumn z typow PostgreSQL ENUM na VARCHAR + CHECK constraints
--
-- Problem: Hibernate 6 binduje wartosci enum jako VARCHAR (JDBC type = 12),
-- co powoduje blad "column X is of type some_enum but expression is of type
-- character varying" przy INSERT/UPDATE.
-- columnDefinition = "some_enum" w encji wplywa tylko na DDL (CREATE TABLE),
-- nie na bindowanie parametrow JDBC.
--
-- Rozwiazanie: konwersja do VARCHAR(50) z CHECK constraint zachowuje te same
-- gwarancje integralnosci bez koniecznosci rzutowania po stronie JDBC.
--
-- Tabele i kolumny:
--   tenant.status        (tenant_status)
--   app_user.role        (user_role)
--   app_user.status      (user_status)
--
-- Widoki zalezne (DROP przed ALTER, odtworzone na koncu):
--   v_tenant_stats           (V005) – uzywa tenant.status, app_user.role, app_user.status
--   v_queue_available_agents (V008) – uzywa app_user.status
--   v_queue_realtime_stats   (V016) – uzywa app_user.status
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Usuniecie widokow zaleznych od zmienianych kolumn
-- ---------------------------------------------------------------------------
-- Kolejnosc: najpierw widoki zalezne od innych widokow (gdyby byly),
-- potem widoki bazowe. CASCADE zapewnia usuniecie zaleznosci kaskadowo.

DROP VIEW IF EXISTS v_tenant_stats CASCADE;
DROP VIEW IF EXISTS v_queue_available_agents CASCADE;
DROP VIEW IF EXISTS v_queue_realtime_stats CASCADE;

-- ---------------------------------------------------------------------------
-- 2. tenant.status: tenant_status -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE tenant
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE tenant
    ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;

ALTER TABLE tenant
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE tenant
    ADD CONSTRAINT chk_tenant_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'));

DROP TYPE tenant_status;

-- ---------------------------------------------------------------------------
-- 3. app_user.role: user_role -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    ALTER COLUMN role TYPE VARCHAR(50) USING role::TEXT;

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_role
        CHECK (role IN ('ADMIN', 'SUPERVISOR', 'AGENT'));

DROP TYPE user_role;

-- ---------------------------------------------------------------------------
-- 4. app_user.status: user_status -> VARCHAR(50)
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    ALTER COLUMN status DROP DEFAULT;

ALTER TABLE app_user
    ALTER COLUMN status TYPE VARCHAR(50) USING status::TEXT;

ALTER TABLE app_user
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE app_user
    ADD CONSTRAINT chk_app_user_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'AVAILABLE', 'BUSY', 'BREAK', 'AFTER_CONTACT'));

DROP TYPE user_status;

-- ---------------------------------------------------------------------------
-- 5. Odtworzenie widoku v_tenant_stats (oryginal z V005)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_tenant_stats AS
SELECT
    t.tenant_id,
    t.name                                              AS tenant_name,
    t.status                                            AS tenant_status,

    -- Liczba uzytkownikow per rola (warunkowe agregaty – jeden scan tabeli)
    COUNT(u.user_id) FILTER (
        WHERE u.role = 'AGENT' AND u.is_deleted = FALSE
    )                                                   AS agent_count,

    COUNT(u.user_id) FILTER (
        WHERE u.role = 'SUPERVISOR' AND u.is_deleted = FALSE
    )                                                   AS supervisor_count,

    COUNT(u.user_id) FILTER (
        WHERE u.status IN ('AVAILABLE','BUSY','BREAK','AFTER_CONTACT')
          AND u.is_deleted = FALSE
    )                                                   AS agents_online,

    -- Limity z konfiguracji (dla porownania z aktualnymi wartosciami)
    get_tenant_limit(t.tenant_id, 'max_agents')         AS limit_max_agents,
    get_tenant_limit(t.tenant_id, 'max_queues')         AS limit_max_queues,
    get_tenant_limit(t.tenant_id, 'max_campaigns')      AS limit_max_campaigns,

    t.created_at                                        AS tenant_created_at,
    t.updated_at                                        AS tenant_updated_at

FROM  tenant    t
LEFT JOIN app_user u ON u.tenant_id = t.tenant_id

GROUP BY
    t.tenant_id, t.name, t.status, t.created_at, t.updated_at;

COMMENT ON VIEW v_tenant_stats IS
    'Statystyki operacyjne per tenant dla dashboardu administratora (US-01-04). '
    'Zawiera liczbe agentow, supervisorow, agentow online oraz aktualne limity z konfiguracji. '
    'Dane na zywo (nie zmaterializowany) – korzyta z indeksow idx_user_tenant_role i idx_user_tenant_status.';

-- ---------------------------------------------------------------------------
-- 6. Odtworzenie widoku v_queue_available_agents (oryginal z V008)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_queue_available_agents AS
SELECT
    qa.queue_id,
    q.tenant_id,
    q.routing_strategy,
    q.required_skills                       AS queue_required_skills,
    u.user_id                               AS agent_id,
    u.skills                                AS agent_skills,
    u.status                                AS agent_status
FROM   queue       q
JOIN   queue_agent qa ON qa.queue_id = q.queue_id
JOIN   app_user    u  ON u.user_id   = qa.agent_id
WHERE  q.is_active     = TRUE
  AND  u.status        = 'AVAILABLE'
  AND  u.is_deleted    = FALSE;

COMMENT ON VIEW v_queue_available_agents IS
    'Agenci dostepni dla kazdej kolejki. Uzywany przez routing engine (DB-010). '
    'Dla SKILL_BASED: aplikacja filtruje po agent_skills @> queue_required_skills.';

-- ---------------------------------------------------------------------------
-- 7. Odtworzenie widoku v_queue_realtime_stats (oryginal z V016)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE VIEW v_queue_realtime_stats AS
SELECT
    q.queue_id,
    q.tenant_id,
    q.name                                                          AS queue_name,
    q.routing_strategy,

    -- Kontakty w kolejce (oczekujace na agenta)
    COUNT(c.contact_id) FILTER (WHERE c.status = 'QUEUED')         AS contacts_waiting,

    -- Kontakty aktualnie obslugiwane
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
