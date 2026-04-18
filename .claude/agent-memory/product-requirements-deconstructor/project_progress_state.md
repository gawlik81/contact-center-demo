---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – scan 2026-04-18 po ukończeniu EPIC-14 (DB-024/025/026, BE-043/044/045); stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-18 po FE-036: DB: 26/26 | BE: 45/54 | FE: 34/43 | RAZEM: 105/123

**Why:** Zaktualizowano po ukończeniu EPIC-14: migracje agent_group schema, queue_agent_group, indeksy wydajnościowe; AgentGroupRepository, AgentGroupController/Service, QueueAssignmentRepository.

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania

### Database (0)
Wszystkie 26 zadań DB ukończone.

### Backend (9 pozostałych)
- BE-017 – OAuth flow i zarządzanie tokenami social media (EPIC-06)
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-030b – ETL ClickHouse: docelowy Data Warehouse (Should Have, czeka na BE-030 ✅)
- BE-046 – REST API zarządzania przypisaniem agentów do kolejki (czeka na BE-044 ✅, BE-045 ✅)
- BE-047 – Aktualizacja DefaultRoutingEngine: filtrowanie po przypisaniu (czeka na BE-045 ✅)

### Frontend (10 pozostałych)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-035 – Panel Supervisora: lista wszystkich callbacków (odblokowany przez FE-034 ✅)
- FE-037 – Panel zarządzania grupami agentów (odblokowane przez FE-036 ✅)
- FE-038 – Komponent przypisania agentów do kolejki (czeka na BE-046, odblokowane przez FE-036 ✅)
- FE-039 – Integracja panelu przypisania z formularzem edycji kolejki (czeka na FE-038)

## Ostatnie ukończone (2026-04-18)
- FE-036 ✅ agent-group.model.ts + AgentGroupService z 8 metodami HTTP (listGroups, createGroup, updateGroup, deleteGroup, getGroupMembers, replaceGroupMembers, getQueueAssignment, updateQueueAssignment)
- DB-024 ✅ V042__create_agent_groups.sql: tabele agent_group + agent_group_member, RLS, indeksy, FK CASCADE
- DB-025 ✅ V043__queue_agent_group.sql: flaga all_agents, tabela queue_agent_group, UPDATE istniejących kolejek
- DB-026 ✅ V044__queue_agent_assignment_indexes.sql: 3 indeksy wydajnościowe IF NOT EXISTS
- BE-043 ✅ AgentGroup entity + AgentGroupRepository (CRUD + membership), testy jednostkowe
- BE-044 ✅ AgentGroupService + AgentGroupController (6 endpointów), DTOs, 12 testów
- BE-045 ✅ QueueAssignmentRepository (resolveEligibleAgentIds UNION, replace*, isGroupAssignedToAnyQueue)

## Ostatnia migracja Flyway: V044__queue_agent_assignment_indexes.sql
