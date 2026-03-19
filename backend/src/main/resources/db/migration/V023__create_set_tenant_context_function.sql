-- =============================================================================
-- V023__create_set_tenant_context_function.sql
-- Tworzy funkcję set_tenant_context(uuid) wymaganą przez TenantAwareRepository
-- do ustawiania app.current_tenant_id przed zapytaniami korzystającymi z RLS.
--
-- Polityki RLS (V012) odczytują: current_setting('app.current_tenant_id', TRUE)::UUID
-- TenantAwareRepository wywołuje: SELECT set_tenant_context(CAST(? AS uuid))
-- =============================================================================

CREATE OR REPLACE FUNCTION set_tenant_context(p_tenant_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    -- TRUE = ustawienie lokalne dla bieżącej transakcji (resetowane po COMMIT/ROLLBACK)
    PERFORM set_config('app.current_tenant_id', p_tenant_id::TEXT, TRUE);
END;
$$;

COMMENT ON FUNCTION set_tenant_context(UUID) IS
    'Ustawia app.current_tenant_id dla bieżącej transakcji. '
    'Wywoływana przez TenantAwareRepository przed każdym zapytaniem korzystającym z RLS. '
    'Wartość jest automatycznie resetowana po zakończeniu transakcji.';
