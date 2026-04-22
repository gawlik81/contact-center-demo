---
name: WebSocket Resilience – ASSIGNED Status (Opcja B)
description: Mechanizm odporności na utratę WS przy przydzielaniu połączeń – nowy status ASSIGNED + ContactAssignmentMonitor
type: project
---

Zaimplementowano "Opcję B" odporności na utratę WebSocket przy routingu połączeń (V046, 2026-04-22).

**Why:** Gdy RoutingService od razu ustawiał ACTIVE i wysyłał CONTACT_ASSIGNED przez WS, utrata WS powodowała ABANDONED.

**Kluczowe zmiany:**
- `V046__add_assigned_contact_status.sql` — nowy status ASSIGNED w CHECK constraint tabeli contact
- `RoutingService.assignContactToAgent()` — teraz ustawia `ASSIGNED` (nie `ACTIVE`)
- `TwilioTelephonyAdapter.dialAgentIntoConference()` — po udanym Call.creator().create() ustawia `ACTIVE` (ASSIGNED→ACTIVE)
- `TwilioWebhookController.handleConferenceStatusCallback()` — `ASSIGNED` traktowany jak QUEUED/ACTIVE przy ABANDONED
- `ContactRepository` — dwie nowe metody: `findStaleAssignedContacts(Instant)` (cross-tenant, bez RLS) i `findAssignedContactForAgent(agentId, tenantId)`
- `ContactAssignmentMonitor` — @Component @Scheduled(fixedDelay=10_000), max 3 retry w Redis (klucz `cc:assign-retry:{contactId}`, TTL 120s), po wyczerpaniu reset do QUEUED + `cc.events` / `contact.queued`
- `AgentSelfController` — `GET /api/agent/me/assigned-contact` zwraca `AssignedContactResponse` (204 gdy brak)
- `AssignedContactResponse` — nowe DTO w `api/contact/dto/`

**How to apply:**
- `ContactQueuedMessage` zawsze przez `rabbitTemplate.convertAndSend(EXCHANGE_EVENTS, "contact.queued", msg)` — nigdy bezpośrednio do kolejki
- `findStaleAssignedContacts` nie wywołuje `setTenantContextInDb` — cross-tenant dla schedulera
- `@EnableScheduling` jest już w `ContactCenterApplication`
