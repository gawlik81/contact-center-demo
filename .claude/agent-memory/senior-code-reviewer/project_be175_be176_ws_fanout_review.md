---
name: be175-be176-ws-fanout-review
description: BE-175/BE-176 Redis WS fan-out review (2026-11-14), 4/5 approve-with-fixes; open items to re-check
metadata:
  type: project
---

BE-175/176 (ADR-14 option B, Redis pub/sub -> local SimpleBroker) reviewed 2026-11-14, 4/5. Written to CR-BACKEND.md "Review BE-175 + BE-176".

Open must/should-fix to re-check on next touch: M1 RoutingServiceImpl.pollAvailableAgents (no @SchedulerLock) broadcasts QUEUE_AGENT_UPDATE from every instance, now N duplicates per session; S1 shared instance-id env silently drops all remote events; S3 user-topic envelope has no tenant check (no HMAC); S5 shutdownNow without drain; docs wrongly say brokerChannel is multi-threaded (javap 6.1.14: synchronous ExecutorSubscribableChannel unless configureBrokerChannel; reorder is on clientOutboundChannel; suggest setPreservePublishOrder(true)); compose has no client-output-buffer-limit pubsub.

**Why:** fan-out turns every per-instance publisher into a cluster-wide publisher, so any @Scheduled without ShedLock that calls WebSocketEventBroadcaster now duplicates.
**How to apply:** when reviewing new broadcaster callers, check they run once per cluster (ShedLock or competing-consumer Rabbit queue).
