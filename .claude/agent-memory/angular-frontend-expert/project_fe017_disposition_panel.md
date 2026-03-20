---
name: Disposition panel after contact (FE-017)
description: DispositionPanelComponent – modal ACW po zakończeniu kontaktu, ContactService, ContactTabStore.wrappingTab
type: project
---

Po zakończeniu połączenia agentowi wyświetla się panel dyspozycji (After Contact Work).

**Nowe pliki:**
- `features/agent/models/contact.model.ts` – `ContactResponse` i `SetDispositionRequest` (przeniesione z contact.service.ts, separacja warstw)
- `features/agent/services/contact.service.ts` – ContactService, re-eksportuje typy z contact.model.ts, metoda `setDisposition(contactId, code, notes): Observable<ContactResponse>` → PATCH `/api/contacts/{id}/disposition`
- `features/agent/models/disposition.model.ts` – DispositionCode interface + DISPOSITION_CODES (6 hardcoded: SALE, NO_INTEREST, CALLBACK, WRONG_NUMBER, TECH_ISSUE, OTHER)
- `features/agent/components/disposition-panel/disposition-panel.component.ts` – standalone, signal inputs (`contactId` required, `customerName`), output `saved`, ACW timer (setInterval), `canSave = selectedCode.length > 0 && !isSaving`

**CR-FRONTEND poprawki (2026-03-20):**
- Dialog otwierany przez `showModal()` (viewChild.required #dialogEl) zamiast atrybutu `open` – pułapka fokusa, prawidłowy aria-modal
- Kolejność operatorów: `catchError` PRZED `takeUntilDestroyed` – zapobiega połknięciu błędu przy destroy
- `AgentStatusService.changeStatus()` zwraca `Observable<void>` zamiast void; `saved.emit()` wywoływane dopiero po `next()` changeStatus, nie fire-and-forget
- `onCodeChange(event: Event)` zamiast `$any($event.target).value` – typowany handler
- `aria-invalid='true'` → `border-color: #c62828` (był #bdbdbd – brak sygnału wizualnego)

**Modyfikacje:**
- `contact-tab.model.ts` – dodano `WrappingContactTab` interface (pomocniczy typ)
- `contact-tab.store.ts` – dodano `wrappingTab = computed()` (aktywna zakładka ze statusem WRAPPING) + `markAsWrapping(id)` metoda
- `agent-desktop.component.ts` – import `DispositionPanelComponent`, `effect()` na `softphoneService.session()` (gdy state=ENDED → `markAsWrapping` dla PHONE tab), `onDispositionSaved()` zamyka zakładkę
- `agent-desktop.component.html` – `@if (wrappingTab())` renderuje `<app-disposition-panel>`

**Przepływ:**
1. WS event CALL_INCOMING → tab otwiera się ze statusem ACTIVE
2. Agent kończy rozmowę → SoftphoneService.session().state = 'ENDED'
3. Effect w AgentDesktop → `tabStore.markAsWrapping(phoneTabId)` → tab.status = 'WRAPPING'
4. `wrappingTab()` computed → nie null → panel wyświetlany jako overlay
5. Agent wybiera kod i klika "Zapisz" → PATCH /api/contacts/{id}/disposition → changeStatus('AVAILABLE') → `saved.emit()`
6. AgentDesktop.onDispositionSaved() → `tabStore.closeTab(id)`

**Wzorzec effect():** zadeklarowany jako field initializer (`private readonly softphoneEndedEffect = effect(() => {...})`), automatycznie powiązany z DestroyRef komponentu.

**Why:** BE-027 implementuje PATCH /api/contacts/{id}/disposition; panel jest prosty overlay bez Angular Material (zgodnie z konwencją projektu – native dialog element).

**How to apply:** Jeśli implementujesz inne typy kontaktów (CHAT/EMAIL) z ACW – możesz podłączyć się pod `markAsWrapping` z odpowiednich event handlerów w ngOnInit w AgentDesktop.
