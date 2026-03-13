---
name: contact-center-saas-project
description: Kontekst projektu Contact Center SaaS – stack technologiczny, struktura zadań i konwencje dekonstrukcji wymagań
type: project
---

Projekt to wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant), wersja PRD 1.0 z 2026-03-12.

**Stack technologiczny (potwierdzony z TECH-STACK.md):**
- Backend: Java 21 + Spring Boot 3.x, Python 3.11+ (AI/automatyzacja – FastAPI)
- Frontend: Angular (TypeScript), WebRTC (SIP.js/JsSIP)
- DB operacyjna: PostgreSQL 16 z Flyway
- DB cache/sesje: Redis
- Message broker: RabbitMQ (nie Kafka – potwierdzono z TECH-STACK.md)
- Data Warehouse: ClickHouse (wskazany w PRD jako opcja)
- Storage nagrań: S3-compatible (AES-256)

**Konwencje ID zadań:**
- Frontend: FE-001 do FE-024 (24 zadania)
- Backend: BE-001 do BE-031 (31 zadań)
- Baza danych: DB-001 do DB-019 (19 zadań)
- Łącznie: 74 zadania deweloperskie

**Pliki zadań:**
- D:/CloudeAI/contact-center-demo/TASKS-FRONTEND.md
- D:/CloudeAI/contact-center-demo/TASKS-BACKEND.md
- D:/CloudeAI/contact-center-demo/TASKS-DATABASE.md

**Persony systemu:** ADMIN (globalny), SUPERVISOR (per tenant), AGENT (per tenant), Klient końcowy (zewnętrzny)

**Kluczowe wymagania niefunkcjonalne:**
- API CRUD < 200ms (p95)
- Routing decision < 500ms
- Dashboard RT odświeżanie ≤ 5s
- Izolacja logiczna przez tenant_id (każde zapytanie filtrowane)
- bcrypt 12 rund, JWT (15 min) + refresh token (7 dni), MFA (TOTP)
- RODO: anonimizacja (Art. 17), eksport danych (Art. 15)

**Why:** PRD zatwierdzone, projekt w fazie dekonstrukcji wymagań dla równoległych zespołów deweloperskich.
**How to apply:** Przy kolejnych zadaniach dekonstrukcji dla tego projektu – używaj tych ID jako kontynuacji numeracji i respektuj powyższy stack.
