---
name: Mock disposition – agent ownership i status QUEUED
description: Dwa warunki które razem blokowały setDisposition w mock flow: agent_id=null w contact + status QUEUED po hangup
type: feedback
---

`ContactService.setDisposition()` ma dwa niezależne guardy:
1. `isAgent && !userId.equals(contact.getAgentId())` → rzuca InvalidOperationException
2. `"QUEUED".equals(status) || "ACTIVE".equals(status)` → rzuca InvalidOperationException

W mock dev flow oba warunki były łamane:
- `agent_id` był null bo `SimulateCallRequest.agentId` jest opcjonalne i deweloper nie podawał go w Swagger UI
- status pozostawał QUEUED bo brak konsumenta który zmieniałby go na COMPLETED przy hangup

**Fix (3 pliki):**

1. `MockCallController.handleIncoming()` – gdy `request.agentId() == null`, fallback na `TenantContext.getUserId()` jako `effectiveAgentId`. Gwarantuje że `contact.agent_id = zalogowany agent`.

2. `CallSession` – dodano pole `contactId` (UUID) z `@With`. Pozwala adapterowi śledzić powiązany rekord DB per sesja.

3. `MockTelephonyAdapter.hangupCall()` – po zmianie stanu sesji na ENDED, wywołuje `contactRepository.updateContactStatusOnTelephonyEvent(contactId, tenantId, "COMPLETED", endedAt)`. Nowa metoda w `ContactRepository` używa `jdbcTemplate` z explicit tenantId (nie `assertSameTenant`) bo adapter może działać poza wątkiem HTTP.

**Why:** assertSameTenant() wymaga aktywnego TenantContext – nie można użyć standardowego update() repozytorium gdy wątek jest poza HTTP (np. scheduled hangup). Dlatego nowa metoda używa jdbcTemplate bezpośrednio z jawnym AND tenant_id = ? jako izolacja cross-tenant.

**How to apply:** Gdy mock adapter potrzebuje dostępu do DB bez TenantContext, dodaj dedykowaną metodę w repo z jdbcTemplate + explicit tenantId zamiast przez EntityManager/assertSameTenant.
