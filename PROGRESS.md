# PROGRESS.md
# Contact Center SaaS – Postęp prac

**Ostatnia aktualizacja:** 2026-04-02 (FE-025 Panel Twilio: TwilioConfigService, TwilioSettingsComponent, route + sidenav, BUILD SUCCESS; łączny stan: DB 20/20, BE 27/33, FE 23/25)

---

## Legenda statusów

| Symbol | Status |
|--------|--------|
| ✅ | Ukończone |
| 🔧 | W trakcie / wymaga poprawek |
| ⬜ | Nie rozpoczęte |

---

## TASKS-DATABASE.md

| ID | Nazwa | Status | Uwagi |
|----|-------|--------|-------|
| DB-001 | Inicjalizacja schematu bazowego i konfiguracja Flyway | ✅ | V001-V014 + V999 seed. Naprawiono: NOW() w partial index (V003), timestamptz::DATE w indeksach (V007, V011), invalid UUID 'gggggggg' (V999) |
| DB-002 | Tabela TENANT: schemat, indeksy, constraints | ✅ | Zrealizowane w ramach DB-001 (V002__create_tenant.sql) |
| DB-003 | Tabela USER: schemat, role, ENUM statusów, indeksy | ✅ | Zrealizowane w ramach DB-001 (V003__create_user.sql) |
| DB-004 | Tabela AUDIT_LOG: schemat, partycjonowanie po dacie | ✅ | Zrealizowane w ramach DB-001 (V004__create_audit_log.sql) |
| DB-005 | Kompletny schemat TENANT z limitami i konfiguracją | ✅ | Zrealizowane w ramach DB-001 (V005__tenant_config_schema.sql) |
| DB-006 | Tabela CONTACT i RECORDING: schemat, indeksy, partycjonowanie | ✅ | Zrealizowane w ramach DB-001 (V007__create_contact.sql) |
| DB-007 | Tabele EMAIL i EMAIL_TEMPLATE: schemat | ✅ | Zrealizowane w ramach DB-001 (V010__create_email_social.sql) |
| DB-008 | Tabela SOCIAL_INTEGRATION i SOCIAL_MESSAGE: schemat | ✅ | Zrealizowane w ramach DB-001 (V010__create_email_social.sql) |
| DB-009 | Tabela IVR_TREE: schemat, wersjonowanie | ✅ | Zrealizowane w ramach DB-001 (V008__create_ivr_queue.sql) |
| DB-010 | Tabela QUEUE: schemat, routing strategy, skills | ✅ | Zrealizowane w ramach DB-001 (V008__create_ivr_queue.sql) |
| DB-011 | Tabele CAMPAIGN i CAMPAIGN_CONTACT: schemat, statusy, indeksy | ✅ | Zrealizowane w ramach DB-001 (V009__create_campaign.sql) |
| DB-012 | Tabela CUSTOMER: schemat, fuzzy search, RODO | ✅ | Zrealizowane w ramach DB-001 (V006__create_customer.sql) |
| DB-013 | Indeksy wydajnościowe i widoki raportowe | ✅ | Zrealizowane w ramach DB-001 (V011__performance_indexes_views.sql) |
| DB-014 | Schemat Data Warehouse: ClickHouse tabele docelowe | ✅ | Zrealizowane w ramach DB-001 (dw/migrations/V001__create_contacts_dw.sql) |
| DB-015 | Row Level Security (RLS): polityki izolacji tenant_id | ✅ | Zrealizowane w ramach DB-001 (V012__row_level_security.sql) |
| DB-016 | Konfiguracja Redis: struktury danych i polityki TTL | ✅ | Zrealizowane w ramach DB-001 (backend/src/main/resources/redis-keys.md) |
| DB-017 | Procedury RODO: funkcje anonimizacji i eksportu | ✅ | Zrealizowane w ramach DB-001 (V013__gdpr_functions.sql). Rozszerzone w DB-002 (V017__gdpr_archive_export.sql) |
| DB-018 | Konfiguracja pg_cron: zadania scheduled | ✅ | Zrealizowane w ramach DB-001 (V014__pg_cron_jobs.sql). Rozszerzone w DB-002 (V015__campaign_contact_archive.sql) |
| DB-019 | Seed danych testowych i migracje dla środowiska dev | ✅ | Zrealizowane w ramach DB-001 (V999__dev_seed.sql) |
| DB-020 | Kolumna email_address w tabeli QUEUE: routing emaili | ✅ | V029__add_email_address_to_queue.sql: kolumna email_address VARCHAR(255) NULL, UNIQUE (tenant_id, email_address), CHECK '%@%', partial index WHERE IS NOT NULL. Używana przez EmailRoutingService do routingu priorytetowego. |

### Dodatkowe migracje z DB-002 (ponad zakres TASKS-DATABASE.md)

| Plik | Opis |
|------|------|
| V015__campaign_contact_archive.sql | Tabela archiwum campaign_contact, pełna implementacja archive_completed_campaign_contacts(), purge z harmonogramem |
| V016__contact_referential_integrity.sql | Trigger FK dla partycjonowanych tabel, widoki: v_active_contacts, v_queue_realtime_stats, v_rls_status, v_index_health |
| V017__gdpr_archive_export.sql | Rozszerzona export_customer_data() (obejmuje archiwum), widok v_customer_timeline |

---

## TASKS-BACKEND.md

| ID | Nazwa | Status | Uwagi |
|----|-------|--------|-------|
| BE-001 | Inicjalizacja projektu Spring Boot i struktura modułów | ✅ | Multi-module Maven, profile dev/prod, Flyway, HikariCP, Redis, RabbitMQ |
| BE-002 | Konfiguracja multi-tenancy: TenantContext i filtr tenant_id | ✅ | TenantContext, TenantFilter, JwtParser, JwtProperties, SecurityConfig, TenantAwareRepository, CrossTenantAccessException, CrossTenantAspect, GlobalExceptionHandler. 85 testów PASS |
| BE-003 | Konfiguracja bezpieczeństwa: Spring Security, JWT, MFA | ✅ | JwtService (RS256), JwtAuthFilter, TokenBlacklistService (Redis SHA-256), MfaService (TOTP RFC 6238 ±30s), AppUser/RefreshToken encje, AuthService, AuthController + DTO. 132 testy PASS |
| BE-004 | Auth API: login, logout, refresh, zmiana hasła | ✅ | Rate limiting Redis (5/15min/IP→429), POST /api/auth/change-password (walidacja siły hasła), POST /api/auth/force-reset/{userId} (ADMIN/SUPERVISOR), passwordResetRequired w LoginResponse, LoginRateLimiter.java. Rozszerzono PublicController: POST /api/public/tenants-by-email (email-first flow), AppUserRepository.findActiveTenantsByUserEmail – zawsze HTTP 200, pusta lista zamiast 404 (bez ujawniania istnienia e-maila). |
| BE-005 | Audit Log: zapis działań użytkowników | ✅ | @Audited AOP, AuditAspect (@Around), AuditLogService (RabbitMQ async), AuditLogConsumer (@RabbitListener), AuditLog entity (JPA + native INSERT dla tabeli partycjonowanej), AuditLogRepository, GET /api/audit-logs (ADMIN, paginacja max 100). @Audited dodany do TenantService: CREATED/UPDATED/DEACTIVATED. 189 testów PASS |
| BE-006 | Tenant CRUD API i limity zasobów | ✅ | Tenant.java (encja JPA, JSONB przez @JdbcTypeCode(SqlTypes.JSON)), TenantRepository, TenantService, TenantResourceLimitService (reużywany przez BE-008/020/022), TenantController (6 endpointów), ResourceLimitExceededException (HTTP 422). 27 nowych testów. Łącznie 173 PASS. Naprawiono: JsonMapConverter → @JdbcTypeCode, ENUM types → VARCHAR+CHECK (V019) |
| BE-007 | Admin metrics API: metryki RT tenantów | ✅ | AdminMetricsService (polling Redis session:agent:*, @Cacheable cache 30s), AdminMetricsController (GET /api/admin/metrics, GET /api/admin/metrics/tenants/{id}, @PreAuthorize("hasRole('ADMIN')")), cache Redis TTL 30s dla admin-metrics (Jackson2JsonRedisSerializer<AdminMetricsResponse>). TenantService inwaliduje cache przy deactivateTenant() i updateTenant(). 204 testy PASS |
| BE-008 | User / Agent CRUD API ze skills | ✅ | AppUser.java (dodano firstName, lastName, skills JSONB via @JdbcTypeCode(SqlTypes.JSON), isDeleted, timestamps, @PrePersist/@PreUpdate), AppUserRepository (findAllByTenantIdAndDeletedFalse, findByIdAndTenantIdAndDeletedFalse, findAllDistinctSkillsByTenantId native SQL, existsActiveContactsByUserId bez is_deleted, softDeleteUser, deactivateAllByTenantId bulk UPDATE N+1 fix), UserService (createUser+limitAgentów, listUsers PagedResponse, getUser, updateUser PATCH, deleteUser soft+HTTP409+RabbitMQ+Redis, listSkills, updateStatus+Redis Map session data+RabbitMQ agent.status.changed), UserController (7 endpointów: /skills przed /{id}), ConflictException (HTTP 409), PagedResponse<T> record, InvalidOperationException (HTTP 409), GlobalExceptionHandler zaktualizowany, V020 migracja (safe DO block). CR-BACKEND: clearAutomatically=true, SCAN zamiast KEYS, blacklist TTL z JWT exp, TOTP replay attack Redis, BasicPolymorphicTypeValidator, max-page-size 100, Swagger OFF w prod, findByTenantIdAndEmailAndActiveTrue. Łącznie 222 PASS |
| BE-009 | Adapter VoIP: integracja z SIP trunk / CPaaS API | ✅ | TelephonyAdapter (interfejs), MockTelephonyAdapter, CallEvent, CallSession, TelephonyEventPublisher, TelephonyWebhookController, MockCallController. Wzorzec adaptera z implementacją mock do testów. |
| BE-010 | Nagrywanie rozmów: zapis do S3, metadane, retencja | ✅ | RecordingService (upload do S3/Minio, presigned URL TTL 1h, SSE-S3), RecordingRetentionJob (@Scheduled cron codziennie 02:00, usuwa pliki starsze niż retencja tenanta), RecordingController (GET /api/recordings/{contactId} – SUPERVISOR/ADMIN), S3Config + S3Properties (konfiguracja Minio/AWS), migracja V022 (indeks retencji). |
| BE-011 | CLI lookup: wzbogacenie połączenia o dane klienta | ✅ | Customer.java (entity, JSONB phone[] via @JdbcTypeCode), CustomerRepository (findByPhoneNumber JSONB @> operator + GIN index, findLastContactsForCustomer native SQL na partycjonowanej tabeli), CustomerCliResult (record DTO + ContactSummary), CliLookupService (Redis cache TTL 5min, null sentinel anti-stampede, fallback do DB, invalidateCacheForCustomer), CallEvent rozszerzony o pole customerInfo, CallEventEnricher (@RabbitListener call.incoming, dedykowana kolejka cc.queue.cli-enricher, unicast przez WebSocketEventBroadcaster), WebSocketEvent.CallIncomingPayload rozszerzony o customerId + lastContacts. Naprawiono pre-istniejące błędy: AuthServiceChangePasswordTest (kolejność argumentów konstruktora) i UserServiceTest.listUsers (sygnatura metody). 299 testów PASS |
| BE-012 | WebSocket hub: real-time events do Agent Desktop | ✅ | WebSocketConfig (STOMP), WebSocketAuthInterceptor (JWT przy handshake), WebSocketController, RabbitToWebSocketRelay, WebSocketEventBroadcaster, StompPrincipal. Topics per user i per tenant. |
| BE-013 | IVR Engine: wykonanie drzewa IVR | ✅ | IvrController (7 endpointów CRUD + activate + DTMF simulate), IvrService, IvrEngineService, IvrCallListener, IvrDefinition/Node/NodeType/Option/SessionData w domain/ivr |
| BE-014 | Voicebot Python: ASR + NLU + eskalacja do agenta | ⬜ | |
| BE-015 | Email Adapter: IMAP polling + SMTP wysyłka | ✅ | EmailPollingService (IMAP @Scheduled), EmailSendService (SMTP), EmailRoutingService, EmailEncryptionService (AES-256), EmailController (5 endpointów), EmailMessage/EmailRoutingRule repozytoria, EmailEventPublisher (RabbitMQ) |
| BE-016 | Szablony odpowiedzi email: CRUD API | ✅ | EmailTemplateController (6 endpointów CRUD + preview), EmailTemplateService, EmailTemplate entity, EmailTemplateRepository, MustacheTemplateEngine (renderowanie zmiennych {{}}), DTOs: CreateEmailTemplateRequest/UpdateEmailTemplateRequest/EmailTemplateResponse |
| BE-017 | OAuth flow i zarządzanie tokenami social media | ⬜ | |
| BE-018 | Social Media Adapter: odbieranie i wysyłka wiadomości | ⬜ | |
| BE-019 | Routing Engine: skill-based, round-robin, sticky agent | ✅ | RoutingEngine (interfejs), DefaultRoutingEngine (skill-based, round-robin, sticky agent), RoutingService, AgentSessionData, RoutingRequest/Result, ContactQueuedMessage, ContactAssignedEvent. |
| BE-020 | Queue API: CRUD kolejek i konfiguracja routingu | ✅ | QueueController (POST/GET/PATCH/DELETE /api/queues, GET /api/queues/{id}/stats), DTOs: CreateQueueRequest, UpdateQueueRequest, QueueResponse. Routing strategy enum: ROUND_ROBIN/FIRST_AVAILABLE/SKILL_BASED. |
| BE-021 | Wait time estimation: informacja o czasie oczekiwania | ✅ | WaitTimeEstimationService (@Scheduled fixedDelay=30s), QueueWaitUpdatePayload (DTO eventu QUEUE_WAIT_UPDATE), QueueStatsResponse (z avgHandleTimeSeconds), ContactRepository +2 native SQL (countWaitingByQueueId, getAvgHandleTimeSeconds fallback 300s), QueueController GET /api/queues/{id}/stats. EWT = ceil(waiting/agents*avg), edge cases: waiting=0→0, agents=0→MAX_VALUE. Poprawki CR: B1 AND is_deleted=false dodane do obu zapytań SQL, B2 partial entity usunięte z kontrolera (serwis ładuje encję sam przez getQueueStats(tenantId, queueId)), B3 ConcurrentHashMap zamiast Redis SCAN per HTTP. 644 testów PASS. |
| BE-022 | Campaign CRUD API i harmonogram | ✅ | Campaign.java, CampaignRepository, CampaignService, CampaignController + DTOs, V026 migracja. Odblokowuje BE-023, FE-015 |
| BE-023 | Import CSV kontaktów kampanii (async job) | ✅ | CampaignImportController (POST import + GET status), CampaignImportService (@Async, OpenCSV, batch JdbcTemplate chunk 1000, deduplikacja ON CONFLICT), Redis TTL 1h dla statusu joba, V027 unikalny indeks (campaign_id, phone). 25 nowych testów, 467 PASS |
| BE-024 | Progressive Dialer: silnik automatycznego dzwonienia | ⬜ | |
| BE-025 | Customer CRUD API i fuzzy search | ✅ | CustomerController (POST/GET/GET{id}/PATCH/DELETE /api/customers, PagedResponse, fuzzy search ILIKE + word_similarity), CustomerService (CRUD + soft-delete anonimizacja RODO + invalidateCacheForCustomer), CustomerRepository (searchCustomers natywny SQL, findById), Customer entity (JSONB phone[], email[], custom_fields, gdpr_consent), migracje V023 (funkcja set_tenant_context) + V024 (fix prefix search ILIKE). |
| BE-026 | Import klientów z CSV (async job) | ✅ | Zrealizowane 2026-03-24. CustomerImportController (POST /api/customers/import 202+jobId, GET status polling, GET errors CSV blob), CustomerImportService (@Async, OpenCSV, batch chunk 500, deduplikacja SKIP/OVERWRITE, walidacja E.164, Redis TTL 1h), CustomerRepository (findByEmail JSONB @>). 24 testy, 506 PASS |
| BE-027 | Contact API: zapis i odczyt historii kontaktów | ✅ | ContactController (6 endp.), ContactService (CRUD + uprawnienia AGENT/SUPERVISOR/ADMIN), ContactRepository (native INSERT/UPDATE, partycjonowana tabela), ContactId.java, DTOs: ContactResponse/CreateContactRequest/UpdateContactRequest/DispositionRequest/ContactFilterParams. 22 testy PASS. |
| BE-028 | Raporty historyczne: agregacje per agent i kampania | ✅ | AgentReportRow/AgentReportParams DTOs (Bean Validation), ContactRepository +2 native SQL GROUP BY, ReportsService (Redis cache MD5 5min, walidacja 90 dni, CSV + XLSX Apache POI 5.2.5), ReportsController (4 endpointy: /api/reports/agents, /agents/export, /agents/export/xlsx, /campaigns 501), 13 testów, 442 PASS |
| BE-029 | RT Metrics API: WebSocket feed dla supervisora | ✅ | SupervisorMetricsPayload (rekord DTO), SupervisorMetricsService (@Scheduled fixedRate=5000, Redis SCAN cursor-based, broadcast /topic/tenant/{tenantId}/supervisor, eventType="SUPERVISOR_METRICS", izolacja cross-tenant, graceful degradation), 15 testów jednostkowych, 429 testów PASS |
| BE-030 | ETL do data warehouse: CDC z PostgreSQL | ⬜ | |
| BE-031 | RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17) | ⬜ | |
| BE-032 | Twilio: konfiguracja numeru telefonu per tenant | ✅ | Tenant.getTwilioPhoneNumber/getTwilioStatusCallbackUrl (JSONB), TenantTwilioConfigRequest (walidacja E.164), TenantService.updateTwilioConfig (@Audited), PATCH /api/tenants/{id}/config (ADMIN), TwilioTelephonyAdapter.resolvePhoneNumber (per-tenant > global fallback), buildStatusCallbackUrl(tenantId). 6 nowych testów resolvePhoneNumber. BUILD SUCCESS. |

---

## TASKS-FRONTEND.md

| ID | Nazwa | Status | Uwagi |
|----|-------|--------|-------|
| FE-001 | Inicjalizacja projektu Angular i konfiguracja workspace | ✅ | Angular 21.2.x, standalone components, SCSS, Vitest. ESLint (angular-eslint) + Prettier + Husky/lint-staged. Struktura: core/, shared/, features/, environments/. Proxy /api/* → localhost:8080, WebSocket /ws. ng build i ng test PASS. |
| FE-002 | Konfiguracja routingu, lazy loading i guard AuthGuard | ✅ | Standalone functional guards (AuthGuard, RoleGuard, RoleRedirectGuard), lazy loading 12 chunków, TokenService (localStorage/sessionStorage), AuthService (sygnały), authInterceptor z silent refresh kolejkującym żądania, routing dla /auth/**, /admin/**, /supervisor/**, /agent/** |
| FE-003 | HTTP Interceptor: JWT, refresh token, obsługa błędów 401/403 | ✅ | errorHandlerInterceptor (403→toast "Brak uprawnień", 5xx→toast "Błąd serwera", status 0→"Brak połączenia"), NotificationService (signal-based, auto-dismiss 4-6s), ToastContainerComponent (WCAG AA, aria-live), oba interceptory zarejestrowane w app.config.ts |
| FE-004 | Moduł uwierzytelniania: ekran logowania i MFA | ✅ | LoginComponent przepisany na flow 3-krokowy "email-first": krok 1 email → POST /api/public/tenants-by-email → krok 2 hasło + opcjonalny dropdown organizacji (gdy >1 trafień) → krok 3 MFA TOTP (6 cyfr). Sygnały Angular: step, matchedTenants, loading, errorMessage. ChangePasswordComponent (cross-field validator, wskaźnik siły hasła), AUTH_ROUTES. Backend: POST /api/public/tenants-by-email w PublicController + findActiveTenantsByUserEmail w AppUserRepository. |
| FE-005 | Shell aplikacji: top navbar, sidenav, breadcrumbs, notyfikacje | ✅ | AppShellComponent (CSS Grid/Flex, skip-link WCAG), TopNavbarComponent (hamburger, badge roli, logout, tenant info), SidenavComponent (menu kontekstowe per rola ADMIN/SUPERVISOR/AGENT, SVG ikony inline, responsive: overlay mobile/tablet, sticky desktop 1280px+), BreadcrumbsComponent + BreadcrumbService (Router.events, data.breadcrumb, aria-current), admin/supervisor/agent shell i routes zaktualizowane. ng build PASS. |
| FE-006 | Lista tenantów i formularz tworzenia tenanta | ✅ | TenantListComponent (tabela z paginacją 20/str, filtry nazwa+status z debounce 300ms, skeleton loading, empty state, badge statusów ACTIVE/INACTIVE/SUSPENDED, przycisk dezaktywacji per wiersz), TenantFormComponent (reactive form, async validator unikalności nazwy debounce 500ms, limity agentów/kolejek/kampanii), TenantDeactivateModalComponent (natywny <dialog>, WCAG AA), TenantService (6 metod API), TENANT_ROUTES lazy-loaded, admin.routes.ts zaktualizowane. ng build PASS. Naprawiono: TenantService kontrakt (List nie PagedResponse), CreateTenantRequest pole config → limits |
| FE-007 | Dashboard techniczny administratora (metryki tenantów RT) | ✅ | AdminMetricsService (singleton state z BehaviorSubject, polling co 30s przez timer(0,30000), alertCount$), AdminDashboardComponent (KPI cards: aktywne tenanty/agenci online/alerty, tabela tenantów z badge statusami i progress bar, skeleton loading, empty state, timestamp odświeżania). Badge alertów w SidenavComponent (podpięty pod alertCount$, widoczny tylko na /admin/dashboard). Placeholder komponenty: AdminUsersComponent (/admin/users) i AdminMetricsPageComponent (/admin/metrics) |
| FE-008 | Zarządzanie agentami: lista, tworzenie, edycja, skills | ✅ | AgentListComponent (tabela z paginacją PagedResponse, filtry status+skill, multi-select skills chips, force-reset hasła, deactivate z HTTP 409 guard), AgentFormComponent (reactive form, skills autocomplete), AgentService (CRUD + skills API). Czeka na BE-008 ✅ |
| FE-009 | Agent Desktop: główny layout i panel statusu agenta | ✅ | AgentDesktopComponent (layout, panel statusu agenta, zakładki kontaktów max 4, integracja WebSocket, baner reconnect). |
| FE-010 | Komponent Softphone WebRTC | ✅ | Zrealizowane 2026-03-18. SIP.js/JsSIP WebRTC, odbieranie/rozłączanie połączeń, mute, hold, blind i attended transfer, wyświetlanie CLI. Wymaga FE-009 ✅, BE-009 ✅, BE-012 ✅ |
| FE-011 | Panel profilu klienta podczas kontaktu | ✅ | Panel boczny w AgentDesktopComponent: dane klienta z CLI lookup, historia ostatnich kontaktów, CTA "Utwórz profil" dla nieznanych numerów. Integracja z BE-025 ✅ i BE-011 ✅. |
| FE-012 | Komponent obsługi kontaktu email | ✅ | EmailContactComponent (wątek email z paginacją load-more, edytor odpowiedzi, wybór szablonu autocomplete, signal-based, ChangeDetectionStrategy.OnPush), EmailThreadMessageComponent, EmailService (agent), EmailSettingsComponent (konfiguracja IMAP/SMTP + test połączenia dla supervisora w /supervisor/settings), EmailConfigService. Integracja z AgentDesktopComponent i customer-panel. Zrealizowane 2026-03-26. |
| FE-013 | Komponent obsługi kontaktu social media | ⬜ | |
| FE-014 | Graficzny edytor drzewa IVR (drag & drop) | ✅ | IvrListComponent (lista drzew IVR), IvrEditorComponent (edytor drag & drop SVG canvas: węzły PlayAudio/TTS/CollectDTMF/Menu/TransferToQueue/Hangup, krawędzie SVG path, panel boczny konfiguracji węzła, zapis JSONB). IvrService (frontend), ivr.model.ts. |
| FE-015 | Zarządzanie kampaniami: lista i formularz tworzenia | ✅ | campaign.model.ts, campaign.service.ts, campaign-list.component (ts/html/scss), campaign-form.component (ts/html/scss), supervisor.routes.ts zaktualizowany. Odblokowuje FE-016. |
| FE-016 | Import listy kontaktów CSV do kampanii | ✅ | CampaignImportComponent: 4-krokowy wizard (upload drag&drop → mapowanie kolumn → progress bar polling 3s → raport), walidacja client-side 50MB, auto-mapowanie kolumn, integracja z campaign-list (przycisk dla DRAFT/SCHEDULED). |
| FE-017 | Panel disposition codes po zakończeniu kontaktu | ✅ | DispositionPanelComponent (modal ACW z timerem MM:SS, dropdown 6 kodów, textarea notatka), ContactService (setDisposition → PATCH /api/contacts/{id}/disposition), contact-tab.store.ts (stan WRAPPING + markAsWrapping()), effect() na session.state=ENDED w agent-desktop. |
| FE-018 | Wyszukiwanie i lista klientów (fuzzy search) | ✅ | CustomerListComponent (tabela z paginacją PagedResponse, wyszukiwanie debounce 300ms, skeleton loading, empty state), CustomerDeleteModalComponent (modal RODO z potwierdzeniem anonimizacji), CustomerService (frontend, 5 metod API), supervisor.routes.ts zaktualizowany. Czeka na BE-025 ✅. |
| FE-019 | Profil klienta: widok szczegółowy i historia kontaktów | ✅ | CustomerDetailComponent (dane podstawowe, multi-value telefon/email chips, custom_fields, oś czasu historii kontaktów z kanałem/agentem/disposition, badge RODO), integracja z BE-025 ✅ i BE-027 ✅. |
| FE-020 | Import klientów z CSV | ✅ | Zrealizowane 2026-03-24. customer-import.component (4-krokowy wizard: upload drag&drop + deduplikacja radio, mapowanie kolumn z auto-mapowaniem, progress bar polling 3s, raport z pobieraniem błędów CSV), customer-import.model.ts, customer.service.ts rozszerzony. Czeka na BE: BE-026 ✅ |
| FE-021 | Dashboard RT supervisora | ✅ | Dashboard RT supervisora: KPI cards (aktywne połączenia, agenci online/przerwa/dostępni), tabela agentów z aktualnym statusem, wykres kolejek; WebSocket STOMP /topic/tenant/{tenantId}/supervisor, dane co 5s; tryb pełnoekranowy |
| FE-022 | Raporty historyczne: filtry, tabele, eksport | ✅ | report.model.ts (AgentReportRow, AgentReportFilters), reports.service.ts (getAgentReport, exportCsv, exportXlsx blob), ReportsComponent (filtry URL sync, tabela badge'ami kanałów, paginacja, eksport Blob, skeleton, empty state), supervisor.routes.ts /reports z roleGuard, build 0 błędów |
| FE-023 | Panel konfiguracji integracji social media (OAuth flow) | ⬜ | |
| FE-024 | Panel konfiguracji kolejek i routingu | ✅ | QueueListComponent (tabela kolejek z liczbą oczekujących, polling co 10s), QueueFormComponent (formularz tworzenia/edycji: nazwa, strategia routingu, required skills multi-select, sticky agent timeout, adres email kolejki emailAddress z walidacją email + maxLength(255)), QueueDeleteModalComponent. Integracja z BE-020 ✅ + DB-020 ✅. |
| FE-025 | Panel konfiguracji Twilio per tenant | ✅ | TwilioConfigService (GET tenant + PATCH /api/tenants/{id}/config), TwilioSettingsComponent (formularz E.164, badge per-tenant/fallback, podgląd auto URL, usunięcie konfiguracji), route /supervisor/settings/twilio, wpis "Twilio VoIP" w sidenavie. BUILD SUCCESS. |

---

## Podsumowanie

| Obszar | Ukończone | W trakcie | Nie rozpoczęte | Razem |
|--------|-----------|-----------|----------------|-------|
| Database (DB) | 20/20 | 0 | 0 | 20 |
| Backend (BE) | 27/33 | 0 | 6 | 33 |
| Frontend (FE) | 23/25 | 0 | 2 | 25 |
| **RAZEM** | **70/78** | **0** | **8** | **78** |

---

## Znane problemy i naprawione błędy

| Data | Plik | Błąd | Rozwiązanie |
|------|------|------|-------------|
| 2026-03-13 | V003__create_user.sql L172 | SQL 42P17: NOW() w partial index (STABLE, nie IMMUTABLE) | Usunięto WHERE predicate z idx_refresh_token_cleanup |
| 2026-03-13 | V007__create_contact.sql L175 | SQL 42P17: (started_at::DATE) w indeksie (STABLE) | Zmieniono na started_at bez cast |
| 2026-03-13 | V011__performance_indexes_views.sql L116,L126 | SQL 42P17: (started_at::DATE) w indeksach | Zmieniono na started_at bez cast |
| 2026-03-13 | V999__dev_seed.sql L354 | SQL 22P02: invalid UUID 'gggggggg-...' | Zmieniono na '22222222-...' |
| 2026-03-13 | application-dev.yml | Flyway: resolved migrations not applied (V015-V017) | Dodano clean-on-validation-error: true |
| 2026-03-13 | application-dev.yml | Flyway: cleanDisabled blokuje clean() | Dodano clean-disabled: false |
| 2026-03-13 | V016__contact_referential_integrity.sql L255 | SQL 42703: column "tablename" does not exist | Zmieniono tablename→relname, indexname→indexrelname (pg_stat_user_indexes) |
| 2026-03-13 | proxy.conf.json | pathRewrite usuwał prefiks /api → backend dostawał /auth/login zamiast /api/auth/login | Usunięto blok pathRewrite, proxy przekazuje ścieżki 1:1 |
| 2026-03-13 | V999__dev_seed.sql | Hash BCrypt nie pasował do hasła Test@12345 (wszystkie 8 kont) | Wygenerowano nowy poprawny hash $2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6 |
| 2026-03-13 | app.html | Domyślny scaffold Angular zasłaniał router-outlet | Zastąpiono zawartością: &lt;cc-toast-container /&gt; + &lt;router-outlet /&gt; |
| 2026-03-14 | features/auth/login/login.component.ts | Po logowaniu bez MFA wyświetlał się ForbiddenComponent – `handleLoginResponse()` w ścieżce "direct login" nie wywoływała `handleLoginSuccess()`, token nie był zapisywany, `getUserRole()` zwracało null, `navigateToDashboard()` kierowało na /forbidden | Dodano wywołanie `authService.handleLoginSuccess({ accessToken, refreshToken })` przed `navigateToDashboard()` w ścieżce direct login |
| 2026-03-14 | core/services/auth.service.ts | Interfejs `LoginResponse` nie zawierał pola `refreshToken` – backend zwracający refreshToken przy bezpośrednim logowaniu był ignorowany | Dodano `refreshToken?: string` do interfejsu `LoginResponse` |
| 2026-03-14 | domain/model/Tenant.java | `JsonMapConverter` (AttributeConverter) powodował konflikty z Hibernate Envers i nie obsługiwał null-safe JSONB | Zastąpiony adnotacją `@JdbcTypeCode(SqlTypes.JSON)` bezpośrednio na polach JSONB |
| 2026-03-14 | V019__convert_enum_types_to_varchar.sql | PostgreSQL custom ENUM types (`tenant_status`, `user_role`, `user_status`) powodowały błędy przy Flyway clean-on-validation (typy pozostawały w schemacie) | Nowa migracja V019: drop ENUM, zmiana kolumn na VARCHAR + CHECK constraint |
| 2026-03-14 | features/admin/tenants/tenant.service.ts | Backend zwraca `List<Tenant>` (tablica JSON), frontend oczekiwał `PagedResponse<Tenant>` z polem `content` | Zaktualizowano TenantService – odpowiedź mapowana bezpośrednio jako `Tenant[]` |
| 2026-03-14 | features/admin/tenants/tenant-form.component.ts | `CreateTenantRequest` zawierał pole `config` zamiast `limits` – backend odrzucał żądanie 400 | Zmieniono pole na `limits` zgodnie z kontraktem TenantController |
| 2026-03-17 | TenantRepository.java | lower(bytea) – Hibernate 6 wiąże parametr :name jako bytea gdy jest użyty w :name IS NULL (brak kontekstu kolumny) | Zmieniono na nativeQuery=true z CAST(:name AS TEXT) |
| 2026-03-17 | AuditLog.java | Schema validation: wrong column type [ip_address] found inet (Types#OTHER), expecting varchar(45) | Zmieniono @Column(length=45) na @Column(columnDefinition="inet") |
| 2026-03-17 | RedisConfig.java | SerializationException: missing type id @class – AdminMetricsResponse jest Java record (final class), GenericJackson2JsonRedisSerializer pomija @class dla typów final | Dla cache admin-metrics użyto Jackson2JsonRedisSerializer<AdminMetricsResponse> (statycznie typowany) |
| 2026-03-17 | admin.routes.ts | RT metrics wyświetlały się na zakładce Użytkownicy – trasy /admin/users i /admin/metrics ładowały AdminDashboardComponent jako placeholder | Utworzono AdminUsersComponent i AdminMetricsPageComponent jako właściwe placeholdery |
| 2026-03-17 | AppUser.java | SUPERVISOR dostawał HTTP 403 na GET /api/users – Hibernate 6 dostarcza JSONB jako PGobject, AttributeConverter<List,String> odbierał PGobject zamiast String → ClassCastException połknięty przez JwtAuthFilter catch(Exception) → SecurityContext nie ustawiony | Zmieniono @Convert(JsonStringListConverter) na @JdbcTypeCode(SqlTypes.JSON) dla pola skills |
| 2026-03-17 | AppUserRepository.java | existsActiveContactsByUserId zawierał AND is_deleted = FALSE – tabela contact (partycjonowana, V007) nie ma kolumny is_deleted, soft delete realizowany przez statusy QUEUED/ACTIVE/ON_HOLD | Usunięto warunek is_deleted z zapytania natywnego |
| 2026-03-17 | AdminMetricsService.ts | SUPERVISOR/AGENT generował flood 403 co 30s – AdminMetricsService (providedIn: 'root') startował timer polling bezwarunkowo w konstruktorze, SidenavComponent wstrzykuje serwis dla wszystkich ról | Dodano guard if (this.auth.getUserRole() === 'ADMIN') w konstruktorze przed wywołaniem this._poll$.subscribe() |
| 2026-03-17 | CR-BACKEND.md (20 issues) | Code review zidentyfikował: N+1 w deactivateTenant, blacklist TTL z runtime zamiast JWT exp, brak clearAutomatically=true, Redis KEYS() blokujący, TOTP replay attack, RLS Javadoc, PagedResponse brak metadanych, AuditAspect bez @Transactional, virtual thread ThreadLocal risk, Redis LaissezFaireSubTypeValidator RCE, brak max-page-size, MFA refresh vulnerability, SIOBE w TenantFilter, passwordHash comment, countOnlineAgents=0, AuditLogConsumer ack, circular dep comment, IllegalStateException→HTTP500, deleted users login, Swagger w prod | Wdrożono wszystkie 20 poprawek: bulk UPDATE (N+1), JwtClaims.expiresAt, clearAutomatically, SCAN cursor, Redis used-code TTL, BasicPolymorphicTypeValidator, PagedResponse<T>, InvalidOperationException, MfaService.verifyCode(userId), UserDetailsServiceImpl findByActiveTrue, springdoc OFF w prod, TenantContext Javadoc virtual thread warning. Naprawiono MfaService @RequiredArgsConstructor conflict i TenantService brakujący import List. 222 testy PASS |
| 2026-03-18 | login.component.ts + public-tenant.service.ts | Flow logowania był dwustanowy (credentials→MFA); brak wykrywania organizacji po e-mailu wymuszał na użytkowniku ręczny wybór tenanta z pełnej listy | Przepisano LoginComponent na flow 3-krokowy "email-first": krok email → POST /api/public/tenants-by-email (nowy endpoint) → krok hasło z opcjonalnym dropdownem org (gdy >1 trafień) → krok MFA. Backend: PublicController.findTenantsByEmail + AppUserRepository.findActiveTenantsByUserEmail (native query). Bezpieczeństwo: zawsze HTTP 200 z pustą listą – nie ujawnia istnienia e-maila. |
| 2026-03-19 | V023__create_set_tenant_context_function.sql | Brak funkcji PostgreSQL `set_tenant_context(uuid)` wywoływanej przez TenantAwareRepository – CustomerRepository rzucał błąd przy pierwszym zapytaniu | Dodano funkcję `set_tenant_context(p_tenant_id UUID)` wykonującą `SET LOCAL app.current_tenant_id` jako samodzielna migracja V023. |
| 2026-03-19 | V024__fix_search_customers_prefix_search.sql + CustomerRepository | Fuzzy search po prefiksie (np. "Kow") nie znajdował rekordów – `word_similarity` zbyt rygorystyczna dla krótkich fraz | Zmieniono `search_customers` na ILIKE `%query%` jako fallback oraz `word_similarity` ≥ 0.2; naprawiony w V024. |
| 2026-03-19 | CustomerController.java | Odpowiedź `GET /api/customers` zwracała `List<Customer>` zamiast `PagedResponse<CustomerResponse>` – niezgodność z kontraktem FE | Ujednolicono do `PagedResponse<CustomerResponse>` spójnie z innymi kontrolerami. |
| 2026-03-22 | ReportsService.java + ContactRepository.java | `ReportsServiceTest` – 6 błędów kompilacji: `u.id` zamiast `u.user_id` w native SQL GROUP BY JOIN na tabeli `app_user`; `StringRedisTemplate` wstrzykiwany zamiast `RedisTemplate<String, String>` (niezgodność typów przy serializacji klucza cache MD5) | Poprawiono alias kolumny na `u.user_id` w zapytaniach natywnych `ContactRepository`; zmieniono typ pola na `StringRedisTemplate` w `ReportsService`. 442 testy PASS. |
| 2026-03-22 | supervisor-dashboard.component.ts | Czas przerwy agenta wyświetlał się jako `undefined` (zamiast HH:MM od startu przerwy) – frontend obliczał czas lokalnie, ale backend nie wysyłał pola `breakStartedAt` | Dodano pole `breakStartedAt` do `SupervisorMetricsPayload` (BE) i obsługę w komponencie (FE). |
| 2026-03-22 | supervisor-dashboard.component.ts | Badge statusu OFFLINE nie był wyświetlany (brak case w ngSwitch) – agenci OFFLINE byli widoczni bez etykiety statusu | Dodano case 'OFFLINE' do przełącznika statusów w szablonie. |

---

## Mapa procesów i kolejność realizacji zadań

**Stan na:** DB: 20/20 ✅ | BE: 26/33 (BE-001 ✅, BE-001b ✅, BE-002 ✅, BE-003 ✅, BE-004 ✅, BE-005 ✅, BE-006 ✅, BE-007 ✅, BE-008 ✅, BE-009 ✅, BE-010 ✅, BE-011 ✅, BE-012 ✅, BE-013 ✅, BE-015 ✅, BE-016 ✅, BE-019 ✅, BE-020 ✅, BE-021 ✅, BE-022 ✅, BE-023 ✅, BE-025 ✅, BE-026 ✅, BE-027 ✅, BE-028 ✅, BE-029 ✅) | FE: 22/25 (FE-001..FE-012 ✅, FE-014 ✅, FE-015 ✅, FE-016 ✅, FE-017 ✅, FE-018 ✅, FE-019 ✅, FE-020 ✅, FE-021 ✅, FE-022 ✅, FE-024 ✅)

---

## 1. Status aktualny

### Warstwa Database – w pełni ukończona

| Zakres | Zadania | Status |
|--------|---------|--------|
| Schemat bazowy, Flyway | DB-001 | ✅ |
| Tabele TENANT, USER, AUDIT_LOG | DB-002..DB-005 | ✅ |
| Tabele CONTACT, RECORDING, CUSTOMER | DB-006, DB-012 | ✅ |
| Tabele EMAIL, SOCIAL, IVR, QUEUE | DB-007..DB-010 | ✅ |
| Tabele CAMPAIGN, CAMPAIGN_CONTACT | DB-011 | ✅ |
| Indeksy, RLS, RODO, pg_cron, DW | DB-013..DB-019 | ✅ |
| Kolumna email_address w QUEUE (V029) | DB-020 | ✅ |

Cała warstwa DB jest gotowa. Wszystkie schematy, RLS, indeksy trigram (pg_trgm), funkcje RODO i schemat ClickHouse DW są zaimplementowane. BE i FE mogą budować na stabilnej bazie.

### Warstwa Backend – fundament gotowy, funkcje do realizacji

| ID | Nazwa | Status |
|----|-------|--------|
| BE-001 | Inicjalizacja Spring Boot, Flyway, Redis, RabbitMQ | ✅ |
| BE-002 | Multi-tenancy: TenantContext, TenantFilter, RLS | ✅ |
| BE-003 | Spring Security, JWT RS256, MFA TOTP, Blacklista Redis | ✅ |
| BE-004 | Auth API: rate limiting, change-password, force-reset; POST /api/public/tenants-by-email (email-first) | ✅ |
| BE-005 | Audit Log: @Audited AOP, AuditAspect, AuditLogService (RabbitMQ async), AuditLogConsumer, AuditLog entity (native INSERT, partycjonowanie), GET /api/audit-logs (ADMIN) | ✅ |
| BE-006 | Tenant CRUD API i limity zasobów | ✅ |
| BE-007 | Admin metrics API: AdminMetricsService (Redis polling), AdminMetricsController (GET /api/admin/metrics, GET /api/admin/metrics/tenants/{id}), cache Redis TTL 30s | ✅ |
| BE-008 | User / Agent CRUD API ze skills: AppUser JSONB (@JdbcTypeCode), UserService (listUsers PagedResponse, deleteUser soft+HTTP409, updateStatus+Redis Map session), UserController, ConflictException, InvalidOperationException, PagedResponse<T>, V020 migracja. CR-BACKEND fixes applied. 222 testy PASS | ✅ |
| BE-009 | Adapter VoIP: TelephonyAdapter (interfejs), MockTelephonyAdapter (implementacja mock), CallEvent, CallSession, TelephonyEventPublisher (RabbitMQ), TelephonyWebhookController, MockCallController. Wzorzec adaptera gotowy do podmiany na producencki CPaaS. | ✅ |
| BE-010 | Nagrywanie rozmów: RecordingService (S3 upload, presigned URL TTL 1h, SSE-S3), RecordingRetentionJob (cron 02:00), RecordingController (GET /api/recordings/{contactId}), S3Config/S3Properties, migracja V022. | ✅ |
| BE-011 | CLI lookup: CliLookupService (Redis TTL 5min, null sentinel), CallEventEnricher (@RabbitListener call.incoming), CustomerCliResult (record DTO + ContactSummary). Integracja z BE-025 (CustomerRepository). | ✅ |
| BE-012 | WebSocket hub: WebSocketConfig (Spring STOMP, endpointy /ws), WebSocketAuthInterceptor (JWT przy handshake → HTTP 401 bez tokenu), WebSocketController, RabbitToWebSocketRelay (RabbitMQ → STOMP push), WebSocketEventBroadcaster, StompPrincipal. Topics: /user/{userId}/events, /tenant/{tenantId}/supervisor. | ✅ |
| BE-025 | Customer CRUD API: CustomerController (5 endpointów, PagedResponse), CustomerService (CRUD + RODO soft-delete anonimizacja), CustomerRepository (fuzzy search ILIKE + word_similarity), Customer entity (JSONB phone[], email[]), migracje V023 + V024. | ✅ |
| BE-027 | Contact API: ContactController (6 endpointów: GET/POST/PATCH /api/contacts, PATCH /disposition, GET /customer/{customerId}), ContactService (CRUD + logika uprawnień AGENT vs SUPERVISOR/ADMIN), ContactRepository (native INSERT/UPDATE dla partycjonowanej tabeli, dynamiczny WHERE dla filtrów), ContactId.java, DTOs: ContactResponse/CreateContactRequest/UpdateContactRequest/DispositionRequest/ContactFilterParams. 22 testy PASS. | ✅ |
| BE-019 | Routing Engine: RoutingEngine (interfejs), DefaultRoutingEngine (skill-based, round-robin, sticky), RoutingService, AgentSessionData, ContactQueuedMessage, ContactAssignedEvent. | ✅ |
| BE-020 | Queue API: QueueController (5 endpointów + stats), DTOs, routing strategy enum, Redis cache TTL 5s dla stats. | ✅ |
| BE-028 | Raporty historyczne: AgentReportRow/AgentReportParams DTOs, ContactRepository +2 native SQL GROUP BY, ReportsService (Redis cache MD5 5min, walidacja 90 dni, CSV + XLSX Apache POI 5.2.5), ReportsController (4 endpointy), ReportsServiceTest 13 testów, 442 PASS. | ✅ |
| BE-029 | RT Metrics API: SupervisorMetricsPayload (DTO), SupervisorMetricsService (@Scheduled fixedRate=5s, Redis SCAN cursor-based, broadcast /topic/tenant/{tenantId}/supervisor eventType="SUPERVISOR_METRICS", izolacja cross-tenant, graceful degradation), 15 testów, 429 PASS. | ✅ |
| BE-013 | IVR Engine: IvrController (7 endp. CRUD + activate + DTMF), IvrService, IvrEngineService, IvrCallListener, domain/ivr model (IvrDefinition/Node/NodeType/Option/SessionData) | ✅ |
| BE-022 | Campaign CRUD API: CampaignController (6 endp.), CampaignService, CampaignRepository, Campaign entity, V026 migracja | ✅ |
| BE-023 | Import CSV kampanii: CampaignImportController, CampaignImportService (@Async, OpenCSV, batch 1000, ON CONFLICT), Redis TTL 1h, V027 indeks | ✅ |
| BE-026 | Import klientów CSV: CustomerImportController, CustomerImportService (@Async, chunk 500, SKIP/OVERWRITE, E.164), 24 testy PASS | ✅ |
| BE-001b | MinIO docker-compose: serwis minio + minio-init, bucket contact-center-recordings, S3Properties | ✅ |
| BE-015 | Email Adapter: EmailPollingService (IMAP @Scheduled), EmailSendService (SMTP), EmailRoutingService (routing priorytetowy po email_address kolejki + reguły), EmailEncryptionService (AES-256), EmailController (5 endpointów), EmailMessage/EmailRoutingRule/EmailTemplate repozytoria przeniesione do domain/model i domain/repository, EmailEventPublisher (RabbitMQ) | ✅ |
| BE-016 | Szablony email: EmailTemplateController (6 endpointów CRUD + preview), EmailTemplateService, EmailTemplate entity, EmailTemplateRepository, MustacheTemplateEngine (renderowanie zmiennych), DTOs CreateEmailTemplateRequest/UpdateEmailTemplateRequest/EmailTemplateResponse | ✅ |
| BE-014 | Voicebot Python: ASR + NLU + eskalacja do agenta | ⬜ |
| BE-017 | OAuth flow i zarządzanie tokenami social media | ⬜ |
| BE-018 | Social Media Adapter: odbieranie i wysyłka wiadomości | ⬜ |
| BE-021 | Wait time estimation: informacja o czasie oczekiwania | ⬜ |
| BE-024 | Progressive Dialer: silnik automatycznego dzwonienia | ⬜ |
| BE-030 | ETL do data warehouse: CDC z PostgreSQL | ⬜ |
| BE-031 | RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17) | ⬜ |
| BE-032 | Twilio: konfiguracja numeru telefonu per tenant | ⬜ |

### Warstwa Frontend – fundament gotowy, widoki do realizacji

| ID | Nazwa | Status |
|----|-------|--------|
| FE-001 | Inicjalizacja Angular, workspace, proxy | ✅ |
| FE-002 | Routing, lazy loading, AuthGuard, RoleGuard | ✅ |
| FE-003 | HTTP Interceptor, JWT refresh, toast błędów | ✅ |
| FE-004 | Ekran logowania (3-krokowy email-first flow) + MFA + zmiana hasła | ✅ |
| FE-005 | Shell: navbar, sidenav, breadcrumbs, notyfikacje | ✅ |
| FE-006 | Lista tenantów i formularz tworzenia tenanta | ✅ |
| FE-007 | Dashboard techniczny admina: AdminMetricsService (BehaviorSubject, polling 30s), AdminDashboardComponent (KPI cards, tabela tenantów, skeleton loading), badge alertów w SidenavComponent | ✅ |
| FE-008 | Zarządzanie agentami: AgentListComponent (tabela paginowana PagedResponse, filtry, multi-select skills), AgentFormComponent, AgentService, guard HTTP 409 przy deactivate | ✅ |
| FE-009 | Agent Desktop: AgentDesktopComponent (layout z panelem statusu agenta AVAILABLE/BUSY/BREAK/AFTER_CONTACT, obszar zakładek kontaktów max 4: 1 telefon + 3 chat/email, integracja WebSocket z WebSocket.service.ts, baner "Utracono połączenie – próba reconnect"). Czeka na BE-012 ✅. | ✅ |
| FE-010 | Softphone WebRTC: SIP.js/JsSIP, odbieranie/rozłączanie, mute, hold, blind i attended transfer, CLi. Czeka na FE-009 ✅, BE-009 ✅, BE-012 ✅. | ✅ |
| FE-011 | Panel profilu klienta: panel boczny w AgentDesktopComponent z danymi klienta (CLI lookup), historia ostatnich kontaktów, CTA "Utwórz profil" dla nieznanych numerów. Integracja z BE-025 ✅ i BE-011 ✅. | ✅ |
| FE-012 | Komponent email: EmailContactComponent (wątek email z paginacją, edytor odpowiedzi, wybór szablonu autocomplete), EmailThreadMessageComponent, EmailService, EmailSettingsComponent (konfiguracja IMAP/SMTP supervisora w /supervisor/settings), EmailConfigService. Integracja z AgentDesktopComponent. | ✅ |
| FE-017 | Disposition panel: DispositionPanelComponent (modal ACW, timer MM:SS, dropdown 6 kodów, textarea notatka), ContactService.setDisposition() → PATCH /api/contacts/{id}/disposition, contact-tab.store.ts (stan WRAPPING), effect() na session.state=ENDED w agent-desktop. | ✅ |
| FE-018 | Lista klientów: CustomerListComponent (tabela PagedResponse, wyszukiwanie debounce 300ms, skeleton loading), CustomerDeleteModalComponent (modal RODO), CustomerService (frontend). Czeka na BE-025 ✅. | ✅ |
| FE-019 | Profil klienta: CustomerDetailComponent (dane podstawowe, chips telefon/email, custom_fields, oś czasu kontaktów, badge RODO). Czeka na FE-018 ✅ + BE-025 ✅ + BE-027 ✅. | ✅ |
| FE-021 | Dashboard RT supervisora: KPI cards, tabela agentów z aktualnym statusem, wykres kolejek; WebSocket STOMP /topic/tenant/{tenantId}/supervisor, dane co 5s, tryb pełnoekranowy. Czeka na BE-029 ✅. | ✅ |
| FE-022 | Raporty historyczne: report.model.ts, reports.service.ts (getAgentReport, exportCsv, exportXlsx blob), ReportsComponent (filtry URL sync, tabela badge'ami kanałów, paginacja, eksport Blob, skeleton, empty state), /reports z roleGuard, build 0 błędów. Czeka na BE-028 ✅. | ✅ |
| FE-024 | Panel konfiguracji kolejek: QueueListComponent (tabela + polling 10s), QueueFormComponent (strategia routingu, skills, sticky timeout), QueueDeleteModalComponent. Czeka na BE-020 ✅. | ✅ |
| FE-014 | IVR Editor: IvrListComponent (lista drzew + status aktywny), IvrEditorComponent (canvas SVG drag & drop: węzły PlayAudio/TTS/CollectDTMF/Menu/TransferToQueue/Hangup, krawędzie SVG, panel konfiguracji, zapis JSONB). IvrService, ivr.model.ts. | ✅ |
| FE-015 | Kampanie: CampaignListComponent (tabela + polling 10s, akcje inline start/pause/stop), CampaignFormComponent (harmonogram, walidacja) | ✅ |
| FE-016 | Import CSV kampanii: CampaignImportComponent (4-krokowy wizard, drag&drop, mapowanie kolumn, polling 3s, raport) | ✅ |
| FE-020 | Import klientów CSV: CustomerImportComponent (4-krokowy wizard, deduplikacja radio, auto-mapowanie, pobieranie błędów CSV) | ✅ |
| FE-013 | Komponent obsługi kontaktu social media | ⬜ |
| FE-023 | Panel konfiguracji integracji social media (OAuth flow) | ⬜ |
| FE-025 | Panel konfiguracji Twilio per tenant | ⬜ |

---

## 2. Zależności między zadaniami

### 2.1 Zależności wewnątrz warstwy Backend (BE → BE)

Wszystkie ścieżki startują od BE-001..BE-003, które są już ukończone.

| Zadanie | Blokuje (wymaga ukończenia) | Uwagi |
|---------|-----------------------------|-------|
| BE-003 ✅ | BE-004 | Auth API wymaga gotowej infrastruktury JWT/MFA |
| BE-002 ✅ | BE-005, BE-006, BE-008, BE-012, BE-015, BE-017, BE-020, BE-022, BE-025, BE-027 | TenantContext wymagany przez wszystkie repozytoria biznesowe |
| BE-001 ✅ | BE-009, BE-015 | Adaptery kanałowe wymagają infrastruktury Spring |
| BE-004 ✅ | – | Nie blokuje innych BE (można realizować równolegle) |
| BE-006 | BE-007 | Metryki adminowe wymagają CRUD tenantów |
| BE-008 | BE-019 | Routing engine wymaga zarządzania agentami i ich statusami |
| BE-009 | BE-010, BE-011, BE-013 | Adapter VoIP jest podstawą nagrywania, CLI lookup i IVR Engine |
| BE-009 + BE-003 | BE-012 | WebSocket hub wymaga adaptera VoIP i JWT do autentykacji WS |
| BE-013 | BE-014 | Voicebot Python wymaga działającego IVR Engine |
| BE-015 | BE-016 | Szablony email wymagają działającego adaptera email |
| BE-017 | BE-018 | Adapter social media wymaga OAuth flow |
| BE-019 ✅ | BE-021 | Wait time estimation wymaga działającego routing engine |
| BE-022 | BE-023, BE-024 | Import CSV i Dialer wymagają CRUD kampanii |
| BE-009 + BE-022 | BE-024 | Progressive Dialer wymaga adaptera VoIP ORAZ kampanii |
| BE-025 | BE-026, BE-031 | Import klientów i RODO wymagają Customer CRUD API |
| BE-027 | BE-028, BE-029 | Raporty historyczne i RT metrics wymagają Contact API |
| BE-012 + BE-019 | BE-029 | RT Metrics WebSocket wymaga WebSocket hub i routing engine |
| BE-027 | BE-030 | ETL DW wymaga Contact API jako źródła danych |
| BE-011 | BE-025 | CLI lookup zależy od Customer API (poszukiwanie po telefonie) |

### 2.2 Zależności wewnątrz warstwy Frontend (FE → FE)

Wszystkie ścieżki startują od FE-001..FE-005, które są już ukończone.

| Zadanie | Blokuje (wymaga ukończenia) | Uwagi |
|---------|-----------------------------|-------|
| FE-005 ✅ | FE-006, FE-007, FE-008, FE-009, FE-014, FE-015, FE-018, FE-021, FE-022, FE-023, FE-024 | Shell aplikacji jest prerekviztem wszystkich widoków feature |
| FE-009 | FE-010, FE-011, FE-012, FE-013, FE-017 | Agent Desktop jest prerekviztem wszystkich komponentów obsługi kontaktu |
| FE-015 | FE-016 | Import kontaktów kampanii wymaga widoku zarządzania kampaniami |
| FE-018 | FE-019, FE-020 | Profil klienta i import klientów wymagają widoku listy klientów |

Pełna ścieżka fundament (ukończona):
```
FE-001 → FE-002 → FE-004 → FE-005
          FE-001 → FE-003 ↗
```

Ścieżki funkcjonalne (do realizacji):
```
FE-005 → FE-006, FE-007                          (Admin)
FE-005 → FE-008, FE-024                          (Supervisor)
FE-005 → FE-009 → FE-010, FE-011, FE-012, FE-013, FE-017  (Agent Desktop)
FE-005 → FE-014                                  (IVR – niezależny od Agent Desktop)
FE-005 → FE-015 → FE-016                         (Kampanie)
FE-005 → FE-018 → FE-019, FE-020                 (Klienci)
FE-005 → FE-021, FE-022                          (Raporty)
FE-005 → FE-023                                  (Integracje social media)
```

### 2.3 Zależności cross-layer (FE czeka na BE)

| Zadanie FE | Czeka na BE | Opis zależności | MSW możliwe? |
|------------|-------------|-----------------|--------------|
| FE-004 | BE-004 ✅ | Ekran logowania (email-first): `POST /api/public/tenants-by-email` (wykrycie org), `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`. Zrealizowane produkcyjnie. | ✅ Gotowe |
| FE-006 | BE-006 | Lista tenantów i formularz tworzenia: cały CRUD `/api/tenants` | 🔵 Tak – MSW mock |
| FE-007 | BE-007 | Dashboard admina: `GET /api/admin/metrics` | 🔵 Tak – MSW mock |
| FE-008 | BE-008 | Zarządzanie agentami: CRUD `/api/users`, endpoint skills | 🔵 Tak – MSW mock |
| FE-009 | BE-012 ✅ | Agent Desktop: WebSocket hub dla statusów i kontaktów RT. FE-009 ✅ ukończone. | ✅ Gotowe |
| FE-010 | BE-009 ✅, BE-012 ✅ | Softphone WebRTC: adapter VoIP + WebSocket sygnalizacja | ✅ Gotowe |
| FE-011 | BE-025 ✅, BE-011 ✅ | Panel klienta podczas kontaktu: CLI lookup + Customer API | ✅ Gotowe |
| FE-012 | BE-015 ✅, BE-016 ✅ | Obsługa emaila: adapter IMAP/SMTP + szablony | ✅ Gotowe |
| FE-013 | BE-018 | Obsługa social media: webhooks i wysyłka | 🔵 Tak – MSW mock |
| FE-014 | BE-020 ✅, BE-013 ✅ | Edytor IVR: Queue API + IVR Engine (zapis JSONB). Oba gotowe. | ✅ Gotowe |
| FE-015 | BE-022 ✅ | Zarządzanie kampaniami: CRUD + akcje start/pause/stop | ✅ Gotowe |
| FE-016 | BE-023 ✅ | Import CSV kampanii: async job + polling statusu | ✅ Gotowe |
| FE-017 | BE-027 ✅ | Disposition codes: `PATCH /api/contacts/{id}/disposition` | ✅ Gotowe |
| FE-018 | BE-025 ✅ | Lista klientów: fuzzy search + paginacja `/api/customers` | ✅ Gotowe |
| FE-019 | BE-025 ✅, BE-027 ✅ | Profil klienta: dane + historia kontaktów | ✅ Gotowe |
| FE-020 | BE-026 ✅ | Import klientów CSV: async job + polling | ✅ Gotowe |
| FE-021 | BE-029 ✅ | Dashboard RT supervisora: WebSocket metrics feed | ✅ Gotowe |
| FE-022 | BE-028 ✅ | Raporty historyczne: agregacje + eksport CSV/XLSX | ✅ Gotowe |
| FE-023 | BE-017 | Panel integracji social media: OAuth flow callback | 🔴 OAuth wymaga prawdziwego backendu |
| FE-024 | BE-020 ✅ | Konfiguracja kolejek: CRUD + stats `/api/queues` | ✅ Gotowe |

---

## 3. Kolejność realizacji – ścieżki krytyczne

### 3.1 Ścieżka krytyczna – MVP (pierwsze 4 tygodnie)

Poniższa kolejność maksymalizuje odblokowanie kolejnych zadań. Zadania oznaczone 🟢 można realizować równolegle.

**Faza 1 – Odblokowanie Auth i podstawowych API (tydzień 1)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 1 | BE-004 ✅ | BE | 🔴 Auth API – odblokuje FE-004 produkcyjnie i jest prerekviztem dla bezpiecznego testowania wszystkich dalszych endpointów |
| 2 | 🟢 BE-005 ✅ | BE | Audit Log – nie blokuje FE, ale jest wymagany przez RODO i powinien być gotowy przed operacjami na danych |
| 3 | 🟢 BE-006 ✅ | BE | Tenant CRUD – odblokuje FE-006 ✅ i BE-007 |
| 4 | 🟢 BE-008 | BE | User/Agent CRUD – odblokuje FE-008 i BE-019 |

**Faza 2 – Core Business (tygodnie 2-3)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 5 | 🟢 BE-007 ✅ | BE | Admin metrics – odblokuje FE-007 |
| 6 | 🟢 BE-025 ✅ | BE | Customer API – ukończone; odblokowane FE-018 ✅, FE-019, FE-011 |
| 7 | 🟢 BE-027 ✅ | BE | Contact API – ukończone; odblokowane FE-017 ✅, FE-019, FE-022, BE-028, BE-029 |
| 8 | 🟢 BE-020 ✅ | BE | Queue API – ukończone; odblokowane FE-024 ✅ i BE-019 ✅ |
| 9 | 🟢 FE-006 ✅ | FE | Lista tenantów (ukończone) |
| 10 | 🟢 FE-008 ✅ | FE | Zarządzanie agentami (ukończone) |
| 11 | 🟢 FE-018 ✅ | FE | Lista klientów – ukończone |
| 12 | 🟢 FE-024 ✅ | FE | Konfiguracja kolejek – ukończone (BE-020 ✅) |

**Faza 3 – Agent Desktop i Real-time (tydzień 3-4)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 13 | BE-009 ✅ | BE | Adapter VoIP – ukończony, MockTelephonyAdapter + interfejs + webhook |
| 14 | 🟢 BE-012 ✅ | BE | WebSocket hub – ukończony, STOMP + JWT auth + RabbitMQ relay |
| 15 | 🟢 BE-019 ✅ | BE | Routing Engine – ukończone (BE-008 ✅ + BE-020 ✅) |
| 16 | FE-009 ✅ | FE | Agent Desktop layout – ukończony, panel statusu + zakładki + WS |
| 17 | 🟢 FE-007 ✅ | FE | Dashboard admina (czeka na BE-007 ✅) |
| 18 | 🟢 FE-017 ✅ | FE | Disposition codes – ukończone (FE-009 ✅ + BE-027 ✅) |
| 19 | 🟢 FE-019 ✅ | FE | Profil klienta – ukończone (FE-018 ✅ + BE-025 ✅ + BE-027 ✅) |
| 20 | 🟢 FE-020 | FE | Import klientów (czeka na FE-018 + BE-026) |

---

### 3.2 Ścieżka – Admin (EPIC-01, EPIC-02)

```
BE-004 (Auth API)
    |
    ├── BE-006 (Tenant CRUD) → FE-006 (Lista tenantów)
    |       |
    |       └── BE-007 (Admin Metrics) → FE-007 (Dashboard admina)
    |
    └── BE-008 (User CRUD) → FE-008 (Zarządzanie agentami)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-004 | ✅ | Prerekvizyt dla wszystkich chronionych endpointów |
| 2 | 🟢 BE-006 | ✅ | Wymaga BE-002 ✅ + DB-005 ✅ |
| 3 | 🟢 BE-008 | ✅ | Wymaga BE-002 ✅ + DB-003 ✅ |
| 4 | BE-007 | ✅ | Wymaga BE-006 ✅ |
| 5 | 🟢 FE-006 | ✅ | Wymaga BE-006 ✅ |
| 6 | 🟢 FE-008 | ✅ | Wymaga BE-008 ✅ |
| 7 | FE-007 | ✅ | Wymaga BE-007 ✅ |

---

### 3.3 Ścieżka – Agent Desktop (EPIC-03, EPIC-05, EPIC-06)

```
BE-009 (VoIP Adapter) ────────┐
    |                         |
    ├── BE-010 (Nagrywanie)   |
    ├── BE-011 (CLI lookup)   └──> BE-012 (WebSocket hub) → FE-009 (Agent Desktop layout)
    └── BE-013 (IVR Engine)                                      |
                                                    ┌────────────┼────────────┐
                                                    |            |            |
                                               FE-010       FE-011       FE-012
                                             (Softphone)  (Profil)     (Email)
                                                    |
                                               FE-013 (Social)
                                               FE-017 (Disposition)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-009 | ✅ | Adapter VoIP z MockTelephonyAdapter i TelephonyEventPublisher |
| 2 | 🟢 BE-010 | ✅ | Ukończone – RecordingService S3, RetentionJob, RecordingController |
| 3 | 🟢 BE-011 | ✅ | Ukończone – CliLookupService, CallEventEnricher, CustomerCliResult |
| 4 | 🟢 BE-012 | ✅ | WebSocket STOMP, JWT interceptor, RabbitMQ relay |
| 5 | 🟢 BE-015 | ✅ | Ukończone – EmailPollingService (IMAP), EmailSendService (SMTP), EmailController |
| 6 | 🟢 BE-016 | ✅ | Ukończone – EmailTemplateController, MustacheTemplateEngine, EmailTemplateService |
| 7 | 🟢 BE-017 | ⬜ | OAuth social – niezależny od BE-009 |
| 8 | FE-009 | ✅ | Agent Desktop layout, panel statusu, zakładki kontaktów, integracja WS |
| 9 | 🟢 FE-010 | ✅ | Wymaga FE-009 ✅ + BE-009 ✅, BE-012 ✅ |
| 10 | 🟢 FE-011 | ✅ | Ukończone – panel boczny CLI lookup, integracja z BE-025 ✅ + BE-011 ✅ |
| 11 | 🟢 FE-012 | ✅ | Ukończone – EmailContactComponent, EmailThreadMessageComponent, EmailService, EmailSettingsComponent |
| 12 | 🟢 FE-013 | ⬜ | Wymaga FE-009 ✅ + BE-018 (lub MSW) |
| 13 | 🟢 FE-017 | ✅ | Ukończone – DispositionPanelComponent, ContactService, stan WRAPPING |

---

### 3.4 Ścieżka – Kampanie Outbound (EPIC-08)

```
BE-022 (Campaign CRUD) ──┬──> BE-023 (Import CSV async)
                         |
BE-009 (VoIP Adapter) ───┴──> BE-024 (Progressive Dialer)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-022 | ✅ | Ukończone – CampaignController, CampaignService, V026 migracja |
| 2 | 🟢 BE-023 | ✅ | Ukończone – CampaignImportController, @Async batch 1000, V027 indeks |
| 3 | 🟢 FE-015 | ✅ | Ukończone – CampaignListComponent, CampaignFormComponent |
| 4 | BE-024 | ⬜ | Wymaga BE-009 ✅ + BE-022 ✅ |
| 5 | FE-016 | ✅ | Ukończone – CampaignImportComponent (4-krokowy wizard) |

---

### 3.5 Ścieżka – Baza Klientów (EPIC-09)

```
BE-025 (Customer CRUD) ──┬──> BE-026 (Import CSV async)
                         |
                         └──> BE-031 (RODO export/anonymize)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-025 | ✅ | Ukończone – CustomerController, CustomerService, fuzzy search |
| 2 | 🟢 BE-026 | ✅ | Ukończone – CustomerImportController, @Async chunk 500, SKIP/OVERWRITE, E.164 |
| 3 | 🟢 BE-031 | ⬜ | Wymaga BE-025 ✅ + BE-027 ✅ |
| 4 | FE-018 | ✅ | Ukończone – CustomerListComponent, CustomerDeleteModalComponent |
| 5 | 🟢 FE-019 | ✅ | Ukończone – CustomerDetailComponent, oś czasu kontaktów, badge RODO |
| 6 | 🟢 FE-020 | ✅ | Ukończone – CustomerImportComponent (4-krokowy wizard, deduplikacja) |

---

### 3.6 Ścieżka – Raportowanie (EPIC-10)

```
BE-027 (Contact API) ──┬──> BE-028 (Raporty historyczne) ──> FE-022
                       |
BE-012 (WebSocket) ────┴──> BE-029 (RT Metrics WS) ──> FE-021
BE-019 (Routing Engine) ──┘
                       |
BE-027 ────────────────┴──> BE-030 (ETL → ClickHouse)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-027 | ✅ | Ukończone – ContactController, ContactService, ContactRepository |
| 2 | 🟢 BE-028 | ✅ | Ukończone – ReportsService (Redis cache MD5 5min, CSV + XLSX Apache POI), ReportsController (4 endpointy) |
| 3 | 🟢 BE-029 | ✅ | Ukończone – SupervisorMetricsService (@Scheduled 5s, Redis SCAN, broadcast WS) |
| 4 | 🟢 BE-030 | ⬜ | Wymaga BE-027 ✅ + DB-013 ✅ + DB-014 ✅ (schemat DW gotowy) |
| 5 | 🟢 FE-021 | ✅ | Ukończone – Dashboard RT supervisora, KPI cards, WebSocket STOMP |
| 6 | 🟢 FE-022 | ✅ | Ukończone – ReportsComponent, filtry URL sync, eksport CSV/XLSX Blob |

---

## 4. Rekomendacje

### 4.1 Zadania BE do realizacji jako pierwsze (odblokują najwięcej FE)

Poniższa kolejność realizacji BE maksymalizuje liczbę odblokowanych zadań FE przy minimalnej pracy:

| Priorytet | Zadanie BE | Odblokuje zadań FE | Uwagi |
|-----------|------------|-------------------|-------|
| ✅ 1 | BE-004 (Auth API) | FE-004 produkcyjnie | Ukończone |
| ✅ 2 | BE-025 (Customer API) | FE-018 ✅, FE-019, FE-011 | Ukończone – fuzzy search, CRUD, RODO delete |
| ✅ 3 | BE-027 (Contact API) | FE-017 ✅, FE-019, FE-022 | Ukończone – fundament raportowania i disposition codes |
| ✅ 4 | BE-006 (Tenant CRUD) | FE-006 ✅ | Ukończone – BE-007 odblokowane |
| ✅ 5 | BE-008 (User CRUD) | FE-008 ✅ | Ukończone – odblokuje BE-019 (Routing Engine) |
| ✅ 6 | BE-020 (Queue API) | FE-024 ✅ | Ukończone – QueueController, routing strategy, stats endpoint |
| 🟡 7 | BE-022 (Campaign CRUD) | FE-015, FE-016 | Odblokuje cały moduł kampanii |
| 🟡 8 | BE-029 (RT Metrics WS) | FE-021 | Odblokowane przez BE-012 ✅ + BE-019 ✅; wszystkie zależności spełnione |
| 🟡 9 | BE-021 (Wait Time Estimation) | – | Odblokowane przez BE-019 ✅ + BE-020 ✅ |
| ✅ 8 | BE-009 (VoIP Adapter) | FE-010 (Softphone) | Ukończone – MockTelephonyAdapter, TelephonyAdapter interfejs, webhook controller |
| ✅ 9 | BE-012 (WebSocket hub) | FE-009 (Agent Desktop) | Ukończone – WebSocketConfig STOMP, JWT auth interceptor, RabbitMQ relay |
| ✅ 10 | BE-010 (Nagrywanie) | FE-019 (link nagranie w profilu) | Ukończone – RecordingService S3, presigned URL |
| ✅ 11 | BE-011 (CLI lookup) | FE-011 (Panel klienta podczas kontaktu) | Ukończone – CliLookupService, CallEventEnricher |

### 4.2 Strategia MSW (Mock Service Worker)

Zadania FE **mogą i powinny być realizowane z MSW** zanim odpowiedni BE jest gotowy. Priorytetyzacja:

- **Zalecane do MSW mock:** FE-006, FE-007, FE-008, FE-009, FE-011, FE-012, FE-013, FE-015, FE-016, FE-017, FE-018, FE-019, FE-020, FE-021, FE-022, FE-024
- **Trudne lub niemożliwe z samym MSW:**
  - FE-010 (Softphone WebRTC) – wymaga prawdziwego sygnalizowania SIP przez BE-009
  - FE-023 (OAuth flow social media) – redirect OAuth musi trafić na prawdziwy callback BE-017

Kontrakt OpenAPI dla MSW: wszystkie endpointy powinny być opisane w Swagger UI (`localhost:8080/swagger-ui.html`) przed implementacją FE, nawet jeśli endpoint nie jest jeszcze zaimplementowany (stub 501).

### 4.3 Zadania możliwe do równoległej realizacji przez różne zespoły

Poniższe grupy zadań są od siebie niezależne i mogą być realizowane przez różne osoby lub pary:

| Zespół A (BE) | Zespół B (BE) | Zespół C (FE) | Zespół D (FE) |
|---------------|---------------|---------------|---------------|
| BE-004 + BE-006 + BE-007 | BE-008 + BE-025 + BE-027 | FE-006 + FE-007 (z MSW) | FE-018 + FE-019 (z MSW) |
| BE-009 + BE-010 + BE-011 | BE-022 + BE-023 | FE-009 + FE-017 (z MSW) | FE-015 + FE-016 (z MSW) |
| BE-012 + BE-015 + BE-017 | BE-019 + BE-020 | FE-010 (czeka na BE-009) | FE-021 + FE-022 (z MSW) |

### 4.4 Blokery krytyczne – podsumowanie

| Bloker | Zadanie | Dlaczego krytyczne |
|--------|---------|-------------------|
| ✅ Rozwiązany | BE-009 (VoIP Adapter) | Ukończone – MockTelephonyAdapter, interfejs TelephonyAdapter, TelephonyEventPublisher |
| ✅ Rozwiązany | BE-004 (Auth API) | Ukończone – produkcyjnie weryfikowane z seedem |
| ✅ Rozwiązany | BE-012 (WebSocket hub) | Ukończone – STOMP, JWT auth, RabbitMQ relay, topics per user/tenant |
| ✅ Rozwiązany | FE-009 (Agent Desktop) | Ukończone – odblokowane FE-010..FE-013, FE-017 |
| ✅ Rozwiązany | FE-010 (Softphone WebRTC) | Ukończone – komponent SIP.js/JsSIP, pełna obsługa połączeń |
| ✅ Rozwiązany | BE-010 (Nagrywanie) | Ukończone – RecordingService S3, RecordingRetentionJob, presigned URL |
| ✅ Rozwiązany | BE-011 (CLI lookup) | Ukończone – CliLookupService, CallEventEnricher, Redis cache 5min |
| ✅ Rozwiązany | BE-025 (Customer API) | Ukończone – CustomerController, CustomerService, fuzzy search, RODO delete |
| ✅ Rozwiązany | FE-018 (Lista klientów) | Ukończone – CustomerListComponent, CustomerDeleteModalComponent, CustomerService |
| ✅ Rozwiązany | BE-027 (Contact API) | Ukończone – ContactController (6 endp.), ContactService, ContactRepository (partycjonowana tabela), 22 testy PASS |
| ✅ Rozwiązany | FE-017 (Disposition panel) | Ukończone – DispositionPanelComponent, ContactService.setDisposition(), stan WRAPPING w contact-tab.store.ts |
| ✅ Rozwiązany | FE-011 (Panel klienta) | Ukończone – panel boczny CLI lookup, historia kontaktów, integracja z BE-025 i BE-011 |
| ✅ Rozwiązany | BE-019 (Routing Engine) | Ukończone – DefaultRoutingEngine (skill-based, round-robin, sticky agent), RoutingService; odblokowane BE-029 i BE-021 |
| ✅ Rozwiązany | BE-020 (Queue API) | Ukończone – QueueController, DTOs, cache Redis TTL 5s; odblokowane FE-024 |
| ✅ Rozwiązany | FE-019 (Profil klienta) | Ukończone – CustomerDetailComponent, oś czasu kontaktów, chips telefon/email, badge RODO |
| ✅ Rozwiązany | FE-024 (Konfiguracja kolejek) | Ukończone – QueueListComponent, QueueFormComponent (strategia routingu, skills, sticky timeout), QueueDeleteModalComponent |
| ✅ Rozwiązany | BE-029 (RT Metrics WebSocket) | Ukończone – SupervisorMetricsService @Scheduled 5s, Redis SCAN, broadcast WS |
| ✅ Rozwiązany | BE-015 (Email Adapter) | Ukończone – routing po email_address kolejki (V029), IMAP, SMTP, szyfrowanie AES-256 |
| ✅ Rozwiązany | BE-016 (Szablony email) | Ukończone – MustacheTemplateEngine, EmailTemplateController (6 endpointów CRUD + preview) |
| ✅ Rozwiązany | FE-012 (Email contact UI) | Ukończone – EmailContactComponent, EmailSettingsComponent (supervisor), EmailService |
| 🟡 Średni | BE-021 (Wait Time Estimation) | Wymaga BE-019 ✅ + BE-020 ✅ – odblokowane |

---

*Dokument generowany na podstawie TASKS-BACKEND.md, TASKS-FRONTEND.md i PROGRESS.md.*
*Aktualizować przy każdej zmianie statusu zadań w PROGRESS.md.*
