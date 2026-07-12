---
name: BE-023 rozszerzenie – import kontaktów kampanii z JSON
description: Alternatywna ścieżka importu JSON obok CSV dla campaign_contact – refaktor współdzielonych metod, nowy endpoint, testy
type: project
---

Rozszerzenie [[project_be023_csv_import]] o alternatywny format importu JSON (analogiczny wzorzec
do importu klientów `CustomerImportServiceImpl`/BE-026, ale bez `email`/`externalId`/`gdprConsent`
– `campaign_contact` jest prostsza, jeden telefon na rekord).

**Why:** Ten sam mechanizm jobId/Redis/polling co CSV, jeden dropzone na froncie z detekcją formatu
z rozszerzenia pliku – brak kroku mapowania kolumn dla JSON (camelCase 1:1 z REST API).

**Kolejność wykonania (2026-07-12):**
1. Refaktor `CampaignImportServiceImpl` BEZ zmiany zachowania CSV – wydzielono `processRow`,
   `flushBatch`, `finalizeJobStatus`, `requireCampaignInTenant`, `buildQueuedStatus`. Uruchomiono
   istniejący `CampaignImportServiceTest` (25 testów) – wszystkie przeszły bez zmian, potwierdzając
   neutralność refaktoru PRZED dodaniem czegokolwiek nowego.
2. Dodano ścieżkę JSON: `initiateJsonImport`/`validateJsonFile`/`processJsonImportAsync`/
   `doJsonImport`/`parseJsonRow`/`jsonStringOrNull` w `CampaignImportServiceImpl` +
   `initiateJsonImport` w interfejsie `CampaignImportService`.
3. Nowy endpoint `POST /{id}/contacts/import/json` w `CampaignImportController` (204 default
   skipDuplicates=true, `@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")`) – GET polling status
   endpoint bez zmian (już format-agnostyczny).
4. Testy: rozszerzony `CampaignImportServiceTest` (+`ValidateJsonFile`, +`JsonImport`, 37 testów
   łącznie w klasie), nowy `CampaignImportControllerTest.java` (wzorowany 1:1 na
   `CustomerImportControllerTest` – `MinimalBootConfig` + `@WebMvcTest` + `MockMvc.multipart`).

**Kluczowe decyzje / pułapki:**
- Root JSON musi być tablicą – inaczej `IOException` w `doJsonImport`, złapane przez
  `processJsonImportAsync` (catch-all), kończy job jako `FAILED_PARTIAL` z próbką `"FATAL: ..."` –
  identyczny mechanizm co błąd parsowania CSV.
- `totalRows` dla JSON znane z góry (`rows.size()`) w odróżnieniu od CSV (parsowanie strumieniowe
  przez OpenCSV) – `finalizeJobStatus` przyjmuje jawnie `totalRowsCount` jako parametr zamiast
  polegać wyłącznie na liczniku rosnącym w pętli.
- Testy jednostkowe: serwis tworzony przez `new CampaignImportServiceImpl(...)` (nie przez
  kontener Spring), więc `@Async` NIE jest realnie asynchroniczne w testach –
  `initiateJsonImport`/`initiateImport` wywołują `process...Async` SYNCHRONICZNIE w tym samym
  wątku. Żeby to zadziałało poprawnie (status zapisany przez `saveJobStatus` musi być odczytany
  przez `loadJobStatus` chwilę później w tym samym teście), w `setUp()` dodano fake magazyn Redis
  w pamięci (`Map<String,String>` + `doAnswer`/`thenAnswer` na `valueOps.set/get`) – testy chcące
  symulować inny scenariusz (np. brak statusu, uszkodzony JSON) nadpisują te stuby lokalnie
  (nadpisanie w metodzie testowej zawsze wygrywa).
- Zobacz [[feedback_argumentcaptor_cleared_batch_list]] – `ArgumentCaptor` na `batch` przekazywanym
  do `campaignContactRepository.batchInsert()` łapie referencję czyszczoną przez `flushBatch()`
  zaraz potem; trzeba przechwycić kopię przez `thenAnswer` w momencie wywołania.
- Świadomie pominięto pole `email` w JSON (istnieje w schemacie DB, ale nieużywane przez CSV ani
  `CampaignContactResponse`/`CampaignContactRepository`) – osobny, szerszy ticket.
- **Code review fix (2026-07-12):** `doJsonImport` pętla po `rows.get(i)` nie sprawdzała `null`
  elementu tablicy (`[{"phone":"..."}, null]`) → `parseJsonRow(null)` rzucał NPE, który NIE był
  łapany przez lokalny try/catch (obejmuje tylko parsowanie JSON-a na starcie metody) i propagował
  się do catch-all w `processJsonImportAsync` → cały job kończył się `FAILED_PARTIAL`, tracąc
  WSZYSTKIE poprawne wiersze zamiast odrzucić tylko zły rekord. Fix: `rawRow != null ?
  parseJsonRow(rawRow) : new CsvRow(null, null, null, Map.of())` – null trafia do zwykłej
  walidacji telefonu w `processRow` i zostaje odrzucony jak każdy inny brakujący telefon. **Ten
  sam bug istnieje w `CustomerImportServiceImpl` (import klientów) – świadomie NIE naprawiony tam,
  osobny zakres/ticket.**
- **Code review fix (2026-07-12):** `parseJsonRow` serializował zagnieżdżone wartości
  `customFields` (np. `{"tags": ["a","b"]}`) przez `String.valueOf(entry.getValue())` → Java
  `toString()` listy (`"[a, b]"`) – niepoprawny JSON, nie do sparsowania z powrotem. Fix: nowa
  `customFieldValueToString(Object)` – skalar (String/Number/Boolean) przez `String.valueOf` jak
  dotychczas, złożone wartości przez `objectMapper.writeValueAsString(value)` z fallbackiem do
  `String.valueOf` przy błędzie (wzorzec identyczny do istniejącego `toJson`). **Ten sam wzorzec
  (String.valueOf na zagnieżdżonych wartościach) istnieje też w `CustomerImportServiceImpl` –
  świadomie NIE naprawiony tam, osobny zakres/ticket.**

**Wynik:** pełne `mvn test -pl app` – 1499 testów, 0 failures, 2 errors (pre-existing,
`ContactCenterApplicationIT` – niezwiązane z tą zmianą, potwierdzone przez `git stash` + rerun na
kodzie sprzed zmian, identyczny wynik). Po poprawkach code review: `CampaignImportServiceTest` –
39 testów (2 nowe: null element tablicy, zagnieżdżony `customFields`), 0 failures.

**How to apply:** Przy kolejnym alternatywnym formacie importu (np. XML) dla dowolnej encji z
istniejącym importem CSV – ten sam wzorzec: refaktor najpierw (dowód neutralności testami), potem
nowa ścieżka współdzieląca `processRow`/`flushBatch`/`finalizeJobStatus`.
