# TASKS-DATABASE.md
# Contact Center SaaS – Zadania deweloperskie: Baza danych (PostgreSQL + Data Warehouse)

**Wersja:** 1.0
**Data:** 2026-03-12
**Stack:** PostgreSQL 16 (operacyjna), ClickHouse (data warehouse), Redis (cache/sesje), Flyway (migracje), Debezium (CDC)
**Powiązany PRD:** PRD v1.0

---

## Konwencje

- Prefiks ID: `DB-`
- Priorytety: **Must Have** (MVP), **Should Have** (kolejna iteracja)
- Rozmiary: S (< 1 dzien), M (1-2 dni), L (3-5 dni), XL (> 5 dni)
- Każde zadanie DB to osobna migracja Flyway (V{numer}__nazwa.sql) – bez konfliktów przy pracy równoległej
- Numery wersji migracji rezerwowane z góry (np. V001–V005 dla zakresu DB-001)
- Wszystkie tabele zawierają kolumnę `tenant_id UUID NOT NULL` + indeks (tenant_id, primary_key)
- Soft delete: kolumna `is_deleted BOOLEAN DEFAULT FALSE` (nie fizyczne usunięcie)
- Timestamps: `created_at TIMESTAMPTZ DEFAULT NOW()`, `updated_at TIMESTAMPTZ`

---

## MODUL: Fundament i infrastruktura bazy

### DB-001 – Inicjalizacja schematu bazowego i konfiguracja Flyway

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** brak
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-002, DB-003, DB-005, DB-009, DB-010, DB-012
**Odniesienie PRD:** przekrojowe

**Opis:**
Konfiguracja Flyway w projekcie Spring Boot (flyway-core). Plik migracji `V001__init_extensions.sql`: włączenie rozszerzeń PostgreSQL: `uuid-ossp` (generowanie UUID), `pg_trgm` (fuzzy search), `pgcrypto` (szyfrowanie). Stworzenie domyślnego schematu `public`, ustawienie `search_path`. Konfiguracja parametrów połączenia per środowisko (dev/prod) przez Spring profiles.

**Kryteria akceptacji:**
- [x] Flyway uruchamia się przy starcie aplikacji i raportuje "Successfully applied N migrations"
- [x] Rozszerzenia uuid-ossp, pg_trgm, pgcrypto dostępne w bazie (SELECT * FROM pg_extension)
- [x] Plik `flyway_schema_history` tworzony automatycznie
- [x] Skrypt idempotentny: powtórne uruchomienie nie generuje błędów

---

### DB-002 – Tabela TENANT: schemat, indeksy, constraints

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-003, DB-005, DB-006, DB-009, DB-010, DB-011, DB-012, DB-015
**Odniesienie PRD:** US-01-01, US-01-02, US-01-03, EPIC-01

**Opis:**
Migracja `V002__create_tenant.sql`. Definicja tabeli TENANT zgodna z modelem danych PRD. Pole `config` jako JSONB z domyślnymi limitami: `{"max_agents": 100, "max_queues": 50, "max_campaigns": 20}`. Pole `status` jako typ ENUM (`ACTIVE`, `INACTIVE`, `SUSPENDED`). Indeks unikalny na `name` (case-insensitive: `LOWER(name)`).

**Kryteria akceptacji:**
- [x] DDL: `tenant_id UUID PRIMARY KEY DEFAULT uuid_generate_v4()`
- [x] `status` jako PostgreSQL ENUM lub CHECK constraint z dozwolonymi wartościami
- [x] Indeks unikalny na `LOWER(name)` zapobiega duplikatom nazw różniących się wielkością liter
- [x] `config JSONB NOT NULL DEFAULT '{"max_agents":100}'`
- [x] Komentarze kolumn (`COMMENT ON COLUMN`) dla dokumentacji schematu

---

### DB-003 – Tabela USER: schemat, role, ENUM statusów, indeksy

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-004, DB-006, DB-013, DB-014, DB-015
**Odniesienie PRD:** US-02-01, US-02-02, US-02-03, EPIC-02

**Opis:**
Migracja `V003__create_user.sql`. Tabela USER z FK do TENANT. Pole `role` jako ENUM (`ADMIN`, `SUPERVISOR`, `AGENT`). Pole `skills` jako JSONB (`string[]`). Pole `status` jako ENUM (`ACTIVE`, `INACTIVE`, `BREAK`, `AVAILABLE`, `BUSY`, `AFTER_CONTACT`). Pole `password_reset_required BOOLEAN DEFAULT FALSE`. Pole `mfa_secret VARCHAR(32)`. Tabela `REFRESH_TOKEN` (token, user_id FK, expires_at, is_revoked).

**Kryteria akceptacji:**
- [x] FK: `tenant_id REFERENCES tenant(tenant_id) ON DELETE RESTRICT`
- [x] Indeks na `(tenant_id, email)` – UNIQUE (jeden email per tenant)
- [x] Indeks na `(tenant_id, status)` – dla zapytań routingu (agenci dostępni)
- [x] Tabela REFRESH_TOKEN z indeksem na `token` i TTL-based cleanup (cron lub pg_cron)
- [x] `password_hash VARCHAR(60) NOT NULL` (bcrypt output length)

---

### DB-004 – Tabela AUDIT_LOG: schemat, partycjonowanie po dacie

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-002, DB-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-018
**Odniesienie PRD:** RODO, przekrojowe

**Opis:**
Migracja `V004__create_audit_log.sql`. Tabela AUDIT_LOG partycjonowana po `created_at` (RANGE partitioning, miesiąc). Automatyczne tworzenie partycji przez `pg_partman` lub dedykowaną funkcję. Kolumny `old_value` i `new_value` jako JSONB (NULL jeśli nie dotyczy). Indeks na `(tenant_id, entity_type, created_at DESC)`.

**Kryteria akceptacji:**
- [x] Tabela partycjonowana (`PARTITION BY RANGE (created_at)`)
- [x] Partycja tworzona automatycznie na następny miesiąc (skrypt lub pg_partman)
- [x] Indeks GIN na `old_value` i `new_value` dla zapytań JSONB
- [x] Zapisy do AUDIT_LOG przez dedykowaną rolę DB z ograniczonymi uprawnieniami (INSERT only)
- [x] Polityka retencji: partycje starsze niż 2 lata usuwane przez cron job

---

## MODUL: Encje domenowe (Core Entities)

### DB-005 – Kompletny schemat TENANT z limitami i konfiguracją

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak (BE-006 i dalej)
**Odniesienie PRD:** US-01-03, EPIC-01

**Opis:**
Migracja `V005__tenant_config_schema.sql`. Dodanie JSON Schema validation dla kolumny `config` przez CHECK constraint lub trigger. Funkcja PostgreSQL `get_tenant_limit(tenant_id UUID, limit_name TEXT) RETURNS INT` – wygodny dostęp do limitów z aplikacji. Widok `v_tenant_stats` agregujący: liczbę agentów, kolejek, kampanii per tenant (dla dashboardu admina).

**Kryteria akceptacji:**
- [x] CHECK constraint lub trigger odrzuca `config` bez wymaganych pól (max_agents, max_queues)
- [x] Funkcja `get_tenant_limit` działa dla dowolnego klucza z config JSONB
- [x] Widok `v_tenant_stats` zwraca dane bez full scan (korzysta z indeksów tabel docelowych)

---

### DB-006 – Tabela CONTACT i RECORDING: schemat, indeksy, partycjonowanie

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-002, DB-003, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-007, DB-008, DB-013, DB-014, DB-015, DB-017
**Odniesienie PRD:** US-03-05, US-09-02, EPIC-03, EPIC-09

**Opis:**
Migracja `V006__create_contact.sql`. Tabela CONTACT partycjonowana po `started_at` (RANGE, miesiąc). ENUMy: `channel` (PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP), `direction` (INBOUND, OUTBOUND), `status` (QUEUED, ACTIVE, ON_HOLD, COMPLETED, ABANDONED). FK do CUSTOMER (nullable, dla nieznanych), AGENT (nullable), CAMPAIGN (nullable). Kolumna `recording_url TEXT` (S3 URL lub NULL).

**Kryteria akceptacji:**
- [x] Tabela partycjonowana po `started_at` (RANGE monthly)
- [x] Indeks na `(tenant_id, customer_id, started_at DESC)` – dla historii klienta
- [x] Indeks na `(tenant_id, agent_id, started_at DESC)` – dla raportów agentów
- [x] Indeks na `(tenant_id, status)` – dla aktywnych kontaktów w RT
- [x] FK na customer_id i agent_id z `ON DELETE SET NULL` (anonimizacja RODO)

---

### DB-007 – Tabele EMAIL i EMAIL_TEMPLATE: schemat

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-017
**Odniesienie PRD:** US-05-01, US-05-02, US-05-03, EPIC-05

**Opis:**
Migracja `V007__create_email.sql`. Tabela `EMAIL_MESSAGE` (message_id, tenant_id, contact_id FK, direction, from_address, to_address, subject, body_html TEXT, body_text TEXT, message_id_header VARCHAR (RFC header), in_reply_to VARCHAR, received_at TIMESTAMPTZ, sent_at TIMESTAMPTZ). Tabela `EMAIL_TEMPLATE` (template_id, tenant_id, name, subject_template, body_html, variables JSONB, created_by FK users). Indeks na `(tenant_id, message_id_header)` – UNIQUE dla deduplikacji.

**Kryteria akceptacji:**
- [x] UNIQUE na `(tenant_id, message_id_header)` zapobiega duplikatom przy wielokrotnym pobraniu IMAP
- [x] Indeks na `(contact_id, received_at DESC)` – dla wątku wiadomości
- [x] `body_html` i `body_text` bez limitu rozmiaru (TEXT, nie VARCHAR)
- [x] FK: `contact_id REFERENCES contact(contact_id) ON DELETE CASCADE`

---

### DB-008 – Tabela SOCIAL_INTEGRATION i SOCIAL_MESSAGE: schemat

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-017
**Odniesienie PRD:** US-06-01, US-06-02, EPIC-06

**Opis:**
Migracja `V008__create_social.sql`. Tabela `SOCIAL_INTEGRATION` (integration_id, tenant_id, platform ENUM(FACEBOOK/INSTAGRAM/WHATSAPP), page_id, access_token_encrypted BYTEA, token_expires_at TIMESTAMPTZ, webhook_status, created_at). Tabela `SOCIAL_MESSAGE` (message_id, tenant_id, contact_id FK, platform, external_message_id, direction, content TEXT, sender_external_id, sent_at TIMESTAMPTZ, attachments JSONB).

**Kryteria akceptacji:**
- [x] `access_token_encrypted` jako BYTEA (zaszyfrowany AES-256 przez pgcrypto lub aplikację)
- [x] UNIQUE na `(tenant_id, platform, page_id)` – jedna konfiguracja per strona per tenant
- [x] UNIQUE na `(tenant_id, external_message_id)` – idempotentny webhook
- [x] Indeks na `(contact_id, sent_at DESC)` – dla historii konwersacji

---

### DB-009 – Tabela IVR_TREE: schemat, wersjonowanie

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** BE-013, BE-014
**Odniesienie PRD:** US-04-01, EPIC-04

**Opis:**
Migracja `V009__create_ivr_tree.sql`. Tabela `IVR_TREE` (ivr_id, tenant_id, name, definition JSONB NOT NULL, version INT DEFAULT 1, is_active BOOLEAN DEFAULT FALSE, created_by FK users, created_at, updated_at). Constraint: maksymalnie jeden aktywny IVR per tenant per "punkt wejścia" (możliwe przez partial unique index lub trigger). Tabela `IVR_AUDIO` (audio_id, tenant_id, ivr_id FK, filename, s3_url, duration_seconds, created_at).

**Kryteria akceptacji:**
- [x] `definition JSONB` walidowane przez CHECK czy zawiera klucz "nodes" (tablica)
- [x] Indeks GIN na `definition` dla zapytań JSONB (wyszukiwanie w definicji)
- [x] `version` inkrementowany przez trigger przy każdym UPDATE `definition`
- [x] Tabela `IVR_AUDIO` z UNIQUE na `(tenant_id, filename)` – bez duplikatów nazw

---

### DB-010 – Tabela QUEUE: schemat, routing strategy, skills

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-018
**Odniesienie PRD:** US-07-01, US-07-02, US-07-03, EPIC-07

**Opis:**
Migracja `V010__create_queue.sql`. Tabela `QUEUE` (queue_id, tenant_id, name, routing_strategy ENUM(ROUND_ROBIN/FIRST_AVAILABLE/SKILL_BASED), required_skills JSONB DEFAULT '[]', sticky_agent_timeout_seconds INT DEFAULT 60, is_active BOOLEAN DEFAULT TRUE, created_at). Tabela `QUEUE_AGENT` (queue_id FK, agent_id FK, assigned_at) – wielo-do-wielu (agent może być w kilku kolejkach). Indeks na `(tenant_id, routing_strategy)`.

**Kryteria akceptacji:**
- [x] CHECK: `sticky_agent_timeout_seconds >= 0`
- [x] UNIQUE na `(tenant_id, name)` – unikalność nazwy kolejki w tenantcie
- [x] Indeks GIN na `required_skills` – szybkie zapytania skill-matching
- [x] Widok `v_queue_available_agents(queue_id)` – agenci dostępni dla danej kolejki z odpowiednimi skills

---

### DB-011 – Tabele CAMPAIGN i CAMPAIGN_CONTACT: schemat, statusy, indeksy

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-002, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-013, DB-014
**Odniesienie PRD:** US-08-01, US-08-02, US-08-03, US-08-04, US-08-05, EPIC-08

**Opis:**
Migracja `V011__create_campaign.sql`. Tabela `CAMPAIGN` (campaign_id, tenant_id, name, type ENUM(OUTBOUND_VOICE/OUTBOUND_EMAIL), dialer_type ENUM(PROGRESSIVE/PREDICTIVE/MANUAL), schedule JSONB, status ENUM(DRAFT/SCHEDULED/RUNNING/PAUSED/STOPPED/COMPLETED), contact_list_id UUID, created_by FK users, created_at). Tabela `CAMPAIGN_CONTACT` (record_id, campaign_id FK, customer_id FK nullable, phone VARCHAR, first_name, last_name, custom_fields JSONB, status ENUM(PENDING/DIALING/CONNECTED/NO_ANSWER/FAILED/COMPLETED), attempt_count INT DEFAULT 0, last_attempt_at TIMESTAMPTZ, next_attempt_at TIMESTAMPTZ, disposition_code VARCHAR). Tabela `SCHEDULED_CALLBACK` (callback_id, campaign_id FK, customer_id FK, phone VARCHAR, scheduled_at TIMESTAMPTZ, created_at).

**Kryteria akceptacji:**
- [x] Indeks na `(campaign_id, status, next_attempt_at)` – dla dialera (pobierz następne do dzwonienia)
- [x] Indeks na `(tenant_id, status)` na tabeli CAMPAIGN – dla filtrowania listy kampanii
- [x] CHECK na CAMPAIGN: `status` przestrzega dozwolonych przejść (możliwe przez trigger)
- [x] Partycjonowanie CAMPAIGN_CONTACT po `campaign_id` (list partitioning) dla izolacji dużych kampanii
- [x] Indeks na `(scheduled_at)` w SCHEDULED_CALLBACK – dla pobierania zaplanowanych callbacków

---

### DB-012 – Tabela CUSTOMER: schemat, fuzzy search, RODO

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-006, DB-011, DB-015, DB-017
**Odniesienie PRD:** US-09-01, US-09-02, US-09-03, US-09-06, EPIC-09

**Opis:**
Migracja `V012__create_customer.sql`. Tabela `CUSTOMER` (customer_id, tenant_id, first_name VARCHAR(100), last_name VARCHAR(100), phone JSONB (string[]), email JSONB (string[]), custom_fields JSONB, gdpr_consent JSONB, source VARCHAR(50) DEFAULT 'MANUAL', is_deleted BOOLEAN DEFAULT FALSE, created_at, updated_at). Indeksy trigram dla fuzzy search na first_name i last_name. Indeks GIN na phone i email dla wyszukiwania w tablicach JSONB.

**Kryteria akceptacji:**
- [x] Indeks trigram: `CREATE INDEX idx_customer_name_trgm ON customer USING GIN ((first_name || ' ' || last_name) gin_trgm_ops)`
- [x] Indeks GIN na `phone jsonb_path_ops` i `email jsonb_path_ops` dla operatora `@>`
- [x] PARTIAL indeks na `(tenant_id, phone)` WHERE `is_deleted = FALSE`
- [x] Funkcja `search_customers(tenant_id UUID, query TEXT, limit INT) RETURNS SETOF customer` używająca `%` operator trigram
- [x] Pole `gdpr_consent JSONB` z przykładową strukturą: `{"consent_given": true, "consent_date": "...", "consent_source": "..."}`

---

## MODUL: Bezpieczeństwo i izolacja

### DB-013 – Indeksy wydajnościowe i widoki raportowe

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** DB-006, DB-003, DB-011, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** DB-018
**Odniesienie PRD:** US-10-02, US-10-03, EPIC-10, wymagania wydajnoscowe (< 200ms)

**Opis:**
Migracja `V013__performance_indexes.sql`. Dodatkowe indeksy kompozytowe na tabelach CONTACT dla zapytań raportowych. Widoki materializowane (MATERIALIZED VIEW) dla raportów historycznych: `mv_agent_daily_stats` (count kontaktów, avg handle time per agent per dzień), `mv_campaign_stats` (dials, connected, conversion per kampania). Polityka odświeżania widoków materializowanych (REFRESH MATERIALIZED VIEW CONCURRENTLY – codziennie o 01:00).

**Kryteria akceptacji:**
- [x] `EXPLAIN ANALYZE` na zapytaniu raportowym agentów (30 dni) zwraca plan z Index Scan (nie Seq Scan)
- [x] `mv_agent_daily_stats` odświeżany przez pg_cron lub aplikację co dobę
- [x] REFRESH CONCURRENTLY: stare dane dostępne podczas odświeżania (brak blokady)
- [x] Indeks na `(tenant_id, channel, started_at::DATE)` na tabeli CONTACT
- [x] Indeks na `(tenant_id, disposition_code, started_at)` na tabeli CONTACT

---

### DB-014 – Schemat Data Warehouse: ClickHouse tabele docelowe

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** DB-006, DB-011, DB-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak
**Odniesienie PRD:** US-10-06, EPIC-10

**Opis:**
Definicja tabel w ClickHouse (DDL skrypty, nie Flyway – osobny katalog `dw/`): `contacts_dw` (ReplacingMergeTree, partycja po toYYYYMM(started_at), ORDER BY (tenant_id, contact_id)), `agent_performance_dw` (AggregatingMergeTree per dzień), `campaigns_dw`. Widoki agregujące dla raportów: `v_agent_kpi_daily`, `v_campaign_conversion`. Materialized views w ClickHouse dla pre-agregacji.

**Kryteria akceptacji:**
- [x] Tabela `contacts_dw` z ENGINE = ReplacingMergeTree(updated_at) dla idempotentnego upsert
- [x] Partycjonowanie miesięczne (toYYYYMM) dla efektywnego pruning zapytań
- [x] Widok `v_agent_kpi_daily` zwraca wyniki dla 1 agenta za 30 dni w < 500ms
- [x] Schemat ClickHouse wersjonowany w katalogu `dw/migrations/`
- [x] Wszystkie pola PII klientów WYKLUCZONE ze schematu DW (zgodność RODO)

---

### DB-015 – Row Level Security (RLS): polityki izolacji tenant_id

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** DB-002, DB-003, DB-006, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak
**Odniesienie PRD:** wymagania bezpieczenstwa (izolacja logiczna tenant_id)

**Opis:**
Migracja `V015__row_level_security.sql`. Włączenie RLS na kluczowych tabelach: CUSTOMER, CONTACT, CAMPAIGN, QUEUE. Polityka: `USING (tenant_id = current_setting('app.current_tenant_id')::UUID)`. Ustawienie `app.current_tenant_id` przez aplikację przy każdym połączeniu DB (przez `SET LOCAL`). Rola DB `app_user` bez uprawnień superuser. Rola `admin_user` z BYPASSRLS dla operacji administracyjnych.

**Kryteria akceptacji:**
- [x] Test: połączenie z `SET LOCAL app.current_tenant_id = 'tenant-A'` nie widzi rekordów tenant-B
- [x] RLS nie spowalnia zapytań > 10% (EXPLAIN ANALYZE przed i po)
- [x] Rola `app_user` ma tylko SELECT/INSERT/UPDATE/DELETE (nie ALTER/DROP)
- [x] RLS wyłączone dla roli `admin_user` (BYPASSRLS) – dla operacji migracyjnych

---

### DB-016 – Konfiguracja Redis: struktury danych i polityki TTL

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** brak (niezalezne od PostgreSQL)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak
**Odniesienie PRD:** wymagania wydajnoscowe, bezpieczenstwo (JWT blacklista)

**Opis:**
Dokumentacja i konfiguracja struktur Redis używanych przez backend. Klucze i TTL: `jwt:blacklist:{token_hash}` (TTL = pozostały czas ważności tokenu), `session:agent:{user_id}` (status agenta, TTL 8h), `cache:customer:phone:{phone}` (CLI lookup, TTL 5 min), `cache:queue:stats:{queue_id}` (TTL 5s), `cache:tenant:metrics` (TTL 30s), `rate:login:{ip}` (TTL 15 min, counter). Konfiguracja Redis maxmemory-policy (`allkeys-lru`) i persistence (AOF dla JWT blacklisty).

**Kryteria akceptacji:**
- [x] Konfiguracja Redis (`redis.conf`) zawiera: maxmemory, maxmemory-policy allkeys-lru
- [x] AOF persistence włączone dla namespace `jwt:blacklist` (lub osobna instancja Redis)
- [x] Dokumentacja kluczy Redis jako komentarze w `RedisKeyConstants.java`
- [x] Test: po logout token trafia do blacklisty i kolejne żądanie z tym tokenem → HTTP 401

---

### DB-017 – Procedury RODO: funkcje anonimizacji i eksportu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-012, DB-006, DB-007, DB-008
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak
**Odniesienie PRD:** US-09-06, wymagania RODO (Art. 15, Art. 17)

**Opis:**
Migracja `V017__gdpr_functions.sql`. Funkcja PostgreSQL `anonymize_customer(p_customer_id UUID, p_tenant_id UUID)`: UPDATE CUSTOMER (first_name='ANONYMIZED', last_name='ANONYMIZED', phone='[]', email='[]', custom_fields='{}', is_deleted=TRUE), wywołanie logowania do AUDIT_LOG. Funkcja `export_customer_data(p_customer_id UUID, p_tenant_id UUID) RETURNS JSONB`: SELECT z JOIN na CONTACT, EMAIL_MESSAGE (ograniczony), SOCIAL_MESSAGE (ograniczony) – przygotowanie payloadu do ZIP.

**Kryteria akceptacji:**
- [x] `anonymize_customer` działa transakcyjnie (COMMIT lub ROLLBACK całości)
- [x] Anonimizacja nie usuwa rekordów CONTACT (zachowanie historii bezpiecznej – bez PII)
- [x] `export_customer_data` zwraca JSONB z kluczami: customer, contacts, email_messages, social_messages
- [x] Obie funkcje sprawdzają tenant_id (nie mogą działać cross-tenant)
- [x] Test: po anonimizacji GET /api/customers/{id} zwraca dane z 'ANONYMIZED' zamiast imienia

---

## MODUL: Narzędzia operacyjne

### DB-018 – Konfiguracja pg_cron: zadania scheduled

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-004, DB-010, DB-013
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** brak
**Odniesienie PRD:** US-03-05 (retencja nagran), RODO (retencja danych)

**Opis:**
Migracja `V018__pg_cron_jobs.sql`. Konfiguracja zadań pg_cron (lub tabela z definicjami dla zewnętrznego crona): (1) Codziennie 02:00 – usunięcie wygasłych REFRESH_TOKEN, (2) Codziennie 02:30 – rotacja AUDIT_LOG partycji (drop starych, create nowych), (3) Co godzinę – REFRESH MATERIALIZED VIEW CONCURRENTLY mv_agent_daily_stats, (4) Co 5 minut – archiwizacja CAMPAIGN_CONTACT dla zakończonych kampanii (status=COMPLETED → tabela archiwalna).

**Kryteria akceptacji:**
- [x] Wszystkie zaplanowane zadania zdefiniowane w jednym miejscu (tabela lub plik konfiguracyjny)
- [x] Zadania logują wynik (sukces/błąd) do tabeli `cron_log` lub AUDIT_LOG
- [x] Cron retencji REFRESH_TOKEN usuwa tylko tokeny z is_revoked=TRUE lub expires_at < NOW()
- [x] Cron nie uruchamia się gdy poprzednie wykonanie jest jeszcze w toku (idempotency)

---

### DB-019 – Seed danych testowych i migracje dla środowiska dev

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-001 do DB-017
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-14
**Blokuje:** brak
**Odniesienie PRD:** przekrojowe (wspomaga development)

**Opis:**
Migracja `V999__dev_seed.sql` (uruchamiana tylko w profilu `dev` przez Flyway locations). Wstawienie: 2 tenantów testowych, 1 ADMIN, 2 SUPERVISOR, 5 AGENT (różne skills), 3 QUEUE, 1 aktywna kampania z 100 CAMPAIGN_CONTACT, 50 CUSTOMER, 200 CONTACT (mix kanałów i statusów), 2 IVR_TREE (przykładowe drzewa). Hasła: `Test@12345` (bcrypt hash).

**Kryteria akceptacji:**
- [x] Seed uruchamiany tylko w profilu `spring.profiles.active=dev` (nie na prod)
- [x] Seed idempotentny: INSERT OR IGNORE / ON CONFLICT DO NOTHING
- [x] Dane seed pokrywają wszystkie kanały (PHONE, EMAIL, SOCIAL) i statusy kontaktów
- [x] Seed nie zawiera prawdziwych danych osobowych (tylko fikcyjne imiona/numery)

---

### DB-020 – Kolumna email_address w tabeli QUEUE: routing emaili

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-010
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-26
**Blokuje:** brak
**Odniesienie PRD:** US-05-01, EPIC-05

**Opis:**
Migracja `V029__add_email_address_to_queue.sql`. Dodanie kolumny `email_address VARCHAR(255) NULL` do tabeli `QUEUE`. Umożliwia przypisanie adresu email do kolejki, tak aby przychodzące wiadomości email na ten adres były automatycznie routowane do tej kolejki. UNIQUE constraint na `(tenant_id, email_address)` (NULL nie narusza UNIQUE w PostgreSQL). CHECK constraint: `email_address IS NULL OR email_address LIKE '%@%'`. Partial index `idx_queue_email_address` na `(tenant_id, email_address) WHERE email_address IS NOT NULL` dla szybkiego lookup w `EmailPollingService`.

**Kryteria akceptacji:**
- [x] Kolumna `email_address` nullable – zachowanie backward-compatible (istniejące kolejki bez zmian)
- [x] UNIQUE constraint na `(tenant_id, email_address)` zapobiega duplikatom adresów w tenantcie
- [x] CHECK constraint weryfikuje obecność `@` w adresie (podstawowa walidacja formatu)
- [x] Partial index (WHERE IS NOT NULL) zoptymalizowany dla lookup w EmailRoutingService
- [x] EmailRoutingService używa `queueRepository.findByEmailAddressAndTenantId()` do routingu przed regułami

---

---

## MODUL: Routing numerów telefonicznych (EPIC-11)

### DB-021 – Tabele PHONE_NUMBER i PHONE_ROUTING_RULE: numery tenanta i harmonogram IVR

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-002 (TENANT), DB-009 (IVR_TREE), DB-010 (QUEUE), DB-015 (RLS)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Blokuje:** BE-033, BE-034
**Odniesienie PRD:** EPIC-11

**Opis:**
Dwie nowe tabele umożliwiające przypisanie wielu numerów telefonicznych do tenanta oraz konfigurację reguł routingu: który IVR (lub kolejka) ma obsługiwać połączenie przychodzące na dany numer w zależności od dnia tygodnia i przedziału czasowego. Kolizje reguł wykrywa trigger PostgreSQL. Gdy żadna reguła nie pasuje – połączenie jest odrzucane (hangup).

**Schemat tabeli `phone_number`:**
```sql
CREATE TABLE phone_number (
    phone_number_id  UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID        NOT NULL REFERENCES tenant(tenant_id),
    number           VARCHAR(20) NOT NULL,           -- E.164, np. +48123456789
    display_name     VARCHAR(100),                   -- opcjonalna etykieta, np. "Sprzedaż"
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    is_deleted       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT uq_phone_number_tenant UNIQUE (tenant_id, number),
    CONSTRAINT chk_phone_number_e164  CHECK (number ~ '^\+[1-9][0-9]{6,14}$')
);
CREATE INDEX idx_phone_number_tenant ON phone_number (tenant_id) WHERE NOT is_deleted;
ALTER TABLE phone_number ENABLE ROW LEVEL SECURITY;
CREATE POLICY phone_number_tenant_isolation ON phone_number
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
```

**Schemat tabeli `phone_routing_rule`:**
```sql
CREATE TABLE phone_routing_rule (
    rule_id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID        NOT NULL REFERENCES tenant(tenant_id),
    phone_number_id  UUID        NOT NULL REFERENCES phone_number(phone_number_id),
    ivr_tree_id      UUID        REFERENCES ivr_tree(ivr_tree_id),   -- NULL gdy target=queue
    queue_id         UUID        REFERENCES queue(queue_id),          -- NULL gdy target=IVR
    days_of_week     INTEGER[]   NOT NULL,  -- ISO: 1=Pon ... 7=Nie; min 1 element
    time_start       TIME        NOT NULL,
    time_end         TIME        NOT NULL,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ,
    CONSTRAINT chk_routing_rule_time   CHECK (time_start < time_end),
    CONSTRAINT chk_routing_rule_target CHECK (
        (ivr_tree_id IS NOT NULL AND queue_id IS NULL) OR
        (ivr_tree_id IS NULL     AND queue_id IS NOT NULL)
    ),
    CONSTRAINT chk_routing_rule_days   CHECK (array_length(days_of_week, 1) >= 1)
);
CREATE INDEX idx_routing_rule_phone  ON phone_routing_rule (phone_number_id) WHERE is_active;
CREATE INDEX idx_routing_rule_tenant ON phone_routing_rule (tenant_id)       WHERE is_active;
ALTER TABLE phone_routing_rule ENABLE ROW LEVEL SECURITY;
CREATE POLICY phone_routing_rule_tenant_isolation ON phone_routing_rule
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
```

**Trigger wykrywania kolizji:**
```sql
CREATE OR REPLACE FUNCTION check_routing_rule_collision() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM phone_routing_rule
        WHERE phone_number_id = NEW.phone_number_id
          AND rule_id        != COALESCE(NEW.rule_id, '00000000-0000-0000-0000-000000000000'::UUID)
          AND is_active       = TRUE
          AND days_of_week   && NEW.days_of_week
          AND time_start      < NEW.time_end
          AND time_end        > NEW.time_start
    ) THEN
        RAISE EXCEPTION 'routing_rule_collision'
            USING DETAIL = 'Regula naklada sie na istniejaca regule dla tego numeru.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_routing_rule_collision
    AFTER INSERT OR UPDATE ON phone_routing_rule
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION check_routing_rule_collision();
```

**Seed dev (V999):** Dwa przykładowe numery dla tenant dev; reguły: pon-pt 8:00-17:00 → IVR „Powitanie", pon-pt 17:00-20:00 → kolejka „After Hours". Sob-nie brak reguł (odrzucenie).

**Kryteria akceptacji:**
- [x] `phone_number`: UNIQUE(tenant_id, number), CHECK E.164, RLS, soft delete
- [x] `phone_routing_rule`: CHECK time_start < time_end, CHECK dokładnie jeden target (IVR xor kolejka), CHECK min 1 dzień
- [x] Trigger `trg_routing_rule_collision` blokuje INSERT/UPDATE gdy nakładają się przedziały czasu dla tego samego dnia i numeru
- [x] RLS na obu tabelach izoluje dane między tenantami
- [x] Migracja idempotentna (IF NOT EXISTS / DO blocks)

---

---

## MODUL: Prezentacja Kontaktów (EPIC-12)

### DB-022 – Indeksy wyszukiwania kontaktów dla Raportów > Kontakty

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-006 ✅ (tabela CONTACT)
**Status:** ✅ Zrealizowane
**Blokuje:** BE-036 ✅
**Odniesienie PRD:** EPIC-12

**Opis:**
Tabela `contact` ma indeksy raportowe z DB-013, jednak filtrowanie w widoku „Raporty > Kontakty" (BE-036) wymaga dodatkowego indeksu pokrywającego kolumny `queue_id`, `campaign_id` oraz `duration_seconds`. Istniejące indeksy `idx_contact_agent_history` i `idx_contact_customer_history` obsługują lookup po agencie i kliencie. Brakuje:
- indeksu do filtrowania po kolejce (raport kontaktów: filtr `queue_id`)
- indeksu do filtrowania po kampanii z uwzględnieniem zakresu dat (inny niż `idx_contact_campaign` — tu potrzebna kolejność `tenant_id, campaign_id, started_at` z kolumną `status` jako include)
- indeksu na `duration_seconds` (filtr min/max — rzadko używany, tylko jeśli selectivity wysoka; dodać jako indeks warunkowy tylko dla COMPLETED)

Migracja: `V035__contact_search_indexes.sql`

**Kryteria akceptacji:**
- [x] `idx_contact_queue_date` na `(tenant_id, queue_id, started_at)` WHERE queue_id IS NOT NULL — V035
- [x] `idx_contact_duration` na `(tenant_id, duration_seconds)` WHERE duration_seconds IS NOT NULL — warunkowy indeks dla filtrów min/max czasu trwania
- [x] Skrypt idempotentny: `CREATE INDEX IF NOT EXISTS`
- [x] Odblokowano BE-036 (filtry zaawansowane Contact API)

---

---

## Zależności między zadaniami

### Kolejność obowiązkowa (blokery)

```
DB-001 (extensions) → DB-002 (TENANT) → DB-003 (USER) → DB-004 (AUDIT_LOG)
DB-002 → DB-005 (TENANT config)
DB-003 + DB-012 → DB-006 (CONTACT)
DB-006 → DB-007 (EMAIL)
DB-006 → DB-008 (SOCIAL)
DB-002 → DB-009 (IVR_TREE)
DB-002 → DB-010 (QUEUE)
DB-010 → DB-020 (email_address w QUEUE)
DB-002 + DB-012 → DB-011 (CAMPAIGN)
DB-002 → DB-012 (CUSTOMER)
DB-006 + DB-011 + DB-003 → DB-013 (indeksy raportowe)
DB-006 + DB-011 + DB-003 → DB-014 (ClickHouse DW)
DB-002 + DB-003 + DB-006 + DB-012 → DB-015 (RLS)
DB-004 + DB-010 + DB-013 → DB-018 (pg_cron)
DB-012 + DB-006 + DB-007 + DB-008 → DB-017 (RODO funkcje)
Wszystkie → DB-019 (seed dev)
```

### Zadania niezależne (mozliwe równolegle po DB-001)

| Ścieżka | Zadania |
|---------|---------|
| Core entities | DB-002 → DB-003 → DB-004 |
| Customer | DB-012 (po DB-002) |
| IVR | DB-009 (po DB-002) |
| Queue | DB-010 (po DB-002) |
| Redis | DB-016 (calkowicie niezależne) |

### Blokery DB dla Backendu (które DB zadania muszą byc gotowe przed startem BE)

| Zadanie BE | Wymaga DB |
|------------|-----------|
| BE-001 (Spring Boot init) | DB-001 (Flyway extensions) |
| BE-002 (TenantContext) | DB-002 (tabela TENANT) |
| BE-003 (Spring Security) | DB-003 (tabela USER, REFRESH_TOKEN) |
| BE-005 (Audit Log) | DB-004 (tabela AUDIT_LOG) |
| BE-006 (Tenant API) | DB-005 (TENANT config) |
| BE-009, BE-010, BE-027 | DB-006 (tabela CONTACT) |
| BE-015, BE-016 | DB-007 (tabela EMAIL) |
| BE-017, BE-018 | DB-008 (SOCIAL_INTEGRATION) |
| BE-013, BE-014 | DB-009 (tabela IVR_TREE) |
| BE-019, BE-020 | DB-010 (tabela QUEUE) |
| BE-015 (email routing po adresie) | DB-020 (email_address w QUEUE) |
| BE-022, BE-023, BE-024 | DB-011 (tabela CAMPAIGN) |
| BE-025, BE-026, BE-031 | DB-012 (tabela CUSTOMER) |
| BE-028, BE-030 | DB-013 + DB-014 |
| BE-003, BE-004 | DB-016 (Redis config) |
| BE-031 (RODO API) | DB-017 (RODO funkcje) |

| BE-038 (Callback Executor) | DB-023 (rozszerzenie scheduled_callback) |
| BE-039 (Reschedule API) | DB-023 |
| BE-040 (Inbound Callback API) | DB-023 |

---

## MODUL: Zaplanowane oddzwonienia (EPIC-13)

### DB-023 – Rozszerzenie tabeli `scheduled_callback` o kontekst źródłowy

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-006 (tabela CONTACT), V032 (scheduled_callback już istnieje)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** BE-038, BE-039, BE-040
**Epic:** EPIC-13 Zaplanowane oddzwonienia
**Flyway:** V037__scheduled_callback_source_context.sql

**Opis:**
Tabela `scheduled_callback` obsługuje dotychczas tylko callbacki po kampaniach (dyspozycja CALLBACK). Nowa funkcjonalność wymaga rozróżnienia źródła callbacku oraz powiązania z kontaktem przychodzącym.

**Zmiany schematu (V036):**

```sql
-- 1. Kolumna source_type: skąd pochodzi callback
ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(30) NOT NULL DEFAULT 'CAMPAIGN_CALLBACK';

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_source_type
        CHECK (source_type IN ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK'));

-- 2. Powiązanie z kontaktem źródłowym (rozmowa przychodząca, z której pochodzi callback)
ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS origin_contact_id UUID;

-- FK z deferrable (nie blokuje operacji batchowych)
ALTER TABLE scheduled_callback
    ADD CONSTRAINT fk_scheduled_callback_origin_contact
        FOREIGN KEY (origin_contact_id) REFERENCES contact(contact_id) DEFERRABLE INITIALLY DEFERRED;

-- 3. Indeks dla widoku supervisora: callbacki z rozmów przychodzących
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_inbound
    ON scheduled_callback (tenant_id, source_type, scheduled_at)
    WHERE source_type = 'INBOUND_CALLBACK' AND status = 'PENDING' AND is_deleted = FALSE;

-- 4. Indeks po origin_contact_id (lookup: wszystkie callbacki z danego kontaktu)
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_origin_contact
    ON scheduled_callback (tenant_id, origin_contact_id)
    WHERE origin_contact_id IS NOT NULL AND is_deleted = FALSE;
```

**Wartości `source_type`:**
- `CAMPAIGN_CALLBACK` – dyspozycja CALLBACK po rozmowie kampanijnej (dotychczasowe zachowanie)
- `INBOUND_CALLBACK` – oddzwonienie zaplanowane podczas rozmowy przychodzącej przez agenta

**Kryteria akceptacji:**
- [ ] Migracja uruchamia się bez błędów na dev i test
- [ ] Istniejące rekordy mają `source_type = 'CAMPAIGN_CALLBACK'` (DEFAULT)
- [ ] CHECK constraint odrzuca nieznane wartości `source_type`
- [ ] FK `origin_contact_id → contact.contact_id` działa (NULL jest dozwolone)
- [ ] Oba nowe indeksy są widoczne w `pg_indexes`
- [ ] RLS dla `scheduled_callback` pokrywa nowe kolumny (polityka per-tenant już istnieje z V032)

**Uwagi implementacyjne:**
- Migracja jest addytywna – żadna istniejąca kolumna nie jest modyfikowana
- `origin_contact_id` jest nullable – nie łamie kompatybilności z istniejącymi callbackami kampanijnymi
- FK jest DEFERRABLE INITIALLY DEFERRED, aby nie blokować importów bulk

---

## MODUL: Zarządzanie przypisaniem agentów do kolejek (EPIC-14)

### DB-024 – Tabele `agent_group` i `agent_group_member`: grupy agentów

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-002 (tabela `app_user`), DB-001 (tabela `tenant`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** DB-025, DB-026, BE-043
**Epic:** EPIC-14 Zarządzanie przypisaniem agentów do kolejek
**Flyway:** V042__create_agent_groups.sql

**Opis:**
Wprowadza koncepcję nazwanych grup agentów. Agent może należeć do wielu grup (many-to-many). Grupy są zasobem tenanta — izolacja RLS analogiczna do pozostałych tabel.

**DDL migracji:**

```sql
CREATE TABLE agent_group (
    group_id   UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agent_group PRIMARY KEY (group_id),
    CONSTRAINT uq_agent_group_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_agent_group_tenant ON agent_group (tenant_id);

CREATE TABLE agent_group_member (
    group_id    UUID NOT NULL REFERENCES agent_group(group_id) ON DELETE CASCADE,
    agent_id    UUID NOT NULL REFERENCES app_user(user_id)    ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_agent_group_member PRIMARY KEY (group_id, agent_id)
);

CREATE INDEX idx_agent_group_member_agent ON agent_group_member (agent_id);
CREATE INDEX idx_agent_group_member_group ON agent_group_member (group_id);

-- RLS: tenant_id na agent_group (agent_group_member nie ma tenant_id — izolacja przez JOIN)
ALTER TABLE agent_group ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_group_tenant_isolation ON agent_group
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);
```

**Kryteria akceptacji:**
- [x] Migracja uruchamia się bez błędów na dev i test
- [x] Constraint `uq_agent_group_tenant_name` zapobiega duplikatom nazw w ramach tenanta
- [x] FK `agent_group_member.group_id → agent_group.group_id` kaskaduje usunięcie grupy
- [x] FK `agent_group_member.agent_id → app_user.user_id` kaskaduje usunięcie agenta
- [x] RLS na `agent_group` blokuje dostęp do rekordów innego tenanta
- [x] Indeksy widoczne w `pg_indexes`

---

### DB-025 – Rozszerzenie tabeli `queue` o flagę `all_agents` i tabelę `queue_agent_group`

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-024 (tabela `agent_group`), DB-010 (tabela `queue`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** DB-026, BE-045
**Epic:** EPIC-14 Zarządzanie przypisaniem agentów do kolejek
**Flyway:** V043__queue_agent_group.sql

**Opis:**
Dodaje flagę `all_agents` do tabeli `queue` (tryb "wszyscy agenci tenanta") oraz tabelę `queue_agent_group` łączącą kolejkę z grupą agentów. Istniejąca tabela `queue_agent` (kolejka ↔ konkretni agenci) pozostaje bez zmian — jej semantyka jest teraz jawna: "ręcznie wybrani agenci".

**DDL migracji:**

```sql
-- 1. Flaga na kolejce: obsługiwana przez wszystkich agentów tenanta
ALTER TABLE queue
    ADD COLUMN IF NOT EXISTS all_agents BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Powiązanie kolejki z grupą agentów
CREATE TABLE queue_agent_group (
    queue_id    UUID NOT NULL REFERENCES queue(queue_id)       ON DELETE CASCADE,
    group_id    UUID NOT NULL REFERENCES agent_group(group_id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_queue_agent_group PRIMARY KEY (queue_id, group_id)
);

CREATE INDEX idx_queue_agent_group_queue ON queue_agent_group (queue_id);
CREATE INDEX idx_queue_agent_group_group ON queue_agent_group (group_id);

-- 3. Domyślna wartość dla istniejących kolejek:
--    kolejki bez konfiguracji queue_agent → zachowaj obecne zachowanie (all_agents = TRUE)
--    Jest to decyzja migracyjna — supervisor może potem zmienić na konkretnych agentów.
UPDATE queue SET all_agents = TRUE WHERE all_agents = FALSE;
```

**Semantyka trybów przypisania:**
- `all_agents = TRUE` → silnik routingu filtruje tylko po `tenantId` (obecne zachowanie, brak zmian w logice)
- `all_agents = FALSE` + rekordy w `queue_agent` i/lub `queue_agent_group` → silnik filtruje tylko do przypisanych agentów
- `all_agents = FALSE` + brak rekordów → kolejka nie obsłuży żadnego kontaktu (logowanie WARNING w RoutingEngine)

**Kryteria akceptacji:**
- [x] Migracja uruchamia się bez błędów; istniejące kolejki mają `all_agents = TRUE`
- [x] FK `queue_agent_group.queue_id → queue.queue_id` kaskaduje
- [x] FK `queue_agent_group.group_id → agent_group.group_id` kaskaduje
- [x] Dodanie `all_agents` nie łamie istniejących INSERT/UPDATE w `QueueRepository` (kolumna ma DEFAULT)

---

### DB-026 – Indeksy wydajnościowe dla rozwiązania łączonego: kolejka + agenci/grupy

**Typ:** Schema migration
**Priorytet:** Should Have
**Zlozonosc:** XS
**Zależy od:** DB-024, DB-025
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** brak
**Epic:** EPIC-14 Zarządzanie przypisaniem agentów do kolejek
**Flyway:** V044__queue_agent_assignment_indexes.sql

**Opis:**
Zapytanie "pobierz wszystkich agentów przypisanych do kolejki Q (przez grupy LUB bezpośrednio)" jest wykonywane przy każdym wywołaniu `findBestAgent()` gdy `all_agents = FALSE`. Indeks wspiera UNION obu źródeł.

**DDL migracji:**

```sql
-- Compound index: queue_agent (już istnieje, ale sprawdź czy ma pokrycie na agent_id)
CREATE INDEX IF NOT EXISTS idx_queue_agent_queue
    ON queue_agent (queue_id, agent_id);

-- Lookup: wszystkie grupy kolejki → agenci
-- Pokrywa JOIN: queue_agent_group → agent_group_member
CREATE INDEX IF NOT EXISTS idx_queue_agent_group_lookup
    ON queue_agent_group (queue_id)
    INCLUDE (group_id);

-- Lookup: wszystkie grupy, do których należy agent (potrzebny dla UI "moje kolejki")
CREATE INDEX IF NOT EXISTS idx_agent_group_member_lookup
    ON agent_group_member (agent_id)
    INCLUDE (group_id);
```

**Kryteria akceptacji:**
- [x] Migracja idempotentna (IF NOT EXISTS)
- [x] `EXPLAIN ANALYZE` dla zapytania "agenci kolejki przez grupy + bezpośrednio" używa index scan
- [x] Indeksy widoczne w `pg_indexes`

---

---

## MODUL: Zakładka Klienci w Agent Desktop (EPIC-15)

### DB-027 – Rozszerzenie `scheduled_callback.source_type` o wartość `AGENT_MANUAL`

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** XS
**Zależy od:** DB-023 (tabela `scheduled_callback`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-24
**Blokuje:** BE-048
**Epic:** EPIC-15 Zakładka Klienci w Agent Desktop
**Flyway:** V047__scheduled_callback_agent_manual_source.sql

**Opis:**
Tabela `scheduled_callback` ma CHECK constraint ograniczający `source_type` do wartości `CAMPAIGN_CALLBACK` i `INBOUND_CALLBACK`. Nowy scenariusz — agent zamawia oddzwonienie do klienta z własnej inicjatywy poza aktywną rozmową — wymaga trzeciej wartości `AGENT_MANUAL`. Przy okazji dodawana jest kolumna `notes` potrzebna dla BE-048.

**DDL migracji (V046):**

```sql
-- Usunięcie starego CHECK constraint i dodanie rozszerzonego
ALTER TABLE scheduled_callback
    DROP CONSTRAINT IF EXISTS chk_scheduled_callback_source_type;

ALTER TABLE scheduled_callback
    ADD CONSTRAINT chk_scheduled_callback_source_type
        CHECK (source_type IN ('CAMPAIGN_CALLBACK', 'INBOUND_CALLBACK', 'AGENT_MANUAL'));

-- Kolumna notes dla manualnych callbacków inicjowanych przez agenta (BE-048)
ALTER TABLE scheduled_callback
    ADD COLUMN IF NOT EXISTS notes TEXT;

-- Indeks dla widoku agenta: jego własne manualne callbacki
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_manual
    ON scheduled_callback (tenant_id, assigned_agent_id, scheduled_at)
    WHERE source_type = 'AGENT_MANUAL'
      AND status = 'PENDING'
      AND is_deleted = FALSE;

-- Indeks kalendarza agenta: wszystkie callbacki w zakresie dat (BE-051)
CREATE INDEX IF NOT EXISTS idx_scheduled_callback_agent_calendar
    ON scheduled_callback (tenant_id, assigned_agent_id, scheduled_at)
    WHERE is_deleted = FALSE;
```

**Kryteria akceptacji:**
- [ ] Migracja uruchamia się bez błędów na dev i test
- [ ] Istniejące rekordy `CAMPAIGN_CALLBACK` i `INBOUND_CALLBACK` pozostają bez zmian
- [ ] Nowy CHECK constraint akceptuje `AGENT_MANUAL` i nadal odrzuca inne wartości
- [ ] Kolumna `notes TEXT` istnieje w tabeli `scheduled_callback`
- [ ] Oba indeksy widoczne w `pg_indexes`

---

## Podsumowanie zadań Baza Danych

| Kategoria | Liczba zadań | Must Have | Should Have |
|-----------|-------------|-----------|-------------|
| Infrastruktura / Fundament | 3 | 3 | 0 |
| Encje domenowe (PostgreSQL) | 12 | 12 | 0 |
| Bezpieczenstwo / Izolacja | 2 | 1 | 1 |
| Redis | 1 | 1 | 0 |
| RODO / Funkcje | 1 | 1 | 0 |
| Narzedzia operacyjne | 2 | 2 | 0 |
| Routing telefoniczny (EPIC-11) | 1 | 1 | 0 |
| Prezentacja Kontaktów (EPIC-12) | 1 | 1 | 0 |
| Zaplanowane oddzwonienia (EPIC-13) | 1 | 1 | 0 |
| Zarządzanie przypisaniem agentów (EPIC-14) | 3 | 2 | 1 |
| Zakładka Klienci w Agent Desktop (EPIC-15) | 1 | 1 | 0 |
| **RAZEM** | **27** | **25** | **2** |

---

---

## MODUŁ: Kalendarz Agenta (EPIC-16)

### DB-028 – Tabela `agent_break`: zaplanowane przerwy agentów

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** DB-003 (tabela `app_user`), DB-012 (FK przez tabelę `app_user`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** BE-049, BE-050
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Nowa migracja Flyway `V047__agent_break.sql`. Tabela przechowuje zaplanowane przerwy agentów widoczne w kalendarzu. Obsługuje typy przerw (obiad, krótka, szkolenie, inne) oraz statusy cyklu życia (PLANNED → ACTIVE → COMPLETED / CANCELLED).

**Schema:**
```sql
CREATE TABLE agent_break (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id   UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE RESTRICT,
  agent_id    UUID NOT NULL REFERENCES app_user(user_id) ON DELETE RESTRICT,
  start_time  TIMESTAMPTZ NOT NULL,
  end_time    TIMESTAMPTZ NOT NULL,
  break_type  VARCHAR(50)  NOT NULL DEFAULT 'SHORT_BREAK',
  notes       TEXT,
  status      VARCHAR(20)  NOT NULL DEFAULT 'PLANNED',
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ,
  CONSTRAINT chk_agent_break_type CHECK (break_type IN ('LUNCH', 'SHORT_BREAK', 'TRAINING', 'OTHER')),
  CONSTRAINT chk_agent_break_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT chk_agent_break_time CHECK (end_time > start_time)
);

CREATE INDEX idx_agent_break_tenant_agent_time ON agent_break (tenant_id, agent_id, start_time);

ALTER TABLE agent_break ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_break_tenant_isolation ON agent_break
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);
```

**Kryteria akceptacji:**
- [ ] Migracja V047 aplikuje się bez błędów
- [ ] `break_type` CHECK IN ('LUNCH','SHORT_BREAK','TRAINING','OTHER')
- [ ] `status` CHECK IN ('PLANNED','ACTIVE','COMPLETED','CANCELLED')
- [ ] `end_time > start_time` CHECK constraint
- [ ] FK `agent_id REFERENCES app_user(user_id) ON DELETE RESTRICT`
- [ ] FK `tenant_id REFERENCES tenant(tenant_id) ON DELETE RESTRICT`
- [ ] RLS włączone — policy izoluje po `app.tenant_id`
- [ ] Indeks pokrywa filtrowanie po zakresie dat dla konkretnego agenta
- [ ] `tenant_id` NOT NULL — izolacja multitenant

---

## MODUŁ: Wielojęzyczność – preferencje użytkownika (EPIC-19)

### DB-029 – Kolumna `preferred_language` w tabeli `app_user`

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-003 (tabela `app_user`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Blokuje:** BE-054
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Nowa migracja Flyway `V050__add_preferred_language_to_app_user.sql`. Dodanie kolumny `preferred_language VARCHAR(10)` do tabeli `app_user` z domyślną wartością `'pl'`. Kolumna przechowuje kod języka (ISO 639-1: `pl`, `en`, `de`, itd.). Brak RLS — każdy użytkownik ma dostęp tylko do własnego rekordu egzekwowanego przez logikę aplikacji (nie wymaga policy RLS na tej kolumnie).

**Schema:**
```sql
ALTER TABLE app_user
  ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'pl';

COMMENT ON COLUMN app_user.preferred_language IS 'ISO 639-1 language code for UI locale preference';
```

**Kryteria akceptacji:**
- [ ] Migracja `V050__add_preferred_language_to_app_user.sql` aplikuje się bez błędów
- [ ] Kolumna `preferred_language VARCHAR(10) NOT NULL DEFAULT 'pl'` istnieje w tabeli `app_user`
- [ ] Istniejące rekordy po migracji mają wartość `'pl'`
- [ ] `ng build` i testy backendu przechodzą po migracji

---

## MODUL: Per-tenant konfiguracja Twilio (EPIC-20)

### DB-030 – Tabela `tenant_twilio_config`: per-tenant kredencjały Twilio z szyfrowaniem

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** DB-001 (rozszerzenie pgcrypto), DB-002 (tabela `tenant`), DB-015 (RLS)
**Status:** ✅ Ukończone
**Blokuje:** BE-055, BE-056, BE-057
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio
**Flyway:** V051__create_tenant_twilio_config.sql

**Opis:**
Nowa tabela przechowująca konfigurację Twilio per tenant. Wrażliwe pola (`account_sid`, `auth_token`, `api_key_sid`, `api_key_secret`) będą szyfrowane na poziomie aplikacji (AES-256-GCM przez AttributeConverter w JPA) – baza przechowuje zaszyfrowany tekst. Kolumna `twiml_app_sid` i `phone_number` przechowywane jako plaintext (nie są tokenami autentykacyjnymi). Tabela ma relację 1:1 z tenantami (UNIQUE na `tenant_id`). RLS policy izoluje konfiguracje między tenantami.

**DDL migracji (V051):**
```sql
CREATE TABLE tenant_twilio_config (
    config_id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id          UUID        NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    account_sid        VARCHAR(255) NOT NULL,           -- szyfrowane AES-256-GCM przez aplikację
    auth_token         TEXT        NOT NULL,            -- szyfrowane AES-256-GCM przez aplikację
    api_key_sid        VARCHAR(255),                   -- szyfrowane, NULL gdy brak
    api_key_secret     TEXT,                           -- szyfrowane, NULL gdy brak
    twiml_app_sid      VARCHAR(64),                    -- plaintext, opcjonalne
    phone_number       VARCHAR(30),                    -- E.164, numer prezentacji tenanta
    status_callback_url TEXT,                          -- URL dla webhooków statusowych
    is_active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ,
    CONSTRAINT uq_tenant_twilio_config UNIQUE (tenant_id)
);

CREATE INDEX idx_tenant_twilio_config_tenant ON tenant_twilio_config (tenant_id) WHERE is_active;

ALTER TABLE tenant_twilio_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_twilio_config_isolation ON tenant_twilio_config
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);

-- Komentarze dokumentujące szyfrowanie (ważne dla audytu)
COMMENT ON COLUMN tenant_twilio_config.account_sid IS
    'Szyfrowane AES-256-GCM przez aplikację; wartość w bazie to Base64(IV||ciphertext)';
COMMENT ON COLUMN tenant_twilio_config.auth_token IS
    'Szyfrowane AES-256-GCM przez aplikację; wartość w bazie to Base64(IV||ciphertext)';
COMMENT ON COLUMN tenant_twilio_config.api_key_sid IS
    'Szyfrowane AES-256-GCM przez aplikację; NULL gdy tenant używa globalnych kredencjałów';
COMMENT ON COLUMN tenant_twilio_config.api_key_secret IS
    'Szyfrowane AES-256-GCM przez aplikację; NULL gdy tenant używa globalnych kredencjałów';
```

**Kryteria akceptacji:**
- [ ] Migracja `V051__create_tenant_twilio_config.sql` aplikuje się bez błędów na dev i test
- [ ] Constraint `UNIQUE (tenant_id)` zapobiega duplikatom konfiguracji (jeden config per tenant)
- [ ] FK `tenant_id REFERENCES tenant(tenant_id) ON DELETE CASCADE` – usunięcie tenanta usuwa config
- [ ] RLS policy izoluje konfiguracje – tenant A nie widzi konfiguracji tenanta B
- [ ] `CHECK` walidacja formatu `phone_number` (E.164: `^\+[1-9][0-9]{6,14}$`) jako constraint lub na poziomie aplikacji
- [ ] Komentarze kolumn dokumentują mechanizm szyfrowania (widoczne w `\d+ tenant_twilio_config`)
- [ ] Migracja idempotentna: `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`
- [ ] Tabela seed dev (V999) zawiera przykładową konfigurację dla tenant testowego z placeholder values

---

### DB-031 – Kolumna `caller_id` w tabeli `campaign`: numer prezentacji dla kampanii wychodzących

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** DB-011 (tabela `campaign`), DB-030 (tenant_twilio_config jako źródło domyślnego numeru)
**Status:** ✅ Ukończone
**Blokuje:** BE-060
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio
**Flyway:** V052__add_caller_id_to_campaign.sql

**Opis:**
Addytywna migracja dodająca kolumnę `caller_id` (nullable) do tabeli `campaign`. Pole przechowuje numer prezentacji (caller ID) w formacie E.164, który będzie używany przez dialer podczas wychodzących połączeń z tej kampanii. Wartość `NULL` oznacza fallback do domyślnego numeru tenanta (z `tenant_twilio_config.phone_number` lub `TwilioProperties` z konfiguracji globalnej).

**DDL migracji (V052):**
```sql
ALTER TABLE campaign
    ADD COLUMN IF NOT EXISTS caller_id VARCHAR(30) NULL;

COMMENT ON COLUMN campaign.caller_id IS
    'Numer prezentacji (caller ID) dla połączeń wychodzących w formacie E.164. '
    'NULL = użyj domyślnego numeru tenanta z tenant_twilio_config lub konfiguracji globalnej.';

-- Partial index dla szybkiego lookup kampanii z własnym caller_id
CREATE INDEX IF NOT EXISTS idx_campaign_caller_id
    ON campaign (tenant_id, caller_id)
    WHERE caller_id IS NOT NULL AND is_deleted = FALSE;
```

**Kryteria akceptacji:**
- [ ] Migracja `V052__add_caller_id_to_campaign.sql` aplikuje się bez błędów
- [ ] Kolumna `caller_id VARCHAR(30) NULL` istnieje w tabeli `campaign`
- [ ] Istniejące kampanie po migracji mają `caller_id = NULL` (zachowanie backward-compatible)
- [ ] Partial index `idx_campaign_caller_id` widoczny w `pg_indexes`
- [ ] Komentarz kolumny dokumentuje semantykę NULL
- [ ] Migracja idempotentna (`ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`)

---

## Macierz gotowości: DB → BE → FE

Poniższa tabela przedstawia minimalny lancuch zależnosci od schematu DB do widoku FE:

| Funkcja | DB | BE | FE |
|---------|-----|-----|-----|
| Logowanie i MFA | DB-001, DB-002, DB-003, DB-016 | BE-003, BE-004 | FE-004 |
| Lista tenantów (Admin) | DB-001, DB-002, DB-005 | BE-006, BE-007 | FE-006, FE-007 |
| Zarządzanie agentami | DB-002, DB-003 | BE-008 | FE-008 |
| Połączenie telefoniczne | DB-003, DB-006 | BE-009, BE-011, BE-012 | FE-010, FE-011 |
| Nagrywanie rozmów | DB-006 | BE-010 | FE-019 (historia) |
| IVR editor | DB-009 | BE-013 | FE-014 |
| Voicebot | DB-009 | BE-014 | – (brak widoku FE) |
| Email handling | DB-006, DB-007, DB-020 | BE-015 | FE-012 |
| Social media | DB-006, DB-008 | BE-017, BE-018 | FE-013, FE-023 |
| Routing | DB-010 | BE-019, BE-020 | FE-024 |
| Kampanie outbound | DB-011 | BE-022, BE-023, BE-024 | FE-015, FE-016 |
| Baza klientów | DB-012 | BE-025, BE-026 | FE-018, FE-019, FE-020 |
| Dashboard RT supervisora | DB-006, DB-003, DB-010 | BE-029 | FE-021 |
| Raporty historyczne | DB-013 | BE-028 | FE-022 |
| Data Warehouse / ETL | DB-013, DB-014 | BE-030 | – |
| RODO anonimizacja | DB-012, DB-017 | BE-031 | FE-018 (przycisk usuń) |
| Routing numerów telefonicznych | DB-021 | BE-033, BE-034, BE-035 | FE-026 |
| Prezentacja Kontaktów (raporty) | DB-022 | BE-036 (czeka na DB-022), BE-037 ✅ (niezależne od DB-022) | FE-028, FE-029, FE-030 |
| Zaplanowane oddzwonienia | DB-023 | BE-038 (executor), BE-039 (reschedule API), BE-040 (inbound callback API) | FE-031 (reschedule modal), FE-032 (inbound callback modal) |
| Zarządzanie przypisaniem agentów | DB-024, DB-025, DB-026 | BE-043, BE-044, BE-045, BE-046, BE-047 | FE-036 ✅, FE-037, FE-038, FE-039 |
| Zakładka Klienci w Agent Desktop | DB-027 | BE-048 | FE-040, FE-041 |
| Kalendarz Agenta (EPIC-16) | DB-028 | BE-049, BE-050, BE-051 | FE-042, FE-043, FE-044, FE-045 |
| Wielojęzyczność UI (EPIC-19) | DB-029 | BE-054 | FE-049, FE-050, FE-051, FE-052, FE-053 |
| Retry i callback w kampaniach wychodzących (EPIC-21) | DB-032, DB-033 | BE-062, BE-063, BE-064, BE-065, BE-066 | FE-069, FE-070 |

---

## MODUL: Retry i callback w kampaniach wychodzących (EPIC-21)

### DB-032 – Nowe statusy `campaign_contact`: `NOT_REACHED` i `CALLBACK` — migracja V053

**Typ:** Schema change
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** DB-011, V034
**Blokuje:** BE-063, BE-064, BE-065, BE-066, DB-033, FE-069
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Rozszerzenie CHECK constraintu `chk_campaign_contact_status` w tabeli `campaign_contact` o dwa nowe statusy:

- **`NOT_REACHED`** – rekord osiągnął limit `max_attempts` bez nawiązania rozmowy (niedodzwoniony). Status finalny, rekord nie wraca do kolejki dialera.
- **`CALLBACK`** – agent podczas rozmowy ustawił dyspozycję CALLBACK. Rekord ma zaplanowane oddzwonienie w `scheduled_callback`. Nie inkrementuje `attempt_count` przy wykonaniu callbacku.

Bieżący constraint (po V034):
```sql
CHECK (status IN ('PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER', 'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR'))
```

**Migracja Flyway V053** (`V053__add_not_reached_callback_status.sql`):

```sql
-- 1. campaign_contact – rozszerzenie CHECK constraint
ALTER TABLE campaign_contact
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_status;

ALTER TABLE campaign_contact
    ADD CONSTRAINT chk_campaign_contact_status
        CHECK (status IN (
            'PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER',
            'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR',
            'NOT_REACHED', 'CALLBACK'
        ));

-- 2. campaign_contact_archive – analogicznie
ALTER TABLE campaign_contact_archive
    DROP CONSTRAINT IF EXISTS chk_campaign_contact_archive_status;

ALTER TABLE campaign_contact_archive
    ADD CONSTRAINT chk_campaign_contact_archive_status
        CHECK (status IN (
            'PENDING', 'DIALING', 'CONNECTED', 'NO_ANSWER',
            'FAILED', 'COMPLETED', 'SKIPPED', 'ERROR',
            'NOT_REACHED', 'CALLBACK'
        ));

-- 3. Aktualizacja indeksu dialera – teraz obejmuje też NO_ANSWER (retry)
DROP INDEX IF EXISTS idx_campaign_contact_dialer;
CREATE INDEX idx_campaign_contact_dialer
    ON campaign_contact (campaign_id, status, next_attempt_at)
    WHERE status IN ('PENDING', 'NO_ANSWER');

-- 4. Odświeżenie mv_campaign_stats – dodanie kolumn dla nowych statusów
DROP MATERIALIZED VIEW IF EXISTS mv_campaign_stats;
CREATE MATERIALIZED VIEW mv_campaign_stats AS
SELECT
    cc.campaign_id,
    c.tenant_id,
    c.name                                                              AS campaign_name,
    c.type                                                              AS campaign_type,
    COUNT(*)                                                            AS total_records,
    COUNT(*) FILTER (WHERE cc.status = 'PENDING')                      AS pending_records,
    COUNT(*) FILTER (WHERE cc.status = 'DIALING')                      AS dialing_records,
    COUNT(*) FILTER (WHERE cc.status = 'CONNECTED')                    AS connected_records,
    COUNT(*) FILTER (WHERE cc.status = 'NO_ANSWER')                    AS no_answer_records,
    COUNT(*) FILTER (WHERE cc.status = 'NOT_REACHED')                  AS not_reached_records,
    COUNT(*) FILTER (WHERE cc.status = 'CALLBACK')                     AS callback_records,
    COUNT(*) FILTER (WHERE cc.status = 'COMPLETED')                    AS completed_records,
    COUNT(*) FILTER (WHERE cc.status = 'FAILED')                       AS failed_records,
    COUNT(*) FILTER (WHERE cc.status = 'ERROR')                        AS error_records,
    ROUND(AVG(cc.attempt_count), 2)                                     AS avg_attempt_count,
    SUM(cc.attempt_count)                                               AS total_attempts,
    COUNT(*) FILTER (WHERE cc.disposition_code IS NOT NULL)             AS contacts_with_disposition,
    MAX(cc.last_attempt_at)                                             AS last_activity_at
FROM  campaign_contact cc
JOIN  campaign          c  ON c.campaign_id = cc.campaign_id
GROUP BY cc.campaign_id, c.tenant_id, c.name, c.type;

CREATE UNIQUE INDEX uq_mv_campaign_stats ON mv_campaign_stats (campaign_id);
CREATE INDEX idx_mv_campaign_stats_tenant ON mv_campaign_stats (tenant_id);

-- 5. Aktualizacja komentarza dokumentacyjnego
COMMENT ON COLUMN campaign_contact.status IS
    'Status rekordu kampanii. '
    'PENDING = oczekuje na polaczenie, '
    'DIALING = w trakcie wybierania (attempt_count zostal zinkrementowany), '
    'CONNECTED = polaczono z agentem, '
    'NO_ANSWER = brak odpowiedzi – zaplanowana kolejna proba (next_attempt_at), '
    'NOT_REACHED = wyczerpano max_attempts bez odpowiedzi (finalny – niedodzwoniony), '
    'CALLBACK = agent zaplanował oddzwonienie (scheduled_callback powiazan), '
    'COMPLETED = zakonczono z dyspozycja agenta, '
    'FAILED = blad polaczenia (np. numer niedostepny), '
    'SKIPPED = pominieto recznie, '
    'ERROR = blad techniczny adaptera telefonii.';
```

**Uwagi:**
- Tabela `campaign_contact` jest partycjonowana LIST po `campaign_id` — ALTER TABLE propaguje na wszystkie partycje i domyślną partycję automatycznie.
- Indeks `idx_campaign_contact_dialer` zmieniony: `WHERE status IN ('PENDING', 'NO_ANSWER')` — konieczny dla BE-065 (dialer pobiera również NO_ANSWER z przeterminowanym `next_attempt_at`).
- `mv_campaign_stats` jest refreshowany przez pg_cron (DB-018) — rekonstrukcja widoku nie wymaga zmian w schedulerze.

**Kryteria akceptacji:**
- [x] Migracja V053 zastosowana bez błędów na czystej bazie i na bazie z danymi
- [x] `INSERT INTO campaign_contact (..., status, ...) VALUES (..., 'NOT_REACHED', ...)` nie rzuca wyjątku CHECK violation
- [x] `INSERT INTO campaign_contact (..., status, ...) VALUES (..., 'CALLBACK', ...)` nie rzuca wyjątku CHECK violation
- [x] `INSERT INTO campaign_contact (..., status, ...) VALUES (..., 'INVALID', ...)` rzuca CHECK violation
- [x] Indeks `idx_campaign_contact_dialer` pokrywa `WHERE status IN ('PENDING', 'NO_ANSWER')`
- [x] Widok `mv_campaign_stats` zawiera kolumny `not_reached_records` i `callback_records`
- [x] Tabele archiwalne (`campaign_contact_archive`) mają taki sam constraint

---

### DB-033 – Dedykowane pole `campaign_contact_record_id` w `scheduled_callback` — migracja V054

**Typ:** Schema change
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zależy od:** DB-032
**Blokuje:** BE-064, BE-066
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Tabela `scheduled_callback` nie ma dedykowanego pola do powiązania z rekordem `campaign_contact`. Dotychczasowy plan (BE-064) zakładał reużycie kolumny `customer_id` jako kontenera na `campaign_contact.record_id` — to anti-pattern: jedna kolumna przechowuje dwie semantycznie różne wartości (ID klienta vs ID rekordu kampanijnego).

Rozwiązanie: nowa kolumna `campaign_contact_record_id UUID` z jasną semantyką.

- `customer_id` pozostaje bez zmian — nadal wskazuje na klienta z tabeli `customer`
- `campaign_contact_record_id` — UUID rekordu `campaign_contact` (brak FK: `campaign_contact` ma composite PK)
- Kolumna nullable: `NULL` dla `INBOUND_CALLBACK` i `AGENT_MANUAL`

**Migracja Flyway V054** (`V054__add_campaign_contact_record_id_to_scheduled_callback.sql`):

```sql
ALTER TABLE scheduled_callback
    ADD COLUMN campaign_contact_record_id UUID;

COMMENT ON COLUMN scheduled_callback.campaign_contact_record_id IS
    'UUID rekordu campaign_contact (campaign_contact.record_id) powiązanego z tym callbackiem. '
    'NULL dla INBOUND_CALLBACK i AGENT_MANUAL. '
    'Brak FK – campaign_contact ma composite PK (record_id, campaign_id).';

CREATE INDEX idx_scheduled_callback_cc_record
    ON scheduled_callback (campaign_contact_record_id)
    WHERE campaign_contact_record_id IS NOT NULL AND is_deleted = FALSE;

COMMENT ON INDEX idx_scheduled_callback_cc_record IS
    'DB-033: Lookup callbacków kampanijnych po record_id rekordu campaign_contact.';
```

**Kryteria akceptacji:**
- [ ] Migracja V054 zastosowana bez błędów
- [ ] Kolumna `campaign_contact_record_id` istnieje i przyjmuje NULL (dla non-campaign callbacków)
- [ ] Indeks `idx_scheduled_callback_cc_record` istnieje z predykatem `IS NOT NULL AND is_deleted = FALSE`
- [ ] Istniejące wiersze `scheduled_callback` nie są naruszone (kolumna domyślnie NULL)

---

## MODUŁ: Notatki do kontaktów (EPIC-22)

### DB-034 – Kolumna `notes` w tabeli `contact` — migracja V058

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-006 (tabela `contact`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** BE-069, BE-070
**Epic:** EPIC-22 Notatki do kontaktów

**Opis:**
Agent może wpisać notatkę podczas obsługi połączenia telefonicznego (panel softphone). Notatka powinna zostać zapisana przy kontakcie i być prezentowana w widoku szczegółów kontaktu oraz w historii klienta w panelu agenta. Notatki mogą być długie — kolumna musi być typu `TEXT`.

**DDL migracji (`V058__add_notes_to_contact.sql`):**

```sql
ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS notes TEXT;

COMMENT ON COLUMN contact.notes IS
    'Notatka agenta wpisana podczas lub po zakończeniu kontaktu. '
    'Opcjonalna, bez limitu długości. Ustawiana przez PATCH /api/contacts/{id}/disposition.';
```

**Uwagi implementacyjne:**
- Kolumna nullable — backward-compatible z istniejącymi kontaktami
- Brak indeksu — pole nie jest używane w filtrach wyszukiwania, tylko do odczytu
- Tabela jest partycjonowana RANGE po `started_at` — `ALTER TABLE ADD COLUMN` propaguje automatycznie na wszystkie partycje
- RLS na tabeli `contact` pokrywa nową kolumnę bez dodatkowych zmian (policy oparta na `tenant_id`)

**Kryteria akceptacji:**
- [ ] Migracja `V058__add_notes_to_contact.sql` aplikuje się bez błędów na dev i test
- [ ] Kolumna `notes TEXT` istnieje w tabeli `contact` i przyjmuje NULL
- [ ] Istniejące wiersze kontaktów nie są naruszone
- [ ] `ALTER TABLE` propaguje na wszystkie partycje miesięczne (weryfikacja przez `\d+ contact_y2026m01` itp.)
- [ ] Migracja idempotentna (`ADD COLUMN IF NOT EXISTS`)

---

## MODUŁ: Historia etapów kontaktu (EPIC-23)

### DB-035 – Tabela `contact_event`: rejestracja etapów kontaktu (IVR, kolejka, agent, hold)

**Typ:** Schema migration
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-006 (tabela `contact`), DB-002 (tabela `tenant`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** BE-071, BE-072, BE-073
**Epic:** EPIC-23 Historia etapów kontaktu
**Flyway:** V059__create_contact_event.sql

**Opis:**
Każdy kontakt przechodzi przez kolejne etapy: IVR → Kolejka → Agent → (Hold → Agent → ...). Aktualnie tabela `contact` przechowuje tylko `queued_at` (wejście do kolejki) i `assigned_at` (przypisanie agenta) — brak czasu IVR i persystencji zdarzeń hold/unhold. Nowa tabela `contact_event` rejestruje każde zdarzenie z dokładnym znacznikiem czasu, umożliwiając pełną rekonstrukcję historii przepływu kontaktu.

**DDL migracji (`V059__create_contact_event.sql`):**

```sql
CREATE TABLE contact_event (
    event_id         UUID         NOT NULL DEFAULT uuid_generate_v4(),
    contact_id       UUID         NOT NULL,
    tenant_id        UUID         NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    stage            VARCHAR(20)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at         TIMESTAMPTZ,
    duration_seconds INT,
    metadata         JSONB        NOT NULL DEFAULT '{}',
    CONSTRAINT pk_contact_event PRIMARY KEY (event_id),
    CONSTRAINT chk_contact_event_stage CHECK (
        stage IN ('IVR', 'VOICEBOT', 'QUEUE', 'AGENT', 'ON_HOLD', 'CONSULTING', 'TRANSFER')
    ),
    CONSTRAINT chk_contact_event_times CHECK (
        ended_at IS NULL OR ended_at >= started_at
    )
);

COMMENT ON TABLE contact_event IS
    'Historia etapów kontaktu. Jeden rekord per zdarzenie: wejście do IVR, '
    'oczekiwanie w kolejce, obsługa przez agenta, wstrzymanie (hold). '
    'metadata JSONB zawiera kontekst etapu: nazwę IVR, kolejki lub agenta.';

COMMENT ON COLUMN contact_event.stage IS
    'IVR = obsługa w drzewie IVR (węzły MENU/DTMF/PLAY_AUDIO), '
    'VOICEBOT = obsługa przez bota ASR+NLU (węzeł VOICEBOT), '
    'QUEUE = oczekiwanie w kolejce, '
    'AGENT = obsługa przez agenta, '
    'ON_HOLD = wstrzymanie połączenia, '
    'CONSULTING = faza konsultacji przy attended transfer (agent rozmawia z celem przed przekazaniem), '
    'TRANSFER = zdarzenie przekazania kontaktu (punkt w czasie, started_at = ended_at).';

COMMENT ON COLUMN contact_event.metadata IS
    'Kontekst etapu. '
    'IVR/VOICEBOT: {"ivr_tree_id":"...", "ivr_tree_name":"...", "outcome":"ESCALATED|COMPLETED|ERROR"}. '
    'QUEUE: {"queue_id":"...", "queue_name":"..."}. '
    'AGENT: {"agent_id":"...", "agent_name":"..."}. '
    'ON_HOLD: {}. '
    'CONSULTING: {"target":"+48...", "transfer_type":"ATTENDED"}. '
    'TRANSFER: {"target":"+48...", "transfer_type":"BLIND|ATTENDED", "target_agent_name":"..."}.';

-- Trigger: automatyczne obliczanie duration_seconds przy zamknięciu etapu
CREATE OR REPLACE FUNCTION fn_contact_event_on_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ended_at IS NOT NULL AND OLD.ended_at IS NULL THEN
        NEW.duration_seconds :=
            EXTRACT(EPOCH FROM (NEW.ended_at - NEW.started_at))::INT;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contact_event_on_update
    BEFORE UPDATE ON contact_event
    FOR EACH ROW EXECUTE FUNCTION fn_contact_event_on_update();

-- Indeks główny: pobierz wszystkie etapy kontaktu posortowane chronologicznie
CREATE INDEX idx_contact_event_contact
    ON contact_event (contact_id, started_at ASC);

-- Indeks tenant: zapytania raportowe i RLS
CREATE INDEX idx_contact_event_tenant
    ON contact_event (tenant_id, started_at DESC);

-- RLS
ALTER TABLE contact_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY contact_event_tenant_isolation ON contact_event
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::uuid);
```

**Semantyka etapów (`stage`):**
| Etap | Znaczenie | Punkt startu | Punkt końca |
|------|-----------|-------------|-------------|
| `IVR` | Węzły MENU/DTMF/PLAY_AUDIO — interaktywne drzewo IVR | `IvrEngineService.startIvrSession()` | Wejście w węzeł VOICEBOT lub wyjście do kolejki |
| `VOICEBOT` | Węzeł VOICEBOT — bot ASR+NLU prowadzi konwersację | Wejście w węzeł VOICEBOT | Eskalacja do kolejki lub przejście do `next` node |
| `QUEUE` | Kontakt oczekuje na agenta | Publikacja `ContactQueuedMessage` | Agent odbiera kontakt |
| `AGENT` | Agent obsługuje kontakt | Agent odpowiada | Zakończenie, hold lub transfer |
| `ON_HOLD` | Połączenie wstrzymane | `holdCall()` | `unholdCall()` |
| `CONSULTING` | Faza konsultacji — agent rozmawia z celem attended transfer zanim przekaże klienta | `transferCall(ATTENDED)` sukces | `completeAttendedTransfer()` lub `cancelTransfer()` |
| `TRANSFER` | Zdarzenie przekazania — punkt w czasie (`started_at = ended_at`) | Transfer zakończony | = `started_at` |

**Kryteria akceptacji:**
- [ ] Migracja V059 aplikuje się bez błędów na dev i test
- [ ] CHECK constraint `stage` akceptuje: IVR, VOICEBOT, QUEUE, AGENT, ON_HOLD, CONSULTING, TRANSFER i odrzuca inne
- [ ] CHECK constraint `ended_at >= started_at` działa poprawnie (TRANSFER: `started_at = ended_at` jest dozwolone)
- [ ] Trigger `trg_contact_event_on_update` oblicza `duration_seconds` przy ustawieniu `ended_at`
- [ ] Indeks `idx_contact_event_contact` widoczny w `pg_indexes`
- [ ] RLS policy izoluje zdarzenia między tenantami

---

## MODUŁ: Przypisywanie agentów do kampanii (EPIC-25)

### DB-036 – Schemat przypisania agentów do kampanii: `all_agents`, `campaign_agent`, `campaign_agent_group` — migracja V062

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-011 (`campaign`), DB-003 (`app_user`), DB-024 (`agent_group`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** BE-079, BE-080
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
Model przypisania agentów do kampanii jest trójipoziomowy — identyczny z modelem kolejek (V043):
1. **`campaign.all_agents = TRUE`** — dialer/widok manualny dostępny dla wszystkich agentów tenanta
2. **`campaign_agent_group`** — kampania powiązana z grupami agentów (`agent_group`)
3. **`campaign_agent`** — bezpośrednie przypisanie konkretnych agentów

Gdy `all_agents = FALSE` i brak przypisań (puste obie tabele dla kampanii) → dialer **nie inicjuje połączeń**, a panel manualny **nie wyświetla rekordów** tej kampanii dla danego agenta.

Istniejące kampanie dostają `all_agents = TRUE` (zachowanie dotychczasowe). Nowe kampanie domyślnie `all_agents = FALSE` — wymagają jawnego przypisania.

**DDL migracji (`V062__campaign_agent_assignment.sql`):**

```sql
-- =============================================================================
-- V062__campaign_agent_assignment.sql
-- DB-036: Trójpoziomowe przypisanie agentów do kampanii wychodzącej.
--
-- Model identyczny z V043 (queue_agent_group):
--   campaign.all_agents    → wszyscy agenci tenanta
--   campaign_agent_group   → kampania ↔ agent_group (many-to-many)
--   campaign_agent         → kampania ↔ agent bezpośrednio (many-to-many)
--
-- Istniejące kampanie: all_agents = TRUE (backward compat).
-- Nowe kampanie: all_agents = FALSE (domyślnie — wymagają jawnego przypisania).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Flaga all_agents na tabeli campaign
-- ---------------------------------------------------------------------------

ALTER TABLE campaign
    ADD COLUMN IF NOT EXISTS all_agents BOOLEAN NOT NULL DEFAULT FALSE;

-- Istniejące kampanie zachowują dotychczasowe zachowanie (wszyscy agenci tenanta)
UPDATE campaign SET all_agents = TRUE WHERE all_agents = FALSE;

COMMENT ON COLUMN campaign.all_agents IS
    'TRUE = dialer i widok manualny dostępne dla wszystkich agentów tenanta. '
    'FALSE = tylko agenci z campaign_agent i/lub campaign_agent_group. '
    'Gdy FALSE i obie tabele puste — dialer nie dzwoni, panel manualny nie pokazuje rekordów.';

-- ---------------------------------------------------------------------------
-- 2. Tabela campaign_agent (bezpośrednie przypisanie agent → kampania)
-- ---------------------------------------------------------------------------

CREATE TABLE campaign_agent (
    campaign_id  UUID        NOT NULL REFERENCES campaign(campaign_id)  ON DELETE CASCADE,
    agent_id     UUID        NOT NULL REFERENCES app_user(user_id)      ON DELETE CASCADE,
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_campaign_agent PRIMARY KEY (campaign_id, agent_id)
);

CREATE INDEX idx_campaign_agent_campaign ON campaign_agent (campaign_id);
CREATE INDEX idx_campaign_agent_agent    ON campaign_agent (agent_id);

COMMENT ON TABLE campaign_agent IS
    'Bezpośrednie przypisanie agenta do kampanii wychodzącej. '
    'Aktywne tylko gdy campaign.all_agents = FALSE. CASCADE DELETE przy usunięciu kampanii lub agenta.';

-- ---------------------------------------------------------------------------
-- 3. Tabela campaign_agent_group (przypisanie grupy agentów → kampania)
-- ---------------------------------------------------------------------------

CREATE TABLE campaign_agent_group (
    campaign_id UUID        NOT NULL REFERENCES campaign(campaign_id)    ON DELETE CASCADE,
    group_id    UUID        NOT NULL REFERENCES agent_group(group_id)    ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_campaign_agent_group PRIMARY KEY (campaign_id, group_id)
);

CREATE INDEX idx_campaign_agent_group_campaign ON campaign_agent_group (campaign_id);
CREATE INDEX idx_campaign_agent_group_group    ON campaign_agent_group (group_id);

COMMENT ON TABLE campaign_agent_group IS
    'Powiązanie many-to-many kampania ↔ grupa agentów. '
    'Aktywne tylko gdy campaign.all_agents = FALSE. CASCADE DELETE przy usunięciu kampanii lub grupy.';

-- ---------------------------------------------------------------------------
-- 4. Indeksy pokrywające (wydajność resolveEligibleAgentIds — UNION)
-- ---------------------------------------------------------------------------

-- Covering: campaign_id → group_id (join campaign_agent_group → agent_group_member)
CREATE INDEX idx_campaign_agent_group_lookup
    ON campaign_agent_group (campaign_id)
    INCLUDE (group_id);

-- Covering: agent_id → group_id (odwrotny lookup: agent → grupy kampanii)
CREATE INDEX idx_campaign_agent_member_lookup
    ON agent_group_member (agent_id)
    INCLUDE (group_id);
```

**Uwagi implementacyjne:**
- Brak `tenant_id` w `campaign_agent` i `campaign_agent_group` — izolacja przez FK do `campaign` (RLS na `campaign` pokrywa pośrednio)
- `agent_group_member` istnieje już z V042 — nowy indeks `idx_campaign_agent_member_lookup` dodany IF NOT EXISTS
- CASCADE DELETE na obu tabelach: usunięcie kampanii lub agenta/grupy usuwa przypisanie

**Kryteria akceptacji:**
- [x] Migracja V062 aplikuje się bez błędów
- [x] Kolumna `campaign.all_agents BOOLEAN NOT NULL DEFAULT FALSE` istnieje
- [x] Istniejące kampanie mają `all_agents = TRUE` po migracji
- [x] Tabela `campaign_agent` z PK `(campaign_id, agent_id)` i indeksami
- [x] Tabela `campaign_agent_group` z PK `(campaign_id, group_id)` i indeksami
- [x] CASCADE DELETE: usunięcie kampanii usuwa wiersze z obu tabel przypisania
- [x] CASCADE DELETE: usunięcie agenta usuwa jego wiersze z `campaign_agent`
- [x] CASCADE DELETE: usunięcie grupy usuwa jej wiersze z `campaign_agent_group`

---

### DB-037 – Kolumna `campaign_contact_record_id` w tabeli `contact` — migracja V063

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-011 (`campaign_contact`), DB-006 (`contact`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** BE-085
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst — zweryfikowany stan:**
Każde połączenie wychodzące z dialera tworzy rekord w tabeli `contact`. Jeden rekord `campaign_contact` (lista kampanii) może generować wiele kontaktów (wiele prób wydzwonienia). Aktualnie:
- `campaign_contact.last_contact_id` istnieje w schemacie (V009), ale **nigdy nie jest wypełniany** przez dialer — grep po całym kodzie zwrócił zero wyników
- `contact` nie ma pola `campaign_contact_record_id` — brak możliwości zapytania „wszystkie kontakty dla rekordu X"
- `DialerCallbackHandler` zna `recordId` (campaign_contact) i `contactId` (z Redis state), ale nie zapisuje powiązania

Rozwiązanie: dodać `campaign_contact_record_id` do `contact`. Brak FK (jak `callback_id` — tabela `campaign_contact` ma composite PK `(record_id, campaign_id)` niekompatybilny z prostą FK).

**DDL migracji (`V063__add_campaign_contact_record_id_to_contact.sql`):**

```sql
-- =============================================================================
-- V063__add_campaign_contact_record_id_to_contact.sql
-- DB-037: Powiązanie kontaktu wychodzącego z rekordem listy kampanii.
--
-- Brak FK: campaign_contact ma composite PK (record_id, campaign_id) —
-- analogicznie do callback_id (V040) używamy UUID bez FK constraint.
-- =============================================================================

ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS campaign_contact_record_id UUID;

CREATE INDEX idx_contact_campaign_contact_record
    ON contact (campaign_contact_record_id)
    WHERE campaign_contact_record_id IS NOT NULL;

COMMENT ON COLUMN contact.campaign_contact_record_id IS
    'UUID rekordu campaign_contact (campaign_contact.record_id), z którego powstał ten kontakt. '
    'Nullable — wypełniany tylko dla kontaktów wychodzących z dialera kampanijnego. '
    'Brak FK ze względu na composite PK w campaign_contact (analogicznie do callback_id).';
```

Dodatkowo: upewnić się że `campaign_contact.last_contact_id` będzie wypełniany przez dialer (logika w BE-085).

**Kryteria akceptacji:**
- [x] Migracja V063 aplikuje się bez błędów na dev i test
- [x] Kolumna `contact.campaign_contact_record_id UUID` istnieje i jest nullable
- [x] Indeks `idx_contact_campaign_contact_record` widoczny w `pg_indexes` z filtrem `WHERE ... IS NOT NULL`
- [x] Istniejące wiersze kontaktów nie są naruszone (NULL backfill)
- [x] Tabela `contact` jest partycjonowana — `ADD COLUMN` propaguje na wszystkie partycje automatycznie

---

## EPIC-26: AI-Powered Conversation Summary

### DB-038 – Tabela `tenant_ai_config`: konfiguracja dostawcy AI per tenant — migracja V064

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-002 (tabela `tenant`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-086
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Kontekst:**
System musi umożliwiać konfigurację dostawcy AI (Claude/OpenAI/Azure OpenAI) niezależnie per tenant. Klucze API muszą być szyfrowane — analogicznie do `tenant_twilio_config` (DB-030) z konwerterem AES-256-GCM. Wzorzec: jeden wiersz per tenant (`UNIQUE tenant_id`).

**DDL migracji (`V064__create_tenant_ai_config.sql`):**

```sql
-- =============================================================================
-- V064__create_tenant_ai_config.sql
-- DB-038: Konfiguracja dostawcy AI per tenant.
-- Klucz api_key szyfrowany AES-256-GCM przez EncryptedStringConverter (JPA).
-- Wzorzec: analogiczny do tenant_twilio_config (V051).
-- =============================================================================

CREATE TYPE ai_provider AS ENUM ('ANTHROPIC', 'OPENAI', 'AZURE_OPENAI');

CREATE TABLE tenant_ai_config (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,

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
```

**Kryteria akceptacji:**
- [x] Migracja V064 aplikuje się bez błędów na dev i test
- [x] ENUM `ai_provider` z wartościami `ANTHROPIC`, `OPENAI`, `AZURE_OPENAI` (+ `OPENROUTER` dodany w V066)
- [x] UNIQUE constraint na `tenant_id` — max jedna konfiguracja per tenant
- [x] RLS policy izoluje dane między tenantami
- [x] Partial index `WHERE is_active` na `tenant_id`
- [x] Komentarze kolumn dokumentują szyfrowanie `api_key_encrypted`

---

### DB-039 – Kolumny AI summary w tabeli `contact` — migracja V065

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-006 (tabela `contact`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-089
**Epic:** EPIC-26 AI-Powered Conversation Summary

> **Uwaga (weryfikacja 2026-08-08):** migracja V065 się zastosowała i ticket jest formalnie
> ukończony, ale kolumny `ai_summary`/`ai_summary_model`/`ai_summary_generated_at` opisane niżej
> zostały od tego czasu **usunięte** z `contact` przez późniejszą (nieopisaną w żadnym tickecie)
> migrację `V068__extract_ai_summary_to_own_table.sql`, która przeniosła te dane do dedykowanej
> tabeli `contact_ai_summary`. Obecny kod (`AiSummaryServiceImpl`, `ContactAiSummaryRepository`)
> używa wyłącznie `contact_ai_summary`. DDL poniżej opisuje architekturę, która już nie
> odzwierciedla stanu bazy — zostawione dla kontekstu historycznego, nie do wdrożenia od nowa.

**Kontekst:**
Wygenerowane podsumowanie AI musi być trwale powiązane z kontaktem. Przechowujemy: treść podsumowania, nazwę modelu który je wygenerował, czas generowania — do celów audytowych i raportowania. Tabela `contact` jest partycjonowana, `ADD COLUMN` propaguje automatycznie na wszystkie partycje.

**DDL migracji (`V065__add_ai_summary_to_contact.sql`):**

```sql
-- =============================================================================
-- V065__add_ai_summary_to_contact.sql
-- DB-039: Pola podsumowania AI w tabeli contact.
-- Tabela jest partycjonowana — ADD COLUMN propaguje automatycznie.
-- =============================================================================

ALTER TABLE contact
    ADD COLUMN IF NOT EXISTS ai_summary                TEXT,
    ADD COLUMN IF NOT EXISTS ai_summary_model          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS ai_summary_generated_at   TIMESTAMPTZ;

COMMENT ON COLUMN contact.ai_summary IS
    'Podsumowanie kontaktu wygenerowane przez AI. NULL jeśli agent nie zlecił generowania.';
COMMENT ON COLUMN contact.ai_summary_model IS
    'Nazwa modelu AI który wygenerował podsumowanie, np. claude-opus-4-7, gpt-4o. Null gdy brak podsumowania.';
COMMENT ON COLUMN contact.ai_summary_generated_at IS
    'Timestamp wygenerowania podsumowania przez AI. NULL gdy brak podsumowania.';
```

**Kryteria akceptacji:**
- [x] Migracja V065 aplikuje się bez błędów na dev i test
- [x] Trzy kolumny nullable: `ai_summary TEXT`, `ai_summary_model VARCHAR(100)`, `ai_summary_generated_at TIMESTAMPTZ`
- [x] Istniejące wiersze nie są naruszone (NULL backfill)
- [x] Partycjonowanie: `ADD COLUMN` aplikuje się na wszystkich partycjach (weryfikacja przez `SELECT count(*) FROM pg_attribute...`)
- [x] Komentarze kolumn dokumentują semantykę

---

## EPIC-27: Własne dyspozycje per kampania i kolejka

### DB-040 – Tabela `custom_disposition`: konfiguracja dyspozycji po kontakcie per kampania i per kolejka — migracja V069

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-004 (tabela `campaign`), DB-005 (tabela `queue`), DB-002 (tabela `tenant`)
**Status:** ✅ Zrobione
**Blokuje:** BE-092
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Kontekst:**
Obecne dyspozycje (SALE, NO_INTEREST, CALLBACK, itp.) są zakodowane statycznie na froncie. Tabela `campaign` posiada co prawda kolumnę `disposition_codes JSONB`, ale przechowuje tylko kody bez pełnych metadanych (etykieta, ton, kolejność). Nowa tabela `custom_disposition` umożliwia supervisorowi konfigurację własnych zestawów dyspozycji dla konkretnej kampanii lub kolejki — gdy skonfigurowane, zastępują one całkowicie dyspozycje systemowe.

Zasada zakresu (scope): wiersz należy **albo** do kampanii, **albo** do kolejki — nigdy do obu naraz. Egzekwowane przez `CHECK` constraint. Unikalność kodu per zakres egzekwowana przez dwa partial unique indexy (NULL nie jest równy NULL w UNIQUE constraint PostgreSQL).

**DDL migracji (`V069__create_custom_disposition.sql`):**

```sql
-- =============================================================================
-- V069__create_custom_disposition.sql
-- DB-040: Własne dyspozycje po kontakcie per kampania lub kolejka.
-- Gdy skonfigurowane dla danego zakresu, zastępują dyspozycje systemowe.
-- Zakres: dokładnie jeden z campaign_id / queue_id musi być ustawiony.
-- =============================================================================

CREATE TABLE custom_disposition (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenant(id)    ON DELETE CASCADE,

    -- Zakres: dokładnie jeden z poniższych musi być NOT NULL
    campaign_id      UUID        REFERENCES campaign(campaign_id)  ON DELETE CASCADE,
    queue_id         UUID        REFERENCES queue(id)              ON DELETE CASCADE,

    -- Definicja dyspozycji
    disposition_code VARCHAR(50) NOT NULL,
    label            VARCHAR(100) NOT NULL,
    tone             VARCHAR(20) NOT NULL DEFAULT 'neutral',
    ordinal          INT         NOT NULL DEFAULT 0,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_custom_disposition_scope CHECK (
        (campaign_id IS NOT NULL AND queue_id IS NULL) OR
        (campaign_id IS NULL     AND queue_id IS NOT NULL)
    ),
    CONSTRAINT chk_custom_disposition_tone CHECK (
        tone IN ('positive', 'negative', 'neutral', 'warning')
    )
);

-- Partial unique indexes — obsługa NULL w UNIQUE dla PostgreSQL
CREATE UNIQUE INDEX uq_custom_disposition_code_per_campaign
    ON custom_disposition (tenant_id, campaign_id, disposition_code)
    WHERE campaign_id IS NOT NULL;

CREATE UNIQUE INDEX uq_custom_disposition_code_per_queue
    ON custom_disposition (tenant_id, queue_id, disposition_code)
    WHERE queue_id IS NOT NULL;

-- Indeksy wyszukiwania
CREATE INDEX idx_custom_disposition_campaign
    ON custom_disposition (tenant_id, campaign_id, ordinal)
    WHERE campaign_id IS NOT NULL AND is_active = TRUE;

CREATE INDEX idx_custom_disposition_queue
    ON custom_disposition (tenant_id, queue_id, ordinal)
    WHERE queue_id IS NOT NULL AND is_active = TRUE;

ALTER TABLE custom_disposition ENABLE ROW LEVEL SECURITY;
CREATE POLICY custom_disposition_isolation ON custom_disposition
    USING (tenant_id = current_setting('app.tenant_id', TRUE)::UUID);

COMMENT ON TABLE custom_disposition IS
    'Własne dyspozycje po kontakcie dla kampanii lub kolejki. Gdy istnieją dla danego zakresu, zastępują dyspozycje systemowe.';
COMMENT ON COLUMN custom_disposition.disposition_code IS
    'Unikalny kod dyspozycji w obrębie zakresu (kampania lub kolejka). Maks. 50 znaków.';
COMMENT ON COLUMN custom_disposition.tone IS
    'Ton wizualny w UI: positive (zielony), negative (czerwony), neutral (szary), warning (pomarańczowy).';
COMMENT ON COLUMN custom_disposition.ordinal IS
    'Kolejność wyświetlania na liście. Rosnąco, domyślnie 0.';
COMMENT ON COLUMN custom_disposition.campaign_id IS
    'Jeśli ustawiony: dyspozycja należy do tej kampanii. Wzajemnie wyklucza się z queue_id.';
COMMENT ON COLUMN custom_disposition.queue_id IS
    'Jeśli ustawiony: dyspozycja należy do tej kolejki. Wzajemnie wyklucza się z campaign_id.';
```

**Kryteria akceptacji:**
- [ ] Migracja V069 aplikuje się bez błędów na dev i test
- [ ] `chk_custom_disposition_scope` — INSERT z `campaign_id IS NOT NULL AND queue_id IS NOT NULL` odrzucony
- [ ] `chk_custom_disposition_scope` — INSERT z `campaign_id IS NULL AND queue_id IS NULL` odrzucony
- [ ] Partial unique indexes — duplikat `disposition_code` per kampania odrzucony; ten sam kod dla różnych kampanii dozwolony
- [ ] RLS policy izoluje dane między tenantami
- [ ] `chk_custom_disposition_tone` — wartość spoza listy odrzucona
- [ ] Komentarze na tabeli i kluczowych kolumnach

---

### DB-041 – Tabele `disposition_set` i `disposition_set_item`: zestawy dyspozycji wielokrotnego użytku — migracja V071

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-040 (tabela `custom_disposition`), DB-002 (tabela `tenant`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-28
**Blokuje:** BE-095
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Kontekst:**
Zestawy dyspozycji (`disposition_set`) to nazwane szablony wielokrotnego użytku. Supervisor definiuje zestaw raz, a następnie przypisuje go do wielu kampanii lub kolejek — elementy zestawu są wtedy **kopiowane** (snapshot) do tabeli `custom_disposition` dla danego zakresu. Po skopiowaniu dyspozycje kampanii/kolejki są niezależne od zestawu i mogą być edytowane ręcznie.

**DDL migracji (`V071__create_disposition_set.sql`):**

```sql
-- =============================================================================
-- V071__create_disposition_set.sql
-- DB-041: Zestawy dyspozycji wielokrotnego użytku (szablony).
-- Przypisanie zestawu do kampanii/kolejki kopiuje elementy (snapshot).
-- =============================================================================

CREATE TABLE disposition_set (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_disposition_set_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE disposition_set_item (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    set_id           UUID        NOT NULL REFERENCES disposition_set(id) ON DELETE CASCADE,
    tenant_id        UUID        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    disposition_code VARCHAR(50)  NOT NULL,
    label            VARCHAR(100) NOT NULL,
    tone             VARCHAR(20)  NOT NULL DEFAULT 'neutral',
    ordinal          INT          NOT NULL DEFAULT 0,

    CONSTRAINT uq_disposition_set_item_code UNIQUE (set_id, disposition_code),
    CONSTRAINT chk_disposition_set_item_tone CHECK (
        tone IN ('positive', 'negative', 'neutral', 'warning')
    )
);

-- Indeksy
CREATE INDEX idx_disposition_set_tenant
    ON disposition_set (tenant_id, name);

CREATE INDEX idx_disposition_set_item_set
    ON disposition_set_item (set_id, ordinal);

-- RLS
ALTER TABLE disposition_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE disposition_set FORCE ROW LEVEL SECURITY;
CREATE POLICY disposition_set_isolation ON disposition_set
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

ALTER TABLE disposition_set_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE disposition_set_item FORCE ROW LEVEL SECURITY;
CREATE POLICY disposition_set_item_isolation ON disposition_set_item
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

COMMENT ON TABLE disposition_set IS
    'Nazwane zestawy dyspozycji wielokrotnego użytku. Przypisanie do kampanii/kolejki kopiuje elementy (snapshot).';
COMMENT ON TABLE disposition_set_item IS
    'Elementy zestawu dyspozycji. Kopiowane do custom_disposition przy przypisaniu zestawu.';
COMMENT ON COLUMN disposition_set_item.disposition_code IS
    'Unikalny kod w obrębie zestawu. Maks. 50 znaków, tylko A-Z, 0-9, _.';
```

**Kryteria akceptacji:**
- [ ] Migracja V071 aplikuje się bez błędów
- [ ] `uq_disposition_set_tenant_name` — duplikat nazwy zestawu per tenant odrzucony
- [ ] `uq_disposition_set_item_code` — duplikat kodu per zestaw odrzucony
- [ ] `chk_disposition_set_item_tone` — wartość spoza listy odrzucona
- [ ] RLS + FORCE RLS na obu tabelach — izolacja między tenantami
- [ ] CASCADE DELETE: usunięcie zestawu usuwa jego elementy

---

## MODUL: Per-Tenant Plugin (Extension) System (EPIC-28)

> Źródło architektury: `ARCHITECTURE.md` §11 (ADR-09…ADR-13, RT-09…RT-14). `plugin`/`plugin_version`
> to katalog **globalny** (bez `tenant_id`, bez RLS — ADR-13); pozostałe trzy tabele są
> tenant-scoped z RLS, zaczynając od `tenant_plugin_installation`.

### DB-042 – Tabele `plugin` i `plugin_version`: globalny katalog pluginów (bez RLS) — migracja V074

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-002 (tabela `tenant` — FK z `app_user` poniżej), DB-003 (tabela `app_user` — `uploaded_by_user_id`)
**Status:** ✅ Zrobione (2026-06-20)
**Blokuje:** DB-043
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Kontekst:**
`plugin` i `plugin_version` to katalog **globalny** — definicja pluginu (jaki kod, jaka wersja, co deklaruje) jest współdzieloną metadaną infrastrukturalną, nie danymi tenanta. Ten sam `plugin_key` może być instalowany niezależnie przez wielu tenantów (zob. DB-043). Wersje są niemutowalne po `VALIDATED` — analogicznie do reguły "nigdy nie edytuj zastosowanej migracji Flyway" (CLAUDE.md): nowa wersja JAR-a to zawsze nowy wiersz `plugin_version`, nigdy edycja istniejącego.

**DDL migracji (`V074__create_plugin_catalog.sql`):**

```sql
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
    uploaded_by_user_id UUID        REFERENCES app_user(id) ON DELETE SET NULL,
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
```

**Kryteria akceptacji:**
- [x] Migracja V074 aplikuje się bez błędów na dev i test
- [x] `plugin.plugin_key` — UNIQUE, brak `tenant_id`, brak RLS (świadomie, katalog globalny — ADR-13)
- [x] `uq_plugin_version_plugin_version` — duplikat `(plugin_id, version)` odrzucony
- [x] `chk_plugin_version_status` — wartość spoza listy odrzucona
- [x] FK `uploaded_by_user_id` → `app_user(user_id) ON DELETE SET NULL` (audyt przetrwa usunięcie użytkownika)
- [x] Komentarze na tabelach i kluczowych kolumnach wyjaśniające decyzję "bez RLS"

**Uwaga implementacyjna:** DDL z ticketu zawierał `REFERENCES app_user(id)` — błędne, PK tej tabeli to `app_user.user_id` (konwencja `{tabela}_id` w tym projekcie, nie `id`). Poprawione w migracji na `REFERENCES app_user(user_id)`. Poza tym DDL przepisany 1:1.

---

### DB-043 – Tabela `tenant_plugin_installation`: instalacja pluginu per tenant (RLS) — migracja V075

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-042 (tabela `plugin_version`), DB-002 (tabela `tenant`), DB-003 (tabela `app_user`)
**Status:** ✅ Zrobione
**Blokuje:** DB-044, DB-045
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Kontekst:**
Pierwsza tabela tenant-scoped w epiku — od niej zaczyna się RLS (ADR-13). Jedna instalacja = jeden tenant + jedna konkretna wersja pluginu. Upgrade nie edytuje wiersza — tworzy **nowy** (nowy `plugin_version_id`), stary zostaje z `enabled=false` jako mechanizm rollbacku (ARCHITECTURE.md §11.11): włączenie starego + wyłączenie nowego to natychmiastowy rollback bez redeployu. `installation_config` przechowuje sekrety tenanta (np. API key zewnętrznego CRM) — szyfrowane AES-256-GCM, ten sam wzorzec konwertera co `tenant_ai_config.api_key` (DB-038) i `tenant_twilio_config`.

**DDL migracji (`V075__create_tenant_plugin_installation.sql`):**

```sql
-- =============================================================================
-- V075__create_tenant_plugin_installation.sql
-- DB-043: Instalacja pluginu per tenant (EPIC-28). RLS od tej tabeli (ADR-13).
-- =============================================================================

CREATE TABLE tenant_plugin_installation (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                  UUID        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    plugin_version_id          UUID        NOT NULL REFERENCES plugin_version(id) ON DELETE RESTRICT,
    enabled                     BOOLEAN     NOT NULL DEFAULT FALSE,
    granted_permissions         JSONB       NOT NULL DEFAULT '[]'::JSONB,
    health_status               VARCHAR(20) NOT NULL DEFAULT 'HEALTHY',
    consecutive_failure_count   INT         NOT NULL DEFAULT 0,
    installation_config         JSONB,
    installed_by_user_id        UUID        REFERENCES app_user(id) ON DELETE SET NULL,
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
```

**Kryteria akceptacji:**
- [x] Migracja V075 aplikuje się bez błędów
- [x] `uq_tenant_plugin_installation_version` — duplikat `(tenant_id, plugin_version_id)` odrzucony
- [x] `chk_tenant_plugin_installation_health` — wartość spoza listy odrzucona
- [x] RLS + FORCE RLS — izolacja danych między tenantami
- [x] FK `plugin_version_id ON DELETE RESTRICT` — nie można usunąć wersji pluginu, która ma aktywne instalacje
- [x] Kolumna `installation_config` gotowa na szyfrowany JSONB (konwerter dodany w warstwie BE, nie w migracji)

**Uwaga implementacyjna:** DDL z ticketu zawierał DWA błędne FK (nie tylko jeden zgłoszony w handoffie z DB-042):
1. `installed_by_user_id UUID REFERENCES app_user(id)` — PK tej tabeli to `app_user.user_id`. Poprawione na `REFERENCES app_user(user_id)`.
2. `tenant_id UUID REFERENCES tenant(id)` — PK tej tabeli to `tenant.tenant_id` (tabela starsza, konwencja `{tabela}_id`). Poprawione na `REFERENCES tenant(tenant_id)`. **Ten błąd nie był zgłoszony w kontekście handoffu — wykryty dodatkową weryfikacją `\d tenant` przed pisaniem FK.**

FK do `plugin_version(id)` był poprawny bez zmian (tabela nowa, od V069+, PK=`id`).

Weryfikacja manualna w transakcji z ROLLBACK (baza dev pozostała czysta):
- RLS: `relrowsecurity=t`, `relforcerowsecurity=t` potwierdzone w `pg_class`.
- **Ważne odkrycie:** rola `ccapp` (używana w psql przez to środowisko dev) ma `rolbypassrls=true` — RLS pod tą rolą NIE jest egzekwowane, niezależnie od FORCE ROW LEVEL SECURITY (to oczekiwane zachowanie Postgresa: BYPASSRLS ignoruje również FORCE). Potwierdzone że to nie jest błąd migracji — ten sam efekt występuje na już zaakceptowanej tabeli `custom_disposition` (V070) pod rolą `ccapp`. Test izolacji trzeba wykonać pod rolą `app_user` (`SET ROLE app_user;` — `rolbypassrls=false`, `Cannot login` więc używana tylko przez `SET ROLE` z `ccapp` lub przez connection pooling backendu): pod tą rolą izolacja działa poprawnie (tenant B widzi 0 wierszy tenanta A, `WITH CHECK` blokuje cross-tenant insert). **Zanotowane w pamięci agenta — przy każdej kolejnej tabeli RLS w tym epiku testować pod `SET ROLE app_user`, nie pod gołym `ccapp`.**
- Duplikat `(tenant_id, plugin_version_id)` odrzucony przez `uq_tenant_plugin_installation_version`.
- `health_status='BOGUS_STATUS'` odrzucony przez `chk_tenant_plugin_installation_health`.
- Próba `DELETE` z `plugin_version` mającej aktywną instalację odrzucona przez FK `ON DELETE RESTRICT`.

Sposób aplikacji na dev: identyczny jak DB-042 (port 5432 niepublikowany na hosta, Flyway odpalony wewnątrz `cc-backend` przez ręcznie skompilowany `RunFlyway.class` + jary z `.m2` w `/tmp/flyway-run2`, bo `/tmp/flyway-run` z poprzedniej sesji było read-only dla zapisu nowych plików; `javac` niedostępny w `cc-backend` (tylko JRE) — kompilacja `RunFlyway.java` wykonana lokalnie na hoście (ma JDK 21) z jarami skopiowanymi z kontenera, potem `.class` skopiowany z powrotem).

**Drugorzędne odkrycie do DB-044/DB-045:** DDL tych ticketów (przeczytane podczas analizy, NIE wykonane) zawiera `REFERENCES tenant(id)` w obu (linia z `tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE` w V076 i V077) — ten SAM błąd co w DB-043. Trzeba poprawić na `tenant(tenant_id)` przy realizacji tych ticketów. Pozostałe FK w DB-044 (`tenant_plugin_installation(id)` — OK, nowa tabela) i DB-045 (`tenant_plugin_installation(id)` — OK) wydają się poprawne, ale wymagają weryfikacji `\d` w danym momencie przed implementacją.

---

### DB-044 – Tabela `tenant_plugin_extension_binding`: bindingi punktów rozszerzeń (RLS) — migracja V076

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-043 (tabela `tenant_plugin_installation`)
**Status:** ✅ Zrobione (2026-06-20)
**Blokuje:** DB-045
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Kontekst:**
Każda instalacja deklaruje w manifeście, do których z pięciu stałych punktów rozszerzeń się podłącza (`PRE_CONTACT_CONNECT`, `POST_CONTACT_END`, `CUSTOMER_SYNC`, `DISPOSITION_SET`, `MANUAL_ACTION` — ARCHITECTURE.md §11.5). Ta tabela materializuje te bindingi z trybem wywołania (`BLOCKING`/`ASYNC`) i timeoutem, żeby `PluginRegistry` (BE-102) mógł je odpytywać bez parsowania manifestu przy każdym wywołaniu.

**DDL migracji (`V076__create_tenant_plugin_extension_binding.sql`):**

```sql
-- =============================================================================
-- V076__create_tenant_plugin_extension_binding.sql
-- DB-044: Bindingi punktów rozszerzeń per instalacja (EPIC-28).
-- =============================================================================

CREATE TABLE tenant_plugin_extension_binding (
    id                              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_plugin_installation_id  UUID        NOT NULL REFERENCES tenant_plugin_installation(id) ON DELETE CASCADE,
    tenant_id                       UUID        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
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
```

**Kryteria akceptacji:**
- [x] Migracja V076 aplikuje się bez błędów
- [x] `uq_tenant_plugin_extension_binding` — duplikat `(installation_id, extension_point)` odrzucony
- [x] `chk_tenant_plugin_extension_binding_point` — wartość spoza 5 punktów rozszerzeń odrzucona
- [x] `chk_tenant_plugin_extension_binding_mode` — tylko `BLOCKING`/`ASYNC`
- [x] RLS + FORCE RLS — izolacja między tenantami
- [x] Indeks `(tenant_id, extension_point)` — sprawdzony plan zapytania (`EXPLAIN`) dla lookupu `PluginRegistry`

**Notatka z realizacji (2026-06-20):** DDL miał ten sam błędny FK co DB-043 — `REFERENCES tenant(id)` poprawiony na `REFERENCES tenant(tenant_id)` (potwierdzone `\d tenant`: PK = `tenant_id`). Pozostałe FK (`tenant_plugin_installation(id)`) były poprawne — ta tabela ma PK `id` (konwencja od V069+). Zweryfikowano manualnie pod `SET ROLE app_user` w transakcji z ROLLBACK: duplikat UNIQUE odrzucony, oba CHECK (`extension_point`, `invocation_mode`) odrzucone, granice `timeout_ms` (0 i 60001 odrzucone, 60000 przechodzi), RLS USING (tenant B nie widzi wierszy tenant A) i WITH CHECK (cross-tenant insert odrzucony) poprawne, `EXPLAIN` potwierdza `Index Scan using idx_tenant_plugin_extension_binding_lookup`. DDL DB-045 (V077, kolejny w kolejce) ma ten sam błędny FK do `tenant(id)` — do poprawy przy jego realizacji.

---

### DB-045 – Tabela `plugin_invocation_log`: audit log wywołań pluginów (RLS, partycjonowana) — migracja V077

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-043 (tabela `tenant_plugin_installation`)
**Status:** ✅ Zrobione
**Blokuje:** —
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Kontekst:**
Log każdego wywołania pluginu (sukces, błąd, timeout, circuit-open skip) — odrębny od `audit_log`, bo wolumen i kształt danych (latencja per-call, snapshoty payloadu) różnią się materialnie od administracyjnych zdarzeń audytowych (ARCHITECTURE.md §11.12). RANGE-partycjonowana miesięcznie po `invoked_at`, identyczny wzorzec co `audit_log`/`contact` (§4.2-4.3). `related_contact_id` ma `ON DELETE SET NULL` — ten sam GDPR-safe wzorzec co `contact.agent_id`/`contact.customer_id`.

**DDL migracji (`V077__create_plugin_invocation_log.sql`):**

```sql
-- =============================================================================
-- V077__create_plugin_invocation_log.sql
-- DB-045: Audit log wywołań pluginów (EPIC-28). RANGE-partycjonowana miesięcznie.
-- =============================================================================

CREATE TABLE plugin_invocation_log (
    id                              UUID        NOT NULL DEFAULT gen_random_uuid(),
    invoked_at                       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id                       UUID        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    tenant_plugin_installation_id   UUID        REFERENCES tenant_plugin_installation(id) ON DELETE SET NULL,
    extension_point                 VARCHAR(30) NOT NULL,
    related_contact_id              UUID,
    status                          VARCHAR(20) NOT NULL,
    duration_ms                     INT,
    error_summary                   TEXT,
    request_payload_redacted        JSONB,

    PRIMARY KEY (id, invoked_at),
    CONSTRAINT chk_plugin_invocation_log_status CHECK (
        status IN ('SUCCESS', 'FAILED', 'TIMED_OUT', 'CIRCUIT_OPEN', 'SKIPPED_DISABLED')
    )
) PARTITION BY RANGE (invoked_at);

-- Partycje iniciálne (wzorzec audit_log/contact, §4.2-4.3) — kolejne tworzone przez job
-- analogiczny do istniejącego mechanizmu partycjonowania (sprawdzić istniejący
-- PartitionMaintenanceJob/migration helper przed implementacją, nie duplikować mechanizmu)
CREATE TABLE plugin_invocation_log_default PARTITION OF plugin_invocation_log DEFAULT;

-- Indeksy
CREATE INDEX idx_plugin_invocation_log_installation
    ON plugin_invocation_log (tenant_plugin_installation_id, invoked_at DESC);

CREATE INDEX idx_plugin_invocation_log_contact
    ON plugin_invocation_log (related_contact_id)
    WHERE related_contact_id IS NOT NULL;

CREATE INDEX idx_plugin_invocation_log_status
    ON plugin_invocation_log (tenant_id, status, invoked_at DESC)
    WHERE status IN ('FAILED', 'TIMED_OUT', 'CIRCUIT_OPEN');

-- RLS
ALTER TABLE plugin_invocation_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_invocation_log FORCE ROW LEVEL SECURITY;
CREATE POLICY plugin_invocation_log_isolation ON plugin_invocation_log
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

COMMENT ON TABLE plugin_invocation_log IS
    'Log każdego wywołania pluginu (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED). Odrębny od audit_log — inny wolumen/kształt (ARCHITECTURE.md §11.12). RANGE-partycjonowana po invoked_at.';
COMMENT ON COLUMN plugin_invocation_log.related_contact_id IS
    'ON DELETE SET NULL — historia wywołań przetrwa usunięcie kontaktu (GDPR-safe FK, wzorzec contact.agent_id/customer_id).';
COMMENT ON COLUMN plugin_invocation_log.request_payload_redacted IS
    'Snapshot payloadu z usuniętym PII, do debugowania — nigdy surowe dane klienta.';
```

**Kryteria akceptacji:**
- [x] Migracja V077 aplikuje się bez błędów
- [x] Tabela RANGE-partycjonowana po `invoked_at`; partycja `DEFAULT` istnieje od startu
- [x] `chk_plugin_invocation_log_status` — wartość spoza 5 statusów odrzucona
- [x] RLS + FORCE RLS — izolacja między tenantami (RLS musi działać poprawnie na tabeli partycjonowanej — zweryfikować na partycji `DEFAULT` i co najmniej jednej partycji miesięcznej utworzonej ręcznie w teście)
- [x] FK `tenant_plugin_installation_id ON DELETE SET NULL` — log przetrwa uninstall (ARCHITECTURE.md §11.11)
- [x] FK `tenant_id ON DELETE CASCADE` — log usuwany przy usunięciu tenanta (zgodnie z resztą schematu)
- [x] Sprawdzić istniejący mechanizm tworzenia kolejnych partycji miesięcznych (np. job/migration helper używany przez `audit_log`/`contact`) i podłączyć tę tabelę do tego samego mechanizmu, zamiast tworzyć nowy

**Notatka z realizacji (2026-06-20):** DDL miał ten sam błędny FK co DB-043/DB-044 — `REFERENCES tenant(id)` poprawiony na `REFERENCES tenant(tenant_id)` (potwierdzone `\d tenant`: PK = `tenant_id`). FK do `tenant_plugin_installation(id)` było poprawne.

**Mechanizm partycjonowania — odpowiedź jednoznaczna: TAK, istnieje w pełni automatyczny mechanizm (nie tylko partycja DEFAULT + migracje ręczne).** Wzorzec ustalony w V004 (`audit_log`) i V007 (`contact`), oba reużyte 1:1 dla `plugin_invocation_log`:
- Funkcja `create_plugin_invocation_log_partition(year, month)` — idempotentna, analogiczna do `create_audit_log_partition`/`create_contact_partition`.
- Funkcja `drop_old_plugin_invocation_log_partitions(retention_months=24)` — retencja 24 miesiące, analogiczna do `drop_old_audit_log_partitions`.
- Funkcja `rotate_plugin_invocation_log_partitions()` — create na +1/+2 miesiące + drop starych, loguje do `cron_log`, aktualizuje `scheduled_job`.
- Nowy wpis w `scheduled_job`: `rotate_plugin_invocation_log_partitions`, cron `45 2 1 * *` (po `rotate_audit_log_partitions` o 02:30).
- `create_next_month_partitions()` (V014) rozszerzona przez `CREATE OR REPLACE` o `PERFORM create_plugin_invocation_log_partition(...)` — ten sam zbiorczy job teraz obsługuje 3 tabele (`audit_log`, `contact`, `plugin_invocation_log`).
- Partycje inicjalne: `2026_06`, `2026_07`, `2026_08` + `DEFAULT` (wzorzec: bieżący + 2 następne miesiące, jak w V004/V007).
- pg_cron w tym środowisku nie jest aktywne (sekcja 4 w V014 zakomentowana) — rotacja w praktyce wywoływana przez zewnętrzny scheduler aplikacyjny (Spring `@Scheduled`/Quartz) czytający `scheduled_job`, identycznie jak dla `audit_log`/`contact`. Nic nowego nie dodano w tym zakresie — `plugin_invocation_log` korzysta z tej samej (już istniejącej) infrastruktury.

**Weryfikacja manualna** (transakcja z `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` per test-case, `ROLLBACK` końcowy — baza dev pozostała czysta):
- CHECK `chk_plugin_invocation_log_status`: `BOGUS_STATUS` odrzucony, `SUCCESS` przechodzi.
- RLS pod `SET ROLE app_user` (nie `ccapp` — `rolbypassrls=true`, daje fałszywy wynik): tenant B nie widzi wierszy tenant A **przez tabelę nadrzędną** `plugin_invocation_log` — zarówno na partycji `2026_06` (bieżący miesiąc) jak i na partycji `2027_01` utworzonej ręcznie w trakcie testu. `WITH CHECK` odrzuca cross-tenant insert.
- FK `tenant_plugin_installation_id ON DELETE SET NULL`: log przetrwał z `tenant_plugin_installation_id = NULL` po usunięciu instalacji.
- FK `tenant_id ON DELETE CASCADE`: log usunięty kaskadowo po usunięciu tenanta.

**Odkrycie poboczne (właściwość PostgreSQL, NIE defekt migracji):** `pg_class.relrowsecurity`/`relforcerowsecurity` na partycjach potomnych są zawsze `f`, niezależnie od `ENABLE`/`FORCE ROW LEVEL SECURITY` na rodzicu i niezależnie od kolejności (partycja utworzona przed czy po `ENABLE RLS`) — zweryfikowane na izolowanym przykładzie (`rls_test_parent`/`rls_test_parent_p1`) oraz potwierdzone identyczne zachowanie na już produkcyjnej `contact`/`contact_2026_03`. Skutek: zapytanie **przez tabelę nadrzędną** (`SELECT ... FROM plugin_invocation_log`) poprawnie egzekwuje RLS na każdej partycji (w tym nowo utworzonej), ale zapytanie bezpośrednio po nazwie partycji potomnej (`SELECT ... FROM plugin_invocation_log_2027_01`) **omija RLS rodzica całkowicie** — udokumentowane zachowanie PostgreSQL (RLS policy jest własnością tabeli na której zdefiniowano `CREATE POLICY`, nie dziedziczy się jako wpis `pg_class` na partycje). Zweryfikowano `grep` po `backend/src/main/java/` — aplikacja nigdy nie odpytuje partycji po nazwie (zawsze przez encję JPA mapowaną na tabelę nadrzędną), więc to nie jest ryzyko w obecnym kodzie, ale ogólna zasada do zachowania na przyszłość: **nigdy nie pisać kodu aplikacyjnego/raportowego, który odpytuje `<table>_YYYY_MM` po nazwie bezpośrednio** — zawsze przez tabelę nadrzędną.

**Sposób aplikacji migracji:** artefakty z poprzednich sesji (V074-V076) przetrwały w kontenerze `cc-backend` (`/tmp/flyway-run2/RunFlyway.class` + jary) — wystarczyło `docker cp` nowego `V077__create_plugin_invocation_log.sql` do `cc-backend:/tmp/migrations/` i odpalić ponownie `java -cp ... RunFlyway` (bez rekompilacji).

---

## MODUL: Partycjonowanie i retencja danych z obsługi kontaktów (EPIC-29)

> Źródło: `DESIGN-data-retention-partitioning.md` (projekt zaakceptowany, 2026-08-08). Powiązane:
> `ARCHITECTURE.md` §4.1–4.3, §6.6, §8.6, §10.3 (RC-02); `PRD.md` §6.5 (NFR-RODO03).
> **Kontekst krytyczny (fundament epiku):** partycje `contact`/`audit_log` kończą się na
> `2026_05` — od czerwca 2026 dane trafiają do partycji `*_default` (fallback bez wydajnych
> indeksów partycjonowania, bez szybkiego `DROP PARTITION`). Przyczyna: `cron.schedule(...)`
> w V014 jest zakomentowany, `pg_cron` nie jest włączony w obrazie `postgres:16-alpine`, i
> żaden Java `@Scheduled` job tego nie wywołuje jako fallback. DB-052 (V088) + BE-114 naprawiają
> to jako fundament — bez tego liczenie/usuwanie danych retencji (BE-112/BE-113) operowałoby na
> błędnych założeniach o strukturze partycji. `audit_log` świadomie POZA zakresem per-tenant
> retencji tego epiku (log platformowy, retencja 24 mies. ustawiana przez SUPER_ADMIN) — tylko
> jego rotacja jest naprawiana w DB-052.

### DB-046 – Tabela `tenant_retention_policy`: konfiguracja polityk retencji per tenant — migracja V082

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-002 (tabela `tenant`), DB-003 (tabela `app_user` — `updated_by`)
**Status:** ✅ Ukończone
**Blokuje:** DB-047, BE-111
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Tabela konfiguracyjna — jeden wiersz per (tenant, kategoria danych). Cztery kategorie:
`CONTACT_INTERACTIONS`, `RECORDINGS`, `TRANSCRIPTS`, `CAMPAIGN_DATA` (`audit_log` świadomie
POZA zakresem — patrz nagłówek modułu). Wzorzec RLS identyczny do reszty projektu, ale
**UWAGA na nazwę GUC**: użyj `app.current_tenant_id` (poprawna nazwa ustawiana przez
`set_tenant_context()`, zgodnie z `TenantAwareRepository`) — NIE `app.tenant_id`, na który
omyłkowo trafiły migracje V059/V064/V067/V068 (naprawiane opcjonalnie w DB-054/V090). Ta
tabela ma być zbudowana od razu poprawnie, bez powtarzania tego błędu.

**DDL migracji (`V082__create_tenant_retention_policy.sql`):**
```sql
CREATE TABLE tenant_retention_policy (
    policy_id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id          UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category      VARCHAR(30) NOT NULL CHECK (data_category IN
                        ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    retention_months   INT NOT NULL CHECK (retention_months BETWEEN 1 AND 120),
    auto_purge_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by         UUID REFERENCES app_user(user_id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenant_retention_policy UNIQUE (tenant_id, data_category)
);

CREATE INDEX idx_tenant_retention_policy_tenant ON tenant_retention_policy (tenant_id);

ALTER TABLE tenant_retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_retention_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_retention_policy_isolation ON tenant_retention_policy
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE tenant_retention_policy IS
    'Konfiguracja retencji per tenant i kategoria danych. audit_log świadomie POZA zakresem — retencja platformowa, nie tenant-scoped.';
```

**Backfill dla tenantów już istniejących (ta sama migracja, po `CREATE TABLE`):**
Wstaw domyślne wiersze dla każdego istniejącego tenanta i każdej z 4 kategorii:
`CONTACT_INTERACTIONS`=60 mies., `CAMPAIGN_DATA`=60 mies., `RECORDINGS`/`TRANSCRIPTS`=wartość
odpowiadająca 90 dniom (zaokrąglona do miesięcy — kolumna jest w miesiącach, nie dniach;
udokumentuj zaokrąglenie w komentarzu SQL, `BE-111`/`BE-116` muszą liczyć w tej samej jednostce
konsekwentnie).

**⚠️ Uwaga krytyczna dla `RECORDINGS` — nie nadpisuj istniejącej personalizacji tenanta:**
kolumna `tenant.config->>'recording_retention_days'` **już istnieje i może być ustawiona
indywidualnie** przez część tenantów (edytowalne dziś przez `PATCH /api/tenants/{id}/config`,
`TenantServiceImpl` ok. linii 526, domyślnie 90). Backfill dla kategorii `RECORDINGS` **musi
czytać wartość z `tenant.config->>'recording_retention_days'` per tenant** (fallback do 90 dni
gdy brak), NIE wstawiać płaskiego domyślnego `3` dla wszystkich — inaczej migracja po cichu
nadpisze już skonfigurowaną przez klienta retencję nagrań. Przelicz dni na miesiące świadomie
(np. `CEIL(dni / 30.0)`) i udokumentuj ograniczenie precyzji w komentarzu kolumny, żeby BE-111/
BE-116 nie musiały zgadywać jednostki.

**Kryteria akceptacji:**
- [x] Migracja V082 aplikuje się bez błędów na dev i test
- [x] `uq_tenant_retention_policy` — duplikat `(tenant_id, data_category)` odrzucony
- [x] CHECK na `data_category` — wartość spoza 4 kategorii odrzucona
- [x] `retention_months` — wartości poza `[1,120]` odrzucone
- [x] RLS + FORCE RLS z poprawnym GUC `app.current_tenant_id` — zweryfikowane pod `SET ROLE app_user` (NIE pod `ccapp` — `rolbypassrls=true`, patrz notatka z DB-043)
- [x] Backfill: każdy istniejący tenant ma dokładnie 4 wiersze (jeden per kategoria) po migracji
- [x] Backfill `RECORDINGS`: wartość per tenant odzwierciedla dotychczasowe `tenant.config->>'recording_retention_days'`, nie płaski default — zweryfikowane na tenancie z niestandardową wartością (jeśli istnieje w danych dev/seed) lub testem symulującym taki przypadek

---

### DB-047 – Tabela `tenant_retention_pending_summary`: cache „danych do usunięcia” — migracja V083

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-046 (kategorie muszą być spójne z `tenant_retention_policy`)
**Status:** ✅ Ukończone
**Blokuje:** BE-112
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Cache zapotrzebowania na usuwanie, żeby dashboard admina (FE-105) czytał gotowy, tani wiersz
zamiast liczyć `COUNT(*)` na żądanie. Wypełniana przez `RetentionEvaluationJob` (BE-112, upsert).
PK złożony `(tenant_id, data_category)` — brak osobnego surogatu, to czysty cache 1:1 z kategorią.

**DDL migracji (`V083__create_tenant_retention_pending_summary.sql`):**
```sql
CREATE TABLE tenant_retention_pending_summary (
    tenant_id              UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category          VARCHAR(30) NOT NULL CHECK (data_category IN
                            ('CONTACT_INTERACTIONS','RECORDINGS','TRANSCRIPTS','CAMPAIGN_DATA')),
    eligible_row_count     BIGINT NOT NULL DEFAULT 0,
    oldest_eligible_period DATE,
    newest_eligible_period DATE,
    computed_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, data_category)
);

ALTER TABLE tenant_retention_pending_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_retention_pending_summary FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_retention_pending_summary_isolation ON tenant_retention_pending_summary
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

COMMENT ON TABLE tenant_retention_pending_summary IS
    'Cache policzony przez RetentionEvaluationJob (BE-112) — dashboard admina (FE-105) czyta ten wiersz zamiast liczyć COUNT(*) na żywo.';
```

**Kryteria akceptacji:**
- [x] Migracja V083 aplikuje się bez błędów
- [x] PK złożony `(tenant_id, data_category)` — upsert (`INSERT ... ON CONFLICT DO UPDATE`) możliwy bez dodatkowego unique constraint
- [x] RLS + FORCE RLS z `app.current_tenant_id`, zweryfikowane pod `SET ROLE app_user`
- [x] Brak wiersza dla tenanta/kategorii = interpretowane przez BE/FE jako „jeszcze nie policzone” (nie jako „zero do usunięcia”) — udokumentowane w komentarzu tabeli

---

### DB-048 – Tabela `retention_purge_log`: audyt operacji usuwania — migracja V084

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-002, DB-003
**Status:** ✅ Ukończone
**Blokuje:** BE-113
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Historia operacji usuwania (manualnych i automatycznych), odrębna od generycznego `audit_log`
— potrzebne ustrukturyzowane liczby (`rows_deleted`, `status`) do UI historii (FE-107), nie
tylko tekstowy wpis audytowy. Każda operacja purge zapisuje TU (RUNNING→COMPLETED/FAILED)
ORAZ w `audit_log` (`entity_type='RETENTION_PURGE'`) dla spójności z istniejącym mechanizmem
audytu — podwójny zapis to świadoma decyzja, nie duplikacja do wyeliminowania.

**DDL migracji (`V084__create_retention_purge_log.sql`):**
```sql
CREATE TABLE retention_purge_log (
    purge_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      UUID NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    data_category  VARCHAR(30) NOT NULL,
    triggered_by   UUID REFERENCES app_user(user_id),
    trigger_type   VARCHAR(10) NOT NULL CHECK (trigger_type IN ('MANUAL','AUTO')),
    cutoff_date    DATE NOT NULL,
    rows_deleted   BIGINT,
    status         VARCHAR(15) NOT NULL DEFAULT 'RUNNING'
                   CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMPTZ,
    error_message  TEXT
);
CREATE INDEX idx_retention_purge_log_tenant ON retention_purge_log (tenant_id, started_at DESC);

ALTER TABLE retention_purge_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_purge_log FORCE ROW LEVEL SECURITY;
CREATE POLICY retention_purge_log_isolation ON retention_purge_log
    USING     (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);
```

**Kryteria akceptacji:**
- [x] Migracja V084 aplikuje się bez błędów
- [x] CHECK na `trigger_type`/`status` odrzuca wartości spoza enum
- [x] `triggered_by` NULL dopuszczalny (auto-purge = system, brak użytkownika)
- [x] RLS + FORCE RLS zweryfikowane pod `SET ROLE app_user`
- [x] Indeks `(tenant_id, started_at DESC)` pokrywa zapytanie historii (FE-107, sortowanie malejąco po dacie)

---

### DB-049 – Partycjonowanie tabeli `contact_event` (online, bez utraty danych) — migracja V085

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** DB-035 (tabela `contact_event` istnieje, V059)
**Status:** ✅ Ukończone
**Blokuje:** DB-052, DB-053, BE-117
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
`contact_event` dziś jest zwykłą (niepartycjonowaną) tabelą powiązaną logicznie z `contact`.
Staje się RANGE-partycjonowana po `started_at`, wzorcem identycznym do `plugin_invocation_log`
(V077, najświeższy przykład w repo) — **NIE** wzorcem generycznym z dokumentu projektowego
wprost (ten pomija RLS/indeksy w opisie kroków — patrz uwaga niżej).

**Wzorzec migracji online (kroki z dokumentu projektowego + dopełnienie tam pominięte):**
1. `CREATE TABLE contact_event_new (... identyczne kolumny + trigger `fn_contact_event_on_update` ..., CONSTRAINT pk_contact_event_new PRIMARY KEY (event_id, started_at)) PARTITION BY RANGE (started_at);`
2. Utworzenie partycji dla istniejącego zakresu danych + bieżący/2 kolejne miesiące + partycja `DEFAULT`
3. `INSERT INTO contact_event_new SELECT * FROM contact_event;` — w transakcji lub batchami przy dużym wolumenie (sprawdź `COUNT(*)` przed decyzją o strategii)
4. `ALTER TABLE contact_event RENAME TO contact_event_old; ALTER TABLE contact_event_new RENAME TO contact_event;`
5. **Odtworzenie na nowej tabeli (pominięte w skróconym opisie dokumentu projektowego, ale wymagane — nic nie jest dziedziczone przy `RENAME`):** indeksy (`idx_contact_event_contact`, `idx_contact_event_tenant`), trigger `trg_contact_event_on_update`, RLS (`ENABLE`+`FORCE`+`CREATE POLICY`) i `COMMENT ON`.
   **Świadoma decyzja:** odtwórz RLS z **tą samą (dziś błędną) nazwą GUC `app.tenant_id`**,
   identycznie jak w oryginalnej V059 — NIE napraw jej tutaj. Naprawa GUC dla tej tabeli jest
   wydzielona do DB-054 (V090, opcjonalny bonus-fix dotyczący 4 tabel naraz) — łączenie obu
   zmian w jednej migracji utrudniłoby ewentualne wycofanie/pominięcie V090.
6. `DROP TABLE contact_event_old;` po weryfikacji (liczba wierszy się zgadza)

**Konsekwencja dla warstwy Java:** `ContactEvent` (dziś proste `@Id`) przechodzi na `@IdClass`
z kluczem złożonym `(eventId, startedAt)` — realizowane w BE-117, NIE w tym tickecie (ten
ticket to czysta migracja SQL).

**Kryteria akceptacji:**
- [x] Migracja V085 aplikuje się bez błędów na dev i test, na bazie z istniejącymi danymi `contact_event`
- [x] Liczba wierszy w `contact_event` po migracji == liczba wierszy przed migracją (zero utraty danych)
- [x] Tabela partycjonowana RANGE po `started_at`; PK złożony `(event_id, started_at)`
- [x] Indeksy `idx_contact_event_contact`, `idx_contact_event_tenant` odtworzone na nowej tabeli
- [x] Trigger `trg_contact_event_on_update` (obliczanie `duration_seconds`) odtworzony i działający
- [x] RLS + FORCE RLS odtworzone (z niezmienioną, dziś błędną nazwą GUC `app.tenant_id` — świadomie, patrz Kontekst)
- [x] Partycja `DEFAULT` istnieje od startu
- [x] `contact_event_old` usunięta dopiero po jawnej weryfikacji liczby wierszy

**Wykonanie (2026-08-09):** Migracja `V085__partition_contact_event.sql` zaaplikowana i zweryfikowana
na dev (`ccapp`/`contact_center`, `cc-postgres`). Liczba wierszy przed = po = **857** (weryfikacja
programowa blokiem `DO $$ ... RAISE EXCEPTION ...$$` wewnątrz samej migracji — przy niezgodności
cała migracja robi ROLLBACK, nic nie trafia do `contact_event_old`/`DROP`). Partycje utworzone:
`contact_event_2026_05`..`contact_event_2026_08` (zakres istniejących danych, 2026-05-14..2026-08-04)
+ `contact_event_2026_09`, `contact_event_2026_10` (bieżący+2) + `contact_event_default`. GUC RLS
pozostał **`app.tenant_id`** (NIE naprawiony — świadomie, zgodnie z decyzją projektową, naprawa
wydzielona do DB-054/V090). `relrowsecurity=t`, `relforcerowsecurity=t` (FORCE dodane, dziś tabela
tego nie miała). Test manualny pod `SET ROLE app_user` z `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`:
trigger (`UPDATE ... SET ended_at` → `duration_seconds=90`, poprawnie), `chk_contact_event_stage`
(BOGUS_STAGE odrzucone), `chk_contact_event_times` (ended_at < started_at odrzucone), izolacja RLS
(tenant B 0 wierszy tenanta A), cross-tenant INSERT odrzucony przez politykę (mimo braku jawnego
`WITH CHECK` w `CREATE POLICY` — dla polityki `ALL` bez `WITH CHECK` Postgres używa `USING` również
jako check przy zapisie), tenant B widzi własny nowy wiersz. Baza po weryfikacji: 857 wierszy
(cała weryfikacja w transakcji z `ROLLBACK`, baza nietknięta).

---

### DB-050 – Partycjonowanie tabeli `contact_transcription` (online) — migracja V086

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** brak (tabela `contact_transcription` już istnieje od V067, poza zakresem TASKS-DATABASE.md jako osobny ticket historycznie)
**Status:** ✅ Ukończone
**Blokuje:** DB-052, DB-053, BE-117
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Ten sam wzorzec online-migracji co DB-049 (patrz tam pełny opis kroków) zastosowany do
`contact_transcription` (V067). Partycjonowanie po `created_at` (jedyna kolumna czasowa tej
tabeli). Nowy PK złożony `(transcription_id, created_at)`.

**Specyfika tej tabeli:**
- Kolumny do zachowania 1:1: `transcription_id, contact_id, tenant_id, content, language, created_at`
- Indeks do odtworzenia: `idx_contact_transcription_contact (contact_id, tenant_id)` — **UWAGA:** ta kolejność kolumn nie pokrywa efektywnie zapytań retencji `WHERE tenant_id = ? AND created_at < ?`; nowy indeks `(tenant_id, created_at)` dochodzi osobno w DB-053, nie w tym tickecie
- RLS: odtwórz z **tą samą (błędną) nazwą GUC `app.tenant_id`** — identycznie jak w V067, naprawa wydzielona do DB-054 (świadoma decyzja, patrz DB-049)
- Brak triggerów/funkcji specyficznych dla tej tabeli (prostsza niż `contact_event`)
- `contact_transcription` nie ma encji JPA (czysty `JdbcTemplate` — `ContactTranscriptionRepository`) — zmiana warstwy Java to wyłącznie dodanie kolumny partycjonowania (`created_at`) do operacji adresujących wiersz po PK, realizowane w BE-117 razem z pozostałymi dwoma tabelami

**Kryteria akceptacji:**
- [x] Migracja V086 aplikuje się bez błędów, zero utraty danych (liczba wierszy przed == po)
- [x] Tabela partycjonowana RANGE po `created_at`; PK złożony `(transcription_id, created_at)`
- [x] Indeks `idx_contact_transcription_contact` odtworzony
- [x] RLS + FORCE RLS odtworzone (GUC `app.tenant_id` niezmieniony, świadomie — patrz DB-054)
- [x] Partycja `DEFAULT` istnieje od startu
- [x] Stara tabela usunięta dopiero po weryfikacji liczby wierszy

**Wykonanie (2026-08-09):** Migracja `V086__partition_contact_transcription.sql` zaaplikowana i
zweryfikowana na dev (`ccapp`/`contact_center`, `cc-postgres`). Liczba wierszy przed = po = **50**
(weryfikacja programowa blokiem `DO $$ ... RAISE EXCEPTION ...$$` wewnątrz samej migracji — przy
niezgodności cała migracja robi ROLLBACK). Partycje utworzone: `contact_transcription_2026_05`..
`contact_transcription_2026_07` (zakres istniejących danych, 2026-05-24..2026-07-29) +
`contact_transcription_2026_08` (bieżący) + `_2026_09`, `_2026_10` (2 kolejne) +
`contact_transcription_default`. GUC RLS pozostał **`app.tenant_id`** (NIE naprawiony —
świadomie, zgodnie z decyzją projektową, naprawa wydzielona do DB-054/V090). `relrowsecurity=t`,
`relforcerowsecurity=t` (FORCE dodane, dziś tabela tego nie miała). PK odtworzony pod pierwotną
(auto-generowaną) nazwą `contact_transcription_pkey`, teraz złożony `(transcription_id,
created_at)`. Indeks `idx_contact_transcription_contact` odtworzony z DOKŁADNIE tą samą kolejnością
kolumn `(contact_id, tenant_id)` co przed migracją (świadomie nie "ulepszany" — nowy indeks
`(tenant_id, created_at)` dochodzi osobno w DB-053). Tabela nie miała FK ani triggerów — nic do
odtworzenia poza indeksem i RLS. Dry-run w transakcji z jawnym `ROLLBACK` wykonany przed
uruchomieniem przez Flyway. Test manualny pod `SET ROLE app_user` z `SAVEPOINT`/`ROLLBACK TO
SAVEPOINT`: izolacja RLS (tenant A widzi 50/50 własnych wierszy, tenant B widzi 0), cross-tenant
INSERT odrzucony przez politykę (`ERROR: new row violates row-level security policy`, mimo braku
jawnego `WITH CHECK` — dla polityki `ALL` bez `WITH CHECK` Postgres używa `USING` również jako
check przy zapisie), insert własnego tenanta zaakceptowany i widoczny. Baza po weryfikacji: 50
wierszy (cała weryfikacja w transakcjach z `ROLLBACK`, baza nietknięta).

---

### DB-051 – Partycjonowanie tabeli `contact_ai_summary` (online) — migracja V087

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** brak (tabela `contact_ai_summary` już istnieje od V068)
**Status:** ✅ Ukończone
**Blokuje:** DB-052, DB-053, BE-117
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Ten sam wzorzec online-migracji co DB-049/DB-050, zastosowany do `contact_ai_summary` (V068).
Partycjonowanie po `generated_at` (**nie** `created_at`, żeby partycja odzwierciedlała moment
wygenerowania podsumowania przez model AI, spójnie z semantyką kolumny — potwierdź tę decyzję
z `db-schema-architect` przy implementacji; alternatywa `created_at` też jest broniona, obie
kolumny istnieją i zwykle są sobie bliskie w czasie). Nowy PK złożony `(ai_summary_id,
generated_at)` (lub `(ai_summary_id, created_at)`, zależnie od decyzji wyżej — spójna z kolumną
partycjonowania).

**Specyfika tej tabeli:**
- Kolumny do zachowania 1:1: `ai_summary_id, contact_id, tenant_id, summary, model, generated_at, created_at`
- Indeks do odtworzenia: `idx_contact_ai_summary_contact (contact_id, tenant_id)`
- RLS: odtwórz z tą samą (błędną) nazwą GUC `app.tenant_id` — naprawa w DB-054 (świadomie, jak DB-049/050)
- Zmiana Java w BE-117 (`@IdClass`)

**Kryteria akceptacji:**
- [x] Migracja V087 aplikuje się bez błędów, zero utraty danych
- [x] Tabela partycjonowana RANGE po kolumnie ustalonej w implementacji (`generated_at` lub `created_at`, udokumentuj wybór w komentarzu migracji)
- [x] PK złożony spójny z kolumną partycjonowania
- [x] Indeks `idx_contact_ai_summary_contact` odtworzony
- [x] RLS + FORCE RLS odtworzone (GUC niezmieniony, świadomie)
- [x] Partycja `DEFAULT` istnieje od startu
- [x] Stara tabela usunięta dopiero po weryfikacji liczby wierszy

**Wykonanie (2026-08-09):** Migracja `V087__partition_contact_ai_summary.sql` zaaplikowana i
zweryfikowana na dev (`ccapp`/`contact_center`, `cc-postgres`). Liczba wierszy przed = po = **57**
(weryfikacja programowa blokiem `DO $$ ... RAISE EXCEPTION ...$$` wewnątrz samej migracji — przy
niezgodności cała migracja robi ROLLBACK). **Decyzja o kolumnie partycjonowania: `generated_at`**
(nie `created_at`) — uzasadnienie: `generated_at` to moment faktycznego wygenerowania treści
podsumowania przez model AI, biznesowo istotny "wiek" danych analogicznie do `started_at` w
`contact_event`/`contact`, podczas gdy `created_at` jest wyłącznie technicznym znacznikiem zapisu
wiersza do bazy. Przyszłe polityki retencji/purge (DB-052/DB-053) mają sens liczone od momentu
wygenerowania treści, nie od przypadkowego opóźnienia zapisu. Zweryfikowano w dev, że obie kolumny
są sobie bliskie co do dnia (`MIN/MAX(generated_at)` = 2026-05-24..2026-07-29,
`MIN/MAX(created_at)` = 2026-05-25..2026-07-29) — wybór nie zmienił liczby/zakresu wymaganych
partycji, tylko poprawność semantyczną przyszłych zapytań. Pełne uzasadnienie w nagłówku migracji.
PK złożony `(ai_summary_id, generated_at)`. Partycje utworzone: `contact_ai_summary_2026_05`..
`contact_ai_summary_2026_07` (zakres istniejących danych wg `generated_at`) + `_2026_08` (bieżący)
+ `_2026_09`, `_2026_10` (2 kolejne) + `contact_ai_summary_default`. GUC RLS pozostał
**`app.tenant_id`** (NIE naprawiony — świadomie, naprawa wydzielona do DB-054/V090).
`relrowsecurity=t`, `relforcerowsecurity=t` (FORCE dodane, dziś tabela tego nie miała). PK
odtworzony pod **nową, konwencyjną nazwą** `pk_contact_ai_summary` (oryginał był autonazwany
`contact_ai_summary_pkey`, bo V068 użyło inline `PRIMARY KEY` bez `CONSTRAINT` — spójne z jawnym
nazewnictwem `pk_contact_event` z V085). Indeks `idx_contact_ai_summary_contact` odtworzony z
DOKŁADNIE tą samą kolejnością kolumn `(contact_id, tenant_id)`. Tabela nie miała FK (potwierdzone
`pg_constraint` — jedyny constraint to PK) ani triggerów — nic do odtworzenia poza indeksem i RLS.
Dry-run w transakcji z jawnym `ROLLBACK` wykonany przed uruchomieniem przez Flyway. Test manualny
pod `SET ROLE app_user` z `SAVEPOINT`/`ROLLBACK TO SAVEPOINT`: izolacja RLS (tenant A widzi własny
wiersz, tenant B widzi 0 wierszy tenanta A), cross-tenant INSERT odrzucony przez politykę (`ERROR:
new row violates row-level security policy`, mimo braku jawnego `WITH CHECK`), insert własnego
tenanta B zaakceptowany i widoczny. Baza po weryfikacji: 57 wierszy (cała weryfikacja w
transakcjach z `ROLLBACK`, baza nietknięta). Migracja zaaplikowana przez Flyway bezpośrednio po
V086 (DB-050, równoległy agent) bez konfliktu — historia `flyway_schema_history` potwierdza oba
wpisy (086, 087) jako `success=t`.

---

### DB-052 – Naprawa rotacji partycji `contact`/`audit_log` + rozszerzenie na nowe tabele partycjonowane (FUNDAMENT) — migracja V088

**Typ:** Schema migration / bugfix
**Priorytet:** Must Have — **krytyczny, blokujący dla całego epiku**
**Złożoność:** L
**Zależy od:** DB-049, DB-050, DB-051 (rozszerza `create_next_month_partitions()` o te 3 nowe tabele partycjonowane — funkcja musi znać ich istnienie)
**Status:** ✅ Ukończone
**Blokuje:** BE-112, BE-114, BE-115
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst — dlaczego to jest fundament epiku:** Dzisiejsza data systemowa to 2026-08-08.
Partycje `contact`/`audit_log` (RANGE po miesiącu, V007/V011 i V004) kończą się na `2026_05`.
Od czerwca 2026 **wszystkie nowe rekordy trafiają do partycji `*_default`** (fallback bez
indeksów partycjonowania, bez możliwości szybkiego `DROP PARTITION`). Przyczyna: `cron.schedule(...)`
w V014 jest zakomentowany, `pg_cron` nie jest nawet włączony w obrazie `postgres:16-alpine`, i
**żaden Java `@Scheduled` job tego nie wywołuje jako fallback** (naprawiane w BE-114, ale ten
ticket dostarcza fundament SQL, na którym BE-114 się opiera). Bez tej naprawy `RetentionEvaluationJob`
(BE-112) liczyłby dane niepoprawnie — partycja `*_default` nie jest objęta logiką „skanuj od
najstarszej partycji, zatrzymaj się gdy górna granica jest młodsza niż najkrótsza retencja”,
bo `*_default` nie ma górnej granicy.

**Migracja ma dwie logicznie niezależne części, połączone w jednym pliku zgodnie z planem projektu:**

**Część A — backfill brakujących partycji + przeniesienie danych z `*_default`:**
```sql
-- Dotworzenie brakujących partycji miesięcznych dla contact i audit_log:
-- 2026_06, 2026_07, 2026_08, 2026_09 (bieżący + zapas)
-- (użyj istniejących funkcji create_contact_partition(year, month) /
--  create_audit_log_partition(year, month) — sprawdź ich dokładne nazwy/sygnatury
--  w V007/V004 przed implementacją, nie zgaduj)

-- Przeniesienie wierszy z contact_default do właściwych partycji miesięcznych,
-- BATCHAMI (nie jedna wielka transakcja — contact może mieć duży wolumen):
--   INSERT INTO contact SELECT * FROM contact_default
--     WHERE started_at >= :batch_start AND started_at < :batch_end;
--   DELETE FROM contact_default WHERE started_at >= :batch_start AND started_at < :batch_end;
-- Analogicznie dla audit_log_default.
```
**Uwaga implementacyjna do potwierdzenia przy wykonaniu:** sprawdź faktyczną liczbę wierszy
aktualnie leżących w `contact_default`/`audit_log_default` przed pisaniem strategii
batchowania — jeśli wolumen jest mały (środowisko dev/demo), pojedyncza transakcja może
wystarczyć; nie buduj niepotrzebnie złożonego mechanizmu batchowania dla garści wierszy testowych.

**Część B — rozszerzenie `create_next_month_partitions()` (V014) o nowe tabele:**
```sql
-- CREATE OR REPLACE FUNCTION create_next_month_partitions() — dodaj wywołania
-- create_contact_event_partition(...), create_contact_transcription_partition(...),
-- create_contact_ai_summary_partition(...) analogicznie do wzorca zastosowanego
-- w V077 dla plugin_invocation_log (PERFORM create_plugin_invocation_log_partition(...)
-- dodane do tej samej zbiorczej funkcji).
-- Każda z 3 nowych tabel potrzebuje też własnej funkcji create_<table>_partition(year,month)
-- i drop_old_<table>_partitions(retention_months) — wzorzec 1:1 z audit_log/contact/plugin_invocation_log.
-- Zarejestruj w scheduled_job (rotate_contact_event_partitions itd., analogicznie do
-- rotate_plugin_invocation_log_partitions z V077).
```

**Kryteria akceptacji:**
- [x] Migracja V088 aplikuje się bez błędów
- [x] Po migracji: `contact_default`/`audit_log_default` są puste (lub zawierają wyłącznie wiersze spoza obsłużonego zakresu, jeśli takie się znajdą — udokumentuj przypadek) — w dev oba `*_default` puste (122/287 wierszy przeniesionych, 0 pozostałych spoza zakresu)
- [x] Partycje `contact_2026_06`..`contact_2026_09` (i analogicznie `audit_log_*`) istnieją i zawierają właściwe dane (weryfikacja `COUNT(*)` per partycja sumuje się do całości)
- [x] Zero utraty danych: `SELECT COUNT(*) FROM contact` (i `audit_log`) identyczne przed i po migracji (556 / 1215)
- [x] `create_next_month_partitions()` po `CREATE OR REPLACE` tworzy partycje dla WSZYSTKICH partycjonowanych tabel: `contact`, `audit_log`, `contact_event`, `contact_transcription`, `contact_ai_summary`, `plugin_invocation_log` (istniejąca) — zweryfikowane ręcznym wywołaniem funkcji w transakcji testowej z `ROLLBACK`
- [x] Nowe funkcje `create_<table>_partition`/`drop_old_<table>_partitions` dla 3 nowych tabel, wzorzec 1:1 z `plugin_invocation_log` (V077)
- [x] Nowe wpisy w `scheduled_job` dla rotacji 3 nowych tabel
- [x] **Test regresyjny kluczowy:** po migracji wstaw testowy wiersz `contact` z `started_at` w bieżącym miesiącu i potwierdź, że trafia do partycji miesięcznej, NIE do `contact_default` (`SELECT tableoid::regclass FROM contact WHERE contact_id = ...`) — potwierdzone, nowy wiersz trafia do `contact_2026_08`

---

### DB-053 – Indeksy `(tenant_id, kolumna_czasowa)` dla wydajnego purge per-tenant — migracja V089

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-049, DB-050, DB-051 (kolumny partycjonowania muszą istnieć na docelowych tabelach)
**Status:** ✅ Ukończone
**Blokuje:** BE-113
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
`RetentionPurgeService` (BE-113) wykonuje `DELETE FROM <tabela> WHERE tenant_id = :tenantId AND
<kolumna_czasowa> < :cutoff` batchami. Bez indeksu `(tenant_id, kolumna_czasowa)` to byłby
sekwencyjny skan całej partycji przy każdym batchu. **Audyt istniejących indeksów (wykonany
podczas dekompozycji — nie zakładaj, sprawdź `\d` przed implementacją):**

| Tabela | Indeks `(tenant_id, czas)` już istnieje? | Akcja |
|---|---|---|
| `contact` | ❌ brak (istniejące indeksy tenant-owe są zawężone do zapytań raportowych) | **DODAJ** `idx_contact_tenant_started_at (tenant_id, started_at)` |
| `contact_event` | ✅ `idx_contact_event_tenant (tenant_id, started_at DESC)` już istnieje (V059) | **POMIŃ** — nic do zrobienia |
| `contact_transcription` | ❌ istniejący `idx_contact_transcription_contact` ma kolejność `(contact_id, tenant_id)`, bez kolumny czasowej | **DODAJ** `idx_contact_transcription_tenant_created (tenant_id, created_at)` |
| `contact_ai_summary` | ❌ analogicznie do transcription | **DODAJ** `idx_contact_ai_summary_tenant_<kolumna z DB-051> (tenant_id, <generated_at lub created_at>)` |
| `campaign_contact_archive` | ❌ istniejący `idx_cca_archived_at` nie jest tenant-scoped | **DODAJ** `idx_cca_tenant_archived_at (tenant_id, archived_at)` |

**DDL migracji (`V089__add_tenant_scoped_retention_indexes.sql`):**
```sql
CREATE INDEX idx_contact_tenant_started_at ON contact (tenant_id, started_at);
CREATE INDEX idx_contact_transcription_tenant_created ON contact_transcription (tenant_id, created_at);
CREATE INDEX idx_contact_ai_summary_tenant_generated ON contact_ai_summary (tenant_id, generated_at); -- nazwa/kolumna do potwierdzenia zgodnie z DB-051
CREATE INDEX idx_cca_tenant_archived_at ON campaign_contact_archive (tenant_id, archived_at);
```

**Kryteria akceptacji:**
- [x] Migracja V089 aplikuje się bez błędów
- [x] `contact_event` świadomie pominięta (już ma równoważny indeks) — udokumentowane komentarzem w migracji, żeby przyszły czytelnik nie pomyślał, że to przeoczenie
- [x] `EXPLAIN` dla `DELETE FROM contact WHERE tenant_id = ? AND started_at < ?` pokazuje `Index Scan`/`Bitmap Index Scan` na nowym indeksie, nie `Seq Scan`
- [x] Analogicznie zweryfikowane dla pozostałych 3 nowych indeksów

**Notatka z implementacji (2026-08-10):**
Dev ma za mały wolumen (partycje `contact`/`contact_transcription`/`contact_ai_summary` po 50-360
wierszy, `campaign_contact_archive` puste), żeby planner naturalnie wybrał Index/Bitmap Scan na
każdej partycji — dla bardzo małych partycji (≤~100 wierszy, jedna strona danych) Seq Scan jest
poprawnie tańszy niż dostęp przez indeks, to nie wada indeksu. Zweryfikowano dodatkowo na
symulowanym wolumenie produkcyjnym (insert + `EXPLAIN ANALYZE` w transakcji z `ROLLBACK`, zero
wpływu na realne dane — potwierdzone `COUNT(*)` identycznym przed/po: 556/50/57/0):
- `contact`: przy 2 tenantach i 50% selektywności `tenant_id` Seq Scan pozostawał tańszy (poprawne
  zachowanie plannera) — przy realistycznej skali SaaS (100 syntetycznych tenantów, ~1%
  selektywność) partycja `contact_2026_05` (100k wierszy) poprawnie przełączyła się na
  `Bitmap Index Scan` na `idx_contact_tenant_started_at`.
- `contact_transcription` i `contact_ai_summary`: przy 100k wierszy/2 tenantach już `Bitmap Heap
  Scan` + `Bitmap Index Scan` na nowych indeksach.
- `campaign_contact_archive`: przy 5000 wierszy/2 tenantach `Index Scan` na `idx_cca_tenant_archived_at`
  (zgodnie z uwagą w tickecie o pustej tabeli w dev).

Pełne wyniki `EXPLAIN (ANALYZE, BUFFERS)` w podsumowaniu sesji implementacyjnej.

---

### DB-054 – [OPCJONALNY, POBOCZNY] Poprawka nazwy GUC RLS `app.tenant_id` → `app.current_tenant_id` — migracja V090

**Typ:** Schema migration / bugfix (bezpieczeństwo, defense-in-depth)
**Priorytet:** Could Have — **zadanie poboczne/opcjonalne względem głównego epiku, łatwe do wydzielenia lub pominięcia bez wpływu na resztę funkcji retencji**
**Złożoność:** S
**Zależy od:** DB-049, DB-050, DB-051 (dotyka tych samych tabel — `contact_transcription`, `contact_ai_summary` — oraz `contact_event`, `tenant_ai_config`)
**Status:** ✅ Ukończone
**Blokuje:** brak
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Kontekst:**
Cztery istniejące migracje (`V059__create_contact_event.sql`, `V064__create_tenant_ai_config.sql`,
`V067__create_contact_transcription.sql`, `V068__extract_ai_summary_to_own_table.sql`) ustawiają
politykę RLS z `current_setting('app.tenant_id', TRUE)` zamiast poprawnej `app.current_tenant_id`,
którą faktycznie ustawia `set_tenant_context()` (wywoływana przez
`TenantAwareRepository.setTenantContextInDb()`). **Skutek: polityka RLS na tych 4 tabelach nigdy
się nie dopasowuje** (GUC o tej nazwie nigdy nie jest ustawiany) — w praktyce brak izolacji przez
RLS na tych tabelach. Warstwa aplikacji (`assertSameTenant(...)` w repozytoriach) wciąż chroni
przed cross-tenant dostępem, więc to nie jest aktywnie eksploatowalna luka dziś, ale osłabia
defense-in-depth.

**To zadanie jest EXPLICITE oznaczone jako poboczne względem głównego celu epiku** (partycjonowanie
i retencja) — nie blokuje żadnego innego ticketu w tym epiku i może zostać zrealizowane osobno,
w innym momencie, lub pominięte bez wpływu na funkcjonalność retencji. Wydzielone do osobnego
pliku migracji właśnie po to, żeby dało się je łatwo wyciąć z planu wykonania.

**DDL migracji (`V090__fix_rls_guc_naming_inconsistency.sql`):**
```sql
DROP POLICY IF EXISTS contact_event_tenant_isolation ON contact_event;
CREATE POLICY contact_event_tenant_isolation ON contact_event
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid);

-- analogicznie dla tenant_ai_config (V064), contact_transcription (odtworzona w DB-050),
-- contact_ai_summary (odtworzona w DB-051) — nazwy polityk do potwierdzenia w momencie
-- implementacji (te dwie ostatnie przechodzą przez RENAME w DB-050/DB-051, więc nazwa
-- polityki zależy od tego, jak dokładnie zaimplementowano tam odtworzenie RLS)
```

**Kryteria akceptacji:**
- [x] Migracja V090 aplikuje się bez błędów
- [x] Wszystkie 4 polityki RLS używają `app.current_tenant_id`
- [x] Test izolacji pod `SET ROLE app_user` (NIE `ccapp`) na wszystkich 4 tabelach: tenant B nie widzi wierszy tenanta A
- [x] `FORCE ROW LEVEL SECURITY` potwierdzone tam, gdzie już było (nie regresja), dodane tam, gdzie brakowało (żadna z 4 oryginalnych migracji nie miała `FORCE` — potwierdź `\d` przed implementacją i rozważ dodanie, to wzmacnia efekt tej poprawki)

**Podsumowanie implementacji (2026-08-10):**
- `V090__fix_rls_guc_naming_inconsistency.sql` — `DROP POLICY`/`CREATE POLICY` (ta sama nazwa, poprawny GUC `app.current_tenant_id`) na `contact_event_tenant_isolation`, `tenant_ai_config_isolation`, `contact_transcription_isolation`, `contact_ai_summary_isolation`. `ALTER TABLE tenant_ai_config FORCE ROW LEVEL SECURITY` — jedyna z czterech, która go dotąd nie miała (pozostałe trzy miały `FORCE` już od DB-049/050/051, potwierdzone bez regresji).
- **Decyzja o zakresie:** świadomie BEZ `WITH CHECK` — minimalny, łatwo odwracalny diff zamiast ujednolicania stylu z nowszymi tabelami EPIC-29 (np. `tenant_retention_policy`/DB-046). Uzasadnienie w nagłówku migracji. Ochrona przy zapisie i tak działa — dla polityki `ALL` bez jawnego `WITH CHECK` Postgres używa `USING` również jako check (potwierdzone testem: cross-tenant INSERT odrzucony na wszystkich 4 tabelach).
- Weryfikacja: dry-run w transakcji z `ROLLBACK` (czysty przebieg), aplikacja przez `RunFlyway.java` (`-Duser.timezone=UTC`), test manualny pod `SET ROLE app_user` + `SAVEPOINT`/`ROLLBACK TO SAVEPOINT` na wszystkich 4 tabelach: izolacja (tenant B 0 wierszy tenanta A), cross-tenant INSERT odrzucony, `tenant_ai_config` dodatkowo test insertu własnego (tenant B, który nie miał dotąd wiersza) — zaakceptowany. Zero wyciekłych wierszy testowych po `ROLLBACK` (potwierdzone `COUNT(*)` po migracji: `tenant_ai_config`=1 czyli tylko oryginalny dev-seed).
- **EPIC-29, warstwa DB zamknięta: DB-046..054, 9/9 ukończone.**

---

### DB-055 – [PORZĄDKOWY] Usunięcie zduplikowanych indeksów z partycjonowanej tabeli `contact` — migracja V093

**Typ:** Schema migration / performance (dług techniczny)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** DB-052 (V088 — rotacja partycji; każda nowa partycja `contact` dziedziczy indeksy rodzica)
**Status:** ✅ Ukończone (migracja zweryfikowana na bazie scratch + pełnym łańcuchu Flyway w Testcontainers; **zastosowana RĘCZNIE przez `psql` na lokalnej bazie demo `contact_center` 2026-09-19 — poza Flyway**, więc `flyway_schema_history` nie ma jeszcze wpisu V093. Przy wdrożeniu z plikami V092+V093 Flyway zarejestruje V093, a ponowne wykonanie jest no-op: `DROP INDEX IF EXISTS`, guard przechodzi, komentarze nadpisywane tym samym tekstem)
**Blokuje:** brak
**Epic:** brak (porządki po EPIC-29 — dotyczy tabeli `contact` partycjonowanej w V007, rotowanej w DB-052)

**Numeracja:** V092 jest zajęte przez `V092__social_integration_global_unique_page.sql` (zastosowane w bazie
dev 2026-08-29, plik istnieje na gałęzi `feature-socialmedia`, a NIE na `develop`), więc migracja dostała
następny wolny numer **V093**.

> **Uwaga wdrożeniowa (zweryfikowana Flyway API na kopii `flyway_schema_history`):** baza `contact_center`
> ma już zastosowane V092. Build z samego `develop` (V091 + V093, bez pliku V092) wystartowany na tej bazie
> **nie przejdzie walidacji Flyway** — `V092 MISSING_SUCCESS`, `Detected applied migration not resolved
> locally: 092` (V092 przestaje być „future" i staje się „missing", gdy V093 jest wyższe i rozpoznane lokalnie;
> domyślne `ignoreMigrationPatterns` ignoruje tylko `*:future`). Najpierw trzeba wprowadzić na `develop`
> `V092__social_integration_global_unique_page.sql` (merge `feature-socialmedia`), dopiero potem wdrażać V093.
> Z plikiem V092 obecnym lokalnie Flyway widzi wyłącznie V093 jako `PENDING` i aplikuje go czysto.

**Kontekst:**
`V007__create_contact.sql` zadeklarował indeksy z sufiksami `_history`/`_date`/`_disposition`, a
`V011__performance_indexes_views.sql` (sekcja 3) dodał drugi zestaw z innymi nazwami i tymi samymi
kolumnami. Oba zestawy są zdefiniowane NA TABELI NADRZĘDNEJ, więc każdy propaguje się do KAŻDEJ partycji
(11 dziś: `contact_2026_03..12` + `contact_default`, plus każda kolejna tworzona przez
`PartitionMaintenanceJob`). Każdy INSERT/UPDATE na `contact` utrzymuje zbędne indeksy × liczba partycji —
czysty koszt zapisu i miejsca, bez korzyści dla odczytu.

**Audyt (żywa baza PG 16.13, `pg_index`: kolumny, `indoption`, opclass, collation, predykat, AM):**

| Para | Indeks A (V007) — ZOSTAJE | Indeks B (V011) — USUWANY | Różnica |
|---|---|---|---|
| 1 | `idx_contact_agent_history` `(tenant_id, agent_id, started_at DESC) WHERE agent_id IS NOT NULL` | `idx_contact_tenant_agent_date` | brak (identyczne) |
| 2 | `idx_contact_disposition` `(tenant_id, disposition_code, started_at) WHERE disposition_code IS NOT NULL` | `idx_contact_tenant_disposition_date` | brak (identyczne) |
| 3 | `idx_contact_channel_date` `(tenant_id, channel, started_at)` | `idx_contact_tenant_channel_date` `(tenant_id, channel, started_at DESC)` | tylko kierunek `started_at` (`indoption` `0 0 0` vs `0 0 3`) |

**Decyzja co zostaje i dlaczego** (kryteria: referencje / nazewnictwo / V025):
- **Referencje:** nazwy V007 występują w `documentation/tech/06-database.md` (+ html), w treści ticketu
  powyżej (linia „Raporty > Kontakty") i w nagłówku V089; nazw V011 nie ma nigdzie poza V011/V025. Zero
  referencji w kodzie Javy, hintach `@Query`, funkcjach SQL (`pg_proc`), widokach (`pg_views`) i w voicebot —
  usunięcie nie wymaga zmian w kodzie ani w dokumentacji.
- **Nazewnictwo:** po migracji 13 z 15 indeksów nie-PK na `contact` ma formę `idx_contact_<przeznaczenie>`;
  infiks `tenant_` nie niesie informacji, bo każdy indeks `contact` (poza `idx_contact_campaign_contact_record`)
  zaczyna się od `tenant_id`.
- **V025:** nie dotknął par 1 i 2 (kolumny nie są enum); parę 3 dotknął symetrycznie (DROP + CREATE obu
  indeksów `channel`) — nie rozstrzyga.
- Statystyki `idx_scan` na żywej bazie (21 skanów tylko na `idx_contact_tenant_agent_date`) NIE są argumentem:
  przy dwóch identycznych indeksach planner wybiera któryś arbitralnie; po usunięciu bliźniaka plan jest ten sam.

**Para 3 (ASC vs DESC) — dowód, nie domysł.** Zapytania z filtrem `channel` w backendzie
(`ContactRepository.findContacts/countContacts/findAgentReportRows/countAgentReportRows/findActiveSocialContact`;
`getContactCountsByChannelInRange` używa `channel` tylko w `GROUP BY`) traktują `channel` wyłącznie jako
równość lub `GROUP BY`; jedyny związany `ORDER BY` to `ORDER BY started_at DESC LIMIT/OFFSET`
(`findContacts`). Brak `ORDER BY` zawierającego `channel` w `backend/`, `voicebot/` i migracjach.
`EXPLAIN (ANALYZE, BUFFERS)` na bazie scratch (500 tys. wierszy, 60 tenantów, 7 zapełnionych partycji,
`SET enable_seqscan = off`, największy tenant 64,6 tys. wierszy; w transakcji z `ROLLBACK`, dodatkowo po
usunięciu `idx_contact_tenant_started_at`, żeby żaden inny indeks nie mógł dać kolejności po `started_at`):

| Wariant | `ORDER BY started_at DESC LIMIT 20` | `ORDER BY started_at ASC LIMIT 20` | głęboka strona `OFFSET 1500` | zakres dat / `COUNT(*)` / raport `GROUP BY` |
|---|---|---|---|---|
| tylko ASC (zostaje) | `Merge Append` > `Index Scan Backward`, **0 węzłów Sort**, 55–57 buforów, 0,6–1,0 ms | `Index Scan`, 0 Sort, 55 buf. | 1580 / 1564 buf., ~4,7 ms | identyczne plany i bufory |
| tylko DESC (usuwany) | `Index Scan`, 0 Sort, 55–56 buf. | `Index Scan Backward`, 0 Sort, 55 buf. | 1564 / 1580 buf., ~4,7 ms | identyczne |
| KONTROLA (oba indeksy `channel` + `tenant_started_at` usunięte) | `Sort` > `Bitmap Heap Scan`, 13955 buf., 35–45 ms | jw. | jw. | — |

Kontrola dowodzi, że test wykrywa brak indeksu — brak `Sort` w wariantach z pojedynczym indeksem jest
dowodem, nie artefaktem. Rozmiar przy monotonicznym napływie wierszy (700 tys., kolejność czasowa): ASC ≈ DESC
≈ 60 MB (różnica <1%; po `REINDEX` po 34 MB). **Jedyna zmierzona różnica** dotyczy kształtu `ORDER BY`, którego
kod NIE używa: `WHERE tenant_id = ? ORDER BY channel, started_at DESC` (`channel` bez równości) — DESC obsługuje
go skanem w przód (55 buf.), ASC wymaga `Incremental Sort` (16 tys. buf., 20–60 ms); symetrycznie ASC obsługuje
`ORDER BY channel, started_at`, a DESC nie. Gdyby takie zapytanie powstało, należy wtedy świadomie dodać indeks
pod jego `ORDER BY`. Wniosek: dla WSZYSTKICH istniejących ścieżek pojedynczy indeks wystarcza → usunięty wariant
DESC, zostaje `idx_contact_channel_date` (te same kryteria co w parach 1 i 2).

**Nowe partycje nie odtwarzają duplikatów.** `create_contact_partition()` (V007; V014/V077/V088 redefiniują
wyłącznie `create_next_month_partitions()`/`rotate_*`) tworzy partycje przez `CREATE TABLE ... PARTITION OF
contact` — partycja dziedziczy dokładnie te indeksy, które istnieją na rodzicu w chwili tworzenia. Żadna
funkcja z `pg_proc`, `PartitionMaintenanceJob`, kod Javy ani event trigger nie tworzy indeksów `contact`
jawnie po nazwie (zweryfikowane). Empirycznie: `SELECT create_contact_partition(2027, 1)` po migracji → 16
indeksów, wszystkie dołączone do rodziców, bez duplikatów (także na świeżej bazie po pełnym łańcuchu Flyway).

**DDL migracji (`V093__drop_duplicate_contact_indexes.sql`):**
```sql
SET LOCAL lock_timeout = '10s';
-- guard (DO $$): survivors idx_contact_agent_history / idx_contact_disposition / idx_contact_channel_date
-- MUSZĄ istnieć, inaczej RAISE EXCEPTION (ochrona przed dryfem — nie usuniemy jedynego indeksu ścieżki)
DROP INDEX IF EXISTS idx_contact_tenant_agent_date;
DROP INDEX IF EXISTS idx_contact_tenant_disposition_date;
DROP INDEX IF EXISTS idx_contact_tenant_channel_date;
COMMENT ON INDEX idx_contact_agent_history / idx_contact_disposition / idx_contact_channel_date IS '...';
```

**Blokada:** `DROP INDEX` na indeksie partycjonowanym zakłada `ACCESS EXCLUSIVE` na `contact` ORAZ na każdej
partycji (zweryfikowane w `pg_locks`: 12 tabel) do końca transakcji Flyway. Operacja jest metadata-only
(milisekundy), ale czeka na zakończenie trwających transakcji na `contact`, a nowe zapytania ustawiają się za
nią w kolejce — stąd `SET LOCAL lock_timeout = '10s'` (lepiej przerwać migrację niż zablokować ruch).
`DROP INDEX CONCURRENTLY` jest niemożliwy na indeksie partycjonowanym (`ERROR: cannot drop partitioned index ...
concurrently`). Zalecenie: wdrożenie poza szczytem ruchu.

**Kryteria akceptacji:**
- [x] Numeracja potwierdzona: V092 zajęte (baza + `feature-socialmedia`) → V093
- [x] Migracja aplikuje się bez błędów: scratch (`psql -1`, `ON_ERROR_STOP`); pełny łańcuch Flyway 10.20.1 na świeżej bazie w dwóch wariantach — bez V092 (czysty `develop`, 92 migracje) i z V092 z `feature-socialmedia` (93 migracje); `mvn verify -pl app` = BUILD SUCCESS, 1779 testów, 0 błędów (w tym Testcontainers `CampaignContactArchivePurgeTenantIsolationTest`; uwaga: `backend/app/target/classes/db/migration` zawierał nieaktualną kopię V092 po wcześniejszym buildzie z gałęzi social, więc ten przebieg walidował wariant „z V092" — wariant „bez V092" zweryfikowano osobno przez Flyway API)
- [x] Idempotentna — drugie uruchomienie bez błędów i bez zmian (`DROP INDEX IF EXISTS`)
- [x] Guard działa: przy usuniętym indeksie-zwycięzcy migracja przerywa się i niczego nie usuwa (symulacja dryfu w transakcji z `ROLLBACK`)
- [x] Rodzic `contact`: 19 → 16 indeksów; każda z 11 partycji: 19 → 16, wszystkie dołączone; zero duplikatów (exact + direction-only)
- [x] `create_contact_partition(2027, 1)` → nowa partycja z 16 indeksami, bez duplikatów; `create_next_month_partitions()` nadal działa
- [x] Diff `pg_dump -s` przed/po: wyłącznie 3 indeksy rodzica + 33 indeksy partycji (+ ich `ATTACH`) usunięte i 3 komentarze dodane
- [x] Plany zapytań pod `SET ROLE app_user` z RLS (GUC `app.current_tenant_id`): bez `Sort`, użyty indeks-zwycięzca
- [x] Żaden kod Javy / dokumentacja nie wymaga zmian (brak referencji do usuwanych nazw)

**Audyt pozostałych tabel partycjonowanych (TYLKO RAPORT, poza zakresem V093 — bez zmian):**
`contact_event`, `contact_transcription`, `contact_ai_summary`, `audit_log`, `plugin_invocation_log` (oraz
`campaign_contact`, LIST) — porównanie definicji indeksów rodzica po zdjęciu nazwy (`pg_indexes`) i strukturalne
(`pg_index`: kolumny/opcje/opclass/collation/predykat) oraz wykrywanie redundancji prefiksowej (A jest
początkiem B, ten sam predykat/kierunek): **zero duplikatów dokładnych, zero bliźniaków różniących się tylko
kierunkiem, zero redundancji prefiksowej**. Wszystkie 3 zidentyfikowane pary występują wyłącznie na `contact`.
Rodzic `contact` po V093 też bez redundancji prefiksowej.

**Podsumowanie implementacji (2026-09-19):**
- `V093__drop_duplicate_contact_indexes.sql` — 3× `DROP INDEX IF EXISTS` + guard + `lock_timeout` + `COMMENT ON INDEX` na 3 indeksach-zwycięzcach. Efekt na wolumenie scratch (500 tys. wierszy, po `VACUUM FULL`): usunięte indeksy ważyły 24,7 + 19,9 + 15,7 = ~60 MiB (~21% miejsca wszystkich indeksów `contact`, ~57% rozmiaru samych danych 106 MiB) i tyle mniej pracy przy każdym INSERT/UPDATE (3 indeksy × liczba partycji).
- Bez zmian w kodzie produkcyjnym i dokumentacji. Wdrożenie: patrz „Uwaga wdrożeniowa" (kolejność względem V092).

---

## MODUL: Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości (EPIC-30)

> Źródło: `DESIGN-message-retention-and-partitioning.md` (projekt do akceptacji, analiza 2026-09-20) — ustalenia U1–U15, decyzje
> D1–D8 z założeniami domyślnymi (tickety działają przy nich; wpływ alternatywy opisany w każdym tickecie zależnym), wymagania
> przekrojowe WP-1…WP-8. Powiązane: `DESIGN-data-retention-partitioning.md` (EPIC-29), `PRD.md` §6.5 (NFR-RODO01/02/03).
> **Numery migracji Flyway NIE są wpisane w tickety** — nadaje się je przy implementacji: „następna wolna wersja; sprawdź `develop`,
> otwarte gałęzie (`git ls-tree -r --name-only <gałąź> -- backend/src/main/resources/db/migration`) ORAZ `flyway_schema_history` żywej
> bazy" (precedens: V092 zajęte przez gałąź `feature-socialmedia`, patrz DB-055). Jedna migracja na jedną zmianę; nigdy edycja
> zastosowanej migracji.
> **Numeracja:** DB-056…DB-077 (poprzedni najwyższy: DB-055). **Priorytety:** Must = luka RODO (grupa 1), Should = harmonogramy/RLS/social,
> Could = bramkowane lub warunkowe. Tickety oznaczone [WARUNKOWY] wchodzą do zakresu tylko przy wskazanej alternatywie decyzji;
> [BRAMKOWANY] — dopiero po spełnieniu progu wolumenowego.
>
> Graf zależności warstwy DB (A → B = kolejność wykonania, B zależy od A):
> ```
> Faza 0:   DB-056 → BE-121;   DB-057;   DB-058
> Grupa 1:  BE-124 → DB-059 → BE-127;   DB-060 → DB-061, DB-062 → BE-129;   [BE-124 → DB-063 → BE-130, tylko D1 = C]
> Grupa 2:  DB-064 → DB-065 → BE-132;   BE-126, DB-059 → DB-065
> Grupa 3:  DB-066 (bramka) → DB-067 → BE-134;   DB-064, DB-059, BE-127 → DB-067;   [DB-066, DB-067 → DB-068 → BE-136, tylko D4 = B]
> Grupa 4:  DB-056, DB-072 → DB-069 (bramka) → BE-137;   DB-070;   [BE-120, DB-056 → DB-075 → BE-140, tylko D6 = koniec kampanii]
> Grupa 5:  DB-071 → DB-072 (+ BE-120), DB-073, DB-074;   DB-071 → BE-138, BE-139;   DB-064 → BE-139
> Grupa 6:  BE-120, BE-122, BE-123, DB-058 → DB-076;   BE-120, BE-122, BE-123, DB-070, DB-076 → DB-077
> ```

### DB-056 – Zbatchowana funkcja `purge_campaign_contact_archive` (pojedynczy DELETE → partie)

**Typ:** Schema migration (funkcja SQL)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak (V091 zastosowane)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-121, DB-069, DB-075
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (test Testcontainers: `test-suite-expert`)

**Kontekst:**
`purge_campaign_contact_archive(p_tenant_id UUID, p_cutoff_date TIMESTAMPTZ) RETURNS INT` (V091) wykonuje jeden
`DELETE … WHERE tenant_id = … AND archived_at < …` w jednej transakcji — dla dużego tenanta to długa transakcja, duży WAL i długie
blokady (DESIGN §2 U12). `campaign_contact_archive` NIE jest partycjonowana, więc partie identyfikujemy pełnym PK
`(record_id, campaign_id)` (zakaz `ctid` dotyczy tabel partycjonowanych — kolizje między partycjami, patrz BE-113 — tu bezpieczny, ale
PK jest odporniejszy na przyszłą konwersję DB-069).

**Zakres:**
- Nowa migracja (numer wg WP-3): `DROP FUNCTION IF EXISTS purge_campaign_contact_archive(UUID, TIMESTAMPTZ)` +
  `CREATE FUNCTION purge_campaign_contact_archive(p_tenant_id UUID, p_cutoff_date TIMESTAMPTZ, p_batch_size INT DEFAULT 10000) RETURNS INT` —
  usuwa najwyżej `p_batch_size` wierszy (`WITH batch AS (SELECT record_id, campaign_id … WHERE tenant_id = p_tenant_id AND archived_at < p_cutoff_date
  ORDER BY archived_at LIMIT p_batch_size) DELETE … USING batch …`), zwraca liczbę usuniętych. Walidacja `p_batch_size BETWEEN 1 AND 100000`.
- `cron_log`: brak wpisu przy wyniku 0 (kończy pętlę Javy — bez spamu); przy > 0 jeden wpis na partię (albo agregat — udokumentuj wybór).
- Jawny `DROP` starej sygnatury (nie zostawiać dwóch przeciążeń — niejednoznaczność przy DEFAULT), `COMMENT ON FUNCTION`.
- `SET LOCAL lock_timeout` niepotrzebne (brak DDL na tabeli) — funkcja tworzona `CREATE`, nie zmienia tabel.

**Kryteria akceptacji:**
- [ ] (WP-3) Numer migracji = następna wolna wersja (develop + otwarte gałęzie + `flyway_schema_history`); migracja idempotentna (`DROP FUNCTION IF EXISTS`); w `pg_proc` istnieje wyłącznie sygnatura 3-argumentowa
- [ ] (WP-1) Test Testcontainers na pełnym łańcuchu Flyway (rozszerzenie `CampaignContactArchivePurgeTenantIsolationTest`): 5 wierszy tenanta A starszych niż cutoff, `p_batch_size = 2` → wywołania zwracają 2, 2, 1, 0; tenant B (też stary) nietknięty; wiersze nowsze niż cutoff nietknięte
- [ ] Wywołanie dwuargumentowe (bez `p_batch_size`) nadal działa dzięki DEFAULT i usuwa ≤ 10 000 wierszy — istniejący test izolacji zielony bez zmian asercji
- [ ] Brak wpisu w `cron_log` przy wyniku 0
- [ ] (WP-3) `EXPLAIN` partii na bazie scratch (kopia `pg_dump -s`, ≥ 500 tys. wierszy, 20 tenantów): użyty `idx_cca_tenant_archived_at`, bez Seq Scan; czas partii 10 000 wierszy w notatce
- [ ] Wdrożenie razem z BE-121 (jedno wydanie): do czasu pętli w Javie pojedyncze wywołanie usuwa tylko pierwszą partię — udokumentowane w nagłówku migracji
- [ ] Notatka w pliku zadań, pamięć agenta commitowana razem ze zmianą (WP-7)

**Ryzyka:** zmiana semantyki wartości zwracanej (dotąd całość, teraz partia); jedyny wołający to `CampaignArchiveRetentionRepository#purgeEligible` (BE-121).

---

### DB-057 – Porządki indeksów: duplikaty (`scheduled_callback`, `agent_group_member`) i nieużywane indeksy archiwum — 3 osobne migracje

**Typ:** Schema migration / performance (dług techniczny)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
Każdy zbędny indeks to koszt INSERT/UPDATE i miejsca bez korzyści dla odczytu (DESIGN §2 U14; precedens: DB-055/V093 — audyt strukturalny w
`pg_index`, guard, `lock_timeout`, komentarze). Dowody live (2026-09-20): `idx_callback_ready` i `idx_scheduled_callback_due` mają identyczną
definicję `(tenant_id, scheduled_at) WHERE status = 'PENDING' AND is_deleted = false`; `idx_agent_group_member_lookup` i
`idx_campaign_agent_member_lookup` to identyczne `(agent_id) INCLUDE (group_id)`; `idx_cca_campaign (campaign_id, archived_at DESC)` i
`idx_cca_archived_at (archived_at)` nie mają czytelników (0 skanów na pustej tabeli **nic nie dowodzi** — decyduje grep Java/`pg_proc`/`pg_views`).

**Zakres — trzy migracje, po jednej na tabelę (jedna zmiana = jedna migracja):**
1. `scheduled_callback`: usuń jeden z pary (zostaje ten z lepszymi referencjami/nazwą wg kryteriów DB-055: referencje w kodzie/dokumentacji/`pg_proc`/`pg_views`,
   nazewnictwo); guard, że zwycięzca istnieje. Tylko RAPORT (bez zmian): `idx_callback_scheduled (tenant_id, scheduled_at, status) WHERE status = 'PENDING'` i pokrewne
   częściowe indeksy tabeli (12 indeksów łącznie z PK) — do osobnej oceny.
2. `agent_group_member`: usuń jeden z pary identycznych. Tylko RAPORT: `idx_agent_group_member_agent (agent_id)` jest pokryty covering-indeksem
   `(agent_id) INCLUDE (group_id)`, a `idx_agent_group_member_group (group_id)` jest prefiksem PK `(group_id, agent_id)`.
3. `campaign_contact_archive`: usuń `idx_cca_archived_at` i `idx_cca_campaign` **tylko jeśli w chwili implementacji**: (a) 0 czytelników (grep `backend/`, `voicebot/`,
   `pg_proc`, `pg_views`), (b) D8 nie zdecydowało o czytelnikach archiwum po kampanii, (c) D6 nie wybrało alternatywy wymagającej takiego indeksu (DB-075).
   Zostają: PK, `idx_cca_tenant_archived_at`, `idx_cca_tenant_customer` (guard). W przeciwnym razie migracja 3 pominięta z notatką.

**Kryteria akceptacji:**
- [ ] (WP-3) Każda migracja: numer wg reguły „następna wolna", `SET LOCAL lock_timeout`, guard (DO $$ — indeks-zwycięzca istnieje, inaczej `RAISE EXCEPTION`), `DROP INDEX IF EXISTS`, `COMMENT ON INDEX` zwycięzcy; idempotentna
- [ ] Definicje porównane strukturalnie w `pg_index` (kolumny, `indoption`, opclass, collation, predykat, AM), nie po nazwie
- [ ] Dowód braku regresji: `EXPLAIN` zapytań `ScheduledCallbackExecutor`/`ScheduledCallbackRepository` (callbacki) i wyszukiwania grup agenta pod `SET ROLE app_user` z GUC — przed/po ten sam indeks-zwycięzca (scratch)
- [ ] Diff `pg_dump -s` przed/po = wyłącznie zamierzone DROP + komentarze
- [ ] (WP-1) Pełny łańcuch Flyway (Testcontainers) zielony; bez zmian w kodzie Javy
- [ ] Migracja 3: wyniki grepów w notatce; decyzja „usunięte/pominięte" z uzasadnieniem
- [ ] Wdrożenie poza szczytem (DROP INDEX bierze ACCESS EXCLUSIVE — tabele niepartycjonowane, krótko)

---

### DB-058 – Widoki materializowane `mv_agent_daily_stats` / `mv_campaign_stats`: decyzja DROP vs harmonogram odświeżania

**Typ:** Schema migration / decyzja (dług techniczny)
**Priorytet:** Could Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-076
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (wariant B: + `backend-dev-expert`)

**Kontekst:**
Widoki z V011 miały być odświeżane przez `scheduled_job.refresh_materialized_views` (`0 1 * * *`, funkcja `refresh_materialized_views()`),
ale `pg_cron` nie jest zainstalowany, więc `last_run_at` jest NULL — dane nieaktualne od utworzenia. Zero referencji w `backend/`,
`frontend/src`, `voicebot/`; zero skanów w `pg_stat_user_tables`; brak widoków zależnych (`pg_depend`); rozmiary 48 kB/16 kB; oba mają unikalne
indeksy (`uq_mv_*`), więc `REFRESH … CONCURRENTLY` byłby możliwy. Widoki materializowane nie mogą mieć RLS, a zawierają `tenant_id` (DESIGN §2 U14).

**Założenie do potwierdzenia:** wariant A = **DROP** (zero czytelników). Wariant B = harmonogram odświeżania w Javie (`@Scheduled` 01:00 UTC, wzorzec
`PartitionMaintenanceJob`, `REFRESH MATERIALIZED VIEW CONCURRENTLY`) — wtedy wykonawca dopisuje ticket BE (kolejny wolny numer BE) i zmienia tylko opis w DB-076.

**Zakres:**
- Potwierdź brak czytelników zewnętrznych: `pg_stat_user_tables`, `pg_depend`, `EtlSyncServiceImpl`/ClickHouse (czy ETL czyta widoki), zapytanie do właściciela o BI/raporty.
- Migracja A: `DROP MATERIALIZED VIEW IF EXISTS mv_agent_daily_stats, mv_campaign_stats` + `DROP FUNCTION IF EXISTS refresh_materialized_views()` (jeśli bez innych zależności).

**Kryteria akceptacji:**
- [ ] Notatka z dowodami (grep, `pg_depend`, statystyki, odpowiedź właściciela) i wybranym wariantem
- [ ] (WP-3) Jedna migracja, idempotentna (`IF EXISTS`), numer wg reguły; pełny łańcuch Flyway zielony (WP-1)
- [ ] `pg_matviews` bez usuniętych widoków; brak błędów zależności; diff `pg_dump -s` tylko zamierzony
- [ ] Wpis `scheduled_job.refresh_materialized_views` uzgodniony w DB-076 (nie w tej migracji)

---

### DB-059 – Indeksy `(tenant_id, wiek wiadomości)` dla purge `email_message` i `social_message`

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-124 (potwierdzona semantyka D1 i definicja „wieku" wiadomości)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-127, DB-065, DB-067
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
BE-125/BE-127 wprowadzają `DELETE … WHERE tenant_id = … AND contact_id IN (…)` oraz purge osieroconych
`WHERE tenant_id = … AND contact_id IS NULL AND <wiek> < cutoff` (DESIGN §2 U1). Dziś: `idx_email_message_contact (contact_id, received_at DESC)`,
`idx_email_message_delivery` (częściowy), `uq_email_message_id_header (tenant_id, message_id_header)`, `idx_social_message_contact (contact_id, sent_at DESC)`,
`idx_social_message_sender (tenant_id, platform, sender_external_id)` — brak `(tenant_id, czas)`, więc sweep osieroconych = Seq Scan przy każdej partii
(analogia do DB-053/V089). Live: `email_message` 55 wierszy, `social_message` 0 — problem pojawi się dopiero na wolumenie, ale indeks jest tani teraz.

**Zakres (jedna migracja — jeden cel):**
- `email_message`: `CREATE INDEX idx_email_message_tenant_orphan_age ON email_message (tenant_id, (COALESCE(received_at, sent_at, created_at))) WHERE contact_id IS NULL`
  — ta sama definicja „wieku wiadomości", która stanie się `message_at` w DB-067 (zapisz w komentarzu).
- `social_message`: `CREATE INDEX idx_social_message_tenant_orphan_sent ON social_message (tenant_id, sent_at) WHERE contact_id IS NULL`.
- Zwykły `CREATE INDEX IF NOT EXISTS` (wzorzec V089; tabele małe). Gdy tabele produkcyjne są duże: `-- flyway:executeInTransaction=false` + `CREATE INDEX CONCURRENTLY` (wykonawca sprawdza rozmiar).
- Konwersje DB-065/DB-067 odtwarzają te indeksy na tabelach partycjonowanych (AC w tamtych ticketach).

**Kryteria akceptacji:**
- [ ] (WP-3) Migracja idempotentna, numer wg reguły, `COMMENT ON INDEX` z definicją „wieku"
- [ ] (WP-3) `EXPLAIN` zapytania sweepu osieroconych **dokładnie w postaci z BE-127** (to samo wyrażenie `COALESCE`) na scratch (≥ 200 tys. wierszy, 60 tenantów, ~20 % osieroconych) → Index/Bitmap Scan na nowym indeksie, bez Seq Scan; pod `SET ROLE app_user` z GUC
- [ ] `DELETE … WHERE tenant_id = … AND contact_id IN (…)` używa `idx_email_message_contact`/`idx_social_message_contact` (EXPLAIN w notatce)
- [ ] Rozmiar indeksów zmierzony (koszt zapisu); pełny łańcuch Flyway zielony (WP-1)

---

### DB-060 – Audyt kolumn PII powiązanych z klientem (macierz Art. 17/15) — raport bez migracji

**Typ:** Analiza / dokumentacja (bez zmian schematu)
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-061, DB-062, BE-129
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
D3 wymaga rozszerzenia RODO, ale DESIGN §2 U2–U4 pokazał, że (a) przepływ Java (`GdprServiceImpl`) nie woła funkcji SQL i pomija wiadomości,
(b) funkcje SQL pomijają `scheduled_callback`, `campaign_contact*`, `contact.notes`, transkrypcje. Zanim ktokolwiek zmieni funkcje, potrzebna jest kompletna macierz.
Znane kolumny PII (live): `contact` (`remote_address`, `channel_metadata`, `notes`, `recording_url`), `scheduled_callback` (`phone` NOT NULL, `first_name`, `last_name`, `notes`),
`campaign_contact`/`campaign_contact_archive` (`phone`, `first_name`, `last_name`, `email`, `custom_fields` — wszystkie nullable; częściowy unikalny indeks
`idx_campaign_contact_phone_unique (campaign_id, phone) WHERE phone IS NOT NULL`), `customer.custom_fields`/`gdpr_consent` (Java ich nie czyści).

**Zakres:**
- Zapytanie do `information_schema.columns` + `pg_constraint`: WSZYSTKIE tabele z `customer_id` lub `contact_id` (nie tylko lista wyżej) — dołącz wynik do notatki.
- Dla każdej tabeli: kolumny PII, sposób powiązania z klientem (bezpośrednio/przez `contact`), stan dziś w: `anonymize_customer` (V013), `export_customer_data` (V017), `GdprServiceImpl`
  (Java). Obowiązkowo: `contact_transcription.content`, `contact_ai_summary` (ocena D3: PII jako treść rozmowy), `email_message`, `social_message`, obiekty S3 (nagrania, EML = `contact.recording_url`,
  `email-attachments/…`, pending), `contacts_dw`/ClickHouse (czy zawierają PII), `audit_log` (`old_value`/`new_value`/`ip_address` — rekomendacja: poza zakresem lub maskowanie).
- Rekomendacja per luka (anonimizuj / usuń / zostaw + uzasadnienie, np. statystyki bez PII) i wskazanie ticketu wykonawczego (DB-062, DB-061, BE-129).
- Ograniczenie do udokumentowania: wiadomości odcięte przed EPIC-30 (`contact_id IS NULL`) nie są przypisywalne do klienta — usuwa je BE-127 wg retencji.

**Kryteria akceptacji:**
- [ ] Macierz obejmuje wszystkie tabele z `customer_id`/`contact_id` (zapytanie w notatce), kolumny: tabela | kolumny PII | powiązanie | Art. 17 dziś | Art. 15 dziś | rekomendacja | ticket
- [ ] Ocena transkrypcji i `contact_ai_summary` z przykładami struktury danych (bez ujawniania PII w notatce)
- [ ] Potwierdzenie greppem: brak wywołań `anonymize_customer`/`export_customer_data` w Javie (U2) + lista miejsc, gdzie `GdprServiceImpl`/`CustomerRepository#anonymize` pomija dane
- [ ] Zakłada D3 = A (rozszerzyć funkcje SQL i podłączyć do Javy): przy D3 = B/C wykonawca zaznacza w notatce, które luki przechodzą do BE-129 / zostają otwarte
- [ ] (WP-4) Wyłącznie zapytania tylko-do-odczytu (`SET default_transaction_read_only = on`); notatka w pliku zadań + pamięć agenta (WP-7)

---

### DB-061 – Naprawa i rozszerzenie `export_customer_data` (RODO Art. 15/20)

**Typ:** Schema migration / bugfix
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-060
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-129
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
`export_customer_data(p_customer_id UUID, p_tenant_id UUID) RETURNS JSONB` (V013, zastąpiona w V017) jest `STABLE` (`pg_proc.provolatile = 's'`), a jej ciało kończy się
`INSERT INTO audit_log` — PostgreSQL odrzuca taki INSERT w funkcji nie-VOLATILE (`ERROR: INSERT is not allowed in a non-volatile function`; zachowanie zreprodukowane na funkcji
sondującej w `pg_temp`, właściwa funkcja NIE została wywołana — DESIGN §2 U3). Poza tym: wiadomości tylko jako metadane (komentarz V017), brak `scheduled_callback`,
`campaign_records` bez `phone`/imienia/nazwiska mimo komentarza „dane osobiste uwzględnione", brak `contact.notes` i transkrypcji. Funkcja nie ma dziś wołającego w Javie (U2).

**Zakres:**
1. Potwierdź U3 na bazie scratch: `SELECT export_customer_data(<klient>, <tenant>)` w transakcji z `ROLLBACK` → oczekiwany błąd; wynik do notatki.
2. Nowa migracja `CREATE OR REPLACE FUNCTION export_customer_data(…)`: **rekomendacja** — usuń `INSERT INTO audit_log` z funkcji (audyt `GDPR_EXPORT` zapisuje Java: `GdprServiceImpl` już to robi; unika podwójnego wpisu) i zostaw `STABLE`;
   alternatywa: `VOLATILE` z zachowanym INSERT — wykonawca uzasadnia wybór.
3. Rozszerz zwracany JSONB o zbiory z macierzy DB-060 (wg D3): `scheduled_callbacks`; `campaign_records` z polami PII klienta; `contact.notes` w `contacts`; transkrypcje i podsumowania AI (jeśli DB-060 zaleca);
   treść wiadomości e-mail/social — zakres wg rekomendacji DB-060 (Art. 15 daje prawo do kopii danych osobowych; komentarz V017 zakładał same metadane).
4. Stabilny kontrakt JSON (klucze, typy) — konsumuje go BE-129.

**Kryteria akceptacji:**
- [ ] Reprodukcja U3 zapisana w notatce; po zmianie funkcja zwraca JSONB bez błędu
- [ ] (WP-1) Test Testcontainers: klient z kontaktami, wiadomościami e-mail i social, callbackiem, rekordami `campaign_contact` i archiwum, transkrypcją → eksport zawiera wszystkie zbiory z macierzy; klient/tenant obcy nie wycieka (asercje po wartościach)
- [ ] Wywołanie z `p_tenant_id` innego tenanta → wyjątek/brak danych obcego tenanta
- [ ] (WP-4) Pod `SET ROLE app_user` z GUC `app.current_tenant_id`: funkcja działa albo wymagane uprawnienie/`SECURITY DEFINER` (z `SET search_path`) jest udokumentowane — tabele wiadomości mają RLS
- [ ] Wydajność: klient z 10 tys. wiadomości < 2 s na scratch (EXPLAIN, indeksy po `contact_id`)
- [ ] (WP-3) Jedna migracja, idempotentna, numer wg reguły; `COMMENT ON FUNCTION` zaktualizowany
- [ ] Zakłada D3 = A; przy D3 = B funkcja pozostaje nieużywana (do decyzji o usunięciu), a eksport buduje Java (BE-129)

**Ryzyka:** rozmiar JSONB dla klientów z dużą historią (BE-129 może wymagać strumieniowania/limitu).

---

### DB-062 – Rozszerzenie `anonymize_customer` (RODO Art. 17)

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-060
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-129
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
`anonymize_customer(p_customer_id, p_tenant_id, p_user_id) RETURNS VOID` (V013, VOLATILE) anonimizuje `customer`, `email_message` i `social_message` (WYŁĄCZNIE przez
`contact_id IN (SELECT contact_id FROM contact WHERE customer_id = …)` — po odcięciu z U1 wiadomości są nieosiągalne), `contact.remote_address`/`channel_metadata`;
NIE dotyka `scheduled_callback`, `campaign_contact`, `campaign_contact_archive`, `contact.notes`, `contact_transcription`, `contact_ai_summary`, `contact.recording_url`; zwraca VOID
(Java nie zna zakresu ani kluczy S3 do usunięcia); blok `EXCEPTION WHEN OTHERS` przepisuje błąd. Nikt jej dziś nie woła (U2) — wpina ją BE-129.

**Zakres (nowa migracja, jawny `DROP FUNCTION` starej sygnatury, bo zmienia się typ zwracany):**
- Według macierzy DB-060: `scheduled_callback` (`phone` NOT NULL → `'ANONYMIZED'`, imię/nazwisko → `'ANONYMIZED'`, `notes` → NULL);
  `campaign_contact` i `campaign_contact_archive` (`phone` → NULL — częściowy unikalny indeks `(campaign_id, phone) WHERE phone IS NOT NULL` nie obejmuje NULL; imię/nazwisko `'ANONYMIZED'`, `email` NULL, `custom_fields` `'{}'`);
  `contact.notes` → NULL; `contact.recording_url` → NULL (po zebraniu kluczy); transkrypcje i podsumowania AI wg D3 (DELETE `WHERE contact_id IN (…)`, tabele partycjonowane — pełny PK); `customer.custom_fields`/`gdpr_consent` (już w V013).
- `RETURNS JSONB`: `{ "counts": { "<tabela>": n, … }, "s3_keys": [ "…" ] }` — klucze S3 zebrane PRZED wyzerowaniem: `contact.recording_url` (nagrania i EML) oraz `attachments[*].s3_key` z wiadomości; Java usuwa obiekty po commit (BE-129).
- Zachowaj wpis audytu `CUSTOMER_ANONYMIZED` (bez PII w `old_value`). Idempotencja: drugie wywołanie = zerowe liczniki, bez błędu.

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers: klient z 2 kontaktami (jeden z `recording_url` i `notes`), 3 e-mailami (załączniki z `s3_key`), 2 wiadomościami social, 2 callbackami, 3 rekordami `campaign_contact`, 2 w archiwum, transkrypcją i podsumowaniem → po wywołaniu żadna kolumna PII z macierzy nie zawiera oryginalnej wartości; `s3_keys` = komplet kluczy
- [ ] Izolacja: drugi klient i drugi tenant nietknięte (asercje po wartościach); wiadomości osierocone (`contact_id IS NULL`) nietknięte i udokumentowane jako ograniczenie
- [ ] Idempotencja i atomowość: wymuszony błąd w połowie → pełny ROLLBACK
- [ ] Placeholder nie łamie unikalności: dwa rekordy tego samego klienta w jednej kampanii (test na `idx_campaign_contact_phone_unique`)
- [ ] (WP-4/R1) Pod `SET ROLE app_user` z GUC: UPDATE/DELETE na tabelach bez polityk zapisu nie jest po cichu pomijany (test: liczniki > 0) albo `SECURITY DEFINER` z `SET search_path` udokumentowane
- [ ] (WP-3) Jedna migracja, numer wg reguły, pełny łańcuch Flyway; brak innych wołających starej sygnatury (grep)
- [ ] Zakłada D3 = A (transkrypcje/podsumowania uznane za PII); przy D3 = „nie" — pomiń DELETE transkrypcji i zapisz w notatce

---

### DB-063 – [WARUNKOWY: D1 = C] Kategoria `MESSAGE_CONTENT` w schemacie polityk retencji

**Typ:** Schema migration
**Priorytet:** Could Have (wchodzi do zakresu wyłącznie przy D1 = osobna kategoria)
**Złożoność:** S
**Zależy od:** BE-124 (potwierdzenie D1 = C)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-130
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
Przy D1 = C treść wiadomości ma własną retencję niezależną od kontaktów. Live: **trzy** CHECK-i ograniczają zbiór kategorii do 4 wartości —
`tenant_retention_policy_data_category_check` (V082), `tenant_retention_pending_summary_data_category_check` (V083) i `retention_purge_log_data_category_check` (V084);
DDL w opisach DB-046..048 tego nie pokazuje, więc wykonawca czyta nazwy i definicje z `pg_constraint`. Przy założeniu D1 = A ten ticket nie jest wykonywany.

**Zakres:** jedna migracja: przebudowa 3 CHECK-ów (5 wartości), backfill wiersza `MESSAGE_CONTENT` dla istniejących tenantów (`INSERT … ON CONFLICT DO NOTHING`) z `retention_months` równym wartości
`CONTACT_INTERACTIONS` danego tenanta (spójność z D1 = A; NIE płaski default — jak w DB-046) i `auto_purge_enabled = FALSE`; RLS bez zmian.

**Kryteria akceptacji:**
- [ ] Wszystkie 3 CHECK-i akceptują `MESSAGE_CONTENT`, odrzucają `BOGUS`
- [ ] Każdy istniejący tenant ma dokładnie 5 wierszy polityk; wartość = `CONTACT_INTERACTIONS` tenanta (test z niestandardową wartością)
- [ ] (WP-3) Idempotentna, numer wg reguły, pełny łańcuch Flyway (WP-1); RLS bez regresji pod `SET ROLE app_user`

---

### DB-064 – RLS `email_message` i `social_message`: `FOR SELECT` → `ALL` + `WITH CHECK` + `FORCE`

**Typ:** Schema migration (bezpieczeństwo, defense-in-depth)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-065, DB-067, BE-139
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
V012 utworzyło `pol_email_message_select` i `pol_social_message_select` wyłącznie `FOR SELECT`, RLS `ENABLE` bez `FORCE`. Brak polityki INSERT/UPDATE/DELETE = deny dla roli
ograniczonej — pod rolą bez BYPASSRLS zapisy wiadomości, aktualizacja statusu dostarczenia (`EmailMessageRepository#update`) i purge byłyby odrzucane; demo tego nie widzi, bo `ccapp` = superuser + BYPASSRLS
(DESIGN §2 U8, R1). `RlsValidationService` sprawdza tylko istnienie jakiejkolwiek polityki. Wzorzec: `plugin_invocation_log_isolation` (V077, ALL + WITH CHECK), V082–V084 (`FORCE`).

**Zakres (jedna migracja — jedna zmiana „polityki zapisu wiadomości"):** dla obu tabel `DROP POLICY IF EXISTS pol_*_select`, `CREATE POLICY <tabela>_tenant_isolation … USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)
WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::uuid)`, `ALTER TABLE … FORCE ROW LEVEL SECURITY` (GUC `app.current_tenant_id`, NIE `app.tenant_id`).
Lista ścieżek zapisu do przeglądu (w notatce): `EmailPollingServiceImpl` (scheduler), `EmailSendServiceImpl`, `EmailContactCreator` (RabbitMQ), `SocialMessageServiceImpl` (webhook/consumer), `RetentionPurgeServiceImpl` (`@Async` + snapshot), `GdprServiceImpl`.
Ścieżki bez kontekstu tenanta zgłaszamy jako defekty — NIE osłabiamy polityki.

**Kryteria akceptacji:**
- [ ] (WP-1/WP-4) Test Testcontainers pod `SET ROLE app_user` (rola z V012): INSERT/UPDATE/DELETE własnego tenanta OK; cross-tenant INSERT odrzucony (WITH CHECK); cross-tenant SELECT/UPDATE/DELETE = 0 wierszy; bez GUC — 0 wierszy i INSERT odrzucony
- [ ] `relrowsecurity = t` i `relforcerowsecurity = t` dla obu tabel
- [ ] Lista ścieżek zapisu przejrzana, wyniki uruchomienia pod rolą ograniczoną w BE-139 (link); defekty ścieżek bez kontekstu zgłoszone
- [ ] (WP-3) Jedna migracja, idempotentna, numer wg reguły; test regresyjny (nie wolno testować przez `ccapp` ani po nazwie partycji potomnej)
- [ ] Konwersje DB-065/DB-067 odtwarzają polityki 1:1 (AC w tamtych ticketach)
- [ ] (WP-4) Weryfikacja na żywo w local-demo: polling IMAP, odpowiedź e-mail i webhook social działają po przebudowie obrazów (uwaga: `ccapp` omija RLS — dowodem jest test pod `app_user`, nie demo)

---

### DB-065 – Partycjonowanie `social_message` (RANGE po `sent_at`)

**Typ:** Schema migration (partycjonowanie online)
**Priorytet:** Should Have
**Złożoność:** M (0 wierszy = najtańsze okno na zmianę klucza; zgodnie z oceną zlecenia)
**Zależy od:** BE-126 (Poziom 1 usuwa wiadomości), DB-059, DB-064
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-132
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
Realni kandydaci do partycjonowania czasowego to tylko `email_message` i `social_message` (DESIGN §2 U6/U15). `social_message`: PK `(message_id)`, `uq_social_message_external_id (tenant_id, external_message_id)`,
`sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, FK do `tenant` (RESTRICT) i `social_integration` (SET NULL), `platform` = enum `social_platform`, CHECK-i `chk_social_attachments_is_array`, `chk_social_message_direction`.
Live: 0 wierszy. **PR #44 (WhatsApp) dodał zapis do `social_message` — zanim zaczniesz, przeczytaj aktualny kod** (`SocialMessageServiceImpl`, `SocialMessageConsumer`, adaptery) i wypisz zmiany w notatce.

**Zakres (jedna migracja; wzorzec DB-049/V085 i V088):**
1. Guard: `COUNT(*)` przed migracją; powyżej progu (np. 10 000) migracja przerywa się z komunikatem (wtedy inna strategia backfillu).
2. `social_message_new … PARTITION BY RANGE (sent_at)`: PK `(message_id, sent_at)`, `UNIQUE (tenant_id, external_message_id, sent_at)` (D4 dla social: dedup aplikacyjny `findByExternalMessageId` pozostaje główną obroną; constraint jest siecią bezpieczeństwa
   tylko przy deterministycznym `sent_at` — patrz BE-132).
3. Partycje: zakres istniejących danych (przy 0 wierszy: bieżący miesiąc) + bieżący/+2 + `DEFAULT`.
4. Kopia danych, `RENAME`, odtworzenie: indeksy (`idx_social_message_contact`, `idx_social_message_sender`, indeksy z DB-059), CHECK-i, FK, **RLS z DB-064 (ALL + WITH CHECK + FORCE)**, komentarze; `DROP social_message_old` dopiero po weryfikacji liczby wierszy (blok `DO $$ … RAISE EXCEPTION`).
5. Funkcja `create_social_message_partition(p_year INT, p_month INT)` (wzorzec `create_contact_event_partition`, V088). **Bez wykonywalnego `drop_old_social_message_partitions`** (WP-6: DROP idzie wyłącznie przez `PartitionReclaimJob` z regułą „tylko puste").
6. `CREATE OR REPLACE create_next_month_partitions()` + `PERFORM create_social_message_partition(…)` — skopiuj AKTUALNĄ definicję (po V088 i ewentualnych nowszych) i dodaj jedną linię.
7. Indeks `(tenant_id, sent_at)` pod purge (jak DB-053/V089).

**Kryteria akceptacji:**
- [ ] (WP-3) Numer migracji wg reguły, jedna migracja, scratch (`pg_dump -s`) + pełny łańcuch Flyway; `SET LOCAL lock_timeout`
- [ ] Liczba wierszy przed == po (weryfikacja w migracji); dodatkowo test na scratch z 100 tys. syntetycznych wierszy w 3 miesiącach: dane w poprawnych partycjach, pruning po `sent_at` widoczny w `EXPLAIN`
- [ ] RANGE po `sent_at`, PK `(message_id, sent_at)`, unikalność złożona, FK (`tenant`, `social_integration`) i CHECK-i zachowane, enum `platform` bez zmian
- [ ] (WP-1/WP-4) RLS ALL + WITH CHECK + FORCE pod `SET ROLE app_user` **przez tabelę nadrzędną** (partycje potomne mają `relrowsecurity = f` — nie testować po nazwie partycji); cross-tenant INSERT odrzucony
- [ ] (WP-6) `create_social_message_partition(2027, 1)` tworzy partycję z indeksami; `create_next_month_partitions()` obejmuje 7 tabel; brak wykonywalnej `drop_old_social_message_partitions`
- [ ] Test regresyjny kluczowy: wiersz z `sent_at` w bieżącym miesiącu trafia do partycji miesięcznej, NIE do `social_message_default` (`tableoid::regclass`)
- [ ] (WP-1) Test Testcontainers: dwa INSERT-y z tym samym `(tenant_id, external_message_id, sent_at)` → naruszenie unikalności; z różnym `sent_at` → przechodzą (udokumentowane ograniczenie D4)
- [ ] Wdrożenie w jednym wydaniu z BE-132; przy > 0 wierszy: backup i okno serwisowe
- [ ] Zakłada D1 = A (Poziom 1 usuwa wiadomości przed DROP); przy D1 = B (anonimizacja) sens partycjonowania maleje — ticket do ponownej oceny

---

### DB-066 – [BRAMKA] Pomiar wolumenu `email_message`, próg partycjonowania (D2) i projekt deduplikacji (D4)

**Typ:** Analiza / decyzja (bez zmian schematu)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak (zalecana kolejność: po wdrożeniu Poziomu 1, żeby mierzyć stan po nim)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-067, DB-068
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
Konwersja `email_message` to L (klucz złożony, dedup, ~20 plików — DESIGN §2 U6/U7), a PRD nie podaje wolumenów (NFR-S03: 50 × 100 agentów; nic o kontaktach/dzień). Demo: 55 wierszy, 216 kB, średni wiersz 1049 B (max 14,5 kB), więc nie rozstrzyga.
Ten ticket dostarcza liczby i decyzje; sam niczego nie zmienia.

**Zakres:**
1. Skrypt SQL (tylko odczyt) do uruchomienia na środowisku docelowym (staging/prod-like; demo = punkt odniesienia): `pg_total_relation_size`/`pg_relation_size`/`pg_indexes_size`, `count(*)` i `reltuples`, rozkład miesięczny po
   `COALESCE(received_at, sent_at, created_at)`, średni i p95 rozmiaru wiersza (`pg_column_size`), udział wiadomości z załącznikami i suma `size_bytes` z `attachments` (S3), `n_dead_tup` z `pg_stat_user_tables`,
   czasy purge z `retention_purge_log` (`completed_at - started_at`, `rows_deleted`).
2. Tempo wzrostu (wierszy i GB/mies.) i prognoza 12/24 mies.; porównanie z progami **G1–G4 z DESIGN §3 D2** → rekomendacja go/no-go i skorygowane progi.
3. Brak danych produkcyjnych → model z wejściem od PO (e-maile/dzień/tenant × liczba tenantów) + lista pytań do PO; bramka pozostaje „nierozstrzygnięta".
4. Projekt D4: źródła `message_at` (INBOUND: `Message#getSentDate()` → `getReceivedDate()` → `now()`; OUTBOUND: `sentAt` ustawiane raz), pomiar, ile wiadomości bez nagłówka Date w próbce, decyzja A (unikalność złożona) vs B (tabela dedup).
5. Projekt załączników: kolejność Poziom 1 (S3 → wiersz) przed DROP, plan dla istniejących kluczy `s3_key` w JSONB, lifecycle `pending/` (BE-131).

**Kryteria akceptacji:**
- [ ] Raport z liczbami z ≥ 1 środowiska, jednoznacznie oznaczony: prod / stage / demo / model; brak liczb prod → lista pytań do PO i status „bramka nierozstrzygnięta"
- [ ] Decyzja go/no-go zapisana w notatce (G1–G4 z wartościami) i w DESIGN §3 D2 (aktualizacja ZAŁOŻENIA)
- [ ] Wybór D4 (A/B) zapisany; przy B tickety DB-068/BE-136 przechodzą z [WARUNKOWY] do wymaganych
- [ ] (WP-4) Skrypty uruchamiane wyłącznie tylko-do-odczytu (`SET default_transaction_read_only = on`); (WP-3) pomiary na scratch z `SET max_parallel_maintenance_workers = 0; SET max_parallel_workers_per_gather = 0` przed VACUUM/CREATE INDEX

---

### DB-067 – Partycjonowanie `email_message` (kolumna `message_at`, backfill, PK/unikalność, RLS, funkcje)

**Typ:** Schema migration (partycjonowanie online) — [BRAMKOWANY: wchodzi po „go" z DB-066]
**Priorytet:** Should Have
**Złożoność:** L (zgodnie z oceną zlecenia: klucz złożony, backfill, dedup)
**Zależy od:** DB-066 (go), DB-064, DB-059, BE-127 (Poziom 1 działa)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-134, DB-068
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
`email_message`: PK `(message_id)` i `uq_email_message_id_header (tenant_id, message_id_header) DEFERRABLE INITIALLY DEFERRED` uniemożliwiają partycjonowanie
(„unique constraint on partitioned table must include all partitioning columns"). `received_at` jest NULL dla OUTBOUND, `sent_at` NULL dla INBOUND, `created_at` niedeterministyczne — stąd nowa kolumna
`message_at TIMESTAMPTZ NOT NULL` (D4). Sprawdzone na PG 16.13: **unikalność DEFERRABLE jest dozwolona na tabeli partycjonowanej** (nie jest przeszkodą), `ON CONFLICT` z deferrable nie działa (kod go nie używa).

**Zakres (zwykle dwie migracje — wykonawca decyduje przy dużym wolumenie):**
1. M1: `ALTER TABLE email_message ADD COLUMN message_at TIMESTAMPTZ` + backfill `COALESCE(received_at, sent_at, created_at)` (batchami) + trigger `BEFORE INSERT` uzupełniający `message_at` jako sieć bezpieczeństwa do wdrożenia BE-134 + `SET NOT NULL` po backfillu.
2. M2: `email_message_new … PARTITION BY RANGE (message_at)`: PK `(message_id, message_at)`; unikalność wg D4 = A: `UNIQUE (tenant_id, message_id_header, message_at) DEFERRABLE INITIALLY DEFERRED` (przy D4 = B — bez unikalności po `message_id_header`, patrz DB-068);
   partycje (zakres danych + bieżący/+2 + `DEFAULT`); kopia, `RENAME`, odtworzenie indeksów (`idx_email_message_contact`, `idx_email_message_delivery`, indeks z DB-059, nowy `(tenant_id, message_at)`), CHECK-i (`chk_email_attachments_is_array`, `chk_email_message_direction`), FK do `tenant`, RLS z DB-064, komentarze; `DROP email_message_old` po weryfikacji.
3. `create_email_message_partition(p_year, p_month)`; **bez wykonywalnego `drop_old_email_message_partitions`** (SQL nie zna S3 — WP-5/WP-6); `create_next_month_partitions()` (kopia aktualnej definicji + 1 linia).
4. `message_at` niemodyfikowalne (trigger `BEFORE UPDATE` odrzucający zmianę albo udokumentowany zakaz) — zmiana klucza partycji przenosiłaby wiersz między partycjami.

**Kryteria akceptacji:**
- [ ] (WP-3) Numery wg reguły, `SET LOCAL lock_timeout`, scratch + pełny łańcuch Flyway; plan wycofania (RENAME z powrotem przed `DROP …_old`)
- [ ] 100 % wierszy ma `message_at NOT NULL` = `COALESCE(received_at, sent_at, created_at)`; liczba wierszy przed == po (RAISE EXCEPTION w migracji)
- [ ] (WP-1) Test Testcontainers: duplikat `(tenant_id, message_id_header, message_at)` odrzucony; ten sam nagłówek z innym `message_at` przechodzi (udokumentowane ograniczenie D4 — obroną jest dedup aplikacyjny BE-134)
- [ ] (WP-4) RLS ALL + WITH CHECK + FORCE pod `SET ROLE app_user` przez tabelę nadrzędną; cross-tenant INSERT odrzucony
- [ ] `EXPLAIN` zapytań `EmailMessageRepository` (`findByContactId`, `findFirstInboundByContactId`, `findByMessageIdHeader`, `findByThreadRootMessageId`, `findAll`) na scratch ≥ 500 tys. wierszy i 12 partycjach — czasy przed/po w notatce (zapytania bez klucza partycji skanują lokalne indeksy wszystkich partycji)
- [ ] (WP-6) `create_next_month_partitions()` obejmuje wszystkie tabele partycjonowane; test `tableoid`: bieżący miesiąc → partycja miesięczna, nie `_default`; brak wykonywalnego `drop_old_email_message_partitions`
- [ ] Wdrożenie w jednym wydaniu z BE-134; okno serwisowe; backup
- [ ] Zakłada D2 (bramka przekroczona), D4 = A i D1 = A; przy D4 = B unikalność przenosi się do DB-068; przy D1 = B ticket do ponownej oceny

---

### DB-068 – [WARUNKOWY: D4 = B] Tabela deduplikacji `email_message_dedup`

**Typ:** Schema migration
**Priorytet:** Could Have (warunkowy)
**Złożoność:** M
**Zależy od:** DB-066 (wybór B), DB-067
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-136
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:** przy D4 = B globalną unikalność `(tenant_id, message_id_header)` przejmuje niepartycjonowana tabela (DESIGN §3 D4); ma **własną retencję**, inaczej rośnie bez końca.

**Zakres:** `email_message_dedup (tenant_id UUID NOT NULL REFERENCES tenant ON DELETE CASCADE, message_id_header VARCHAR(255) NOT NULL, message_id UUID NOT NULL, first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY (tenant_id, message_id_header))`;
RLS ALL + WITH CHECK + FORCE (GUC `app.current_tenant_id`); indeks `(tenant_id, first_seen_at)` pod purge; backfill z `email_message` (`DISTINCT ON (tenant_id, message_id_header)`); DB-067 zbudowane bez unikalności po `message_id_header`.

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers: dwa równoległe INSERT `ON CONFLICT DO NOTHING` z tym samym `(tenant, header)` → 1 wiersz; backfill = liczba różnych niepustych nagłówków
- [ ] RLS pod `SET ROLE app_user`, cross-tenant INSERT odrzucony; (WP-3) jedna migracja, numer wg reguły
- [ ] Purge wg `first_seen_at` (definicja progu = max retencja kategorii wiadomości) opisany i przetestowany w BE-136

---

### DB-069 – [BRAMKOWANY] Partycjonowanie `campaign_contact_archive` RANGE(`archived_at`)

**Typ:** Schema migration (partycjonowanie)
**Priorytet:** Could Have — **nie wykonywać przed spełnieniem warunku wejścia**
**Złożoność:** L
**Zależy od:** DB-056, DB-072 (purge w partiach i RLS kształtują projekt partycjonowanej tabeli); dodatkowo bramka wolumenowa — patrz Kontekst
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-137
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
Pomiar agenta na scratch (1,2 mln wierszy, DESIGN §2 U12): partycjonowanie nie zmienia kosztu purge per tenant (~250 ms w obu wariantach); zysk tylko przy globalnym „ogonie" (DELETE 400 tys. + VACUUM ≈ 1,6 s i ≈ 129 MB WAL vs `DROP` 28 ms i 0,35 MB WAL).
Rekomendacja: NIE partycjonować teraz. **Warunek wejścia** (szacunek do kalibracji): ≥ 50–100 mln wierszy LUB problem z WAL/lagiem repliki wywołany purge LUB globalny DELETE „ogona" > 30 s. Sprawdzenie warunku = zapytanie
(count/`reltuples`, `pg_total_relation_size`, czasy CAMPAIGN_DATA z `retention_purge_log`) zapisane w notatce; do tego czasu ticket „śpi".

**Zakres (po spełnieniu warunku):** PK musi zawierać `archived_at` → `(record_id, campaign_id, archived_at)`; **`ON CONFLICT (record_id, campaign_id)` w `archive_completed_campaign_contacts()` przestaje działać** — przepisać idempotencję (np. `WHERE NOT EXISTS`);
`purge_campaign_contact_archive` (DB-056) bez zmiany semantyki (indeks lokalny `(tenant_id, archived_at)`); `export_customer_data`/`anonymize_customer` (DB-061/062) — zapytania po `customer_id` skanują wszystkie partycje (indeks lokalny `(tenant_id, customer_id)`);
funkcje `create_campaign_contact_archive_partition`, `create_next_month_partitions()`; RLS z DB-072.

**Kryteria akceptacji:**
- [ ] Warunek wejścia udokumentowany liczbami; pomiar DELETE vs DROP na scratch ≥ warunek (z `SET max_parallel_maintenance_workers = 0` przed VACUUM)
- [ ] (WP-1) Test Testcontainers: idempotencja archiwizacji (dwa uruchomienia = brak duplikatów), izolacja purge per tenant na tabeli partycjonowanej
- [ ] (WP-3/WP-6) Migracja wg reguł; maszyneria (funkcje, `create_next_month_partitions()`); brak wykonywalnego `drop_old_*` — DROP przez `PartitionReclaimJob` (BE-137)

---

### DB-070 – `campaign_contact`: decyzja o dekoracyjnym partycjonowaniu LIST (zostawić / uprościć / HASH)

**Typ:** Decyzja (ADR) / dług techniczny
**Priorytet:** Could Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-077
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
`campaign_contact` jest `PARTITION BY LIST (campaign_id)`, ale istnieje wyłącznie `campaign_contact_default`; żaden kod nie tworzy partycji (grep `PARTITION OF` w `backend/app/src/main` = 0; w V009 tylko komentarz),
a `documentation/tech/06-database.md:282` i `ARCHITECTURE.md:503` twierdzą inaczej (DESIGN §2 U13). Pomiar agenta: `CREATE … PARTITION OF` przy istniejącym DEFAULT bierze `ACCESS EXCLUSIVE` na rodzicu i na DEFAULT;
HASH(`campaign_id`) działa z PK `(record_id, campaign_id)` i częściowym unikalnym `(campaign_id, phone)`, pruning działa.

**Zakres — ADR z liczbami:** opcje (A) zostawić LIST z jedyną partycją DEFAULT, (B) uprościć do zwykłej tabeli (shadow + RENAME; ryzyko: ścieżka gorąca dialera, `ACCESS EXCLUSIVE`), (C) HASH(`campaign_id`) 16–32 partycji — dopiero przy > ok. 50 mln wierszy.
**ZAŁOŻENIE DO POTWIERDZENIA: A + sprostowanie dokumentacji (DB-077)**; B/C tylko przy dowodzie pomiarowym. Skrypt progu (count, rozmiar, plan zapytań dialera `idx_campaign_contact_dialer`) do uruchamiania przy przeglądach.

**Kryteria akceptacji:**
- [ ] ADR w notatce z dowodami: `pg_inherits`, `count(*)` (demo: 37), `EXPLAIN` zapytania dialera, koszt utrzymania każdej opcji
- [ ] Decyzja właściciela zapisana; lista linii dokumentacji do poprawy przekazana do DB-077
- [ ] Przy A: brak zmian schematu; przy C: opis kroków migracji (shadow HASH, kopia, RENAME, zachowanie `idx_campaign_contact_dialer`, RLS z DB-073, `lock_timeout`) — bez implementacji przed progiem

---

### DB-071 – Klasyfikacja tabel z `tenant_id` bez pełnego RLS (raport)

**Typ:** Analiza (bez zmian schematu)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-072, DB-073, DB-074, BE-138, BE-139
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:**
DESIGN §2 U8/U9. Live: bez RLS — `campaign_contact`, `campaign_contact_archive` (PII), `contacts_dw`, `email_routing_rule`, `email_template`, `gdpr_processing_register`, `ivr_audio`, `plugin_version`, `refresh_token` (37 wierszy z `tenant_id` NULL = SUPER_ADMIN);
polityka tylko-SELECT — `audit_log`, `ivr_tree`, `queue`, `app_user`, `email_message`, `social_message`; `customer`/`campaign` mają SELECT+INSERT+UPDATE bez DELETE, `contact` tylko SELECT+INSERT. Nie każda tabela jest tenantowa (katalogi globalne, `tenant`), a niektóre mają
ścieżki pre-tenant (logowanie/`findByToken`, publiczne endpointy `TenantFilter.PUBLIC_PATH_PREFIXES`).

**Zakres:** dla KAŻDEJ tabeli z kolumną `tenant_id` (`information_schema.columns` + `pg_class` + `pg_policies`): klasa TENANT (RLS wymagane) / GLOBAL (świadomie bez RLS + uzasadnienie) / MIXED (`tenant_id` NULL = globalne, np. `refresh_token`, `audit_log`);
macierz komend SELECT/INSERT/UPDATE/DELETE × polityka (brak polityki = deny pod rolą ograniczoną); ścieżki bez kontekstu tenanta, które złamałoby RLS (auth, publiczne endpointy, `@Scheduled`, RabbitMQ, `EtlSyncServiceImpl`); proponowana polityka per tabela
(MIXED: odczyt `tenant_id IS NULL OR tenant_id = current_setting(…)`, wyjątek dla ścieżki auth); podział wykonania na DB-072 (archiwum), DB-073 (`campaign_contact`), DB-074 (reszta).

**Kryteria akceptacji:**
- [ ] Macierz kompletna (wszystkie tabele z `tenant_id`), każda z klasą i decyzją RLS tak/nie + uzasadnieniem; zapytanie źródłowe w notatce
- [ ] Lista ścieżek pre-tenant/scheduler/RabbitMQ z odniesieniem do klas Javy
- [ ] Decyzje do właściciela zebrane (D7 dla `campaign_contact*`); (WP-4) wyłącznie zapytania tylko-do-odczytu; notatka + pamięć agenta (WP-7)

---

### DB-072 – RLS `campaign_contact_archive` (D7)

**Typ:** Schema migration (bezpieczeństwo, PII)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** DB-071, BE-120 (rozstrzygnięcie ścieżki `archive_completed_campaign_contacts()` pod rolą ograniczoną)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-069, DB-073
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:**
Archiwum zawiera PII (`phone`, `first_name`, `last_name`, `email`) i nie ma RLS; klauzula `WHERE tenant_id = p_tenant_id` w `purge_campaign_contact_archive` jest JEDYNĄ izolacją (komentarz V091). **Uwaga:**
`archive_completed_campaign_contacts()` wstawia do archiwum wiersze WIELU tenantów w jednym wywołaniu — pod rolą bez BYPASSRLS `WITH CHECK` odrzuciłby wiersze innych tenantów niż GUC. Rozwiązanie wybrane w BE-120/R5 (rola serwisowa/`SECURITY DEFINER`
albo pętla per tenant) musi być spójne z tą polityką.

**Zakres:** jedna migracja: `ENABLE` + `FORCE` RLS, polityka ALL + WITH CHECK (GUC `app.current_tenant_id`); dostosowanie funkcji SQL wg decyzji z BE-120 (osobna migracja, jeśli wymagana).

**Kryteria akceptacji:**
- [ ] (WP-1/WP-4) Test pod `SET ROLE app_user`: izolacja SELECT/INSERT/UPDATE/DELETE; `purge_campaign_contact_archive(tenant, cutoff, batch)` z GUC tenanta działa i nie rusza innych; rozszerzenie `CampaignContactArchivePurgeTenantIsolationTest`
- [ ] Zachowanie `archive_completed_campaign_contacts()` pod rolą ograniczoną opisane i zgodne z decyzją BE-120; `export_customer_data`/`anonymize_customer` (DB-061/062) działają pod rolą z GUC
- [ ] (WP-3) Jedna migracja, numer wg reguły; **zakłada D7 = TAK — przy D7 = NIE ticket anulowany**, a izolację utrzymuje wyłącznie filtr `tenant_id` (testy izolacji zyskują na wadze)

---

### DB-073 – RLS `campaign_contact` (tabela partycjonowana LIST, ścieżka gorąca dialera) (D7)

**Typ:** Schema migration (bezpieczeństwo, PII)
**Priorytet:** Should Have
**Złożoność:** M (ścieżka gorąca dialera; przegląd wszystkich ścieżek bez kontekstu tenanta)
**Zależy od:** DB-071, DB-072
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `backend-dev-expert`, `test-suite-expert`)

**Kontekst:** `campaign_contact` (PII: `phone`, `first_name`, `last_name`, `email`) nie ma RLS. Dialer (`ProgressiveDialerServiceImpl` `@Scheduled`), `CampaignWindowActivator`, `ScheduledCallbackExecutor`, `DialerCallbackHandlerImpl`,
`CampaignImportServiceImpl`, `EtlSyncServiceImpl` i `archive_completed_campaign_contacts()` czytają/zapisują tę tabelę potencjalnie bez kontekstu tenanta — po włączeniu RLS pod rolą ograniczoną straciłyby widoczność wierszy.

**Zakres:** `ENABLE` + `FORCE` + polityka ALL + WITH CHECK na rodzicu (RLS nie dziedziczy się na partycję `campaign_contact_default` — zapytania zawsze przez rodzica; grep: 0 zapytań po nazwie partycji);
**przegląd ścieżek** (lista klas z oceną „ma kontekst / nie ma / wymaga pętli per tenant") w notatce; `EXPLAIN` zapytań dialera po dodaniu predykatu polityki.

**Kryteria akceptacji:**
- [ ] (WP-1/WP-4) Pod `SET ROLE app_user`: izolacja 4 komend; wszystkie ścieżki `@Scheduled`/`@Async`/RabbitMQ z przeglądu przetestowane pod rolą ograniczoną (BE-139) — brak cichej utraty wierszy
- [ ] `EXPLAIN` zapytań dialera (`idx_campaign_contact_dialer`, `idx_campaign_contact_dialer_tenant`) bez regresji planu; czas przed/po na scratch
- [ ] (WP-3) Jedna migracja, numer wg reguły, `lock_timeout` (ALTER … ENABLE RLS na tabeli partycjonowanej); (WP-4) dialer w local-demo działa po przebudowie (uwaga: `ccapp` omija RLS — dowodem jest test pod `app_user`)
- [ ] **Zakłada D7 = TAK**; przy D7 = NIE ticket anulowany

---

### DB-074 – RLS pozostałych tabel tenantowych wg klasyfikacji (uzupełnienie komend, FORCE)

**Typ:** Schema migration (bezpieczeństwo) — zbiorczy, wykonawca dzieli na migracje po tabeli/zmianie
**Priorytet:** Should Have
**Złożoność:** M
**Zależy od:** DB-071
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect` (+ `test-suite-expert`)

**Kontekst:** wynik DB-071: tabele klasy TENANT bez RLS (`email_routing_rule`, `email_template`, `ivr_audio`, `gdpr_processing_register`, `contacts_dw` — jeśli TENANT) oraz tabele z niepełnym pokryciem komend (`audit_log`, `ivr_tree`, `queue`, `app_user` — tylko SELECT;
`contact` — bez UPDATE/DELETE; `customer`, `campaign` — bez DELETE). Uwaga na `audit_log`: `AuditLogConsumer` zapisuje zdarzenia globalne z `tenant_id` NULL → polityka INSERT `WITH CHECK (tenant_id IS NULL OR tenant_id = current_setting(…))`. Wyłączone: tabele MIXED wg klasyfikacji (np. `refresh_token`) — osobna decyzja.

**Kryteria akceptacji:**
- [ ] Lista tabel z klasyfikacji DB-071; każda migracja: polityki dla brakujących komend + WITH CHECK + FORCE tam, gdzie decyzja „tak"; ścieżki pre-tenant (auth, publiczne endpointy) sprawdzone i wyłączone/obsłużone
- [ ] (WP-1/WP-4) Test pod `SET ROLE app_user` dla każdej tabeli (4 komendy, cross-tenant, bez GUC); dla `contact` udowodnione, że purge (`DELETE`) działa pod rolą ograniczoną (dziś: 0 usuniętych wierszy po cichu)
- [ ] (WP-3) Jedna migracja na tabelę/zmianę, numery wg reguły, pełny łańcuch Flyway; `RlsValidationService` (BE-138) zaktualizowany listą
- [ ] Zakłada D7 = TAK dla PII; pozostałe tabele wg klasyfikacji

---

### DB-075 – [WARUNKOWY: D6 = koniec kampanii] Baza czasowa `CAMPAIGN_DATA` w archiwum

**Typ:** Schema migration
**Priorytet:** Could Have (warunkowy)
**Złożoność:** M
**Zależy od:** BE-120, DB-056
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-140
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:** przy założeniu D6 = `archived_at` zaległa archiwizacja (BE-120: 30 kampanii) startuje zegar retencji od dnia pierwszego uruchomienia (DESIGN §3 D6). Tabela `campaign` nie ma kolumny końca kampanii (tylko `created_at`, `updated_at`, `schedule` JSONB) —
proxy końca to `updated_at` w chwili archiwizacji lub data z `schedule`.

**Zakres:** `ADD COLUMN campaign_ended_at TIMESTAMPTZ` w `campaign_contact_archive`, backfill z `campaign.updated_at` (udokumentuj ograniczenie proxy), `NOT NULL` po backfillu; zmiana `archive_completed_campaign_contacts()` (zapis `campaign_ended_at`),
`purge_campaign_contact_archive` (filtr po `campaign_ended_at`), indeks `(tenant_id, campaign_ended_at)`.

**Kryteria akceptacji:**
- [ ] (WP-1) Test: zaległa kampania (koniec 3 lata temu, zarchiwizowana dziś) kwalifikuje się do purge natychmiast przy retencji 24 mies.; izolacja tenantów
- [ ] Backfill 100 % wierszy, `EXPLAIN` purge używa nowego indeksu; (WP-3) jedna migracja per zmianę, numer wg reguły

---

### DB-076 – `scheduled_job`: uzgodnienie wpisów z rzeczywistymi wykonawcami (Java `@Scheduled`)

**Typ:** Schema migration (dane) / dokumentacja
**Priorytet:** Could Have
**Złożoność:** S
**Zależy od:** BE-120, BE-122, BE-123, DB-058
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-077
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:** `scheduled_job` (11 wpisów, V014/V077/V088) opisuje zadania „pg_cron", z których żadne nie jest wykonywane przez pg_cron; `last_run_at` ma tylko `create_next_month_partitions` (aktualizuje je funkcja wołana przez `PartitionMaintenanceJob`).
Wpisy `cleanup_expired_refresh_tokens`, `refresh_materialized_views` mają `last_run_at` NULL — to dokumentacja bez wykonania (DESIGN §2 U10).

**Zakres:** jedna migracja (data-only, idempotentna): `UPDATE scheduled_job` — opis wskazuje faktycznego wykonawcę (`PartitionMaintenanceJob`, `PartitionReclaimJob`, `RetentionEvaluationJob`, `CampaignArchiveJob`, `RefreshTokenCleanupJob`) albo „brak (backstop SQL, nieaktywny)";
`is_active = FALSE` dla wpisów bez wykonawcy (pg_cron nieobecny). Funkcje SQL zostają (backstop) — ewentualnie `COMMENT ON FUNCTION`. Bez nowych kolumn.

**Kryteria akceptacji:**
- [ ] `SELECT job_name, is_active, description FROM scheduled_job` zgodne z rzeczywistością (tabela job → wykonawca w notatce); migracja idempotentna, nie zmienia funkcji; (WP-3) numer wg reguły

---

### DB-077 – Dokumentacja: `06-database.md`, `ARCHITECTURE.md` (pg_cron, dynamiczne partycje, retencja, funkcje RODO)

**Typ:** Documentation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** BE-120, BE-122, BE-123, DB-070, DB-076 (tickety fal 1–3 aktualizują swoje fragmenty we własnym DoD)
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `db-schema-architect`

**Kontekst:** dokumentacja twierdzi rzeczy sprzeczne z faktami (DESIGN §2 U10, U13).

**Zakres (lokalizacje zweryfikowane 2026-09-20 — przed edycją sprawdź numery linii):** `documentation/tech/06-database.md`: ~196 (`refresh_token` „czyszczone przez pg_cron" → `RefreshTokenCleanupJob`), ~277 (archiwum: wypełniane przez job + flaga D8), ~282–284 (**„partycje tworzone dynamicznie przez aplikację" — nieprawda**: jedyna partycja `campaign_contact_default`, wg DB-070),
~372–380 (`cron_log` i „pg_cron (V014)" → Java `@Scheduled`: `PartitionMaintenanceJob`, `PartitionReclaimJob`, `RetentionEvaluationJob`, …), ~633 (retencja `audit_log` 2 lata → `PartitionReclaimJob` i konfigurowalny horyzont, BE-123), nowa sekcja o retencji treści wiadomości (Poziom 1/2, S3) i o klucz JSONB `attachments[*].s3_key` (a nie `s3_url`);
`ARCHITECTURE.md`: ~503–504 (dynamiczne partycje `campaign_contact`), 848 (`drop_old_audit_log_partitions()` przez pg_cron), 1071 (tabela „pg_cron jobs"). Po edycji: `node documentation/build-html.js` (regeneracja śledzonych `documentation/tech/html/*.html`).

**Kryteria akceptacji:**
- [ ] `grep -rn pg_cron documentation/ ARCHITECTURE.md` — każde wystąpienie zgodne z faktem albo oznaczone „nieaktywne, zastąpione przez …"; brak twierdzenia o dynamicznych partycjach `campaign_contact`
- [ ] Wygenerowany HTML, linki do `DESIGN-message-retention-and-partitioning.md`; opis funkcji RODO zgodny z BE-129 (funkcje SQL wołane z Javy)
