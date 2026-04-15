---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – scan 2026-04-15 po ukończeniu FE-034; stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-15 po FE-034: DB: 23/23 | BE: 41/49 | FE: 32/39 | RAZEM: 96/111

**Why:** Zaktualizowano po ukończeniu FE-034 (Panel Agenta: lista callbacków z edycją i usunięciem). Odblokowane: FE-035.

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania

### Database (0)
Wszystkie 23 zadania DB ukończone.

### Backend (8 pozostałych)
- BE-017 – OAuth flow i zarządzanie tokenami social media (EPIC-06)
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-030b – ETL ClickHouse: docelowy Data Warehouse (Should Have, czeka na BE-030 ✅)
- BE-043 – Model i repozytorium grup agentów (EPIC-14, czeka na DB-024)
- BE-044 – CRUD REST API grup agentów (czeka na BE-043)
- BE-045 – QueueAssignmentRepository (EPIC-14)
- BE-046 – REST API zarządzania przypisaniem agentów do kolejki (czeka na BE-044, BE-045)
- BE-047 – Aktualizacja DefaultRoutingEngine: filtrowanie po przypisaniu (czeka na BE-045)

### Frontend (7 pozostałych)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-035 – Panel Supervisora: lista wszystkich callbacków (odblokowany przez FE-034 ✅)
- FE-036 – AgentGroupService i typy DTO (EPIC-14, czeka na BE-044)
- FE-037 – Panel zarządzania grupami agentów (czeka na FE-036)
- FE-038 – Komponent przypisania agentów do kolejki (czeka na BE-046, FE-036)
- FE-039 – Integracja panelu przypisania z formularzem edycji kolejki (czeka na FE-038)

## Ostatnie ukończone (2026-04-15)
- FE-034 ✅ Panel Agenta: lista callbacków z edycją i usunięciem: CallbackService, AgentCallbacksPageComponent, filtry/paginacja/modal edycji (reużycie RescheduleCallbackModalComponent)/dialog usunięcia, trasa /agent/callbacks, sidenav AGENT_NAV

## Ostatnia migracja Flyway: V039__create_phone_number_routing.sql
