# Architecture Document – Contact Center SaaS Platform

**Version:** 1.0
**Date:** 2026-03-12
**Status:** Approved
**Based on:** PRD v1.0, TECH-STACK v1.0

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

| ID | Decision |
|----|----------|
| ADR-01 | Modular monolith in Phase 1, with module boundaries designed for future microservice extraction |
| ADR-02 | Single shared PostgreSQL database with logical multi-tenancy via tenant_id + Row-Level Security |
| ADR-03 | RabbitMQ as the message broker (confirmed in TECH-STACK) |
| ADR-04 | Redis for distributed caching, session state, agent presence, and queue state |
| ADR-05 | Telephony via external CPaaS provider behind an Adapter interface (provider selected separately) |
| ADR-06 | Python microservice for all AI/NLP/voicebot/chatbot workloads, called via REST from Spring Boot |
| ADR-07 | Data Warehouse fed by Change Data Capture (CDC) + RabbitMQ event streaming |
| ADR-08 | WebRTC for in-browser softphone; TURN/STUN servers managed internally |

---

## 2. System Architecture

### 2.1 Architecture Style

**Phase 1 (MVP): Modular Monolith**

The backend is a single deployable Spring Boot application divided into strictly bounded modules. Each module owns its domain logic, exposes internal interfaces, and has no direct cross-module database access. This approach reduces operational complexity while preserving the ability to extract modules into independent microservices in Phase 2+.

The Python AI service is the sole external service from day one, given the fundamentally different runtime requirements of ML/NLP workloads.

### 2.2 High-Level Architecture Diagram

```
+---------------------------------------------------------------------+
|                      BROWSER CLIENTS                                |
|              Angular SPA (Agent / Supervisor / Admin)               |
|         HTTP/REST + WebSocket (Socket.IO / STOMP over WS)           |
+---------------------------------------------------------------------+
                               |
+---------------------------------------------------------------------+
|                          API GATEWAY                                |
|          (Nginx / Spring Cloud Gateway)                             |
|    JWT validation · Rate limiting · TLS termination · Routing       |
+---------------------------------------------------------------------+
          |                   |                    |
+---------+-------+  +--------+--------+  +--------+--------+
|  Auth Module    |  | Contact Center  |  |  Admin/Tenant   |
|  (Spring Boot)  |  |  Core Module    |  |  Module         |
|                 |  |  (Spring Boot)  |  |  (Spring Boot)  |
| - Login/MFA     |  |                 |  |                 |
| - JWT issue     |  | - Routing Engine|  | - Tenant CRUD   |
| - Refresh token |  | - Queue Mgmt    |  | - User Mgmt     |
| - Password mgmt |  | - Contact Mgmt  |  | - Audit Log     |
| - Audit         |  | - Campaign Svc  |  | - Tech Dashboard|
+-----------------+  | - Customer Svc  |  +-----------------+
                     | - Recording Svc |
                     | - Reporting Svc |
                     +-----------------+
                               |
          +--------------------+--------------------+
          |                    |                    |
+---------+------+  +----------+-------+  +---------+------+
| Channel        |  | Python AI        |  | WebRTC         |
| Adapters       |  | Service          |  | Media Service  |
| (Spring Boot)  |  | (FastAPI)        |  | (STUN/TURN)    |
|                |  |                  |  |                |
| - VoIP Adapter |  | - Voicebot (NLP) |  | - Coturn server|
| - Email Adapter|  | - Chatbot (NLP)  |  | - ICE candidate|
| - SocialAdapter|  | - TTS integration|  |   handling     |
+----------------+  | - Intent classify|  +----------------+
                     +------------------+
                               |
+---------------------------------------------------------------------+
|                       MESSAGE BROKER                                |
|                       RabbitMQ                                      |
|  Exchanges: contact.events · campaign.events · audit.events         |
|             channel.inbound · channel.outbound · dwh.cdc            |
+---------------------------------------------------------------------+
          |                                         |
+---------+----------+               +--------------+-----------+
|   PostgreSQL        |               |  Data Warehouse          |
|   (Primary + HA     |               |  (ClickHouse or          |
|    Replica)         |               |   PostgreSQL + TimescaleDB)|
|                     |               |                          |
| - Tenants           |               | - contact_facts          |
| - Users/Skills      |               | - agent_activity_facts   |
| - Customers         |               | - campaign_facts         |
| - Contacts          |               | - queue_metrics          |
| - Campaigns         |               | - dimensions: tenant,    |
| - Queues/IVR        |               |   agent, customer, time  |
| - Audit Log         |               |                          |
+---------------------+               +--------------------------+
          |
+---------+----------+
|   Redis             |
|                     |
| - Agent presence    |
| - Queue state (RT)  |
| - Session cache     |
| - JWT blacklist     |
| - Rate limit counters|
+---------------------+
```

### 2.3 Real-Time Communication Architecture

```
Angular SPA  <--STOMP/WS-->  Spring Boot (WebSocket endpoint)
                                     |
                              Redis Pub/Sub
                              (fan-out to all
                               app instances)
                                     |
                          Routing Engine + Queue state
```

Agent presence, queue depths, and real-time dashboard metrics are maintained in Redis and broadcast to connected Angular clients via WebSocket (STOMP protocol over SockJS). All application instances subscribe to the same Redis channels, making the WebSocket tier stateless and horizontally scalable.

---

## 3. Component Structure

### 3.1 Angular Frontend (SPA)

The Angular application is organized as a monorepo using Angular workspaces with shared libraries.

```
/frontend
  /apps
    /agent-desktop         # Agent workspace: softphone, chat, email panels
    /supervisor-portal     # Supervisor: dashboards, campaigns, reports
    /admin-portal          # Admin: tenant management, system health
  /libs
    /shared-ui             # Component library (design system)
    /auth                  # Auth service, JWT interceptor, MFA flow
    /api-client            # Generated OpenAPI TypeScript client
    /real-time             # WebSocket/STOMP service wrapper
    /i18n                  # Translation files (pl, en as baseline)
```

**State management:** NgRx Store for global state (agent status, active contacts, queue state). Component-level state via Angular Signals.

**Softphone:** WebRTC peer connection managed in a dedicated Angular service. Audio tracks attached to hidden `<audio>` elements. SIP signaling delegated to the CPaaS SDK (loaded per chosen provider).

### 3.2 Spring Boot Backend (Modular Monolith)

```
/backend
  /app                        # Main application, Spring Boot entry point
  /modules
    /auth                     # Authentication and authorization
    /tenant                   # Tenant and user management
    /contact                  # Contact (interaction) lifecycle
    /routing                  # Routing engine
    /campaign                 # Campaign and dialer management
    /customer                 # Customer profile and CRM-lite
    /channel
      /telephony              # VoIP adapter + WebRTC signaling relay
      /email                  # Email adapter (IMAP/SMTP/webhook)
      /socialmedia            # Social media adapter (plugin architecture)
    /ivr                      # IVR tree configuration and execution
    /recording                # Call recording management
    /reporting                # Real-time and historical reporting
    /datawarehouse            # CDC event publisher to DWH queue
    /audit                    # Audit log write and query
    /gdpr                     # GDPR: erasure, export, consent registry
  /shared
    /multitenancy             # TenantContext, RLS configuration
    /events                   # Domain event types, RabbitMQ publishers
    /security                 # JWT filter, MFA utilities
    /api                      # OpenAPI annotations, common DTOs
```

**Module isolation rules:**
- No module may directly query another module's tables. Cross-module data access goes through the owning module's internal service interface.
- Domain events published to RabbitMQ are the only asynchronous coupling between modules.
- Each module has its own Spring `@Configuration` class and may declare its own flyway migration scripts under a module-specific prefix.

### 3.3 Python AI Service (FastAPI)

```
/ai-service
  /app
    /routers
      /voicebot             # POST /voicebot/process  (ASR result in, intent + response out)
      /chatbot              # POST /chatbot/process   (text in, intent + response out)
      /tts                  # POST /tts/synthesize    (text in, audio bytes out)
      /classification       # POST /classify/intent   (text in, intent label out)
    /models
      /nlu_model            # Intent classification model (Rasa NLU or HuggingFace)
    /integrations
      /google_tts           # Google Cloud TTS adapter
      /azure_tts            # Azure Cognitive Services TTS adapter
      /dialogflow           # Optional: Dialogflow passthrough adapter
    /config                 # Env-based configuration
```

Communication with Spring Boot: synchronous HTTP (internal network only, not exposed through API Gateway). Circuit breaker on the Spring Boot side (Resilience4j).

### 3.4 Channel Adapters

Each channel adapter implements the `ChannelAdapter` interface:

```
interface ChannelAdapter {
    void sendMessage(OutboundMessage message);
    void handleInbound(InboundMessage message);  // called by webhook/polling
    ChannelType getChannelType();
    boolean isHealthy();
}
```

**Telephony adapter:** Abstracts the chosen CPaaS provider (Twilio / Telnyx / Vonage). SIP/PSTN events translated to internal `ContactEvent` domain events. The adapter registers with the Spring context at startup; swapping providers requires only a new adapter implementation and configuration change.

**Email adapter:** Inbound via IMAP polling or provider webhook (e.g., SendGrid Inbound Parse). Outbound via SMTP or provider API. Attachment handling streams to Object Storage.

**Social media adapters (plugin architecture):** Each social platform (Facebook Messenger, WhatsApp Business, Instagram) is a separate `@Plugin` component loaded at startup. New platforms added without modifying core code. OAuth tokens stored encrypted per-tenant in `tenant_config`.

### 3.5 Routing Engine

The routing engine is a core in-process component of the Contact Center module.

```
Contact arrives
      |
      v
[Sticky agent check] --> agent available within timeout? --> assign directly
      |
      v (no sticky match)
[Skill matching] --> find agents with required skills, order by availability + score
      |
      v
[Strategy selector] --> SKILL_BASED | ROUND_ROBIN | FIRST_AVAILABLE (per queue config)
      |
      v
[Assign contact] --> publish ContactAssigned event --> notify agent via WebSocket
```

Queue state (agent availability, skill sets, contact queue depths) is maintained in Redis for sub-millisecond reads. The routing decision loop runs in < 500 ms (NFR-P04) because it reads from Redis, not PostgreSQL.

### 3.6 Data Warehouse Pipeline

```
PostgreSQL (operational)
      |
      |  Outbox pattern: domain events written to outbox table
      |  in same transaction as business data
      v
RabbitMQ (dwh.cdc exchange)
      |
      v
DWH Consumer (Spring Batch job / lightweight consumer)
      |
      v
Data Warehouse (ClickHouse / PostgreSQL+TimescaleDB)
      |
      v
BI Tools (Power BI, Tableau, Metabase) via SQL connector
```

The Outbox Pattern guarantees that events are never lost even if RabbitMQ is temporarily unavailable. The transactional outbox table is polled by a relay process that publishes to RabbitMQ. This ensures at-least-once delivery to the DWH (NFR requirement: replication lag < 1 hour; target < 15 minutes).

---

## 4. Data Architecture

### 4.1 Multi-Tenancy Strategy

**Approach: Shared schema with tenant_id column on every tenant-scoped table**

Rationale: Balances operational simplicity (single database to manage) with data isolation. At the scale of the initial target (50 tenants × 100 agents), dedicated databases per tenant would add significant operational overhead without proportional security benefit.

**Enforcement layers:**

1. **Application layer:** `TenantContext` (ThreadLocal) is populated from the validated JWT by a Spring filter early in the request lifecycle. All repository methods receive `tenantId` from the context — it is not passed by the caller.
2. **PostgreSQL Row-Level Security (RLS):** As a defense-in-depth measure, RLS policies are defined on all tenant-scoped tables. The application connects as a role with `SET app.current_tenant_id = ?` executed at connection acquisition. Even a SQL injection bypassing the application layer cannot read another tenant's data.
3. **Integration tests:** Each CI pipeline run includes a cross-tenant isolation test suite that attempts to read tenant B's data from tenant A's session.

### 4.2 Core Data Model

```
TENANT (1) ─────────────────────────── (N) USER
    |                                       |
    ├─── (N) CUSTOMER                  role: ADMIN | SUPERVISOR | AGENT
    |         |                             |
    |         └─ (N) CONTACT ──────────────┘
    |                   |
    |                   ├─── channel: PHONE | EMAIL | SOCIAL_MEDIA
    |                   ├─── direction: INBOUND | OUTBOUND
    |                   ├─── status: queued|active|completed|abandoned
    |                   └─── campaign_id (nullable FK → CAMPAIGN)
    |
    ├─── (N) CAMPAIGN
    |         └─── contact_list_id FK → CONTACT_LIST
    |               └─── (N) CONTACT_LIST_ITEM
    |
    ├─── (N) QUEUE
    |         └─── routing_strategy, required_skills, sticky_agent_timeout
    |
    ├─── (N) IVR_TREE
    |         └─── definition (JSONB: nodes, transitions, actions)
    |
    ├─── (N) AGENT_SKILL (many-to-many: USER <-> SKILL)
    |
    ├─── (N) EMAIL_TEMPLATE
    |
    ├─── (N) DISPOSITION_CODE
    |
    ├─── (N) RECORDING (metadata; binary in Object Storage)
    |
    ├─── (N) AUDIT_LOG
    |
    ├─── (N) GDPR_CONSENT
    |         └─── customer_id, consent_type, granted_at, withdrawn_at
    |
    └─── (1) TENANT_CONFIG (JSONB for all tenant-level settings)
```

### 4.3 Key Entity Details

```sql
-- Multi-tenancy pivot
TENANT (
  tenant_id     UUID PK,
  name          TEXT NOT NULL,
  status        TEXT CHECK (status IN ('active','inactive')),
  config        JSONB,            -- feature flags, limits, channel configs
  created_at    TIMESTAMPTZ DEFAULT now()
)

-- Agent presence tracked in Redis; DB holds profile
USER (
  user_id       UUID PK,
  tenant_id     UUID FK REFERENCES tenant,
  role          TEXT CHECK (role IN ('ADMINISTRATOR','SUPERVISOR','AGENT')),
  email         TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,    -- bcrypt, min 12 rounds
  mfa_secret    TEXT,             -- TOTP secret, encrypted at rest
  is_active     BOOLEAN DEFAULT true,
  created_at    TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ
)

AGENT_SKILL (
  agent_id  UUID FK REFERENCES user,
  skill_id  UUID FK REFERENCES skill,
  level     INT CHECK (level BETWEEN 1 AND 5),
  PRIMARY KEY (agent_id, skill_id)
)

-- GDPR: soft-delete + anonymization
CUSTOMER (
  customer_id   UUID PK,
  tenant_id     UUID FK,
  first_name    TEXT,             -- nulled on erasure
  last_name     TEXT,             -- nulled on erasure
  phone         TEXT[],           -- nulled on erasure
  email         TEXT[],           -- nulled on erasure
  custom_fields JSONB,
  gdpr_consent  JSONB,
  is_deleted    BOOLEAN DEFAULT false,
  anonymized_at TIMESTAMPTZ,
  created_at    TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ
)

-- Every interaction, regardless of channel
CONTACT (
  contact_id      UUID PK,
  tenant_id       UUID FK,
  customer_id     UUID FK REFERENCES customer,
  agent_id        UUID FK REFERENCES user NULLABLE,
  channel         TEXT CHECK (channel IN ('PHONE','EMAIL','SOCIAL_MEDIA','RCS')),
  direction       TEXT CHECK (direction IN ('INBOUND','OUTBOUND')),
  status          TEXT CHECK (status IN ('queued','active','completed','abandoned')),
  campaign_id     UUID FK NULLABLE,
  queue_id        UUID FK NULLABLE,
  started_at      TIMESTAMPTZ,
  answered_at     TIMESTAMPTZ,
  ended_at        TIMESTAMPTZ,
  disposition_code TEXT,
  recording_id    UUID FK NULLABLE,
  metadata        JSONB            -- channel-specific data (email headers, social thread id)
)
```

### 4.4 Redis Data Structures

| Key Pattern | Type | TTL | Content |
|-------------|------|-----|---------|
| `agent:{tenantId}:{agentId}:presence` | Hash | 30s (heartbeat refreshed) | status, current_contact_count, skills |
| `queue:{tenantId}:{queueId}:depth` | String | none | integer: contacts waiting |
| `queue:{tenantId}:{queueId}:contacts` | Sorted Set | none | contact_ids ordered by enqueue time |
| `session:{userId}` | Hash | JWT expiry | user metadata for fast auth checks |
| `jwt:blacklist:{jti}` | String | token expiry | "1" (revoked token marker) |
| `ratelimit:{tokenId}` | String | 60s sliding | request counter |
| `tenant:{tenantId}:config` | Hash | 10min | tenant config cache |
| `rt:dashboard:{tenantId}` | Hash | 10s | aggregated real-time metrics for supervisor dashboard |

### 4.5 Data Warehouse Schema (Star Schema)

```
FACT_CONTACT (
  contact_id, tenant_id, customer_id, agent_id, campaign_id, queue_id,
  channel, direction, disposition_code,
  started_at, answered_at, ended_at,
  wait_duration_seconds, handle_duration_seconds, wrap_up_seconds,
  abandoned BOOLEAN
)

FACT_AGENT_ACTIVITY (
  activity_id, tenant_id, agent_id,
  status (available|busy|break|offline),
  started_at, ended_at, duration_seconds
)

FACT_CAMPAIGN_ATTEMPT (
  attempt_id, tenant_id, campaign_id, contact_list_item_id, customer_id, agent_id,
  attempted_at, outcome (connected|no_answer|busy|failed), disposition_code,
  call_duration_seconds
)

DIM_TENANT (tenant_id, name, plan, created_at)
DIM_AGENT  (agent_id, tenant_id, full_name, skills_snapshot)
DIM_CUSTOMER (customer_id, tenant_id, anonymized)
DIM_CAMPAIGN (campaign_id, tenant_id, name, type, dialer_type)
DIM_DATE / DIM_TIME (standard time dimensions)
```

---

## 5. API Design

### 5.1 API Style and Conventions

- **Style:** REST over HTTPS
- **Format:** JSON (application/json); multipart/form-data for file uploads
- **Versioning:** URL path prefix (`/api/v1/`)
- **Documentation:** OpenAPI 3.0, served at `/api/v1/docs` (Swagger UI) per tenant context
- **Authentication:** Bearer JWT in Authorization header
- **Pagination:** Cursor-based for large collections (`?after=<cursor>&limit=50`)
- **Error format:**
  ```json
  {
    "error": "RESOURCE_NOT_FOUND",
    "message": "Customer with id X not found",
    "traceId": "abc123",
    "timestamp": "2026-03-12T10:00:00Z"
  }
  ```

### 5.2 Key Endpoint Groups

```
Authentication
  POST   /api/v1/auth/login
  POST   /api/v1/auth/refresh
  POST   /api/v1/auth/logout
  POST   /api/v1/auth/mfa/verify
  POST   /api/v1/auth/password/reset-request
  POST   /api/v1/auth/password/reset

Tenant Management (Admin only)
  GET    /api/v1/admin/tenants
  POST   /api/v1/admin/tenants
  GET    /api/v1/admin/tenants/{tenantId}
  PATCH  /api/v1/admin/tenants/{tenantId}
  DELETE /api/v1/admin/tenants/{tenantId}/deactivate
  GET    /api/v1/admin/tenants/{tenantId}/metrics

Users and Agents
  GET    /api/v1/users
  POST   /api/v1/users
  GET    /api/v1/users/{userId}
  PATCH  /api/v1/users/{userId}
  PUT    /api/v1/agents/{userId}/status         # Agent sets own presence
  GET    /api/v1/agents/{userId}/skills
  PUT    /api/v1/agents/{userId}/skills

Customers
  GET    /api/v1/customers?q={search}           # Fuzzy search (p95 < 1s)
  POST   /api/v1/customers
  GET    /api/v1/customers/{customerId}
  PATCH  /api/v1/customers/{customerId}
  DELETE /api/v1/customers/{customerId}/gdpr-erase
  GET    /api/v1/customers/{customerId}/export  # GDPR Art. 20
  GET    /api/v1/customers/{customerId}/contacts

Contacts (Interactions)
  GET    /api/v1/contacts
  GET    /api/v1/contacts/{contactId}
  PATCH  /api/v1/contacts/{contactId}/disposition
  POST   /api/v1/contacts/{contactId}/transfer

Campaigns
  GET    /api/v1/campaigns
  POST   /api/v1/campaigns
  GET    /api/v1/campaigns/{campaignId}
  PATCH  /api/v1/campaigns/{campaignId}
  POST   /api/v1/campaigns/{campaignId}/start
  POST   /api/v1/campaigns/{campaignId}/pause
  POST   /api/v1/campaigns/{campaignId}/stop
  POST   /api/v1/campaigns/{campaignId}/contact-list (multipart CSV upload)

Queues
  GET    /api/v1/queues
  POST   /api/v1/queues
  GET    /api/v1/queues/{queueId}
  PUT    /api/v1/queues/{queueId}
  GET    /api/v1/queues/{queueId}/state          # Real-time: reads Redis

IVR
  GET    /api/v1/ivr
  POST   /api/v1/ivr
  GET    /api/v1/ivr/{ivrId}
  PUT    /api/v1/ivr/{ivrId}
  POST   /api/v1/ivr/{ivrId}/activate

Recordings
  GET    /api/v1/recordings/{recordingId}
  GET    /api/v1/recordings/{recordingId}/stream  # Presigned URL redirect

Reporting
  GET    /api/v1/reports/realtime/dashboard       # Reads Redis rt:dashboard
  GET    /api/v1/reports/agents?from=&to=&granularity=
  GET    /api/v1/reports/campaigns/{campaignId}
  GET    /api/v1/reports/export?type=&from=&to=   # Async, returns job ID
  GET    /api/v1/reports/export/{jobId}/status
  GET    /api/v1/reports/export/{jobId}/download

Webhooks (external integration)
  POST   /api/v1/webhooks                        # Register webhook endpoint
  GET    /api/v1/webhooks
  DELETE /api/v1/webhooks/{webhookId}
```

### 5.3 WebSocket API

```
STOMP endpoint: /ws  (SockJS fallback)

Subscriptions (client → server):
  /topic/tenant/{tenantId}/dashboard       # Real-time supervisor metrics
  /topic/tenant/{tenantId}/queue/{queueId} # Queue depth updates
  /user/queue/contacts                     # Contact assignments for logged-in agent
  /user/queue/notifications                # System notifications for the user

Messages (server → client):
  ContactAssigned     { contactId, channel, customerId, customerName, cliNumber }
  ContactEnded        { contactId, duration }
  QueueDepthUpdate    { queueId, depth, longestWaitSeconds }
  DashboardUpdate     { agentsAvailable, agentsBusy, contactsQueued, aht }
  SystemNotification  { severity, message }
```

### 5.4 Internal AI Service API (internal network only)

```
POST /voicebot/process
  Request:  { tenantId, sessionId, asrText, context }
  Response: { intent, confidence, responseText, action, escalate: bool }

POST /chatbot/process
  Request:  { tenantId, sessionId, text, channel, context }
  Response: { intent, confidence, responseText, quickReplies[], escalate: bool }

POST /tts/synthesize
  Request:  { tenantId, text, language, voice }
  Response: audio/mp3 binary stream

POST /classify/intent
  Request:  { tenantId, text }
  Response: { intent, confidence, entities[] }
```

---

## 6. Security Architecture

### 6.1 Authentication

**JWT-based authentication with refresh token rotation:**

```
Login request
    |
    v
Validate credentials (bcrypt, 12 rounds)
    |
    v (success)
MFA check required? (mandatory for ADMIN and SUPERVISOR)
    |
    v
Issue Access Token (JWT, 1h TTL, configurable) + Refresh Token (30d, httpOnly cookie)
    |
    v
Request → API Gateway validates JWT signature + expiry + blacklist check (Redis)
```

- Access tokens: short-lived JWTs signed with RS256 (asymmetric key pair). Public key served at `/.well-known/jwks.json` for potential third-party validation.
- Refresh tokens: opaque, stored hashed in PostgreSQL. Single-use; each refresh rotates the token (rotation + revocation on theft detection).
- JWT blacklist: revoked JTIs stored in Redis with TTL matching original token expiry. Checked on every request in the JWT filter.
- MFA: TOTP (RFC 6238) using time-based one-time passwords. Secret stored AES-256 encrypted in the database.

### 6.2 Authorization

Role-Based Access Control (RBAC) with tenant scoping:

| Role | Scope | Permissions |
|------|-------|-------------|
| SYSTEM_ADMIN | All tenants | Full access to all resources |
| SUPERVISOR | Own tenant | Manage agents, campaigns, queues, IVR, reports; read customers |
| AGENT | Own tenant | Handle assigned contacts; read/write own customer interactions; update own status |

**Implementation:** Spring Security with custom `@PreAuthorize` annotations. A `TenantSecurityService` bean verifies both role and tenant ownership on every protected operation. Controllers use method-level security; tenant_id injection is never trusted from the request body — it is always sourced from the authenticated principal.

### 6.3 Data Protection

| Layer | Mechanism |
|-------|-----------|
| Transport | TLS 1.2 minimum (TLS 1.3 preferred); HSTS enforced |
| Database | PostgreSQL RLS as defense-in-depth; connection via SSL |
| Passwords | bcrypt, minimum 12 rounds (NFR-SEC02) |
| MFA secrets | AES-256 encrypted in DB column |
| Call recordings | AES-256 encrypted at rest in Object Storage (NFR-SEC07) |
| Sensitive config | Vault (HashiCorp Vault) or environment secrets — never committed to code |
| API tokens | Rate limited: 1000 req/min per token (NFR-SEC09), enforced in Redis |

### 6.4 GDPR / RODO Compliance

| Requirement | Implementation |
|-------------|----------------|
| Right to erasure (Art. 17) | `GdprEraseCommand` nulls PII fields on CUSTOMER row; sets `is_deleted=true` and `anonymized_at`. Contact history rows are retained with anonymized customer reference for statistical integrity. |
| Data portability (Art. 20) | `GET /customers/{id}/export` returns all tenant-scoped data for a customer in JSON or CSV. |
| Retention policy | RECORDING rows have `delete_after` date. A scheduled job (daily) deletes expired recordings from Object Storage and marks records. Configurable per tenant. |
| Processing registry (Art. 30) | `GDPR_PROCESSING_REGISTRY` table auto-populated from audit events; accessible to Admin. |
| Marketing consent | `GDPR_CONSENT` table; Campaign dialer checks consent before initiating outbound call. |
| EU data residency | Deployment region locked to EU (NFR-RODO04); enforced at infrastructure level. |
| Data breach notification | Audit log + alerting pipeline provides evidence chain. Incident response playbook in runbooks. |

### 6.5 Audit Log

Every create/update/delete operation on administrative entities (tenant, user, campaign, queue, IVR, customer) writes to `AUDIT_LOG`:

```
AUDIT_LOG (
  log_id        UUID PK DEFAULT gen_random_uuid(),
  tenant_id     UUID FK NULLABLE,       -- null for system-level ops
  user_id       UUID FK,
  action        TEXT,                   -- e.g., 'CUSTOMER_UPDATED'
  entity_type   TEXT,
  entity_id     UUID,
  old_value     JSONB,
  new_value     JSONB,
  ip_address    INET,
  user_agent    TEXT,
  created_at    TIMESTAMPTZ DEFAULT now()
)
```

Audit log is append-only; no UPDATE/DELETE is permitted on this table at the application level. Database-level revoke on UPDATE/DELETE for the application role.

---

## 7. Infrastructure and Deployment

### 7.1 Environments

| Environment | Purpose | Notes |
|-------------|---------|-------|
| local | Developer workstation | Docker Compose: all services |
| dev | Shared development | Auto-deployed on merge to `develop` branch |
| staging | Pre-production QA | Production parity; load tests run here |
| production | Live system | EU-region only; HA configuration |

### 7.2 Docker Compose (Local/Dev)

```yaml
services:
  postgres:       image: postgres:16-alpine
  redis:          image: redis:7-alpine
  rabbitmq:       image: rabbitmq:3.13-management
  backend:        build: ./backend   (Spring Boot, port 8080)
  ai-service:     build: ./ai-service (FastAPI, port 8001)
  frontend:       build: ./frontend   (Nginx, port 4200)
  coturn:         image: coturn/coturn (STUN/TURN, port 3478)
  dwh:            image: clickhouse/clickhouse-server (port 8123)
```

### 7.3 Production Infrastructure (Kubernetes)

```
Kubernetes Cluster (EU region)
├── Namespace: contact-center-prod
│   ├── Deployment: backend (Spring Boot)
│   │     replicas: 3+ (HPA: scale on CPU > 70%)
│   │     readinessProbe: /actuator/health/readiness
│   │     livenessProbe:  /actuator/health/liveness
│   ├── Deployment: ai-service (FastAPI)
│   │     replicas: 2+ (HPA: scale on CPU > 60%)
│   ├── Deployment: frontend (Nginx)
│   │     replicas: 2
│   ├── StatefulSet: coturn (TURN server)
│   │     replicas: 2 (active-active with DNS round-robin)
│   ├── Service: api-gateway (LoadBalancer / Ingress with cert-manager)
│   └── CronJob: gdpr-retention-cleanup (daily)
│
├── Managed Services (cloud provider)
│   ├── PostgreSQL: Managed HA (primary + 1 sync replica + 1 async read replica)
│   │     Automated backups every 5 minutes → RPO < 15 min (NFR-A04)
│   │     Point-in-time recovery
│   ├── Redis: Managed Redis Cluster (3 nodes, data persistence enabled)
│   ├── RabbitMQ: Managed or self-hosted as StatefulSet (3-node cluster, mirrored queues)
│   ├── Object Storage: S3-compatible (EU region) for recordings
│   └── Data Warehouse: ClickHouse Cloud or self-hosted ClickHouse StatefulSet
│
└── Observability Namespace
    ├── Prometheus + Alertmanager
    ├── Grafana
    └── Loki (log aggregation)
```

### 7.4 High Availability Design

| Component | HA Strategy | RTO Impact |
|-----------|-------------|------------|
| Spring Boot | 3+ replicas, rolling updates, HPA | Zero-downtime deploys |
| PostgreSQL | Primary + sync replica; automatic failover (Patroni or cloud HA) | < 60s automatic failover |
| Redis | Cluster with replication; sentinel for failover | < 30s failover |
| RabbitMQ | 3-node cluster, quorum queues | Survives 1 node loss |
| Object Storage | Cloud-native HA (multi-AZ) | Transparent |
| TURN server | 2 active nodes; client retries with DNS | Transparent to WebRTC |

**Overall RTO target:** < 1 hour (NFR-A03). Database failover < 60s; app pod restart < 30s; DNS propagation is the primary variable.

### 7.5 CI/CD Pipeline

```
Developer pushes branch
        |
        v
GitHub Actions / GitLab CI
  1. Unit tests (JUnit 5 / Pytest)
  2. Integration tests (Testcontainers: Postgres, Redis, RabbitMQ)
  3. Cross-tenant isolation tests
  4. Static analysis (SpotBugs / Checkstyle / Ruff)
  5. OWASP Dependency Check
  6. Build Docker image + push to registry
        |
        v (merge to develop)
  Deploy to DEV (Helm upgrade)
        |
        v (merge to main / release tag)
  Deploy to STAGING
  Run load tests (k6: 50 tenants × 100 agents)
  Run OWASP ZAP scan
        |
        v (manual approval gate)
  Deploy to PRODUCTION (Helm upgrade, rolling deployment)
  Smoke tests
  Alerting validation
```

### 7.6 Database Migrations

Flyway manages schema migrations. Version naming: `V{yyyyMMddHHmm}__{module}_{description}.sql`.

Rules:
- Migrations are always additive in production (no DROP COLUMN without deprecation cycle).
- RLS policies deployed as separate Flyway scripts under `/db/migration/rls/`.
- Each module maintains its own migration scripts, merged into the main migration sequence at build time.

---

## 8. Cross-cutting Concerns

### 8.1 Logging

**Structured JSON logging** (Logback + logstash-logback-encoder):

Every log line includes: `traceId`, `spanId`, `tenantId`, `userId`, `module`, `level`, `message`, `timestamp`.

```
Log levels:
  ERROR  – exceptions requiring immediate attention
  WARN   – degraded behavior, retries, circuit breaker opens
  INFO   – significant business events (contact started, campaign paused)
  DEBUG  – verbose detail (disabled in production by default)
```

Logs shipped to Loki via Promtail. Retention: 30 days in hot storage, 1 year in cold storage.

**Sensitive data masking:** A custom Logback converter masks phone numbers, email addresses, and credit card patterns in log output.

### 8.2 Distributed Tracing

Spring Boot instrumented with Micrometer Tracing (OpenTelemetry exporter). Traces include:
- HTTP request lifecycle
- RabbitMQ publish/consume spans
- Redis commands
- Database query spans (via datasource proxy)
- AI service HTTP calls

Trace context propagated via W3C TraceContext headers. Jaeger (or Grafana Tempo) as trace backend.

### 8.3 Metrics and Monitoring

**Application metrics** (Micrometer → Prometheus):

| Metric | Description |
|--------|-------------|
| `cc_contacts_active{tenantId, channel}` | Active contacts per tenant/channel |
| `cc_queue_depth{tenantId, queueId}` | Contacts waiting in queue |
| `cc_routing_decision_ms` | Routing engine latency histogram |
| `cc_api_response_ms{endpoint, status}` | API latency per endpoint |
| `cc_rabbitmq_lag{queue}` | Consumer lag |
| `cc_ai_service_latency_ms{operation}` | AI service response time |

**Alerting rules (Alertmanager):**
- API p95 latency > 200 ms for > 5 min → WARNING
- API p95 latency > 500 ms for > 2 min → CRITICAL
- RabbitMQ consumer lag > 1000 messages → WARNING
- PostgreSQL replica lag > 30s → CRITICAL
- Any tenant with 0 available agents + > 0 queued contacts → WARNING

### 8.4 Error Handling

**Spring Boot global exception handler** (`@ControllerAdvice`):

| Exception Type | HTTP Status | Behavior |
|----------------|------------|---------|
| `ResourceNotFoundException` | 404 | Standard error JSON |
| `AccessDeniedException` | 403 | Standard error JSON; audit logged |
| `TenantLimitExceededException` | 429 | Error with limit details |
| `ValidationException` | 422 | Field-level validation errors |
| `ExternalServiceException` | 502 | Logs the downstream error; returns generic message |
| Uncaught exception | 500 | Error logged with full stack trace; traceId returned to client |

Internal service calls to the AI Service are wrapped with **Resilience4j** circuit breaker:
- Threshold: 50% failure rate over 10 calls → OPEN
- Wait duration: 30 seconds
- Fallback: graceful degradation (voicebot/chatbot routes to agent immediately)

### 8.5 Caching Strategy

| Cache Layer | Technology | What is Cached | Invalidation |
|-------------|-----------|----------------|-------------|
| Tenant config | Redis, TTL 10 min | `TENANT_CONFIG` rows | On config update (event-driven) |
| User/agent profile | Redis, TTL 5 min | User role + skills (for routing) | On user update |
| Queue routing state | Redis, no TTL | Agent presence + queue depths | Real-time via heartbeat + events |
| IVR tree definition | Redis, TTL 5 min | Active IVR tree JSON | On IVR publish event |
| Report aggregates | Redis, TTL 60s | Pre-computed dashboard metrics | TTL expiry + push from RabbitMQ consumer |
| Static assets | Nginx / CDN | Angular SPA bundles | Cache-busting via content hash in filename |

**No application-level L1 cache (e.g., Caffeine) for multi-tenant data.** Using a process-local cache in a multi-instance environment creates cross-instance inconsistency risk. All shared state goes through Redis.

### 8.6 Background Jobs and Scheduling

| Job | Schedule | Description |
|-----|----------|-------------|
| GDPR retention cleanup | Daily 02:00 | Delete recordings past retention date; anonymize overdue customers |
| Campaign scheduler | Every 60s | Check campaign schedules; start/stop dialers accordingly |
| DWH outbox relay | Every 10s | Flush outbox events to RabbitMQ for DWH consumer |
| Agent heartbeat expiry | Every 30s | Mark agents as offline if heartbeat not received in 60s |
| Report pre-aggregation | Every 5 min | Pre-compute supervisor dashboard metrics into Redis |
| JWT refresh token cleanup | Daily 03:00 | Delete expired refresh tokens from database |

Jobs implemented as Spring `@Scheduled` tasks. In a multi-instance deployment, only one instance executes a given job at a time — enforced via a distributed lock in Redis (Redisson's `RLock`).

---

## 9. Key Architectural Decisions

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

**Exchange topology:**
```
contact.events     (topic exchange)  → routing_engine, dwh_consumer, reporting_consumer
campaign.events    (topic exchange)  → dialer, dwh_consumer
channel.inbound    (direct exchange) → channel-specific handler queues
audit.events       (fanout exchange) → audit_log_writer, compliance_consumer
dwh.cdc            (direct exchange) → dwh_loader
notifications      (topic exchange)  → websocket_broadcaster
```

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

### ADR-08: Modular Social Media Plugin Architecture

**Decision:** Each social media platform is a separate plugin loaded via Spring's component scanning, implementing the `ChannelAdapter` interface.

**Rationale:** PRD 6.2 notes platform API instability as a risk. Isolating each platform in its own plugin class means a breaking API change at one provider affects only that plugin. New platforms (TikTok, Telegram) are added as new plugins without modifying any existing code.

---

## 10. Risks and Mitigations

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

## Appendix A: Technology Decisions Summary

| Layer | Technology | Version Target | Decision Basis |
|-------|-----------|----------------|----------------|
| Frontend framework | Angular | 18+ | TECH-STACK mandate |
| Backend runtime | Java + Spring Boot | Java 21, Spring Boot 3.3+ | TECH-STACK mandate |
| AI / NLP service | Python + FastAPI | Python 3.12+ | TECH-STACK mandate (Python); FastAPI for async performance |
| Relational database | PostgreSQL | 16+ | TECH-STACK mandate |
| Cache / presence | Redis | 7+ | TECH-STACK mandate |
| Message broker | RabbitMQ | 3.13+ | TECH-STACK mandate |
| Data Warehouse | ClickHouse | Latest stable | PRD requirement; columnar storage for analytics |
| Object Storage | S3-compatible | n/a | PRD requirement (recordings) |
| WebRTC media | Coturn (TURN/STUN) | Latest stable | RFC 5766; open source; production proven |
| Container orchestration | Kubernetes | 1.30+ | Industry standard for HA at this scale |
| Service mesh | None in Phase 1 | — | Modular monolith eliminates most inter-service traffic |
| API Gateway | Spring Cloud Gateway or Nginx | — | To be decided in Phase 1 Sprint 1 |
| Migrations | Flyway | Latest stable | Java-native; works with Spring Boot lifecycle |
| Observability | Prometheus + Grafana + Loki | Latest stable | Open source; Kubernetes-native |
| Tracing | Micrometer + OpenTelemetry | Latest stable | Vendor-neutral; Grafana Tempo or Jaeger backend |
| CI/CD | GitHub Actions or GitLab CI | — | To be decided based on team preference |
| Secret management | HashiCorp Vault or K8s Secrets + Sealed Secrets | — | To be decided in infrastructure setup |

---

## Appendix B: Module Dependency Rules

To maintain the modularity of the monolith, the following dependency rules are enforced (verified by ArchUnit tests in CI):

```
auth         → (no module dependencies)
tenant       → auth
customer     → auth, tenant
contact      → auth, tenant, customer, routing
routing      → auth, tenant           (reads agent/queue state from Redis)
campaign     → auth, tenant, customer, contact
channel      → auth, tenant, contact  (adapters publish to contact module)
ivr          → auth, tenant
recording    → auth, tenant, contact
reporting    → auth, tenant           (reads from DWH, not from other modules' tables)
datawarehouse→ (consumer only; no module dependencies)
audit        → auth                   (append-only; called by all modules via AOP)
gdpr         → auth, tenant, customer, contact, recording
```

**Cycle detection:** ArchUnit tests fail the build if a circular dependency between modules is introduced.

---

## Appendix C: Performance Budget

| Operation | Target | Measurement Point |
|-----------|--------|-------------------|
| API CRUD operations | p95 < 200 ms | API Gateway egress |
| Customer search (fuzzy) | p95 < 1000 ms | API Gateway egress |
| Routing decision | p95 < 500 ms | Internal metric |
| WebSocket dashboard update | <= 5s between updates | Client-side measurement |
| AI service (chatbot/voicebot) | p95 < 800 ms | Spring → AI service round trip |
| Call connection setup | < 3s | Agent desktop to call connected |
| DWH replication lag | < 1 hour (target < 15 min) | DWH consumer metric |
| CSV import (100k records) | < 2 minutes | API response (async job completion) |
| Report export (12 months) | < 30 seconds | API response (async job completion) |
