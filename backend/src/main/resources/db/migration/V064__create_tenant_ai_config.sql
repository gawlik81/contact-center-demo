-- =============================================================================
-- V064__create_tenant_ai_config.sql
-- DB-038: Konfiguracja dostawcy AI per tenant.
-- Klucz api_key szyfrowany AES-256-GCM przez EncryptedStringConverter (JPA).
-- Wzorzec: analogiczny do tenant_twilio_config (V051).
-- =============================================================================

CREATE TYPE ai_provider AS ENUM ('ANTHROPIC', 'OPENAI', 'AZURE_OPENAI');

CREATE TABLE tenant_ai_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,

    provider                ai_provider NOT NULL,
    api_key_encrypted       TEXT NOT NULL,
    model_name              VARCHAR(100) NOT NULL,

    -- Opcjonalne — używane tylko dla Azure OpenAI
    azure_endpoint          VARCHAR(500),
    azure_deployment_name   VARCHAR(100),

    -- Prompt systemowy do podsumowania; NULL = użyj domyślnego z aplikacji
    summary_prompt_template TEXT,

    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_tenant_ai_config UNIQUE (tenant_id)
);

CREATE INDEX idx_tenant_ai_config_tenant ON tenant_ai_config (tenant_id) WHERE is_active;

ALTER TABLE tenant_ai_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_ai_config_isolation ON tenant_ai_config
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

COMMENT ON TABLE tenant_ai_config IS
    'Konfiguracja dostawcy AI per tenant. api_key_encrypted przechowywany jako szyfrowany blob AES-256-GCM.';
COMMENT ON COLUMN tenant_ai_config.api_key_encrypted IS
    'Klucz API dostawcy AI (Claude / OpenAI / Azure). Szyfrowany przez EncryptedStringConverter, nigdy nie eksponować plaintext przez REST.';
COMMENT ON COLUMN tenant_ai_config.summary_prompt_template IS
    'Opcjonalny prompt systemowy nadpisujący domyślny z aplikacji. NULL = użyj domyślnego.';
COMMENT ON COLUMN tenant_ai_config.azure_endpoint IS
    'Wymagane tylko dla AZURE_OPENAI: URL endpointu (https://<resource>.openai.azure.com/).';
