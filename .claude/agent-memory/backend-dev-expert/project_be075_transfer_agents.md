---
name: project_be075_transfer_agents
description: BE-075 Transfer Agents endpoint – GET /api/telephony/transfer/agents, serwis i repozytorium
metadata:
  type: project
---

BE-075: Endpoint zwracający agentów dostępnych do transferu połączenia.

**Why:** Funkcjonalność transferu rozmowy wymaga listy agentów do których można przekierować aktywne połączenie.

**How to apply:** Wzorzec batch-query dla nazw kolejek — jedno zapytanie UNION zamiast N+1.

## Pliki

- `api/telephony/dto/TransferAgentResponse.java` – record DTO (agentId, firstName, lastName, status, queueNames)
- `api/telephony/TransferController.java` – GET /api/telephony/transfer/agents, @PreAuthorize AGENT/SUPERVISOR/ADMIN
- `domain/service/TransferService.java` – logika filtrowania i sortowania (OFFLINE wykluczony, excludeUserId, AGENT role only)
- `domain/repository/TransferAgentQueueRepository.java` – extends TenantAwareRepository, UNION 3 źródeł: queue_agent + queue_agent_group→agent_group_member + all_agents=TRUE

## Kluczowe decyzje

- SecurityConfig nie wymaga zmian – `anyRequest().authenticated()` obejmuje endpoint
- `AppUserRepository.findAllByTenantIdAndDeletedFalse(tenantId, Pageable.unpaged())` do pobrania kandydatów
- Nazwy kolejek przez `TransferAgentQueueRepository.findQueueNamesByAgentIds()` – jedno zbiorcze SQL UNION
- Parametr listy UUID przez PostgreSQL array literal `{uuid1,...}` + `CAST(:agentIds AS uuid[])` w native query
- Sortowanie: AVAILABLE=0, BUSY=1, AFTER_CONTACT=2, ACTIVE=3, BREAK=4, INACTIVE=5, nieznane=99; dalej lastName, firstName
