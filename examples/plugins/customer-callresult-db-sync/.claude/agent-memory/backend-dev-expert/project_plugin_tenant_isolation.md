---
name: plugin-tenant-isolation
description: Izolacja katalogu plugin_version per-tenant (EPIC-28, V078) — plugin_version ma tenant_id od migracji V078
metadata:
  type: project
---

`plugin_version` otrzymała `tenant_id` w migracji V078 (EPIC-28). Wcześniej była tabelą globalną.

Kluczowe decyzje architektoniczne:
- `plugin` pozostaje globalną tabelą (pluginKey, displayName, vendor) — bez tenant_id
- `plugin_version` jest per-tenant — każdy upload JAR-a należy do tenanta, który go wgrał
- Klucz S3: `plugins/{tenantId}/{pluginKey}/{version}/{filename}` — zawiera tenantId od V078
- Constraint unikalności: `(plugin_id, version, tenant_id)` — dwa tenanty mogą mieć tę samą wersję tego samego pluginu
- Przy instalacji: sprawdzamy `pluginVersion.getTenantId().equals(tenantId)` — zwracamy 404 (nie 403) dla wersji innego tenanta
- `GET /api/supervisor/plugins/catalog` zwraca wyłącznie wersje bieżącego tenanta (nie globalny katalog)

**Why:** Każdy supervisor widział pluginy wszystkich tenantów — naruszenie izolacji multi-tenant.

**How to apply:** Każda nowa metoda query na plugin_version musi filtrować po tenant_id. Nie używać `findAllByOrderByUploadedAtDesc()` (usunięte) — używać `findByTenantIdOrderByUploadedAtDesc(tenantId)`.
