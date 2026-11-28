-- =============================================================================
-- V153__plugin_installation_degraded_reason.sql
-- EPIC-33 (BE-244/BE-245): explicit cause of health_status = 'DEGRADED' on tenant_plugin_installation.
--
-- WHY: the cause was encoded implicitly in consecutive_failure_count (circuit breaker => DEGRADED with count >= 5,
-- failed load after a config change => DEGRADED with count 0). That is an overloaded column (CLAUDE.md anti-pattern).
-- The cause gets its own, descriptively named column.
--
--   CIRCUIT_OPEN        - circuit breaker opened after CIRCUIT_FAILURE_THRESHOLD consecutive invocation failures
--   CONFIG_LOAD_FAILED  - the plugin instance failed to (re)load after an installation_config change
--
-- CONSISTENCY (decision: SOFT check): only the set of allowed values is enforced here. The invariant
-- "degraded_reason IS NOT NULL <=> health_status = 'DEGRADED'" is enforced by the application (every UPDATE/CAS that
-- changes health_status must set or clear degraded_reason in the same statement). A hard cross-column CHECK is NOT
-- used because of rolling deploy: OLD instances do not know the column; they write DEGRADED without a reason
-- (would violate "DEGRADED => NOT NULL") and, after recovery, write HEALTHY without clearing a reason set by a NEW
-- instance (would violate "NOT NULL => DEGRADED"). Either failure would make the old instance's health write throw.
-- Tightening plan (a FUTURE migration, only after the new code runs on ALL instances):
--   1. UPDATE ... SET degraded_reason = NULL WHERE health_status <> 'DEGRADED';
--   2. re-run the backfill below (DEGRADED rows with NULL reason, by consecutive_failure_count);
--   3. ADD CONSTRAINT ... CHECK ((health_status = 'DEGRADED') = (degraded_reason IS NOT NULL)) NOT VALID;
--   4. ALTER TABLE ... VALIDATE CONSTRAINT ...;
--   5. remove the legacy fallback (NULL reason => derive from the counter) from the Java code.
-- Rows left with DEGRADED + NULL reason by an old instance are treated by the application as legacy
-- (derive: consecutive_failure_count >= 5 => CIRCUIT_OPEN, otherwise CONFIG_LOAD_FAILED).
--
-- No index: the column is read together with the row (admin/catalog views, runtime sync) and never filtered on;
-- cardinality is tiny (rows per tenant x plugin). Existing idx_tenant_plugin_installation_tenant is sufficient.
-- No RLS change: the column is covered by the existing row-level policy tenant_plugin_installation_isolation (FORCE RLS).
-- =============================================================================

ALTER TABLE tenant_plugin_installation
    ADD COLUMN IF NOT EXISTS degraded_reason VARCHAR(32);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_tenant_plugin_installation_degraded_reason'
          AND conrelid = 'tenant_plugin_installation'::regclass
    ) THEN
        ALTER TABLE tenant_plugin_installation
            ADD CONSTRAINT chk_tenant_plugin_installation_degraded_reason
            CHECK (degraded_reason IS NULL OR degraded_reason IN ('CIRCUIT_OPEN', 'CONFIG_LOAD_FAILED'));
    END IF;
END $$;

-- Backfill: existing DEGRADED rows (count >= 5 was the circuit-breaker signature; everything else = failed config load).
-- The threshold 5 mirrors TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD; it is the only duplication of that value
-- and a later change of the Java constant does not affect this historical backfill.
UPDATE tenant_plugin_installation
SET degraded_reason = CASE WHEN consecutive_failure_count >= 5 THEN 'CIRCUIT_OPEN' ELSE 'CONFIG_LOAD_FAILED' END
WHERE health_status = 'DEGRADED' AND degraded_reason IS NULL;

COMMENT ON COLUMN tenant_plugin_installation.degraded_reason IS
    'Cause of DEGRADED: CIRCUIT_OPEN (circuit breaker) or CONFIG_LOAD_FAILED (failed load after config change); NULL unless health_status = DEGRADED (invariant kept by the application, BE-244/BE-245).';
COMMENT ON COLUMN tenant_plugin_installation.consecutive_failure_count IS
    'Consecutive invocation failures (circuit breaker counter). Does NOT encode the cause of DEGRADED any more - see degraded_reason (V153).';
COMMENT ON COLUMN tenant_plugin_installation.health_status IS
    'HEALTHY by default; DEGRADED = circuit breaker opened OR failed load after config change (cause in degraded_reason); DISABLED_BY_ADMIN after manual disable.';
