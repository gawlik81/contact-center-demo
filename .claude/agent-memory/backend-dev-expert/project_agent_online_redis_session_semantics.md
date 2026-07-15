---
name: project_agent_online_redis_session_semantics
description: Definicja "agent online" i zachowanie klucza Redis session:agent:{userId} przy wylogowaniu — kluczowe przy każdej zmianie liczącej agentów online
type: project
---

Klucz Redis `session:agent:{userId}` (zapisywany WYŁĄCZNIE przez `UserServiceImpl`, zawsze jako
`Map<String,String>` z polami `tenantId`/`userId`/`status`/`breakStartedAt`) **NIE jest usuwany
przy wylogowaniu agenta** — wartość jest aktualizowana in-place na `status=OFFLINE` i żyje do
wygaśnięcia TTL (~8h, `REDIS_AGENT_STATUS_TTL`). To jest ZAMIERZONE zachowanie (sesja żyje, tylko
zmienia status) — nie zmieniać tego mechanizmu.

Ustalona, udokumentowana w Javadoc definicja "agent online" (`TenantMetrics.agentsOnline()`,
`TenantDetailMetrics.agentsOnline()`, klasowy Javadoc `AdminMetricsService`): status
**AVAILABLE, BUSY lub AFTER_CONTACT**. `BREAK` jest CELOWO wykluczony (zalogowany, ale
niedostępny) — ta sama konwencja co w `SupervisorMetricsService` (BREAK nigdy nie liczy się do
`activeCalls`/`availableAgents`). `OFFLINE`/`INACTIVE` nigdy nie są online.

**Why:** 2026-07-14 znaleziono i naprawiono realny bug w
`AdminMetricsServiceImpl.countOnlineAgentsForTenant()` — metoda sprawdzała TYLKO `tenantId`
w sesji Redis, w ogóle nie sprawdzając `status`, mimo że Javadoc już opisywał poprawną definicję.
Efekt: wylogowany agent (status=OFFLINE w tym samym kluczu Redis) był liczony jako online przez
cały pozostały czas TTL (do ~8h) — widoczne na dashboardzie ADMIN. Naprawiono dodając stałą
`ONLINE_AGENT_STATUSES = Set.of("AVAILABLE","BUSY","AFTER_CONTACT")` i filtr w pętli. Przy okazji
usunięto martwy fallback branch dla formatu "plain String" (zweryfikowano: jedyny writer to
`UserServiceImpl`, zawsze Map — branch nigdy nie miał producenta i nie pozwalał sprawdzić statusu).

**How to apply:** Każda przyszła logika licząca/agregująca "agentów online" na podstawie SCAN
`session:agent:*` (istnieją min. 3 inne miejsca skanujące ten sam wzorzec kluczy:
`SupervisorMetricsService`, `DefaultRoutingEngine`, `WaitTimeEstimationServiceImpl`) MUSI
filtrować po `status` z tego samego zestawu (AVAILABLE/BUSY/AFTER_CONTACT), nie tylko po
przynależności do tenanta — sam fakt istnienia klucza NIE oznacza, że agent jest zalogowany.
Odwrotnie: `SystemResourceMetrics.redisAgentSessions` (karta "Redis" w Zasobach systemowych)
CELOWO pozostaje surową liczbą kluczy (bez filtra statusu/tenanta) — to inna, świadomie szersza
metryka infrastrukturalna ("ile kluczy sesji faktycznie zajmuje Redis", capacity planning), a nie
biznesowe "agentsOnline"; udokumentowano to w `Schema#description` tego pola, żeby ktoś znów nie
uznał rozbieżności między obiema liczbami za bug.
