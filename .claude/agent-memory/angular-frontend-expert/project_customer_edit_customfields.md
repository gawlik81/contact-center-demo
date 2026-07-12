---
name: project_customer_edit_customfields
description: Dodanie edycji customFields (pól dodatkowych) do CustomerEditComponent — przedistniejący brak odkryty przy okazji customer-refactor
metadata:
  type: project
---

Naprawiono przedistniejący brak: okno "Edytuj klienta" (`CustomerEditComponent`,
`frontend/src/app/features/supervisor/pages/customers/customer-edit/`) nie pozwalało
edytować `customFields` mimo że backend (`UpdateCustomerRequest`, PATCH semantics: null =
bez zmiany) i widok szczegółów (`customer-detail.component.html`, read-only `keyvalue` pipe)
już to wspierały. Zgłoszone przez użytkownika 2026-07-05, niezwiązane z [[project_customer_external_id]]
ale odkryte przy tej samej okazji testowania.

**Wzorzec zastosowany — FormArray of FormGroups (pierwszy taki przypadek w repo):**
- Wcześniej w projekcie nie było FormArray zawierającego FormGroup (tylko FormArray<string>
  dla phones/emails, patrz [[project_fe020_customer_import.md]] i sąsiednie). Ustalony wzorzec
  typowany:
  ```typescript
  type CustomFieldFormGroup = FormGroup<{ key: FormControl<string>; value: FormControl<string> }>;
  // ...
  customFields: this.fb.array<CustomFieldFormGroup>([]),
  ```
  `fb.array<T>([])` gdzie T jest już `FormGroup<...>` (a nie primitive) działa poprawnie z
  Angular typed forms (ɵElement passthrough) — potwierdzone przez zielony build.
- `createCustomFieldGroup(key='', value='')` prywatna metoda buduje wiersz z
  `nonNullable: true` na obu kontrolkach (unika `string|null` i potrzeby `?.trim()`).
- `onSubmit()`: `Object.fromEntries(raw.customFields.map(f => [f.key.trim(), f.value.trim()]).filter(([k]) => k.length > 0))`
  — zawsze wysyła pełny `customFields` (nawet `{}` jeśli puste), czyli pełne zastąpienie, nie
  merge — spójne z tym że komponent teraz w pełni zarządza tą mapą.

**Gotcha zauważona (nie naprawiana, świadomie pominięta):** aria-label i placeholder w
istniejących wierszach `phones`/`emails` (`removePhone`/`removeEmail`) NIE są zi18n-owane
(hardcoded string + index, np. `'Usuń numer telefonu ' + ($index+1)`) mimo że reszta UI używa
Transloco wszędzie. Nowa sekcja customFields celowo powiela ten sam (niespójny) wzorzec dla
zgodności wizualnej z sąsiednimi sekcjami — do rozważenia jako osobny drobny fix kiedyś.

**Pliki zmienione:**
- `frontend/src/app/features/supervisor/pages/customers/customer-edit/customer-edit.component.ts`
- `frontend/src/app/features/supervisor/pages/customers/customer-edit/customer-edit.component.html`
- `frontend/src/app/features/supervisor/pages/customers/customer-edit/customer-edit.component.scss`
  (nowa klasa `.custom-field` — grid 3 kolumny key/value/remove, stack na `max-width: 480px`)
- `frontend/src/app/features/supervisor/pages/customers/services/customer.service.ts`
  (`updateCustomer` payload +`customFields?: Record<string, unknown>`)
- `frontend/public/i18n/{pl,en,de,uk}.json` — nowe klucze `supervisor.customerEdit.customFieldsLabel`,
  `customFieldsKeyPlaceholder`, `customFieldsValuePlaceholder`, `noCustomFields`, `addCustomField`

Brak istniejącego `customer-edit.component.spec.ts` w repo (nie dodano testów — nie proszono).
`npm run lint` (0 błędów) i `npm test` (136/136 zielone) zweryfikowane po zmianie.

Scope świadomie NIE objął `customer-create-modal` (zgodnie z poleceniem — edycja ≠ tworzenie).
