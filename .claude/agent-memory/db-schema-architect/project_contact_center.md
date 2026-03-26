---
name: contact_center_project
description: Opis projektu Contact Center SaaS – kluczowe informacje architektoniczne i podjete decyzje
type: project
---

Projekt: Wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant).
Stack: PostgreSQL 16 (operacyjna), ClickHouse (DW), Redis (cache/sesje), RabbitMQ (broker), Flyway (migracje), Debezium (CDC).
Backend: Java Spring Boot (modularny monolit Faza 1). Frontend: Angular SPA.

**Why:** PRD v1.0 z 2026-03-12. Faza 1 = MVP z kanałami PHONE/EMAIL/SOCIAL_MEDIA.

**How to apply:** Przy kolejnych zadaniach DB zakładaj że V001-V029 już istnieją. Numery migracji kontynuuj od V030+.

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

Stan migracji po V029 (2026-03-26):
- V029__add_email_address_to_queue.sql: kolumna email_address VARCHAR(255) NULL w tabeli queue, UNIQUE (tenant_id, email_address), CHECK (IS NULL OR LIKE '%@%'), partial index idx_queue_email_address WHERE email_address IS NOT NULL

Stan migracji po DB-002 (2026-03-13):
- V001-V014: wykonane w ramach DB-001
- V015__campaign_contact_archive.sql: tabela archiwum campaign_contact, pelna funkcja archive_completed_campaign_contacts() (zastapienie stubu z V014), funkcja purge_campaign_contact_archive()
- V016__contact_referential_integrity.sql: trigger trg_contact_ref_integrity (FK zastepczy dla partycjonowanej tabeli CONTACT), widoki: v_active_contacts, v_queue_realtime_stats, v_rls_status, v_index_health
- V017__gdpr_archive_export.sql: rozszerzona export_customer_data() obejmujaca archiwum, widok v_customer_timeline (historia klienta – CONTACT + EMAIL + SOCIAL UNION ALL)
