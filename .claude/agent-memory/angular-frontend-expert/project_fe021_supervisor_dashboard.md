---
name: Supervisor RT Dashboard (FE-021)
description: Real-time supervisor dashboard – KPI cards, agent table with break tracking, queue bar chart, WebSocket integration
type: project
---

Supervisor dashboard with WebSocket real-time updates implemented.

**Why:** Supervisors need live visibility into agent statuses, queue depths, and KPI metrics updated via WebSocket SUPERVISOR_METRICS events every 5 seconds.

**How to apply:** Reference this implementation pattern for any future real-time dashboard features.

## Files created/modified
- `frontend/src/app/features/agent/models/ws-event.model.ts` – added `'SUPERVISOR_METRICS'` to `WsEventType` union
- `frontend/src/app/features/supervisor/models/supervisor-metrics.model.ts` – model types (AgentMetric, QueueMetric, KpiMetric, SupervisorMetrics, SupervisorMetricsRawPayload)
- `frontend/src/app/features/supervisor/services/supervisor-metrics.service.ts` – subscribes to WS topic, maps snake_case→camelCase, exposes `metrics: Signal<SupervisorMetrics | null>`
- `frontend/src/app/features/supervisor/supervisor-dashboard.component.ts` – standalone OnPush component, break-time tracking with local signal + setInterval, fullscreen API
- `frontend/src/app/features/supervisor/supervisor-dashboard.component.html` – external template
- `frontend/src/app/features/supervisor/supervisor-dashboard.component.scss` – external styles, pure CSS queue bar chart with `transition: width 0.35s`

## Key patterns used
- `SupervisorMetricsService` subscribes to WS in constructor via `takeUntilDestroyed()`, no need to re-subscribe on component init
- `breakStartMap` signal tracks BREAK entry timestamps per agent id; effect() monitors metrics changes to update it
- `nowMs` signal updated every 10s via `setInterval` drives `agentBreakMinutes` computed()
- Queue bar chart width computed as `Math.min(100, waiting / maxWaiting * 100)` with CSS `transition: width 0.35s ease-in-out`
- No Angular Material – project uses pure CSS only (Material is NOT installed)
- `Record<string, number | undefined>` needed for indexed access type to avoid NG8102 warnings in template
