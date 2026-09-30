---
name: project-epic30-be129-gdpr-java-integration
description: EPIC-30 BE-129 — GdprServiceImpl podpięty do anonymize_customer/export_customer_data (DB-061/062), GdprRepository jako jedyny wołający, guard rekordów w toku, konsolidacja DELETE/POST
metadata:
  type: project
---

BE-129 (2026-09-24) — pierwsze zadanie faktycznie WOŁAJĄCE gotowe funkcje SQL `anonymize_customer`
(DB-062)/`export_customer_data` (DB-061) z Javy. Wcześniej (U2, DESIGN) nikt ich nie wywoływał.

**Architektura:** nowy `domain.gdpr.GdprRepository` (package-private, `extends TenantAwareRepository`)
jest JEDYNYM miejscem `em.createNativeQuery(...)` dla obu funkcji. Zwraca surowy JSONB jako
`String` przez `SELECT fn(...)::text` (nie `PGobject`/jsonb-natywny typ — cast do `::text` unika
problemów z mapowaniem typu jsonb przez Hibernate/JDBC; ten sam trik co w testach DB-061/062
`scalarJson` helper). `GdprServiceImpl` parsuje przez `ObjectMapper.readTree(json)`.

**Kontrakt `GdprRepository.AnonymizeAttempt(customerExists, rejectedInProgress, resultJson)`:**
repozytorium NIE rzuca `EntityNotFoundException`/`ConflictException` (te żyją w serwisie, zgodnie
z konwencją warstw tego projektu — sprawdzone grepem: żaden inny `*Repository` nie rzuca tych
wyjątków, tylko `*ServiceImpl`). Cała orkiestracja (istnienie klienta niezależnie od `is_deleted`
→ guard rekordów w toku → wywołanie funkcji) siedzi w JEDNEJ `@Transactional` metodzie repozytorium
— unika self-invocation (`GdprServiceImpl` sam nie ma `@Transactional`, woła zewnętrzny bean
`GdprRepository`, więc S3 cleanup PO powrocie z tej metody jest naturalnie post-commit, bez
`TransactionSynchronization`).

**Guard „rekordów w toku" (decyzja BE-129, nie ma odpowiednika w SQL):** przed rzeczywistą
anonimizacją (nie dry-run) sprawdź `campaign_contact.status = 'DIALING'` LUB
`scheduled_callback.status = 'PROCESSING'` w zbiorze podmiotu klienta — reużywa
`fn_customer_subject_ids` (ten sam zbiór co mutacja, `WITH subj AS MATERIALIZED (...)`) zamiast
własnej reguły dopasowania. Trafienie → `ConflictException` (409) PRZED wywołaniem
`anonymize_customer`, zero zmian. Decyzja: prostsze/bezpieczniejsze niż próba dokończenia po
zakończeniu połączenia (osobny epik synchronizacji z eventami telefonii). `scheduled_callback`
w `PENDING`/`PROCESSING` i tak trafia do `CANCELLED` WEWNĄTRZ `anonymize_customer` — ten guard
chroni tylko rekordy FAKTYCZNIE w locie, nie zakolejkowane.

**Istnienie klienta — DWIE różne semantyki, nie myl ich:**
- `anonymizeCustomer`/`previewAnonymizeCustomer`: `GdprRepository#exists` — `SELECT EXISTS(...)`
  BEZ filtra `is_deleted` (pozwala dosanityzować klienta zanonimizowanego dawniej
  `CustomerRepository#anonymize`, wymagane przez AC).
- `exportCustomerData`: nadal `customerService.findById(...)` (filtruje `is_deleted = false`) —
  spójne z guardem WEWNĄTRZ `export_customer_data`, który też odrzuca `is_deleted = TRUE`
  („Klient nie istnieje lub został zanonimizowany").
Użycie `customerService.findById` (filtrującego) jako gate dla `anonymizeCustomer` złamałoby
AC dosanityzowania — ticket sugerował to literalnie w sekcji Zakres, ale sekcje Uzupełnienia
(DB-062 AC) go nadpisują.

**S3 sprzątanie (anonymize) i manifest presigned URL (export) — DWIE różne allow-listy:**
`EmailAttachmentKeys.isOwnedByTenant` (schemat `email-attachments/{tenantId}/…`) vs
`isRecordingKeyOwnedByTenant` (schemat `{tenantId}/…`, nagrania+EML). Klasyfikacja identyczna
w obu miejscach (`cleanupS3Objects`, `buildS3Manifest`) — klucz niepasujący do żadnej → pomiń +
WARN z `EmailAttachmentKeys.forLog`, nie przerywaj. `EmailAttachmentStorageService#delete` używany
dla OBU schematów (jeden bucket) — `RecordingService#deleteFromS3` NIGDY (połyka `S3Exception`,
[[feedback-recording-delete-from-s3-swallows-errors]]). Prezygnowanie w eksporcie: klucze
email-attachment przez `EmailAttachmentStorageService#presignedDownloadUrl` (własny TTL),
nagrania/EML przez `RecordingService#generatePresignedUrlForKey(key, ttl)` (TTL z
`S3Properties#getPresignedUrlExpirationMinutes`) — best-effort per klucz, błąd → `presignedUrl:
null` w manifeście, nie przerywa eksportu.

**Konsolidacja DELETE/POST:** `CustomerController#anonymizeCustomer` (DELETE) woła teraz wprost
`GdprService#anonymizeCustomer` (drugi konstruktorowy bean w kontrolerze) — zero duplikacji.
`CustomerServiceImpl#anonymizeCustomer`/`CustomerService#anonymize`/`CustomerRepository#anonymize`
NIE usunięte — `@Deprecated` + javadoc (decyzja: usunięcie z publicznego interfejsu uznane za zbyt
ryzykowne bez pewności co do wszystkich wołających poza `backend/`). `@Audited(action =
"CUSTOMER_ANONYMIZED")` USUNIĘTY z `CustomerServiceImpl#anonymizeCustomer` (jedno źródło audytu —
funkcja SQL).

**Testy:** `GdprServiceTest` (mock `GdprRepository`, 15) — logika Javy w izolacji. Nowy
`GdprServiceIntegrationTest` (Testcontainers, pełny Flyway, prawdziwy `GdprRepository`/SQL, 13) —
**`CustomerService`/`RecordingService`/`EmailAttachmentStorageService`/`AuditLogService` są zwykłymi
mockami Mockito** (NIE realne beany Springa) wstrzykniętymi ręcznie do `new GdprServiceImpl(...)`
skonstruowanego POZA kontenerem — `GdprServiceImpl` nie ma własnych `@Transactional`, więc nie musi
być zarządzana przez kontener, co pozwala ominąć realny `EmailAttachmentStorageServiceImpl`
(package-private w `domain.email`, niedostępny z `domain.gdpr` przy próbie `Class<?>[]` do
`JpaTestContext.create` — **pułapka**: `EmailAttachmentStorageServiceImpl.class` jako LITERAŁ w
kodzie testu wymaga widoczności typu na etapie kompilacji, nie da się obejść przez
`Class.forName` bez utraty czytelności; prościej zamockować interfejs na poziomie
`EmailAttachmentStorageService` niż ciągnąć realny S3-backed bean między pakietami). Kontener
JPA rejestruje WYŁĄCZNIE `GdprRepository.class` (+ `Customer.class` jako typ encji).

**Why (nieoczywiste):** `p_dry_run` MUSI być Java `boolean` (prymityw), nigdy `Boolean` — funkcja
SQL odrzuca jawny SQL `NULL` (DB062-01), ale warstwa wywołująca nie powinna nawet dopuszczać takiej
wartości. `counts.customer` z funkcji SQL jest ZAWSZE `1` przy sukcesie (nie licznik delty) — nie
traktować `0` jako możliwego wyniku.

**How to apply:** przyszłe zmiany w `GdprServiceImpl` powinny zachować rozdział: SQL/DB w
`GdprRepository` (z `AnonymizeAttempt` jako kontrakt bez wyjątków), mapowanie na wyjątki domenowe
i S3/eksport w serwisie. FE-112 konsumuje `AnonymizePreviewResponse` i musi obsłużyć 409 (rekord
w toku) osobno od 404. Powiązane: [[project-epic30-be125-message-purge]],
[[project-epic30-be143-attachment-key-validation]], [[feedback-recording-delete-from-s3-swallows-errors]],
[[feedback-jpa-real-db-integration-test-harness]], [[feedback-self-invocation-transactional]].
