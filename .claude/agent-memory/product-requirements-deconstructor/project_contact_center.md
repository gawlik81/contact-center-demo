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
- E:\ClaudAI\contact-center-demo\PROCESSES.md (nowy – mapa zależności i kolejność realizacji)

**Persony systemu:** ADMIN (globalny), SUPERVISOR (per tenant), AGENT (per tenant), Klient końcowy (zewnętrzny)

**Aktualny postęp (stan na 2026-03-14):**
- DB: 19/19 ukończone (cały schemat, RLS, indeksy, RODO, ClickHouse DW)
- BE: 4/31 ukończone (BE-001 Spring Boot, BE-002 multi-tenancy, BE-003 JWT/MFA/Security, BE-004 Auth API z rate limitingiem Redis)
- FE: 5/24 ukończone (FE-001 init, FE-002 routing/guards, FE-003 interceptory, FE-004 login/MFA, FE-005 shell)

**Kluczowe blokery krytyczne (zidentyfikowane w PROGRESS.md):**
- BE-004 (Auth API) – ukończone; odblokowany FE-004 produkcyjnie
- BE-009 (VoIP Adapter) – blokuje cały kanał telefoniczny (softphone, nagrywanie, CLI, IVR, dialer)
- BE-012 (WebSocket hub) – blokuje Agent Desktop i RT metrics dashboard
- FE-009 (Agent Desktop layout) – blokuje 5 komponentów obsługi kontaktu

**Konwencje pól w plikach zadań (TASKS-*.md) – ustalone 2026-03-14:**
- Każde zadanie ma metadane: Typ, Priorytet, Zlozonosc, Zależności, Status, Blokuje (BE) / Czeka na BE (FE), Odniesienie PRD
- Status: ✅ Ukończone / ⬜ Nie rozpoczęte
- Pole Blokuje (TASKS-BACKEND.md): lista zadań BE/FE odblokowanych po ukończeniu danego zadania
- Pole Czeka na BE (TASKS-FRONTEND.md): lista wymaganych BE z adnotacją "(lub MSW)" gdzie mocking jest realny; "(trudne do zamockowania)" dla WebRTC/VoIP; "(OAuth wymaga prawdziwego BE)" dla FE-023

**Kluczowe wymagania niefunkcjonalne:**
- API CRUD < 200ms (p95)
- Routing decision < 500ms
- Dashboard RT odświeżanie <= 5s
- Izolacja logiczna przez tenant_id (każde zapytanie filtrowane)
- bcrypt 12 rund, JWT (15 min) + refresh token (7 dni), MFA (TOTP)
- RODO: anonimizacja (Art. 17), eksport danych (Art. 15)

**Strategia MSW:** Frontend może realizować większość zadań z Mock Service Worker przed gotowością BE. Wyjątki: FE-010 (WebRTC – trudne do mock), FE-023 (OAuth flow – wymaga prawdziwego backendu).

**Why:** PRD zatwierdzone, projekt w fazie implementacji. Fundament (DB + BE-001..003 + FE-001..005) gotowy.
**How to apply:** Przy kolejnych zadaniach dekonstrukcji – używaj tych ID jako kontynuacji numeracji i respektuj powyższy stack. Sprawdź PROCESSES.md przed sugerowaniem kolejności zadań.
