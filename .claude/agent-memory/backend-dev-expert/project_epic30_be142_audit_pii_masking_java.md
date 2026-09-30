---
name: project_epic30_be142_audit_pii_masking_java
description: BE-142 Zakres p.1/p.4 (Java) — AuditPiiKeys/AuditAspect maskowanie PII, spójność z V098, nowy ticket BE-146 (bug entity_id)
type: project
---

BE-142 (EPIC-30, D10 potwierdzone 2026-09-30) realizowane w dwóch krokach: `db-schema-architect`
zrobił V098 (SQL, `mask_audit_log_pii` wołane wewnątrz `anonymize_customer` — patrz
[[project_epic30_be129_gdpr_java_integration]] dla ogólnego wzorca współpracy SQL↔Java w GDPR),
`backend-dev-expert` zrobił Zakres p.1 (maskowanie u źródła w `AuditAspect`) i p.4 (test że
`GdprServiceImpl` nie wkłada PII do własnych wpisów audytu).

**Wzorzec „jedno źródło prawdy" dla listy PII, dwa niezależne mechanizmy maskujące tę samą treść:**
- `AuditPiiKeys` (`infrastructure/aspect/AuditPiiKeys.java`) — `Set<String> KEYS` (13 kluczy:
  firstName, lastName, phone, email, customFields, gdprConsent, externalId, remoteAddress,
  channelMetadata, notes, recordingUrl, fromAddress, subject), `MASKED_ENTITY_TYPES = {CUSTOMER,
  CONTACT}`, `MASK_PLACEHOLDER = "[MASKED]"`. Musi być identyczna z listą w komentarzu funkcji SQL
  `fn_mask_pii_jsonb_value` (V098) — **NIE kopiuj ręcznie druga kopię w teście**, zamiast tego
  parsuj rzeczywistą treść pliku migracji z classpath (`AuditPiiKeysSqlConsistencyTest`, regex na
  bloku `FOREACH v_key IN ARRAY ARRAY[...]`) i porównaj `containsExactlyInAnyOrderElementsOf` — to
  jedyny sposób ochrony przed dryfem, który faktycznie coś wykrywa (druga ręczna kopia w teście
  mogłaby dryfować tak samo jak klasa produkcyjna).
- `AuditAspect#serializeToJson(obj, entityType)` — maskowanie PŁYTKIE (tylko top-level klucze),
  identycznie jak SQL: `channelMetadata` maskowany w całości jako jedna wartość, co przy okazji
  pokrywa zagnieżdżone `fromAddress`/`subject` (nie rekurencja, efekt uboczny). Osobny mechanizm od
  `SENSITIVE_FIELDS` (usuwanie hasła/tokeny, zawsze, każda encja) — NIE scalać: różna semantyka
  (usuń klucz vs zamaskuj wartość) i różny warunek (zawsze vs tylko CUSTOMER/CONTACT).

**Decyzja `presignedUrl` (RECORDING_URL_REQUESTED, entityType=CONTACT, TTL 15 min): NIE maskować.**
Rozwiązuje się samo — `presignedUrl` nazwą różni się od `recordingUrl` (które JEST na liście, ale
odnosi się do innego pola: klucza S3 w `contact.recording_url`), więc po prostu nie ma go na liście
`AuditPiiKeys.KEYS`. Zero specjalnego wykluczenia po `action` potrzebne — dokładnie tak jak po
stronie SQL (V098 komentarz: dopasowanie po `entity_id`/JSON content, nie po `action`).

**Zakres p.4 (czy GdprServiceImpl audytuje SAMĄ operację RODO) — już było pokryte PRZED BE-142:**
`exportCustomerData` publikuje własny wpis `GDPR_EXPORT` z `oldValue=null, newValue=null` (bez PII
z konstrukcji, nie przez maskowanie). `anonymizeCustomer` NIE publikuje żadnego własnego wpisu z
Javy (SQL pisze `CUSTOMER_ANONYMIZED` wewnątrz `anonymize_customer`, bez kluczy PII) — ten stan był
już pokryty pre-existing testem `GdprServiceTest$AnonymizeCustomer#doesNotPublishOwnAuditEvent`
(BE-129, commit `8eff7ad`, 2026-09-25) — przed pisaniem nowego testu na "czy X jest audytowane"
zawsze sprawdź `git log`/istniejące testy, bo to jest dokładnie ten rodzaj testu, który ktoś mógł
już napisać przy okazji innego ticketu.

**Bug odkryty przez db-schema-architect (entity_id = tenant_id dla `*_CREATED`) zdiagnozowany
dokładniej, NIE naprawiony — nowy ticket `BE-146`:** `AuditAspect#extractEntityId` dla
`createCustomer`/`createContact` (sygnatura `(Request, UUID tenantId)`, ID generowane wewnątrz
metody, brak w parametrach) — Próba 2 (skan parametrów po pierwszym UUID) łapie `tenantId` jako
JEDYNY UUID w argumentach, PRZED Próbą 3 (odczyt `id()`/`getId()` z wyniku). Nawet gdyby Próba 2 nie
przechwyciła, Próba 3 i tak zawiodłaby dziś: `CustomerResponse`/`ContactResponse` to rekordy z
akcesorami `customerId()`/`contactId()`, NIE `id()`/`getId()`. Naprawa dotyka WSZYSTKICH ~20
`@Audited` call site'ów w systemie (nie tylko CUSTOMER/CONTACT) — świadomie poza zakresem BE-142
(wzorzec z tej sesji EPIC-30: BE127-01→BE-145, teraz też BE142-znalezisko→BE-146).

Pliki: `AuditPiiKeys.java` (nowy), `AuditAspect.java`, `AuditPiiKeysSqlConsistencyTest.java` (nowy),
`AuditAspectTest.java` (nested `PiiMasking`), `GdprServiceTest.java`, `GdprServiceIntegrationTest.java`.
`mvn clean verify -pl app`: BUILD SUCCESS, 2175 testów (2167 + 8 nowych), 0 failures/errors.
