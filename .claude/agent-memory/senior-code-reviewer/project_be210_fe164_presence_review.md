---
name: be210-fe164-presence-review
description: BE-210/FE-164 agent presence (ghost agents) review 2026-11-08 - BE 3.5/5 (must-fix Redis flush/outage mass-OFFLINE), FE 4/5
metadata:
  type: project
---

BE-210 (Redis ZSET presence + sweeper CAS) 3.5/5; FE-164 (ping timer + notice) 4/5, both approve-with-fixes, 2026-11-08.

Open: BE must-fix = after Redis outage/flush sweeper flips present agents (no recovery grace / epoch sentinel); should-fix = Redis I/O (4 RTT/touch) on STOMP inbound thread with default Lettuce 60s timeout, ping-after-disconnect revives session, full AppUser entities cross-tenant every 30s. FE should-fix = no client-side dead-link detection (heartbeatIncoming 0, PONG goes to /user/** which nobody subscribes), background-tab throttling vs 90s grace.

Verified OK (don't re-check): STOMP events carry StompPrincipal (Spring 6.1.14 stompAuthentications), CAS UPDATE + afterCommit event, updated_at via DB trigger, AppUserRepository intentionally non-TenantAware (ccapp BYPASSRLS, V123).

**How to apply:** when BE-210 follow-ups land, re-check the Redis recovery guard and STOMP e2e test first. See [[recurring-antipatterns]].
