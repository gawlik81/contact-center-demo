# Backend – dokumentacja techniczna

> Dokument onboardingowy dla warstwy backendowej Contact Center SaaS (`backend/`).
> Stack: **Java 21 + Spring Boot 3.3.x**, **PostgreSQL 15** (Flyway, Row Level Security),
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
domain/          – logika biznesowa: model, repository, service, routing, telephony, ...
infrastructure/  – konfiguracja Spring, AOP, integracje (S3, ETL, social adapters)
security/        – JWT, TenantContext, filtry, MFA
```

Każdy moduł domenowy w `api/<moduł>` zawiera kontroler(y) + pakiet `dto/`. Logika
biznesowa żyje w `domain/service/`, `domain/model/`, `domain/repository/`.

### 1.1 admin

**Cel:** metryki administracyjne (przegląd wszystkich tenantów) oraz status pipeline'u ETL.

| Klasa | Rola |
|---|---|
| `AdminMetricsController` | `/api/admin/metrics` – agregaty po tenantach |
| `EtlStatusController` | `/api/admin/etl` – status i ręczne wywołanie synchronizacji DW |
| `AdminMetricsService` | logika agregacji metryk |

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
| `AgentBreakRepository` | `TenantAwareRepository` |
| `AgentBreakService` | logika CRUD + walidacja kolizji czasowych |
| `AgentCalendarService` | agreguje breaks + `ScheduledCallback` + kampanie |
| `AgentBreakActivator` | `@Scheduled` – automatyczna aktywacja/kończenie przerw |
| `BreakStatus`, `BreakType` | enumy |

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
| `AgentGroupRepository` | CRUD + zarządzanie członkami |
| `AgentGroupService` | logika domenowa |

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
| `AuditLogRepository` | odczyt (tabela partycjonowana po czasie) |
| `AuditLogService` | publikacja eventów do `cc.audit` |
| `AuditLogConsumer` | `@RabbitListener` na `cc.queue.audit-log` – zapis do DB |
| `infrastructure/aspect/AuditAspect` + `@Audited` | AOP – przechwytuje metody serwisów oznaczone `@Audited` |

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
| `AuthService` | logika logowania, refresh, MFA, blacklist |
| `JwtService` / `JwtParser` | wystawianie / parsowanie JWT RS256 |
| `MfaService` | TOTP (RFC 6238) |
| `LoginRateLimiter` | limit prób logowania (Redis) |
| `TokenBlacklistService` | blacklista access tokenów po logout (Redis) |
| `RefreshToken` (entity) | tabela `refresh_token` |

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
| `CampaignRepository`, `CampaignAssignmentRepository`, `CampaignContactRepository` | |
| `CampaignService` | CRUD, walidacja stanów (`DRAFT/RUNNING/PAUSED/STOPPED`) |
| `CampaignAssignmentService` | logika przypisań |
| `CampaignImportService` | async import (Redis job status) |
| `CampaignWindowActivator` | `@Scheduled` – aktywacja kampanii wg okna czasowego |
| `ProgressiveDialerService` | silnik wybierania numerów (patrz sekcja 5) |

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
| `ContactService` | logika domenowa: tworzenie, dyspozycje, akceptacja/abandon, transfery |
| `ContactEventService` | log zdarzeń (timeline) kontaktu |
| `AiSummaryService` / `AiSummaryClient` | generowanie podsumowań AI |

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
| `CustomerService` | CRUD + walidacja |
| `CustomerImportService` | async import (`DeduplicationMode`: SKIP/OVERWRITE) |
| `GdprService` | eksport/anonimizacja |
| `CliLookupService` | rozpoznawanie numeru dzwoniącego (CLI) → klient |

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
| `ScheduledCallbackRepository` | |
| `ProgressiveDialerService` | `@RabbitListener agent.status.changed` – wybiera numer gdy agent staje się AVAILABLE |
| `DialerCallbackHandler` | `@RabbitListener call.hangup` (dedykowana kolejka `cc.queue.dialer-hangup`) |
| `ScheduledCallbackExecutor` | `@Scheduled` – wykonuje zaplanowane oddzwonienia |

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
| `CustomDispositionRepository`, `DispositionSetRepository`, `DispositionSetItemRepository` | |
| `CustomDispositionService`, `DispositionSetService` | logika domenowa |

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
| `EmailAccountConfig`, `EmailEncryptionService` | konfiguracja kont (AES-256-GCM dla haseł) |
| `EmailPollingService` | `@Scheduled` – polling IMAP (`email.poll-delay-ms`, domyślnie 60s) |
| `EmailSendService` | wysyłka SMTP |
| `EmailRoutingService` | przypisanie wiadomości do kolejki/kontaktu wg reguł |
| `EmailContactCreator` | tworzenie rekordu `contact` dla nowej wiadomości |
| `EmailTemplateService`, `MustacheTemplateEngine`, `TemplateVariableResolver`, `PredefinedTemplateVariable` | renderowanie szablonów |
| `EmailEventPublisher` | publikacja eventów `email.#` do `cc.events` |
| `EmailEmlService` | eksport/import `.eml` |

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
| `IvrTreeRepository`, `IvrAudioRepository` | |
| `IvrEngineService` | silnik – interpretacja drzewa, sesje Redis, TTS cache |
| `IvrService` | CRUD + walidacja drzew |
| `IvrCallListener` | `@RabbitListener` na `cc.queue.ivr-handler` (routing key `call.incoming`) |
| `IvrSessionData` | stan sesji w Redis (`ivr:session:{callId}`) |

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
| `PhoneNumberRepository`, `PhoneRoutingRuleRepository` | |
| `PhoneNumberService`, `PhoneRoutingRuleService` | walidacja kolizji harmonogramów |

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
| `Queue` (entity), `QueueAssignmentRepository`, `QueueRepository` | |
| `QueueService`, `QueueAssignmentService` | logika domenowa |
| `WaitTimeEstimationService` | EWT (estimated wait time) – `@Scheduled` co 30s |
| `QueueWaitUpdatePayload` | payload WebSocket `QUEUE_WAIT_UPDATE` |

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
| `RecordingService` | generowanie presigned URL, upload |
| `RecordingRetentionJob` | `@Scheduled` (cron `s3.retention-cron`, domyślnie 02:00 UTC) – usuwa nagrania starsze niż `s3.retention-days` |
| `TwilioRecordingDownloadService` | pobiera nagranie z Twilio i zapisuje do S3 |

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
| `ReportsService` | agregacje SQL |
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
| `SocialIntegrationRepository`, `SocialMessageRepository` | |
| `SocialIntegrationService` | zarządzanie integracjami, `@Scheduled` refresh tokenów co 1h |
| `SocialMessageService` | wysyłka/odbiór |
| `SocialTokenEncryptionService` | AES-256-GCM (BYTEA) dla tokenów OAuth |
| `infrastructure/social/{Facebook,Instagram,WhatsApp}Adapter` + `SocialAdapterRegistry` | adaptery per platforma |
| `domain/social/{IncomingSocialMessage, SocialMediaAdapter, SocialMessageConsumer, SocialMessagePublisher}` | abstrakcje + consumer RabbitMQ |

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
| `TenantAiConfig`, `TenantTwilioConfig` (entity) | konfiguracje per-tenant (szyfrowane klucze) |
| `TenantAiConfigService`, `TenantAiConfigDecrypted` | |
| `TenantTwilioConfigService`, `TenantTwilioConfigDecrypted` | upsert + masking + decrypt + delete + event `TwilioConfigChangedEvent` |
| `SupervisorMetricsService` | agregacja KPI (czas oczekiwania, liczba w IVR, w kolejce...) |

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
| `CallEvent`, `CallSession`, `CallEventEnricher`, `TelephonyEventPublisher` | model eventów + publikacja do RabbitMQ |
| `TransferRequest`, `TransferTargetType` | model żądania transferu |
| `TransferService`, `TransferAgentQueueRepository` | logika transferu (BE-075/077) |
| `IncomingCallRoutingService` | routing połączeń przychodzących (numer → IVR/kolejka) |

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
| `TenantRepository` | |
| `TenantService` | CRUD + walidacja |
| `TenantResourceLimitService` | limity (agenci/kolejki/kampanie) – `get_tenant_limit()` w DB |

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
| `AppUserRepository` | |
| `UserService`, `AdminUserService`, `UserPreferencesService` | |
| `AgentStatusChangedEvent` | event publikowany do `cc.events` (routing key `agent.status.#`) |

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
| `domain/websocket/{WebSocketEvent, WebSocketEventBroadcaster, RabbitToWebSocketRelay}` | model eventu, broadcaster, relay RabbitMQ→WebSocket |

Szczegóły – sekcja 5.

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

Backend komunikuje się przez `domain/service/VoicebotClient`
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
  agentbreak/, agentgroup/, disposition/, email/, repository/, service/, websocket/
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
