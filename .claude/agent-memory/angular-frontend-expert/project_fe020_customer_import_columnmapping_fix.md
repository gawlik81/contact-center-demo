---
name: fe020-customer-import-columnmapping-fix
description: Fix do CustomerImportComponent - multi-column phone/email, named custom_fields, konsent RODO opcje w mapowaniu CSV (customer-refactor branch, 2026-07-05)
metadata:
  type: project
---

Rozszerzenie [[project_fe020_customer_import]] (CustomerImportComponent) o naprawę bugu nadpisywania kolumn phone/email oraz nowy format `columnMapping` uzgodniony równolegle z backendem (gałąź `customer-refactor`).

**Problem:** stary kod budował `mappingObj: Record<string, number>` przez `mappingObj[m.systemField] = index` w pętli — jeśli użytkownik zmapował 2 kolumny CSV na `phone` (UI na to pozwalał), druga nadpisywała pierwszą w obiekcie JS. `custom_fields` nie miało w ogóle możliwości nazwania klucza docelowego. Brak opcji zgód RODO.

**Nowy kontrakt `columnMapping` wysyłany do backendu:**
```json
{
  "first_name": 0, "phone": [2, 5], "email": [3],
  "consent_given": 6, "marketing_consent": 7,
  "custom_fields": { "vip": 8, "segment": 9 }
}
```

**Zmiany:**
- `SystemField` type → dodano `'consent_given' | 'marketing_consent'`.
- `ColumnMapping` interface → dodano `customFieldKey?: string` (nazwa docelowa w custom_fields).
- `systemFieldOptions` — hardkodowane polskie stringi (potwierdzony wzorzec pliku, NIE transloco keys) — dodano tam 2 nowe pozycje.
- Nowa metoda `updateCustomFieldKey(index, key)` — immutable update, analogiczna do `updateMapping`.
- `onImport()` — walidacja: każdy wiersz z `systemField === 'custom_fields'` musi mieć niepusty (trim) `customFieldKey`, inaczej `mappingError.set(...)` (hardkodowany string, wzorem istniejącej walidacji `isPhoneMapped`) i przerwanie. Budowa `mappingObj: Record<string, unknown>` z osobnymi tablicami `phoneIndices`/`emailIndices` (push zamiast nadpisania) oraz `customFieldsObj: Record<string, number>`.
- `CustomerService.importCsv()` — sygnatura `columnMapping` zmieniona z `Record<string, number>` na `Record<string, unknown>` (implementacja bez zmian, tylko `JSON.stringify`).
- HTML: dodano `.mapping-hint` (info box nad tabelą mapowania) tłumaczący że wiele kolumn CSV można zmapować na to samo pole Telefon/Email — wcześniej ta możliwość istniała ale była niewidoczna w UI. Dodano warunkowy `<input>` tekstowy (`.field-input`) obok selecta gdy `systemField === 'custom_fields'`, powiązany z `updateCustomFieldKey`.
- i18n: dodano do `customerImport` w pl/en/de/uk: `consentGivenOption`, `marketingConsentOption`, `customFieldKeyPlaceholder`, `multiColumnHint`, `customFieldKeyRequiredError` — na przyszłość, gdyby ktoś zdecydował się przenieść hardkodowane etykiety na transloco (obecnie `systemFieldOptions` nadal hardkodowane, hint/placeholder w HTML już używają tych kluczy).

**Update 2026-07-05 (CR fix, runda 2):** dodano walidację duplikatów kluczy `custom_fields` w `onImport()` — po sprawdzeniu `hasUnnamedCustomField`, wykrywa dwie kolumny zmapowane na tę samą nazwę pola dodatkowego (`customFieldKeys.find((key, i) => customFieldKeys.indexOf(key) !== i)`), blokuje import przez `mappingError.set(...)`. Hardkodowany ASCII string (bez polskich diakrytyków — potwierdzony wzorzec pliku: `mappingError.set()` NIE używa transloco, string renderowany bezpośrednio w HTML `{{ mappingError() }}`). Dodano też klucz i18n `duplicateCustomFieldKeyError` (z `{{ key }}` param) w pl/en/de/uk — analogicznie do wcześniej dodanego, również nieużywanego `customFieldKeyRequiredError` — na przyszłość, gdyby ktoś migrował te komunikaty na transloco. Lint 0 errors, testy 136/136, prettier OK.

**Testy:** brak pliku `customer-import.component.spec.ts` (potwierdzone, nie istniał wcześniej) — zgodnie z konwencją tego repo nie tworzę nowych testów przy bugfixie istniejącego kreatora bez testów, chyba że zadanie tego wprost wymaga.

**Weryfikacja:** `npm run lint` (0 errors, tylko pre-istniejące warningi console w innych plikach), `npm test` (136/136 passed), `npm run build` (sukces, tylko pre-istniejące bundle-budget warningi), `prettier --check` (OK) na wszystkich zmienionych plikach.
