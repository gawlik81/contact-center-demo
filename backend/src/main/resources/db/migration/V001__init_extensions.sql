-- =============================================================================
-- V001__init_extensions.sql
-- DB-001: Inicjalizacja rozszerzen PostgreSQL i konfiguracja schematu bazowego
--
-- Migracja: Flyway V001
-- Zaleznosci: brak (pierwsza migracja)
-- Opis: Wlaczenie rozszerzen uuid-ossp, pg_trgm, pgcrypto.
--       Ustawienie search_path. Konfiguracja bazowa schematu public.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. Rozszerzenia PostgreSQL
-- ---------------------------------------------------------------------------

-- uuid-ossp: generowanie UUID v4 przez uuid_generate_v4()
-- Uzywane jako DEFAULT dla kluczy glownych we wszystkich tabelach
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- pg_trgm: indeksy trigram dla fuzzy search (wyszukiwanie klientow po imieniu/nazwisku)
-- Uzywane w DB-012 (CUSTOMER) – operator %, indeksy GIN gin_trgm_ops
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- pgcrypto: funkcje kryptograficzne (szyfrowanie AES-256 dla tokenow social media)
-- Uzywane w DB-008 (SOCIAL_INTEGRATION) – access_token_encrypted BYTEA
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- 2. Konfiguracja search_path
-- ---------------------------------------------------------------------------

-- Upewniamy sie, ze wszystkie obiekty tworzone sa w schemacie public
-- (domyslne zachowanie, jawne dla czytelnosci)
SET search_path TO public;

-- ---------------------------------------------------------------------------
-- 3. Komentarz do schematu
-- ---------------------------------------------------------------------------

COMMENT ON SCHEMA public IS
    'Contact Center SaaS – schemat operacyjny. '
    'Multi-tenant: kazda tabela zawiera kolumne tenant_id UUID NOT NULL. '
    'Izolacja danych egzekwowana przez warstwe repozytorium + Row Level Security (DB-015).';

-- ---------------------------------------------------------------------------
-- 4. Weryfikacja (zapytanie diagnostyczne – mozna wykonac recznie po migracji)
-- ---------------------------------------------------------------------------
-- SELECT extname, extversion FROM pg_extension
--   WHERE extname IN ('uuid-ossp', 'pg_trgm', 'pgcrypto');
-- Oczekiwany wynik: 3 wiersze
