---
name: BE-026 Customer Import CSV
description: Async import klientów z CSV – endpointy, serwis, testy
type: project
---

Implementacja async importu klientów z CSV (BE-026).

**Nowe pliki:**
- `api/customer/DeduplicationMode.java` – enum SKIP/OVERWRITE
- `api/customer/CustomerImportStatusResponse.java` – record DTO
- `api/customer/CustomerImportController.java` – POST/GET/GET-errors na `/api/customers/import`
- `domain/service/CustomerImportService.java` – async serwis z Redis, OpenCSV, JdbcTemplate batch
- `test/domain/CustomerImportServiceTest.java` – 24 testy jednostkowe (mocki)

**Modyfikacje:**
- `domain/repository/CustomerRepository.java` – dodano `findByEmail()` (JSONB `@>` + GIN index)

**Wzorzec Redis:** klucz `import:customer:{jobId}`, raport błędów `import:customer:{jobId}:errors`, TTL 3600s

**Wzorzec deduplikacji:** szukaj po każdym phone (E.164), potem po każdym email; SKIP → skipped++, OVERWRITE → batchUpdate

**Batch insert:** `jdbcTemplate.batchUpdate`, chunk 500, INSERT z `gen_random_uuid()`, UPDATE przez osobny SQL. NULL w row[0] = INSERT, non-null = UPDATE.

**Wielokrotne wartości:** phone i email rozdzielone `;` w jednej komórce CSV; filtrowne przez `filterValidPhones()` (co najmniej 1 poprawny E.164 wymagany)

**Stałe publiczne:** `JOB_KEY_PREFIX`, `ERRORS_KEY_SUFFIX`, `JOB_TTL_SECONDS`, `MAX_FILE_SIZE_BYTES`, `BATCH_SIZE` – wszystkie `public static final` (dostęp z testów)

**Znana pułapka z testami:** `@Async processImportAsync` czyści `TenantContext` (InheritableThreadLocal) przez `finally { TenantContext.clear() }`, co może wyczyścić kontekst głównego wątku testu gdy wątek async dziedziczy ThreadLocal. Rozwiązanie: po wywołaniu `initiateImport` w teście przywróć `TenantContext.setTenantId()` przed kolejnym wywołaniem.

**Why:** zadanie BE-026 z TASKS-BACKEND.md
**How to apply:** wzorzec do naśladowania dla kolejnych importów CSV w systemie

---

**Rozszerzenie 2026-07-05 (naprawa 3 braków zgłoszonych w code review, gałąź `customer-refactor`):**
wiele kolumn phone/email, nazwane `custom_fields`, import zgody RODO (consent_given/marketing_consent).

- Nowy record `ParsedMapping(single, multi, customFields, legacyCustomFieldsColumn)` (package-private,
  NIE `private` – testy w tym samym pakiecie muszą go nazwać po `var result = service.parseColumnMappingJson(...)`).
  Zastąpił płaski `Map<String,Integer> columnMapping` używany zarówno przy jawnym mapowaniu z frontendu
  (`parseColumnMappingJson`, publiczna – wywoływana bezpośrednio z testów), jak i przy auto-detekcji
  nagłówka pliku (`buildColumnIndex`) oraz fallbacku pozycyjnym (`defaultColumnIndex` – celowo BEZ
  domyślnych pozycji dla zgód, żeby zgoda była importowana tylko gdy jawnie zmapowana).
- Nowy kontrakt `columnMapping` JSON z frontendu: `phone`/`email` mogą być tablicą indeksów
  (`"phone": [2, 5]`) – łączone w jedną listę przez `getMultiColumn()`; pojedyncza liczba nadal
  akceptowana (wstecznie kompatybilna, opakowywana w listę 1-elementową). `custom_fields` może być
  obiektem `{nazwa: indeks}` – budowane wprost w `customFields`; pojedyncza liczba nadal akceptowana
  (`legacyCustomFieldsColumn` – zachowuje dokładnie stare zachowanie: parsuje zawartość TEJ JEDNEJ
  komórki jako JSON, fallback na literalny klucz `custom_fields` gdy nie jest poprawnym JSON-em).
- `CsvRow` ma teraz `Boolean consentGiven, marketingConsent` – parsowane przez `parseBoolean()`
  (tak/true/1/yes → true, nie/false/0/no → false, inne/puste → null, NIE blokuje importu wiersza).
- `buildGdprConsent()` – buduje `gdpr_consent` tylko z niepustych wartości + `consent_source=CSV_IMPORT`
  + `consent_date` (tylko gdy przynajmniej jedna wartość niepusta); pusta mapa gdy obie null.
- **Krytyczne dla bezpieczeństwa danych (regresja pokryta testem):** `hasConsentMapping` liczone RAZ
  na cały import (`mapping.single().containsKey(COL_CONSENT_GIVEN/MARKETING_CONSENT)`) i przekazywane
  do `batchUpdateCustomers`. Dwa warianty SQL UPDATE: `UPDATE_SQL_WITHOUT_CONSENT` (domyślny – NIE
  dotyka `gdpr_consent`, chroni przed cichym zerowaniem zgody przy re-imporcie samego telefonu) vs
  `UPDATE_SQL_WITH_CONSENT` (`gdpr_consent = gdpr_consent || CAST(? AS jsonb)` – merge JSONB, nie
  nadpisanie). INSERT zawsze pisze `gdpr_consent` (bindowany parametr zamiast `'{}'::jsonb` na sztywno);
  pusta mapa → fallback `{"consent_given": false}` (spójne z `CustomerServiceImpl.defaultGdprConsent()`).
- `toJson(Map<String, ?> map)` – zmieniony na wildcard, żeby obsłużyć zarówno `Map<String,String>`
  (custom_fields) jak i `Map<String,Object>` (gdpr_consent z boolean).
- Row layout w `buildInsertRow`/`buildUpdateRow` przesunięty o 1 (dodano `gdpr_consent` na indeksie 8,
  `created_at`/`updated_at` przesunięte na 9) – jeśli dodajesz kolejne pole, pamiętaj zaktualizować
  komentarze z indeksami w `batchInsertCustomers`/`batchUpdateCustomers`.

---

**Poprawka 2026-07-05 (runda 2 code review, gałąź `customer-refactor`):** dziura w fallbacku
`consent_given` dla ścieżki INSERT.

- **Bug:** stary warunek `gdprConsent.isEmpty() ? {"consent_given": false} : gdprConsent` podstawiał
  domyślne `consent_given=false` TYLKO gdy CAŁA mapa była pusta (obie wartości null). Gdy CSV miał
  zmapowane WYŁĄCZNIE `marketing_consent` (bez `consent_given`), wynikowy `gdpr_consent` nowego
  klienta w ogóle nie zawierał klucza `consent_given` – łamało to inwariant
  `CustomerServiceImpl.defaultGdprConsent()` (klucz zawsze obecny).
- **Fix:** nowa metoda `buildInsertGdprConsent(consentGiven, marketingConsent)` – buduje bazową mapę
  `{"consent_given": false}`, potem `putAll(buildGdprConsent(...))` nakłada realne wartości z CSV.
  Dzięki temu `consent_given` jest zawsze obecny (domyślnie `false`, nadpisany gdy CSV go dostarczył),
  a `marketing_consent`/`consent_source`/`consent_date` pojawiają się tylko gdy faktycznie dostarczone.
  Używana WYŁĄCZNIE w `buildInsertRow` (ścieżka INSERT).
- **Nie dotknięte:** `buildUpdateRow` nadal woła `buildGdprConsent()` bezpośrednio (bez fallbacku) –
  dla UPDATE/OVERWRITE pusta/częściowa mapa to świadomy no-op merge JSONB
  (`gdpr_consent = gdpr_consent || CAST(? AS jsonb)`); wymuszenie tam `consent_given: false` nadpisałoby
  prawdziwą, wcześniej zebraną zgodę istniejącego klienta zerem – to była świadoma decyzja z poprzedniej
  rundy, potwierdzona ponownie w tej rundzie jako NIE do zmiany.
- Testy dodane w `CustomerImportServiceTest$ColumnMappingAndConsent`:
  `newCustomer_onlyMarketingConsentMapped_consentGivenDefaultsToFalse` (regresja z code review) oraz
  `newCustomer_noConsentColumnsMapped_defaultsToConsentGivenFalse` (potwierdza stary przypadek "obie
  wartości null" nadal działa identycznie). Pełny `mvn test -pl app`: 1451/1451 zielone.

**Why:** code review zgłosił, że fallback na poziomie "cała mapa pusta" nie pokrywał przypadku
częściowego mapowania (tylko `marketing_consent`).
**How to apply:** przy każdej kolejnej zmianie w budowaniu `gdpr_consent` dla INSERT pamiętaj o tym
rozróżnieniu: INSERT zawsze ma jawny `consent_given` (fallback+overlay), UPDATE nigdy nie wymusza
fallbacku (czysty merge).
