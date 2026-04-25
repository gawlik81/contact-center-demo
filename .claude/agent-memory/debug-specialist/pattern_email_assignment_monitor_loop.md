---
name: ContactAssignmentMonitor — nieskończona pętla dla EMAIL (brak accept)
description: Monitor re-kolejkuje kontakty EMAIL bo nie ma endpointu potwierdzenia przyjęcia; fix: POST /api/contacts/{id}/accept ASSIGNED→ACTIVE
type: project
---

ContactAssignmentMonitor działa dla wszystkich kanałów (PHONE, EMAIL, CHAT) przez jedno zapytanie `findStaleAssignedContacts` bez filtrowania po kanale. Dla PHONE poprawny flow to: WS event `CONTACT_ASSIGNED` → agent odbiera → status zmienia się przy hangup/answer. Dla EMAIL/CHAT agent pobiera kontakt przez HTTP polling — nigdy nie ma mechanizmu zmiany ASSIGNED→ACTIVE, przez co monitor zapętla się.

**Brakujące elementy przed poprawką:**
- Brak endpointu `POST /api/contacts/{id}/accept` (ASSIGNED→ACTIVE)
- Brak endpointu `POST /api/contacts/{id}/abandon` (ASSIGNED/ACTIVE→ABANDONED)
- `EmailContactComponent.cancelReply()` emitował tylko `replySent.emit()` bez HTTP call
- `AgentRecoveryService.recoverAfterReconnect()` obsługiwał tylko kanał PHONE

**Poprawka (2026-04-25):**
- `ContactService.acceptContact()` + `ContactService.abandonContact()` — nowe metody serwisu
- `ContactController` — endpointy `POST /{id}/accept` i `POST /{id}/abandon`
- `EmailContactComponent.ngOnInit()` — fire-and-forget call do `/accept`
- `EmailContactComponent.cancelReply()` — call do `/abandon` przed `replySent.emit()`
- `AgentRecoveryService` — obsługa EMAIL/CHAT: otwiera zakładkę i wywołuje `/accept`
- `ContactService` (frontend) — dwie nowe metody `acceptContact()` i `abandonContact()`

**Why:** Monitor używał tylko statusu ASSIGNED jako kryterium "nieodebrany" — nie rozróżniał kanałów. Jedynym sposobem zatrzymania monitora jest przejście do ACTIVE lub stanu końcowego.

**How to apply:** Przy każdym nowym kanale asynchronicznym (EMAIL, CHAT, SOCIAL) — upewnij się że komponent wywołuje `accept` przy otwarciu zakładki i `abandon` przy zamknięciu bez akcji.
