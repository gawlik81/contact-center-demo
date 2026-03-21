---
name: Mock telephony contactId pattern
description: MockTelephonyAdapter tworzy rekord contact w DB i wysyła UUID jako contactId w WebSocket evencie; nie używa już mock-N jako contactId
type: feedback
---

MockTelephonyAdapter (i docelowo real webhook handler) MUSI tworzyć rekord w tabeli `contact` PRZED opublikowaniem `CALL_INCOMING` na RabbitMQ.

**Why:** Frontend używa pola `contactId` z `CALL_INCOMING` payload do wywołania `PATCH /api/contacts/{contactId}/disposition`. Gdy `contactId` był surowym `callId` w formacie `mock-N`, endpoint zwracał 422 bo `parseContactId()` nie mogło sparsować "mock-1" jako UUID.

**How to apply:**
- `MockTelephonyAdapter.persistMockContact()` tworzy `Contact` z `UUID.randomUUID()` i wywołuje `contactRepository.insert()` przed publikacją eventu. `sip_call_id` w `channelMetadata` zachowuje oryginalne "mock-N" dla diagnostyki.
- `TelephonyEventPublisher.publishIncoming()` ma sygnaturę `(callId, contactId, tenantId, agentId, from, to)` – `contactId` to UUID z DB (może być null przy błędzie DB, wtedy fallback na callId w `WebSocketEvent.CallIncomingPayload.from()`).
- `CallEvent` ma pole `UUID contactId` obok `String callId`.
- `CallEventEnricher` przepisuje `contactId` przy budowaniu enriched event (nie wolno pominąć!).
- `WebSocketEvent.CallIncomingPayload.from()` używa `callEvent.getContactId()` gdy != null, fallback na `callEvent.getCallId()`.
- W testach `MockTelephonyAdapterTest` mockuj `ContactRepository.insert()` przez `when(...).thenAnswer(inv -> inv.getArgument(0))` + `@MockitoSettings(LENIENT)` bo niektóre testy nie wywołują insert (UnnecessaryStubbing).
- `ContactController.parseContactId()` pozostaje jako fallback dla starych eventów bez `contactId` w DB.
