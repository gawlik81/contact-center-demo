---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – pełny scan 2026-04-10, stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-10: DB: 22/23 | BE: 35/43 | FE: 29/34 | RAZEM: 86/100

**Why:** Regularnie aktualizowany po pełnym przeglądzie kodu i git log. Poprzedni stan: 75/90 (przed dodaniem EPIC-13 tasków BE-038..040 i nowych BE-041/042, FE-034/035).

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania

### Database (1)
- DB-021 – Tabele PHONE_NUMBER i PHONE_ROUTING_RULE (EPIC-11, routing numerów telefonicznych)

### Backend (8)
- BE-017 – OAuth flow i zarządzanie tokenami social media
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-033 – PhoneNumber CRUD API (EPIC-11, czeka na DB-021)
- BE-034 – PhoneRoutingRule CRUD API (EPIC-11, czeka na BE-033)
- BE-035 – Incoming call routing per numer (EPIC-11, czeka na BE-034)
- BE-041 – Callback List API: filtrowana lista callbacków dla agenta i supervisora (NOWE 2026-04-10)
- BE-042 – Callback Management API: pełna edycja i usunięcie callbacku (NOWE 2026-04-10)

### Frontend (5)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-026 – Panel zarządzania numerami telefonów i regułami routingu IVR (czeka na BE-033, BE-034)
- FE-028 – Modal szczegółów kontaktu + AudioPlayerComponent (EPIC-12, BE-037 ✅ odblokowane — czeka tylko na implementację FE)
- FE-029 – Strona Raporty > Kontakty (EPIC-12, czeka na FE-028)
- FE-030 – Integracja szczegółów kontaktu w CustomerDetailComponent (EPIC-12, czeka na FE-028)
- FE-034 – Panel Agenta: lista własnych callbacków z edycją i usunięciem (NOWE 2026-04-10, czeka na BE-041, BE-042)
- FE-035 – Panel Supervisora: lista wszystkich callbacków z reassign agenta (NOWE 2026-04-10, czeka na BE-041, BE-042)

## Ukończone od 2026-04-08 (6 tasków)
- DB-022 (2026-04-09) – V035 indeksy wyszukiwania kontaktów
- DB-023 (2026-04-09) – V037 rozszerzenie scheduled_callback o source_type/origin_contact_id
- BE-030 (2026-04-09) – ETL EtlSyncService, DataWarehouseWriter, EtlStatusController
- BE-031 (2026-04-09) – RODO: GdprService + GdprController (ZIP export, anonimizacja)
- BE-038 (2026-04-09) – ScheduledCallbackExecutor @Scheduled 60s
- BE-039 (2026-04-09) – PUT /api/dialer/callbacks/{id} reschedule
- BE-040 (2026-04-09) – POST /api/contacts/{contactId}/callback inbound
- FE-033 (2026-04-09) – Panel RODO w CustomerDetailComponent

## Nowe zadania dodane 2026-04-10 (EPIC-13 Panel Listy Callbacków)

### Kontekst nowych zadań:
- `GET /api/dialer/callbacks` istnieje, ale zwraca TYLKO status=PENDING bez filtrowania po agentId — agent widzi callbacki wszystkich agentów (bug bezpieczeństwa)
- Brak endpointów: DELETE callback, PATCH pełna edycja, filtrowanie po statusie/agencie
- Frontend nie ma żadnego komponentu listy callbacków
- BE-041 naprawia izolację agentId i dodaje filtry; BE-042 dodaje PATCH+DELETE; FE-034/035 implementują widoki

**Priorytety (odblokują najwięcej):**
1. BE-041 → BE-042 → FE-034 + FE-035 (Panel listy callbacków – odblokowane, brak zewnętrznych zależności)
2. FE-028 → FE-029, FE-030 (EPIC-12 modal + nagrania – BE-037 ✅ odblokowane, tylko praca FE pozostała)
3. DB-021 + BE-033 → BE-034 → BE-035 + FE-026 (EPIC-11 routing numerów)
4. BE-017 → BE-018 → FE-013 + FE-023 (Social Media – EPIC-06)
