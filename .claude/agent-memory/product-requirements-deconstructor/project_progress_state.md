---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – scan 2026-04-15 po ukończeniu BE-041 i BE-042; stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-15 po BE-041 i BE-042: DB: 23/23 | BE: 41/49 | FE: 31/39 | RAZEM: 95/111

**Why:** Zaktualizowano po ukończeniu BE-041 (Callback List API) i BE-042 (Callback Management API). Dodano nowe taski EPIC-13 (BE-043..BE-047, FE-034..FE-035) i EPIC-14 (FE-036..FE-039). Skorygowano: FE-031 i FE-032 są faktycznie ✅ (zrealizowane 2026-04-09), PROGRESS.md błędnie oznaczał je jako ⬜.

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

### Frontend (8 pozostałych)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-034 – Panel Agenta: lista własnych callbacków (czeka na implementację, BE gotowy: BE-041 ✅, BE-042 ✅)
- FE-035 – Panel Supervisora: lista wszystkich callbacków (czeka na FE-034)
- FE-036 – AgentGroupService i typy DTO (EPIC-14, czeka na BE-044)
- FE-037 – Panel zarządzania grupami agentów (czeka na FE-036)
- FE-038 – Komponent przypisania agentów do kolejki (czeka na BE-046, FE-036)
- FE-039 – Integracja panelu przypisania z formularzem edycji kolejki (czeka na FE-038)

## Ostatnie ukończone (2026-04-15)
- BE-041 ✅ Callback List API: ScheduledCallbackRepository +4 metody, CallbackListItemResponse DTO, izolacja ról, batch agentName lookup, 6 testów
- BE-042 ✅ Callback Management API: UpdateCallbackRequest, PATCH /dialer/callbacks/{id}, DELETE (soft-delete → CANCELLED), 10 testów, 757 PASS łącznie

## Ostatnia migracja Flyway: V039__create_phone_number_routing.sql
