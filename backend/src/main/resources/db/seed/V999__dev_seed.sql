-- =============================================================================
-- V999__dev_seed.sql
-- DB-019: Dane testowe dla srodowiska DEV
--
-- UWAGA: Ten plik uruchamiany TYLKO przy profilu spring.profiles.active=dev
-- Konfiguracja Flyway: locations = classpath:db/migration, classpath:db/seed
-- Dla srodowisk prod/staging: locations = classpath:db/migration (bez seed)
--
-- Migracja: Flyway V999 (wysoki numer = zawsze ostatnia)
-- Idempotentna: ON CONFLICT DO NOTHING na wszystkich INSERT
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 0. Ustawienie kontekstu (RLS pomijamy w seed – uzywamy admin_user lub superuser)
-- ---------------------------------------------------------------------------

SET app.current_tenant_id = '00000000-0000-0000-0000-000000000000';

-- ---------------------------------------------------------------------------
-- 1. Tenanty testowe
-- ---------------------------------------------------------------------------

INSERT INTO tenant (tenant_id, name, status, config) VALUES
(
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Acme Corporation',
    'ACTIVE',
    '{"max_agents":100,"max_queues":50,"max_campaigns":20,"recording_retention_days":90,"timezone":"Europe/Warsaw"}'
),
(
    'aaaaaaaa-0000-0000-0000-000000000002',
    'Beta Telecom',
    'ACTIVE',
    '{"max_agents":50,"max_queues":20,"max_campaigns":10,"recording_retention_days":60,"timezone":"Europe/Warsaw"}'
)
ON CONFLICT (tenant_id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Uzytkownicy (haslo: Test@12345 – bcrypt 12 rund)
-- Prawdziwy hash bcrypt dla hasla "Test@12345" wygenerowany offline
-- ---------------------------------------------------------------------------

-- ADMIN globalny
INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, first_name, last_name, status, mfa_enabled) VALUES
(
    'bbbbbbbb-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'ADMIN',
    'admin@contactcenter.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',  -- Test@12345
    'System',
    'Administrator',
    'ACTIVE',
    FALSE
)
ON CONFLICT DO NOTHING;

-- Supervisorzy
INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, first_name, last_name, status) VALUES
(
    'bbbbbbbb-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'SUPERVISOR',
    'supervisor1@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Anna',
    'Kowalska',
    'ACTIVE'
),
(
    'bbbbbbbb-0000-0000-0000-000000000003',
    'aaaaaaaa-0000-0000-0000-000000000002',
    'SUPERVISOR',
    'supervisor1@beta.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Marek',
    'Nowak',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

-- Agenci dla Acme Corporation
INSERT INTO app_user (user_id, tenant_id, role, email, password_hash, first_name, last_name, skills, status) VALUES
(
    'bbbbbbbb-0000-0000-0000-000000000010',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'AGENT',
    'agent1@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Piotr',
    'Wisniewksi',
    '["SALES","POLISH","BILLING"]',
    'AVAILABLE'
),
(
    'bbbbbbbb-0000-0000-0000-000000000011',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'AGENT',
    'agent2@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Katarzyna',
    'Lewandowska',
    '["TECH_SUPPORT","POLISH","ENGLISH"]',
    'BUSY'
),
(
    'bbbbbbbb-0000-0000-0000-000000000012',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'AGENT',
    'agent3@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Tomasz',
    'Wojciechowski',
    '["BILLING","COMPLAINTS","POLISH"]',
    'BREAK'
),
(
    'bbbbbbbb-0000-0000-0000-000000000013',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'AGENT',
    'agent4@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Magdalena',
    'Dabrowska',
    '["SALES","ENGLISH","GERMAN"]',
    'AFTER_CONTACT'
),
(
    'bbbbbbbb-0000-0000-0000-000000000014',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'AGENT',
    'agent5@acme.dev',
    '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
    'Lukasz',
    'Kaminski',
    '["TECH_SUPPORT","BILLING","POLISH"]',
    'INACTIVE'
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Kolejki
-- ---------------------------------------------------------------------------

INSERT INTO queue (queue_id, tenant_id, name, routing_strategy, required_skills) VALUES
(
    'cccccccc-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Sprzedaz',
    'SKILL_BASED',
    '["SALES","POLISH"]'
),
(
    'cccccccc-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Wsparcie techniczne',
    'SKILL_BASED',
    '["TECH_SUPPORT"]'
),
(
    'cccccccc-0000-0000-0000-000000000003',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Rozliczenia',
    'ROUND_ROBIN',
    '["BILLING"]'
)
ON CONFLICT DO NOTHING;

-- Przypisanie agentow do kolejek
INSERT INTO queue_agent (queue_id, agent_id) VALUES
('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000010'),  -- agent1 -> Sprzedaz
('cccccccc-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000013'),  -- agent4 -> Sprzedaz
('cccccccc-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000011'),  -- agent2 -> Tech
('cccccccc-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000014'),  -- agent5 -> Tech
('cccccccc-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000012'),  -- agent3 -> Billing
('cccccccc-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000014')   -- agent5 -> Billing
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. IVR Trees
-- ---------------------------------------------------------------------------

INSERT INTO ivr_tree (ivr_id, tenant_id, name, definition, is_active, created_by) VALUES
(
    'dddddddd-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Menu glowne Acme',
    '{
        "entry_node_id": "node-1",
        "nodes": [
            {
                "node_id": "node-1",
                "type": "MENU",
                "prompt": "Witamy w Acme Corporation. Nacisnij 1 dla sprzedazy, 2 dla wsparcia technicznego, 3 dla rozliczen.",
                "timeout_seconds": 5,
                "max_retries": 2,
                "options": [
                    {"key": "1", "next_node_id": "node-2"},
                    {"key": "2", "next_node_id": "node-3"},
                    {"key": "3", "next_node_id": "node-4"}
                ]
            },
            {
                "node_id": "node-2",
                "type": "QUEUE_TRANSFER",
                "queue_id": "cccccccc-0000-0000-0000-000000000001",
                "prompt": "Laczenie z dzialem sprzedazy. Prosimy o chwile cierpliwosci."
            },
            {
                "node_id": "node-3",
                "type": "QUEUE_TRANSFER",
                "queue_id": "cccccccc-0000-0000-0000-000000000002",
                "prompt": "Laczenie z dzialem wsparcia technicznego."
            },
            {
                "node_id": "node-4",
                "type": "QUEUE_TRANSFER",
                "queue_id": "cccccccc-0000-0000-0000-000000000003",
                "prompt": "Laczenie z dzialem rozliczen."
            }
        ]
    }',
    TRUE,
    'bbbbbbbb-0000-0000-0000-000000000002'
),
(
    'dddddddd-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'IVR Nocny (poza godzinami)',
    '{
        "entry_node_id": "node-1",
        "nodes": [
            {
                "node_id": "node-1",
                "type": "PLAY_AUDIO",
                "prompt": "Dziekujemy za kontakt. Nasze biuro jest czynne w godzinach 9:00-18:00 od poniedzialku do piatku. Prosimy o kontakt w godzinach pracy.",
                "next_node_id": "node-2"
            },
            {
                "node_id": "node-2",
                "type": "HANGUP"
            }
        ]
    }',
    FALSE,
    'bbbbbbbb-0000-0000-0000-000000000002'
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. Klienci (50 rekordow – fikcyjne dane)
-- ---------------------------------------------------------------------------

INSERT INTO customer (customer_id, tenant_id, first_name, last_name, phone, email, gdpr_consent, source) VALUES
('eeeeeeee-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 'Jan', 'Kowalski', '["+48501111001"]', '["jan.kowalski@example.com"]', '{"consent_given":true,"consent_date":"2025-01-15","marketing_consent":true}', 'MANUAL'),
('eeeeeeee-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 'Maria', 'Nowak', '["+48501111002"]', '["maria.nowak@example.com"]', '{"consent_given":true,"consent_date":"2025-02-10","marketing_consent":false}', 'CSV_IMPORT'),
('eeeeeeee-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001', 'Andrzej', 'Wisniewski', '["+48501111003"]', '["a.wisniewski@example.com"]', '{"consent_given":true,"consent_date":"2025-03-01","marketing_consent":true}', 'INBOUND_PHONE'),
('eeeeeeee-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001', 'Agnieszka', 'Wojciechowska', '["+48501111004","+48601111004"]', '["agnieszka.w@example.com"]', '{"consent_given":true,"consent_date":"2025-04-20","marketing_consent":true}', 'CSV_IMPORT'),
('eeeeeeee-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000001', 'Piotr', 'Kowalczyk', '["+48501111005"]', '["piotr.k@example.com","piotr@work.com"]', '{"consent_given":false}', 'INBOUND_EMAIL')
ON CONFLICT DO NOTHING;

-- Wstawienie dodatkowych 45 klientow (uproszczone)
INSERT INTO customer (tenant_id, first_name, last_name, phone, email, gdpr_consent, source)
SELECT
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Klient' || n,
    'Testowy' || n,
    ('["' || '+485011' || LPAD(n::TEXT, 5, '0') || '"]')::jsonb,
    ('["klient' || n || '@example.com"]')::jsonb,
    '{"consent_given":true,"marketing_consent":true}'::jsonb,
    CASE WHEN n % 3 = 0 THEN 'CSV_IMPORT'
         WHEN n % 3 = 1 THEN 'INBOUND_PHONE'
         ELSE 'MANUAL' END
FROM generate_series(6, 50) AS n
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Kampania testowa
-- ---------------------------------------------------------------------------

INSERT INTO campaign (campaign_id, tenant_id, name, type, dialer_type, schedule, status, queue_id, disposition_codes, created_by) VALUES
(
    'ffffffff-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'Kampania Sprzedazowa Q1 2026',
    'OUTBOUND_VOICE',
    'PROGRESSIVE',
    '{"start_date":"2026-03-01","end_date":"2026-03-31","active_hours":{"from":"09:00","to":"18:00"},"active_days":["MON","TUE","WED","THU","FRI"],"timezone":"Europe/Warsaw"}',
    'RUNNING',
    'cccccccc-0000-0000-0000-000000000001',
    '[
        {"code":"SALE","label":"Sprzedaz dokonana"},
        {"code":"DECLINED","label":"Odmowa"},
        {"code":"NO_ANSWER","label":"Brak odpowiedzi"},
        {"code":"CALLBACK","label":"Oddzwonienie"},
        {"code":"INVALID","label":"Nieprawidlowy numer"}
    ]',
    'bbbbbbbb-0000-0000-0000-000000000002'
)
ON CONFLICT DO NOTHING;

-- Partycja dla kampanii testowej (LIST partitioning po campaign_id)
CREATE TABLE IF NOT EXISTS campaign_contact_ffffffff_0000_0000_0000_000000000001
    PARTITION OF campaign_contact
    FOR VALUES IN ('ffffffff-0000-0000-0000-000000000001');

-- Wstawienie 100 rekordow kampanii
INSERT INTO campaign_contact (campaign_id, tenant_id, customer_id, phone, first_name, last_name, status, attempt_count, next_attempt_at)
SELECT
    'ffffffff-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    CASE WHEN n <= 5 THEN ('eeeeeeee-0000-0000-0000-0000000000' || LPAD(n::TEXT, 2, '0'))::UUID ELSE NULL END,
    '+485011' || LPAD(n::TEXT, 5, '0'),
    'Klient' || n,
    'Testowy' || n,
    CASE
        WHEN n <= 20  THEN 'COMPLETED'
        WHEN n <= 30  THEN 'NO_ANSWER'
        WHEN n <= 35  THEN 'CONNECTED'
        WHEN n <= 40  THEN 'FAILED'
        ELSE 'PENDING'
    END,
    CASE WHEN n <= 35 THEN (n % 3 + 1) ELSE 0 END,
    CASE WHEN n > 40 THEN NOW() + (n || ' minutes')::INTERVAL ELSE NULL END
FROM generate_series(1, 100) AS n
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7. Przykladowe kontakty (mix kanalow i statusow)
-- ---------------------------------------------------------------------------

INSERT INTO contact (contact_id, tenant_id, customer_id, agent_id, queue_id, channel, direction, status, remote_address, queued_at, assigned_at, started_at, ended_at, duration_seconds, disposition_code) VALUES
-- Polaczenia telefoniczne zakonczone
('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000010', 'cccccccc-0000-0000-0000-000000000001', 'PHONE', 'INBOUND', 'COMPLETED', '+48501111001', NOW()-INTERVAL '2 hours', NOW()-INTERVAL '1 hour 55 min', NOW()-INTERVAL '1 hour 55 min', NOW()-INTERVAL '1 hour 45 min', 600, 'RESOLVED'),
('11111111-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000002', 'bbbbbbbb-0000-0000-0000-000000000011', 'cccccccc-0000-0000-0000-000000000002', 'PHONE', 'INBOUND', 'COMPLETED', '+48501111002', NOW()-INTERVAL '3 hours', NOW()-INTERVAL '2 hour 58 min', NOW()-INTERVAL '2 hour 58 min', NOW()-INTERVAL '2 hours 40 min', 1080, 'ESCALATED'),
-- Email
('11111111-0000-0000-0000-000000000003', 'aaaaaaaa-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000003', 'bbbbbbbb-0000-0000-0000-000000000012', 'cccccccc-0000-0000-0000-000000000003', 'EMAIL', 'INBOUND', 'COMPLETED', 'a.wisniewski@example.com', NOW()-INTERVAL '1 day', NOW()-INTERVAL '23 hours', NOW()-INTERVAL '23 hours', NOW()-INTERVAL '22 hours', 3600, 'RESOLVED'),
-- Social media
('11111111-0000-0000-0000-000000000004', 'aaaaaaaa-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000004', 'bbbbbbbb-0000-0000-0000-000000000013', 'cccccccc-0000-0000-0000-000000000001', 'SOCIAL_FACEBOOK', 'INBOUND', 'COMPLETED', 'fb:user:123456', NOW()-INTERVAL '5 hours', NOW()-INTERVAL '4 hours 55 min', NOW()-INTERVAL '4 hours 55 min', NOW()-INTERVAL '4 hours 30 min', 1500, 'RESOLVED'),
-- Aktywny kontakt
('11111111-0000-0000-0000-000000000005', 'aaaaaaaa-0000-0000-0000-000000000001', 'eeeeeeee-0000-0000-0000-000000000005', 'bbbbbbbb-0000-0000-0000-000000000010', 'cccccccc-0000-0000-0000-000000000001', 'PHONE', 'OUTBOUND', 'ACTIVE', '+48501111005', NOW()-INTERVAL '10 min', NOW()-INTERVAL '8 min', NOW()-INTERVAL '8 min', NULL, NULL, NULL),
-- Kontakt w kolejce
('11111111-0000-0000-0000-000000000006', 'aaaaaaaa-0000-0000-0000-000000000001', NULL, NULL, 'cccccccc-0000-0000-0000-000000000002', 'PHONE', 'INBOUND', 'QUEUED', '+48502999888', NOW()-INTERVAL '3 min', NULL, NOW()-INTERVAL '3 min', NULL, NULL, NULL),
-- Porzucony
('11111111-0000-0000-0000-000000000007', 'aaaaaaaa-0000-0000-0000-000000000001', NULL, NULL, 'cccccccc-0000-0000-0000-000000000001', 'PHONE', 'INBOUND', 'ABANDONED', '+48503777666', NOW()-INTERVAL '30 min', NULL, NOW()-INTERVAL '30 min', NOW()-INTERVAL '25 min', NULL, NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8. Przykladowe integracje social media
-- ---------------------------------------------------------------------------

INSERT INTO social_integration (integration_id, tenant_id, platform, page_id, display_name, webhook_status) VALUES
(
    '22222222-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'FACEBOOK',
    'fb-page-123456789',
    'Acme – Facebook Obsluga Klienta',
    'ACTIVE'
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 9. Potwierdzenie seed
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    RAISE NOTICE '=== DEV SEED ZAKONCZONY ===';
    RAISE NOTICE 'Tenanty: %', (SELECT COUNT(*) FROM tenant);
    RAISE NOTICE 'Uzytkownicy: %', (SELECT COUNT(*) FROM app_user);
    RAISE NOTICE 'Kolejki: %', (SELECT COUNT(*) FROM queue);
    RAISE NOTICE 'Klienci: %', (SELECT COUNT(*) FROM customer);
    RAISE NOTICE 'Kampanie: %', (SELECT COUNT(*) FROM campaign);
    RAISE NOTICE 'Rekordy kampanii: %', (SELECT COUNT(*) FROM campaign_contact);
    RAISE NOTICE 'Kontakty: %', (SELECT COUNT(*) FROM contact);
    RAISE NOTICE '========================';
END $$;

-- ---------------------------------------------------------------------------
-- Phone numbers i routing rules dla tenanta Acme Corporation
--    Tenant: aaaaaaaa-0000-0000-0000-000000000001
--    IVR:    dddddddd-0000-0000-0000-000000000001 (Menu glowne Acme)
--    Queue:  cccccccc-0000-0000-0000-000000000002 (Wsparcie techniczne – after hours)
-- ---------------------------------------------------------------------------

INSERT INTO phone_number (phone_number_id, tenant_id, number, display_name, is_active)
VALUES
(
    'eeeeeeee-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    '+48221234567',
    'Linia główna Acme',
    TRUE
),
(
    'eeeeeeee-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    '+48221234568',
    'Linia sprzedaży Acme',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Reguła 1: pon-pt 08:00-17:00 → IVR Menu główne
INSERT INTO phone_routing_rule (rule_id, tenant_id, phone_number_id, ivr_tree_id, queue_id, days_of_week, time_start, time_end, is_active)
VALUES
(
    'ffffffff-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'eeeeeeee-0000-0000-0000-000000000001',
    'dddddddd-0000-0000-0000-000000000001',
    NULL,
    ARRAY[1,2,3,4,5],
    '08:00',
    '17:00',
    TRUE
)
ON CONFLICT DO NOTHING;

-- Reguła 2: pon-pt 17:00-20:00 → kolejka Wsparcie techniczne (after hours)
INSERT INTO phone_routing_rule (rule_id, tenant_id, phone_number_id, ivr_tree_id, queue_id, days_of_week, time_start, time_end, is_active)
VALUES
(
    'ffffffff-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000001',
    'eeeeeeee-0000-0000-0000-000000000001',
    NULL,
    'cccccccc-0000-0000-0000-000000000002',
    ARRAY[1,2,3,4,5],
    '17:00',
    '20:00',
    TRUE
)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- DB-030: Konfiguracja Twilio per tenant (V051)
-- UWAGA: Wartości account_sid i auth_token to PLACEHOLDERY symulujące format
--        Base64(IV||ciphertext) po szyfrowaniu AES-256-GCM przez aplikację.
--        W środowisku DEV aplikacja i tak odszyfrowuje je przez JPA
--        AttributeConverter – tu wstawiamy wartości tekstowe omijając szyfrowanie,
--        ponieważ seed działa jako superuser poza kontekstem aplikacji.
-- ---------------------------------------------------------------------------

INSERT INTO tenant_twilio_config (
    config_id,
    tenant_id,
    account_sid,
    auth_token,
    api_key_sid,
    api_key_secret,
    twiml_app_sid,
    phone_number,
    status_callback_url,
    is_active
) VALUES
(
    '11111111-0000-0000-0000-000000000001',
    'aaaaaaaa-0000-0000-0000-000000000001',   -- Acme Corporation
    'PLACEHOLDER_ENCRYPTED_ACMExxACCOUNT_SID',  -- Base64(IV||ciphertext) – zastąp prawdziwą wartością
    'PLACEHOLDER_ENCRYPTED_ACMExxxxxxxxAUTH_TOKEN',  -- Base64(IV||ciphertext) – zastąp prawdziwą wartością
    NULL,          -- brak API Key – tenant używa globalnych kredencjałów
    NULL,          -- brak API Key Secret
    'APxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',   -- placeholder TwiML App SID
    '+48221234001',
    'https://acme.contact-center.dev/twilio/status',
    TRUE
),
(
    '11111111-0000-0000-0000-000000000002',
    'aaaaaaaa-0000-0000-0000-000000000002',   -- Beta Telecom
    'PLACEHOLDER_ENCRYPTED_BETAxxACCOUNT_SID',  -- Base64(IV||ciphertext) – zastąp prawdziwą wartością
    'PLACEHOLDER_ENCRYPTED_BETAxxxxxxxxAUTH_TOKEN',  -- Base64(IV||ciphertext) – zastąp prawdziwą wartością
    NULL,
    NULL,
    NULL,
    '+48221234002',
    'https://beta.contact-center.dev/twilio/status',
    TRUE
)
ON CONFLICT DO NOTHING;
