---
name: Inbound Callback Endpoint BE-040
description: POST /api/contacts/{contactId}/callback – planowanie oddzwonienia z rozmowy przychodzącej
type: project
---

Endpoint `POST /api/contacts/{contactId}/callback` dodany do `DialerController` z pełną bezwzględną ścieżką `@PostMapping("/api/contacts/{contactId}/callback")` (klasa ma `@RequestMapping("/api/dialer")`).

**Encja ScheduledCallback** – dodano pola `sourceType` (VARCHAR 30, domyślnie `CAMPAIGN_CALLBACK`) i `originContactId` (UUID nullable). Migracja V037 już dodała kolumny w DB.

**DTO** – `CreateInboundCallbackRequest` record z `@NotBlank phone`, `@NotNull @Future scheduledAt`, polami nullable i `agentId` ignorowanym dla AGENT.

**ScheduledCallbackResponse** – rozszerzone o `sourceType` i `originContactId`.

**Logika**: 404 jeśli kontakt nie istnieje, 403 jeśli AGENT próbuje zaplanować callback z cudzej rozmowy (contact.agentId != null i != jwtAgentId), 201 gdy contact.agentId == null (zezwól – DB jeszcze nie zaktualizowana).

**Why:** Feature BE-040 – agent może zaplanować callback podczas rozmowy przychodzącej bez powiązania z kampanią.

**How to apply:** Przy podobnych endpointach cross-resource (kontakt→callback) – ścieżka absolutna w `@PostMapping` gdy kontroler ma inne `@RequestMapping` bazowe. Unikaj `ServletUriComponentsBuilder.fromCurrentContextPath()` w metodach testowanych przez testy jednostkowe – brak kontekstu HTTP.
