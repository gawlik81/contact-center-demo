---
name: epic25-campaign-assignment
description: BE-079 do BE-085 — trójpoziomowe przypisanie agentów do kampanii, zmiana queueId→campaignId w TelephonyAdapter, historia prób
metadata:
  type: project
---

Implementacja EPIC-25 Phase 2 (branch campaign-refactor):

**BE-079** — `CreateCampaignRequest.queueId` stało się nullable (usunięto `@NotNull`), usunięto `validateQueue()` i `QueueRepository` z `CampaignService`.

**BE-080** — Nowe pliki:
- `CampaignAssignmentRepository` (analogiczny do `QueueAssignmentRepository`, tabele `campaign_agent`, `campaign_agent_group`)
- `CampaignAssignmentService` (wzorowany na `QueueAssignmentService`)
- `CampaignAssignmentController` GET/PUT `/api/campaigns/{id}/assignment`
- DTOs: `CampaignAssignmentResponse`, `UpdateCampaignAssignmentRequest`

**BE-082** — `TelephonyAdapter.initiateCall()` 5. parametr zmieniony z `queueId` → `campaignId`. Efekt: `contact.campaign_id` jest ustawiany dla kontaktów wychodzących z dialera.

**BE-081** — `ProgressiveDialerService.agentHasRequiredSkills()` zastąpiony `isAgentEligibleForCampaign()` korzystającym z `CampaignAssignmentRepository.resolveEligibleAgentIds()`. Usunięto `QueueRepository` ze serwisu.

**BE-083** — Guard w `ContactService.initiateTransfer()`: OUTBOUND + targetType=QUEUE → `InvalidOperationException` (HTTP 400).

**BE-084** — `DialerController.getManualCampaignRecords()` filtruje kampanie po przypisaniu agenta przez `CampaignAssignmentRepository.resolveEligibleAgentIds()`.

**BE-085** — `contact.campaign_contact_record_id` (V063), zapis po `initiateCall()` w dialerze. Nowy endpoint `GET /api/campaigns/{id}/contacts/{recordId}/attempts`. `CampaignContactResponse` ma nowe pole `lastContactId`.

**Uwaga:** `ContactServiceTest` (13 testów) failuje preistniejąco z powodu brakującego `AppUserRepository` w mock setup — niezwiązane z tymi zmianami.

**Why:** Przypisanie agentów do kampanii umożliwia segmentację agentów (np. różne kampanie dla różnych zespołów) bez dependencji od kolejek inbound.

**How to apply:** Przy rozbudowie dialera zawsze sprawdzaj `campaign.isAllAgents()` przed wywołaniem `resolveEligibleAgentIds()`.
