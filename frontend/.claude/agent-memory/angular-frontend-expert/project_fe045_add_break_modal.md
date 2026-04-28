---
name: FE-045 AddBreakModal implementation
description: AddBreakModalComponent zaimplementowany — lokalizacja, API, wzorzec, znane błędy lint
type: project
---

`AddBreakModalComponent` zaimplementowany w `src/app/features/agent/components/add-break-modal/`.

**Why:** Zadanie FE-045 — modal dodawania/edycji zaplanowanej przerwy agenta, zintegrowany z `AgentCalendarComponent`.

**How to apply:** Przy rozbudowie kalendarza agenta — komponent obsługuje dwa tryby (ADD gdy `existingBreak = null`, EDIT gdy przekazany obiekt `CalendarBreak`). Inline confirm dialog dla anulowania przerwy (nie osobny modal).

Kluczowe decyzje:
- `endAfterStartValidator` jako standalone function (nie metoda klasy) — cross-field walidacja na poziomie `FormGroup`
- `loadData()` w `AgentCalendarComponent` jest metodą prywatną — `onBreakSaved()` i `onBreakCancelled()` muszą ją wywoływać przez `this` (dostęp przez nowe publiczne metody w tym samym komponencie)
- Błąd lint `click-events-have-key-events` na `<dialog (click)="onBackdropClick">` jest pre-istniejący w projekcie (identyczny w `reschedule-callback-modal`) — nie naprawiać, to znany pattern backdrop-dismiss
- Kolor headerów modali przerw: `#166534` → `#15803d` (zielony) vs `#1565c0` → `#0d47a1` (niebieski) dla callbacków
