---
name: Stack technologiczny i konwencje projektu Contact Center
description: Stack technologiczny, konwencje nazewnicze i wzorce architektoniczne projektu Contact Center SaaS
type: project
---

Projekt Contact Center SaaS – multi-tenant platforma z trzema warstwami: PostgreSQL DB (Flyway migracje), Java 21 + Spring Boot 3.3.5 (backend), Angular 21 (frontend).

**Prefiks ID zadań:** DB-, BE-, FE-

**Stack:**
- Backend: Java 21, Spring Boot 3.x, Maven multi-module, JPA/Hibernate 6, RabbitMQ, Redis, RS256 JWT
- Frontend: Angular 21, standalone components, signal-based state, SCSS, Vitest
- DB: PostgreSQL 16, Flyway migracje V{NNN}__opis.sql, ClickHouse (DW), Redis
- JSONB via @JdbcTypeCode(SqlTypes.JSON) – nie AttributeConverter

**Konwencje migracji Flyway:**
- Lokalizacja: backend/src/main/resources/db/migration/
- Naming: V{NNN}__{opis_snake_case}.sql
- Ostatnia migracja: V024__fix_search_customers_prefix_search.sql

**Multi-tenancy:**
- TenantContext (InheritableThreadLocal) + TenantFilter
- TenantAwareRepository (base class, wywołuje set_tenant_context(uuid))
- RLS na poziomie PostgreSQL

**PagedResponse<T>:** standardowy format paginacji, używany we wszystkich list endpoints (backend record, frontend interface).

**Why:** Wiedza budowana stopniowo przez kolejne implementacje; kluczowe wzorce powtarzają się we wszystkich modułach.
**How to apply:** Każde nowe zadanie BE powinno korzystać z TenantAwareRepository, assertSameTenant(), @JdbcTypeCode dla JSONB, PagedResponse dla list.
