# Backend – dokumentacja techniczna

> Dokument onboardingowy dla warstwy backendowej Contact Center SaaS (`backend/`).
> Stack: **Java 21 + Spring Boot 3.3.x**, **PostgreSQL 16** (Flyway, Row Level Security),
> **Redis** (sesje IVR, cache, rate limiting, blacklisty), **RabbitMQ** (eventy domenowe,
> async processing), **MinIO/S3** (nagrania rozmów), **Twilio Programmable Voice** (CPaaS),
> opcjonalna mikrousługa **Python/FastAPI** (`voicebot/`) do ASR+NLU.

Build: Maven (`mvn package -pl app`). Główny moduł: `backend/app`, kod produkcyjny w
`backend/app/src/main/java/com/contactcenter/`, migracje SQL w
`backend/src/main/resources/db/migration/` (146 plików, V001…V073+).

---

## 1. Struktura modułów

Pakiety główne pod `com.contactcenter`:

```
api/             – kontrolery REST + DTO (warstwa wejścia/wyjścia)
app/             – ContactCenterApplication (main class)
domain/          – logika biznesowa, podzielona na pakiety per domena (zob. 1.x)
infrastructure/  – konfiguracja Spring, AOP, integracje (S3, ETL, social adapters)
security/        – JWT, TenantContext, filtry, MFA
```

Każdy moduł domenowy w `api/<moduł>` zawiera kontroler(y) + pakiet `dto/`. Logika
biznesowa żyje w `com.contactcenter.domain.<domena>` – dedykowanym pakiecie per domena
(np. `domain.campaign`, `domain.routing`, `domain.voicebot`, `domain.etl`, ...). Encje JPA,
repozytoria i serwisy danej domeny znajdują się w jej pakiecie. Wyjątkiem są dwa wspólne
"jądra" współdzielone przez wszystkie domeny:

- `domain.repository` – zawiera tylko `TenantAwareRepository` (klasa bazowa repozytoriów, RLS).
- `domain.messaging` – wspólne klasy bazowe dla konsumentów RabbitMQ
  (`TenantAwareConsumer`, `DeadLetterConsumer`).

Aktualna lista pakietów domenowych: `agentbreak`, `agentgroup`, `audit`, `campaign`,
`contact`, `customer`, `disposition`, `email`, `etl`, `exception`, `gdpr`, `ivr`,
`messaging`, `phonenumber`, `queue`, `recording`, `reporting`, `repository`, `retention`, `routing`,
`social`, `telephony`, `tenant`, `user`, `voicebot`, `websocket`.

### 1.0 Konwencje pakietów domenowych i enkapsulacja

Każda domena w `com.contactcenter.domain.<nazwa>` stosuje następujący wzorzec enkapsulacji
(obowiązujący także dla nowych domen/refaktorów):

1. **Repozytoria** (`extends TenantAwareRepository`, `@Repository`) są **package-private**
   (brak modyfikatora `public`) – dostępne tylko wewnątrz własnej domeny.
2. **Serwisy** są dzielone na `public interface XxxService` (kontrakt dla innych domen i dla
   `api/`) oraz package-private `class XxxServiceImpl implements XxxService` (`@Service`) –
   implementacja jest szczegółem domeny.
3. **Dostęp cross-domain** odbywa się wyłącznie przez `public interface XxxService` domeny
   właścicielskiej – nigdy bezpośrednio przez repozytorium lub konkretną klasę implementacyjną
   innej domeny.
4. **Cykliczne zależności** między domenami (gdy `ServiceA` potrzebuje `ServiceB`, a `ServiceB`
   potrzebuje `ServiceA`) rozwiązywane są przez `@Autowired @Lazy` setter injection.
5. **Wyjątki od podziału interfejs+Impl** są dopuszczalne dla klas czysto wewnętrznych, bez
   cross-domain DI po nazwie konkretnej klasy – np. `@Scheduled` joby, `@RabbitListener`
   konsumenci, klienci HTTP/integracji. Przykłady: `AgentBreakActivator`,
   `RecordingRetentionJob`, `SupervisorMetricsService`, `AiSummaryClient`, `EmailEmlService`,
   `AuditLogConsumer`, `DeadLetterConsumer`.
6. **Encje JPA, DTO, wyjątki i enumy** mogą być `public` – są to typy danych współdzielone
   pomiędzy warstwami (`api/`, `domain/`), nie implementacje logiki.

Przykład (domena `agentgroup`):

```
domain/agentgroup/
├── AgentGroup.java              – encja JPA (public)
├── AgentGroupOverview.java      – DTO/projekcja (public)
├── AgentGroupRepository.java    – package-private, extends TenantAwareRepository
├── AgentGroupService.java        – public interface
└── AgentGroupServiceImpl.java   – package-private @Service implements AgentGroupService
```

---

### 1.1 admin

**Cel:** metryki administracyjne (przegląd wszystkich tenantów) oraz status pipeline'u ETL.

| Klasa | Rola |
|---|---|
| `AdminMetricsController` | `/api/admin/metrics` – agregaty po tenantach |
| `EtlStatusController` | `/api/admin/etl` – status i ręczne wywołanie synchronizacji DW |
| `AdminMetricsService` (`domain.tenant`, interfejs + `AdminMetricsServiceImpl`) | logika agregacji metryk |

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/admin/metrics` | ADMIN | zbiorcze metryki wszystkich tenantów |
| GET | `/api/admin/metrics/tenants/{id}` | ADMIN | szczegółowe metryki jednego tenanta |
| GET | `/api/admin/etl/status` | ADMIN | status ostatniej synchronizacji ETL → DW |
| POST | `/api/admin/etl/trigger` | ADMIN | ręczne wywołanie cyklu ETL |

Relacje: `AdminMetricsService` odpytuje repozytoria innych modułów (cross-tenant –
endpoint nie jest scope'owany do jednego tenanta, dlatego rola ADMIN).

---

### 1.2 agentbreak

**Cel:** zarządzanie przerwami agentów (lunch, szkolenie itd.) i kalendarzem agenta
(przerwy + zaplanowane oddzwonienia + kampanie).

| Klasa | Rola |
|---|---|
| `AgentBreakController` | CRUD przerw agenta |
| `AgentCalendarController` | zagregowany widok kalendarza |
| `AgentBreak` (entity) | tabela `agent_break` |
| `AgentBreakRepository` | `TenantAwareRepository`, package-private |
| `AgentBreakService` (interfejs + `AgentBreakServiceImpl`) | logika CRUD + walidacja kolizji czasowych |
| `AgentCalendarService` (interfejs + `AgentCalendarServiceImpl`) | agreguje breaks + `ScheduledCallback` + kampanie |
| `AgentBreakActivator` | `@Scheduled` – automatyczna aktywacja/kończenie przerw |
| `BreakStatus`, `BreakType` | enumy |

Wszystkie powyższe klasy znajdują się w `com.contactcenter.domain.agentbreak`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/agent/breaks` | AGENT | lista przerw bieżącego agenta |
| POST | `/api/agent/breaks` | AGENT | utworzenie przerwy |
| PUT | `/api/agent/breaks/{id}` | AGENT | edycja przerwy |
| DELETE | `/api/agent/breaks/{id}` | AGENT | usunięcie przerwy |
| GET | `/api/agent/calendar` | AGENT | widok kalendarza (breaks + callbacks + kampanie) |

**Scheduler:** `AgentBreakActivator` (`agent.breaks.activator.enabled`, interwał
`agent.breaks.activator.interval-ms`, domyślnie 30s) – ustawia status agenta na
`ON_BREAK`/`AVAILABLE` w zależności od czasu rozpoczęcia/zakończenia przerwy.

---

### 1.3 agentgroup

**Cel:** grupowanie agentów (do przypisań kolejek/kampanii i widoczności w raportach).

| Klasa | Rola |
|---|---|
| `AgentGroupController` | `/api/agent-groups` (SUPERVISOR/ADMIN) |
| `AgentGroup` (entity) | tabela `agent_group` |
| `AgentGroupRepository` | CRUD + zarządzanie członkami, package-private |
| `AgentGroupService` (interfejs + `AgentGroupServiceImpl`) | logika domenowa |

Pakiet: `com.contactcenter.domain.agentgroup`.

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/agent-groups` | lista grup |
| POST | `/api/agent-groups` | utworzenie grupy |
| PUT | `/api/agent-groups/{groupId}` | edycja |
| DELETE | `/api/agent-groups/{groupId}` | usunięcie |
| GET | `/api/agent-groups/{groupId}/members` | lista członków |
| PUT | `/api/agent-groups/{groupId}/members` | zamiana całej listy członków (`ReplaceMembersRequest`) |

Relacje: grupy są referowane przez `Queue`/`Campaign` (przypisania – patrz moduły
`queue`, `campaign`) i wykorzystywane przez `RoutingEngine` (skill-based routing).

---

### 1.4 auditlog

**Cel:** dziennik audytu (CRUD na encjach wrażliwych: user, tenant, customer itd.).

| Klasa | Rola |
|---|---|
| `AuditLogController` | `/api/audit-logs` (ADMIN) – odczyt z paginacją |
| `AuditLog`, `AuditLogId`, `AuditLogEvent` | model encji + event RabbitMQ |
| `AuditLogRepository` | odczyt (tabela partycjonowana po czasie), package-private |
| `AuditLogService` (interfejs + `AuditLogServiceImpl`) | publikacja eventów do `cc.audit` |
| `AuditLogConsumer` | `@RabbitListener` na `cc.queue.audit-log` (extends `TenantAwareConsumer` z `domain.messaging`) – zapis do DB |
| `infrastructure/aspect/AuditAspect` + `@Audited` | AOP – przechwytuje metody serwisów oznaczone `@Audited` |

Pakiet: `com.contactcenter.domain.audit`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/audit-logs` | ADMIN | paginowana lista wpisów audytu z filtrami |

**Przepływ audytu:**
```
Serwis @Audited metoda
   → AuditAspect.around()
       → (opcjonalnie) EntityManager.find() – stary stan (old_value)
       → proceed() – wykonanie metody biznesowej
       → serializacja new_value (bez pól: password, mfaSecret, token, refreshToken...)
       → AuditLogService.publish(AuditLogEvent) → exchange cc.audit (routing key audit.#)
           → AuditLogConsumer (@RabbitListener QUEUE_AUDIT_LOG) → INSERT audit_log
```
Błędy audytu (serializacja/publikacja) są logowane, ale **nie przerywają** operacji
biznesowej.

---

### 1.5 auth

**Cel:** logowanie, refresh tokenów, MFA (TOTP), zmiana/reset hasła, rate limiting.

| Klasa | Rola |
|---|---|
| `AuthController` | `/api/auth/**` |
| `AuthService` (interfejs + `AuthServiceImpl`, pakiet `domain.user`) | logika logowania, refresh, MFA, blacklist |
| `JwtService` / `JwtParser` | wystawianie / parsowanie JWT RS256 (`security/`) |
| `MfaService` | TOTP (RFC 6238) (`security/`) |
| `LoginRateLimiter` | limit prób logowania (Redis) (`security/`) |
| `TokenBlacklistService` | blacklista access tokenów po logout (Redis) (`security/`) |
| `RefreshToken` (entity), `RefreshTokenRepository` | tabela `refresh_token`, pakiet `domain.user`, repozytorium package-private |

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/auth/login` | publiczny | logowanie (email+hasło → access+refresh token) |
| POST | `/api/auth/refresh` | publiczny | odnowienie access tokenu z refresh tokenu |
| POST | `/api/auth/logout` | auth | unieważnienie pojedynczego refresh tokenu + blacklista access tokenu |
| POST | `/api/auth/logout-all` | auth | unieważnienie wszystkich sesji użytkownika |
| GET | `/api/auth/mfa/setup` | auth | generuje TOTP secret + QR code |
| POST | `/api/auth/mfa/verify` | auth | weryfikacja kodu TOTP, aktywacja MFA |
| POST | `/api/auth/change-password` | auth | zmiana własnego hasła |
| POST | `/api/auth/force-reset/{userId}` | ADMIN/SUPERVISOR | wymuszenie resetu hasła innego użytkownika |

Szczegóły JWT i MFA – patrz sekcja 2 (Security).

---

### 1.6 campaign

**Cel:** kampanie outbound (predictive/progressive dialer), import kontaktów CSV,
przypisania agentów/grup, historia połączeń per rekord kampanii.

| Klasa | Rola |
|---|---|
| `CampaignController` | CRUD + lifecycle (start/pause/stop/draft) |
| `CampaignAssignmentController` | przypisania agentów/grup do kampanii |
| `CampaignContactHistoryController` | historia prób połączeń per rekord |
| `CampaignImportController` | async import CSV |
| `Campaign` (entity) | tabela `campaign` |
| `CampaignRepository`, `CampaignAssignmentRepository`, `CampaignContactRepository` | package-private |
| `CampaignService` (interfejs + `CampaignServiceImpl`) | CRUD, walidacja stanów (`DRAFT/RUNNING/PAUSED/STOPPED`) |
| `CampaignAssignmentService` (interfejs + `CampaignAssignmentServiceImpl`) | logika przypisań |
| `CampaignImportService` (interfejs + `CampaignImportServiceImpl`) | async import (Redis job status, `ImportJobStatus`) |
| `CampaignWindowActivator` | `@Scheduled` – aktywacja kampanii wg okna czasowego |
| `ProgressiveDialerService` (interfejs + `ProgressiveDialerServiceImpl`) | silnik wybierania numerów (patrz sekcja 5) |

Pakiet: `com.contactcenter.domain.campaign`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/campaigns` | ADMIN/SUPERVISOR | utworzenie kampanii |
| GET | `/api/campaigns/check-name` | ADMIN/SUPERVISOR | sprawdzenie unikalności nazwy |
| GET | `/api/campaigns` | ADMIN/SUPERVISOR | lista (paginacja, filtry) |
| GET | `/api/campaigns/{id}` | ADMIN/SUPERVISOR | szczegóły |
| PATCH | `/api/campaigns/{id}` | ADMIN/SUPERVISOR | edycja |
| POST | `/api/campaigns/{id}/start` \| `/pause` \| `/stop` \| `/draft` | ADMIN/SUPERVISOR | zmiana stanu kampanii |
| POST | `/api/campaigns/{id}/import` (multipart) | ADMIN/SUPERVISOR | import CSV kontaktów (async) |
| GET | `/api/campaigns/{id}/import-status/{jobId}` | ADMIN/SUPERVISOR | status importu (Redis) |
| GET | `/api/campaigns/{id}/contacts` | ADMIN/SUPERVISOR | lista rekordów kampanii |
| GET | `/api/campaigns/{campaignId}/assignment` | SUPERVISOR/ADMIN | przypisani agenci/grupy |
| PUT | `/api/campaigns/{campaignId}/assignment` | SUPERVISOR/ADMIN | zamiana przypisań |
| GET | `/api/campaigns/{campaignId}/contacts/{recordId}/attempts` | SUPERVISOR/ADMIN | historia prób połączeń rekordu |

**Import CSV (BE-023):** `CampaignImportService` → batch insert przez `JdbcTemplate`,
status zapisywany w Redis (`QUEUED/PROCESSING/COMPLETED/FAILED_PARTIAL`), unikalny
indeks z V027 zapobiega duplikatom.

---

### 1.7 contact

**Cel:** centralna historia kontaktów (calls, email, social, callbacks) – tabela
partycjonowana `contact`. To "rdzeń" CRM-owy, do którego odwołuje się większość modułów.

| Klasa | Rola |
|---|---|
| `ContactController` | główny kontroler – CRUD, dyspozycje, AI summary, recordingi |
| `Contact`, `ContactId`, `ContactEvent`, `ContactAiSummary` | encje |
| `ContactRepository` (rozszerzony przez wiele BE-xxx) | `TenantAwareRepository`, INSERT/UPDATE z `CAST(:x AS VARCHAR)` (po V025 – patrz uwagi) |
| `ContactEventRepository`, `ContactAiSummaryRepository`, `ContactTranscriptionRepository` | |
| `ContactService` (interfejs + `ContactServiceImpl`) | logika domenowa: tworzenie, dyspozycje, akceptacja/abandon, transfery |
| `ContactEventService` (interfejs + `ContactEventServiceImpl`) | log zdarzeń (timeline) kontaktu |
| `AiSummaryService` (interfejs + `AiSummaryServiceImpl`) / `AiSummaryClient` | generowanie podsumowań AI |

Pakiet: `com.contactcenter.domain.contact`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/contacts` | ADMIN/SUPERVISOR/AGENT | lista z filtrami (`ContactFilterParams`: queueId, campaignId, remoteAddress, durationMin/Max...) |
| GET | `/api/contacts/{id}` | ADMIN/SUPERVISOR/AGENT | szczegóły |
| POST | `/api/contacts` | ADMIN/SUPERVISOR/AGENT | utworzenie kontaktu (ręczne / spoza telefonii) |
| PATCH | `/api/contacts/{id}` | ADMIN/SUPERVISOR/AGENT | edycja |
| PATCH | `/api/contacts/{id}/disposition` | ADMIN/SUPERVISOR/AGENT | ustawienie dyspozycji (kod zakończenia) |
| GET | `/api/contacts/customer/{customerId}` | ADMIN/SUPERVISOR/AGENT | historia kontaktów klienta |
| GET | `/api/contacts/{id}/recording` | ADMIN/SUPERVISOR/AGENT | URL nagrania (presigned S3) |
| GET | `/api/contacts/{id}/events` | ADMIN/SUPERVISOR/AGENT | timeline zdarzeń |
| GET | `/api/contacts/{id}/email-preview` | ADMIN/SUPERVISOR/AGENT | podgląd treści email |
| POST | `/api/contacts/{contactId}/callback` | ADMIN/SUPERVISOR/AGENT | zaplanowanie oddzwonienia (inbound callback, BE-040) |
| GET | `/api/contacts/{id}/related` | ADMIN/SUPERVISOR/AGENT | powiązane zasoby (np. wątek email/social) |
| POST | `/api/contacts/{id}/accept` | AGENT/SUPERVISOR/ADMIN | przyjęcie przydzielonego kontaktu (status ASSIGNED → ACTIVE) |
| POST | `/api/contacts/{id}/abandon` | AGENT/SUPERVISOR/ADMIN | odrzucenie przydziału |
| GET | `/api/contacts/{contactId}/available-dispositions` | AGENT/SUPERVISOR/ADMIN | dostępne kody dyspozycji (z `DispositionSet`) |
| POST | `/api/contacts/{contactId}/ai-summary` | ADMIN/SUPERVISOR/AGENT | generowanie podsumowania AI (wymaga `TenantAiConfig`) |

**Uwagi historyczne (ważne dla migracji/zapytań):**
- Tabela `contact` jest **partycjonowana** (JPA: `@IdClass` + native INSERT przez
  `@Modifying @Query(nativeQuery=true)`).
- `contact.channel/direction/status` były ENUM (V007), skonwertowane na
  VARCHAR+CHECK w V025 → repozytorium musi rzucać `CAST(:x AS VARCHAR)`, **nie**
  `CAST(:x AS contact_status)` (typ już nie istnieje).
- Brak `is_deleted` – aktywne statusy to `QUEUED/ACTIVE/ON_HOLD/ASSIGNED`.
- `customer.phone` to JSONB array – zapytania przez `phone @> to_jsonb(...)`.

---

### 1.8 customer

**Cel:** baza klientów (CRM), fuzzy search, import CSV, RODO (eksport/anonimizacja).

| Klasa | Rola |
|---|---|
| `CustomerController` | CRUD + lookup |
| `CustomerImportController` | async import CSV |
| `GdprController` | eksport danych / anonimizacja |
| `Customer` (entity) | tabela `customer`, `phone`/`email` jako JSONB array |
| `CustomerRepository` | fuzzy search (pg_trgm), wyszukiwanie po telefonie (JSONB) |
| `CustomerService` (interfejs + `CustomerServiceImpl`) | CRUD + walidacja |
| `CustomerImportService` (interfejs + `CustomerImportServiceImpl`) | async import (`DeduplicationMode`: SKIP/OVERWRITE) |
| `GdprService` (interfejs + `GdprServiceImpl`, pakiet `domain.gdpr`) | eksport/anonimizacja |
| `CliLookupService` (interfejs + `CliLookupServiceImpl`) | rozpoznawanie numeru dzwoniącego (CLI) → klient |

Pakiet: `com.contactcenter.domain.customer` (oraz `domain.gdpr` dla `GdprService`).

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/customers` | ADMIN/SUPERVISOR | utworzenie |
| GET | `/api/customers` | ADMIN/SUPERVISOR/AGENT | lista + fuzzy search |
| GET | `/api/customers/lookup` | ADMIN/SUPERVISOR/AGENT | wyszukanie po numerze telefonu |
| GET | `/api/customers/lookup/email` | ADMIN/SUPERVISOR/AGENT | wyszukanie po email |
| GET | `/api/customers/{id}` | ADMIN/SUPERVISOR | szczegóły |
| PATCH | `/api/customers/{id}` | ADMIN/SUPERVISOR | edycja |
| DELETE | `/api/customers/{id}` | ADMIN/SUPERVISOR | usunięcie |
| POST | `/api/customers/import` (multipart) | ADMIN/SUPERVISOR | import CSV |
| GET | `/api/customers/import/{jobId}` | ADMIN/SUPERVISOR | status importu |
| GET | `/api/customers/import/{jobId}/errors` | ADMIN/SUPERVISOR | szczegóły błędów importu |
| POST | `/api/customers/{id}/gdpr/export` | ADMIN/SUPERVISOR | eksport danych osobowych |
| POST | `/api/customers/{id}/gdpr/anonymize` | ADMIN/SUPERVISOR | anonimizacja (RODO) |

**Integracja:** przy nieznanym numerze przychodzącym (`call.unknown_caller`,
kolejka `cc.queue.unknown-caller`) automatycznie tworzony jest profil klienta (BE-025).

---

### 1.9 dialer

**Cel:** Progressive Dialer (automatyczne wybieranie numerów kampanii) + zaplanowane
oddzwonienia (scheduled callbacks) + ręczne połączenia agenta.

| Klasa | Rola |
|---|---|
| `DialerController` | status dialera, CRUD callbacków, manual dial |
| `ManualCallbackController` | ręczne planowanie oddzwonienia przez agenta |
| `ScheduledCallback` (entity) | tabela `scheduled_callback` |
| `ScheduledCallbackRepository` | package-private |
| `ScheduledCallbackService` (interfejs + `ScheduledCallbackServiceImpl`) | CRUD/reschedule callbacków |
| `ProgressiveDialerService` (interfejs + `ProgressiveDialerServiceImpl`) | `@RabbitListener agent.status.changed` – wybiera numer gdy agent staje się AVAILABLE |
| `DialerCallbackHandler` (interfejs + `DialerCallbackHandlerImpl`) | `@RabbitListener call.hangup` (dedykowana kolejka `cc.queue.dialer-hangup`) |
| `ScheduledCallbackExecutor` | `@Scheduled` – wykonuje zaplanowane oddzwonienia |

Wszystkie powyższe klasy znajdują się w `com.contactcenter.domain.campaign` (moduł `dialer`
w `api/` korzysta z serwisów domeny `campaign`).

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/dialer/status` | ADMIN/SUPERVISOR | status dialera (liczba aktywnych połączeń, kampanie) |
| GET | `/api/dialer/callbacks` | ADMIN/SUPERVISOR/AGENT | lista zaplanowanych oddzwonień |
| POST | `/api/dialer/callbacks` | ADMIN/SUPERVISOR/AGENT | utworzenie callbacku |
| PUT \| PATCH | `/api/dialer/callbacks/{callbackId}` | ADMIN/SUPERVISOR/AGENT | edycja / reschedule |
| DELETE | `/api/dialer/callbacks/{callbackId}` | ADMIN/SUPERVISOR/AGENT | usunięcie |
| GET | `/api/dialer/manual/records` | AGENT | rekordy kampanii do ręcznego wybrania |
| POST | `/api/dialer/manual/call` | AGENT | inicjacja ręcznego połączenia z rekordu kampanii |
| POST | `/api/callbacks/manual` | AGENT/SUPERVISOR | ręczne planowanie oddzwonienia (BE-048, `sourceType=AGENT_MANUAL`) |

**Mechanizm Progressive Dialer (BE-024):**
```
agent.status.changed (AVAILABLE) ──▶ ProgressiveDialerService
                                          │ Redis SET NX (guard – jeden agent = jedno wybieranie)
                                          ▼
                              SELECT campaign_contact FOR UPDATE SKIP LOCKED
                                          │
                                          ▼
                          TelephonyAdapter.initiateCall(...)
```
`ScheduledCallbackExecutor` (`@Scheduled fixedDelay`, `dialer.callback-executor.interval-ms`)
atomowo oznacza callback jako `IN_PROGRESS` (`updateStatusIfPending`) przed
zainicjowaniem połączenia – chroni przed podwójnym przetworzeniem.

---

### 1.10 disposition

**Cel:** kody dyspozycji (np. "Sprzedano", "Nie zainteresowany") oraz zestawy
dyspozycji przypisywane do kampanii/kolejek.

| Klasa | Rola |
|---|---|
| `CustomDispositionController` | `/api/dispositions/**` – dyspozycje per kampania/kolejka |
| `DispositionSetController` | `/api/disposition-sets` – zestawy + ich elementy |
| `CustomDisposition`, `DispositionSet`, `DispositionSetItem` | encje |
| `CustomDispositionRepository`, `DispositionSetRepository`, `DispositionSetItemRepository` | package-private |
| `CustomDispositionService` (interfejs + `CustomDispositionServiceImpl`), `DispositionSetService` (interfejs + `DispositionSetServiceImpl`) | logika domenowa |

Pakiet: `com.contactcenter.domain.disposition`.

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET/POST | `/api/dispositions/campaigns/{campaignId}` | dyspozycje kampanii |
| PUT/DELETE | `/api/dispositions/campaigns/{campaignId}/{id}` | edycja/usunięcie |
| GET/POST | `/api/dispositions/queues/{queueId}` | dyspozycje kolejki |
| PUT/DELETE | `/api/dispositions/queues/{queueId}/{id}` | edycja/usunięcie |
| GET/POST | `/api/disposition-sets` | zestawy dyspozycji |
| GET/PUT/DELETE | `/api/disposition-sets/{setId}` | szczegóły/edycja/usunięcie zestawu |
| GET/POST | `/api/disposition-sets/{setId}/items` | elementy zestawu |
| PUT/DELETE | `/api/disposition-sets/{setId}/items/{itemId}` | edycja/usunięcie elementu |
| POST | `/api/disposition-sets/{setId}/apply-to-campaign/{campaignId}` | przypisanie zestawu do kampanii |
| POST | `/api/disposition-sets/{setId}/apply-to-queue/{queueId}` | przypisanie zestawu do kolejki |

Relacje: `ContactController.GET /{contactId}/available-dispositions` odczytuje
zestaw dyspozycji przypisany do kolejki/kampanii kontaktu.

---

### 1.11 email

**Cel:** kanał e-mail – IMAP polling (odbieranie), SMTP (wysyłka), szablony
(Mustache), routing wiadomości do kolejek/kontaktów.

| Klasa | Rola |
|---|---|
| `EmailController` | wiadomości, wątki, konfiguracja IMAP/SMTP |
| `EmailTemplateController` | CRUD szablonów + podgląd renderowania |
| `EmailMessage`, `EmailTemplate`, `EmailRoutingRule` | encje |
| `EmailMessageRepository`, `EmailTemplateRepository`, `EmailRoutingRuleRepository` | package-private |
| `EmailMessageService` (interfejs + `EmailMessageServiceImpl`) | CRUD/odczyt wiadomości i wątków |
| `EmailAccountConfig`, `EmailEncryptionService` (interfejs + `EmailEncryptionServiceImpl`) | konfiguracja kont (AES-256-GCM dla haseł) |
| `EmailPollingService` (interfejs + `EmailPollingServiceImpl`) | `@Scheduled` – polling IMAP (`email.poll-delay-ms`, domyślnie 60s) |
| `EmailSendService` (interfejs + `EmailSendServiceImpl`) | wysyłka SMTP |
| `EmailRoutingService` | przypisanie wiadomości do kolejki/kontaktu wg reguł (wewnętrzny helper, package-private) |
| `EmailContactCreator` | tworzenie rekordu `contact` dla nowej wiadomości (wewnętrzny helper, package-private) |
| `EmailTemplateService` (interfejs + `EmailTemplateServiceImpl`), `MustacheTemplateEngine`, `TemplateVariableResolver` (interfejs + `TemplateVariableResolverImpl`), `PredefinedTemplateVariable` | renderowanie szablonów |
| `EmailEventPublisher` | publikacja eventów `email.#` do `cc.events` (wewnętrzny helper, package-private) |
| `EmailEmlService` | eksport/import `.eml` (wewnętrzny helper, package-private) |

Pakiet: `com.contactcenter.domain.email`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/email/messages` | auth | lista wiadomości |
| GET | `/api/email/messages/{id}` | auth | szczegóły wiadomości |
| GET | `/api/email/contacts/{contactId}/message` | auth | wiadomość powiązana z kontaktem |
| GET | `/api/email/threads/{messageIdHeader}` | auth | wątek wiadomości |
| POST | `/api/email/messages/{id}/reply` | auth | odpowiedź na wiadomość |
| POST | `/api/email/messages/outbound` | AGENT/SUPERVISOR/ADMIN | nowa wiadomość wychodząca |
| GET | `/api/email/config` | SUPERVISOR/ADMIN | konfiguracja IMAP/SMTP tenanta |
| PUT | `/api/email/config` | SUPERVISOR/ADMIN | zapis konfiguracji |
| POST | `/api/email/config/test` | SUPERVISOR/ADMIN | test połączenia |
| GET | `/api/email-templates/available-variables` | auth | lista zmiennych `${...}` dostępnych w szablonach |
| GET | `/api/email-templates` | auth | lista szablonów |
| GET | `/api/email-templates/{id}` | auth | szczegóły |
| POST | `/api/email-templates` | ADMIN/SUPERVISOR | utworzenie |
| PATCH | `/api/email-templates/{id}` | ADMIN/SUPERVISOR | edycja |
| DELETE | `/api/email-templates/{id}` | ADMIN/SUPERVISOR | usunięcie |
| POST | `/api/email-templates/{id}/preview` | auth | podgląd renderowania (rzuca `TemplateRenderException` przy brakujących zmiennych → HTTP 422) |

**Async eventy:** `EXCHANGE_EVENTS` routing key `email.#` → `QUEUE_EMAIL_EVENTS`
(received, queued, sent, assigned).

---

### 1.12 ivr

**Cel:** silnik IVR (drzewa decyzyjne, DTMF, integracja z voicebotem).

| Klasa | Rola |
|---|---|
| `IvrController` | CRUD drzew IVR + lifecycle + endpoint DTMF |
| `IvrTree`, `IvrAudio`, `IvrDefinition`, `IvrNode`, `IvrNodePosition`, `IvrOption`, `IvrNodeType` | model drzewa (JSONB) |
| `IvrTreeRepository`, `IvrAudioRepository` | package-private |
| `IvrEngineService` (interfejs + `IvrEngineServiceImpl`) | silnik – interpretacja drzewa, sesje Redis, TTS cache |
| `IvrService` (interfejs + `IvrServiceImpl`) | CRUD + walidacja drzew |
| `IvrCallListener` | `@RabbitListener` na `cc.queue.ivr-handler` (routing key `call.incoming`) |
| `IvrSessionData` | stan sesji w Redis (`ivr:session:{callId}`) |

Pakiet: `com.contactcenter.domain.ivr`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/ivr` | SUPERVISOR/ADMIN | lista drzew IVR |
| POST | `/api/ivr` | SUPERVISOR/ADMIN | utworzenie drzewa |
| GET | `/api/ivr/{ivrId}` | SUPERVISOR/ADMIN | szczegóły (definicja JSONB z pozycjami węzłów) |
| PATCH | `/api/ivr/{ivrId}` | SUPERVISOR/ADMIN | edycja |
| DELETE | `/api/ivr/{ivrId}` | SUPERVISOR/ADMIN | usunięcie |
| POST | `/api/ivr/{ivrId}/activate` \| `/deactivate` | SUPERVISOR/ADMIN | lifecycle |
| POST | `/api/ivr/dtmf` | SUPERVISOR/ADMIN | (legacy/test) symulacja wejścia DTMF |

**Przepływ połączenia przychodzącego z IVR:**
```
Twilio webhook (call.incoming) → cc.events (routing key call.incoming)
   → QUEUE_IVR_HANDLER → IvrCallListener
       → IvrEngineService: ładuje aktywne IvrTree tenanta
           → tworzy sesję Redis ivr:session:{callId}
           → renderuje pierwszy węzeł (TTS – cache ivr:tts:{hash})
           → fallback do kolejki (RoutingEngine) gdy brak drzewa / błąd / VOICEBOT niedostępny
```
Typ węzła `VOICEBOT` (`IvrNodeType.VOICEBOT`) wywołuje `VoicebotClient` (sekcja 5).
Timeout DTMF realizowany przez `TaskScheduler` z `AsyncConfig`.

---

### 1.13 phonenumber

**Cel:** zarządzanie numerami telefonów tenanta (E.164) oraz reguły routingu
numerów (np. godziny pracy → kolejka/IVR).

| Klasa | Rola |
|---|---|
| `PhoneNumberController` | CRUD numerów |
| `PhoneRoutingRuleController` | CRUD reguł routingu per numer |
| `PhoneNumber`, `PhoneRoutingRule` (entity) | |
| `PhoneNumberRepository`, `PhoneRoutingRuleRepository` | package-private |
| `PhoneNumberService` (interfejs + `PhoneNumberServiceImpl`), `PhoneRoutingRuleService` (interfejs + `PhoneRoutingRuleServiceImpl`) | walidacja kolizji harmonogramów |

Pakiet: `com.contactcenter.domain.phonenumber`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/phone-numbers` | ADMIN/SUPERVISOR | rejestracja numeru |
| GET | `/api/phone-numbers` | ADMIN/SUPERVISOR | lista |
| GET | `/api/phone-numbers/{id}` | ADMIN/SUPERVISOR | szczegóły |
| PATCH | `/api/phone-numbers/{id}` | ADMIN/SUPERVISOR | edycja |
| DELETE | `/api/phone-numbers/{id}` | ADMIN/SUPERVISOR | usunięcie (blokowane gdy aktywne reguły routingu) |
| POST | `/api/phone-numbers/{numberId}/routing-rules` | ADMIN/SUPERVISOR | nowa reguła |
| GET | `/api/phone-numbers/{numberId}/routing-rules` | ADMIN/SUPERVISOR | lista reguł |
| PATCH | `/api/phone-numbers/{numberId}/routing-rules/{ruleId}` | ADMIN/SUPERVISOR | edycja |
| DELETE | `/api/phone-numbers/{numberId}/routing-rules/{ruleId}` | ADMIN/SUPERVISOR | usunięcie |

**Kolizje reguł:** wykrywane dwuwarstwowo – aplikacyjnie (`RoutingRuleConflictException`
→ HTTP 409 z listą `collidingRuleIds`) oraz przez trigger PostgreSQL
`trg_routing_rule_collision` (last-resort, `DataIntegrityViolationException` z
komunikatem `routing_rule_collision`).

---

### 1.14 public_

**Cel:** endpointy bez JWT – ekran logowania (lista tenantów).

| Klasa | Rola |
|---|---|
| `PublicController` | `/api/public/**` |

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/public/tenants` | lista aktywnych tenantów (do wyboru na ekranie logowania) |
| POST | `/api/public/tenants-by-email` | tenanty powiązane z danym adresem email |

---

### 1.15 queue

**Cel:** kolejki obsługi (skill-based routing), przypisania agentów/grup, statystyki
oczekiwania (EWT).

| Klasa | Rola |
|---|---|
| `QueueController` | CRUD kolejek, strategie routingu, statystyki |
| `QueueAssignmentController` | przypisania agentów/grup do kolejki |
| `Queue` (entity), `QueueAssignmentRepository`, `QueueRepository` | repozytoria package-private |
| `QueueService` (interfejs + `QueueServiceImpl`), `QueueAssignmentService` (interfejs + `QueueAssignmentServiceImpl`) | logika domenowa |
| `WaitTimeEstimationService` (interfejs + `WaitTimeEstimationServiceImpl`) | EWT (estimated wait time) – `@Scheduled` co 30s |
| `QueueWaitUpdatePayload` | payload WebSocket `QUEUE_WAIT_UPDATE` |

Pakiet: `com.contactcenter.domain.queue`.

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/queues` | lista kolejek |
| GET | `/api/queues/routing-strategies` | dostępne strategie (skill-based, round-robin, sticky agent...) |
| POST | `/api/queues` | utworzenie |
| GET | `/api/queues/{id}` | szczegóły |
| PATCH | `/api/queues/{id}` | edycja |
| GET | `/api/queues/{id}/stats` | statystyki (EWT, liczba w kolejce) |
| DELETE | `/api/queues/{id}` | usunięcie |
| GET | `/api/queues/{queueId}/assignment` | przypisani agenci/grupy |
| PUT | `/api/queues/{queueId}/assignment` | zamiana przypisań |

Wszystkie endpointy: `@PreAuthorize hasAnyRole('SUPERVISOR','ADMIN')`.

---

### 1.16 recording

**Cel:** dostęp do nagrań rozmów (S3/MinIO, presigned URL) oraz retencja.

| Klasa | Rola |
|---|---|
| `RecordingController` | `/api/recordings/{contactId}` (SUPERVISOR/ADMIN) |
| `RecordingService` (interfejs + `RecordingServiceImpl`) | generowanie presigned URL, upload |
| `RecordingRetentionJob` | `@Scheduled` (cron `s3.retention-cron`, domyślnie 02:00 UTC) – usuwa nagrania starsze niż `s3.retention-days` |
| `TwilioRecordingDownloadService` (interfejs + `TwilioRecordingDownloadServiceImpl`) | pobiera nagranie z Twilio i zapisuje do S3 |

Pakiet: `com.contactcenter.domain.recording`.

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/recordings/{contactId}` | presigned URL nagrania kontaktu |

Konferencje Twilio (`TwilioWebhookController` → `/recording`) wywołują
`TwilioRecordingDownloadService` po zakończeniu nagrywania.

---

### 1.17 reports

**Cel:** raporty agregowane (agenci, kampanie) + eksport CSV/XLSX.

| Klasa | Rola |
|---|---|
| `ReportsController` | `/api/reports/**` |
| `ReportsService` (interfejs + `ReportsServiceImpl`, pakiet `domain.reporting`) | agregacje SQL |
| `AgentReportParams`, `AgentReportRow` | DTO |

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/reports/agents` | raport per agent (czas pracy, liczba kontaktów, AHT...) |
| GET | `/api/reports/campaigns` | raport per kampania |
| GET | `/api/reports/agents/export` | eksport CSV |
| GET | `/api/reports/agents/export/xlsx` | eksport XLSX |

Wszystkie: `hasAnyRole('SUPERVISOR','ADMIN')`.

---

### 1.18 social

**Cel:** integracje Facebook Messenger / Instagram / WhatsApp Business – OAuth,
webhooki, wysyłka/odbiór wiadomości.

| Klasa | Rola |
|---|---|
| `SocialContactController` | wysyłka/odczyt wiadomości w kontekście kontaktu |
| `SocialOAuthController` | inicjacja OAuth, callback, lista integracji |
| `SocialWebhookController` | webhooki FB/IG/WhatsApp (publiczne) |
| `SocialIntegration`, `SocialMessage`, `SocialPlatform` (entity/enum) | |
| `SocialIntegrationRepository`, `SocialMessageRepository` | package-private |
| `SocialIntegrationService` (interfejs + `SocialIntegrationServiceImpl`) | zarządzanie integracjami, `@Scheduled` refresh tokenów co 1h |
| `SocialMessageService` (interfejs + `SocialMessageServiceImpl`) | wysyłka/odbiór |
| `SocialTokenEncryptionService` | AES-256-GCM (BYTEA) dla tokenów OAuth |
| `infrastructure/social/{Facebook,Instagram,WhatsApp}Adapter` + `SocialAdapterRegistry` | adaptery per platforma |
| `domain/social/{IncomingSocialMessage, SocialMediaAdapter, SocialMessageConsumer}`, `SocialMessagePublisher` (interfejs + `SocialMessagePublisherImpl`) | abstrakcje + consumer RabbitMQ |

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/contacts/{contactId}/social/message` | AGENT/SUPERVISOR/ADMIN | wysyłka wiadomości w wątku kontaktu |
| GET | `/api/contacts/{contactId}/social/messages` | AGENT/SUPERVISOR/ADMIN | historia wiadomości |
| GET | `/api/integrations` | ADMIN/SUPERVISOR | lista integracji social tenanta |
| POST | `/api/integrations/{platform}/initiate` | ADMIN/SUPERVISOR | start OAuth (zapis `state` w Redis: `oauth:state:{state}` → tenantId, TTL 10 min) |
| GET | `/api/oauth/{platform}/callback` | **publiczny** | callback OAuth (weryfikacja `state`, ustawia TenantContext) |
| DELETE | `/api/integrations/{integrationId}` | ADMIN/SUPERVISOR | usunięcie integracji |
| GET/POST | `/api/webhooks/facebook` \| `/instagram` \| `/whatsapp` | **publiczne** | webhook verification (GET) i odbiór wiadomości (POST) |

**Async webhook pattern:** handler webhooka odpowiada natychmiast (200), wiadomość
trafia do `QUEUE_SOCIAL_INCOMING` (routing przez `SocialMessagePublisher`), a
`SocialMessageConsumer` przetwarza ją asynchronicznie (tworzy/aktualizuje `contact` +
`social_message`, cross-tenant `findByPlatformAndPageId`).

---

### 1.19 supervisor

**Cel:** panel supervisora – konfiguracja AI (podsumowania) i Twilio per tenant,
metryki real-time (WebSocket).

| Klasa | Rola |
|---|---|
| `TenantAiConfigController` | `/api/supervisor/ai-config` |
| `TenantTwilioConfigController` | `/api/supervisor/twilio-config` |
| `SupervisorMetricsPayload` | payload WebSocket (KPI real-time) |
| `TenantAiConfig`, `TenantTwilioConfig` (entity) | konfiguracje per-tenant (szyfrowane klucze), pakiet `domain.tenant` |
| `TenantAiConfigService` (interfejs + `TenantAiConfigServiceImpl`), `TenantAiConfigDecrypted`, `AiProvider` | pakiet `domain.tenant` |
| `TenantTwilioConfigService` (interfejs + `TenantTwilioConfigServiceImpl`), `TenantTwilioConfigDecrypted` | upsert + masking + decrypt + delete + event `TwilioConfigChangedEvent`, pakiet `domain.tenant` |
| `SupervisorMetricsService` | agregacja KPI (czas oczekiwania, liczba w IVR, w kolejce...), pakiet `domain.reporting` |

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET/PUT/DELETE | `/api/supervisor/ai-config` | konfiguracja dostawcy AI (klucz API, model) – SUPERVISOR |
| GET/PUT/DELETE | `/api/supervisor/twilio-config` | per-tenant Twilio (account SID, auth token – szyfrowane) – SUPERVISOR |
| GET | `/api/supervisor/twilio-config/phone-numbers` | lista numerów z konta Twilio tenanta |
| POST | `/api/supervisor/twilio-config/test` | test połączenia z Twilio |

Dodatkowo, w `TenantController`: `GET/PATCH /api/tenants/{id}/config` –
`hasAnyRole('ADMIN','SUPERVISOR')` – odczyt/zapis ogólnej konfiguracji tenanta
(zawiera maskowane dane Twilio).

`SupervisorMetricsService` publikuje KPI na WebSocket
(`/topic/tenant/{tenantId}/supervisor`) – patrz sekcja 5.

---

### 1.20 telemetry

**Cel:** odbiór logów z frontendu (debugging w produkcji).

| Klasa | Rola |
|---|---|
| `FrontendLogController` | `/api/logs` (publiczny – FE wysyła logi nawet przed zalogowaniem) |

| Metoda | Ścieżka | Opis |
|---|---|---|
| POST | `/api/logs` | przyjmuje `FrontendLogRequest` (poziom, komunikat, stack trace) i loguje po stronie backendu |

---

### 1.21 telephony

**Cel:** integracja z CPaaS (Twilio), kontrola połączeń przez agenta, webhooki,
transfer.

| Klasa | Rola |
|---|---|
| `AgentCallController` | `/api/telephony/calls/**` – sterowanie połączeniem przez agenta |
| `AgentSelfController` | `/api/agent/me/**` – self-service (assigned contact, KPI) |
| `MockCallController` | `/api/dev/telephony/**` – symulacja połączeń (tylko `telephony.provider=mock`) |
| `TelephonyWebhookController` | generyczny webhook VoIP (`X-Webhook-Secret`) |
| `TransferController` | `/api/telephony/transfer/**` – listy agentów/kolejek do transferu |
| `TwilioVoiceController` | feature flags, Voice Access Token (JS SDK), hold music TwiML |
| `TwilioWebhookController` | `/api/telephony/webhook/twilio/**` – webhooki Twilio (TwiML, DTMF, status, conference, recording, voicebot) |
| `domain/telephony/{TelephonyAdapter, MockTelephonyAdapter, TwilioTelephonyAdapter}` | implementacje adaptera |
| `CallEvent`, `CallSession`, `CallEventEnricher`, `TelephonyEventPublisher` (interfejs + `TelephonyEventPublisherImpl`) | model eventów + publikacja do RabbitMQ, pakiet `domain.telephony` |
| `TransferRequest`, `TransferTargetType` | model żądania transferu |
| `TransferService` (interfejs + `TransferServiceImpl`, pakiet `domain.telephony`), `TransferAgentQueueRepository` (pakiet `domain.queue`) | logika transferu (BE-075/077) |
| `IncomingCallRoutingService` (interfejs + `IncomingCallRoutingServiceImpl`, pakiet `domain.routing`) | routing połączeń przychodzących (numer → IVR/kolejka) |

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/telephony/calls/outbound` | AGENT/SUPERVISOR/ADMIN | inicjacja połączenia wychodzącego |
| POST | `/api/telephony/calls/{callId}/answer` | AGENT/SUPERVISOR/ADMIN | odebranie |
| POST | `/api/telephony/calls/{callId}/hangup` | AGENT/SUPERVISOR/ADMIN | zakończenie |
| POST | `/api/telephony/calls/{callId}/hold` | AGENT/SUPERVISOR/ADMIN | hold/unhold |
| POST | `/api/telephony/calls/{callId}/mute` | AGENT/SUPERVISOR/ADMIN | mute/unmute |
| POST | `/api/telephony/calls/{callId}/transfer` | AGENT/SUPERVISOR/ADMIN | transfer (BLIND/ATTENDED → PHONE/AGENT/QUEUE) |
| POST | `/api/telephony/calls/{callId}/bridge/{secondCallId}` | AGENT/SUPERVISOR/ADMIN | połączenie dwóch nóg po attended transfer |
| GET | `/api/telephony/calls/{callId}/session` | AGENT/SUPERVISOR/ADMIN | aktualny stan sesji |
| GET | `/api/agent/me/assigned-contact` | AGENT/SUPERVISOR/ADMIN | aktualnie przydzielony kontakt (resilience – BE WS) |
| GET | `/api/agent/me/kpi` | AGENT/SUPERVISOR/ADMIN | KPI agenta (AHT, liczba kontaktów dzisiaj...) |
| GET | `/api/telephony/transfer/agents` | AGENT/SUPERVISOR/ADMIN | dostępni agenci do transferu (UNION 3 źródeł kolejek) |
| GET | `/api/telephony/transfer/queues` | AGENT/SUPERVISOR/ADMIN | dostępne kolejki do transferu |
| GET | `/api/telephony/features` | AGENT/SUPERVISOR/ADMIN | feature flags (np. czy Twilio aktywny) |
| GET | `/api/telephony/voice-token` | AGENT/SUPERVISOR/ADMIN | Twilio Access Token dla Voice JS SDK |
| GET | `/api/telephony/hold-music` | **publiczny** | TwiML hold music (waitUrl Conference) |
| POST | `/api/telephony/webhook` | **publiczny** (`X-Webhook-Secret`) | generyczny webhook VoIP |
| POST | `/api/dev/telephony/simulate` | dev | symulacja zdarzenia połączenia |
| GET | `/api/dev/telephony/sessions/count` \| `/sessions/{callId}` | dev | introspekcja sesji mock |

Webhooki Twilio – patrz sekcja 5.

---

### 1.22 tenant

**Cel:** zarządzanie tenantami (ADMIN), limity zasobów, konfiguracja per-tenant.

| Klasa | Rola |
|---|---|
| `TenantController` | `/api/tenants/**` |
| `Tenant` (entity) | tabela `tenant` |
| `TenantRepository` | package-private |
| `TenantService` (interfejs + `TenantServiceImpl`) | CRUD + walidacja |
| `TenantResourceLimitService` (interfejs + `TenantResourceLimitServiceImpl`) | limity (agenci/kolejki/kampanie) – `get_tenant_limit()` w DB |

Pakiet: `com.contactcenter.domain.tenant`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/tenants` | ADMIN | utworzenie tenanta |
| GET | `/api/tenants` | ADMIN | lista |
| GET | `/api/tenants/{id}` | ADMIN | szczegóły |
| PATCH | `/api/tenants/{id}` | ADMIN | edycja |
| POST | `/api/tenants/{id}/deactivate` | ADMIN | deaktywacja |
| GET | `/api/tenants/{id}/config` | ADMIN/SUPERVISOR | konfiguracja (Twilio maskowane itd.) |
| PATCH | `/api/tenants/{id}/config` | ADMIN/SUPERVISOR | zapis konfiguracji |
| GET | `/api/tenants/check-name` \| `/{id}/check-name` | ADMIN | sprawdzenie unikalności nazwy |

Przekroczenie limitu zasobu → `ResourceLimitExceededException` → HTTP 422
(`resourceType`, `limit`, `current` w odpowiedzi).

---

### 1.23 user

**Cel:** zarządzanie użytkownikami (agenci/supervisorzy/admini), statusy agenta,
preferencje UI.

| Klasa | Rola |
|---|---|
| `UserController` | CRUD + status agenta |
| `AdminUserController` | `/api/admin/users` – zarządzanie użytkownikami przez ADMIN |
| `UserPreferencesController` | `/api/users/me/preferences` |
| `AppUser` (entity) | tabela `app_user` |
| `AppUserRepository` | package-private |
| `UserService` (interfejs + `UserServiceImpl`), `AdminUserService` (interfejs + `AdminUserServiceImpl`), `UserPreferencesService` (interfejs + `UserPreferencesServiceImpl`) | |
| `AgentStatusChangedEvent` | event publikowany do `cc.events` (routing key `agent.status.#`) |

Pakiet: `com.contactcenter.domain.user`.

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| POST | `/api/users` | ADMIN/SUPERVISOR | utworzenie użytkownika |
| GET | `/api/users` | ADMIN/SUPERVISOR | lista |
| GET | `/api/users/me` | wszyscy | profil zalogowanego |
| GET | `/api/users/skills` | ADMIN/SUPERVISOR | lista dostępnych skilli (routing) |
| GET | `/api/users/{id}` | ADMIN/SUPERVISOR | szczegóły |
| PATCH | `/api/users/{id}` | ADMIN/SUPERVISOR | edycja |
| DELETE | `/api/users/{id}` | ADMIN/SUPERVISOR | usunięcie |
| PATCH | `/api/users/me/status` | wszyscy | zmiana własnego statusu (AVAILABLE/BREAK/...) → publikuje `agent.status.changed` |
| PATCH | `/api/users/{id}/status` | wszyscy* | zmiana statusu innego użytkownika (np. supervisor wymusza) |
| GET/PUT | `/api/users/me/preferences` | wszyscy | preferencje UI (np. layout, powiadomienia) |
| GET | `/api/admin/users` | ADMIN | lista (widok administracyjny) |
| POST | `/api/admin/users` | ADMIN | utworzenie |
| PATCH | `/api/admin/users/{id}` | ADMIN | edycja |
| DELETE | `/api/admin/users/{id}` | ADMIN | usunięcie |
| POST | `/api/admin/users/{id}/force-password-reset` | ADMIN | wymuszenie resetu hasła |

**Eventy:** zmiana statusu agenta (`agent.status.changed`) konsumowana przez
`RoutingService` (`QUEUE_AGENT_STATUS`), `ProgressiveDialerService`
(`QUEUE_DIALER_AGENT_STATUS`) – patrz RabbitMQ w sekcji 6.

---

### 1.24 websocket

**Cel:** kanał real-time do Agent Desktop (STOMP over WebSocket).

| Klasa | Rola |
|---|---|
| `WebSocketController` (api) | `@MessageMapping`/`@SubscribeMapping` – ping/pong, walidacja subskrypcji |
| `WebSocketConfig` (infrastructure) | konfiguracja brokera STOMP, endpointy `/ws`, `/ws-native` |
| `WebSocketAuthInterceptor` (security) | autentykacja JWT przy STOMP CONNECT |
| `StompPrincipal` (security) | principal z `tenantId`, `userId`, `role` |
| `domain/websocket/{WebSocketEvent, RabbitToWebSocketRelay}`, `WebSocketEventBroadcaster` (interfejs + `WebSocketEventBroadcasterImpl`) | model eventu, broadcaster, relay RabbitMQ→WebSocket |

Szczegóły – sekcja 5.

---

### 1.25 retention – okres archiwizacji kontaktów kampanii (EPIC-30, BE-151)

**Cel:** konfigurowalny **per tenant** okres, po którym kontakty zakończonych kampanii
(`COMPLETED`/`STOPPED`) są przenoszone z `campaign_contact` do `campaign_contact_archive`, oraz
cykliczny job, który to wykonuje. Pakiet `com.contactcenter.domain.retention` zawiera też inne klasy
retencji (polityki, purge, partycje) – tu opisano wyłącznie ten fragment; model danych: `06-database.md`
(sekcja 3.4, „Okres archiwizacji kontaktów kampanii per tenant”).

| Klasa | Rola |
|---|---|
| `CampaignArchiveSettingController` (`api/retention`) | `/api/tenants/{tenantId}/retention/campaign-archive` |
| `CampaignArchiveSettingService` (interfejs, public: stałe `DEFAULT_ARCHIVE_AFTER_DAYS=180`, `MIN…=30`, `MAX…=730`) + `CampaignArchiveSettingServiceImpl` (package-private) | odczyt/zapis ustawienia, podgląd skutku, audyt |
| `TenantCampaignArchiveSetting` + `TenantCampaignArchiveSettingRepository` | encja/repozytorium tabeli `tenant_campaign_archive_setting` (natywny SQL) |
| `CampaignArchiveJob` + `CampaignArchiveJobRepository` | `@Scheduled` – woła `archive_completed_campaign_contacts()` |

| Metoda | Ścieżka | Rola | Opis |
|---|---|---|---|
| GET | `/api/tenants/{tenantId}/retention/campaign-archive` | ADMIN | okres efektywny |
| PUT | `/api/tenants/{tenantId}/retention/campaign-archive` | ADMIN | ustawienie okresu (body `{"archiveAfterDays": N}`) |
| GET | `/api/tenants/{tenantId}/retention/campaign-archive/preview?archiveAfterDays=N` | ADMIN | podgląd skutku (tylko odczyt) |

**Bezpieczeństwo:** `@PreAuthorize("hasRole('ADMIN')")` + istniejąca reguła
`/api/tenants/*/retention/**` w `SecurityConfig` (nowych publicznych ścieżek brak). `{tenantId}` z URL
musi być równe tenantowi z JWT (`assertOwnTenant`), inaczej `CrossTenantAccessException` → 403.

**Kształty odpowiedzi (DTO):**
- `CampaignArchiveSettingDto` (GET/PUT): `archiveAfterDays`, `isDefault` (true = brak wiersza w bazie,
  obowiązuje 180), `defaultDays`, `minDays`, `maxDays`, `updatedBy` (nullable), `updatedAt` (nullable),
  `warning` (stały tekst ostrzeżenia o skutkach skrócenia okresu).
- `CampaignArchivePreviewDto` (preview): `currentDays`, `newDays`, `campaignsToArchive`,
  `contactsToArchive` (dla `newDays`), `campaignsToArchiveAtCurrentDays`, `contactsToArchiveAtCurrentDays`,
  `additionalCampaigns`, `additionalContacts` (różnica względem okresu bieżącego, ujemna przy
  wydłużeniu), `shortensPeriod` (`newDays < currentDays`).

**Walidacja:** `archiveAfterDays` musi być liczbą całkowitą z zakresu 30–730; naruszenie (brak/`null`,
tekst, ułamek, poza zakresem – także w `preview`) daje **HTTP 422** (`validation-failed`). Body przechodzi
przez `StrictIntegerDeserializer` (odrzuca ułamki), a lokalne `@ExceptionHandler`-y kontrolera
(`HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` dla `archiveAfterDays`) mapują
nieczytelne body i nienumeryczny parametr na 422 (globalny handler dałby 500/400). Serwis powtarza
sprawdzenie zakresu (obrona w głąb), a baza ma `CHECK` (V136).

**Zapis (`PUT`) i audyt:** `TenantCampaignArchiveSettingRepository#upsertReturningPrevious` działa atomowo
w jednej transakcji (`INSERT … ON CONFLICT DO NOTHING`, a przy konflikcie `SELECT … FOR UPDATE` +
`UPDATE`) i zwraca poprzednią wartość – równoległe `PUT` serializują się na wierszu. `PUT` z wartością już
obowiązującą nic nie zapisuje (idempotentny no-op, bez audytu). Przy zmianie wartości **efektywnej**
publikowany jest `AuditLogEvent` o akcji `CAMPAIGN_ARCHIVE_PERIOD_CHANGED` (`entityType`
`TENANT_CAMPAIGN_ARCHIVE_SETTING`, `entityId` = `tenantId`; `oldValue` `{archiveAfterDays, isDefault}`,
`newValue` `{archiveAfterDays, shortened}`). Pierwszy jawny zapis wartości równej domyślnej tworzy wiersz,
ale nie jest audytowany. **Audyt jest best-effort:** `AuditLogService#publishAuditEvent` jest `@Async`
i połyka błędy brokera, więc zmiana może zostać zapisana bez wpisu w `audit_log` (utrata logowana na ERROR).

**Semantyka podglądu:** `preview` liczy (`countArchivable`) kampanie `COMPLETED`/`STOPPED` tenanta z
`COALESCE(ended_at, updated_at, created_at) < NOW() - N dni` – predykat zgodny z funkcją SQL `archive_completed_campaign_contacts()` od V140 (oba zapytania `countArchivable`: kampanie i kontakty; wiersze z `ended_at` i `updated_at` jednocześnie `NULL` są liczone wg `created_at`) – ale **tylko te,
które mają jeszcze wiersze w `campaign_contact`** (kampanie już zarchiwizowane nie mają nic do przeniesienia
i dawałyby wiecznie niezerowy wynik). Nic nie zapisuje i nie uruchamia archiwizacji.

**`CampaignArchiveJob`:** `@Scheduled` wg `retention.campaign-archive-cron` (domyślnie `0 0 4 * * *`, UTC);
flaga `retention.campaign-archive.enabled` jest **domyślnie `false`** (decyzja D8) – wyłączony job tylko loguje
INFO i nie wykonuje SQL. Włączenie wymaga zgody właściciela produktu (po archiwizacji kontakty znikają z UI,
statystyk i dialera; pierwszy przebieg archiwizuje całą zaległość). Wyjątek nie zatrzymuje schedulera; ślad
awarii zapisuje `CronFailureLogRepository` w osobnej transakcji. Funkcja jest cross-tenant, więc rola DB
musi widzieć wszystkich tenantów (obecnie `BYPASSRLS`).

> **Ostrzeżenie:** **skrócenie** okresu archiwizacji powoduje masową archiwizację przy najbliższym
> przebiegu joba (kontakty wszystkich kampanii zakończonych dawniej niż nowy okres znikną z UI, statystyk
> i dialera) – przed zapisem użyj `preview`. Okres archiwizacji (dni od zakończenia kampanii) to coś innego
> niż retencja kategorii `CAMPAIGN_DATA` (miesiące od `archived_at`, usuwanie wierszy archiwum).

---

## 2. Warstwa security

### 2.1 Kolejność filtrów (krytyczne)

```
HTTP Request
   │
   ▼
JwtAuthFilter        – parsuje JWT, sprawdza blacklistę Redis, ustawia SecurityContext (Authentication)
   │
   ▼
TenantFilter         – ponownie parsuje JWT, ustawia TenantContext (tenantId, userId, role) + MDC
   │
   ▼
UsernamePasswordAuthenticationFilter  – pomijany (STATELESS, brak sesji)
   │
   ▼
ExceptionTranslationFilter – AccessDeniedException / AuthenticationException
   │
   ▼
Kontroler (@PreAuthorize sprawdzane przez @EnableMethodSecurity)
```

Plik: `security/SecurityConfig.java:142-146` – `addFilterBefore(jwtAuthFilter, ...)`,
`addFilterAfter(tenantFilter, JwtAuthFilter.class)`.

**Dlaczego dwa filtry parsują ten sam JWT?** `JwtAuthFilter` odpowiada za
autoryzację Spring Security (role, `@PreAuthorize`), `TenantFilter` za
multi-tenancy (`TenantContext` ThreadLocal + RLS w DB). Rozdzielenie odpowiedzialności
ułatwia testowanie i pozwala `TenantFilter` zwracać RFC 7807 bezpośrednio (przed
`ExceptionTranslationFilter`).

### 2.2 JWT – claims i lifecycle

`JwtService` (wystawianie, RS256, klucz prywatny) / `JwtParser` (weryfikacja, klucz
publiczny):

| Claim | Opis |
|---|---|
| `sub` | email użytkownika |
| `tenant_id` | UUID tenanta |
| `tenant_name` | nazwa tenanta (do UI) |
| `user_id` | UUID użytkownika |
| `role` | `ADMIN` \| `SUPERVISOR` \| `AGENT` |
| `email` | email (duplikat `sub`, do czytelności) |
| `mfaVerified` | `true` po przejściu TOTP |
| `iss` | `jwt.issuer` (domyślnie `contact-center`) |
| `exp` / `iat` | access token TTL = 900s (15 min) |

**Refresh token:** UUID v4 (`JwtService.generateRefreshTokenValue()`), przechowywany
w tabeli `refresh_token` (TTL = 604800s / 7 dni). `POST /api/auth/refresh` wymienia
refresh token na nowy access token (+ rotacja refresh tokenu).

**Logout:** `POST /api/auth/logout` – unieważnia refresh token w DB + wpisuje access
token na blacklistę Redis (`TokenBlacklistService`, TTL = pozostały czas życia
tokenu). `JwtAuthFilter` sprawdza blacklistę przy każdym żądaniu.

### 2.3 MFA (TOTP)

`MfaService` – RFC 6238, SHA1, 6 cyfr, okres 30s, tolerancja ±1 krok (90s razem).
Ochrona przed replay attack: użyty kod zapisywany w Redis
(`mfa:used:{userId}:{code}`, TTL 90s).

Przepływ:
```
GET /api/auth/mfa/setup   → generateSecret() + generateQrCodeDataUri() (otpauth://)
                              użytkownik skanuje QR w Google Authenticator/Authy
POST /api/auth/mfa/verify → verifyCode(secret, code, userId)
                              sukces → JWT z mfaVerified=true
```

### 2.4 Role i autoryzacja

`@EnableMethodSecurity(prePostEnabled = true)` + `@PreAuthorize` na poziomie metod
kontrolerów. Role: `ADMIN`, `SUPERVISOR`, `AGENT` (z claim `role`, prefiks `ROLE_`
dodawany automatycznie przez `AppUserDetails.getAuthorities()`).

Reguły URL-level w `SecurityConfig` (kolejność ma znaczenie – Spring Security
dopasowuje pierwszy matching matcher):
- `/api/admin/**` → `ADMIN`
- `GET/PATCH /api/tenants/*/config` → `ADMIN` lub `SUPERVISOR` (musi być **przed**
  ogólną regułą `/api/tenants/**`)
- `/api/tenants/**` → `ADMIN`
- `/api/supervisor/twilio-config/**`, `/api/supervisor/ai-config/**` → `SUPERVISOR`
- `POST /api/contacts/*/ai-summary` → `AGENT`/`SUPERVISOR`/`ADMIN`
- pozostałe → `authenticated()`

### 2.5 Publiczne ścieżki – dwa miejsca synchronizacji

Każdy nowy publiczny endpoint wymaga wpisu w **obu** miejscach:

1. `SecurityConfig.securityFilterChain()` – `requestMatchers(...).permitAll()`
2. `TenantFilter` → `PublicPathsConfig.PUBLIC_PREFIXES` (lista współdzielona)
3. (jeśli endpoint wymaga pominięcia `JwtAuthFilter`) –
   `JwtAuthFilter.PUBLIC_PATH_PREFIXES` – osobna, podobna lista

Aktualne publiczne prefiksy: `/actuator/health`, `/api-docs`, `/swagger-ui`,
`/api/auth/login`, `/api/auth/refresh`, `/api/public/`, `/webhooks/`,
`/api/webhooks/`, `/api/telephony/webhook/**`, `/api/telephony/hold-music`,
`/api/oauth/*/callback`, `/ws/**`, `/ws-native/**`, `/api/logs`.

### 2.6 WebSocket – autentykacja STOMP

Endpoint `/ws` i `/ws-native` są publiczne w `SecurityConfig` (HTTP upgrade), ale
`WebSocketAuthInterceptor` (kanał inbound STOMP) weryfikuje JWT przy ramce
`CONNECT` i tworzy `StompPrincipal(userId, tenantId, role)`. Brak/nieprawidłowy JWT
→ odmowa CONNECT.

---

## 3. Multi-tenancy

### 3.1 TenantContext

`security/TenantContext.java` – `InheritableThreadLocal<UUID>` dla `tenantId`,
`userId`, `tenantName`, `userRole`. Ustawiany przez `TenantFilter` na początku
żądania HTTP, **czyszczony w `finally`** (zapobiega cross-tenant leakage przy
reużyciu wątków z puli).

API:
- `getTenantId()` / `getUserId()` – rzuca `IllegalStateException` gdy nie ustawiono
- `getTenantIdOrNull()` / `getUserIdOrNull()` – bezpieczne dla anonimowych żądań
- `snapshot()` → `Snapshot` (record, immutable) – do propagacji między wątkami
- `restore(Snapshot)` – przywraca kontekst w wątku roboczym
- `clear()` – **obowiązkowe** w `finally` po `restore()`

### 3.2 Wzorzec propagacji do @Async / RabbitMQ listenerów

```java
// Wątek wywołujący (HTTP):
TenantContext.Snapshot snapshot = TenantContext.snapshot();
asyncExecutor.execute(() -> {
    TenantContext.restore(snapshot);
    try {
        doAsyncWork();
    } finally {
        TenantContext.clear();
    }
});
```

**Uwaga:** serwisy konsumujące z RabbitMQ (`@RabbitListener`) działają w wątkach
**niepowiązanych** z HTTP – `TenantContext` nie jest tam ustawiony automatycznie.
Te serwisy muszą jawnie wywołać `TenantContext.setTenantId(...)` (np. na podstawie
`tenantId` z payloadu eventu) i wyczyścić w `finally`
(`TenantAwareConsumer` – bazowa klasa pomocnicza dla takich konsumentów).
`infrastructure/aspect/CrossTenantAspect` celowo **wyklucza** pakiet
`domain.websocket` z monitoringu, bo te klasy działają w wątkach RabbitMQ bez
`TenantContext`.

### 3.3 TenantAwareRepository i RLS

`domain/repository/TenantAwareRepository.java` – klasa bazowa dla repozytoriów:

- `setTenantContextInDb()` – `SELECT set_tenant_context(CAST(:tenantId AS uuid))`
  (musi być wywołane w ramach `@Transactional`, bo `set_config(..., TRUE)` jest
  per-transakcja)
- `assertSameTenant(entityTenantId, resourceId)` – rzuca `CrossTenantAccessException`
  (HTTP 403) gdy `tenant_id` encji ≠ `TenantContext.getTenantId()`
- `denyAccess(resourceId)` – jawna odmowa (gdy zasób istnieje, ale innego tenanta)

### 3.4 PostgreSQL RLS

- Funkcja `set_tenant_context(uuid)` (V023) – `set_config('app.current_tenant_id', ..., TRUE)`
  (ustawienie **lokalne dla transakcji**, resetowane po COMMIT/ROLLBACK)
- Polityki RLS (V012, rozszerzane w późniejszych migracjach) – wzorzec:
  ```sql
  CREATE POLICY pol_<table>_select ON <table>
      FOR SELECT
      USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
  ```
  Tabele z RLS: `customer`, `contact`, `campaign`, `queue`, `app_user`, `ivr_tree`,
  `email_message`, `social_message`, `social_integration`, `audit_log` (+ dalsze w
  V032, V041, V042, V069-V072 dla nowych modułów).

RLS jest **drugą linią obrony** – `assertSameTenant()` w warstwie aplikacji jest
pierwszą (chroni przed błędami logiki przed wysłaniem zapytania do DB).

---

## 4. Wzorce i konwencje

### 4.1 GlobalExceptionHandler (RFC 7807)

`api/GlobalExceptionHandler.java` – `@RestControllerAdvice`, wszystkie błędy w
formacie `ProblemDetail`:

```json
{
  "type": "https://contactcenter.io/errors/{error-code}",
  "title": "...",
  "status": 422,
  "detail": "...",
  "timestamp": "2026-06-12T10:00:00Z",
  "errors": { "field": "message" }
}
```

| Wyjątek | HTTP | Uwagi |
|---|---|---|
| `MethodArgumentNotValidException` (@Valid) | 422 | mapa field→message |
| `ConstraintViolationException` (@RequestParam/@PathVariable) | 400 | |
| `CrossTenantAccessException` | 403 | komunikat generyczny – nie ujawnia istnienia zasobu |
| `AccessDeniedException` (Spring Security) | 403 | |
| `BadCredentialsException` | 401 | |
| `DisabledException` | 403 | konto nieaktywne |
| `AuthService.InvalidTokenException` | 401 | refresh token |
| `RateLimitExceededException` | 429 | `Retry-After: 900` |
| `ResourceLimitExceededException` | 422 | limity tenanta (agenci/kolejki/kampanie) |
| `ResourceNotFoundException` / `EntityNotFoundException` | 404 | |
| `ObjectOptimisticLockingFailureException` | 409 | `@Version` konflikt |
| `MethodArgumentTypeMismatchException` | 400 | np. UUID zamiast string |
| `RoutingRuleConflictException` | 409 | `collidingRuleIds` |
| `DataIntegrityViolationException` | 409 | fallback dla trigger `routing_rule_collision` |
| `ConflictException` / `InvalidOperationException` | 409 | konflikty stanu domeny |
| `TemplateRenderException` | 422 | `missingVariables` |
| `TelephonyAdapter.TelephonyException` | 404 | sesja połączenia nie istnieje |
| `UnsupportedOperationException` | 501 | operacja nieobsługiwana przez adapter |
| `TwilioApiException` / `AiSummaryGenerationException` | 502 | błąd usługi zewnętrznej |
| `AiConfigNotFoundException` | 422 | brak konfiguracji AI tenanta |
| `Exception` (catch-all) | 500 | bez szczegółów w odpowiedzi |

### 4.2 DTO i mapowanie

- DTO jako `record` w pakietach `api/<moduł>/dto/`
- Walidacja przez Jakarta Bean Validation (`@NotNull`, `@Pattern`, `@Email`...)
  na rekordach DTO + `@Valid` w kontrolerach
- Brak generycznego mappera (MapStruct) – mapowanie ręczne w serwisach/kontrolerach
  (konstruktory DTO / statyczne fabryki `XxxResponse.from(entity)`)

### 4.3 Paginacja – PagedResponse<T>

`api/PagedResponse.java` – generyczny wrapper nad Spring Data `Page<T>`:

```json
{
  "content": [...],
  "page": 0, "size": 20,
  "totalElements": 150, "totalPages": 8,
  "first": true, "last": false
}
```

Globalny limit: `spring.data.web.pageable.max-page-size: 100`,
`default-page-size: 20` (application.yml).

### 4.4 Audyt (`@Audited` + `AuditAspect`)

Metody serwisów oznaczone `@Audited(entityType="USER", captureOldValue=true)` są
przechwytywane przez `infrastructure/aspect/AuditAspect`:

1. (opcjonalnie) `EntityManager.find()` – stary stan (`old_value`), z L1 cache gdy
   możliwe (mapowanie `entityType` → klasa JPA w `ENTITY_CLASS_MAP`)
2. wykonanie metody biznesowej
3. serializacja `new_value` (JSON, bez pól: `password`, `passwordHash`, `mfaSecret`,
   `token`, `refreshToken`)
4. `AuditLogService.publish(AuditLogEvent)` → exchange `cc.audit` → `AuditLogConsumer`
   → INSERT do `audit_log`

Błędy audytu nie przerywają operacji biznesowej (logowane jako WARN/ERROR).

### 4.5 Optimistic locking

Encje z `@Version` (np. `Campaign`, `Queue`) – konflikt równoczesnej edycji →
`ObjectOptimisticLockingFailureException` → HTTP 409 (klient odświeża i ponawia).

### 4.6 Zadania cykliczne i blokady rozproszone (ShedLock, BE-171/BE-172)

Przy więcej niż jednej instancji backendu każda metoda `@Scheduled` uruchomiłaby się na każdej instancji.
Joby singletonowe są serializowane przez **ShedLock** (`shedlock-spring` + `shedlock-provider-jdbc-template`,
wersja w property `shedlock.version` w `backend/pom.xml`), tabela platformowa `shedlock` (migracja V149, bez RLS i
`tenant_id`, GRANT DML dla `app_user`).

> **Bramka wdrożeniowa (twarda): więcej niż 1 replika backendu dopiero po BE-175 (broker WebSocket współdzielony)
> i BE-179 (stan lokalny cache).** WebSocket używa dziś wbudowanego `SimpleBroker` w pamięci JVM
> (`WebSocketConfig`, `enableSimpleBroker`): zdarzenie opublikowane na jednej replice trafia tylko do sesji
> podłączonych do TEJ repliki. Przed ShedLock każda replika publikowała do własnych subskrybentów (poprawnie); po
> ShedLock publikuje tylko zwycięzca blokady, więc sesje na pozostałych replikach przestają dostawać zdarzenia.
> Joby publikujące WS bezpośrednio przez `SimpleBroker` i objęte tą bramką: `SupervisorMetricsService#broadcastMetrics`
> (metryki RT - supervisorzy na replice-przegranej nie dostają ich wcale), `WaitTimeEstimationServiceImpl#broadcastWaitTimeUpdates`
> (EWT + lokalny `lastKnownAgentCounts`, BE-179) oraz `ContactAssignmentMonitor#checkStaleAssignments` (ponowne
> `CONTACT_ASSIGNED` do agenta, którego sesja WS jest na innej replice, nie dotrze, a licznik prób w Redis rośnie ->
> reset kontaktu do QUEUED mimo żywej sesji). Pośrednio (zdarzenie przez RabbitMQ do konsumenta WS, który też
> wypuszcza je przez własny `SimpleBroker`): `AgentBreakActivator`, `AgentPresenceSweeper`. Do czasu BE-175/BE-179
> produkcja i compose zostają przy JEDNEJ instancji.

**Konfiguracja** (`infrastructure/config/ShedLockConfig.java`):

- `JdbcTemplateLockProvider` z `usingDbTime()` - czas blokady liczy zegar bazy (UTC), więc rozjazd zegarów instancji nie
  skraca ani nie wydłuża blokady. Kolumny `shedlock.lock_until/locked_at` są celowo `TIMESTAMP` bez strefy
  (provider zapisuje `timezone('utc', CURRENT_TIMESTAMP)`) - nie zmieniać typów.
- `@EnableSchedulerLock(interceptMode = PROXY_METHOD, order = HIGHEST_PRECEDENCE, proxyTargetClass = true)`:
  advisor AOP wokół metody z `@SchedulerLock`, **najbardziej zewnętrzny** (blokada przed otwarciem `@Transactional`
  joba; provider sam używa `REQUIRES_NEW`). Skutek uboczny `PROXY_METHOD`: **bezpośrednie wywołanie metody z
  `@SchedulerLock` na beanie Springa też jest blokowane** (pominięte, gdy blokada jest trzymana lub trwa
  `lockAtLeastFor`); na obiekcie z `new` (testy jednostkowe) blokady nie ma. Testy integracyjne wołające joby przez
  beana muszą to uwzględnić (czyścić `DELETE FROM shedlock WHERE name LIKE ...`).
- Bean `LockingTaskExecutor` do blokad programowych (polling e-mail per skrzynka).
- Scheduling w testach: pełny kontekst `@SpringBootTest` nie jest dziś uruchamiany (`ContactCenterApplicationIT` nie
  jest wykonywany przez surefire); testy jobów budują małe konteksty (`ShedLockTestInstance`) z prawdziwym
  providerem i `@EnableScheduling`. Profil `test` nie wyłącza schedulingu - nie ma potrzeby, bo nie ładuje całej aplikacji.

**Konwencja nazw:** `<Klasa>_<metoda>` (kolumna `name` to VARCHAR(64)); wyjątek: `EmailPolling_pollInbox_<tenantId>`
(59 znaków). Test `SchedulerLockCoverageTest` pilnuje konwencji, unikalności, `lockAtLeastFor <= lockAtMostFor` i tego,
że KAŻDA metoda `@Scheduled` ma blokadę albo wpis na liście wyjątków i wiersz w tabeli poniżej.

**Dobór `lockAtMostFor` / `lockAtLeastFor`:**

- `lockAtMostFor` to bezpiecznik na wypadek padniętej instancji, a nie limit czasu pracy: musi być dłuższy niż realny
  maksymalny przebieg (inaczej drugi węzeł wejdzie w trakcie), ale nie tak długi, by po awarii joby długo stały.
  Joby cron (raz dziennie): `atMost` 30 min - 2 h; krótkie interwały: 2-3x interwał, ETL i polling e-mail dłużej
  (cykl ETL to do 100 tys. wierszy, IMAP + S3 trwa minuty).
- `lockAtLeastFor` ogranicza częstotliwość między instancjami (dwie instancje w przesuniętych fazach nie dadzą 2x na
  interwał) i chroni cron przed rozjazdem zegarów JVM między węzłami (cron liczy zegar JVM, blokada zegar bazy; węzeł z zegarem +2 min odpaliłby job po wygaśnięciu krótkiego `atLeast` jeszcze raz) - dla cronów dziennych **10 min**, bo przy jednym przebiegu na dobę nic to nie kosztuje. **Przy `fixedDelay` nie może zablokować
  własnego następnego przebiegu:** kolejny start tej samej instancji następuje po `interwał + czas pracy`, więc
  `lockAtLeastFor < interwał` jest bezpieczne. Przyjęto **~0,8x interwału** (a nie 0,5x z tickietu): przy 0,5x dwie
  instancje w przeciwfazie wykonują job co 0,5 interwału (2x na interwał), przy 0,8x co >= 0,8 interwału; margines 0,2
  absorbuje różnicę zegara JVM vs baza. `lockAtLeastFor == interwał` jest NIEbezpieczne (job o czasie pracy bliskim 0
  i zegar bazy przed zegarem JVM -> instancja pominęłaby własny przebieg). Dla `fixedRate` (SupervisorMetrics, 5 s)
  przyjęto 3 s.
- Interwał ETL ma jedną nazwę env **`ETL_SYNC_FIXED_DELAY_MS`** (`etl.sync.fixed-delay-ms`); stara nazwa `ETL_SYNC_DELAY_MS` działa jako fallback (`${ETL_SYNC_FIXED_DELAY_MS:${ETL_SYNC_DELAY_MS:60000}}` w `application.yml`, `-dev`, `-prod`). Test `SchedulerLockCoverageTest#lockAtLeastForIsBelowTheInterval` sprawdza, że dla domyślnych interwałów (profile base/dev/prod) `lockAtLeastFor < interwał`.
- Wartości dla interwałów konfigurowalnych idą przez placeholdery z domyślnymi (`${shedlock.etl.at-most-for:PT10M}`
  itd.; klucze w tabeli). **Obniżenie interwału joba (env/property) poniżej domyślnego WYMAGA obniżenia odpowiedniego `shedlock.<job>.at-least-for` (np. `SHEDLOCK_ETL_AT_LEAST_FOR`) - inaczej blokada po cichu spowolni job do kadencji `at-least`; nie ma automatycznej walidacji dla wartości z env, tylko dla domyślnych** (np. dev:
  `agent.breaks.activator.interval-ms=10000` ma `shedlock.break-activator.at-least-for=PT8S` w `application-dev.yml`);
  podwyższenie interwału nie wymaga zmian.
- Wygaśnięcie blokady w trakcie przebiegu nie zwalnia z idempotencji (kolumna „Idempotencja" poniżej).
  `unlock()` wykonuje `... WHERE name = ? AND locked_by = ?`, więc instancja z wygasłą blokadą NIE zwolni blokady
  przejętej przez inny węzeł, o ile `locked_by` obu jest różne: stąd `locked_by = host:pid:uuid` (uuid na start JVM;
  domyślny hostname nie odróżniałby dwóch JVM na jednym hoście). `lockAtMostFor` nadal ma być zapasowe, a nie robocze.
- Cron raz dziennie + awaria instancji w trakcie przebiegu = przebieg tego dnia przepada (inne instancje już go
  pominęły); nadrabia go następny dzień. Bez ShedLock każda instancja wykonałaby job - tu świadomie wybieramy
  pojedynczy przebieg.
- Rolling deploy: instancje starej wersji (bez ShedLock) nie biorą blokad - w oknie wdrożenia joby mogą wykonać się
  podwójnie; przed wdrożeniem V149 musi być zastosowana.

**Klasyfikacja WSZYSTKICH metod `@Scheduled`** (weryfikacja: `grep -rnE '^\s*@Scheduled\b' backend/app/src/main` = 25 wierszy
tabeli). S = singleton globalny (ShedLock), I = per instancja (bez blokady, świadomie), T = shardowalny per
tenant/kampania/kolejka (BE-173, zrealizowane: blokada per kampania/kolejka wewnątrz przebiegu, patrz „Dialer i routing" niżej). Podsumowanie: **S = 23, I = 0, T = 2**.

| # | Metoda | Harmonogram | Kl. | Blokada / klucze property | atMost / atLeast | Idempotencja przy wygaśnięciu blokady w trakcie przebiegu | Stan |
|---|--------|-------------|-----|---------------------------|------------------|-----------------------------------------------------------|------|
| 1 | `RecordingRetentionJob#runRetentionJob` | cron 02:00 UTC | S | `RecordingRetentionJob_runRetentionJob` | PT2H / PT10M | Usuwanie nagrań po progu: drugi przebieg nie znajdzie już wyczyszczonych `recording_url`; DELETE obiektu S3 idempotentny | zrealizowane (BE-171) |
| 2 | `RefreshTokenCleanupJob#run` | cron 03:30 UTC | S | `RefreshTokenCleanupJob_run` | PT30M / PT10M | Pojedynczy `DELETE ... WHERE wygasły/odwołany` - bezpieczny | zrealizowane (BE-171) |
| 3 | `PendingAttachmentSweepJob#runSweep` | cron 02:30 UTC | S | `PendingAttachmentSweepJob_runSweep` | PT1H / PT10M | Re-sprawdza referencje przed usunięciem, DELETE S3 idempotentny; domyślnie dry-run | zrealizowane (BE-171) |
| 4 | `CampaignArchiveJob#run` | cron 04:00 UTC | S | `CampaignArchiveJob_run` | PT1H / PT10M | Funkcja SQL `archive_completed_campaign_contacts()` idempotentna (V015); job domyślnie wyłączony. Odczyt `cron_log` „najnowszy wpis" patrz `CampaignArchiveJobRepository` | zrealizowane (BE-171) |
| 5 | `PartitionReclaimJob#runReclaimJob` | cron niedz. 03:00 UTC | S | `PartitionReclaimJob_runReclaimJob` | PT1H / PT10M | `DROP TABLE IF EXISTS` + strażnik niepustych partycji (BE-145) | zrealizowane (BE-171) |
| 6 | `RetentionEvaluationJob#runEvaluationJob` | cron 01:00 UTC | S | `RetentionEvaluationJob_runEvaluationJob` | PT2H / PT10M | Cutoffy liczone na nowo, DELETE-y po cutoffie idempotentne; brak własnej serializacji (tylko `retention_purge_log` RUNNING) - drugi przebieg dubluje co najwyżej wpisy logu, nie dane. Uwaga: auto-purge startuje przez `purgeAsync` (`@Async`) i PRZEŻYWA blokadę (zwalniana po powrocie z `runForAllActiveTenants`); ochroną przed równoległym purge jest własna serializacja EPIC-30, nie ShedLock | zrealizowane (BE-171) |
| 7 | `PartitionMaintenanceJob#ensureFuturePartitions` | cron 00:30 UTC | S | `PartitionMaintenanceJob_ensureFuturePartitions` | PT30M / PT10M | Funkcje `create_*_partition` z `IF NOT EXISTS` | zrealizowane (BE-171) |
| 8 | `EtlSyncServiceImpl#runContactSync` | fixedDelay `etl.sync.fixed-delay-ms` (60 s) | S | `EtlSyncServiceImpl_runContactSync`; `shedlock.etl.at-most-for` / `.at-least-for` | PT10M / PT48S | Upsert DW po kluczu (ON CONFLICT / ReplacingMergeTree), znacznik przesuwany monotonicznie (BE-213) - drugi przebieg czyta te same batche, nie duplikuje i nie cofa znacznika (test `EtlSyncShedLockIntegrationTest`). ClickHouse: `argMax` po `updated_at = now()` zakłada jednego writera - wygaśnięcie blokady = chwilowo dwóch (dług S6 niżej). Ręczny trigger (`POST /api/admin/etl/trigger`) bierze TĘ SAMĄ blokadę (`EtlSyncService.LOCK_CONTACT_SYNC`, `lockAtLeastFor=0`) i przy zajętej zwraca 409 `ETL_SYNC_IN_PROGRESS` | zrealizowane (BE-172) |
| 9 | `EtlSyncServiceImpl#runCampaignContactSync` | j.w. | S | `EtlSyncServiceImpl_runCampaignContactSync`; klucze j.w. | PT10M / PT48S | j.w. | zrealizowane (BE-172) |
| 10 | `EtlSyncServiceImpl#runAgentDimSync` | j.w. | S | `EtlSyncServiceImpl_runAgentDimSync`; klucze j.w. | PT10M / PT48S | Pełny snapshot wymiaru, ReplacingMergeTree(snapshot_at) - idempotentny | zrealizowane (BE-172) |
| 11 | `EtlSyncServiceImpl#runQueueDimSync` | j.w. | S | `EtlSyncServiceImpl_runQueueDimSync`; klucze j.w. | PT10M / PT48S | j.w. | zrealizowane (BE-172) |
| 12 | `EtlSyncServiceImpl#checkJobLiveness` | fixedDelay `etl.sync.liveness-check-interval-ms` (60 s) | S | `EtlSyncServiceImpl_checkJobLiveness`; `shedlock.etl-liveness.at-most-for` / `.at-least-for` | PT3M / PT48S | Czysty odczyt + alert; duplikat alertu tłumi cooldown Redis (BE-214) | zrealizowane (BE-172) |
| 13 | `EmailPollingServiceImpl#pollAllTenants` | fixedDelay `email.poll-delay-ms` (60 s) | S | programowo, per skrzynka: `EmailPolling_pollInbox_<tenantId>` (`LockingTaskExecutor`) | max(3x interwał, 5 min) / 0,8x interwał | Deduplikacja po `Message-ID` pod advisory lockiem (BE-134) - wygaśnięcie blokady nie tworzy duplikatów; wyjątek: wiadomość BEZ nagłówka `Message-ID` mogłaby zostać zapisana dwa razy | zrealizowane (BE-172) |
| 14 | `SocialIntegrationServiceImpl#refreshExpiringTokens` | fixedDelay 1 h | S | `SocialIntegrationServiceImpl_refreshExpiringTokens` | PT2H / PT45M | Podwójna wymiana tokenu: ostatni zapis wygrywa, dodatkowy wpis audytu; ryzyko niskie | zrealizowane (BE-172) |
| 15 | `ScheduledCallbackExecutor#executeScheduledCallbacks` | fixedDelay `dialer.callback-executor.interval-ms` (60 s) | S | `ScheduledCallbackExecutor_executeScheduledCallbacks`; `shedlock.callback-executor.*` | PT3M / PT48S | Atomowe `PENDING -> PROCESSING` (`updateStatusIfPending`) - drugi węzeł dostaje 0 zmienionych wierszy | zrealizowane (BE-172) |
| 16 | `DialerCallbackHandlerImpl#checkRingTimeouts` | fixedDelay `dialer.ring-timeout-check-interval-ms` (5 s) | S | `DialerCallbackHandlerImpl_checkRingTimeouts`; `shedlock.ring-timeout.*` | PT30S / PT4S | Redis `GETDEL` na `dialer:call:{callSid}` - tylko jeden wykonawca przetwarza połączenie | zrealizowane (BE-172) |
| 17 | `CampaignWindowActivator#activateScheduledCampaigns` | fixedDelay `campaign.window-activator.interval-ms` (60 s) | S | `CampaignWindowActivator_activateScheduledCampaigns`; `shedlock.window-activator.*` | PT3M / PT48S | `SCHEDULED -> RUNNING` ustawia tę samą wartość; wyścig z ręczną zmianą statusu - ostatni zapis wygrywa (ryzyko niskie) | zrealizowane (BE-172) |
| 18 | `ContactAssignmentMonitor#checkStaleAssignments` | fixedDelay 10 s | S | `ContactAssignmentMonitor_checkStaleAssignments` | PT30S / PT8S | Licznik prób w Redis (`cc:assign-retry:*`) - bez blokady dwie instancje liczą 2x szybciej i dublują `CONTACT_ASSIGNED`; reset do QUEUED warunkowy (`resetAssignmentIfAssigned`, BE-159) | zrealizowane (BE-172) |
| 19 | `AgentBreakActivator#activateAndCompleteBreaks` | fixedDelay `agent.breaks.activator.interval-ms` (30 s; dev 10 s, prod 60 s) | S | `AgentBreakActivator_activateAndCompleteBreaks`; `shedlock.break-activator.*` | PT90S / PT24S (dev PT30S / PT8S) | `UPDATE ... RETURNING` zwraca przerwy tylko raz; zmiana statusu agenta warunkowa (`changeAgentStatusIfBreak`) | zrealizowane (BE-172) |
| 20 | `SupervisorMetricsService#broadcastMetrics` | fixedRate 5 s | S | `SupervisorMetricsService_broadcastMetrics` | PT15S / PT3S | Tylko odczyt + publikacja WS; podwójny przebieg = zdublowany event (odbiorca nadpisuje stan). Jedna instancja publikuje; po BE-175 broker dostarczy zdarzenie wszystkim sesjom | zrealizowane (BE-172) |
| 21 | `WaitTimeEstimationServiceImpl#broadcastWaitTimeUpdates` | fixedDelay 30 s (initialDelay 5 s) | S | `WaitTimeEstimationServiceImpl_broadcastWaitTimeUpdates` | PT90S / PT24S | Odczyt + `SET` statystyk w Redis (ostatni wygrywa) + WS - bezpieczne. **Stan lokalny:** `lastKnownAgentCounts` (cache JVM używany przez `getQueueStats`) odświeża tylko instancja, która wygrała blokadę - na pozostałych `getQueueStats` zwróci 0 agentów do czasu BE-179 (przeniesienie do Redis) | zrealizowane (BE-172); dług: BE-179 |
| 22 | `AgentPresenceSweeper#scheduledSweep` | fixedDelay `agent.presence.sweep-interval-ms` (30 s) | S | `AgentPresenceSweeper_scheduledSweep`; `shedlock.presence-sweeper.*` | PT90S / PT24S | Compare-and-set `markOfflineOnPresenceTimeout` - dwie instancje dają najwyżej jedno przejście i jedno zdarzenie. Przebieg startowy (`onApplicationReady`) świadomie NIE jest blokowany (per instancja, własny `startedAt`) | zrealizowane (BE-172) |
| 23 | `ProgressiveDialerServiceImpl#pollAvailableAgents` | fixedDelay `dialer.agent-poll-interval-ms` (30 s) | T | brak `@SchedulerLock` (świadomie); `pg_try_advisory_xact_lock(0x43430173, hashtext(campaignId))` per kampania w krótkiej transakcji claim | - | Redis `SET NX` per agent + warunkowy `DIALING` (`status IN (PENDING,NO_ANSWER)`, sprawdzenie liczby wierszy) + `SKIP LOCKED`; claim cofany przy błędzie po claim | zrealizowane (BE-173) |
| 24 | `RoutingServiceImpl#pollAvailableAgents` | fixedDelay `dialer.agent-poll-interval-ms` (30 s) | T | brak `@SchedulerLock` (świadomie); ShedLock `RoutingQueue_<queueId>` (programowo, `RoutingQueueLock`) per kolejka wewnątrz przebiegu | atMost PT2M (`routing.queue-lock.at-most-for-ms`) / 0 | Przydział warunkowy (BE-159); koalescencja per tenant w JVM zostaje, między instancjami wyklucza blokada kolejki | zrealizowane (BE-173) |
| 25 | `OrphanedDialingReaper#reapOrphanedDialing` | fixedDelay `dialer.dialing-reaper.interval-ms` (5 min) | S | `OrphanedDialingReaper_reapOrphanedDialing`; `shedlock.dialing-reaper.*` | PT15M / PT4M | Idempotentne UPDATE-y warunkowe na `status='DIALING'` starszych niż `dialer.dialing-reaper.stale-after-minutes` (10); powtórka po wygaśnięciu blokady nic nie zmienia | zrealizowane (BE-173 review S3) |

Klasa **I** jest pusta: żadna metoda `@Scheduled` nie wymaga wykonywania na każdej instancji (jedyny taki przypadek to
przebieg startowy `AgentPresenceSweeper#onApplicationReady`, nie `@Scheduled`). Inne mechanizmy czasowe poza
`@Scheduled`: timeouty DTMF IVR (`taskScheduler.schedule` per rozmowa, stan w pamięci JVM - BE-177).

**Decyzje (BE-172):**

- **Polling e-mail - blokada per skrzynka, nie globalna.** Pętla już iteruje po tenantach, a skrzynka IMAP należy do
  tenanta (jedna na tenanta), więc `tenantId` == skrzynka. Blokada globalna zserializowałaby cały polling na jednej
  instancji; per tenant dwie instancje dzielą się skrzynkami (kto pierwszy, ten bierze). `TenantContext` jest ustawiany
  i czyszczony wewnątrz blokady, na tym samym wątku schedulera.
- **`EtlSyncServiceImpl.readAndLockSyncState` - martwy `@Transactional` usunięty, nie „naprawiony".** Adnotacja na
  metodzie package-private wołanej self-invocation nigdy nie działała, więc `SELECT ... FOR UPDATE` szedł w autocommit
  i blokada wiersza zwalniała się od razu. Zamiast otwierać transakcję na cały cykl (do 100 tys. wierszy + zapisy do DW,
  heartbeat i znacznik aktualizują ten sam wiersz) wzajemne wykluczanie przejął ShedLock na `run*Sync`; z metody usunięto
  `@Transactional` i `FOR UPDATE`, a komentarze mówią, że `RUNNING` to tylko stan dla liveness (BE-212).
- **Single-writer ClickHouse (BE-215).** Blokada per metoda `run*Sync` daje jednego writera na tabelę (kontakty i
  `campaign_contact` mogą pracować równolegle na różnych instancjach - różne tabele DW). Okno dwóch writerów:
  wygaśnięcie blokady w trakcie cyklu (stąd zapas `PT10M`). **Ręczny trigger** `POST /api/admin/etl/trigger`
  (tylko tabela `contact`) przechodzi przez `LockingTaskExecutor` z tą samą nazwą blokady co zaplanowany cykl
  (`lockAtLeastFor=0`); gdy blokada zajęta (cykl trwa tu lub na innej instancji) odpowiedź to **409 Conflict**
  (`ProblemDetail`, `code=ETL_SYNC_IN_PROGRESS`), więc trigger nie jest już drugim writerem.
- **Monitory (`ContactAssignmentMonitor`, `AgentBreakActivator`, `WaitTimeEstimationServiceImpl`) - S.** Pierwszy ma
  semantykę „raz na interwał" opartą o licznik w Redis (bez blokady liczy 2x szybciej), drugi jest bezpieczny dzięki
  `UPDATE ... RETURNING`, ale daje podwójną pracę, trzeci publikuje zdarzenia WS i zapis Redis (stan lokalny: BE-179).
  `TenantContext` w jobach wielotenantowych: ShedLock wykonuje zadanie inline na wątku schedulera (brak granicy wątków),
  więc obowiązuje istniejący wzorzec `setTenantId(...)` + `clear()` w `finally` per tenant/rekord - bez
  snapshot/restore (te służą przekraczaniu wątków, `@Async`/`CompletableFuture`).

**Otwarte kwestie / dług:**

1. **ClickHouse `argMax` vs dwóch writerów (S6, dług jawny).** Widoki BE-215 deduplikują po `updated_at`, które
   `ClickHouseDwWriter` ustawia jako `Instant.now()` w chwili zapisu. Gdy `lockAtMostFor` wygaśnie w trakcie cyklu i
   drugi węzeł zapisze nowszą wersję wiersza przed wolniejszym pierwszym, starsza wersja zapisana później wygra
   `argMax`. Zapas `PT10M`, monotoniczny znacznik i idempotentne upserty zmniejszają, ale nie eliminują ryzyka; test
   `EtlSyncShedLockIntegrationTest` dowodzi braku duplikatów na PostgreSQL (`ON CONFLICT`), NIE semantyki `argMax`
   ClickHouse. Do rozważenia w osobnym tickecie: `updated_at` w DW z kolumny źródłowej zamiast `now()`
   albo `KeepAliveLockProvider` dla ETL. Wpis także w `dw/README.md`.
2. ~~`WaitTimeEstimationServiceImpl.lastKnownAgentCounts`~~ - usunięte w BE-179 (patrz §4.7). Joby klasy T (dialer/routing) - zrealizowane w BE-173 (sekcja niżej).
3. **Brak metryki/alertu „job pominięty, bo blokada trzymana" i „blokada przejęta po wygaśnięciu".** Przy
   `throwUnexpectedException=false` (domyślnie) awaria DB przy zakładaniu blokady jest tylko logiem ERROR providera,
   a job po prostu nie startuje na żadnej instancji. Licznik Micrometer wymagałby własnego wrappera nad
   `LockProvider` - świadomie odłożone (dług).
4. Skan `SchedulerLockCoverageTest` nie widzi schedulingu programowego (`taskScheduler.schedule`,
   `SchedulingConfigurer`, np. IVR DTMF) - tylko adnotacji `@Scheduled`.
5. ~~Dowód startu pełnego kontekstu na dev/prod~~ - rozwiązane w BE-227: `FullApplicationContextStartupTest`
   (profil prod, rola bez BYPASSRLS, uruchamiany w `mvn verify`); martwy `ContactCenterApplicationIT` usunięty.

**Dialer i routing: blokada per kampania / per kolejka (BE-173).** Obie pętle (`pollAvailableAgents`, plus ścieżki
event-driven `onAgentStatusChanged`/`onContactQueued`) mogą chodzić na wielu instancjach równolegle dla RÓŻNYCH
kampanii/kolejek, nigdy dla tej samej. Dwa różne mechanizmy, bo różne są granice transakcji:

| | Dialer (kampania) | Routing (kolejka) |
|---|---|---|
| Mechanizm | `pg_try_advisory_xact_lock(int, int)`: key1 = `AdvisoryLockKeys.NAMESPACE_DIALER_CAMPAIGN` (0x43430173), key2 = `hashtext(campaignId)` | ShedLock programowy (`RoutingQueueLock`, `LockingTaskExecutor`), nazwa `RoutingQueue_<queueId>` (49 znaków, limit 64), atLeast 0, atMost `routing.queue-lock.at-most-for-ms` (PT2M) |
| Dlaczego | Przydział to krótka transakcja (agent nadal wolny + `FOR UPDATE SKIP LOCKED` + warunkowy `DIALING`); blokada zwalnia się z commitem, ZANIM ruszy telefonia; brak wycieku przy awarii; zgodne z PgBouncer (BE-181) | Przebieg routingu świadomie NIE jest jedną transakcją (każdy przydział = krótka transakcja, patrz uwaga w `RoutingServiceImpl#routeContact`); advisory xact lock wymagałby transakcji na cały przebieg (blokady per agent z BE-156 trzymane przez całą pętlę = ryzyko deadlocku), advisory sesyjny odpada przy PgBouncer |
| Poziom | kampania. Blokada kampanii to USZEREGOWANIE i ochrona przed zbędną pracą, NIE mechanizm poprawności: poprawność dają `FOR UPDATE SKIP LOCKED` + warunkowy `DIALING` + blokada per agent (ten sam klucz co `assignIfQueued`, BE-156, ale `assignIfQueued` bierze ją tylko przy limicie async, a routing PHONE wcale: szereguje dialer × dialer i dialer × przydział async, NIE dialer × routing PHONE = otwarty P0) z ponowną weryfikacją „agent wolny" i zapisem `dialing_agent_id` (V151; niezmiennik: ważne tylko przy `DIALING`, pilnują go zapisy Java, trigger i `CHECK`); token Redis `dialer:agent:<id>` (TTL 60 s) to dodatkowy pas bezpieczeństwa i ogranicza zbędne zapytania | kolejka (nie tenant: ticket wymaga równoległości różnych kolejek) |
| Oczekiwanie | krótkie, ograniczone: `dialer.campaign-lock.wait-ms` (500 ms, retry co 25 ms; sekcja krytyczna to ms, bo nie obejmuje telefonii); po upływie przebieg pomijany, agent wolny do następnego wyzwalacza | pętla pollingu: brak; ścieżka event-driven (nowy kontakt, pętla po evencie): do `routing.queue-lock.wait-ms` (2 s) |
| Metryka | `scheduler.lock.skipped{type=dialer}` | `scheduler.lock.skipped{type=routing}` |

Klucze advisory: dotychczasowe użycia to wyłącznie forma jednoargumentowa `bigint` (`hashtextextended(<tekst>, 0)`:
limit async agenta `tenantId:agentId` w `ContactRepository`, dedup e-mail w `EmailMessageRepository`). Forma
dwuargumentowa `(int, int)` ma w PostgreSQL ODRĘBNĄ przestrzeń kluczy, więc nie koliduje z nimi (test
`twoKeyAdvisoryLockDoesNotCollideWithBigintForm`); rejestr przestrzeni: `infrastructure/lock/AdvisoryLockKeys`. Kolizja
32-bitowego hasha między dwiema kampaniami powoduje tylko chwilowe uszeregowanie ich przebiegów (poprawność
gwarantują zapisy warunkowe, nie blokada).

Dialer, granice transakcji: `initiateDialForAgent` NIE jest już `@Transactional`. Claim (`claimNextContact`) =
`set_tenant_context` + agent nadal wolny (`AVAILABLE` i brak otwartego połączenia PHONE w
`OccupiedSlot.AGENT_LOAD_STATUSES_SQL`) + blokada kampanii + `FOR UPDATE SKIP LOCKED` + warunkowy `DIALING` ze sprawdzeniem
liczby wierszy; commit zwalnia blokadę; dopiero potem `telephonyAdapter.initiateCall` (poza transakcją). Błąd telefonii
oznacza kontakt `ERROR` (jak dotąd), nieoczekiwany wyjątek cofa claim (`revertDialingToPending`). Granica bezpieczeństwa:
awaria JVM między commitem claim a zakończeniem wywołania zostawia rekord w `DIALING` (wcześniej cofała go transakcja);
osierocone `DIALING` sprząta `OrphanedDialingReaper` (V151: `campaign_contact.dialing_agent_id`; wiersz 25 tabeli). Błąd zapisu stanu Redis po udanym `initiateCall` przenosi rekord do NO_ANSWER/NOT_REACHED (nigdy PENDING: dubel połączenia), licznik `dialer.call_state_lost`. Limit kanałów kampanii progresywnej =
liczba wolnych agentów (jedno połączenie wychodzące na agenta; brak kolumny „max linii").
Routing: przebieg pogrupowany po kolejkach (faza 1 bez blokady: kolejka social/ERROR; faza 2: kolejka pod blokadą);
skrót „agent z telefonem zajęty do następnego wyzwalacza" pozostaje wspólny dla przebiegu.
Metryki bez tagów tenantId/campaignId/queueId (kardynalność), szczegóły w logu DEBUG pominiętego przebiegu.

Agent z osieroconym `DIALING` jest blokowany własną sondą do `stale-after-minutes` + interwał reapera (10 + 5 min) - świadomy kompromis. Gałąź reapera „brak kontaktu → PENDING" przy awarii między API Twilio a INSERT-em kontaktu może zdublować połączenie (brak idempotencji `initiateCall`).

**Otwarte (osobny ticket P0, hard-gate „więcej niż 1 replika", obok BE-175/BE-179):** blokada per kolejka i blokada
kampanii NIE zamykają (a) dwóch kolejek konkurujących o tego samego agenta w routingu phone oraz (b) wyścigu
routing ↔ dialer o tego samego agenta (`assignIfQueued` dla phone nie bierze blokady per agent, a dialer nie bierze
`RoutingQueue_*`). To stan sprzed BE-173, nie regresja; rozwiązanie: blokada per agent
(`AdvisoryLockKeys.AGENT_ASSIGNMENT_LOCK_SQL`, już użyta przez dialer) przy KAŻDYM przydziale agenta wraz z
weryfikacją obciążenia. Kryterium akceptacji „brak podwójnego przydziału agenta" jest więc spełnione dla dialer × dialer
i routing × routing w obrębie jednej kolejki. Dług: wiersze `shedlock` usuniętych kolejek (`RoutingQueue_*`) nie są
czyszczone (do BE-228). `initiateCall` nie ma klucza idempotencji: timeout HTTP PO utworzeniu połączenia po stronie Twilio
cofa claim do PENDING i numer może zostać wybrany ponownie (zachowanie sprzed BE-173).

### 4.7 Cache lokalne a wiele instancji (BE-179)

Przy więcej niż jednej replice backendu każdy stan trzymany w pamięci JVM jest per instancja. Dwa przypadki
rozwiązane w BE-179, reszta - inwentarz z oceną ryzyka.

**Unieważnianie `TwilioTelephonyAdapter.clientCache` (Caffeine, max 100, TTL 15 min):**

- `TenantTwilioConfigServiceImpl` (`saveConfig`, `deleteConfig` - jedyne ścieżki zapisu/rotacji/usunięcia
  `TenantTwilioConfig`, czyli poświadczeń klienta REST; `TenantServiceImpl.updateTwilioConfig` zapisuje tylko numer i
  callback w `tenant.config` i klienta nie dotyczy) publikuje lokalnie `TwilioConfigChangedEvent(tenantId)`.
- Dwa niezależne listenery `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` (kolejność
  wykonania nieistotna i niegwarantowana, wyjątek w AFTER_COMMIT nie cofa commitu): adapter unieważnia wpis lokalnie
  (działa także gdy Redis leży), `TwilioConfigInvalidationPublisher` publikuje przez `CacheInvalidationBus` na kanał
  Redis pub/sub `cc:cache:invalidate:twilio-client`.
- Komunikat = wyłącznie `tenantId` (UUID jako tekst); poświadczenia nigdy nie opuszczają bazy/serwisu. Zdarzenie jest
  per tenant - masowa zmiana to N małych komunikatów, bez globalnego przeładowania.
- Każda instancja, także nadawca, ma subskrypcję zarejestrowaną w `init()` adaptera. Echo do nadawcy jest zamierzone
  (idempotentne; `instanceId` w payloadzie złamałby zasadę "sam tenantId").
- **Wyścig odbudowy:** `resolveRestClient` używa atomowego `clientCache.get(key, loader)`. Caffeine wykonuje loader pod
  blokadą klucza, a `invalidate` czeka na jego koniec, więc unieważnienie w trakcie ładowania (konfiguracja czytana
  przed commitem) nie zostanie nadpisane późnym `put` starego klienta (`TwilioClientCacheRaceTest`). Zostaje
  nieusuwalne okno commit -> odbiór komunikatu na innych instancjach (milisekundy), w którym inna instancja może
  jeszcze użyć starego klienta.
- **Odporność:** `CacheInvalidationBus.publish` nigdy nie rzuca. Awaria Redis = log WARN + licznik
  `cc.cache.invalidation.publish.failures{channel}`, zapis konfiguracji (już zacommitowany) nie jest cofany.
  Pub/sub jest fire-and-forget, więc TTL 15 min zostaje siatką bezpieczeństwa dla zgubionego komunikatu (także dla
  instancji odłączonej od Redis w chwili publikacji). Metryki: `cc.cache.invalidation.published|received|
  handler.failures`. Zły payload (nie-UUID, pusty, binarny) jest liczony i logowany jako klasa wyjątku + obcięty,
  zsanityzowany fragment (max 64 znaki), listener działa dalej. Brak debouncingu per tenant jest świadomy:
  unieważnienie jest idempotentne i kosztuje jedną odbudowę klienta, a debounce z przednią krawędzią mógłby połknąć
  drugą zmianę tuż po pierwszej i zostawić stare poświadczenia.
- **Założenie bezpieczeństwa:** Redis działa w sieci zaufanej, a ACL nie daje `PUBLISH` nieuprzywilejowanym klientom.
  Spreparowany komunikat może co najwyżej unieważnić wpis (koszt: odbudowa klienta z bazy); nie podmieni poświadczeń.
  Zalew komunikatów ogranicza ograniczony executor (niżej).
- Dodanie kolejnego cache lokalnego per tenant: nowa wartość `CacheInvalidationChannel`, `bus.subscribe(...)` w
  inicjalizacji beana (przed startem kontenera) i `bus.publish(...)` po commicie zmiany.

**Kontener subskrypcji Redis (`PubSubListenerContainer`, wspólny dla BE-175):**

- W spring-data-redis 3.3.5 `RedisMessageListenerContainer.start()` czeka na rejestrację pierwszej subskrypcji
  (do 2 s) i przy nieosiągalnym Redis rzuca od razu `RedisListenerExecutionFailedException`: pierwsza próba nie jest
  ponawiana, ponawiane są dopiero odzyskiwania po udanej subskrypcji (`recoveryInterval`). Samo uruchomienie kontenera
  jako beana (SmartLifecycle) wywaliłoby `refresh()` kontekstu przy martwym Redis, więc kontener nie startuje
  automatycznie (`isAutoStartup() == false`); startuje go `PubSubListenerContainerSupervisor` po zakończeniu
  refreshu, w wątku w tle, z retry/backoffem 1 s do 30 s (`stop()` + `start()` przy każdej próbie). Aplikacja wstaje
  więc bez Redis, a subskrypcje podnoszą się po jego powrocie (test `contextStartsWithRedisDown...`).
  Po pierwszym sukcesie utratę i powrót połączenia obsługuje sam kontener (`recoveryInterval` 5 s).
- Executory są ograniczone: jeden wątek trzyma subskrypcję (`redis-pubsub-subscription-`), pula 2 wątków z kolejką 256
  wykonuje listenery (`redis-pubsub-dispatch-`). Pełna kolejka odrzuca komunikat (log co 10 s +
  `cc.pubsub.dispatch.rejected`) - akceptowalne dla idempotentnych unieważnień z TTL jako siatką. Komunikaty NIE są
  dostarczane w kolejności (2 wątki). **BE-175 musi przemyśleć executor i kolejność zdarzeń WebSocket** (wolumen,
  jednowątkowa kolejność per kanał, polityka przy przepełnieniu) przed reużyciem tego beana.

**`WaitTimeEstimationServiceImpl`:** lokalny `lastKnownAgentCounts` usunięto. `getQueueStats` czyta
`availableAgents` ze współdzielonego `cache:queue:stats:{queueId}` (zapisuje go job EWT co 30 s, TTL 60 s, więc dane
mają do ok. 60 s przy opóźnionym jobie; weryfikowane jest pole `tenantId`). Gdy wpisu brak (stan normalny: kolejka lub
tenant nieaktywny, pierwsze 30 s kolejki, job stoi) czyta krótki cache `cache:queue:agents:{tenantId}` (TTL 15 s, w
Redis, więc współdzielony), a dopiero gdy i jego brak - liczy z sesji agentów (SCAN `session:agent:*`) i zapisuje
wynik do tego cache. Klucze sesji nie zawierają tenanta, więc SCAN nie da się zawęzić; zamiast tego jego
częstotliwość spada do ok. raz na tenant na 15 s. Użycie fallbacku: `cc.ewt.stats.fallback{result=cache_hit|scan}`.
Uzasadnienie: stan już istnieje w Redis (zero dodatkowych zapisów w ścieżce normalnej), jeden GET na żądanie jest
tani, a wynik nie zależy od zwycięzcy blokady ShedLock.

**Inwentarz stanu lokalnego (stan na BE-179):**

| Miejsce | Co trzyma | Ryzyko nieaktualności per instancja | Rekomendacja |
|---|---|---|---|
| `TwilioTelephonyAdapter.clientCache` (Caffeine) | `TwilioRestClient` per tenant (klient REST) | było wysokie (stare poświadczenia do 15 min) | rozwiązane w BE-179 (pub/sub + TTL) |
| `WaitTimeEstimationServiceImpl.lastKnownAgentCounts` | liczba agentów per tenant | było wysokie (0 na instancjach bez joba) | rozwiązane w BE-179 (Redis) |
| `@Cacheable` (`AdminMetricsServiceImpl`, 4 cache names) przez `RedisCacheManager` | metryki admina | niskie - cache współdzielony w Redis, TTL 30 s-20 min | OK |
| `ReportsServiceImpl` (ręczny cache `StringRedisTemplate`) | raporty | niskie - Redis, TTL 5 min | OK |
| `AgentPresenceRegistry.lastTouch` (`ConcurrentHashMap`) | throttling zapisu obecności per sesja WS | niskie - sesja WS jest związana z jedną instancją; to tylko throttle, prawda w Redis | OK |
| `RoutingServiceImpl.passesRunning/passesDirty` | tenanci z trwającym przebiegiem backlogu | niskie - wyłącznie dedup w JVM; poprawność chroni blokada Redis/DB (BE-173) | OK |
| `AgentPresenceSweeper.running` (`AtomicBoolean`) | reentrancja joba | niskie - per instancja z założenia; wykluczenie między instancjami przez ShedLock | OK |
| `AgentPresenceSweeper.startedAt/redisUnhealthy/redisRecoveredAt` (`volatile`) | karencja obecności po starcie i stan zdrowia Redis tej instancji | niskie-średnie - per instancja z założenia; przy rolling deploy każda replika ma własne okno karencji (`startedAt`), stan zdrowia Redis może się chwilowo różnić między replikami | OK, świadomie per instancja |
| `CircuitBreakerState.consecutiveFailuresByInstallation` | licznik błędów wtyczki | średnie - bezpiecznik otwiera się niezależnie na każdej instancji (próg liczony z 1/N ruchu), restart zeruje | już w zakresie BE-178 |
| `PluginRegistryImpl`, `PluginRuntimeManagerImpl.activeHandles` | załadowane wtyczki i classloadery | wysokie - instalacja/revoke/upgrade na jednej instancji nie zmienia rejestru pozostałych do restartu (`PluginRuntimeStartupLoader` odbudowuje tylko przy starcie) | ticket BE-178 (już w planie EPIC-33) |
| `IvrEngineServiceImpl.pending*` (5 map) | kontekst konferencji/promptów między webhookami Twilio | wysokie - kolejny webhook tego samego połączenia może trafić na inną replikę (brak sticky) i zgubić kontekst | ticket BE-177 (już w planie EPIC-33) |
| `MockTelephonyAdapter.sessions/contactIdToCallId` | sesje atrapy telefonii | nie dotyczy produkcji (profil mock/dev) | OK |
| `SimpleBroker` WebSocket (STOMP) | subskrypcje i dostarczanie zdarzeń WS | wysokie - zdarzenie z jobu/instancji A nie dotrze do sesji na B | BE-175 (ADR-14, opcja B: Redis pub/sub -> lokalny SimpleBroker) |

Brak nowych ticketów: wszystkie ryzyka wysokie/średnie są już pokryte przez BE-175/177/178.

---

## 5. Integracje zewnętrzne

### 5.1 Telephony – Twilio Programmable Voice

`telephony.provider` (`mock` | `twilio`) wybiera implementację `TelephonyAdapter`:

- **`MockTelephonyAdapter`** (695 linii) – symulacja w pamięci + Redis, do dev/test.
  Aktywny przez `@ConditionalOnProperty(telephony.provider=mock)`. Tworzy rekord
  `contact` w DB **przed** publikacją `call.incoming` – UUID z DB staje się
  `contactId` w payloadzie WebSocket (obok `callId` jako String).
- **`TwilioTelephonyAdapter`** (3101 linii, `@Primary` gdy `twilio.enabled=true`) –
  integracja z Twilio REST API (`CallCreator`, `Call.UpdateStatus` – Twilio SDK
  10.1.5, `create/update(TwilioRestClient)`).

**Wzorzec konferencji Twilio** (zestawienie audio klient-agent + nagrywanie):
każde połączenie agenta tworzy dedykowaną konferencję `contact-{contactId}`;
`<Conference record="record-from-start">` + `recordingStatusCallback` →
`/api/telephony/webhook/twilio/recording`.

**Webhook handlery** (`TwilioWebhookController`, prefix
`/api/telephony/webhook/twilio`):

| Endpoint | Opis |
|---|---|
| `POST /voice` | TwiML dla połączenia przychodzącego (XML response) |
| `POST /dtmf` | obsługa wejścia DTMF (`<Gather>`) – zwraca TwiML |
| `POST /` (status callback, form-urlencoded) | status połączenia (ringing/in-progress/completed) |
| `POST /conference` | eventy konferencji (join/leave/end) |
| `POST /recording` | `recordingStatusCallback` → `TwilioRecordingDownloadService` → S3 |
| `POST /voicebot-recording` | nagranie segmentu audio dla voicebota |

**Wzorzec async webhook:** handler zwraca HTTP 204 natychmiast; logika wymagająca
wywołań Twilio REST API (`Conference.fetcher` itp.) wykonywana w `@Async`.
Bezpieczeństwo: weryfikacja `X-Twilio-Signature` przez `RequestValidator`
(`twilio.signature-validation-enabled`, domyślnie `true`, wyłączone w dev).

**Voice Access Token (JS SDK):** `GET /api/telephony/voice-token` –
`TwilioVoiceController` generuje token z `twilio.api-key-sid` /
`twilio.api-key-secret` / `twilio.twiml-app-sid`, z fallbackiem per-tenant
(`TenantTwilioConfig` – BE-059; klucze testowe muszą mieć ≥32 znaków).

**Per-tenant Twilio config (BE-056/057/059):** `TenantTwilioConfigService` –
upsert + maskowanie (UI nie widzi pełnego auth tokenu) + odszyfrowane DTO (tylko
do użytku wewnętrznego, np. `TwilioTelephonyAdapter`) + `TwilioConfigChangedEvent`
(odświeżenie cache adaptera).

**Transfer (BE-075/077):** `TransferController.GET /agents` –
`TransferAgentQueueRepository` (UNION 3 źródeł kolejek, batch query bez N+1).
`AgentCallController.POST /{callId}/transfer` deleguje do
`ContactService.initiateTransfer()` → `TelephonyAdapter.initiateTransfer()`.

### 5.2 WebSocket – kanały i eventy

```
Destinations (broker /topic, /user):
  /topic/tenant/{tenantId}/supervisor   – broadcast KPI/eventy do supervisorów
  /topic/tenant/{tenantId}/agents       – broadcast do agentów tenanta
  /user/{userId}/events                 – unicast (np. PONG, przydział kontaktu)

App destinations (klient → serwer, prefix /app):
  /app/ping                             – heartbeat → odpowiedź PONG na /user/{userId}/events
```

**Relay RabbitMQ → WebSocket** (`domain/websocket/RabbitToWebSocketRelay`,
`WebSocketEventBroadcaster`, `WebSocketEvent`): eventy z `cc.events` (np.
`call.incoming`, `contact.assigned`, `agent.status.changed`,
`QUEUE_WAIT_UPDATE` z `WaitTimeEstimationService`,
`SupervisorMetricsPayload` z `SupervisorMetricsService`) są tłumaczone na
wiadomości STOMP i wysyłane na odpowiednie topiki/kolejki użytkownika.

**Resilience (status ASSIGNED):** gdy przydział kontaktu przez WebSocket nie
dotrze do agenta (np. rozłączenie), `ContactAssignmentMonitor` + status
`ASSIGNED` + `GET /api/agent/me/assigned-contact` pozwalają agentowi odzyskać
przydzielony kontakt po reconnect (V046).

### 5.3 Voicebot (Python/FastAPI, ASR+NLU)

Katalog `voicebot/app/` (FastAPI): `main.py`, `asr.py` (rozpoznawanie mowy –
Whisper), `nlu.py` (intencje), `summarize.py` (podsumowania), `session.py`,
`rabbit.py`.

Backend komunikuje się przez `domain/voicebot/VoicebotClient`
(`@ConditionalOnProperty(voicebot.enabled=true)`, domyślnie **wyłączone**):

```
HTTP POST {voicebot.url}/... (java.net.http.HttpClient, connectTimeout=1s)
  TurnRequest  { session_id, tenant_id, contact_id, audio_base64, audio_format, turn_number }
  TurnResponse { transcript, intent, confidence, escalate, response_text, continue_conversation, ... }

  TranscribeRequest  { audio_base64, audio_format }
  TranscribeResponse { transcript, language, confidence }
```

Naming: SNAKE_CASE (Pydantic) ↔ camelCase (Java) przez `ObjectMapper` z
`PropertyNamingStrategies.SNAKE_CASE`. Przy błędzie/timeout – `Optional.empty()`
+ WARN log → `IvrEngineService` wykonuje graceful degradation (fallback node,
np. przekierowanie do kolejki/agenta). Aktywowane przez `IvrNodeType.VOICEBOT`
w drzewie IVR. Uruchomienie: `docker compose --profile ai up -d`.

---

## 6. Async / messaging (RabbitMQ)

Konfiguracja: `infrastructure/config/RabbitMQConfig.java`.

### 6.1 Exchanges

| Exchange | Typ | Opis |
|---|---|---|
| `cc.events` | topic | eventy domenowe (call, contact, agent, campaign, email) |
| `cc.audit` | topic | eventy audytu |
| `cc.notifications` | topic | notyfikacje |
| `cc.dlx` | direct | dead letter exchange (wszystkie kolejki mają DLQ) |

### 6.2 Kolejki i konsumenci

| Kolejka | Routing key | Konsument | Cel |
|---|---|---|---|
| `cc.queue.call-events` | `call.#` | `RabbitToWebSocketRelay` | broadcast eventów połączeń do WS |
| `cc.queue.contact-routing` | `contact.queued` | `RoutingService` | przydział kontaktu do agenta (TTL 30s) |
| `cc.queue.agent-status` | `agent.status.#` | `RoutingService` | odświeżenie kolejki po zmianie statusu agenta |
| `cc.queue.audit-log` | `audit.#` | `AuditLogConsumer` | zapis do `audit_log` |
| `cc.queue.campaign-dialer` | `campaign.contact.#` | (dialer-related) | eventy wybierania numerów kampanii |
| `cc.queue.notifications` | `#` | – | ogólne notyfikacje |
| `cc.queue.csv-import` | – | – | (rezerwa importu CSV) |
| `cc.queue.dead-letter` | `dlq` | – | DLQ dla wszystkich kolejek |
| `cc.queue.unknown-caller` | `call.unknown_caller` | `CustomerService`/`CliLookupService` | auto-tworzenie profilu klienta (BE-025) |
| `cc.queue.ivr-handler` | `call.incoming` | `IvrCallListener` | przechwycenie połączenia przed routingiem (BE-013) |
| `cc.queue.dialer-hangup` | `call.hangup` | `DialerCallbackHandler` | zakończenie połączenia kampanijnego |
| `cc.queue.email-events` | `email.#` | – | eventy email (received/queued/sent/assigned) |
| `cc.queue.dialer-agent-status` | `agent.status.#` | `ProgressiveDialerService` | trigger wybierania numeru po AVAILABLE |
| `cc.queue.social-incoming` | – (bind w `SocialAdapterRegistry`) | `SocialMessageConsumer` | przychodzące wiadomości social |
| `cc.queue.routing-hangup` | `call.hangup` | `RoutingService` | odświeżenie kolejki po rozłączeniu |
| `cc.queue.agent-direct` | `contact.agent.direct` | `RoutingService` | bezpośredni przydział po BLIND transfer do agenta (TTL 30s) |

**Dlaczego tyle osobnych kolejek dla tego samego eventu (np. `call.hangup`)?**
RabbitMQ przy standardowej kolejce dostarcza wiadomość **tylko jednemu**
konsumentowi (round-robin). Gdy kilka niezależnych serwisów musi otrzymać **każdy**
egzemplarz eventu, każdy serwis dostaje własną kolejkę z bindingiem na ten sam
routing key (np. `call.hangup` → `dialer-hangup` + `routing-hangup` +
`call-events` dla relay WS).

**Konwencja:** każda kolejka ma `x-dead-letter-exchange: cc.dlx`,
`x-dead-letter-routing-key: dlq`. Wiadomości niedostarczone (publisher
returns/mandatory) są logowane jako WARN.

### 6.3 Wzorzec implementacji kolejki

```java
// 1. Stała nazwy w RabbitMQConfig
public static final String QUEUE_XXX = "cc.queue.xxx";

// 2. @Bean Queue + @Bean Binding w RabbitMQConfig
@Bean public Queue xxxQueue() { return QueueBuilder.durable(QUEUE_XXX)...build(); }
@Bean public Binding bindingXxx(Queue xxxQueue, TopicExchange eventsExchange) {...}

// 3. Listener w serwisie domenowym
@RabbitListener(queues = RabbitMQConfig.QUEUE_XXX)
public void onEvent(SomeEvent event) {
    TenantContext.setTenantId(event.tenantId());
    try { ... } finally { TenantContext.clear(); }
}
```

---

## 7. Konfiguracja

Hierarchia: `application.yml` (base) → `application-{dev|prod}.yml` (override).
Profil domyślny: `dev` (`spring.profiles.default`).

### 7.1 Kluczowe sekcje `application.yml`

| Sekcja | Klucz | Domyślna wartość | Opis |
|---|---|---|---|
| JPA | `spring.jpa.hibernate.ddl-auto` | `validate` | Flyway = jedyne źródło schematu |
| JPA | `jakarta.persistence.query.timeout` | `5000` ms | globalny timeout zapytań |
| Flyway | `spring.flyway.locations` | `classpath:db/migration` | |
| Flyway | `spring.flyway.validate-on-migrate` | `true` | |
| Paginacja | `spring.data.web.pageable.max-page-size` | `100` | |
| JWT | `jwt.issuer` | `contact-center` | |
| JWT | `jwt.access-token-ttl-seconds` | `900` (15 min) | |
| JWT | `jwt.refresh-token-ttl-seconds` | `604800` (7 dni) | |
| Telephony | `telephony.provider` | `twilio` | `mock` w dev/testach |
| Telephony | `telephony.webhook.secret` | `dev-secret` | `X-Webhook-Secret` |
| Twilio | `twilio.enabled` | `true` | wymaga `account-sid`, `auth-token`, `phone-number` |
| Twilio | `twilio.signature-validation-enabled` | `true` | wyłączone w dev |
| App | `app.encryption.secret` | (dev: zera, **zmienić w prod**) | AES-256 dla danych szyfrowanych (Twilio creds per tenant) |
| ETL | `etl.sync.fixed-delay-ms` | `60000` | cykl synchronizacji do DW |
| S3 | `s3.endpoint` / `bucket` / `retention-days` | MinIO localhost / `contact-center-recordings` / `90` | nagrania |
| Email | `email.poll-delay-ms` | `60000` | IMAP polling |
| Email | `email.encryption-key` | (dev: zera) | AES-256 hasła IMAP/SMTP |
| Social | `social.token-encryption-key` | dev fallback | AES-256 tokeny OAuth |
| Voicebot | `voicebot.enabled` | `false` | aktywuje `VoicebotClient` |
| Retention | `retention.campaign-archive.enabled` / `retention.campaign-archive-cron` | `false` / `0 0 4 * * *` (UTC) | `CampaignArchiveJob` (sekcja 1.25); włączenie wymaga zgody właściciela |
| Dialer | `dialer.enabled` | `true` | Progressive Dialer + ScheduledCallbackExecutor |
| Dialer | `dialer.callback-executor.interval-ms` | `60000` | |
| Agent breaks | `agent.breaks.activator.enabled` | `true` | |

### 7.2 Sekrety produkcyjne (ENV vars)

`JWT_PRIVATE_KEY_VALUE` / `JWT_PUBLIC_KEY_VALUE`, `TWILIO_ACCOUNT_SID`,
`TWILIO_AUTH_TOKEN`, `TWILIO_API_KEY_SID/SECRET`, `APP_ENCRYPTION_SECRET`,
`EMAIL_ENCRYPTION_KEY`, `SOCIAL_TOKEN_ENCRYPTION_KEY`, `S3_ACCESS_KEY/SECRET_KEY`,
`CORS_ALLOWED_ORIGINS`, `WEBSOCKET_ALLOWED_ORIGINS`. **Nigdy** nie commitować
realnych wartości – domyślne wartości w `application.yml` są bezpieczne tylko
dla `dev`.

---

## 8. Testy backendowe

Lokalizacja: `backend/app/src/test/java/com/contactcenter/` (83 klasy `*Test.java`
+ 1 `*IT.java`).

### 8.1 Struktura

```
api/                – testy kontrolerów (MockMvc / standalone)
  supervisor/, telephony/
domain/
  – jeden pakiet testowy per pakiet domenowy, np.
    agentbreak/, agentgroup/, audit/, campaign/, contact/, customer/,
    disposition/, email/, etl/, gdpr/, ivr/, phonenumber/, queue/,
    recording/, reporting/, repository/, routing/, social/, telephony/,
    tenant/, user/, websocket/
infrastructure/
  persistence/
security/
ContactCenterApplicationIT.java   – smoke test (Spring context + actuator/health)
```

### 8.2 Konwencje

- **Brak H2** – repozytoria (`*Repository extends TenantAwareRepository`) testowane
  przez `Mockito` na `EntityManager` + `ReflectionTestUtils` (nie pełny kontekst
  Spring/JPA). Uwaga przy `thenReturn(List<Object[]>)` – pułapka generics.
- **Testy integracyjne** (`ContactCenterApplicationIT`) – `@SpringBootTest` z
  `webEnvironment = RANDOM_PORT`, profil `test`, Flyway wyłączony
  (`spring.flyway.enabled=false` w `@TestPropertySource`) – obecnie **bez**
  Testcontainers (TODO w kodzie: PostgreSQL+Redis+RabbitMQ przez Testcontainers
  planowane, gdy środowisko CI będzie gotowe).
- **`@Transactional` self-invocation** – serwisy wywołujące własne metody
  `@Transactional` przez `this` nie przechodzą przez proxy Springa; fix:
  `@Autowired @Lazy NazwaSerwisu self` + `self.metoda()`.
- **Mockito + `@InjectMocks` + Lombok `@RequiredArgsConstructor`** – pola
  non-final bywają pomijane przez Mockito 5; fix: ręczne wywołanie settera w
  `@BeforeEach`.
- **`@Nested` + `@BeforeEach`** – pola inicjalizowane w `@BeforeEach` klasy
  zewnętrznej mogą nie być widoczne, gdy Surefire uruchamia klasy `@Nested`
  osobno; rozwiązanie: `@MockitoSettings(Strictness.LENIENT)` + przeniesienie
  `setUp()` do `@Nested`.
- Znany flaky test: `SupervisorMetricsServiceTest$KpiCallsInIvrTests`
  (order-dependent, nie traktować jako regresji).

### 8.3 Uruchamianie

```bash
mvn test -pl app                           # wszystkie testy
mvn test -pl app -Dtest=JwtServiceTest     # jedna klasa
mvn verify -pl app                         # pełna weryfikacja (bez pakowania)
```

---

## Dodatek: szybka mapa "od żądania do bazy danych"

```
HTTP Request (Authorization: Bearer <JWT>)
   │
   ▼
JwtAuthFilter        → SecurityContext (role z claim 'role')
   ▼
TenantFilter         → TenantContext.setTenantId/setUserId/setUserRole + MDC
   ▼
DispatcherServlet → @PreAuthorize (rola) → Controller
   ▼
Service (@Transactional, opcjonalnie @Audited)
   ▼
Repository extends TenantAwareRepository
   │   setTenantContextInDb()  → SELECT set_tenant_context(tenantId)  [per-transakcja]
   │   assertSameTenant(entity.tenantId)
   ▼
EntityManager / JdbcTemplate → PostgreSQL
   │   RLS POLICY: tenant_id = current_setting('app.current_tenant_id')::uuid
   ▼
Response (DTO record) ── lub ── ProblemDetail (GlobalExceptionHandler)
   ▼
finally: TenantContext.clear() + MDC.remove(...)
```
