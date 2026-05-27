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
**Blokuje:** brak
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
**Blokuje:** DB-011, DB-015, DB-017
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
**Blokuje:** BE-058
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
**Status:** ⬜ Nie rozpoczęte
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
- [ ] Migracja V062 aplikuje się bez błędów
- [ ] Kolumna `campaign.all_agents BOOLEAN NOT NULL DEFAULT FALSE` istnieje
- [ ] Istniejące kampanie mają `all_agents = TRUE` po migracji
- [ ] Tabela `campaign_agent` z PK `(campaign_id, agent_id)` i indeksami
- [ ] Tabela `campaign_agent_group` z PK `(campaign_id, group_id)` i indeksami
- [ ] CASCADE DELETE: usunięcie kampanii usuwa wiersze z obu tabel przypisania
- [ ] CASCADE DELETE: usunięcie agenta usuwa jego wiersze z `campaign_agent`
- [ ] CASCADE DELETE: usunięcie grupy usuwa jej wiersze z `campaign_agent_group`

---

### DB-037 – Kolumna `campaign_contact_record_id` w tabeli `contact` — migracja V063

**Typ:** Schema migration
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-011 (`campaign_contact`), DB-006 (`contact`)
**Status:** ⬜ Nie rozpoczęte
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
- [ ] Migracja V063 aplikuje się bez błędów na dev i test
- [ ] Kolumna `contact.campaign_contact_record_id UUID` istnieje i jest nullable
- [ ] Indeks `idx_contact_campaign_contact_record` widoczny w `pg_indexes` z filtrem `WHERE ... IS NOT NULL`
- [ ] Istniejące wiersze kontaktów nie są naruszone (NULL backfill)
- [ ] Tabela `contact` jest partycjonowana — `ADD COLUMN` propaguje na wszystkie partycje automatycznie

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
