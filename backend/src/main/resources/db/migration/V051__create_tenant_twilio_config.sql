-- =============================================================================
-- V051__create_tenant_twilio_config.sql
-- DB-030: Konfiguracja Twilio per tenant z szyfrowaniem AES-256-GCM
--
-- Migracja: Flyway V051
-- Zależności: V001 (uuid-ossp), V002 (tenant), V012 (RLS)
--
-- STRATEGIA:
-- 1. Tabela tenant_twilio_config: jeden wiersz per tenant (UNIQUE tenant_id)
-- 2. Wrażliwe pola (account_sid, auth_token, api_key_sid, api_key_secret)
--    szyfrowane przez aplikację – JPA AttributeConverter (AES-256-GCM).
--    Baza przechowuje zaszyfrowany tekst w formacie Base64(IV||ciphertext).
-- 3. Partial index na tenant_id WHERE is_active – wspiera lookup aktywnej
--    konfiguracji (wzorzec: "pobierz aktywny config Twilio dla tenanta X")
-- 4. RLS policy ALL – izolacja między tenantami; wzorzec zgodny z V048
--    (current_setting 'app.current_tenant_id').
-- 5. COMMENT ON COLUMN – dokumentacja mechanizmu szyfrowania dla audytu
--    i przyszłych deweloperów.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Tabela tenant_twilio_config
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tenant_twilio_config (
    config_id           UUID         NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    account_sid         VARCHAR(255) NOT NULL,
    auth_token          TEXT         NOT NULL,
    api_key_sid         VARCHAR(255),
    api_key_secret      TEXT,
    twiml_app_sid       VARCHAR(64),
    phone_number        VARCHAR(30),
    status_callback_url TEXT,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT pk_tenant_twilio_config      PRIMARY KEY (config_id),
    CONSTRAINT uq_tenant_twilio_config      UNIQUE (tenant_id)
);

-- ---------------------------------------------------------------------------
-- 2. Indeks
-- ---------------------------------------------------------------------------

-- Partial index – tylko aktywne konfiguracje; wspiera szybkie wyszukiwanie
-- aktywnego config Twilio per tenant (najczęstszy wzorzec dostępu w runtime)
CREATE INDEX IF NOT EXISTS idx_tenant_twilio_config_tenant
    ON tenant_twilio_config (tenant_id)
    WHERE is_active;

-- ---------------------------------------------------------------------------
-- 3. Row Level Security
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_twilio_config ENABLE ROW LEVEL SECURITY;

-- Polityka ALL: tenant widzi i operuje tylko na własnej konfiguracji Twilio
-- Wzorzec zgodny z konwencją projektu (V012, V041, V042, V048)
CREATE POLICY tenant_twilio_config_isolation ON tenant_twilio_config
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- ---------------------------------------------------------------------------
-- 4. Komentarze – dokumentacja szyfrowania (wymagana przez audyt)
-- ---------------------------------------------------------------------------

COMMENT ON TABLE tenant_twilio_config IS
    'Konfiguracja Twilio per tenant. '
    'Wrażliwe pola (account_sid, auth_token, api_key_sid, api_key_secret) '
    'szyfrowane AES-256-GCM przez aplikację (JPA AttributeConverter). '
    'Baza przechowuje Base64(IV||ciphertext). '
    'Izolacja per-tenant przez RLS (current_setting app.current_tenant_id). '
    'Jeden wiersz per tenant – wymuszane przez UNIQUE (tenant_id).';

COMMENT ON COLUMN tenant_twilio_config.config_id IS
    'Klucz główny konfiguracji (UUID generowany automatycznie).';

COMMENT ON COLUMN tenant_twilio_config.tenant_id IS
    'FK do tenant.tenant_id. Kolumna używana przez politykę RLS. '
    'UNIQUE – jeden config per tenant.';

COMMENT ON COLUMN tenant_twilio_config.account_sid IS
    'Szyfrowane AES-256-GCM przez aplikację; wartość w bazie to Base64(IV||ciphertext).';

COMMENT ON COLUMN tenant_twilio_config.auth_token IS
    'Szyfrowane AES-256-GCM przez aplikację; wartość w bazie to Base64(IV||ciphertext).';

COMMENT ON COLUMN tenant_twilio_config.api_key_sid IS
    'Szyfrowane AES-256-GCM przez aplikację; NULL gdy tenant używa globalnych kredencjałów.';

COMMENT ON COLUMN tenant_twilio_config.api_key_secret IS
    'Szyfrowane AES-256-GCM przez aplikację; NULL gdy tenant używa globalnych kredencjałów.';

COMMENT ON COLUMN tenant_twilio_config.twiml_app_sid IS
    'SID aplikacji TwiML (plaintext). Opcjonalne – wymagane tylko przy Voice SDK.';

COMMENT ON COLUMN tenant_twilio_config.phone_number IS
    'Numer prezentacji tenanta w formacie E.164 (np. +48221234567). Plaintext.';

COMMENT ON COLUMN tenant_twilio_config.status_callback_url IS
    'URL webhooka statusowego Twilio (np. https://tenant.example.com/twilio/status). Plaintext.';

COMMENT ON COLUMN tenant_twilio_config.is_active IS
    'Flaga aktywności konfiguracji. FALSE = konfiguracja wyłączona bez usuwania rekordu.';

COMMENT ON COLUMN tenant_twilio_config.created_at IS
    'Znacznik czasu utworzenia rekordu (UTC).';

COMMENT ON COLUMN tenant_twilio_config.updated_at IS
    'Znacznik czasu ostatniej modyfikacji rekordu (UTC). NULL jeśli nie modyfikowano.';
