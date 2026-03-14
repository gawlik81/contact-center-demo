# PROGRESS.md
# Contact Center SaaS – Postęp prac

**Ostatnia aktualizacja:** 2026-03-14 (aktualizacja po FE-005)

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
| BE-004 | Auth API: login, logout, refresh, zmiana hasła | ⬜ | |
| BE-005 | Audit Log: zapis działań użytkowników | ⬜ | |
| BE-006 | Tenant CRUD API i limity zasobów | ⬜ | |
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
| FE-006 | Lista tenantów i formularz tworzenia tenanta | ⬜ | |
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
| Backend (BE) | 3/31 | 0 | 28 | 31 |
| Frontend (FE) | 5/24 | 0 | 19 | 24 |
| **RAZEM** | **27/74** | **0** | **47** | **74** |

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
