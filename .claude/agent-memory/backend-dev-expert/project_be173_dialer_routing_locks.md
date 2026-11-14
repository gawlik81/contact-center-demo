---
name: be173-dialer-routing-locks
description: EPIC-33 BE-173 - blokada per kampania (dialer, advisory xact) i per kolejka (routing, ShedLock), granice transakcji, pulapki
metadata:
  type: project
---

Dialer: `ProgressiveDialerServiceImpl#initiateDialForAgent` NIE jest juz @Transactional. Claim = krotka tx (`claimNextContact`, package-private): agent nadal wolny (AVAILABLE + brak PHONE contact w OccupiedSlot.AGENT_LOAD) -> `pg_try_advisory_xact_lock(NAMESPACE_DIALER_CAMPAIGN, hashtext(campaignId))` -> SKIP LOCKED -> warunkowy DIALING (rows==1). Commit zwalnia lock PRZED telefonia. Ograniczone oczekiwanie `dialer.campaign-lock.wait-ms` (500) - bez niego agenci z kolizji gubili wyzwalacz do nastepnego pollu (30 s) - test (d) to wylapal.
Routing: `RoutingQueueLock` (ShedLock `RoutingQueue_<queueId>`), pass pogrupowany po kolejkach (faza 1 bez locka, faza 2 pod lockiem).
Klucze advisory: forma 2-int ma ODRebna przestrzen od bigint (hashtextextended uzywany w repo); rejestr w `infrastructure/lock/AdvisoryLockKeys`. Metryka `scheduler.lock.skipped{type}` (LockSkipMetrics), bez tagow tenant/campaign/queue.

**Why:** pętle dialera/routingu na wielu instancjach; advisory xact pasuje do krotkiej tx claim, ShedLock do przebiegu bez jednej tx (BE-156 lock per agent w assignIfQueued).
**How to apply:** po claim poza tx kazdy zapis wymaga wlasnej tx (set_tenant_context jest LOCAL -> bez tx RLS potnie UPDATE po cichu). Konstruktory RoutingServiceImpl/ProgressiveDialerServiceImpl rozszerzone (3 testy routingu budowane przez `new`/refleksje).
Otwarte: osierocone DIALING po crashu JVM miedzy claim a callem (brak reapera), agent wspolny miedzy kolejkami (blokada per kolejka nie zamyka cross-queue), brak kolumny max linii kampanii.
