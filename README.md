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
| VoIP | Twilio (optional), Mock (dev) |

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
- **Recording encryption** – handled at bucket level in MinIO/S3 (see below); no per-request SSE header is sent by the application

---

## Twilio Integration (VoIP)

By default the backend uses `MockTelephonyAdapter` which generates fake call events for local development. To connect a real Twilio account, set the following environment variables and restart the backend.

### Environment Variables

| Variable | Description |
|----------|-------------|
| `TWILIO_ENABLED` | Set to `true` to activate Twilio (default: `false`) |
| `TWILIO_ACCOUNT_SID` | Twilio Account SID (`ACxxxxxxxx…`) |
| `TWILIO_AUTH_TOKEN` | Twilio Auth Token |
| `TWILIO_PHONE_NUMBER` | Caller ID in E.164 format (e.g. `+48111000111`) |
| `TWILIO_STATUS_CALLBACK_URL` | Public HTTPS URL for Twilio status webhooks (see below) |

### Webhook Setup

Twilio sends call status updates (POST form-encoded) to the endpoint:

```
POST /api/telephony/webhook/twilio
```

The URL must be publicly reachable by Twilio. For local development use [ngrok](https://ngrok.com/) or a similar tunnel:

```bash
ngrok http 8080
# then set TWILIO_STATUS_CALLBACK_URL=https://<ngrok-id>.ngrok.io/api/telephony/webhook/twilio?tenantId=<UUID>
```

### Switching Adapters

| `TWILIO_ENABLED` | Active bean | Effect |
|---|---|---|
| `false` (default) | `MockTelephonyAdapter` | Simulated calls, no external traffic |
| `true` | `TwilioTelephonyAdapter` | Live Twilio REST API |

---

## MinIO – Bucket-Level Encryption (SSE-S3)

Recordings are uploaded without a per-request SSE header. Encryption is configured once on the bucket so every object is encrypted automatically.

### Enable via MinIO Client (`mc`)

```bash
# Add alias for local MinIO (adjust URL/credentials as needed)
mc alias set local http://localhost:9000 minioadmin minioadmin

# Enable SSE-S3 (AES-256) on the recordings bucket
mc encrypt set sse-s3 local/recordings
```

Verify:

```bash
mc encrypt info local/recordings
# Expected output: Auto encryption 'sse-s3' is enabled
```

### Enable via MinIO Console

1. Open `http://localhost:9001` → log in with MinIO root credentials.
2. Navigate to **Buckets** → select your recordings bucket.
3. Open the **Summary** tab → **Encryption** section.
4. Set encryption type to **SSE-S3** and save.

### Notes

- SSE-S3 on MinIO requires the **KES** (Key Encryption Service) sidecar **only for SSE-KMS**. SSE-S3 works without KES – MinIO manages the keys internally.
- In production (AWS S3), enable **Default Encryption** on the bucket (`AES-256`) via the S3 Console or IaC, or set `serverSideEncryption(ServerSideEncryption.AES256)` back in `RecordingService.uploadToS3()`.
- The `docker-compose.yml` MinIO service does **not** enable SSE-S3 by default – run the `mc encrypt set` command once after first `docker compose up -d`.

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
| `TWILIO_ENABLED` | Enable Twilio adapter (`true`/`false`) |
| `TWILIO_ACCOUNT_SID` | Twilio Account SID |
| `TWILIO_AUTH_TOKEN` | Twilio Auth Token |
| `TWILIO_PHONE_NUMBER` | Outbound caller ID (E.164) |
| `TWILIO_STATUS_CALLBACK_URL` | Public webhook URL for call status updates |
