-- V078: Izolacja katalogu pluginów per-tenant (EPIC-28)
-- plugin_version była tabelą globalną bez tenant_id — każdy supervisor widział pluginy
-- wszystkich tenantów. Każdy upload JAR-a musi należeć do tenanta, który go wgrał.

-- 1. Dodaj kolumnę (nullable na czas backfill)
ALTER TABLE plugin_version ADD COLUMN tenant_id UUID REFERENCES tenant(tenant_id) ON DELETE CASCADE;

-- 2. Backfill z app_user.tenant_id przez uploaded_by_user_id
UPDATE plugin_version pv
SET tenant_id = u.tenant_id
FROM app_user u
WHERE pv.uploaded_by_user_id = u.user_id
  AND pv.tenant_id IS NULL;

-- 3. NOT NULL po backfill
ALTER TABLE plugin_version ALTER COLUMN tenant_id SET NOT NULL;

-- 4. Nowe unikalne ograniczenie (per-tenant, nie globalne)
ALTER TABLE plugin_version DROP CONSTRAINT uq_plugin_version_plugin_version;
ALTER TABLE plugin_version ADD CONSTRAINT uq_plugin_version_plugin_version_tenant
    UNIQUE (plugin_id, version, tenant_id);

-- 5. Indeks
CREATE INDEX idx_plugin_version_tenant ON plugin_version (tenant_id, uploaded_at DESC);
