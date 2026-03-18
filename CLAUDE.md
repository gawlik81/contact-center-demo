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
│       ├── api/                     # REST controllers + DTOs (per feature)
│       │   ├── admin/               # AdminMetricsController + DTOs (AdminMetricsResponse, TenantMetrics, TenantDetailMetrics)
│       │   ├── auditlog/            # AuditLogController + AuditLogResponse DTO
│       │   ├── auth/                # AuthController + DTOs (LoginRequest/Response, MfaVerifyRequest, RefreshRequest, ChangePasswordRequest, LogoutRequest, MfaSetupResponse)
│       │   ├── public_/             # PublicController (tenants list for login dropdown)
│       │   ├── telephony/           # TelephonyWebhookController, MockCallController (dev) + DTOs
│       │   ├── tenant/              # TenantController + DTOs (CreateTenantRequest, UpdateTenantRequest, TenantResponse, TenantFilterParams, TenantResourceLimitsDto, NameAvailabilityResponse)
│       │   ├── user/                # UserController, AdminUserController + DTOs (CreateUserRequest, UpdateUserRequest, UpdateStatusRequest, UserResponse, AgentStatusChangedEvent)
│       │   ├── websocket/           # WebSocketController (STOMP message handling)
│       │   ├── GlobalExceptionHandler.java   # @RestControllerAdvice (HTTP 4xx/5xx mapping)
│       │   └── PagedResponse.java            # Generic pagination record
│       ├── domain/
│       │   ├── exception/           # CrossTenantAccessException, ConflictException (409), InvalidOperationException (409), RateLimitExceededException (429), ResourceLimitExceededException (422)
│       │   ├── model/               # JPA entities: AppUser (JSONB skills), Tenant (JSONB limits/config), AuditLog (native INSERT, partitioned), AuditLogEvent, AuditLogId, RefreshToken
│       │   ├── repository/          # TenantAwareRepository (base), TenantRepository, AppUserRepository, AuditLogRepository, RefreshTokenRepository
│       │   ├── service/             # AuthService, TenantService, TenantResourceLimitService, UserService, AdminUserService, AuditLogService, AuditLogConsumer, AdminMetricsService
│       │   ├── telephony/           # TelephonyAdapter (interface), MockTelephonyAdapter, CallEvent, CallSession, TelephonyEventPublisher (RabbitMQ)
│       │   └── websocket/           # RabbitToWebSocketRelay, WebSocketEventBroadcaster, WebSocketEvent
│       ├── infrastructure/
│       │   ├── aspect/              # CrossTenantAspect (AOP), AuditAspect (@Around), @Audited annotation
│       │   ├── config/              # SecurityConfig, RedisConfig, RabbitMQConfig, FlywayConfig, WebSocketConfig (STOMP), AsyncConfig, OpenApiConfig
│       │   └── persistence/         # JsonMapConverter, JsonStringListConverter (legacy – prefer @JdbcTypeCode)
│       ├── security/                # JwtService, JwtParser, JwtProperties, JwtAuthFilter, TenantContext, TenantFilter, TokenBlacklistService, MfaService, LoginRateLimiter, SecurityConfig, AppUserDetails, UserDetailsServiceImpl, StompPrincipal, WebSocketAuthInterceptor
│       └── app/                     # ContactCenterApplication main class
└── src/main/resources/
    └── db/
        ├── migration/               # Flyway migrations V001–V020 (shared)
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

### VoIP Adapter Pattern

`TelephonyAdapter` (interface in `domain/telephony/`) decouples the system from a specific CPaaS provider:
- `MockTelephonyAdapter` – dev/test implementation, generates fake `CallEvent`s for UI testing.
- `TelephonyWebhookController` – receives webhook callbacks from the telephony provider.
- `MockCallController` (`/api/dev/calls/simulate`) – dev-only endpoint to trigger simulated calls.
- `TelephonyEventPublisher` – publishes `CallEvent`s to RabbitMQ exchange `cc.events` with routing key `call.*`.

To integrate a real CPaaS, implement `TelephonyAdapter` and replace the `@Primary` bean.

### WebSocket / Real-Time Hub

Spring STOMP over WebSocket (`WebSocketConfig`):
- Endpoint: `/ws` (SockJS fallback enabled).
- `WebSocketAuthInterceptor` – validates JWT at the STOMP CONNECT frame; rejects with HTTP 401 if invalid or missing. Creates `StompPrincipal` from token claims.
- `WebSocketController` – handles STOMP messages from clients (`@MessageMapping`).
- `RabbitToWebSocketRelay` – `@RabbitListener` on `cc.notifications` exchange; pushes events to STOMP destinations.
- `WebSocketEventBroadcaster` – service for sending events to `/user/{userId}/events` (targeted) and `/topic/tenant/{tenantId}/supervisor` (broadcast per tenant).

### Audit Log

`@Audited` annotation triggers `AuditAspect` (`@Around`) which publishes an `AuditLogEvent` to RabbitMQ (`cc.audit` exchange, routing key `audit.write`). `AuditLogConsumer` (`@RabbitListener`) persists via native INSERT to the partitioned `audit_log` table (bypasses JPA limitations with partitioned tables). Pagination endpoint: `GET /api/audit-logs` (ADMIN only, max 100/page).

### Database Migrations (Flyway)

Migrations live in `backend/src/main/resources/db/migration/`. Naming: `V{NNN}__{description}.sql`.

Current migrations: V001 (extensions) → V020 (user name fields). V019 converts ENUM types to VARCHAR + CHECK.

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

## Frontend Architecture

### Stack

Angular 21, standalone components, SCSS, RxJS, Angular Material, Vitest. Proxy `/api/*` and `/ws` → `localhost:8080`.

### Package Layout

```
frontend/src/app/
├── app.ts                           # Root component (<cc-toast-container> + <router-outlet>)
├── app.config.ts                    # provideRouter, provideHttpClient with interceptors, provideAnimations
├── app.routes.ts                    # Top-level lazy routes
├── core/
│   ├── interceptors/
│   │   ├── auth.interceptor.ts      # Attaches Bearer token; silent refresh on 401; queues in-flight requests
│   │   └── error-handler.interceptor.ts  # 403→toast "Brak uprawnień", 5xx→toast, status 0→"Brak połączenia"
│   ├── models/
│   │   └── jwt-payload.model.ts     # Typed JWT claims interface
│   └── services/
│       ├── auth.service.ts          # Signal-based auth state, login/logout/refresh, handleLoginSuccess()
│       ├── breadcrumb.service.ts    # Router.events → BreadcrumbItem[] observable
│       ├── notification.service.ts  # Signal-based toast queue, auto-dismiss 4–6s
│       ├── token.service.ts         # localStorage / sessionStorage for access+refresh tokens
│       ├── token-refresh.service.ts # Singleton refresh lock; queues concurrent 401 responses
│       └── websocket.service.ts     # STOMP/SockJS client; reconnect with backoff; typed event observables
├── shared/
│   └── components/
│       ├── app-shell/               # AppShellComponent – CSS Grid layout (navbar + sidenav + router-outlet)
│       ├── breadcrumbs/             # BreadcrumbsComponent (aria-current, Router.events)
│       ├── sidenav/                 # SidenavComponent – context menu per role, badge alertów (ADMIN), responsive overlay/sticky
│       ├── toast/                   # ToastContainerComponent (WCAG AA, aria-live)
│       └── top-navbar/              # TopNavbarComponent (hamburger, role badge, logout, tenant info)
└── features/
    ├── auth/                        # LoginComponent (credentials→MFA flow), ChangePasswordComponent, ForbiddenComponent
    │   └── services/public-tenant.service.ts  # Fetches tenant list for login dropdown (no auth)
    ├── tenants/                     # TenantListComponent, TenantFormComponent, TenantDeactivateModalComponent, TenantEditModalComponent
    │   └── tenant.service.ts        # 6 API methods; response is Tenant[] (not PagedResponse)
    ├── admin/
    │   ├── pages/
    │   │   ├── dashboard/           # AdminDashboardComponent (KPI cards, polling 30s, skeleton)
    │   │   ├── metrics/             # AdminMetricsPageComponent (placeholder)
    │   │   └── users/               # AdminUsersComponent → AdminUserListComponent + AdminUserFormComponent
    │   └── services/
    │       ├── admin-metrics.service.ts  # BehaviorSubject, timer(0,30000), guarded to ADMIN role only
    │       └── admin-user.service.ts
    ├── supervisor/
    │   ├── pages/users/             # UserListComponent, UserFormComponent, UserDeleteModalComponent, UserResetPasswordModalComponent
    │   └── services/user.service.ts # Agent CRUD; PagedResponse<User> paginacja
    └── agent/
        ├── pages/agent-desktop/     # AgentDesktopComponent – status panel (AVAILABLE/BUSY/BREAK/AFTER_CONTACT), contact tabs (max 4), WS baner reconnect
        └── services/
            ├── agent-status.service.ts   # Updates agent status via API + WS
            └── contact-tab.store.ts      # Signal store – manages open contact tabs (max 4)
```

### Guards & Routing

- `AuthGuard` – redirects to `/auth/login` if no valid token.
- `RoleGuard` – checks `data.roles` on route; redirects to `/forbidden` on mismatch.
- `RoleRedirectGuard` – on root `/`, navigates to role-specific dashboard.
- Lazy-loaded feature chunks: auth, tenants, admin, supervisor, agent (12 total).

### Key Conventions

- Standalone components only – no NgModules.
- Signal-based state (`signal()`, `computed()`) preferred over BehaviorSubject where possible; BehaviorSubject retained for streaming/polling scenarios.
- `@JdbcTypeCode(SqlTypes.JSON)` fields on the backend map to typed interfaces/models on the frontend (e.g. `skills: string[]`).
- `PagedResponse<T>` record from backend maps to frontend `PagedResponse<T>` interface with `content`, `totalElements`, `totalPages`, `page`, `size`.
- WCAG AA compliance: `aria-live` on toasts, `aria-current` on breadcrumbs, skip-link in AppShellComponent.

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
