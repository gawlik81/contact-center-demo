---
name: Async threads and TenantContext
description: Serwisy domenowe (@Service) wywołane z wątków RabbitMQ nie mają TenantContext – CrossTenantAspect wykrywa to jako ERROR, ale to jest oczekiwane zachowanie.
type: project
---

Serwisy domenowe wywołane z wątków RabbitMQ (CallEventEnricher, RecordingService, SupervisorMetricsService) nie mają TenantContext – to oczekiwane. CrossTenantAspect był poprawiony (2026-03-22): teraz sprawdza RequestContextHolder.getRequestAttributes() != null żeby odróżnić wątek HTTP od async. Wątki async dostają TRACE log zamiast ERROR.

**Why:** Wątki RabbitMQ nie przechodzą przez TenantFilter, więc TenantContext nie jest ustawiony. Serwisy te przyjmują tenantId jako jawny parametr (setTenantContextInDb(UUID) zamiast setTenantContextInDb()).

**How to apply:** Gdy dodajesz nowy serwis wywoływany z RabbitMQ/@Scheduled, upewnij się że NIE wywołuje metod które odczytują TenantContext.getTenantId() bez parametru. Używaj setTenantContextInDb(tenantId) z jawnym UUID.
