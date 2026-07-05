-- =============================================================================
-- V076__create_tenant_plugin_extension_binding.sql
-- DB-044: Bindingi punktów rozszerzeń per instalacja (EPIC-28).
-- =============================================================================

CREATE TABLE tenant_plugin_extension_binding (
    id                              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_plugin_installation_id  UUID        NOT NULL REFERENCES tenant_plugin_installation(id) ON DELETE CASCADE,
    tenant_id                       UUID        NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    extension_point                 VARCHAR(30) NOT NULL,
    invocation_mode                 VARCHAR(10) NOT NULL,
    timeout_ms                      INT         NOT NULL,
    display_order                   INT         NOT NULL DEFAULT 0,

    CONSTRAINT uq_tenant_plugin_extension_binding UNIQUE (tenant_plugin_installation_id, extension_point),
    CONSTRAINT chk_tenant_plugin_extension_binding_point CHECK (
        extension_point IN ('PRE_CONTACT_CONNECT', 'POST_CONTACT_END', 'CUSTOMER_SYNC', 'DISPOSITION_SET', 'MANUAL_ACTION')
    ),
    CONSTRAINT chk_tenant_plugin_extension_binding_mode CHECK (
        invocation_mode IN ('BLOCKING', 'ASYNC')
    ),
    CONSTRAINT chk_tenant_plugin_extension_binding_timeout CHECK (timeout_ms > 0 AND timeout_ms <= 60000)
);

-- Indeksy — lookup krytyczny dla ścieżki blocking (PRE_CONTACT_CONNECT, MANUAL_ACTION)
CREATE INDEX idx_tenant_plugin_extension_binding_lookup
    ON tenant_plugin_extension_binding (tenant_id, extension_point);

-- RLS
ALTER TABLE tenant_plugin_extension_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_plugin_extension_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_plugin_extension_binding_isolation ON tenant_plugin_extension_binding
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

COMMENT ON TABLE tenant_plugin_extension_binding IS
    'Punkty rozszerzeń deklarowane przez instalację, z trybem wywołania i timeoutem. Lookup krytyczny dla PRE_CONTACT_CONNECT (budżet 2s, ARCHITECTURE.md §11.5/§11.7).';
COMMENT ON COLUMN tenant_plugin_extension_binding.timeout_ms IS
    'Domyślne wartości platformy: PRE_CONTACT_CONNECT=2000, MANUAL_ACTION=5000, async=30000 — konfigurowalne per instalacja, capped przez maksimum platformy.';
