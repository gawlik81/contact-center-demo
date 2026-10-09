---
name: project-db057-index-cleanup
description: DB-057 (2026-10-09) V129-V131 - duplikaty indeksow scheduled_callback/agent_group_member i nieuzywane idx_cca_*; wybor zwyciezcow, pulapka is_deleted w findDueCallbacks, wzorzec guardu strukturalnego
metadata:
  type: project
---

V129 DROP idx_callback_ready (zostaje idx_scheduled_callback_due - naming 8/11 indeksow tabeli ma prefiks idx_scheduled_callback_*), V130 DROP idx_campaign_agent_member_lookup (zostaje idx_agent_group_member_lookup - V044, nazwa zgodna z tabela; docs 06-database.md/html zaktualizowane), V131 DROP idx_cca_campaign + idx_cca_archived_at (warunki a/b/c spelnione; wszyscy czytelnicy archiwum maja tenant_id; DB-075 chce (tenant_id, campaign_ended_at), nie te indeksy). Test: Db057IndexCleanupMigrationsTest (12 testow, Flyway target po opisie, klony TEMPLATE do guardow).

**Why:** ZNALEZISKO: Java (ScheduledCallbackRepository.findDueCallbacks) NIE filtruje is_deleted, wiec partial indeksy `due`/`ready` (is_deleted=false) nie sa przez nia uzywane - realnie obsluguje ja idx_callback_scheduled. Zwyciezca due dziala tylko dla ksztaltu z is_deleted=false. Raportowane do osobnej oceny (nie zmieniane).
**How to apply:** guard w migracji porownuje sygnature pg_index (indkey/indoption/indclass/indcollation/predykat/relam) zwyciezcy i duplikatu - przerywa gdy rozne; w EXPLAIN-testach deterministycznosc przez DROP INDEX konkurenta w transakcji + ROLLBACK, seed z session_replication_role=replica (omija FK), tenant=g%30 vs status=(g/30)%5 (niezalezne dzielniki).
