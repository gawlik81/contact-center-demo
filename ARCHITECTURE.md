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
11. [EPIC-28 — Per-Tenant Plugin (Extension) System](#11-epic-28--per-tenant-plugin-extension-system)

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

---

## 11. EPIC-28 — Per-Tenant Plugin (Extension) System

> **Status:** Planned (design stage, not yet implemented). This section is additive to the
> rest of this document and does not change any as-built behavior described in §1-10. It
> introduces a **new, runtime, JAR-based extension mechanism** that must not be confused with
> **ADR-08** (§9): ADR-08 is a *compile-time* plugin pattern for social-media channel adapters,
> registered via Spring component scanning at build time, with no per-tenant runtime upload.
> The system below allows a **tenant administrator to upload a JAR at runtime** through the
> admin panel; the JAR is loaded into the running JVM behind a dedicated `ClassLoader`,
> scoped to that tenant, and invoked at well-defined extension points in the agent/supervisor
> workflow.

### 11.1 Goals and Non-Goals

| | |
|---|---|
| **Goal** | Let a tenant integrate with their own external systems (customer CRM, ticketing, data sync) without a backend code change or redeploy. |
| **Goal** | Let a plugin extend the agent/supervisor UI (a button, a side panel) without a frontend build per tenant. |
| **Goal** | Contain a buggy or hostile plugin so it cannot destabilize the platform or read another tenant's data. |
| **Non-goal** | Full sandboxing equivalent to a separate process/container — explicitly out of scope per the agreed in-process `ClassLoader` isolation model (see §11.3 and RT-10). |
| **Non-goal** | A plugin marketplace/catalog shared across tenants in this epic — each installation is tenant-private; a shared catalog is a future epic. |
| **Non-goal** | Arbitrary plugin-to-plugin communication — not supported; every plugin only talks to the Plugin SDK facade. |

### 11.2 Plugin Manifest

Every plugin JAR must contain `META-INF/plugin-manifest.json` (chosen over `MANIFEST.MF`
attributes so it can hold structured, nested data and be validated with a JSON Schema before
any class is touched):

```json
{
  "pluginKey": "acme-crm-sync",
  "displayName": "Acme CRM Sync",
  "version": "1.3.0",
  "vendor": "Acme Sp. z o.o.",
  "vendorContact": "support@acme.example",
  "sdkVersion": "1.x",
  "entryPointClass": "com.acme.contactcenter.plugin.AcmeCrmPlugin",
  "extensionPoints": [
    "PRE_CONTACT_CONNECT",
    "POST_CONTACT_END",
    "CUSTOMER_SYNC",
    "DISPOSITION_SET",
    "MANUAL_ACTION"
  ],
  "permissions": [
    "customer:read",
    "customer:update",
    "contact:read",
    "http:egress:api.acme-crm.example"
  ],
  "uiPanels": [
    {
      "panelId": "acme-crm-side-panel",
      "mountPoint": "AGENT_DESKTOP_SIDE_PANEL",
      "url": "classpath:/plugin-ui/index.html",
      "sandbox": "allow-scripts"
    }
  ],
  "manualActions": [
    { "actionId": "open-in-crm", "label": "Otwórz w CRM", "mountPoint": "AGENT_DESKTOP_TOOLBAR" }
  ],
  "checksumSha256": "<sha-256 of the JAR, computed by the build tool that produced it>"
}
```

**Validation rules (enforced at upload time, §11.4):**
- `pluginKey` is globally unique per tenant installation (`tenant_plugin_installation.plugin_key`,
  unique per tenant) but the same `pluginKey` can be installed by many tenants independently.
- `extensionPoints` and `permissions` must be drawn from a fixed, backend-defined enum — a
  plugin cannot declare a permission the platform doesn't know about.
- `entryPointClass` must implement the SDK's `PluginEntryPoint` interface (§11.6) and must be
  the **only** class in the JAR directly instantiated by the host; everything else is reached
  through it.
- `sdkVersion` is checked against the host's supported SDK range; a plugin compiled against an
  incompatible major SDK version is rejected at upload, not at first invocation.

### 11.3 Execution and Isolation Model

**Decision (pre-agreed, not renegotiated here): in-process execution, one dedicated
`ClassLoader` per installed plugin, inside the same JVM as the Spring Boot application.**

```
ContactCenterApplication JVM
 ├── Spring ApplicationContext (beans, repositories, services)   <- never exposed to plugins
 ├── PluginRuntimeManager
 │     ├── PluginClassLoader[tenant=A, pluginKey=acme-crm-sync]  -- parent: a narrow
 │     │     "platform-api" ClassLoader exposing ONLY the plugin-sdk module's
 │     │     interfaces/DTOs, not the full application classpath
 │     ├── PluginClassLoader[tenant=A, pluginKey=other-plugin]
 │     └── PluginClassLoader[tenant=B, pluginKey=acme-crm-sync]  -- separate instance even
 │           though it is "the same plugin" as tenant A's; no shared static state
 └── PluginInvocationExecutor (bounded thread pool + per-call timeout, §11.7)
```

Key isolation mechanisms and their explicit limits:

1. **Dedicated `ClassLoader` per `(tenant_id, plugin_key)` pair.** Each installation gets its
   own loader instance — even the same JAR installed twice (two tenants) is loaded twice, so
   static fields, caches, and singletons inside the plugin cannot leak between tenants through
   class-level state. The loader's parent is **not** the application classloader; it is a thin
   `platform-api` classloader that exposes only `com.contactcenter.pluginsdk.*` (interfaces and
   immutable DTOs — see §11.6), so a plugin cannot simply `Class.forName("com.contactcenter...")`
   its way into a Spring bean or a JPA repository class.
2. **No direct access to `ApplicationContext`, JPA repositories, or any Spring bean.** The
   *only* object passed into the plugin is a `PluginContext` facade backed by the SDK. This is
   the single most important control: even if a plugin obtains a reference to *some* object via
   reflection, the object graph reachable from the SDK facade contains no repository, no
   `EntityManager`, no `TenantContext` mutator, and no other tenant's data, by construction
   (the facade is instantiated per-call with the calling tenant's ID baked in, not looked up by
   the plugin).
3. **Per-tenant data scoping is enforced in the SDK implementation, not by the plugin.** Every
   `PluginContext` method that touches data (e.g., `getCustomer(id)`, `updateCustomer(...)`)
   is implemented by a backend class that calls the normal tenant-aware repositories with
   `tenantId` taken from the invocation's `TenantContext` snapshot (§11.8), identical to how a
   normal request-scoped service call would — the plugin never supplies or controls the
   `tenantId` used for the underlying query.
4. **What this model does *not* fully prevent (accepted residual risk, tracked as RT-10):**
   reflection against JDK classes (`java.lang.reflect`, `sun.misc.Unsafe`-style tricks where
   still reachable), classloader manipulation to reach sibling classloaders via thread-context
   classloader swapping, or resource exhaustion (CPU/memory) from within the same JVM. A
   `SecurityManager`-style hard sandbox is not provided by the JDK going forward (deprecated for
   removal since JDK 17+), so this is mitigated procedurally (code review gate + signing,
   §11.4) and operationally (timeouts, thread pool isolation, resource quotas, §11.7), not
   eliminated. This is the explicit trade-off of choosing in-process isolation over
   process/container isolation, and is the single largest architectural risk in this epic.

### 11.4 Upload, Validation, and Activation Flow

```
Tenant Admin (Angular admin panel)
   |  multipart/form-data: POST /api/supervisor/plugins  (JAR + manifest already inside JAR)
   v
PluginUploadController
   |
   v
PluginValidationService
   ├─ 1. Size/MIME guard (reject >50MB, reject non-JAR/ZIP magic bytes)
   ├─ 2. Compute SHA-256 of the uploaded bytes; compare against manifest.checksumSha256
   │      (detects accidental corruption; NOT a substitute for signing, see below)
   ├─ 3. Open as ZIP, read META-INF/plugin-manifest.json, validate against JSON Schema
   ├─ 4. Static scan of the JAR's class list (ASM, no class loading yet):
   │      - reject if it references blacklisted packages (java.lang.reflect.* beyond
   │        normal use, java.lang.ProcessBuilder, java.nio.file.* outside an allowed
   │        temp scratch dir, sun.misc.*, custom ClassLoader subclasses)
   │      - reject if entryPointClass is missing or does not implement PluginEntryPoint
   │      - confirm declared extensionPoints/permissions are a subset of the platform enum
   ├─ 5. (Optional, recommended for production — flagged as OQ in §11.10) verify a detached
   │      signature against a vendor's registered public key, or require platform-side
   │      signing after a manual admin review step before activation
   └─ 6. Persist JAR bytes to object storage (MinIO/S3, same bucket family as `recording`,
          §3.1 of TECH-STACK) + insert `plugin_version` row, status = PENDING_REVIEW or
          VALIDATED depending on whether signing is required (§11.10)
   |
   v
PluginRegistrationService (on tenant admin clicking "Install"/"Enable")
   ├─ creates/updates tenant_plugin_installation (tenant_id, plugin_version_id, enabled=true,
   │    granted_permissions = subset of manifest permissions the admin approved)
   ├─ PluginRuntimeManager.load(tenantId, pluginVersionId):
   │    - downloads JAR from object storage (cached on local disk per node)
   │    - creates a new PluginClassLoader, loads entryPointClass, instantiates it via a
   │      no-arg constructor (enforced — no DI into the plugin's own constructor)
   │    - calls entryPoint.onActivate(PluginContext) — first and only privileged callback
   │      that may run setup logic (e.g., test the external CRM connection)
   └─ registers the instance's declared extensionPoints in PluginRegistry, keyed by
        (tenant_id, extension_point) -> ordered list of active plugin instances

   ... later, during normal operation (see §11.5) ...

Agent picks up a call / customer record is fetched
   v
ExtensionPointPublisher.PRE_CONTACT_CONNECT(tenant, contact, customer)
   v
PluginRegistry.lookup(tenantId, PRE_CONTACT_CONNECT) -> [AcmeCrmPlugin instance]
   v
PluginInvocationExecutor.invoke(instance, event, timeout=2s)
   |     (runs on a dedicated bounded executor, TenantContext snapshot/restore, §11.8)
   v
plugin.onPreContactConnect(ctx, event) -> PluginResult (success/data | error)
   v
PluginInvocationLogService.record(...)   (always, success or failure, §11.9)
   v
Result merged back into the contact-connect flow (non-blocking on plugin failure
unless the extension point is explicitly configured as "blocking", §11.5)
```

### 11.5 Hook Mechanism: Event-Driven, Not a Generic Interceptor Chain

**Decision:** extension points are modeled as a fixed, backend-defined set of **typed
domain events with a synchronous-call contract**, dispatched by an `ExtensionPointPublisher`,
not as a generic AOP/interceptor chain woven into arbitrary service methods.

**Rationale:**
- A generic interceptor (e.g., a `@Pointcut` around every service method) would let plugin
  authors implicitly depend on internal method signatures that are free to change — it
  couples the SDK contract to the implementation, which violates the same "stable interface"
  principle behind ADR-05's `TelephonyAdapter`. A fixed, versioned set of named extension
  points (`PRE_CONTACT_CONNECT`, `POST_CONTACT_END`, `CUSTOMER_SYNC`, `DISPOSITION_SET`,
  `MANUAL_ACTION` — directly mapping to the four integration points agreed for this epic) is
  a deliberately small, explicit surface that can be versioned independently of the rest of
  the codebase.
- Each extension point has an explicit **blocking vs. fire-and-forget** classification:
  - `PRE_CONTACT_CONNECT` — **blocking with timeout** (agent desktop waits briefly for a CRM
    lookup before connect; on timeout/error, connect proceeds anyway — never blocks core
    telephony on a plugin, see RT-12);
  - `POST_CONTACT_END`, `DISPOSITION_SET`, `CUSTOMER_SYNC` — **fire-and-forget**, published as
    a RabbitMQ message (`cc.queue.plugin-invocation`) and consumed asynchronously by
    `PluginInvocationConsumer`, decoupling plugin latency entirely from the agent-facing
    request;
  - `MANUAL_ACTION` — **blocking**, since it is a direct user-initiated request/response (the
    agent clicked "Open in CRM" and expects a result), but still timeout-bounded.
- This mirrors the existing RabbitMQ domain-event pattern already used for
  `agent.status.changed` / `call.incoming` (§3.5) rather than introducing a second, competing
  extensibility mechanism.

### 11.6 Plugin SDK (`plugin-sdk` module)

A new, minimal Maven module, `backend/plugin-sdk`, published as the **only** compile-time
dependency a third-party plugin developer needs (no dependency on `backend/app`, Spring, or
JPA). It contains interfaces and immutable DTOs only — no implementations, no Spring
annotations, so it cannot be used to reach into the host application even via the classpath.

```java
// Entry point every plugin must implement
public interface PluginEntryPoint {
    void onActivate(PluginContext context);     // called once on install/enable
    void onDeactivate();                          // called once on disable/uninstall
    default PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) { ... }
    default void onPostContactEnd(PluginContext ctx, ContactEvent e) { }
    default CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) { ... }
    default void onDispositionSet(PluginContext ctx, DispositionEvent e) { }
    default ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) { ... }
}

// The ONLY object through which a plugin reaches the platform.
// Implemented by backend/app, instantiated per-invocation with the calling tenant baked in.
public interface PluginContext {
    CustomerView getCustomer(UUID customerId);            // read-only DTO, scoped to caller's tenant
    void updateCustomerFields(UUID customerId, Map<String, Object> customFields); // customer.custom_fields only
    ContactView getContact(UUID contactId);
    void appendContactNote(UUID contactId, String note);
    HttpEgressClient httpClient();                         // restricted egress, see below
    PluginLogger logger();                                 // writes into plugin_invocation_log, not app logs
    PluginConfig config();                                  // tenant-scoped key/value config the admin set in UI
}

// Egress is allow-listed per plugin, per the manifest's "http:egress:<host>" permissions —
// a plugin cannot make an arbitrary outbound call to a host it didn't declare.
public interface HttpEgressClient {
    HttpResponse get(String url, Map<String, String> headers);
    HttpResponse post(String url, Map<String, String> headers, byte[] body);
}
```

Notable constraints baked into the SDK contract, not left to plugin discipline:
- `CustomerView`/`ContactView` are immutable DTOs (records), never JPA entities — a plugin
  can never obtain a Hibernate-managed entity, a `Session`, or trigger lazy-loading against
  the real schema.
- `updateCustomerFields` only ever writes into `customer.custom_fields JSONB` (already a
  flexible, tenant-owned bag of data per §4.3) — a plugin can never write to a core platform
  column directly, which also keeps this consistent with the project's "no overloaded
  columns" rule (CLAUDE.md): plugin data lives in its own namespaced JSONB key
  (`custom_fields.plugins.<pluginKey>`), never repurposing an existing typed column.
- `HttpEgressClient` enforces the manifest's declared egress hosts at the SDK implementation
  layer (host allow-list checked before every call) and applies a platform-wide circuit
  breaker per `(tenant_id, plugin_key, host)` so a failing external CRM degrades gracefully
  instead of accumulating hanging connections.

### 11.7 Fault Containment: Timeouts, Thread Pools, Crash Isolation

The backend must never be destabilized by a plugin, by design, not by hope:

| Mechanism | Detail |
|---|---|
| Dedicated executor | `PluginInvocationExecutor` is a bounded `ThreadPoolExecutor` (separate from Tomcat's request threads and from the existing `@Async`/`@Scheduled` pools), sized independently so plugin slowness cannot starve normal request handling |
| Per-call timeout | Every `PluginEntryPoint` callback is invoked via `Future.get(timeout)`; default timeouts per extension point (`PRE_CONTACT_CONNECT` 2s, `MANUAL_ACTION` 5s, async ones 30s) are configurable per installation, capped by a platform-wide maximum |
| Timeout = no kill | The JVM cannot forcibly stop a runaway thread; on timeout the invocation is marked `TIMED_OUT` in `plugin_invocation_log`, the result is discarded/ignored by the caller, and the orphaned thread is left to finish into a "fire and forget" sink — repeated timeouts trip a per-installation circuit breaker (see below) |
| Circuit breaker per installation | After N consecutive timeouts/exceptions (default 5) within a rolling window, `tenant_plugin_installation.health_status` flips to `DEGRADED`; the registry stops invoking that installation until an admin re-enables it or a cooldown elapses — surfaced in the admin/supervisor UI |
| Exception containment | Every invocation path wraps the call in `try/catch (Throwable)` — a plugin throwing `Error`/`OutOfMemoryError`-adjacent conditions cannot propagate past the executor boundary; caught and logged as `FAILED` |
| Resource quotas (best-effort) | No hard per-plugin CPU/memory quota is achievable with in-process isolation (JDK has no per-classloader resource control); mitigated operationally via JVM-wide memory limits, GC pause monitoring, and the circuit breaker above — tracked as residual risk RT-10/RT-13, not solved |

### 11.8 Multi-tenancy and Thread-Boundary Rules

Plugin invocation almost always crosses a thread boundary (executor pool, or the
`cc.queue.plugin-invocation` RabbitMQ consumer for async extension points), so it must follow
the project's mandatory `TenantContext` pattern (CLAUDE.md) exactly as `@Async`/`@Scheduled`
code does today (§8.6):

```
Calling thread (request thread or RabbitMQ listener thread):
    TenantContext.Snapshot snapshot = TenantContext.snapshot();
    pluginInvocationExecutor.submit(() -> {
        try {
            TenantContext.restore(snapshot);
            // construct PluginContext with tenantId from TenantContext — never from the
            // plugin or from any value the plugin can influence
            entryPoint.onXxx(pluginContext, event);
        } finally {
            TenantContext.clear();
        }
    });
```

In addition, the `PluginClassLoader` boundary gives a second, independent isolation layer on
top of `TenantContext`: even if a bug caused `TenantContext` to leak or be misread, the
plugin instance handling tenant A's invocation is a physically different object (different
classloader, different heap-resident static state) from the instance handling tenant B's
invocation, because installations are loaded per-`(tenant_id, plugin_key)` (§11.3). All
repositories reached through `PluginContext` still extend `TenantAwareRepository` and still
call `assertSameTenant(...)` before any write — the plugin boundary does not bypass this
project-wide invariant, it sits in front of it.

### 11.9 Data Model

New tables, all tenant-scoped where noted, following the project's RLS pattern (§4.1) — `app.current_tenant_id` set per transaction, RLS policy added in the same migration that creates the table, no overloaded columns (CLAUDE.md):

```
PLUGIN (global catalog entry — NOT tenant-scoped; a plugin definition can be installed by many tenants)
  plugin_id          UUID PK
  plugin_key         TEXT UNIQUE NOT NULL        -- from manifest, immutable across versions
  display_name       TEXT NOT NULL
  vendor             TEXT NOT NULL
  vendor_contact     TEXT
  created_at         TIMESTAMPTZ

PLUGIN_VERSION (global — one row per uploaded JAR version; NOT tenant-scoped)
  plugin_version_id    UUID PK
  plugin_id            UUID FK -> PLUGIN
  version              TEXT NOT NULL              -- semver, from manifest
  jar_object_key        TEXT NOT NULL              -- MinIO/S3 object key
  checksum_sha256        TEXT NOT NULL
  manifest_json          JSONB NOT NULL             -- full parsed manifest, for audit/replay
  sdk_version            TEXT NOT NULL
  status                 TEXT NOT NULL              -- UPLOADED|VALIDATED|PENDING_REVIEW|REJECTED|REVOKED
  validation_errors      JSONB                       -- populated if status=REJECTED
  uploaded_by_user_id    UUID FK -> APP_USER
  uploaded_at            TIMESTAMPTZ
  UNIQUE (plugin_id, version)

TENANT_PLUGIN_INSTALLATION (tenant-scoped; RLS enabled)
  tenant_plugin_installation_id  UUID PK
  tenant_id                       UUID FK -> TENANT NOT NULL
  plugin_version_id               UUID FK -> PLUGIN_VERSION NOT NULL
  enabled                          BOOLEAN NOT NULL DEFAULT FALSE
  granted_permissions              JSONB NOT NULL      -- admin-approved subset of manifest permissions
  health_status                    TEXT NOT NULL        -- HEALTHY|DEGRADED|DISABLED_BY_ADMIN
  consecutive_failure_count        INT NOT NULL DEFAULT 0
  installation_config              JSONB                -- tenant-supplied config (API keys for the
                                                          --   external CRM, etc.) — encrypted at rest,
                                                          --   AES-256-GCM, same converter pattern as
                                                          --   tenant_twilio_config / tenant_ai_config (§4.3)
  installed_by_user_id             UUID FK -> APP_USER
  installed_at, updated_at         TIMESTAMPTZ
  UNIQUE (tenant_id, plugin_version_id)    -- a tenant installs a given version at most once;
                                            -- upgrading creates a new row pointing at a new
                                            -- plugin_version_id (see §11.11 rollback)

TENANT_PLUGIN_EXTENSION_BINDING (tenant-scoped; RLS enabled)
  tenant_plugin_extension_binding_id  UUID PK
  tenant_plugin_installation_id       UUID FK -> TENANT_PLUGIN_INSTALLATION NOT NULL
  extension_point                      TEXT NOT NULL    -- PRE_CONTACT_CONNECT|POST_CONTACT_END|...
  invocation_mode                      TEXT NOT NULL     -- BLOCKING|ASYNC
  timeout_ms                           INT NOT NULL
  display_order                        INT NOT NULL DEFAULT 0  -- when >1 plugin binds the same point
  UNIQUE (tenant_plugin_installation_id, extension_point)

PLUGIN_INVOCATION_LOG (tenant-scoped; RLS enabled; RANGE-partitioned monthly on invoked_at,
                         same pattern as audit_log/contact, §4.2-4.3)
  plugin_invocation_log_id   UUID
  invoked_at                  TIMESTAMPTZ    -- partition key
  tenant_id                   UUID NOT NULL
  tenant_plugin_installation_id UUID FK -> TENANT_PLUGIN_INSTALLATION
  extension_point              TEXT NOT NULL
  related_contact_id           UUID NULLABLE   -- ON DELETE SET NULL, same GDPR-safe pattern as contact.agent_id
  status                       TEXT NOT NULL    -- SUCCESS|FAILED|TIMED_OUT|CIRCUIT_OPEN
  duration_ms                  INT
  error_summary                 TEXT
  request_payload_redacted      JSONB           -- PII-redacted snapshot for debugging
  PRIMARY KEY (plugin_invocation_log_id, invoked_at)
```

Relations: `PLUGIN (1) -> (N) PLUGIN_VERSION -> (N) TENANT_PLUGIN_INSTALLATION (per tenant) ->
(N) TENANT_PLUGIN_EXTENSION_BINDING` and `(N) PLUGIN_INVOCATION_LOG`. `PLUGIN`/`PLUGIN_VERSION`
are intentionally **not** tenant-scoped (the catalog is global; only the *installation* is
tenant-private) — RLS is applied starting at `TENANT_PLUGIN_INSTALLATION`, matching the
existing precedent of global vs. tenant-scoped tables already in the schema (e.g.
`disposition_set` templates vs. `custom_disposition`, §4.3).

### 11.10 UI Integration: Hybrid Hooks + Sandboxed iframe Panels

**(a) Data hooks (Angular calling existing REST endpoints):** for the four extension points
that don't need custom UI (e.g., "show CRM sync status"), the plugin only implements backend
logic; the existing Angular components (agent desktop customer panel, disposition dialog)
call **existing, generic** endpoints —
`GET /api/agent/plugins/{installationId}/extension-points/{point}` — that the frontend already
has, with no plugin-specific frontend code required. This covers `CUSTOMER_SYNC` status
display and `DISPOSITION_SET` side effects without any custom UI.

**(b) Plugin-provided UI panel (iframe / web component):**

```
Angular host (agent desktop / supervisor dashboard)
  <cc-plugin-panel-host [installationId]="..." [mountPoint]="'AGENT_DESKTOP_SIDE_PANEL'">
    <iframe
      [src]="trustedPluginPanelUrl"                 -- served from a separate, plugin-only
                                                       origin (e.g. plugins.<tenant-domain>),
                                                       NEVER same-origin as the main app
      sandbox="allow-scripts allow-forms"            -- explicitly NOT allow-same-origin,
                                                       NOT allow-top-navigation,
                                                       NOT allow-popups
      referrerpolicy="no-referrer"
    ></iframe>
  </cc-plugin-panel-host>
```

- **Origin isolation:** plugin UI assets (extracted from the JAR's `plugin-ui/` resources at
  install time) are served from a dedicated, plugin-specific subdomain/path that is a
  **different origin** from the main Angular app, so the browser's same-origin policy alone
  blocks the iframe from reading the host page's DOM, cookies, or `localStorage` even without
  relying on the `sandbox` attribute.
- **`sandbox` attribute:** `allow-scripts allow-forms` only — no `allow-same-origin` (keeps
  the iframe's origin opaque/null if served same-origin by mistake, as a defense-in-depth
  backstop), no `allow-top-navigation`, no `allow-popups`, no `allow-pointer-lock`.
- **CSP:** the host page sets `frame-src` to the specific plugin-asset origin pattern only
  (not `*`); the plugin asset response itself sets a strict CSP (`default-src 'self'`,
  `connect-src` limited to the same egress allow-list declared in the manifest) so the panel's
  own JS cannot be used to exfiltrate data to arbitrary third parties even if compromised.
- **postMessage SDK (`window.postMessage`-based, host <-> iframe):** the *only* communication
  channel between the iframe and the host Angular app. A small `plugin-ui-sdk.js` (shipped by
  the platform, not by the plugin vendor) is injected into the iframe and exposes a typed,
  promise-based API to the plugin's own UI code:
  ```
  PluginUiSdk.getContext()              -> { tenantId, contactId, customerId } (read-only,
                                              minimal — never the full customer/contact record)
  PluginUiSdk.invokeManualAction(actionId, payload) -> Promise<ManualActionResult>
                                              (routes through the SAME backend
                                               PluginInvocationExecutor path as §11.7 —
                                               the iframe never calls the plugin's backend
                                               logic directly, only via this REST round trip)
  PluginUiSdk.requestResize(height)     -> host adjusts iframe height
  PluginUiSdk.notify(message, severity) -> host shows a toast
  ```
  The host validates `event.origin` against the expected plugin-asset origin on every
  received message and validates the message shape against a fixed schema before acting on
  it; messages from unexpected origins are dropped and logged.
- **No direct backend access from the iframe's own JS to anything outside the manual-action
  REST endpoint** — the iframe cannot call arbitrary `/api/**` endpoints with the agent's
  session, because it never receives the host's JWT (the SDK proxies the one allowed call
  through the host page, which attaches auth).

### 11.11 Versioning, Enable/Disable, and Rollback

- **Versioning:** semver in the manifest; `PLUGIN_VERSION` rows are immutable once `VALIDATED`
  (never edited in place — mirrors the Flyway "never edit an applied migration" rule in
  CLAUDE.md). A new version is always a new `plugin_version` row.
- **Upgrade flow:** admin uploads a new version of an already-known `plugin_key`; on
  "Install"/"Activate", a **new** `TENANT_PLUGIN_INSTALLATION` row is created pointing at the
  new `plugin_version_id`; the old installation row is flipped to `enabled=false` but kept
  (not deleted) — this is the rollback mechanism: re-enabling the prior row's `enabled` flag
  and disabling the new one is an instant, no-redeploy rollback if the new version misbehaves
  (surfaced by the circuit breaker in §11.7 going `DEGRADED` shortly after an upgrade).
- **Disable per tenant:** `tenant_plugin_installation.enabled=false` immediately removes all
  of that installation's bindings from `PluginRegistry`'s lookup table; in-flight async
  invocations already queued in `cc.queue.plugin-invocation` are still processed but logged
  with a `status=SKIPPED_DISABLED` if they arrive after the disable, not silently dropped.
- **Uninstall:** calls `entryPoint.onDeactivate()` (best-effort, also timeout-bounded), then
  unloads the `PluginClassLoader` (drops the last strong reference so it becomes eligible for
  GC) and deletes the `TENANT_PLUGIN_INSTALLATION` + bindings; `PLUGIN_INVOCATION_LOG` history
  is retained (`tenant_plugin_installation_id` FK uses `ON DELETE SET NULL`, consistent with
  the GDPR-safe FK pattern already used for `contact.agent_id`/`contact.customer_id`, §4.3) so
  audit history survives uninstall.
- **Platform-level kill switch:** `plugin_version.status` can be flipped to `REVOKED` by a
  global system administrator (not a tenant admin) — e.g. a vendor's plugin is found to be
  malicious — which immediately disables every tenant's installation of that version
  regardless of their individual `enabled` flag, checked at lookup time in `PluginRegistry`.

### 11.12 Audit and Observability

- Every invocation (success, failure, timeout, circuit-open skip) is written to
  `plugin_invocation_log` (§11.9) — this is the plugin-specific equivalent of the platform's
  `@Audited`/`audit_log` mechanism (§6.7), kept as a separate table rather than overloading
  `audit_log` because the volume/shape (per-call latency, payload snapshots) and retention
  needs differ materially from administrative audit events.
- Install/uninstall/enable/disable/permission-grant actions on
  `tenant_plugin_installation` additionally go through the existing `@Audited` AOP mechanism
  into `audit_log` (§6.7), since those are exactly the kind of administrative state changes
  `audit_log` already exists to capture — this is intentionally **not** duplicated into
  `plugin_invocation_log`.
- `health_status`/`consecutive_failure_count` on `tenant_plugin_installation` are surfaced in
  the supervisor/admin plugin management screen, following the same pattern as
  `EtlStatusController` (§3.6/§8.3) for operational visibility of an async background
  mechanism.

### 11.13 Sequence Diagram: Upload to First Invocation

```mermaid
sequenceDiagram
    participant Admin as Tenant Admin (Angular)
    participant API as PluginUploadController
    participant Val as PluginValidationService
    participant Store as MinIO/S3
    participant Reg as PluginRegistrationService
    participant RT as PluginRuntimeManager
    participant Registry as PluginRegistry
    participant Agent as Agent Desktop (Angular)
    participant Pub as ExtensionPointPublisher
    participant Exec as PluginInvocationExecutor
    participant Plugin as Plugin instance (own ClassLoader)
    participant Log as PluginInvocationLogService

    Admin->>API: POST /api/supervisor/plugins (JAR)
    API->>Val: validate(jarBytes)
    Val->>Val: checksum, manifest schema, ASM static scan
    Val->>Store: store JAR (if VALIDATED)
    Val-->>API: PLUGIN_VERSION (status=VALIDATED|REJECTED)
    API-->>Admin: 201 Created / 400 with validation_errors

    Admin->>API: POST /api/supervisor/plugins/{id}/install
    API->>Reg: install(tenantId, pluginVersionId, grantedPermissions)
    Reg->>RT: load(tenantId, pluginVersionId)
    RT->>Store: download JAR
    RT->>RT: new PluginClassLoader; load entryPointClass
    RT->>Plugin: onActivate(PluginContext)
    RT->>Registry: register bindings (tenant, extensionPoints)
    Reg-->>Admin: installation ENABLED

    Note over Agent,Log: Later — agent handles an incoming call
    Agent->>Pub: contact about to connect (PRE_CONTACT_CONNECT)
    Pub->>Registry: lookup(tenantId, PRE_CONTACT_CONNECT)
    Registry-->>Pub: [installation: acme-crm-sync]
    Pub->>Exec: invoke(plugin, event, timeout=2s)
    Exec->>Plugin: onPreContactConnect(ctx, event)
    Plugin-->>Exec: PreContactConnectResult
    Exec->>Log: record SUCCESS/FAILED/TIMED_OUT
    Exec-->>Pub: result (or empty on timeout — never blocks connect)
    Pub-->>Agent: enriched contact data (or none)
```

### 11.14 New ADRs

### ADR-09: In-Process ClassLoader Isolation for Tenant Plugins (Not a Separate Process/Container)

**Decision:** plugins execute in the same JVM as the Spring Boot application, each installation
behind its own `ClassLoader`, rather than in a separate process (e.g., a sidecar JVM) or a
container (e.g., gVisor/Firecracker microVM per plugin).

**Rationale:** at the target scale (50 tenants, a handful of plugins per tenant), per-plugin
process/container isolation would multiply operational surface area (process supervision,
IPC/RPC serialization for every SDK call, container image management for arbitrary
tenant-uploaded code) disproportionately to the actual threat model for a B2B SaaS platform
where plugin vendors are vetted business partners, not anonymous third parties. In-process
isolation accepts a smaller but non-zero residual risk (RT-10) in exchange for materially
lower latency (no IPC marshaling on every `PluginContext` call, important for the
`PRE_CONTACT_CONNECT` blocking path's 2s budget) and lower operational cost.

**Consequences:** the platform cannot offer a hard security boundary against a fully
malicious plugin author; mitigations are procedural (manifest permission review, optional
signing, RT-10/RT-13) and operational (timeouts, circuit breakers, §11.7), not absolute.
This must be communicated in the plugin vendor agreement/terms of service — a non-engineering
consequence worth flagging to product/legal stakeholders.

**Migration path:** the `PluginContext` facade (§11.6) is deliberately the *only* surface a
plugin touches; if a future epic needs harder isolation (e.g., a regulated tenant requires
container-per-plugin), the SDK contract does not need to change — only `PluginRuntimeManager`'s
implementation of "how is `entryPoint.onXxx` actually invoked" would be replaced with an
RPC call into a sidecar/container, with `PluginContext` calls proxied the same way
`TelephonyAdapter` (ADR-05) abstracts the provider underneath a stable interface.

### ADR-10: Event-Driven Extension Points Over a Generic Interceptor Chain

**Decision:** a fixed, versioned set of named extension points (`PRE_CONTACT_CONNECT`,
`POST_CONTACT_END`, `CUSTOMER_SYNC`, `DISPOSITION_SET`, `MANUAL_ACTION`) dispatched by
`ExtensionPointPublisher`, rather than a generic AOP-based interceptor woven into arbitrary
service methods.

**Rationale:** see §11.5. A fixed extension-point enum is independently versionable and keeps
the SDK contract decoupled from internal method signatures, the same way `TelephonyAdapter`
(ADR-05) decouples telephony provider details from the routing engine.

**Consequences:** adding a new extension point in a future epic requires a backend code
change (a new enum value + publisher call site) — by design, this is a deliberate gate against
extension-point sprawl, not an oversight.

### ADR-11: JAR Upload with Manifest + Static Scan as the Primary Gate; Signing as a Hardening Option, Not a Day-1 Requirement

**Decision:** the upload pipeline (§11.4) enforces manifest schema validation, checksum
verification, and an ASM-based static bytecode scan against a package blacklist as the
mandatory gate. Cryptographic signing of the JAR by the vendor is supported by the data model
(`plugin_version.status=PENDING_REVIEW`) but is **not** a hard Day-1 requirement — left as an
open question for the security/compliance review before production rollout (§11.16, OQ-28-1).

**Rationale:** signing requires a key-distribution and vendor-onboarding process that does not
yet exist for this platform's partner ecosystem; gating the entire epic on building that
process first would block the integration use cases (CRM sync) that are the actual business
driver. The static-scan + manual-review-before-activation combination is a pragmatic
intermediate control appropriate for an initial rollout with a small, known set of vetted
integration partners.

**Consequences:** until signing is mandatory, the platform is relying on (a) the static scan,
(b) the in-process containment controls in §11.3/§11.7, and (c) administrative trust in
whichever tenant admin clicks "Install" — equivalent in spirit to how browser extensions or
IDE plugins are typically trusted today. This is recorded as RT-13 below and should be
revisited before onboarding any tenant's plugin vendor that the platform operator has not
vetted directly.

### ADR-12: Plugin UI via Cross-Origin Sandboxed iframe + postMessage SDK, Not a Web Component Loaded Same-Origin

**Decision:** plugin-provided UI panels are rendered in a `sandbox`-restricted `<iframe>`
served from a separate origin, communicating with the Angular host exclusively through a
purpose-built `postMessage`-based SDK (§11.10), rather than loading plugin-supplied JS as a
same-origin Angular web component / custom element.

**Rationale:** a same-origin web component would execute with the full privileges of the host
page — access to `localStorage`, cookies, the agent's JWT in memory, and the DOM of the rest
of the application. Given that plugin code is third-party and uploaded at runtime (the exact
opposite trust level of the platform's own first-party Angular code, which is the only code
the project's "standalone components only, no NgModules" frontend rules — CLAUDE.md — were
designed to govern), cross-origin iframe isolation is the only option consistent with not
trusting plugin UI code at the same level as first-party code.

**Consequences:** plugin UI panels cannot directly manipulate the host DOM or access host
application state beyond what `PluginUiSdk.getContext()` deliberately exposes (§11.10);
plugin vendors must build their panel as a standalone static web app (any framework, since it
runs in an isolated iframe) rather than as an Angular component — a constraint that must be
documented in the plugin developer guide.

### ADR-13: Plugin Data Tables Are Tenant-Scoped Starting at the Installation Level, Not at the Catalog Level

**Decision:** `PLUGIN` and `PLUGIN_VERSION` are global (no `tenant_id`, no RLS);
`TENANT_PLUGIN_INSTALLATION`, `TENANT_PLUGIN_EXTENSION_BINDING`, and `PLUGIN_INVOCATION_LOG`
are tenant-scoped with RLS enabled (§11.9).

**Rationale:** mirrors the existing precedent for global-vs-tenant-scoped tables in the schema
(e.g. global `disposition_set` templates vs. tenant-scoped `custom_disposition`, §4.3) — the
*definition* of a plugin (what code, what version, what it declares it can do) is shared
infrastructure metadata, while the *decision to run it, with what permissions, for which
tenant* is exactly the kind of data that must never leak across tenants and therefore gets
the full RLS treatment from §4.1.

**Consequences:** a single buggy or malicious plugin *version* can be globally revoked
(ADR-11/§11.11) without per-tenant cleanup, while each tenant's choice to install/configure/
enable it remains fully isolated and independently auditable.

### 11.15 Risks and Mitigations (RT-09 through RT-14)

| ID | Risk | Probability | Impact | Mitigation |
|----|------|-------------|--------|------------|
| RT-09 | A plugin hangs indefinitely (infinite loop, blocked I/O) and exhausts the plugin executor pool, starving other plugins/tenants | Medium | Medium | Dedicated bounded `PluginInvocationExecutor` separate from request/async pools (§11.7); per-call timeout via `Future.get(timeout)`; circuit breaker opens after N consecutive timeouts per installation, isolating the blast radius to that one installation |
| RT-10 | Malicious plugin uses reflection/classloader manipulation to reach another tenant's data or platform internals despite the `ClassLoader`/SDK-facade boundary — the JDK provides no hard sandbox (`SecurityManager` deprecated for removal) | Low-Medium | Critical | Layered, not absolute, mitigation: narrow parent classloader exposing only `plugin-sdk` interfaces (§11.3); static bytecode scan rejecting reflection/`ProcessBuilder`/classloader-manipulation patterns at upload (§11.4); `PluginContext` never exposes a Spring bean, `EntityManager`, or repository — only immutable DTOs; manual review gate before activation (ADR-11) for any vendor not already vetted; explicitly tracked as the epic's largest residual risk — escalate to a process/container isolation model (ADR-09 migration path) if a tenant's compliance requirements demand a hard boundary |
| RT-11 | A plugin's iframe UI is compromised (vendor's own supply chain) and attempts to exfiltrate agent/customer data visible to it | Medium | High | Cross-origin iframe (not same-origin web component, ADR-12); `sandbox="allow-scripts allow-forms"` with no `allow-same-origin`; strict CSP on the plugin-asset response limiting `connect-src` to the manifest's declared egress hosts; `PluginUiSdk.getContext()` exposes only minimal IDs, never full customer/contact records; host validates `event.origin` on every postMessage |
| RT-12 | A slow/unavailable external CRM behind a `PRE_CONTACT_CONNECT` plugin delays call connection, degrading the core telephony experience | Medium | High | `PRE_CONTACT_CONNECT` is timeout-bounded (default 2s) and explicitly non-blocking on failure — call connect proceeds with or without plugin enrichment (§11.5); circuit breaker on the `HttpEgressClient` per `(tenant, plugin, host)` (§11.6) avoids repeated slow calls once a downstream is known-bad |
| RT-13 | Unsigned/unvetted plugin JAR from a compromised or careless vendor is installed by a tenant admin before a signing requirement exists (ADR-11) | Medium | High | Static scan + manifest permission allow-list as the Day-1 gate; `granted_permissions` requires explicit admin approval per installation (not auto-granted from the manifest); platform-level `REVOKED` kill switch (§11.11) for rapid global response; flagged as OQ-28-1 (§11.16) to formalize a signing requirement before onboarding un-vetted vendors |
| RT-14 | Plugin data written into `customer.custom_fields` (via `updateCustomerFields`, §11.6) from multiple plugins collides or is overwritten | Low | Medium | SDK enforces a namespaced JSONB path per plugin (`custom_fields.plugins.<pluginKey>`), never a flat merge — collisions between distinct plugins are structurally impossible; a single plugin overwriting its own prior data is treated as expected behavior, not a defect |

### 11.16 Open Questions

- **OQ-28-1:** should JAR signing be a hard requirement before allowing any tenant to install
  a plugin in production, or is the static-scan + manual-review gate (ADR-11) sufficient for
  the initial rollout's vetted-partner-only scope? Needs a decision from security/compliance
  before the first non-pilot tenant onboarding.
- **OQ-28-2:** is a shared, cross-tenant plugin marketplace/catalog UI in scope for a later
  epic, or does every tenant independently source and upload its own vendor's JAR
  indefinitely? Affects whether `PLUGIN`/`PLUGIN_VERSION` need a public-facing discovery API.
- **OQ-28-3:** what is the expected concurrency ceiling for `PluginInvocationExecutor` at
  target scale (50 tenants x N plugins x agent concurrency, §1.2/Appendix C performance
  budget) — needed to size the bounded thread pool and choose default timeout values with
  actual load-test data rather than estimates.
