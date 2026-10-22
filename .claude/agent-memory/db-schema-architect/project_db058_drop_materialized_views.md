---
name: project-db058-drop-materialized-views
description: DB-058 (2026-10-09) V132 - DROP mv_agent_daily_stats/mv_campaign_stats + refresh_materialized_views(); wlasciciel potwierdzil wariant A; scheduled_job zostaje dla DB-076
metadata:
  type: project
---

V132__drop_unused_materialized_views.sql: guard DO (pg_depend 'n' na widokach i funkcji, pg_views regex, pg_proc prosrc, cron.job jesli istnieje) + DROP bez CASCADE. Test: Db058DropMaterializedViewsMigrationTest (8 testow, Flyway target po opisie, klony TEMPLATE). Wlasciciel (2026-10-09): wariant A = DROP, brak zewnetrznych czytelnikow BI/raportow.

**Why:** pg_cron nie istnieje, widoki nigdy nie odswiezone, zero czytelnikow (backend/frontend/voicebot/dw, 0 skanow), matview nie moze miec RLS a ma tenant_id.
**How to apply:** wpis scheduled_job.refresh_materialized_views (pg_function wskazuje na nieistniejaca funkcje) zostawiony dla DB-076. Definicje do odtworzenia: V025 sekcja 9 (mv_agent_daily_stats), V053 sekcja 4 (mv_campaign_stats). Pulapka testowa: nie mozna zrobic widoku z kolumna typu void (rzutuj ::text). DB-070 ADR (TASKS-DATABASE.md ~4066) wspomina mv_campaign_stats jako zaleznosc przy RENAME - po V132 nieaktualne.
