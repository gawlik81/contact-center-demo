---
name: BE-021 Wait Time Estimation
description: Serwis EWT (Estimated Wait Time) – szacowanie czasu oczekiwania w kolejkach, broadcast WebSocket co 30s
type: project
---

BE-021 zaimplementowany. Serwis oblicza EWT per kolejka per tenant i broadcastuje do supervisorów przez WebSocket.

**Why:** Supervisorzy potrzebują RT informacji o czasie oczekiwania na dashboardzie do zarządzania kolejkami.

**How to apply:** Wzorzec EWT to formuła `ceil(waiting/agents * avgHandleTime)`. `waiting_count` ma priorytet – EWT=0 gdy brak oczekujących (nawet gdy brak agentów). MAX_VALUE tylko gdy `waiting > 0` i `agents == 0`.

## Kluczowe klasy

- `WaitTimeEstimationService` – `@Service`, `@Scheduled(fixedRate=30_000)`, iteruje po aktywnych tenantach i kolejkach
- `QueueWaitUpdatePayload` – rekord DTO eventu QUEUE_WAIT_UPDATE (pakiet `api/queue`)
- `QueueStatsResponse` – DTO dla REST endpoint GET /api/queues/{id}/stats
- `ContactRepository.getAvgHandleTimeSeconds(tenantId, queueId)` – natywny SQL AVG, COALESCE fallback 300s
- `ContactRepository.countWaitingByQueueId(tenantId, queueId)` – COUNT(status='QUEUED')

## Uwagi implementacyjne

- Scheduled thread nie ma TenantContext → metody ContactRepository NIE wywołują `setTenantContextInDb()` – izolacja przez jawne `tenant_id` w SQL
- Redis SCAN pattern: `session:agent:*`, pole `status=AVAILABLE` + weryfikacja `tenantId` (cross-tenant guard)
- `DEFAULT_AVG_HANDLE_TIME_SECONDS = 300.0` – stała `public static final` dostępna z testów
- Endpoint REST `GET /api/queues/{id}/stats` dodany do `QueueController` – deleguje do `WaitTimeEstimationService.getQueueStats()`
- Testy: 19 testów w `WaitTimeEstimationServiceTest` – 5 klas `@Nested`
