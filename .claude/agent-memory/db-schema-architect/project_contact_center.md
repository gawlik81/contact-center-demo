---
name: contact_center_project
description: Opis projektu Contact Center SaaS – kluczowe informacje architektoniczne i podjete decyzje
type: project
---

Projekt: Wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant).
Stack: PostgreSQL 16 (operacyjna), ClickHouse (DW), Redis (cache/sesje), RabbitMQ (broker), Flyway (migracje), Debezium (CDC).
Backend: Java Spring Boot (modularny monolit Faza 1). Frontend: Angular SPA.

**Why:** PRD v1.0 z 2026-03-12. Faza 1 = MVP z kanałami PHONE/EMAIL/SOCIAL_MEDIA.

**How to apply:** Przy kolejnych zadaniach DB zakładaj że V001-V035 już istnieją. Numery migracji kontynuuj od V036+.

Kluczowe decyzje architektoniczne:
- Izolacja logiczna przez tenant_id (nie osobne schematy/bazy) + RLS jako dodatkowa warstwa
- Partycjonowanie RANGE miesięczne: tabele CONTACT i AUDIT_LOG
- Partycjonowanie LIST po campaign_id: tabela CAMPAIGN_CONTACT
- Soft delete: is_deleted BOOLEAN na CUSTOMER i APP_USER
- UUID v4 (uuid-ossp) jako klucze główne we wszystkich tabelach
- Fuzzy search: pg_trgm trigram indexes na CUSTOMER (first_name || last_name)
- JSONB dla: phone[], email[], skills[], custom_fields, config, gdpr_consent, IVR definition
- Tabela APP_USER (nie "user" – słowo zarezerwowane w PostgreSQL)

Znane pułapki i poprawki:
- V003 linia 172: indeks idx_refresh_token_cleanup na refresh_token(expires_at, is_revoked) — usunięto predykat WHERE z NOW() (STABLE, nie IMMUTABLE). Indeks jest kompozytowy bez predykatu; pg_cron filtruje warunki po stronie zapytania.
- V007 linia 176 (naprawiono 2026-03-13): started_at::DATE na kolumnie TIMESTAMPTZ w wyrażeniu indeksowym — STABLE, nie IMMUTABLE (wynik zależy od TimeZone GUC). Zamieniono na zwykłą kolumnę started_at w indeksie. Reguła ogólna: nigdy nie używaj ::DATE, AT TIME ZONE, DATE_TRUNC w wyrażeniach indeksowych na kolumnach timestamptz.
- V011 linia 116 i 126 (naprawiono 2026-03-13): te same błędy co V007 — dwa indeksy z (started_at::DATE) DESC. Zamieniono na started_at DESC bez rzutowania.

Lokalizacja migracji:
- PostgreSQL: D:\CloudeAI\contact-center-demo\backend\src\main\resources\db\migration\
- Seed DEV: D:\CloudeAI\contact-center-demo\backend\src\main\resources\db\seed\V999__dev_seed.sql
- ClickHouse DW: D:\CloudeAI\contact-center-demo\dw\migrations\

Stan migracji po V035 (2026-04-08):
- V034__add_error_status_to_campaign_contact.sql: status ERROR dla campaign_contact
- V035__contact_search_indexes.sql (DB-022): indeksy wyszukiwania kontaktów dla EPIC-12 Raporty > Kontakty
  - idx_contact_queue_date: (tenant_id, queue_id, started_at) – filtrowanie po kolejce i zakresie dat (BE-036)
  - idx_contact_duration: (tenant_id, duration_seconds) WHERE duration_seconds IS NOT NULL – filtrowanie po czasie trwania (BE-036)
  - Oba z CREATE INDEX IF NOT EXISTS; propagują do partycji automatycznie (PostgreSQL 11+)
  - Odblokowano: BE-036 GET /api/contacts z filtrami queueId/dateFrom/dateTo/durationMin/Max

Stan migracji po V065 (2026-05-24):
- V064__create_tenant_ai_config.sql (DB-038): konfiguracja dostawcy AI per tenant.
  - Enum ai_provider: ANTHROPIC | OPENAI | AZURE_OPENAI
  - UNIQUE (tenant_id) — jeden rekord per tenant, FK ON DELETE CASCADE
  - api_key_encrypted TEXT: klucz API szyfrowany AES-256-GCM przez JPA EncryptedStringConverter
  - Pola Azure-only: azure_endpoint, azure_deployment_name (nullable)
  - summary_prompt_template TEXT NULL: nadpisuje domyślny prompt aplikacji; NULL = użyj domyślnego
  - Partial index WHERE is_active; RLS USING current_setting('app.tenant_id', TRUE)::UUID
- V065__add_ai_summary_to_contact.sql (DB-039): pola podsumowania AI w tabeli contact.
  - ai_summary TEXT NULL: treść podsumowania (generowane na żądanie agenta)
  - ai_summary_model VARCHAR(100) NULL: nazwa modelu który wygenerował (np. claude-opus-4-7)
  - ai_summary_generated_at TIMESTAMPTZ NULL: timestamp generowania
  - ADD COLUMN IF NOT EXISTS — propaguje do partycji automatycznie (tabela jest partycjonowana RANGE)

Stan migracji po V062 (2026-05-21):
- V062__campaign_agent_assignment.sql (DB-036): trójpoziomowe przypisanie agentów do kampanii wychodzącej.
  - campaign.all_agents BOOLEAN DEFAULT FALSE: TRUE = wszyscy agenci tenanta, FALSE = jawne przypisanie
  - Istniejące kampanie: UPDATE SET all_agents = TRUE (backward compat)
  - campaign_agent: many-to-many kampania ↔ agent (bezpośrednie), PK (campaign_id, agent_id), CASCADE DELETE
  - campaign_agent_group: many-to-many kampania ↔ agent_group, PK (campaign_id, group_id), CASCADE DELETE
  - Indeksy pokrywające: idx_campaign_agent_group_lookup INCLUDE(group_id), idx_campaign_agent_member_lookup na agent_group_member(agent_id) INCLUDE(group_id)
  - BUILD SUCCESS: mvn verify -pl app -DskipTests (01:15 min)

Stan migracji po V053 (2026-05-08):
- V053__add_not_reached_callback_status.sql (DB-032): rozszerzenie CHECK constraint na campaign_contact i campaign_contact_archive o statusy NOT_REACHED i CALLBACK; przebudowa idx_campaign_contact_dialer (teraz WHERE status IN ('PENDING', 'NO_ANSWER') – retry); przebudowa mv_campaign_stats z nowymi kolumnami not_reached_records i callback_records; COMMENT ON COLUMN campaign_contact.status z opisem wszystkich 10 statusów.

Stan migracji po V052 (2026-05-05):
- V049__add_version_columns.sql: kolumny wersjonowania
- V050__add_preferred_language_to_app_user.sql: preferred_language na app_user
- V051__create_tenant_twilio_config.sql (DB-030): tabela tenant_twilio_config – konfiguracja Twilio per tenant, UNIQUE (tenant_id), FK ON DELETE CASCADE, wrażliwe pola (account_sid, auth_token, api_key_sid, api_key_secret) szyfrowane AES-256-GCM przez JPA AttributeConverter (baza przechowuje Base64(IV||ciphertext)), partial index WHERE is_active, RLS USING current_setting('app.current_tenant_id', TRUE)::uuid, komentarze kolumn dokumentujące szyfrowanie. Seed V999 uzupełniony o placeholder konfiguracje dla obu tenantów testowych.
- V052__add_caller_id_to_campaign.sql (DB-031): kolumna caller_id VARCHAR(30) NULL na tabeli campaign (format E.164, addytywna/idempotentna), partial index idx_campaign_caller_id ON (tenant_id, caller_id) WHERE caller_id IS NOT NULL AND is_deleted = FALSE. NULL = fallback do tenant_twilio_config.phone_number.

Stan migracji po V048 (2026-04-25):
- V048__agent_break.sql: tabela przerw agentów (agent_break), klucz UUID (uuid_generate_v4()), FK do tenant i app_user ON DELETE RESTRICT, CHECK constraints na break_type (LUNCH/SHORT_BREAK/TRAINING/OTHER), status (PLANNED/ACTIVE/COMPLETED/CANCELLED) i end_time > start_time, indeks kompozytowy (tenant_id, agent_id, start_time), RLS USING (current_setting('app.current_tenant_id', TRUE)::uuid)

Stan migracji po V033 (2026-04-08):
- V030__add_error_contact_status.sql: dodanie statusu ERROR do tabeli contact
- V031__add_dialer_indexes.sql: indeksy dla Progressive Dialer (BE-024) – zawierała błędy redundancji naprawione w V033
- V032__create_scheduled_callback.sql: tabela scheduled_callback (klucz: callback_id, statusy: PENDING/PROCESSING/COMPLETED/CANCELLED, agent_id i campaign_id opcjonalne, is_deleted soft-delete, RLS policy)
- V033__fix_dialer_indexes.sql: naprawa redundantnych indeksów z V031 (status usunięty z klucza, zachowany tylko w WHERE), + odbudowa idx_callback_ready na scheduled_callback

Stan migracji po V029 (2026-03-26):
- V029__add_email_address_to_queue.sql: kolumna email_address VARCHAR(255) NULL w tabeli queue, UNIQUE (tenant_id, email_address), CHECK (IS NULL OR LIKE '%@%'), partial index idx_queue_email_address WHERE email_address IS NOT NULL

Stan migracji po DB-002 (2026-03-13):
- V001-V014: wykonane w ramach DB-001
- V015__campaign_contact_archive.sql: tabela archiwum campaign_contact, pelna funkcja archive_completed_campaign_contacts() (zastapienie stubu z V014), funkcja purge_campaign_contact_archive()
- V016__contact_referential_integrity.sql: trigger trg_contact_ref_integrity (FK zastepczy dla partycjonowanej tabeli CONTACT), widoki: v_active_contacts, v_queue_realtime_stats, v_rls_status, v_index_health
- V017__gdpr_archive_export.sql: rozszerzona export_customer_data() obejmujaca archiwum, widok v_customer_timeline (historia klienta – CONTACT + EMAIL + SOCIAL UNION ALL)
