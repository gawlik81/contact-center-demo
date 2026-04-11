---
name: BE-024 Progressive Dialer
description: Implementacja Progressive Dialer – silnik automatycznego dzwonienia kampanii wychodzących (zrealizowane 2026-04-03)
type: project
---

BE-024 Progressive Dialer ukończony 2026-04-03. 662 testów Java PASS.

**Komponenty:**
- `ProgressiveDialerService` (`domain/service/`) – `@RabbitListener` na `cc.queue.agent-status`, guard Redis SET NX `dialer:agent:{agentId}` TTL 60s, `initiateDialForAgent()` @Transactional, `fetchNextPendingContact()` FOR UPDATE SKIP LOCKED, `isInSchedule()` sprawdza harmonogram JSONB (start_date, end_date, active_days, active_hours, timezone), `@ConditionalOnProperty(dialer.enabled)`
- `DialerCallbackHandler` (`domain/service/`) – `handleNoAnswer()` (NO_ANSWER + next_attempt_at +4h), `handleAnswered()` (CONNECTED), `handleCallbackDisposition()` (tworzy ScheduledCallback), `handleCompleted()`
- `ScheduledCallback` (`domain/model/`) – encja JPA tabela `scheduled_callback` (V009), statusy: PENDING/PROCESSING/COMPLETED/CANCELLED, pola: callbackId, tenantId, campaignId, customerId, agentId, phone, firstName, lastName, scheduledAt, notes
- `ScheduledCallbackRepository` (`domain/repository/`) – extends TenantAwareRepository, findPendingByTenantId (stronicowane), countPendingByTenantId, findDueCallbacks, save, updateStatus
- `DialerController` (`api/dialer/`) – GET /api/dialer/status (aktywne kampanie + metryki PENDING/DIALING/COMPLETED), GET/POST /api/dialer/callbacks (paginacja PagedResponse)
- DTOs: `DialerStatusResponse` (record z ActiveCampaignSummary), `ScheduledCallbackResponse` (factory from()), `CreateCallbackRequest` (walidacja @Future scheduledAt)
- Migracja `V031__add_dialer_indexes.sql` – idx_campaign_contact_dialer_tenant, idx_campaign_running_tenant, idx_callback_ready

**Redis klucze:**
- `dialer:agent:{agentId}` → "locked", TTL 60s (guard race condition)
- `dialer:call:{callSid}` → "campaignContactId,campaignId,agentId,tenantId", TTL 60s
- `dialer:timeout:{callSid}` → "", TTL 30s (marker timeout)

**Harmonogram kampanii JSONB:**
```json
{"start_date":"2026-04-01","end_date":"2026-04-30","active_days":["MON","TUE"],"active_hours":{"from":"09:00","to":"17:00"},"timezone":"Europe/Warsaw"}
```
Uwaga: specyfikacja używa `days_of_week` ale implementacja odczytuje klucze `active_days` i `active_hours.from/to`.

**Why:** kampanie outbound wymagają automatycznego dzwonienia gdy agent zmienia status na AVAILABLE.

**How to apply:** przy kolejnych zadaniach związanych z kampaniami outbound i dialerem – patrz ProgressiveDialerService jako wzorzec nasłuchiwania na agent.status.changed.
