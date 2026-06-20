-- =============================================================================
-- V074__create_plugin_catalog.sql
-- DB-042: Globalny katalog pluginów (EPIC-28). Bez tenant_id/RLS — katalog
-- współdzielony; instalacja per tenant zaczyna się w V075 (tenant_plugin_installation).
-- =============================================================================

CREATE TABLE plugin (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_key      VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    vendor          VARCHAR(200) NOT NULL,
    vendor_contact  VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE plugin_version (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id           UUID        NOT NULL REFERENCES plugin(id) ON DELETE CASCADE,
    version             VARCHAR(50) NOT NULL,
    jar_object_key      VARCHAR(500) NOT NULL,
    checksum_sha256     VARCHAR(64) NOT NULL,
    manifest_json       JSONB       NOT NULL,
    sdk_version         VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    validation_errors   JSONB,
    uploaded_by_user_id UUID        REFERENCES app_user(user_id) ON DELETE SET NULL,
    uploaded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_plugin_version_plugin_version UNIQUE (plugin_id, version),
    CONSTRAINT chk_plugin_version_status CHECK (
        status IN ('UPLOADED', 'VALIDATED', 'PENDING_REVIEW', 'REJECTED', 'REVOKED')
    )
);

-- Indeksy
CREATE INDEX idx_plugin_version_plugin
    ON plugin_version (plugin_id, uploaded_at DESC);

CREATE INDEX idx_plugin_version_status
    ON plugin_version (status)
    WHERE status IN ('VALIDATED', 'PENDING_REVIEW');

COMMENT ON TABLE plugin IS
    'Globalny katalog pluginów (EPIC-28). Bez tenant_id/RLS — definicja współdzielona, instalacja per tenant w tenant_plugin_installation (ADR-13).';
COMMENT ON TABLE plugin_version IS
    'Wersje JAR-a pluginu, niemutowalne po VALIDATED. Nowa wersja = nowy wiersz, nigdy edycja (analogia do Flyway).';
COMMENT ON COLUMN plugin_version.jar_object_key IS
    'Klucz obiektu w MinIO/S3 (ten sam bucket family co recording, ARCHITECTURE.md §3.1).';
COMMENT ON COLUMN plugin_version.manifest_json IS
    'Pełny sparsowany META-INF/plugin-manifest.json — przechowywany dla audytu/replay.';
COMMENT ON COLUMN plugin_version.status IS
    'UPLOADED → VALIDATED|REJECTED (walidacja), VALIDATED → PENDING_REVIEW (jeśli wymagany manual review), * → REVOKED (kill switch globalny, ADR-11).';
