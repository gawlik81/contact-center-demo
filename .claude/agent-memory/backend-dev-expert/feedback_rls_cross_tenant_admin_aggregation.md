---
name: feedback_rls_cross_tenant_admin_aggregation
description: Jak agregować dane cross-tenant (SUPER_ADMIN) dla tabel z FORCE ROW LEVEL SECURITY — nigdy nie bypassować RLS jednym zapytaniem
type: feedback
---

Dla endpointów SUPER_ADMIN potrzebujących agregacji cross-tenant na tabelach z `FORCE ROW LEVEL SECURITY`
(`contact`, `campaign`, `customer`, `queue` — patrz V012__row_level_security.sql) NIE używaj jednego
zapytania `WHERE tenant_id = ANY(:ids)` ani żadnej innej formy bypassu RLS. Zamiast tego pętla po
`TenantService.getAllTenants()` (tabela `tenant` jest globalna, bez RLS) i N zapytań — jedno per tenant,
każde z jawnym `setTenantContextInDb(tenantId)` przed SELECT-em.

**Why:** To jest już ustanowiony, udokumentowany idiom w tym repo — patrz
`PluginCatalogQueryServiceImpl.findAllEnabledAcrossTenantsByVersionId`/`findAllEnabledAcrossAllTenants`
(jawny komentarz: "N zapytań, jedno per tenant, zero bypassu RLS — projekt nie posiada ani jednego
wzorca bypassu RLS, zweryfikowano"). Sugestia z promptu implementacyjnego (`tenant_id = ANY(:ids)` w
jednym zapytaniu) działałaby TYLKO przypadkiem w dev (DB_USERNAME domyślnie `postgres` = superuser,
zawsze bypassuje RLS niezależnie od FORCE), ale na produkcji pod rolą `app_user` (NOBYPASSRLS) zwróciłaby
milcząco 0 wierszy zamiast błędu — cichy bug bardzo trudny do wykrycia.

Wyjątek: `ContactRepository.findTenantsWithRecordings()` jawnie bypassuje RLS, ale WYŁĄCZNIE dla
scheduled jobów bez aktywnego TenantContext (z guardem `IllegalStateException` gdy TenantContext jest
ustawiony) — to jedyny akceptowany wyjątek od reguły, nie wzorzec do powielania w nowym kodzie API.

**How to apply:** Przy nowych metrykach/raportach cross-tenant (AdminMetrics, przyszłe dashboardy
SUPER_ADMIN) zawsze projektuj jako pętlę po tenantach z jawnym tenant context per iterację. Liczba
tenantów jest bounded (SaaS B2B, dziesiątki/setki), więc N zapytań jest akceptowalne, zwłaszcza gdy
wynik jest cache'owany (Redis TTL 5-30 min dla tego typu endpointów).
