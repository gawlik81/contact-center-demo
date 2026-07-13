---
name: AgentBreak scheduler implementation
description: AgentBreakActivator scheduler for automatic break status transitions (PLANNED→ACTIVE→COMPLETED)
type: project
---

AgentBreakActivator scheduler implemented in `backend/app/src/main/java/com/contactcenter/domain/agentbreak/AgentBreakActivator.java`.

Bulk SQL methods added to AgentBreakRepository: `activateDueBreaks(UUID tenantId)` and `completeExpiredBreaks(UUID tenantId)`.

**Why:** Agents plan breaks (e.g. at 12:00) but the system had no mechanism to automatically transition statuses in time. Scheduler runs every 30s by default.

**How to apply:** When touching AgentBreak domain, scheduler is enabled via `agent.breaks.activator.enabled=true` (matchIfMissing=true). Configurable interval: `agent.breaks.activator.interval-ms` (default 30000ms). Pattern follows ScheduledCallbackExecutor exactly — per-tenant TenantContext, finally block for cleanup, error isolation per tenant.
