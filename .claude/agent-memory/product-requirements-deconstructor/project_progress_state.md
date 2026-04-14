---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – scan 2026-04-14 po ukończeniu EPIC-11; stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-14 po EPIC-11: DB: 23/23 | BE: 38/41 | FE: 32/33 | RAZEM: 93/97

**Why:** Zaktualizowano po ukończeniu EPIC-11 (routing numerów telefonicznych): DB-021, BE-033, BE-034, BE-035, FE-026, FE-028, FE-029, FE-030. Poprzedni stan: 87/96 (przed sesją 2026-04-14).

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania

### Database (0)
Wszystkie 23 zadania DB ukończone.

### Backend (3 pozostałe)
- BE-017 – OAuth flow i zarządzanie tokenami social media (EPIC-06)
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-030b – ETL ClickHouse: docelowy Data Warehouse (Should Have, czeka na BE-030 ✅)

### Frontend (1 pozostały)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)

Uwaga: FE-031 i FE-032 (modal przełożenia rozmowy, modal dodania oddzwonienia) są niezapisane w TASKS-FRONTEND.md ale figurują w PROGRESS.md jako nieukończone (FE-031 ⬜, FE-032 ⬜). Łącznie TASKS-FRONTEND.md liczy 33 zadania (FE-001 – FE-033), PROGRESS.md zawiera też FE-031, FE-032, FE-033.

## EPIC-11 — ukończone 2026-04-14
- DB-021 ✅ V039 tabele phone_number + phone_routing_rule (UNIQUE, CHECK E.164, RLS, trigger kolizji)
- BE-033 ✅ PhoneNumber CRUD API (entity, repository extends TenantAwareRepository, service, controller /api/phone-numbers)
- BE-034 ✅ PhoneRoutingRule CRUD API (findOverlapping native SQL, kolizja → HTTP 409 z collidingRuleIds)
- BE-035 ✅ IncomingCallRoutingService.resolveRoute() zintegrowany w TwilioWebhookController
- FE-026 ✅ PhoneNumbersComponent + RoutingRulesComponent + RoutingRuleFormComponent + PhoneNumberService

## Ostatnia migracja Flyway: V039__create_phone_number_routing.sql
