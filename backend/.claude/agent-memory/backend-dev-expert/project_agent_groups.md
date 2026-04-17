---
name: BE-043 AgentGroup domain package
description: Zrealizowano BE-043 – pakiet domain/agentgroup z encją i repozytorium grup agentów
type: project
---

Pakiet `com.contactcenter.domain.agentgroup` zawiera:
- `AgentGroup` – encja JPA mapująca tabelę `agent_group` (V042)
- `AgentGroupRepository` – natywny SQL, extends TenantAwareRepository, CRUD + membership management

**Why:** Grupy agentów są wymagane przez routing kolejek (queue.all_agents=FALSE) – BE-043 to warstwa danych, BE-044 doda serwis i kontroler.

**How to apply:** Przy BE-044 korzystać z `AgentGroupRepository` do CRUD. Tabele DB: `agent_group`, `agent_group_member`, `queue_agent_group` (V042, V043).
