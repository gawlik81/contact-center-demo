---
name: project_customer_json_import
description: Import klientów z JSON (równoległy do CSV) – processRow/ImportCounters, parametryzacja source, endpoint POST /api/customers/import/json
metadata:
  type: project
---

Dodano import klientów z pliku JSON jako równoległą ścieżkę do istniejącego importu CSV
(`CustomerImportServiceImpl`, `CustomerImportController`) — tablica obiektów camelCase zgodna
z `CreateCustomerRequest`/`CustomerResponse` (firstName, lastName, externalId, phone/email
string-lub-tablica, customFields obiekt, gdprConsent zagnieżdżony z `consent_given`/`marketing_consent`).

**Why:** Klienci chcieli importować dane z systemów, które eksportują JSON zamiast CSV, bez
duplikowania całej logiki walidacji/deduplikacji/RODO.

**Jak to zrobiono (wzorzec do reużycia przy kolejnych formatach importu):**
- Logika per-wiersz (walidacja E.164, duplikat external_id w pliku, kolizja external_id w bazie,
  SKIP/OVERWRITE, liczniki) wydzielona do współdzielonej `processRow(...)` wywoływanej zarówno
  z pętli CSV (`doImport`) jak i z pętli JSON (`doJsonImport`). Liczniki (imported/updated/skipped/failed)
  przeniesione z lokalnych `int` do mutowalnej klasy pomocniczej `ImportCounters`.
- `source` (wartość kolumny `customer.source` ORAZ `gdpr_consent.consent_source` — w tym projekcie
  to zawsze ta sama wartość, np. "CSV_IMPORT"/"JSON_IMPORT") jest teraz parametrem przekazywanym
  przez cały łańcuch wywołań: `processRow` → `buildInsertRow`/`buildUpdateRow` →
  `buildInsertGdprConsent`/`buildGdprConsent`, oraz osobno `flushBatch` → `batchInsertCustomers`
  (tam jako bindowany parametr JDBC `?`, NIE konkatenacja SQL — było wcześniej zaszyte na sztywno
  jako literał `'CSV_IMPORT'`).
- Finalizacja stanu joba w Redis (status COMPLETED/FAILED_PARTIAL, zapis raportu błędów CSV)
  wydzielona do `finalizeImportState(...)`, współdzielona przez CSV i JSON.
- Endpointy GET `/{jobId}` i GET `/{jobId}/errors` są format-agnostyczne — nie wymagały zmian.
- Błąd "root JSON nie jest tablicą" propaguje się jako `IOException` z `doJsonImport`, łapany przez
  istniejący catch-all w `processJsonImportAsync` (mirror `processImportAsync`) → job kończy się
  `FAILED_PARTIAL` z wpisem FATAL — identyczny mechanizm jak błąd parsowania CSV.
- `hasConsentMapping` dla JSON liczone inaczej niż dla CSV: nie z nagłówka kolumny, tylko
  `rows.stream().anyMatch(obj -> obj.get("gdprConsent") instanceof Map zawierająca consent_given
  lub marketing_consent)` — ta sama semantyka ochrony RODO (merge `||`, nigdy pełne nadpisanie).

**How to apply:** Przy kolejnym formacie importu (np. XML, Excel) reużyj `processRow`/`ImportCounters`/
`finalizeImportState` zamiast kopiować logikę per-wiersz. Pamiętaj o dwóch miejscach dla nowego
publicznego endpointu (SecurityConfig + TenantFilter.PUBLIC_PATH_PREFIXES) tylko jeśli endpoint miałby
być publiczny — tu oba endpointy importu wymagają JWT + ADMIN/SUPERVISOR, więc te miejsca nie były
dotykane.

Pliki: `app/src/main/java/com/contactcenter/domain/customer/CustomerImportServiceImpl.java`,
`CustomerImportService.java`, `app/src/main/java/com/contactcenter/api/customer/CustomerImportController.java`,
testy: `CustomerImportServiceTest.java` (`@Nested class JsonImport`), `CustomerImportControllerTest.java`.
