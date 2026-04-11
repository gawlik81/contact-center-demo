---
name: ScheduledCallbackExecutor BE-038
description: Scheduler oddzwonień @Scheduled fixedDelay, atomowa ochrona przed double-processing przez updateStatusIfPending WHERE status='PENDING', brak TenantContext w wątku Spring Scheduling
type: project
---

`ScheduledCallbackExecutor` w `domain/service/` – cykliczne oddzwanianie dla wszystkich aktywnych tenantów.

- `@Scheduled(fixedDelayString = "${dialer.callback-executor.interval-ms:60000}")` – fixedDelay zamiast fixedRate (unika nakładania cykli)
- `@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)` – wyłączany przez `DIALER_ENABLED=false`
- TenantContext: `TenantContext.setTenantId(tenant.getId())` na początku per-tenant, `TenantContext.clear()` w `finally` – brak kontekstu HTTP w wątku schedulera
- Ochrona przed double-processing: `ScheduledCallbackRepository.updateStatusIfPending(callbackId, tenantId, "PROCESSING")` – atomowy UPDATE WHERE status='PENDING'; wynik 0 → skip
- Flow: PENDING → PROCESSING (przez updateStatusIfPending) → initiateCall → COMPLETED lub FAILED
- `telephonyAdapter.initiateCall(tenantId, fromNumber, phone, agentId, null)` – queueId=null dla callbacków niekampanijnych
- fromNumber: `tenant.getTwilioPhoneNumber()` lub fallback `${telephony.outbound-number}`
- Nowa metoda w `ScheduledCallbackRepository`: `updateStatusIfPending(callbackId, tenantId, newStatus)` – zwraca int (0 lub 1)
- Konfiguracja w `application.yml`: `dialer.callback-executor.interval-ms` i `dialer.callback-executor.batch-size`

**Why:** BE-038 – oddzwaniania zaplanowane przez agenta (disposition CALLBACK) muszą być automatycznie realizowane gdy `scheduledAt <= NOW()`.

**How to apply:** Przy rozszerzaniu logiki dialera pamiętać że scheduler NIE ma HTTP TenantContext – zawsze ustawiać ręcznie przed operacjami DB.
