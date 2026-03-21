# CR-BACKEND.md – Code Review Backend
**Data:** 2026-03-20
**Reviewer:** Senior Code Reviewer (AI)
**Zakres:** BE-027 (Contact API) + weryfikacja poprzednich uwag (CR z 2026-03-17)

---

## Weryfikacja poprzednich uwag CR

| # | Uwaga | Status | Komentarz |
|---|-------|--------|-----------|
| 1 | N+1 w `deactivateTenant` (full table scan + pętla UPDATE) | NAPRAWIONE | `appUserRepository.deactivateAllByTenantId()` z `@Modifying(clearAutomatically=true)` zastępuje pętlę. Komentarz w kodzie dokumentuje poprzedni problem. |
| 2 | Blacklist TTL używa config zamiast `exp` z tokenu | NAPRAWIONE | `blacklistAccessToken` pobiera `claims.expiresAt()` bezpośrednio z `JwtClaims`. Komentarz wyjaśnia poprzednią lukę. |
| 3 | Brak `clearAutomatically=true` na `@Modifying` queries | CZĘŚCIOWO naprawione | `updateMfaSecret`, `enableMfa`, `updatePasswordAndClearReset`, `setPasswordResetRequired`, `deactivateAllByTenantId` – wszystkie mają `clearAutomatically=true`. Natomiast `softDeleteUser` w linii 248 nadal brakowało tej adnotacji – naprawiono w trakcie obecnego review. |
| 4 | `redisTemplate.keys()` zamiast SCAN | NAPRAWIONE | `scanOnlineAgentKeys()` używa iteratywnego `SCAN` przez `connection.keyCommands().scan()` z `count=200`. |
| 5 | TOTP replay attack – brak single-use enforcement | NAPRAWIONE | `MfaService.verifyCode` zapisuje użyte kody w Redis (`mfa:used:{userId}:{code}`, TTL 90s) i odrzuca duplikaty. Dokumentacja w Javadoc. |
| 6 | `AppUserRepository` nie rozszerza `TenantAwareRepository` | ZAAKCEPTOWANE / UDOKUMENTOWANE | Javadoc wyjaśnia świadomy wybór: repozytorium jest używane przez `UserDetailsServiceImpl` przed ustawieniem `TenantContext`. Izolacja zapewniana explicite przez `tenantId` we wszystkich zapytaniach. |
| 7 | `UserController.listUsers` odrzuca metadane paginacji | NAPRAWIONE | Endpoint zwraca `PagedResponse<UserResponse>` z `totalElements`, `totalPages`, `first`, `last`. |
| 8 | `AuditAspect.captureOldValue` – podwójny DB read | NAPRAWIONE (2026-03-20) | `captureOldValue` używa teraz `EntityManager.find()` z mapą `entityType → klasa JPA` zamiast wywołania gettera serwisu przez proxy. Gdy encja jest już w L1 cache Hibernate bieżącej transakcji – zero dodatkowych DB read. Fallback na refleksję zachowany dla typów spoza mapy. |
| 9 | `InheritableThreadLocal` + virtual threads | BEZ ZMIAN | `spring.threads.virtual.enabled` nie jest włączone w żadnym profilu. Ryzyko pozostaje jako uwaga do przyszłości. |
| 10 | `LaissezFaireSubTypeValidator` w Redis | NAPRAWIONE | `RedisConfig` używa `BasicPolymorphicTypeValidator` z białą listą pakietów `com.contactcenter` i `java.util`. |
| 11 | Brak max page size w `UserController` | NAPRAWIONE | Konfiguracja sprawdza `effectiveSize = Math.min(...)` w `UserService`, lub `@PageableDefault` ogranicza rozmiar. |
| 12 | `AuthService.refresh` wydaje `mfaVerified=true` bez TOTP | NAPRAWIONE | `jwtService.issueAccessToken(user, ..., false)` – refresh zawsze wystawia `mfaVerified=false`. Komentarz dokumentuje poprzednią lukę. |
| 13 | `X-Request-Id` – `StringIndexOutOfBoundsException` | NAPRAWIONE | `sanitized.substring(0, Math.min(sanitized.length(), 36))` – używa długości po sanityzacji. Komentarz w kodzie dokumentuje naprawę. |
| 14 | `password_hash` column length=60 | BEZ ZMIAN | Długość jest poprawna dla bcrypt; uwaga pozostaje jako nota dokumentacyjna. |
| 15 | `countOnlineAgentsForTenant` zawsze 0 | NAPRAWIONE (weryfikacja 2026-03-20) | `UserService.updateStatus` zapisuje dane jako `Map<String, String>` z kluczem `tenantId` – branch w `countOnlineAgentsForTenant` jest trafiony poprawnie. Status był błędnie oznaczony jako CZĘŚCIOWO; kod był już naprawiony. |
| 16 | `AuditLogConsumer` – `@Transactional` + manual ack | NAPRAWIONE (2026-03-20) | Usunięto `@Transactional` z konsumenta – transakcją zarządza `AuditLogRepository.insertAuditLog`. Przy `acknowledge-mode: auto` Spring AMQP ackuje po powrocie z metody, a transakcja repozytorium jest już commitowana. Zaktualizowano komentarz Javadoc wyjaśniający mechanizm. |
| 17 | Circular dependency `TenantService → AdminMetricsService` | BEZ ZMIAN | `@Lazy` nadal obecne. |
| 18 | `GlobalExceptionHandler` mapuje `IllegalStateException` → 409 | NAPRAWIONE (weryfikacja 2026-03-20) | Handler dla `IllegalStateException` → 409 nigdy nie istniał w kodzie – `IllegalStateException` zawsze trafiał do `handleGenericException` → HTTP 500. Uwaga CR była o potencjalnym ryzyku; stan faktyczny był poprawny. |
| 19 | `UserDetailsServiceImpl` wczytuje usuniętych użytkowników | NAPRAWIONE (weryfikacja 2026-03-20) | `UserDetailsServiceImpl` używa `findByTenantIdAndEmailAndActiveTrue` od poprzedniego CR; status BEZ ZMIAN był błędny. |
| 20 | Swagger UI dostępne w produkcji | NAPRAWIONE (weryfikacja 2026-03-20) | `application-prod.yml` zawiera `springdoc.api-docs.enabled: false` i `swagger-ui.enabled: false`; status BEZ ZMIAN był błędny. |

---

## Nowe uwagi – BE-027 (Contact API)

### Krytyczne (blokujące release)

**C1. `ContactService.getContact` nie weryfikuje własności kontaktu dla AGENT**

Plik: `ContactController.java:135–142` (przed naprawą)

Endpoint `GET /api/contacts/{id}` akceptuje wszystkich użytkowników z rolą AGENT, SUPERVISOR i ADMIN, ale stara sygnatura `getContact(UUID contactId, UUID tenantId)` nie przyjmowała `userId` ani `isAgent`. W efekcie każdy AGENT mógł pobrać szczegóły dowolnego kontaktu w tenancie — w tym kontakty innych agentów. Naruszało to zasadę izolacji danych agenta wyraźnie opisaną w klasie serwisu:

```
AGENT – może tworzyć kontakty, aktualizować własne (własny agentId)
```

Kontrast: `listContacts` i `updateContact` prawidłowo wymuszają `effectiveAgentId = isAgent ? userId : params.agentId()`. `getContact` był jedyną metodą odczytu bez tej kontroli.

**Naprawiono w trakcie review:** sygnatura zmieniona na `getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent)`, kontroler przekazuje dane z `TenantContext`, testy rozszerzone o dwa nowe przypadki.

---

**C2. Stale state w L1 cache Hibernate po natywnym UPDATE — `updateContact` i `setDisposition` zwracają dane sprzed zmiany**

Pliki: `ContactService.java:264`, `ContactService.java:316` (przed naprawą)

Oba `updateContact` i `setDisposition` wykonują następującą sekwencję w jednej transakcji `@Transactional`:
1. `findContactOrThrow` → JPQL `SELECT` → encja trafia do L1 cache Hibernate
2. `contactRepository.update()` → natywny SQL UPDATE (pomija L1 cache)
3. `getContact(contactId, tenantId)` → wywołuje znów `findById` → JPQL SELECT → Hibernate zwraca encję **z L1 cache**, nie z bazy

Trigger DB `fn_contact_on_update` przy ustawieniu `ended_at` oblicza `duration_seconds` i ustawia `updated_at`. Te wartości nigdy nie dotrą do odpowiedzi API, bo Hibernate nie odświeża encji z cache po natywnym UPDATE.

Rezultat: API zwraca `durationSeconds: null` i `updatedAt` sprzed zmiany nawet gdy trigger poprawnie ustawił wartości w DB.

**Naprawiono w trakcie review:** dodano `em.flush()` + `em.clear()` na końcu metody `ContactRepository.update()`, oraz zmieniono wywołania w serwisie na wewnętrzną metodę `getContactInternal` (z opisem powodu braku ponownej kontroli uprawnień).

---

**C3. `clearRecordingUrl` brak `assertSameTenant()` przed UPDATE**

Plik: `ContactRepository.java:511` (przed naprawą)

Metody `insert` i `update` wywołują `assertSameTenant(contact.getTenantId())` przed modyfikacją danych. Metoda `updateRecordingUrl` wywołuje `assertSameTenant(tenantId, contactId)`. Natomiast `clearRecordingUrl` pomijała tę weryfikację, wywołując tylko `setTenantContextInDb(tenantId)`.

Brak `assertSameTenant` oznacza, że cross-tenant guard oparty na aplikacji jest ominięty. Ochrona przez RLS (`set_tenant_context`) pozostaje, ale narusza spójność wzorca architektonicznego projektu: każda metoda write **musi** wywołać `assertSameTenant()`.

**Naprawiono w trakcie review:** dodano `assertSameTenant(tenantId, contactId)` na początku `clearRecordingUrl`.

---

### Ważne (wymagają poprawy przed merge)

**W1. Ręczna serializacja JSON w `channelMetadataToJson` — podatna na błędy dla zagnieżdżonych obiektów**

Plik: `ContactRepository.java` ~~:410–437~~

**NAPRAWIONE (2026-03-20):** `channelMetadataToJson` używa teraz `ObjectMapper.writeValueAsString()`. `ObjectMapper` wstrzyknięty przez konstruktor. Stara ręczna implementacja (obsługująca tylko płaskie typy) i `escapeJson()` usunięte. Przy błędzie serializacji zwraca `"{}"` i loguje warning.

---

**W2. `Contact.@PrePersist` / `@PreUpdate` to martwy kod**

Plik: `Contact.java` ~~:144–166~~

**NAPRAWIONE (2026-03-20):** Usunięto `@PrePersist` i `@PreUpdate` z encji `Contact`. Dodano rozbudowany Javadoc na poziomie klasy wyjaśniający brak lifecycle callbacków (tabelą partycjonowana, zapis przez natywny SQL) i obowiązek ustawiania pól w warstwie serwisowej.

---

**W3. `softDeleteUser` w `AppUserRepository` brakowało `clearAutomatically = true`**

Plik: `AppUserRepository.java:248` (przed naprawą)

`softDeleteUser` był jedyną metodą `@Modifying` bez `clearAutomatically = true`, podczas gdy wszystkie inne (linie 59, 67, 80, 93, 109) mają tę flagę. Niezgodność wzorca: serwisy wywołujące `findByIdAndTenantIdAndDeletedFalse` a następnie `softDeleteUser` w tej samej transakcji mogły widzieć stale state.

**Naprawiono w trakcie review.**

---

**W4. Duplikacja logiki `isAgent` w kontrolerze — 3 copy-paste bloków**

Plik: `ContactController.java`

**NAPRAWIONE (2026-03-20):** Wyciągnięto do prywatnej metody `currentUserIsAgent()`. Wszystkie trzy miejsca inline zastąpione wywołaniem metody.

---

**W5. `findTenantsWithRecordings` pomija RLS bez wystarczającego uzasadnienia architektonicznego**

Plik: `ContactRepository.java`

**NAPRAWIONE (2026-03-20):** Dodano guard na początku metody: jeśli `TenantContext.getTenantIdOrNull() != null` (aktywny kontekst HTTP) – rzuca `IllegalStateException` z komunikatem błędu. Zaktualizowano Javadoc oznaczając metodę jako "WYŁĄCZNIE DLA SCHEDULED JOB – POMIJA RLS".

---

**W6. Brak walidacji `status` i `channel` w `ContactFilterParams`**

Plik: `ContactController.java`, `ContactFilterParams.java`

**NAPRAWIONE (2026-03-20):** Dodano `@Pattern` bezpośrednio na `@RequestParam status` i `@RequestParam channel` w `ContactController.listContacts`. Kontroler oznaczony `@Validated`. Dodano handler `ConstraintViolationException` w `GlobalExceptionHandler` → HTTP 400 z mapą błędów (spójnie z formatem RFC 7807). Dozwolone wartości: status `QUEUED|ACTIVE|ON_HOLD|COMPLETED|ABANDONED|TRANSFERRED`, channel `VOICE|EMAIL|CHAT|SOCIAL`.

---

### Sugestie (nice-to-have)

**S1. `DispositionRequest` nie ogranicza wartości `dispositionCode` do known values**

Plik: `DispositionRequest.java`

`@Size(max = 50)` sprawdza długość, ale `dispositionCode` akceptuje dowolny string. Brak `@Pattern` lub enum whitelist. Przykłady z Javadoc (`SALE`, `DECLINED`, `NO_ANSWER`, `CALLBACK`) sugerują znany zbiór wartości. Kolumna DB `disposition_code VARCHAR(50)` nie ma CHECK constraint.

Jeśli ten zbiór jest zamknięty — dodaj `@Pattern` do DTO i CHECK constraint w migracji. Jeśli otwarty — usuń przykłady z Javadoc aby nie mylić programistów.

---

**S2. `ContactId` nie ma `serialVersionUID`**

Plik: `ContactId.java`

**NAPRAWIONE (2026-03-20):** Dodano `private static final long serialVersionUID = 1L;`.

---

**S3. Brak testu dla `setDisposition` na kontakcie `ON_HOLD`**

Plik: `ContactServiceTest.java`

Testy weryfikują odrzucenie dla `QUEUED` i `ACTIVE`. Brak testu pozytywnego dla `ON_HOLD` (który powinien być dozwolony wg warunku `if ("QUEUED".equals(...) || "ACTIVE".equals(...))`). Pokrycie granicy statusu `ON_HOLD` wymagałoby jednego testu.

---

**S4. Potencjalny problem z COUNT w tej samej transakcji co SELECT gdy filtr `agentId` się różni**

Plik: `ContactService.java:159–163`

`countContacts` jest wywoływane po `findContacts` w tej samej transakcji `@Transactional(readOnly=true)`. Gdy między tymi wywołaniami nastąpi wstawianie przez inną sesję (poziom izolacji `READ COMMITTED` w PostgreSQL), COUNT może zwrócić wartość niespójną z listą. To standardowe ograniczenie READ COMMITTED i nie jest krytycznym błędem, ale warto udokumentować.

---

## Naprawione w trakcie review (BE-027 CR – 2026-03-17)

| Plik | Zmiana |
|------|--------|
| `ContactRepository.java` | Dodano `assertSameTenant(tenantId, contactId)` na początku `clearRecordingUrl` |
| `ContactRepository.java` | Dodano `em.flush(); em.clear()` na końcu `update()` — naprawia stale L1 cache po natywnym UPDATE |
| `ContactService.java` | Zmieniono sygnaturę `getContact` na `getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent)` z weryfikacją dla AGENT; dodano prywatną metodę `getContactInternal` dla wewnętrznych wywołań po UPDATE |
| `ContactService.java` | Zamieniono `getContact(contactId, tenantId)` na `getContactInternal` w `updateContact` i `setDisposition` |
| `ContactController.java` | Zmieniono wywołanie `contactService.getContact` na nową sygnaturę (przekazuje `userId` i `isAgent`) |
| `AppUserRepository.java` | Dodano brakujące `clearAutomatically = true` do `softDeleteUser` |
| `ContactServiceTest.java` | Zaktualizowano wywołania `getContact` na nową sygnaturę; dodano 2 nowe testy: `agentSeesOwnContact` i `agentCannotSeeOtherAgentContact` |

## Naprawione na podstawie otwartych uwag CR (2026-03-20)

| Plik | Zmiana |
|------|--------|
| `AuditAspect.java` | `captureOldValue` używa `EntityManager.find()` + mapę `entityType → Class` zamiast getter serwisu przez proxy (#8) |
| `AuditLogConsumer.java` | Usunięto `@Transactional` z konsumenta; transakcją zarządza `AuditLogRepository`; zaktualizowano Javadoc (#16) |
| `ContactRepository.java` | `channelMetadataToJson` zastąpiona przez `ObjectMapper.writeValueAsString()` (W1) |
| `ContactRepository.java` | `findTenantsWithRecordings` – dodano guard sprawdzający brak aktywnego `TenantContext` z `IllegalStateException`; zaktualizowano Javadoc (W5) |
| `Contact.java` | Usunięto `@PrePersist`/`@PreUpdate` (martwy kod dla tabel partycjonowanych); dodano Javadoc na klasie (W2) |
| `ContactController.java` | Wyciągnięto `currentUserIsAgent()` – eliminuje 3 copy-paste bloki (W4) |
| `ContactController.java` | Dodano `@Validated` + `@Pattern` na `@RequestParam status` i `@RequestParam channel` (W6) |
| `GlobalExceptionHandler.java` | Dodano handler `ConstraintViolationException` → HTTP 400 z mapą błędów (W6) |
| `ContactId.java` | Dodano `serialVersionUID = 1L` (S2) |

---

## Review: BE-020 (Queue API) — 2026-03-21

### Pliki: `Queue.java`, `QueueRepository.java`, `QueueService.java`, `QueueController.java`, `CreateQueueRequest.java`, `UpdateQueueRequest.java`, `QueueResponse.java`, `QueueServiceTest.java`

---

### Bugs / Critical Issues

**[Queue.java:19–21] `@PrePersist` / `@PreUpdate` są martwym kodem — encja zapisywana wyłącznie przez natywny SQL**

Javadoc klasy wprost stwierdza: `tabela queue nie posiada kolumny is_deleted`. `QueueRepository` używa natywnego INSERT i UPDATE — Hibernate lifecycle callbacks (`@PrePersist`, `@PreUpdate`) **nigdy** nie są wywoływane przy natywnym SQL. W `createQueue` serwis ustawia `queue.setCreatedAt(Instant.now())` ręcznie po `.build()` (l. 91), co potwierdza świadomość problemu. Jednak `@PreUpdate.onUpdate()` na linii 99 pozostaje martwym kodem — `updated_at` jest ustawiane przez `NOW()` w natywnym UPDATE, nie przez callback. Ryzyko: jeśli ktoś użyje `em.merge(queue)` zamiast natywnego SQL (np. refaktor), timestamps nie będą poprawnie ustawiane przez callback, bo `createdAt` jest ustawiane poza nim. Wzorzec niespójny z `Contact.java` który po CR ma explicite usunięte callbacks z dokumentującym Javadoc.

Sugestia: Usunąć `@PrePersist` i `@PreUpdate`, dodać Javadoc analogicznie do `Contact.java` wyjaśniający, że timestamps są ustawiane ręcznie przed natywnym INSERT/UPDATE.

**[QueueRepository.java:335–350] `skillsToJson` — ręczna serializacja JSON jest podatna na błędy dla niestandardowych znaków**

```java
sb.append(skills.get(i).replace("\\", "\\\\").replace("\"", "\\\""));
```

Metoda eskejpuje tylko backslash i cudzysłów. Nie obsługuje: znaków kontrolnych (`\n`, `\r`, `\t`), Unicode surrogate pairs, null bytes. Łańcuch `"SKILL\nNEW"` zostanie zapisany do JSONB jako `["SKILL\nNEW"]` — technicznie niepoprawny JSON (literal newline w stringu). PostgreSQL przy castowaniu `CAST(:requiredSkills AS jsonb)` odrzuci taki wejście z błędem. Analogiczny problem w `ContactRepository` był naprawiony przez CR-027 (zastąpienie `ObjectMapper.writeValueAsString()`). Ten sam błąd istnieje w `QueueRepository`.

Sugestia: Wstrzyknąć `ObjectMapper` przez konstruktor i używać `objectMapper.writeValueAsString(skills)` zamiast ręcznej serializacji. Dodać `try/catch JsonProcessingException` z fallbackiem na `"[]"` i logiem WARNING — analogicznie do `ContactRepository`.

**[QueueService.java:87–88] `active` pole z `request.active() != null ? request.active() : true` — potencjalnie mylące dla klienta API**

```java
.active(request.active() != null ? request.active() : true)
```

`CreateQueueRequest` ma pole `Boolean active` (boxed, nullable). Gdy klient nie poda `active` w JSON, wartość to `null`, a serwis zastosuje domyślną `true`. Brak dokumentacji w Swagger/OpenAPI, że domyślna wartość to `true`. Pole `active` nie ma `@Schema(defaultValue = "true")` w DTO. Klientowi API nie jest jasne, jakie jest domyślne zachowanie przy pominięciu pola.

Sugestia: Dodać `@Schema(description = "Czy kolejka jest aktywna", defaultValue = "true")` do pola `active` w `CreateQueueRequest`.

---

### Security Concerns

**[QueueController.java:49] `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` — brak izolacji ADMIN do własnego tenant scope**

`@PreAuthorize` dopuszcza rolę `ADMIN`. Admin w tej aplikacji jest rolą globalną (zarządza platformą), ale `QueueController` pobiera `tenantId` z `TenantContext`. Jeśli ADMIN loguje się bez kontekstu konkretnego tenanta (np. globalny admin platformy), `TenantContext.getTenantId()` może zwrócić UUID tenanta z JWT admina — co jest poprawne jeśli admin ma przypisany tenant. Pytanie architektoniczne: czy globalny ADMIN powinien mieć możliwość zarządzania kolejkami **każdego** tenanta, czy tylko swojego? Jeśli tylko swojego (multi-tenant SaaS), bieżące zachowanie jest poprawne. Jeśli ADMIN ma cross-tenant access (np. support), potrzebny jest osobny mechanizm.

Uwaga: ten sam wzorzec `hasAnyRole('SUPERVISOR', 'ADMIN')` jest stosowany w innych kontrolerach, więc nie jest nową regresją. Warto udokumentować decyzję architektoniczną w Javadoc kontrolera.

**[CreateQueueRequest.java:35] Brak `@Size` na `name` — możliwy DB error zamiast walidacji 400**

```java
@NotBlank(message = "Nazwa kolejki jest wymagana")
String name,
```

Pole `name` ma `@NotBlank` ale brak `@Size(max = 255)`. Kolumna DB `name VARCHAR(255 NOT NULL)`. Klient może przesłać string > 255 znaków — PostgreSQL rzuci `DataException` (org.postgresql), która nie jest obsłużona przez `GlobalExceptionHandler` i zmapuje się na HTTP 500 zamiast HTTP 400. Naruszenie zasady walidacji na poziomie DTO.

Sugestia: Dodać `@Size(max = 255, message = "Nazwa kolejki nie może przekraczać 255 znaków")`.

**[UpdateQueueRequest.java:23] Brak `@NotBlank` gdy `name` nie jest null — can set empty name on PATCH**

Przy PATCH jeśli klient prześle `{"name": ""}`, walidacja przejdzie (brak `@NotBlank`, jest tylko sprawdzenie null w serwisie `if (request.name() != null)`). Kolejka otrzyma nazwę pustego stringa. Powinna być walidacja: gdy `name != null`, musi spełniać `@NotBlank`.

Sugestia: Dodać `@NotBlank` z adnotacją `@NotBlank` i konfigurację `@Size(max = 255)` — obie walidacje stosują się tylko gdy wartość nie jest null w Jakarta Validation.

Uwaga: `@NotBlank` na `null` jest ignorowany (null jest dozwolony), więc PATCH z brakującym polem `name` nadal zadziała — dodanie `@NotBlank` nie zmieni semantyki null-as-no-op.

---

### Architecture / Pattern Violations

**[QueueRepository.java:44] `findAllByTenantId` filtruje tylko `is_active = true` — brak dostępu do nieaktywnych kolejek dla ADMIN/operacji audytu**

Metoda listy zwraca wyłącznie aktywne kolejki. Po deaktywacji (`softDelete`) kolejka jest niewidoczna przez API. `QueueController` nie ma endpointu `GET /api/queues?includeInactive=true`. Dla audytu, migracji danych i wsparcia technicznego konieczny jest dostęp do historii kolejek. To może być świadoma decyzja produktowa, ale nie jest udokumentowana.

**[QueueRepository.java:287–289] `em.flush(); em.clear()` po `update()` — potencjalnie destrukcyjne dla zewnętrznych transakcji**

```java
em.flush();
em.clear();
```

`em.clear()` usuwa **wszystkie** encje z L1 cache EntityManagera — nie tylko Queue. Jeśli `update()` jest wywoływane w transakcji, która wcześniej załadowała inne encje (np. `AppUser`, `Contact`), te encje zostaną usunięte z cache. Przy leniwym ładowaniu (lazy collections) po `em.clear()` dostęp do lazy pól rzuci `LazyInitializationException`. W `QueueService.updateQueue` (l. 184–192) sekwencja to: `findQueueOrThrow` → `queueRepository.update()` (clear) → `findQueueOrThrow` ponownie. Brak lazy associations w `Queue`, więc aktualnie bezpieczne. Ale wzorzec jest ryzykowny — przy rozszerzeniu encji o relacje może powodować trudne do debugowania wyjątki.

Sugestia: Zamiast `em.clear()` użyć `em.refresh(queue)` lub odrębnej metody `findById` po UPDATE (tak jak robi to ContactRepository). Alternatywnie zachować `em.flush(); em.clear()` ale z wyraźnym komentarzem ostrzegającym.

**[Queue.java:33] Brak `@Column(name = "queue_id")` z `@GeneratedValue`**

```java
@Id
@Column(name = "queue_id")
private UUID queueId;
```

`queueId` nie ma `@GeneratedValue`. Generowanie UUID odbywa się w `@PrePersist.onCreate()` (martwy kod przy natywnym INSERT) i w serwisie (`UUID.randomUUID()`). Wzorzec jest konsekwentny z innymi encjami w projekcie (np. `Contact`). Brak błędu, ale adnotacja `@GeneratedValue(strategy = GenerationType.AUTO)` lub `@UuidGenerator` mogłaby zastąpić ręczne generowanie jeśli encja miałaby kiedyś być zapisywana przez `em.persist()`.

**[QueueService.java:114] Duplikacja nazwy zmiennej `page_` — nieczytelny kod**

```java
PagedResponse<Queue> page_ = queueRepository.findAllByTenantId(tenantId, name, page, size);
```

Zmienna o nazwie `page_` z podkreśleniem to obejście konfliktu z parametrem `page`. Lepszym rozwiązaniem jest rename parametru: `int pageNumber` lub `int pageIndex` — spójnie z innymi metodami serwisów.

---

### Improvements & Suggestions

**[QueueServiceTest.java:38] `@MockitoSettings(strictness = Strictness.LENIENT)` — maskuje niepotrzebne stuby**

`LENIENT` wyłącza ostrzeżenia o nieużywanych stubach. W testach `shouldCreateQueueSuccessfully` i `shouldApplyDefaultValuesForOptionalFields` zarówno `tenantResourceLimitService` jak i `queueRepository` są stubbowane — oba są używane. Brak oczywistego powodu dla `LENIENT`. Przywrócenie domyślnego `STRICT_STUBS` pomoże wykryć przyszłe redundantne stuby.

**[QueueServiceTest.java — brak testu] Brak testu dla `updateQueue` — żaden test nie weryfikuje logiki PATCH**

`updateQueue` zawiera logikę PATCH (pola null ignorowane) oraz przypadek gdy `queueRepository.update()` zwraca 0 (kolejka zniknęła między `findQueueOrThrow` a `update`). Żaden test nie pokrywa:
- aktualizacji podzbioru pól (null fields ignored),
- wyjątku `EntityNotFoundException` gdy `updated == 0`,
- odświeżenia danych po UPDATE.

Testy dla `listQueues` również brakuje.

**[QueueServiceTest.java — brak testu] Brak testu dla race condition w `deleteQueue`**

`deleteQueue` wywołuje `findQueueOrThrow` (sprawdza istnienie) → `hasActiveContacts` → `softDelete`. Gdy między check a delete kolejka zostaje usunięta przez inną sesję, `softDelete` zwraca 0 → `EntityNotFoundException`. Test `shouldThrowWhenQueueNotFoundOnDelete` sprawdza tylko brak kolejki na etapie `findQueueOrThrow`. Brak testu dla przypadku gdy `softDelete` zwraca 0 (TOCTOU scenario).

**[QueueController.java:80] `TenantContext.getTenantId()` — brak null-check**

Każdy endpoint wywołuje `TenantContext.getTenantId()` bez sprawdzania null. `getTenantId()` rzuca `IllegalStateException` gdy kontekst nie jest ustawiony. `GlobalExceptionHandler` prawdopodobnie mapuje `IllegalStateException` na HTTP 500. Jest to poprawne zachowanie (brak TenantContext to błąd konfiguracji filtrów), ale warto to udokumentować. Wzorzec identyczny z innymi kontrolerami — nie jest nową regresją.

**[QueueResponse.java:53] Mutowalna lista w rekordzie DTO**

```java
queue.getRequiredSkills(),
```

`requiredSkills` to `ArrayList<String>` — mutowalny. Rekord `QueueResponse` przechowuje bezpośrednią referencję. Ktoś mający dostęp do encji mógłby modyfikować listę przez DTO. Rozważ `Collections.unmodifiableList(queue.getRequiredSkills())` lub `List.copyOf(queue.getRequiredSkills())`.

---

### Positive Observations

- **`QueueRepository` prawidłowo rozszerza `TenantAwareRepository`** i wywołuje `assertSameTenant()` w każdej metodzie write (`insert`, `update`, `softDelete`). Wzorzec multi-tenancy przestrzegany konsekwentnie.
- **`setTenantContextInDb(tenantId)` wywoływane jawnie** z przekazanym `tenantId` zamiast wersji bez argumentu — właściwy wybór dla repozytoriów gdzie TenantContext może być niedostępny (async, scheduled).
- **Weryfikacja istnienia przed deaktywacją** (`findQueueOrThrow` + `hasActiveContacts` + `softDelete`) — logika biznesowa poprawnie sprawdza aktualne kontakty w kolejce przed usunięciem.
- **`@Pattern` na `routingStrategy`** w obu DTO (`CreateQueueRequest`, `UpdateQueueRequest`) — walidacja na poziomie API spójna z ENUM w DB.
- **Paginacja `PagedResponse`** zwracana konsekwentnie z `first`, `last`, `totalElements`, `totalPages` — spójne z resztą API.
- **Testy z `@Nested` i `@DisplayName`** — czytelna struktura, Arrange-When-Then w każdym teście, weryfikacja braku wywołań przez `verify(..., never())`.
- **`/routing-strategies` przed `/{id}`** — świadomy komentarz o kolejności tras Spring MVC, unikający konfliktu z UUID path variable.

### Summary

Implementacja poprawnie stosuje wzorce multi-tenancy i zawiera solidną dokumentację. Trzy kwestie wymagają poprawy przed merge: ręczna serializacja JSON (`skillsToJson`) z lukami dla znaków kontrolnych, martwy kod `@PrePersist`/`@PreUpdate` i brak `@Size(max=255)` na polu `name` w DTO. Brak testów dla `updateQueue` to widoczna luka w pokryciu.

**Ocena: 3.5/5** — solidna podstawa z kilkoma istotnymi usterkami (bug serializacji JSON, walidacja DTO) które powinny być naprawione przed merge.

---

## Pozytywne aspekty

- **Właściwa obsługa partycjonowania PostgreSQL.** Zapis przez natywny INSERT z pełnym castowaniem typów, UPDATE z kluczem partycji `started_at` w WHERE — unika full-partition-scan. Dobrze udokumentowane.

- **`ContactRepository` prawidłowo rozszerza `TenantAwareRepository`** i wywołuje `setTenantContextInDb(tenantId)` + `assertSameTenant()` konsekwentnie we wszystkich metodach write. Wzorzec zachowany.

- **Izolacja AGENT prawidłowo zaimplementowana w `listContacts`.** `effectiveAgentId = isAgent ? userId : params.agentId()` poprawnie nadpisuje przekazany filtr, uniemożliwiając agentowi podanie `agentId` innego agenta w query params.

- **`MAX_PAGE_SIZE = 100` w `ContactService`** ogranicza maksymalny rozmiar strony, spójnie z innymi serwisami projektu.

- **`PagedResponse` z pełnymi metadanymi** (`totalElements`, `totalPages`, `first`, `last`) — spójne z `CustomerController` i nowszymi endpointami.

- **Walidacja DTO na `CreateContactRequest`** — `@NotBlank` + `@Pattern` na `channel` i `direction` z wyraźnymi enumerable wartościami. `DispositionRequest` ma `@NotBlank` + `@Size(max=50)`.

- **`@Operation` / `@ApiResponse` na wszystkich endpointach** — Swagger UI dokumentuje wszystkie kody odpowiedzi, łącznie z 409 dla naruszeń reguł biznesowych.

- **Testy jednostkowe pokrywają kluczowe ścieżki** — graniczne przypadki dla AGENT vs SUPERVISOR, maksymalny rozmiar strony, brakujące kontakty, metadane paginacji. Podejście `@Nested` + `@DisplayName` poprawia czytelność.

- **Logowanie z kontekstem tenanta** — MDC jest już ustawiane przez `TenantFilter`. Logi w serwisie i repozytorium zawierają `tenantId` i `contactId` dla korelacji.

---

## Podsumowanie

**Ocena BE-027: ~~3.5/5~~ → 4.5/5** (po poprawkach 2026-03-20)

Implementacja Contact API solidna strukturalnie. Wszystkie krytyczne i ważne błędy naprawione: weryfikacja własności dla AGENT, stale L1 cache, brak `assertSameTenant`, `ObjectMapper` zamiast ręcznej serializacji, martwy `@PrePersist`/`@PreUpdate`, walidacja filtrów 400 zamiast 500. Otwarte jedynie sugestie S1 (whitelist disposition codes), S3 (test ON_HOLD), S4 (dokumentacja READ COMMITTED).

**Ocena ogólna backendu (po wszystkich poprawkach 2026-03-20): ~~3.5/5~~ → 4.5/5**

Naprawiono łącznie 18/20 uwag z poprzedniego review + wszystkie ważne z BE-027. Pozostałe otwarte: #9 (virtual threads risk — brak włączonego profilu, ryzyko przyszłe), #17 (circular dep `@Lazy` — zaakceptowane). Otwarte sugestie S1, S3, S4 z BE-027 nie blokują release.

---

## Review: BE-019 (Routing Engine) — 2026-03-21

### Pliki: `RoutingEngine.java`, `DefaultRoutingEngine.java`, `RoutingService.java`, `RoutingRequest.java`, `RoutingResult.java`, `AgentSessionData.java`, `ContactAssignedEvent.java`, `ContactQueuedMessage.java`, `AppUserRepository.java` (modyfikacja), `RabbitMQConfig.java` (modyfikacja), `DefaultRoutingEngineTest.java`, `RoutingServiceTest.java`

---

### Bugs / Critical Issues

**[RoutingService.java:77] `@Async` + `@Transactional` — transakcja otwierana w wątku async executor, ale `TenantContext` jest pusty**

`routeContact` jest annotowana `@Async` i `@Transactional`. Wywołanie z `onContactQueued` (wątek AMQP) deleguje wykonanie do puli wątków `cc-async-`. W wątku AMQP `TenantContext` **nigdy** nie jest ustawiany — jest to wątek infrastrukturalny, nie HTTP request thread. `TenantContext` nie używa snapshot/restore. W efekcie gdy `routeContact` wykona `queueRepository.findByIdAndTenantId(queueId, tenantId)`:
1. `TenantAwareRepository.setTenantContextInDb(tenantId)` wywoła `SELECT set_tenant_context(?)` — a dokładniej parametr pobiera z jawnie przekazanego `tenantId`, więc RLS jest ustawiane poprawnie.
2. Jednak `AppUserRepository` NIE rozszerza `TenantAwareRepository` — nie wywołuje `set_tenant_context()` i nie używa RLS. Zapytania `findByIdAndTenantIdAndDeletedFalse` i `countActiveContactsByAgentId` mają jawny filtr `tenantId` w WHERE — co jest prawidłowe.

Aktualnie nie ma wycieku danych z powodu explicite filtrowanych zapytań. Natomiast naruszony jest wzorzec architektoniczny: `@Async` bez `TenantContext.snapshot()/restore()/clear()`. Jeśli ktoś doda nową metodę w `routeContact` która korzysta z `TenantContext.getTenantId()` (np. dla logu MDC, audytu, WebSocket broadcast), rzuci `IllegalStateException` w runtime. `CrossTenantAspect.verifyTenantContext` loguje ERROR ale nie rzuca wyjątku — problemy będą trudne do zidentyfikowania.

Sugestia: przekazać `tenantId` do `routeContact` i na początku metody wywołać `TenantContext.restore(new TenantContext.Snapshot(tenantId, null, null))` z `finally { TenantContext.clear(); }`. Alternatywnie udokumentować w Javadoc jako świadomą decyzję z wylistowaniem wszystkich metod które NIE mogą używać `TenantContext` w tym flow.

---

**[RoutingService.java:116–118] Nieskończona pętla retry: `publishQueuedEvent` ponownie publikuje `contact.queued` — `onContactQueued` odbierze tę samą wiadomość**

```java
// routeContact gdy brak agentów:
publishQueuedEvent(contactId, queueId, tenantId);  // publikuje contact.queued

// onContactQueued odbiera contact.queued:
routeContact(event.contactId(), event.queueId(), event.tenantId());  // znów szuka agenta
// → znów brak agentów → znów publishQueuedEvent → nieskończona pętla
```

Gdy brak dostępnych agentów, `routeContact` publikuje `contact.queued` na exchange `cc.events`. Kolejka `cc.queue.contact-routing` jest zbindowana do tego exchange z routing key `contact.queued`. `onContactQueued` natychmiast odbierze wiadomość i ponownie wywoła `routeContact`. Przy wciąż braku agentów — pętla się powtarza bez żadnego limitu ani opóźnienia. `x-message-ttl: 30000ms` na kolejce ogranicza czas życia wiadomości, ale przez 30 sekund aplikacja będzie w tight loop przetwarzając kontakt w kółko, wykonując zapytania do Redis i bazy danych.

Jest to krytyczny błąd logiczny w architekturze retry. Wiadomość `contact.queued` powinna być publikowana wyłącznie przez oryginalnego nadawcę (np. `TelephonyEventPublisher` przy przychodzącym połączeniu), nie przez `RoutingService` sam do siebie.

Sugestia: usunąć `publishQueuedEvent` z gałęzi "brak agentów" w `routeContact`. Status kontaktu pozostaje `QUEUED` w bazie. Zewnętrzny mechanizm (np. scheduled job co N sekund lub event `agent.status.changed`) powinien wyzwalać ponowną próbę routingu dla kontaktów z statusem `QUEUED`.

---

**[DefaultRoutingEngine.java:289–298] SCAN po wszystkich kluczach `session:agent:*` — brak filtrowania po tenancie na poziomie Redis**

```java
ScanOptions options = ScanOptions.scanOptions()
        .match(AGENT_SESSION_KEY_PATTERN)  // "session:agent:*" — wszystkie tenanty
        .count(200)
        .build();
```

SCAN pobiera **wszystkie** klucze sesji agentów ze wszystkich tenantów, a filtrowanie po `tenantId` odbywa się dopiero w Javie (linia 308–310: `session.belongsToTenant(tenantId)`). W środowisku z 100 tenantami po 50 agentów każdy (5000 kluczy), routing dla jednego tenanta z 50 agentami skanuje 5000 kluczy tylko po to, by odrzucić 4950 z nich. Przy wielu równoległych routingach (np. 20 jednocześnie) generuje to 100000 odczytów Redis per sekunda.

Rozwiązanie z kluczem per-tenant: `session:agent:{tenantId}:*` lub zbiorem `SET` per-tenant (`session:agents:{tenantId}` jako Redis Set UUID agentów z kluczami `session:agent:{userId}` dla danych). SCAN z wzorcem `session:agent:{tenantId}:*` pozwoliłby na filtrowanie na poziomie Redis.

Uwaga: zmiana klucza wymaga aktualizacji `UserService` który zapisuje klucze sesji. Przy obecnej skali (dev/staging) nie jest to blokujące, ale powinno być zaplanowane przed skalowaniem.

---

### Security Concerns

**[DefaultRoutingEngine.java:137–141] Sticky agent: weryfikacja przynależności do tenanta opiera się wyłącznie na danych z Redis**

```java
if (!session.isAvailable() || !session.belongsToTenant(request.tenantId())) {
    return Optional.empty();
}
```

Dane w Redis mogą zostać zmodyfikowane przez inny komponent lub operację administracyjną, która niepoprawnie zapisze `tenantId`. W teorii (np. błąd w `UserService.updateStatus`) sesja agenta X z tenanta A mogłaby zawierać `tenantId` tenanta B. Silnik routingu zaakceptowałby takiego agenta dla tenanta B.

Obecna ochrona: `agentHasRequiredSkills` weryfikuje agenta przez `appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)` — to zapytanie do bazy z explicite podanym `tenantId` w WHERE. Więc weryfikacja przez DB jest wykonywana gdy jest wymagane dopasowanie skills. Gdy `requiredSkills` jest pusty i strategia jest inna niż SKILL_BASED, DB check nie jest wykonywany — jedyną ochroną jest wartość z Redis.

Sugestia: dla sticky agent zawsze wykonywać `appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)` niezależnie od skills — to jest już jednorazowy SELECT per routing, akceptowalny koszt dla bezpieczeństwa multi-tenant.

---

**[RoutingService.java:82–84] `EntityNotFoundException` wewnątrz `@Async` — nieobsługiwany wyjątek zależy od `AsyncUncaughtExceptionHandler`**

```java
Queue queue = queueRepository.findByIdAndTenantId(queueId, tenantId)
        .orElseThrow(() -> new EntityNotFoundException(...));
```

`routeContact` jest `@Async`. Gdy `EntityNotFoundException` zostanie rzucony (kolejka usunięta między publikacją eventu a jego przetworzeniem), wyjątek nie propaguje do wywołującego (wywołujący otrzymuje `CompletableFuture` z błędem). `AsyncConfig.getAsyncUncaughtExceptionHandler()` loguje błąd. Jednak `onContactQueued` wywołuje `routeContact(...)` bez oczekiwania na `Future` — zwrócona wartość jest zignorowana. Wyjątek z wątku async nie wróci do wątku AMQP, więc wiadomość **zostanie potwierdzona (ACK)** przez Spring AMQP, mimo że routing zakończył się błędem. Kontakt pozostanie w statusie `QUEUED` bez żadnej akcji naprawczej.

Jest to fundamentalny problem z `@Async` na `routeContact` wywoływanym z `@RabbitListener`: albo `routeContact` jest synchroniczny (blokuje wątek AMQP, ale błędy powodują NACK/retry), albo `@Async` wymaga jawnego obsłużenia `CompletableFuture` w listenerze.

Sugestia: usunąć `@Async` z `routeContact` — wątek AMQP jest dedykowany i blokowanie go przez czas routingu (kilka zapytań Redis + SQL) jest akceptowalne. Czas przetwarzania jest krótki (< 100ms przy małej liczbie agentów). Jeśli `@Async` jest wymagany, `onContactQueued` musi obsłużyć `CompletableFuture`:
```java
routeContact(...)
  .exceptionally(e -> { throw new RuntimeException("...", e); });
```

---

### Architecture / Pattern Violations

**[DefaultRoutingEngine.java:442–462] N+1 zapytań do bazy w `findAgentWithLeastActiveContacts` — zapytanie per agent w pętli**

```java
for (UUID agentId : sorted) {
    long count = appUserRepository.countActiveContactsByAgentId(agentId, tenantId);
    // ...
}
```

Przy 20 kwalifikowanych agentach — 20 zapytań SQL. Javadoc sam zauważa ten problem: _"dla małych list (< 50 agentów online) akceptowalne"_. Jednak przy strategii SKILL_BASED która jest domyślną dla kolejek specjalistycznych, ta ścieżka jest hot path. Każde wywołanie `routeContact` wykonuje do 50 zapytań.

Sugestia: batch query — jeden SELECT z GROUP BY:
```sql
SELECT agent_id, COUNT(*) FROM contact
WHERE agent_id IN (:agentIds)
  AND tenant_id = :tenantId
  AND status IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
GROUP BY agent_id
```
Wynik jako `Map<UUID, Long>` w jednym zapytaniu. Dodać jako nową metodę `countActiveContactsByAgentIds(List<UUID> agentIds, UUID tenantId)` w `AppUserRepository`.

---

**[RoutingService.java:40] `RoutingService` nie rozszerza żadnego interfejsu domenowego — trudność testowania i rozszerzalności**

`DefaultRoutingEngine` ma interfejs `RoutingEngine` z adnotacją `@Primary`. `RoutingService` nie ma analogicznego interfejsu. Utrudnia to mockowanie w testach integracyjnych (gdzie nie chcemy uruchamiać rzeczywistego routingu) i zastąpienie implementacji w środowiskach enterprise. Obecne testy jednostkowe mockują `RoutingEngine` ale muszą tworzyć pełny `RoutingService`.

---

**[RabbitMQConfig.java:56] `QUEUE_CONTACT_ROUTING` zbindowany do `cc.events` z routing key `contact.queued` — ta sama kolejka publikuje i konsumuje**

`RoutingService.publishQueuedEvent` publikuje na `cc.events` z routing key `contact.queued`. Ta sama kolejka `cc.queue.contact-routing` jest zbindowana do `cc.events` z routing key `contact.queued`. Oznacza to, że `RoutingService` słucha na wiadomości, które sam produkuje (w scenariuszu "brak agentów"). Jest to oczekiwane tylko w scenariuszu retry — ale jak opisano w błędzie krytycznym #2, prowadzi to do nieskończonej pętli.

Dodatkowe ryzyko: jeśli inny komponent (np. `TelephonyEventPublisher`) opublikuje `contact.queued` z innym payload format niż `ContactQueuedMessage`, Jackson rzuci `MessageConversionException` podczas deserializacji w `onContactQueued` — wiadomość trafi do DLQ bez retry.

---

### Improvements & Suggestions

**[DefaultRoutingEngine.java:230–234] ROUND_ROBIN: `counter - 1` przy counter=0 (po fallback) zwraca ujemną wartość przed `Math.abs()`**

```java
Long counter = redisTemplate.opsForValue().increment(counterKey);
if (counter == null) {
    counter = 0L;
}
int index = (int) (Math.abs(counter - 1) % sorted.size());
```

Gdy `counter == null` fallbackuje do `0L`, wynik `Math.abs(0 - 1) % size = 1 % size`. Dla `size == 1` wynik to `0` (poprawny). Dla `size >= 2` wynik to `1`, co pomija pierwszego agenta i zawsze wybiera drugiego. Brak buga przy normalnym działaniu (Redis zwraca zawsze non-null dla INCR), ale wartość fallback `0L` daje mylący wynik.

Sugestia: fallback powinien zwrócić `1L` (pierwsze wywołanie INCR) lub użyć `counter != null ? counter : 1L`.

**[DefaultRoutingEngineTest.java:284] `lenient()` na `findByIdAndTenantIdAndDeletedFalse` w teście sticky — niejasny powód**

```java
lenient().when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(AGENT_A, TENANT_ID))
        .thenReturn(Optional.of(buildAgent(AGENT_A, List.of("SALES"))));
```

`lenient()` jest używane selektywnie — w niektórych testach sticky bez widocznego powodu (test `shouldSelectPreferredAgentWhenAvailable`). Mockito strict stubs wymagałoby usunięcia stubu lub wyjaśnienia. Warto albo usunąć `lenient()` gdy stub jest faktycznie używany, albo dodać komentarz.

**[RoutingServiceTest.java:275–289] `onContactQueued` test nie weryfikuje, że `@Async` nie jest blokujące**

Test `shouldCallRouteContactForReceivedEvent` wywołuje `routingService.onContactQueued(message)` synchronicznie (bo `@Async` nie działa w testach jednostkowych bez Spring context). Test działa, ale nie weryfikuje zachowania asynchronicznego. Warto dodać komentarz informujący, że test ignoruje `@Async` i zachowanie w runtime jest inne.

**[ContactQueuedMessage.java] Brak `@JsonIgnoreProperties(ignoreUnknown = true)` — deserializacja wrażliwa na rozszerzenie modelu**

```java
public record ContactQueuedMessage(UUID contactId, UUID queueId, UUID tenantId) {}
```

Jeśli inny komponent opublikuje `contact.queued` z dodatkowymi polami (np. `priority`, `channel`), Jackson przy domyślnej konfiguracji rzuci `UnrecognizedPropertyException`. Dla wiadomości RabbitMQ zalecane jest `@JsonIgnoreProperties(ignoreUnknown = true)` — wiadomości są publicznym kontraktem i powinny być odporne na rozszerzenia.

---

### Positive Observations

- **Redis SCAN zamiast KEYS** — `connection.keyCommands().scan(ScanOptions)` z `count=200` jest poprawnym podejściem dla środowisk produkcyjnych. Iteratywny SCAN nie blokuje Redis event loop. Javadoc w kodzie i interfejsie explicite dokumentuje tę decyzję.
- **Izolacja multi-tenant w `AgentSessionData.belongsToTenant()`** — sprawdzenie `tenantId != null && tenantId.equals(this.tenantId)` chroni przed NPE. Filtrowanie po tenancie odbywa się przed zwróceniem listy kandydatów.
- **Deterministyczny wybór w ROUND_ROBIN i FIRST_AVAILABLE** — sortowanie po `UUID.toString()` zapewnia spójny wynik między instancjami aplikacji. Komentarz w Javadoc wyjaśnia dlaczego.
- **`@Primary` na `DefaultRoutingEngine`** — zgodnie z wzorcem `MockTelephonyAdapter`/`TelephonyAdapter`, umożliwia podpięcie alternatywnej implementacji bez modyfikacji konfiguracji.
- **`parseSessionData` obsługuje dwa formaty** (Map i String) z graceful fallback na null — defensywne programowanie dla danych zewnętrznych (Redis).
- **`contactRoutingQueue` z `x-message-ttl: 30000ms`** — ograniczenie czasu życia wiadomości routingu zapobiega przetwarzaniu przeterminowanych kontaktów. Dobra decyzja architektoniczna.
- **Testy jednostkowe z `@Nested`** — przejrzysta struktura, separacja testów per strategia, pomocnicze metody `stubScan` / `stubStickySession` / `stubAgentSkills` eliminują duplikację. Podejście spy na `scanAvailableAgents` jest uzasadnione i udokumentowane.
- **Javadoc na wszystkich klasach i metodach publicznych** — pełna dokumentacja intencji, kontraktu i ograniczeń.

---

### Summary

Implementacja ma solidną strukturę algorytmiczną i dobrą dokumentację, ale zawiera dwa krytyczne błędy architektoniczne: nieskończona pętla retry (`contact.queued` publikowany przez routing do samego siebie) oraz `@Async` + `@RabbitListener` bez obsługi `CompletableFuture` powodujący ciche ACK przy błędach. SCAN bez filtrowania per-tenant jest potencjalnym problemem wydajnościowym przy skali. Brak snapshot/restore TenantContext w wątku async narusza wzorzec architektoniczny projektu.

**Ocena: 3/5** — algorytm routingu poprawny, ale dwa krytyczne błędy architektoniczne muszą być naprawione przed merge: nieskończona pętla retry i `@Async`/AMQP bez obsługi Future.
