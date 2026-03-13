---
name: Contact Center SaaS - Project Context
description: Core architectural decisions, domain entities, and NFRs for the Contact Center SaaS platform
type: project
---

## Project: Contact Center SaaS Platform (Multi-tenant)

**PRD version:** 1.0 (2026-03-12)
**ARCHITECTURE.md generated:** 2026-03-12

## Architecture Style
- Phase 1: Modular Monolith (single Spring Boot app with bounded modules)
- Python FastAPI AI service is the only separate runtime in Phase 1
- Migration path to microservices preserved via strict module isolation rules

## Technology Stack (confirmed)
- Frontend: Angular (TypeScript)
- Backend: Java 21 + Spring Boot 3.3+
- AI/NLP: Python 3.12 + FastAPI
- Database: PostgreSQL 16+ (shared schema, logical multi-tenancy)
- Cache: Redis 7+ (agent presence, queue state, session cache, JWT blacklist)
- Broker: RabbitMQ 3.13+ (quorum queues)
- Data Warehouse: ClickHouse (not yet confirmed - noted as option in PRD)
- Object Storage: S3-compatible (recordings, encrypted AES-256)
- WebRTC TURN/STUN: Coturn

## Key ADRs
- ADR-01: Modular monolith Phase 1 (not microservices)
- ADR-02: Shared PostgreSQL + RLS (not database-per-tenant)
- ADR-03: RabbitMQ (not Kafka - scale doesn't justify Kafka complexity)
- ADR-04: Redis as system-of-record for agent presence and routing state
- ADR-05: Adapter pattern for telephony (provider swappable via config)
- ADR-06: Python AI service separate runtime for NLP/ML
- ADR-07: Outbox Pattern for DWH replication (solves dual-write problem)
- ADR-08: Plugin architecture for social media channels

## Multi-tenancy
- tenant_id column on all tenant-scoped tables
- TenantContext (ThreadLocal) set from JWT in Spring Security filter
- PostgreSQL RLS as defense-in-depth second layer
- Cross-tenant isolation tests run in every CI build

## Domain Entities
TENANT, USER (roles: SYSTEM_ADMIN, SUPERVISOR, AGENT), CUSTOMER (GDPR soft-delete),
CONTACT (every interaction regardless of channel), CAMPAIGN, QUEUE, IVR_TREE,
AGENT_SKILL (many-to-many), RECORDING, AUDIT_LOG, GDPR_CONSENT,
DISPOSITION_CODE, EMAIL_TEMPLATE, CONTACT_LIST, CONTACT_LIST_ITEM

## NFRs
- SLA: 99.9% uptime
- RTO < 1 hour, RPO < 15 minutes
- API CRUD p95 < 200ms
- Routing decision p95 < 500ms
- 100 concurrent agents per tenant target
- 50 tenants scale target
- GDPR/RODO full compliance (EU data residency mandatory)
- bcrypt 12 rounds, JWT RS256, MFA mandatory for ADMIN+SUPERVISOR

## Open Questions (from PRD, not blocking architecture)
- OQ-01: Which VoIP provider for MVP (Twilio vs Telnyx POC recommended)
- OQ-02: Which social media platforms in MVP scope
- OQ-04: Voicebot in-house (Python/Rasa) vs Dialogflow/external
- OQ-05: Preferred BI tool (affects DWH schema)
