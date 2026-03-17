# PROGRESS.md
# Contact Center SaaS – Postęp prac

**Ostatnia aktualizacja:** 2026-03-14 (aktualizacja po BE-006 + FE-006)

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
| BE-004 | Auth API: login, logout, refresh, zmiana hasła | ✅ | Rate limiting Redis (5/15min/IP→429), POST /api/auth/change-password (walidacja siły hasła), POST /api/auth/force-reset/{userId} (ADMIN/SUPERVISOR), passwordResetRequired w LoginResponse, LoginRateLimiter.java |
| BE-005 | Audit Log: zapis działań użytkowników | ✅ | @Audited AOP, AuditAspect (@Around), AuditLogService (RabbitMQ async), AuditLogConsumer (@RabbitListener), AuditLog entity (JPA + native INSERT dla tabeli partycjonowanej), AuditLogRepository, GET /api/audit-logs (ADMIN, paginacja max 100). @Audited dodany do TenantService: CREATED/UPDATED/DEACTIVATED. 189 testów PASS |
| BE-006 | Tenant CRUD API i limity zasobów | ✅ | Tenant.java (encja JPA, JSONB przez @JdbcTypeCode(SqlTypes.JSON)), TenantRepository, TenantService, TenantResourceLimitService (reużywany przez BE-008/020/022), TenantController (6 endpointów), ResourceLimitExceededException (HTTP 422). 27 nowych testów. Łącznie 173 PASS. Naprawiono: JsonMapConverter → @JdbcTypeCode, ENUM types → VARCHAR+CHECK (V019) |
| BE-007 | Admin metrics API: metryki RT tenantów | ⬜ | |
| BE-008 | User / Agent CRUD API ze skills | ⬜ | |
| BE-009 | Adapter VoIP: integracja z SIP trunk / CPaaS API | ⬜ | |
| BE-010 | Nagrywanie rozmów: zapis do S3, metadane, retencja | ⬜ | |
| BE-011 | CLI lookup: wzbogacenie połączenia o dane klienta | ⬜ | |
| BE-012 | WebSocket hub: real-time events do Agent Desktop | ⬜ | |
| BE-013 | IVR Engine: wykonanie drzewa IVR | ⬜ | |
| BE-014 | Voicebot Python: ASR + NLU + eskalacja do agenta | ⬜ | |
| BE-015 | Email Adapter: IMAP polling + SMTP wysyłka | ⬜ | |
| BE-016 | Szablony odpowiedzi email: CRUD API | ⬜ | |
| BE-017 | OAuth flow i zarządzanie tokenami social media | ⬜ | |
| BE-018 | Social Media Adapter: odbieranie i wysyłka wiadomości | ⬜ | |
| BE-019 | Routing Engine: skill-based, round-robin, sticky agent | ⬜ | |
| BE-020 | Queue API: CRUD kolejek i konfiguracja routingu | ⬜ | |
| BE-021 | Wait time estimation: informacja o czasie oczekiwania | ⬜ | |
| BE-022 | Campaign CRUD API i harmonogram | ⬜ | |
| BE-023 | Import CSV kontaktów kampanii (async job) | ⬜ | |
| BE-024 | Progressive Dialer: silnik automatycznego dzwonienia | ⬜ | |
| BE-025 | Customer CRUD API i fuzzy search | ⬜ | |
| BE-026 | Import klientów z CSV (async job) | ⬜ | |
| BE-027 | Contact API: zapis i odczyt historii kontaktów | ⬜ | |
| BE-028 | Raporty historyczne: agregacje per agent i kampania | ⬜ | |
| BE-029 | RT Metrics API: WebSocket feed dla supervisora | ⬜ | |
| BE-030 | ETL do data warehouse: CDC z PostgreSQL | ⬜ | |
| BE-031 | RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17) | ⬜ | |

---

## TASKS-FRONTEND.md

| ID | Nazwa | Status | Uwagi |
|----|-------|--------|-------|
| FE-001 | Inicjalizacja projektu Angular i konfiguracja workspace | ✅ | Angular 21.2.x, standalone components, SCSS, Vitest. ESLint (angular-eslint) + Prettier + Husky/lint-staged. Struktura: core/, shared/, features/, environments/. Proxy /api/* → localhost:8080, WebSocket /ws. ng build i ng test PASS. |
| FE-002 | Konfiguracja routingu, lazy loading i guard AuthGuard | ✅ | Standalone functional guards (AuthGuard, RoleGuard, RoleRedirectGuard), lazy loading 12 chunków, TokenService (localStorage/sessionStorage), AuthService (sygnały), authInterceptor z silent refresh kolejkującym żądania, routing dla /auth/**, /admin/**, /supervisor/**, /agent/** |
| FE-003 | HTTP Interceptor: JWT, refresh token, obsługa błędów 401/403 | ✅ | errorHandlerInterceptor (403→toast "Brak uprawnień", 5xx→toast "Błąd serwera", status 0→"Brak połączenia"), NotificationService (signal-based, auto-dismiss 4-6s), ToastContainerComponent (WCAG AA, aria-live), oba interceptory zarejestrowane w app.config.ts |
| FE-004 | Moduł uwierzytelniania: ekran logowania i MFA | ✅ | LoginComponent (dwustanowy: credentials→MFA, reactive form, walidacja, spinner, błąd 401 inline), MFA krok TOTP (6 cyfr, pattern validator), ChangePasswordComponent (cross-field validator, wskaźnik siły hasła), AUTH_ROUTES, dropdown tenanta w formularzu logowania. Naprawiony proxy.conf.json (usunięty pathRewrite). Naprawiony hash BCrypt w V999__dev_seed.sql. |
| FE-005 | Shell aplikacji: top navbar, sidenav, breadcrumbs, notyfikacje | ✅ | AppShellComponent (CSS Grid/Flex, skip-link WCAG), TopNavbarComponent (hamburger, badge roli, logout, tenant info), SidenavComponent (menu kontekstowe per rola ADMIN/SUPERVISOR/AGENT, SVG ikony inline, responsive: overlay mobile/tablet, sticky desktop 1280px+), BreadcrumbsComponent + BreadcrumbService (Router.events, data.breadcrumb, aria-current), admin/supervisor/agent shell i routes zaktualizowane. ng build PASS. |
| FE-006 | Lista tenantów i formularz tworzenia tenanta | ✅ | TenantListComponent (tabela z paginacją 20/str, filtry nazwa+status z debounce 300ms, skeleton loading, empty state, badge statusów ACTIVE/INACTIVE/SUSPENDED, przycisk dezaktywacji per wiersz), TenantFormComponent (reactive form, async validator unikalności nazwy debounce 500ms, limity agentów/kolejek/kampanii), TenantDeactivateModalComponent (natywny <dialog>, WCAG AA), TenantService (6 metod API), TENANT_ROUTES lazy-loaded, admin.routes.ts zaktualizowane. ng build PASS. Naprawiono: TenantService kontrakt (List nie PagedResponse), CreateTenantRequest pole config → limits |
| FE-007 | Dashboard techniczny administratora (metryki tenantów RT) | ⬜ | |
| FE-008 | Zarządzanie agentami: lista, tworzenie, edycja, skills | ⬜ | |
| FE-009 | Agent Desktop: główny layout i panel statusu agenta | ⬜ | |
| FE-010 | Komponent Softphone WebRTC | ⬜ | |
| FE-011 | Panel profilu klienta podczas kontaktu | ⬜ | |
| FE-012 | Komponent obsługi kontaktu email | ⬜ | |
| FE-013 | Komponent obsługi kontaktu social media | ⬜ | |
| FE-014 | Graficzny edytor drzewa IVR (drag & drop) | ⬜ | |
| FE-015 | Zarządzanie kampaniami: lista i formularz tworzenia | ⬜ | |
| FE-016 | Import listy kontaktów CSV do kampanii | ⬜ | |
| FE-017 | Panel disposition codes po zakończeniu kontaktu | ⬜ | |
| FE-018 | Wyszukiwanie i lista klientów (fuzzy search) | ⬜ | |
| FE-019 | Profil klienta: widok szczegółowy i historia kontaktów | ⬜ | |
| FE-020 | Import klientów z CSV | ⬜ | |
| FE-021 | Dashboard RT supervisora | ⬜ | |
| FE-022 | Raporty historyczne: filtry, tabele, eksport | ⬜ | |
| FE-023 | Panel konfiguracji integracji social media (OAuth flow) | ⬜ | |
| FE-024 | Panel konfiguracji kolejek i routingu | ⬜ | |

---

## Podsumowanie

| Obszar | Ukończone | W trakcie | Nie rozpoczęte | Razem |
|--------|-----------|-----------|----------------|-------|
| Database (DB) | 19/19 | 0 | 0 | 19 |
| Backend (BE) | 6/31 | 0 | 25 | 31 |
| Frontend (FE) | 6/24 | 0 | 18 | 24 |
| **RAZEM** | **31/74** | **0** | **43** | **74** |

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

---

## Mapa procesów i kolejność realizacji zadań

**Stan na:** DB: 19/19 ✅ | BE: 6/31 (BE-001..BE-004 ✅, BE-006 ✅) | FE: 6/24 (FE-001..FE-006 ✅)

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

Cała warstwa DB jest gotowa. Wszystkie schematy, RLS, indeksy trigram (pg_trgm), funkcje RODO i schemat ClickHouse DW są zaimplementowane. BE i FE mogą budować na stabilnej bazie.

### Warstwa Backend – fundament gotowy, funkcje do realizacji

| ID | Nazwa | Status |
|----|-------|--------|
| BE-001 | Inicjalizacja Spring Boot, Flyway, Redis, RabbitMQ | ✅ |
| BE-002 | Multi-tenancy: TenantContext, TenantFilter, RLS | ✅ |
| BE-003 | Spring Security, JWT RS256, MFA TOTP, Blacklista Redis | ✅ |
| BE-004 | Auth API: rate limiting, change-password, force-reset | ✅ |
| BE-006 | Tenant CRUD API i limity zasobów | ✅ |
| BE-005, BE-007..BE-031 | Wszystkie pozostałe endpointy funkcjonalne | ⬜ |

### Warstwa Frontend – fundament gotowy, widoki do realizacji

| ID | Nazwa | Status |
|----|-------|--------|
| FE-001 | Inicjalizacja Angular, workspace, proxy | ✅ |
| FE-002 | Routing, lazy loading, AuthGuard, RoleGuard | ✅ |
| FE-003 | HTTP Interceptor, JWT refresh, toast błędów | ✅ |
| FE-004 | Ekran logowania + MFA + zmiana hasła | ✅ |
| FE-005 | Shell: navbar, sidenav, breadcrumbs, notyfikacje | ✅ |
| FE-006 | Lista tenantów i formularz tworzenia tenanta | ✅ |
| FE-007..FE-024 | Wszystkie widoki funkcjonalne | ⬜ |

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
| BE-019 | BE-021 | Wait time estimation wymaga działającego routing engine |
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
| FE-004 | BE-004 | Ekran logowania: `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`. Aktualnie działa z seed data, ale BE-004 musi być gotowe przed środowiskiem produkcyjnym | 🔵 Aktualnie mock przez seed |
| FE-006 | BE-006 | Lista tenantów i formularz tworzenia: cały CRUD `/api/tenants` | 🔵 Tak – MSW mock |
| FE-007 | BE-007 | Dashboard admina: `GET /api/admin/metrics` | 🔵 Tak – MSW mock |
| FE-008 | BE-008 | Zarządzanie agentami: CRUD `/api/users`, endpoint skills | 🔵 Tak – MSW mock |
| FE-009 | BE-012 | Agent Desktop: WebSocket hub dla statusów i kontaktów RT | 🔵 Tak – MSW/WS mock |
| FE-010 | BE-009, BE-012 | Softphone WebRTC: adapter VoIP + WebSocket sygnalizacja | 🔴 Trudne do zamockowania end-to-end |
| FE-011 | BE-025, BE-011 | Panel klienta podczas kontaktu: CLI lookup + Customer API | 🔵 Tak – MSW mock |
| FE-012 | BE-015, BE-016 | Obsługa emaila: adapter IMAP/SMTP + szablony | 🔵 Tak – MSW mock |
| FE-013 | BE-018 | Obsługa social media: webhooks i wysyłka | 🔵 Tak – MSW mock |
| FE-014 | BE-020, BE-013 | Edytor IVR: Queue API + IVR Engine (zapis JSONB) | 🔵 Tak – MSW mock |
| FE-015 | BE-022 | Zarządzanie kampaniami: CRUD + akcje start/pause/stop | 🔵 Tak – MSW mock |
| FE-016 | BE-023 | Import CSV kampanii: async job + polling statusu | 🔵 Tak – MSW mock |
| FE-017 | BE-027 | Disposition codes: `PATCH /api/contacts/{id}/disposition` | 🔵 Tak – MSW mock |
| FE-018 | BE-025 | Lista klientów: fuzzy search + paginacja `/api/customers` | 🔵 Tak – MSW mock |
| FE-019 | BE-025, BE-027 | Profil klienta: dane + historia kontaktów | 🔵 Tak – MSW mock |
| FE-020 | BE-026 | Import klientów CSV: async job + polling | 🔵 Tak – MSW mock |
| FE-021 | BE-029 | Dashboard RT supervisora: WebSocket metrics feed | 🔵 Tak – MSW/WS mock |
| FE-022 | BE-028 | Raporty historyczne: agregacje + eksport CSV/XLSX | 🔵 Tak – MSW mock |
| FE-023 | BE-017 | Panel integracji social media: OAuth flow callback | 🔴 OAuth wymaga prawdziwego backendu |
| FE-024 | BE-020 | Konfiguracja kolejek: CRUD + stats `/api/queues` | 🔵 Tak – MSW mock |

---

## 3. Kolejność realizacji – ścieżki krytyczne

### 3.1 Ścieżka krytyczna – MVP (pierwsze 4 tygodnie)

Poniższa kolejność maksymalizuje odblokowanie kolejnych zadań. Zadania oznaczone 🟢 można realizować równolegle.

**Faza 1 – Odblokowanie Auth i podstawowych API (tydzień 1)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 1 | BE-004 ✅ | BE | 🔴 Auth API – odblokuje FE-004 produkcyjnie i jest prerekviztem dla bezpiecznego testowania wszystkich dalszych endpointów |
| 2 | 🟢 BE-005 | BE | Audit Log – nie blokuje FE, ale jest wymagany przez RODO i powinien być gotowy przed operacjami na danych |
| 3 | 🟢 BE-006 ✅ | BE | Tenant CRUD – odblokuje FE-006 ✅ i BE-007 |
| 4 | 🟢 BE-008 | BE | User/Agent CRUD – odblokuje FE-008 i BE-019 |

**Faza 2 – Core Business (tygodnie 2-3)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 5 | 🟢 BE-007 | BE | Admin metrics – odblokuje FE-007 |
| 6 | 🟢 BE-025 | BE | Customer API – odblokuje FE-018, FE-019, FE-011 i BE-011 |
| 7 | 🟢 BE-027 | BE | Contact API – odblokuje FE-017, FE-019, FE-022, BE-028, BE-029 |
| 8 | 🟢 BE-020 | BE | Queue API – odblokuje FE-024 i BE-019 |
| 9 | 🟢 FE-006 | FE | Lista tenantów (czeka na BE-006) |
| 10 | 🟢 FE-008 | FE | Zarządzanie agentami (czeka na BE-008) |
| 11 | 🟢 FE-018 | FE | Lista klientów (czeka na BE-025) |
| 12 | 🟢 FE-024 | FE | Konfiguracja kolejek (czeka na BE-020) |

**Faza 3 – Agent Desktop i Real-time (tydzień 3-4)**

| Krok | Zadanie | Warstwa | Uzasadnienie |
|------|---------|---------|--------------|
| 13 | BE-009 | BE | 🔴 Adapter VoIP – blokuje softphone, nagrywanie, CLI lookup, IVR, dialer |
| 14 | 🟢 BE-012 | BE | WebSocket hub – odblokuje FE-009 (Agent Desktop) i FE-021 |
| 15 | 🟢 BE-019 | BE | Routing Engine (czeka na BE-008 + BE-020) |
| 16 | FE-009 | FE | Agent Desktop layout (czeka na BE-012) |
| 17 | 🟢 FE-007 | FE | Dashboard admina (czeka na BE-007) |
| 18 | 🟢 FE-017 | FE | Disposition codes (czeka na FE-009 + BE-027) |
| 19 | 🟢 FE-019 | FE | Profil klienta (czeka na FE-018 + BE-025, BE-027) |
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
| 3 | 🟢 BE-008 | ⬜ | Wymaga BE-002 ✅ + DB-003 ✅ |
| 4 | BE-007 | ⬜ | Wymaga BE-006 ✅ |
| 5 | 🟢 FE-006 | ✅ | Wymaga BE-006 ✅ |
| 6 | 🟢 FE-008 | ⬜ | Wymaga BE-008 (lub MSW) |
| 7 | FE-007 | ⬜ | Wymaga BE-007 (lub MSW) |

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
| 1 | BE-009 | ⬜ | 🔴 Bloker krytyczny dla całego kanału telefonicznego |
| 2 | 🟢 BE-010 | ⬜ | Wymaga BE-009 |
| 3 | 🟢 BE-011 | ⬜ | Wymaga BE-009 + BE-025 |
| 4 | 🟢 BE-012 | ⬜ | Wymaga BE-009 + BE-003 ✅ |
| 5 | 🟢 BE-015 | ⬜ | Kanał email – niezależny od BE-009 |
| 6 | 🟢 BE-017 | ⬜ | OAuth social – niezależny od BE-009 |
| 7 | FE-009 | ⬜ | 🔴 Bloker dla FE-010..FE-013, FE-017. Wymaga BE-012 |
| 8 | 🟢 FE-010 | ⬜ | Wymaga FE-009 + BE-009, BE-012 |
| 9 | 🟢 FE-011 | ⬜ | Wymaga FE-009 + BE-025, BE-011 (lub MSW) |
| 10 | 🟢 FE-012 | ⬜ | Wymaga FE-009 + BE-015, BE-016 (lub MSW) |
| 11 | 🟢 FE-013 | ⬜ | Wymaga FE-009 + BE-018 (lub MSW) |
| 12 | 🟢 FE-017 | ⬜ | Wymaga FE-009 + BE-027 (lub MSW) |

---

### 3.4 Ścieżka – Kampanie Outbound (EPIC-08)

```
BE-022 (Campaign CRUD) ──┬──> BE-023 (Import CSV async)
                         |
BE-009 (VoIP Adapter) ───┴──> BE-024 (Progressive Dialer)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-022 | ⬜ | Wymaga BE-002 ✅ + DB-011 ✅ |
| 2 | 🟢 BE-023 | ⬜ | Wymaga BE-022 + DB-011 ✅ |
| 3 | 🟢 FE-015 | ⬜ | Wymaga BE-022 (lub MSW) |
| 4 | BE-024 | ⬜ | Wymaga BE-009 + BE-022 (bloker: BE-009 musi być gotowe) |
| 5 | FE-016 | ⬜ | Wymaga FE-015 + BE-023 (lub MSW) |

---

### 3.5 Ścieżka – Baza Klientów (EPIC-09)

```
BE-025 (Customer CRUD) ──┬──> BE-026 (Import CSV async)
                         |
                         └──> BE-031 (RODO export/anonymize)
```

| Kolejność | Zadanie | Status | Warunek |
|-----------|---------|--------|---------|
| 1 | BE-025 | ⬜ | Wymaga BE-002 ✅ + DB-012 ✅. Indeks pg_trgm gotowy |
| 2 | 🟢 BE-026 | ⬜ | Wymaga BE-025 |
| 3 | 🟢 BE-031 | ⬜ | Wymaga BE-025 + BE-027 |
| 4 | FE-018 | ⬜ | Wymaga BE-025 (lub MSW) |
| 5 | 🟢 FE-019 | ⬜ | Wymaga FE-018 + BE-025, BE-027 (lub MSW) |
| 6 | 🟢 FE-020 | ⬜ | Wymaga FE-018 + BE-026 (lub MSW) |

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
| 1 | BE-027 | ⬜ | Wymaga BE-002 ✅ + DB-006 ✅ |
| 2 | 🟢 BE-028 | ⬜ | Wymaga BE-027 + DB-013 ✅ |
| 3 | 🟢 BE-029 | ⬜ | Wymaga BE-012 + BE-019 |
| 4 | 🟢 BE-030 | ⬜ | Wymaga BE-027 + DB-013 ✅ + DB-014 ✅ (schemat DW gotowy) |
| 5 | 🟢 FE-021 | ⬜ | Wymaga BE-029 (lub MSW WebSocket mock) |
| 6 | 🟢 FE-022 | ⬜ | Wymaga BE-028 (lub MSW) |

---

## 4. Rekomendacje

### 4.1 Zadania BE do realizacji jako pierwsze (odblokują najwięcej FE)

Poniższa kolejność realizacji BE maksymalizuje liczbę odblokowanych zadań FE przy minimalnej pracy:

| Priorytet | Zadanie BE | Odblokuje zadań FE | Uwagi |
|-----------|------------|-------------------|-------|
| ✅ 1 | BE-004 (Auth API) | FE-004 produkcyjnie | Ukończone |
| 🔴 2 | BE-025 (Customer API) | FE-018, FE-019, FE-011 | pg_trgm gotowy, koszt implementacji niski, wartość wysoka |
| 🔴 3 | BE-027 (Contact API) | FE-017, FE-019, FE-022 | Fundament raportowania i disposition codes |
| ✅ 4 | BE-006 (Tenant CRUD) | FE-006 ✅ | Ukończone – BE-007 odblokowane |
| 🟡 5 | BE-008 (User CRUD) | FE-008 | Odblokuje FE-008 i BE-019 (Routing Engine) |
| 🟡 6 | BE-020 (Queue API) | FE-024 | Krótkie zadanie (M), odblokuje konfigurację routingu |
| 🟡 7 | BE-022 (Campaign CRUD) | FE-015, FE-016 | Odblokuje cały moduł kampanii |
| 🔴 8 | BE-009 (VoIP Adapter) | FE-010 (Softphone) | Krytyczny bloker dla Agent Desktop – najtrudniejsze zadanie (XL), zacząć wcześnie |
| 🟡 9 | BE-012 (WebSocket hub) | FE-009 (Agent Desktop) | Wymaga BE-009, odblokuje core Agent Desktop |

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
| 🔴 Najwyższy | BE-009 (VoIP Adapter) | Blokuje: softphone, nagrywanie, CLI lookup, IVR, dialer – cały kanał telefoniczny |
| 🔴 Wysoki | BE-004 (Auth API) | Bez tego żadne środowisko poza dev z seedem nie jest użyteczne |
| 🔴 Wysoki | BE-012 (WebSocket hub) | Blokuje Agent Desktop (FE-009) i RT metrics (FE-021) |
| 🔴 Wysoki | FE-009 (Agent Desktop) | Blokuje 5 komponentów obsługi kontaktu (FE-010..FE-013, FE-017) |
| 🟡 Średni | BE-019 (Routing Engine) | Blokuje BE-029 (RT metrics) i BE-021 (wait time) |
| 🟡 Średni | BE-025 (Customer API) | Blokuje CLI lookup (BE-011), RODO (BE-031) i 3 widoki FE |

---

*Dokument generowany na podstawie TASKS-BACKEND.md, TASKS-FRONTEND.md i PROGRESS.md.*
*Aktualizować przy każdej zmianie statusu zadań w PROGRESS.md.*
