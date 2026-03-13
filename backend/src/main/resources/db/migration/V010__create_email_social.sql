-- =============================================================================
-- V010__create_email_social.sql
-- DB-007 + DB-008: Tabele EMAIL_MESSAGE, EMAIL_TEMPLATE, SOCIAL_INTEGRATION, SOCIAL_MESSAGE
--
-- Migracja: Flyway V010
-- Zaleznosci: V007 (contact), V002 (tenant), V003 (app_user)
-- Odniesienie PRD: US-05-01 do US-05-04, US-06-01 do US-06-04, EPIC-05, EPIC-06
-- =============================================================================

-- ===========================================================================
-- CZESC 1: EMAIL (DB-007)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1.1 Tabela EMAIL_MESSAGE
-- ---------------------------------------------------------------------------

CREATE TABLE email_message (
    message_id          UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID            NOT NULL,
    contact_id          UUID            NOT NULL,   -- FK do contact (partycjonowanego)
    direction           contact_direction NOT NULL,

    -- Adresy email
    from_address        VARCHAR(255)    NOT NULL,
    to_address          TEXT            NOT NULL,   -- moze byc lista adresow
    cc_address          TEXT,
    bcc_address         TEXT,

    subject             VARCHAR(998),   -- RFC 2822 max Subject length

    -- Tresc wiadomosci
    body_html           TEXT,           -- HTML wersja (bez limitu)
    body_text           TEXT,           -- Plaintext wersja (bez limitu)

    -- RFC 2822 Message-ID header – unikalny identyfikator wiadomosci w swiecie email
    -- Uzytkowany do deduplikacji (wielokrotne pobieranie IMAP)
    -- Format: <unique@domain>
    message_id_header   VARCHAR(255),

    -- Dla odpowiedzi: ID wiadomosci, na ktora odpowiadamy (RFC 2822 In-Reply-To)
    in_reply_to         VARCHAR(255),

    -- Zalaczniki (metadane – same pliki w Object Storage)
    -- [{"filename": "umowa.pdf", "content_type": "application/pdf", "size_bytes": 102400, "s3_url": "..."}]
    attachments         JSONB           NOT NULL DEFAULT '[]',

    -- Czas dostarczenia/wyslania
    received_at         TIMESTAMPTZ,    -- Dla INBOUND: czas odebrania przez system
    sent_at             TIMESTAMPTZ,    -- Dla OUTBOUND: czas wyslania przez system

    -- Status wyslania (dla OUTBOUND)
    delivery_status     VARCHAR(30),    -- PENDING, SENT, DELIVERED, BOUNCED, FAILED

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_message PRIMARY KEY (message_id),

    -- FK do tabeli CONTACT (partycjonowanej) – nie mozna uzyc FK na partycjonowanej tabeli
    -- Egzekwowane na poziomie aplikacji
    CONSTRAINT fk_email_message_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    -- Deduplikacja: ten sam message_id_header nie moze pojawic sie dwukrotnie w tenancie
    CONSTRAINT uq_email_message_id_header
        UNIQUE (tenant_id, message_id_header)
        DEFERRABLE INITIALLY DEFERRED,  -- Odroczone dla importu zbiorczego

    CONSTRAINT chk_email_attachments_is_array
        CHECK (jsonb_typeof(attachments) = 'array')
);

-- Indeks dla historii wiadomosci w kontakcie (US-05-01)
CREATE INDEX idx_email_message_contact
    ON email_message (contact_id, received_at DESC);

-- Filtrowanie po statusie dostarczenia (monitoring i retry)
CREATE INDEX idx_email_message_delivery
    ON email_message (tenant_id, delivery_status)
    WHERE delivery_status IN ('PENDING', 'FAILED');

COMMENT ON TABLE  email_message                     IS 'Wiadomosci email w ramach kontaktow. Jeden kontakt = witek emailowy (wiele wiadomosci).';
COMMENT ON COLUMN email_message.message_id_header   IS 'RFC 2822 Message-ID. Uzytkowany do deduplikacji przy wielokrotnym pobraniu IMAP.';
COMMENT ON COLUMN email_message.in_reply_to         IS 'RFC 2822 In-Reply-To. Powiazanie odpowiedzi z poprzednia wiadomoscia w watku.';
COMMENT ON COLUMN email_message.attachments         IS 'Metadane zalacznikow (JSONB). Pliki przechowywane w S3-compatible storage.';

-- ---------------------------------------------------------------------------
-- 1.2 Tabela EMAIL_TEMPLATE (US-05-03)
-- ---------------------------------------------------------------------------

CREATE TABLE email_template (
    template_id         UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id           UUID            NOT NULL,

    name                VARCHAR(255)    NOT NULL,

    -- Szablon tematu wiadomosci z placeholderami, np. "Witaj {{first_name}}, ..."
    subject_template    VARCHAR(998)    NOT NULL,

    -- Tresc szablonu HTML z placeholderami
    body_html           TEXT            NOT NULL,
    body_text           TEXT,           -- Opcjonalna wersja tekstowa

    -- Lista dostepnych zmiennych w szablonie
    -- Przyklad: [{"name": "first_name", "description": "Imie klienta"}, ...]
    variables           JSONB           NOT NULL DEFAULT '[]',

    -- Kategoria szablonu (np. WELCOME, FOLLOW_UP, PROMOTION)
    category            VARCHAR(50),

    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,

    created_by          UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ,

    CONSTRAINT pk_email_template PRIMARY KEY (template_id),

    CONSTRAINT fk_email_template_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_email_template_created_by
        FOREIGN KEY (created_by)
        REFERENCES app_user (user_id)
        ON DELETE SET NULL,

    CONSTRAINT uq_email_template_tenant_name
        UNIQUE (tenant_id, name),

    CONSTRAINT chk_email_template_variables_is_array
        CHECK (jsonb_typeof(variables) = 'array')
);

CREATE INDEX idx_email_template_tenant_active
    ON email_template (tenant_id, is_active);

CREATE TRIGGER trg_email_template_updated_at
    BEFORE UPDATE ON email_template
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE email_template IS 'Szablony odpowiedzi email. Zarzadzane przez supervisora, uzywane przez agentow (US-05-03).';

-- ---------------------------------------------------------------------------
-- 1.3 Tabela EMAIL_ROUTING_RULE (US-05-02)
-- ---------------------------------------------------------------------------

CREATE TABLE email_routing_rule (
    rule_id         UUID            NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id       UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,

    -- Kolejka docelowa dla emaili spelniajacych regule
    queue_id        UUID,

    -- Warunki routingu (evaluowane sekwencyjnie)
    -- Schemat: [
    --   {"field": "from_address", "operator": "CONTAINS", "value": "@vip-client.com"},
    --   {"field": "subject", "operator": "REGEX", "value": "^URGENT.*$"},
    --   {"field": "to_address", "operator": "EQUALS", "value": "support@company.com"}
    -- ]
    conditions      JSONB           NOT NULL DEFAULT '[]',

    -- Priorytet reguly (niz = wyzszy priorytet)
    priority        INT             NOT NULL DEFAULT 100,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_routing_rule PRIMARY KEY (rule_id),

    CONSTRAINT fk_err_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_err_queue
        FOREIGN KEY (queue_id)
        REFERENCES queue (queue_id)
        ON DELETE SET NULL,

    CONSTRAINT chk_err_conditions_is_array
        CHECK (jsonb_typeof(conditions) = 'array')
);

CREATE INDEX idx_email_routing_rule_tenant
    ON email_routing_rule (tenant_id, priority)
    WHERE is_active = TRUE;

COMMENT ON TABLE email_routing_rule IS 'Reguly routingu emaili do kolejek (US-05-02). Ewaluowane po priorytecie.';

-- ===========================================================================
-- CZESC 2: SOCIAL MEDIA (DB-008)
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 2.1 Typy ENUM
-- ---------------------------------------------------------------------------

CREATE TYPE social_platform AS ENUM (
    'FACEBOOK',
    'INSTAGRAM',
    'WHATSAPP'
);

COMMENT ON TYPE social_platform IS 'Obsługiwane platformy social media w MVP. Rozszerzalne przez ENUM ALTER.';

-- ---------------------------------------------------------------------------
-- 2.2 Tabela SOCIAL_INTEGRATION (US-06-02)
-- ---------------------------------------------------------------------------

CREATE TABLE social_integration (
    integration_id          UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id               UUID                NOT NULL,
    platform                social_platform     NOT NULL,

    -- ID strony/konta na platformie (np. Facebook Page ID)
    page_id                 VARCHAR(255)        NOT NULL,

    -- Nazwa konta (czytelna dla supervisora, np. "Facebook – Obsluga Klienta")
    display_name            VARCHAR(255),

    -- Token dostepu zaszyfrowany (AES-256) – pgcrypto lub szyfrowanie aplikacyjne
    -- Przechowywany jako BYTEA, nigdy w plaintext
    access_token_encrypted  BYTEA,

    -- Czas wygasniecia tokenu (do monitorowania i odswiezania)
    token_expires_at        TIMESTAMPTZ,

    -- URL webhooka dla tej integracji
    webhook_url             TEXT,

    -- Status integracji: ACTIVE, INACTIVE, ERROR, EXPIRED_TOKEN
    webhook_status          VARCHAR(20)         NOT NULL DEFAULT 'ACTIVE',

    -- Konfiguracja per-platforma (np. Webhook verify token dla FB)
    platform_config         JSONB               NOT NULL DEFAULT '{}',

    created_at              TIMESTAMPTZ         NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ,

    CONSTRAINT pk_social_integration PRIMARY KEY (integration_id),

    CONSTRAINT fk_social_integration_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    -- Jeden tenant moze miec tylko jedno konto per strona per platforma
    CONSTRAINT uq_social_integration_tenant_platform_page
        UNIQUE (tenant_id, platform, page_id),

    CONSTRAINT chk_social_webhook_status
        CHECK (webhook_status IN ('ACTIVE', 'INACTIVE', 'ERROR', 'EXPIRED_TOKEN'))
);

CREATE INDEX idx_social_integration_tenant_platform
    ON social_integration (tenant_id, platform)
    WHERE webhook_status = 'ACTIVE';

CREATE TRIGGER trg_social_integration_updated_at
    BEFORE UPDATE ON social_integration
    FOR EACH ROW
    EXECUTE FUNCTION fn_set_updated_at();

COMMENT ON TABLE  social_integration                    IS 'Konfiguracja polaczen z platformami social media per tenant (US-06-02).';
COMMENT ON COLUMN social_integration.access_token_encrypted IS 'Token dostepu zaszyfrowany AES-256. Nigdy nie przechowywany jako plaintext.';

-- ---------------------------------------------------------------------------
-- 2.3 Tabela SOCIAL_MESSAGE (US-06-01)
-- ---------------------------------------------------------------------------

CREATE TABLE social_message (
    message_id              UUID                NOT NULL DEFAULT uuid_generate_v4(),
    tenant_id               UUID                NOT NULL,
    contact_id              UUID                NOT NULL,   -- FK do contact
    integration_id          UUID,

    platform                social_platform     NOT NULL,
    direction               contact_direction   NOT NULL,

    -- Zewnetrzny ID wiadomosci z platformy – dla idempotentnosci webhooka
    external_message_id     VARCHAR(255)        NOT NULL,

    -- Zewnetrzny ID nadawcy na platformie (np. Facebook user ID)
    sender_external_id      VARCHAR(255),

    -- Tresc wiadomosci tekstowej
    content                 TEXT,

    -- Zalaczniki (zdjecia, filmy, dokumenty)
    -- [{"type": "IMAGE", "url": "...", "size_bytes": 204800}]
    attachments             JSONB               NOT NULL DEFAULT '[]',

    -- Czas wyslania na platformie
    sent_at                 TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    -- Czas dostarczenia do agenta przez webhook
    received_at             TIMESTAMPTZ,

    created_at              TIMESTAMPTZ         NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_social_message PRIMARY KEY (message_id),

    CONSTRAINT fk_social_message_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_social_message_integration
        FOREIGN KEY (integration_id)
        REFERENCES social_integration (integration_id)
        ON DELETE SET NULL,

    -- Idempotentnosc webhookow: ten sam external_message_id nie moze pojawic sie 2x
    CONSTRAINT uq_social_message_external_id
        UNIQUE (tenant_id, external_message_id),

    CONSTRAINT chk_social_attachments_is_array
        CHECK (jsonb_typeof(attachments) = 'array')
);

-- Historia konwersacji z klientem (US-06-04)
CREATE INDEX idx_social_message_contact
    ON social_message (contact_id, sent_at DESC);

-- Lookup po nadawcy (identyfikacja klienta przy nowej wiadomosci)
CREATE INDEX idx_social_message_sender
    ON social_message (tenant_id, platform, sender_external_id);

COMMENT ON TABLE  social_message                    IS 'Wiadomosci z platform social media. Idempotentne przez UNIQUE (tenant_id, external_message_id).';
COMMENT ON COLUMN social_message.external_message_id IS 'ID wiadomosci z platformy social media. Gwarantuje idempotentnosc przy wielokrotnym deliveries webhooka.';
