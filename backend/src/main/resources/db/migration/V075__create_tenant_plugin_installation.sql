-- =============================================================================
-- V075__create_tenant_plugin_installation.sql
-- DB-043: Instalacja pluginu per tenant (EPIC-28). RLS od tej tabeli (ADR-13).
-- =============================================================================

CREATE TABLE tenant_plugin_installation (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID        NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    plugin_version_id          UUID        NOT NULL REFERENCES plugin_version(id) ON DELETE RESTRICT,
    enabled                     BOOLEAN     NOT NULL DEFAULT FALSE,
    granted_permissions         JSONB       NOT NULL DEFAULT '[]'::JSONB,
    health_status               VARCHAR(20) NOT NULL DEFAULT 'HEALTHY',
    consecutive_failure_count   INT         NOT NULL DEFAULT 0,
    installation_config         JSONB,
    installed_by_user_id        UUID        REFERENCES app_user(user_id) ON DELETE SET NULL,
    installed_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tenant_plugin_installation_version UNIQUE (tenant_id, plugin_version_id),
    CONSTRAINT chk_tenant_plugin_installation_health CHECK (
        health_status IN ('HEALTHY', 'DEGRADED', 'DISABLED_BY_ADMIN')
    )
);

-- Indeksy
CREATE INDEX idx_tenant_plugin_installation_tenant
    ON tenant_plugin_installation (tenant_id, enabled);

CREATE INDEX idx_tenant_plugin_installation_version
    ON tenant_plugin_installation (plugin_version_id);

-- RLS
ALTER TABLE tenant_plugin_installation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_plugin_installation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_plugin_installation_isolation ON tenant_plugin_installation
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

COMMENT ON TABLE tenant_plugin_installation IS
    'Instalacja konkretnej wersji pluginu dla tenanta. Upgrade = nowy wiersz (stary enabled=false = rollback). RLS od tej tabeli (ADR-13).';
COMMENT ON COLUMN tenant_plugin_installation.granted_permissions IS
    'Podzbiór uprawnień z manifestu zatwierdzony przez admina tenanta — NIE auto-grant z manifestu.';
COMMENT ON COLUMN tenant_plugin_installation.installation_config IS
    'Konfiguracja tenanta (np. API key zewnętrznego CRM) — szyfrowana AES-256-GCM, wzorzec konwertera jak tenant_ai_config/tenant_twilio_config.';
COMMENT ON COLUMN tenant_plugin_installation.health_status IS
    'HEALTHY domyślnie; DEGRADED po N kolejnych timeoutów/wyjątków (circuit breaker, ARCHITECTURE.md §11.7); DISABLED_BY_ADMIN po ręcznym wyłączeniu.';
