---
name: Manual dialer call button (FE-027)
description: Przycisk "Zadzwoń" na liście rekordów kampanii MANUAL – DialerService + rozszerzenie CampaignContactsComponent
type: project
---

Dodano przycisk "Zadzwoń" w `CampaignContactsComponent` dla rekordów PENDING w kampaniach MANUAL.

**Pliki zmienione:**
- `campaign.model.ts` – dodano `'DIALING'` do `CampaignContactStatus`, nowy interfejs `ManualCallResponse`
- `dialer.service.ts` (nowy) – `DialerService.manualCall(campaignId, recordId)` → `POST /api/dialer/manual/call`
- `campaign-contacts.component.ts` – inject `DialerService`, `AuthService`, `NotificationService`; signal `callingRecordId`, metody `isAgent()`, `isManualCampaign()`, `callRecord()`
- `campaign-contacts.component.html` – warunkowa kolumna "Akcja" z przyciskiem i spinnerem (tylko AGENT + MANUAL + PENDING)
- `campaign-contacts.component.scss` – style `.btn-call`, `.btn-call__spinner`, `@keyframes spin`, `.col-action`, `contact-status-badge--dialing`

**Kluczowe decyzje:**
- Jeden `callingRecordId signal` blokuje całą tabelę podczas żądania (zapobiega podwójnym kliknięciom)
- Optymistyczna aktualizacja statusu rekordu na `DIALING` po sukcesie (bez przeładowania całej listy)
- `finalize()` zawsze zeruje `callingRecordId` (po sukcesie i błędzie)
- Błąd 409 – toast z `err.error?.message` (wiadomość z backendu); 404 – stały komunikat
- Widoczność przycisku: `isAgent() && isManualCampaign() && contact.status === 'PENDING'`

**Why:** Wymaganie FE-027: agent może ręcznie inicjować połączenia dla kampanii MANUAL.
**How to apply:** Przy rozbudowie dialera sprawdzaj `DialerService` zamiast dodawać do `CampaignService`.
