-- =============================================================================
-- V041__social_integration_rls_dml_policies.sql
-- Dodanie polityk RLS INSERT/UPDATE/DELETE dla tabeli social_integration
--
-- Migracja: Flyway V041
-- Zależności: V012 (row_level_security – tworzy role i policy SELECT),
--             V017 (create_social_integration – tworzy tabelę)
--
-- Problem: V012 dodał ENABLE ROW LEVEL SECURITY i policy SELECT dla
-- social_integration, ale pominął polityki DML (INSERT/UPDATE/DELETE).
-- Bez nich operacje zapisu mogły przejść przez RLS bez weryfikacji tenant_id.
--
-- UWAGA: Nie modyfikujemy V012 – Flyway zabrania edycji zastosowanych migracji.
-- =============================================================================

-- INSERT: tenant może wstawić tylko rekordy ze swoim tenant_id
CREATE POLICY pol_social_integration_insert ON social_integration
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- UPDATE: tenant może modyfikować tylko swoje rekordy i nie może zmienić tenant_id
CREATE POLICY pol_social_integration_update ON social_integration
    FOR UPDATE
    USING  (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- DELETE: tenant może usuwać tylko swoje rekordy
CREATE POLICY pol_social_integration_delete ON social_integration
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
