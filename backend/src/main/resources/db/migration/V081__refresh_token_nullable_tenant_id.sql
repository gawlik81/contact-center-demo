-- =============================================================================
-- V081__refresh_token_nullable_tenant_id.sql
-- Uzupelnienie V080 (refaktor rol SUPER_ADMIN / ADMIN / SUPERVISOR / AGENT):
-- refresh_token.tenant_id musi rowniez dopuszczac NULL.
--
-- Migracja: Flyway V081
-- Zaleznosci: V003 (create_user – definicja refresh_token), V080 (SUPER_ADMIN,
--             app_user.tenant_id nullable)
-- Kontekst: V080 zmienil app_user.tenant_id na nullable dla roli SUPER_ADMIN, ale
--           pominal refresh_token.tenant_id (NOT NULL od V003). Kazde logowanie
--           zapisuje refresh token (AuthServiceImpl.createRefreshToken ustawia
--           tenantId = user.getTenantId()) – dla SUPER_ADMIN (tenant_id == null)
--           INSERT naruszylby NOT NULL constraint i logowanie zakonczyloby sie
--           bledem SQL. Odkryte podczas implementacji backendu (weryfikacja
--           pelnego przeplywu login() -> createRefreshToken()), stad osobny plik
--           zamiast edycji juz zaaplikowanego V080 (zasada z CLAUDE.md).
--
-- Bezpieczenstwo: fk_refresh_token_tenant (V003) juz dopuszcza NULL (FK nie
-- waliduje wartosci NULL) – nie wymaga zmian. RefreshTokenRepository rozszerza
-- JpaRepository (nie TenantAwareRepository), wszystkie zapytania filtruja po
-- user_id, nie tenant_id – brak RLS na tej tabeli (zweryfikowane w V012).
-- =============================================================================

ALTER TABLE refresh_token
    ALTER COLUMN tenant_id DROP NOT NULL;

COMMENT ON COLUMN refresh_token.tenant_id IS
    'FK do tenant. NULL wylacznie dla refresh tokenow wystawionych uzytkownikowi '
    'SUPER_ADMIN (globalny administrator platformy, bez przypisania do tenanta) – '
    'spojnie z app_user.tenant_id (V080).';
