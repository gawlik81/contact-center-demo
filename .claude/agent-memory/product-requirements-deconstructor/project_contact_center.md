---
name: contact-center-saas-project
description: Kontekst projektu Contact Center SaaS – stack technologiczny, struktura zadań, konwencje i aktualny postęp
type: project
---

Projekt to wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant), wersja PRD 1.0 z 2026-03-12.

**Stack technologiczny (potwierdzony z TECH-STACK.md):**

- Backend: Java 21 + Spring Boot 3.x, Python 3.11+ (AI/automatyzacja – FastAPI)
- Frontend: Angular (TypeScript), WebRTC (SIP.js/JsSIP), Angular Material / PrimeNG
- DB operacyjna: PostgreSQL 16 z Flyway
- DB cache/sesje: Redis
- Message broker: RabbitMQ (nie Kafka – potwierdzono z TECH-STACK.md)
- Data Warehouse: ClickHouse (wskazany w PRD jako opcja)
- Storage nagrań: S3-compatible (AES-256)

**Konwencje ID zadań:**

- Frontend: FE-001 do FE-024 (24 zadania)
- Backend: BE-001 do BE-031 (31 zadań)
- Baza danych: DB-001 do DB-019 (19 zadań)
- Lacznie: 74 zadania deweloperskie

**Pliki zadań i dokumentacji (ścieżki Windows):**

- E:\ClaudAI\contact-center-demo\TASKS-FRONTEND.md
- E:\ClaudAI\contact-center-demo\TASKS-BACKEND.md
- E:\ClaudAI\contact-center-demo\PROGRESS.md

**Persony systemu:** ADMIN (globalny), SUPERVISOR (per tenant), AGENT (per tenant), Klient końcowy (zewnętrzny)

**Aktualny postęp (stan na 2026-03-18):**

- DB: 19/19 ukończone (cały schemat, RLS, indeksy, RODO, ClickHouse DW)
- BE: 10/31 ukończone (BE-001..BE-009, BE-012; w tym BE-009 VoIP Adapter z MockTelephonyAdapter i interfejsem TelephonyAdapter, BE-012 WebSocket hub STOMP z JWT interceptor i RabbitMQ relay)
- FE: 9/24 ukończone (FE-001..FE-009; w tym FE-009 Agent Desktop z panelem statusu, zakładkami kontaktów max 4 i integracją WebSocket)

**Kluczowe blokery krytyczne (stan 2026-03-18):**

- BE-009 (VoIP Adapter) – UKOŃCZONE; odblokowane BE-010, BE-011, BE-013
- BE-012 (WebSocket hub) – UKOŃCZONE; odblokowane FE-009 i FE-021
- FE-009 (Agent Desktop layout) – UKOŃCZONE; odblokowane FE-010..FE-013, FE-017
- BE-010 (Nagrywanie) – nowy aktywny bloker (zależy od BE-009 ✅)
- BE-025 (Customer API) – blokuje CLI lookup (BE-011), RODO (BE-031) i 3 widoki FE
- BE-019 (Routing Engine) – blokuje BE-029 (RT metrics) i BE-021 (wait time)

**Konwencje pól w plikach zadań (TASKS-*.md) – ustalone 2026-03-14, rozszerzone 2026-03-17:**

- Każde zadanie ma metadane: Typ, Priorytet, Zlozonosc, Zależności, Status, [Zrealizowane: data], Blokuje, Odniesienie PRD
- Status: ✅ Ukończone / ⬜ Nie rozpoczęte
- Pole Blokuje: stosowane we wszystkich trzech plikach (TASKS-BACKEND, TASKS-FRONTEND, TASKS-DATABASE). Lista zadań odblokowanych po ukończeniu danego zadania
- Pole Czeka na BE (TASKS-FRONTEND.md): lista wymaganych BE z adnotacją "(lub MSW)" gdzie mocking jest realny; "(trudne do zamockowania)" dla WebRTC/VoIP; "(
  OAuth wymaga prawdziwego BE)" dla FE-023
- Pole Zrealizowane: data ukończenia w formacie RRRR-MM-DD – dodawane tylko do ukończonych zadań

**Kluczowe wymagania niefunkcjonalne:**

- API CRUD < 200ms (p95)
- Routing decision < 500ms
- Dashboard RT odświeżanie <= 5s
- Izolacja logiczna przez tenant_id (każde zapytanie filtrowane)
- bcrypt 12 rund, JWT (15 min) + refresh token (7 dni), MFA (TOTP)
- RODO: anonimizacja (Art. 17), eksport danych (Art. 15)

**Strategia MSW:** Frontend może realizować większość zadań z Mock Service Worker przed gotowością BE. Wyjątki: FE-010 (WebRTC – trudne do mock), FE-023 (OAuth
flow – wymaga prawdziwego backendu).

**Why:** PRD zatwierdzone, projekt w fazie implementacji. Fundament (DB + BE-001..003 + FE-001..005) gotowy.
**How to apply:** Przy kolejnych zadaniach dekonstrukcji – używaj tych ID jako kontynuacji numeracji i respektuj powyższy stack. Sprawdź PROCESSES.md przed
sugerowaniem kolejności zadań.
