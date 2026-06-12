# Architecture Document – Contact Center SaaS Platform

**Version:** 2.0
**Date:** 2026-06-12
**Status:** As-Built (updated from original Phase-1 plan)
**Based on:** PRD v1.0, TECH-STACK v1.0, and the `documentation/` as-built technical docs

> **Note on this revision:** Version 1.0 of this document (2026-03-12, "Approved") was
> written *before implementation* and described the planned architecture. This version 2.0
> updates the document to reflect the **actual state of the implemented system** as of
> 2026-06-12, based on the verified technical documentation in
> [`documentation/`](documentation/00-index.md) (which is itself cross-checked against the
> code). Where the original plan diverged from what was actually built, this document now
> describes "as-built" reality and marks unrealized parts of the original plan as
> **"Future / not yet implemented"**.
>
> For detailed, module-by-module documentation (backend modules, frontend routing, database
> schema, data flows, infrastructure), see [`documentation/00-index.md`](documentation/00-index.md) —
> that directory is the primary source of truth for implementation details; this document
> remains the high-level architectural reference and historical decision record (ADRs).

---

## Table of Contents

1. [Overview](#1-overview)
2. [System Architecture](#2-system-architecture)
3. [Component Structure](#3-component-structure)
4. [Data Architecture](#4-data-architecture)
5. [API Design](#5-api-design)
6. [Security Architecture](#6-security-architecture)
7. [Infrastructure and Deployment](#7-infrastructure-and-deployment)
8. [Cross-cutting Concerns](#8-cross-cutting-concerns)
9. [Key Architectural Decisions](#9-key-architectural-decisions)
10. [Risks and Mitigations](#10-risks-and-mitigations)

---

## 1. Overview

### 1.1 System Purpose

The Contact Center SaaS Platform is a multi-tenant, multi-channel communication management system. It enables organizations (tenants) to manage inbound and outbound customer interactions across telephone (VoIP/WebRTC), email, and social media channels from a single unified agent desktop.

The system targets three user personas: a global **Administrator** who manages the platform and all tenants, per-tenant **Supervisors** who manage agents and campaigns, and **Agents** who handle customer contacts.

### 1.2 Architectural Goals

| Goal | Description |
|------|-------------|
| Multi-tenancy | Logical data isolation between tenants with tenant_id-scoped queries enforced at the repository layer |
| Replaceability | Telephone provider and social media channels implemented via the Adapter pattern, swappable without code rewrites |
| High Availability | 99.9% SLA; no single point of failure in critical paths |
| Horizontal Scalability | Stateless application layer scales out; async communication decouples services |
| GDPR Compliance | Right to erasure, data portability, retention policies, and processing registry built in from day one |
| Real-time Capability | WebSocket-based live dashboards; routing decisions under 500 ms |

### 1.3 Key Architectural Decisions Summary

| ID | Decision | Status (as-built, 2026-06-12) |
|----|----------|--------------------------------|
| ADR-01 | Modular monolith in Phase 1, with module boundaries designed for future microservice extraction | ✅ Current — single Spring Boot app (`backend/app`), 24 domain modules under `com.contactcenter.api.<domain>` |
| ADR-02 | Single shared PostgreSQL database with logical multi-tenancy via tenant_id + Row-Level Security | ✅ Current — PostgreSQL 16, `tenant_id` + RLS via `app.current_tenant_id` (see `documentation/06-database.md`) |
| ADR-03 | RabbitMQ as the message broker (confirmed in TECH-STACK) | ✅ Current — RabbitMQ 3.13, extensive exchange/queue topology for domain events, voicebot escalation, social/email |
| ADR-04 | Redis for distributed caching, session state, agent presence, and queue state | ✅ Current — Redis 7: cache, agent presence, JWT blacklist, IVR sessions, rate limiting |
| ADR-05 | Telephony via external CPaaS provider behind an Adapter interface (provider selected separately) | ✅ Current — implemented for **Twilio Programmable Voice** (`TelephonyAdapter` / `TwilioTelephonyAdapter` / `MockTelephonyAdapter`) |
| ADR-06 | Python microservice for all AI/NLP/voicebot/chatbot workloads, called via REST from Spring Boot | ✅ Current — `voicebot/` (FastAPI): ASR (Whisper), NLU, AI summaries (Anthropic/OpenAI), REST + RabbitMQ escalation |
| ADR-07 | Data Warehouse fed by Change Data Capture (CDC) + RabbitMQ event streaming | ⚠️ Modified — implemented as a **periodic ETL** (`EtlSyncService`, `@Scheduled` every 60s) into **ClickHouse 24.3**, not CDC/outbox |
| ADR-08 | WebRTC for in-browser softphone; TURN/STUN servers managed internally | ⚠️ Partially — softphone uses **Twilio Voice JS SDK** (WebRTC under the hood, Twilio-managed media/TURN); no self-hosted coturn/TURN-STUN exists in the codebase or `docker-compose.yml` |

**New elements not present in the original plan:**
- **MinIO** (S3-compatible object storage) for call recordings (`recording` module, `S3Config`/`S3Properties`).
- **ClickHouse** as the Data Warehouse engine (ADR-07 originally left this unspecified).
- `ivr` and `dialer` as fully-developed, independent domains (drag-and-drop IVR editor with
  node positioning, zoom/fit-to-view; progressive dialer with `SKIP LOCKED` queue semantics).
- `social` module with adapters for Facebook Messenger, Instagram, and WhatsApp Cloud API.

See `documentation/02-architecture.md` §2.7 for the full ADR status table.

---

## 2. System Architecture

### 2.1 Architecture Style

**Phase 1 (MVP): Modular Monolith**

The backend is a single deployable Spring Boot application divided into strictly bounded modules. Each module owns its domain logic, exposes internal interfaces, and has no direct cross-module database access. This approach reduces operational complexity while preserving the ability to extract modules into independent microservices in Phase 2+.

The Python AI service is the sole external service from day one, given the fundamentally different runtime requirements of ML/NLP workloads.

### 2.2 High-Level Architecture Diagram (As-Built)

```
                         BROWSER (Angular SPA)
            Agent Desktop | Supervisor Dashboard | Admin Panel
                 HTTP/REST (/api/*)  +  WebSocket (STOMP, /ws-native)
                              |
                         NGINX (reverse proxy; TLS in prod, ngrok in local-demo)
                              |
                 +------------+-------------+
                 |                          |
        Spring Boot Backend          Angular static (Nginx)
        (modular monolith, :8080)
                 |
   +-----------------------------------------------------------+
   | JwtAuthFilter -> TenantFilter -> UsernamePasswordAuthFilter |
   +-----------------------------------------------------------+
        |        |          |          |        |       |
     auth/    tenant/     queue/     campaign/  ivr/    recording/
     user     agentgroup  routing    dialer     telephony  email/
                                                            social/  ...
        |        |          |          |        |       |
        v        v          v          v        v       v
   +----------------------------------------------------------+
   |                PostgreSQL 16 (RLS, tenant_id)             |
   +----------------------------------------------------------+

        |                    |                    |
        v                    v                    v
     Redis 7              RabbitMQ 3.13         MinIO (S3)
  (cache/presence/    (domain events,        (call recordings,
   JWT blacklist,      voicebot escalation)    files)
   IVR sessions)

        |                                          |
        v                                          v
   Voicebot (FastAPI, Python)               ClickHouse 24.3 (DWH)
   - ASR (Whisper)                          fed by EtlSyncService
   - NLU / intent detection                 (@Scheduled, every 60s)
   - AI summaries (Anthropic/OpenAI)
        |
        v
   RabbitMQ (escalation to agent) / Redis (conversation session)

   External: Twilio (Programmable Voice) — HTTP webhooks to
   TwilioWebhookController / TwilioVoiceController
```

**Phase 2 (plan, not yet implemented):** an API Gateway tier (Spring Cloud Gateway) in front
of the backend, and a dedicated WebRTC Media Service with self-hosted coturn (STUN/TURN)
were part of the original Phase-1 plan but do not exist in the codebase or
`docker-compose.yml`. Today, Nginx performs reverse-proxy/TLS-termination duties, and the
softphone relies on the Twilio Voice JS SDK (Twilio-managed WebRTC media/TURN), not a
self-hosted TURN server.

### 2.3 Real-Time Communication Architecture

```
Angular SPA  <--STOMP over native WebSocket (/ws-native)-->  Spring Boot (WebSocketController)
                                     |
                          WebSocketAuthInterceptor
                          (validates JWT on STOMP CONNECT,
                           builds StompPrincipal)
                                     |
                          Routing/Queue/Supervisor services
                          publish events to:
                            /topic/user/{userId}/events
                            /topic/tenant/{tenantId}/agents
                            /topic/tenant/{tenantId}/supervisor
                            /topic/tenant/{tenantId}/queue/{queueId}
```

Agent presence, queue depths, and real-time dashboard metrics are maintained in Redis (and
the operational PostgreSQL tables) and broadcast to connected Angular clients via STOMP over
a native WebSocket endpoint (`/ws-native`; legacy `/ws` also exists). The frontend
`WebSocketService` (`@stomp/stompjs`, no SockJS) maintains a single event stream
(`events$: Observable<WsEvent>`), with automatic reconnect and topic re-subscription. Domain
events that need to be broadcast are typically relayed from RabbitMQ to WebSocket
(`RabbitToWebSocketRelay`, queue `cc.queue.call-events`) or published directly by the owning
service (e.g., `SupervisorMetricsService`, `QueueController`/`WaitTimeEstimationService`).

---

## 3. Component Structure

### 3.1 Angular Frontend (SPA)

The frontend is **a single Angular project** (`frontend/`) — not a monorepo with separate
apps/libs. Angular 21, **standalone components only** (no NgModules), Signals + RxJS.

```
frontend/
  src/app/
    app.config.ts          # global providers (router, HttpClient, Transloco, error handler)
    app.routes.ts           # root routes, role-based lazy loading
    core/                    # cross-cutting: guards, interceptors, services, models
      guards/                # authGuard, roleGuard, roleRedirectGuard
      interceptors/          # authInterceptor (JWT + silent refresh), errorHandlerInterceptor
      services/              # AuthService, TokenService, WebSocketService, NotificationService, ...
      models/                # JwtPayload, PagedResponse, contact/customer/agent-group models
    shared/                  # shared components/services/styles
      components/            # cc-app-shell, cc-top-navbar, cc-sidenav, cc-breadcrumbs, ...
      styles/                # design tokens (oklch palettes, light/dark theme)
    features/                # feature modules per domain/role
      auth/                  # login, MFA, change password, forbidden
      admin/                 # ADMIN: dashboard, cross-tenant users, metrics
      supervisor/            # SUPERVISOR: agents, queues, campaigns, customers, reports,
                              #   settings (email, phone numbers, IVR, disposition sets,
                              #   Twilio config, AI config), agent groups, callbacks, IVR editor
      agent/                  # AGENT: desktop (softphone, customer panel, dispositions,
                              #   email/social contact panels, calendar), customers, callbacks
      tenants/                # tenant CRUD (routed from admin)
      dispositions/           # disposition set / custom disposition CRUD (shared)
      integrations/           # social media OAuth integrations (FB/IG/WhatsApp)
      campaigns/, customers/, reports/  # currently placeholders — real implementations
                              #   live under features/supervisor/pages/*
  public/i18n/                # pl.json, en.json, de.json, uk.json (Transloco)
```

**Routing:** top-level routes are role-scoped and lazy-loaded (`/admin/**`, `/supervisor/**`,
`/agent/**`, `/auth/**`), guarded by `authGuard` + `roleGuard`. `roleRedirectGuard` sends an
authenticated user to their role's default route.

**State management:** `signal()` / `computed()` / `effect()` for local/component state
(e.g., `ContactTabStore`, `SoftphoneService`). `BehaviorSubject` / RxJS only for continuous
streams (WebSocket events, polling), bridged to signals via `toSignal()` where convenient.
**No NgRx** is used.

**Softphone:** `SoftphoneService` is a signal-based state machine wrapping the **Twilio Voice
JS SDK** (`@twilio/voice-sdk`), handling call states (idle/ringing/connecting/active/ended)
and blind/attended transfer. There is no custom WebRTC peer-connection management or
self-hosted signaling — Twilio's SDK handles the WebRTC/SIP layer.

**i18n:** `@jsverse/transloco`, 4 languages (`pl` default, `en`, `de`, `uk`), loaded from
`public/i18n/*.json` via a custom `TranslocoHttpLoader`.

**UI:** no Angular Material/Bootstrap — custom CSS design system (`styles.scss`, oklch color
tokens, light/dark theme via `data-theme` attribute), native `<dialog>` elements for modals.

See `documentation/05-frontend.md` for full routing tables, component inventories, and
WebSocket event flows.

### 3.2 Spring Boot Backend (Modular Monolith)

Single Maven multi-module project (`backend/pom.xml` parent + `backend/app`), Java 21 /
Spring Boot 3.3.5. Code is organized by **technical layer first**, with domain modules as
packages within each layer:

```
backend/app/src/main/java/com/contactcenter/
  api/             # REST controllers + DTOs, one package per domain module
  app/             # ContactCenterApplication (main class)
  domain/          # business logic: model, repository, service, routing, telephony, social, ...
  infrastructure/  # Spring config, AOP (audit aspect), S3, ETL, social adapters
  security/        # JWT, TenantContext, filters, MFA
```

**24 domain modules** (under `api/<module>`, each with its own controllers + `dto/`, backed
by `domain/service` and `domain/repository`):

| Module | Responsibility |
|--------|-----------------|
| `tenant` | Tenant CRUD, resource limits, per-tenant config |
| `user`, `auth` | Users, login, JWT, MFA, password management |
| `agentgroup`, `agentbreak` | Agent groups; agent breaks / unavailability + calendar |
| `queue` | Queues, routing strategies, assignments, wait-time estimation |
| `telephony`, `dialer`, `ivr` | Twilio integration, progressive dialer, IVR trees |
| `campaign` | Outbound campaigns, CSV import, agent/group assignment |
| `contact`, `customer` | Contact (interaction) lifecycle; customer CRM + GDPR |
| `disposition` | Disposition codes and disposition set templates |
| `email`, `social` | Email channel (IMAP/SMTP); social media channels (FB/IG/WhatsApp) |
| `recording` | Call recording access (MinIO/S3 presigned URLs), retention |
| `reports`, `telemetry`, `admin` | Historical reports, frontend log ingestion, cross-tenant admin metrics + ETL status |
| `auditlog` | Append-only audit log (AOP-driven via `@Audited`) |
| `phonenumber` | Phone number (DID) management + routing rules |
| `websocket` | STOMP/WebSocket controller and auth interceptor |
| `public_` | Public endpoints (tenant list for login screen) |

**Module isolation rules:**
- Each module's repositories extend `TenantAwareRepository` and call
  `assertSameTenant(entity.getTenantId())` before writes.
- Cross-module communication primarily happens via in-process service calls and RabbitMQ
  domain events (e.g., `agent.status.changed`, `call.incoming`, `call.hangup`,
  `contact.queued`).
- Flyway migrations are shared across the whole application (single sequential numbering
  `V001`–`V073+`, not per-module prefixes).

See `documentation/04-backend.md` and `documentation/01-overview.md` §1.3 for full module
detail (classes, endpoints, RabbitMQ bindings).

### 3.3 Python AI Service (Voicebot, FastAPI)

`voicebot/` is a standalone FastAPI service — the only non-JVM runtime, per ADR-06.

```
voicebot/
  app/
    main.py            # FastAPI app; endpoints below
    nlu.py             # detect_intent — intent/NLU detection
    summarize.py       # AI conversation summaries (anthropic / openai SDKs)
    session.py         # Redis-backed session get/update/delete
    rabbit.py          # aio_pika robust connection; publish_escalation()
    config.py          # pydantic-settings configuration
  tests/               # pytest + pytest-asyncio
```

**Actual endpoints (`voicebot/app/main.py`):**

```
POST /voicebot/turn      # one turn of an IVR voicebot conversation:
                          # ASR (if audio) -> NLU -> response text/action,
                          # may trigger escalation to an agent via RabbitMQ
POST /ai/summarize       # generate an AI summary of a contact's transcript
POST /ai/transcribe      # speech-to-text via openai-whisper
GET  /health             # health check
```

Communication with Spring Boot: synchronous HTTP (`VoicebotClient` in the `ivr` module) for
ASR/NLU/summary calls, plus RabbitMQ for escalation events (voicebot -> agent) and Redis for
shared conversation-session state (`ivr:session:{callId}`).

> The original plan's `/chatbot/process`, `/tts/synthesize`, `/classify/intent` endpoints,
> Rasa/Dialogflow integrations, and a separate text chatbot do **not** exist in the codebase.
> A text-based chatbot for social/chat channels remains a **planned, unimplemented** feature
> (see `documentation/07-data-flows.md` §7.13).

### 3.4 Channel Adapters

**Telephony — Twilio (module `telephony`):**
- `TelephonyAdapter` interface with `TwilioTelephonyAdapter` (production) and
  `MockTelephonyAdapter` (dev/testing, `telephony.provider=mock`) implementations.
- Inbound: Twilio webhooks (TwiML) -> `TwilioWebhookController` / `TwilioVoiceController` ->
  `call.incoming` RabbitMQ event -> IVR / routing.
- Outbound: backend -> Twilio REST API (`com.twilio.sdk`), with a per-tenant
  `TwilioRestClient` cached via Caffeine.
- Recordings downloaded from Twilio and stored in MinIO/S3 by
  `TwilioRecordingDownloadService`.

**Email (module `email`):**
- Inbound: IMAP polling (`EmailPollingService`, `@Scheduled`, default every 60s) per tenant
  mailbox configuration (Jakarta Mail / `angus-mail`).
- Outbound: SMTP (`EmailSendService`). Reply templates rendered with Mustache
  (`MustacheTemplateEngine`, `email_template` table).
- Routing of inbound messages to queues/contacts via `EmailRoutingService` +
  `email_routing_rule`.

**Social media (module `social`):**
- Adapters for **Facebook Messenger, Instagram, and WhatsApp Cloud API** under
  `infrastructure/social/{Facebook,Instagram,WhatsApp}Adapter`, registered in
  `SocialAdapterRegistry`.
- OAuth2 connect flow (`SocialOAuthController`), webhooks (`SocialWebhookController`,
  public). Inbound messages are queued (`cc.queue.social-incoming`) and processed
  asynchronously by `SocialMessageConsumer`, which creates/updates `contact` +
  `social_message` rows.
- OAuth tokens encrypted at rest (AES-256-GCM, `SocialTokenEncryptionService`), stored in
  `social_integration`.

> A common `ChannelAdapter` interface as originally envisioned is realized in spirit (each
> channel has its own adapter/registry), but the concrete interface shapes differ per channel
> rather than sharing one generic Java interface across telephony/email/social.

### 3.5 Routing Engine

Implemented as `RoutingService` within the `queue` module, driven by RabbitMQ events rather
than a single synchronous in-process call chain:

```
contact.queued (RabbitMQ, cc.queue.contact-routing)
      |
      v
RoutingService: looks up queue config (routing_strategy: SKILL_BASED | ROUND_ROBIN |
                FIRST_AVAILABLE), available agents (v_queue_available_agents,
                AVAILABLE status + skills match for SKILL_BASED)
      |
      v
Assign contact to agent -> update contact.status=ASSIGNED -> publish assignment
      via WebSocket (/topic/user/{agentId}/events, type=CONTACT_ASSIGNED)
```

- `agent.status.changed` events (cc.queue.agent-status) trigger re-evaluation of the queue.
- `call.hangup` (cc.queue.routing-hangup) triggers queue cleanup/refresh.
- `contact.agent.direct` (cc.queue.agent-direct) handles direct assignment after a BLIND
  transfer to a specific agent.
- `WaitTimeEstimationService` (`@Scheduled`, every 30s) computes estimated wait time (EWT)
  per queue, published via WebSocket (`QUEUE_WAIT_UPDATE`).

Queue/agent availability reads are primarily against PostgreSQL (`v_queue_available_agents`
view, GIN-indexed `app_user.skills`/`queue.required_skills`) rather than a pure Redis-only
model as originally envisioned, though Redis is used for agent presence/heartbeats and for
guarding against duplicate dialer attempts (`SET NX`).

### 3.6 Data Warehouse Pipeline

```
PostgreSQL (operational: contact, campaign_contact, app_user, queue, ...)
      |
      |  EtlSyncService — @Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")
      |  runContactSync() / runCampaignContactSync() / runAgentDimSync() / runQueueDimSync()
      v
ClickHouse 24.3 (DWH)
  - ContactDwRow (fact table for contacts)
  - CampaignDwRow (campaign attempt facts)
  - agent / queue dimension tables
      |
      v
Reporting / Admin UI (reports module, AdminMetricsController, EtlStatusController)
```

Each synced table tracks its own sync state (`etl_sync_state`, `markDone`/`markError`/
`checkLagAndAlert`), visible in the admin panel (`/api/admin/etl/status`,
`/api/admin/etl/trigger` for manual re-sync).

This corresponds to ADR-07 (replication to a DWH), but implemented as a **periodic polling
ETL**, not a transactional outbox + CDC pipeline. The DWH consumer (`EtlSyncService`) writes
via the ClickHouse JDBC driver (`ClickHouseDwWriter`). There is no `outbox` table and no
`dwh.cdc` RabbitMQ exchange in the implementation.

---

## 4. Data Architecture

> **As-built note:** the model below reflects the real schema (PostgreSQL 16, Flyway
> `V001`-`V073+`). For full table-by-table detail, indexes, ER diagrams and known schema
> inconsistencies, see `documentation/06-database.md`.

### 4.1 Multi-Tenancy Strategy

**Approach (unchanged from ADR-02): shared schema, `tenant_id` column on every tenant-scoped
table, enforced in two layers.**

1. **Application layer:** every repository extends `TenantAwareRepository` and calls
   `assertSameTenant(entity.getTenantId())` before `save()`/`update()`, comparing the
   entity's `tenant_id` against `TenantContext` (ThreadLocal, populated by `TenantFilter`
   from the validated JWT). This check catches mistakes *before* the SQL is issued.
2. **PostgreSQL Row-Level Security (RLS):** introduced in `V012` as defense-in-depth. The
   application executes `SET LOCAL app.current_tenant_id = '<uuid>'` (via
   `set_tenant_context()`, `V023`) at the start of each transaction; RLS policies read this
   via `current_setting('app.current_tenant_id', TRUE)::UUID`. Tables with RLS enabled
   include `customer`, `contact`, `campaign`, `queue`, `app_user`, `ivr_tree`, `audit_log`,
   `email_message`, `social_message`, `social_integration`, `agent_group`, `agent_break`,
   `tenant_twilio_config`, `tenant_ai_config`, `phone_number`, `phone_routing_rule`,
   `contact_transcription`, `contact_ai_summary`, `custom_disposition`, `disposition_set(_item)`,
   `contact_event`.
3. **Database roles:** `app_user` (DB role, NOBYPASSRLS — used by the running application)
   vs. `admin_user` (BYPASSRLS — used by Flyway and cross-tenant ETL/admin operations).
4. **Known inconsistency:** a few newer migrations (`V059`, `V064`, `V067`, `V068`) use
   `app.tenant_id` instead of the established `app.current_tenant_id` convention — flagged
   as a fix candidate, not yet corrected.
5. `audit_log.tenant_id` is nullable (NULL = global/system event, e.g. tenant creation).

### 4.2 Core Data Model (simplified)

```
TENANT (1) ──────────────────────────────── (N) APP_USER
    |   config JSONB (limits, channels)          role: ADMIN | SUPERVISOR | AGENT
    |                                             status: ACTIVE/AVAILABLE/BUSY/BREAK/
    |                                                      AFTER_CONTACT/OFFLINE/INACTIVE
    |                                             skills JSONB (GIN) — skill-based routing
    |
    ├─── (N) CUSTOMER (phone/email JSONB arrays, gdpr_consent, soft-delete)
    |         └─ (N) CONTACT  ── partitioned by started_at (monthly)
    |                  ├─ channel: PHONE | EMAIL | SOCIAL_FACEBOOK | SOCIAL_INSTAGRAM |
    |                  |           SOCIAL_WHATSAPP
    |                  ├─ status: IVR|QUEUED|ASSIGNED|ACTIVE|ON_HOLD|COMPLETED|ABANDONED|
    |                  |          ERROR|NOT_REACHED|TRANSFERRED
    |                  ├─ (N) CONTACT_EVENT (stage history: IVR/QUEUE/AGENT/...)
    |                  ├─ (0..1) CONTACT_TRANSCRIPTION, CONTACT_AI_SUMMARY
    |                  ├─ (N) EMAIL_MESSAGE  (channel=EMAIL)
    |                  └─ (N) SOCIAL_MESSAGE (channel=SOCIAL_*)
    |
    ├─── (N) CAMPAIGN ── (N) CAMPAIGN_CONTACT (LIST-partitioned per campaign)
    |         ├─ dialer_type: PROGRESSIVE|PREDICTIVE|MANUAL
    |         ├─ M:N CAMPAIGN_AGENT / CAMPAIGN_AGENT_GROUP (or all_agents=TRUE)
    |         └─ queue_id (nullable FK -> QUEUE)
    |
    ├─── (N) QUEUE (routing_strategy, required_skills JSONB GIN, wait_config)
    |         └─ M:N QUEUE_AGENT, AGENT_GROUP / AGENT_GROUP_MEMBER
    |
    ├─── (N) SCHEDULED_CALLBACK (campaign_id/agent_id/customer_id nullable)
    |
    ├─── (N) IVR_TREE (definition JSONB: nodes/transitions, versioned) + IVR_AUDIO
    |
    ├─── (N) PHONE_NUMBER -> PHONE_ROUTING_RULE (-> IVR_TREE or QUEUE)
    |
    ├─── (N) CUSTOM_DISPOSITION (scope: campaign XOR queue) / DISPOSITION_SET(_ITEM)
    |
    ├─── (N) EMAIL_TEMPLATE, EMAIL_ROUTING_RULE
    |
    ├─── (N) SOCIAL_INTEGRATION (OAuth tokens, AES-256 encrypted)
    |
    ├─── (N) AGENT_BREAK
    |
    ├─── (1) TENANT_TWILIO_CONFIG, (1) TENANT_AI_CONFIG (both AES-256-GCM encrypted secrets)
    |
    └─── (N) AUDIT_LOG (partitioned by created_at, monthly)
```

### 4.3 Key Entity Notes

- **`app_user`** (not `user` — reserved word in PostgreSQL): `role` (ADMIN/SUPERVISOR/AGENT),
  `status` (account vs. availability), `skills JSONB` (GIN-indexed array for skill-based
  routing), `mfa_secret`/`mfa_enabled`, soft-delete via `is_deleted`.
- **`customer`**: GDPR-aware — `phone`/`email` as JSONB arrays, `custom_fields JSONB`,
  `gdpr_consent JSONB`, `is_deleted` + `anonymize_customer()`/`export_customer_data()`
  functions, fuzzy search via `pg_trgm` (`search_customers()`).
- **`contact`**: the core interaction record, **RANGE-partitioned monthly on `started_at`**
  (composite PK `(contact_id, started_at)`). `agent_id`/`customer_id` are `ON DELETE SET NULL`
  to support GDPR erasure without losing aggregate history. `duration_seconds` is
  trigger-maintained.
- **`campaign_contact`**: **LIST-partitioned per `campaign_id`**, partitions created
  dynamically by the application at campaign-creation time; archived to
  `campaign_contact_archive` after completion.
- Secrets (`tenant_twilio_config`, `tenant_ai_config`) are encrypted at rest with
  AES-256-GCM via JPA `AttributeConverter`s, stored as `Base64(IV‖ciphertext)`.

Full DDL, indexes, partitioning helper functions and Mermaid ER diagrams:
`documentation/06-database.md` §3-4.

### 4.4 Redis Data Structures

| Key Pattern | Type | Content |
|-------------|------|---------|
| `jwt:blacklist:{jti}` | String (TTL = token expiry) | revoked access-token marker |
| Agent presence / heartbeats | Hash | status, last-seen — used by routing/availability views |
| `ivr:session:{callId}` | Hash/String | voicebot conversation session state (shared with `voicebot/`) |
| Dialer de-duplication locks | String (`SET NX`) | prevents double-dialing the same `campaign_contact` record |
| Tenant/queue config caches | Hash/String | short-TTL caches to avoid repeated PostgreSQL reads |

> The original plan's Redis-only "queue depth / sorted-set of waiting contacts" model is
> **not** the primary mechanism in the as-built system — queue/agent availability is read
> primarily from PostgreSQL (`v_queue_available_agents`), with Redis used for presence,
> session/auth state, and dialer locking. See `documentation/02-architecture.md` §2.5 and
> `documentation/06-database.md`.

### 4.5 Data Warehouse Schema (ClickHouse)

Populated by `EtlSyncService` (periodic, not CDC — see §3.6):

```
ContactDwRow      -- fact table: one row per contact (channel, direction, status,
                  -- durations, disposition, agent/queue/campaign references)
CampaignDwRow     -- fact table: campaign attempt outcomes
agent dimension   -- via runAgentDimSync()
queue dimension   -- via runQueueDimSync()
```

Sync status/lag is tracked per table in `etl_sync_state` (PostgreSQL) and surfaced via
`EtlStatusController` / `AdminMetricsController` (`/api/admin/etl/status`).

---

## 5. API Design

### 5.1 API Style and Conventions

- **Style:** REST over HTTPS
- **Format:** JSON (application/json); multipart/form-data for file uploads (CSV import,
  IVR audio upload)
- **Path prefix:** `/api/...` — **no `/api/v1/` version prefix** in the implementation
  (versioning is not yet a concern; revisit if/when a breaking v2 is needed)
- **Documentation:** springdoc-openapi, Swagger UI at `/swagger-ui.html`
- **Authentication:** Bearer JWT in `Authorization` header (except endpoints listed in
  `TenantFilter.PUBLIC_PATH_PREFIXES` and `SecurityConfig` permit-list, e.g. `/api/auth/**`,
  `/api/public/**`, Twilio/social webhook callbacks)
- **Pagination:** Spring Data `Pageable` (`?page=&size=&sort=`) for list endpoints
- **Error format:** standard Spring `ProblemDetail`/exception-handler JSON
  (`status`, `error`, `message`, `path`, `timestamp`)

### 5.2 Key Endpoint Groups (representative, not exhaustive)

```
Authentication (auth, public)
  POST   /api/auth/login
  POST   /api/auth/refresh
  POST   /api/auth/logout
  POST   /api/auth/mfa/verify
  POST   /api/auth/password/reset-request
  POST   /api/auth/password/reset
  GET    /api/public/tenants               # tenant list for login screen (no auth)

Tenants (tenant, admin-only)
  GET    /api/tenants
  POST   /api/tenants
  GET    /api/tenants/{tenantId}
  PATCH  /api/tenants/{tenantId}
  GET    /api/admin/metrics                 # cross-tenant admin dashboard
  GET    /api/admin/etl/status              # EtlSyncService lag per table
  POST   /api/admin/etl/trigger

Users (user)
  GET    /api/users
  POST   /api/users
  GET    /api/users/{userId}
  PATCH  /api/users/{userId}
  PUT    /api/users/{userId}/status         # agent sets own presence (AVAILABLE/BUSY/...)
  PUT    /api/users/{userId}/skills

Customers (customer)
  GET    /api/customers?q={search}          # fuzzy search via pg_trgm
  POST   /api/customers
  GET    /api/customers/{customerId}
  PATCH  /api/customers/{customerId}
  DELETE /api/customers/{customerId}/gdpr-erase
  GET    /api/customers/{customerId}/export # GDPR Art. 20
  GET    /api/customers/{customerId}/contacts (v_customer_timeline)

Contacts (contact)
  GET    /api/contacts
  GET    /api/contacts/{contactId}
  PATCH  /api/contacts/{contactId}/disposition
  POST   /api/contacts/{contactId}/transfer

Campaigns (campaign)
  GET    /api/campaigns
  POST   /api/campaigns
  GET    /api/campaigns/{campaignId}
  PATCH  /api/campaigns/{campaignId}
  POST   /api/campaigns/{campaignId}/start
  POST   /api/campaigns/{campaignId}/pause
  POST   /api/campaigns/{campaignId}/stop
  POST   /api/campaigns/{campaignId}/contacts (multipart CSV import)
  PUT    /api/campaigns/{campaignId}/agents          # campaign_agent / campaign_agent_group

Queues / Agent Groups / Breaks (queue, agentgroup, agentbreak)
  GET    /api/queues
  POST   /api/queues
  GET    /api/queues/{queueId}
  PUT    /api/queues/{queueId}
  GET    /api/queues/{queueId}/agents       # v_queue_available_agents
  GET    /api/agent-groups
  POST   /api/agent-groups
  GET    /api/agent-breaks?agentId=&from=&to=
  POST   /api/agent-breaks

IVR / Telephony / Dialer / Phone Numbers (ivr, telephony, dialer, phonenumber)
  GET    /api/ivr
  POST   /api/ivr
  GET    /api/ivr/{ivrId}
  PUT    /api/ivr/{ivrId}
  POST   /api/ivr/{ivrId}/activate
  POST   /api/telephony/webhooks/voice      # Twilio TwiML webhook (public)
  POST   /api/telephony/calls               # initiate outbound call
  GET    /api/phone-numbers
  PUT    /api/phone-numbers/{id}/routing-rules

Recordings (recording)
  GET    /api/recordings/{recordingId}
  GET    /api/recordings/{recordingId}/stream  # presigned MinIO/S3 URL redirect

Reports / Telemetry / Admin (reports, telemetry, admin)
  GET    /api/reports/agents?from=&to=
  GET    /api/reports/campaigns/{campaignId}
  GET    /api/reports/export?type=&from=&to=
  POST   /api/telemetry/logs                # frontend log ingestion

Email / Social (email, social)
  GET    /api/email/templates
  POST   /api/email/messages/{id}/reply
  GET    /api/social/integrations
  POST   /api/social/oauth/connect
  POST   /api/social/webhooks/{platform}    # public webhook callbacks

Dispositions (disposition)
  GET    /api/disposition-sets
  POST   /api/disposition-sets
  GET    /api/custom-dispositions?campaignId=|queueId=

Audit Log (auditlog)
  GET    /api/audit-log?from=&to=&entityType=
```

> For the full per-module endpoint inventory (request/response DTOs, query params,
> `@PreAuthorize` rules), see `documentation/04-backend.md`.

### 5.3 WebSocket API

```
STOMP over native WebSocket, endpoint: /ws-native (no SockJS fallback)

Auth: WebSocketAuthInterceptor validates the JWT on STOMP CONNECT and builds a
      StompPrincipal (userId, tenantId, role) used for topic authorization.

Subscriptions (server -> client):
  /topic/user/{userId}/events            # per-agent: assignments, call state, notifications
  /topic/tenant/{tenantId}/agents         # supervisor: agent presence/status changes
  /topic/tenant/{tenantId}/supervisor     # supervisor: live KPI/dashboard updates
  /topic/tenant/{tenantId}/queue/{queueId} # queue depth / wait-time updates

Representative WsEvent types (server -> client):
  CONTACT_ASSIGNED     { contactId, channel, customerId, customerName, remoteAddress }
  CONTACT_ENDED        { contactId, durationSeconds }
  AGENT_STATUS_CHANGED { agentId, status }
  QUEUE_WAIT_UPDATE    { queueId, depth, estimatedWaitSeconds }
  SYSTEM_NOTIFICATION  { severity, message }

Internal relay: RabbitToWebSocketRelay consumes cc.queue.call-events and republishes to
the relevant /topic/... destinations; some services (SupervisorMetricsService,
WaitTimeEstimationService) publish directly via SimpMessagingTemplate.
```

### 5.4 Voicebot API (internal network only)

```
POST /voicebot/turn
  Request:  { tenantId, callId, sessionId, audio? | text?, context }
  Response: { responseText, audioUrl?, action, escalate: bool }

POST /ai/summarize
  Request:  { tenantId, contactId, transcript }
  Response: { summary }

POST /ai/transcribe
  Request:  audio (multipart) / { tenantId, callId }
  Response: { text, segments[] }

GET /health
  Response: { status: "ok" }
```

> Original plan's `/chatbot/process`, `/tts/synthesize`, `/classify/intent` are **not
> implemented**; a text chatbot for social/chat channels remains a future feature.

---

## 6. Security Architecture

### 6.1 Filter Chain (critical order)

```
HTTP Request
   |
   v
JwtAuthFilter        -- parses JWT (RS256), checks Redis blacklist, sets SecurityContext
   |
   v
TenantFilter         -- re-parses JWT, sets TenantContext (tenantId, userId, role) + MDC;
   |                     skips verification for PUBLIC_PATH_PREFIXES
   v
UsernamePasswordAuthenticationFilter -- effectively a no-op (stateless, no sessions)
   |
   v
ExceptionTranslationFilter -- AccessDeniedException / AuthenticationException -> RFC 7807
   |
   v
Controller (@PreAuthorize, @EnableMethodSecurity)
```

This exact order (`JwtAuthFilter -> TenantFilter -> UsernamePasswordAuthenticationFilter`)
is enforced in `SecurityConfig` and is a **hard project rule** (see `CLAUDE.md`) — `JwtAuthFilter`
handles Spring Security authorization (roles/`@PreAuthorize`), `TenantFilter` handles
multi-tenancy (`TenantContext` ThreadLocal + DB session variable for RLS).

### 6.2 Authentication (JWT)

`JwtService`/`JwtParser` issue and verify **RS256**-signed JWTs. Claims:

| Claim | Meaning |
|---|---|
| `sub`, `email` | user's email |
| `tenant_id`, `tenant_name` | tenant UUID and display name |
| `user_id` | user UUID |
| `role` | `ADMIN` \| `SUPERVISOR` \| `AGENT` |
| `mfaVerified` | `true` once TOTP has been completed |
| `iss` | `jwt.issuer` (default `contact-center`) |
| `exp`/`iat` | access token TTL = 900s (15 min) |

- **Refresh token:** UUID v4, stored hashed (SHA-256) in `refresh_token`, TTL 604800s
  (7 days). `POST /api/auth/refresh` exchanges it for a new access token and rotates the
  refresh token.
- **Logout:** `POST /api/auth/logout` revokes the refresh token in PostgreSQL and adds the
  access token's JTI to the Redis blacklist (`TokenBlacklistService`, TTL = remaining token
  lifetime). `JwtAuthFilter` checks this blacklist on every request.
- **MFA (TOTP, RFC 6238):** `MfaService` — SHA1, 6 digits, 30s period, ±1 step tolerance.
  Replay protection via `mfa:used:{userId}:{code}` in Redis (TTL 90s). Flow:
  `GET /api/auth/mfa/setup` (generates secret + QR code) -> user scans in an authenticator
  app -> `POST /api/auth/mfa/verify` -> JWT reissued with `mfaVerified=true`.

### 6.3 Authorization (RBAC)

`@EnableMethodSecurity(prePostEnabled = true)` + `@PreAuthorize` at controller-method level.
Roles (`ADMIN`, `SUPERVISOR`, `AGENT`) come from the `role` JWT claim
(`AppUserDetails.getAuthorities()` adds the `ROLE_` prefix). `tenant_id` is **never** trusted
from the request body — always sourced from `TenantContext`.

URL-level rules in `SecurityConfig` (order matters — first matching matcher wins):
- `/api/admin/**` -> `ADMIN`
- `GET/PATCH /api/tenants/*/config` -> `ADMIN` or `SUPERVISOR` (must precede the general
  `/api/tenants/**` rule)
- `/api/tenants/**` -> `ADMIN`
- `/api/supervisor/twilio-config/**`, `/api/supervisor/ai-config/**` -> `SUPERVISOR`
- `POST /api/contacts/*/ai-summary` -> `AGENT`/`SUPERVISOR`/`ADMIN`
- everything else -> `authenticated()`

**New public endpoint = changes in two places** (project rule, see `CLAUDE.md`):
1. `SecurityConfig.securityFilterChain()` — `requestMatchers(...).permitAll()`
2. `TenantFilter.PUBLIC_PATH_PREFIXES` (and, if `JwtAuthFilter` itself must be skipped,
   `JwtAuthFilter.PUBLIC_PATH_PREFIXES` too)

Current public prefixes: `/actuator/health`, `/api-docs`, `/swagger-ui`, `/api/auth/login`,
`/api/auth/refresh`, `/api/public/`, `/webhooks/`, `/api/webhooks/`,
`/api/telephony/webhook/**`, `/api/telephony/hold-music`, `/api/oauth/*/callback`, `/ws/**`,
`/ws-native/**`, `/api/logs`.

### 6.4 WebSocket Authentication

`/ws` and `/ws-native` are public at the HTTP-upgrade level (`SecurityConfig`), but
`WebSocketAuthInterceptor` validates the JWT on the STOMP `CONNECT` frame and builds a
`StompPrincipal(userId, tenantId, role)`; a missing/invalid JWT rejects the CONNECT.

### 6.5 Data Protection

| Layer | Mechanism |
|-------|-----------|
| Transport | TLS terminated at Nginx/ngrok (prod: TLS 1.2+, HSTS) |
| Database | PostgreSQL RLS (`app.current_tenant_id`) as defense-in-depth — see §4.1 |
| Passwords | bcrypt |
| MFA secrets | encrypted in `app_user.mfa_secret` |
| Tenant integration secrets | AES-256-GCM (`tenant_twilio_config`, `tenant_ai_config`, social OAuth tokens) via JPA `AttributeConverter`s |
| Call recordings | stored in MinIO/S3 (`recording`), accessed via presigned URLs |

> The original plan's reference to HashiCorp Vault for secrets management is **not
> implemented** — secrets are environment variables (Docker Compose `.env*`) plus the
> AES-256-GCM application-level encryption above. Centralized secrets management remains a
> future consideration.

### 6.6 GDPR / RODO Compliance

| Requirement | Implementation |
|-------------|----------------|
| Right to erasure (Art. 17) | `GdprService` / `anonymize_customer()` (PostgreSQL function) nulls PII on `customer`, sets `is_deleted=TRUE`; `contact` rows keep `customer_id` via `ON DELETE SET NULL` for statistical integrity |
| Data portability (Art. 20) | `export_customer_data()` + `GET /api/customers/{id}/export` |
| Customer history view | `v_customer_timeline` (UNION ALL of contact/email/social) |
| Marketing consent | `customer.gdpr_consent JSONB` |

### 6.7 Audit Log

Service methods annotated `@Audited(entityType=..., captureOldValue=true)` are intercepted by
`AuditAspect` (`infrastructure/aspect`) and written to `audit_log`
(RANGE-partitioned monthly by `created_at`):

```
audit_log (
  log_id        UUID,
  created_at    TIMESTAMPTZ   -- partition key
  tenant_id     UUID NULLABLE, -- NULL = global/system event
  user_id       UUID NULLABLE,
  action        TEXT,
  entity_type   TEXT,
  entity_id     UUID,
  old_value     JSONB,
  new_value     JSONB,
  ...
)
```

Retention: 2 years, old partitions dropped via `drop_old_audit_log_partitions()` (pg_cron).

---

## 7. Infrastructure and Deployment

> **As-built note:** the implemented deployment is **Docker Compose**, not Kubernetes. §7.3
> ("Production Infrastructure (Kubernetes)") and §7.4 (HA design) below describe a **future
> target state** that has not been built; treat them as forward-looking planning, not current
> reality. See `documentation/08-infrastructure.md` for the actual local/local-demo setup.

### 7.1 Environments (current)

| Environment | Purpose | Notes |
|-------------|---------|-------|
| local (dev) | Developer workstation | `docker compose up -d` for infra services (Postgres, Redis, RabbitMQ, MinIO, ClickHouse, voicebot); backend (`mvn spring-boot:run`) and frontend (`npm start`) run natively with hot reload |
| local-demo | Full containerized stack for demos | Adds `docker-compose.local-demo.yml`: backend + frontend containers, `cc-nginx` reverse proxy, ngrok for TLS — only port 80 exposed |

Staging/production environments as described in §7.3/§7.4 are **not yet implemented**.

### 7.2 Docker Compose (As-Built)

```bash
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d --remove-orphans
```

`docker-compose.yml` (base infrastructure):

| Service | Image | Host port | Role |
|---------|-------|-----------|------|
| `postgres` | `postgres:16-alpine` | 5432 | OLTP, multi-tenant (RLS) |
| `redis` | `redis:7-alpine` | 6379 | cache, sessions, presence, JWT blacklist |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | 5672 (AMQP), 15672 (UI) | domain events, backend <-> voicebot |
| `minio` + `minio-init` | `minio/minio:latest` / `minio/mc:latest` | 9000 (S3), 9001 (console) | call recordings, files |
| `clickhouse` + `clickhouse-init` | `clickhouse/clickhouse-server:24.3` | 8123 (HTTP/JDBC), 9002 (native) | DWH, fed by `EtlSyncService` |
| `voicebot` | build from `voicebot/` (FastAPI) | 8001 | ASR/NLU/summaries |

`docker-compose.local-demo.yml` overrides:

| Service | Change |
|---------|--------|
| `backend` | build `Dockerfile.backend`, Spring profile `prod`, healthcheck `/actuator/health`, internal-only (`expose: 8080`) |
| `frontend` | build `Dockerfile.frontend`, served by an internal Nginx (`expose: 80`) |
| `nginx` (`cc-nginx`) | reverse proxy, **only port 80 exposed to host** (TLS via ngrok) |
| `postgres`, others | host ports removed — internal-only on `cc-network` |

All services share the `cc-network` Docker bridge network. `depends_on: condition:
service_healthy` ensures Postgres/Redis/RabbitMQ are healthy before backend start; backend
healthcheck has `start_period: 120s` to allow Flyway migrations to complete.

**Volumes:** `postgres_data`, `redis_data`, plus default volumes for RabbitMQ/MinIO/ClickHouse,
and `backend_logs`/`nginx_logs` in local-demo. `docker compose down` preserves volumes;
`down -v` deletes them (see `CLAUDE.md`).

**Key environment variable groups** (`.env.local-demo`): database (`DB_*`), Redis
(`REDIS_*`), RabbitMQ (`RABBITMQ_*`), S3/MinIO (`S3_*`), ClickHouse (`CLICKHOUSE_*`,
`ETL_DW_TYPE`), JWT/security (`JWT_*`, `APP_ENCRYPTION_SECRET`, `EMAIL_ENCRYPTION_KEY`,
`SOCIAL_TOKEN_ENCRYPTION_KEY`), Twilio (`TWILIO_*`), voicebot (`VOICEBOT_ENABLED`,
`VOICEBOT_URL`), dialer (`DIALER_AGENT_POLL_INTERVAL_MS`), and general
(`SPRING_PROFILES_ACTIVE`, `CORS_ALLOWED_ORIGINS`, `WEBSOCKET_ALLOWED_ORIGINS`,
`PROMETHEUS_ENABLED`, `APP_BASE_URL`).

### 7.3 Production Infrastructure (Kubernetes) — Future / Not Yet Implemented

```
Kubernetes Cluster (EU region)
├── Namespace: contact-center-prod
│   ├── Deployment: backend (Spring Boot)
│   │     replicas: 3+ (HPA: scale on CPU > 70%)
│   │     readinessProbe: /actuator/health/readiness
│   │     livenessProbe:  /actuator/health/liveness
│   ├── Deployment: voicebot (FastAPI)
│   │     replicas: 2+ (HPA: scale on CPU > 60%)
│   ├── Deployment: frontend (Nginx)
│   │     replicas: 2
│   ├── Service: ingress (LoadBalancer / Ingress with cert-manager)
│   └── CronJob: gdpr-retention-cleanup (daily)
│
├── Managed Services (cloud provider)
│   ├── PostgreSQL: Managed HA (primary + replica(s))
│   ├── Redis: Managed Redis Cluster
│   ├── RabbitMQ: Managed or self-hosted cluster
│   ├── Object Storage: S3-compatible (EU region), replacing self-hosted MinIO
│   └── ClickHouse Cloud or self-hosted ClickHouse cluster
│
└── Observability Namespace
    ├── Prometheus + Alertmanager
    ├── Grafana
    └── Loki (log aggregation)
```

> Note: the original plan's `coturn`/TURN StatefulSet and `api-gateway` are dropped from this
> future picture — see §2.2 and ADR-08 status; the Twilio Voice JS SDK handles WebRTC media,
> so no self-hosted TURN server is needed.

### 7.4 High Availability Design — Future / Not Yet Implemented

| Component | Target HA Strategy | RTO Impact |
|-----------|-------------|------------|
| Spring Boot | 3+ replicas, rolling updates, HPA | Zero-downtime deploys |
| PostgreSQL | Primary + sync replica; automatic failover (Patroni or cloud HA) | < 60s automatic failover |
| Redis | Cluster with replication; sentinel for failover | < 30s failover |
| RabbitMQ | 3-node cluster, quorum queues | Survives 1 node loss |
| Object Storage | Cloud-native HA (multi-AZ) | Transparent |

**Target overall RTO:** < 1 hour (NFR-A03). None of this is implemented in the current
single-node Docker Compose deployment — there is no HA, automated failover, or PITR backup
configured for local/local-demo.

### 7.5 CI/CD Pipeline — Future / Not Yet Implemented

```
Developer pushes branch
        |
        v
CI (GitHub Actions or similar)
  1. Unit tests (JUnit 5 / Vitest / Pytest)
  2. Integration tests (Testcontainers: Postgres, Redis, RabbitMQ)
  3. Cross-tenant isolation tests
  4. Static analysis / lint (Checkstyle, ESLint, Ruff)
  5. Build Docker images
        |
        v (merge to develop/main)
  Deploy to DEV / STAGING
        |
        v (manual approval gate)
  Deploy to PRODUCTION
```

No CI/CD pipeline currently exists in the repository; tests are run locally
(`mvn test`, `npm test`, `pytest`).

### 7.6 Database Migrations

Flyway manages schema migrations: `backend/src/main/resources/db/migration/`,
sequential `V001`...`V073+` (current count), naming `V<number>__<description>.sql`.

Rules (see `CLAUDE.md` and `documentation/06-database.md` §1):
- **Never edit an applied migration** — Flyway validates checksums (`validate-on-migrate:
  true`) and refuses to start on mismatch. Always add a new `Vxxx__fix_*.sql`.
- `clean-disabled: true` on all profiles — `flyway clean` is blocked.
- `clean-on-validation-error: true` / `clean-disabled: false` must **never** be set, especially
  not on `prod`.
- Single sequential numbering across the whole application — not per-module.

---

## 8. Cross-cutting Concerns

> **As-built note:** the items below mix implemented capabilities with future plans.
> `spring-boot-starter-actuator` is present and `/actuator/health` is used for Docker
> healthchecks. A Prometheus metrics registry is wired in `application-prod.yml` but is
> **disabled by default** (`PROMETHEUS_ENABLED=false` in `.env.local-demo`) — it can be
> turned on but is not part of the running local-demo stack. Distributed tracing
> (Jaeger/Tempo/OpenTelemetry), centralized log aggregation (Loki), Alertmanager, and a
> dedicated metrics dashboard remain **future / not yet implemented**.

### 8.1 Logging

Standard Spring Boot logging (Logback), written to `backend_logs` volume in local-demo
(`LOG_PATH`/`LOG_FILE` env vars). `TenantFilter` populates MDC with `tenantId`/`userId`/`role`
so log lines can be correlated per tenant/request. The frontend has a `POST /api/telemetry/logs`
(`/api/logs`, public) endpoint via the `telemetry` module for client-side error/log ingestion.

Structured JSON logging, log shipping to a central store (Loki/ELK), and sensitive-data
masking converters described in earlier drafts are **not implemented**.

### 8.2 Distributed Tracing — Future / Not Yet Implemented

No tracing instrumentation (Micrometer Tracing / OpenTelemetry / Jaeger / Tempo) exists in the
codebase today. `traceId` propagation in API error responses is limited to whatever Spring
Boot's default `ProblemDetail`/exception handling provides.

### 8.3 Metrics and Monitoring (current state + future plan)

**Implemented today:**
- `spring-boot-starter-actuator`, base path `/actuator`, `/actuator/health` used by Docker
  Compose healthchecks.
- Prometheus registry conditionally enabled (`management.metrics.export.prometheus.enabled:
  ${PROMETHEUS_ENABLED:false}`) in `application-prod.yml` — present but off by default.
- Application-level dashboards: `admin` module (`AdminMetricsController`, cross-tenant) and
  `supervisor` module (`SupervisorMetricsService`, per-tenant KPIs pushed over WebSocket) —
  these read directly from PostgreSQL/Redis, not from a metrics/observability stack.
- `EtlStatusController` exposes ETL sync lag per table.

**Future (not implemented):** a `cc_*` Prometheus metric naming convention, Alertmanager
rules (API latency, RabbitMQ consumer lag, replica lag, agent-availability alerts), and a
Grafana dashboard layer.

### 8.4 Error Handling

Spring's standard exception-handling/`ProblemDetail` mechanism returns structured JSON error
responses (`status`, `error`, `message`, `path`, `timestamp`). `TenantFilter` and
`JwtAuthFilter` can short-circuit with RFC 7807 responses before reaching
`ExceptionTranslationFilter` (see §6.1). `AccessDeniedException`/`AuthenticationException`
are handled by Spring Security's `ExceptionTranslationFilter`.

Calls to the voicebot service (`VoicebotClient`, `ivr` module) use standard `RestClient`/
`WebClient` calls with timeouts; a circuit breaker (Resilience4j) as described in earlier
drafts is **not confirmed as implemented** — verify in code before relying on this.

### 8.5 Caching Strategy

| Cache Layer | Technology | What is Cached |
|-------------|-----------|----------------|
| JWT blacklist | Redis | revoked access-token JTIs (TTL = remaining token life) |
| Per-tenant Twilio REST client | Caffeine (in-process) | `TwilioRestClient` instances, keyed by tenant |
| Agent presence / dialer locks | Redis | presence heartbeats, `SET NX` dialer de-dup locks |
| Voicebot conversation session | Redis | `ivr:session:{callId}` |
| MFA replay protection | Redis | `mfa:used:{userId}:{code}` (TTL 90s) |

The Redis-backed "queue routing state" and "report aggregates" caches from earlier drafts are
**not the primary mechanism** — queue/agent availability and reporting read mostly from
PostgreSQL views (`v_queue_available_agents`, `mv_campaign_stats`) and ClickHouse (via
`EtlSyncService`). See §4.4/§4.5.

### 8.6 Background Jobs and Scheduling

| Job | Schedule | Description |
|-----|----------|-------------|
| `EtlSyncService` (contact/campaign-contact/agent-dim/queue-dim sync) | `@Scheduled(fixedDelayString="${etl.sync.fixed-delay-ms:60000}")` (60s) | Sync operational data to ClickHouse |
| `WaitTimeEstimationService` | `@Scheduled` (~30s) | Recompute estimated wait time per queue, push via WebSocket |
| `EmailPollingService` | `@Scheduled` (~60s, per-tenant mailbox config) | IMAP polling for inbound email |
| pg_cron jobs (DB-side) | daily/periodic | create next-month `contact`/`audit_log` partitions, clean up `refresh_token`, archive `campaign_contact` |

All `@Scheduled` jobs that touch tenant-scoped data must follow the `TenantContext.snapshot()`/
`restore()`/`clear()` pattern documented in `CLAUDE.md` and `documentation/04-backend.md`,
since `@Scheduled` methods run outside the normal request thread and filter chain.

---

## 9. Key Architectural Decisions

> The ADRs below are kept as the **historical record** of the original design rationale.
> See §1.3 for the as-built status of each (✅ current / ⚠️ modified / ⚠️ partial), and
> `documentation/02-architecture.md` §2.7 for the as-built summary.

### ADR-01: Modular Monolith in Phase 1

**Decision:** Deploy all Spring Boot modules as a single application in Phase 1.

**Rationale:** The team is internal and limited in size (PRD 12.3). Operating a distributed microservices system demands significant DevOps maturity (service discovery, distributed tracing, network policies, independent deployments). The modular monolith delivers the same bounded-context discipline with a fraction of the operational overhead. Module boundaries are enforced by package visibility and internal interface contracts, not network calls.

**Consequences:** Modules must not share database tables directly. All cross-module communication uses in-process service interfaces or domain events via RabbitMQ. The Python AI service is the exception — it is genuinely a separate runtime requirement.

**Migration path:** Each module can be extracted into an independent Spring Boot service by replacing its in-process calls with HTTP/gRPC calls and moving its Flyway scripts to its own schema, without changing business logic.

### ADR-02: Shared PostgreSQL with Logical Multi-tenancy

**Decision:** Single PostgreSQL cluster; all tenant-scoped tables include `tenant_id`. Row-Level Security (RLS) as defense-in-depth.

**Rationale:** At the target scale of 50 tenants, database-per-tenant would mean 50 Flyway migration targets, 50 connection pools, and 50× the schema maintenance burden. Logical isolation is sufficient and validated by penetration testing (PRD acceptance criterion). RLS provides a second enforcement layer that survives application-layer bugs.

**Risk mitigation:** RLS policies defined and tested in CI. Cross-tenant isolation test suite runs on every build.

### ADR-03: RabbitMQ as Message Broker

**Decision:** RabbitMQ (confirmed in TECH-STACK).

**Rationale:** RabbitMQ is a mature, operationally well-understood broker. For the event volumes expected (50 tenants × ~200 events/second peak), RabbitMQ quorum queues provide sufficient throughput with strong durability guarantees. Kafka would be a better fit at higher throughput volumes (Faza 2 option), but adds operational complexity (ZooKeeper/KRaft, partitioning, consumer group management) that is not justified in Phase 1.

**Exchange topology (original plan):**
```
contact.events     (topic exchange)  → routing_engine, dwh_consumer, reporting_consumer
campaign.events    (topic exchange)  → dialer, dwh_consumer
channel.inbound    (direct exchange) → channel-specific handler queues
audit.events       (fanout exchange) → audit_log_writer, compliance_consumer
dwh.cdc            (direct exchange) → dwh_loader
notifications      (topic exchange)  → websocket_broadcaster
```

> **As-built:** the actual topology is queue-centric rather than the exchange layout above,
> e.g. `cc.queue.contact-routing`, `cc.queue.agent-status`, `cc.queue.agent-direct`,
> `cc.queue.routing-hangup`, `cc.queue.call-events`, `cc.queue.social-incoming`, plus
> voicebot escalation queues. There is no `dwh.cdc` exchange — DWH sync is a periodic ETL
> (ADR-07). See `documentation/02-architecture.md` and `documentation/04-backend.md` for the
> real bindings.

### ADR-04: Redis for Agent Presence and Real-Time State

**Decision:** Redis is the system of record for agent presence and queue state (not PostgreSQL).

**Rationale:** Real-time routing requires sub-millisecond reads of agent availability and skills. PostgreSQL is authoritative for durable data; Redis is authoritative for ephemeral operational state. Agent presence is maintained via heartbeats (30s TTL on Redis keys). If an agent's browser disconnects, the Redis key expires within 60s and the agent is automatically set offline — no stale routing assignments.

### ADR-05: Adapter Pattern for Telephony Provider

**Decision:** All telephony provider interactions go through the `TelephonyAdapter` interface.

**Rationale:** PRD explicitly requires the ability to swap VoIP providers without rewriting code (PRD 4.4). The adapter layer translates provider-specific webhook payloads and SDK calls to internal domain events and commands. Provider selection (Twilio, Telnyx, Vonage) is a configuration concern, not a code concern.

**Interface contract:**
```java
interface TelephonyAdapter {
    CallSession initiateCall(String from, String to, CallOptions options);
    void answerCall(String callId, AgentSession agentSession);
    void transferCall(String callId, TransferTarget target, TransferMode mode);
    void hangupCall(String callId);
    void startRecording(String callId);
    void stopRecording(String callId);
    HealthStatus checkHealth();
}
```

### ADR-06: Python AI Service for NLP Workloads

**Decision:** Voicebot, chatbot, intent classification, and TTS integration run in a separate Python FastAPI service.

**Rationale:** The Python ML ecosystem (HuggingFace Transformers, Rasa, spaCy) is fundamentally incompatible with the JVM runtime. Running NLP models in a Python process allows independent scaling (GPU nodes for inference), independent deployment, and access to the full Python ML toolchain. The Spring Boot backend calls this service synchronously over HTTP with a circuit breaker.

### ADR-07: Outbox Pattern for DWH Replication

**Decision:** Events destined for the Data Warehouse are written to an outbox table in the same transaction as the originating business data, then relayed to RabbitMQ asynchronously.

**Rationale:** Prevents the dual-write problem: if the application writes to PostgreSQL and then publishes to RabbitMQ in two separate operations, a crash between the two leaves the DWH out of sync. The outbox guarantees atomicity with the business transaction. The relay process (polling or Debezium) provides at-least-once delivery. The DWH consumer must be idempotent (contact_id as deduplication key).

> **As-built (⚠️ modified):** implemented as a periodic polling ETL (`EtlSyncService`,
> `@Scheduled` every 60s) directly to **ClickHouse**, not an outbox + CDC/RabbitMQ pipeline.
> There is no `outbox` table. Idempotency/incremental sync is tracked via `etl_sync_state`
> per table rather than a dedup key on `contact_id`. See §3.6 and §4.5.

### ADR-08: Modular Social Media Plugin Architecture

**Decision:** Each social media platform is a separate plugin loaded via Spring's component scanning, implementing the `ChannelAdapter` interface.

**Rationale:** PRD 6.2 notes platform API instability as a risk. Isolating each platform in its own plugin class means a breaking API change at one provider affects only that plugin. New platforms (TikTok, Telegram) are added as new plugins without modifying any existing code.

> **As-built (⚠️ partial):** the `social` module implements adapters for Facebook
> Messenger, Instagram, and WhatsApp Cloud API under `infrastructure/social/`, registered in
> a `SocialAdapterRegistry` — the isolation goal is achieved, but there is no single shared
> `ChannelAdapter` Java interface spanning telephony/email/social; each channel has its own
> adapter shape. See §3.4.

---

## 10. Risks and Mitigations

> **As-built note:** several mitigations below assume infrastructure that is **not yet
> implemented** (k6 load tests, Kubernetes HPA/Redis Cluster HA, Alertmanager, a TURN
> server). These remain valid as a future hardening plan; the current single-node Docker
> Compose deployment does not provide most of these safety nets. RT-04 (WebRTC/TURN) is
> largely superseded — the Twilio Voice JS SDK handles WebRTC media, removing the
> self-hosted TURN dependency (see ADR-08 status and §2.2).

### 10.1 Technical Risks

| ID | Risk | Probability | Impact | Mitigation |
|----|------|-------------|--------|------------|
| RT-01 | VoIP provider API differences requiring significant integration work | High | High | Adapter pattern (ADR-05); POC with 2 providers before committing; interface contract defined first |
| RT-02 | Routing engine performance degradation under load | Medium | High | Routing state in Redis (ADR-04); load tests (50 tenants × 100 agents) as Definition of Done gate; routing decision latency metric alerted at 300ms |
| RT-03 | Data inconsistency between PostgreSQL and DWH | Medium | Medium | Outbox pattern (ADR-07); idempotent DWH consumer; replication lag metric alerted when > 30 minutes |
| RT-04 | WebRTC audio quality issues across diverse network configurations | High | Medium | TURN/STUN server in infrastructure; MOS score monitoring per call; fallback SIP dial-in number documented |
| RT-05 | Social media API instability (policy changes, rate limits) | Medium | High | Plugin isolation (ADR-08); health checks per adapter; graceful degradation (channel marked unavailable, supervisor alerted) |
| RT-06 | Cross-tenant data leak via application bug | Low | Critical | RLS as second enforcement layer (ADR-02); penetration testing gate before production; cross-tenant isolation tests in CI |
| RT-07 | Redis unavailability breaks routing and agent presence | Medium | High | Redis Cluster with 3 nodes; fallback: routing falls back to PostgreSQL query (slower but available); graceful degradation documented |
| RT-08 | RabbitMQ quorum queue performance under peak load | Low | Medium | Load tests include RabbitMQ consumer lag monitoring; queue depth alert at 1000 messages; horizontal scaling of consumers |

### 10.2 Architectural Risks Introduced by Decisions

| Decision | Risk | Mitigation |
|----------|------|------------|
| Modular monolith (ADR-01) | A bug in one module can crash all modules | Module-level exception isolation; circuit breakers around external calls; graceful degradation patterns |
| Shared PostgreSQL (ADR-02) | Noisy-neighbor: one tenant's heavy queries affect others | Per-tenant query timeout (statement_timeout); connection pool limits per tenant; read replicas for report queries |
| Redis as system of record for presence (ADR-04) | Redis unavailability means agents cannot be routed | Redis Cluster HA; fallback routing mode documented in runbook |

### 10.3 Compliance Risks

| ID | Risk | Mitigation |
|----|------|------------|
| RC-01 | GDPR erasure not propagated to DWH | GDPR erase command publishes anonymization event to DWH pipeline; DWH consumer anonymizes corresponding dimension records |
| RC-02 | Recording retention policy not enforced | Daily GDPR cleanup job with alerting if job fails; supervisor UI shows pending deletions |
| RC-03 | Outbound contact initiated without marketing consent | Campaign dialer checks GDPR_CONSENT before each call attempt; contact skipped and flagged if no valid consent |

---

## Appendix A: Technology Decisions Summary (As-Built)

| Layer | Technology | Version (as-built) | Notes |
|-------|-----------|----------------|----------------|
| Frontend framework | Angular | ^21.2.0, standalone components, no NgModules | |
| Frontend language/tooling | TypeScript ~5.9.2, RxJS ~7.8.0, `@jsverse/transloco` ^8.3.0, Vitest, ESLint+Prettier | | |
| Backend runtime | Java + Spring Boot | Java 21, Spring Boot 3.3.5 | Maven multi-module (`backend/pom.xml` + `backend/app`) |
| Backend libs | JJWT (RS256), MapStruct, Lombok, springdoc-openapi, Resilience4j (where used), Caffeine | | |
| AI / Voicebot service | Python + FastAPI + Uvicorn | `voicebot/` | Whisper (ASR), Anthropic/OpenAI SDKs (summaries), `aio_pika`, `pydantic-settings` |
| Relational database | PostgreSQL | 16 (alpine) | Flyway `V001`-`V073+`, RLS |
| Cache / presence | Redis | 7 (alpine) | sessions, JWT blacklist, presence, voicebot sessions |
| Message broker | RabbitMQ | 3.13 (management-alpine) | domain events, backend <-> voicebot |
| Data Warehouse | ClickHouse | 24.3 | fed by `EtlSyncService` (periodic ETL) |
| Object Storage | MinIO (S3-compatible) | `minio/minio:latest` | call recordings, files |
| Telephony | Twilio Programmable Voice + Twilio Java SDK + Twilio Voice JS SDK | | webhooks + REST + browser softphone |
| Email | Jakarta Mail (`angus-mail`) + Mustache templates | | IMAP polling + SMTP |
| Social media | Facebook Graph API, Instagram, WhatsApp Cloud API (OAuth2 + webhooks) | | |
| Reverse proxy | Nginx (`nginx/nginx-local-demo.conf`) | | local-demo: only port 80 exposed |
| Local TLS | ngrok | | local-demo only |
| Containerization | Docker / Docker Compose | `docker-compose.yml` + `docker-compose.local-demo.yml` | |

**Items from the original plan not present in the as-built system** (kept here as a future
backlog, not a current gap to "fix"):

| Item | Original role | As-built status |
|------|----------------|------------------|
| Coturn (TURN/STUN) | WebRTC media relay | Not needed — Twilio Voice JS SDK handles WebRTC (ADR-08 status) |
| API Gateway (Spring Cloud Gateway / Nginx routing layer) | Central ingress/routing | Nginx does reverse-proxy only; no gateway-level auth/rate-limiting layer |
| Kubernetes | Container orchestration | Docker Compose only (§7.3 is a future target) |
| Service mesh | — | Not applicable (modular monolith, no inter-service mesh needed) |
| Prometheus + Grafana + Loki | Observability stack | Actuator/Prometheus registry present but disabled (`PROMETHEUS_ENABLED=false`); Grafana/Loki not present |
| Micrometer + OpenTelemetry / Jaeger / Tempo | Tracing | Not implemented |
| CI/CD (GitHub Actions/GitLab CI) | Pipeline | Not implemented — tests run locally |
| HashiCorp Vault / Sealed Secrets | Secret management | Env vars (`.env*`) + application-level AES-256-GCM encryption for sensitive config fields |

---

## Appendix B: Module Dependency Rules (As-Built, 24 modules)

The original ArchUnit-enforced dependency table below described a *planned* set of ~13
coarse-grained modules. The as-built system has **24 domain modules** under
`com.contactcenter.api.<module>` (see §3.2), organized by technical layer
(`api/`/`domain/`/`infrastructure/`/`security/`) rather than one Java package per module with
ArchUnit-enforced boundaries. Indicative dependency directions (in-process service calls +
RabbitMQ events):

```
auth, user        -> (foundational; no dependencies on other domain modules)
tenant            -> auth, user
agentgroup,
agentbreak        -> user, tenant
customer          -> tenant (GDPR functions live in PostgreSQL, called via customer module)
contact           -> tenant, customer, user, queue, campaign (FK references)
queue             -> tenant, user, agentgroup            (routing reads v_queue_available_agents)
telephony         -> tenant, contact, queue, ivr, recording
dialer            -> campaign, contact, queue, telephony
ivr               -> tenant, contact, telephony, voicebot (HTTP client)
campaign          -> tenant, customer, contact, queue, agentgroup, disposition
disposition       -> tenant (campaign/queue-scoped via custom_disposition)
email, social     -> tenant, contact, customer
recording         -> tenant, contact (MinIO/S3)
reports,
telemetry, admin  -> read-only across modules + ClickHouse (EtlSyncService)
auditlog          -> cross-cutting, via @Audited AOP (called by all modules)
phonenumber       -> tenant, telephony, ivr, queue (routing rules)
websocket         -> cross-cutting (security/STOMP), used by queue/supervisor/agent modules
public_           -> tenant (read-only, unauthenticated)
```

> No ArchUnit cycle-detection tests are confirmed to exist for this 24-module layout —
> verify in `backend/app/src/test` before relying on this as an enforced constraint. All
> repositories extend `TenantAwareRepository` (§4.1) regardless of module.

---

## Appendix C: Performance Budget

> Largely unchanged from the original plan; treat as **target NFRs**, not yet validated by
> load testing (no k6/load-test suite exists — see §7.5).

| Operation | Target | Measurement Point |
|-----------|--------|-------------------|
| API CRUD operations | p95 < 200 ms | Backend response time |
| Customer search (fuzzy, `pg_trgm`) | p95 < 1000 ms | API response (NFR, `search_customers()`) |
| Routing decision | p95 < 500 ms | `RoutingService` internal metric |
| WebSocket dashboard update | <= 5s between updates | Client-side measurement |
| Voicebot turn (`POST /voicebot/turn`) | p95 < 800 ms | Backend -> voicebot round trip |
| Call connection setup | < 3s | Agent desktop to call connected (Twilio) |
| DWH sync lag (`EtlSyncService`) | < 1 hour (target < 15 min) | `etl_sync_state` / `EtlStatusController` |
| CSV import (campaign contacts, up to 100k) | < 2 minutes | API response (async) |
| Report export | < 30 seconds | API response (async) |
