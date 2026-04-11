---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – pełny scan 2026-04-10, stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-10 po EPIC-14: DB: 22/26 | BE: 35/48 | FE: 29/38 | RAZEM: 86/112

**Why:** Zaktualizowano po zaprojektowaniu EPIC-14 (zarządzanie przypisaniem agentów do kolejek). Poprzedni stan: 86/100 (przed EPIC-14).

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania

### Database (4 + nowe EPIC-14)
- DB-021 – Tabele PHONE_NUMBER i PHONE_ROUTING_RULE (EPIC-11, routing numerów telefonicznych)
- DB-024 – Tabele `agent_group` i `agent_group_member` (EPIC-14, V039)
- DB-025 – Kolumna `all_agents` na `queue` + tabela `queue_agent_group` (EPIC-14, V040)
- DB-026 – Indeksy wydajnościowe dla przypisania kolejka-agenci (EPIC-14, V041, Should Have)

### Backend (7 istniejących + 5 nowych EPIC-14)
- BE-017 – OAuth flow i zarządzanie tokenami social media
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-033 – PhoneNumber CRUD API (EPIC-11, czeka na DB-021)
- BE-034 – PhoneRoutingRule CRUD API (EPIC-11, czeka na BE-033)
- BE-035 – Incoming call routing per numer (EPIC-11, czeka na BE-034)
- BE-041 – Callback List API (EPIC-13, czeka na implementację)
- BE-042 – Callback Management API: pełna edycja i usunięcie (EPIC-13)
- BE-043 – AgentGroup encja + AgentGroupRepository (EPIC-14, czeka na DB-024)
- BE-044 – CRUD REST API grup agentów (EPIC-14, czeka na BE-043)
- BE-045 – QueueAssignmentRepository (EPIC-14, czeka na DB-025)
- BE-046 – REST API zarządzania przypisaniem kolejki (EPIC-14, czeka na BE-044, BE-045)
- BE-047 – Aktualizacja DefaultRoutingEngine: filtrowanie eligibleAgentIds (EPIC-14, czeka na BE-045)

### Frontend (9 istniejących + 4 nowe EPIC-14)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-026 – Panel zarządzania numerami telefonów i regułami routingu IVR (czeka na BE-033, BE-034)
- FE-028 – Modal szczegółów kontaktu + AudioPlayerComponent (EPIC-12, odblokowane — czeka tylko na FE)
- FE-029 – Strona Raporty > Kontakty (EPIC-12, czeka na FE-028)
- FE-030 – Integracja szczegółów kontaktu w CustomerDetailComponent (EPIC-12, czeka na FE-028)
- FE-034 – Panel Agenta: lista własnych callbacków (EPIC-13, czeka na BE-041, BE-042)
- FE-035 – Panel Supervisora: lista wszystkich callbacków (EPIC-13, czeka na BE-041, BE-042)
- FE-036 – AgentGroupService + typy DTO (EPIC-14, czeka na BE-044)
- FE-037 – Panel zarządzania grupami agentów (EPIC-14, czeka na FE-036)
- FE-038 – QueueAssignmentPanelComponent (EPIC-14, czeka na FE-036, FE-037)
- FE-039 – Integracja panelu przypisania z formularzem kolejki (EPIC-14, czeka na FE-038)

## EPIC-14 — kluczowy kontekst projektowy (2026-04-10)
- Istniejąca tabela `queue_agent` (queue_id, agent_id) pozostaje bez zmian — jej semantyka to "ręcznie przypisani agenci"
- Nowa tabela `queue_agent_group` łączy kolejkę z grupą (wiele-do-wielu)
- Nowa kolumna `queue.all_agents` (boolean, DEFAULT TRUE dla istniejących kolejek) — gdy TRUE: routing bez filtru (zachowanie dotychczasowe)
- `DefaultRoutingEngine.findBestAgent()`: po filtrze tenanta dodano krok filtrowania po `eligibleAgentIds` (UNION z queue_agent + queue_agent_group → agent_group_member)
- `RoutingRequest` rozszerzony o pole `Set<UUID> eligibleAgentIds` (null = brak filtru)
- Ostatnia migracja Flyway przed EPIC-14: V038 — nowe migracje: V039, V040, V041

**Priorytety (odblokują najwięcej):**
1. DB-024 → BE-043 → BE-044 równolegle z DB-025 → BE-045 → BE-046 + BE-047 (EPIC-14 rdzeń)
2. FE-036 → FE-037 → FE-038 → FE-039 (EPIC-14 UI)
3. BE-041 → BE-042 → FE-034 + FE-035 (EPIC-13 panel callbacków)
4. FE-028 → FE-029, FE-030 (EPIC-12 modal szczegółów kontaktu)
5. DB-021 + BE-033 → BE-034 → BE-035 + FE-026 (EPIC-11 routing numerów)
6. BE-017 → BE-018 → FE-013 + FE-023 (Social Media – EPIC-06)
