---
name: BE-050 AgentBreak REST API
description: pakiet domain/agentbreak i api/agentbreak; serwis + kontroler + DTO + testy; BE-050 zaimplementowane
type: project
---

BE-050 zaimplementowane: pełne REST API zarządzania przerwami agenta.

**Pliki produkcyjne:**
- `api/agentbreak/dto/AgentBreakResponse.java` — record DTO
- `api/agentbreak/dto/CreateAgentBreakRequest.java` — record z @NotNull
- `api/agentbreak/dto/UpdateAgentBreakRequest.java` — record z @NotNull
- `domain/agentbreak/AgentBreakService.java` — serwis z logiką biznesową
- `api/agentbreak/AgentBreakController.java` — kontroler REST

**Plik testowy:**
- `test/.../domain/agentbreak/AgentBreakServiceTest.java` — 11 scenariuszy Mockito

**Logika właścicielska:** sprawdzana przez porównanie `agentBreak.getAgentId() != agentId` → rzuca `CrossTenantAccessException(resourceId, tenantId)` (konstruktor 2-arg).

**Domyślny zakres dat:** brak from/to → bieżący tydzień (poniedziałek 00:00 UTC – niedziela 23:59:59 UTC) przez `TemporalAdjusters`.

**Why:** Agenci potrzebują UI do planowania przerw; backend musi je udostępniać przez REST API z ochroną właścicielską.

**How to apply:** Wzorzec do kolejnych endpointów per-agent: @PreAuthorize("hasRole('AGENT')"), agentId z TenantContext.getUserId(), asercja właściciela przed mutacją.
