---
name: JdbcTemplate set_tenant_context – parametryzowane zapytanie zamiast string concat
description: Wzorzec bezpiecznego ustawiania RLS tenant context przez JdbcTemplate
type: feedback
---

Wzorzec NIEBEZPIECZNY (SQL injection risk):
```java
jdbcTemplate.execute("SELECT set_tenant_context('" + tenantId + "'::uuid)");
```

Wzorzec POPRAWNY:
```java
jdbcTemplate.update("SELECT set_tenant_context(?::uuid)", tenantId.toString());
```

Lub wydziel prywatną metodę `setTenantContextInJdbc(UUID tenantId)` w klasie gdzie JdbcTemplate jest wstrzyknięty.

**Why:** String concatenation UUID teoretycznie bezpieczna (UUID ma ograniczony format), ale narusza zasadę parametryzowania i jest flagowana przez code review. Użycie `update` zamiast `execute` pozwala na bindowanie parametrów.

**How to apply:** Wszędzie gdzie JdbcTemplate.execute() jest używane do set_tenant_context – zamień na update() z parametrem. Dotyczy: DialerCallbackHandler, ProgressiveDialerService, CampaignContactRepository, ScheduledCallbackRepository.
