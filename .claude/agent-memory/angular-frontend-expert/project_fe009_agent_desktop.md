---
name: Agent Desktop layout and status panel (FE-009)
description: AgentDesktopComponent with WebSocket service, status panel, contact tabs, queue sidebar
type: project
---

Agent Desktop main view implemented. Route: /agent/desktop (already existed, pointed to placeholder – updated import).

**Key files:**
- `core/services/websocket.service.ts` – native WebSocket (no @stomp/stompjs – not in package.json), signals for connectionState, exponential backoff reconnect, 30s ping timer
- `features/agent/models/agent-status.model.ts` – AgentStatus type + AGENT_STATUS_CONFIG (label/color/bgColor/icon per status)
- `features/agent/models/contact-tab.model.ts` – ContactTab interface
- `features/agent/models/queue-item.model.ts` – QueueItem interface
- `features/agent/models/ws-event.model.ts` – WsEvent types + payload interfaces
- `features/agent/services/agent-status.service.ts` – PATCH /api/users/me/status, signal currentStatus
- `features/agent/services/contact-tab.store.ts` – signals: tabs, activeTab, tab limits (1 PHONE + 3 CHAT/EMAIL = 4 total)
- `features/agent/pages/agent-desktop/agent-desktop.component.ts` – main component (OnInit connects WS, OnDestroy disconnects)

**Architecture decisions:**
- Native WebSocket (not STOMP) because @stomp/stompjs absent. TODO comment in websocket.service.ts explains migration path.
- WS token passed as ?token= query param (native WS can't set custom headers)
- ContactTabStore is providedIn: root (singleton, survives navigation)
- AgentStatusService is providedIn: root (singleton status)

**Layout:** header (status panel + WS indicator) | sidebar (queue) | main (contact tabs)
**Tab limits enforced in ContactTabStore.checkLimits() – returns TabLimitReason, component shows timed message**
**WS banner shown when connectionState === DISCONNECTED | ERROR, reconnecting text when CONNECTING**

**Why:** FE-009 task – agent needs real-time contact handling interface

**How to apply:** When adding phone/chat/email panels, inject ContactTabStore and replace placeholder @switch blocks in agent-desktop.component.html
