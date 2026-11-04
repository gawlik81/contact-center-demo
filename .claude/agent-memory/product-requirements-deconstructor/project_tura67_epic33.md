---
name: project-tura67-epic33
description: EPIC-33 Skalowanie poziome i pojemność (tura 67, 2026-11-04) — 30 ticketów ⬜, brak prefiksu INF-, liczniki 374/451
metadata:
  type: project
---

EPIC-33 założony 2026-11-04: DB-090..093, BE-171..195, FE-155 (wszystko ⬜); liczniki PROGRESS 374 ✅ / 70 ⬜ / 7 🚫 = 451. Następne wolne: DB-094, BE-196, FE-156.

**Why:** analiza kodu — 1 instancja ≈ 300 agentów/20 tenantów (hipoteza, do potwierdzenia w BE-184/185), cel 50×100; blokery: `enableSimpleBroker` (WebSocketConfig:69), brak ShedLock (22 `@Scheduled` w 19 klasach), stan IVR/wtyczek/cache w JVM.

**How to apply:** w repo NIE ma prefiksu `INF-` — zadania infra numerowane jako BE (Typ: Infrastructure). Fazy: P0 = Must Have (ShedLock, WS relay, stan), P1 = Should, P2 = Could (repliki, komórki). Następny ADR: ADR-14 (WS) i ADR-15 (komórki). `set_tenant_context` jest tx-local (V023) — kluczowe dla PgBouncer (BE-181). Zob. [[project-progress-state]].
