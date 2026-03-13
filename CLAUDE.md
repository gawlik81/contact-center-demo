# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

Multi-tenant Contact Center SaaS platform. Three user personas: **Admin** (global platform), **Supervisor** (per-tenant), **Agent** (handles contacts). Three delivery layers: PostgreSQL DB migrations, Java/Spring Boot backend, Angular frontend.

Progress tracking: `PROGRESS.md`. Task definitions: `TASKS-DATABASE.md`, `TASKS-BACKEND.md`, `TASKS-FRONTEND.md`.

---

## Backend Commands

All backend commands run from `backend/` (parent POM) or `backend/app/` (app module).

```bash
# Start infrastructure (PostgreSQL 16, Redis 7, RabbitMQ 3.13)
docker compose up -d
docker compose down          # stop (keep volumes)
docker compose down -v       # stop + wipe all data (DB reset)

# Build
cd backend && mvn package -pl app -DskipTests

# Run all tests
cd backend && mvn test -pl app

# Run a single test class
cd backend && mvn test -pl app -Dtest=JwtServiceTest

# Run a single test method
cd backend && mvn test -pl app -Dtest=JwtServiceTest#shouldGenerateValidToken

# Run app locally (profile: dev)
cd backend/app && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Verify build without packaging
cd backend && mvn verify -pl app
```

Local services after `docker compose up -d`:
- PostgreSQL: `localhost:5432` db=`contact_center_dev` user=`postgres` pass=`postgres`
- Redis: `localhost:6379`
- RabbitMQ: `localhost:5672` (AMQP), `localhost:15672` (Management UI, guest/guest)
- Spring Boot: `localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

---

## Backend Architecture

### Module & Package Layout

```
backend/
├── pom.xml                          # parent POM (Spring Boot 3.3.5, Java 21)
├── app/                             # sole Maven module (contact-center-app)
│   └── src/main/java/com/contactcenter/
│       ├── api/                     # REST controllers + DTOs (per feature, e.g. api/auth/)
│       ├── domain/
│       │   ├── model/               # JPA entities (AppUser, RefreshToken, ...)
│       │   ├── repository/          # data access (extend TenantAwareRepository)
│       │   └── service/             # business logic (@Service)
│       ├── infrastructure/
│       │   ├── aspect/              # CrossTenantAspect (AOP)
│       │   └── config/              # Spring configs (Security, Redis, RabbitMQ, Flyway, ...)
│       ├── security/                # JWT, MFA, TenantContext, filters
│       └── app/                     # ContactCenterApplication main class
└── src/main/resources/
    └── db/
        ├── migration/               # Flyway migrations (V001–V018, shared)
        ├── seed/                    # V999__dev_seed.sql (dev profile only)
        └── init/                    # Docker init scripts (PostgreSQL extensions)
dw/migrations/                       # ClickHouse DDL scripts (not Flyway)
```

The `app/pom.xml` build resources section includes **both** `app/src/main/resources` and `../src/main/resources`, so all migrations in `backend/src/main/resources/db/` are on the classpath.

### Multi-Tenancy Pattern

Every request flow follows this chain:

1. **`JwtAuthFilter`** – verifies RS256 JWT signature, sets Spring `SecurityContext`.
2. **`TenantFilter`** – extracts `tenant_id`/`user_id`/`role` from JWT claims, sets `TenantContext` (InheritableThreadLocal), adds MDC fields `[tenantId]` / `[userId]` / `[requestId]`. Always clears in `finally`.
3. **`TenantAwareRepository`** – base class for all repositories; calls `SELECT set_tenant_context(?)` before each query to activate PostgreSQL Row-Level Security. Use `assertSameTenant()` before writes.
4. **`CrossTenantAspect`** – AOP `@AfterThrowing` logs WARNING when `CrossTenantAccessException` is thrown; `@Before` logs ERROR when `TenantContext` is missing in a domain service (config bug).

**Filter order is critical** – `JwtAuthFilter` is registered _before_ `TenantFilter`, which is before `UsernamePasswordAuthenticationFilter`.

**Rule:** Every new repository must extend `TenantAwareRepository`. Every write must call `assertSameTenant(entity.getTenantId())` before persisting.

**Async propagation:** When crossing thread boundaries (e.g. `@Async`, `CompletableFuture`), call `TenantContext.snapshot()` on the caller thread and `TenantContext.restore(snapshot)` + `TenantContext.clear()` in `finally` on the worker thread.

### Adding a New Public Endpoint

Two places must be kept in sync:
1. `SecurityConfig` – `requestMatchers` permit list
2. `TenantFilter.PUBLIC_PATH_PREFIXES` – set of path prefixes that skip JWT check

### JWT

- Algorithm: RS256. Keys in `classpath:keys/private.pem` (PKCS#8) and `classpath:keys/public.pem` (dev). In prod, override via `JWT_PRIVATE_KEY_VALUE` / `JWT_PUBLIC_KEY_VALUE` ENV vars.
- `JwtService` – signs tokens (uses private key).
- `JwtParser` – validates tokens (uses public key).
- Custom claims: `tenant_id`, `user_id`, `role`, `email`, `mfaVerified`.
- Access token TTL: 15 min (`jwt.access-token-ttl-seconds`). Refresh token TTL: 7 days.
- Logout: access token SHA-256 hash stored in Redis with TTL = remaining validity (`jwt:blacklist:{hash}`).
- `TokenBlacklistService` hashes with SHA-256 before storing (never stores raw token).

### RabbitMQ Exchanges & Routing Key Convention

| Exchange | Type | Purpose |
|----------|------|---------|
| `cc.events` | topic | Domain events (calls, contacts, agents, campaigns) |
| `cc.audit` | topic | Async audit log writes |
| `cc.notifications` | topic | Push to agents/supervisors |
| `cc.dlx` | direct | Dead-letter for failed messages |

Routing key format: `{aggregate}.{event}` – e.g. `call.incoming`, `agent.status.changed`, `contact.queued`.

### Database Migrations (Flyway)

Migrations live in `backend/src/main/resources/db/migration/`. Naming: `V{NNN}__{description}.sql`.

**Dev-only settings** (`application-dev.yml`) – **never set these in prod**:
- `clean-on-validation-error: true` – wipes and re-runs all migrations if checksums mismatch
- `clean-disabled: false` – allows `clean()` to execute

The seed file `V999__dev_seed.sql` is in `db/seed/` and only loaded when the `dev` profile is active (Flyway `locations` includes `classpath:db/seed` only in `application-dev.yml`).

ClickHouse DDL scripts in `dw/migrations/` are versioned manually (not Flyway).

### Redis Key Namespaces

| Key pattern | TTL | Purpose |
|-------------|-----|---------|
| `jwt:blacklist:{hash}` | remaining token validity | Logged-out tokens |
| `session:agent:{userId}` | 8h | Agent presence/status |
| `cache:customer:phone:{phone}` | 5 min | CLI lookup cache |
| `cache:queue:stats:{queueId}` | 5s | Queue stats |
| `cache:tenant:metrics` | 30s | Admin metrics |
| `rate:login:{ip}` | 15 min | Login rate limit counter |

### Test Configuration

Tests use `application-test.yml` which:
- Disables Flyway
- Excludes `RabbitAutoConfiguration`, `RedisAutoConfiguration`, `RedisRepositoriesAutoConfiguration`
- Disables Redis/RabbitMQ health checks

Integration tests that need a real database use Testcontainers (`@Testcontainers` + `@Container` PostgreSQL). Test RSA keys are in `src/test/resources/keys/`.

---

## Database Schema Key Points

- **Every table** has `tenant_id UUID NOT NULL` with a composite index on `(tenant_id, primary_key)`.
- **Soft delete**: `is_deleted BOOLEAN DEFAULT FALSE` – never physically delete rows.
- **Timestamps**: `created_at TIMESTAMPTZ DEFAULT NOW()`, `updated_at TIMESTAMPTZ`.
- RLS policies: `USING (tenant_id = current_setting('app.current_tenant_id')::UUID)` – activated via `set_tenant_context()` function.
- PostgreSQL extensions required: `uuid-ossp`, `pg_trgm` (fuzzy search), `pgcrypto` (AES-256 for social tokens).
- Indexes with `::DATE` or `NOW()` casts **cannot be used** in partial index predicates (PostgreSQL requires IMMUTABLE functions) – see known bugs in PROGRESS.md.

---

## Environment Variables (Production Overrides)

| Variable | Default (dev) | Description |
|----------|--------------|-------------|
| `DB_URL` | `jdbc:postgresql://localhost:5432/contact_center_dev` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis connection |
| `RABBITMQ_HOST` / `_USERNAME` / `_PASSWORD` | `localhost` / `guest` / `guest` | RabbitMQ |
| `JWT_PRIVATE_KEY_VALUE` / `JWT_PUBLIC_KEY_VALUE` | _(file fallback)_ | RSA key content (PEM string) |
| `JWT_ISSUER` | `contact-center` | JWT `iss` claim |
| `cors.allowed-origins` | `http://localhost:4200,http://localhost:3000` | CORS whitelist |
