---
name: project_customer_create_customfields_gdpr
description: Rozszerzenie modala "Nowy klient" o customFields i gdprConsent, dorównując do CustomerEditComponent
metadata:
  type: project
---

Naprawiono zgłoszony przez użytkownika brak parytetu: modal "Nowy klient"
(`CustomerCreateModalComponent`) miał tylko `firstName/lastName/externalId/phones/emails`,
mimo że `CustomerEditComponent` (patrz [[project_customer_edit_customfields]]) i backend
(`CreateCustomerRequest.java`) już w pełni wspierały `customFields` (Map<String,Object>) i
`gdprConsent` przy TWORZENIU klienta — czysty brak warstwy frontendowej, 2026-07-12
(branch `customer-refactor`).

**Zastosowany wzorzec — 1:1 skopiowany z `customer-edit.component.ts`:**
- `CustomFieldFormGroup` type + `fb.array<CustomFieldFormGroup>([])` — drugi w repo przypadek
  tego wzorca (pierwszy w customer-edit).
- `createCustomFieldGroup()`, `addCustomField()`, `removeCustomField()`, `getControl()` —
  identyczne z edycją.
- **Różnica względem edycji (świadoma, uzgodniona z userem w treści zadania):** przy
  tworzeniu nie ma istniejącego klienta do scalenia (`{...existing, ...raw}` jak w edit), więc
  `gdprConsent` jest wysyłane wprost z dwóch checkboxów, ZAWSZE (nawet `{consent_given: false,
  marketing_consent: false}`) — nie pomijane warunkowo. `customFields` też zawsze wysyłane
  (nawet `{}` gdy puste) — spójne z semantyką „pełne zastąpienie” z edycji.
- **Modal jest reużywalnym singletonem** (`customer-list.component.ts` trzyma
  `viewChild(CustomerCreateModalComponent)` i wywołuje `.open()` wielokrotnie) — dlatego
  `open()` musi jawnie robić `this.customFieldsArray.clear()` OPRÓCZ `form.reset()`, bo
  `FormGroup.reset()` NIE usuwa nadmiarowych wierszy z `FormArray`, tylko resetuje wartości
  istniejących kontrolek. Bez tego drugie otwarcie modala pokazywałoby custom fieldy z
  poprzedniej sesji tworzenia.

**Pliki zmienione:**
- `frontend/src/app/features/supervisor/pages/customers/customer-create-modal/customer-create-modal.component.ts`
- `frontend/src/app/features/supervisor/pages/customers/customer-create-modal/customer-create-modal.component.html`
  (nowe sekcje `<section class="form-section">` dla "Pola dodatkowe" i "RODO" — zauważ że
  reszta pól w tym komponencie NIE była opakowana w `.form-section`, tylko luźne `.form-field`/
  `.form-row` bezpośrednio w `__body`; nowe sekcje wprowadzają ten wzorzec pierwszy raz tutaj)
- `frontend/src/app/features/supervisor/pages/customers/customer-create-modal/customer-create-modal.component.scss`
  (dopisane `.form-section`, `.array-fields`, `.array-empty`, `.custom-field(__key/__value)`,
  `.btn-add`, `.btn-remove`, `.form-field--checkbox`, `.form-checkbox(__input/__label)` —
  skopiowane z `customer-edit.component.scss`, bo w create-modal jeszcze nie istniały)
- `frontend/src/app/features/supervisor/pages/customers/services/customer.service.ts`
  (`createCustomer` payload +`customFields?: Record<string, unknown>` +`gdprConsent?:
  Record<string, unknown>` — dokładnie taki sam kształt jak istniejący `updateCustomer`)
- `frontend/public/i18n/{pl,en,de,uk}.json` — nowe klucze pod `supervisor.customerCreate.*`
  (NIE reużyto `customerEdit.*` namespace mimo identycznej treści — osobny namespace per
  komponent to konwencja już istniejąca w repo dla `customerCreate` vs `customerEdit`):
  `customFieldsLabel`, `customFieldsKeyPlaceholder`, `customFieldsValuePlaceholder`,
  `noCustomFields`, `addCustomField`, `gdprTitle`, `consentProcessing`, `consentMarketing`.
  Uwaga: `customerEdit.*` w pl.json ma sekcję RODO z hardkodowanym polskim "RODO" (nie
  osobnym kluczem tytułu) — w customerCreate dodano `gdprTitle` jako właściwy klucz i18n
  (drobne ulepszenie, nie regresja).
- `frontend/src/app/features/supervisor/pages/customers/customer-create-modal/customer-create-modal.component.spec.ts`
  (NOWY plik — wcześniej nie istniał spec dla tego komponentu; 8 testów: create, add/remove
  custom field, `open()` czyści leftover rows, payload z customFields+gdprConsent, trim +
  filtrowanie pustych kluczy, gdprConsent zawsze wysyłane nawet gdy oba false, error path)

Weryfikacja: `npm test` 154/154 zielone (13 plików testowych), `npm run lint` 0 błędów (10
pre-istniejących warningów `no-console` w niepowiązanych plikach).

Świadomie NIE zmieniono `customer-edit.component.ts/html` (miało być tylko wzorcem, zgodnie
z poleceniem usera).
