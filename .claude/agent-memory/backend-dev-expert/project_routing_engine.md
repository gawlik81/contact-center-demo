---
name: BE-019 Routing Engine
description: Implementacja silnika routingu kontaktów (skill-based, round-robin, sticky agent) – pakiet com.contactcenter.domain.routing
type: project
---

BE-019 zaimplementowany. Routing engine w pakiecie `com.contactcenter.domain.routing`.

**Why:** Zadanie BE-019 – silnik routingu kontaktów do agentów w Contact Center z trzema strategiami.

**How to apply:** Przy rozwijaniu funkcjonalności routingu lub powiązanych serwisów – patrz ten pakiet.

## Kluczowe klasy
- `RoutingEngine` – interfejs
- `DefaultRoutingEngine` – implementacja (@Primary), strategie: STICKY → SKILL_BASED → ROUND_ROBIN → FIRST_AVAILABLE
- `RoutingService` – orkiestracja, @RabbitListener na `cc.queue.contact-routing`
- `RoutingRequest` / `RoutingResult` – rekordy żądania i wyniku
- `AgentSessionData` – dane sesji agenta z Redis
- `ContactAssignedEvent` / `ContactQueuedMessage` – eventy RabbitMQ

## Redis
- Agenci: `session:agent:{userId}` → Map{tenantId, userId, status} (format z UserService)
- Round-robin counter: `routing:rr:{queueId}` → INCR atomowy
- SCAN pattern: `session:agent:*` z count=200

## RabbitMQ
- Exchange `cc.events`, queue `cc.queue.contact-routing` (już istniała)
- Routing key `contact.queued` → listener w RoutingService
- Publikuje: `contact.assigned` (ContactAssignedEvent), `contact.queued` (ContactQueuedMessage)

## AppUserRepository – nowa metoda
- `countActiveContactsByAgentId(agentId, tenantId)` – zlicza QUEUED/ACTIVE/ON_HOLD

## Podejście testowe
- Spy na DefaultRoutingEngine + stubowanie `scanAvailableAgents()` (public)
- Unikamy mockowania niskopoziomowego Redis SCAN (Cursor/ScanOptions) bo kruche
