-- =============================================================================
-- V005__tenant_config_schema.sql
-- DB-005: Rozszerzenie konfiguracji TENANT – widok statystyk, funkcje pomocnicze
--
-- Migracja: Flyway V005
-- Zaleznosci: V002 (tenant), V003 (app_user)
-- Odniesienie PRD: US-01-03, US-01-04, EPIC-01
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Widok v_tenant_stats – agregacja statystyk per tenant (dashboard admina)
-- ---------------------------------------------------------------------------
-- Widok NIE jest zmaterializowany (dane zmieniaja sie czesto).
-- Dla wydajnosci korzysta z indeksow na tabelach zrodlowych.

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
-- 2. Funkcja weryfikacji czy tenant nie przekroczyl limitu zasobow
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION check_tenant_limit(
    p_tenant_id   UUID,
    p_resource    TEXT   -- 'agents', 'queues', 'campaigns'
) RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    v_current_count INT;
    v_limit         INT;
    v_count_query   TEXT;
BEGIN
    v_limit := get_tenant_limit(p_tenant_id, 'max_' || p_resource);

    IF v_limit IS NULL THEN
        RAISE EXCEPTION 'Nieznany zasob: % (dopuszczalne: agents, queues, campaigns)', p_resource;
    END IF;

    -- Dynamiczne zliczanie aktualnych zasobow
    IF p_resource = 'agents' THEN
        SELECT COUNT(*) INTO v_current_count
        FROM   app_user
        WHERE  tenant_id = p_tenant_id
          AND  role = 'AGENT'
          AND  is_deleted = FALSE;

    ELSIF p_resource = 'queues' THEN
        -- Tabela QUEUE tworzona w V010 – zabezpieczenie przed brakiem tabeli
        BEGIN
            SELECT COUNT(*) INTO v_current_count
            FROM   queue
            WHERE  tenant_id = p_tenant_id
              AND  is_active = TRUE;
        EXCEPTION WHEN undefined_table THEN
            v_current_count := 0;
        END;

    ELSIF p_resource = 'campaigns' THEN
        BEGIN
            SELECT COUNT(*) INTO v_current_count
            FROM   campaign
            WHERE  tenant_id = p_tenant_id
              AND  status NOT IN ('STOPPED', 'COMPLETED');
        EXCEPTION WHEN undefined_table THEN
            v_current_count := 0;
        END;
    END IF;

    RETURN v_current_count < v_limit;
END;
$$;

COMMENT ON FUNCTION check_tenant_limit(UUID, TEXT) IS
    'Sprawdza czy tenant nie przekroczyl limitu zasobu (agents/queues/campaigns). '
    'Zwraca TRUE jesli mozna dodac nowy zasob. '
    'Wywolywana przez aplikacje przed tworzeniem nowego agenta/kolejki/kampanii (US-01-03).';
