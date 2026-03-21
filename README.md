# Contact Center SaaS – Demo

Multi-tenant Contact Center platform in SaaS model. Supports inbound and outbound contacts across voice (VoIP), email, and social media channels. Built with Java/Spring Boot backend, Angular frontend, and PostgreSQL database with strict row-level tenant isolation.

---

## Architecture Overview

Three user personas:
- **Admin** – manages the platform, tenants, and global configuration
- **Supervisor** – monitors team performance and real-time queue stats per tenant
- **Agent** – handles contacts (calls, emails, social messages) via Agent Desktop

Three delivery layers:
- **Database** – PostgreSQL with Flyway migrations, RLS, multi-tenancy
- **Backend** – Java 21 / Spring Boot 3.3.5 REST API
- **Frontend** – Angular 21 SPA (standalone components, RxJS, Angular Material)

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21, Spring Boot 3.3.5 |
| Frontend | Angular 21, TypeScript, RxJS, SCSS |
| Automation | Python (Voicebot / NLU) |
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| Messaging | RabbitMQ 3.13 |
| Data Warehouse | ClickHouse |
| Auth | JWT RS256, TOTP MFA |

---

## Prerequisites

- Docker & Docker Compose
- Java 21
- Maven 3.9+
- Node.js 20+ (for frontend)

---

## Quick Start

### 1. Start Infrastructure

```bash
docker compose up -d
```

Services started:
- PostgreSQL: `localhost:5432` (db=`contact_center_dev`, user/pass=`postgres`)
- Redis: `localhost:6379`
- RabbitMQ: `localhost:5672` | Management UI: `localhost:15672` (guest/guest)

### 2. Run Backend

```bash
cd backend && mvn spring-boot:run -pl app -Dspring-boot.run.profiles=dev
```

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### 3. Run Frontend

```bash
cd frontend
npm install
npm start
```

- App: `http://localhost:4200`
- Dev server proxies `/api/*` to `http://localhost:8080`

### 4. Build

```bash
# Backend
cd backend && mvn package -pl app -DskipTests

# Frontend (production)
cd frontend && npm run build:prod
```

### 5. Run Tests

```bash
# Backend – all tests
cd backend && mvn test -pl app

# Backend – single test class
cd backend && mvn test -pl app -Dtest=JwtServiceTest

# Frontend
cd frontend && npm test
```

### 6. Reset Database

```bash
docker compose down -v   # wipes all data
docker compose up -d
```

---

## Project Structure

```
contact-center-demo/
├── backend/
│   ├── pom.xml                          # Parent POM
│   └── app/src/main/java/com/contactcenter/
│       ├── api/                         # REST controllers + DTOs
│       │   ├── contact/                 # Contact history API
│       │   ├── queue/                   # Queue CRUD API
│       │   ├── telemetry/               # Frontend log collector (POST /api/logs)
│       │   ├── telephony/               # VoIP webhook + MockCallController (dev)
│       │   └── ...                      # auth, tenant, user, admin, websocket
│       ├── domain/
│       │   ├── model/                   # JPA entities
│       │   ├── repository/              # TenantAwareRepository extensions
│       │   ├── routing/                 # Routing Engine (skill-based, round-robin, sticky)
│       │   ├── service/                 # Business logic
│       │   ├── telephony/               # VoIP adapter + MockTelephonyAdapter
│       │   └── websocket/               # STOMP hub, RabbitMQ relay
│       ├── infrastructure/
│       │   ├── aspect/                  # CrossTenantAspect, AuditAspect (AOP)
│       │   └── config/                  # Spring configs (Security, Redis, RabbitMQ, WS)
│       └── security/                    # JWT, MFA, TenantContext, filters
├── backend/src/main/resources/
│   └── db/
│       ├── migration/                   # Flyway V001–V025 (shared)
│       └── seed/                        # V999__dev_seed.sql (dev only)
├── frontend/
│   ├── src/app/
│   │   ├── core/                        # Guards, interceptors, singleton services, models
│   │   ├── shared/                      # Reusable components (shell, navbar, sidenav, toasts)
│   │   ├── features/                    # Lazy-loaded feature modules
│   │   │   ├── auth/                    # Login (email-first 3-step flow), MFA
│   │   │   ├── admin/                   # Tenant management, metrics dashboard (ADMIN)
│   │   │   ├── supervisor/              # Agents, customers, queues, customer detail (SUPERVISOR)
│   │   │   └── agent/                   # Agent Desktop, softphone WebRTC, disposition panel
│   │   └── environments/                # Environment configs
│   └── proxy.conf.json                  # Dev proxy → localhost:8080
├── dw/migrations/                       # ClickHouse DDL (manual versioning)
├── docker-compose.yml
├── PRD.md
├── ARCHITECTURE.md
├── TECH-STACK.md
├── TASKS-DATABASE.md
├── TASKS-BACKEND.md
├── TASKS-FRONTEND.md
└── PROGRESS.md
```

---

## Security Model

- **JWT RS256** – access token (15 min) + refresh token (7 days)
- **MFA** – TOTP (RFC 6238, ±30s window)
- **Multi-tenancy** – every request sets PostgreSQL `app.current_tenant_id` via RLS; enforced at DB level
- **Token blacklist** – SHA-256 token hash stored in Redis on logout
- **Filter chain order**: `JwtAuthFilter` → `TenantFilter` → `UsernamePasswordAuthenticationFilter`

---

## Key Conventions

- Every repository extends `TenantAwareRepository`; every write calls `assertSameTenant()`
- Soft deletes only – `is_deleted = TRUE`, never physical deletes
- All tables have `tenant_id UUID NOT NULL` with composite index `(tenant_id, pk)`
- Flyway migration naming: `V{NNN}__{description}.sql`
- RabbitMQ routing keys: `{aggregate}.{event}` (e.g. `call.incoming`)
- Async thread crossing: use `TenantContext.snapshot()` / `restore()` / `clear()`

---

## Dev Credentials

After `docker compose up -d` + backend start, use any of the following accounts (all share the same password):

**Password:** `Test@12345`

| Role | Email | Tenant |
|------|-------|--------|
| ADMIN | `admin@contactcenter.dev` | Acme Corporation |
| SUPERVISOR | `supervisor1@acme.dev` | Acme Corporation |
| SUPERVISOR | `supervisor1@beta.dev` | Beta Telecom |
| AGENT | `agent1@acme.dev` | Acme Corporation |
| AGENT | `agent2@acme.dev` | Acme Corporation |
| AGENT | `agent3@acme.dev` | Acme Corporation |

> The login form requires selecting the tenant from a dropdown. If you reset the database (`docker compose down -v`), the seed runs automatically on next backend start.

---

## Progress

| Area | Done | Total |
|------|------|-------|
| Database (DB) | 19 | 19 |
| Backend (BE) | 16 | 31 |
| Frontend (FE) | 15 | 24 |
| **Total** | **50** | **74** |

See [PROGRESS.md](PROGRESS.md) for full task status.

---

## Environment Variables (Production)

| Variable | Description |
|----------|-------------|
| `DB_URL` | JDBC connection URL |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials |
| `REDIS_HOST` / `REDIS_PORT` | Redis connection |
| `RABBITMQ_HOST` / `_USERNAME` / `_PASSWORD` | RabbitMQ connection |
| `JWT_PRIVATE_KEY_VALUE` / `JWT_PUBLIC_KEY_VALUE` | RSA keys (PEM string) |
| `JWT_ISSUER` | JWT `iss` claim |
| `cors.allowed-origins` | CORS whitelist |
