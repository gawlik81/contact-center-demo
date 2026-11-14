---
name: adr-14-ws-broker
description: BE-174 / ADR-14 WebSocket multi-instance decision (Redis fan-out proposed over RabbitMQ STOMP relay), spike findings and the existing SUBSCRIBE-authorization leak
metadata:
  type: project
---

ADR-14 lives in ARCHITECTURE.md §11.14 (after ADR-13), pointer in §9; spike report in documentation/tech/adr-14-ws-broker-spike.md (written 2026-11-13, status Proposed, owner approval pending).

- Proposed: Option B (Redis pub/sub into local SimpleBroker); RabbitMQ STOMP relay (A) is the escalation path; gateway (C) deferred. Deviates from BE-175/BE-176 default (A).
- **Why:** A drops all sessions on broker restart (11k reconnects/35 s at 500 sessions), needs destination rename (RabbitMQ rejects `/` in `/topic/` names), ~2 queues + 1 fd per session (default nofile 1024 caps ~830 sessions), `/queue/` queues are durable and leak, WS memory can hit the shared broker's watermark and block business queues.
- Existing P0 defect found: no SUBSCRIBE authorization anywhere (only CONNECT JWT); SimpleBroker honours Ant patterns, an AGENT received other tenants' events in the spike. Needs its own ticket regardless of broker.
- Presence events (SessionConnected/Subscribe/Disconnect) stay local to the instance holding the WS even under a relay; the BE-174 premise was wrong.
- Spike gotchas: Testcontainers 1.20.4 needs `-Dapi.version=1.44` on this host; backend lacks reactor-netty-http (needed for relay).
- **How to apply:** if the owner picks A or B, use the requirement lists in ADR-14; do not re-run the spike, numbers are in the report.
