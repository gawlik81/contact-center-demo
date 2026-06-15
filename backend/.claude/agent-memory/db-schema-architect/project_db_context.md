---
name: project-db-context
description: Kluczowe fakty o schemacie bazy danych Contact Center — konwencje, PK, RLS, Flyway
metadata:
  type: project
---

Projekt Contact Center SaaS — PostgreSQL 16, Flyway, Spring Boot, multi-tenant.

**Kluczowa konwencja: klucz główny tabeli `tenant` to `tenant_id` (UUID), NIE `id`.**
Wszystkie FK do tej tabeli muszą używać `REFERENCES tenant(tenant_id)`.

**Aktualny stan migracji (2026-05-28):** Ostatnia zastosowana migracja to V071.

**Konwencje schematów:**
- Każda tabela ma `tenant_id UUID NOT NULL` + RLS policy (USING + WITH CHECK) + FORCE ROW LEVEL SECURITY
- RLS używa `current_setting('app.current_tenant_id', TRUE)::UUID`
- PK: `UUID PRIMARY KEY DEFAULT gen_random_uuid()` (lub `uuid_generate_v4()` w starszych migracjach)
- Timestamps: `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- Triggery `fn_set_updated_at()` do automatycznej aktualizacji `updated_at`

**Tabele EPIC-27 (custom dispositions):**
- `custom_disposition` — V069 — dyspozycje per kampania/kolejka
- `disposition_set` — V071 — szablony zestawów wielokrotnego użytku
- `disposition_set_item` — V071 — elementy zestawów (kopiowane jako snapshot przy przypisaniu)

**Dostęp do bazy lokalnie:**
- Kontener: `cc-postgres`, user: `ccapp`, baza: `contact_center`
- `docker exec cc-postgres psql -U ccapp -d contact_center`

**Why:** Unikanie błędów FK (tenant.id vs tenant.tenant_id) i niezgodności RLS.
**How to apply:** Przed każdą nową migracją z FK do tenant — sprawdź że używa `tenant(tenant_id)`. Przed ręcznym stosowaniem migracji przez psql — zawsze oblicz CRC32 (signed) i zarejestruj w flyway_schema_history.
