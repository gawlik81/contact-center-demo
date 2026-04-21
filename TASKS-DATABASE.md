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
**Status:** 🔲 Do zrobienia
**Blokuje:** BE-048
**Epic:** EPIC-15 Zakładka Klienci w Agent Desktop
**Flyway:** V046__scheduled_callback_agent_manual_source.sql

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
**Zależy od:** DB-003 (tabela `app_user`)
**Status:** ⬜ Do zrobienia
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
