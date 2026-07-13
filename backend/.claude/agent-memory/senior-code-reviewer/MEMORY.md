# Memory Index

- [RLS Inconsistency: V069 dead policy](project_rls_inconsistency.md) — V069 custom_disposition uses wrong RLS setting name 'app.tenant_id'; V023 sets 'app.current_tenant_id'
- [Recurring Anti-Patterns](project_recurring_antipatterns.md) — Missing @Transactional, rollback-only trap, N+1 in listSets, TOCTOU uniqueness checks, maxLength mismatch, Polish pluralization, em.detach() in native-SQL repos, DB-only (non-batch-aware) bulk-import dedup, partial i18n migration, JSON-array `null`-element crashes whole import job, `customFields` corrupted via `String.valueOf()`, TenantContext.isSet() false alarms for nullable-tenant roles
- [SUPER_ADMIN role refactor context](project_super_admin_role_refactor.md) — what changed (SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT), review outcome and ratings (2026-07-12)
- [Manual security fixes verified](feedback_manual_security_fixes_verified.md) — TenantServiceImpl + AuthServiceImpl IDOR fixes confirmed correct, don't re-flag
- [Tracked env file with real secrets](project_tracked_env_file_secrets.md) — .env.local-demo is git-tracked with placeholders; check diffs for real secrets before any commit
