---
name: RoutingService — kontakty OUTBOUND z kampanii trafiają do ERROR (queueId=null)
description: persistOutboundContact nie ustawiał queueId; RoutingService ustawiał ERROR dla kontaktów OUTBOUND bez kolejki
type: project
---

Kontakty tworzone przez `TwilioTelephonyAdapter.persistOutboundContact` nie miały ustawionego `queueId`.
`RoutingService.onAgentStatusChanged` pobierał wszystkie kontakty QUEUED (bez filtra `direction`) i dla
tych bez `queueId` ustawiał status `ERROR`, blokując dalsze przetwarzanie przez dialera.

**Root cause:**
- `persistOutboundContact` nie przekazywało `campaign.getQueueId()` do buildera `Contact` — budowało kontakt bez `queueId`.
- `RoutingService.findQueuedContacts` pobierał kontakty bez filtra na `direction`.
- Brak warunku "pomiń OUTBOUND z przypisanym agentem" w pętli retry routingu.

**Fix (Opcja C — kombinacja A+B):**
1. `TelephonyAdapter.initiateCall` dostał parametr `UUID queueId` (nullable).
2. `ProgressiveDialerService` przekazuje `campaign.getQueueId()` do `initiateCall`.
3. `persistOutboundContact` ustawia `.queueId(queueId)` w builderze kontaktu.
4. `RoutingService.onAgentStatusChanged` pomija kontakty `direction=OUTBOUND` z `agentId != null`.

**Schemat DB:** Kolumna `queue_id` w tabeli `campaign` istnieje od V009 — nie była potrzebna migracja.

**Why:** Dla outbound agent jest przypisany przez dialer PRZED połączeniem — RoutingService nie powinien
podejmować próby routingu tych kontaktów (agent już znany, połączenie czeka na odebranie przez klienta).

**How to apply:** Gdy widzisz WARN `Kontakt bez queueId – kończę ze statusem ERROR` — sprawdź `direction`
i `agentId` kontaktu. OUTBOUND z agentId != null to kontakt kampanii, nie wymaga routingu.
