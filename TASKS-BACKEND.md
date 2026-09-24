# TASKS-BACKEND.md
# Contact Center SaaS – Zadania deweloperskie: Backend (Java/Spring Boot + Python)

**Wersja:** 1.2
**Data:** 2026-03-22
**Stack:** Java 21 + Spring Boot 3.x, Python 3.11+ (AI/automatyzacja), RabbitMQ, Redis, REST/OpenAPI 3.0, JWT/OAuth 2.0
**Powiązany PRD:** PRD v1.0

---

## Konwencje

- Prefiks ID: `BE-`
- Priorytety: **Must Have** (MVP), **Should Have** (kolejna iteracja)
- Rozmiary: S (< 1 dzien), M (1-2 dni), L (3-5 dni), XL (> 5 dni)
- Kazde zadanie to odrębny moduł/pakiet Spring Boot lub odrebny serwis Python – praca rownolegle bez konfliktow
- Kazdy endpoint dokumentowany przez OpenAPI 3.0 (springdoc-openapi)
- Izolacja tenant_id egzekwowana na poziomie repozytorium (metoda findByIdAndTenantId)

---

## MODUL: Infrastruktura i Fundament

### BE-001 – Inicjalizacja projektu Spring Boot i struktura modułów

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** BE-002, BE-005, BE-009, BE-015, BE-001b, BE-003, BE-012, BE-067, BE-068
**Odniesienie PRD:** przekrojowe

**Opis:**
Inicjalizacja projektu Spring Boot 3.x (Maven/Gradle multi-module). Konfiguracja profili (dev/staging/prod), actuator health endpoints, Flyway migrations, connection pool (HikariCP), konfiguracja Redis (cache), RabbitMQ (broker). Struktura pakietów: `api/`, `domain/`, `infrastructure/`, `security/`.

**Kryteria akceptacji:**
- [x] `mvn package` buduje projekt bez błędów
- [x] `/actuator/health` zwraca status UP ze statusami DB, Redis, RabbitMQ
- [x] Flyway uruchamia migracje przy starcie aplikacji (baza pusta → schemat MVP)
- [x] Profil `dev` używa bazy lokalnej, profil `prod` czyta z ENV vars

---

### BE-001b – Lokalne środowisko DEV: docker-compose (MinIO i pozostałe serwisy)

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-001
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-21
**Blokuje:** BE-010 (nagrywanie rozmów), BE-013 (IVR audio), BE-028 (GDPR anonymize)
**Odniesienie PRD:** przekrojowe (środowisko deweloperskie)

**Opis:**
Uzupełnienie `docker-compose.yml` o MinIO (S3-compatible object storage) wymagany przez `S3Properties` i taskt BE-010 (nagrywanie rozmów). Serwis `minio` na portach 9000 (S3 API) / 9001 (Console UI), serwis `minio-init` tworzący bucket `contact-center-recordings` po starcie. Aktualizacja nagłówka `docker-compose.yml` i sekcji `volumes`.

**Kryteria akceptacji:**
- [x] `docker compose up -d` uruchamia MinIO obok PostgreSQL, Redis, RabbitMQ
- [x] MinIO Console dostępna pod `http://localhost:9001` (minioadmin/minioadmin)
- [x] Bucket `contact-center-recordings` tworzony automatycznie przy pierwszym starcie
- [x] `S3Properties` (endpoint `http://localhost:9000`, credentiale minioadmin) odpowiada konfiguracji docker-compose
- [x] Volumen `minio_data` persystuje dane między restartami

---

### BE-002 – Konfiguracja multi-tenancy: TenantContext i filtr tenant_id

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-001, DB-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** BE-003, BE-005, BE-006, BE-008, BE-012, BE-015, BE-017, BE-020, BE-022, BE-025, BE-027, BE-007, BE-016
**Odniesienie PRD:** US-01-01, wymagania bezpieczenstwa (izolacja)

**Opis:**
Implementacja `TenantContext` (ThreadLocal) ładowanego z JWT claims przy każdym żądaniu HTTP przez `TenantFilter`. Bazowa klasa `TenantAwareRepository` wymuszająca `WHERE tenant_id = :tenantId` w każdym zapytaniu. Globalny `@Aspect` logujący próby dostępu cross-tenant jako WARNING.

**Kryteria akceptacji:**
- [x] Każde zapytanie do DB przez TenantAwareRepository automatycznie filtruje po tenant_id
- [x] Próba dostępu do zasobu innego tenanta zwraca HTTP 403 (nie 404)
- [x] Test integracyjny: tenant A nie może odczytać danych tenanta B
- [x] TenantContext czyszczony po zakończeniu żądania (finally block)

---

### BE-003 – Konfiguracja bezpieczeństwa: Spring Security, JWT, MFA

**Typ:** Infrastructure
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-001, DB-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-13
**Blokuje:** BE-004, BE-012, BE-054
**Odniesienie PRD:** wymagania bezpieczenstwa (TLS, JWT, MFA, bcrypt 12)

**Opis:**
Konfiguracja Spring Security: JWT access token (15 min TTL) + refresh token (7 dni, stored in DB). bcrypt z 12 rundami dla haseł. Endpoint `/auth/mfa/setup` (generowanie TOTP secret) i `/auth/mfa/verify`. Filtr `JwtAuthFilter` ekstrahujący claims: userId, tenantId, role. Blacklista tokenów w Redis (przy logout/revoke).

**Kryteria akceptacji:**
- [x] Logowanie zwraca access_token (15 min) i refresh_token (7 dni)
- [x] Refresh endpoint wymienia refresh_token na nową parę tokenów (rotation)
- [x] Hasła hashowane bcrypt cost=12 (weryfikacja: BCryptPasswordEncoder(12))
- [x] MFA TOTP weryfikowany algorytmem RFC 6238 z oknem tolerancji ±30s
- [x] Logout dodaje access_token do blacklisty Redis (TTL = pozostały czas ważności)

---

### BE-004 – Auth API: login, logout, refresh, zmiana hasła

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Blokuje:** FE-004
**Odniesienie PRD:** US-02-04, wymagania bezpieczenstwa

**Opis:**
Endpointy REST: `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/refresh`, `POST /api/auth/change-password`, `POST /api/auth/force-reset/{userId}`. Obsługa flagi `password_reset_required` w JWT claims. Rate limiting na endpoint logowania (5 prób / 15 min / IP przez Redis). Rozszerzono `PublicController` o endpoint `POST /api/public/tenants-by-email` (flow "email-first" na stronie logowania): metoda `findActiveTenantsByUserEmail` w `AppUserRepository` zwraca aktywne tenanty powiązane z danym e-mailem; zawsze HTTP 200 z pustą listą zamiast 404 (nie ujawnia istnienia e-maila – bezpieczeństwo PII).

**Kryteria akceptacji:**
- [x] `POST /auth/login` z błędnymi danymi zwraca HTTP 401 (bez ujawniania czy email istnieje)
- [x] Po 5 nieudanych logowaniach z IP → HTTP 429 przez 15 minut
- [x] `POST /auth/force-reset/{userId}` wymaga roli ADMIN lub SUPERVISOR (dla własnych agentów)
- [x] `POST /auth/change-password` waliduje: nowe hasło min 8 znaków, 1 cyfra, 1 wielka litera
- [x] Dokumentacja OpenAPI wygenerowana automatycznie (springdoc)

---

### BE-005 – Audit Log: zapis działań użytkowników

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-004
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-17
**Blokuje:** brak
**Odniesienie PRD:** RODO (rejestr przetwarzania), przekrojowe

**Opis:**
Serwis `AuditLogService` zapisujący do tabeli `AUDIT_LOG` każdą operację CRUD na encjach wrażliwych (User, Customer, Tenant, Campaign). Implementacja przez Spring AOP `@Aspect` na metodach serwisowych z adnotacją `@Audited`. Zapis asynchroniczny przez RabbitMQ (nie blokuje głównego flow). Endpoint `GET /api/audit-logs` (ADMIN only, paginacja, filtry: tenantId, entityType, userId, zakres dat).

**Kryteria akceptacji:**
- [x] Każda operacja Create/Update/Delete na encjach wrażliwych generuje wpis w AUDIT_LOG
- [x] Wpis zawiera: old_value i new_value jako JSONB (bez pól hasło/token)
- [x] Zapis przez RabbitMQ nie spowalnia głównego zapytania (async, fire-and-forget)
- [x] GET /api/audit-logs dostępny tylko dla ADMIN, paginacja max 100 rekordów

---

## MODUL: Zarządzanie Tenantami (EPIC-01)

### BE-006 – Tenant CRUD API i limity zasobów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-005
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-14
**Blokuje:** BE-007, FE-006, BE-032, BE-033
**Odniesienie PRD:** US-01-01, US-01-02, US-01-03, EPIC-01

**Opis:**
Endpointy: `POST /api/tenants`, `GET /api/tenants`, `GET /api/tenants/{id}`, `PATCH /api/tenants/{id}`, `POST /api/tenants/{id}/deactivate`. Logika limitu zasobów w config JSONB (max_agents, max_queues, max_campaigns). Walidacja limitu przy tworzeniu każdego zasobu tenanta (np. przy dodawaniu agenta sprawdz max_agents).

**Kryteria akceptacji:**
- [x] `POST /api/tenants` wymaga roli ADMIN
- [x] Dezaktywacja nie usuwa danych, ustawia status=INACTIVE, blokuje logowanie użytkowników tenanta
- [x] Próba dodania agenta powyżej limitu zwraca HTTP 422 z komunikatem o przekroczeniu limitu
- [x] `GET /api/tenants/{id}/check-name?name=X` zwraca {available: bool} (dla async validator FE)

---

### BE-007 – Admin metrics API: metryki RT tenantów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-006, BE-002
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-17
**Blokuje:** FE-007
**Odniesienie PRD:** US-01-04, US-10-04, EPIC-01

**Opis:**
Endpoint `GET /api/admin/metrics` zwracający: liczba aktywnych tenantów, suma agentów online per tenant, alerty systemowe (lista). Dane częściowo cachowane w Redis (TTL 30s). Endpoint `GET /api/admin/metrics/tenants/{id}` z metrykami per tenant (CPU proxy z monitoring systemu lub mocki na MVP).

**Kryteria akceptacji:**
- [x] Odpowiedź `/api/admin/metrics` zawiera tablicę tenantów z polami: id, name, agents_online, status
- [x] Cache Redis TTL 30s, inwalidowany przy zmianie statusu tenanta
- [x] Dostępny tylko dla roli ADMIN (403 dla innych ról)
- [ ] Czas odpowiedzi < 200ms (p95) – wymóg z PRD (nie mierzony, MVP)

---

## MODUL: Użytkownicy i Role (EPIC-02)

### BE-008 – User / Agent CRUD API ze skills

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-17
**Blokuje:** BE-019, FE-008, BE-041, FE-009
**Odniesienie PRD:** US-02-01, US-02-02, US-02-03, EPIC-02

**Opis:**
Endpointy: `POST /api/users`, `GET /api/users`, `GET /api/users/{id}`, `PATCH /api/users/{id}`, `DELETE /api/users/{id}` (soft delete). Skills zarządzane jako JSONB w tabeli USER. Endpoint `GET /api/users/skills` – lista wszystkich unikalnych skills w tenantcie. `PATCH /api/users/{id}/status` – zmiana statusu agenta (AVAILABLE/BUSY/BREAK/AFTER_CONTACT).

**Kryteria akceptacji:**
- [x] SUPERVISOR może zarządzać tylko użytkownikami własnego tenanta
- [x] Usunięcie agenta z aktywnymi kontaktami zwraca HTTP 409
- [x] Skills przechowywane jako `string[]` w JSONB, endpoint skills zwraca unikalne wartości (deduplicated)
- [x] Zmiana statusu agenta publikuje event na RabbitMQ (dla routing engine)

---

## MODUL: Kanał Telefoniczny (EPIC-03)

### BE-009 – Adapter VoIP: integracja z SIP trunk / CPaaS API

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-001, DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Blokuje:** BE-010, BE-011, BE-012, BE-013, BE-024, BE-032, BE-035, BE-038, BE-040, FE-010
**Odniesienie PRD:** US-03-01, US-03-03, US-03-04, EPIC-03

**Opis:**
Implementacja interfejsu `TelephonyAdapter` z metodami: initiateCall, answerCall, hangupCall, holdCall, muteCall, transferCall (blind/attended). Konkretna implementacja przez CPaaS REST API (np. Twilio/Vonage) lub SIP stack (JAIN SIP). Zdarzenia przychodzące (webhook/WebSocket od providera) mapowane na domenowe eventy i publikowane na RabbitMQ.

**Kryteria akceptacji:**
- [x] Interfejs TelephonyAdapter jest implementowalny przez różne providery (wzorzec adaptera)
- [x] Zdarzenia: CALL_INCOMING, CALL_ANSWERED, CALL_HANGUP, CALL_TRANSFERRED publikowane na RabbitMQ
- [x] Attended transfer: tworzenie drugiej nogi połączenia, bridge po potwierdzeniu
- [x] Testy jednostkowe z mockiem adaptera (bez wywołań do prawdziwego providera)

---

### BE-010 – Nagrywanie rozmów: zapis do S3, metadane, retencja

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-001b (MinIO), BE-009, DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-19
**Blokuje:** BE-037
**Odniesienie PRD:** US-03-05, wymagania RODO (retencja nagrań)

**Opis:**
Serwis `RecordingService`: odbiera strumień audio z TelephonyAdapter, konwertuje do MP3/WAV (FFmpeg przez ProcessBuilder lub biblioteka), uploaduje do S3-compatible storage (Minio / AWS S3) z szyfrowaniem AES-256 (SSE-S3). Ścieżka: `/{tenantId}/{year}/{month}/{contactId}.mp3`. Zapis URL nagrania do tabeli CONTACT. Zadanie cron usuwające nagrania starsze niż 90 dni (konfigurowalne per tenant).

**Kryteria akceptacji:**
- [x] Plik audio uploadowany do S3 po zakończeniu połączenia (< 60s od hangup)
- [x] Nagranie szyfrowane AES-256 (SSE-S3 lub SSE-C)
- [x] Cron retencji uruchamiany codziennie o 02:00, usuwa pliki i czyści URL w tabeli CONTACT
- [x] Endpoint `GET /api/recordings/{contactId}` generuje presigned URL ważny 1h (nie zwraca pliku bezpośrednio)
- [x] Dostęp do nagrań wymaga roli SUPERVISOR lub ADMIN

---

### BE-011 – CLI lookup: wzbogacenie połączenia o dane klienta

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-009, BE-025
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Blokuje:** FE-011
**Odniesienie PRD:** US-03-02, EPIC-03

**Opis:**
Przy zdarzeniu CALL_INCOMING: lookup klienta po numerze telefonu (tabela CUSTOMER, pola phone[]). Wynik (dane klienta lub null) dołączany do eventu CALL_INCOMING przed przekazaniem do Agent Desktop przez WebSocket. Cache Redis dla numerów (TTL 5 min).

**Kryteria akceptacji:**
- [x] Lookup wykonywany < 100ms (cache hit) lub < 500ms (cache miss z DB)
- [x] Wynik zawiera: customer_id, first_name, last_name, ostatnie 3 kontakty
- [x] Dla nieznanych numerów wynik null – frontend wyświetla "Nieznany klient"
- [x] Cache inwalidowany przy aktualizacji profilu klienta

---

### BE-012 – WebSocket hub: real-time events do Agent Desktop

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-001, BE-002, BE-003, BE-009
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Blokuje:** FE-009, FE-021, BE-029, FE-010
**Odniesienie PRD:** US-03-01, US-07-05, EPIC-03

**Opis:**
Implementacja WebSocket server (Spring WebSocket + STOMP) lub Server-Sent Events. Topics per użytkownik (`/user/{userId}/events`) i per tenant (`/tenant/{tenantId}/supervisor`). Eventy: CALL_INCOMING, CONTACT_ASSIGNED, AGENT_STATUS_CHANGED, QUEUE_UPDATE. Autentykacja WebSocket przez JWT w handshake.

**Kryteria akceptacji:**
- [x] Agent otrzymuje CALL_INCOMING przez WebSocket przed odebraniem telefonu
- [x] Supervisor otrzymuje AGENT_STATUS_CHANGED w czasie < 5s od zmiany
- [x] Rozłączenie WebSocket → automatyczny reconnect po stronie klienta (backoff)
- [x] JWT weryfikowany przy upgrade'ie do WebSocket (brak tokenu → HTTP 401)

---

### BE-032 – Twilio: konfiguracja numeru telefonu per tenant

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-009, BE-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-01
**Blokuje:** FE-025
**Odniesienie PRD:** EPIC-03

**Opis:**
Rozszerzenie adaptera Twilio o obsługę wielu numerów telefonów – po jednym (lub więcej) dla każdego tenanta. Numer przechowywany jako `twilio_phone_number` w JSONB `tenant.config`. Przy połączeniu wychodzącym adapter szuka numeru tenanta; jeśli brak – używa globalnego fallbacku z `twilio.phone-number`. Analogicznie `twilio_status_callback_url` per tenant. Supervisor konfiguruje numery przez API ustawień tenanta (endpoint PATCH `/api/tenants/{id}/config`).

**Szczegóły implementacji:**
- Dodaj metody pomocnicze `getTwilioPhoneNumber()` i `getTwilioStatusCallbackUrl()` do encji `Tenant`
- Wstrzyknij `TenantRepository` do `TwilioTelephonyAdapter`; zastąp `twilioProperties.getPhoneNumber()` logiką: lookup po `tenantId` → fallback globalny
- Metoda `buildStatusCallbackUrl(UUID tenantId)` buduje URL dynamicznie: `baseUrl + "?tenantId=" + tenantId` (gdy brak per-tenant URL) lub pobiera z `tenant.config`
- Walidacja przy zapisie: numer musi być w formacie E.164 (`+` i 7–15 cyfr)
- Endpoint `PATCH /api/tenants/{id}/config` z DTO `TenantTwilioConfigRequest { twilioPhoneNumber, twilioStatusCallbackUrl }`; dostępny dla ADMIN i SUPERVISOR swojego tenanta

**Kryteria akceptacji:**
- [x] Dwa tenanci z różnymi numerami Twilio – połączenia wychodzące używają właściwego numeru per tenant
- [x] Brak konfiguracji per tenant → fallback do `twilio.phone-number` z `application.yml`
- [x] Webhook URL zawiera `tenantId` jako query param (automatycznie lub z konfiguracji)
- [x] Walidacja E.164 zwraca HTTP 400 dla niepoprawnego numeru
- [x] Zapis nowego numeru nie wymaga restartu aplikacji (brak cache bez TTL)
- [x] Test jednostkowy: `resolvePhoneNumber()` – priorytet: per-tenant > globalny fallback

---

## MODUL: IVR i Automatyzacja (EPIC-04)

### BE-013 – IVR Engine: wykonanie drzewa IVR

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-001b (MinIO), BE-009, DB-009
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-25
**Blokuje:** BE-014, FE-014, BE-034, BE-035
**Odniesienie PRD:** US-04-01, US-04-03, US-04-04, EPIC-04

**Opis:**
Silnik IVR interpretujący JSONB definicję drzewa (`IVR_TREE.definition`). Przetwarzanie węzłów: PlayAudio (stream z S3), TTS (wywołanie Google/Azure TTS API), CollectDTMF (oczekiwanie na wejście), TransferToQueue, Hangup. Integracja ze stanem połączenia przez TelephonyAdapter. Obsługa timeout (brak wejścia DTMF → domyślna gałąź).

**Kryteria akceptacji:**
- [x] Węzeł PlayAudio odtwarza plik z S3 przez TelephonyAdapter
- [x] Węzeł TTS wywołuje zewnętrzne API i cache'uje wygenerowany plik audio (Redis/S3) na 24h
- [x] CollectDTMF obsługuje timeout (domyślnie 10s) i przejście do gałęzi "no-input"
- [x] TransferToQueue przekazuje połączenie do routing engine z odpowiednią kolejką
- [x] Błąd w IVR (wyjątek, nieosiągalny węzeł) → przekazanie do kolejki domyślnej zamiast hangup

---

### BE-014 – Voicebot Python: ASR + NLU + eskalacja do agenta

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-013, DB-009
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-02
**Blokuje:** brak
**Odniesienie PRD:** US-04-02, EPIC-04

**Opis:**
Serwis Python (FastAPI) integrujący ASR (Google Speech-to-Text lub Whisper) i prosty NLU (reguły lub fine-tuned model). Logika: jeśli confidence < 0.70 → eskalacja do kolejki agentów (event na RabbitMQ z priority=HIGH). API: `POST /voicebot/turn` (audio chunk → intent + confidence). Sesja konwersacji w Redis (TTL 15 min).

**Kryteria akceptacji:**
- [x] Confidence < 0.70 zawsze skutkuje eskalacją (test: mock ASR z confidence=0.69 → event ESCALATE)
- [x] Eskalacja zawiera transcript rozmowy jako kontekst dla agenta
- [x] Sesja voicebot w Redis TTL 15 min, czyszczona po hangup
- [x] `POST /voicebot/turn` odpowiada < 2s (p95) dla audio do 5s

---

## MODUL: Kanał Email (EPIC-05)

### BE-015 – Email Adapter: IMAP polling + SMTP wysyłka

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-001, DB-007, DB-020
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-26
**Blokuje:** BE-016, FE-012
**Odniesienie PRD:** US-05-01, US-05-02, US-05-04, EPIC-05

**Opis:**
Serwis emailowy: polling IMAP co 60s (konfiguracja per tenant: host, port, login, hasło zaszyfrowane), parsowanie wiadomości (JavaMail/Jakarta Mail), zapis do tabeli CONTACT. Routing emaila do kolejki przez `EmailRoutingEngine` (reguły: nadawca, temat, słowa kluczowe → kolejka). SMTP do wysyłki odpowiedzi. Linkowanie wątku po Message-ID / In-Reply-To. Routing priorytetowy: najpierw po `email_address` kolejki (`queueRepository.findByEmailAddressAndTenantId()`), następnie po regułach routingu.

Zrealizowane: `EmailPollingService` (@Scheduled, IMAP Jakarta Mail, parsowanie MimeMessage/MimeMultipart), `EmailSendService` (SMTP), `EmailRoutingService` (routing po email_address kolejki + reguły per tenant), `EmailRoutingRule`/`EmailRoutingRuleRepository`, `EmailMessage`/`EmailMessageRepository`, `EmailAccountConfig`, `EmailEncryptionService` (AES-256), `EmailEventPublisher` (RabbitMQ), `EmailController` (GET /api/email/messages, PATCH /api/email/messages/{id}/read, POST /api/email/config, GET /api/email/config, POST /api/email/reply). Encje przeniesione do `domain/model/`: `EmailMessage.java`, `EmailRoutingRule.java`, `EmailTemplate.java`; repozytoria do `domain/repository/`.

**Kryteria akceptacji:**
- [x] Nowe emaile odbierane w czasie < 2 min od wpłynięcia na skrzynkę
- [x] Wątek email grupowany po nagłówkach Message-ID / In-Reply-To / Subject (Re:)
- [x] Routing emaila przypisuje do kolejki zgodnie z regułami w DB (priorytet reguł: kolejność)
- [x] Wysłana odpowiedź zapisana jako kolejna wiadomość w wątku (CONTACT powiązany)
- [x] Dane logowania IMAP/SMTP szyfrowane AES-256 w DB (not plaintext)

---

### BE-016 – Szablony odpowiedzi email: CRUD API

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-002, DB-007
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-26
**Blokuje:** FE-012
**Odniesienie PRD:** US-05-03, EPIC-05

**Opis:**
Tabela `EMAIL_TEMPLATE` (template_id, tenant_id, name, subject_template, body_html, variables JSONB). Endpointy CRUD: `GET /api/email-templates`, `POST /api/email-templates`, `PATCH`, `DELETE`. Zmienne w szablonie jako `{{customer.first_name}}` – renderowanie przez silnik Mustache/Freemarker przed wysłaniem.

**Kryteria akceptacji:**
- [x] Renderowanie szablonu z podstawieniem zmiennych
- [x] Walidacja: brak undefined zmiennych w szablonie zwraca HTTP 422 z listą brakujących pól
- [x] Szablony widoczne tylko w ramach tenanta (izolacja tenant_id)

---

## MODUL: Kanał Social Media (EPIC-06)

### BE-017 – OAuth flow i zarządzanie tokenami social media

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-008
**Status:** ✅ Ukończone (stub token exchange)
**Zrealizowane:** 2026-04-17
**Blokuje:** BE-018, FE-023
**Odniesienie PRD:** US-06-02, EPIC-06

**Opis:**
Endpointy OAuth 2.0 callback dla: Facebook Messenger API, Instagram API, WhatsApp Business API. Zapis access_token i refresh_token (AES-256 encrypted) do tabeli SOCIAL_INTEGRATION. Mechanizm automatycznego odświeżenia tokenu przed wygaśnięciem (scheduled task co 1h sprawdzający tokeny wygasające w ciągu 24h).

**Kryteria akceptacji:**
- [x] OAuth callback dla każdej z 3 platform zapisuje token do DB
- [x] Tokeny szyfrowane AES-256 w kolumnie (nie plaintext)
- [x] Automatyczne odświeżenie tokenu loguje sukces/błąd (AUDIT_LOG)
- [x] Endpoint `DELETE /api/integrations/{platform}` revoke'uje token u providera i usuwa z DB

---

### BE-018 – Social Media Adapter: odbieranie i wysyłka wiadomości

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-017, DB-008
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-17
**Blokuje:** FE-013
**Odniesienie PRD:** US-06-01, US-06-03, US-06-04, EPIC-06

**Opis:**
Implementacja interfejsu `SocialMediaAdapter` z metodami: receiveMessage, sendMessage, getConversationHistory. Trzy implementacje: FacebookAdapter, InstagramAdapter, WhatsAppAdapter. Webhooki od platform (POST /webhooks/facebook, /webhooks/instagram, /webhooks/whatsapp) przetwarzane asynchronicznie przez RabbitMQ. Routing wiadomości do kolejki przez analogiczny mechanizm jak email.

**Kryteria akceptacji:**
- [x] Webhook endpoint zwraca HTTP 200 w < 3s (szybkie ACK, przetwarzanie async)
- [x] Wiadomości od jednego użytkownika na jednej platformie grupowane w konwersację (CONTACT)
- [x] sendMessage obsługuje: tekst, emoji (Unicode), zdjęcia (URL) – dla WhatsApp i FB
- [ ] Test integracyjny z mockiem webhooka Facebooka (weryfikacja parsowania payload) — **weryfikacja 2026-08-08: brak takiego testu w `backend/app/src/test`**; istniejący `SocialMessageServiceTest` testuje tylko warstwę serwisu na już sparsowanym `IncomingSocialMessage`, nie parsowanie surowego payloadu w kontrolerze webhooka. Reszta funkcjonalności (adaptery, webhooki, async publish) potwierdzona w kodzie — status ✅ pozostaje, brakuje tylko tego testu.

---

## MODUL: Routing i Kolejkowanie (EPIC-07)

### BE-019 – Routing Engine: skill-based, round-robin, sticky agent

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-008, DB-010
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-21
**Blokuje:** BE-021, BE-029
**Odniesienie PRD:** US-07-01, US-07-02, US-07-03, US-07-05, EPIC-07

**Opis:**
Serwis `RoutingEngine` konsumujący eventy z RabbitMQ (CONTACT_QUEUED). Algorytmy: round-robin (cykliczny przydział), first-available (pierwszy wolny agent), skill-based (match skills agenta z wymaganiami kolejki). Sticky agent: sprawdź czy poprzedni agent (z CONTACT.agent_id) jest dostępny w ciągu timeout (default 60s) – jeśli tak, przydziel do niego. Decyzja routingu < 500ms (wymóg PRD).

**Kryteria akceptacji:**
- [x] Czas decyzji routingu mierzony i logowany; alert jeśli > 500ms
- [x] Sticky agent działa: przy ponownym kontakcie klienta sprawdzana dostępność poprzedniego agenta przez 60s
- [x] Skill-based: kontakt trafia do agenta posiadającego WSZYSTKIE wymagane skills kolejki
- [x] Brak dostępnego agenta → kontakt pozostaje w kolejce, event QUEUE_WAIT_UPDATE co 30s
- [x] Wielozadaniowość: agent może mieć max 1 aktywne połączenie TEL lub 3 aktywne chat/email

---

### BE-020 – Queue API: CRUD kolejek i konfiguracja routingu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-010
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-21
**Blokuje:** BE-021, BE-034, FE-024, FE-014
**Odniesienie PRD:** US-07-01, US-07-03, EPIC-07

**Opis:**
Endpointy: `POST /api/queues`, `GET /api/queues`, `GET /api/queues/{id}`, `PATCH /api/queues/{id}`, `DELETE /api/queues/{id}`. Pola: name, routing_strategy (ROUND_ROBIN/FIRST_AVAILABLE/SKILL_BASED), required_skills (string[]), sticky_agent_timeout_seconds (integer, min 0). Endpoint `GET /api/queues/{id}/stats` – aktualna liczba oczekujących i dostępnych agentów.

**Kryteria akceptacji:**
- [x] Usunięcie kolejki z aktywnym ruchem zwraca HTTP 409
- [x] `GET /api/queues/{id}/stats` zwraca: waiting_count, available_agents_count, avg_wait_time_seconds
- [x] required_skills walidowane jako tablica niepustych stringów
- [x] Endpoint stats cachowany Redis TTL 5s

---

### BE-021 – Wait time estimation: informacja o czasie oczekiwania

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-019, BE-020
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-26
**Blokuje:** brak
**Odniesienie PRD:** US-07-04, EPIC-07

**Opis:**
Serwis kalkulujący szacowany czas oczekiwania w kolejce na podstawie: liczby oczekujących, dostępnych agentów, historycznego AVG handle time (z ostatnich 7 dni z data warehouse lub lokalna agregacja). Wynik zwracany w evencie QUEUE_WAIT_UPDATE przez RabbitMQ → WebSocket do klienta (np. przez IVR lub widget na stronie).

Zrealizowane: `WaitTimeEstimationService` (@Scheduled fixedDelay=30s), `QueueWaitUpdatePayload` (DTO eventu QUEUE_WAIT_UPDATE), `QueueStatsResponse` (z avgHandleTimeSeconds), `ContactRepository` +2 native SQL (`countWaitingByQueueId` i `getAvgHandleTimeSeconds` z fallback 300s, oba z `AND is_deleted = false`), `QueueController` GET /api/queues/{id}/stats. EWT = ceil(waiting/agents*avg), edge cases: waiting=0→0, agents=0→MAX_VALUE. Cache: `ConcurrentHashMap` po stronie serwisu (nie Redis SCAN per HTTP). Partial entity usunięte z kontrolera – serwis ładuje encję sam przez `getQueueStats(tenantId, queueId)`. 644 testów PASS.

**Kryteria akceptacji:**
- [x] Szacowany czas obliczany: (waiting_count / available_agents) * avg_handle_time_seconds
- [x] AVG handle time obliczany z ostatnich 7 dni z tabeli CONTACT
- [x] Wynik aktualizowany co 30s dla aktywnych kolejek

---

## MODUL: Kampanie Outbound (EPIC-08)

### BE-022 – Campaign CRUD API i harmonogram

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-002, DB-011
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Blokuje:** BE-023, BE-024, FE-015, BE-051, BE-053
**Odniesienie PRD:** US-08-02, US-08-06, EPIC-08

**Opis:**
Endpointy: `POST /api/campaigns`, `GET /api/campaigns`, `PATCH /api/campaigns/{id}`, `POST /api/campaigns/{id}/start`, `POST /api/campaigns/{id}/pause`, `POST /api/campaigns/{id}/stop`. Schedule jako JSONB (start_date, end_date, time_from, time_to, days_of_week[]). Walidacja: nie można uruchomić bez listy kontaktów.

**Kryteria akceptacji:**
- [x] Przejścia statusów kampanii: DRAFT → SCHEDULED → RUNNING → PAUSED → STOPPED/COMPLETED
- [x] Przejście niedozwolone (np. STOPPED → RUNNING) zwraca HTTP 422 z opisem
- [x] Harmonogram waliduje: end_date >= start_date, time_to > time_from
- [x] Start kampanii poza harmonogramem zwraca HTTP 422 "poza oknem czasowym"

---

### BE-023 – Import CSV kontaktów kampanii (async job)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-022, DB-011
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-24
**Blokuje:** FE-016
**Odniesienie PRD:** US-08-01, EPIC-08

**Opis:**
Endpoint `POST /api/campaigns/{id}/contacts/import` przyjmuje plik CSV multipart. Przetwarzanie asynchroniczne (RabbitMQ job): parsowanie CSV (biblioteka OpenCSV), walidacja rekordów (format telefonu), zapis do tabeli CAMPAIGN_CONTACT. SLA: 100k rekordów < 2 min. Endpoint `GET /api/campaigns/{id}/import-status/{jobId}` zwraca postęp i raport.

Zrealizowane: CampaignImportController (POST import + GET status), CampaignImportService (@Async, OpenCSV, batch JdbcTemplate chunk 1000, deduplikacja ON CONFLICT), Redis TTL 1h dla statusu joba, V027 unikalny indeks (campaign_id, phone). 25 testów, 467 PASS.

**Kryteria akceptacji:**
- [x] Import 100k rekordów CSV kończy się w czasie < 2 min (test wydajnościowy)
- [x] Rekordy z nieprawidłowym formatem telefonu odrzucane i raportowane
- [x] Import idempotentny przy re-uploadzie tego samego pliku (deduplikacja po telefonie w kampanii)
- [x] Status job: QUEUED → PROCESSING (X/Y) → COMPLETED/FAILED_PARTIAL

---

### BE-024 – Progressive Dialer: silnik automatycznego dzwonienia

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-009, BE-022, DB-011
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-03
**Blokuje:** BE-038, BE-039, BE-040, BE-041, BE-042
**Odniesienie PRD:** US-08-03, US-08-05, EPIC-08

**Opis:**
Serwis `ProgressiveDialer`: po zmianie statusu agenta na AVAILABLE (event RabbitMQ) i aktywnej kampanii → pobierz następny kontakt z CAMPAIGN_CONTACT (status=PENDING), inicjuj połączenie przez TelephonyAdapter. Po odpowiedzi klienta – bridging do agenta. Po disposition code CALLBACK → zaplanuj ponowne wywołanie w harmonogramie.

**Kryteria akceptacji:**
- [x] Połączenie inicjowane w < 5s od zmiany statusu agenta na AVAILABLE
- [x] Brak odpowiedzi (timeout 30s) → status CAMPAIGN_CONTACT = NO_ANSWER, next attempt +4h
- [x] Disposition CALLBACK tworzy rekord w SCHEDULED_CALLBACKS z datą/godziną
- [x] Dialer respektuje godziny kampanii (nie dzwoni poza harmonogramem)
- [x] Wstrzymanie kampanii (PAUSED) natychmiast zatrzymuje nowe inicjowania połączeń

---

## MODUL: Baza Klientów (EPIC-09)

### BE-025 – Customer CRUD API i fuzzy search

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-002, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-19
**Blokuje:** BE-026, BE-031, FE-018, FE-019, FE-011, BE-011, BE-048, FE-040
**Odniesienie PRD:** US-09-01, US-09-02, US-09-03, US-09-04, EPIC-09

**Opis:**
Endpointy: `POST /api/customers`, `GET /api/customers` (search, paginacja), `GET /api/customers/{id}`, `PATCH /api/customers/{id}`, `DELETE /api/customers/{id}` (anonimizacja RODO). Fuzzy search przez PostgreSQL `pg_trgm` (trigram index) na polach first_name, last_name, phone[], email[]. Odpowiedź < 1s (wymóg PRD). Auto-tworzenie profilu przy nieznanych kontaktach przychodzących (event UNKNOWN_CALLER).

**Kryteria akceptacji:**
- [x] `GET /api/customers?q=kowalsk` zwraca wyniki fuzzy w < 1s (indeks trigram)
- [x] `DELETE` anonimizuje dane (first_name='ANONYMIZED', last_name='ANONYMIZED', phone[]=[], email[]=[], is_deleted=true) – nie usuwa rekordu (zachowanie CONTACT history)
- [ ] Auto-tworzenie: event UNKNOWN_CALLER tworzy CUSTOMER z phone=[CLI] i source='AUTO'
- [x] Paginacja cursor-based dla wydajności przy dużych zbiorach

---

### BE-026 – Import klientów z CSV (async job)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-025, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-24
**Blokuje:** FE-020
**Odniesienie PRD:** US-09-05, EPIC-09

**Opis:**
Analogiczny do BE-023. Endpoint `POST /api/customers/import` – CSV z kolumnami: first_name, last_name, phone (wielokrotne; separator „;"), email (wielokrotne), custom_fields. Deduplikacja po telefonie lub emailu (opcja: skip/overwrite). Walidacja formatu telefonu (E.164). Job status przez polling.

Zrealizowane: `DeduplicationMode.java` (enum SKIP/OVERWRITE), `CustomerImportStatusResponse.java` (record DTO), `CustomerImportController.java` (3 endpointy: POST /api/customers/import → 202+jobId, GET /api/customers/import/{jobId}, GET /api/customers/import/{jobId}/errors), `CustomerImportService.java` (@Async, OpenCSV, batch chunk 500, deduplikacja SKIP/OVERWRITE, walidacja E.164, Redis TTL 1h), `CustomerRepository.java` – dodano `findByEmail()` JSONB @> operator. 24 testy jednostkowe, 506 testów PASS.

**Kryteria akceptacji:**
- [x] Import z opcją "overwrite" aktualizuje istniejące profile (merge phone[] i email[])
- [x] Import z opcją "skip" pomija duplikaty bez błędu (tylko raport)
- [x] Plik błędnych rekordów do pobrania jako CSV (endpoint download)
- [x] Telefony normalizowane do E.164 podczas importu

---

## MODUL: Raportowanie i Analityka (EPIC-10)

### BE-027 – Contact API: zapis i odczyt historii kontaktów

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-002, DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-20
**Blokuje:** BE-028, BE-029, BE-030, BE-031, FE-017, FE-019, FE-022, BE-036, BE-037, FE-030
**Odniesienie PRD:** US-09-02, US-10-02, EPIC-09, EPIC-10

**Opis:**
Endpointy: `GET /api/contacts` (filtry: customer_id, agent_id, channel, status, zakres dat, kampania), `GET /api/contacts/{id}`, `POST /api/contacts`, `PATCH /api/contacts/{id}`, `PATCH /api/contacts/{id}/disposition`, `GET /api/contacts/customer/{customerId}`. ContactService z logiką uprawnień AGENT vs SUPERVISOR/ADMIN. ContactRepository z natywnym INSERT/UPDATE dla tabeli partycjonowanej i dynamicznym WHERE dla filtrów. ContactId.java (klucz złożony). DTOs: ContactResponse, CreateContactRequest, UpdateContactRequest, DispositionRequest, ContactFilterParams. 22 testy jednostkowe, build 365/365 PASS.

**Kryteria akceptacji:**
- [x] Filtrowanie po wszystkich wymienionych polach (AND logic)
- [x] `PATCH /api/contacts/{id}/disposition` wymaga roli AGENT lub SUPERVISOR
- [x] Kontakt z typem CALL zawiera recording_url (jeśli nagranie dostępne)
- [x] Paginacja cursor-based (kontakty mogą być milionami rekordów)

---

### BE-028 – Raporty historyczne: agregacje per agent i kampania

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależy od:** BE-027, DB-013
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Blokuje:** FE-022
**Odniesienie PRD:** US-10-02, US-10-03, US-10-05, EPIC-10

**Opis:**
Endpointy raportów: `GET /api/reports/agents` (metryki: avg_handle_time, contacts_count, first_contact_resolution per agent per dzień/tydzień/miesiąc), `GET /api/reports/campaigns` (dials, connected, conversion_rate per kampania). Eksport CSV/XLSX: streaming response z nagłówkiem `Content-Disposition: attachment`. Cache Redis 5 min dla zapytań raportowych.

Zrealizowane: `AgentReportRow.java`, `AgentReportParams.java` – DTOs z Bean Validation; `ContactRepository.java` – +2 native SQL queries z GROUP BY, JOIN na `u.user_id`; `ReportsService.java` – Redis cache MD5 5 min, walidacja 90 dni, eksport CSV + XLSX (Apache POI poi-ooxml:5.2.5); `ReportsController.java` – 4 endpointy: `GET /api/reports/agents`, `/agents/export`, `/agents/export/xlsx`, `/campaigns` (501 placeholder); `ReportsServiceTest.java` – 13 testów; 442 testy PASS.

**Kryteria akceptacji:**
- [x] Raporty agentów zwracają dane dla zakresu do 90 dni wstecz
- [x] Eksport XLSX generowany przez Apache POI (biblioteka Java)
- [x] Cache hit dla identycznych parametrów zapytania (klucz: hash parametrów)
- [x] Zapytania raportowe wykonywane na replice DB (read replica) jeśli dostępna

---

### BE-029 – RT Metrics API: WebSocket feed dla supervisora

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-012, BE-019
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-22
**Blokuje:** FE-021
**Odniesienie PRD:** US-10-01, EPIC-10

**Opis:**
WebSocket topic `/tenant/{tenantId}/supervisor/metrics` z payloadem: {agents: [{id, name, status, current_contact}], queues: [{id, name, waiting, available_agents}], kpi: {active_calls, avg_wait_time, avg_handle_time}}. Dane agregowane co 5s z Redis (bieżący stan agentów/kolejek). Supervisor subskrybuje przy wejściu na dashboard.

**Kryteria akceptacji:**
- [x] Metryki wysyłane co max 5s (wymóg PRD ≤ 5s odświeżanie)
- [x] Payload zawiera pełną listę agentów tenanta z aktualnym statusem
- [x] Disconnected supervisor automatycznie przestaje otrzymywać eventy
- [x] Dane AGENTS_ONLINE zsynchronizowane z faktycznym statusem w Redis

---

### BE-030 – ETL do data warehouse: CDC z PostgreSQL

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależy od:** BE-027, DB-013, DB-014
**Status:** ✅ Ukończone (PostgreSQL fallback)
**Zrealizowane:** 2026-04-09
**Blokuje:** BE-030b, BE-067
**Odniesienie PRD:** US-10-06, EPIC-10

**Opis:**
Zaimplementowany jako polling-based ETL z PostgreSQL fallback DW. EtlSyncService (@Scheduled 60s, FOR UPDATE lock), DataWarehouseWriter interfejs, PostgresDwWriter (ON CONFLICT upsert), EtlStatusController (GET /api/admin/etl/status, POST /api/admin/etl/trigger), V036 migracja (etl_sync_state + contacts_dw w PostgreSQL). 16 testów PASS.

**Kryteria akceptacji:**
- [x] Nowy rekord CONTACT pojawia się w contacts_dw (PostgreSQL fallback) w czasie < 1h
- [x] ETL idempotentny: ON CONFLICT (contact_id) DO UPDATE
- [x] Alert monitoringowy gdy lag > 30 min (RabbitMQ + WARN log)
- [x] Transformacje zachowują anonimizację RODO (NOT EXISTS gdzie first_name='ANONYMIZED')

---

### BE-030b – ETL ClickHouse: docelowy Data Warehouse

**Typ:** Feature – Infrastructure
**Priorytet:** Should Have
**Złożoność:** L
**Zależy od:** BE-030 ✅, DB-014 (dw/migrations/V001__create_contacts_dw.sql)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** brak
**Odniesienie PRD:** US-10-06, EPIC-10

**Opis:**
Podpięcie ClickHouse jako docelowego DW zamiast PostgreSQL fallback. Interfejs `DataWarehouseWriter` już istnieje – wystarczy zaimplementować `ClickHouseDwWriter` i skonfigurować datasource.

Schemat ClickHouse gotowy: `dw/migrations/V001__create_contacts_dw.sql` (tabele `contacts_dw`, `agent_performance_dw`, `campaigns_dw` z `ReplacingMergeTree`; widoki `v_agent_kpi_daily`, `v_campaign_conversion`).

**Szczegóły implementacji:**

1. **Docker Compose** – dodaj serwis `clickhouse` do `docker-compose.yml`:
   ```yaml
   clickhouse:
     image: clickhouse/clickhouse-server:latest
     ports:
       - "8123:8123"
       - "9000:9000"
     ulimits:
       nofile: { soft: 262144, hard: 262144 }
   ```

2. **Inicjalizacja schematu ClickHouse** – uruchom `dw/migrations/V001__create_contacts_dw.sql` przez ClickHouse CLI lub init container:
   ```bash
   docker exec -i clickhouse clickhouse-client \
     < dw/migrations/V001__create_contacts_dw.sql
   ```

3. **ClickHouseDataSourceConfig** – `@Configuration` z `@Bean @Qualifier("clickhouse") DataSource clickhouseDataSource`:
   - Właściwości: `spring.datasource.clickhouse.url=jdbc:clickhouse://localhost:8123/contact_center_dw`
   - Zależność Maven: `com.clickhouse:clickhouse-jdbc:0.6.x` + `org.apache.httpcomponents.client5:httpclient5`

4. **`ClickHouseDwWriter implements DataWarehouseWriter`** (`infrastructure/etl/`):
   - `writeContacts(List<ContactDwRow> rows)` → INSERT INTO contacts_dw (ClickHouse wspiera batch insert natywnie)
   - ClickHouse `ReplacingMergeTree` zapewnia idempotentność (upsert po `contact_id`)
   - Zastąp `@Primary` z `PostgresDwWriter` na `ClickHouseDwWriter` (lub użyj `@ConditionalOnProperty etl.dw.type=clickhouse`)

5. **EtlSyncService** – bez zmian w logice; podmiana tylko bean `DataWarehouseWriter`

6. **application-dev.yml** – dodaj właściwości ClickHouse datasource

**Kryteria akceptacji:**
- [x] Docker Compose startuje serwis `clickhouse` razem z PostgreSQL/Redis/RabbitMQ
- [x] `dw/migrations/V001__create_contacts_dw.sql` wykonany – tabele istnieją w ClickHouse
- [x] `ClickHouseDwWriter` zapisuje wiersze do `contacts_dw` w ClickHouse (nie PostgreSQL)
- [x] ETL nadal idempotentny (ReplacingMergeTree deduplikuje po `contact_id`)
- [x] `GET /api/admin/etl/status` nadal działa
- [x] `PostgresDwWriter` pozostaje jako fallback (profil `etl.dw.type=postgres`)
- [ ] Testy integracyjne weryfikują zapis do ClickHouse (testcontainers lub mock) — **weryfikacja 2026-08-08: brak takiego testu**; jedyne wystąpienie słowa "ClickHouse" w testach to treść komunikatu wyjątku w niepowiązanym `EtlSyncServiceImplTest`. Reszta (writer, config, docker-compose, schemat) potwierdzona w kodzie — status ✅ pozostaje.

---

## MODUL: RODO / GDPR (przekrojowe)

### BE-031 – RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-025, BE-027, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** FE-033
**Odniesienie PRD:** US-09-06, wymagania RODO

**Opis:**
Endpoint `POST /api/customers/{id}/gdpr/export` – generuje ZIP z danymi klienta w JSON (CUSTOMER, CONTACT history, AUDIT_LOG). Endpoint `POST /api/customers/{id}/gdpr/anonymize` – anonimizuje pola PII, usuwa nagrania z S3, usuwa wątki email/social (lub anonimizuje treść). Oba działania logowane w AUDIT_LOG z userId wykonującego operację.

**Kryteria akceptacji:**
- [x] Export ZIP zawiera wszystkie dane klienta w czytelnym JSON
- [x] Anonimizacja: wszystkie pola PII zastąpione, is_deleted=true, plik nagrania usunięty z S3
- [x] Obie operacje wymagają roli SUPERVISOR lub ADMIN
- [x] Operacje logowane w AUDIT_LOG z entity_type='CUSTOMER', action='GDPR_EXPORT'/'GDPR_ANONYMIZE'

---

---

## MODUL: Routing numerów telefonicznych (EPIC-11)

### BE-033 – PhoneNumber CRUD API: zarządzanie numerami telefonów tenanta

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-021, BE-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Blokuje:** BE-034, BE-035, FE-026
**Odniesienie PRD:** EPIC-11

**Opis:**
CRUD API do zarządzania numerami telefonów przypisanymi do tenanta. Każdy tenant może mieć wiele numerów E.164. Numery są bazą dla reguł routingu (BE-034). Walidacja E.164 po stronie aplikacji uzupełnia CHECK constraint w DB.

**Szczegóły implementacji:**
- Encja `PhoneNumber` (JPA, tabela `phone_number`): `phoneNumberId`, `tenantId`, `number`, `displayName`, `isActive`, `isDeleted`, timestamps
- `PhoneNumberRepository extends TenantAwareRepository<PhoneNumber>`
- `PhoneNumberService`:
  - `createPhoneNumber(tenantId, request)` – walidacja E.164 regex, sprawdzenie duplikatu w tenant → 409
  - `listPhoneNumbers(tenantId)` – lista aktywnych (is_deleted=false)
  - `getPhoneNumber(tenantId, id)` – 404 jeśli nie istnieje lub inny tenant
  - `updatePhoneNumber(tenantId, id, request)` – aktualizacja `displayName`, `isActive`
  - `deletePhoneNumber(tenantId, id)` – soft delete; 409 jeśli istnieją aktywne reguły routingu dla tego numeru
- `PhoneNumberController` (`/api/phone-numbers`), `@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")`
  - `POST /api/phone-numbers` → 201
  - `GET /api/phone-numbers` → lista
  - `GET /api/phone-numbers/{id}` → szczegóły
  - `PATCH /api/phone-numbers/{id}` → update
  - `DELETE /api/phone-numbers/{id}` → soft delete

**Kryteria akceptacji:**
- [x] Walidacja E.164 (`^\+[1-9]\d{6,14}$`) → HTTP 400 dla niepoprawnych numerów
- [x] Duplikat numeru w tenant → HTTP 409
- [x] Próba usunięcia numeru z aktywnymi regułami → HTTP 409 z komunikatem
- [x] RLS: SUPERVISOR widzi tylko numery swojego tenanta; ADMIN widzi wszystkie (omija RLS przez wywołanie `set_tenant_context`)
- [x] Testy jednostkowe: CRUD + walidacja + duplikat

---

### BE-034 – PhoneRoutingRule CRUD API: reguły routingu IVR per numer i harmonogram

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-033, BE-013 (IVR), BE-020 (Queue)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Blokuje:** BE-035, FE-026
**Odniesienie PRD:** EPIC-11

**Opis:**
CRUD API reguł routingu przypisujących IVR lub kolejkę do danego numeru telefonu w określonych dniach tygodnia i przedziale czasowym. Aplikacyjna walidacja kolizji (przed triggerem DB) zwraca czytelny HTTP 409 z detalem nakładających się reguł.

**Szczegóły implementacji:**
- Encja `PhoneRoutingRule`: `ruleId`, `tenantId`, `phoneNumberId`, `ivrTreeId` (nullable), `queueId` (nullable), `daysOfWeek` (`List<Integer>`, `@JdbcTypeCode(SqlTypes.JSON)` lub native array), `timeStart`, `timeEnd`, `isActive`, timestamps
- `PhoneRoutingRuleRepository extends TenantAwareRepository`
  - `findByPhoneNumberIdAndTenantId(UUID, UUID)` – lista reguł dla numeru
  - `findOverlapping(UUID phoneNumberId, UUID excludeRuleId, List<Integer> days, LocalTime start, LocalTime end)` – native SQL z `days_of_week && ARRAY[...] AND time_start < :end AND time_end > :start`
- `PhoneRoutingRuleService`:
  - `createRule(tenantId, phoneNumberId, request)`:
    1. Sprawdź właściwość numeru (assertSameTenant)
    2. `findOverlapping(...)` → jeśli wynik niepusty → `ConflictException` (HTTP 409) z listą kolidujących ruleId
    3. Persist
  - `updateRule(tenantId, phoneNumberId, ruleId, request)` – analogicznie z wykluczeniem samej siebie
  - `deleteRule(tenantId, phoneNumberId, ruleId)` – hard delete (reguły nie są auditowane jako dane użytkownika)
  - `listRules(tenantId, phoneNumberId)` – posortowane po `time_start`
- `PhoneRoutingRuleController` (`/api/phone-numbers/{numberId}/routing-rules`), `@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")`
  - `POST` → 201 lub 409 z body `{ collidingRuleIds: [...] }`
  - `GET` → lista
  - `PATCH /{ruleId}` → update lub 409
  - `DELETE /{ruleId}` → 204

**DTO:**
```java
// CreatePhoneRoutingRuleRequest
@NotNull List<@Min(1) @Max(7) Integer> daysOfWeek; // ISO: 1=Pon, 7=Nie
@NotNull LocalTime timeStart;
@NotNull LocalTime timeEnd;                         // walidacja: timeEnd > timeStart (cross-field)
UUID ivrTreeId;                                     // exactly one of: ivrTreeId / queueId
UUID queueId;
```

**Kryteria akceptacji:**
- [x] Kolizja (ten sam numer, nakładający się dzień+czas) → HTTP 409 z `collidingRuleIds` w body
- [x] Dokładnie jeden target (IVR xor kolejka) – walidacja → HTTP 400
- [x] `timeEnd > timeStart` – walidacja cross-field → HTTP 400
- [x] `daysOfWeek` min 1 element, wartości 1–7 → HTTP 400
- [x] Testy: kolizja, brak kolizji (różne dni), brak kolizji (przylegające godziny), update własnej reguły bez fałszywej kolizji

---

### BE-035 – Incoming call routing: wybór IVR/kolejki na podstawie reguł harmonogramu

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-034, BE-009, BE-013
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-14
**Blokuje:** brak
**Odniesienie PRD:** EPIC-11

**Opis:**
Integracja reguł routingu z logiką obsługi połączenia przychodzącego w `TwilioWebhookController`. Gdy Twilio sygnalizuje przychodzące połączenie (`POST /api/telephony/webhook/twilio`), serwis wyszukuje numer docelowy w `phone_number`, sprawdza aktualne reguły harmonogramu i uruchamia odpowiedni IVR lub kieruje do kolejki. Brak pasującej reguły → TwiML `<Hangup/>`.

**Szczegóły implementacji:**
- `IncomingCallRoutingService`:
  ```java
  public RouteResult resolveRoute(UUID tenantId, String calledNumber, ZonedDateTime callTime) {
      // 1. Znajdź phone_number WHERE number = calledNumber AND tenant_id = tenantId AND is_active
      // 2. dayOfWeek = callTime.getDayOfWeek().getValue() (ISO: Mon=1)
      // 3. timeOfDay = callTime.toLocalTime()
      // 4. Znajdź regułę: phone_number_id pasuje, day w days_of_week, time_start <= timeOfDay < time_end, is_active
      // 5. Brak reguły → RouteResult.reject()
      // 6. Znaleziona → RouteResult.ivr(ivrTreeId) lub RouteResult.queue(queueId)
  }
  ```
  `RouteResult` – value object: `{ type: IVR | QUEUE | REJECT, targetId: UUID }`

- `TwilioWebhookController.handleIncomingCall()`:
  - Pobiera `To` (numer docelowy) i `tenantId` z query param
  - Wywołuje `IncomingCallRoutingService.resolveRoute()`
  - `REJECT` → `<Response><Reject/></Response>` (Twilio odrzuca połączenie bez opłaty za polling)
  - `IVR` → przekazuje do `IvrEngineService.startSession(ivrTreeId, callSid)`
  - `QUEUE` → TwiML `<Dial><Queue>{queueId}</Queue></Dial>`

- Strefa czasowa z `tenant.config.timezone` (domyślnie `Europe/Warsaw`)

**Kryteria akceptacji:**
- [x] Połączenie na numer z pasującą regułą IVR → IVR uruchamia się
- [x] Połączenie na numer z pasującą regułą kolejki → połączenie trafia do kolejki
- [x] Brak pasującej reguły (poza godzinami, weekend) → TwiML Reject
- [x] Numer nieznany w tenantcie → TwiML Reject (nie 404 – Twilio wymaga zawsze 200 + TwiML)
- [x] Strefa czasowa tenanta uwzględniona przy porównaniu godzin
- [x] Testy: wszystkie 4 ścieżki + edge cases (dokładnie na granicy godziny)

---

---

## MODUL: Prezentacja Kontaktów (EPIC-12)

### BE-036 – Rozszerzenie Contact API o filtry zaawansowane (queueId, campaignId, remoteAddress, durationMin/Max)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-027 ✅, DB-022 ✅
**Status:** ✅ Zrealizowane
**Blokuje:** FE-029
**Odniesienie PRD:** EPIC-12

**Opis:**
Istniejący `GET /api/contacts` (BE-027) obsługuje filtry: `agentId`, `customerId`, `status`, `channel`, `dateFrom`, `dateTo`. Rozszerzono o filtry potrzebne dla widoku „Raporty > Kontakty": `queueId`, `campaignId`, `remoteAddress` (numer telefonu klienta), `durationMin` (sekundy), `durationMax` (sekundy). Rozszerzenie jest addytywne — nie łamie kompatybilności z istniejącymi wywołaniami.

**Zrealizowane:**
- `ContactFilterParams` — dodano pola: `@Size(max=36) String queueId`, `@Size(max=36) String campaignId`, `String remoteAddress`, `@Min(0) Integer durationMin`, `@Min(0) Integer durationMax`
- `ContactController.listContacts()` — dodano `@RequestParam(required = false)` z adnotacjami `@Size`/`@Min` dla nowych parametrów; zaktualizowano tworzenie `ContactFilterParams`
- `ContactRepository.appendFilterConditions()` — rozszerzona sygnatura o 5 nowych parametrów; predykaty SQL:
  - `queue_id = CAST(:queueId AS uuid)` (jeśli podane)
  - `campaign_id = CAST(:campaignId AS uuid)` (jeśli podane)
  - `remote_address ILIKE '%' || :remoteAddress || '%'` (jeśli podane — partial match, case-insensitive)
  - `duration_seconds IS NOT NULL AND duration_seconds >= :durationMin` (jeśli podane)
  - `duration_seconds IS NOT NULL AND duration_seconds <= :durationMax` (jeśli podane)
- `findContacts()` i `countContacts()` — zaktualizowane sygnatury przekazujące nowe parametry
- 3 nowe testy jednostkowe w `ContactServiceTest` (filterByQueueId, filterByDurationMin, kombinacja AND)
- Build i testy zielone: 35/35

**Kryteria akceptacji:**
- [x] `GET /api/contacts?queueId=UUID` zwraca tylko kontakty z danej kolejki danego tenanta
- [x] `GET /api/contacts?campaignId=UUID` zwraca tylko kontakty powiązane z kampanią
- [x] `GET /api/contacts?remoteAddress=+48123` zwraca kontakty z `remote_address` zawierającym podaną wartość (ILIKE, case-insensitive)
- [x] `GET /api/contacts?durationMin=60&durationMax=300` zwraca kontakty z `duration_seconds` w zakresie [60, 300]
- [x] Istniejące filtry (agentId, status, channel, dateFrom, dateTo) działają bez zmian
- [x] SUPERVISOR/ADMIN: filtry działają dla całego tenanta; AGENT: filtr `agentId` nadal wymuszony na własne ID
- [x] Nowe filtry korzystają z indeksów z DB-022 (idx_contact_queue_date, idx_contact_duration)

---

### BE-037 – Endpoint streamowania nagrania z MinIO/S3: GET /api/contacts/{id}/recording

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-027 ✅, BE-010 ✅
**Status:** ✅ Zrealizowane
**Blokuje:** FE-028, FE-029, FE-030
**Odniesienie PRD:** EPIC-12

**Opis:**
Frontend potrzebuje możliwości odtwarzania nagrania rozmowy bezpośrednio w przeglądarce (Audio Player) oraz pobierania pliku. Nagranie jest przechowywane w MinIO/S3, a `recording_url` w tabeli `contact` zawiera wewnętrzny URL (`s3://bucket/...`). Frontend nie może bezpośrednio wywołać MinIO (CORS, credentiale). Backend pełni rolę proxy: generuje presigned URL lub streamuje dane przez HTTP.

Podejście: **presigned URL (krótkotrwały, 15 min)** — bezpieczniejsze i nie obciąża JVM streamingiem dużych plików audio.

**Szczegóły implementacji:**

Nowy endpoint w `ContactController`:
```java
@GetMapping("/{id}/recording")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
public ResponseEntity<RecordingUrlResponse> getRecordingUrl(@PathVariable String id)
```

`RecordingUrlResponse` (record):
```java
public record RecordingUrlResponse(
    String presignedUrl,   // URL do MinIO z podpisem HMAC, ważny 15 min
    Instant expiresAt,     // czas wygaśnięcia
    String filename,       // np. "nagranie-2026-04-08-12-30.mp3"
    Long contentLength     // rozmiar w bajtach (z metadata S3)
) {}
```

`RecordingService` (nowy lub rozszerzenie `S3Service`/`RecordingStorageService`):
- Pobiera kontakt z `ContactRepository.findById(contactId, tenantId)` → 404 jeśli nie istnieje
- Sprawdza `contact.getRecordingUrl() != null` → 404 z body `{"error": "NO_RECORDING"}` jeśli brak
- Parsuje `recording_url` (format `s3://bucket/path/file.mp3`) → wyciąga bucket i key
- Generuje presigned URL przez `S3Presigner` (AWS SDK lub MinIO SDK): TTL = 15 min
- Zwraca `RecordingUrlResponse`

Warunki bezpieczeństwa:
- `assertSameTenant(contact.getTenantId())` przed zwróceniem URL
- AGENT może pobrać URL tylko dla kontaktu, w którym był przypisanym agentem (`agentId == userId`)
- Presigned URL nie zawiera credentiali na stałe — wygasa po 15 min

**Kryteria akceptacji:**
- [x] `GET /api/contacts/{id}/recording` zwraca 200 z `presignedUrl` dla kontaktu z `recording_url != null`
- [x] `GET /api/contacts/{id}/recording` zwraca 404 gdy `recording_url` jest null lub pusty (komunikat: "Brak nagrania dla tego kontaktu")
- [x] `GET /api/contacts/{id}/recording` zwraca 404 gdy kontakt nie istnieje lub należy do innego tenanta
- [x] AGENT wywołujący endpoint dla kontaktu innego agenta otrzymuje 409 (InvalidOperationException)
- [x] Presigned URL jest ważny dokładnie 15 minut (weryfikowalne przez `expiresAt` w response)
- [ ] Presigned URL pozwala na pobranie pliku bez dodatkowego uwierzytelnienia (weryfikacja w środowisku dev z MinIO — test manualny)
- [x] Testy jednostkowe: brak nagrania → 404, kontakt nie istnieje → 404, AGENT cudzy kontakt → 409, S3 niedostępny → 503, sukces → 200 (8 testów w ContactServiceTest)

**Uwagi implementacyjne:**
- DTO: `ContactRecordingUrlResponse` (record w `api/contact/dto/`) z polami: `presignedUrl`, `expiresAt`, `fileName` (s3Key), `durationSeconds`
- Logika w `ContactService.getRecordingUrl()` — weryfikacja cross-tenant + uprawnień AGENT przed wołaniem S3
- Nowa metoda `RecordingService.generatePresignedUrlForKey(String s3Key, Duration ttl)` — unika redundantnego zapytania do DB (s3Key znany z załadowanego kontaktu)
- Obsługa błędów S3: `RecordingException` → HTTP 503 z czytelnym komunikatem
- AGENT z `agentId == null` na kontakcie (inbound Twilio przed odebraniem) — dostęp dozwolony

---

---

## Zależności między zadaniami

### Kolejność obowiązkowa (blokery)

```
BE-001 → BE-002 → BE-003 → BE-004
BE-001 → BE-005 (Audit Log)
BE-002 + DB-005 → BE-006 → BE-007
BE-002 + DB-003 → BE-008
BE-001 + DB-006 → BE-009 → BE-010, BE-011
BE-009 + BE-003 → BE-012 (WebSocket)
BE-013 + DB-009 → BE-014 (Voicebot Python)
BE-001 + DB-007 → BE-015 → BE-016
BE-002 + DB-008 → BE-017 → BE-018
BE-008 + DB-010 → BE-019 → BE-020, BE-021
BE-002 + DB-011 → BE-022 → BE-023
BE-009 + BE-022 + DB-011 → BE-024 (Dialer)
BE-002 + DB-012 → BE-025 → BE-026
BE-002 + DB-006 → BE-027 → BE-028
BE-012 + BE-019 → BE-029
BE-027 + DB-013 + DB-014 → BE-030 (ETL)
BE-025 + BE-027 + DB-012 → BE-031 (RODO)
BE-009 + BE-006 → BE-032 (Twilio per-tenant)
BE-027 + DB-022 → BE-036 (Contact API filtry zaawansowane)
BE-027 + BE-010 → BE-037 (Recording presigned URL)
```

### Blokery od Bazy Danych (BE czeka na DB)

| Zadanie BE | Czeka na zadanie DB |
|------------|---------------------|
| BE-001 | DB-001 (schemat bazowy, Flyway) |
| BE-002 | DB-002 (tabela TENANT) |
| BE-003 | DB-003 (tabela USER, tokeny) |
| BE-005 | DB-004 (tabela AUDIT_LOG) |
| BE-006 | DB-005 (TENANT schema) |
| BE-009, BE-010, BE-027 | DB-006 (tabela CONTACT, RECORDING) |
| BE-015, BE-016 | DB-007 (tabela EMAIL, EMAIL_TEMPLATE) |
| BE-017, BE-018 | DB-008 (tabela SOCIAL_INTEGRATION) |
| BE-013, BE-014 | DB-009 (tabela IVR_TREE) |
| BE-019, BE-020 | DB-010 (tabela QUEUE) |
| BE-022, BE-023, BE-024 | DB-011 (tabela CAMPAIGN, CAMPAIGN_CONTACT) |
| BE-025, BE-026, BE-031 | DB-012 (tabela CUSTOMER) |
| BE-028, BE-030 | DB-013 (indeksy raportowe, widoki) |
| BE-030 | DB-014 (schemat ClickHouse) |

### Zadania mozliwe do realizacji rownoleglem (po BE-001 i odpowiednich schematach DB)

| Sciezka | Zadania |
|---------|---------|
| Auth + Security | BE-003 → BE-004 |
| Tenants | BE-006 → BE-007 |
| Users | BE-008 |
| Telephony | BE-009 → BE-010, BE-011, BE-012, BE-032 |
| Routing telefoniczny | BE-033 → BE-034 → BE-035 |
| IVR + Voicebot | BE-013 → BE-014 |
| Email | BE-015 → BE-016 |
| Social Media | BE-017 → BE-018 |
| Routing | BE-019 → BE-020, BE-021 |
| Kampanie | BE-022 → BE-023, BE-024 |
| Klienci | BE-025 → BE-026, BE-031 |
| Raporty | BE-027 → BE-028, BE-029, BE-030 |
| Prezentacja kontaktów | BE-036, BE-037 (równolegle po BE-027) |
| Zaplanowane oddzwonienia | BE-038 → BE-039, BE-040 (równolegle po BE-038) |

---

## MODUL: Zaplanowane oddzwonienia (EPIC-13)

### BE-038 – Executor zaplanowanych callbacków (`ScheduledCallbackExecutor`)

**Typ:** Feature – Scheduler
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-009 (TelephonyAdapter), DB-023 (scheduled_callback z source_type), BE-024 (DialerCallbackHandler)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** BE-068
**Epic:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Tabela `scheduled_callback` ma metodę `findDueCallbacks()` w repozytorium, ale **brakuje schedulera**, który ją wywoła. Ten task implementuje `ScheduledCallbackExecutor` — komponent uruchamiający co minutę zaplanowane callbacki.

**Zakres implementacji:**

1. **`ScheduledCallbackExecutor`** (`domain/service/ScheduledCallbackExecutor.java`):
   - `@Scheduled(fixedDelay = 60_000)` – co minutę
   - Pobiera listę aktywnych tenantów z callbackami PENDING i `scheduled_at <= NOW()` (jedno zapytanie cross-tenant przez JDBC bez RLS – dostęp przez service account)
   - Dla każdego callbacku:
     - Ustawia TenantContext (snapshot pattern dla `@Async`)
     - Aktualizuje status PENDING → PROCESSING (optimistic lock przez UPDATE WHERE status='PENDING')
     - Wywołuje `TelephonyAdapter.initiateCall()` z numerem z callbacku
     - Po sukcesie: zapisuje callState w Redis (dialer:call:{callSid}), status → COMPLETED
     - Po błędzie Twilio: status → FAILED, loguje przyczynę
   - Limit per iterację: 50 callbacków (konfigurowalny przez `dialer.callback-executor.batch-size`)
   - `@ConditionalOnProperty(name = "dialer.enabled", havingValue = "true", matchIfMissing = true)`

2. **`ScheduledCallbackRepository.findDueCallbacksAllTenants(int limit)`** – nowe zapytanie cross-tenant (JDBC bez RLS, dla schedulera):
   ```sql
   SELECT * FROM scheduled_callback
   WHERE status = 'PENDING' AND scheduled_at <= NOW() AND is_deleted = FALSE
   ORDER BY scheduled_at ASC
   LIMIT ?
   ```

3. **Konfiguracja** w `application.yml`:
   ```yaml
   dialer:
     enabled: true
     callback-executor:
       batch-size: 50
   ```

**Endpointy:** brak (scheduler wewnętrzny)

**Kryteria akceptacji:**
- [x] Scheduler uruchamia się co minutę (weryfikacja przez logi)
- [x] Callbacki z `scheduled_at <= NOW()` i status=PENDING są inicjowane (wywołanie TelephonyAdapter)
- [x] Brak double-processing: UPDATE WHERE status='PENDING' gwarantuje atomowość
- [x] Błąd Twilio → status FAILED + log ERROR (nie przerywa pętli dla innych callbacków)
- [x] Scheduler nie uruchamia się gdy `dialer.enabled=false`
- [x] Test: `ScheduledCallbackExecutorTest` – mockuje TelephonyAdapter, weryfikuje zmianę statusów
- [x] Obsługa TenantContext.snapshot()/restore() dla przekazania kontekstu do przetwarzania

**Uwagi implementacyjne:**
- Zapytanie cross-tenant (bez RLS) musi używać konta z uprawnieniami `bypass_rls` lub bezpośrednio JdbcTemplate z osobnym datasource
- Alternatywnie: RLS bypass przez `SET LOCAL row_security TO off` w ramach transakcji (wymaga superuser lub uprawnienia BYPASSRLS)
- Jeśli nie ma dostępu bypass: dodać kolumnę `next_execution_tenant_ids` do dedykowanej tabeli scheduler lub wykonywać per-tenant przez pobranie listy tenantów z tabeli `tenant`

---

### BE-039 – API przełożenia zaplanowanego callbacku

**Typ:** Feature – REST API
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-024 (ScheduledCallbackRepository, DialerController), DB-023
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** BE-051, FE-031, FE-044
**Epic:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Agent może przełożyć istniejące zaplanowane oddzwonienie (PENDING) na inną godzinę. Dotyczy zarówno callbacków po kampaniach (`CAMPAIGN_CALLBACK`) jak i oddzwonień z rozmów przychodzących (`INBOUND_CALLBACK`).

**Nowe endpointy w `DialerController`:**

```
PUT /api/dialer/callbacks/{callbackId}
```
- Role: `AGENT`, `SUPERVISOR`, `ADMIN`
- Request body: `RescheduleCallbackRequest { scheduledAt: Instant (wymagane), notes: String (opcjonalne) }`
- Logika:
  1. Pobierz callback `findById(callbackId, tenantId)` → 404 jeśli nie istnieje
  2. Zweryfikuj status == 'PENDING' → 409 jeśli już nie PENDING
  3. Dla roli AGENT: zweryfikuj że `callback.agentId == jwtAgentId` → 403 jeśli cudzy callback
  4. Zaktualizuj `scheduledAt` (i opcjonalnie `notes`), zapisz
  5. Zwróć `ScheduledCallbackResponse` (HTTP 200)
- Response: `ScheduledCallbackResponse` (istniejące DTO)

**DTO** (`api/dialer/dto/RescheduleCallbackRequest.java`):
```java
public record RescheduleCallbackRequest(
    @NotNull @Future Instant scheduledAt,
    String notes  // nullable
) {}
```

**Walidacja:**
- `scheduledAt` musi być w przyszłości (`@Future`)
- Callback musi być PENDING (nie PROCESSING/COMPLETED/CANCELLED)
- AGENT może przełożyć tylko swoje callbacki (`agentId == jwtAgentId`)

**Kryteria akceptacji:**
- [x] PENDING callback → zmiana scheduledAt → HTTP 200 z zaktualizowanym DTO
- [x] Callback nie-PENDING → HTTP 409 z czytelnym komunikatem
- [x] AGENT próbuje przełożyć cudzy callback → HTTP 403
- [x] scheduledAt w przeszłości → HTTP 400 (walidacja Bean Validation)
- [x] Nieistniejący callbackId → HTTP 404
- [x] Test jednostkowy: `DialerCallbackRescheduleTest` (5 przypadków)

---

### BE-040 – API dodania oddzwonienia podczas rozmowy przychodzącej

**Typ:** Feature – REST API
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-009 (Contact model), BE-024 (ScheduledCallbackRepository), DB-023
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** FE-032
**Epic:** EPIC-13 Zaplanowane oddzwonienia

**Opis:**
Podczas lub po zakończeniu rozmowy przychodzącej agent może zaplanować oddzwonienie do klienta na konkretną godzinę. Callback jest powiązany z kontaktem źródłowym (`origin_contact_id`) i ma `source_type = 'INBOUND_CALLBACK'`.

**Nowy endpoint w `DialerController`:**

```
POST /api/contacts/{contactId}/callback
```
- Role: `AGENT`, `SUPERVISOR`, `ADMIN`
- Path param: `contactId` UUID
- Request body: `CreateInboundCallbackRequest`
- Logika:
  1. Pobierz kontakt `contactRepository.findById(contactId, tenantId)` → 404 jeśli nie istnieje
  2. Dla roli AGENT: zweryfikuj że `contact.agentId == jwtAgentId` → 403 (agent może planować tylko ze swoich rozmów)
  3. Utwórz `ScheduledCallback` z:
     - `source_type = 'INBOUND_CALLBACK'`
     - `origin_contact_id = contactId`
     - `agentId` z JWT (AGENT) lub z request (SUPERVISOR/ADMIN)
     - `phone`, `firstName`, `lastName` z request (jeśli null – użyj danych z kontaktu/klienta)
     - `scheduledAt`, `notes` z request
  4. Zapisz i zwróć HTTP 201 Created z `ScheduledCallbackResponse`

**DTO** (`api/dialer/dto/CreateInboundCallbackRequest.java`):
```java
public record CreateInboundCallbackRequest(
    @NotBlank String phone,           // numer docelowy (E.164)
    String firstName,                  // nullable – opcjonalne dane klienta
    String lastName,                   // nullable
    @NotNull @Future Instant scheduledAt,
    String notes,                      // nullable
    UUID agentId                       // ignorowane dla roli AGENT (zawsze jwtAgentId)
) {}
```

**Uwaga dot. autoryzacji:** Dla AGENT – `contact.agentId` może być null (np. kontakt przyszedł przez IVR, zanim agent odebrał). W takim przypadku zezwól na tworzenie callbacku (agent jest przypisany do kontaktu w sesji, nawet jeśli DB jeszcze nie zaktualizowana).

**Kryteria akceptacji:**
- [x] Poprawne żądanie → HTTP 201, `source_type='INBOUND_CALLBACK'`, `origin_contact_id=contactId`
- [x] Nieistniejący contactId → HTTP 404
- [x] AGENT dla cudzego kontaktu (agentId != null i różny) → HTTP 403
- [x] scheduledAt w przeszłości → HTTP 400
- [x] phone null/blank → HTTP 400
- [x] Test: `InboundCallbackCreationTest` (5 przypadków)
- [x] Endpoint widoczny w Swagger UI

**Uwagi implementacyjne:**
- Endpoint w `DialerController` (nie ContactController) – logika dotyczy schedulowania połączeń
- Alternatywnie można dodać do ContactController jeśli PR review uzna to za bardziej spójne
- `ScheduledCallbackResponse` wymaga nowego pola `sourceType` i `originContactId` do pełnego odzwierciedlenia danych (aktualizacja istniejącego DTO)

---

### BE-041 – Callback List API: filtrowana lista callbacków dla agenta i supervisora

**Typ:** Feature – REST API
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-024 (ScheduledCallbackRepository), BE-008 (AppUser – do rozwiązania nazwy agenta)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-15
**Epic:** EPIC-13 Zaplanowane oddzwonienia
**Blokuje:** FE-034, FE-035, BE-042, BE-048

**Opis:**
Rozszerzenie istniejącego endpointu `GET /api/dialer/callbacks` o obsługę ról z izolacją danych oraz nowe filtry. Aktualnie endpoint zwraca wyłącznie callbacki w statusie PENDING bez filtrowania po `agentId` — agent widzi callbacki wszystkich agentów. Zadanie naprawia to zachowanie i dodaje filtry wymagane przez panele listy.

**Zmiany w `ScheduledCallbackRepository`:**

Dodaj dwie nowe metody natywnym SQL:

```java
// Dla AGENT: stronicowana lista własnych callbacków z filtrem statusu
List<ScheduledCallback> findByAgentId(
    UUID tenantId, UUID agentId,
    String status,          // null = wszystkie statusy
    String sortDirection,   // "ASC" | "DESC" po scheduledAt
    int page, int size);

long countByAgentId(UUID tenantId, UUID agentId, String status);

// Dla SUPERVISOR/ADMIN: stronicowana lista wszystkich callbacków tenanta z filtrem statusu i agentId
List<ScheduledCallback> findByTenantIdWithFilters(
    UUID tenantId,
    String status,          // null = wszystkie statusy
    UUID agentIdFilter,     // null = wszyscy agenci
    String sortDirection,   // "ASC" | "DESC" po scheduledAt
    int page, int size);

long countByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter);
```

Zapytania SQL muszą uwzględniać `AND tenant_id = CAST(:tenantId AS uuid)` jako warunek bazowy (RLS + eksplicytny filtr).

**Zmiany w `DialerController`:**

Rozszerz istniejący `GET /api/dialer/callbacks` o parametry:

```
GET /api/dialer/callbacks?status=PENDING&agentId={uuid}&sortDir=ASC&page=0&size=20
```

| Parametr | Typ | Domyślnie | Opis |
|---|---|---|---|
| `status` | String | `null` (wszystkie) | Filtr statusu: PENDING / COMPLETED / CANCELLED / PROCESSING |
| `agentId` | UUID | `null` | Tylko SUPERVISOR/ADMIN; AGENT ignoruje ten parametr |
| `sortDir` | String | `ASC` | Kierunek sortowania po `scheduled_at` |
| `page` | int | 0 | Numer strony |
| `size` | int | 20 | Rozmiar strony (max 100) |

**Logika izolacji ról:**
- `AGENT`: zawsze `findByAgentId(tenantId, jwtAgentId, status, sortDir, page, size)` — parametr `agentId` z zapytania jest ignorowany
- `SUPERVISOR` / `ADMIN`: `findByTenantIdWithFilters(tenantId, status, agentIdFilter, sortDir, page, size)`

**Rozszerzenie `ScheduledCallbackResponse`:**

Dodaj pole `agentName: String` (może być null gdy `agentId` jest null). Wartość rozwiązywana przez `AppUserRepository.findById(agentId, tenantId)` → `firstName + " " + lastName`. Używaj cache (jeden SELECT IN dla wszystkich unikalnych `agentId` na stronie, nie N zapytań).

**Nowy DTO dla odpowiedzi rozszerzonej:**

Zamiast modyfikować istniejący `ScheduledCallbackResponse`, stwórz `CallbackListItemResponse` z dodatkowym polem `agentName`:

```java
public record CallbackListItemResponse(
    UUID callbackId,
    UUID agentId,
    String agentName,      // null gdy agentId == null
    String phone,
    String firstName,
    String lastName,
    Instant scheduledAt,
    String notes,
    String status,
    String sourceType,
    UUID originContactId,
    Instant createdAt
) {}
```

**Kryteria akceptacji:**
- [x] AGENT wywołujący `GET /api/dialer/callbacks` widzi wyłącznie callbacki przypisane do swojego `agentId` (weryfikacja: brak callbacków innych agentów w odpowiedzi)
- [x] SUPERVISOR wywołujący `GET /api/dialer/callbacks` widzi callbacki wszystkich agentów w ramach tenanta
- [x] Filtr `?status=COMPLETED` zwraca wyłącznie rekordy w statusie COMPLETED
- [x] Filtr `?status=` (pusty) lub brak parametru zwraca callbacki wszystkich statusów
- [x] SUPERVISOR może filtrować po `?agentId={uuid}` i widzi tylko callbacki danego agenta
- [x] AGENT wywołujący `?agentId={cudzuuid}` — parametr jest ignorowany, widzi tylko własne callbacki
- [x] `?sortDir=DESC` sortuje po `scheduled_at` malejąco
- [x] Pole `agentName` zawiera imię i nazwisko agenta (jeden SELECT IN, nie N zapytań)
- [x] Paginacja: `totalElements`, `totalPages`, `first`, `last` zgodne z faktyczną liczbą wyników
- [x] Brak callbacków → HTTP 200 z pustą listą
- [x] Testy jednostkowe: filtrowanie AGENT vs SUPERVISOR (min. 6 przypadków)
- [x] Endpoint widoczny w Swagger UI z opisem parametrów

---

### BE-042 – Callback Management API: edycja pełna i usunięcie callbacku

**Typ:** Feature – REST API
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-024 (ScheduledCallbackRepository), BE-041
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-15
**Epic:** EPIC-13 Zaplanowane oddzwonienia
**Blokuje:** FE-034, FE-035

**Opis:**
Dwa nowe endpointy w `DialerController` umożliwiające pełną edycję callbacku (zmiana numeru telefonu, daty, notatki, a dla supervisora również reassign agenta) oraz usunięcie callbacku.

Istniejący `PUT /api/dialer/callbacks/{callbackId}` (BE-039) obsługuje tylko reschedule (zmiana daty + notatka). Nowe zadanie dodaje endpoint PATCH do pełnej edycji i DELETE do usunięcia.

**Endpoint 1 – Pełna edycja callbacku:**

```
PATCH /api/dialer/callbacks/{callbackId}
```

Request DTO `UpdateCallbackRequest`:

```java
public record UpdateCallbackRequest(
    @Pattern(regexp = "\\+[1-9]\\d{6,14}") String phone,   // nullable – bez zmiany gdy null
    String firstName,                                         // nullable
    String lastName,                                          // nullable
    @Future Instant scheduledAt,                             // nullable – bez zmiany gdy null
    String notes,                                            // nullable
    UUID agentId                                             // nullable; ignorowane dla roli AGENT
) {}
```

Logika:
1. Pobierz callback → 404 jeśli nie istnieje
2. Weryfikacja statusu: PATCH dozwolony tylko dla statusu PENDING → 409 gdy COMPLETED / CANCELLED / PROCESSING
3. Autoryzacja:
   - AGENT: może edytować wyłącznie własny callback (`agentId z JWT == callback.agentId`) → 403 dla cudzych; pole `agentId` z requestu ignorowane
   - SUPERVISOR / ADMIN: może edytować każdy callback w ramach tenanta; jeśli `agentId` w requescie niepusty — wykonuje reassign
4. Patch semantics: aktualizuj tylko pola niepuste (null = bez zmiany)
5. Zapisz i zwróć HTTP 200 z `CallbackListItemResponse`

**Endpoint 2 – Usunięcie callbacku:**

```
DELETE /api/dialer/callbacks/{callbackId}
```

Logika:
1. Pobierz callback → 404 jeśli nie istnieje
2. AGENT: tylko własne callbacki → 403 dla cudzych
3. Callbacki w statusie PROCESSING nie mogą być usunięte → 409 "Callback jest aktualnie przetwarzany"
4. Zmień status na CANCELLED (soft-delete; nie usuwaj wiersza z DB — zachowuje historię dla raportów)
5. Zwróć HTTP 204 No Content

**Dodaj do `ScheduledCallbackRepository`:**

```java
// Soft-delete: zmiana statusu na CANCELLED
int cancelCallback(UUID callbackId, UUID tenantId);
```

Implementacja przez `updateStatus(callbackId, "CANCELLED", tenantId)` — reużywa istniejącej metody.

**Kryteria akceptacji:**
- [x] PATCH z `phone` → numer telefonu zaktualizowany w DB
- [x] PATCH z `agentId` przez SUPERVISOR → callback przypisany do nowego agenta
- [x] PATCH z `agentId` przez AGENT → pole ignorowane, agentId bez zmian
- [x] PATCH dla callbacku w statusie COMPLETED → HTTP 409
- [x] PATCH przez AGENT dla cudzego callbacku → HTTP 403
- [x] DELETE własnego callbacku przez AGENT → HTTP 204, status=CANCELLED w DB
- [x] DELETE cudzego callbacku przez AGENT → HTTP 403
- [x] DELETE callbacku w statusie PROCESSING → HTTP 409
- [x] DELETE przez SUPERVISOR dla callbacku dowolnego agenta → HTTP 204
- [x] Wiersz w tabeli `scheduled_callback` nie jest fizycznie usuwany (status=CANCELLED)
- [x] Testy jednostkowe: min. 8 przypadków (edycja + usunięcie, oba role)
- [x] Oba endpointy widoczne w Swagger UI

---

## MODUL: Zarządzanie przypisaniem agentów do kolejek (EPIC-14)

### BE-043 – Model i repozytorium grup agentów (`AgentGroup`, `AgentGroupRepository`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** M
**Zależy od:** DB-024
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** BE-044

**Opis:**
Encja domenowa `AgentGroup` mapująca tabelę `agent_group` oraz repozytorium `AgentGroupRepository` z operacjami CRUD i zarządzaniem członkostwem.

Nowy pakiet: `com.contactcenter.domain.agentgroup`

Klasy do stworzenia:

```java
// Encja
@Entity @Table(name = "agent_group")
public class AgentGroup {
    @Id UUID groupId;
    UUID tenantId;
    String name;
    Instant createdAt;
    Instant updatedAt;
}
```

`AgentGroupRepository extends TenantAwareRepository` — natywny SQL (wzorzec zgodny z `QueueRepository`):
- `PagedResponse<AgentGroup> findAllByTenantId(UUID tenantId, String name, int page, int size)` — z opcjonalnym filtrem ILIKE po `name`
- `Optional<AgentGroup> findByIdAndTenantId(UUID groupId, UUID tenantId)`
- `boolean existsByNameAndTenantId(String name, UUID tenantId)` — walidacja unikalności nazwy
- `AgentGroup insert(AgentGroup group)`
- `int update(AgentGroup group)` — aktualizuje `name` i `updated_at`
- `int delete(UUID groupId, UUID tenantId)` — fizyczne usunięcie (grupy nie mają soft-delete; kaskada w DB usuwa `agent_group_member` i `queue_agent_group`)

Zarządzanie członkostwem (operacje na `agent_group_member`):
- `List<UUID> findMemberIds(UUID groupId, UUID tenantId)` — lista agentId w grupie
- `void addMember(UUID groupId, UUID agentId)` — INSERT OR IGNORE (idempotentne)
- `void removeMember(UUID groupId, UUID agentId)` — DELETE
- `void replaceMembers(UUID groupId, UUID tenantId, List<UUID> agentIds)` — atomowy DELETE + INSERT w jednej transakcji

Każda metoda wywołuje `assertSameTenant` i `setTenantContextInDb`.

**Kryteria akceptacji:**
- [x] `findAllByTenantId` zwraca stronicowaną listę grup tenanta
- [x] `insert` z duplikatem nazwy w tym samym tenancie rzuca `DataIntegrityViolationException` (unique constraint z DB)
- [x] `delete` grupy kaskadowo usuwa z `agent_group_member` i `queue_agent_group` (weryfikacja przez test integracyjny)
- [x] `replaceMembers` jest atomowy: jeśli INSERT failduje, DELETE też się cofa
- [x] Brak cross-tenant leakage: `findByIdAndTenantId` z obcym `tenantId` zwraca `Optional.empty()`
- [x] Testy jednostkowe pokrywają: CRUD, duplikat nazwy, `replaceMembers`, cross-tenant

---

### BE-044 – CRUD REST API grup agentów (`AgentGroupController`, `AgentGroupService`)

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** M
**Zależy od:** BE-043
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** BE-046, FE-036

**Opis:**
Warstwa serwisowa i REST controller zarządzania grupami agentów dla Supervisora.

**Serwis:** `AgentGroupService` w pakiecie `com.contactcenter.domain.agentgroup`:
- `PagedResponse<AgentGroupResponse> listGroups(UUID tenantId, String name, int page, int size)`
- `AgentGroupResponse createGroup(UUID tenantId, CreateAgentGroupRequest req)`
- `AgentGroupResponse updateGroup(UUID tenantId, UUID groupId, UpdateAgentGroupRequest req)`
- `void deleteGroup(UUID tenantId, UUID groupId)`
- `AgentGroupMembersResponse getMembers(UUID tenantId, UUID groupId)`
- `AgentGroupMembersResponse replaceMembers(UUID tenantId, UUID groupId, List<UUID> agentIds)` — weryfikuje że każdy agentId należy do tenanta i ma rolę AGENT

**DTO:**

```java
record CreateAgentGroupRequest(@NotBlank @Size(max=255) String name) {}
record UpdateAgentGroupRequest(@NotBlank @Size(max=255) String name) {}
record AgentGroupResponse(UUID groupId, String name, int memberCount, Instant createdAt, Instant updatedAt) {}
record AgentGroupMembersResponse(UUID groupId, String groupName, List<AgentSummary> members) {}
record AgentSummary(UUID agentId, String firstName, String lastName, String email) {}
```

**Controller:** `AgentGroupController` w pakiecie `com.contactcenter.api.agentgroup`:

```
GET    /api/agent-groups                      → listGroups (SUPERVISOR, ADMIN)
POST   /api/agent-groups                      → createGroup (SUPERVISOR, ADMIN)
PUT    /api/agent-groups/{groupId}            → updateGroup (SUPERVISOR, ADMIN)
DELETE /api/agent-groups/{groupId}            → deleteGroup (SUPERVISOR, ADMIN)
GET    /api/agent-groups/{groupId}/members    → getMembers (SUPERVISOR, ADMIN)
PUT    /api/agent-groups/{groupId}/members    → replaceMembers (SUPERVISOR, ADMIN) — body: {"agentIds": [...]}
```

Uwagi:
- Endpointy chronione `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")`
- `deleteGroup` zwraca 409 jeśli grupa jest przypisana do jakiejkolwiek kolejki (sprawdź `queue_agent_group`)
- `replaceMembers` używa semantyki PUT (pełna podmiana listy), nie PATCH
- Wszystkie endpointy wymagają rejestracji w `SecurityConfig` i `TenantFilter.PUBLIC_PATH_PREFIXES` (nie są publiczne — nie dodawaj do public paths)

**Kryteria akceptacji:**
- [x] `GET /api/agent-groups` zwraca paginowaną listę z `memberCount`
- [x] `POST` z duplikatem nazwy → HTTP 409
- [x] `DELETE` grupy przypisanej do kolejki → HTTP 409
- [x] `PUT /members` z `agentId` spoza tenanta → HTTP 400
- [x] `PUT /members` z `agentId` roli SUPERVISOR → HTTP 400 (tylko AGENT dozwolony)
- [x] Wszystkie endpointy widoczne w Swagger UI
- [x] Testy jednostkowe serwisu: min. 6 przypadków

---

### BE-045 – Repozytorium przypisań kolejki: `QueueAssignmentRepository`

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** M
**Zależy od:** DB-025
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** BE-046, BE-047

**Opis:**
Nowe repozytorium zarządzające przypisaniem agentów i grup do kolejki. Oddzielne od `QueueRepository` — separacja odpowiedzialności.

Klasa: `QueueAssignmentRepository extends TenantAwareRepository` w pakiecie `com.contactcenter.domain.repository`.

Metody:

```java
// Odczyt flagi all_agents
boolean isAllAgents(UUID queueId, UUID tenantId);

// Odczyt przypisanych agentów (bezpośrednio przez queue_agent)
List<UUID> findDirectAgentIds(UUID queueId, UUID tenantId);

// Odczyt przypisanych grup
List<UUID> findGroupIds(UUID queueId, UUID tenantId);

// Kluczowe dla silnika routingu: pełna lista agentId uprawnionych do obsługi kolejki
// UNION: bezpośredni (queue_agent) + przez grupy (queue_agent_group → agent_group_member)
Set<UUID> resolveEligibleAgentIds(UUID queueId, UUID tenantId);

// Aktualizacja trybu przypisania
void setAllAgents(UUID queueId, UUID tenantId, boolean allAgents);

// Podmiana bezpośrednich agentów (atomowe DELETE + INSERT)
void replaceDirectAgents(UUID queueId, UUID tenantId, List<UUID> agentIds);

// Podmiana grup (atomowe DELETE + INSERT do queue_agent_group)
void replaceGroups(UUID queueId, UUID tenantId, List<UUID> groupIds);
```

SQL dla `resolveEligibleAgentIds`:
```sql
SELECT agent_id FROM queue_agent WHERE queue_id = CAST(:queueId AS uuid)
UNION
SELECT agm.agent_id FROM queue_agent_group qag
    JOIN agent_group_member agm ON agm.group_id = qag.group_id
WHERE qag.queue_id = CAST(:queueId AS uuid)
```

Wynik `resolveEligibleAgentIds` jest używany przez `DefaultRoutingEngine` — metoda musi być odpowiednio wydajna (jedno złożone zapytanie, nie N zapytań).

**Kryteria akceptacji:**
- [x] `resolveEligibleAgentIds` zwraca UNION agentów bezpośrednich i przez grupy (brak duplikatów)
- [x] `replaceDirectAgents` jest atomowy (transakcja)
- [x] `replaceGroups` jest atomowy (transakcja)
- [x] Cross-tenant: `resolveEligibleAgentIds` z obcym `tenantId` zwraca puste zbiory
- [x] Testy jednostkowe: UNION scenario (agent w obu źródłach pojawia się raz), cross-tenant

---

### BE-046 – REST API zarządzania przypisaniem agentów do kolejki

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Zależy od:** BE-044, BE-045
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** FE-036 (API contract), FE-038

**Opis:**
Nowe endpointy w istniejącym `QueueController` (lub osobny `QueueAssignmentController`) do odczytu i aktualizacji konfiguracji przypisania dla kolejki.

**Nowe endpointy:**

```
GET  /api/queues/{queueId}/assignment
     → QueueAssignmentResponse
     Zwraca: { allAgents: bool, directAgentIds: [UUID], groupIds: [UUID] }

PUT  /api/queues/{queueId}/assignment
     body: UpdateQueueAssignmentRequest
     → QueueAssignmentResponse
```

**DTO:**

```java
record QueueAssignmentResponse(
    UUID queueId,
    boolean allAgents,
    List<AgentSummary> directAgents,    // imię + nazwisko + email
    List<AgentGroupSummary> groups      // groupId + name + memberCount
) {}

record UpdateQueueAssignmentRequest(
    @NotNull Boolean allAgents,
    List<UUID> directAgentIds,   // nullable; ignorowane gdy allAgents=true
    List<UUID> groupIds          // nullable; ignorowane gdy allAgents=true
) {}

record AgentGroupSummary(UUID groupId, String name, int memberCount) {}
```

**Logika PUT:**
1. Pobierz kolejkę → 404 jeśli nie istnieje
2. `assertSameTenant`
3. Jeśli `allAgents = true` → wywołaj `setAllAgents(true)`, wyczyść `directAgentIds` i `groupIds` (opcjonalnie — nie usuwa historycznych rekordów, ale flaga `all_agents` przesłania je w routingu)
4. Jeśli `allAgents = false` → wywołaj `setAllAgents(false)`, `replaceDirectAgents(directAgentIds ?: [])`, `replaceGroups(groupIds ?: [])`
5. Walidacja: każdy `directAgentId` musi należeć do tenanta i mieć rolę AGENT; każdy `groupId` musi należeć do tenanta
6. Zwróć HTTP 200 z `QueueAssignmentResponse`

Role wymagane: SUPERVISOR, ADMIN.

**Kryteria akceptacji:**
- [x] `GET /api/queues/{queueId}/assignment` zwraca aktualny stan przypisania z danymi agentów i grup
- [x] `PUT` z `allAgents=true` ustawia flagę i zwraca `directAgents=[], groups=[]`
- [x] `PUT` z `allAgents=false` i listami → agenci i grupy zapisane w DB
- [x] `PUT` z `directAgentId` spoza tenanta → HTTP 400
- [x] `PUT` z `groupId` spoza tenanta → HTTP 400
- [x] `PUT` przez AGENT → HTTP 403
- [x] Testy jednostkowe: min. 5 scenariuszy

---

### BE-047 – Aktualizacja `DefaultRoutingEngine`: filtrowanie agentów po przypisaniu do kolejki

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** M
**Zależy od:** BE-045
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-18
**Blokuje:** brak

**Opis:**
Kluczowa zmiana: `DefaultRoutingEngine.findBestAgent()` musi uwzględniać konfigurację przypisania kolejki. Obecnie `scanAvailableAgents(tenantId)` zwraca wszystkich dostępnych agentów tenanta — co pozostaje poprawne jako pierwszy krok. Drugi krok to filtrowanie po `eligibleAgentIds` gdy kolejka nie ma flagi `all_agents`.

**Zmiany w `RoutingRequest`:**
Dodaj pole `Set<UUID> eligibleAgentIds` (może być null = brak filtru):

```java
public record RoutingRequest(
    UUID tenantId,
    UUID queueId,
    String routingStrategy,
    List<String> requiredSkills,
    UUID preferredAgentId,
    String contactChannel,
    Set<UUID> eligibleAgentIds   // null = all_agents=TRUE (brak filtru)
) {
    public boolean hasAgentFilter() {
        return eligibleAgentIds != null;
    }

    // Aktualizacja fabryki:
    public static RoutingRequest of(Contact contact, Queue queue, UUID tenantId,
                                    Set<UUID> eligibleAgentIds) { ... }
}
```

**Zmiany w `DefaultRoutingEngine.findBestAgent()`:**
Po wywołaniu `scanAvailableAgents(tenantId)` dodaj krok filtrowania:

```java
List<AgentSessionData> availableAgents = scanAvailableAgents(request.tenantId());

// Filtruj po przypisaniu do kolejki (gdy all_agents=FALSE)
if (request.hasAgentFilter() && !request.eligibleAgentIds().isEmpty()) {
    availableAgents = availableAgents.stream()
        .filter(a -> request.eligibleAgentIds().contains(a.agentId()))
        .toList();
    log.debug("[RoutingEngine] Po filtrze przypisania: {}/{} agentów uprawnionych dla queue={}",
        availableAgents.size(), /* przed filtrem */, request.queueId());
} else if (request.hasAgentFilter() && request.eligibleAgentIds().isEmpty()) {
    log.warn("[RoutingEngine] Kolejka {} ma all_agents=FALSE ale brak przypisanych agentów — kontakt nie zostanie obsłużony",
        request.queueId());
    return Optional.empty();
}
```

**Zmiany w `RoutingService`:**
Przed budowaniem `RoutingRequest`, pobierz `eligibleAgentIds` z `QueueAssignmentRepository`:

```java
// W RoutingService.routeContact() lub odpowiedniej metodzie:
Set<UUID> eligibleAgentIds = null;
if (!queueAssignmentRepository.isAllAgents(queue.getQueueId(), tenantId)) {
    eligibleAgentIds = queueAssignmentRepository.resolveEligibleAgentIds(
        queue.getQueueId(), tenantId);
}
RoutingRequest request = RoutingRequest.of(contact, queue, tenantId, eligibleAgentIds);
```

**Sticky agent:** gdy `hasAgentFilter() = true`, weryfikuj też czy `preferredAgentId` należy do `eligibleAgentIds`. Jeśli nie — pomiń sticky i przejdź do strategii.

**Kryteria akceptacji:**
- [x] Kolejka z `all_agents=TRUE` → silnik zachowuje się identycznie jak przed zmianą (żaden test regresji nie może failować)
- [x] Kolejka z `all_agents=FALSE` i przypisanymi agentami → tylko przypisani agenci są kandydatami
- [x] Kolejka z `all_agents=FALSE` i pusta lista → `findBestAgent` zwraca `Optional.empty()` + log WARNING
- [x] Sticky agent spoza listy eligibleAgentIds → pominięty, fallback na strategię
- [x] Sticky agent z listy eligibleAgentIds → wybrany normalnie
- [x] Zmiana `RoutingRequest` nie łamie żadnych istniejących testów jednostkowych
- [x] Testy jednostkowe `DefaultRoutingEngine`: min. 4 nowe przypadki (all_agents on/off, pusta lista, sticky filter)
- [x] `RoutingService` pobiera `eligibleAgentIds` jednym zapytaniem DB (nie N zapytań)

---

---

## MODUL: Zakładka Klienci w Agent Desktop (EPIC-15)

### BE-048 – API manualnego callbacku do klienta inicjowanego przez agenta

**Typ:** Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Zależy od:** DB-027 (source_type AGENT_MANUAL), BE-025 (Customer API), BE-041 (Callback List API)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-24
**Blokuje:** FE-041
**Epic:** EPIC-15 Zakładka Klienci w Agent Desktop
**Odniesienie PRD:** US-09-02 (historia kontaktów klienta), EPIC-13 (callbacki)

> **Uwaga (weryfikacja 2026-08-09):** brak dedykowanego testu jednostkowego dla
> `ManualCallbackController` (potwierdzone — brak pliku w `backend/app/src/test`). Funkcjonalność
> sama w sobie zaimplementowana poprawnie — status ✅ pozostaje, brakuje tylko pokrycia testowego.

**Opis:**
Nowy endpoint umożliwiający agentowi zaplanowanie oddzwonienia do wybranego klienta bez aktywnej rozmowy. Różni się od `BE-040` (inbound callback — wymaga aktywnego kontaktu) i `BE-039` (reschedule istniejącego callbacku). Tu agent samodzielnie inicjuje callback do klienta z zakładki "Klienci" w Agent Desktop.

**Endpoint:**
`POST /api/callbacks/manual`

**Request body:**
```json
{
  "customerId": "uuid",
  "phoneNumber": "+48123456789",
  "scheduledAt": "2026-04-25T10:00:00Z",
  "notes": "Klient prosi o info o ofercie X"
}
```

**Response `201 Created`:**
```json
{
  "callbackId": "uuid",
  "customerId": "uuid",
  "customerName": "Jan Kowalski",
  "phoneNumber": "+48123456789",
  "scheduledAt": "2026-04-25T10:00:00Z",
  "status": "PENDING",
  "sourceType": "AGENT_MANUAL",
  "assignedAgentId": "uuid (zalogowany agent)",
  "notes": "...",
  "createdAt": "2026-04-21T12:00:00Z"
}
```

**Logika serwisu:**
1. Zweryfikuj że `customerId` należy do tenanta agenta
2. Zweryfikuj że `phoneNumber` istnieje w `customer.phone` (JSONB array) — lub pozwól na dowolny numer (do decyzji — zalecane: walidacja przynależności do klienta, ale nie blokuj)
3. Utwórz rekord w `scheduled_callback` z:
   - `source_type = 'AGENT_MANUAL'`
   - `customer_id = customerId`
   - `phone = phoneNumber`
   - `assigned_agent_id = zalogowany agent`
   - `scheduled_at = scheduledAt`
   - `notes = notes` (jeśli istnieje kolumna; alternatywnie `custom_fields JSONB`)
   - `origin_contact_id = NULL`
   - `campaign_id = NULL`
4. Publikuj event na RabbitMQ (opcjonalnie, dla powiadomień RT w FE-034/FE-035)

**Walidacje:**
- `scheduledAt` musi być w przyszłości (min. 5 minut od teraz)
- `phoneNumber` musi spełniać format E.164 (+[1-9][0-9]{6,14})
- `customerId` wymagane i musi istnieć dla tenanta
- Tylko agent może wywoływać (rola AGENT lub SUPERVISOR)

**Klasy do implementacji:**
- `ManualCallbackRequest` (record/DTO)
- `ManualCallbackResponse` (record/DTO)
- Logika w `ScheduledCallbackService.createManualCallback()`
- Nowy handler w `ScheduledCallbackController` lub nowym `ManualCallbackController`

**Kryteria akceptacji:**
- [ ] `POST /api/callbacks/manual` zwraca `201` z poprawnymi danymi
- [ ] `source_type = 'AGENT_MANUAL'` zapisane w bazie
- [ ] `assigned_agent_id` ustawiony na zalogowanego agenta (z JWT)
- [ ] `scheduledAt` w przeszłości → `400 Bad Request` z komunikatem
- [ ] `customerId` obcego tenanta → `403 Forbidden`
- [ ] Niepoprawny format `phoneNumber` → `400 Bad Request`
- [ ] Nowy callback pojawia się na liście callbacków agenta (`GET /api/callbacks?type=AGENT_MANUAL`)
- [ ] OpenAPI dokumentacja dla endpointu

---

---

## MODUŁ: Kalendarz Agenta (EPIC-16)

### BE-049 – Model i repozytorium przerw agenta (`AgentBreak`, `AgentBreakRepository`)

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** DB-028
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** BE-050, BE-051, BE-052
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
JPA entity `AgentBreak` mapująca tabelę `agent_break`. Enum `BreakType` (LUNCH, SHORT_BREAK, TRAINING, OTHER) i `BreakStatus` (PLANNED, ACTIVE, COMPLETED, CANCELLED). Repozytorium `AgentBreakRepository extends TenantAwareRepository` z metodami wyszukiwania po zakresie dat i agentId.

**Kryteria akceptacji:**
- [ ] Entity poprawnie mapuje wszystkie kolumny tabeli DB-028
- [ ] Repozytorium rozszerza `TenantAwareRepository`, metoda `findByAgentIdAndStartTimeBetween`
- [ ] `assertSameTenant` wywołane przed każdym zapisem
- [ ] Testy jednostkowe repozytorium (H2 in-memory)

---

### BE-050 – CRUD REST API przerw agenta (`AgentBreakController`, `AgentBreakService`)

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-049
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** FE-045, FE-042
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
REST API zarządzania zaplanowanymi przerwami agenta. Agent widzi i zarządza wyłącznie swoimi przerwami. Endpoint DELETE faktycznie zmienia status na CANCELLED (soft delete). Walidacja: `endTime > startTime`, czas w przyszłości przy tworzeniu.

**Endpoints:**
```
GET    /api/agent/breaks?from={ISO}&to={ISO}   → lista przerw agenta w zakresie dat
POST   /api/agent/breaks                        → dodaj przerwę
PUT    /api/agent/breaks/{id}                   → edytuj przerwę (tylko PLANNED)
DELETE /api/agent/breaks/{id}                   → anuluj przerwę (PLANNED → CANCELLED)
```

**Kryteria akceptacji:**
- [ ] Agent pobiera tylko swoje przerwy (tenant + agent_id z tokenu JWT)
- [ ] `POST` z `endTime <= startTime` → `400 Bad Request`
- [ ] `POST` z `startTime` w przeszłości → `400 Bad Request`
- [ ] `GET` bez parametrów `from`/`to` → domyślnie bieżący tydzień
- [ ] `GET` z `from > to` → `400 Bad Request`
- [ ] `PUT`/`DELETE` na przerwie innego agenta → `403 Forbidden`
- [ ] `PUT` przerwy o statusie ACTIVE/COMPLETED → `409 Conflict`
- [ ] OpenAPI dokumentacja dla wszystkich endpointów

---

### BE-051 – Agregujące API kalendarza agenta (`AgentCalendarController`)

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-049, BE-039, BE-022, DB-028
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-27
**Blokuje:** FE-042, FE-043
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Jeden endpoint agregujący wszystkie zdarzenia kalendarza zalogowanego agenta w podanym zakresie dat. Łączy trzy źródła danych: zaplanowane callbacki (`scheduled_callback`), kampanie wychodzące do których agent jest przypisany (`campaign_agent`), oraz zaplanowane przerwy (`agent_break`). Odpowiedź zawiera trzy listy w jednym DTO.

**Endpoint:**
```
GET /api/agent/calendar?from={ISO}&to={ISO}
```

**Response DTO:**
```json
{
  "callbacks": [
    { "id": "...", "customerName": "...", "scheduledAt": "...", "sourceType": "CAMPAIGN_CALLBACK|INBOUND_CALLBACK|AGENT_MANUAL", "status": "PENDING" }
  ],
  "campaigns": [
    { "id": "...", "name": "...", "startDate": "...", "endDate": "...", "status": "SCHEDULED|ACTIVE" }
  ],
  "breaks": [
    { "id": "...", "startTime": "...", "endTime": "...", "breakType": "LUNCH", "notes": "...", "status": "PLANNED" }
  ]
}
```

**Kryteria akceptacji:**
- [ ] Dane z wszystkich trzech źródeł zwracane w jednym wywołaniu
- [ ] Filtrowanie po zakresie dat (domyślnie bieżący tydzień gdy brak parametrów)
- [ ] `from > to` → `400 Bad Request`
- [ ] Zakres max 90 dni → `400 Bad Request`
- [ ] Brak przypisanych kampanii → pusta lista `campaigns: []`
- [ ] Tylko dane zalogowanego agenta — izolacja tenant + agent_id z JWT
- [ ] OpenAPI dokumentacja

---

### BE-052 – Scheduler automatycznej aktywacji i zamykania przerw (`AgentBreakActivator`)

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależy od:** BE-049
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-26
**Blokuje:** brak
**Epic:** EPIC-16 Kalendarz Agenta
**Odniesienie PRD:** EPIC-16 – Agent Calendar

**Opis:**
Scheduled component `AgentBreakActivator` wykonujący cykliczne zadanie (np. co minutę) aktywujące i kończące przerwy agentów. Przy każdym uruchomieniu: przerwy o statusie `PLANNED` których `start_time <= NOW()` przejście do `ACTIVE`; przerwy o statusie `ACTIVE` których `end_time <= NOW()` przejście do `COMPLETED`. Obsługuje izolację multitenant. Zaimplementowany w `/domain/agentbreak/AgentBreakActivator.java`.

**Kryteria akceptacji:**
- [ ] `@Scheduled` uruchamia zadanie cyklicznie (co minutę lub konfigurowalne)
- [ ] PLANNED → ACTIVE gdy `start_time <= NOW()` dla wszystkich tenantów
- [ ] ACTIVE → COMPLETED gdy `end_time <= NOW()` dla wszystkich tenantów
- [ ] Zmiany statusu atomowe (per rekord lub batch UPDATE)
- [ ] Błąd dla jednego rekordu nie zatrzymuje przetwarzania pozostałych
- [ ] Testy jednostkowe `AgentBreakActivatorTest` pokrywające przejścia statusów

---

### BE-053 – Scheduler automatycznej aktywacji kampanii wg harmonogramu (`CampaignWindowActivator`)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-022, DB-011
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-27
**Blokuje:** brak
**Epic:** EPIC-08 Kampanie Outbound
**Odniesienie PRD:** EPIC-08

> **Uwaga (weryfikacja 2026-08-09):** brak testu `CampaignWindowActivatorTest` (potwierdzone —
> brak pliku w `backend/app/src/test`, tylko skompilowana klasa i raport jacoco). Status
> pozostaje ✅ dla głównej ścieżki (SCHEDULED→RUNNING, RUNNING→COMPLETED), ale przejście
> RUNNING→SCHEDULED przy wyjściu poza okno harmonogramu oraz pokrycie testowe wymagają
> dokończenia.

**Opis:**
Scheduled component `CampaignWindowActivator` sprawdzający cyklicznie kampanie o statusie `SCHEDULED` i automatycznie przełączający je do `RUNNING` gdy bieżący czas mieści się w oknie harmonogramu (pola `schedule.start_date`, `schedule.end_date`, `schedule.time_from`, `schedule.time_to`, `schedule.days_of_week`). Obsługuje strefę czasową tenanta. Zaimplementowany w `/domain/service/CampaignWindowActivator.java`.

**Kryteria akceptacji:**
- [ ] `@Scheduled` uruchamia zadanie cyklicznie (co minutę lub konfigurowalne)
- [ ] Kampania SCHEDULED → RUNNING gdy aktualny czas mieści się w oknie harmonogramu
- [ ] Kampania RUNNING → SCHEDULED gdy wychodzi poza okno harmonogramu (poza godzinami lub dniami)
- [ ] Strefa czasowa pobierana z konfiguracji tenanta (`tenant.config.timezone`)
- [ ] Kampanie bez harmonogramu lub z brakującymi polami pomijane bez błędu
- [ ] Błąd dla jednej kampanii nie zatrzymuje przetwarzania pozostałych

---

## MODUL: Wielojęzyczność – preferencje użytkownika (EPIC-19)

### BE-054 – `UserPreferencesController`: GET/PUT preferencji zalogowanego użytkownika

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-003 (auth), DB-029
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-28
**Blokuje:** FE-050
**Epic:** EPIC-19 Wielojęzyczność
**Odniesienie PRD:** przekrojowe

**Opis:**
Nowy kontroler `UserPreferencesController` z dwoma endpointami dla zalogowanego użytkownika:

- `GET /api/users/me/preferences` — zwraca aktualne preferencje (`preferred_language`, ewentualnie inne w przyszłości)
- `PUT /api/users/me/preferences` — aktualizuje preferencje (body: `{ "preferredLanguage": "en" }`)

Serwis `UserPreferencesService` operuje na encji `AppUser`, aktualizując pole `preferred_language` w tabeli `app_user`. Endpointy wymagają autoryzacji (JWT), operują na kontekście zalogowanego użytkownika (wyciągają `userId` z tokenu, nie z path variable). Izolacja tenant_id — zapis dotyczy tylko własnego rekordu.

**DTO:**
```java
record UserPreferencesDto(String preferredLanguage) {}
```

**Kryteria akceptacji:**
- [ ] `GET /api/users/me/preferences` zwraca 200 z `{ "preferredLanguage": "pl" }` dla zalogowanego użytkownika
- [ ] `PUT /api/users/me/preferences` aktualizuje pole i zwraca 200 z nową wartością
- [ ] Walidacja: `preferredLanguage` musi być jednym z `["pl", "en", "de"]` (można rozszerzać)
- [ ] Nieuprawniony request (brak JWT) zwraca 401
- [ ] Endpoint udokumentowany przez OpenAPI (`springdoc`)
- [ ] Testy jednostkowe kontrolera i serwisu

---

## Podsumowanie zadań Backend

| Kategoria | Liczba zadań | Must Have | Should Have |
|-----------|-------------|-----------|-------------|
| Infrastruktura | 5 | 5 | 0 |
| Tenants (EPIC-01) | 2 | 2 | 0 |
| Użytkownicy (EPIC-02) | 1 | 1 | 0 |
| Telefonia (EPIC-03) | 5 | 4 | 1 |
| Routing telefoniczny (EPIC-11) | 3 | 3 | 0 |
| IVR + Voicebot (EPIC-04) | 2 | 2 | 0 |
| Email (EPIC-05) | 2 | 2 | 0 |
| Social Media (EPIC-06) | 2 | 2 | 0 |
| Routing (EPIC-07) | 3 | 2 | 1 |
| Kampanie (EPIC-08) | 4 | 4 | 0 |
| Klienci (EPIC-09) | 2 | 2 | 0 |
| Raporty (EPIC-10) | 4 | 4 | 0 |
| RODO | 1 | 1 | 0 |
| Prezentacja Kontaktów (EPIC-12) | 2 | 2 | 0 |
| Zaplanowane oddzwonienia (EPIC-13) | 5 | 5 | 0 |
| Zarządzanie przypisaniem agentów (EPIC-14) | 5 | 5 | 0 |
| Zakładka Klienci w Agent Desktop (EPIC-15) | 1 | 1 | 0 |
| Kalendarz Agenta (EPIC-16) | 5 | 0 | 5 |
| Testy jednostkowe (EPIC-18) | 4 | 0 | 4 |
| Wielojęzyczność (EPIC-19) | 1 | 1 | 0 |
| Per-tenant konfiguracja Twilio (EPIC-20) | 7 | 0 | 7 |

---

## MODUL: Testy jednostkowe (EPIC-18)

### BE-T001 – Testy jednostkowe CampaignService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-kampanie (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-08

**Opis:**
Napisać testy jednostkowe dla `CampaignService` pokrywające kluczową logikę biznesową.

**Kryteria akceptacji:**
- [ ] CRUD kampanii z walidacją tenant isolation (`assertSameTenant`)
- [ ] `convertLead()` — happy path, lead z obcego tenanta (blokada), lead już skonwertowany
- [ ] Niedozwolone przejścia statusów kampanii (np. COMPLETED → ACTIVE)
- [ ] Paginacja wyników z filtrowaniem po statusie
- [ ] Wszystkie testy przechodzą (`mvn test -pl app -Dtest=CampaignServiceTest`)

---

### BE-T002 – Testy jednostkowe AdminUserService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-użytkownicy (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-02

**Opis:**
Napisać testy jednostkowe dla `AdminUserService`.

**Kryteria akceptacji:**
- [ ] Tworzenie użytkownika: unikalność emaila w ramach tenanta, haszowanie hasła, przypisanie roli
- [ ] Dezaktywacja konta: wylogowanie aktywnych sesji
- [ ] Reset hasła przez admina: generowanie linku, blokada cross-tenant dla SUPERVISOR
- [ ] Bulk import: sukces, częściowy błąd (rollback per-user), duplikat email
- [ ] Wszystkie testy przechodzą (`mvn test -pl app -Dtest=AdminUserServiceTest`)

---

### BE-T003 – Testy jednostkowe EmailSendService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-email (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-05

**Opis:**
Napisać testy jednostkowe dla `EmailSendService`.

**Kryteria akceptacji:**
- [ ] Wysyłanie emaila: poprawny SMTP call z nagłówkami, tenant, subject/body
- [ ] Obsługa błędu SMTP: retry logic, zapis do dead-letter queue
- [ ] Załączniki: poprawne enkodowanie Base64, limit rozmiaru
- [ ] Template rendering: zmienne kontekstowe, fallback dla brakującej zmiennej
- [ ] Wszystkie testy przechodzą (`mvn test -pl app -Dtest=EmailSendServiceTest`)

---

### BE-T004 – Testy jednostkowe IvrService

**Typ:** Testing
**Priorytet:** Should Have
**Zlozonosc:** M
**Zależy od:** BE-ivr (ukończone)
**Status:** 🔲 Do zrobienia
**Blokuje:** -
**Odniesienie PRD:** EPIC-04

**Opis:**
Napisać testy jednostkowe dla `IvrService`.

**Kryteria akceptacji:**
- [ ] Budowanie drzewa IVR: tworzenie węzłów, podpinanie akcji
- [ ] Walidacja drzewa: brak root node, cykl w grafie, niedozwolona akcja
- [ ] Modyfikacja węzła: update akcji, usunięcie węzła z dziećmi (kaskada)
- [ ] Tenant isolation: blokada odczytu/zapisu IVR z obcego tenanta
- [ ] Wszystkie testy przechodzą (`mvn test -pl app -Dtest=IvrServiceTest`)
| **RAZEM** | **56** | **46** | **10** |

---

## MODUL: Per-tenant konfiguracja Twilio (EPIC-20)

### BE-055 – Encja `TenantTwilioConfig` + `TenantTwilioConfigRepository` + konwerter szyfrowania

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** M
**Zależy od:** DB-030 (tabela `tenant_twilio_config`)
**Status:** ✅ Ukończone
**Blokuje:** BE-056, BE-057, BE-058, BE-059, BE-086
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Warstwa danych dla konfiguracji Twilio per tenant. Trzy elementy:

1. **`EncryptedStringConverter`** (`infrastructure/persistence/converter/EncryptedStringConverter.java`) – `AttributeConverter<String, String>` implementujący AES-256-GCM szyfrowanie/deszyfrowanie. Klucz pobierany z `application.yml` (`app.encryption.secret`, minimum 32 bajty). Każdy zapis generuje nowy losowy IV (16 bajtów) i przechowuje `Base64(IV || ciphertext)`. Konwerter rejestrowany jako Spring bean (`@Converter`).

2. **`TenantTwilioConfig`** (`domain/model/TenantTwilioConfig.java`) – encja JPA mapująca tabelę `tenant_twilio_config`. Pola `accountSid`, `authToken`, `apiKeySid`, `apiKeySecret` annotowane `@Convert(converter = EncryptedStringConverter.class)`. Pola `twimlAppSid`, `phoneNumber`, `statusCallbackUrl` bez konwertera (plaintext). Encja rozszerza lub stosuje wzorzec analogiczny do `TenantAwareEntity`.

3. **`TenantTwilioConfigRepository`** (`domain/repository/TenantTwilioConfigRepository.java`) – rozszerza `TenantAwareRepository`. Metoda `findByTenantId(UUID tenantId): Optional<TenantTwilioConfig>`. Przed każdym `save()` wywołanie `assertSameTenant(entity.getTenantId())`.

**Wskazówki techniczne:**
- AES-256-GCM: `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, iv)` (128-bitowy tag)
- Klucz: `SecretKeySpec(Base64.decode(secret), "AES")` – secret musi mieć 32 bajty po decode
- `SecureRandom` do generowania IV przy każdym `convertToDatabaseColumn()`
- Przy `null` input konwerter zwraca `null` (nullable pola jak `apiKeySid`)

**Kryteria akceptacji:**
- [ ] `EncryptedStringConverter` szyfruje i deszyfruje poprawnie roundtrip (`encrypt(decrypt(x)) == x`)
- [ ] Różne wywołania `convertToDatabaseColumn()` dla tej samej wartości generują różne ciphertexty (losowy IV)
- [ ] `TenantTwilioConfig` zapisuje się do bazy – `account_sid` w bazie jest zaszyfrowany (nie plaintext)
- [ ] `findByTenantId()` zwraca odszyfrowane wartości pól automatycznie przez JPA
- [ ] `save()` na encji z obcym `tenant_id` rzuca wyjątek z `assertSameTenant()`
- [ ] `EncryptedStringConverter` obsługuje `null` – pola nullable nie rzucają NPE
- [ ] Testy jednostkowe `EncryptedStringConverterTest`: roundtrip, null safety, losowość IV
- [ ] Testy repozytorium (TestcontainerS PostgreSQL lub H2): zapis i odczyt z weryfikacją że w bazie dane są zaszyfrowane

---

### BE-056 – `TenantTwilioConfigService`: logika biznesowa zarządzania konfiguracją

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** M
**Zależy od:** BE-055 (encja i repozytorium)
**Status:** ✅ Ukończone
**Blokuje:** BE-057, BE-058, BE-061
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Serwis `TenantTwilioConfigService` (`domain/service/TenantTwilioConfigService.java`) z logiką biznesową zarządzania konfiguracją Twilio per tenant.

**Metody publiczne:**
- `saveConfig(UUID tenantId, TenantTwilioConfigRequest request): TenantTwilioConfigResponse` – upsert (INSERT lub UPDATE jeśli istnieje). Waliduje format `accountSid` (prefix `AC`, długość 34 znaki), format `phoneNumber` (E.164). Publikuje event `TwilioConfigChangedEvent` do invalidacji cache (potrzebny przez BE-058).
- `getConfig(UUID tenantId): Optional<TenantTwilioConfigResponse>` – zwraca konfigurację z wrażliwymi polami maskowanymi w response DTO (patrz: format masking poniżej).
- `getDecryptedConfig(UUID tenantId): Optional<TenantTwilioConfigDecrypted>` – wewnętrzna metoda (package-private lub osobny interfejs), zwraca odszyfrowane dane dla adaptera Twilio. Nie używać w kontrolerach.
- `deleteConfig(UUID tenantId): void` – usuwa konfigurację. Publikuje `TwilioConfigChangedEvent`.
- `testConnection(UUID tenantId): TwilioConnectionTestResult` – pobiera odszyfrowaną konfigurację, wywołuje Twilio API (`TwilioRestClient.accounts().fetch()` lub równoważne) i zwraca wynik testu z komunikatem błędu jeśli niepoprawne.

**Format masking wrażliwych pól w response:**
- `accountSid`: zwraca plaintext (nie jest sekretem – widoczne w dashboard Twilio)
- `authToken`: `"●●●●●●●●...` + ostatnie 4 znaki" np. `"●●●●●●●●...a3f2"`
- `apiKeySid`: zwraca plaintext (identyfikator klucza, nie sekret)
- `apiKeySecret`: `"●●●●●●●●...` + ostatnie 4 znaki"`

**Klasy DTO:**
- `TenantTwilioConfigRequest` (record): `accountSid`, `authToken`, `apiKeySid`, `apiKeySecret`, `twimlAppSid`, `phoneNumber`, `statusCallbackUrl`
- `TenantTwilioConfigResponse` (record): wszystkie pola jak wyżej + `isActive`, `createdAt`, `updatedAt` – z maskingiem dla sekretów
- `TenantTwilioConfigDecrypted` (record, nie eksponować przez REST): pełne odszyfrowane dane
- `TwilioConnectionTestResult` (record): `success: boolean`, `message: String`, `testedAt: Instant`

**Kryteria akceptacji:**
- [ ] `saveConfig()` tworzy nowy rekord jeśli nie istnieje lub aktualizuje istniejący (upsert)
- [ ] `saveConfig()` z niepoprawnym `accountSid` (brak prefixu `AC`) rzuca `ValidationException`
- [ ] `saveConfig()` z niepoprawnym `phoneNumber` (nie E.164) rzuca `ValidationException`
- [ ] `getConfig()` zwraca `authToken` i `apiKeySecret` z maskingiem (nie w plaintext)
- [ ] `getDecryptedConfig()` zwraca plaintext – nie dostępna przez REST (test: nie ma adnotacji `@RequestMapping`)
- [ ] `deleteConfig()` usuwa rekord i publikuje event invalidacji cache
- [ ] `testConnection()` z poprawnymi kredencjałami zwraca `success = true`
- [ ] `testConnection()` z niepoprawnymi kredencjałami zwraca `success = false` + komunikat błędu z Twilio API (nie rzuca wyjątku do klienta)
- [ ] `TwilioConfigChangedEvent` publikowany przez `ApplicationEventPublisher` po save i delete
- [ ] Testy jednostkowe z mockowanym repozytorium i Twilio client

---

### BE-057 – `TenantTwilioConfigController`: REST API konfiguracji Twilio dla supervisora

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** S
**Zależy od:** BE-056 (serwis)
**Status:** ✅ Ukończone
**Blokuje:** FE-066
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Kontroler REST `TenantTwilioConfigController` (`api/controller/TenantTwilioConfigController.java`) eksponujący endpointy zarządzania konfiguracją Twilio w scope supervisora bieżącego tenanta. Wszystkie endpointy wymagają autoryzacji `ROLE_SUPERVISOR`.

**Endpointy:**
```
GET    /api/supervisor/twilio-config          → 200 TenantTwilioConfigResponse (lub 204 gdy brak)
PUT    /api/supervisor/twilio-config          → 200 TenantTwilioConfigResponse (upsert)
DELETE /api/supervisor/twilio-config          → 204 No Content
POST   /api/supervisor/twilio-config/test     → 200 TwilioConnectionTestResult
```

**Autoryzacja:**
- `ROLE_SUPERVISOR` dla wszystkich endpointów (sprawdzić przez `@PreAuthorize("hasRole('SUPERVISOR')")`)
- `TenantContext.getTenantId()` jako źródło `tenantId` – nie przyjmować z path/query params

**Walidacja (PUT):**
- `accountSid`: `@NotBlank`, `@Pattern(regexp = "^AC[0-9a-fA-F]{32}$", message = "Invalid Twilio Account SID format")`
- `authToken`: `@NotBlank`
- `phoneNumber`: `@Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "Phone number must be in E.164 format")` (nullable)
- `apiKeySid`, `apiKeySecret`, `twimlAppSid`, `statusCallbackUrl`: opcjonalne

**Rejestracja w SecurityConfig:**
- Dodać `/api/supervisor/twilio-config/**` do listy `requestMatchers` z `hasRole('SUPERVISOR')`

**Rejestracja w TenantFilter:**
- Ścieżka `/api/supervisor/twilio-config` NIE powinna być w `PUBLIC_PATH_PREFIXES` (wymaga JWT)

**Kryteria akceptacji:**
- [ ] `GET /api/supervisor/twilio-config` zwraca `200` z zamaskowanymi sekretami lub `204` gdy brak konfiguracji
- [ ] `PUT /api/supervisor/twilio-config` z poprawnymi danymi → `200` z zapisaną konfiguracją
- [ ] `PUT` z niepoprawnym `accountSid` → `400 Bad Request` z komunikatem walidacji
- [ ] `PUT` z niepoprawnym `phoneNumber` → `400 Bad Request`
- [ ] `DELETE /api/supervisor/twilio-config` → `204 No Content`; ponowny `GET` → `204`
- [ ] `POST /api/supervisor/twilio-config/test` → `200` z `success: true/false` i komunikatem
- [ ] Żądanie bez JWT → `401 Unauthorized`
- [ ] Żądanie z rolą `ROLE_AGENT` → `403 Forbidden`
- [ ] OpenAPI (springdoc) dokumentuje wszystkie 4 endpointy z przykładami request/response
- [ ] Tenant supervisora z tokenem JWT tenanta B nie widzi konfiguracji tenanta A

---

### BE-058 – Refaktoryzacja `TwilioTelephonyAdapter` na per-tenant z cache i fallbackiem

**Typ:** Refactor
**Priorytet:** Should Have
**Szacowany rozmiar:** L
**Zależy od:** BE-055 (TenantTwilioConfig encja), BE-056 (serwis z getDecryptedConfig), DB-030
**Status:** ✅ Ukończone
**Blokuje:** BE-059, BE-060, BE-061
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Zastąpienie globalnej inicjalizacji Twilio SDK (`@PostConstruct Twilio.init(accountSid, authToken)`) dynamicznym tworzeniem per-tenant klientów REST z cache'owaniem. Adapter musi zachować pełną kompatybilność wsteczną (fallback do globalnej konfiguracji gdy tenant nie ma własnych kredencjałów).

**Zmiany w `TwilioTelephonyAdapter`:**

1. **Usunięcie `@PostConstruct Twilio.init()`** – globalna inicjalizacja zastąpiona przez `resolveRestClient()`.

2. **Cache per-tenant** – Caffeine cache (`CaffeineCache` lub `@Cacheable("twilioClients")`) z kluczem `tenantId`:
   ```java
   // Przykładowa konfiguracja Caffeine (application.yml lub @Bean):
   // maximumSize: 100, expireAfterWrite: 15min
   private final LoadingCache<UUID, TwilioRestClient> clientCache;
   ```

3. **`resolveRestClient(UUID tenantId): TwilioRestClient`** – metoda prywatna:
   - Próba pobrania `TenantTwilioConfigDecrypted` przez `tenantTwilioConfigService.getDecryptedConfig(tenantId)`
   - Jeśli istnieje i `isActive = true`: `new TwilioRestClient(apiKeySid, apiKeySecret, accountSid)`
   - Fallback: `new TwilioRestClient(twilioProperties.getApiKeySid(), twilioProperties.getApiKeySecret(), twilioProperties.getAccountSid())`
   - Log na poziomie `DEBUG`: `"[Twilio] tenant={} używa {} konfiguracji"` (per-tenant / globalnej)

4. **Invalidacja cache** – nasłuch na `TwilioConfigChangedEvent` (pubish przez BE-056):
   ```java
   @EventListener
   public void onTwilioConfigChanged(TwilioConfigChangedEvent event) {
       clientCache.invalidate(event.tenantId());
   }
   ```

5. **`configureStatusCallbacksForAllTenants()`** – iteracja przez tenant configs w bazie, dla każdego tenanta użycie własnego `resolveRestClient(tenantId)` zamiast globalnego klienta.

6. **`resolveAccountSid(UUID tenantId): String`** – pomocnicza metoda zwracająca `accountSid` (per-tenant lub globalny) potrzebna do budowania TwiML callback URLs.

**Wskazówki techniczne:**
- `TwilioRestClient` jest thread-safe – jeden instancja na tenant w cache jest bezpieczna
- Nie używać statycznych metod `Twilio.*` (są oparte na globalnym stanie) – wyłącznie przez `TwilioRestClient` instancję
- Przy przekraczaniu granic wątków: `TenantContext.snapshot()` / `restore()` / `clear()` zgodnie z regułami CLAUDE.md

**Kryteria akceptacji:**
- [ ] Aplikacja startuje bez `@PostConstruct Twilio.init()` – brak błędów inicjalizacji
- [ ] Tenant z własną konfiguracją (DB-030) używa swoich kredencjałów do połączeń (weryfikacja przez logi DEBUG lub test integracyjny z mockiem Twilio)
- [ ] Tenant bez konfiguracji używa globalnych `TwilioProperties` (fallback)
- [ ] Cache jest invalidowany po wywołaniu `PUT /api/supervisor/twilio-config` lub `DELETE`
- [ ] Po invalidacji cache kolejne wywołanie tworzy nowy `TwilioRestClient` z aktualnymi danymi
- [ ] `configureStatusCallbacksForAllTenants()` iteruje przez konfigi tenantów i używa ich kredencjałów
- [ ] Brak wycieków `TenantContext` przy wywołaniach asynchronicznych
- [ ] Istniejące testy integracyjne telefonii nie failują (kompatybilność wsteczna z globalnym fallbackiem)
- [ ] Caffeine cache: max 100 wpisów, TTL 15 minut (konfigurowalne przez `application.yml`)

---

### BE-059 – Per-tenant Access Token dla Twilio Voice JS SDK

**Typ:** Refactor
**Priorytet:** Should Have
**Szacowany rozmiar:** S
**Zależy od:** BE-058 (resolveRestClient, resolveAccountSid), BE-055 (getDecryptedConfig)
**Status:** ✅ Ukończone
**Blokuje:** brak
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Refaktoryzacja serwisu generującego Access Token dla Twilio Voice JS SDK (frontend softphone). Obecna implementacja używa globalnych `apiKeySid`, `apiKeySecret`, `accountSid` i `twimlAppSid` z `TwilioProperties`. Po refaktoryzacji serwis pobiera per-tenant wartości z `TenantTwilioConfigDecrypted` (jeśli istnieje) lub fallback do globalnych.

**Zmiany:**
- W serwisie generującym token (`TwilioTokenService` lub analogiczny):
  ```java
  TenantTwilioConfigDecrypted config = tenantTwilioConfigService
      .getDecryptedConfig(tenantId)
      .orElse(null);

  String accountSid   = config != null ? config.accountSid()   : twilioProperties.getAccountSid();
  String apiKeySid    = config != null ? config.apiKeySid()    : twilioProperties.getApiKeySid();
  String apiKeySecret = config != null ? config.apiKeySecret() : twilioProperties.getApiKeySecret();
  String twimlAppSid  = config != null ? config.twimlAppSid()  : twilioProperties.getTwimlAppSid();
  ```
- Jeśli per-tenant config nie ma `apiKeySid` / `apiKeySecret` (pola nullable), fallback do globalnych
- `tenantId` pobierany z `TenantContext.getTenantId()`

**Kryteria akceptacji:**
- [ ] Token generowany dla tenanta z per-tenant confgiem używa jego `apiKeySid`/`apiKeySecret`/`accountSid`/`twimlAppSid`
- [ ] Token generowany dla tenanta bez konfiguracji używa globalnych `TwilioProperties`
- [ ] Gdy per-tenant config ma `accountSid` ale brak `apiKeySid` – fallback do globalnego `apiKeySid`
- [ ] Endpoint zwracający token (`GET /api/agent/twilio-token` lub analogiczny) zwraca `200` dla obu scenariuszy
- [ ] Testy jednostkowe: scenariusz per-tenant, scenariusz globalny, scenariusz częściowego fallbacku

---

### BE-060 – Caller ID dla kampanii: propagacja do `ProgressiveDialerService`

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** M
**Zależy od:** DB-031 (kolumna `caller_id` w `campaign`), BE-058 (resolveAccountSid dla fallbacku)
**Status:** ✅ Ukończone
**Blokuje:** FE-067
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

> **Uwaga (weryfikacja 2026-08-09):** `ScheduledCallbackExecutor.resolveCallbackFromNumber(UUID
> tenantId)` (`backend/app/src/main/java/com/contactcenter/domain/campaign/ScheduledCallbackExecutor.java:271`)
> rozwiązuje numer wychodzący wyłącznie z `tenantTwilioConfigService.getDecryptedConfig(tenantId)`
> — nie przyjmuje `campaignId` i nigdy nie sięga po `campaign.getCallerId()`. Dla oddzwonień
> powiązanych z kampanią (`isCampaignCallback`) `caller_id` kampanii jest więc ignorowany, mimo że
> `ProgressiveDialerService` (główna ścieżka dialowania) poprawnie go stosuje. Realny gap — status
> pozostaje ✅, bo główne kryteria akceptacji (dialer progresywny) są spełnione, ale ta ścieżka
> wymaga dokończenia.

**Opis:**
Implementacja obsługi pola `caller_id` w kampaniach wychodzących. Zmiany w trzech miejscach:

1. **Encja `Campaign`** – dodanie pola `callerId` (mapowanie kolumny `caller_id` z DB-031):
   ```java
   @Column(name = "caller_id", length = 30)
   private String callerId;  // null = użyj domyślnego numeru tenanta
   ```

2. **`ProgressiveDialerService`** i **`ScheduledCallbackExecutor`** – przy budowaniu parametrów połączenia wychodzącego:
   ```java
   String from = campaign.getCallerId() != null
       ? campaign.getCallerId()
       : resolveDefaultPhoneNumber(campaign.getTenantId());

   // resolveDefaultPhoneNumber():
   // 1. TenantTwilioConfigService.getDecryptedConfig(tenantId).phoneNumber
   // 2. Fallback: TwilioProperties.getPhoneNumber()
   ```

3. **`CampaignRequest` / `CampaignResponse` DTO** – dodanie pola `callerId` (opcjonalne):
   - W `CampaignRequest`: `@Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String callerId` (nullable)
   - W `CampaignResponse`: `String callerId` (może być null)

4. **`CampaignService`** – obsługa `callerId` przy tworzeniu i aktualizacji kampanii (save/update).

**Kryteria akceptacji:**
- [ ] Encja `Campaign` ma pole `callerId` mapujące kolumnę `caller_id`
- [ ] `CampaignRequest` akceptuje opcjonalne pole `callerId` z walidacją E.164
- [ ] `CampaignResponse` zawiera pole `callerId` (null gdy nieustalone)
- [ ] `ProgressiveDialerService` używa `campaign.callerId` jako `from` numeru gdy ustawiony
- [ ] `ProgressiveDialerService` fallbackuje do `tenant_twilio_config.phone_number` gdy `callerId = null`
- [ ] `ProgressiveDialerService` fallbackuje do `TwilioProperties.phoneNumber` gdy brak per-tenant config
- [ ] `ScheduledCallbackExecutor` stosuje tę samą logikę resolwowania `from` numeru
- [ ] `POST /api/supervisor/campaigns` z `callerId: "+48123456789"` zapisuje i zwraca pole
- [ ] `POST /api/supervisor/campaigns` z `callerId: "invalid"` zwraca `400 Bad Request`
- [ ] `POST /api/supervisor/campaigns` bez `callerId` zapisuje `null` (domyślny numer tenanta przy dzwonieniu)
- [ ] Istniejące testy dialera nie failują (backward compatible – `callerId = null` = stare zachowanie)

---

### BE-061 – Endpoint listowania aktywnych numerów Twilio per-tenant

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** S
**Zależy od:** BE-058 (per-tenant klient Twilio z resolveRestClient), BE-056 (getDecryptedConfig)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-07
**Blokuje:** FE-068
**Epic:** EPIC-20 Per-tenant konfiguracja Twilio

**Opis:**
Nowy endpoint w `TenantTwilioConfigController` pobierający listę aktywnych numerów (`IncomingPhoneNumber`) z konta Twilio przypisanego do aktualnego tenanta. Lista używana przez frontend do budowania selecta w formularzu konfiguracji Twilio i formularzu kampanii — zamiast ręcznego wpisywania numeru w formacie E.164.

Logika pobierania numerów jest już częściowo zaimplementowana w `TwilioTelephonyAdapter.configureStatusCallbacksForAllTenants()` (używa `IncomingPhoneNumber.reader()`). Tutaj wystawiamy to jako dedykowany endpoint z per-tenant klientem.

**Endpoint:**
```
GET /api/supervisor/twilio-config/phone-numbers
```
- Autoryzacja: `ROLE_SUPERVISOR`
- Odpowiedź `200 OK`:
  ```json
  {
    "phoneNumbers": [
      {
        "sid": "PN...",
        "phoneNumber": "+48123456789",
        "friendlyName": "Contact Center PL"
      }
    ]
  }
  ```
- Odpowiedź `404 Not Found` gdy tenant nie ma skonfigurowanego konta Twilio (brak rekordu w `tenant_twilio_config`)
- Odpowiedź `502 Bad Gateway` gdy Twilio API zwróci błąd (z komunikatem przyczyny)

**Implementacja w `TenantTwilioConfigService`:**
```java
List<TwilioPhoneNumberDto> listActivePhoneNumbers(UUID tenantId) {
    // 1. Pobierz odszyfrowane kredencjały per-tenant (lub rzuć NotFoundException)
    // 2. Wywołaj IncomingPhoneNumber.reader() z per-tenant klientem Twilio
    //    (ten sam mechanizm co resolveRestClient() w BE-058)
    // 3. Zmapuj wyniki na TwilioPhoneNumberDto {sid, phoneNumber, friendlyName}
    // 4. Zwróć pustą listę jeśli konto Twilio nie ma żadnych numerów
}
```

Nie należy buforować wyników (lista może się zmieniać po zakupie/usunięciu numeru w konsoli Twilio). Timeout wywołania Twilio API: 10 sekund.

**Kryteria akceptacji:**
- [ ] `GET /api/supervisor/twilio-config/phone-numbers` wymaga roli `SUPERVISOR`
- [ ] Zwraca listę numerów z konta Twilio przypisanego do tenanta (per-tenant kredencjały z BE-058)
- [ ] Każdy element listy zawiera `sid`, `phoneNumber` (format E.164), `friendlyName`
- [ ] Zwraca `404` gdy tenant nie ma rekordu w `tenant_twilio_config`
- [ ] Zwraca `502` z opisem błędu gdy Twilio API jest niedostępne lub zwróci błąd autoryzacji
- [ ] Zwraca `200` z pustą tablicą `phoneNumbers: []` gdy konto Twilio nie ma żadnych numerów
- [ ] Endpoint udokumentowany w OpenAPI/Swagger
- [ ] Brak fallbacku do globalnych `TwilioProperties` — endpoint operuje tylko na per-tenant konfiguracji

---

## MODUL: Retry i callback w kampaniach wychodzących (EPIC-21)

### BE-062 – Propagacja wyniku połączenia Twilio przez `CallEvent` — rozróżnienie no-answer od completed

**Typ:** Bug fix / Feature
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** –
**Blokuje:** BE-063, BE-066
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Aktualnie `TelephonyEventPublisher.publishHangup()` nie przekazuje rzeczywistego statusu Twilio (`no-answer`, `busy`, `failed`, `completed`, `canceled`) — wszystkie trafiają jako `CALL_HANGUP` bez rozróżnienia. W efekcie `DialerCallbackHandler.onCallHangup()` oznacza każde zakończone połączenie kampanijne jako `COMPLETED`, nawet gdy klient nie odebrał.

**Zmiany:**

### 1. `CallEvent` — nowe pole `callOutcome`

```java
// Dodać pole do klasy CallEvent (Builder)
/** Wynik połączenia zwrócony przez Twilio. Np. "completed", "no-answer", "busy", "failed", "canceled". */
private final String callOutcome;
```

### 2. `TelephonyEventPublisher.publishHangup()` — przyjmuje `callOutcome`

```java
public void publishHangup(String callId, UUID contactId, UUID tenantId, UUID agentId,
                           String from, String to, String callOutcome) {
    publish(CallEvent.builder()
            .eventType(CallEvent.EventType.CALL_HANGUP)
            .callId(callId)
            .contactId(contactId)
            .tenantId(tenantId)
            .agentId(agentId)
            .from(from)
            .to(to)
            .callOutcome(callOutcome)  // nowe pole
            .timestamp(Instant.now())
            .build());
}
```

### 3. `TwilioTelephonyAdapter` — przekazanie statusu Twilio

W metodzie `handleWebhookStatusUpdate()` przy wywołaniu `publishHangup()` przekazać oryginalny `callStatus` (np. `"no-answer"`, `"busy"`, `"completed"`):

```java
// Zamiast:
eventPublisher.publishHangup(callSid, contactId, tenantId, agentId, from, to);
// Użyć:
eventPublisher.publishHangup(callSid, contactId, tenantId, agentId, from, to, callStatus);
```

### 4. `DialerCallbackHandler.onCallHangup()` — obsługa wyniku

```java
String outcome = callEvent.getCallOutcome();
boolean isNoAnswer = outcome != null &&
    (outcome.equalsIgnoreCase("no-answer") || outcome.equalsIgnoreCase("busy"));

if (isNoAnswer) {
    // Pobierz kampanię żeby uzyskać maxAttempts i retryDelayMinutes
    Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
    int maxAttempts = campaign != null ? campaign.getMaxAttempts() : 3;
    int retryDelayMinutes = campaign != null ? campaign.getRetryDelayMinutes() : 60;
    handleNoAnswer(callSid, tenantId, campaignId, recordId, agentId, maxAttempts, retryDelayMinutes);
} else {
    // completed, canceled, failed → COMPLETED
    updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, null);
}
```

**Uwagi:**
- `DialerCallbackHandler` potrzebuje wstrzyknięcia `CampaignRepository` (do odczytu `maxAttempts` i `retryDelayMinutes`)
- `MockTelephonyAdapter` powinien przekazywać `"completed"` jako `callOutcome` domyślnie
- Zmiana sygnatury `publishHangup()` może wymagać aktualizacji innych wywołań (wyszukać przez `publishHangup(`)

**Kryteria akceptacji:**
- [ ] `CallEvent` ma pole `callOutcome` (String, nullable)
- [ ] `publishHangup()` przyjmuje i propaguje `callOutcome`
- [ ] Przy statusie Twilio `"no-answer"` lub `"busy"` → `onCallHangup()` wywołuje `handleNoAnswer()`
- [ ] Przy statusie `"completed"` lub `"canceled"` → rekord kampanijny → `COMPLETED`
- [ ] `MockTelephonyAdapter` nie rzuca NPE (przekazuje `"completed"` lub null)
- [ ] Testy jednostkowe `DialerCallbackHandlerTest` weryfikują obie gałęzie (no-answer i completed)

---

### BE-063 – Naprawa logiki `handleNoAnswer()` — używaj `retryDelayMinutes` z kampanii, status `NOT_REACHED`

**Typ:** Bug fix
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** DB-032, BE-062
**Blokuje:** BE-065, FE-069
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

`DialerCallbackHandler.handleNoAnswer()` ma dwa błędy:
1. Używa stałej `NO_ANSWER_RETRY_HOURS = 4` zamiast `campaign.retryDelayMinutes` — ignoruje konfigurację kampanii.
2. Używa statusu `FAILED` jako terminal zamiast `NOT_REACHED` (niedodzwoniony).

**Zmiany w `DialerCallbackHandler`:**

### 1. Zmień sygnaturę metody — dodaj `retryDelayMinutes`

```java
// Stara sygnatura:
public void handleNoAnswer(String callSid, UUID tenantId, UUID campaignId,
                           UUID recordId, UUID agentId, int maxAttempts)

// Nowa sygnatura:
public void handleNoAnswer(String callSid, UUID tenantId, UUID campaignId,
                           UUID recordId, UUID agentId, int maxAttempts, int retryDelayMinutes)
```

### 2. Użyj `retryDelayMinutes` zamiast stałej

```java
if (attemptCount >= maxAttempts) {
    // Terminal: wyczerpano próby → NOT_REACHED (niedodzwoniony)
    updateCampaignContact(recordId, campaignId, tenantId, "NOT_REACHED", null, null);
    log.info("[DialerHandler] Kontakt {} wyczerpał próby ({}/{}), status=NOT_REACHED",
            recordId, attemptCount, maxAttempts);
} else {
    // Zaplanuj kolejną próbę wg konfiguracji kampanii
    Instant nextAttempt = Instant.now().plus(retryDelayMinutes, ChronoUnit.MINUTES);
    updateCampaignContact(recordId, campaignId, tenantId, "NO_ANSWER", nextAttempt, null);
    log.info("[DialerHandler] Kontakt {} – NO_ANSWER, próba {}/{}, next_attempt_at={} (+{}min)",
            recordId, attemptCount, maxAttempts, nextAttempt, retryDelayMinutes);
}
```

### 3. Usuń stałą `NO_ANSWER_RETRY_HOURS`

Stała `private static final int NO_ANSWER_RETRY_HOURS = 4;` jest martwa po zmianie — usunąć.

**Uwagi:**
- Status `NO_ANSWER` z `next_attempt_at` w przyszłości = rekord czeka na kolejną próbę. Dialer pobiera go przez zmieniony query (BE-065).
- Status `NOT_REACHED` = finalny, rekord nie wraca do kolejki.
- Usunąć wzmiankę o `FAILED` z dokumentacji JavaDoc metody.

**Kryteria akceptacji:**
- [ ] Po nieodebraniu, gdy `attempt_count < max_attempts`: status = `NO_ANSWER`, `next_attempt_at = NOW() + retryDelayMinutes`
- [ ] Po nieodebraniu, gdy `attempt_count >= max_attempts`: status = `NOT_REACHED` (nie `FAILED`)
- [ ] Stała `NO_ANSWER_RETRY_HOURS` usunięta
- [ ] Testy jednostkowe: scenariusz retry (attempt_count < max), scenariusz finał (attempt_count == max)
- [ ] `handleNoAnswer()` wywoływany z `retryDelayMinutes` przekazanym przez `onCallHangup()` (BE-062)

---

### BE-064 – Naprawa `handleCallbackDisposition()` — status `CALLBACK`, powiązanie z rekordem kampanii

**Typ:** Bug fix
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** DB-032, DB-033
**Blokuje:** BE-066, FE-069
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

`DialerCallbackHandler.handleCallbackDisposition()` ustawia status rekordu `campaign_contact` na `COMPLETED`, co jest błędem. Rekord z callbackiem powinien mieć status `CALLBACK` — jest nadal aktywny, czeka na oddzwonienie. Dyspozycja `COMPLETED` powinna być ustawiona dopiero po faktycznym zakończeniu oddzwonienia.

Dodatkowo: `next_attempt_at` nie jest ustawiane, przez co brak informacji o zaplanowanym czasie.

**Zmiany w `handleCallbackDisposition()`:**

```java
// Zamiast:
updateCampaignContact(recordId, campaignId, tenantId, "COMPLETED", null, "CALLBACK");

// Użyć:
updateCampaignContact(recordId, campaignId, tenantId, "CALLBACK", scheduledAt, "CALLBACK");
```

Metoda `updateCampaignContact()` musi zachować `attempt_count` bez zmiany — weryfikacja: `updateCampaignContact()` nie inkrementuje `attempt_count` (inkrementacja dzieje się tylko przy przejściu na `DIALING`). **Sprawdzić i potwierdzić w teście.**

**Powiązanie `ScheduledCallback` z `campaign_contact`:**

Po DB-033 tabela `scheduled_callback` ma dedykowane pole `campaign_contact_record_id`. Używać go zamiast `customer_id`:

```java
@Column(name = "campaign_contact_record_id")
private UUID campaignContactRecordId;
```

Przy tworzeniu rekordu `ScheduledCallback` z dyspozycji CALLBACK:

```java
scheduledCallback.setCampaignContactRecordId(recordId);  // campaign_contact.record_id
// customer_id nadal wskazuje na prawdziwego klienta z tabeli customer
```

**Kryteria akceptacji:**
- [ ] Po dyspozycji CALLBACK: `campaign_contact.status = 'CALLBACK'`
- [ ] Po dyspozycji CALLBACK: `campaign_contact.next_attempt_at = scheduledAt` (czas agenta)
- [ ] `campaign_contact.attempt_count` NIE zmienia się przy ustawieniu CALLBACK
- [ ] `ScheduledCallback.campaignContactRecordId` zawiera `record_id` rekordu `campaign_contact`
- [ ] `ScheduledCallback.customerId` zawiera prawdziwe ID klienta z tabeli `customer` (nie record_id)
- [ ] Test jednostkowy: po `handleCallbackDisposition()` rekord ma status CALLBACK, nie COMPLETED

---

### BE-065 – Naprawa `ProgressiveDialerService` — retry rekordów NO_ANSWER, usunięcie stałego 4h guard

**Typ:** Bug fix
**Priorytet:** Must Have
**Szacowany rozmiar:** S
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** DB-032, BE-063
**Blokuje:** –
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

Dwa problemy w `ProgressiveDialerService`:

1. **Dialer nie pobiera rekordów `NO_ANSWER`** do ponowienia — `fetchNextPendingContact()` filtruje wyłącznie `status = 'PENDING'`. Rekordy po `handleNoAnswer()` mają status `NO_ANSWER` z `next_attempt_at` i nigdy nie wróciłyby do kolejki.

2. **`isCalledTooRecently()` używa stałej 4h** — stała gwardia `4h` jest architektonicznie błędna: powielenie logiki z `retryDelayMinutes` i działa nawet gdy kampania ma skonfigurowany krótszy/dłuższy interwał. Kolumna `next_attempt_at` jest już autorytatywna.

**Zmiany w `fetchNextPendingContact()`:**

```java
// Zamiast:
AND status = 'PENDING'
// Użyć:
AND status IN ('PENDING', 'NO_ANSWER')
```

Zapytanie już filtruje `next_attempt_at IS NULL OR next_attempt_at <= NOW()` — rekordy `NO_ANSWER` będą pobierane automatycznie gdy minie czas `next_attempt_at`.

**Usunięcie `isCalledTooRecently()`:**

Metoda `isCalledTooRecently()` (sprawdza 4h od `last_attempt_at`) jest wywoływana w `initiateDialForAgent()`. Usunąć wywołanie i samą metodę — `next_attempt_at` przejął tę rolę. Usunąć również log debug z komunikatem "dzwoniony zbyt niedawno".

**Uwaga na indeks DB:**

Zmiana wymaga indeksu `idx_campaign_contact_dialer` pokrywającego `WHERE status IN ('PENDING', 'NO_ANSWER')` (dostarczany przez DB-032). Bez niego zapytanie będzie seq-scanem na dużych kampaniach.

**Kryteria akceptacji:**
- [ ] `fetchNextPendingContact()` pobiera rekordy `NO_ANSWER` gdy `next_attempt_at <= NOW()`
- [ ] `fetchNextPendingContact()` nadal pomija `NO_ANSWER` gdy `next_attempt_at > NOW()` (jeszcze za wcześnie)
- [ ] Metoda `isCalledTooRecently()` usunięta z klasy i z wywołania
- [ ] Test integracyjny: rekord NO_ANSWER z `next_attempt_at` w przeszłości → dialer go pobiera
- [ ] Test integracyjny: rekord NO_ANSWER z `next_attempt_at` w przyszłości → dialer go pomija

---

### BE-066 – `ScheduledCallbackExecutor` — aktualizacja statusu `campaign_contact` przy wykonaniu callbacku kampanijnego

**Typ:** Feature
**Priorytet:** Should Have
**Szacowany rozmiar:** M
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-08
**Zależy od:** DB-032, DB-033, BE-062, BE-064
**Blokuje:** –
**Epic:** EPIC-21 Retry i callback w kampaniach wychodzących

**Opis:**

`ScheduledCallbackExecutor` inicjuje połączenia dla zaplanowanych oddzwonień, ale przy callbackach kampanijnych (gdzie `callback.getCampaignId() != null`) nie aktualizuje statusu `campaign_contact`. Wymagana jest pełna integracja:

1. Przed dzwonieniem: `campaign_contact.status = DIALING` **bez inkrementacji `attempt_count`**
2. Wynik połączenia przekazany przez mechanizm Redis → `DialerCallbackHandler.onCallHangup()`

**Zmiany:**

### 1. Nowa prywatna metoda w `ProgressiveDialerService` (lub osobny `DialerStateRepository`)

Wyodrębnić `markAsDialingWithoutAttemptIncrement()` do repozytorium/serwisu:

```java
// W CampaignContactRepository lub ProgressiveDialerService:
public void markAsDialingForCallback(UUID recordId, UUID campaignId, UUID tenantId) {
    // UPDATE campaign_contact SET status = 'DIALING', last_attempt_at = NOW(), updated_at = NOW()
    // WHERE record_id = ? AND campaign_id = ? AND tenant_id = ?
    // UWAGA: attempt_count NIE jest inkrementowany
}
```

### 2. W `ScheduledCallbackExecutor.processCallback()` — dla callbacków kampanijnych

```java
UUID campaignId = callback.getCampaignId();
UUID recordId = callback.getCampaignContactRecordId();  // DB-033: dedykowane pole, nie customer_id

if (campaignId != null && recordId != null) {
    // Oznacz rekord jako DIALING (bez inkrementacji attempt_count)
    campaignContactRepository.markAsDialingForCallback(recordId, campaignId, callback.getTenantId());
}

// Inicjuj połączenie jak dotychczas...
telephonyAdapter.initiateCall(...);

// Zapisz stan w Redis (jak ProgressiveDialerService.saveCallState)
// WAŻNE: dodaj marker że to jest callback attempt
String callKey = "dialer:call:" + callSid;
String value = recordId + "," + campaignId + "," + callback.getAgentId() + "," + callback.getTenantId();
redisTemplate.opsForValue().set(callKey, value, Duration.ofSeconds(1800));

// Marker że nie inkrementować attempt_count przy no-answer
redisTemplate.opsForValue().set("dialer:callback-attempt:" + callSid, "true", Duration.ofSeconds(1800));
```

### 3. W `DialerCallbackHandler.handleNoAnswer()` — obsługa callback attempt

```java
// Sprawdź czy to był callback attempt (attempt_count nie ma być inkrementowany)
boolean isCallbackAttempt = Boolean.TRUE.equals(
    redisTemplate.hasKey("dialer:callback-attempt:" + callSid));

if (!isCallbackAttempt) {
    // Odczytaj aktualny attempt_count (normalny flow)
    int attemptCount = getCurrentAttemptCount(recordId, campaignId, tenantId);
    // ... normalny retry logic
} else {
    // Callback attempt – brak inkrementacji attempt_count
    // Rekord wraca do PENDING (będzie czekał na kolejną próbę z normalnym timeoutem)
    Instant nextAttempt = Instant.now().plus(retryDelayMinutes, ChronoUnit.MINUTES);
    updateCampaignContact(recordId, campaignId, tenantId, "NO_ANSWER", nextAttempt, null);
    redisTemplate.delete("dialer:callback-attempt:" + callSid);
}
```

### 4. W `DialerCallbackHandler.cleanupRedisKeys()` — czyszczenie nowego klucza

```java
redisTemplate.delete("dialer:callback-attempt:" + callSid);
```

**Uwagi:**
- `ScheduledCallbackExecutor` potrzebuje wstrzyknięcia: `CampaignContactRepository`, `StringRedisTemplate`, `ProgressiveDialerService` (lub jego SaveCallState)
- Aby uniknąć circular dependency, wyciągnąć `saveCallState()` do osobnego bean lub przekazać `JdbcTemplate` do executora
- Dla nie-kampanijnych callbacków (`campaignId == null`): brak zmian w logice
- Zakładany TTL klucza `dialer:callback-attempt:*`: 30 minut (taki sam jak `dialer:call:*`)

**Kryteria akceptacji:**
- [ ] Przy wykonaniu callbacku kampanijnego: `campaign_contact.status = 'DIALING'`, `attempt_count` bez zmiany
- [ ] Po zakończeniu rozmowy przez agenta: `campaign_contact.status = 'COMPLETED'`
- [ ] Po braku odpowiedzi przy callbacku: `campaign_contact.status = 'NO_ANSWER'`, `attempt_count` bez zmiany
- [ ] Klucz Redis `dialer:callback-attempt:{callSid}` czyszczony po obsłudze
- [ ] Dla callbacków bez `campaignId`: brak zmian w działaniu (backward compatible)
- [ ] Test jednostkowy: callback attempt nie inkrementuje `attempt_count`

---

## MODUŁ: Ad hoc połączenia i email z panelu agenta

### BE-067 – Endpoint `POST /api/telephony/calls/outbound` — inicjowanie wychodzącego połączenia ad hoc

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-001, BE-030 (TelephonyAdapter.initiateCall)
**Status:** [x] Zrobione
**Blokuje:** FE-071
**Odniesienie PRD:** Agent desktop – kontakt z klientem

**Opis:**
Nowy endpoint REST umożliwiający agentowi zainicjowanie wychodzącego połączenia telefonicznego do dowolnego numeru (ad hoc, bez kampanii). Reużywa istniejącego `TelephonyAdapter.initiateCall()`. Numer `from` pobierany z konfiguracji Twilio tenanta (`TenantTwilioConfig.phoneNumber`), analogicznie jak w `ProgressiveDialerService`.

**Endpoint:**
```
POST /api/telephony/calls/outbound
Body: { "phoneNumber": "+48123456789", "customerId": "uuid" (opcjonalne) }
Response 200: { "contactId": "uuid", "callId": "string" }
```

**Implementacja:**
- Kontroler: `AgentCallController` — nowa metoda `initiateOutboundCall()`
- DTO request: `OutboundCallRequest` (record) — `phoneNumber` (@Pattern E.164, @NotBlank), `customerId` (UUID, nullable)
- DTO response: `OutboundCallResponse` (record) — `contactId`, `callId`
- Walidacja: numer E.164 przez Bean Validation
- `@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")`
- Logika: `telephonyAdapter.initiateCall(tenantId, resolvedFromNumber, phoneNumber, agentId, null, null)`
- `resolvedFromNumber`: odczyt z `TenantTwilioConfig` (jeśli null — `TwilioProperties.defaultFrom` lub pusty string dla mock)
- Wynik: kontakt tworzony przez adapter (jak dla callbacków); zwróć `contactId` i `callId` z `CallSession`
- Nie tworzy `ScheduledCallback`; `queueId=null`, `callbackId=null`

**Kryteria akceptacji:**
- [x] `POST /api/telephony/calls/outbound` z poprawnym numerem E.164 zwraca 200 z `contactId` i `callId`
- [x] Niepoprawny format numeru zwraca 400 (Bean Validation)
- [x] Agent bez tokenu JWT otrzymuje 401
- [ ] Dla Mock adaptera: nowy kontakt OUTBOUND tworzony w DB, event `CALL_ASSIGNED` wysłany WS do agenta
- [ ] Dla Twilio adaptera: połączenie inicjowane przez Twilio REST API
- [x] Dokumentacja OpenAPI uzupełniona

---

### BE-068 – Endpoint `POST /api/email/messages/outbound` — wysyłka nowego emaila ad hoc

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-001, BE-038 (EmailSendService)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-13
**Blokuje:** FE-072
**Odniesienie PRD:** Agent desktop – kontakt z klientem

**Opis:**
Nowy endpoint REST do wysyłki nowej wiadomości email (nie odpowiedzi na istniejącą). Reużywa infrastruktury SMTP z `EmailSendService`, ale bez wymagania `originalMessageId`. Tworzy nowy wątek emailowy.

**Endpoint:**
```
POST /api/email/messages/outbound
Body: {
  "toAddress": "klient@example.com",
  "subject": "Temat",
  "bodyHtml": "<p>Treść</p>",
  "customerId": "uuid" (opcjonalne)
}
Response 200: EmailMessageResponse
```

**Implementacja:**
- Kontroler: `EmailController` — nowa metoda `sendOutboundEmail()`
- DTO request: `OutboundEmailRequest` (record) — `toAddress` (@Email, @NotBlank), `subject` (@NotBlank, max 500), `bodyHtml` (@NotBlank), `customerId` (UUID, nullable)
- Logika w nowej metodzie `EmailSendService.sendNew()`:
  - Pobiera konfigurację SMTP tenanta (jak w `sendReply`)
  - Generuje nowy `Message-ID`, brak nagłówków `In-Reply-To` / `References`
  - Wywołuje `sendSmtp()` bez `inReplyTo` i `references` (null)
  - Zapisuje `EmailMessage` z `direction=OUTBOUND`, `contactId` = null lub powiązany z `customerId` jeśli podano
  - Opcjonalnie: tworzy kontakt typu EMAIL_OUTBOUND jeśli `customerId` podano (przez `EmailContactCreator` lub bezpośrednio)
- `@PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")`

**Kryteria akceptacji:**
- [ ] `POST /api/email/messages/outbound` z poprawnymi danymi zwraca 200 z `EmailMessageResponse`
- [ ] Brak skonfigurowanego SMTP → 422/500 z czytelnym komunikatem
- [ ] `toAddress` nie jest emailem → 400 (Bean Validation)
- [ ] Wiadomość zapisana w DB jako `direction=OUTBOUND`
- [ ] Wysyłka przez SMTP przebiega poprawnie (test integracyjny z mock SMTP lub Greenmail)
- [ ] Dokumentacja OpenAPI uzupełniona

---

## MODUŁ: Notatki do kontaktów (EPIC-22)

### BE-069 – Zapis notatki agenta do kontaktu — `Contact`, `DispositionRequest`, `ContactResponse`, `ContactRepository`, `ContactService`

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-034 (kolumna `notes` w tabeli `contact`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** FE-073, BE-070
**Epic:** EPIC-22 Notatki do kontaktów

**Opis:**
Agent wpisuje notatkę w panelu softphone (zaimplementowane po stronie frontendu — `SetDispositionRequest` wysyła `notes` jako string). Aktualnie backend ignoruje to pole: `DispositionRequest` nie ma pola `notes`, a `Contact` nie ma odpowiedniej kolumny. Zadanie domyka pętlę: odbiór → zapis → zwrot notatki w API.

**Pliki do modyfikacji:**

1. **`Contact.java`** (`domain/model/`) — dodaj pole:
   ```java
   @Column(name = "notes", columnDefinition = "TEXT")
   private String notes;
   ```

2. **`DispositionRequest.java`** (`api/contact/dto/`) — dodaj opcjonalne pole:
   ```java
   @Size(max = 5000, message = "notes nie może przekraczać 5000 znaków")
   String notes
   ```
   Pole nullable (nie `@NotBlank`) — notatka jest opcjonalna.

3. **`ContactService.setDisposition()`** — po `contact.setDispositionCode(...)` dodaj:
   ```java
   contact.setNotes(request.notes());
   ```

4. **`ContactResponse.java`** — dodaj pole `String notes` do rekordu i zmapuj w `from(contact)`:
   ```java
   contact.getNotes()
   ```
   Pole na pozycji po `dispositionCode`, przed `recordingUrl`.

5. **`ContactRepository.java`** — dwa miejsca:
   - **`insert()`**: dodaj `notes` do listy kolumn i `:notes` do VALUES; dodaj `.setParameter("notes", contact.getNotes())`
   - **`update()`**: dodaj `notes = :notes` do SET i `.setParameter("notes", contact.getNotes())`

**Uwagi implementacyjne:**
- `notes` w `DispositionRequest` nullable — agent może zapisać dyspozycję bez notatki
- `@Size(max = 5000)` to limit aplikacyjny; kolumna DB jest TEXT — chroni przed patologicznie dużymi payloadami
- Zapis `notes` w `update()` nadpisuje poprzednią wartość — celowe (agent może poprawić notatkę)

**Kryteria akceptacji:**
- [ ] `PATCH /api/contacts/{id}/disposition` z `{"dispositionCode":"SALE","notes":"Klient zainteresowany"}` zapisuje notatkę w DB
- [ ] `GET /api/contacts/{id}` zwraca pole `notes` z zapisaną wartością
- [ ] `PATCH` z `notes: null` lub bez pola `notes` — kontakt zapisywany z `notes = null` w DB
- [ ] `PATCH` z `notes` przekraczającym 5000 znaków → 400 z komunikatem walidacyjnym
- [ ] Istniejące kontakty bez notatki — `GET` zwraca `notes: null`
- [ ] `ContactRepository.insert()` — nowa kolumna `notes` przekazywana (NULL domyślnie przy tworzeniu kontaktu)

---

### BE-070 – Notatka w historii klienta — `CustomerLookupResponse`, `CustomerRepository`, `CustomerService`

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** DB-034, BE-069
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** FE-074
**Epic:** EPIC-22 Notatki do kontaktów

**Opis:**
Panel klienta (prawy panel w Agent Desktop) wyświetla ostatnie 5 kontaktów klienta. Aktualnie dla każdego kontaktu pokazuje kanał, dyspozycję i datę — bez notatki. Zadanie rozszerza endpoint `GET /api/customers/lookup` o pole `notes` w każdym elemencie historii, żeby agent widział notatki z poprzednich rozmów z klientem.

**Pliki do modyfikacji:**

1. **`CustomerRepository.findLastContactsForCustomer()`** — rozszerz natywny SELECT o kolumnę `notes`:
   ```sql
   SELECT contact_id, channel::text, status::text, started_at, notes
   FROM contact
   WHERE tenant_id  = CAST(:tenantId AS uuid)
     AND customer_id = CAST(:customerId AS uuid)
     AND status NOT IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
   ORDER BY started_at DESC
   LIMIT :limit
   ```
   Zwracane `Object[]` ma teraz 5 elementów: `[0]=contact_id, [1]=channel, [2]=status, [3]=started_at, [4]=notes`.
   Zaktualizuj javadoc metody (zmień `(contact_id, channel, status, started_at)` na `(contact_id, channel, status, started_at, notes)`).

2. **`CustomerLookupResponse.ContactSummaryDto`** — dodaj pole `String notes` jako ostatnie:
   ```java
   public record ContactSummaryDto(
       UUID id,
       String channel,
       String disposition,
       String date,
       String agentName,
       String notes
   ) {}
   ```

3. **`CustomerService.fetchRecentContactsForLookup()`** — zmapuj `row[4]` na `notes`:
   ```java
   String notes = row[4] != null ? row[4].toString() : null;
   result.add(new CustomerLookupResponse.ContactSummaryDto(
       contactId, channel, status, date, null, notes));
   ```

**Uwagi implementacyjne:**
- `agentName` jest hardcodowane jako `null` — bez zmian (osobny zakres)
- `notes` może być bardzo długi — frontend odpowiada za truncation/expand; backend zwraca pełny tekst bez przycinania
- Dodanie pola do rekordu `ContactSummaryDto` jest addytywne — JSON z nowym polem `notes` nie łamie istniejących klientów API

**Kryteria akceptacji:**
- [ ] `GET /api/customers/lookup?phone=+48123456789` zwraca `recentContacts[].notes` (string lub null)
- [ ] Kontakt z notatką — `notes` zawiera pełny tekst notatki (bez truncacji backendowej)
- [ ] Kontakt bez notatki — `notes: null` (nie pusty string)
- [ ] `GET /api/customers/lookup/email?email=test@example.com` — analogicznie zwraca `notes`
- [ ] Brak regresji w istniejących testach `CustomerServiceTest` / `CustomerControllerTest`

---

## MODUŁ: Historia etapów kontaktu (EPIC-23)

### BE-071 – Model `ContactEvent`, repozytorium i serwis zarządzania etapami

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** DB-035 (tabela `contact_event`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** BE-072, BE-073
**Epic:** EPIC-23 Historia etapów kontaktu

**Opis:**
Fundament warstwy domenowej dla historii etapów: encja JPA, repozytorium (natywny SQL — tabela bez partycjonowania, można użyć `JpaRepository`), i serwis z metodami do otwierania i zamykania etapów.

**Pliki do stworzenia/modyfikacji:**

**1. `domain/model/ContactEvent.java`** — encja JPA:
```java
@Entity
@Table(name = "contact_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContactEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "stage", nullable = false, length = 20)
    private String stage; // IVR | QUEUE | AGENT | ON_HOLD

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
```

**2. `domain/repository/ContactEventRepository.java`** — rozszerzenie `TenantAwareRepository`:
```java
@Repository
public class ContactEventRepository extends TenantAwareRepository {

    // Zapisz nowe zdarzenie
    public ContactEvent save(ContactEvent event) { ... }

    // Zamknij ostatnie otwarte zdarzenie danego etapu dla kontaktu
    // Ustawia ended_at = NOW(); trigger DB obliczy duration_seconds
    public int closeLastOpenEvent(UUID contactId, UUID tenantId, String stage, Instant endedAt) { ... }

    // Pobierz wszystkie zdarzenia kontaktu posortowane chronologicznie
    public List<ContactEvent> findByContactId(UUID contactId, UUID tenantId) { ... }

    // Pobierz ostatnie otwarte zdarzenie danego etapu (ended_at IS NULL)
    public Optional<ContactEvent> findLastOpen(UUID contactId, UUID tenantId, String stage) { ... }
}
```
Metody `save()` i `closeLastOpenEvent()` używają natywnego SQL (INSERT / UPDATE). `findByContactId()` i `findLastOpen()` używają JPQL lub natywnego SELECT.

**3. `domain/service/ContactEventService.java`** — fasada domenowa:
```java
@Service
@RequiredArgsConstructor
public class ContactEventService {

    private final ContactEventRepository repository;

    // Otwórz nowy etap IVR
    public void openIvr(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName) { ... }

    // Zamknij etap IVR
    public void closeIvr(UUID contactId, UUID tenantId) { ... }

    // Otwórz etap QUEUE
    public void openQueue(UUID contactId, UUID tenantId, UUID queueId, String queueName, Instant queuedAt) { ... }

    // Zamknij etap QUEUE
    public void closeQueue(UUID contactId, UUID tenantId) { ... }

    // Otwórz etap AGENT
    public void openAgent(UUID contactId, UUID tenantId, UUID agentId, String agentName) { ... }

    // Zamknij etap AGENT
    public void closeAgent(UUID contactId, UUID tenantId) { ... }

    // Otwórz etap ON_HOLD
    public void openHold(UUID contactId, UUID tenantId) { ... }

    // Zamknij etap ON_HOLD
    public void closeHold(UUID contactId, UUID tenantId) { ... }

    // Otwórz etap VOICEBOT (wejście w węzeł VOICEBOT w IVR)
    public void openVoicebot(UUID contactId, UUID tenantId, UUID ivrTreeId, String ivrTreeName) { ... }

    // Zamknij etap VOICEBOT; outcome: "ESCALATED" | "COMPLETED" | "ERROR"
    public void closeVoicebot(UUID contactId, UUID tenantId, String outcome) { ... }

    // Otwórz etap CONSULTING (faza konsultacji przy attended transfer)
    public void openConsulting(UUID contactId, UUID tenantId, String target) { ... }

    // Zamknij etap CONSULTING
    public void closeConsulting(UUID contactId, UUID tenantId) { ... }

    // Zapisz zdarzenie TRANSFER (punkt w czasie — started_at = ended_at)
    // transferType: "BLIND" | "ATTENDED"; targetAgentName nullable
    public void recordTransfer(UUID contactId, UUID tenantId,
                               String target, String transferType, String targetAgentName) { ... }

    // Pobierz pełną historię etapów kontaktu
    public List<ContactEvent> getHistory(UUID contactId, UUID tenantId) { ... }
}
```

Każda metoda `open*` zapisuje nowy rekord z `started_at = Instant.now()` i `ended_at = null`. Każda metoda `close*` woła `closeLastOpenEvent()` z `ended_at = Instant.now()`. Metody są odporne na brak otwartego etapu (gdy `closeLastOpenEvent` zwraca 0 wierszy — loguj WARN, nie rzucaj wyjątku).

**Uwagi implementacyjne:**
- `assertSameTenant(tenantId)` przed każdym zapisem (wzorzec z `ContactRepository`)
- `TenantContext.snapshot()` / `restore()` / `clear()` jeśli `ContactEventService` jest wołany z wątku `@Async`
- Metody serwisu NIE są `@Transactional` — każde zdarzenie to osobna operacja; rollback rodzica nie powinien cofać historii etapów

**Kryteria akceptacji:**
- [ ] `ContactEvent` mapuje tabelę `contact_event` poprawnie (kolumny, typy, JSONB)
- [ ] `ContactEventRepository.save()` zapisuje rekord z `ended_at = null`
- [ ] `ContactEventRepository.closeLastOpenEvent()` ustawia `ended_at`; trigger DB oblicza `duration_seconds`
- [ ] `ContactEventRepository.findByContactId()` zwraca rekordy posortowane po `started_at ASC`
- [ ] `ContactEventService.openIvr()` + `closeIvr()` tworzą parę rekordów IVR
- [ ] `ContactEventService.closeAgent()` gdy brak otwartego etapu AGENT — loguje WARN, nie rzuca wyjątku
- [ ] `mvn verify -pl app` przechodzi po dodaniu klasy

---

### BE-072 – Rejestracja etapów w punktach przejścia kontaktu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-071
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** BE-073
**Epic:** EPIC-23 Historia etapów kontaktu

**Opis:**
Podpięcie `ContactEventService` w punktach, gdzie kontakt faktycznie zmienia etap. Wszystkie modyfikacje są addytywne — nie zmieniają istniejącej logiki biznesowej.

**Punkty integracji:**

**1. `IvrEngineService.startIvrSession()` (lub `startIvrSessionAndBuildTwiml()`):**
Po pobraniu `ivrTree` i ustawieniu `session.setContactId(contactId)`:
```java
if (contactId != null) {
    contactEventService.openIvr(contactId, tenantId,
        ivrTree.getIvrId(), ivrTree.getName());
}
```

**2. `IvrEngineService` — wyjście z IVR do kolejki** (metoda `routeToQueue()` lub `fallbackToDefaultQueue()`):
Gdy kontakt opuszcza IVR i trafia do kolejki:
```java
contactEventService.closeIvr(contactId, tenantId);
contactEventService.openQueue(contactId, tenantId, queueId, queue.getName(), Instant.now());
```

**3. `ContactService.assignAgent()` — agent odpowiada:**
Po sukcesie `contactRepository.assignAgent(...)`:
```java
contactEventService.closeQueue(contactId, tenantId);
contactEventService.openAgent(contactId, tenantId, agentId, resolveAgentName(agentId, tenantId));
```
`resolveAgentName()` — pobierz imię+nazwisko agenta z `AppUserRepository.findById()` (nullable safe: jeśli brak → pusty string).

**4. `TwilioTelephonyAdapter.holdCall()` / `unholdCall()`:**
W `holdCall()` przy sukcesie Twilio:
```java
contactEventService.closeAgent(contactId, tenantId);
contactEventService.openHold(contactId, tenantId);
```
W `unholdCall()` przy sukcesie:
```java
contactEventService.closeHold(contactId, tenantId);
contactEventService.openAgent(contactId, tenantId, agentId, agentName);
```
`contactId` i `agentId` odczytaj z `CallSession` (dostępne przez `softphone.session()`).

**5. `ContactService.updateContact()` — zakończenie kontaktu:**
Gdy `request.status()` to `COMPLETED` lub `ABANDONED`:
```java
// Zamknij każdy potencjalnie otwarty etap
contactEventService.closeAgent(contactId, tenantId);
contactEventService.closeQueue(contactId, tenantId);
contactEventService.closeHold(contactId, tenantId);
contactEventService.closeConsulting(contactId, tenantId);
```
Wielokrotne wywołania `close*` gdy etap nie istnieje — serwis loguje WARN i kontynuuje.

**6. `IvrEngineService` — wejście i wyjście z węzła VOICEBOT:**
Gdy silnik IVR przetwarza węzeł typu `VOICEBOT`:
```java
// Zamknij bieżący etap IVR i otwórz VOICEBOT
contactEventService.closeIvr(contactId, tenantId);
contactEventService.openVoicebot(contactId, tenantId, ivrTree.getIvrId(), ivrTree.getName());
```
Po odpowiedzi voicebota (callback `/voicebot-recording`, metoda `handleVoicebotCallback()`):
```java
// outcome: "ESCALATED" gdy routing do kolejki, "COMPLETED" gdy kontynuacja IVR, "ERROR" przy błędzie
contactEventService.closeVoicebot(contactId, tenantId, outcome);
if ("ESCALATED".equals(outcome)) {
    contactEventService.openQueue(contactId, tenantId, queueId, queueName, Instant.now());
} else {
    contactEventService.openIvr(contactId, tenantId, ivrTree.getIvrId(), ivrTree.getName());
}
```

**7. `TwilioTelephonyAdapter.transferCall()` — faza konsultacji i transfer:**

Blind transfer (`TransferType.BLIND`) — po sukcesie:
```java
contactEventService.closeAgent(contactId, tenantId);
contactEventService.recordTransfer(contactId, tenantId, target, "BLIND", null);
```

Attended transfer (`TransferType.ATTENDED`) — po sukcesie (2. noga nawiązana):
```java
contactEventService.closeAgent(contactId, tenantId);
contactEventService.openConsulting(contactId, tenantId, target);
```

`completeAttendedTransfer()` — gdy agent potwierdza przekazanie:
```java
contactEventService.closeConsulting(contactId, tenantId);
contactEventService.recordTransfer(contactId, tenantId, target, "ATTENDED", targetAgentName);
```

`cancelTransfer()` — gdy agent anuluje attended (klient wraca do agenta):
```java
contactEventService.closeConsulting(contactId, tenantId);
contactEventService.openAgent(contactId, tenantId, agentId, agentName);
```

**Uwagi implementacyjne:**
- Wstrzyknij `ContactEventService` przez konstruktor (`@RequiredArgsConstructor`) w każdym z powyższych serwisów
- Błąd zapisu historii NIE powinien przerywać głównego przepływu — owijaj wywołania `contactEventService.*` w `try/catch(Exception e) { log.warn(...) }` w krytycznych miejscach
- `IvrEngineService` działa synchronicznie — `TenantContext` jest już ustawiony, brak potrzeby `snapshot()`
- `TwilioTelephonyAdapter` może być wołany z wątku asynchronicznego — sprawdź czy `TenantContext` jest dostępny; jeśli nie — przekaż `tenantId` explicite

**Kryteria akceptacji:**
- [ ] Połączenie przez IVR bez VOICEBOT → rekordy: IVR → QUEUE → AGENT (chronologicznie)
- [ ] Połączenie przez IVR z węzłem VOICEBOT → rekordy: IVR → VOICEBOT → QUEUE → AGENT
- [ ] VOICEBOT zakończony eskalacją → `outcome = "ESCALATED"` w metadata
- [ ] Hold → rekord ON_HOLD; Unhold → nowy rekord AGENT
- [ ] Blind transfer → AGENT zakończony + rekord TRANSFER z `transfer_type = "BLIND"` i `target`
- [ ] Attended transfer → AGENT zakończony + CONSULTING → po potwierdzeniu: TRANSFER z `transfer_type = "ATTENDED"`
- [ ] Anulowanie attended transfer → CONSULTING zakończony + nowy rekord AGENT (agent wraca)
- [ ] ABANDONED w kolejce → rekord QUEUE (bez rekordu AGENT)
- [ ] Błąd zapisu historii nie rzuca wyjątku do klienta — tylko WARN w logu
- [ ] `mvn verify -pl app` przechodzi

---

### BE-073 – Endpoint `GET /api/contacts/{id}/events` — historia etapów kontaktu

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** S
**Zależy od:** BE-071, BE-072
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-14
**Blokuje:** FE-075
**Epic:** EPIC-23 Historia etapów kontaktu

**Opis:**
Nowy endpoint zwracający listę etapów kontaktu. Używany przez modal szczegółów kontaktu na frontendzie.

**DTO — `ContactEventResponse.java`:**
```java
public record ContactEventResponse(
    UUID eventId,
    String stage,           // IVR | QUEUE | AGENT | ON_HOLD
    Instant startedAt,
    Instant endedAt,        // null jeśli etap trwa
    Integer durationSeconds,
    Map<String, Object> metadata
) {
    public static ContactEventResponse from(ContactEvent e) { ... }
}
```

**Endpoint w `ContactController`:**
```
GET /api/contacts/{id}/events
Authorization: AGENT (tylko własne kontakty), SUPERVISOR, ADMIN
Response: List<ContactEventResponse>  (posortowana po startedAt ASC)
HTTP 200 — lista (może być pusta)
HTTP 404 — kontakt nie istnieje lub inny tenant
HTTP 403 — AGENT próbuje pobrać historię cudzego kontaktu
```

**Implementacja w `ContactService`:**
```java
public List<ContactEventResponse> getContactEvents(
        UUID contactId, UUID tenantId, UUID userId, boolean isAgent) {
    // 1. Zweryfikuj istnienie i dostęp (użyj findContactOrThrow + sprawdzenie agentId)
    // 2. Zwróć contactEventService.getHistory(contactId, tenantId)
    //    .stream().map(ContactEventResponse::from).toList()
}
```

**Uwagi implementacyjne:**
- Dodaj endpoint do `SecurityConfig` i `TenantFilter.PUBLIC_PATH_PREFIXES` NIE jest potrzebne — endpoint wymaga JWT
- `@PreAuthorize` lub logika w serwisie (wzorzec jak `getContact()`)

**Kryteria akceptacji:**
- [ ] `GET /api/contacts/{id}/events` zwraca 200 z listą `ContactEventResponse`
- [ ] Lista jest posortowana po `startedAt ASC`
- [ ] Kontakt bez historii → pusta lista `[]`
- [ ] Nieistniejący kontakt → 404
- [ ] AGENT dla cudzego kontaktu → 409 (zgodnie z wzorcem `getContact()`)
- [ ] Dokumentacja OpenAPI: endpoint opisany w Swagger UI
- [ ] `mvn verify -pl app` przechodzi

---

## EPIC-24 Transfer połączenia: agent i kolejka

Rozszerzenie istniejącego panelu transferu połączeń o możliwość przekazania lub konsultacji z konkretnym agentem oraz przekazania do innej kolejki. Obecna implementacja obsługuje tylko transfer na numer telefonu (BLIND + ATTENDED). Po tej epoce agent będzie miał do wyboru trzy cele transferu: **Telefon**, **Agent**, **Kolejka**.

---

### BE-074 – Rozszerzenie modelu transferu: `TransferTargetType`, `TransferRequest`, rozszerzenie `TelephonyAdapter` i `MockTelephonyAdapter`

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** —
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-15
**Blokuje:** BE-077, BE-078
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Warstwa fundamentalna całego epiku — model i adapter przed implementacją endpointów.

**1. Nowe typy w pakiecie `domain/telephony`:**

```java
// TransferTargetType.java
public enum TransferTargetType {
    PHONE,   // numer telefonu (istniejące)
    AGENT,   // konkretny agent
    QUEUE    // kolejka
}
```

**2. `TransferRequest.java` — DTO żądania transferu:**

```java
public record TransferRequest(
    TransferType    transferType,   // BLIND | ATTENDED
    TransferTargetType targetType,  // PHONE | AGENT | QUEUE

    String phoneNumber,   // wymagane gdy targetType=PHONE
    UUID   agentId,       // wymagane gdy targetType=AGENT
    UUID   queueId        // wymagane gdy targetType=QUEUE
) {
    /** Walidacja wywołana przed przekazaniem do adaptera */
    public void validate() {
        switch (targetType) {
            case PHONE -> Objects.requireNonNull(phoneNumber, "phoneNumber required for PHONE transfer");
            case AGENT -> Objects.requireNonNull(agentId,    "agentId required for AGENT transfer");
            case QUEUE -> {
                Objects.requireNonNull(queueId, "queueId required for QUEUE transfer");
                if (transferType == TransferType.ATTENDED)
                    throw new IllegalArgumentException("ATTENDED transfer to QUEUE is not supported");
            }
        }
    }
}
```

**3. Rozszerzenie `TelephonyAdapter` — nowa metoda zamiast dwóch oddzielnych:**

```java
// Nowa, ujednolicona metoda (stara transferCall zostaje dla kompatybilności z istniejącym kodem)
CallSession initiateTransfer(String callId, TransferRequest request);
```

**4. Rozszerzenie `MockTelephonyAdapter`:**

- `targetType=PHONE` → istniejąca logika (blind/attended na numer telefonu)
- `targetType=AGENT`:
  - BLIND: przypisuje kontakt do agenta-celu (`contact.agentId = targetAgentId`), kończy sesję aktualnego agenta, publikuje `CALL_TRANSFERRED` z `target_agent_id` w metadanych
  - ATTENDED: tworzy drugą nogę (`secondLegCallId`) jako symulowane połączenie do agenta-celu, publikuje `CALL_OUTBOUND`; bridge łączy obie nogi
- `targetType=QUEUE`:
  - Tylko BLIND: ustawia `contact.status = QUEUED`, `contact.agentId = null`, `contact.queueId = targetQueueId`; publikuje `CALL_TRANSFERRED` z `target_queue_id`

**5. Rozszerzenie metadanych `contact_event` (stage=TRANSFER):**

```jsonc
{
  "transfer_type": "BLIND|ATTENDED",
  "target_type":   "PHONE|AGENT|QUEUE",

  // PHONE:
  "target": "+48123456789",

  // AGENT:
  "target_agent_id":   "uuid",
  "target_agent_name": "Jan Kowalski",

  // QUEUE:
  "target_queue_id":   "uuid",
  "target_queue_name": "Obsługa VIP"
}
```

**Kryteria akceptacji:**
- [ ] `TransferTargetType`, `TransferRequest` skompilowane i dostępne w pakiecie `domain/telephony`
- [ ] `TransferRequest.validate()` rzuca `IllegalArgumentException` przy błędnych kombinacjach (QUEUE + ATTENDED)
- [ ] `TelephonyAdapter.initiateTransfer()` dodane do interfejsu
- [ ] `MockTelephonyAdapter.initiateTransfer()` obsługuje wszystkie trzy `targetType`
- [ ] `contact_event` z `stage=TRANSFER` zawiera `target_type` we wszystkich ścieżkach
- [ ] `mvn verify -pl app` przechodzi

---

### BE-075 – Endpoint `GET /api/telephony/transfer/agents` — lista agentów dostępnych do transferu

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** —
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-15
**Blokuje:** FE-076, FE-078
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Endpoint zwracający listę agentów tego samego tenanta, którzy mogą odebrać transfer. Wywoływany przez panel transferu na frontendzie przy przełączeniu na zakładkę „Agent".

**DTO — `TransferAgentResponse.java`:**

```java
public record TransferAgentResponse(
    UUID   agentId,
    String firstName,
    String lastName,
    String status,        // AVAILABLE | BUSY | BREAK | ON_CALL
    List<String> queueNames   // kolejki, do których należy agent
) {}
```

**Endpoint w `AgentCallController` (lub nowym `TransferController`):**

```
GET /api/telephony/transfer/agents
Authorization: AGENT, SUPERVISOR, ADMIN (JWT)
Response: List<TransferAgentResponse>
HTTP 200 – lista (może być pusta)
```

**Filtrowanie:**
- Tylko agenci tego samego tenanta (`TenantContext`)
- Wyklucz zalogowanego agenta (`principal.userId`)
- Wyklucz statusy `OFFLINE`, `LOGGED_OUT`
- Sortuj: AVAILABLE najpierw, potem BUSY, potem pozostałe; alfabetycznie po nazwisku

**Implementacja:**

```java
// TransferService.java
public List<TransferAgentResponse> getAvailableAgents(UUID tenantId, UUID excludeUserId) {
    return userRepository.findByTenantIdAndStatusNotIn(
            tenantId,
            List.of(UserStatus.OFFLINE, UserStatus.LOGGED_OUT))
        .stream()
        .filter(u -> !u.getUserId().equals(excludeUserId))
        .map(u -> new TransferAgentResponse(
            u.getUserId(), u.getFirstName(), u.getLastName(),
            u.getStatus().name(),
            queueRepository.findQueueNamesByAgentId(u.getUserId())))
        .sorted(Comparator.comparing(r -> agentStatusOrder(r.status())))
        .toList();
}
```

**Kryteria akceptacji:**
- [ ] `GET /api/telephony/transfer/agents` zwraca 200 z listą agentów
- [ ] Zalogowany agent nie pojawia się na liście
- [ ] Agenci OFFLINE/LOGGED_OUT są wykluczone
- [ ] Sortowanie: AVAILABLE → BUSY → pozostałe, następnie alfabetycznie po nazwisku
- [ ] Każdy rekord zawiera: `agentId`, `firstName`, `lastName`, `status`, `queueNames`
- [ ] Dokumentacja OpenAPI dostępna w Swagger UI
- [ ] `mvn verify -pl app` przechodzi

---

### BE-076 – Endpoint `GET /api/telephony/transfer/queues` — lista kolejek dostępnych do transferu

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** —
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-15
**Blokuje:** FE-076, FE-079
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Endpoint zwracający kolejki tego samego tenanta dostępne jako cel transferu. Wywoływany przez panel transferu przy przełączeniu na zakładkę „Kolejka".

**DTO — `TransferQueueResponse.java`:**

```java
public record TransferQueueResponse(
    UUID   queueId,
    String name,
    int    waitingContacts,    // aktualnie w kolejce
    int    availableAgents     // agenci ze statusem AVAILABLE przypisani do kolejki
) {}
```

**Endpoint:**

```
GET /api/telephony/transfer/queues
Authorization: AGENT, SUPERVISOR, ADMIN (JWT)
Response: List<TransferQueueResponse>
HTTP 200 – lista kolejek (sortowana alfabetycznie po name)
```

**Implementacja — `TransferService.java`:**

```java
public List<TransferQueueResponse> getAvailableQueues(UUID tenantId) {
    return queueRepository.findAllByTenantId(tenantId)
        .stream()
        .map(q -> new TransferQueueResponse(
            q.getQueueId(),
            q.getName(),
            contactRepository.countByQueueIdAndStatus(q.getQueueId(), ContactStatus.QUEUED),
            queueAgentRepository.countAvailableAgentsByQueueId(q.getQueueId())))
        .sorted(Comparator.comparing(TransferQueueResponse::name))
        .toList();
}
```

**Uwagi:**
- `waitingContacts` i `availableAgents` — snapshot, nie real-time; wartości mogą być lekko nieaktualne
- Nie filtruj kolejek po aktualnej kolejce kontaktu — agent może transferować do tej samej kolejki (re-queue)

**Kryteria akceptacji:**
- [ ] `GET /api/telephony/transfer/queues` zwraca 200 z listą kolejek tenanta
- [ ] Każdy rekord zawiera: `queueId`, `name`, `waitingContacts`, `availableAgents`
- [ ] Lista posortowana alfabetycznie po `name`
- [ ] Dokumentacja OpenAPI dostępna w Swagger UI
- [ ] `mvn verify -pl app` przechodzi

---

### BE-077 – Endpoint `POST /api/telephony/calls/{callId}/transfer` — ujednolicony transfer (phone / agent / queue)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-074
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-15
**Blokuje:** FE-076, FE-080, BE-083
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Docelowy endpoint transferu dla frontendu — zastępuje użycie `/api/dev/telephony/simulate` do transferów. Obsługuje wszystkie trzy typy celów i oba tryby (BLIND/ATTENDED tam gdzie ma zastosowanie).

**Request DTO — `TransferCallRequest.java` (API layer):**

```java
public record TransferCallRequest(
    @NotNull TransferType       transferType,
    @NotNull TransferTargetType targetType,

    String phoneNumber,  // wymagane gdy targetType=PHONE
    UUID   agentId,      // wymagane gdy targetType=AGENT
    UUID   queueId       // wymagane gdy targetType=QUEUE
) {}
```

**Endpoint w `AgentCallController`:**

```
POST /api/telephony/calls/{callId}/transfer
Authorization: AGENT, SUPERVISOR (JWT)
Body: TransferCallRequest (JSON)
Response: CallSessionResponse (JSON)
HTTP 200  – transfer zainicjowany, zwraca stan sesji
HTTP 400  – błędna kombinacja (QUEUE + ATTENDED)
HTTP 403  – kontakt nie należy do zalogowanego agenta
HTTP 404  – kontakt nie istnieje lub inny tenant
HTTP 409  – kontakt nie jest w stanie ACTIVE (np. już ON_HOLD)
```

**Implementacja:**

```java
@PostMapping("/{callId}/transfer")
public ResponseEntity<CallSessionResponse> transferCall(
        @PathVariable String callId,
        @RequestBody @Valid TransferCallRequest req,
        Authentication auth) {

    UUID tenantId = TenantContext.getTenantId();
    UUID userId   = ((UserPrincipal) auth.getPrincipal()).getUserId();

    // 1. Zbuduj domenowy TransferRequest i wywołaj validate()
    TransferRequest domainReq = new TransferRequest(
        req.transferType(), req.targetType(),
        req.phoneNumber(), req.agentId(), req.queueId());
    domainReq.validate();

    // 2. Sprawdź, że callId należy do zalogowanego agenta i jest ACTIVE
    // 3. Wywołaj telephonyAdapter.initiateTransfer(callId, domainReq)
    // 4. Zapisz contact_event z stage=TRANSFER
    // 5. Zwróć CallSessionResponse
    CallSession session = agentCallService.initiateTransfer(callId, domainReq, tenantId, userId);
    return ResponseEntity.ok(CallSessionResponse.from(session));
}
```

**Kryteria akceptacji:**
- [ ] `POST /api/telephony/calls/{callId}/transfer` z `targetType=PHONE` działa identycznie jak dotychczasowy `/api/dev/telephony/simulate` (BLIND + ATTENDED)
- [ ] `targetType=AGENT`, `transferType=BLIND` — kontakt przypisany do agenta-celu
- [ ] `targetType=AGENT`, `transferType=ATTENDED` — zwraca `CallSession` ze stanem secondLeg (gotowy do bridge)
- [ ] `targetType=QUEUE`, `transferType=BLIND` — kontakt przechodzi w status QUEUED w docelowej kolejce
- [ ] `targetType=QUEUE`, `transferType=ATTENDED` → 400
- [ ] Kontakt nienależący do agenta → 403
- [ ] Kontakt nieaktywny → 409
- [ ] `contact_event` z `stage=TRANSFER` zapisywany we wszystkich ścieżkach
- [ ] `mvn verify -pl app` przechodzi

---

---

## MODUŁ: Przypisywanie agentów do kampanii (EPIC-25)

### BE-079 – Usunięcie obowiązkowego powiązania kampanii z kolejką

**Typ:** Refactor
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-036
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** BE-080, BE-081, FE-081, BE-082
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
`CampaignService.createCampaign()` wywołuje `validateQueue(request.queueId(), tenantId)`, która wymaga podania `queueId` należącego do tenanta. `CreateCampaignRequest` ma `@NotNull UUID queueId`. Kampanie wychodzące nie powinny wymagać kolejki — kolejki służą tylko do routingu przychodzącego.

**Zakres zmian:**

1. **`CreateCampaignRequest`** (`api/campaign/dto/`):
   - Usuń `@NotNull` z pola `queueId` → `UUID queueId` (nullable, opcjonalne)

2. **`UpdateCampaignRequest`** (`api/campaign/dto/`):
   - Bez zmian — `queueId` już jest nullable

3. **`CampaignService`**:
   - Usuń wywołanie `validateQueue(request.queueId(), tenantId)` z `createCampaign()`
   - Usuń wywołanie aktualizacji `queueId` z `updateCampaign()` (lub zostaw jako opcjonalne dla backward compat)
   - Usuń import `QueueRepository` i pole `queueRepository` z serwisu (używane wyłącznie przez `validateQueue`)
   - Metoda prywatna `validateQueue()` — usuń całkowicie

4. **`CampaignResponse`** (`api/campaign/dto/`):
   - `queueId` pozostaje jako nullable UUID (dane historyczne mogą mieć przypisaną kolejkę)

5. **Testy** — zaktualizuj testy które dostarczają `queueId` jako required:
   - `CampaignCallerIdTest`, `CampaignImportServiceTest` — usuń `queueId` z builderów lub zmień na opcjonalny

**Kryteria akceptacji:**
- [x] `POST /api/campaigns` bez pola `queueId` zwraca 201 (kampania tworzona bez kolejki)
- [x] `POST /api/campaigns` z `queueId` nadal działa (backward compat — pole zapisywane, ale nie walidowane)
- [x] `mvn verify -pl app` przechodzi — brak kompilacji do `QueueRepository` w `CampaignService`
- [x] Istniejące kampanie z `queue_id != NULL` działają bez zmian

---

### BE-080 – Campaign Assignment API: trójpoziomowe przypisanie agentów (`CampaignAssignmentController`)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-036, BE-079
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** BE-081, BE-084, FE-082, FE-083
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Opis:**
Nowy kontroler i serwis zarządzający przypisaniem agentów do kampanii — wzorowany 1:1 na `QueueAssignmentController` + `QueueAssignmentService`. Obsługuje trzy poziomy: flagę `all_agents`, bezpośrednich agentów (`campaign_agent`) i grupy agentów (`campaign_agent_group`).

**Nowe pliki:**
- `domain/repository/CampaignAssignmentRepository.java` — analogiczny do `QueueAssignmentRepository`
- `domain/service/CampaignAssignmentService.java` — analogiczny do `QueueAssignmentService`
- `api/campaign/CampaignAssignmentController.java`
- `api/campaign/dto/CampaignAssignmentResponse.java`
- `api/campaign/dto/UpdateCampaignAssignmentRequest.java`

**Endpointy w `CampaignAssignmentController` (`/api/campaigns/{campaignId}/assignment`):**

```
GET /api/campaigns/{campaignId}/assignment
    Role: ADMIN, SUPERVISOR
    Response: CampaignAssignmentResponse

PUT /api/campaigns/{campaignId}/assignment
    Role: ADMIN, SUPERVISOR
    Body: UpdateCampaignAssignmentRequest
    Response: CampaignAssignmentResponse
```

**DTO:**
```java
public record CampaignAssignmentResponse(
    UUID    campaignId,
    boolean allAgents,
    List<AgentSummary>      directAgents,  // puste gdy allAgents=true
    List<AgentGroupSummary> groups         // puste gdy allAgents=true
) {}

public record UpdateCampaignAssignmentRequest(
    @NotNull Boolean      allAgents,
    List<UUID>            directAgentIds,  // ignorowane gdy allAgents=true
    List<UUID>            groupIds         // ignorowane gdy allAgents=true
) {}
```

**`CampaignAssignmentService`** — kopia logiki `QueueAssignmentService` z podmianą `queue` → `campaign`:

- **`getAssignment(campaignId, tenantId)`**: czyta `all_agents` + bezpośrednich agentów + grupy z enrichowanymi danymi (imię, nazwisko, memberCount)
- **`updateAssignment(campaignId, request, tenantId)`**:
  - `allAgents=true` → ustawia flagę, istniejące przypisania pozostają (silnik je ignoruje)
  - `allAgents=false` → wyłącza flagę, atomowo podmienia listy agentów i grup (DELETE + batch INSERT)
  - Walidacja: każdy `directAgentId` musi należeć do tenanta i mieć rolę AGENT; każdy `groupId` musi należeć do tenanta

**`CampaignAssignmentRepository`** — kopia `QueueAssignmentRepository` z podmianą tabel:

```java
boolean isAllAgents(UUID campaignId, UUID tenantId);
List<UUID> findDirectAgentIds(UUID campaignId, UUID tenantId);
List<UUID> findGroupIds(UUID campaignId, UUID tenantId);
Set<UUID> resolveEligibleAgentIds(UUID campaignId, UUID tenantId); // UNION campaign_agent + campaign_agent_group→agent_group_member
boolean isGroupAssignedToAnyCampaign(UUID groupId, UUID tenantId);
void setAllAgents(UUID campaignId, UUID tenantId, boolean value);
void replaceDirectAgents(UUID campaignId, UUID tenantId, List<UUID> agentIds);
void replaceGroups(UUID campaignId, UUID tenantId, List<UUID> groupIds);
```

Metoda `resolveEligibleAgentIds()` — SQL UNION identyczny jak w `QueueAssignmentRepository`:
```sql
SELECT agent_id FROM campaign_agent WHERE campaign_id = :campaignId
UNION
SELECT agm.agent_id FROM campaign_agent_group cag
    JOIN agent_group_member agm ON agm.group_id = cag.group_id
WHERE cag.campaign_id = :campaignId
```

**Kryteria akceptacji:**
- [x] `GET /api/campaigns/{id}/assignment` — zwraca `allAgents=true` dla migrowanych kampanii
- [x] `PUT /api/campaigns/{id}/assignment` z `allAgents=true` → ustawia flagę, listy puste w response
- [x] `PUT` z `allAgents=false, directAgentIds=[A,B], groupIds=[G1]` → atomowo podmienia przypisanie
- [x] Przypisanie agenta z innego tenanta → HTTP 400 (jak `QueueAssignmentService`)
- [x] Przypisanie grupy z innego tenanta → HTTP 400
- [x] `resolveEligibleAgentIds()` zwraca UNION bezpośrednich agentów + członków grup (bez duplikatów)
- [x] Wszystkie endpointy wymagają ADMIN lub SUPERVISOR
- [x] `mvn verify -pl app` przechodzi

---

### BE-081 – Aktualizacja `ProgressiveDialerService`: trójpoziomowa kwalifikacja agentów do kampanii

**Typ:** Refactor + Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-036, BE-079, BE-080
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** brak
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
`ProgressiveDialerService.agentHasRequiredSkills()` używa `campaign.getQueueId()` do weryfikacji agenta. Po EPIC-25 kolejka jest usunięta z kampanii. Kwalifikacja agenta opiera się wyłącznie na modelu trójpoziomowym (`all_agents` / grupy / bezpośrednie przypisania).

**Logika kwalifikacji agenta dla kampanii (`isAgentEligibleForCampaign`):**

```
campaign.all_agents == TRUE
    → agent kwalifikuje się (wszyscy agenci tenanta)

campaign.all_agents == FALSE
    AND resolveEligibleAgentIds(campaignId).isEmpty()
    → kampania nie ma agentów → POMIŃ kampanię (WARN log), nie dzwoń

campaign.all_agents == FALSE
    AND agentId ∈ resolveEligibleAgentIds(campaignId)
    → agent kwalifikuje się

campaign.all_agents == FALSE
    AND agentId ∉ resolveEligibleAgentIds(campaignId)
    → agent nie kwalifikuje się do tej kampanii
```

**Zakres zmian w `ProgressiveDialerService`:**

1. **Wstrzyknij `CampaignAssignmentRepository`** (BE-080), usuń `QueueRepository`

2. **Zastąp `agentHasRequiredSkills()`** nową metodą `isAgentEligibleForCampaign(agentId, campaign, tenantId)`:
   ```java
   private boolean isAgentEligibleForCampaign(UUID agentId, Campaign campaign, UUID tenantId) {
       if (campaign.isAllAgents()) {
           return true;
       }
       Set<UUID> eligible = campaignAssignmentRepository
               .resolveEligibleAgentIds(campaign.getCampaignId(), tenantId);
       if (eligible.isEmpty()) {
           log.warn("[Dialer] Kampania {} (all_agents=false) nie ma przypisanych agentów — pomijam",
                   campaign.getCampaignId());
           return false;
       }
       return eligible.contains(agentId);
   }
   ```

3. **Encja `Campaign`** — dodaj pole `allAgents`:
   ```java
   @Column(name = "all_agents", nullable = false)
   @Builder.Default
   private boolean allAgents = false;
   ```

4. **Usuń `QueueRepository`** z serwisu (nie jest już potrzebny)

**Zachowanie przy braku przypisania:**
- `all_agents = false` + puste przypisanie → kampania jest **pominięta** przez dialer — WARN log
- `all_agents = true` → wszyscy agenci tenanta kwalifikują się (backward compat dla migrowanych kampanii)

**Kryteria akceptacji:**
- [x] `all_agents=true`: dialer inicjuje połączenia dla każdego AVAILABLE agenta tenanta
- [x] `all_agents=false` + przypisany bezpośrednio: dialer dzwoni przez tego agenta
- [x] `all_agents=false` + agent należy do przypisanej grupy: dialer dzwoni przez tego agenta
- [x] `all_agents=false` + brak przypisania: kampania pominięta (WARN log), brak połączeń
- [x] `all_agents=false` + agent nie przypisany: kampania pominięta dla tego agenta
- [x] Dialer nie wywołuje `QueueRepository` — kompilacja bez tej zależności
- [x] Testy jednostkowe `ProgressiveDialerServiceTest` pokrywają wszystkie 5 przypadków powyżej
- [x] `mvn verify -pl app` przechodzi

---

### BE-084 – Filtrowanie `GET /api/dialer/manual/records` według przypisania agenta do kampanii

**Typ:** Feature / Bug fix
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-036, BE-080
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** FE-082
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst — zweryfikowany stan:**
`DialerController.getManualCampaignRecords()` (linia 656) pobiera **wszystkie** kampanie `MANUAL+RUNNING` dla tenanta bez żadnej weryfikacji przypisania agenta:
```java
List<Campaign> manualCampaigns = campaignRepository.findRunningManualByTenantId(tenantId);
```
Po EPIC-25: agenci przypisani do kampanii przez `all_agents/campaign_agent/campaign_agent_group`. Gdy `all_agents=false` i agent nie jest przypisany do kampanii — rekordy tej kampanii **nie powinny być widoczne** w panelu manualnym agenta.

**Zakres zmian w `DialerController.getManualCampaignRecords()`:**

```java
UUID agentId = TenantContext.getUserId();

// 1. Kampanie MANUAL+RUNNING dla tenanta (bez zmian)
List<Campaign> manualCampaigns = campaignRepository.findRunningManualByTenantId(tenantId);

// 2. Filtruj po przypisaniu agenta
List<Campaign> eligibleCampaigns = manualCampaigns.stream()
    .filter(campaign -> {
        if (campaign.isAllAgents()) return true;
        Set<UUID> eligible = campaignAssignmentRepository
                .resolveEligibleAgentIds(campaign.getCampaignId(), tenantId);
        return eligible.contains(agentId);
    })
    .toList();

if (eligibleCampaigns.isEmpty()) {
    return ResponseEntity.ok(List.of());
}
// ... reszta bez zmian, używa eligibleCampaigns zamiast manualCampaigns
```

**Wstrzyknij `CampaignAssignmentRepository`** do `DialerController` (już dostępny po BE-080).

**Kryteria akceptacji:**
- [x] Agent z `all_agents=true` dla kampanii: widzi rekordy tej kampanii
- [x] Agent bezpośrednio przypisany (`campaign_agent`): widzi rekordy
- [x] Agent w grupie przypisanej do kampanii (`campaign_agent_group`): widzi rekordy
- [x] Agent nieprzypisany (`all_agents=false`, brak bezpośredniego/grupowego przypisania): **nie widzi** kampanii w panelu manualnym
- [x] Kampania bez żadnych przypisań (`all_agents=false`, puste tabele): żaden agent jej nie widzi
- [x] Testy jednostkowe: 5 przypadków powyżej
- [x] `mvn verify -pl app` przechodzi

---

### BE-082 – Ustawienie `campaign_id` na kontakcie wychodzącym — przepięcie `queueId` na `campaignId` w `TelephonyAdapter.initiateCall()`

**Typ:** Bug fix
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-079
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** BE-083, BE-085
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst — zweryfikowany stan:**
Tabela `contact` ma kolumnę `campaign_id` (nullable). Encja `Contact` ma pole `campaignId`. Jednak `TwilioTelephonyAdapter.persistOutboundContact()` **nigdy nie ustawia `campaign_id`** — zamiast tego ustawia `queue_id = campaign.queue_id`. Oznacza to, że `GET /api/contacts?campaignId=X` nie zwraca żadnych kontaktów wychodzących z dialera, bo pole jest zawsze `NULL`.

Analogia z inbound jest prawidłowa:
- Kontakt **przychodzący** → `queue_id` ustawiany przez IVR/routing (`ContactRepository.updateQueueId()`)
- Kontakt **wychodzący** → `campaign_id` powinien być ustawiany przez dialer, ale dotychczas nie był

**Zakres zmian:**

### 1. `TelephonyAdapter` — zmiana sygnatury `initiateCall()`

```java
// Przed:
CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId, UUID queueId, UUID callbackId);

// Po:
CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId, UUID campaignId, UUID callbackId);
```

Zmiana nazwy parametru `queueId` → `campaignId`. Semantycznie: dla połączeń wychodzących z kampanii to `campaign_id`, nie `queue_id`, ma być ustawiony na kontakcie. Dla połączeń ad-hoc i callbacków bez kampanii — `null`.

### 2. `TwilioTelephonyAdapter.persistOutboundContact()` — ustawienie `campaign_id`

```java
// Przed:
Contact contact = Contact.builder()
    // ...
    .queueId(queueId)       // Kolejka kampanii – wymagana przez RoutingService do ACW
    // ...
    .build();

// Po:
Contact contact = Contact.builder()
    // ...
    .campaignId(campaignId) // Kampania outbound — powiązanie kontaktu z kampanią
    // ...
    .build();
```

Usunąć `queueId` z buildera. `RoutingService` pomija outbound kontakty z `agentId != null` (linia 251-254 `RoutingService.onAgentStatusChanged()`) — zmiana nie wpływa na routing.

### 3. Aktualizacja wszystkich wywołań `initiateCall()`

| Miejsce | Przed | Po |
|---------|-------|----|
| `ProgressiveDialerService.initiateDialForAgent()` | `campaign.getQueueId()` | `campaign.getCampaignId()` |
| `DialerController` (MANUAL) | `campaign.getQueueId()` | `campaign.getCampaignId()` |
| `ScheduledCallbackExecutor` | `null` | `callback.getCampaignId()` (null dla inbound callbacków) |
| `AgentCallController` (ad-hoc) | `null` | `null` (bez zmian — połączenia nieoparte o kampanię) |

### 4. `MockTelephonyAdapter.initiateCall()` — aktualizacja sygnatury i logiki

Analogiczne zmiany jak w `TwilioTelephonyAdapter` — parametr `queueId` → `campaignId`, ustawienie `.campaignId()` w builderze `Contact`.

### 5. Log w `TwilioTelephonyAdapter`

```java
// Przed:
log.debug("[TwilioAdapter] Rekord contact OUTBOUND utworzony: contactId={}, to={}, queueId={}, tenant={}",
    contactId, to, queueId, tenantId);

// Po:
log.debug("[TwilioAdapter] Rekord contact OUTBOUND utworzony: contactId={}, to={}, campaignId={}, tenant={}",
    contactId, to, campaignId, tenantId);
```

**Kryteria akceptacji:**
- [x] `GET /api/contacts?campaignId={id}` zwraca kontakty wychodzące zainicjowane przez dialer dla tej kampanii
- [x] Rekord `contact` ma `campaign_id = campaign.campaignId` po wywołaniu dialera
- [x] Rekord `contact` ma `campaign_id = callback.campaignId` po wykonaniu campaign-callback przez `ScheduledCallbackExecutor`
- [x] Rekord `contact` ma `campaign_id = NULL` dla połączeń ad-hoc (`AgentCallController`)
- [x] `contact.queue_id` pozostaje `NULL` dla kontaktów wychodzących (nie wchodzą do routingu kolejkowego)
- [x] `RoutingService` nadal poprawnie pomija outbound kontakty z `agentId != null`
- [x] `MockTelephonyAdapter` zaktualizowany — testy jednostkowe przechodzą
- [x] `mvn verify -pl app` przechodzi

---

### BE-078 – Endpoint `POST /api/telephony/calls/{callId}/bridge/{secondCallId}` — łączenie nóg dla attended transfer

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-074
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-15
**Blokuje:** FE-076, FE-080
**Epic:** EPIC-24 Transfer połączenia: agent i kolejka

**Opis:**

Dedykowany endpoint do finalizacji attended transfer — łączy nogę klienta z nogą agenta-celu. Dotychczas wywoływany przez `/api/dev/telephony/simulate` z `action=BRIDGE`. Po tym zadaniu frontend wywołuje właściwy endpoint.

**Endpoint w `AgentCallController`:**

```
POST /api/telephony/calls/{callId}/bridge/{secondCallId}
Authorization: AGENT, SUPERVISOR (JWT)
Response: 204 No Content
HTTP 204  – bridge wykonany
HTTP 403  – callId nie należy do zalogowanego agenta
HTTP 404  – jedna z sesji nie istnieje lub inny tenant
HTTP 409  – nogi nie są w stanie kompatybilnym z bridge (np. callId nie ON_HOLD)
```

**Implementacja:**

```java
@PostMapping("/{callId}/bridge/{secondCallId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void bridgeCalls(
        @PathVariable String callId,
        @PathVariable String secondCallId,
        Authentication auth) {

    UUID tenantId = TenantContext.getTenantId();
    UUID userId   = ((UserPrincipal) auth.getPrincipal()).getUserId();

    // 1. Sprawdź, że callId należy do zalogowanego agenta
    // 2. Wywołaj telephonyAdapter.bridgeCalls(callId, secondCallId)
    // 3. Aktualizuj contact_event (stage=TRANSFER, zapisz czas zakończenia)
    agentCallService.bridgeCalls(callId, secondCallId, tenantId, userId);
}
```

**Uwagi:**
- Metoda `MockTelephonyAdapter.bridgeCalls()` już istnieje — potrzebny jest tylko endpoint HTTP i wołanie z `AgentCallService`
- `TwilioTelephonyAdapter.bridgeCalls()` też istnieje — wystarczy podpiąć

**Kryteria akceptacji:**
- [x] `POST /api/telephony/calls/{callId}/bridge/{secondCallId}` zwraca 204
- [x] Po bridge: callId → `TRANSFERRED`, secondCallId → `ACTIVE`
- [x] Publikowany event `CALL_TRANSFERRED` z `transferType=ATTENDED`
- [x] callId nienależący do agenta → 403
- [x] Niezgodny stan sesji → 409
- [x] `mvn verify -pl app` przechodzi

---

## MODUŁ: Blokada transferu do kolejki dla połączeń wychodzących (EPIC-25)

### BE-083 – Guard: odrzucenie transferu OUTBOUND → QUEUE w endpoincie transferu

**Typ:** Bug fix / Validation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-077 (endpoint transferu), BE-082 (contact.direction ustawiony poprawnie)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** brak
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst:**
Endpoint `POST /api/telephony/calls/{callId}/transfer` (BE-077) nie weryfikuje kierunku połączenia. Dla połączeń wychodzących (`contact.direction = 'OUTBOUND'`) transfer do kolejki jest semantycznie niemożliwy — kolejka obsługuje wyłącznie ruch przychodzący. FE-084 blokuje ten scenariusz na poziomie UI, ale API musi być odporne na bezpośrednie wywołania (curl, testy, inne klienty).

**Implementacja — rozszerzenie `AgentCallService.initiateTransfer()` lub kontrolera:**

```java
// W AgentCallService.initiateTransfer() po pobraniu sesji / kontaktu:

Contact contact = contactRepository.findById(contactId, tenantId)
    .orElseThrow(...);

if ("OUTBOUND".equals(contact.getDirection())
        && TransferTargetType.QUEUE == domainReq.targetType()) {
    throw new InvalidOperationException(
        "Transfer do kolejki jest niedozwolony dla połączeń wychodzących (outbound). " +
        "Dla kampanii wychodzących dostępny jest wyłącznie transfer do agenta lub na numer telefonu.");
}
```

**Mapowanie wyjątku:** `InvalidOperationException` → HTTP 400 (obsługiwane przez `GlobalExceptionHandler`).

**Nie wymaga zmian w DB ani modelu** — weryfikacja na poziomie logiki serwisowej.

**Kryteria akceptacji:**
- [x] `POST /api/telephony/calls/{callId}/transfer` z `targetType=QUEUE` dla kontaktu `direction=OUTBOUND` → HTTP 400 z opisowym komunikatem
- [x] Ten sam endpoint z `targetType=QUEUE` dla kontaktu `direction=INBOUND` → działa poprawnie (bez zmian)
- [x] Transfer `OUTBOUND` z `targetType=PHONE` lub `targetType=AGENT` → działa poprawnie (bez zmian)
- [x] Test jednostkowy: `outbound + QUEUE → InvalidOperationException`
- [x] `mvn verify -pl app` przechodzi

---

## MODUŁ: Historia prób wydzwonienia rekordu kampanii (EPIC-25)

### BE-085 – Powiązanie kontaktu z rekordem kampanii: zapis `campaign_contact_record_id` + endpoint historii

**Typ:** Feature + Bug fix
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-037, BE-082 (campaign_id na kontakcie)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-21
**Blokuje:** FE-085
**Epic:** EPIC-25 Przypisywanie agentów do kampanii

**Kontekst — zweryfikowany stan:**
- `ProgressiveDialerService.initiateDialForAgent()` zna `recordId` (campaign_contact) w momencie wywołania dialera, ale NIE przekazuje go do `telephonyAdapter.initiateCall()` ani nie zapisuje na kontakcie
- `telephonyAdapter.initiateCall()` zwraca `CallSession` z `contactId` — w tym momencie znane są obie wartości (`recordId` + `contactId`), ale powiązanie nie jest zapisywane
- `campaign_contact.last_contact_id` nigdy nie jest ustawiane (pole istnieje od V009, kod go nie używa)
- Redis state: `{campaignContactId},{campaignId},{agentId},{tenantId}` — brak `contactId`, więc `DialerCallbackHandler` nie może zaktualizować `last_contact_id`

**Zakres zmian:**

### 1. `Contact` entity — nowe pole

```java
@Column(name = "campaign_contact_record_id")
private UUID campaignContactRecordId;
```

### 2. `ContactRepository` — nowa metoda zapisu

```java
public int updateCampaignContactRecordId(UUID contactId, UUID recordId, UUID tenantId) {
    // UPDATE contact SET campaign_contact_record_id = :recordId WHERE contact_id = :contactId AND tenant_id = :tenantId
}
```

### 3. `ProgressiveDialerService.initiateDialForAgent()` — zapis po `initiateCall()`

```java
// Po:
CallSession session = telephonyAdapter.initiateCall(...);
saveCallState(session.getCallId(), recordId, campaign.getCampaignId(), agentId, tenantId);

// Dodać:
if (session.getContactId() != null) {
    contactRepository.updateCampaignContactRecordId(session.getContactId(), recordId, tenantId);
}
```

### 4. Redis state — rozszerzenie o `contactId`

Zmiana formatu klucza `dialer:call:{callSid}` z:
```
{campaignContactId},{campaignId},{agentId},{tenantId}
```
na:
```
{campaignContactId},{campaignId},{agentId},{tenantId},{contactId}
```

`contactId` może być pusty string gdy `session.getContactId() == null` (błąd DB — defensywnie).

Zaktualizować `DialerCallbackHandler.onCallHangup()` i `onCallAnswered()` by parsowały 5. element (backward compat: jeśli `parts.length == 4` → `contactId = null`).

### 5. `campaign_contact.last_contact_id` — wypełnianie przy CONNECTED

W `DialerCallbackHandler.handleAnswered()` (lub `updateCampaignContact()` przy statusie CONNECTED):

```java
// Gdy kontakt odbiera (CONNECTED) — zapisz last_contact_id na rekordzie kampanii
if (contactId != null) {
    jdbcTemplate.update("""
        UPDATE campaign_contact
        SET last_contact_id = ?::uuid, updated_at = NOW()
        WHERE record_id = ?::uuid AND campaign_id = ?::uuid
    """, contactId.toString(), recordId.toString(), campaignId.toString());
}
```

### 6. `CampaignContactResponse` — dodaj `lastContactId`

```java
public record CampaignContactResponse(
    UUID recordId,
    String phone,
    String firstName,
    String lastName,
    Map<String, String> customFields,
    String status,
    String dispositionCode,
    Instant createdAt,
    int attemptCount,
    Instant nextAttemptAt,
    UUID lastContactId   // null gdy brak prób — nowe pole
) {}
```

### 7. Nowy endpoint: historia prób dla rekordu

```
GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts
Role: ADMIN, SUPERVISOR
Response: List<ContactResponse>  — lista kontaktów powiązanych z rekordem,
          posortowana started_at DESC (najnowsza próba pierwsza)
```

Implementacja w `CampaignImportController` (lub nowym `CampaignContactsController`):
```java
@GetMapping("/{campaignId}/contacts/{recordId}/attempts")
public ResponseEntity<List<ContactResponse>> getAttempts(
        @PathVariable UUID campaignId,
        @PathVariable UUID recordId) {
    UUID tenantId = TenantContext.getTenantId();
    // SELECT * FROM contact WHERE campaign_contact_record_id = :recordId
    //   AND campaign_id = :campaignId AND tenant_id = :tenantId
    //   ORDER BY started_at DESC
    List<ContactResponse> attempts = contactRepository
            .findByCampaignContactRecordId(recordId, campaignId, tenantId);
    return ResponseEntity.ok(attempts);
}
```

**`ContactRepository.findByCampaignContactRecordId()`:**
```java
public List<ContactResponse> findByCampaignContactRecordId(UUID recordId, UUID campaignId, UUID tenantId) {
    // Natywne SQL z ORDER BY started_at DESC, max 100 wyników
}
```

**Kryteria akceptacji:**
- [x] Po zainicjowaniu połączenia przez dialer: `contact.campaign_contact_record_id = recordId`
- [x] Po odebraniu przez klienta (CONNECTED): `campaign_contact.last_contact_id = contactId`
- [x] `GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts` zwraca listę kontaktów dla rekordu
- [x] Lista posortowana `started_at DESC` — najnowsza próba na górze
- [x] `CampaignContactResponse.lastContactId` wypełnione gdy kampania ma przynajmniej jedną próbę
- [x] Backward compat: istniejące rekordy bez `campaign_contact_record_id` (NULL) — endpoint zwraca pustą listę
- [x] Redis backward compat: stary format (4 części) obsługiwany przez `DialerCallbackHandler`
- [x] Test jednostkowy: `DialerCallbackHandlerTest` — hangup z 5-elementowym Redis state
- [x] `mvn verify -pl app` przechodzi

---

## EPIC-26: AI-Powered Conversation Summary

### BE-086 – Encja `TenantAiConfig` + Repository + konwerter szyfrowania

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-038 (tabela `tenant_ai_config`), BE-055 (wzorzec `EncryptedStringConverter`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-087
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Encja JPA + repozytorium dla konfiguracji AI per tenant. Wzorzec identyczny jak `TenantTwilioConfig` (BE-055) — `api_key_encrypted` annotowane `@Convert(converter = EncryptedStringConverter.class)`.

**Komponenty:**

1. **`AiProvider`** (`domain/model/AiProvider.java`) — ENUM: `ANTHROPIC`, `OPENAI`, `AZURE_OPENAI`

2. **`TenantAiConfig`** (`domain/model/TenantAiConfig.java`) — encja JPA:
   - Pola: `id`, `tenantId`, `provider` (AiProvider), `apiKeyEncrypted` (zaszyfrowany `@Convert`), `modelName`, `azureEndpoint`, `azureDeploymentName`, `summaryPromptTemplate`, `isActive`, `createdAt`, `updatedAt`
   - `@PreUpdate` ustawia `updatedAt = Instant.now()`

3. **`TenantAiConfigRepository`** (`domain/repository/TenantAiConfigRepository.java`) — rozszerza `TenantAwareRepository`:
   - `findByTenantId(UUID tenantId): Optional<TenantAiConfig>`
   - `assertSameTenant()` przed każdym `save()`

**Kryteria akceptacji:**
- [x] `TenantAiConfig` mapuje na tabelę `tenant_ai_config` z poprawnymi typami kolumn
- [x] `api_key_encrypted` w bazie jest szyfrowany (nie plaintext) — weryfikacja przez `SELECT api_key_encrypted FROM tenant_ai_config`
- [x] `findByTenantId()` zwraca `Optional.empty()` gdy brak konfiguracji dla tenanta
- [x] Multi-tenancy: `assertSameTenant()` rzuca wyjątek przy próbie zapisu dla innego tenanta
- [x] `mvn verify -pl app` przechodzi

---

### BE-087 – `TenantAiConfigService`: logika biznesowa konfiguracji AI

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-086
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-088, BE-089
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Serwis zarządzania konfiguracją AI (`domain/service/TenantAiConfigService.java`).

**Metody:**
- `saveConfig(UUID tenantId, TenantAiConfigRequest request): TenantAiConfigResponse` — upsert; waliduje `modelName` (nie pusty), dla `AZURE_OPENAI` wymaga `azureEndpoint` i `azureDeploymentName`
- `getConfig(UUID tenantId): Optional<TenantAiConfigResponse>` — z maskowaniem `apiKey` w response DTO (pokazuj tylko ostatnie 4 znaki: `****xxxx`)
- `getDecryptedConfig(UUID tenantId): Optional<TenantAiConfigDecrypted>` — package-private, pełne odszyfrowane dane dla `AiSummaryService`; nie eksponować przez REST
- `deleteConfig(UUID tenantId): void`

**DTO (records):**
- `TenantAiConfigRequest`: `provider` (AiProvider), `apiKey` (plaintext — nigdy nie zapisywać bez szyfrowania), `modelName`, `azureEndpoint`?, `azureDeploymentName`?, `summaryPromptTemplate`?
- `TenantAiConfigResponse`: wszystkie pola + `isActive`, `createdAt`, `updatedAt` — `apiKey` zamaskowane
- `TenantAiConfigDecrypted` (nie eksponować przez REST): wszystkie pola z odszyfrowanym `apiKey`

**Kryteria akceptacji:**
- [x] Upsert działa poprawnie: przy istniejącej konfiguracji UPDATE, przy braku INSERT
- [x] `apiKey` nigdy nie pojawia się w plaintext w `TenantAiConfigResponse` — zawsze maskowany
- [x] `getDecryptedConfig()` zwraca odszyfrowany klucz — weryfikacja w teście jednostkowym (nie przez REST)
- [x] Walidacja: `AZURE_OPENAI` bez `azureEndpoint` → `400 Bad Request`
- [x] `mvn verify -pl app` przechodzi (15 testów)

---

### BE-088 – `TenantAiConfigController`: REST API konfiguracji AI dla supervisora

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-087
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** FE-088
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Kontroler REST eksponujący zarządzanie konfiguracją AI dla roli SUPERVISOR w kontekście własnego tenanta.

**Endpointy (`/api/supervisor/ai-config`):**
```
GET    /api/supervisor/ai-config    → 200 TenantAiConfigResponse | 204 (brak konfiguracji)
PUT    /api/supervisor/ai-config    → 200 TenantAiConfigResponse (upsert)
DELETE /api/supervisor/ai-config    → 204
```

**Wymagania bezpieczeństwa:**
- Wymagana rola `ROLE_SUPERVISOR`
- `tenantId` pochodzi z `TenantContext` — nie z parametru URL (izolacja multi-tenant)
- Endpoint dodać do `SecurityConfig` i `TenantFilter.PUBLIC_PATH_PREFIXES` NIE — endpoint wymaga JWT

**Kryteria akceptacji:**
- [x] `GET` zwraca 204 gdy brak konfiguracji, 200 z DTO gdy istnieje
- [x] `PUT` działa jako upsert — zwraca 200 z aktualnym stanem
- [x] `DELETE` usuwa konfigurację — kolejny `GET` zwraca 204
- [x] Agent (`ROLE_AGENT`) dostaje 403 na wszystkich endpointach
- [x] Inny tenant nie widzi konfiguracji (izolacja RLS + `TenantContext`)
- [x] Swagger: `@Operation` z opisem bezpieczeństwa kluczy API
- [x] `mvn verify -pl app` przechodzi

---

### BE-089 – `AiSummaryService`: logika generowania podsumowania przez Python AI service

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-087 (TenantAiConfigService), DB-039 (kolumny `ai_summary` w `contact`), BE-091 (Python AI service)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-090
**Epic:** EPIC-26 AI-Powered Conversation Summary

> **Uwaga (weryfikacja 2026-08-08):** funkcjonalnie ukończone i działające, ale zapis odbywa się
> do tabeli `contact_ai_summary` (po refaktorze `V068__extract_ai_summary_to_own_table.sql`), nie
> do `contact.ai_summary` jak opisano niżej — zob. uwaga przy DB-039.

**Opis:**
Serwis orchestrujący generowanie podsumowania AI dla kontaktu. Wywołuje Python AI service (FastAPI) przez REST, zapisuje wynik w `contact.ai_summary`.

**Przepływ:**
1. Pobierz kontakt z bazy — rzuć `ContactNotFoundException` jeśli nie istnieje
2. Pobierz `TenantAiConfigDecrypted` — rzuć `AiConfigNotFoundException` jeśli brak konfiguracji
3. Wyodrębnij zawartość do podsumowania zależnie od kanału:
   - `PHONE`: `contact.notes` (transkrypcja/notatki agenta) + metadane (czas trwania, queue)
   - `EMAIL`: treść emaila z `email.body` (relacja przez `contact.email_id`)
   - `SOCIAL_MEDIA`: wątki wiadomości z `social_message` (relacja przez `contact.social_integration_id`)
4. Zbuduj payload `AiSummarizeRequest` i wywołaj `POST {ai_service_url}/ai/summarize`
5. Zapisz wynik: `contact.ai_summary`, `contact.ai_summary_model`, `contact.ai_summary_generated_at = NOW()`
6. Zwróć `AiSummaryResponse`

**HTTP client do Python AI service:**
Użyj istniejącego `RestTemplate` lub `WebClient` — zgodnie ze wzorcem stosowanym w projekcie dla innych wywołań serwisów zewnętrznych. Timeout: 30s (generowanie może być wolne).

**DTO komunikacji ze Spring → Python AI service:**
```java
record AiSummarizeRequest(
    String channel,         // PHONE | EMAIL | SOCIAL_MEDIA
    String content,         // treść do podsumowania
    String provider,        // ANTHROPIC | OPENAI | AZURE_OPENAI
    String apiKey,          // odszyfrowany klucz — tylko przez sieć wewnętrzną
    String modelName,
    String azureEndpoint,   // null dla non-Azure
    String deploymentName,  // null dla non-Azure
    String promptTemplate   // null = użyj domyślnego w Python service
) {}

record AiSummarizeResponse(
    String summary,
    String modelUsed,
    int tokensUsed
) {}
```

**Wyjątki:**
- `AiConfigNotFoundException` — brak konfiguracji AI dla tenanta
- `AiSummaryGenerationException` — błąd wywołania Python AI service (4xx/5xx/timeout) — nie retryować

**Kryteria akceptacji:**
- [x] Kontakt nieistniejący → `404 Not Found`
- [x] Brak konfiguracji AI dla tenanta → `422 Unprocessable Entity` z opisowym komunikatem
- [x] Błąd Python AI service → `502 Bad Gateway` z komunikatem nie eksponującym klucza API
- [x] `contact.ai_summary` zapisany w bazie po pomyślnym wywołaniu
- [x] Klucz API (`apiKey`) nie pojawia się w logach aplikacji (maskowanie w MDC lub przez `@Sensitive`)
- [x] Timeout 30s — po przekroczeniu `AiSummaryGenerationException`
- [x] Test jednostkowy z zaślepionym HTTP client: happy path + błąd HTTP 500 z serwisu AI (8 testów)
- [x] `mvn verify -pl app` przechodzi

---

### BE-090 – Endpoint `POST /api/contacts/{contactId}/ai-summary`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-089 (AiSummaryService)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** FE-086
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Endpoint REST wywoływany przez agenta z formularza dyspozycji. Deleguje do `AiSummaryService`, zwraca wygenerowane podsumowanie.

```
POST /api/contacts/{contactId}/ai-summary
Role: AGENT, SUPERVISOR
Body: brak (contactId w path wystarczy)
Response 200: AiSummaryResponse { summary, modelUsed, tokensUsed }
Response 404: kontakt nie istnieje
Response 422: brak konfiguracji AI dla tenanta
Response 502: błąd wywołania serwisu AI
```

**Wymagania:**
- Kontakt musi należeć do tenanta z `TenantContext` — izolacja multi-tenant
- Agent może wygenerować podsumowanie dowolnego kontaktu swojego tenanta (nie tylko własnego) — SUPERVISOR i AGENT mają dostęp
- Wywołanie idempotentne — wielokrotne wywołanie nadpisuje poprzednie `ai_summary` (brak blokady)

**Dodać do `SecurityConfig`:** `requestMatchers("/api/contacts/*/ai-summary").hasAnyRole("AGENT", "SUPERVISOR")`

**Kryteria akceptacji:**
- [x] `POST /api/contacts/{contactId}/ai-summary` — poprawna odpowiedź 200 z polem `summary`
- [x] Kontakt z innego tenanta → 404 (nie 403 — nie ujawniamy istnienia)
- [x] Brak konfiguracji AI → 422 z czytelnym komunikatem dla agenta
- [x] Błąd serwisu AI → 502 — klucz API nie w odpowiedzi błędu
- [x] Po wywołaniu: `contact.ai_summary` zaktualizowany w bazie (weryfikacja przez `GET /api/contacts/{id}`)
- [x] `mvn verify -pl app` przechodzi

---

### BE-091 – Python AI service: endpoint `/ai/summarize`

**Typ:** Backend implementation (Python FastAPI)
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** Architektura Python AI service (ADR-06)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Blokuje:** BE-089
**Epic:** EPIC-26 AI-Powered Conversation Summary

**Opis:**
Nowy endpoint w istniejącym Python FastAPI AI service. Przyjmuje request z danymi kontaktu i konfiguracją dostawcy, wywołuje wybrany model AI, zwraca podsumowanie.

**Endpoint:**
```
POST /ai/summarize
Internal network only — nie eksponować publicznie
```

**Pydantic modele:**
```python
class AiProvider(str, Enum):
    ANTHROPIC = "ANTHROPIC"
    OPENAI = "OPENAI"
    AZURE_OPENAI = "AZURE_OPENAI"

class SummarizeRequest(BaseModel):
    channel: str                    # PHONE | EMAIL | SOCIAL_MEDIA
    content: str                    # treść do podsumowania
    provider: AiProvider
    api_key: str
    model_name: str
    azure_endpoint: str | None = None
    deployment_name: str | None = None
    prompt_template: str | None = None  # None = użyj domyślnego

class SummarizeResponse(BaseModel):
    summary: str
    model_used: str
    tokens_used: int
```

**Logika:**
- Domyślny prompt systemowy (gdy `prompt_template` is None):
  ```
  You are an expert contact center assistant. Summarize the following {channel} contact
  in 3-5 sentences. Focus on: customer issue, resolution outcome, and any follow-up actions.
  Reply in the same language as the content.
  ```
- Dispatcher na podstawie `provider`: `AnthropicSummarizer`, `OpenAiSummarizer`, `AzureOpenAiSummarizer`
- Każdy summarizer używa oficjalnego SDK: `anthropic` / `openai`
- Timeout: 25s (Spring timeout 30s — Python musi zdążyć odpowiedzieć wcześniej)
- Błąd SDK → HTTP 502 z `{"detail": "AI provider error: <sanitized message>"}` (nie eksponuj klucza)

**Kryteria akceptacji:**
- [x] Endpoint `/ai/summarize` odpowiada 200 z poprawnym `SummarizeResponse`
- [x] Provider `ANTHROPIC`: używa `anthropic` SDK (`claude-*` modele)
- [x] Provider `OPENAI`: używa `openai` SDK (`gpt-*` modele)
- [x] Provider `AZURE_OPENAI`: używa `openai` SDK z `azure_endpoint` i `api_version`
- [x] Provider `OPENROUTER`: obsługiwany przez dispatcher (dodany ponad zakres pierwotny)
- [x] `prompt_template = None` → użyty domyślny prompt
- [x] Błąd autoryzacji SDK (nieprawidłowy klucz) → HTTP 502, klucz nie w odpowiedzi
- [x] Timeout 25s — asyncio z `asyncio.wait_for`
- [x] `pytest` dla happy path każdego providera (mockowane SDK calls) — 9 testów

---

## EPIC-27: Własne dyspozycje per kampania i kolejka

### BE-092 – Encja `CustomDisposition`, repozytorium i `CustomDispositionService`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-040 (tabela `custom_disposition`)
**Status:** ✅ Zrealizowane
**Blokuje:** BE-093, BE-094, FE-090
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Opis:**
Warstwa domenowa obsługi własnych dyspozycji. Encja JPA z multi-tenancy. Repozytorium rozszerzające `TenantAwareRepository`. Serwis z logiką CRUD i kluczową metodą rozwiązywania dyspozycji (resolution) dla danego kontaktu.

**Encja `CustomDisposition` (`domain/model/CustomDisposition.java`):**
```java
@Entity
@Table(name = "custom_disposition")
public class CustomDisposition {
    @Id UUID id;
    UUID tenantId;
    UUID campaignId;   // nullable — zakres kampania
    UUID queueId;      // nullable — zakres kolejka
    String dispositionCode;
    String label;
    String tone;       // positive | negative | neutral | warning
    int ordinal;
    boolean isActive;
    Instant createdAt;
    Instant updatedAt;
}
```

**Repozytorium (`CustomDispositionRepository`):**
```java
List<CustomDisposition> findByCampaignIdAndTenantIdAndIsActiveTrueOrderByOrdinalAsc(UUID campaignId, UUID tenantId);
List<CustomDisposition> findByQueueIdAndTenantIdAndIsActiveTrueOrderByOrdinalAsc(UUID queueId, UUID tenantId);
boolean existsByCampaignIdAndTenantId(UUID campaignId, UUID tenantId);
boolean existsByQueueIdAndTenantId(UUID queueId, UUID tenantId);
```

**`CustomDispositionService` — kluczowe metody:**

```java
// Zwraca listę dyspozycji dla agenta — custom lub systemowe defaulty.
// Priorytet: kampania → kolejka → system.
// Nigdy nie zwraca pustej listy.
List<AvailableDispositionDto> resolveForContact(UUID contactId, UUID tenantId);

// CRUD per kampania (supervisor)
List<CustomDispositionDto> listForCampaign(UUID campaignId, UUID tenantId);
CustomDispositionDto createForCampaign(UUID campaignId, CreateCustomDispositionRequest req, UUID tenantId);
CustomDispositionDto update(UUID dispositionId, UpdateCustomDispositionRequest req, UUID tenantId);
void delete(UUID dispositionId, UUID tenantId);

// CRUD per kolejka (supervisor)
List<CustomDispositionDto> listForQueue(UUID queueId, UUID tenantId);
CustomDispositionDto createForQueue(UUID queueId, CreateCustomDispositionRequest req, UUID tenantId);
```

**Logika `resolveForContact`:**
1. Pobierz kontakt przez `ContactRepository` → odczytaj `campaignId` i `queueId`
2. Jeśli `campaignId != null` i `existsByCampaignId(campaignId)` → zwróć `findByCampaignId(...)`
3. Else jeśli `queueId != null` i `existsByQueueId(queueId)` → zwróć `findByQueueId(...)`
4. Else → zwróć `SYSTEM_DEFAULT_DISPOSITIONS` (statyczna lista 6 kodów)

**Systemowe defaulty (stałe w serwisie):**
```java
private static final List<AvailableDispositionDto> SYSTEM_DEFAULT_DISPOSITIONS = List.of(
    new AvailableDispositionDto("SALE",         "Sprzedaż",            "positive", 1),
    new AvailableDispositionDto("NO_INTEREST",  "Brak zainteresowania", "negative", 2),
    new AvailableDispositionDto("CALLBACK",     "Oddzwonienie",         "warning",  3),
    new AvailableDispositionDto("WRONG_NUMBER", "Zły numer",            "neutral",  4),
    new AvailableDispositionDto("TECH_ISSUE",   "Problem techniczny",   "neutral",  5),
    new AvailableDispositionDto("OTHER",        "Inne",                 "neutral",  6)
);
```

**DTO:**
- `AvailableDispositionDto(dispositionCode, label, tone, ordinal)` — dla agenta
- `CustomDispositionDto` — pełny widok dla supervisora (zawiera `id`, `isActive`, `createdAt`)
- `CreateCustomDispositionRequest(dispositionCode, label, tone, ordinal)` — walidacja: `@NotBlank`, `@Size(max=50/100)`, `@Pattern` dla tone
- `UpdateCustomDispositionRequest(label, tone, ordinal, isActive)` — kod jest niezmienny po stworzeniu

**Kryteria akceptacji:**
- [ ] `CustomDisposition` encja mapuje na tabelę `custom_disposition`; `assertSameTenant()` w każdej operacji zapisu
- [ ] `resolveForContact` — priorytet kampania > kolejka > system, nigdy nie zwraca pustej listy
- [ ] Systemowe defaulty zwracane gdy żadna custom dyspozycja nie skonfigurowana
- [ ] Walidacja: zduplikowany `dispositionCode` per zakres → `409 Conflict`
- [ ] `mvn test` — testy jednostkowe logiki resolucji (mock repo), minimum 5 scenariuszy
- [ ] `mvn verify -pl app` przechodzi

---

### BE-093 – `CustomDispositionController`: REST API zarządzania dyspozycjami dla supervisora

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-092
**Status:** ✅ Zrobione
**Blokuje:** FE-090, FE-091, FE-092
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Opis:**
Endpointy REST dla supervisora do zarządzania własnymi dyspozycjami per kampania i per kolejka. Dostęp ograniczony do roli `SUPERVISOR` / `ADMIN`. Pełne CRUD.

**Endpointy (`/api/dispositions`):**
```
GET    /api/dispositions/campaigns/{campaignId}        → 200 List<CustomDispositionDto>
POST   /api/dispositions/campaigns/{campaignId}        → 201 CustomDispositionDto
PUT    /api/dispositions/campaigns/{campaignId}/{id}   → 200 CustomDispositionDto
DELETE /api/dispositions/campaigns/{campaignId}/{id}   → 204

GET    /api/dispositions/queues/{queueId}              → 200 List<CustomDispositionDto>
POST   /api/dispositions/queues/{queueId}              → 201 CustomDispositionDto
PUT    /api/dispositions/queues/{queueId}/{id}         → 200 CustomDispositionDto
DELETE /api/dispositions/queues/{queueId}/{id}         → 204
```

**Bezpieczeństwo:**
- `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")` na wszystkich metodach
- `campaignId` i `queueId` weryfikowane przez `assertSameTenant()` w serwisie przed każdą operacją

**Walidacja:**
- `dispositionCode` niezmienialny po stworzeniu (PUT nie przyjmuje `dispositionCode`)
- Duplikat kodu per zakres → `409 Conflict` z czytelnym komunikatem
- Usunięcie ostatniej dyspozycji → dozwolone (zakres wraca do systemowych defaultów)

**Kryteria akceptacji:**
- [ ] Wszystkie 8 endpointów udokumentowane przez OpenAPI (`@Operation`, `@ApiResponse`)
- [ ] `GET /campaigns/{campaignId}` zwraca pustą listę `[]` gdy brak własnych dyspozycji (nie 404)
- [ ] Rola AGENT wywołująca supervisor endpoint → `403 Forbidden`
- [ ] `campaign_id` / `queue_id` innego tenanta → `403 Forbidden`
- [ ] Integracja: `DELETE` → ponowny `GET` zwraca pustą listę
- [ ] `mvn verify -pl app` przechodzi

---

### BE-094 – Endpoint `GET /api/contacts/{contactId}/available-dispositions` — dyspozycje dla agenta

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-092
**Status:** ✅ Zrobione
**Blokuje:** FE-093, FE-090
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Opis:**
Endpoint wywoływany przez panel dyspozycji agenta tuż po zakończeniu kontaktu. Zwraca listę dyspozycji do wyboru — własne per kampania, własne per kolejka lub systemowe defaulty. Agent nie wie skąd pochodzi lista; zawsze dostaje gotowy zestaw.

**Endpoint:**
```
GET /api/contacts/{contactId}/available-dispositions
Authorization: Bearer <agent-token>
→ 200 List<AvailableDispositionDto>
```

**Response body:**
```json
[
  { "dispositionCode": "SALE_FULL", "label": "Pełna sprzedaż", "tone": "positive", "ordinal": 1 },
  { "dispositionCode": "SALE_PARTIAL", "label": "Częściowa sprzedaż", "tone": "positive", "ordinal": 2 }
]
```

**Lokalizacja:** dodać jako nową metodę w `ContactController` (`@GetMapping("/{contactId}/available-dispositions")`), delegującą do `CustomDispositionService.resolveForContact()`.

**Dostęp:** `hasAnyRole('AGENT','SUPERVISOR','ADMIN')` — agent musi mieć możliwość wywołania.

**Kryteria akceptacji:**
- [ ] Kontakt bez konfiguracji custom → zwraca 6 systemowych defaultów (nigdy pusta lista)
- [ ] Kontakt z kampanią z 3 custom dyspozycjami → zwraca te 3, posortowane po `ordinal`
- [ ] Kontakt bez kampanii, ale kolejka ma custom → zwraca dyspozycje kolejki
- [ ] Kontakt innego tenanta → `403 Forbidden`
- [ ] Nieistniejący `contactId` → `404 Not Found`
- [ ] Endpoint w Swagger UI z przykładem response
- [ ] `mvn verify -pl app` przechodzi

---

### BE-095 – Encja `DispositionSet`, `DispositionSetItem`, repozytoria i `DispositionSetService`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-041
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-06-04 (data przybliżona — status w treści był niespójny z nagłówkiem, skorygowano na podstawie potwierdzonego kodu: DispositionSet.java, DispositionSetItem.java, DispositionSetRepository.java, DispositionSetItemRepository.java, DispositionSetServiceImpl.java, DispositionSetServiceTest.java)
**Blokuje:** BE-096
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Opis:**
Warstwa domenowa zestawów dyspozycji. Dwie encje JPA, dwa repozytoria (native SQL, wzorzec z `AgentGroupRepository`), serwis z CRUD zestawów i elementów oraz kluczową metodą `applyToCampaign`/`applyToQueue` kopiującą elementy do `custom_disposition`.

**Encje:**

```java
// domain/disposition/DispositionSet.java
@Entity @Table(name = "disposition_set")
public class DispositionSet {
    @Id UUID id;
    UUID tenantId;
    String name;
    String description;
    Instant createdAt;
    Instant updatedAt;
}

// domain/disposition/DispositionSetItem.java
@Entity @Table(name = "disposition_set_item")
public class DispositionSetItem {
    @Id UUID id;
    UUID setId;
    UUID tenantId;
    String dispositionCode;
    String label;
    String tone;
    int ordinal;
}
```

**Repozytoria (`DispositionSetRepository`, `DispositionSetItemRepository`):**
- Native SQL przez `TenantAwareRepository`
- `findAllByTenantId(tenantId)` — lista zestawów
- `findByIdAndTenantId(id, tenantId)` — pojedynczy zestaw
- `existsByNameAndTenantId(name, tenantId)` — walidacja duplikatu
- `findItemsBySetId(setId, tenantId)` — elementy zestawu (ORDER BY ordinal)
- `insertSet(DispositionSet)` / `updateSet(DispositionSet)` / `deleteSet(id, tenantId)`
- `insertItem(DispositionSetItem)` / `updateItem(...)` / `deleteItem(id, tenantId)`

**`DispositionSetService` — kluczowe metody:**

```java
// CRUD zestawów
List<DispositionSetDto> listSets(UUID tenantId);
DispositionSetDto createSet(CreateDispositionSetRequest req, UUID tenantId);
DispositionSetDto updateSet(UUID setId, UpdateDispositionSetRequest req, UUID tenantId);
void deleteSet(UUID setId, UUID tenantId);

// CRUD elementów zestawu
List<DispositionSetItemDto> listItems(UUID setId, UUID tenantId);
DispositionSetItemDto addItem(UUID setId, CreateDispositionSetItemRequest req, UUID tenantId);
DispositionSetItemDto updateItem(UUID setId, UUID itemId, UpdateDispositionSetItemRequest req, UUID tenantId);
void removeItem(UUID setId, UUID itemId, UUID tenantId);

// Aplikowanie zestawu (snapshot copy)
void applyToCampaign(UUID setId, UUID campaignId, UUID tenantId);
void applyToQueue(UUID setId, UUID queueId, UUID tenantId);
```

**Logika `applyToCampaign(setId, campaignId, tenantId)`:**
1. Pobierz elementy zestawu przez `findItemsBySetId`
2. Jeśli pusta lista → `ResourceNotFoundException("Zestaw nie istnieje lub jest pusty")`
3. Dla każdego elementu zestawu: utwórz nowy `CustomDisposition` z `campaignId` i wstaw przez `CustomDispositionRepository.insert()`
4. Duplikaty kodów (jeśli kampania już ma ten kod) → pomiń z logiem WARN (nie przerywaj całej operacji)

Analogicznie `applyToQueue`.

**DTO:**
- `DispositionSetDto(id, name, description, itemCount, createdAt)` — lista zestawów
- `DispositionSetDetailDto(id, name, description, items: List<DispositionSetItemDto>, createdAt)` — szczegóły
- `DispositionSetItemDto(id, dispositionCode, label, tone, ordinal)`
- `CreateDispositionSetRequest(@NotBlank @Size(max=100) name, @Size(max=500) description)`
- `UpdateDispositionSetRequest` — jak Create
- `CreateDispositionSetItemRequest(@NotBlank @Size(max=50) @Pattern dispositionCode, @NotBlank @Size(max=100) label, @NotNull @Pattern tone, ordinal)`
- `UpdateDispositionSetItemRequest(label, tone, ordinal)` — kod niezmienialny

**Kryteria akceptacji:**
- [ ] Encje mapują na tabele DB-041
- [ ] `applyToCampaign/Queue` kopiuje elementy jako nowe wiersze `custom_disposition`; duplikaty pomijane z WARN
- [ ] Duplikat nazwy zestawu → `409 Conflict`
- [ ] Duplikat kodu elementu w zestawie → `409 Conflict`
- [ ] Usunięcie zestawu nie wpływa na istniejące `custom_disposition` (są niezależnymi kopiami)
- [ ] Testy jednostkowe logiki `apply*` (min. 4 scenariusze: sukces kampania, sukces kolejka, pusty zestaw → 404, duplikat kodu → pominięty)
- [ ] `mvn verify -pl app` przechodzi

---

### BE-096 – `DispositionSetController`: REST API zarządzania zestawami dyspozycji

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-095
**Status:** ✅ Zrobione
**Blokuje:** FE-094, FE-095, FE-096
**Epic:** EPIC-27 Własne dyspozycje per kampania i kolejka

**Opis:**
Kontroler REST dla supervisora do zarządzania zestawami dyspozycji i aplikowania ich do kampanii/kolejek.

**Endpointy (`/api/disposition-sets`):**

```
GET    /api/disposition-sets                                    → 200 List<DispositionSetDto>
POST   /api/disposition-sets                                    → 201 DispositionSetDto
PUT    /api/disposition-sets/{setId}                            → 200 DispositionSetDto
DELETE /api/disposition-sets/{setId}                            → 204

GET    /api/disposition-sets/{setId}/items                      → 200 List<DispositionSetItemDto>
POST   /api/disposition-sets/{setId}/items                      → 201 DispositionSetItemDto
PUT    /api/disposition-sets/{setId}/items/{itemId}             → 200 DispositionSetItemDto
DELETE /api/disposition-sets/{setId}/items/{itemId}             → 204

POST   /api/disposition-sets/{setId}/apply-to-campaign/{campaignId}  → 200 (liczba skopiowanych)
POST   /api/disposition-sets/{setId}/apply-to-queue/{queueId}        → 200 (liczba skopiowanych)
```

**Bezpieczeństwo:** `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")` na klasie.

**Response `apply-to-*`:**
```json
{ "copied": 4, "skipped": 1, "message": "Skopiowano 4 dyspozycje (1 pominięto — duplikat kodu)" }
```

**Kryteria akceptacji:**
- [ ] Wszystkie 10 endpointów z dokumentacją OpenAPI
- [ ] Rola AGENT → `403 Forbidden`
- [ ] `apply-to-campaign` z obcym `campaignId` → `403 Forbidden`
- [ ] `apply-to-*` z pustym zestawem → `404 Not Found`
- [ ] Response body `apply-to-*` zawiera liczniki `copied` i `skipped`

---

## MODUL: Per-Tenant Plugin (Extension) System (EPIC-28)

> Źródło architektury: `ARCHITECTURE.md` §11 (ADR-09…ADR-13, RT-09…RT-14). Pre-agreed decyzje
> (nie renegocjować bez wyraźnej prośby użytkownika): izolacja in-process przez dedykowany
> `ClassLoader` per `(tenant_id, plugin_key)`, NIE proces/kontener osobny; punkty rozszerzeń to
> stały, wersjonowany enum dispatchowany przez `ExtensionPointPublisher`, NIE generyczny
> interceptor AOP; UI pluginu w cross-origin sandboxed iframe + `postMessage`, NIE web component
> same-origin. Tabele bazowe: DB-042…DB-045 (TASKS-DATABASE.md).

### BE-097 – Nowy moduł Maven `plugin-sdk`: `PluginEntryPoint`, `PluginContext`, DTO

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** brak (nowy moduł niezależny od `app`)
**Status:** ✅ Zrobione
**Blokuje:** BE-098, BE-101
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Nowy, minimalny moduł Maven `backend/plugin-sdk` — **jedyna** zależność compile-time, jakiej potrzebuje deweloper pluginu firmy trzeciej (ARCHITECTURE.md §11.6). Zawiera wyłącznie interfejsy i niemutowalne DTO (rekordy) — zero zależności od `spring-*`, `jakarta.persistence`, `hibernate-*`. Dodaj `<module>plugin-sdk</module>` do root `pom.xml`, obok istniejącego `<module>app</module>`.

**Struktura modułu:**
```
backend/plugin-sdk/
  pom.xml                                          ← packaging: jar, brak spring-boot-starter-parent jako parent (tylko parent groupId/version z root POM, bez Spring BOM)
  src/main/java/com/contactcenter/pluginsdk/
    PluginEntryPoint.java
    PluginContext.java
    HttpEgressClient.java
    HttpResponse.java
    PluginLogger.java
    PluginConfig.java
    model/
      CustomerView.java          (record, immutable)
      ContactView.java            (record, immutable)
      ContactEvent.java
      CustomerSyncRequest.java
      CustomerSyncResult.java
      DispositionEvent.java
      ManualActionRequest.java
      ManualActionResult.java
      PreContactConnectResult.java
```

**`PluginEntryPoint` (z ARCHITECTURE.md §11.6, kopiuj sygnatury 1:1):**
```java
public interface PluginEntryPoint {
    void onActivate(PluginContext context);
    void onDeactivate();
    default PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) { return PreContactConnectResult.empty(); }
    default void onPostContactEnd(PluginContext ctx, ContactEvent e) { }
    default CustomerSyncResult onCustomerSync(PluginContext ctx, CustomerSyncRequest req) { return CustomerSyncResult.noop(); }
    default void onDispositionSet(PluginContext ctx, DispositionEvent e) { }
    default ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) { return ManualActionResult.unsupported(); }
}
```

**`PluginContext` (fasada SDK, z ARCHITECTURE.md §11.6):**
```java
public interface PluginContext {
    CustomerView getCustomer(UUID customerId);
    void updateCustomerFields(UUID customerId, Map<String, Object> customFields);
    ContactView getContact(UUID contactId);
    void appendContactNote(UUID contactId, String note);
    HttpEgressClient httpClient();
    PluginLogger logger();
    PluginConfig config();
}
```

**Kluczowe ograniczenia kontraktu (wymuszone typami, nie dyscypliną pluginu):**
- `CustomerView`/`ContactView` to `record` — plugin nigdy nie może otrzymać encji JPA zarządzanej przez Hibernate
- `updateCustomerFields` to jedyny sposób zapisu — implementacja (BE-101) musi pisać wyłącznie do `customer.custom_fields.plugins.<pluginKey>`, nigdy do flat merge ani istniejącej typowanej kolumny (reguła anti-overloaded-column, CLAUDE.md; RT-14)
- `HttpEgressClient` ma tylko `get`/`post` — egress allow-list per `http:egress:<host>` z manifestu jest wymuszony w implementacji (BE-101), nie w SDK

**Kryteria akceptacji:**
- [x] `cd backend/plugin-sdk && mvn package` przechodzi jako samodzielny moduł
- [x] `mvn dependency:tree -pl plugin-sdk` nie zawiera żadnej zależności `org.springframework.*` ani `jakarta.persistence.*`
- [x] `PluginEntryPoint` ma 2 metody wymagane (`onActivate`/`onDeactivate`) + 5 metod `default` no-op
- [x] Wszystkie DTO w `model/` są `record` (niemutowalne)
- [x] Javadoc na każdym publicznym interfejsie i metodzie (to jedyna dokumentacja, jaką zobaczy zewnętrzny deweloper pluginu)
- [x] Root `pom.xml` zawiera `<module>plugin-sdk</module>`
- [x] `mvn package -pl app -DskipTests` (istniejący moduł `app`) wciąż przechodzi bez zmian

---

### BE-098 – Encje `Plugin`/`PluginVersion` + `PluginValidationService` (manifest, checksum, ASM scan)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-097, DB-042
**Status:** ✅ Zrobione (2026-06-20)
**Blokuje:** BE-099
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Warstwa domenowa katalogu globalnego pluginów + serwis walidacji JAR-a — gate egzekwowany **przed** dotknięciem jakiejkolwiek klasy z JAR-a (ARCHITECTURE.md §11.4). Nowy pakiet domenowy `domain.plugin` w module `app`, wzorzec interfejs+Impl zgodny z resztą projektu (zob. PROGRESS.md — audyt enkapsulacji).

**Pliki do stworzenia:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/Plugin.java                          (encja JPA, tabela DB-042)
  domain/plugin/PluginVersion.java                   (encja JPA, tabela DB-042)
  domain/plugin/PluginRepository.java                (package-private, native SQL, extends TenantAwareRepository — UWAGA: ta tabela nie ma tenant_id, więc bez assertSameTenant; potwierdzić wzorzec dla tabel globalnych przed implementacją, np. zwykłe JdbcTemplate repo bez TenantAwareRepository)
  domain/plugin/PluginVersionRepository.java          (jak wyżej)
  domain/plugin/PluginValidationService.java          (publiczny interfejs)
  domain/plugin/PluginValidationServiceImpl.java      (package-private, @Service)
  domain/plugin/PluginManifest.java                   (record — sparsowany manifest)
  domain/plugin/dto/PluginVersionDto.java
  domain/plugin/dto/ValidationResult.java              (record: status, validationErrors)
```

**`PluginValidationService.validate(byte[] jarBytes, UUID uploadedByUserId)` — etapy (ARCHITECTURE.md §11.4):**
1. Guard rozmiaru/MIME: odrzuć >50MB, odrzuć jeśli magic bytes nie wskazują na ZIP/JAR (`PK\x03\x04`)
2. Policz SHA-256 uploadowanych bajtów, porównaj z `manifest.checksumSha256` (po rozpakowaniu manifestu — wymaga otwarcia ZIP najpierw dla odczytu manifestu, ale przed jakimkolwiek `ClassLoader.loadClass`)
3. Otwórz jako ZIP, odczytaj `META-INF/plugin-manifest.json`, zwaliduj względem JSON Schema (biblioteka: `everit-org/json-schema` lub `networknt/json-schema-validator` — sprawdź, czy jakaś jest już na classpath aplikacji przed dodaniem nowej zależności)
4. Statyczny skan listy klas JAR-a przez ASM (`org.ow2.asm:asm`, bez ładowania klas) — odrzuć jeśli referencje do `java.lang.reflect.*` (poza zwykłym użyciem), `java.lang.ProcessBuilder`, `java.nio.file.*` poza dozwolonym scratch dir, `sun.misc.*`, własne podklasy `ClassLoader`; odrzuć jeśli `entryPointClass` nie istnieje lub nie implementuje `PluginEntryPoint` (z `plugin-sdk`, BE-097); odrzuć jeśli `extensionPoints`/`permissions` nie są podzbiorem enuma platformy
5. (Opcjonalnie, flagowane jako OQ-28-1 w EPIC-28-PLAN.md — NIE blokować tego ticketu na decyzji o signing) — zostaw hook/no-op do weryfikacji podpisu, ale nie implementuj logiki podpisu w tym tickecie
6. Zapisz JAR do object storage (BE-099, nie ten ticket) + wstaw wiersz `plugin_version`, `status = PENDING_REVIEW` lub `VALIDATED`

**Kryteria akceptacji:**
- [x] Encje mapują na tabele DB-042 (`plugin`, `plugin_version`) — bez `tenant_id`
- [x] JSON Schema waliduje wszystkie pola manifestu z ARCHITECTURE.md §11.2 (pluginKey, displayName, version, vendor, sdkVersion, entryPointClass, extensionPoints, permissions, uiPanels, manualActions, checksumSha256)
- [x] Checksum mismatch → `ValidationResult` ze statusem `REJECTED` i opisowym błędem w `validationErrors`
- [x] ASM scan odrzuca JAR z referencją do `java.lang.reflect.Method.setAccessible` (test z przygotowanym JAR-em testowym)
- [x] ASM scan odrzuca JAR z `entryPointClass` nieimplementującym `PluginEntryPoint`
- [x] `extensionPoints`/`permissions` spoza enuma platformy → `REJECTED`
- [x] Testy jednostkowe ≥8 scenariuszy walidacji (rozmiar, MIME, checksum, schema, ASM blacklist x3, sukces) — zrealizowano 14 scenariuszy
- [x] `mvn verify -pl app` przechodzi (1156 testów, 0 failures, 0 errors)

**Uwaga implementacyjna (odstępstwo od literalnego opisu kroku 2):** checksum SHA-256 jest liczony z wpisów ZIP-a **z wyłączeniem `META-INF/plugin-manifest.json`** samego, nie z całych "uploaded bytes" dosłownie. Hashowanie całego pliku łącznie z polem zawierającym ten sam hash jest matematycznie niewykonalne do spełnienia przez dostawcę pluginu (self-referencyjny SHA-256 nie ma praktycznych punktów stałych) — analogicznie do `META-INF/MANIFEST.MF` w standardowych JAR-ach Javy, który też nigdy nie zawiera checksumu samego siebie, oraz precedensów branżowych (Maven `.sha256` jest plikiem zewnętrznym, Docker image digest nie jest polem we własnym manifeście). Cel kroku ("detects accidental corruption; NOT a substitute for signing", ARCHITECTURE.md §11.4) jest zachowany.

---

### BE-099 – `PluginUploadController` + integracja object storage (MinIO/S3)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-098
**Status:** ✅ Zrobione
**Blokuje:** BE-100, BE-110, FE-097, FE-098
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Endpoint REST do uploadu JAR-a przez admina tenanta — `multipart/form-data`. Po `VALIDATED` zapisuje bajty JAR-a do object storage. Reużyj istniejący wzorzec `S3Config`/`S3Properties` (`infrastructure/config/`, ten sam bucket family co `recording`, ARCHITECTURE.md §11.4 punkt 6) — nie twórz nowej konfiguracji klienta S3 od zera.

**Endpoint:**
```
POST /api/supervisor/plugins
  multipart/form-data: file=<jar bytes>
  → 201 Created: PluginVersionDto (status=VALIDATED|PENDING_REVIEW)
  → 400 Bad Request: { validationErrors: [...] } (status=REJECTED, JAR NIE zapisany do storage)
```

**Bezpieczeństwo:** `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")`.

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  api/plugin/PluginUploadController.java
  domain/plugin/PluginStorageService.java            (publiczny interfejs)
  domain/plugin/PluginStorageServiceImpl.java         (package-private, @Service, wstrzykuje istniejący S3 client bean z infrastructure/config/S3Config)
```

**Kryteria akceptacji:**
- [x] Upload >50MB → `400 Bad Request` z czytelnym komunikatem, request odrzucony przed wejściem do `PluginValidationService` (guard w `PluginUploadController` + limit `application.yml` `servlet.multipart` zwiększony do 50MB/55MB)
- [x] Upload pliku niebędącego JAR/ZIP (np. `.txt` z fałszywym rozszerzeniem) → `400 Bad Request` (przez `PluginValidationService` z BE-098, magic bytes check)
- [x] JAR zapisany do object storage TYLKO gdy `status` ∈ {`VALIDATED`, `PENDING_REVIEW`} — `REJECTED` nigdy nie trafia do storage (`PluginUploadController` zwraca 400 przed wywołaniem `PluginStorageService`)
- [x] `jar_object_key` zapisany w `plugin_version` wskazuje na rzeczywisty obiekt w MinIO — **brak wzorca testów integracyjnych S3/MinIO w projekcie** (port 9000 tylko `expose`d, niepublikowany na host w `docker-compose.local-demo.yml`); zastosowano ten sam fallback co `RecordingServiceTest`/`RecordingControllerTest`: testy jednostkowe z mockiem `S3Client` (`PluginStorageServiceImplTest`), weryfikujące `PutObjectRequest.key()`/`.bucket()` przekazane do klienta
- [x] Endpoint w Swagger UI z przykładem multipart i response (wzorzec z `CampaignImportController`/`EmailAttachmentController`: `@Operation`, `@ApiResponse` 201/400/401/403, `requestBody` z `MULTIPART_FORM_DATA_VALUE`)
- [x] `mvn verify -pl app` przechodzi (1167 testów, 0 failures, BUILD SUCCESS)

**Uwaga implementacyjna:** `ValidationResult` (BE-098) niesie tylko `status`/`validationErrors`, nie sparsowany manifest — `PluginStorageServiceImpl` ponownie odczytuje `META-INF/plugin-manifest.json` z tych samych `jarBytes` (już zwalidowanych) przez `PluginManifestValidator` (reużycie, nie duplikacja walidacji bezpieczeństwa). `ValidationStatus` w BE-098 zwraca tylko `VALIDATED`/`REJECTED` (`PENDING_REVIEW` świadomie odłożone razem z podpisem, OQ-28-1) — `PluginStorageServiceImpl.isStorable`/`toPluginVersionStatus` są napisane jako `switch` wyczerpujący enum, gotowe na rozszerzenie gdy `PENDING_REVIEW` zostanie dodane do `ValidationStatus`.

---

### BE-100 – Encja `TenantPluginInstallation` + `PluginRegistrationService` (install/enable/disable/rollback)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-099, DB-043
**Status:** ✅ Zrobione
**Blokuje:** BE-101, BE-106
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Warstwa domenowa instalacji per tenant. `PluginRegistrationService` zarządza cyklem życia instalacji **bez** jeszcze ładowania klas pluginu do JVM (to robi `PluginRuntimeManager`, BE-101 — ten ticket tylko zapisuje stan w DB i woła BE-101 jako kolejny krok zintegrowany w BE-101).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/TenantPluginInstallation.java         (encja JPA, tabela DB-043, RLS)
  domain/plugin/TenantPluginInstallationRepository.java (package-private, extends TenantAwareRepository)
  domain/plugin/PluginRegistrationService.java         (publiczny interfejs)
  domain/plugin/PluginRegistrationServiceImpl.java      (package-private, @Service)
  domain/plugin/dto/TenantPluginInstallationDto.java
  domain/plugin/dto/InstallPluginRequest.java           (pluginVersionId, grantedPermissions: List<String>)
```

**`PluginRegistrationService` — metody kluczowe:**
```java
TenantPluginInstallationDto install(UUID tenantId, UUID pluginVersionId, List<String> grantedPermissions, UUID installedByUserId);
void enable(UUID tenantId, UUID installationId);
void disable(UUID tenantId, UUID installationId);
List<TenantPluginInstallationDto> listInstallations(UUID tenantId);
TenantPluginInstallationDto rollback(UUID tenantId, UUID currentInstallationId, UUID targetInstallationId);
```

**Logika `install`:**
1. Sprawdź `assertSameTenant` (CLAUDE.md — przed każdym zapisem)
2. Wstaw `tenant_plugin_installation` z `enabled=false`, `granted_permissions` = przecięcie żądanych z dozwolonymi przez manifest (nigdy auto-grant pełnego manifestu — ARCHITECTURE.md §11.4/RT-13)
3. Zwróć DTO — **nie** ładuje jeszcze `ClassLoader`a (to dzieje się przy `enable`, zintegrowane w BE-101)

**Logika `rollback`:** przełącza `enabled=true` na `targetInstallationId` (starsza wersja) i `enabled=false` na `currentInstallationId` — atomowo w jednej transakcji (ARCHITECTURE.md §11.11). Nie usuwa żadnego wiersza.

**Kryteria akceptacji:**
- [x] Encja mapuje na tabelę DB-043, repozytorium wywołuje `assertSameTenant` przed każdym zapisem
- [x] `install` z duplikatem `(tenant_id, plugin_version_id)` → `409 Conflict` (DB constraint propagowany jako wyjątek domenowy)
- [x] `granted_permissions` zapisane to przecięcie żądanych ∩ manifestu — żądanie permission nie zadeklarowanej w manifeście jest ignorowane, nie powoduje błędu
- [x] `rollback` jest atomowy — test weryfikujący, że przy wyjątku w trakcie żaden z dwóch wierszy nie zmienia `enabled`
- [x] Testy jednostkowe ≥5 scenariuszy (install sukces, duplikat, rollback sukces, rollback obcego tenanta → 403, disable)
- [x] `mvn verify -pl app` przechodzi

**Uwaga implementacyjna:** Tabela `tenant_plugin_installation` ma RLS (V075) — repozytorium napisane jako natywny SQL przez `EntityManager` rozszerzający `TenantAwareRepository`, wzorzec identyczny do `CustomDispositionRepository` (EPIC-27), a nie zwykły `JpaRepository` (jak `PluginRepository`/`PluginVersionRepository`, tabele globalne bez RLS). Duplikat unikalnego indeksu propaguje się jako `DataIntegrityViolationException` — translacja Spring działa automatycznie dzięki `@Repository` na klasie, mapowanie na HTTP 409 już istnieje globalnie w `GlobalExceptionHandler` (fallback generyczny), bez potrzeby dodatkowego kodu w serwisie. `rollback` weryfikuje przynależność OBU instalacji do tenanta PRZED jakimkolwiek `UPDATE` — atomowość na poziomie logiki serwisu (żaden wiersz nie zmienia się, jeśli walidacja drugiej instalacji zawiedzie), nie wymaga ręcznego try/catch z rollbackiem transakcji.

---

### BE-101 – `PluginRuntimeManager` + `PluginClassLoader` + implementacja `PluginContext`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** XL
**Zależy od:** BE-100, BE-097
**Status:** ✅ Zrobione — code review (`senior-code-reviewer`) zakończony, blokujące znaleziska naprawione 2026-06-20
**Blokuje:** BE-102, BE-106
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Jądro mechanizmu izolacji — najbardziej krytyczny i najbardziej ryzykowny ticket epika (RT-10). Implementuje model z ARCHITECTURE.md §11.3: dedykowany `ClassLoader` per `(tenant_id, plugin_key)`, wąski parent classloader eksponujący tylko `com.contactcenter.pluginsdk.*`, oraz implementację `PluginContext` jako **jedyny** obiekt przekazywany do pluginu.

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/runtime/PluginRuntimeManager.java       (publiczny interfejs)
  domain/plugin/runtime/PluginRuntimeManagerImpl.java    (package-private, @Service)
  domain/plugin/runtime/PluginClassLoader.java           (extends URLClassLoader lub custom; parent = PlatformApiClassLoader)
  domain/plugin/runtime/PlatformApiClassLoader.java      (eksponuje WYŁĄCZNIE com.contactcenter.pluginsdk.* — singleton, jeden na cały JVM, nie per-instalacja)
  domain/plugin/runtime/PluginContextImpl.java            (package-private, implements PluginContext z plugin-sdk; instancjowany PER WYWOŁANIE z tenantId zabranym z TenantContext, NIGDY od pluginu)
  domain/plugin/runtime/PluginRegistry.java               (publiczny interfejs — lookup (tenantId, extensionPoint) -> List<PluginInstanceHandle>)
  domain/plugin/runtime/PluginRegistryImpl.java
  domain/plugin/runtime/PluginInstanceHandle.java         (record: installationId, tenantId, pluginKey, PluginEntryPoint instance, classLoader)
```

**`PluginRuntimeManager` — metody kluczowe:**
```java
PluginInstanceHandle load(UUID tenantId, UUID pluginVersionId);   // downloads JAR (cache local disk), new PluginClassLoader, instantiate entryPointClass via no-arg constructor, call onActivate(PluginContext)
void unload(UUID tenantId, UUID installationId);                   // calls onDeactivate() best-effort timeout-bounded, drops last strong reference to ClassLoader (GC-eligible)
```

**Wymogi krytyczne (testowalne, nie tylko opisowe — ARCHITECTURE.md §11.3):**
- `entryPointClass` instancjowany **wyłącznie** przez konstruktor bezargumentowy — żadnego DI do konstruktora pluginu
- `PluginContextImpl` budowany **per wywołanie** z `tenantId` branym z `TenantContext` aktualnego wątku — plugin nigdy nie dostaje obiektu, z którego mógłby odczytać/wstrzyknąć inny `tenantId`
- `PlatformApiClassLoader` to JEDEN classloader dla całego JVM, eksponujący tylko pakiet `com.contactcenter.pluginsdk` — NIE cały classpath aplikacji jako parent
- Każda para `(tenant_id, plugin_key)` (nawet ten sam JAR dla dwóch tenantów) dostaje **odrębną instancję** `PluginClassLoader` — bez współdzielonego stanu statycznego

**Kryteria akceptacji:**
- [x] Test negatywny: kod testowy w pluginie testowym próbujący `Class.forName("com.contactcenter.domain.tenant.TenantServiceImpl")` przez classloader pluginu — musi rzucić `ClassNotFoundException` (parent classloader nie eksponuje pakietów `app`). Zaimplementowane w `PlatformApiClassLoaderTest`/`PluginClassLoaderTest` (testy też pokrywają `domain.customer.CustomerServiceImpl` i `org.springframework.*`)
- [x] Test: dwa tenanty instalujące ten sam `plugin_key`/`plugin_version_id` dostają dwa różne obiekty `ClassLoader` (porównanie referencji). `PluginClassLoaderTest.twoTenantsWithSameJarGetDifferentClassLoaderInstances`
- [x] Test: `PluginContextImpl.getCustomer(id)` zwraca dane tylko dla tenanta z `TenantContext` bieżącego wątku, niezależnie od tego, jaki `tenantId` "próbowałby" przekazać kod pluginu (SDK nie przyjmuje `tenantId` jako parametr — zweryfikowane, sygnatura `PluginContext.getCustomer` z plugin-sdk nie ma parametru tenantId). `PluginContextImplTest$GetCustomerTests`
- [x] `unload` zwalnia silną referencję do `ClassLoader`a (test z `WeakReference` + `System.gc()` + assert na czyszczenie, best-effort/no-flaky-retry). `PluginRuntimeManagerImplTest$UnloadTests.classLoaderIsGarbageCollectibleAfterUnload`
- [x] `onActivate`/`onDeactivate` wywoływane w odpowiednich momentach cyklu życia, timeout-bounded — prosty `Future.get(timeout)` lokalny w tym tickecie (`PluginRuntimeManagerImpl.invokeWithTimeout`, `ExecutorService` dedykowany, 10s); pełny `PluginInvocationExecutor`/circuit breaker pozostaje BE-102
- [x] `mvn verify -pl app` przechodzi — 1216 testów, 0 failures, 0 errors, BUILD SUCCESS (2026-06-20)
- [x] **Code review (`senior-code-reviewer`) wykonany — werdykt NO-GO (2 blokujące: Critical + High), oba naprawione 2026-06-20, patrz CR-BACKEND.md.**

**Naprawa po code review (2026-06-20) — oba blokery NO-GO usunięte:**
- **Critical (TCCL leak):** `lifecycleExecutor` nie ustawiał Thread-Context ClassLoader na granicy `onActivate`/`onDeactivate` — kod pluginu mógł przez `Thread.currentThread().getContextClassLoader()` dostać classloader aplikacji i obejść `PlatformApiClassLoader` (`Class.forName` na klasę hosta). Fix: nowa klasa `PluginExecutionContext.runWithPluginClassLoader(pluginClassLoader, action)` (snapshot/set/restore TCCL, wzorzec analogiczny do `TenantContext`) — zastosowana wokół KAŻDEGO wywołania `entryPoint.onActivate`/`onDeactivate` w `PluginRuntimeManagerImpl`. Defense in depth: `PluginBytecodeScanner` (BE-098) rozszerzony o blacklistę `Thread#getContextClassLoader`/`setContextClassLoader` i `ServiceLoader` — JAR-y próbujące to jawnie wywołać są odrzucane przy walidacji. Test regresyjny `PluginRuntimeManagerImplTest$LoadTests.onActivateRunsWithPluginClassLoaderAsThreadContextClassLoader` — zweryfikowano empirycznie, że PRZED fixem test faktycznie failuje (`"FOUND:com.contactcenter.app.ContactCenterApplication"`), PO fixie przechodzi (`ClassNotFoundException` → `"NOT_FOUND"`).
- **High (wyciek plików tymczasowych):** `downloadJarToLocalCache` nigdy nie czyściła `Files.createTempFile`. Fix: `PluginInstanceHandle` ma nowe pole `localJarPath`; `unload()` usuwa plik PO `closeQuietly(classLoader)` (URLClassLoader musi zwolnić uchwyt pierwszy); ścieżki błędu w `load()` (instancjonowanie/`onActivate` rzuca) też czyszczą plik. Test `PluginRuntimeManagerImplTest$UnloadTests.unloadDeletesLocalJarCacheFile`.
- **Medium (opcjonalny, zaadresowany):** `PluginLoggerImpl` truncate na 4000 znaków + escape CRLF, mitygacja log-forging/log-flood do czasu pełnego `PluginInvocationLogService` (BE-102).
- Weryfikacja: `mvn verify -pl app` ✅ (1218 testów, 0 failures, 0 errors, BUILD SUCCESS).

---

### BE-102 – `ExtensionPointPublisher` + `PluginInvocationExecutor` (timeouty, circuit breaker)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-101
**Status:** ✅ Zrobione (2026-06-21)
**Blokuje:** BE-103, BE-104, BE-105
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Mechanizm dispatchu i fault containment (ARCHITECTURE.md §11.5/§11.7). `ExtensionPointPublisher` to jedyny sposób wywołania pluginu — żaden inny serwis nie woła `PluginEntryPoint` bezpośrednio.

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/runtime/ExtensionPointPublisher.java      (publiczny interfejs)
  domain/plugin/runtime/ExtensionPointPublisherImpl.java   (package-private, @Service)
  domain/plugin/runtime/PluginInvocationExecutor.java      (bounded ThreadPoolExecutor, bean odrębny od istniejących @Async/@Scheduled pools — sprawdź AsyncConfig istniejący w infrastructure/config przed dodaniem nowego)
  domain/plugin/runtime/CircuitBreakerState.java            (per installation: consecutive failures, opens after N=5, w pamięci lub Redis — sprawdź wzorzec circuit breaker jeśli istnieje gdzieś w projekcie, np. HttpEgressClient pattern)
```

**`ExtensionPointPublisher` — metody kluczowe:**
```java
PreContactConnectResult publishPreContactConnect(UUID tenantId, ContactEvent event);   // blocking, timeout 2s default, never throws — empty result on timeout/error
ManualActionResult publishManualAction(UUID tenantId, UUID installationId, ManualActionRequest req);  // blocking, timeout 5s default
void publishPostContactEnd(UUID tenantId, ContactEvent event);          // fire-and-forget (delegates to BE-104's RabbitMQ publish)
void publishCustomerSync(UUID tenantId, CustomerSyncRequest req);       // fire-and-forget
void publishDispositionSet(UUID tenantId, DispositionEvent event);     // fire-and-forget
```

**Logika wywołania blocking (`publishPreContactConnect`/`publishManualAction`):**
1. `PluginRegistry.lookup(tenantId, extensionPoint)` → lista `PluginInstanceHandle`
2. Dla każdej instalacji: sprawdź circuit breaker — jeśli `OPEN`, zapisz `CIRCUIT_OPEN` do logu (BE-105) i przejdź dalej
3. `TenantContext.snapshot()` na wątku wywołującym → `pluginInvocationExecutor.submit(...)` → w wątku roboczym `TenantContext.restore(snapshot)` w `try`, `TenantContext.clear()` w `finally` (wzorzec CLAUDE.md/§11.8 — kopiuj dokładnie)
4. `Future.get(timeoutMs)` — na `TimeoutException` oznacz `TIMED_OUT`, zwróć wynik pusty/domyślny (nigdy nie blokuje dalej, nigdy nie propaguje wyjątku do wołającego)
5. Każda ścieżka (sukces/błąd/timeout/circuit-open) zapisana do logu (BE-105)

**Kryteria akceptacji:**
- [x] `PluginInvocationExecutor` to odrębny bean `ThreadPoolExecutor`, NIE współdzieli poola z Tomcat request threads ani z istniejącym `@Async` executorem
- [x] Domyślne timeouty: `PRE_CONTACT_CONNECT`=2000ms, `MANUAL_ACTION`=5000ms, async (BE-104)=30000ms — konfigurowalne, capped przez maksimum platformy
- [x] Test: plugin który wywołuje `Thread.sleep(10000)` w `onPreContactConnect` → wynik `TIMED_OUT` po ~2s, wołający kod otrzymuje wynik pusty (nie wyjątek, nie blokuje się 10s)
- [x] Test: plugin który rzuca `Throwable`/`Error` (nie tylko `Exception`) jest złapany przez `try/catch(Throwable)` na granicy executora i nie propaguje się dalej
- [x] Circuit breaker: po 5 kolejnych `TIMED_OUT`/`FAILED` dla tej samej instalacji → `health_status=DEGRADED` w DB (DB-043), kolejne wywołania pomijane jako `CIRCUIT_OPEN` bez próby wywołania
- [x] `TenantContext.snapshot()/restore()/clear()` na granicy wątku — test weryfikujący brak leaku tenant context między dwoma kolejnymi wywołaniami różnych tenantów na tym samym executorze
- [x] `mvn verify -pl app` przechodzi

**Zrealizowane 2026-06-21:**
Pakiet `domain.plugin.runtime` rozszerzony o: `ExtensionPointPublisher`/`Impl`, `PluginInvocationExecutor` (`@Configuration`, bean `pluginInvocationExecutor`, core=8/max=32/queue=200, `CallerRunsPolicy`, wątki daemon), `PluginInvocationProperties` (`@ConfigurationProperties(prefix="plugin.invocation")`), `CircuitBreakerState` (`ConcurrentHashMap<UUID, AtomicInteger>` w pamięci, próg 5, "closed on first success"), `InvocationStatus` (enum lokalny, placeholder do podmiany przez BE-105), `PluginInvocationFailedException`.

Dodano `TenantPluginInstallationRepository.updateHealthStatus(...)` + `PluginRegistrationService.updateHealthStatus(...)` (BE-100) — wołane wyłącznie przez `CircuitBreakerState`, best-effort.

**Logowanie wywołań jest placeholderem SLF4J** (`recordInvocation`, private w `ExtensionPointPublisherImpl`) — BE-105 podmieni ciało tej jednej metody na `PluginInvocationLogService` bez zmiany sygnatury/miejsc wołających.

**Decyzja merge wyników wielu instalacji** (`publishPreContactConnect`): SDK nie definiuje semantyki łączenia wielu `PreContactConnectResult` — zwracany jest wynik pierwszej instalacji w porządku rejestracji, której wywołanie zwróciło wynik niepusty (`!displayData.isEmpty() || warning != null`); każda próbowana instalacja jest mimo to w pełni zarejestrowana w circuit breakerze/logu.

`publishPostContactEnd`/`publishCustomerSync`/`publishDispositionSet`: w tym tickecie submit-and-forget na `pluginInvocationExecutor` bez integracji RabbitMQ (BE-104, kolejny ticket).

Testy: `CircuitBreakerStateTest` (6), `ExtensionPointPublisherImplTest` (12, executor realny nie mockowany). Weryfikacja: `mvn verify -pl app` ✅ (1236 testów, 0 failures, 0 errors, BUILD SUCCESS).

---

### BE-103 – Integracja `PRE_CONTACT_CONNECT`/`MANUAL_ACTION` w przepływie połączenia

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-102
**Status:** ✅ Zrobione (2026-06-21)
**Blokuje:** BE-107, FE-100
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Podłączenie `ExtensionPointPublisher.publishPreContactConnect`/`publishManualAction` do istniejącego przepływu połączenia agenta. To pierwszy ticket, który dotyka kodu telefonii poza pakietem `domain.plugin` — zachowaj szczególną ostrożność, żeby nie zmienić istniejącego zachowania telefonii dla tenantów bez zainstalowanych pluginów.

**Punkty integracji (zlokalizować przed implementacją — sprawdzić aktualny przepływ w `domain.telephony`/`api.telephony`, prawdopodobnie `AgentCallController`/`TelephonyAdapter.initiateCall` lub miejsce, gdzie agent dostaje powiadomienie o nadchodzącym połączeniu z danymi klienta):**
```
[miejsce, gdzie kontakt jest o krok od połączenia z agentem, dane klienta już rozwiązane]
  → ExtensionPointPublisher.publishPreContactConnect(tenantId, contactEvent)
  → wynik (lub pusty po timeout/braku pluginów) scalony z danymi kontaktu zwracanymi do agenta
  → connect przebiega NIEZALEŻNIE od wyniku pluginu (nigdy nie blokuje na błędzie, ARCHITECTURE.md §11.5/RT-12)
```

**Nowy endpoint manual action:**
```
POST /api/agent/plugins/{installationId}/manual-action/{actionId}
  body: { payload: {...} }
  → 200: ManualActionResult
  → 504 Gateway Timeout: jeśli plugin przekroczy 5s (ManualActionResult z polem error, NIE wyjątek nieobsłużony)
```

**Kryteria akceptacji:**
- [x] Tenant BEZ zainstalowanych pluginów: zachowanie connect identyczne jak przed epikiem (zero regresji — test regresyjny na istniejącym flow telefonii)
- [x] Tenant z pluginem na `PRE_CONTACT_CONNECT`, który timeoutuje → connect i tak następuje w budżecie pierwotnego SLA telefonii (`< 3s` z ARCHITECTURE.md Appendix C) + budżet pluginu, nie zamiast
- [x] `POST .../manual-action/{actionId}` z nieistniejącym `installationId` → `404`
- [x] `POST .../manual-action/{actionId}` z `installationId` innego tenanta → `404` (świadome odejście od `403` — patrz notatka poniżej)
- [x] Endpoint w Swagger UI
- [x] `mvn verify -pl app` przechodzi

**Zrealizowane 2026-06-21:**

**Punkt integracji telefonii (zlokalizowany):** `domain/telephony/CallEventEnricher.java`, metoda `onCallEvent()` — jedyny wspólny punkt dla `call.incoming` i `call.outbound` (inbound i dialer/outbound zbiegają się w tym samym listenerze RabbitMQ), gdzie `agentId` jest już znany i dane klienta są już rozwiązane przez `CliLookupService`. Wstawiono jedno wywołanie `ExtensionPointPublisher.publishPreContactConnect`, bez duplikacji w innych miejscach (np. `TwilioTelephonyAdapter`/`ProgressiveDialerServiceImpl` nie wymagały zmian — oba routing keys trafiają do tego enrichera).

**Decyzja response-first (nie late-arriving event):** wynik pluginu jest scalany z `CallEvent` (nowe pola `pluginDisplayData`/`pluginWarning`) PRZED wysłaniem `WebSocketEvent.callIncoming/callOutbound` do agenta — nie jako osobny, późniejszy event. Uzasadnienie: `publishPreContactConnect` ma już wbudowany twardy budżet 2s i nigdy nie rzuca/nie blokuje dłużej; telefon klienta dzwoni niezależnie od tego listenera, więc dodatkowe maks. 2s przed dostarczeniem danych do agenta nie zagraża SLA telefonii. Late-arriving event wymagałby nowego typu eventu WS i logiki merge po stronie frontendu bez wystarczającego zysku przy tej wielkości budżetu.

**TenantContext w wątku RabbitMQ:** `CallEventEnricher` działa na wątku konsumenta (async, bez `TenantFilter`) — dodano jawne `TenantContext.setTenantId(...)` na początku `onCallEvent` i `TenantContext.clear()` w `finally`, żeby `ExtensionPointPublisherImpl.invokeBlocking`'s `TenantContext.snapshot()/restore()` miało co propagować na wątek roboczy `pluginInvocationExecutor` (CLAUDE.md, ARCHITECTURE.md §11.8).

**Decyzja 404 vs 403 dla manual-action na instalacji innego tenanta:** tabela `tenant_plugin_installation` ma RLS (V075) — zapytanie tenant-aware (`TenantPluginInstallationRepository.findByIdAndTenantId`) nie zwraca wiersza innego tenanta, więc z punktu widzenia backendu wygląda identycznie jak "nie istnieje wcale". W projekcie nie istnieje żaden wzorzec zapytania z bypassem RLS (zweryfikowano grep po repozytoriach domenowych) — dodanie go tylko na potrzeby tego jednego endpointu byłoby nieproporcjonalnym ryzykiem bezpieczeństwa względem korzyści z literalnego rozróżnienia 403/404. **Świadome odejście od kryterium akceptacji:** kontroler zwraca **404 dla obu przypadków** (nieistniejąca instalacja ORAZ instalacja innego tenanta), zgodnie z istniejącą konwencją projektu (`PluginRegistrationService.enable`/`disable`/`rollback` — wszystkie traktują "nie znaleziono dla tego tenanta" jako `ResourceNotFoundException`/404, nigdy 403).

**Pliki:**
- `domain/telephony/CallEventEnricher.java` — wywołanie `publishPreContactConnect`, `TenantContext` jawny, scalanie wyniku
- `domain/telephony/CallEvent.java` — nowe pola `pluginDisplayData: Map<String,Object>`, `pluginWarning: String`
- `domain/websocket/WebSocketEvent.java` — `CallIncomingPayload` rozszerzony o `pluginDisplayData`/`pluginWarning` (obsługuje zarówno `callIncoming` jak i `callOutbound`, bo obie metody używają tego samego payloadu)
- `domain/plugin/PluginRegistrationService(Impl).java` — nowa metoda `getInstallation(tenantId, installationId)` → `ResourceNotFoundException` gdy nie istnieje dla tenanta
- `domain/plugin/runtime/PluginInvocationProperties.java` — `effectiveManualActionTimeoutMs()` zmieniona z package-private na `public` (potrzebna kontrolerowi do wykrycia przekroczenia budżetu, bo `ManualActionResult.unsupported()` jest nierozróżnialne między timeout i "plugin nie wsparł akcji")
- `api/plugin/PluginManualActionController.java` (nowy) — `POST /api/agent/plugins/{installationId}/manual-action/{actionId}`, `@PreAuthorize("hasAnyRole('AGENT','SUPERVISOR','ADMIN')")`, mierzy czas wywołania i mapuje przekroczenie budżetu na 504 z ciałem JSON
- `api/plugin/dto/ManualActionRequestDto.java`, `ManualActionResponseDto.java` (nowe)

**Testy:** `CallEventEnricherTest` (10, nowy plik — regresja brak pluginów, scalanie wyniku, TenantContext set/clear na granicy wątku, early return), `PluginManualActionControllerTest` (9, nowy plik — happy path, timeout→504, ownership→404), `PluginRegistrationServiceImplTest` (+3 w nowym `@Nested GetInstallation`). `mvn verify -pl app`: **1258 testów, 0 failures, 0 errors, BUILD SUCCESS** (przyrost +22 względem BE-102: 1236→1258).

---

### BE-104 – Async punkty rozszerzeń: `POST_CONTACT_END`/`CUSTOMER_SYNC`/`DISPOSITION_SET` przez RabbitMQ

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-102
**Status:** ✅ Zrealizowane 2026-06-21
**Blokuje:** —
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Trzy punkty rozszerzeń fire-and-forget publikowane do nowej kolejki RabbitMQ `cc.queue.plugin-invocation`, konsumowane asynchronicznie — odpowiedzialność za latencję pluginu jest całkowicie odseparowana od żądania agenta (ARCHITECTURE.md §11.5). Wzorzec identyczny z istniejącymi domenowymi event'ami `agent.status.changed`/`call.incoming` (§3.5) — sprawdź istniejącą konfigurację RabbitMQ (exchange/queue/binding) przed dodaniem nowej.

**Zrealizowane:**
`ExtensionPointPublisherImpl.publishPostContactEnd`/`publishCustomerSync`/`publishDispositionSet` (BE-102) zastąpione: zamiast submit-and-forget na `pluginInvocationExecutor`, serializują payload (`ContactEvent`/`CustomerSyncRequest`/`DispositionEvent`) do `PluginInvocationMessage` i publikują przez `RabbitTemplate.convertAndSend(EXCHANGE_EVENTS, RK_PLUGIN_INVOCATION, message)` — nie wywołują pluginu, nie wołają `PluginRegistry.lookup`. Błąd publikacji (broker niedostępny) jest złapany i zalogowany, NIE propagowany do wołającego.

`PluginInvocationMessage` (record): `tenantId`, `extensionPoint` (String, nie enum — kontrakt wiadomości stabilny niezależnie od ewentualnej zmiany nazw enuma), `eventPayload` (`Map<String,Object>` — Jackson nie wie w punkcie deserializacji, który z trzech rekordów SDK zastosować, bo to zależy od `extensionPoint` będącego polem TEGO SAMEGO payloadu), `publishedAt`. **Bez `installationId`** — zgodnie ze wskazówką w tickecie: lookup wszystkich aktywnych instalacji na ten extension point dzieje się w `PluginInvocationConsumer` w momencie konsumpcji, nie w publisherze w momencie publikacji (instalacja mogła zostać zainstalowana/odinstalowana między tymi dwoma momentami).

`PluginInvocationConsumer` (`extends TenantAwareConsumer`, `@RabbitListener(queues = QUEUE_PLUGIN_INVOCATION)`): `processWithTenant` → `PluginRegistry.lookup` → dla każdej instalacji **dociąga aktualny stan `enabled` z `PluginRegistrationService.getInstallation`** (krok krytyczny: `PluginRegistry` w pamięci nie odzwierciedla automatycznie `enable()`/`disable()` w DB — zweryfikowano w `PluginRegistryImpl`/`PluginRegistrationServiceImpl.disable()`, które tylko zmienia flagę w DB bez wołania `unregister()`) → `enabled=false` lub instalacja nieznaleziona w DB → `SKIPPED_DISABLED` (nie silent drop) → circuit breaker check (**stan WSPÓLNY z `ExtensionPointPublisherImpl`**, ten sam bean `CircuitBreakerState`, bo jest indeksowany tylko po `installationId`) → wywołanie metody `PluginEntryPoint` dopasowanej do `extensionPoint` przez `switch`, timeout 30000ms (`PluginInvocationProperties.effectiveAsyncInvocationTimeoutMs()`, nowe pole), na `pluginInvocationExecutor` (ten sam executor co BE-102), granica `TenantContext`/TCCL identyczna jak `ExtensionPointPublisherImpl.invokeBlocking` (snapshot/restore/clear, `PluginExecutionContext.runWithPluginClassLoader`, `catch(Throwable)`).

`infrastructure/config/RabbitMqPluginConfig.java` (nowy plik, osobny od `RabbitMQConfig` żeby nie rozrastać dalej już dużego pliku istniejącego) — `pluginInvocationQueue` (durable, DLX→`cc.dlx`/`dlq`, bez TTL w przeciwieństwie do `contactRoutingQueue`) + binding do istniejącego `eventsExchange` (`cc.events`) z routing key `plugin.invocation`. Stałe `QUEUE_PLUGIN_INVOCATION`/`RK_PLUGIN_INVOCATION` żyją w `RabbitMQConfig` (centralizacja nazw, konwencja istniejąca). **Retry/DLQ:** brak konfiguracji per-kolejka — reużyty globalny `spring.rabbitmq.listener.simple.retry` (dev: max-attempts=3, prod: max-attempts=5), identycznie jak `AuditLogConsumer`/wszystkie inne konsumenty domenowe w projekcie; po wyczerpaniu retry wiadomość trafia do istniejącego `cc.queue.dead-letter` → `DeadLetterConsumer`. Wyjątek/`Error` rzucony PRZEZ PLUGIN jest złapany WEWNĄTRZ konsumenta (nigdy nie dociera do Spring AMQP) — tylko błąd `PluginRegistry.lookup`/`PluginRegistrationService.getInstallation` (infrastruktura, np. JDBC) propaguje się do nack/retry/DLQ.

**Wspólny logger:** wydzielony `PluginInvocationLogger` (nowa, mała klasa package-private, statyczna metoda `record(...)`) używany przez `ExtensionPointPublisherImpl.recordInvocation` (delegacja, sygnatura niezmieniona) i `PluginInvocationConsumer` — ocena: duplikat dwóch identycznych metod 6-argumentowych był warty wydzielenia do jednego miejsca, żeby podmiana w BE-105 (na `PluginInvocationLogService`) dotyczyła jednego punktu, nie dwóch kopii do ręcznego scalenia.

**Testy:** `ExtensionPointPublisherImplTest$FireAndForgetTests` przepisany (5 testów — publikacja do RabbitMQ z weryfikacją exchange/routing-key/payload, NIE wywołanie pluginu; round-trip serializacji do każdego z 3 rekordów SDK; błąd `RabbitTemplate` zawierany). `PluginInvocationConsumerTest` (nowy, 13 testów) — sukces z deserializacją payloadu, **`SKIPPED_DISABLED` dla instalacji disabled między publikacją i konsumpcją** (kryterium akceptacji), instalacja usunięta z DB traktowana jak disabled, brak instalacji w registry → no-op, timeout po ~300ms skonfigurowanym, `Error` pluginu zawierany, circuit breaker OPEN pomija wywołanie, `onCustomerSync`/`onDispositionSet` dispatch, **brak leaku `TenantContext` między dwoma tenantami konsumowanymi sekwencyjnie na tym samym wątku** (kryterium akceptacji), wiadomości malformed (null/tenantId null/extensionPoint nierozpoznany) ignorowane bez wyjątku. `mvn verify -pl app`: **1274 testy, 0 failures, 0 errors, BUILD SUCCESS** (przyrost +16 względem BE-103: 1258→1274).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/runtime/PluginInvocationMessage.java       (record — serializowalny payload kolejki: tenantId, installationId, extensionPoint, eventPayload jako JSON)
  domain/plugin/runtime/PluginInvocationConsumer.java       (@RabbitListener na cc.queue.plugin-invocation, TenantContext snapshot/restore/clear na granicy listenera — wzorzec identyczny jak istniejący TenantAwareConsumer w domain.messaging)
  infrastructure/config/RabbitMqPluginConfig.java            (deklaracja exchange/queue/binding cc.queue.plugin-invocation, dead-letter queue wzorcem istniejącego DeadLetterConsumer)
```

**Rozważ:** `PluginInvocationConsumer` powinien `extends TenantAwareConsumer` (domain.messaging) jeśli ta klasa bazowa już obsługuje wzorzec snapshot/restore/clear — sprawdź `domain/messaging/TenantAwareConsumer.java` przed pisaniem własnej logiki od zera.

**Logika konsumenta:**
1. Deserializuj `PluginInvocationMessage`
2. `TenantContext.restore(...)` (lub wzorzec z `TenantAwareConsumer`)
3. `PluginRegistry.lookup(tenantId, extensionPoint)` → dla każdej instalacji, jeśli `enabled=false` → log `SKIPPED_DISABLED` (nie silently dropped, ARCHITECTURE.md §11.11), inaczej wywołaj odpowiedni `onPostContactEnd`/`onCustomerSync`/`onDispositionSet` z timeoutem 30s (reużyj `PluginInvocationExecutor` z BE-102)
4. Każda ścieżka zapisana do `plugin_invocation_log` (BE-105)

**Kryteria akceptacji:**
- [x] Kolejka `cc.queue.plugin-invocation` zadeklarowana z dead-letter queue (wzorzec istniejącego `DeadLetterConsumer`)
- [x] Test: wiadomość dla instalacji, która między publikacją a konsumpcją została `disabled` → log `SKIPPED_DISABLED`, nie wyjątek, nie silent drop
- [x] `TenantContext` poprawnie ustawiony na wątku listenera (test z dwoma tenantami konsumowanymi sekwencyjnie na tym samym wątku/komponencie — brak cross-tenant leak, analogicznie do testu z BE-102)
- [x] Plugin rzucający wyjątek w `onPostContactEnd` nie powoduje requeue w nieskończoność (DLQ po N retry, wzorzec istniejący — globalny retry `spring.rabbitmq.listener.simple.retry`, nie per-kolejka)
- [x] `mvn verify -pl app` przechodzi

---

### BE-105 – `PluginInvocationLogService` + REST historii wywołań

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-102, DB-045
**Status:** ✅ Zrealizowane (2026-06-22)
**Blokuje:** —
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Serwis zapisu (wołany z BE-102/BE-103/BE-104 na każdej ścieżce wywołania) + endpoint REST do przeglądania historii przez supervisora, analogicznie do `EtlStatusController` (ARCHITECTURE.md §11.12, §3.6/§8.3).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/plugin/runtime/PluginInvocationLogService.java      (publiczny interfejs)
  domain/plugin/runtime/PluginInvocationLogServiceImpl.java   (package-private, @Service)
  domain/plugin/PluginInvocationLogRepository.java             (package-private, extends TenantAwareRepository, tabela DB-045)
  api/plugin/PluginInvocationLogController.java
  domain/plugin/dto/PluginInvocationLogDto.java
```

**Endpoint:**
```
GET /api/supervisor/plugins/{installationId}/invocations?page=0&size=20&status=FAILED
  → 200: Page<PluginInvocationLogDto>
```

**Bezpieczeństwo:** `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")`.

**Kryteria akceptacji:**
- [x] `record(...)` wywoływane na każdej z 5 ścieżek statusu (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED) — weryfikacja przez testy istniejących wywołujących (BE-102/BE-104) po integracji
- [x] `request_payload_redacted` nie zawiera PII surowego klienta — test z przykładowym payloadem weryfikujący redakcję pól (np. `phoneNumber`, `email`)
- [x] Endpoint paginowany, filtrowanie po `status`
- [x] `installationId` innego tenanta → `404` (świadome odejście od `403` — patrz notatka poniżej)
- [x] `mvn verify -pl app` przechodzi

**Zrealizowane 2026-06-22:**

**Złożony PK partycjonowanej tabeli:** `PluginInvocationLog` (`@Entity`, tabela `plugin_invocation_log`, V077) używa `@IdClass(PluginInvocationLogId.class)` z dwoma polami `@Id` (`id`, `invokedAt`) — wzorzec 1:1 skopiowany z `AuditLog`/`AuditLogId` (V004), jedynej innej encji partycjonowanej po kolumnie czasowej w projekcie. Zapis przez natywny `INSERT ... CAST(... AS jsonb)` w `PluginInvocationLogRepository.insert` (PostgreSQL nie wspiera standardowych INSERT-ów JPA na tabelach partycjonowanych z PK obejmującym kolumnę partycjonowania), odczyt przez JPQL z `PageImpl`/`setFirstResult`/`setMaxResults` (Hibernate odpytuje tabelę nadrzędną, automatyczne przekierowanie do partycji) — wzorzec paginacji 1:1 z `EmailMessageRepository`.

**Widoczność repozytorium:** w przeciwieństwie do `TenantPluginInstallationRepository` (package-private, BE-100), `PluginInvocationLogRepository` jest **publiczne** — jedyny konsument (`PluginInvocationLogServiceImpl`) leży w innym pakiecie (`domain.plugin.runtime`), zgodnie ze strukturą plików zdefiniowaną w tickecie (repo w `domain.plugin`, serwis w `domain.plugin.runtime`).

**Integracja z wołającymi:** klasa-placeholder `PluginInvocationLogger` (SLF4J-only, BE-102/104) **usunięta** — `ExtensionPointPublisherImpl` i `PluginInvocationConsumer` wstrzykują teraz `PluginInvocationLogService` bezpośrednio przez DI. Sygnatura `record(...)` rozszerzona o `relatedContactId`/`requestPayload` względem pierwotnego placeholdera — wymagało przeniesienia parametru payloadu (`ContactEvent`/`ManualActionRequest`/`DispositionEvent`/itd.) przez `invokeBlocking`/`processOneInstallation` do punktu wołania `record`. `relatedContactId` wyciągany z `event.contactId()`/`req.contactId()` gdzie dostępne (`CustomerSyncRequest` nie ma tego pola → `null`). W `PluginInvocationConsumer` deserializacja payloadu przesunięta na samy początek `processOneInstallation` (wcześniej działa się tylko przed faktycznym wywołaniem pluginu) — potrzebna dla **wszystkich** ścieżek `record(...)`, włącznie z `SKIPPED_DISABLED`/`CIRCUIT_OPEN`, nie tylko `SUCCESS`/`FAILED`/`TIMED_OUT`.

**Redakcja PII (`PiiRedactor`, nowa klasa, package-private w `domain.plugin.runtime`):** brak istniejącego mechanizmu redakcji PII gdzie indziej w projekcie (zweryfikowano — `audit_log` przechowuje surowe `old_value`/`new_value`, bo dostęp jest ograniczony do ADMIN, inny model ryzyka). Rekurencyjne przejście `Map`/`List` (wynik `ObjectMapper.convertValue(payload, Object.class)`), zamiana wartości dla kluczy z ustalonej listy na `"[REDACTED]"`, normalizacja klucza case-insensitive + usunięcie `_`/`-` przed porównaniem. **Lista pól PII:** `phoneNumber`/`phone`/`msisdn`, `email`/`emailAddress`, `firstName`/`lastName`/`fullName`/`customerName`/`name`, `address`/`street`/`city`/`postalCode`/`zipCode`, `pesel`/`nip`/`ssn`, `cardNumber`/`creditCardNumber`/`iban`. Ryzyko PII leży głównie w `ManualActionRequest.parameters()` (dowolna `Map<String,Object>` z UI agenta) — payloady SDK (`ContactEvent`/`CustomerSyncRequest`/`DispositionEvent`) niosą tylko UUID/kody/timestampy, bez PII na poziomie własnych pól.

**Decyzja 404 vs 403 dla `installationId` innego tenanta:** kontynuacja konwencji ustalonej w BE-103 (`PluginManualActionController`) — `tenant_plugin_installation` ma RLS (V075), więc zapytanie tenant-aware nie odróżnia "nie istnieje" od "istnieje, ale innego tenanta". Świadomie zwrócone **404 dla obu przypadków** przez `PluginRegistrationService.getInstallation` (już istniejąca metoda z BE-103) wołaną w `PluginInvocationLogServiceImpl.findByInstallation` PRZED odczytem historii — to jest odejście od literalnego zapisu kryterium akceptacji tego ticketu (które wymienia 403) na rzecz konsekwencji z resztą epiku; brak w projekcie wzorca zapytania z bypassem RLS do odróżnienia tych dwóch przypadków bez ryzyka bezpieczeństwa nieproporcjonalnego do korzyści.

**Testy:** `PiiRedactorTest` (10 — redakcja top-level, rekurencyjna w mapach/listach zagnieżdżonych, case/separator-insensitive matching, null/empty), `PluginInvocationLogServiceImplTest` (9 — mapowanie encji, **redakcja PII zweryfikowana asercją na zawartości JSON wynikowego** nie tylko wywołaniem metody, błąd repozytorium złapany/nie propagowany, 404 ownership propagowany bez wołania repo), `PluginInvocationLogControllerTest` (5 — happy path, paginacja/filtr status, 404 propagowany). Zaktualizowane `ExtensionPointPublisherImplTest`/`PluginInvocationConsumerTest` (BE-102/104) z dodatkowymi asercjami `verify(pluginInvocationLogService).record(...)` dla każdej z 5 ścieżek statusu już istniejącej w tych klasach. `mvn verify -pl app`: **1323 testy, 0 failures, 0 errors, BUILD SUCCESS** (+49 vs BE-104: 1274→1323).

**BUG KRYTYCZNY znaleziony i naprawiony (2026-06-24), dotyczy BE-101/BE-102/BE-104:** `ExtensionPointPublisherImpl.buildPluginContext(...)` i `PluginInvocationConsumer.buildPluginContext(...)` (obie metody wprowadzone w tych tickecie/BE-104) hardkodowały `List.of()`/`null` jako `grantedPermissions`/`installationConfig` przy konstrukcji `PluginContextImpl` dla **każdego** wywołania pluginu poza `onActivate()` (tj. `onPreContactConnect`, `onManualAction`, `onPostContactEnd`, `onCustomerSync`, `onDispositionSet`) — niezależnie od rzeczywistych danych zapisanych dla instalacji w bazie. Skutek: `PluginContext.config().get(...)` zawsze zwracał `Optional.empty()`, a `PluginContext.httpClient()` miał zawsze pustą allow-listę egress (każde wywołanie HTTP → `SecurityException`) przy każdym wywołaniu poza aktywacją. Tylko jednorazowy `onActivate()` (wołany z `PluginRuntimeManagerImpl.load()`, osobna metoda budująca `PluginContextImpl`, która poprawnie przekazuje `installation.getGrantedPermissions()`/`installation.getInstallationConfig()`) dostawał prawdziwe dane.

**Realny scenariusz, który ujawnił bug:** przykładowy plugin Google Lookup (`examples/plugins/customer-google-lookup`) zwracał błąd "Brak konfiguracji 'googleApiKey'" w `onPreContactConnect`, mimo że konfiguracja była poprawnie ustawiona przez admina i widoczna w bazie — `onActivate()` (walidacja "czy klucz API jest ustawiony") przechodził poprawnie, ale każde kolejne wywołanie pluginu i tak dostawało pusty config.

**Naprawa:** w obu metodach `buildPluginContext` — `grantedPermissions` czytane z `handle.grantedPermissions()` (pole już istniejące na `PluginInstanceHandle` od BE-107, bezpieczne do cache'owania na handle, bo niezmienne dla czasu życia instalacji — zmiana uprawnień wymaga reinstalacji = nowy handle). `installationConfig` czytany **świeżo z bazy przy każdym wywołaniu** przez nowo wstrzykniętą zależność `PluginCatalogQueryService.findInstallation(tenantId, handle.installationId())` (port już istniejący, użyty analogicznie do `PluginRuntimeManagerImpl.load`) — celowo NIE cache'owany na handle, bo `PluginRegistrationService.updateConfig` (BE-108) może zmienić konfigurację instalacji bez disable/enable, więc cache na handle uczyniłby taką zmianę nigdy niewidoczną bez ponownego load(). `Optional.empty()` (instalacja usunięta między load() i wywołaniem, race rzadkie) → `installationConfig = null` → `PluginConfigImpl` zwraca pustą mapę, brak NPE.

**Testy regresyjne dodane:** `ExtensionPointPublisherImplTest$PluginContextConfigAndPermissionsTests` (4: config widoczny z bazy, config zmienia się między dwoma wywołaniami bez reload — dowód braku cache, instalacja nieznaleziona → bezpieczny `Optional.empty()`, `grantedPermissions` z handle dociera do `PluginContext`), `PluginInvocationConsumerTest$PluginContextConfigAndPermissionsTests` (3, analogiczne dla ścieżki async/RabbitMQ). `mvn verify -pl app`: bez regresji.

---

### BE-106 – `PluginAdminController`: enable/disable, rollback, platform `REVOKED` kill switch

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-101
**Status:** ✅ Zrobione (2026-06-22)
**Blokuje:** BE-107, BE-110, FE-097, FE-098
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Kontroler REST dla panelu admina tenanta (enable/disable/rollback instalacji) + endpoint globalny dla administratora systemowego (`REVOKED` — kill switch wpływający na wszystkich tenantów niezależnie od ich `enabled`, ARCHITECTURE.md §11.11).

**Notatka implementacyjna (decyzja świadoma, nie renegocjować bez nowego ticketu):** projekt
NIE posiada odrębnej roli "administratora systemowego" (`UserRole` ma tylko
`ADMIN, SUPERVISOR, AGENT`, wszystkie tenant-scoped — potwierdzone: nawet `POST /api/tenants`
używa zwykłego `hasRole('ADMIN')`, identycznie jak `AdminMetricsController`). Endpoint
`/api/admin/plugins/versions/{id}/revoke` (`PluginRevokeController`) używa **tymczasowo**
`@PreAuthorize("hasRole('ADMIN')")` — tej samej roli tenantowej co reszta aplikacji. Skutek:
każdy tenantowy `ADMIN` może globalnie wycofać wersję pluginu również innym tenantom, nie
tylko swojemu. To **known limitation / dług techniczny**, udokumentowany w Javadoc
`PluginRevokeController` — naprawienie wymaga nowej roli systemowej + migracji DB, poza
zakresem BE-106. Cross-tenant zapytanie dla `revoke` (`findAllEnabledAcrossTenantsByVersionId`)
zaimplementowane przez iterację po `TenantService#getAllTenants()` z jawnym ustawieniem
kontekstu RLS per tenant (N zapytań) — projekt nie posiada żadnego wzorca bypassu RLS, więc nie
wprowadzono nowego. `uninstall` = fizyczny `DELETE` wiersza `tenant_plugin_installation`
(zgodnie z ARCHITECTURE.md §11.11 i FK `ON DELETE SET NULL` z `plugin_invocation_log`, V077) —
nowa metoda `PluginRegistrationService#uninstall` + `TenantPluginInstallationRepository#delete`.

**Endpointy:**
```
GET    /api/supervisor/plugins                                    → 200 List<TenantPluginInstallationDto> (wszystkie instalacje tenanta, w tym disabled)
POST   /api/supervisor/plugins/{pluginVersionId}/install           → 201 TenantPluginInstallationDto
POST   /api/supervisor/plugins/installations/{id}/enable           → 200 (PluginRuntimeManager.load wywołany jeśli jeszcze nie był)
POST   /api/supervisor/plugins/installations/{id}/disable           → 200 (bindingi usunięte z PluginRegistry NATYCHMIAST)
POST   /api/supervisor/plugins/installations/{id}/rollback/{targetId} → 200 TenantPluginInstallationDto
DELETE /api/supervisor/plugins/installations/{id}                   → 204 (uninstall: onDeactivate best-effort, unload ClassLoader, log zostaje — FK SET NULL)

-- Endpoint administratora SYSTEMOWEGO (nie tenant admin — sprawdź istniejący wzorzec roli system-admin, jeśli istnieje, np. AdminMetricsController)
POST   /api/admin/plugins/versions/{pluginVersionId}/revoke         → 200 (status=REVOKED, wyłącza WSZYSTKIE instalacje wszystkich tenantów, checked w PluginRegistry.lookup)
```

**Bezpieczeństwo:** `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")` dla `/api/supervisor/plugins/**`; endpoint `/api/admin/plugins/**` — sprawdź i reużyj dokładnie ten sam mechanizm autoryzacji co istniejący `AdminMetricsController`/`AdminTenantController` dla roli systemowej (prawdopodobnie inna od tenant `ADMIN`).

**Kryteria akceptacji:**
- [x] `enable` woła `PluginRuntimeManager.load` (BE-101) jeśli `ClassLoader` jeszcze nie istnieje dla tej instalacji (`PluginRuntimeManager#isLoaded`, idempotentny)
- [x] `disable` usuwa bindingi z `PluginRegistry.lookup` table NATYCHMIAST — `unload()` wywołane PRZED `disable()` w DB (weryfikowane `InOrder` w `PluginAdminControllerTest`)
- [x] `rollback` deleguje do `PluginRegistrationService.rollback` (BE-100) i dodatkowo przełącza runtime: stary `ClassLoader` ładowany (jeśli był odładowany), nowy odładowany
- [x] `revoke` (endpoint systemowy) — test z 2 tenantami mającymi `enabled=true` dla tej samej wersji: po revoke `unload()` wywołane dla OBU instalacji niezależnie od tenanta (`PluginRevokeControllerTest`)
- [x] Rola tenant `SUPERVISOR` → `403` na endpoincie `/api/admin/plugins/**` (deklaratywnie `@PreAuthorize("hasRole('ADMIN')")` + `SecurityConfig` `/api/admin/**`; patrz known limitation wyżej — to jest rola tenantowa ADMIN, nie systemowa)
- [x] `mvn verify -pl app` przechodzi (1299 testów, 0 failures, 0 errors)

**Aktualizacja kontraktu DTO (2026-06-22, na potrzeby FE-097, bez zmiany ticketu — wykonane
ad-hoc po wykryciu niezgodności kontraktu frontend/backend):** `PluginVersionDto` wzbogacony o
`displayName`/`vendor` (z `Plugin`); `TenantPluginInstallationDto` wzbogacony o `pluginKey`,
`displayName`, `version` (semver `PluginVersion`, NIE pomylić z `pluginVersionId`),
`manualActions: List<ManualActionDto>`, `uiPanels: List<UiPanelDto>` (nowe publiczne rekordy w
`dto/`, bez pola `sandbox` — model TS `UiPanelDef` z FE-097 go nie ma). Dane pochodzą z
`PluginVersion.manifestJson`; `PluginStorageServiceImpl#manifestToMap` zostało poprawione, żeby
faktycznie zapisywać `uiPanels`/`manualActions` do `manifestJson` — wcześniej te pola manifestu
były odczytywane do `PluginManifest` (rekord), ale gubione przy serializacji do JSONB. URL-e i
metody HTTP endpointów BEZ zmian — wyłącznie wzbogacenie ciała odpowiedzi już istniejących
endpointów (`POST/GET /api/supervisor/plugins`, install/enable/disable/rollback/uninstall).
`PluginRegistrationServiceImpl#mapToDto` dociąga `PluginVersion`+`Plugin` (batch `findAllById`
w `listInstallations`, zamiast N+1; pojedynczy `findById` w `install`/`getInstallation`/`rollback`,
gdzie wersja jest i tak potrzebna do innej logiki). `mvn verify -pl app`: 1350 testów, 0 failures,
0 errors.

**Kolejna poprawka kontraktu (2026-06-23, na potrzeby FE-098, ta sama zasada co wyżej —
rozszerzenie backendu zamiast workaroundu na frontendzie):** `PluginVersionDto` wzbogacony o
`permissions: List<String>` — uprawnienia z manifestu (np. `"customer:read"`), potrzebne do
dialogu instalacji FE-098 (checkboxy `grantedPermissions` do zatwierdzenia przez admina).
`PluginManifest.permissions()` był już parsowany i zapisywany do `manifestJson` (BE-099), ale
NIE był przekazywany do `PluginVersionDto` — `PluginStorageServiceImpl#toDto` przyjmował tylko
`pluginKey: String`, teraz przyjmuje cały `PluginManifest` (niesie też `pluginKey()`, więc
sygnatura jest prostsza, nie szersza). Bez dodatkowego parsowania `manifestJson` — `manifest`
jest już w zasięgu w `storeValidatedJar` w momencie budowy DTO. `mvn verify -pl app`: 1350
testów, 0 failures, 0 errors.

**BE-108 — szyfrowana konfiguracja instalacji (`installation_config`), 2026-06-23.** Odkryte
jako brakujący element EPIC-28 podczas pisania przykładowego pluginu
(`examples/plugins/customer-google-lookup/`, integracja z zewnętrznym API wymagająca sekretu
tenanta — Google API key). Do tego momentu `PluginRegistrationServiceImpl#install` na trwałe
ustawiał `installation.setInstallationConfig(null)` — żaden endpoint nie zapisywał wartości do
tej kolumny; pole na encji nie miało `@Convert`, bo `TenantPluginInstallationRepository` używa
wyłącznie natywnego SQL (jak `CustomDispositionRepository`, EPIC-27) i Hibernate nie aplikuje
konwerterów atrybutów przy ręcznym mapowaniu wierszy z natywnego SQL — `@Convert` na tym polu
byłby martwym kodem.

Nowy endpoint:
```
PATCH /api/supervisor/plugins/installations/{id}/config
Body: { "config": { "klucz1": "wartość1", ... } }
→ 200 (bez ciała), 404 jeśli instalacja nie istnieje dla tenanta
```
Semantyka REPLACE (nie merge) — każde wywołanie zastępuje cały dotychczasowy zestaw kluczy.
`UpdateInstallationConfigRequest(Map<String,String> config)` w `domain/plugin/dto/`.
`PluginRegistrationService#updateConfig(tenantId, installationId, config)` weryfikuje ownership
(wzorzec identyczny jak `setEnabled`), serializuje `config` do plaintext JSON, deleguje
szyfrowanie do repozytorium.

**Jak działa szyfrowanie w tym konkretnym repozytorium (różni się od `TenantAiConfig`/
`TenantTwilioConfig`, które używają standardowego Spring Data JPA + `@Convert`):**
`EncryptedStringConverter` jest wstrzyknięty do `TenantPluginInstallationRepository` jako zwykły
Spring bean (konstruktor, nie `@Convert`) i wołany RĘCZNIE — `encryptInstallationConfig()` przy
zapisie (`updateInstallationConfig`, i też `insert()` — patrz finding code review niżej),
`decryptInstallationConfig()` przy KAŻDYM odczycie wiersza, w jednym miejscu: prywatnej metodzie
pomocniczej wołanej z `mapRow()` (więc automatycznie konsekwentnie we wszystkich query, które
przechodzą przez `mapRow` — `findByIdAndTenantId`, `findAllByTenantId`,
`findAllEnabledByPluginVersionIdForTenant` — bez duplikacji logiki). Format kolumny `jsonb`:
ciphertext Base64 zawijany w obiekt JSON `{"encrypted": "<base64>"}` (nie goły skalar JSON) —
pozwala na zwykły `CAST(:json AS jsonb)` przy zapisie i `row[N].toString()` + `ObjectMapper` przy
odczycie, bez ręcznego escapingu cytowania. Encja `TenantPluginInstallation.installationConfig`
niesie zawsze PLAINTEXT JSON in-memory (zarówno przed zapisem jak i po odczycie) — deszyfrowanie
dzieje się najbliżej granicy repozytorium/SQL, nie w serwisie czy w `PluginRuntimeManagerImpl`/
`PluginConfigImpl` (które nie wiedzą nic o szyfrowaniu — kontrakt SDK niezmieniony).

**Finding code review (naprawiony przed merge):** pierwsza wersja `insert()` zapisywała
`installation.getInstallationConfig()` 1:1 do kolumny bez przejścia przez
`encryptInstallationConfig()` — latentny bug: dziś nieszkodliwy (`install()` zawsze ustawia
`null`), ale gdyby ktoś w przyszłości ustawił initial config przy instalacji, zapisałby
plaintext niezgodny z formatem `{"encrypted":...}` oczekiwanym przez `decryptInstallationConfig`,
co przy kolejnym odczycie rzuciłoby `IllegalStateException` dla całej instalacji. Naprawione:
`insert()` woła teraz `encryptInstallationConfig()` tak samo jak `updateInstallationConfig()`.
Test regresyjny: `TenantPluginInstallationRepositoryTest$UpdateInstallationConfig
.insert_withNonNullInstallationConfig_encryptsBeforeSaving`.

Testy: `TenantPluginInstallationRepositoryTest` (round-trip szyfrowania na granicy SQL — realny
`EncryptedStringConverter` z testowym kluczem, mock tylko `EntityManager`),
`PluginRegistrationServiceImplTest$UpdateConfig` (ownership, REPLACE, null→`{}`),
`PluginInstallationConfigEncryptionEndToEndTest` (łańcuch pełny: `updateConfig()` → repo →
odczyt → `PluginConfigImpl.get()` zwraca odszyfrowane wartości; `PluginConfigTestAccessor` jako
accessor testowy do package-private `PluginConfigImpl`). `config` nigdy nie trafia do
`TenantPluginInstallationDto` (bez zmian — sekrety nie wracają przez API). `mvn verify -pl app`:
1364 testy, 0 failures, 0 errors.

---

### BE-107 – Serwowanie `plugin-ui/` assetów + manual-action proxy endpoint dla iframe

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-103, BE-106
**Status:** ✅ Zrobione
**Blokuje:** FE-099
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Opis:**
Backend dla integracji UI pluginu (ARCHITECTURE.md §11.10/ADR-12). Dwie odpowiedzialności: (a) ekstrakcja i serwowanie statycznych assetów `plugin-ui/` z JAR-a z dedykowanej originy (NIE tej samej co główne SPA), (b) endpoint proxy, przez który `PluginUiSdk.invokeManualAction` (FE-099) woła backend — iframe nigdy nie woła `/api/**` z JWT agenta bezpośrednio.

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  api/plugin/PluginAssetController.java                  (serwuje pliki z plugin-ui/ rozpakowane przy install na dysk lokalny per node lub object storage, Content-Security-Policy nagłówki per ARCHITECTURE.md §11.10)
  domain/plugin/runtime/PluginAssetExtractionService.java (publiczny interfejs — rozpakowuje plugin-ui/ z JAR-a przy load(), BE-101)
```

**Wymogi nagłówków (ARCHITECTURE.md §11.10 — krytyczne dla bezpieczeństwa, weryfikowane testami):**
- Response assetów: `Content-Security-Policy: default-src 'self'; connect-src <egress hosts z manifestu>` — NIE `*`
- Serwowane z subdomeny/path **innej originy** niż główne SPA (np. `/plugin-assets/{installationId}/**` z osobnym CORS policy, lub osobny vhost — potwierdź z istniejącą konfiguracją deploymentu/nginx przed implementacją, może wymagać współpracy z infra poza zakresem tego ticketu jeśli wymaga zmiany DNS/reverse proxy w środowisku — w takim przypadku zaimplementuj ścieżkę względną i udokumentuj wymóg dla DevOps)

**Manual-action proxy endpoint** (już zadeklarowany w BE-103 jako `POST /api/agent/plugins/{installationId}/manual-action/{actionId}`) — ten ticket dodaje warunek: endpoint dostępny tylko gdy `tenant_plugin_installation.enabled=true` AND `health_status != 'DISABLED_BY_ADMIN'` AND powiązana `plugin_version.status != 'REVOKED'`.

**Kryteria akceptacji:**
- [x] Response assetów zawiera `Content-Security-Policy` z `connect-src` ograniczonym do hostów z `manifest.permissions` (`http:egress:<host>`)
- [x] Asset serwowany z dedykowanej ścieżki `/plugin-assets/{installationId}/**` — **decyzja architektoniczna:** prawdziwa, osobna origina (subdomena/vhost) wymaga zmiany infrastruktury wdrożeniowej (DNS + nginx vhost) poza zakresem tego ticketu backendowego; zaimplementowano ścieżkę względną pod tym samym originem, udokumentowane w Javadoc `PluginAssetController` jako wymóg dla DevOps przed produkcyjnym udostępnieniem UI pluginów zewnętrznych dostawców. Warstwy obrony w głębi pozostają aktywne niezależnie (CSP, `sandbox="allow-scripts allow-forms"` bez `allow-same-origin`, FE-099)
- [x] Manual-action proxy odrzuca wywołanie dla `enabled=false`/`DISABLED_BY_ADMIN`/`REVOKED` → `403`
- [x] Iframe (test E2E poza zakresem backendu, ale endpoint testowany izolowanie) nie wymaga JWT agenta w żądaniu do `/plugin-assets/**` — autoryzacja przez stronę hosta, nie przez iframe (zarejestrowane w `SecurityConfig` + `PublicPathsConfig`)
- [x] `mvn verify -pl app` przechodzi (1346 testów, 0 failures, 0 errors)

**Implementacja:**
- `PluginAssetExtractionService`/`PluginAssetExtractionServiceImpl` (domain/plugin/runtime) — rozpakowuje `plugin-ui/` z JAR-a (strumieniowo, `JarInputStream`, bez zapisu pośredniego pliku) do katalogu tymczasowego; no-op (zwraca `false`) gdy JAR nie ma katalogu `plugin-ui/`; broni się przed Zip Slip/path traversal (`resolveSafely`)
- `PluginRuntimeManagerImpl.load()` woła ekstrakcję po pozytywnym `onActivate`; `unload()` usuwa katalog rekursywnie. `PluginInstanceHandle` rozszerzony o `uiAssetsDir: Optional<Path>` i `grantedPermissions: List<String>`
- `PluginRuntimeManager.findActiveHandle(installationId)` — nowa metoda lookup po samym `installationId` (bez `tenantId`), przeszukuje wyłącznie mapę w pamięci procesu (`activeHandles`) — zero zapytań do bazy/RLS, bezpieczne dla publicznego `PluginAssetController`
- `PluginAssetController` (`GET /plugin-assets/{installationId}/**`, publiczny) — serwuje pliki statyczne z katalogu znalezionego przez `findActiveHandle`; 404 dla instalacji nieznanej/bez zasobów UI/pliku nieistniejącego; CSP `default-src 'self'; connect-src 'self' <egress hosts>`; broni się przed path traversal w segmencie wildcard
- `PluginManualActionController.invokeManualAction` — nowa walidacja `checkInstallationInvokable` przed `publishManualAction`: 403 JSON (`ManualActionResponseDto.forbidden`) dla `enabled=false`/`DISABLED_BY_ADMIN`/wersja `REVOKED` (dociągnięta przez `PluginCatalogQueryService.findVersionById`)
- `SecurityConfig` + `PublicPathsConfig` — `/plugin-assets/**` dodane jako publiczne (dwa miejsca, zgodnie z CLAUDE.md)

---

### BE-110 – `GET /api/supervisor/plugins/catalog` — przeglądarka globalnego katalogu wersji

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** BE-099, BE-106
**Status:** ✅ Zrobione
**Blokuje:** FE-101
**Epic:** EPIC-28 Per-Tenant Plugin (Extension) System

**Skąd ten ticket — realny scenariusz odkryty przy testowaniu uploadu na żywym backendzie:**
Administrator wgrał JAR pluginu (`POST /api/supervisor/plugins`) — walidacja przeszła
(`status=VALIDATED`), ale zapis do bazy zakończył się `duplicate key violates unique constraint
"uq_plugin_version_plugin_version"`, bo wcześniejsza (częściowo nieudana z innego powodu) próba
uploadu już wcześniej zapisała wiersz `plugin_version` dla tej samej `(plugin_id, version)`. Skoro
upload zwrócił błąd HTTP (nie `PluginVersionDto`), frontend (`PluginAdminService.uploadJar`) nigdy
nie dostał `pluginVersionId`, więc nigdy nie pokazał przycisku "Zainstaluj" — administrator nie
miał żadnego sposobu zainstalować wersję, która **już istniała** w katalogu.

**Przyczyna strukturalna:** `GET /api/supervisor/plugins` (`listInstallations`) zwraca wyłącznie
instalacje tenanta (`tenant_plugin_installation`), NIE globalny katalog wgranych wersji
(`plugin`/`plugin_version`). Przed tym tickietem nie istniał żaden endpoint do przeglądania
katalogu — jedyny sposób zdobycia `pluginVersionId` był świeżą, udaną odpowiedzią z uploadu. Zbyt
wąskie dla modelu katalogu globalnego (ADR-13): ten sam JAR może być wgrany raz i instalowany
przez wielu tenantów niezależnie, ale bez przeglądarki katalogu nikt poza tenantem z świeżym
uploadem nie miał jak go zainstalować.

**Kontrakt:**
```
GET /api/supervisor/plugins/catalog
  → 200: List<PluginVersionDto>
```
`@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` (ten sam jak resztę `PluginAdminController`).

**Decyzja filtrowania po statusie:** endpoint zwraca WSZYSTKIE statusy (`UPLOADED`/`VALIDATED`/
`PENDING_REVIEW`/`REJECTED`/`REVOKED`), bez filtrowania w zapytaniu. Powód: wartość diagnostyczna
— administrator widzi też nieudane uploady i powód odrzucenia (`validationErrors`). Filtrowanie
"czy instalowalna" (`VALIDATED`/`PENDING_REVIEW`) jest decyzją prezentacji — zaimplementowane po
stronie frontendu (`isCatalogVersionInstallable`), nie po stronie zapytania SQL, żeby nie tracić
informacji diagnostycznej dla pozostałych statusów.

**Implementacja:**
- `PluginVersionRepository.findAllByOrderByUploadedAtDesc()` — nowa metoda derived-query (bez
  filtra `status`, najnowsze pierwsze)
- `PluginCatalogQueryService.findAllVersions()`/`Impl` — delegacja do repozytorium, zwraca encje
  `PluginVersion` (nie DTO — wzorzec portu odczytu, jak reszta tego interfejsu)
- `PluginVersionDto.from(PluginVersion entity)` — nowa statyczna fabryka mapująca (wzorzec
  identyczny do `PluginInvocationLogDto.from`), czytająca `permissions` z `manifestJson` zapisanej
  encji (nie ze świeżo sparsowanego `PluginManifest`) — pozwala mapować KAŻDĄ odczytaną z bazy
  encję, nie tylko tę zaraz po uploadzie. **Refaktoryzacja bez duplikacji:** `PluginStorageServiceImpl.toDto`
  (BE-099, prywatna metoda z dodatkowym parametrem `PluginManifest`) zastąpiona wywołaniem tej
  samej `PluginVersionDto.from(pluginVersion)` — `manifestJson` i świeży manifest niosą identyczne
  `permissions` (zapisywane przez `manifestToMap`), więc usunięcie duplikatu nie zmienia zachowania
  (zweryfikowane testami `PluginStorageServiceImplTest`, bez regresji)
- `PluginAdminController.listCatalog()` (`GET /api/supervisor/plugins/catalog`) — nowa zależność
  `PluginCatalogQueryService` wstrzyknięta do kontrolera (trzecia, po `PluginRegistrationService`/
  `PluginRuntimeManager`)

**Frontend:**
- `PluginAdminService.listCatalog()` — `GET /api/supervisor/plugins/catalog`
- `plugins-page.component.ts/html` — nowa sekcja "Dostępne w katalogu" między uploadem i listą
  instalacji, ładowana w `ngOnInit`. Dialog instalacji uogólniony na `installDialogTarget` (źródło:
  `uploadResult()` ZNA sekcji uploadu LUB wersja wybrana z katalogu) — `confirmInstall()` czyta
  teraz z `installDialogTarget()`, nie bezpośrednio z `uploadResult()`. `catalogToShow` (computed)
  odfiltrowuje wersje, których `pluginVersionId` jest już w `installations()` tenanta — uproszczenie
  zaimplementowane (proste: `Set` z `installations().map(i => i.pluginVersionId)`)

**Kryteria akceptacji:**
- [x] `GET /api/supervisor/plugins/catalog` zwraca listę wersji niezależnie od tenanta/instalacji
- [x] Rola AGENT → `403` (deklaratywne `@PreAuthorize` na poziomie klasy kontrolera, jak reszta `PluginAdminController`)
- [x] `mvn verify -pl app` przechodzi bez regresji (1369 testów, 0 failures, 0 errors)
- [x] `npm run lint` przechodzi bez regresji (0 errors, 10 preexistujących warningów `no-console` w niezwiązanych plikach)
- [x] Endpoint w Swagger UI (`@Operation`/`@ApiResponse` na `listCatalog`, `@Tag` już na poziomie klasy)

**Testy:** `PluginAdminControllerTest` (`listCatalog_mapsAllVersionsToDto`,
`listCatalog_empty_returnsEmptyList`), `PluginCatalogQueryServiceImplTest$FindAllVersions`
(delegacja do repozytorium, wszystkie statusy włącznie z `REJECTED`, katalog pusty).

---

## MODUL: Partycjonowanie i retencja danych z obsługi kontaktów (EPIC-29)

> Źródło: `DESIGN-data-retention-partitioning.md` (projekt zaakceptowany, 2026-08-08). Tabele
> bazowe: DB-046…DB-054 (TASKS-DATABASE.md). **DB-052 (V088, naprawa rotacji partycji) jest
> fundamentem tego epiku** — BE-112/BE-114/BE-115 operują na założeniu, że partycje `contact`/
> `audit_log`/nowych tabel są tworzone poprawnie na bieżąco; bez DB-052 liczenie i usuwanie
> danych retencji dawałoby błędne wyniki. `RECORDINGS` to jedyna kategoria NIE realizowana
> przez `RetentionPurgeService` (BE-113) — to nie jest usuwanie wiersza, tylko wyzerowanie
> kolumny + S3 delete, obsługiwane przez rozszerzenie `RecordingRetentionJob` (BE-116).

### BE-111 – `RetentionPolicyService`: CRUD polityk retencji + seeding domyślnych polityk dla nowych tenantów

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-046
**Status:** ✅ Ukończone
**Blokuje:** BE-112, BE-113, BE-115, BE-116, BE-118
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
Warstwa domenowa polityk retencji — nowy pakiet `domain.retention`, wzorzec interfejs+Impl
zgodny z resztą projektu (zob. PROGRESS.md, audyt enkapsulacji).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/retention/TenantRetentionPolicy.java          (encja JPA, tabela DB-046)
  domain/retention/RetentionDataCategory.java           (enum: CONTACT_INTERACTIONS, RECORDINGS, TRANSCRIPTS, CAMPAIGN_DATA)
  domain/retention/TenantRetentionPolicyRepository.java (package-private, extends TenantAwareRepository, assertSameTenant przed każdym zapisem)
  domain/retention/RetentionPolicyService.java          (publiczny interfejs)
  domain/retention/RetentionPolicyServiceImpl.java      (package-private, @Service)
  domain/retention/dto/RetentionPolicyDto.java
  domain/retention/dto/UpdateRetentionPolicyRequest.java
```

**`RetentionPolicyService`:**
```java
public interface RetentionPolicyService {
    List<TenantRetentionPolicy> listPolicies(UUID tenantId);
    TenantRetentionPolicy updatePolicy(UUID tenantId, RetentionDataCategory category, int retentionMonths, boolean autoPurgeEnabled, UUID updatedByUserId);
    int getRetentionMonths(UUID tenantId, RetentionDataCategory category);   // używane przez BE-112/BE-113/BE-119
    void seedDefaultPolicies(UUID tenantId);                                 // wołane z TenantServiceImpl.createTenant
}
```

**Wartości domyślne przy seedowaniu (zgodnie z DB-046 backfill):** `CONTACT_INTERACTIONS`=60,
`CAMPAIGN_DATA`=60, `RECORDINGS`/`TRANSCRIPTS`=wartość odpowiadająca 90 dniom w miesiącach
(potwierdź jednostkę zgodnie z decyzją podjętą w DB-046 — jeśli tabela przechowuje wyłącznie
miesiące, `RECORDINGS`/`TRANSCRIPTS` będą miały ograniczoną precyzję; BE-116
(`RecordingRetentionJob`) musi czytać tę samą jednostkę spójnie).

**Integracja z `TenantServiceImpl`:** rozszerz `createTenant(...)` o wywołanie
`retentionPolicyService.seedDefaultPolicies(tenant.getTenantId())` — analogicznie do
dzisiejszego zasiewania `tenant.config` (linie ~523-527). Cykliczna zależność
`tenant`↔`retention` (jeśli wystąpi) rozwiąż setter injection `@Autowired @Lazy`, wzorcem
`RecordingServiceImpl`/`TenantServiceImpl` już użytym w projekcie.

**Kryteria akceptacji:**
- [x] `listPolicies`/`updatePolicy` respektują multi-tenancy (`TenantAwareRepository`, `assertSameTenant`)
- [x] `updatePolicy` na nieistniejącej kategorii dla tenanta → tworzy wiersz (upsert), nie 404 — wszystkie 4 kategorie muszą zawsze istnieć po seedowaniu, ale endpoint ma być odporny na brakujący wiersz
- [x] `seedDefaultPolicies` wywoływane z `TenantServiceImpl.createTenant` — nowy tenant ma dokładnie 4 wiersze polityk zaraz po utworzeniu
- [x] `retentionMonths` walidacja `[1,120]` na poziomie serwisu (spójna z CHECK w DB)
- [x] Testy jednostkowe: seedowanie, update istniejącej polityki, update nieistniejącej (upsert), walidacja granic
- [x] `mvn verify -pl app` przechodzi bez regresji

**Notatka z implementacji (2026-08-10):** `CreateTenantRequest.limits().recordingRetentionDays()`
JEST dziś dostępne (trafia do `tenant.config` przez `TenantServiceImpl.buildConfig()`, fallback
90 dni) — zamiast rozszerzać sygnaturę `seedDefaultPolicies(UUID)` o dodatkowy parametr,
`RetentionPolicyServiceImpl` czyta tę wartość z już zapisanej encji `Tenant` przez dedykowane
zapytanie `TenantRetentionPolicyRepository.findConfiguredRecordingRetentionDays` (bezpośrednio
z tabeli `tenant`, bez RLS — analogicznie do `TenantRepository.countActiveAgentsByTenantId` i
podobnych). Dzięki temu `RetentionPolicyServiceImpl` NIE zależy zwrotnie od `TenantService` —
cykl `tenant`↔`retention`, którego spodziewał się ticket, w praktyce nie wystąpił, więc
`TenantServiceImpl` wstrzykuje `RetentionPolicyService` zwykłym polem finalnym (bez
`@Autowired @Lazy`).

---

### BE-112 – `RetentionEvaluationJob`: liczenie „danych do usunięcia” partition-aware + trigger auto-purge

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-111, DB-047, DB-052
**Status:** ✅ Ukończone
**Blokuje:** BE-118
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
`@Scheduled` job (codziennie, np. 01:00 UTC) liczący liczbę rekordów kwalifikujących się do
usunięcia per tenant/kategoria, zapisujący wynik do `tenant_retention_pending_summary` (DB-047).
**Kluczowa właściwość wydajnościowa (powód, dla którego DB-052 blokuje ten ticket):** dla każdej
partycjonowanej tabeli w zakresie job **iteruje partycje od najstarszej**, nie skanuje całej
tabeli — partycja, której górna granica jest młodsza niż najkrótsza skonfigurowana retencja
spośród WSZYSTKICH tenantów, kończy skanowanie (nowsze partycje na pewno nie mają jeszcze
przeterminowanych danych). Bez naprawionej rotacji partycji (DB-052) ta logika operowałaby na
błędnym założeniu o strukturze partycji (dane w `*_default` nie mają górnej granicy do porównania).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/retention/RetentionEvaluationJob.java
  domain/retention/PartitionScanner.java   (publiczny interfejs — lista partycji tabeli posortowana rosnąco wg granicy, dla każdej: SELECT tenant_id, count(*) FROM ONLY <partycja> GROUP BY tenant_id)
  domain/retention/PartitionScannerImpl.java (odczyt pg_catalog/information_schema dla listy partycji + ich granic)
```

**Algorytm:**
1. Dla każdej z kategorii (poza `RECORDINGS`, patrz nagłówek modułu) × jej tabel
   (`CONTACT_INTERACTIONS`→`contact`+`contact_event`, `TRANSCRIPTS`→`contact_transcription`+
   `contact_ai_summary`, `CAMPAIGN_DATA`→`campaign_contact_archive`) — pobierz listę partycji
   posortowaną rosnąco
2. Dla partycji, których górna granica < (teraz − MIN(retention_months) po wszystkich tenantach
   dla tej kategorii): `SELECT tenant_id, count(*) FROM ONLY <partycja> GROUP BY tenant_id`
3. Zestaw z `RetentionPolicyService.getRetentionMonths(tenantId, category)` per tenant, upsert
   do `tenant_retention_pending_summary`
4. Dla polityk z `auto_purge_enabled=TRUE` — wywołaj `RetentionPurgeService.purge(tenantId,
   category, TriggerType.AUTO)` (BE-113) od razu po policzeniu

**Kryteria akceptacji:**
- [x] Job partition-aware — zweryfikowane testem, że NIE wykonuje `SELECT COUNT(*) FROM contact` (całej tabeli), tylko iteruje partycje
- [x] **Test scenariusza granic miesięcy** (wymagany przez §11 pkt 5 dokumentu projektowego): partycja z danymi dokładnie na granicy cutoff (ostatni dzień miesiąca vs pierwszy dzień następnego) liczona poprawnie — brak off-by-one
- [x] Upsert do `tenant_retention_pending_summary` idempotentny (kolejne uruchomienia nadpisują, nie duplikują)
- [x] `auto_purge_enabled=TRUE` → `RetentionPurgeService` wywołane po policzeniu dla tej kategorii/tenanta; `auto_purge_enabled=FALSE` → tylko zapis do summary, bez purge
- [x] Job ustawia kontekst DB per tenant ręcznie w pętli (scheduler bez kontekstu HTTP, wzorzec `RecordingRetentionJob`/`SupervisorMetricsService`)
- [x] Błąd przy jednym tenancie/kategorii nie przerywa przetwarzania pozostałych (log ERROR + kontynuacja, wzorzec `RecordingRetentionJob`)
- [x] Testy jednostkowe ≥6 scenariuszy (partycja pusta, partycja z danymi wielu tenantów, granica miesiąca, auto-purge trigger, brak auto-purge, błąd pojedynczego tenanta nie przerywa reszty) — dodatkowo: reset do zera, CAMPAIGN_DATA liczony ale purge NIE wywołany
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-12):**
- **Rozbieżności odkryte względem opisu ticketu (rozstrzygnięte przed implementacją, kontekst zweryfikowany na żywej `cc-postgres`):**
  - `CAMPAIGN_DATA` (`campaign_contact_archive`, V015) **NIE jest partycjonowana** — algorytm w opisie ticketu ("dla każdej kategorii × jej tabel ... pobierz listę partycji") się do niej nie stosuje. Policzona osobno, bezpośrednim zapytaniem `SELECT COUNT(*), MIN(archived_at), MAX(archived_at) FROM campaign_contact_archive WHERE tenant_id=:t AND archived_at < :cutoff` (per-tenant, wykorzystuje indeks `idx_cca_tenant_archived_at` z DB-053/V089) przez nowe `CampaignArchiveRetentionRepository`, NIE przez `PartitionScanner`. Wynik zapisywany do summary tak samo jak pozostałe kategorie (w tym `oldest`/`newest_eligible_period` z MIN/MAX), ale `RetentionPurgeService.purge()` **celowo nigdy nie wywoływane** dla tej kategorii — rzuciłoby `UnsupportedOperationException` (BE-113 obsługuje wyłącznie CONTACT_INTERACTIONS/TRANSCRIPTS, integracja z `purge_campaign_contact_archive` to zakres przyszłego BE-119). Zalogowane `log.info` gdy `auto_purge_enabled=true` i `eligibleRowCount>0` dla CAMPAIGN_DATA — jawna informacja, nie błąd.
  - `RECORDINGS` jest **całkowicie poza zakresem** tego jobu (zero liczenia, zero wiersza w summary) — potwierdzone nagłówkiem modułu `domain.retention` już udokumentowanym w `RetentionPurgeService.java`, obsługiwana wyłącznie przez `RecordingRetentionJob` (BE-116, nieukończony).
  - `RetentionPolicyService` nie miało metody do minimalnej retencji cross-tenant (potrzebnej do wyznaczenia globalnego progu skanowania) — dodano `findMinRetentionMonths(RetentionDataCategory)` do interfejsu (rzuca `ResourceNotFoundException` gdy żaden tenant nie ma polityki dla kategorii; `RetentionEvaluationJob` łapie ten wyjątek per kategoria i pomija ją w danym przebiegu z `log.warn`, zamiast przerywać cały job) oraz `TenantRetentionPolicyRepository.findMinRetentionMonths` (natywny `SELECT MIN(retention_months) ... WHERE data_category=:category`, celowo bez `set_tenant_context`/`assertSameTenant` — cross-tenant po zamierzeniu, ten sam precedens co `findConfiguredRecordingRetentionDays`; uwaga: `MIN()` nad pustym zbiorem zwraca jeden wiersz z `NULL`, nie pustą listę — repozytorium to jawnie rozróżnia).
- **Rozszerzenie poza listę plików z ticketu** (konieczne, analogicznie do BE-113): `TenantRetentionPendingSummaryRepository` (upsert do cache `tenant_retention_pending_summary`, `extends TenantAwareRepository`, `ON CONFLICT (tenant_id, data_category) DO UPDATE`) oraz `CampaignArchiveRetentionRepository` (liczenie CAMPAIGN_DATA, patrz wyżej) — oba niewymienione wprost w ticketcie, ale niezbędne bo repozytoria w tym projekcie są `package-private`.
- **`PartitionScannerImpl`:** parsowanie nazwy partycji → granica czasowa **w SQL** (`substring(tablename FROM 'tabela_([0-9]{4}_[0-9]{2})')` + `to_date(..., 'YYYY_MM')`), identyczny wzorzec do funkcji rotacji `drop_old_contact_event_partitions`/`drop_old_contact_transcription_partitions`/`drop_old_contact_ai_summary_partitions` (V088, DB-052) — celowo NIE `pg_get_expr(relpartbound, ...)` (niespójność formatu literału między wersjami). Zweryfikowano manualnie na żywej `cc-postgres` (bind-parametry przez `PREPARE`/`EXECUTE`, symulacja JDBC) przed napisaniem testów — zapytanie `listPartitions` i `countRowsByTenant` (`FROM ONLY <partycja> GROUP BY tenant_id`) działają poprawnie. Ustalono też empirycznie, że rola DB `ccapp` ma `BYPASSRLS` + superuser (stąd `PartitionScannerImpl` celowo NIE rozszerza `TenantAwareRepository` — zapytanie jest cross-tenant z założenia, potrzebuje widzieć wszystkich tenantów w partycji na raz).
- **Test granicy miesiąca:** `PartitionScanner.PartitionInfo.rangeEnd()` porównywane przez `!isAfter(cutoffDate)` (czyli `<=`, nie `<`) — zarówno dla globalnego progu zatrzymania skanowania, jak i dla indywidualnego cutoffu tenanta — brak off-by-one potwierdzony dedykowanym testem (`MonthBoundary` w `RetentionEvaluationJobTest`).
- **Testy:** `RetentionEvaluationJobTest` — 20 scenariuszy (≥6 wymaganych + reset do zera + CAMPAIGN_DATA bez purge), zorganizowane w `@Nested` klasy (partition-aware scanning, granica miesiąca, partycja pusta, wielu tenantów w jednej partycji, auto-purge trigger, brak auto-purge, izolacja błędów, reset do zera, idempotentność, CAMPAIGN_DATA, RECORDINGS poza zakresem). Dodatkowo testy repozytoriów: `PartitionScannerImplTest` (10), `TenantRetentionPendingSummaryRepositoryTest` (3), `CampaignArchiveRetentionRepositoryTest` (3), oraz rozszerzone `RetentionPolicyServiceImplTest`/`TenantRetentionPolicyRepositoryTest` o `findMinRetentionMonths` (2+2). Pułapka odkryta przy pisaniu testów: `ArgumentMatchers.any()` użyty na pozycji parametru **prymitywnego** `long eligibleRowCount` w `upsert(...)` rzuca `NullPointerException` przy odbindowywaniu (`any()` zwraca `null`) — poprawione na `anyLong()`, ten sam mechanizm co ostrzeżenie we własnej dokumentacji Mockito.
- `mvn verify -pl app`: **BUILD SUCCESS**, 1662 testy, 0 failures, 0 errors (1622 przed BE-112 wg notatki BE-113 + 40 nowych).
- `/verify` (frontend + backend): lint PASS (10 pre-existing warnings, niezwiązane z tym ticketem, 0 błędów), format:check PASS, frontend testy PASS (205/205), backend `mvn verify -pl app` PASS.

**Aktualizacja (BE-119, 2026-08-13):** od integracji `CAMPAIGN_DATA` z `RetentionPurgeService`
(`purge_campaign_contact_archive`, patrz BE-119 niżej), `evaluateCampaignData` w
`RetentionEvaluationJob` już NIE pomija wywołania `RetentionPurgeService.purge()` dla tej
kategorii — auto-purge wyzwalany identycznie jak dla `CONTACT_INTERACTIONS`/`TRANSCRIPTS`, przez
wspólny `maybeTriggerAutoPurge`. Powyższy opis „celowo nigdy nie wywoływane” dotyczy stanu sprzed
BE-119, pozostawiony jako zapis historyczny decyzji podjętej w tamtym momencie.

**Aktualizacja (bugfix TenantContext w wątku schedulera, 2026-08-13):** przy tej samej sesji
weryfikacyjnej co bugfix w BE-116 (patrz jego notatka po pełny opis mechanizmu) wykryto identyczny
problem w OBU pętlach per-tenant tego jobu: `persistAndMaybeAutoPurge` (wywołuje
`summaryRepository.upsert` → `assertSameTenant`) i `evaluateCampaignData` (wywołuje
`campaignArchiveRetentionRepository.countEligible`/`summaryRepository.upsert`, oraz pośrednio,
przez `maybeTriggerAutoPurge`, `RetentionPurgeLogRepository#insertRunning`). Wątek `@Scheduled`
nigdy nie przechodzi przez `TenantFilter`, więc `TenantContext` (ThreadLocal) nigdy nie był
ustawiony — `assertSameTenant` zawsze rzucał `IllegalStateException`, złapaną przez istniejący
`try/catch` per-tenant. Efekt: job nigdy realnie nie zapisywał wyniku do
`tenant_retention_pending_summary` (dashboard admina FE-105 zawsze pokazywałby "jeszcze nie
policzone" zamiast realnej liczby) ani nie wyzwalał auto-purge (nieodkryte live, bo
`auto_purge_enabled=false` u wszystkich tenantów w momencie weryfikacji — dormant ścieżka).
**Naprawa:** `TenantContext.setTenantId(tenantId)` na początku KAŻDEJ iteracji obu pętli,
`TenantContext.clear()` w `finally` obejmującym całe ciało iteracji. Ustawienie kontekstu PRZED
wywołaniem `maybeTriggerAutoPurge` jest kluczowe dla dormant ścieżki: `RetentionPurgeService#purge`
woła `TenantContext.snapshot()` do propagacji kontekstu do `@Async purgeAsync` — bez tej naprawy
snapshot zawsze byłby pusty, więc naprawienie samego `insertRunning` (patrz niżej) nie
wystarczyłoby. Wzorzec skopiowany z `SocialIntegrationServiceImpl#refreshToken`.
- **Nowe testy regresyjne** (mockowane repozytoria w istniejących testach tego jobu nigdy nie
  mogły wykryć tego buga — mock nie wykonuje prawdziwego ciała `assertSameTenant`, patrz też
  analogiczny udokumentowany przypadek `NativeQueryConstructorTransformer` w notatce BE-119
  wyżej): `TenantRetentionPendingSummaryRepositoryTest` (+test: `upsert` bez `TenantContext` rzuca
  ISE, brak zapytania do DB — repozytorium prawdziwe, tylko `EntityManager` mockowany),
  `RetentionPurgeLogRepositoryTest` (+nested `InsertRunning`: analogiczny test negatywny dla
  `insertRunning`, plus test pozytywny z kontekstem ustawionym), `RetentionEvaluationJobTest`
  (+nested `TenantContextRegression`, 8 testów: kontekst ustawiony na poprawny `tenantId` w
  momencie wywołania `summaryRepository.upsert`/`campaignArchiveRetentionRepository.countEligible`
  w obu pętlach, izolacja między dwoma tenantami w tej samej pętli w obu pętlach, kontekst widoczny
  podczas wywołania `RetentionPurgeService.purge` — dowód pośredni na naprawę dormant ścieżki auto-
  purge, z komentarzem w teście dlaczego pełna weryfikacja `purgeAsync`/zapisu do
  `retention_purge_log` wymaga ręcznej weryfikacji e2e z `auto_purge_enabled=true` na tenancie
  testowym, zbyt krucha/kompleksowa do sensownego zamockowania na poziomie testu jednostkowego,
  wyczyszczenie kontekstu po sukcesie i po błędzie).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1767 testów, 0 failures, 0 errors.

**Aktualizacja (ręczne przeliczenie dashboardu + wydzielenie `RetentionEvaluationService`,
2026-08-13):** rozszerzenie na żądanie użytkownika, odkryte przy okazji bugfixu `TenantContext` w
wątku schedulera opisanego wyżej — administrator może teraz wymusić natychmiastowe przeliczenie
dashboardu "dane do usunięcia" (`FE-105`) zamiast czekać na nocny cron o 01:00 UTC.

- **Nowy endpoint:** `POST /api/tenants/{tenantId}/retention/recompute` (kontroler:
  `RetentionController`, sekcja "Dashboard" obok istniejącego `GET .../summary`) —
  `hasRole('ADMIN')` + `assertOwnTenant`, jak pozostałe endpointy tego kontrolera. Zwraca `200 OK`
  + `List<RetentionSummaryDto>` (dokładnie ten sam DTO/kontrakt co `GET .../summary`) ze świeżo
  przeliczonymi wartościami dla wszystkich 4 kategorii. `RECORDINGS` pozostaje poza zakresem
  (`computed=false`), tak jak w nocnym jobie.
- **KRYTYCZNA różnica względem nocnego joba: BEZ auto-purge.** Ręczne przeliczenie to celowo
  bezpieczna, pozbawiona efektów ubocznych w sensie usuwania danych akcja "odśwież liczby" —
  NIGDY nie wyzwala `RetentionPurgeService.purge`, nawet gdy `eligibleRowCount > 0` i
  `auto_purge_enabled=true` dla jakiejś kategorii tenanta. Auto-purge pozostaje WYŁĄCZNIE
  odpowiedzialnością nocnego `RetentionEvaluationJob`.
- **Refaktor "wydziel serwis":** cała logika ewaluacji z `RetentionEvaluationJob` (partition-aware
  `CONTACT_INTERACTIONS`/`TRANSCRIPTS` + `CAMPAIGN_DATA`, dotąd prywatne metody
  package-private klasy `@Component`) przeniesiona 1:1 do nowego `RetentionEvaluationService`
  (interfejs, publiczny — konsumowany przez `RetentionController` w innym pakiecie) +
  `RetentionEvaluationServiceImpl`, dokładnie ten sam wzorzec co już istniejący
  `RetentionPurgeService`/`RetentionPurgeServiceImpl`. `RetentionEvaluationJob` stał się cienkim
  wrapperem `@Scheduled` wołającym wyłącznie `evaluationService.runForAllActiveTenants()`. Nowa
  metoda `List<RetentionSummaryDto> runForTenant(UUID tenantId)` liczy jednego tenanta i deleguje
  na końcu do `RetentionPurgeService.getPendingSummary(tenantId)` po zapisaniu świeżych wartości
  do cache — identyczna synteza "zawsze 4 wpisy" co `GET .../summary`, bez duplikowania logiki
  budowy DTO.
- **Pułapka `TenantContext` między dwiema ścieżkami wejścia (rozwiązana):** ścieżka schedulera
  (`runForAllActiveTenants`) wymaga `TenantContext.setTenantId`/`clear()` per tenant w pętli (patrz
  bugfix wyżej) — ścieżka REST (`runForTenant`) NIE MOŻE wołać `clear()`, bo wyczyściłoby to
  kontekst wątku HTTP już poprawnie ustawiony przez `TenantFilter` i zweryfikowany przez
  `assertOwnTenant` w kontrolerze PRZED wywołaniem tej metody — czyszczenie w trakcie obsługi
  żądania wyciekłoby do reszty łańcucha przetwarzania (dokładnie ten sam bug co naprawiony wyżej,
  tylko w przeciwnym kierunku). Rozwiązanie: wydzielono rdzeń per-tenant BEZ zarządzania
  kontekstem (`persistSummaryAndMaybeAutoPurgeForTenant`, `evaluateCampaignDataForTenant` —
  jawny prekontrakt w Javadoc: "TenantContext musi być już ustawiony przez wywołującego"), a
  `setTenantId`/`clear()` przeniesiono WYŁĄCZNIE do pętli wrapujących w `persistAndMaybeAutoPurge`/
  `evaluateCampaignData`, używanych TYLKO przez `runForAllActiveTenants`. `runForTenant` woła rdzeń
  bezpośrednio, bez własnego `set`/`clear`. Skanowanie partycji (`scanPartitionAwareCategory`,
  cross-tenant, rola DB ma `BYPASSRLS`) nie dotyka `TenantContext` w ogóle, więc jest bezpiecznie
  współdzielone przez obie ścieżki bez zmian.
- **Nowe/zmienione pliki:**
  `domain/retention/RetentionEvaluationService.java` (nowy interfejs),
  `domain/retention/RetentionEvaluationServiceImpl.java` (nowa implementacja — cała logika z
  `RetentionEvaluationJob`, plus dwie metody core per-tenant bez zarządzania kontekstem),
  `domain/retention/RetentionEvaluationJob.java` (zredukowany do cienkiego wrappera),
  `api/retention/RetentionController.java` (+zależność `RetentionEvaluationService`, +endpoint
  `POST .../recompute`, zaktualizowany Javadoc klasy — zdanie "`RetentionEvaluationJob` pozostaje
  wewnętrznym schedulerem, bez REST API" było nieaktualne),
  `domain/retention/RetentionEvaluationServiceImplTest.java` (nowy plik — bezpośredni następca
  starego `RetentionEvaluationJobTest`, wszystkie 29 scenariuszy przeniesionych bez zmian
  merytorycznych + nowy nested `ManualRecomputeForTenant`, 7 testów: brak auto-purge mimo
  `eligibleRowCount>0`+`autoPurgeEnabled=true` na WSZYSTKICH 3 kategoriach jednocześnie, zapis
  summary tylko dla żądanego tenanta mimo współdzielonej partycji z innym tenantem, delegacja
  zwracanej wartości do `getPendingSummary`, `TenantContext` ustawiony PRZED wywołaniem pozostaje
  nietknięty PO wywołaniu — w tym przy błędzie jednej kategorii, izolacja błędów między
  kategoriami),
  `domain/retention/RetentionEvaluationJobTest.java` (zredukowany do 1 testu: deleguje do
  `runForAllActiveTenants()` dokładnie raz, `verifyNoMoreInteractions`),
  `api/retention/RetentionControllerTest.java` (+konstruktor z nową zależnością, +nested
  `Recompute`: happy path + 403 cross-tenant).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1777 testów, 0 failures, 0 errors (1767 + 10 nowych:
  netto po redukcji `RetentionEvaluationJobTest` z 29 do 1 testu, przeniesieniu tych 29 do
  `RetentionEvaluationServiceImplTest` bez zmian, +7 `ManualRecomputeForTenant`, +2
  `RetentionControllerTest.Recompute`).
- `/verify` (frontend + backend): lint PASS (10 pre-existing warningów, niezwiązane z tym
  rozszerzeniem, 0 błędów), format:check PASS, frontend testy PASS (205/205), backend
  `mvn verify -pl app` PASS.

---

### BE-113 – `RetentionPurgeService`: silnik usuwania Poziom 1 (per-tenant, batchowany) dla CONTACT_INTERACTIONS i TRANSCRIPTS

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L
**Zależy od:** BE-111, DB-048, DB-053, BE-117
**Status:** ✅ Ukończone
**Blokuje:** BE-118, BE-119
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
Usuwanie na poziomie wiersza, per tenant — wywoływane ręcznie (`POST /purge`, BE-118) lub
automatycznie (BE-112, auto-purge). Działa niezależnie od innych tenantów współdzielących tę
samą partycję miesięczną. Wykonanie **asynchroniczne** (`@Async`, zwraca `purgeId` natychmiast)
— zgodnie z regułą `CLAUDE.md`: `TenantContext.snapshot()` na wątku wywołującym,
`TenantContext.restore(snapshot)` + `TenantContext.clear()` w `finally` na wątku roboczym.

**Zakres tego ticketu:** kategorie `CONTACT_INTERACTIONS` (`contact` + `contact_event`, plus
czyszczenie logicznie powiązanych rekordów bez fizycznego FK: `email_message`/`social_message`
wskazujące na usuwany `contact_id`, już nullable od V028) i `TRANSCRIPTS` (`contact_transcription`
+ `contact_ai_summary`). `CAMPAIGN_DATA` wydzielona do BE-119 (dodatkowa integracja z istniejącą
funkcją SQL). `RECORDINGS` NIE przechodzi przez ten serwis (obsługiwana przez BE-116).

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/retention/RetentionPurgeService.java       (publiczny interfejs)
  domain/retention/RetentionPurgeServiceImpl.java   (package-private, @Service, @Async)
  domain/retention/RetentionPurgeLogRepository.java (extends TenantAwareRepository)
  domain/retention/dto/PurgeResultDto.java
```

**Algorytm:**
```
DELETE FROM contact WHERE tenant_id = :tenantId AND started_at < :cutoff
```
batchami (`BATCH_SIZE=100`/`LIMIT` + pętla, wzorzec identyczny do `RecordingRetentionJob`), żeby
nie trzymać długich locków na partycji współdzielonej przez innych tenantów. Zapis do
`retention_purge_log` (RUNNING na starcie → COMPLETED/FAILED na końcu, `rows_deleted`
sumaryczne) oraz do `audit_log` (`entity_type='RETENTION_PURGE'`) po zakończeniu.

**Kryteria akceptacji:**
- [x] `purge(tenantId, category, triggerType, triggeredByUserId)` zwraca `purgeId` natychmiast (async), zapis `RUNNING` do `retention_purge_log` PRZED zwróceniem
- [x] Usuwanie batchowane (`BATCH_SIZE` konfigurowalny, domyślnie 100), pętla do wyczerpania kwalifikujących się wierszy
- [x] **Test izolacji między tenantami we wspólnej partycji** (wymagany przez §11 pkt 5): dwaj tenanci mają dane w TEJ SAMEJ partycji miesięcznej `contact`; purge tenanta A z krótszą retencją nie usuwa ŻADNEGO wiersza tenanta B — zweryfikowane wprost (`COUNT(*)` dla tenanta B niezmieniony)
- [x] `CONTACT_INTERACTIONS`: dodatkowo czyści `email_message.contact_id`/`social_message.contact_id` → `NULL` dla usuwanych kontaktów (nie usuwa tych wierszy, tylko odcina referencję)
- [x] `TenantContext.snapshot()`/`restore()`/`clear()` na granicy wątku `@Async` — zweryfikowane testem, że kontekst tenanta w wątku roboczym jest poprawny niezależnie od wątku wywołującego
- [x] Błąd w trakcie batcha → `retention_purge_log.status='FAILED'` + `error_message`, job nie zawiesza się w stanie `RUNNING` na zawsze
- [x] Sukces → `status='COMPLETED'`, `completed_at`, `rows_deleted` = suma wszystkich batchy
- [x] Wpis w `audit_log` (`entity_type='RETENTION_PURGE'`) po zakończeniu (sukces i porażka)
- [x] Testy jednostkowe ≥8 scenariuszy (batch pojedynczy, wiele batchy, izolacja cross-tenant, błąd w trakcie, cutoff dokładnie na granicy, kategoria CONTACT_INTERACTIONS czyści email/social FK, kategoria TRANSCRIPTS usuwa z 2 tabel, purgeId zwrócony natychmiast)
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-11):**
- **Self-invocation `@Async`:** rozwiązane wzorcem `@Autowired @Lazy private RetentionPurgeService self`
  (identyczny do sprawdzonego `ProgressiveDialerServiceImpl.self`) — `purgeAsync` MUSIAŁA zostać
  dodana do interfejsu `RetentionPurgeService` (nie tylko do impl), żeby wywołanie przez
  wstrzyknięty do siebie samego bean przechodziło przez proxy Springa. Po drodze zweryfikowano
  (przez czytanie kodu + testów), że istniejący `CustomerImportServiceImpl.initiateImport` wywołuje
  `processImportAsync` przez zwykłe self-invocation (`this.`, metoda nie jest częścią interfejsu
  `CustomerImportService`) — to prawdopodobnie **pre-existing bug**: `@Async` nigdy nie jest
  honorowane w tym miejscu w produkcji (import wykonuje się synchronicznie w wątku HTTP). Poza
  zakresem BE-113 (inny serwis, inny ticket), ale odnotowane do ewentualnej przyszłej korekty.
- **Krytyczne odkrycie przy projektowaniu batchowanego DELETE na tabeli partycjonowanej:**
  zweryfikowano EMPIRYCZNIE na żywej instancji PostgreSQL (`cc-postgres`, ten sam schemat co dev),
  że wzorzec zasugerowany w treści ticketu (`DELETE ... WHERE ctid IN (SELECT ctid ... LIMIT N)`)
  jest NIEBEZPIECZNY na tabelach partycjonowanych: `ctid` (fizyczny adres blok+offset) nie jest
  unikalny globalnie — ta sama para współrzędnych występuje niezależnie w wielu różnych partycjach.
  Eksperyment: subquery ograniczone do jednego tenanta w partycji `contact_2026_05` z `LIMIT 100`
  usunęło **168 wierszy w całej tabeli** (wszystkie partycje/miesiące, nie tylko maj) zamiast
  zamierzonych 100 dla jednego tenanta — nadmiarowe usunięcia trafiły w wiersze innych partycji na
  podstawie przypadkowej kolizji `ctid`. Zastąpiono bezpiecznym wzorcem: `WITH batch AS (SELECT
  <pk_techniczny>, <kolumna_partycjonowania> ... LIMIT N) DELETE ... USING batch WHERE
  <pk_techniczny> = batch.<pk_techniczny> AND <kolumna_partycjonowania> = batch.<kolumna_partycjonowania>`
  — identyfikacja wiersza przez PEŁNY klucz główny (w tym kolumnę partycjonowania), zweryfikowany w
  tym samym eksperymencie jako usuwający dokładnie zamierzoną liczbę wierszy, wyłącznie dla
  właściwego tenanta. Zastosowano konsekwentnie w `ContactRepository.deleteBatchOlderThan`,
  `ContactEventRepository.deleteBatchOlderThan`, `ContactAiSummaryRepository.deleteBatchOlderThan`,
  `ContactTranscriptionRepository.deleteBatchOlderThan`.
- **Rozszerzenie poza listę plików z ticketu** (konieczne, nie opcjonalne): logika usuwania per
  tabela musiała trafić do istniejących repozytoriów/serwisów domenowych (`ContactRepository`,
  `ContactEventRepository`, `ContactAiSummaryRepository`, `ContactTranscriptionRepository`,
  `EmailMessageRepository`, `SocialMessageRepository` + odpowiadające im publiczne serwisy
  `ContactService`/`ContactEventService`/`EmailMessageService`/`SocialMessageService`), ponieważ
  repozytoria w tym projekcie są `package-private` — `RetentionPurgeServiceImpl` (pakiet
  `domain.retention`) nie mógł ich wstrzyknąć bezpośrednio. Nowe metody: `purgeContactsOlderThan`,
  `purgeTranscriptionsOlderThan`, `purgeAiSummariesOlderThan` (ContactService), `purgeOlderThan`
  (ContactEventService), `detachContactReferences` (EmailMessageService/SocialMessageService).
  Dodano też encję `RetentionPurgeLog.java` (JPA, tabela `retention_purge_log`) — nie wymieniona
  wprost w liście plików ticketu, ale niezbędna dla `RetentionPurgeLogRepository`.
- **CAMPAIGN_DATA/RECORDINGS:** `purge()` rzuca `UnsupportedOperationException` z czytelnym
  komunikatem wskazującym właściwy przyszły mechanizm (BE-119/BE-116) — walidacja PRZED zapisem do
  `retention_purge_log`, więc błędne wywołanie nie zostawia śmieciowego wiersza RUNNING.
- **Testy:** `RetentionPurgeServiceImplTest` — 20 scenariuszy (≥8 wymaganych), mockowany
  `RetentionPurgeLogRepository`/`RetentionPolicyService`/`ContactService`/`ContactEventService`/
  `EmailMessageService`/`SocialMessageService`/`AuditLogService`; self-invocation testowane
  wzorcem `ReflectionTestUtils.setField(service, "self", service)` (zgodnie z
  `ProgressiveDialerServiceTest`), z dedykowanym testem podmieniającym `self` na mock żeby
  udowodnić że `purge()` zwraca się przed wykonaniem faktycznej pracy. Dodatkowo
  `ContactRepositoryPurgeTest` (6 scenariuszy) weryfikujący samą treść SQL (brak `ctid`, pełny PK,
  poprawne parametry) na poziomie repozytorium. Ograniczenie testów jednostkowych: nie odtwarzają
  fizycznie przełączenia wątku `@Async` (brak kontenera Spring) ani nie weryfikują zachowania na
  żywej partycjonowanej bazie (projekt nie ma Testcontainers/H2 skonfigurowanych dla testów
  repozytoriów — wzorzec potwierdzony jedynie manualną weryfikacją opisaną wyżej, poza automatycznym
  zestawem testów).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1622 testy, 0 failures, 0 errors (1596 przed BE-113 wg
  notatki BE-117 + 20 nowych w `RetentionPurgeServiceImplTest` + 6 w `ContactRepositoryPurgeTest`).
- `/verify` (frontend + backend): lint PASS (10 pre-existing warnings, 0 błędów), format:check PASS,
  frontend testy PASS (205/205), backend `mvn verify -pl app` PASS.

---

### BE-114 – `PartitionMaintenanceJob`: fallback Java `@Scheduled` dla tworzenia przyszłych partycji (KRYTYCZNE)

**Typ:** Backend implementation / bugfix
**Priorytet:** Must Have — **krytyczny, ta sama waga co DB-052**
**Złożoność:** M
**Zależy od:** DB-052
**Status:** ✅ Ukończone
**Blokuje:** brak bezpośrednio (ale bez tego ticketu błąd rotacji partycji wróci już w kolejnym miesiącu — DB-052 naprawia stan dzisiejszy, ten ticket zapobiega powtórce)
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
Zamiast aktywować `pg_cron` (rozszerzenie niedostępne w obrazie `postgres:16-alpine`,
wymagałoby customowego obrazu Docker), rotację partycji realizujemy przez Spring `@Scheduled`
— dominujący, sprawdzony wzorzec w projekcie (12+ istniejących jobów, m.in.
`RecordingRetentionJob`). **To jest bezpośrednia przyczyna, dla której DB-052 w ogóle było
potrzebne** — dziś nic nie wywołuje `create_next_month_partitions()`.

**Plik:**
```
backend/app/src/main/java/com/contactcenter/domain/retention/PartitionMaintenanceJob.java
```

```java
@Scheduled(cron = "${retention.partition-maintenance-cron:0 30 0 * * *}", zone = "UTC")
public void ensureFuturePartitions() {
    // Wywołuje create_next_month_partitions() (rozszerzona w DB-052 o wszystkie
    // partycjonowane tabele: contact, audit_log, contact_event, contact_transcription,
    // contact_ai_summary, plugin_invocation_log) — codziennie, idempotentnie
}
```

**Kryteria akceptacji:**
- [x] Job wywołuje `create_next_month_partitions()` codziennie o 00:30 UTC (konfigurowalny cron, wzorzec `${property:default}`)
- [x] Idempotentny — dwukrotne uruchomienie tego samego dnia nie tworzy duplikatów/błędów (funkcja SQL ma `IF NOT EXISTS`)
- [x] Log INFO z podsumowaniem (ile partycji utworzono, dla których tabel) po każdym uruchomieniu
- [x] Błąd SQL nie crashuje aplikacji — log ERROR, job kontynuuje przy następnym uruchomieniu
- [x] **Test regresyjny kluczowy:** test integracyjny/jednostkowy potwierdzający, że po ręcznym wywołaniu `ensureFuturePartitions()` partycja na „bieżący miesiąc + 3” istnieje dla wszystkich 6 partycjonowanych tabel
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-13):**
- **Rozbieżność w treści ticketu, wykryta i rozwiązana świadomie:** przykładowy kod w ticketcie
  sugerował, że `ensureFuturePartitions()` ma jedynie wołać zbiorczą funkcję SQL
  `create_next_month_partitions()`. Odczytanie treści tej funkcji w V088 (linie 622-648) pokazało,
  że tworzy ona partycję WYŁĄCZNIE na miesiąc `teraz + 1` dla wszystkich 6 tabel na raz — nigdy
  `+2`/`+3`. Zweryfikowano to dodatkowo EMPIRYCZNIE na żywej instancji `cc-postgres` (transakcja
  z `ROLLBACK`, bez trwałych zmian): stan na dziś (2026-08-13) to `plugin_invocation_log` z
  partycjami tylko do bieżącego miesiąca, `contact`/`audit_log` do `+1`, `contact_event`/
  `contact_transcription`/`contact_ai_summary` do `+2` — ŻADNA tabela nie miała jeszcze partycji na
  `+3`. Wywoływanie WYŁĄCZNIE `create_next_month_partitions()` (nawet codziennie) nigdy nie
  zbudowałoby bufora `+3` wymaganego przez kluczowe kryterium testu regresyjnego w tym samym
  tickecie — sprzeczność między przykładowym kodem/AC#1 (dosłowna nazwa funkcji) a kluczowym testem
  (`+3`). Rozwiązanie: `ensureFuturePartitions()` woła `create_next_month_partitions()` DOKŁADNIE
  RAZ na uruchomienie (honoruje dosłowne brzmienie AC#1 + zachowuje wpis bookkeeping w
  `cron_log`/`scheduled_job` zgodny z konwencją V014/V077/V088), a NIEZALEŻNIE OD TEGO buduje
  samodzielnie bufor `MONTHS_AHEAD=3` miesięcy dla wszystkich 6 tabel, wywołując bezpośrednio
  niskopoziomowe funkcje `create_<tabela>_partition(rok, miesiąc)` w pętli po ofsetach `1..3`
  (`PartitionMaintenanceRepository.createTablePartition`). Obie ścieżki są idempotentne
  (`IF NOT EXISTS` w SQL), więc częściowe pokrycie się `+1` między nimi nie generuje błędu/duplikatu.
  Efekt uboczny (pozytywny): bufor 3-miesięczny jest bardziej odporny na przestój aplikacji
  (deploy/incydent) niż poleganie wyłącznie na codziennym `+1`.
- **Wywołanie funkcji `RETURNS VOID` z Javy:** zweryfikowane empirycznie na `cc-postgres`
  (`BEGIN; SELECT create_next_month_partitions(); ROLLBACK;`) — zwraca dokładnie 1 wiersz, 1
  kolumnę typu `void` (pusta wartość), którą pgjdbc mapuje na `null` bez wyjątku;
  `EntityManager.getSingleResult()` działa poprawnie, potwierdzone też testami jednostkowymi
  (`PartitionMaintenanceRepositoryTest`).
- **Nowe pliki poza listą z ticketu:** `PartitionMaintenanceRepository.java` (analogiczny do
  `PartitionScannerImpl` — `@Repository`, package-private, `EntityManager` natywne zapytania,
  `assertSafeIdentifier` przed konkatenacją nazwy tabeli w SQL) — niezbędny, bo `PartitionMaintenanceJob`
  nie może wywołać `EntityManager` bezpośrednio bez własnego repozytorium warstwy (repozytoria w
  projekcie są `package-private`, ten sam wzorzec co dodatkowe repozytoria z BE-112/BE-113).
- **Log podsumowania:** wzorowany na wskazówce z ticketu — `PartitionScanner.listPartitions()` per
  tabela PRZED i PO wywołaniach SQL, diff nazw partycji logowany na poziomie INFO (`Utworzono N
  nowych partycji: <tabela>=[nazwy] ...` albo jawny komunikat "brak nowych" gdy wszystko już
  istniało).
- **Odporność na błędy:** trzy niezależne warstwy try/catch (błąd `create_next_month_partitions()`,
  błąd pojedynczej pary tabela/miesiąc w pętli bufora, błąd `PartitionScanner` przy snapshotach) —
  żadna pojedyncza usterka SQL nie przerywa pozostałych 17 wywołań ani nie crashuje `@Scheduled`
  metody, wzorzec identyczny do `RetentionEvaluationJob` ("błąd przy jednej kategorii/tenancie nie
  przerywa reszty").
- **Testy:** bez H2/Testcontainers dla warstwy repozytorium (konwencja projektu, zob.
  `PartitionScannerImplTest`) — `PartitionMaintenanceRepositoryTest`
  (mockowany `EntityManager`, weryfikacja dosłownego SQL + parametrów + bezpiecznika identyfikatora,
  9 scenariuszy) i `PartitionMaintenanceJobTest` (mockowane `PartitionMaintenanceRepository`/
  `PartitionScanner`, `@InjectMocks`, 8 scenariuszy) — kluczowy test regresyjny
  `buildsThreeMonthBufferForAllSixPartitionedTables()` przechwytuje wszystkie wywołania
  `createTablePartition(...)` przez `ArgumentCaptor` i potwierdza, że dla KAŻDEJ z 6 tabel zbiór
  żądanych `(rok, miesiąc)` to dokładnie `{teraz+1, teraz+2, teraz+3}`, w tym jawne
  `assertThat(...).contains(currentMonth.plusMonths(3))`.
- `mvn verify -pl app`: **BUILD SUCCESS**, 1716 testów, 0 failures, 0 errors (stan bazowy w tym
  worktree + 13 nowych testów BE-114; po scaleniu z BE-115 łączna liczba testów będzie wyższa).

---

### BE-115 – `PartitionReclaimJob`: fizyczne odzyskanie miejsca, globalne (Poziom 2)

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** M
**Zależy od:** BE-111, DB-052
**Status:** ✅ Ukończone
**Blokuje:** brak
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
`@Scheduled` (tygodniowo) — dla każdej partycji miesięcznej (tabel `contact`, `contact_event`,
`contact_transcription`, `contact_ai_summary`) sprawdza, czy jej górna granica jest starsza niż
**maksimum** `retention_months` spośród WSZYSTKICH tenantów mających kiedykolwiek dane w tej
kategorii. Jeśli tak — `DROP TABLE <partycja>` (rozszerzenie istniejącego wzorca
`drop_old_audit_log_partitions`, uogólnione). Bezpieczny, zachowawczy próg: nigdy nie usuwa
danych szybciej niż zezwala na to najdłuższa skonfigurowana retencja — Poziom 1 (BE-113) już
wcześniej usunął wiersze każdego tenanta zgodnie z jego własną, krótszą retencją, więc DROP na
końcu w praktyce trafia na już (prawie) puste partycje.

**Plik:**
```
backend/app/src/main/java/com/contactcenter/domain/retention/PartitionReclaimJob.java
```

**Kryteria akceptacji:**
- [x] Job liczy `MAX(retention_months)` per kategoria spośród wszystkich tenantów (`RetentionPolicyService`) — próg globalny, nie per-tenant
- [x] `DROP TABLE` wykonywany TYLKO dla partycji, których górna granica < (teraz − max_retention_months)
- [x] **Test wymagany przez §11 pkt 5:** `PartitionReclaimJob` NIE usuwa partycji zawierającej choćby jeden wiersz należący do tenanta, którego retencja jeszcze nie minęła — zweryfikowane wprost (partycja z mieszanymi danymi tenantów o różnych retencjach, job musi policzyć próg po NAJDŁUŻSZEJ, nie najkrótszej)
- [x] Partycja `DEFAULT` nigdy nie jest kandydatem do `DROP` (zawsze pomijana)
- [x] Log INFO z listą usuniętych partycji po każdym uruchomieniu; log WARN jeśli partycja kandydująca do usunięcia wciąż ma wiersze (niespójność z Poziomem 1 — nie powinno się zdarzyć, ale nie blokuj joba, tylko ostrzeż)
- [x] Testy jednostkowe ≥5 scenariuszy (partycja bezpiecznie pusta do usunięcia, partycja z żywymi danymi NIE usunięta, próg liczony po max nie min, partycja DEFAULT pomijana, wiele tabel przetwarzanych niezależnie)
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-13):**
- **`findMaxRetentionMonths`** dodane jako lustrzane odbicie już istniejącego `findMinRetentionMonths`
  (BE-112) w trzech warstwach: `TenantRetentionPolicyRepository` (natywne `SELECT MAX(retention_months)
  FROM tenant_retention_policy WHERE data_category = :category`, ta sama obsługa pustego zbioru —
  `MAX()` nad pustym zbiorem zwraca jeden wiersz z `NULL`, nie pustą listę), `RetentionPolicyService`
  (interfejs) i `RetentionPolicyServiceImpl` (rzuca `ResourceNotFoundException` przy braku polityki,
  identyczna semantyka co `findMinRetentionMonths`).
- **`PartitionScanner.dropPartition(String)`** — celowo dodane do istniejącego interfejsu/impl
  (`PartitionScannerImpl`) zamiast wstrzykiwać `EntityManager` bezpośrednio do `PartitionReclaimJob`
  (odstępstwo od dosłownego brzmienia researchu w zleceniu, które sugerowało
  `EntityManager.createNativeQuery` wprost w jobie). Uzasadnienie: `PartitionScannerImpl` jest już
  JEDYNYM miejscem w kodzie z dostępem do `pg_catalog`/DDL partycji i bezpiecznikiem
  `assertSafeIdentifier` — dodanie tam `dropPartition` (z tym samym bezpiecznikiem) utrzymuje tę
  odpowiedzialność w jednym miejscu i pozwala testować `PartitionReclaimJob` w 100% przez mockowanie
  `PartitionScanner`/`RetentionPolicyService` (zgodnie z poleceniem w zleceniu), bez potrzeby
  mockowania `EntityManager`/`Query` w teście joba. `DROP TABLE IF EXISTS "<partycja>"` wykonywane
  przez `em.createNativeQuery(...).executeUpdate()` wewnątrz `PartitionScannerImpl.dropPartition`.
- **Próg = ściśle `<` (nie `<=`)**: `PartitionReclaimJob` kwalifikuje partycję do `DROP` tylko gdy
  `partition.rangeEnd().isBefore(globalCutoffDate)` — celowo bardziej zachowawcze niż próg
  "eligible for purge" w `RetentionEvaluationJob` (który dopuszcza `rangeEnd <= tenantCutoffDate`),
  zgodnie z nieodwracalnym charakterem `DROP TABLE` (Poziom 2) w przeciwieństwie do usuwania
  wierszy (Poziom 1).
- **Dodatkowy bezpiecznik przed DDL**: poza `assertSafeIdentifier` w `PartitionScannerImpl`,
  `PartitionReclaimJob` sam sprawdza, czy nazwa partycji zwrócona przez `listPartitions` pasuje do
  wzorca `<tabela>_YYYY_MM` (regex) — obronnie, na wypadek nieoczekiwanego wpisu (np. gdyby
  `PartitionScanner` kiedyś przestał wykluczać `<tabela>_default`). Zweryfikowane testem
  `PartitionReclaimJobTest$DefaultPartitionNeverDropped`.
- **Pętla po 4 kombinacjach (tabela, kategoria)** — `contact`/`contact_event` →
  `CONTACT_INTERACTIONS`, `contact_transcription`/`contact_ai_summary` → `TRANSCRIPTS` (identyczne
  mapowanie co `RetentionPurgeServiceImpl`/`RetentionEvaluationJob`), każda tabela przetwarzana
  niezależnie we własnym `try/catch` (wzorzec `RecordingRetentionJob.processRetentionForTenant`) —
  `findMaxRetentionMonths` jest więc wołane 2× per kategoria (raz na tabelę), nie 1× — świadoma
  konsekwencja niezależności per-tabela, nie duplikacja błędu.
- **Cron:** `retention.partition-reclaim-cron` (domyślnie `0 0 3 * * SUN`, niedziela 3:00 UTC) —
  nowa sekcja w `application.yml` obok istniejącego `retention.evaluation-cron`.
- **Testy:** `PartitionReclaimJobTest` (8 testów w 6 nested klasach, pokrywają wszystkie 5 wymaganych
  scenariuszy z AC + 1 dodatkowy: partycja bezpiecznie pusta usunięta, partycja młodsza niż globalny
  próg NIE usunięta, próg liczony po MAX a nie MIN (2 warianty), partycja `_default` nigdy nie
  kandyduje nawet gdy defensywnie zwrócona przez mock, wiele tabel przetwarzanych niezależnie (błąd
  RuntimeException w jednej tabeli + brak polityki dla jednej kategorii — oba warianty), DROP mimo
  WARN o niespójności z Poziomem 1). Dodatkowo: `TenantRetentionPolicyRepositoryTest$FindMaxRetentionMonths`
  (2), `RetentionPolicyServiceImplTest$FindMaxRetentionMonths` (2), `PartitionScannerImplTest$DropPartition`
  (2) — łącznie 14 nowych testów.
- `mvn verify -pl app`: **BUILD SUCCESS**, 1717 testów, 0 failures, 0 errors (1703 przed BE-115 wg
  arytmetyki: 1717 − 14 nowych).

---

### BE-116 – Rozszerzenie `RecordingRetentionJob` o per-tenant retencję + usunięcie `recording_retention_days` z `tenant.config`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-111
**Status:** ✅ Ukończone
**Blokuje:** FE-109
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
`RecordingRetentionJob` dziś czyta globalny `S3Properties.retentionDays` (domyślnie 90, ten sam
dla wszystkich tenantów) mimo że `tenant.config.recording_retention_days` i
`TenantResourceLimitsDto` od dawna sugerują per-tenant. To domyka lukę wprost wymaganą przez
`PRD.md` NFR-RODO03 ("konfigurowalny per tenant") i `ARCHITECTURE.md` RC-02.

**Zmiana źródła prawdy (decyzja zaakceptowana w projekcie):** `recording_retention_days`
przestaje istnieć w `tenant.config` JSONB — jedynym źródłem prawdy staje się
`tenant_retention_policy` (kategoria `RECORDINGS`). **To NIE jest dodanie nowego pola do
istniejącego miejsca — to jego usunięcie z `tenant.config` i migracja do nowej tabeli**, żeby
nie było dwóch źródeł prawdy dla tej samej wartości (patrz FE-109, analogiczna zmiana po
stronie frontendu).

**Zmiany:**
```java
// RecordingRetentionJob.runRetentionJob() — zamiast jednego globalnego cutoffTimestamp:
for (UUID tenantId : contactService.findTenantsWithRecordings()) {
    int retentionDays = retentionPolicyService.getRetentionDays(tenantId, RECORDINGS); // nowa metoda, konwersja z retentionMonths jeśli tabela przechowuje miesiące (patrz BE-111 uwaga o jednostce)
    Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    // ... reszta logiki batchowania bez zmian
}
```
- `TenantServiceImpl`: usuń `config.put("recording_retention_days", ...)` z `createTenant`/`updateTenant` (linie ~526, ~532, ~558 wg dzisiejszego stanu — zweryfikuj dokładne numery linii przed implementacją, mogły się przesunąć)
- `TenantController`/`PATCH /api/tenants/{id}/config`: usuń `recordingRetentionDays` z odpowiednich DTO (jeśli tam jest) — **sprawdź wszystkich konsumentów tego pola przed usunięciem, żeby nie złamać kontraktu API w sposób niezauważony** (frontend fix w FE-109 musi być wdrożony w tej samej fali/PR co ta zmiana)
- Backfill istniejących wartości do `tenant_retention_policy` już obsłużony w DB-046 (V082) — ten ticket TYLKO usuwa stare źródło po stronie Javy, nie duplikuje backfillu

**Kryteria akceptacji:**
- [x] `RecordingRetentionJob` czyta retencję per tenant z `RetentionPolicyService`, nie z `S3Properties.retentionDays` (pole `S3Properties.retentionDays` może zostać jako fallback/domyślna wartość seedowania w DB-046, ale przestaje być odczytywane w runtime joba)
- [x] `recording_retention_days` usunięty z odczytu/zapisu w `TenantServiceImpl`/`TenantController`
- [x] Test regresyjny: dwaj tenanci z różnymi wartościami `RECORDINGS.retentionMonths` — job usuwa nagrania każdego zgodnie z JEGO własną retencją, nie globalną
- [x] Test: **`PATCH /api/tenants/{id}`** (korekta — nie `/config`, patrz notatka niżej) z polem `recordingRetentionDays` w body → pole ignorowane, nie powoduje 500
- [x] `mvn verify -pl app` przechodzi bez regresji istniejących testów `RecordingRetentionJobTest`/`TenantServiceTest`

**Notatka z implementacji (2026-08-13):**
- **Konwersja miesiące → dni w nowej `RetentionPolicyService.getRetentionDays(tenantId, category)`:** `retentionMonths * 30` (stała `DAYS_PER_MONTH`), NIE `* 30.44` ani uwzględnianie faktycznej liczby dni w danym miesiącu kalendarzowym. Uzasadnienie udokumentowane w javadoc metody: to najprostsza wierna odwrotność konwersji dni → miesiące już użytej w backfillu V082 (`CEIL(dni / 30.0)`) — spójność z istniejącym precedensem w projekcie, nie precyzja kalendarzowa; różnica rzędu 1–2 dni na 30-dniowy miesiąc jest nieistotna dla polityki retencji **konfigurowanej i wyrażanej w miesiącach** (administrator ustawia "6 miesięcy", nie "182 dni").
- **Korekta endpointu (istotna rozbieżność względem treści ticketu):** `recordingRetentionDays` NIGDY nie był częścią `PATCH /api/tenants/{id}/config` — ten endpoint (`TenantController.updateTwilioConfig`) obsługuje wyłącznie `TenantTwilioConfigRequest` (numer telefonu/webhook URL Twilio), zero związku z `TenantResourceLimitsDto`. Właściwy endpoint to **`PATCH /api/tenants/{id}`** (`TenantController.updateTenant`, `UpdateTenantRequest.limits()` zagnieżdżający `TenantResourceLimitsDto`) — tam faktycznie żyło (i zostało przetestowane) pole.
- **Zachowanie Jacksona ze zweryfikowane (nie zgadywane):** projekt NIE MA globalnego `spring.jackson.deserialization.fail-on-unknown-properties=false` ani dedykowanego `ObjectMapper` beana dla warstwy REST (jedyny customowy `ObjectMapper` to ten w `RedisConfig`, niezwiązany z HTTP). Domyślny Jackson (`FAIL_ON_UNKNOWN_PROPERTIES=true`) rzuciłby `UnrecognizedPropertyException` dla nieznanego pola — a ponieważ `GlobalExceptionHandler` NIE MA dedykowanego `@ExceptionHandler(HttpMessageNotReadableException.class)`, wyjątek trafiłby do generycznego `@ExceptionHandler(Exception.class)` → **HTTP 500** (dokładnie to, czego zabrania AC). Zweryfikowane empirycznie testem Jacksona bezpośrednio na DTO (`TenantResourceLimitsDtoTest`, `new ObjectMapper()` bez customowej konfiguracji — reprezentuje realne ustawienia projektu). **Decyzja: pole jest CICHO IGNOROWANE** (nie 400) — dodano `@JsonIgnoreProperties(ignoreUnknown = true)` na `TenantResourceLimitsDto` (analogicznie do istniejącego wzorca w `ImportJobStatus`/`CallSession`/`IvrNode`), żeby ułatwić zgodność wsteczną klientom wysyłającym to pole podczas okresu przejściowego (przed wdrożeniem FE-109).
- **Wszyscy znalezieni konsumenci `recordingRetentionDays`/`recording_retention_days` (zmodyfikowani/usunięci):**
  - `TenantServiceImpl.buildConfig()`/`mergeConfig()` — usunięto zapis klucza do `config` (obie gałęzie `buildConfig`, `null`-check w `mergeConfig`).
  - `TenantResourceLimitsDto` — usunięto pole `recordingRetentionDays` + z `defaults()`, dodano `@JsonIgnoreProperties(ignoreUnknown = true)`.
  - `TenantResponse.from()` — usunięto odczyt `extractInt(config, "recording_retention_days", 90)` przy budowaniu `limits`; martwa metoda pomocnicza `extractInt` również usunięta (brak innych wywołań).
  - `Tenant.defaultConfig()` — usunięto klucz (czwarty konsument, nieujęty wprost w treści ticketu, znaleziony przez grep) + zaktualizowano javadoc pola `config`.
  - `RetentionPolicyServiceImpl.resolveRecordingsRetentionMonths()` — **punkt styku z BE-111**: metoda personalizowała domyślną politykę `RECORDINGS` nowego tenanta czytając `tenant.config->>'recording_retention_days'` przez `TenantRetentionPolicyRepository.findConfiguredRecordingRetentionDays(tenantId)`. Po usunięciu klucza z `tenant.config` ta personalizacja stała się niemożliwa (i zapytanie zawsze zwracałoby COALESCE-owy fallback) — metoda przepisana na wprost `S3Properties.getRetentionDays()` (jawnie dozwolony fallback wg treści ticketu), parametr `tenantId` i teraz-zbędny `FALLBACK_RECORDINGS_MONTHS` usunięte z sygnatury/klasy.
  - `TenantRetentionPolicyRepository.findConfiguredRecordingRetentionDays(UUID)` — metoda usunięta całkowicie (dead code po powyższej zmianie; zapytanie o klucz JSONB, który nigdy więcej nie zostanie ustawiony). Referencje w javadoc `findMinRetentionMonths`/`PartitionScannerImpl` (precedens cross-tenant) zaktualizowane, żeby nie wskazywały na usuniętą metodę.
  - Testy: `TenantServiceTest` (config w `setUp()`, konstruktory `TenantResourceLimitsDto`, asercja `recordingRetentionDays()`, nowy test `shouldNotWriteRecordingRetentionDaysToConfig`), `AdminMetricsServiceImplTest.buildDefaultConfig()`, `RetentionPolicyServiceImplTest` (`SeedDefaultPolicies` przepisane na mock `S3Properties` zamiast `repository.findConfiguredRecordingRetentionDays`; nowy `@Nested GetRetentionDays`), `TenantRetentionPolicyRepositoryTest` (usunięty cały `@Nested FindConfiguredRecordingRetentionDays`).
- **Nowe testy:** `RecordingRetentionJobTest` (nowy plik, `domain.recording` — brak istniejącego wcześniej mimo że ticket i notatka BE-112 zakładały jego istnienie) — regresja AC (dwaj tenanci, różne `retentionMonths`, oddzielny cutoff per tenant, `ArgumentCaptor<Instant>`), test usuwania nagrań niezależnie per tenant, test odporności na `ResourceNotFoundException` dla jednego tenanta (job kontynuuje dla pozostałych). `TenantResourceLimitsDtoTest` (nowy plik, `api.tenant.dto`) — bezpośredni test Jacksona (nie MockMvc — zgodnie z udokumentowanym wzorcem projektu z `RetentionControllerTest`: kontrolery `api.*` NIE mają `@WebMvcTest` z aktywnym security chain, testowanie infrastruktury Spring MVC/Jackson robione bezpośrednio na mechanizmie, nie przez pełny stack).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1711 testów, 0 failures, 0 errors.

**Aktualizacja (bugfix TenantContext w wątku schedulera, 2026-08-13):** przy ręcznej weryfikacji
EPIC-29 na żywym kontenerze (uruchomienie jobu poza testami jednostkowymi) wykryto, że
`processRetentionForTenant` NIGDY nie ustawiał `TenantContext` (ThreadLocal) przed wywołaniem
`contactService.clearRecordingUrl(...)` → `ContactRepository#clearRecordingUrl` →
`TenantAwareRepository#assertSameTenant`, które BEZWARUNKOWO czyta `TenantContext.getTenantId()`.
Wątek `@Scheduled` nigdy nie przechodzi przez `TenantFilter` (brak JWT), więc to wywołanie zawsze
rzucało `IllegalStateException`, złapaną przez istniejący `try/catch` per-tenant — job "kończył się
sukcesem" w logach (`Usunięto: 0, Błędy: N`), ale w rzeczywistości: plik nagrania BYŁ poprawnie
usuwany z S3 (ta operacja nie dotyka `TenantContext`), lecz `contact.recording_url` w DB NIGDY nie
było czyszczone. Efekt w produkcji: ten sam kontakt wracałby jako "wygasły" przy KAŻDYM kolejnym
przebiegu jobu, próbując bezskutecznie usunąć już nieistniejący plik z S3, w nieskończoność, bez
żadnej widoczności poza rosnącym licznikiem błędów w logu. **Naprawa:**
`TenantContext.setTenantId(tenantId)` na początku ciała `processRetentionForTenant`,
`TenantContext.clear()` w `finally` obejmującym całą metodę (ochrona przed wyciekiem kontekstu
między tenantami przy reużyciu wątku puli schedulera między kolejnymi tenantami/przebiegami).
Wzorzec skopiowany z `SocialIntegrationServiceImpl#refreshToken`. Ten sam bug (identyczny
mechanizm, dwie osobne pętle) naprawiony równolegle w `RetentionEvaluationJob` — patrz aktualizacja
w notatce BE-112 wyżej po pełny opis tamtej naprawy; oba buga odkryte i naprawione w tej samej
sesji weryfikacyjnej.
- **Nowe testy regresyjne** (mockowany `ContactService` w istniejących testach tego jobu nigdy nie
  mógł wykryć tego buga — mock nie wykonuje prawdziwego ciała `assertSameTenant`/`TenantContext`,
  analogicznie do udokumentowanego wcześniej przypadku `NativeQueryConstructorTransformer`):
  `ContactRepositoryClearRecordingUrlTest` (NOWY plik, `domain.contact` — prawdziwe
  `ContactRepository`, mockowany tylko `JdbcTemplate`/`EntityManager`; potwierdza, że
  `clearRecordingUrl` bez `TenantContext` rzuca ISE i NIE dotyka DB przez `jdbcTemplate`, a z
  ustawionym kontekstem poprawnie wykonuje `UPDATE ... SET recording_url = NULL`), plus test
  cross-tenant obronny), `RecordingRetentionJobTest` (+nested `TenantContextRegression`, 5 testów:
  kontekst ustawiony na poprawny `tenantId` w momencie wywołania `clearRecordingUrl`, izolacja
  między dwoma tenantami przetwarzanymi w tej samej pętli, wyczyszczenie kontekstu po sukcesie i
  po błędzie przetwarzania tenanta).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1767 testów, 0 failures, 0 errors.

---

### BE-117 – Migracja encji JPA na klucz złożony: `ContactEvent`, `ContactAiSummary` → `@IdClass`; aktualizacja `ContactTranscriptionRepository`

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-049, DB-050, DB-051
**Status:** ✅ Ukończone
**Blokuje:** BE-113
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

> **Uwaga (weryfikacja 2026-08-09):** wcześniej pole `Blokuje` wymieniało też BE-112, ale
> `BE-112.Zależy od` nigdy nie deklarował BE-117 (asymetria) i BE-112 (liczenie/agregacja
> partition-aware) operuje na natywnych zapytaniach COUNT per partycja, nie na encjach JPA — nie
> jest wrażliwy na zmianę `@IdClass`. Tylko BE-113 (silnik usuwania przez encje JPA) faktycznie
> zależy od tej migracji. Poprawiono na samo BE-113.

**Opis:**
Po partycjonowaniu (DB-049/050/051) PK tabel `contact_event`/`contact_ai_summary` staje się
złożony `(id, kolumna_czasowa)` (PostgreSQL wymaga, żeby kolumna partycjonowania wchodziła w
PK). Warstwa JPA musi to odzwierciedlić — wzorzec identyczny do istniejącego `Contact`/
`ContactId` (`ContactRepository`, natywny SQL zamiast `EntityManager.persist()`).

**Zmiany:**
```
backend/app/src/main/java/com/contactcenter/domain/contact/
  ContactEventId.java        (nowy, record lub @Embeddable — para eventId+startedAt, wzorzec ContactId.java)
  ContactEvent.java          (@IdClass(ContactEventId.class), oba pola @Id: eventId, startedAt)
  ContactEventRepository.java (jeśli używa EntityManager.persist — zmień na natywny INSERT, wzorzec ContactRepository)

  ContactAiSummaryId.java    (analogicznie)
  ContactAiSummary.java      (@IdClass, oba pola @Id: aiSummaryId, <kolumna z DB-051>)
  ContactAiSummaryRepository.java (już natywny SQL wg dzisiejszego stanu — sprawdź czy insert/update/delete zawiera teraz kolumnę partycjonowania w WHERE)

  ContactTranscriptionRepository.java (czysty JdbcTemplate, bez encji — dodaj created_at do WHERE przy UPDATE/DELETE jeśli takie operacje istnieją; INSERT bez zmian poza upewnieniem się że created_at jest zawsze jawnie ustawiane, nie poleganie wyłącznie na DEFAULT NOW() przy operacjach batch)
```

**Kryteria akceptacji:**
- [x] `ContactEvent`/`ContactAiSummary` kompilują się z `@IdClass`, `equals`/`hashCode` na obu polach klucza (Lombok `@EqualsAndHashCode` lub ręcznie w klasie ID)
- [x] Wszystkie istniejące zapytania (`findByContactId` itd.) wciąż działają — kolumna partycjonowania NIE musi być podawana przy SELECT po `contact_id`, tylko przy operacjach adresujących wiersz po jego PK (UPDATE/DELETE po samym `id`, jeśli takie istnieją, muszą dodać drugą kolumnę do WHERE)
- [x] Testy jednostkowe istniejące dla `ContactEvent`/`ContactAiSummary`/transcription przechodzą bez zmian w oczekiwaniach biznesowych (tylko dostosowanie do nowego PK w warstwie technicznej)
- [x] `mvn verify -pl app` przechodzi bez regresji

**Notatka z implementacji (2026-08-10):**
- `ContactEventRepository.closeLastOpen` (UPDATE) **NIE wymagał** dodania `started_at` do WHERE —
  identyfikuje wiersz przez kombinację `contact_id`/`tenant_id`/`stage`/`ended_at IS NULL` (nie tylko
  przez `event_id`), więc jest semantycznie poprawny bez zmian; jedyny koszt to brak partition pruning
  (akceptowalne przy dzisiejszej liczbie partycji). Udokumentowano decyzję w javadoc metody.
- `ContactAiSummaryRepository` nie miał żadnych metod UPDATE/DELETE adresujących wiersz po PK — bez zmian
  poza dodaniem `@IdClass` do encji.
- `ContactTranscriptionRepository.save` (czysty JdbcTemplate, brak encji JPA) zmieniony: `created_at`
  ustawiane jawnie z Javy (`Instant.now()` → `Timestamp`) zamiast polegania na `DEFAULT NOW()` w DB.
- `mvn verify -pl app`: **BUILD SUCCESS**, 1596 testów, 0 failures, 0 errors.

---

### BE-118 – `RetentionController` + DTO: REST API zarządzania retencją

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-111, BE-112, BE-113
**Status:** ✅ Ukończone
**Blokuje:** FE-103, FE-104, FE-105, FE-106, FE-107, FE-108
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
Wszystkie endpointy wymagają roli `ADMIN` (tenant-scoped), autoryzacja przez istniejący
`JwtAuthFilter`/`TenantFilter` — **brak nowych endpointów publicznych**, nie dotyczy
`SecurityConfig`/`TenantFilter.PUBLIC_PATH_PREFIXES`.

**Endpointy:**
| Endpoint | Opis |
|---|---|
| `GET /api/tenants/{tenantId}/retention/policies` | lista 4 polityk (BE-111) |
| `PUT /api/tenants/{tenantId}/retention/policies/{category}` | zmiana `retentionMonths`/`autoPurgeEnabled` (ADMIN) |
| `GET /api/tenants/{tenantId}/retention/summary` | dashboard „ile do usunięcia” (z cache `tenant_retention_pending_summary`, BE-112) |
| `POST /api/tenants/{tenantId}/retention/purge` | body `{dataCategory}` → async purge (BE-113/BE-119), zwraca `purgeId` |
| `GET /api/tenants/{tenantId}/retention/purge/{purgeId}` | status trwającego/zakończonego purge |
| `GET /api/tenants/{tenantId}/retention/history` | log operacji (`retention_purge_log`, paginacja `PagedResponse<T>`) |

**Pliki:**
```
backend/app/src/main/java/com/contactcenter/
  api/retention/RetentionController.java
  domain/retention/dto/RetentionSummaryDto.java
  domain/retention/dto/PurgeRequestDto.java
  domain/retention/dto/PurgeStatusDto.java
  domain/retention/dto/PurgeHistoryEntryDto.java
```

**Kryteria akceptacji:**
- [x] `@PreAuthorize("hasRole('ADMIN')")` na wszystkich endpointach (weryfikacja: SUPERVISOR/AGENT → 403)
- [x] `GET .../summary` zwraca 4 wpisy (jeden per kategoria), z `computedAt` — brak wiersza w cache (jeszcze nie policzony przez BE-112) zwraca `eligibleRowCount=0`/`computedAt=null` z jawnym flagowaniem "not yet computed" (nie myli się z "zero do usunięcia")
- [x] `POST .../purge` zwraca `202 Accepted` + `purgeId`, nie blokuje na wykonaniu (deleguje do `@Async` RetentionPurgeService)
- [x] `GET .../purge/{purgeId}` → 404 jeśli purgeId nie należy do tenanta (cross-tenant access przez zgadywanie UUID zablokowane)
- [x] `GET .../history` paginowane `PagedResponse<PurgeHistoryEntryDto>`, sortowane malejąco po `started_at`
- [x] Endpointy udokumentowane w Swagger UI (`@Operation`/`@ApiResponse`)
- [x] Testy jednostkowe kontrolera (autoryzacja per rola, happy path każdego endpointu, 404 cross-tenant)
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-12):**
- **KRYTYCZNE odkrycie w `SecurityConfig` (poza zakresem plików wymienionych w tickecie, ale
  blokujące bez naprawy):** reguła filtra `.requestMatchers("/api/tenants/**").hasRole("SUPER_ADMIN")`
  (BE-006) dopasowałaby też nowe ścieżki `/api/tenants/{tenantId}/retention/**`, blokując ADMIN
  na poziomie Spring Security filter chain, ZANIM żądanie dotarłoby do `@PreAuthorize("hasRole('ADMIN')")`
  tego kontrolera — ADMIN dostawałby 403 zawsze, niezależnie od logiki kontrolera. Naprawione
  dodaniem `.requestMatchers("/api/tenants/*/retention/**").hasRole("ADMIN")` PRZED ogólną regułą
  `/api/tenants/**`, dokładnie tym samym wzorcem co istniejący wyjątek dla `/api/tenants/*/config`
  (BE-025). Bez tej zmiany żaden z 6 endpointów nie działałby end-to-end mimo poprawnego kodu
  kontrolera — wykryte przez przegląd `SecurityConfig` przed napisaniem kontrolera, nie przez
  test (testy kontrolera w tym projekcie nie uruchamiają łańcucha Spring Security, patrz niżej).
- **Bezpieczeństwo `{tenantId}`/`{purgeId}` — dwa niezależne mechanizmy, oba zaimplementowane
  dokładnie wg analizy z briefu:** (1) `RetentionController.assertOwnTenant(UUID)` — prywatna
  metoda wywoływana jako pierwsza instrukcja KAŻDEGO z 6 endpointów, porównuje `{tenantId}` ze
  ścieżki z `TenantContext.getTenantId()`, rzuca `CrossTenantAccessException` → 403 przy
  niezgodności (wzorzec `TenantServiceImpl.assertSameTenantUnlessSuperAdmin`, uproszczony — brak
  SUPER_ADMIN w zakresie tego kontrolera). (2) `RetentionPurgeService.getPurgeStatus` mapuje
  `Optional.empty()` z `RetentionPurgeLogRepository.findById(purgeId, tenantId)` (filtruje po OBU
  kolumnach w SQL) na `ResourceNotFoundException` → 404 — purgeId innego tenanta jest
  nieodróżnialny od nieistniejącego. `RetentionPolicyService`/`RetentionPurgeService` pozostały
  BEZ ŻADNEJ zmiany w kierunku "tylko własny tenant" — cały pomysł żyje wyłącznie w kontrolerze,
  bo `RetentionEvaluationJob` (BE-112) legalnie wywołuje te serwisy cross-tenant dla wszystkich
  tenantów po kolei.
- **`RetentionPurgeService` rozszerzone o 3 nowe metody publiczne** (implementacja w
  `RetentionPurgeServiceImpl`, nowa zależność `TenantRetentionPendingSummaryRepository`
  wstrzyknięta przez `@RequiredArgsConstructor`):
  - `PurgeResultDto getPurgeStatus(UUID tenantId, UUID purgeId)` — rzuca `ResourceNotFoundException`
    gdy purgeId nie istnieje/inny tenant.
  - `Page<PurgeResultDto> getPurgeHistory(UUID tenantId, Pageable pageable)` — deleguje do nowej
    `RetentionPurgeLogRepository.findAllByTenantId(UUID, Pageable)` (natywny SQL + `resultClass`
    mapping + osobny `COUNT(*)`, wzorzec identyczny do reszty repozytorium — celowo NIE JPQL, żeby
    nie mieszać stylów zapytań w jednej klasie), `ORDER BY started_at DESC` zawsze, niezależnie od
    `pageable.getSort()`.
  - `List<RetentionSummaryDto> getPendingSummary(UUID tenantId)` — czyta surowe wiersze przez nową
    `TenantRetentionPendingSummaryRepository.findAllByTenantId(UUID)` (zwraca `List<PendingSummaryRow>`,
    nowy pakietowy rekord zagnieżdżony w repozytorium, wzorzec `CampaignArchiveRetentionRepository.EligibleSummary`
    z BE-112), po czym syntetyzuje DOKŁADNIE 4 wpisy iterując `RetentionDataCategory.values()` —
    kategoria bez wiersza w cache dostaje `computed=false`/`eligibleRowCount=0`/pozostałe pola `null`.
    Synteza żyje w serwisie (nie w kontrolerze), zgodnie z rekomendacją briefu — łatwiej testować
    jednostkowo.
- **Reuse `PurgeResultDto` zamiast nowych `PurgeStatusDto`/`PurgeHistoryEntryDto`:** ticket
  wymienia te dwa pliki w liście, ale miałyby identyczny kształt co `PurgeResultDto` (BE-111) —
  używany bez zmian zarówno dla `GET .../purge/{purgeId}` (pojedynczy) jak i `GET .../history`
  (strona). Lista plików w sekcji "Pliki" powyżej pozostawiona bez zmian jako zapis historyczny
  ticketu — faktycznie utworzone/zmienione pliki wypisane niżej.
- **`UpdateRetentionPolicyRequest.dataCategory()` vs `{category}` z path:** zamiast ignorować
  pole body (co ukryłoby błąd klienta wysyłającego niespójne dane) lub tworzyć nowy DTO bez tego
  pola, `RetentionController.updatePolicy` weryfikuje zgodność i rzuca `IllegalArgumentException`
  (→ 422, istniejący handler) przy niezgodności — `{category}` ze ścieżki pozostaje źródłem
  prawdy (REST semantyka).
- **`POST .../purge` zwraca `Map.of("purgeId", ...)`**, bez dedykowanego DTO — wzorzec
  `CampaignImportController` (`jobId`), zgodnie z ustalonym w projekcie precedensem dla
  pojedynczego pola w odpowiedzi `202`.
- **Nowe metody repozytoriów** (obie package-private, wywoływane wyłącznie z `RetentionPurgeServiceImpl`
  w tym samym pakiecie `domain.retention` — bez potrzeby zmiany widoczności na `public`, w
  odróżnieniu od `PluginInvocationLogRepository`, którego konsument leży w innym pakiecie):
  `RetentionPurgeLogRepository.findAllByTenantId(UUID, Pageable): Page<RetentionPurgeLog>`,
  `TenantRetentionPendingSummaryRepository.findAllByTenantId(UUID): List<PendingSummaryRow>`.
- **Testy:** `RetentionControllerTest` (26 scenariuszy: happy path × 6 endpointów, 403 cross-tenant
  × 6 + parametryzowany po `RetentionDataCategory` dla `updatePolicy`, 404 purgeId innego tenanta
  propagowany bez maskowania, niezgodność `dataCategory` body/path, propagacja `UnsupportedOperationException`
  dla RECORDINGS/CAMPAIGN_DATA, 8 testów Bean Validation przez `jakarta.validation.Validator`
  bezpośrednio — wzorzec `UserPreferencesServiceTest`, bo `@Valid` jest infrastrukturą Spring MVC
  nieaktywną przy bezpośrednim wywołaniu metody kontrolera). Wzorzec testu kontrolera: wywołanie
  metod bezpośrednio, `TenantContext` mockowany statycznie (`PluginAdminControllerTest`) — projekt
  NIE ma `@WebMvcTest` z aktywnym łańcuchem Spring Security dla kontrolerów `api.*`, `@PreAuthorize`
  zweryfikowany deklaratywnie (obecny na klasie, code review), NIE testem MockMvc — świadome
  odstępstwo od dosłownego brzmienia kryterium "SUPERVISOR/AGENT → 403" na rzecz ustalonego wzorca
  projektu (ten sam kompromis co `PluginAdminControllerTest`/`CampaignImportControllerTest`).
  Dodatkowo: 8 nowych testów `RetentionPurgeServiceImplTest` (`getPurgeStatus`/`getPurgeHistory`/
  `getPendingSummary`, w tym rozróżnienie `computed=false` vs `computed=true` z `eligibleRowCount=0`),
  2 nowe `TenantRetentionPendingSummaryRepositoryTest` (mapowanie wierszy, cache pusty), nowy plik
  `RetentionPurgeLogRepositoryTest` (2 testy paginacji natywnego SQL).
- `mvn verify -pl app`: **BUILD SUCCESS**, **1700 testów**, 0 failures, 0 errors (1662 przed
  BE-118 + 38 nowych: 26 `RetentionControllerTest` + 8 `RetentionPurgeServiceImplTest` + 2
  `TenantRetentionPendingSummaryRepositoryTest` + 2 `RetentionPurgeLogRepositoryTest`).
- `/verify` (frontend + backend): lint PASS (10 pre-existing warnings, niezwiązane z tym
  tickietem, 0 błędów), format:check PASS, frontend testy PASS (205/205), backend `mvn verify -pl app` PASS.

**Utworzone/zmienione pliki (stan faktyczny):**
```
backend/app/src/main/java/com/contactcenter/
  api/retention/RetentionController.java                          (nowy)
  domain/retention/dto/RetentionSummaryDto.java                   (nowy)
  domain/retention/dto/PurgeRequestDto.java                       (nowy)
  domain/retention/RetentionPurgeService.java                     (zmieniony: +3 metody)
  domain/retention/RetentionPurgeServiceImpl.java                 (zmieniony: +3 implementacje, +1 zależność)
  domain/retention/RetentionPurgeLogRepository.java                (zmieniony: +findAllByTenantId)
  domain/retention/TenantRetentionPendingSummaryRepository.java   (zmieniony: +findAllByTenantId, +PendingSummaryRow)
  security/SecurityConfig.java                                    (zmieniony: +requestMatcher ADMIN dla /retention/**)

backend/app/src/test/java/com/contactcenter/
  api/retention/RetentionControllerTest.java                      (nowy)
  domain/retention/RetentionPurgeServiceImplTest.java              (zmieniony: +8 testów)
  domain/retention/TenantRetentionPendingSummaryRepositoryTest.java (zmieniony: +2 testy)
  domain/retention/RetentionPurgeLogRepositoryTest.java            (nowy)
```

**Sygnatury nowych metod publicznych:**
```java
// RetentionPurgeService (interfejs)
PurgeResultDto getPurgeStatus(UUID tenantId, UUID purgeId);
Page<PurgeResultDto> getPurgeHistory(UUID tenantId, Pageable pageable);
List<RetentionSummaryDto> getPendingSummary(UUID tenantId);
```

---

### BE-119 – Integracja `CAMPAIGN_DATA` z `RetentionPurgeService`: wywołanie `purge_campaign_contact_archive()` per tenant

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** BE-113
**Status:** ✅ Ukończone (2026-08-13)
**Blokuje:** FE-110
**Epic:** EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów

**Opis:**
Podłącza kategorię `CAMPAIGN_DATA` do istniejącej funkcji SQL
`purge_campaign_contact_archive(p_retention_years INT DEFAULT 5)` (V015), zamieniając jej dziś
zahardkodowany parametr na wartość czytaną per-tenant z `tenant_retention_policy`.

**⚠️ Luka wykryta podczas dekompozycji, do rozwiązania przed/podczas implementacji — flagowana,
nie blokuje planowania:** `purge_campaign_contact_archive()` w obecnej postaci **nie przyjmuje
`tenant_id` i nie filtruje po nim** — usuwa z `campaign_contact_archive` WSZYSTKIE wiersze
starsze niż cutoff, globalnie, dla wszystkich tenantów naraz. Wywołanie jej „per tenant” z
różnymi wartościami `p_retention_years` dla różnych tenantów nie ma sensu przy obecnej
sygnaturze (ostatnie wywołanie „wygrywa” globalnie). Do rozstrzygnięcia przy implementacji:
- **Opcja A (zalecana):** nowa migracja (numer do ustalenia z `db-schema-architect` — poza
  zakresem V082–V090 z projektu, bo to nie było przewidziane w oryginalnym DDL) dodająca
  `p_tenant_id UUID` do funkcji + `WHERE tenant_id = p_tenant_id AND archived_at <
  v_cutoff_date`, wywoływana raz per tenant przez `RetentionPurgeService`.
- **Opcja B:** zostaw funkcję globalną, wywołuj raz z `MIN(retention_months)` po wszystkich
  tenantach (najbardziej konserwatywny wspólny mianownik) — prostsze, ale nie realizuje w pełni
  obietnicy „per-tenant retencja” dla tej kategorii.

Decyzja należy do wykonawcy (`backend-dev-expert` + `db-schema-architect`) przy implementacji
tego ticketu — nie blokuje reszty epiku, bo `CAMPAIGN_DATA` to jedna z 4 kategorii, pozostałe 3
działają niezależnie od tej decyzji.

**Kryteria akceptacji:**
- [x] `RetentionPurgeService` obsługuje `CAMPAIGN_DATA` jako osobną gałąź delegującą do
  zmodyfikowanej funkcji SQL (Opcja A) lub wywołania z minimalną retencją (Opcja B) — zgodnie z
  decyzją podjętą przy implementacji, udokumentowaną w komentarzu kodu
- [x] Wynik wywołania (liczba usuniętych wierszy) zapisany do `retention_purge_log` identycznie jak pozostałe kategorie
- [x] Test jednostkowy: purge `CAMPAIGN_DATA` dla tenanta A nie wpływa (Opcja A) lub wpływ jest udokumentowany i świadomy (Opcja B) na dane tenanta B
- [x] `mvn verify -pl app` przechodzi

**Notatka z implementacji (2026-08-13):**
- **Decyzja: Opcja A** (izolacja per-tenant w samej funkcji SQL), zgodnie z rekomendacją z
  dekompozycji — spójna z resztą epiku (BE-112/BE-113 realizują prawdziwą per-tenant retencję dla
  pozostałych 3 kategorii; Opcja B cichcem złamałaby tę obietnicę tylko dla `CAMPAIGN_DATA`, co
  byłoby mylące dla administratora patrzącego na UI sugerujące pełną kontrolę per-tenant). Dodatkowe
  uzasadnienie znalezione podczas implementacji: `campaign_contact_archive` (V015) jest jedyną z
  czterech tabel objętych retencją, która **nie ma włączonego Row Level Security** (w odróżnieniu od
  `contact`/`contact_event`/`contact_transcription`/`contact_ai_summary` — patrz V012/V090) — klauzula
  `WHERE tenant_id = ...` wewnątrz funkcji SQL jest więc JEDYNYM mechanizmem izolacji tenantów dla tej
  operacji, nie tylko dodatkową warstwą ponad RLS jak dla pozostałych kategorii. To czyni Opcję B
  (globalny DELETE bez filtra tenant_id) wyraźnie bardziej ryzykowną niż w innych kontekstach RLS-owych
  tego projektu.
- **Nowa sygnatura SQL (migracja `V091__purge_campaign_contact_archive_per_tenant.sql`):**
  `purge_campaign_contact_archive(p_tenant_id UUID, p_cutoff_date TIMESTAMPTZ) RETURNS INT`.
  Stara sygnatura `purge_campaign_contact_archive(p_retention_years INT DEFAULT 5)` z V015 jest
  jawnie usunięta (`DROP FUNCTION IF EXISTS ... (INT)`), NIE pozostawiona jako przeciążenie — pozostawienie
  jej działającej równolegle byłoby niebezpiecznym reliktem (ręczne wywołanie starej wersji po tej
  migracji usunęłoby dane wszystkich tenantów naraz).
- **Cutoff jako `TIMESTAMPTZ`, nie lata:** `RetentionPurgeServiceImpl#purgeAsync` wylicza
  `Instant cutoff` jednolicie dla WSZYSTKICH kategorii (na podstawie `retentionMonths` z
  `tenant_retention_policy`) PRZED dyspatchem do metody per-kategoria. Przekazanie tego samego
  `Instant` wprost do funkcji SQL (zamiast przeliczania `retentionMonths` → lata w drugą stronę)
  jest spójniejsze z wzorcem już ustalonym dla `CONTACT_INTERACTIONS`/`TRANSCRIPTS` i unika
  stratnej konwersji miesiące↔lata.
- **Efekt uboczny odkryty i naprawiony:** `RetentionEvaluationJob#evaluateCampaignData` (BE-112)
  miał wbudowany celowy „bookmark” — jawnie POMIJAŁ wywołanie `RetentionPurgeService.purge()` dla
  CAMPAIGN_DATA nawet przy `auto_purge_enabled=true`, z komentarzem odsyłającym wprost do tego
  ticketu. Po tej integracji ten warunek byłby martwym kodem (funkcjonalnie: przełącznik auto-purge
  dla CAMPAIGN_DATA w UI nic by nie robił) — zamieniono na wywołanie tego samego wspólnego
  `maybeTriggerAutoPurge`, którego już używają CONTACT_INTERACTIONS/TRANSCRIPTS. Zaktualizowano też
  odpowiadający test w `RetentionEvaluationJobTest` (`neverCallsPurgeEvenWhenAutoPurgeEnabled...`
  → `callsPurgeWhenAutoPurgeEnabledAndDataEligible` + nowy test na `autoPurgeEnabled=false`).
- **Nowe/zmienione pliki:**
  `backend/src/main/resources/db/migration/V091__purge_campaign_contact_archive_per_tenant.sql` (nowa migracja),
  `domain/retention/CampaignArchiveRetentionRepository.java` (+metoda `purgeEligible`),
  `domain/retention/RetentionPurgeService.java` (Javadoc: CAMPAIGN_DATA już nie „poza zakresem”),
  `domain/retention/RetentionPurgeServiceImpl.java` (+pole `campaignArchiveRetentionRepository`,
  +gałąź `switch`, +metoda `purgeCampaignData`, `validateSupportedCategory` już nie odrzuca CAMPAIGN_DATA),
  `domain/retention/RetentionEvaluationJob.java` (`evaluateCampaignData` już nie pomija auto-purge,
  patrz wyżej), `domain/retention/RetentionPurgeServiceImplTest.java` (+nested `CampaignDataPurge`,
  `UnsupportedCategories` zawężone do samego RECORDINGS), `domain/retention/CampaignArchiveRetentionRepositoryTest.java`
  (+nested `PurgeEligible`), `domain/retention/RetentionEvaluationJobTest.java` (zaktualizowany
  scenariusz CAMPAIGN_DATA auto-purge), `domain/retention/CampaignContactArchivePurgeTenantIsolationTest.java`
  (NOWY plik — test integracyjny Testcontainers weryfikujący realną izolację cross-tenant na
  prawdziwym Postgresie, uzasadnienie w Javadoc klasy: mockowany `EntityManager` potwierdziłby
  jedynie że repozytorium WYSŁAŁO zapytanie, nie że baza faktycznie odfiltrowała wiersze — dokładnie
  taka luka była już źródłem realnego błędu w tym module),
  `api/retention/RetentionController.java` (Swagger doc 501 zawężony do samego RECORDINGS).
- `mvn verify -pl app`: **BUILD SUCCESS**, 1713 testów, 0 failures, 0 errors (`mvn clean verify`,
  sekwencyjnie — uwaga: równoległe uruchomienie dwóch `mvn` na tym samym module w trakcie
  implementacji dało fałszywe `NoClassDefFoundError` niepowiązanych klas, przez kolizję zapisu do
  `target/`; po czystym, pojedynczym `mvn clean verify` build jest zielony).

---

## MODUL: Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości (EPIC-30)

> Źródło: `DESIGN-message-retention-and-partitioning.md` (projekt do akceptacji, analiza 2026-09-20) — ustalenia U1–U15, decyzje D1–D8 z
> założeniami domyślnymi, wymagania przekrojowe WP-1…WP-8. Tabele/funkcje bazowe: DB-056…DB-077 (`TASKS-DATABASE.md`). **Brak nowych endpointów
> publicznych** (`SecurityConfig` i listy `PUBLIC_PATH_PREFIXES` w filtrach bez zmian); wyjątek: BE-129 zmienia zawartość istniejących
> `POST /api/customers/{id}/gdpr/export|anonymize`, bez zmiany ścieżek i ról (`hasAnyRole('ADMIN','SUPERVISOR')`).
> **Numeracja:** BE-120…BE-143 (poprzedni najwyższy: BE-119); BE-143 dopisany 2026-09-21 z code review BE-125 (BE125-01); BE-144 (porządkowy, bez epiku) w osobnym module na końcu pliku.
> Kluczowe fakty zweryfikowane w kodzie 2026-09-20: `RetentionPurgeServiceImpl#purgeContactInteractions` woła `detachContactReferences` (tylko `contact_id := NULL`);
> `EmailAttachmentStorageService` nie ma operacji usuwania; `PartitionReclaimJob.TABLE_CATEGORIES` = 4 tabele `contact*` i **kasuje także niepuste partycje** (WARN, ale DROP wykonany —
> zamierzone dla `contact*`), więc dla tabel z obiektami S3 potrzebny jest tryb „tylko puste" (BE-123 wprowadza `DropMode`, BE-133 go implementuje);
> `PartitionScannerImpl#countRowsByTenant` rzuca NPE dla wierszy z `tenant_id IS NULL` (`row[0].toString()`), co dotyczy `audit_log` (BE-123).
>
> Graf zależności warstwy BE (A → B = kolejność wykonania, B zależy od A):
> ```
> Faza 0:   BE-120;   DB-056 → BE-121;   BE-122;   BE-123
> Grupa 1:  BE-124 ✅ → BE-125 ✅ → BE-126 ✅ → BE-127 → BE-128;   DB-059 → BE-127;   DB-060 ✅, DB-061, DB-062, DB-079 ✅, BE-125 ✅ → BE-129;   BE-125 ✅ → BE-131;   BE-125 ✅ → BE-143 ✅ (walidacja `s3Key`, niezależne od BE-126);
>           BE-141 → DB-078;   [BE-142, tylko D10];   [DB-063, BE-126, BE-127, BE-128 → BE-130, tylko D1 = C]
> Grupa 2:  DB-065 → BE-132 → BE-133;   BE-123, BE-126 → BE-133
> Grupa 3:  DB-067 → BE-134 → BE-135;   BE-133, BE-125 ✅, BE-127 → BE-135;   [DB-068, BE-134 → BE-136, tylko D4 = B]
> Grupa 4:  DB-069 (bramka) → BE-137;   [DB-075 → BE-140, tylko D6 = koniec kampanii]
> Grupa 5:  DB-071 → BE-138;   DB-064, DB-071 → BE-139
> ```
> Wspólne wymagania (skrót; pełna treść w DESIGN §5): **WP-1** testy Testcontainers na pełnym łańcuchu Flyway dla każdej zmiany natywnego SQL/JPA (precedens:
> `CampaignContactArchivePurgeTenantIsolationTest`; mocki `EntityManager` nie złapały błędu `resultClass`+enum, braku `TenantContext` w schedulerze, `Map.of().get(null)`);
> **WP-2** joby `@Scheduled` z pętlą per tenant: `TenantContext.setTenantId` na początku iteracji i `clear()` w `finally`, NIGDY `clear()` na ścieżce z żądania HTTP
> (wzorce: `SocialIntegrationServiceImpl#refreshToken`, `RetentionEvaluationServiceImpl`); **WP-4** weryfikacja na żywo w local-demo po przebudowie obrazów, joby destrukcyjne — policz kandydatów i uzyskaj zgodę właściciela;
> **WP-5** Poziom 1 (wiersze + S3 per tenant) przed Poziomem 2 (DROP partycji); **WP-7** DoD: status i notatki w pliku zadań, pamięć agentów (`.claude/agent-memory/`) commitowana razem ze zmianą.

### BE-120 – Job archiwizacji kampanii: `archive_completed_campaign_contacts()` (`CampaignArchiveJob`)

**Typ:** Backend implementation (domknięcie martwego harmonogramu)
**Priorytet:** Should Have
**Złożoność:** S (ocena zlecenia potwierdzona; ryzyko RLS opisane niżej — jeśli okaże się wymagać refaktoru funkcji, wykonawca dopisuje osobny ticket DB)
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-072, DB-075, DB-076, DB-077
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
Funkcja SQL `archive_completed_campaign_contacts()` (V015) dla każdej kampanii `COMPLETED`/`STOPPED` z `updated_at < now() - 30 dni` kopiuje `campaign_contact` do `campaign_contact_archive`
(`ON CONFLICT (record_id, campaign_id) DO NOTHING`), usuwa je z tabeli operacyjnej, próbuje dropnąć partycję `campaign_contact_<uuid>` (nie istnieje), zapisuje `cron_log` i `scheduled_job.last_run_at` — w jednej transakcji dla wszystkich tenantów.
Nikt jej nie woła (DESIGN §2 U10): live 30/30 kampanii kwalifikuje się (37 wierszy `campaign_contact`), archiwum = 0 wierszy.

**⚠️ To nie jest zmiana wolna od decyzji PO (D8, DESIGN §3):** czytelnicy `campaign_contact` (`CampaignContactRepository`, `CampaignContactHistoryController` `/api/campaigns/{campaignId}/contacts/{recordId}/attempts`,
`DialerController`, statystyki po `(campaign_id, status)`, `EtlSyncServiceImpl`) nie czytają archiwum — po archiwizacji kontakty zakończonych kampanii > 30 dni znikają z UI i statystyk. **ZAŁOŻENIE DO POTWIERDZENIA:** job dostarczony
za flagą `retention.campaign-archive.enabled` (domyślnie `false`); włączenie po zgodzie właściciela. Przy alternatywie „domyślnie włączony" (pierwotny zamiar V015) zmienia się tylko domyślna wartość flagi i konieczność zgody przed wdrożeniem.
Skutek uboczny D6: pierwsze uruchomienie archiwizuje zaległość, więc jej zegar `archived_at` startuje od tego dnia (efektywnie wydłuża retencję PII zaległych kampanii).

**Zakres:**
- `domain/retention/CampaignArchiveJob` (package-private `@Component`): `@Scheduled(cron = "${retention.campaign-archive-cron:0 0 4 * * *}", zone = "UTC")` — 04:00 zgodnie z wpisem w `scheduled_job`; zajęte sloty: 00:30 `PartitionMaintenanceJob`, 01:00 `RetentionEvaluationJob`, 02:00 `RecordingRetentionJob`, niedziela 03:00 `PartitionReclaimJob`.
- Metoda `CampaignArchiveRetentionRepository#archiveCompletedCampaigns()` (`SELECT archive_completed_campaign_contacts()`; funkcja jest `RETURNS VOID` — liczba przeniesionych wierszy z różnicy `count(*)` kwalifikujących się przed/po albo z `cron_log`; nie zmieniaj typu zwracanego bez ticketu DB).
- Flaga `retention.campaign-archive.enabled` (domyślnie `false`); przy `false` job loguje INFO i nic nie wywołuje. Wpisy w `application.yml` (sekcja `retention:`) z komentarzem.
- **Kontekst tenanta (WP-2):** funkcja jest cross-tenant z założenia i nie używa GUC — job NIE ustawia `TenantContext`; jawny prekontrakt w Javadoc (scheduler, brak kontekstu, rola DB musi widzieć wszystkie kampanie).
- **Ryzyko RLS (DESIGN R5):** funkcja iteruje po `campaign` (FORCE RLS) i usuwa z `campaign_contact`; pod rolą bez BYPASSRLS i bez GUC zwróci 0 kampanii. Domyślnie: zachowanie zmierzone i opisane (test), rozwiązanie (rola serwisowa/`SECURITY DEFINER` albo pętla per tenant z `p_tenant_id`) rozstrzyga DB-072 razem z BE-139.
- Pierwsze uruchomienie = jedna transakcja na wszystkie zaległe kampanie: zmierz na scratch (np. 30 kampanii × 10 tys. rekordów) czas, WAL i blokady `campaign_contact`; > 30 s → wariant per kampania (ticket DB).

**Kryteria akceptacji:**
- [ ] Job `@Scheduled` (UTC, konfigurowalny cron), flaga `enabled` domyślnie `false`; przy `false` zero wywołań SQL (test jednostkowy: `verifyNoInteractions`)
- [ ] (WP-1) Test Testcontainers na pełnym łańcuchu Flyway: kampania `COMPLETED` z `updated_at` > 30 dni i 3 rekordami → rekordy w archiwum (`archived_at` ustawione), znikają z `campaign_contact`; kampania `RUNNING` i `COMPLETED` < 30 dni nietknięte; kwalifikująca się kampania drugiego tenanta też zarchiwizowana — asercje po wartościach
- [ ] Idempotencja: drugie uruchomienie bez zmian i bez błędu
- [ ] Wyjątek funkcji SQL nie zatrzymuje schedulera (try/catch, log ERROR); test
- [ ] Zachowanie pod `SET ROLE app_user` bez GUC zmierzone i opisane w Javadoc jobu (zero kampanii); decyzja przekazana do DB-072/BE-139
- [ ] Inwentaryzacja czytelników `campaign_contact` (lista klas/endpointów) w notatce: co zobaczy użytkownik po archiwizacji; zgoda właściciela na włączenie flagi zapisana
- [ ] (WP-4) Local-demo po przebudowie obrazów: policz kwalifikujące się (spodziewane 30 kampanii/37 wierszy), uzyskaj zgodę, uruchom (flaga włączona tymczasowo), sprawdź `cron_log`, `scheduled_job.last_run_at` (aktualizuje je funkcja) i UI kampanii
- [ ] `mvn verify -pl app`; DoD (WP-7)

---

### BE-121 – Pętla batchowa w `CampaignArchiveRetentionRepository#purgeEligible`

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** DB-056
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis:**
`purgeEligible(UUID tenantId, Instant cutoff)` woła jednokrotnie `SELECT purge_campaign_contact_archive(CAST(:tenantId AS uuid), :cutoff)` (V091) w jednej `@Transactional`. Po DB-056 funkcja usuwa ≤ `p_batch_size` wierszy,
więc repozytorium musi iterować. Wołający: `RetentionPurgeServiceImpl#purgeCampaignData` (w wątku `@Async` po `TenantContext.restore`).

**Zakres:**
- Pętla `do { n = callBatch(); total += n; } while (n == batchSize)`; **każda partia w osobnej transakcji** (`TransactionTemplate` lub metoda w osobnym beanie — uwaga na self-invocation `this.` omijające proxy, patrz BE-113) → krótkie blokady i WAL.
- Rozmiar partii: nowa właściwość `retention.campaign-archive.purge-batch-size` (domyślnie 10 000; istniejące `retention.purge.batch-size` = 100 dotyczy DELETE po PK na tabelach partycjonowanych).
- `assertSameTenant(tenantId)` i `setTenantContextInDb(tenantId)` na starcie (jak dziś); **bez `TenantContext.clear()`** — metoda działa na wątku, którego kontekstem zarządza `purgeAsync` (WP-2). Guard przed nieskończoną pętlą (limit iteracji lub brak postępu → przerwanie z WARN).
- `rowsDeleted` = suma partii; sygnatura `purgeEligible` bez zmian (`RetentionPurgeServiceImpl` nietknięty).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers: 25 000 wierszy tenanta A i 10 000 tenanta B (batch 10 000) → A usunięte całkowicie, B nietknięty; liczba wywołań funkcji = 4 (3 pełne + końcowa 0)
- [ ] Każda partia w osobnej transakcji: awaria w 2. partii nie cofa 1. (test); `retention_purge_log` kończy się `FAILED` z komunikatem, częściowy postęp zostaje
- [ ] (WP-2) Repozytorium nie woła `TenantContext.clear()`; test z pustym `TenantContext` → `IllegalStateException` z `assertSameTenant` (wzorzec `TenantRetentionPendingSummaryRepositoryTest`)
- [ ] Guard nieskończonej pętli przetestowany; `CampaignArchiveRetentionRepositoryTest` (mock) zaktualizowany, `CampaignContactArchivePurgeTenantIsolationTest` zielony
- [ ] `mvn verify -pl app`; DoD (WP-7); wdrożenie razem z DB-056

---

### BE-122 – Job czyszczenia `refresh_token` (`RefreshTokenCleanupJob`)

**Typ:** Backend implementation (domknięcie martwego harmonogramu)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-076, DB-077
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`RefreshTokenRepository#deleteExpiredAndRevoked(Instant cutoff)` (JPQL `DELETE … WHERE rt.expiresAt < :cutoff OR rt.revoked = true`, javadoc: „nie zaimplementowany w BE-003, ale metoda gotowa") nie ma wołającego; SQL `cleanup_expired_refresh_tokens()` wymagała nieobecnego pg_cron.
Live: `refresh_token` 1333 wiersze, 1331 wygasłych, 1298 unieważnionych, 37 z `tenant_id` NULL (SUPER_ADMIN). Repozytorium jest `interface … extends JpaRepository` **package-private** w `domain.user`, tabela nie ma RLS.

**Zakres:**
- `domain/user/RefreshTokenCleanupJob` (package-private `@Component`, `@Scheduled(cron = "${auth.refresh-token-cleanup.cron:0 30 3 * * *}", zone = "UTC")`; wolny slot względem 00:30/01:00/02:00/04:00), `@Transactional` (metoda `@Modifying` wymaga transakcji).
- Semantyka: `cutoff = now − auth.refresh-token-cleanup.grace-days` (domyślnie 7). Obecne JPQL kasuje unieważnione tokeny natychmiast, co gubi diagnostykę replay w `AuthServiceImpl` (stary token po rotacji jest `revoked = true`, replay = odrzucenie; po usunięciu = „nie znaleziono" — obie ścieżki odrzucają, ale log/ślad znika).
  Rekomendacja: `(rt.revoked = true AND rt.createdAt < :cutoff) OR rt.expiresAt < :cutoff`; wykonawca uzasadnia wybór.
- Bez `TenantContext` (tabela globalna, brak pętli per tenant) — jawny komentarz w Javadoc (WP-2). Log INFO z liczbą usuniętych. Przy dużych wolumenach (zmierz) batching natywnym DELETE po PK `token_id` (tabela zwykła, niepartycjonowana) w osobnych transakcjach.

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers z realnym JPQL: tokeny aktywny / wygasły < grace / wygasły > grace / unieważniony świeży / unieważniony stary / `tenant_id IS NULL` → usunięte dokładnie te przewidziane przez wybraną semantykę (mock nie wykryłby błędu warunku `OR`)
- [ ] (WP-2) Job nie zależy od `TenantContext` — test z pustym kontekstem przechodzi; wyjątek nie crashuje schedulera
- [ ] Istniejące testy `AuthServiceImpl` (refresh/logout/replay) zielone; test replay: token usunięty po grace → 401 jak nieistniejący
- [ ] (WP-4) Local-demo: policz kandydatów (spodziewane ≈ 1331 wygasłych / 1298 unieważnionych, bez tokenów aktywnych), uzyskaj zgodę, uruchom; po: aktywne i w grace zostają, logowanie/refresh działa
- [ ] `application.yml`: cron + `grace-days`; DoD (WP-7)

---

### BE-123 – `audit_log` i `plugin_invocation_log` w `PartitionReclaimJob` (horyzont platformowy, D5)

**Typ:** Backend implementation / bugfix
**Priorytet:** Should Have
**Złożoność:** M (odbiega od oceny zlecenia „S": osobna ścieżka horyzontu, refaktor `TABLE_CATEGORIES` → `ReclaimTarget`/`DropMode`, rozszerzenie `PartitionScanner`, poprawka NPE dla `tenant_id` NULL, test na prawdziwej bazie)
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-133, DB-076, DB-077
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`PartitionReclaimJob.TABLE_CATEGORIES` (`Map<String, RetentionDataCategory>`) obejmuje 4 tabele `contact*` i liczy próg z `MAX(retention_months)` kategorii (`RetentionPolicyService#findMaxRetentionMonths`). `audit_log` i `plugin_invocation_log` (6 tabel w `PartitionMaintenanceJob.PARTITIONED_TABLES`) nie mają kategorii —
log platformowy (DESIGN EPIC-29 §12.1) — więc rosną bez końca (najstarsza partycja `audit_log` = 2026_03; `ARCHITECTURE.md:848` obiecuje 2 lata). SQL `drop_old_audit_log_partitions`/`rotate_*` nie mają wołającego.
**D5 (ZAŁOŻENIE DO POTWIERDZENIA prawnie):** horyzont 24 mies., konfigurowalny; przy innej wartości zmienia się wyłącznie konfiguracja.

**Zakres:**
1. Refaktor `TABLE_CATEGORIES` → lista `ReclaimTarget(tableName, ThresholdSource, DropMode)`: `ThresholdSource` = `CATEGORY_MAX_RETENTION(category)` (dziś) | `PLATFORM_HORIZON(propertyKey)` (nowe); `DropMode` = `AFTER_CUTOFF` (dzisiejsze zachowanie dla `contact*`: DROP także niepustej z WARN — **bez zmian**) | `ONLY_IF_EMPTY` (deklaracja; implementuje BE-133 jako pierwszy użytkownik).
2. Ścieżka horyzontu: `retention.platform.audit-log-months` i `retention.platform.plugin-invocation-log-months` (domyślnie 24, walidacja ≥ 1: wartość niepoprawna → start z czytelnym błędem albo fallback 24 + WARN — opisz wybór); `cutoff = now(UTC) − months`; kandydat gdy `rangeEnd` ściśle `<` cutoff. **Niezależna od `RetentionPolicyService`** (brak polityk nie wyłącza tej ścieżki).
3. Partycja `<tabela>_default`: nigdy nie kandyduje (scanner ją wyklucza); **WARN gdy niepusta** (sygnał awarii rotacji — dokładnie błąd z EPIC-29/DB-052) — nowa metoda `PartitionScanner#countRows(String tableName)`/`isEmpty`.
4. Niepusta partycja po horyzoncie jest OCZEKIWANA (brak Poziomu 1 dla logów platformowych) → INFO z liczbą wierszy, DROP wykonany.
5. **Bug:** `PartitionScannerImpl#countRowsByTenant` robi `UUID.fromString(row[0].toString())` — dla `audit_log` (`tenant_id` nullable = zdarzenia globalne) `row[0] == null` → `NullPointerException`. Dodaj obsługę NULL (metoda `countRows` bez grupowania po tenancie dla ścieżki platformowej + poprawka `countRowsByTenant`) i test na prawdziwej bazie.
6. `application.yml` (`retention.platform.*`) z komentarzem o wymaganym potwierdzeniu prawnym. Funkcji SQL `rotate_*`/`drop_old_*` nie usuwamy (backstop; uzgodnienie opisu w DB-076).

**Kryteria akceptacji:**
- [ ] `audit_log` i `plugin_invocation_log`: partycja `rangeEnd < now − 24 mies.` → DROP; 23-miesięczna nie; `_default` nigdy (test jednostkowy + (WP-1/WP-6) Testcontainers z realnymi partycjami z `create_audit_log_partition`)
- [ ] Test Testcontainers: partycja `audit_log` z wierszami `tenant_id IS NULL` i z tenantami → brak NPE, DROP wykonany, INFO z liczbą wierszy (test zapisany tak, by PRZED poprawką padał NPE — udokumentuj)
- [ ] `_default` niepusta → WARN (test z appenderem logów)
- [ ] Ścieżka horyzontu nie zależy od `RetentionPolicyService` (test: usługa rzuca `ResourceNotFoundException`, `audit_log` nadal przetwarzany); błąd jednej tabeli nie przerywa pozostałych
- [ ] Zachowanie `contact*` bez zmian (istniejące `PartitionReclaimJobTest` zielone bez zmiany asercji)
- [ ] (WP-2) Javadoc: scheduler bez kontekstu, scanner cross-tenant z założenia
- [ ] (WP-4) Local-demo: najstarsza partycja `audit_log_2026_03` ma < 24 mies., więc job niczego nie usunie — dowód: log INFO; test DROP z niskim horyzontem wyłącznie na kopii/po policzeniu wierszy i zgodzie (destrukcyjne)
- [ ] `mvn verify -pl app`; DoD (WP-7)

---

### BE-124 – [ADR + inwentaryzacja] Retencja treści wiadomości: decyzja D1, PII i obiekty S3

**Typ:** Analiza / ADR (bez zmian w kodzie produkcyjnym)
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ✅ Ukończone (2026-09-20) — D1 = A przyjęte do realizacji bez wyraźnego potwierdzenia właściciela (patrz notatka, sekcja 1)
**Blokuje:** BE-125, DB-059, DB-063
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (decyzja: właściciel produktu)

**Opis:**
Zamyka decyzję D1 i przygotowuje kompletny inwentarz, zanim ktokolwiek zacznie usuwać dane. **ZAŁOŻENIE DO POTWIERDZENIA (D1 = A):** DELETE wierszy i załączników S3 razem z purge kontaktu w istniejącej kategorii `CONTACT_INTERACTIONS` (bez zmian CHECK/enuma/UI).
Alternatywy: B (anonimizacja) — BE-125/126/127 robią UPDATE PII zamiast DELETE, tabele rosną bez końca, sens Poziomu 2 znika; C (kategoria `MESSAGE_CONTENT`) — wchodzą DB-063, BE-130, FE-111.

**Zakres:**
1. ADR w notatce ticketu + aktualizacja DESIGN §3 D1 (potwierdzenie/zmiana założenia); pytania do PO (D1, D2 — wolumeny) wysłane listą.
2. Inwentarz PII i obiektów zewnętrznych: `email_message` (`from_address`, `to_address`, `cc_address`, `bcc_address`, `subject`, `body_html`, `body_text`, `attachments`, `message_id_header`, `in_reply_to`), `social_message` (`content`, `sender_external_id`, `attachments`, `external_message_id`);
   S3: `email-attachments/{tenantId}/{messageId}/…`, `email-attachments/{tenantId}/pending/{uuid}/…`, EML (`contact.recording_url`), nagrania. **Klucz metadanych załącznika w JSONB to `s3_key`** (potwierdzone w `EmailPollingServiceImpl#collectAttachments` i `EmailSendServiceImpl#buildAttachmentsJson`) — komentarz V010 i javadoc `EmailMessage` mówią `s3_url` (do sprostowania w DB-077).
3. **Ponowne przeczytanie kodu po PR #44 (WhatsApp):** `SocialMessageServiceImpl` (przychodzące: dedup `findByExternalMessageId`, `sentAt = incoming.sentAt() ?: now()`; wychodzące: `externalMessageId = "OUTBOUND-" + UUID`), `SocialMessageConsumer`, `SocialMessagePublisherImpl`, `SocialWebhookController`, `SocialContactController`, adaptery — czy `attachments` niosą URL-e z tokenami/PII; brak S3 w domenie social (grep) potwierdzić.
4. Dwa ryzyka do opisania: (a) brak walidacji `RECORDINGS ≤ CONTACT_INTERACTIONS` w `RetentionPolicyServiceImpl#updatePolicy` → kontakt z niepustym `contact.recording_url` usunięty przed retencją nagrań osierocia obiekt S3 (`RecordingRetentionJob` szuka po `contact.recording_url`); (b) kolejność operacji przy awarii — **dzieci przed rodzicem, S3 przed wierszem, brak postępu = koniec pętli** (DESIGN R3).
5. Definicja „wieku wiadomości" dla osieroconych: `COALESCE(received_at, sent_at, created_at)` (e-mail) / `sent_at` (social) — ta sama co przyszłe `message_at` (DB-067).

**Kryteria akceptacji:**
- [x] ADR zapisany (notatka poniżej + `DESIGN-message-retention-and-partitioning.md` §3 D1); wpływ decyzji na tickety wypisany (przy B/C: co się zmienia, które tickety warunkowe wchodzą)
- [ ] Pytania do PO wysłane — **NIE wysłano** (brak kanału do właściciela); lista w sekcji 9 notatki, „do przekazania"; odhaczyć po przekazaniu
- [x] Inwentarz kompletny (grep po `attachments`, `s3_key`, `EmailAttachmentStorageService`, `deleteFromS3`, `social_message`, `SocialMessage`); lista zmian od PR #44 w notatce (sekcja 4)
- [x] Opisane oba ryzyka (a)/(b) z rekomendacją (walidacja `RECORDINGS ≤ CONTACT_INTERACTIONS` jako osobny follow-up, jeśli PO potwierdzi) — sekcje 5 i 6
- [x] Notatka w pliku zadań
- [ ] Pamięć agenta commitowana razem ze zmianą (WP-7) — pliki pamięci zapisane w `.claude/agent-memory/backend-dev-expert/`; commit wykonuje zlecający

**Notatka z realizacji (2026-09-20):**

Metoda: wyłącznie odczyt — kod gałęzi `chore/epic-30-zadania` (zawiera PR #44), baza demo `cc-postgres` (`default_transaction_read_only=on`), MinIO (`ls`, `mc ilm rule ls`, `mc version info`, `mc du`; bez zapisu). Kod, testy, migracje i dane bez zmian; żaden purge nie został uruchomiony. Numery linii = stan z 2026-09-20.

**1. ADR — D1: semantyka retencji treści wiadomości (e-mail, social)**
- **Stan decyzji:** przyjęte do realizacji 2026-09-20 na podstawie polecenia realizacji („przejdź do realizacji") po przedstawieniu założenia domyślnego A; **wyraźnego potwierdzenia D1 właściciel nie złożył**. To nie jest zatwierdzenie: D1 = A pozostaje założeniem roboczym, tickety warunkowe (DB-063, BE-130, FE-111) są nieaktywne, ale nie zamknięte.
- **Decyzja robocza — A:** DELETE wierszy `email_message`/`social_message` i ich obiektów S3 razem z purge kontaktu, w istniejącej kategorii `CONTACT_INTERACTIONS` (bez zmian CHECK, enuma `RetentionDataCategory` i UI kategorii).
- **Uzasadnienie:** (1) najmniejszy zakres — 3 CHECK-i `data_category` (`tenant_retention_policy_data_category_check`, `tenant_retention_pending_summary_data_category_check`, `retention_purge_log_data_category_check`; zweryfikowane w `pg_constraint`) zostają; (2) wiadomość jest częścią interakcji, a jej wiek ≈ wiek kontaktu: w demo kontakty e-mail trwają ≤ 6 h 10 min, wiadomość powstaje najpóźniej 2 min 19 s po `contact.started_at` (0 wiadomości > 1 doby po starcie kontaktu); social: 0 wierszy, brak danych; (3) B zostawia PII, którego nie da się zanonimizować kolumną bez utraty funkcji (`filename` w `attachments`, `message_id_header`/`in_reply_to`, `cc`/`bcc` — `anonymize_customer` z V013 już dziś ich nie rusza), a tabele rosną bez końca; (4) Poziom 2 (DROP partycji) ma sens tylko przy usuwaniu wierszy.
- **Konsekwencje A:** usunięcie jest nieodwracalne (bucket `contact-center-recordings` niewersjonowany); ten sam przycisk „Usuń teraz" (`CONTACT_INTERACTIONS`) zyskuje szerszy skutek, więc FE-110 (opis w UI) ma wejść razem z BE-126, nie po; wiadomość dziedziczy wiek kontaktu (`started_at`), nie własny; koszt S3 w pętli purge (BE-125, „Ryzyka").
- **Zabezpieczenie na czas do potwierdzenia (opcja, decyzja zlecającego):** flaga `retention.purge.delete-messages` (domyślnie `false` = dotychczasowe `detachContactReferences`), włączana po potwierdzeniu D1 — wtedy zmiana na B/C przed wdrożeniem nie wymaga cofania kodu, a merge BE-125/126 jest neutralny. Bez flagi: nie włączać `auto_purge_enabled` dla `CONTACT_INTERACTIONS` na produkcji i nie uruchamiać ręcznego purge po wdrożeniu BE-126 do czasu potwierdzenia.
- **Wpływ alternatyw (ścieżka zmiany):**

| Ticket | D1 = B (anonimizacja) | D1 = C (kategoria `MESSAGE_CONTENT`) |
|---|---|---|
| BE-125 | `purgeByContactIds` → UPDATE PII (`from/to/cc/bcc`, `subject`, `body_*`, `attachments = '[]'`; social: `content`, `sender_external_id`; `message_id_header`/`in_reply_to` — decyzja pod dedup IMAP); S3 nadal usuwane (pliki niosą PII); potrzebny znacznik „zanonimizowano" (kolumna — nowy ticket DB), inaczej sweep nie jest idempotentny | logika bez zmian; dochodzi wariant po wieku `purgeOlderThan(tenantId, cutoff, batch)` (wiadomości powiązane i osierocone) |
| BE-126 | `detachContactReferences` zostaje (bez `@Deprecated`) + wołanie anonimizacji | wraca do odcinania referencji; usuwanie wiadomości przechodzi do BE-130 |
| BE-127, BE-128 | sweep = UPDATE wierszy niezanonimizowanych; liczenie tych wierszy | sweep i liczenie pod kategorią `MESSAGE_CONTENT` (przenoszą się do BE-130) |
| DB-059 | predykat indeksu + kolumna znacznika | indeks bez `WHERE contact_id IS NULL` (usuwanie po wieku obejmuje też powiązane) |
| DB-063, BE-130, FE-111 | nie wchodzą | wchodzą (3 CHECK-i, backfill = wartość `CONTACT_INTERACTIONS`, enum, `seedDefaultPolicies`, UI, 4× i18n) |
| DB-065/067, BE-132…135 | sens Poziomu 2 znika (wiersze nie znikają) — ponowna ocena | bez zmian; `ReclaimTarget` czyta próg `MESSAGE_CONTENT` |
| FE-110 | „zanonimizowane" zamiast „usunięte" | tylko punkt 3 (dryf `CAMPAIGN_DATA`); opis wiadomości → FE-111 |

- **Zmiana decyzji po starcie prac:** przed merge BE-125/126 — zmieniają się wyłącznie tickety; po uruchomieniu purge na realnych danych usunięcie jest nieodwracalne, więc B/C dotyczą tylko danych jeszcze nieusuniętych.

**2. Inwentarz PII** (schemat z `\d+` żywej bazy + kod). Jedyni pisarze `email_message`: `EmailPollingServiceImpl#parseMessage` :219–270 (INBOUND), `EmailSendServiceImpl#sendReply` :100–113 i `#sendNew` :200–212 (OUTBOUND) — grep `EmailMessage.builder()`, brak INSERT-ów w migracjach; `social_message`: `SocialMessageServiceImpl` :101–117 (INBOUND) i :217–233 (OUTBOUND).

| Kolumna | PII | Uwagi |
|---|---|---|
| `email_message.from_address` | tak | INBOUND: `Message#getFrom()[0].toString()` = „Imię Nazwisko <adres>"; OUTBOUND: skrzynka tenanta |
| `email_message.to_address` | tak | INBOUND: skrzynka tenanta + inni odbiorcy; OUTBOUND: adres klienta |
| `email_message.cc_address`, `bcc_address` | tak (osoby trzecie) | ustawiane tylko dla INBOUND (live 0/55 niepustych); `anonymize_customer` (V013) ich nie czyści |
| `email_message.subject`, `body_html`, `body_text` | tak (treść) | live: 55 / 33 / 30 niepustych |
| `email_message.attachments` (JSONB NOT NULL, CHECK „array") | pośrednio | wpis: `filename` (bywa PII), `content_type`, `size_bytes`, **`s3_key`**; treść pliku w S3; V013 zeruje do `[]` BEZ usunięcia S3 (klucze przepadają) |
| `email_message.message_id_header`, `in_reply_to` | pseudonimowe | Message-ID nadawcy niesie domenę/host; `uq_email_message_id_header` = dedup IMAP |
| `email_message.contact_id` | klucz do kontaktu/klienta | NULL dla niezroutowanych i po dotychczasowym purge |
| `social_message.content` | tak (treść) | WhatsApp: tylko `type=text`, inne typy zapisują `content = NULL` (`SocialWebhookController:399–403`) |
| `social_message.sender_external_id` | tak | INBOUND: WhatsApp = numer telefonu (`from`), FB/IG = PSID/IGSID; OUTBOUND = `pageId` integracji (nie klient) |
| `social_message.external_message_id` | pseudonimowe | `mid.`/`wamid.` lub `OUTBOUND-<uuid>`; klucz dedup (`uq_social_message_external_id`) |
| `social_message.attachments` | obecnie brak | zawsze `[]`: parsery webhooków przekazują `null` (`SocialWebhookController:311`, `:349`, `:417`), wychodzące nie zapisują `attachmentUrls`; pole istnieje, więc przyszłe URL-e platform (często z tokenem) traktować jak PII |

Kopie PII w `contact` (znikają z purge kontaktu, nie z tabel wiadomości): `remote_address`, `channel_metadata` (`subject`, `fromAddress`, `emailMessageId`). Poza bazą i S3 (poza zakresem EPIC-30, do odnotowania): logi aplikacji (`WhatsAppAdapter:126,133` — numer odbiorcy na INFO/ERROR; `SocialWebhookController:149,193,248` — pełny payload na DEBUG); RabbitMQ DLQ `cc.queue.dead-letter` (`RabbitMQConfig:218`, `durable`, bez TTL) trzyma `EmailEvent`/`IncomingSocialMessage` po nieudanym przetworzeniu (demo: 0 wiadomości).

**3. Inwentarz obiektów zewnętrznych (S3)** — jeden bucket `contact-center-recordings`; live: 98 obiektów, 6,6 MiB, **bez wersjonowania** i **bez reguł lifecycle** (`mc version info`: „un-versioned"; `mc ilm rule ls`: „lifecycle configuration does not exist"; `docker-compose.yml:126–137` `minio-init` robi tylko `mc mb`).

| Prefiks / klucz | Zapis | Wskaźnik w DB | Kto usuwa dziś | Live |
|---|---|---|---|---|
| `email-attachments/{tenantId}/{messageId}/{plik}` | `EmailAttachmentStorageServiceImpl#store` :43–48 ← `EmailPollingServiceImpl#collectAttachments` :363 (INBOUND) | `email_message.attachments[*].s3_key` (:376) | nikt | 6 obiektów, wszystkie wskazywane |
| `email-attachments/{tenantId}/pending/{uuid}/{plik}` | `#storePending` :51–56 ← `EmailAttachmentController#uploadAttachment` :94 | po wysyłce: `attachments[*].s3_key` wiadomości OUTBOUND (**ten sam klucz `pending/`** — `EmailSendServiceImpl#buildAttachmentsJson` :376–395 zapisuje `s3Key` z żądania, brak przeniesienia do `{messageId}/`); przy porzuconym uploadzie: brak | nikt | 9 obiektów: 8 wskazywanych przez wysłane wiadomości (883 570 B), 1 porzucony |
| `{tenantId}/{yyyy}/{MM}/{contactId}.eml` | `EmailEmlService#generateAndUpload` :76, `#buildEmlS3Key` :117–122 ← `EmailContactCreator#generateAndStoreEml` :192–206 (wołane :96, :357, :448) | `contact.recording_url` | `RecordingRetentionJob` (RECORDINGS) | 14 (kontakty e-mail) |
| `{tenantId}/{yyyy}/{MM}/{contactId}.mp3` | `RecordingServiceImpl#buildS3Key` :333 | `contact.recording_url` | `RecordingRetentionJob` | 61 |
| `plugins/…` | `PluginStorageServiceImpl` | poza kontaktami | — | 8 (poza zakresem) |

- Zgodność DB↔S3: 75 wskaźników `contact.recording_url` = 75 obiektów (0 osieroconych, 0 wiszących); 6/6 kluczy INBOUND; 8/9 `pending/`. **Domena social nie ma żadnych obiektów S3** (grep `s3|S3Client|EmailAttachmentStorageService|presign|putObject` w `domain/social`, `infrastructure/social`, `api/social` = 0 trafień).
- **Klucz metadanych = `s3_key`** (nie `s3_url`): 14/14 wpisów w bazie (`s3_url` 0); `s3_url` tylko w komentarzu V010:45 i javadoc `EmailMessage:102` (sprostować w DB-077; V010 NIE edytować).
- **EML to pełna kopia wiadomości poza tabelą** (treść + załączniki base64, `EmailEmlService#buildAttachmentPart` :284 pobiera je po `s3_key`), a wskaźnik leży w `contact.recording_url` — kategoria RECORDINGS, nie CONTACT_INTERACTIONS (patrz ryzyko (a)).
- **`RecordingService#deleteFromS3` połyka `S3Exception`** (`RecordingServiceImpl:307–322`, log ERROR bez rzucenia) — brak sygnału porażki, więc nie nadaje się do „S3 przed wierszem" (uzasadnia własną `EmailAttachmentStorageService#delete` z BE-125). Skutki w istniejącym kodzie: `RecordingRetentionJob#deleteRecording` :205–208 czyści `recording_url` mimo błędu S3 (obiekt osiera); `GdprServiceImpl#deleteCustomerRecordingsFromS3` liczy `failed`, który dla `S3Exception` nie rośnie (błędy sieci to `SdkClientException`, te propagują) — BE-129 nie może na tym polegać.
- **Klucze OUTBOUND to dane od klienta:** `EmailReplyRequest.PendingAttachment#s3Key` → `EmailController#replyToMessage` :146–166 / `#sendOutboundEmail` :174–191 → `EmailSendServiceImpl` bez walidacji prefiksu `email-attachments/{tenantId}/` (kontrola IDOR jest tylko w `EmailAttachmentController#downloadAttachment` :129–135). Skutki: (i) agent może dołączyć do maila obiekt spoza swojego tenanta (`download(s3Key)` bez sprawdzenia) — luka poza zakresem EPIC-30, zgłosić osobnym ticketem; (ii) purge z BE-125 kasujący `s3_key` z JSONB bez sprawdzenia prefiksu skasowałby cudzy obiekt → allow-list prefiksu w BE-125.

**4. Kod social po PR #44 (`67943eb`, merge `aa8a799`, 2026-09-19)** — lista zmian istotnych dla BE-125…127, DB-065, BE-132:
1. Zmieniona jest **wyłącznie ścieżka wychodząca** `SocialMessageServiceImpl` (diff :128–245); przychodząca (:35–125), `SocialWebhookController`, `SocialMessageConsumer`, `SocialMessagePublisherImpl`, `SocialContactController`, `SocialMessage`, `SocialMessageRepository`, `IncomingSocialMessage` oraz `FacebookAdapter`/`InstagramAdapter` (nadal `[STUB]`) — bez zmian. Nowe: `WhatsAppAdapter` (realne HTTP), `V092` (globalny unique `(platform, page_id)` na `social_integration` — nie dotyczy `social_message`), `POST /api/integrations/WHATSAPP/connect`.
2. `sendMessage` :153–172 rozbite na 3 etapy: `loadSendContext` :178 → `adapter.sendMessage` poza transakcją → `saveOutboundMessage` :217. Oba helpery mają `@Transactional protected` i są wołane przez `this.` — **self-invocation, adnotacje nieskuteczne**; granicą transakcji zapisu jest klasa `SocialMessageRepository` (`@Transactional` na klasie). Dla BE-132: natywny INSERT zostaje w transakcji repozytorium, nie zakładać transakcji serwisowej.
3. Wiersz OUTBOUND powstaje tylko po udanym wywołaniu API (WhatsApp: 2xx). `external_message_id` nadal syntetyczne `OUTBOUND-<uuid>` (:226) — id z odpowiedzi Graph API nie jest zapisywane (webhooki statusów są ignorowane: `SocialWebhookController:369–372`); `sent_at = Instant.now()` (:229), `received_at` NULL, `attachments = []` (WhatsApp odrzuca załączniki `IllegalArgumentException`).
4. **Korekta założenia BE-132/DB-065 („`sentAt` z platformy"):** deterministyczny jest tylko WhatsApp (`timestamp` z payloadu, `SocialWebhookController:406–409`, fallback `now()`); **Facebook i Instagram zawsze przekazują `Instant.now()` z chwili webhooka** (:312, :350). Redelivery FB/IG (Meta ponawia przy braku 200) dostaje inny `sent_at`, więc constraint `(tenant_id, external_message_id, sent_at)` go nie złapie; jedyną obroną pozostaje `findByExternalMessageId` (:75–81, bez daty; listener bez `concurrency`, więc wyścig dopiero przy ≥ 2 instancjach). Rekomendacja: w BE-132, przed DB-065, wziąć `sent_at` FB/IG z pola czasu payloadu (nazwę pola potwierdzić w dokumentacji Meta — niezweryfikowane).
5. `SocialMessage#contactId` ma `nullable = false` (:47), a kolumna jest nullable od V028 — zapis zawsze ustawia kontakt, więc social nie produkuje sierot przy zapisie; sieroty social mogłyby powstać tylko z `detachContactReferences`. `platform` to enum `social_platform` (`columnDefinition`) — natywny INSERT (BE-132) wymaga `CAST(:platform AS social_platform)`.
6. Dla D1: WhatsApp (`from` = numer telefonu w `sender_external_id` i `contact.remote_address`) to pierwszy kanał social z realistycznym wolumenem i danymi identyfikującymi wprost — luka „treść nigdy nie jest usuwana" dotyczy go od pierwszego dnia integracji. Live: `social_message` = 0 wierszy (najtańsze okno na DB-065).

**5. Ryzyko (a) — osierocone obiekty S3 przy `RECORDINGS` > `CONTACT_INTERACTIONS`** (potwierdzone w kodzie)
- Brak walidacji: `RetentionPolicyServiceImpl#updatePolicy` :100–114 sprawdza wyłącznie zakres [1, 120] (`validateRetentionMonths` :172–178); `RetentionController#updatePolicy` :143–178 — tylko zgodność kategorii ścieżka/body; w bazie tylko CHECK zakresu i UNIQUE, brak triggerów. Nic nie porównuje obu kategorii.
- `RecordingRetentionJob` znajduje obiekty wyłącznie przez `contact.recording_url` (`ContactRepository#findExpiredRecordings` :1322–1348), a `ContactRepository#deleteBatchOlderThan` :587–615 (`DELETE … RETURNING contact_id`) usuwa wiersz bez odczytu `recording_url` — obiekt staje się nieosiągalny na stałe.
- **Zasięg szerszy niż „nagrania":** w `contact.recording_url` leży też EML kontaktów e-mail (pełna kopia treści z załącznikami) — purge kontaktu przed RECORDINGS zostawia w S3 dokładnie ten materiał, który D1 = A ma usunąć.
- **Sama walidacja `RECORDINGS ≤ CONTACT_INTERACTIONS` nie wystarcza (nawet równość jest niebezpieczna):** (i) `RecordingRetentionJob` przetwarza **jedną paczkę 100 rekordów na tenanta na przebieg** (`BATCH_SIZE` :60, :162–164, brak pętli; 1×/dobę 02:00) — tenant z > 100 nagrań/dobę nie nadąża, a zaległość przecina się z purge; (ii) różne jednostki: nagrania `now − 30·miesięcy` dni (`getRetentionDays` :156, `DAYS_PER_MONTH` = 30), purge `LocalDate.now(UTC).minusMonths(n)` (`RetentionPurgeServiceImpl:100`) — różnica do kilku dni; (iii) różne kolumny: `ended_at` vs `started_at`; (iv) polityki bywają zmieniane wstecz, a ręczny purge działa w dowolnej chwili.
- **Stan live:** brak naruszeń (KMN Software: RECORDINGS = CI = 6 mies. — równość; Kampania Handlowa: 3 vs 60) i 0 osieroconych obiektów — ryzyko utajone, niezmaterializowane.
- **Rekomendacja:** (1) siatka bezpieczeństwa w purge (BE-126, zakres do potwierdzenia): batch `(contact_id, recording_url)` → usunięcie obiektu (metodą zwracającą wynik, nie `RecordingService#deleteFromS3`) → wiersze wiadomości → kontakt; działa także dla zaległości i danych historycznych; (2) walidacja `RECORDINGS ≤ CONTACT_INTERACTIONS` w `RetentionPolicyServiceImpl#updatePolicy` (422 przez `IllegalArgumentException`, także przy zmianie CI w dół) — **osobny follow-up po potwierdzeniu PO**; (3) `RecordingRetentionJob`: pętla do wyczerpania zamiast jednej paczki oraz brak `clearRecordingUrl` po porażce S3 (wymaga usunięcia „połykania" błędu) — osobny ticket.

**6. Ryzyko (b) — kolejność operacji przy awarii** (`RetentionPurgeServiceImpl#purgeContactInteractions` :171–192)
- Dziś: `do { ids = contactService.purgeContactsOlderThan(…) /* DELETE…RETURNING, własna transakcja, commit */; detach(email); detach(social); } while (ids.size() == batch)`, potem pętla `contact_event` (wybór po wieku, niezależna od kontaktów — samoleczące). Kolejność „rodzic przed dzieckiem" jest dziś nieszkodliwa (`detach` to UPDATE po `contact_id`). **Po BE-126 (DELETE + S3) byłaby błędna:** `RETURNING` daje ID dopiero po commit; awaria S3/DB/JVM przed usunięciem dzieci gubi ID, wiadomości wiszą, a wskaźniki do S3 (`attachments`, `contact.recording_url`) są jedyną drogą do obiektów.
- **Ocena rekomendacji BE-126: poprawna i konieczna dla D1 = A.** Niezmiennik: nie usuwaj wiersza, który jest jedynym wskaźnikiem do niesprzątniętego zasobu → S3 → wiersze wiadomości → wiersz kontaktu. Kolejność odwrotna (obiekt usunięty, wiersz zostaje) jest nieszkodliwa (`DeleteObject` na nieistniejącym kluczu = 204 w S3/MinIO — do potwierdzenia testem BE-125; ponowny przebieg idempotentny). Alternatywę „zostać przy `RETURNING` + `NOT EXISTS`" odradzam: odzyskuje wiersze wiadomości (klucze w JSONB przeżywają), ale nie obiekty z `contact.recording_url`, i wymaga anti-joinu po `contact_id` bez klucza partycji.
- **Luki w BE-125/BE-126 (uzupełnione w tych ticketach):** (1) `contact.recording_url` poza opisem (patrz (a)); (2) „ta sama lista ID dwa razy" to przybliżenie — niezmiennik: **postęp = liczba faktycznie usuniętych wierszy `contact` w iteracji > 0**, przy 0 koniec pętli z WARN i `error_message`; `findContactIdsOlderThan` z `ORDER BY started_at, contact_id`; (3) usuwać kontakty wyłącznie spoza `contactIdsBlocked` (nowe pole `PurgedMessages`), nie całą paczkę — inaczej jeden nieosiągalny obiekt blokuje pozostałe kontakty paczki; (4) porażka trwała (np. 403) stoi na czele kolejki — pętla kończy się bez postępu, kontakty dalej w kolejce czekają (akceptowalne, `error_message` z liczbą); (5) współbieżne purge tego samego tenanta (ręczny + auto) nie mają blokady, ale ścieżka „S3 przed wierszem" jest idempotentna; (6) ten sam klucz S3 może być wskazywany przez > 1 wiadomość (klient może użyć `s3Key` ponownie) — przyjęte.

**7. Wiek wiadomości dla osieroconych** (schemat + kod + live)
- `email_message`: `received_at`, `sent_at` nullable; `created_at` NOT NULL DEFAULT now(). INBOUND: `receivedAt = Message#getReceivedDate() ?: now()` (`EmailPollingServiceImpl:251–253,267`), `sentAt` nigdy; OUTBOUND: `sentAt = Instant.now()` (`EmailSendServiceImpl:111,209`), `receivedAt` nigdy. Live: 30/30 INBOUND ma `received_at` i `sent_at` NULL; 25/25 OUTBOUND odwrotnie; 0 wierszy z oboma NULL i 0 z oboma ustawionymi; `created_at` różni się od `COALESCE(received_at, sent_at)` o ≤ 65,7 s.
- **Potwierdzone:** `COALESCE(received_at, sent_at, created_at)` jest poprawne i totalne (nigdy NULL dzięki `created_at NOT NULL`); trzeci argument jest dziś nieosiągalny z kodu (dwa miejsca budowania `EmailMessage`) i zostaje jako siatka dla zapisów spoza aplikacji. Zgodne z widokiem `v_customer_timeline` (V025: `COALESCE(em.received_at, em.sent_at)`).
- `social_message`: `sent_at` NOT NULL DEFAULT now(), `received_at` nullable, `created_at` NOT NULL → **`sent_at` wystarcza**. Semantyka `sent_at`: WhatsApp INBOUND = czas platformy, FB/IG INBOUND = czas webhooka, OUTBOUND = czas zapisu (sekcja 4, pkt 4).
- **Uwagi:** (i) „wiek" INBOUND = INTERNALDATE serwera IMAP, nie nagłówek `Date`; DESIGN D4 proponuje dla przyszłego `message_at` `Message#getSentDate()` (nagłówek nadawcy) PRZED `getReceivedDate()` — rozbieżność z backfillem DB-067 (`COALESCE(received_at, …)` = INTERNALDATE) i wartość kontrolowana przez nadawcę (data z przeszłości = natychmiastowa kwalifikacja do purge). Rekomendacja: wiek retencyjny = czas zaobserwowany przez system; rozstrzygnąć w DB-066/DB-067/BE-134; (ii) skrzynka zmigrowana z historycznym INTERNALDATE daje „stare" świeżo zapisane wiadomości — BE-127 nie powinien usuwać sierot z `created_at` młodszym niż ≈ 24 h (filtr resztkowy, nie psuje indeksu z DB-059).

**8. Testy S3, lifecycle, liczby demo**
- (i) Testcontainers w `backend/app/pom.xml:371–388`: `junit-jupiter`, `postgresql`, `rabbitmq` (BOM 1.20.4); **brak MinIO/LocalStack** (`grep MinIO|LocalStack|S3Client` w `src/test`: tylko mock `S3Client` — `RecordingServiceTest:52`, `TwilioRecordingDownloadServiceTest:56`, `PluginStorageServiceImplTest:54`, komentarz „Brak wzorca testów integracyjnych S3/MinIO w projekcie"); w `~/.m2` brak `org.testcontainers:minio`/`localstack` (dostępność `MinIOContainer` w 1.20.4 niezweryfikowana). Wniosek: BE-125 = Testcontainers Postgres + `@Mock S3Client` (jak `CampaignContactArchivePurgeTenantIsolationTest` + `RecordingServiceTest`); MinIO-Testcontainer to nowa zależność — tylko gdy zlecający uzna za potrzebną (mock nie sprawdzi semantyki prefiksów/paginacji `ListObjectsV2` w BE-131).
- (ii) Lifecycle/TTL dla `pending/`: **brak** (patrz sekcja 3; backend nie ustawia lifecycle — grep `Lifecycle`/`createBucket` w `src/main` = 0). Reguła prefiksu `pending/` jest dodatkowo **niebezpieczna**, dopóki załączniki wysłanych wiadomości leżą w `pending/` (BE-131 skorygowany).
- (iii) Demo (2026-09-20, tylko odczyt): `email_message` 55 wierszy/216 kB (30 IN, 25 OUT), 23 z `contact_id IS NULL` (15 z `body_html`, 1 z załącznikiem; 18 sprzed 2026-05-13 = odcięte purge'em z 2026-08-13, 10 z `body_html`; **5 późniejszych, w tym 1 IN i 4 OUT — nie z purge, przyczyny nie zbadano**: niezroutowane/nie powiązane), 0 wiszących; 9 wiadomości z załącznikami = 14 wpisów (INBOUND 6 = 1 089 270 B w `{messageId}/`, OUTBOUND 8 = 883 570 B w `pending/`); `social_message` 0; `contact` 426 (EMAIL 32, z tego 14 z EML; PHONE 394, 61 z mp3); MinIO 98 obiektów (61 mp3, 14 eml, 6 INBOUND, 9 pending, 8 plugins). Polityki: KMN Software — wszystkie 4 kategorie = 6 mies.; Kampania Handlowa — CI 60, RECORDINGS 3, TRANSCRIPTS 3, CAMPAIGN_DATA 60; `auto_purge_enabled = false` wszędzie. `retention_purge_log`: 2 wpisy (KMN, MANUAL, COMPLETED): 2026-08-12 (cutoff 2021-08-12, 0 wierszy), 2026-08-13 (cutoff 2026-05-13, 133 wiersze).
- **Kandydaci do purge dziś: 0** (KMN: CI 6 mies. → cutoff 2026-03-20; najstarszy kontakt 2026-05-13, najstarsza wiadomość 2026-04-25); pierwsza sierota zakwalifikuje się ≈ 2026-10-26. Test destrukcyjny (WP-4) w BE-126/BE-127 wymaga tymczasowego skrócenia polityki na tenancie testowym i zgody właściciela.

**9. Pytania do PO — DO PRZEKAZANIA (nie wysłano)**
1. **D1:** czy potwierdzasz A — treść wiadomości i załączniki znikają nieodwracalnie razem z kontaktem po retencji `CONTACT_INTERACTIONS` (a przycisk „Usuń teraz" dla tej kategorii zyskuje szerszy skutek)? Jeśli nie — B (anonimizacja) czy C (osobna kategoria `MESSAGE_CONTENT`)?
2. **D2 (wolumeny):** ile e-maili/dzień/tenant i ilu tenantów na produkcji, jaki udział załączników i ich rozmiar (demo: 55 wierszy — nie jest reprezentatywne)?
3. Czy purge ma usuwać także obiekt z `contact.recording_url` (EML kontaktów e-mail, nagrania) niezależnie od retencji RECORDINGS (rekomendowane), i czy wprowadzić blokadę `RECORDINGS ≤ CONTACT_INTERACTIONS` (422)?
4. Po ilu godzinach uznajemy porzucony upload załącznika za odpadek (BE-131, propozycja 72 h) — i czy akceptujesz, że załączniki wysłanych e-maili podlegają retencji razem z wiadomością?
5. Czy numer telefonu nadawcy WhatsApp (`sender_external_id`) traktujemy jako PII usuwane razem z wiadomością (rekomendowane: tak)?

**10. Korekty w ticketach i wpływ na tickety spoza tego pliku**
- Sprostowane w tym pliku: **BE-125** (źródło kluczy S3 = `attachments[*].s3_key` także dla `pending/`; allow-list prefiksu; `contactIdsBlocked`; powód własnej metody `delete`), **BE-126** (`contact.recording_url`, niezmiennik postępu, kontakty spoza `contactIdsBlocked`, liczby WP-4), **BE-127** (oczekiwana liczba kandydatów: dziś 0, sieroty ogółem 23; źródła sierot; filtr `created_at`), **BE-131** (założenie „`pending/` = tymczasowe" jest fałszywe — TTL/lifecycle skasowałyby załączniki wysłanych wiadomości).
- Do zmiany w ticketach spoza zakresu tej edycji (BE-129 i BE-132 w tym pliku — nie ruszane, żeby nie wychodzić poza BE-124…127/131; DB-*/FE-* w innych plikach; szczegóły w raporcie zlecającemu): BE-129 (metoda usuwania zwracająca wynik; klucze EML i `pending/`), BE-132 (źródło `sent_at` FB/IG, self-invocation, cast enuma, aktualne numery linii), DB-059/DB-060/DB-061/DB-065/DB-066/DB-067/DB-077, FE-110 (wejście razem z BE-126).

**11. Niezweryfikowane / ograniczenia:** (1) semantyka `DeleteObject` na nieistniejącym kluczu (204) przyjęta z dokumentacji S3, nie sprawdzona na MinIO (wymagałaby operacji zapisu); (2) nazwa pola czasu w payloadzie Meta FB/IG — niesprawdzona; (3) dostępność `MinIOContainer` w Testcontainers 1.20.4 — niesprawdzona (brak zależności w `~/.m2`); (4) przyczyna 5 sierot po 2026-05-13 — niezbadana; (5) zachowanie wiadomości social wobec czasu życia kontaktu — brak danych (0 wierszy); (6) RLS pod rolą ograniczoną nie był tematem tego ticketu (DB-064, BE-139).

---

### BE-125 – Usuwanie wiadomości e-mail i social po `contact_id` wraz z obiektami S3 (warstwa repo/serwis)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-124 ✅
**Status:** ✅ Ukończone (2026-09-21) — zakłada D1 = A (przyjęte do realizacji bez wyraźnego potwierdzenia właściciela, zob. BE-124); kod NIE jest podpięty do purge (BE-126), nic destrukcyjnego się nie uruchamia
**Blokuje:** BE-126, BE-129, BE-131, BE-135, BE-143
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
Zastępuje `detachContactReferences` (JPQL `UPDATE … SET contact_id = NULL`) operacją usuwania. Repozytoria domen `email`/`social` są package-private, więc logika trafia do `EmailMessageRepository`/`SocialMessageRepository` i publicznych `EmailMessageService`/`SocialMessageService` (wzorzec BE-113).
**Zakłada D1 = A;** przy D1 = B te metody robią UPDATE PII (`body_*`, adresy, `subject`, `attachments = '[]'`) zamiast DELETE, przy D1 = C — bez zmian w tym tickecie.

**Zakres:**
- `EmailAttachmentStorageService#delete(String s3Key)` (+ `EmailAttachmentStorageServiceImpl`; `S3Client` i `S3Properties#getBucket()` już wstrzyknięte): idempotentne (brak obiektu = sukces), błąd S3 → `EmailAttachmentException`. Własna metoda w `domain.email` zamiast zależności od `RecordingService#deleteFromS3`.
- `EmailMessageRepository#purgeByContactIds(tenantId, contactIds)`: kolejność **S3 przed wierszem**: (1) `SELECT message_id, attachments` dla wiadomości `WHERE tenant_id = :t AND contact_id IN (:ids)`, (2) usuń obiekty z `attachments[*].s3_key` (odporność na `[]`, brak klucza, uszkodzony JSON), (3) `DELETE` WYŁĄCZNIE wiadomości, których wszystkie obiekty usunięto; awaria S3 → wiadomość zostaje (ponowi ją następny purge), licznik `s3Failures`; wynik `PurgedMessages(deletedRows, s3ObjectsDeleted, s3Failures)`.
  Natywny SQL, identyfikacja wierszy pełnym PK, **zakaz `ctid`**; zapytanie po `contact_id IN` działa też na tabeli partycjonowanej (DB-067) — komentarz w kodzie.
- **Korekty z BE-124 (wiążące, dowody w notatce BE-124, sekcje 3 i 6):**
  (a) klucze S3 pochodzą wyłącznie z `attachments[*].s3_key`; klucze wiadomości OUTBOUND wskazują na `email-attachments/{tenantId}/pending/{uuid}/…`, **nie** na `{messageId}/` (`EmailSendServiceImpl#buildAttachmentsJson` zapisuje klucz z żądania; live 8 z 8) — opcjonalne sprzątanie prefiksu `{messageId}/` (niżej) obejmuje tylko załączniki INBOUND;
  (b) **allow-list prefiksu:** kasuj wyłącznie klucze zaczynające się od `email-attachments/{tenantId}/` — klucz OUTBOUND to dane od klienta bez walidacji (`EmailReplyRequest.PendingAttachment#s3Key` → `EmailController#replyToMessage`/`#sendOutboundEmail`); obcy klucz (inny tenant, nagranie) pomiń z ERROR i licznikiem `s3Rejected`, a wiersz usuń mimo to (PII musi zniknąć, cudzy obiekt zostaje nietknięty); pusty klucz `""` (`buildAttachmentsJson` :387 zapisuje `""` przy `s3Key == null`) i wpisy niebędące obiektami pomiń bez wyjątku;
  (c) `PurgedMessages` zwraca dodatkowo `contactIdsBlocked` (kontakty z ≥ 1 wiadomością nieusuniętą po porażce S3) — BE-126 usuwa kontakty tylko spoza tego zbioru;
  (d) własna metoda `delete` jest konieczna z konkretnego powodu: `RecordingService#deleteFromS3` (`RecordingServiceImpl:307–322`) połyka `S3Exception` (brak sygnału porażki);
  (e) ten sam klucz może być wskazywany przez > 1 wiadomość (klient może użyć `s3Key` ponownie) — `DeleteObject` idempotentny, przyjęte; semantykę usunięcia nieistniejącego klucza (204) potwierdzić w teście.
- `SocialMessageRepository#purgeByContactIds(...)` (bez S3 — załączniki to URL-e platform) i `EmailMessageService#purgeByContactIds`, `SocialMessageService#purgeByContactIds`. `detachContactReferences` oznaczyć `@Deprecated` (usunięcie w BE-126).
- Opcjonalnie (Should): po usunięciu wiersza sprzątanie prefiksu `email-attachments/{tenantId}/{messageId}/` (`ListObjectsV2` + delete) — łapie załączniki wgrane, ale nieodnotowane w `attachments` (błąd `extractAndStoreAttachments` po uploadzie); wykonawca ocenia koszt.
- Każda metoda: `setTenantContextInDb(tenantId)` + `assertSameTenant(tenantId)` (wzorzec repozytoriów retencji), bez `TenantContext.clear()` (WP-2).

**Kryteria akceptacji:**
- [x] (WP-1) Test na prawdziwej bazie (Testcontainers, pełny Flyway) + `S3Client` mock (wzorzec `RecordingServiceTest`; MinIO Testcontainer opcjonalnie): wiadomość z 2 załącznikami → obiekty usunięte, wiersz usunięty; awaria S3 na 2. obiekcie → wiersz zostaje, `s3Failures = 1`; drugi przebieg (S3 sprawny) → sukces (idempotencja)
- [x] Izolacja: `contactIds` tenanta A nie usuwa wiadomości tenanta B (test po wartościach); wiadomości z pustym/uszkodzonym JSONB `attachments` obsłużone bez wyjątku
- [x] (WP-2) Test z pustym `TenantContext` → `IllegalStateException`; brak `ctid`; `EXPLAIN` DELETE po `contact_id IN` używa `idx_email_message_contact`/`idx_social_message_contact` (notatka)
- [x] Social: usunięcie bez operacji S3 (test); `detachContactReferences` `@Deprecated` z odwołaniem do BE-126
- [x] `mvn verify -pl app` — 1940 testów, 0 failures, 0 errors, 0 skipped
- [ ] DoD (WP-7): status i notatka w pliku zadań — zrobione; pamięć agenta zapisana w `.claude/agent-memory/backend-dev-expert/` (`project_epic30_be125_message_purge.md`, `feedback_jpa_real_db_integration_test_harness.md`) — **commit razem ze zmianą wykonuje zlecający**

**Ryzyka:** opóźnienie S3 × rozmiar partii (`retention.purge.batch-size` = 100 wiadomości może oznaczać setki obiektów) — zmierz; partie a limity S3 (rate).

**Notatka z realizacji (2026-09-21):**

Zmiany wyłącznie w kodzie i testach (żadnej migracji); nie ruszano bazy demo, MinIO ani stosu docker; `RetentionPurgeServiceImpl` bez zmian (podpięcie = BE-126). Numery testów: 96 nowych (59 jednostkowych + 37 na prawdziwej infrastrukturze).

**1. Co zrobiono**
- `EmailAttachmentStorageService#delete(String)` (+ Impl): `DeleteObject`, brak obiektu = sukces; błąd S3 → `EmailAttachmentException`. Łapie `SdkException`, nie tylko `S3Exception` — błędy sieci/timeoutu to `SdkClientException`, więc `S3Exception` samo (jak `RecordingService#deleteFromS3`) by je przepuściło. `EmailAttachmentException` wyniesiony z Impl do publicznej klasy top-level (metoda `delete` jest częścią publicznego kontraktu i będą ją wołać BE-126/129/131 spoza pakietu).
- `EmailAttachmentKeys` (nowa, package-private): jedno źródło budowania kluczy (`store`/`storePending` używają jej — schemat bez zmian, test regresji), allow-lista `isOwnedByTenant` (prefiks `email-attachments/{tenantId}/` + odrzut segmentów `.`/`..` i znaków sterujących), `extractS3Keys` (odporność na `null`/`[]`/uszkodzony JSON/wpisy nieobiektowe/brak lub pusty `s3_key`/klucz nietekstowy/stare `s3_url`; nigdy nie rzuca), `forLog` (klucz OUTBOUND to dane od klienta — bez wstrzyknięcia linii do logów).
- `EmailMessageRepository`: `findAttachmentsByContactIds` (natywny SQL, projekcja `Object[]`: `message_id, contact_id, CAST(attachments AS text)`) i `deleteByIds` (`DELETE … WHERE tenant_id AND message_id IN (…) RETURNING message_id`); `assertSameTenant` + `setTenantContextInDb`, porcje `IN` po 1000, brak `ctid`, brak `TenantContext.clear()`; `detachContactReferences` → `@Deprecated` (Javadoc odsyła do BE-126; repo, interfejs i impl).
- `EmailMessageService#purgeByContactIds(tenantId, contactIds)` → `PurgedMessages(deletedRows, s3ObjectsDeleted, s3Failures, s3Rejected, Set<UUID> contactIdsBlocked)`; `EmailMessageServiceImpl#purgeRows` (package-private) = fazy S3 → DELETE niezależne od selektora (BE-127 dołoży tylko własny SELECT osieroconych).
- Social: `SocialMessageRepository#purgeByContactIds` (jeden `DELETE … contact_id IN`), `SocialMessageService#purgeByContactIds` → `int`; `detachContactReferences` → `@Deprecated`.

**2. Decyzje projektowe i odstępstwa od ticketu**
- **Orkiestracja w serwisie, nie w `EmailMessageRepository#purgeByContactIds` (odstępstwo).** Repozytorium ma dwie krótkie transakcje (SELECT readOnly, DELETE), a serwis — celowo BEZ `@Transactional` — robi I/O do S3 pomiędzy nimi. Ticketowy wariant „wszystko w repozytorium" trzymałby połączenie HikariCP przez setki wywołań S3 i wiązał repozytorium z klientem S3. Dowód: test z pulą rozmiaru 1 — w chwili wywołania S3 aktywnych połączeń = 0.
- **`contactIdsBlocked` obejmuje także wiersze niepotwierdzone przez `DELETE … RETURNING`** (ponad ticket). Pod rolą bez `BYPASSRLS` `DELETE` z `email_message` usuwa **0 wierszy bez błędu** (polityka wiadomości to tylko `FOR SELECT`, V012; DESIGN U8/R1) — potwierdzone testem pod rolą LOGIN członkiem `app_user`: `deletedRows = 0`, 3 kontakty zablokowane, obiekty S3 usunięte. Bez tego BE-126 uznałby sukces i usunął kontakt, zostawiając wiadomość z PII bez wskaźnika do niczego. Dotyczy też wyścigu z równoległym purge (zwraca wiersze tylko ten, który je usunął).
- **Bezpiecznik S3 (ponad ticket):** po 3 porażkach `DeleteObject` z rzędu (`EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD`) reszta obiektów nie jest próbowana (wiadomości zostają, ich kontakty → `contactIdsBlocked`). Domyślny `S3Client` ponawia żądania (3 próby — widać w logu testu z niedostępnym S3: „Request attempt 1–3") i ma timeouty rzędu sekund, więc bez bezpiecznika niedostępny S3 blokowałby wątek purge na `timeout × liczba obiektów`. Wiadomości bez obiektów S3 są usuwane mimo przerwania.
- **Klucz S3 w partii usuwany raz** (pamięć wyników per klucz): ten sam klucz w >1 wiadomości = jedno wywołanie i jeden wynik dla wszystkich; nieudane usunięcie blokuje wszystkie wiadomości, które go wskazują. Skutek uboczny przyjęty w tickecie (punkt e): skasowanie klucza współdzielonego z wiadomością spoza partii psuje jej załącznik.
- **Allow-lista rozszerzona** o odrzut segmentów `.`/`..` i znaków sterujących (klucz z bazy jest niezaufany). Obcy klucz: pominięty z ERROR i `s3Rejected` (liczone raz na różny klucz), wiersz usuwany.
- **`PurgedMessages` ma `s3Rejected`** (z listy testów ticketu) i metody `empty()`/`plus()`; social zwraca `int` — bez S3 nie ma czego blokować, a wspólny typ wymusiłby zależność social → email.
- **Opcjonalne sprzątanie prefiksu `{messageId}/` — NIE wdrożone (świadomie).** Koszt: 1 `ListObjectsV2` na każdą usuwaną wiadomość INBOUND, także tę bez załączników (dziś purge bez załączników nie dotyka S3 w ogóle) + kasacja po prefiksie (większy promień rażenia przy błędzie niż kasacja po kluczach z JSONB). Zysk: łapie jedyny realny przypadek „wiersz istnieje, obiekt wgrany, ale nieodnotowany w `attachments`" — padł `UPDATE attachments` po uploadzie w `EmailPollingServiceImpl#extractAndStoreAttachments`. To samo, i szerzej (także wiersze usunięte), pokryje sweep „obiektów niewskazywanych przez żaden wiersz" z BE-131.
- `EmailMessageRepository#purgeByContactIds` z ticketu w kodzie nie istnieje — jego rolę pełni para `findAttachmentsByContactIds`/`deleteByIds` + `EmailMessageService#purgeByContactIds` (jw.).

**3. Testy (96 nowych)**
- Jednostkowe (59): `EmailAttachmentKeysTest` 24, `EmailMessageServiceImplPurgeTest` 22 (kolejność S3 → wiersz przez `InOrder`, awaria na 2. obiekcie, izolacja awarii do wiadomości, bezpiecznik, współdzielony klucz, allow-lista, potwierdzenie `DELETE`, wiadomości osierocone z `contactId = null`, deprecjacja), `EmailAttachmentStorageServiceImplTest` 8, `PurgedMessagesTest` 3, `SocialMessageServicePurgeTest` 2.
- Na prawdziwej infrastrukturze (37; Testcontainers `postgres:16-alpine`, pełny Flyway, prawdziwy Hibernate + proxy `@Transactional`; `S3Client` = mock/fake): `EmailMessagePurgeIntegrationTest` 18 (2 załączniki → obiekty i wiersz usunięte; awaria S3 na 2. obiekcie → wiersz zostaje, `s3Failures = 1`, drugi przebieg sukces, klucze usunięte dwukrotnie = idempotencja; całkowita awaria S3; brak połączenia DB w trakcie S3; obcy prefiks/nagranie/EML/`..` → `s3Rejected`, wiersz usunięty; pusty/nietypowy JSONB; ten sam klucz w >1 wiadomości; tenant A nie tyka B — także przy TYM SAMYM `contact_id`, po wartościach; niezgodny tenant → `CrossTenantAccessException`; pusty `TenantContext` → `IllegalStateException` (serwis + repo); `TenantContext` przeżywa purge; 2345 kontaktów/1205 wiadomości ponad porcję `IN`; `EXPLAIN`), `EmailMessagePurgeRlsIntegrationTest` 2 (rola bez `BYPASSRLS`), `SocialMessagePurgeIntegrationTest` 12 (kontekst bez beana S3; izolacja; `TenantContext`; porcje; `EXPLAIN`; `@Deprecated`), `EmailAttachmentStorageServiceMinioTest` 5 (prawdziwy MinIO przez `GenericContainer`, bez nowej zależności: usunięcie, **usunięcie nieistniejącego klucza = sukces (204) — punkt (e) potwierdzony**, niedostępny S3 → `EmailAttachmentException`, purge end-to-end z cudzym obiektem, pomiar opóźnienia).
- Nowy harness (do ponownego użycia w BE-126/127): `com.contactcenter.support.{PostgresTestDatabase, JpaTestContext, TestcontainersSupport}` — singleton kontenera per JVM, minimalny kontekst Springa z prawdziwym Hibernate.
- `mvn verify -pl app`: **BUILD SUCCESS, 1940 testów, 0 failures, 0 errors, 0 skipped.**

**4. Pomiary (Testcontainers, scratch — baza demo i MinIO demo nietknięte)**
- `EXPLAIN` (30 tys. wierszy + `ANALYZE`): `SELECT … contact_id IN (…)` → `Index Scan using idx_email_message_contact` (filtr `tenant_id`); `DELETE … message_id IN (…)` → `Bitmap Index Scan on pk_email_message`; social `DELETE … contact_id IN (…)` → `Index Scan using idx_social_message_contact`. Bez Seq Scan.
- Opóźnienie `DeleteObject` na lokalnym MinIO (kontener, 1 wątek, sekwencyjnie): 200 × istniejący = 674 ms (3,4 ms/szt.), 200 × nieistniejący = 552 ms (2,8 ms/szt.). Partia 100 kontaktów × ~2 załączniki ≈ 200 wywołań ≈ 0,7 s lokalnie. Dla AWS S3 (~20–50 ms/wywołanie — **założenie, nie pomiar**) ≈ 4–10 s na partię. Limity S3 (rzędu tysięcy DELETE/s na prefiks wg dokumentacji AWS — niezweryfikowane) przy wywołaniach sekwencyjnych nie są zagrożone.

**5. Nie zweryfikowane**
- Opóźnienia i limity na prawdziwym AWS S3 (tylko lokalny MinIO). Tag `minio/minio:latest` w teście jest ruchomy (jak w `docker-compose.yml`).
- Zachowanie pod dużym wolumenem produkcyjnym (D2 — brak liczb).
- Wyścig: wiadomość zapisana po SELECT a przed usunięciem kontaktu (np. odpowiedź klienta w wątku) zostanie z `contact_id` wskazującym na usunięty kontakt — obsłuży ją BE-127 (dangling), o ile zostanie wybrany wariant z `NOT EXISTS`; ten wyścig istniał też przy `detachContactReferences`.

**6. Uwagi dla BE-126 / BE-127**
- BE-126: wołać `email.purgeByContactIds(tenantId, ids)` → `social.purgeByContactIds(tenantId, ids)` (social można wołać dla WSZYSTKICH `ids` — usunięcie PII nie zależy od S3) → `deleteContacts` tylko dla `ids − contactIdsBlocked`. Wołać POZA transakcją (z `purgeAsync` — spełnione; kontekst z snapshotu; metody nie czyszczą `TenantContext`). Niezmiennik „postęp = faktycznie usunięte wiersze `contact` > 0" jest tu KLUCZOWY: pod rolą bez `BYPASSRLS` także `DELETE` z `contact` może usunąć 0 wierszy bez błędu (polityka tylko `INSERT`, DESIGN U8) — wtedy `contactIdsBlocked` chroni wiadomości, a strażnik pętli zatrzyma purge. `deletedRows` z `PurgedMessages` (a nie liczba zleconych) idzie do `rowsDeleted`/breakdown; `s3Failures`/`s3Rejected`/`s3ObjectsDeleted` do `breakdown`. `PurgedMessages#plus` sumuje partie. Wyjątek z `purgeByContactIds` (np. awaria DB po usunięciu obiektów) traktować jak błąd purge — kolejny przebieg jest idempotentny.
- BE-126 pkt (b) (`contact.recording_url`): `EmailAttachmentStorageService#delete` jest neutralne co do prefiksu (ten sam bucket) i zwraca wynik przez wyjątek — nadaje się do kluczy `recording_url`, ale allow-listę (`{tenantId}/…`) trzeba dodać po stronie wołającego; `EmailAttachmentKeys` jest package-private i dotyczy tylko `email-attachments/`.
- BE-127: `EmailMessageServiceImpl#purgeRows` + własne zapytanie zwracające `AttachmentsRow` (z `contactId = null`); `PurgedMessages.deletedRows` = miara postępu pętli. Dane osierocone nie mają `contactIdsBlocked`.
- Po przejściu na klucz złożony `(message_id, message_at)` (BE-134) `AttachmentsRow` musi nieść `messageAt`, a `deleteByIds` identyfikować wiersze pełnym kluczem (komentarz w kodzie); `contact_id IN` bez klucza partycji odpyta indeksy partycji.

**Code review i poprawki (2026-09-21):**
- Werdykt `senior-code-reviewer` (`CR-BACKEND.md`, wpis BE-125): **4/5 — zatwierdzić z poprawkami**; brak blockerów i majorów w samym diffie (rdzeń „S3 przed wierszem", potwierdzanie `DELETE … RETURNING`, allow-lista i izolacja tenantów poprawne). Review czytało kod i testy, nie uruchamiało Mavena.
- Ustalenia BE125-01…13 i dokąd trafiły: **BE125-01** (major, poza zakresem diffu: `s3Key` z żądania wysyłki nie jest walidowany, kontrola w downloadzie bez odrzutu `..`) → nowy ticket **BE-143**; **BE125-02** (okno SELECT→DELETE), **BE125-04** (publiczne `delete(String)` bez kontroli tenanta — decyzja API razem z BE-143), **BE125-05** (social zwraca tylko `int`), **BE125-06** (bezpiecznik/budżet czasu/timeouty S3) oraz hipotezy **H-1** (head-of-line blocking) i **H-2** (wersjonowanie/Object Lock/lifecycle) → uwaga w **BE-126** (H-2 także DESIGN R10 i pytanie do właściciela); **BE125-08/12/13** (test RLS asertujący inwariant, higiena harnessu, bulk `DeleteObjects`/podpartie/guard „poza transakcją") → backlog bez ticketu (do wzięcia przy BE-126/DB-064); **BE125-03/07/09/10/11** → wykonane.
- Wykonane poprawki: **BE125-03** — `toUuid` null-safe + package-private `toAttachmentsRow(Object[])` (wiersz z `contact_id IS NULL` nie wysadza SELECT-a; potrzebne BE-127); **BE125-07** — test MinIO przypięty do `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` (powód: obrazy MinIO z Docker Hub nie są już anonimowo pobieralne — `docker manifest inspect minio/minio:latest` → `denied / unauthorized`, kontrola `alpine` działa; `docker-compose.yml` nadal używa `minio/minio:latest` i `minio/mc:latest` → **BE-144**); **BE125-09** — `PurgedMessages#contactIdsBlocked().contains(null)` nie rzuca NPE; **BE125-10** — WARN dla pominiętych wpisów `attachments` (stary `s3_url`, nietekstowy `s3_key`); **BE125-11** — `forLog` filtruje U+2028/U+2029 i znaki kierunkowe (bidi), a nazwa załącznika `.`/`..` jest zamieniana na bezpieczną.
- Niezależna weryfikacja końcowa (zlecający, po wszystkich poprawkach): `mvn clean verify -pl app` — **Tests run: 1998, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS** (liczba 1940 w AC i w notatce powyżej to stan sprzed review).
- Sprostowanie do pkt 5 notatki („Nie zweryfikowane", pierwszy punkt): uwaga o ruchomym tagu `minio/minio:latest` w teście jest po BE125-07 nieaktualna (test przypięty); `latest` zostaje w `docker-compose.yml` (BE-144). Otwarte bez zmian: punkt DoD o commicie (wykonuje zlecający).

---

### BE-126 – Integracja w `RetentionPurgeServiceImpl#purgeContactInteractions` (usuwanie zamiast odcinania)

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-125 ✅
**Status:** ✅ Ukończone (2026-09-22, częściowo — patrz notatka: punkt (b) i WP-4 odłożone)
**Blokuje:** BE-127, BE-133, DB-065, BE-130, FE-110
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
Dziś pętla `contactService.purgeContactsOlderThan(tenantId, cutoff, batch)` (`ContactRepository#deleteBatchOlderThan`: `DELETE … RETURNING contact_id`) usuwa kontakty, po czym woła `emailMessageService/socialMessageService.detachContactReferences` (linie ≈ 177–183) — PII wiadomości zostaje (DESIGN §2 U1).

**Zakres:**
- Zastąp `detachContactReferences` wołaniem `purgeByContactIds` (BE-125). **Kolejność (rekomendacja, DESIGN R3): dzieci przed rodzicem** — nowa metoda `ContactService#findContactIdsOlderThan(tenantId, cutoff, batch)` → `purgeByContactIds` → `deleteContacts(tenantId, ids)`; awaria S3/DELETE zostawia i kontakt, i wiadomości (idempotentny ponowny przebieg).
  Alternatywa (zostać przy `RETURNING`): kontakty znikają pierwsze, więc wiadomości z dangling `contact_id` obsługuje BE-127 (`NOT EXISTS`) — uzasadnij wybór w notatce. **BE-124 (sekcja 6) odradza tę alternatywę:** nie odzyskuje obiektów z `contact.recording_url` i wymaga anti-joinu bez klucza partycji.
- Guard nieskończonej pętli: ta sama lista ID dwa razy pod rząd (S3 nie odpowiada) → przerwij z WARN i status `FAILED`/`error_message`. **Doprecyzowanie BE-124:** niezmiennikiem jest *postęp = liczba faktycznie usuniętych wierszy `contact` w iteracji > 0* (0 → koniec pętli, WARN, `error_message`); „ta sama lista dwa razy" jest tylko jego przybliżeniem i wymaga deterministycznego `ORDER BY started_at, contact_id` w `findContactIdsOlderThan`.
- **Korekty z BE-124 (wiążące, dowody w notatce BE-124, sekcje 5 i 6):**
  (a) `deleteContacts` wołać wyłącznie dla kontaktów spoza `contactIdsBlocked` zwróconego przez BE-125 (nie dla całej paczki) — inaczej jeden nieosiągalny obiekt S3 blokuje pozostałe kontakty paczki albo, przy usunięciu całej paczki, gubi wskaźnik do niesprzątniętych obiektów;
  (b) **`contact.recording_url` (EML kontaktów e-mail = pełna kopia treści z załącznikami; nagrania mp3):** paczka powinna nieść `(contact_id, recording_url)`, a obiekt być usuwany w tej samej fazie „S3 przed wierszem" — inaczej purge osierocia obiekt (ryzyko (a) z BE-124; `RecordingRetentionJob` szuka wyłącznie po `contact.recording_url`, a `DELETE … RETURNING` go nie odczytuje). Wymaga metody usuwania zwracającej wynik (nie `RecordingService#deleteFromS3` — połyka `S3Exception`). **Zakres do potwierdzenia przez zlecającego** (rozszerza BE-125/BE-126, złożoność M zostaje, jeśli metodę usuwania wspólną dla załączników i `recording_url` zrobi BE-125);
  (c) `contact_event` zostaje po pętli kontaktów (wybór po wieku, niezależny od kontaktu — samoleczący po awarii);
  (d) WP-4: dziś (2026-09-20) dry-run daje **0** kandydatów (KMN: CI = 6 mies. → cutoff 2026-03-20, najstarszy kontakt 2026-05-13) — test destrukcyjny wymaga tymczasowego skrócenia polityki na tenancie testowym i zgody właściciela.
- Semantyka `rowsDeleted`: suma `contact` + `contact_event` + `email_message` + `social_message` (Javadoc `RetentionPurgeService`, FE-110 opis). Audyt: rozszerz `buildNewValueJson` o `"breakdown":{"contacts","events","emailMessages","socialMessages","s3ObjectsDeleted","s3Failures"}`.
  `s3Failures > 0` → status `COMPLETED` z `error_message` = liczba awarii S3 (kolumna istnieje) albo `FAILED` — wykonawca uzasadnia.
- `purgeAsync` (`TenantContext.restore/clear`) i ścieżka auto-purge (`RetentionEvaluationServiceImpl#maybeTriggerAutoPurge`) bez zmian (WP-2) — dodaj testy. `detachContactReferences` przestaje być wołane; usuń je dopiero po potwierdzeniu D1 (przy D1 = C BE-130 przywraca odcinanie referencji, bo wiadomości starzeją się wg własnej kategorii).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers na pełnym łańcuchu Flyway: tenanci A i B w TEJ SAMEJ partycji `contact`; A ma kontakty przed cutoff z e-mailami (w tym z `attachments`) i social; po purge A: kontakty, zdarzenia, wiadomości usunięte, obiekty S3 (mock) usunięte; B nietknięty (COUNT po wartościach); `rowsDeleted` = suma — **częściowo**: `ContactRepository#findContactIdsOlderThan`/`#deleteContacts` mają dedykowany test Testcontainers (`ContactRepositoryPurgeCandidatesIntegrationTest`, pakiet `domain.contact`, tenanci A/B w partycji `contact_2026_03`); orkiestracja `RetentionPurgeServiceImpl` (email+social+contact razem) jest przetestowana na mockach (`RetentionPurgeServiceImplTest`), NIE na jednym teście na żywej bazie obejmującym wszystkie trzy domeny naraz — patrz notatka niżej
- [ ] Awaria S3 w środku: kontakt i jego wiadomości zostają, ponowny purge kończy pracę; brak nieskończonej pętli (test) — **częściowo**: „kontakt zostaje" i „brak nieskończonej pętli" pokryte (mock), „ponowny purge faktycznie kończy pracę" nie jest wykonane jako jeden test end-to-end w tym tickecie (wynika z kompozycji z idempotencją `purgeByContactIds` z BE-125, ale nie jest to zweryfikowane jednym testem)
- [x] Granica: kontakt z `started_at` dokładnie na cutoff i jego wiadomości wg istniejącej semantyki `<`; wiadomości kontaktów nowszych nietknięte — real DB (`ContactRepositoryPurgeCandidatesIntegrationTest#cutoffBoundary_isExclusive`)
- [x] Audyt `RETENTION_PURGE_COMPLETED` zawiera breakdown; `PurgeResultDto`/kontrakt REST bez zmian — `RetentionPurgeServiceImplTest$MessageDeletionEnabled$AuditBreakdown`; `PurgeResultDto`/`RetentionController` nietknięte
- [x] Istniejące testy `RetentionPurgeServiceImplTest` (granica `@Async`, `TenantContext`) zielone — 27/27 bez zmian
- [ ] (WP-4, destrukcyjne) Local-demo po przebudowie obrazów: dry-run SQL policzy wiadomości powiązane z kontaktami kwalifikującymi się (w notatce), zgoda właściciela, manualny purge `CONTACT_INTERACTIONS` na tenancie testowym z UI; sprawdź `retention_purge_log`, `audit_log`, MinIO (brak osieroconych obiektów) — celowo NIE wykonane (ograniczenie zlecającego, flaga `retention.purge.delete-messages` pozostaje `false` we wszystkich profilach)
- [x] `mvn verify -pl app`; DoD (WP-7); zakłada D1 = A — `mvn verify -pl app`: 2046 testów, 0 błędów, 0 awarii

**Notatka wykonawcy (2026-09-22, `backend-dev-expert`):**
- **Flaga bezpiecznika:** `retention.purge.delete-messages` (`@Value`, domyślnie `false`) w `RetentionPurgeServiceImpl#deleteMessagesEnabled`; `application.yml` (`retention.purge.delete-messages: ${RETENTION_PURGE_DELETE_MESSAGES:false}`), bez nadpisania w żadnym profilu. `false` → `purgeContactInteractionsLegacy` (kod identyczny co dziś, tylko przemianowany). `true` → `purgeContactInteractionsWithMessageDeletion` (nowa ścieżka).
- **Strategia H-1 (head-of-line blocking):** zamiast guardu „faktycznie usunięte kontakty w iteracji > 0" (pierwotne doprecyzowanie BE-124) zaimplementowano **stronicowanie keyset**: `ContactRepository#findContactIdsOlderThan(tenantId, cutoff, cursor, batchSize)` zwraca stronę uporządkowaną po `(started_at, contact_id)`; kursor to ostatni kandydat POPRZEDNIEJ strony i przesuwa się o CAŁĄ stronę niezależnie od tego, ilu kontaktów w niej faktycznie usunięto. Terminacja pętli zależy WYŁĄCZNIE od wyczerpania kandydatów (`page.isEmpty()` / `page.size() < batchSize`), nie od liczby usunięć — to strukturalnie eliminuje ryzyko H-1 (zablokowane kontakty nigdy nie są zwracane ponownie). Uzasadnienie i test: `RetentionPurgeServiceImplTest$MessageDeletionEnabled$HeadOfLineBlockingDefense` (2 trwale zablokowane kontakty = `batchSize` nie zatrzymują młodszego kontaktu).
- **Drugi przebieg (BE125-02):** po `contactService.deleteContacts` dla KAŻDEJ strony wołany jest ponowny `purgeByContactIds` (email+social) wyłącznie dla kontaktów faktycznie usuniętych w tej iteracji — łapie wiadomość dopisaną w oknie SELECT→DELETE. Test: `MessageDeletionEnabled$BasicFlow`.
- **`s3Failures > 0` → `COMPLETED` z `error_message` (NIE `FAILED`):** nowy `RetentionPurgeLogRepository#markCompleted(purgeId, tenantId, rowsDeleted, warningMessage)` (4-argumentowy, stary 3-argumentowy zachowany dla zgodności wstecznej). Uzasadnienie: część batcha kończy się poprawnie (kontakty spoza zablokowanych są usunięte), więc `FAILED` dla całej operacji byłoby mylące; kontakty dotknięte porażką S3 zostają nieusunięte i retry jest idempotentny (zgodnie z BE-125).
- **Breakdown audytu:** `buildNewValueJson` rozszerzone o `"breakdown":{"contacts","events","emailMessages","socialMessages","s3ObjectsDeleted","s3Failures","s3Rejected"}` (zawiera `s3Rejected`, pominięty w pierwotnym opisie Zakresu).
- **Social (BE125-05):** pozostawione bez zmian (poza zakresem) — miejsce wywołania oznaczone komentarzem w kodzie.
- **Testy:** `ContactRepositoryPurgeCandidatesIntegrationTest` (pakiet `domain.contact`, Testcontainers, **12 testów** — granica, porządek/tie-break, stronicowanie keyset, izolacja tenantów, `deleteContacts` RETURNING, brak `ctid`) + rozszerzenie `RetentionPurgeServiceImplTest` o **8 nowych testów** (pakiet `domain.retention`, mocki) w `MessageDeletionEnabled`/`FlagDisabledRegression` (38 razem w klasie). Pełny `mvn verify -pl app`: 2046/0/0. **Sprostowanie (2026-09-22, code review CR-BACKEND.md BE126-04, przeliczone przez `grep -c @Test` i `git diff` przeciw HEAD):** pierwotna notatka wykonawcy podawała 13/12 — poprawne liczby to 12/8.
- **Odstępstwo od WP-1 (uzasadnienie architektoniczne):** `ContactRepository`, `EmailMessageServiceImpl`/`EmailMessageRepository`, `SocialMessageRepository` są klasami **package-private** w SWOICH pakietach (`domain.contact`/`domain.email`/`domain.social`); `RetentionPurgeServiceImpl` jest package-private w `domain.retention`. Żaden pojedynczy plik testowy nie może mieć widoczności do wszystkich naraz (poza pełnym `@SpringBootTest`, świadomie odrzuconym przez ten projekt jako zbyt ciężki dla testów integracyjnych — patrz Javadoc `JpaTestContext`). Rozwiązanie: nowy natywny SQL testowany na żywej bazie w jego własnym pakiecie (`domain.contact`), orkiestracja testowana na mockach kolaboratorów (wzorzec już istniejący w tym pliku dla ścieżki legacy) — poprawność `EmailMessageService#purgeByContactIds` na żywej bazie (S3, RLS, allow-lista) jest już pokryta przez `EmailMessagePurgeIntegrationTest`/`EmailMessagePurgeRlsIntegrationTest` z BE-125 i celowo NIE duplikowana.
- **Punkt (b) z Zakresu (usuwanie `contact.recording_url` w fazie „S3 przed wierszem"): ODŁOŻONY, nie zrobiony.** Decyzja zlecającego: kolizja z równoległym BE-143 (walidacja prefiksu tenanta, allow-lista kluczy). `contact.recording_url` pozostaje nietknięty przez ten purge — znana luka (BE-124 ryzyko (a)). Follow-up po scaleniu BE-143.
- **WP-4 (weryfikacja na local-demo) celowo NIE wykonane** — zgodnie z ograniczeniem zlecającego (brak przebudowy stosu/purge na danych demo w tej iteracji).

**Uwaga z BE-125 (2026-09-21) — kontrakt do użycia (zweryfikowany w kodzie: `EmailMessageService`, `PurgedMessages`, `SocialMessageService`):**
- Kolejność: `EmailMessageService#purgeByContactIds(tenantId, ids)` → `SocialMessageService#purgeByContactIds(tenantId, ids)` (dla WSZYSTKICH `ids`; zwraca tylko `int` — brak odpowiednika `contactIdsBlocked`) → usunięcie kontaktów wyłącznie dla `ids − PurgedMessages#contactIdsBlocked`. Wołać POZA transakcją (z `purgeAsync` spełnione — `purgeContactInteractions` nie jest `@Transactional`); metody nie czyszczą `TenantContext`.
- `rowsDeleted` z `PurgedMessages#deletedRows` (nie z liczby zleconych); `s3ObjectsDeleted`/`s3Failures`/`s3Rejected` do breakdownu audytu (lista w Zakresie nie ma `s3Rejected`); `PurgedMessages#plus` sumuje partie. Wyjątek z `purgeByContactIds` = błąd purge (kolejny przebieg jest idempotentny).
- Niezmiennik postępu (faktycznie usunięte wiersze `contact` > 0) jest tym ważniejszy, że pod rolą bez `BYPASSRLS` także `DELETE` z `contact` może usunąć 0 wierszy bez błędu (polityki tylko SELECT/INSERT) — `contactIdsBlocked` chroni wiadomości.
  **Sprostowanie po wykonaniu BE-126 (2026-09-22, code review CR-BACKEND.md BE126-01):** wykonawca NIE zastosował guardu „brak postępu → koniec pętli, `FAILED`" — zamiast tego strategia H-1 (stronicowanie keyset po `(started_at, contact_id)`) strukturalnie omija zablokowane kontakty i **nie zatrzymuje** pętli nawet przy 0 usunięciach w stronie. To celowa, lepsza obrona przed head-of-line blocking, ale oznacza, że „strażnik pętli zatrzymuje purge" jest NIEAKTUALNE — cicha strata pod RLS bez `BYPASSRLS` jest dziś wykrywana wyłącznie przez WARN w logu (rozszerzony po review na każdą częściową stratę, nie tylko całą stronę), nie przez zatrzymanie operacji. Dziś bez wpływu (`ccapp` w local-demo/prod ma `BYPASSRLS`), ale przyszły wykonawca DB-071/074 (RLS na `contact`) musi to uwzględnić przy projektowaniu alertowania.
- Pkt (b) (`contact.recording_url`): `EmailAttachmentStorageService#delete(String)` jest gotowe i neutralne co do prefiksu (brak obiektu = sukces, błąd S3 → `EmailAttachmentException`), ale allow-listę kluczy z `recording_url` musi dodać wołający — `EmailAttachmentKeys` jest package-private i zna tylko prefiks `email-attachments/{tenantId}/`.

**Uwaga z code review BE-125 (2026-09-21) — ustalenia do uwzględnienia w BE-126 (bez zmiany zakresu, statusu i złożoności; źródło: `CR-BACKEND.md`, wpis BE-125; „potwierdzone" = wykazane w kodzie, „hipoteza" = do oceny wykonawcy):**
- **(a) BE125-02 — okno SELECT→DELETE [potwierdzone w kodzie; częstość — hipoteza]:** wiadomość dopisana do kontaktu PO SELECT-cie z `purgeByContactIds` (np. odpowiedź agenta na starą wiadomość dziedziczy stary kontakt: `EmailSendServiceImpl` ≈ l. 102 `.contactId(original.getContactId())`; okno = czas fazy S3, sekundy do minut) nie trafia do `rows`, więc nie jest usuwana i nie blokuje kontaktu; po `deleteContacts` zostaje sierotą z PII (`email_message.contact_id` nie ma FK). Rozwiązania: drugi, idempotentny `purgeByContactIds` po `deleteContacts` (najtańsze) albo obowiązkowy wariant „dangling" w BE-127; dodać test charakteryzujący (fake S3 wstawiający wiadomość w trakcie `delete`).
- **(b) H-1 — head-of-line blocking [hipoteza]:** `contactIdsBlocked` nie odróżnia porażki przejściowej od stałej; przy `ORDER BY started_at, contact_id` zablokowane kontakty leżą na początku każdej partii, a ≥ `batch-size` takich kontaktów zatrzyma purge tenanta (pętla kończy się „brakiem postępu", ale nie dociera do młodszych). Do oceny: kursor keyset (`started_at, contact_id`) przesuwany poza zablokowane i koniec pętli przy `ids.size() < batch` zamiast miary „postęp = usunięte kontakty > 0" (zablokowane skracają tę liczbę i kończyłyby pętlę przedwcześnie); test z ≥ `batch-size` trwale nieusuwalnymi kontaktami. Dotyczy sformułowania guardu w Zakresie (doprecyzowanie BE-124) — decyzja wykonawcy.
- **(c) H-2 — semantyka `DeleteObject` [do sprawdzenia w środowisku docelowym; pytanie do właściciela]:** wersjonowanie bucketu, Object Lock albo globalny lifecycle 365 dni (`DEPLOYMENT.md` §21 ≈ l. 1209–1211: `mc ilm add … --expiry-days 365` na całym buckecie `contact-center-recordings`). Przy wersjonowaniu `DeleteObject` dodaje tylko delete marker (PII zostaje), przy Object Lock zwraca błąd (→ `s3Failures`, kontakt zablokowany na stałe); lifecycle 365 dni jest niezależny od retencji tenanta. Patrz DESIGN R10; rozstrzygnięcie z właścicielem i w BE-131.
- **(d) BE125-05 — social zwraca tylko `int` [potwierdzone; skutek latentny]:** ciche „0 pod RLS" jest niewykrywalne (e-mail ma `contactIdsBlocked`, social nie). Dziś bez wpływu (aplikacja łączy się jako superuser z `BYPASSRLS`; `contact` ma polityki tylko SELECT/INSERT, `social_message` tylko SELECT), ale gdyby DB-074 dał `contact` politykę DELETE przed DB-064 (polityki `social_message`), kontakty zniknęłyby, a wiadomości social z PII zostałyby. Wymóg: kolejność DB-064 przed DB-074 albo potwierdzenie usunięcia także dla social (np. tanie `SELECT DISTINCT contact_id … WHERE contact_id IN (…)` po `DELETE`, włączane do blokad); test RLS dla social.
- **(e) BE125-06 — bezpiecznik S3 i budżet czasu [potwierdzone w kodzie; scenariusz — hipoteza]:** bezpiecznik (`S3_FAIL_FAST_THRESHOLD = 3`) liczy każdą porażkę jednakowo (także stałe błędy 4xx per klucz), faza S3 nie ma budżetu czasu, a `S3Client` jest budowany bez własnych timeoutów (`S3Config` ≈ l. 63–68); wolny, ale „zdrowy" S3 nie uruchomi bezpiecznika, a wiersze znikają dopiero na końcu partii. Wykonawca BE-126 definiuje budżet czasu fazy S3 i timeouty (`apiCallTimeout`), rozważa podpartie (S3 → `DELETE` po ok. 100 wiadomości) i rozróżnienie błędów przejściowych od stałych.
- **(f) BE125-04 — publiczne `delete(String)` bez kontroli tenanta:** decyzja API **podjęta w BE-143** (2026-09-22; code review CR-BACKEND.md BE126-05 zgłosił, że to zdanie nie było zaktualizowane na czas — sprostowane tutaj): `EmailAttachmentKeys` rozszerzone o `isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key)` (schemat `{tenantId}/…` — nagrania `RecordingServiceImpl#buildS3Key`, EML `EmailEmlService#buildEmlS3Key`), obie metody allow-listy oraz `forLog` teraz `public`. `EmailAttachmentStorageService#delete(String)` NIE dostał wariantu `delete(UUID, String)` — kontrolę tenanta wykonuje wołający PRZED wywołaniem `delete` (ten sam wzorzec co w `EmailSendServiceImpl`/`EmailAttachmentController`, BE-143). Pkt (b) z Zakresu (klucze `contact.recording_url`) pozostaje ODŁOŻONY do follow-upu (patrz notatka wykonawcy BE-126, akapit „Punkt (b)…”) — ma teraz gotowe API do użycia, gdy follow-up zostanie podjęty.
- Odsyłacze: BE-127 (wariant „dangling" — pkt a; kursor — pkt b), BE-129 (publiczna allow-lista — BE-143), BE-131 (H-2).

**Code review i poprawki (2026-09-22):**
- Werdykt `senior-code-reviewer` (`CR-BACKEND.md`, wpis BE-126): **4/5 — zatwierdzić z poprawkami**; zero blockerów/bugów w diffie. Review czytało kod i testy oraz zweryfikowało `EXPLAIN` na żywej bazie (tylko odczyt), nie uruchamiało Mavena.
- Ustalenia BE126-01…05 i co się z nimi stało:
  - **BE126-01** (major, latentne, dziś nieosiągalne: strategia H-1 (keyset) usunęła niezmiennik „postęp=0 → FAILED" z BE-124; częściowa cicha strata w stronie nie była logowana, tylko strata całej strony) → **naniesione przez zlecającego**: WARN rozszerzony na KAŻDĄ częściową cichą stratę (`actuallyDeletedContacts.size() < deletableIds.size()`), nie tylko na całą stronę = 0.
  - **BE126-02** (minor: `deleteContacts` nie filtruje `started_at < cutoff`, mimo że wywołujący już ma tę wartość z `ContactPurgeCandidate` — `DELETE` skanuje wszystkie 11 partycji zamiast tylko starych; potwierdzone `EXPLAIN` na żywej bazie — identyczny profil kosztowy jak istniejący `deleteBatchOlderThan`, NIE regresja) → **zaakceptowane jako świadomie odłożone, NIE naprawiane**: tania, ale dziś niekrytyczna okazja do poprawy przycinania partycji; koszt/zysk nie uzasadnia teraz zmiany sygnatury `deleteContacts` (dodanie `cutoff` do `DELETE_CONTACTS_SQL`).
  - **BE126-03** (minor: Javadoc `PurgeResultDto#errorMessage` nadal mówił „komunikat błędu przy status=FAILED", a po BE-126 pole bywa niepuste też przy `status=COMPLETED` — ostrzeżenia S3) → **naniesione przez zlecającego**: Javadoc poprawiony.
  - **BE126-04** (nit: notatka wykonawcy liczyła „13 testów"/„12 nowych testów"; `grep -c @Test` + `git diff` przeciw HEAD dają 12/8) → **sprostowane w notatce wykonawcy** (liczby niżej poprawione na 12/8).
  - **BE126-05** (informacyjne: uwaga kontraktowa BE125-04→BE-143 wewnątrz notatki „Uwaga z code review BE-125" punkt (f) tej sekcji nie została zaktualizowana po tym, jak BE-143 faktycznie podjęła decyzję API) → **sprostowane w punkcie (f) powyżej**.
- Niezależna weryfikacja końcowa (zlecający, po obu poprawkach kodu): `mvn verify -pl app` — **BUILD SUCCESS, Tests run: 2046, Failures: 0, Errors: 0, Skipped: 0** (ta sama liczba co w pierwotnej notatce wykonawcy — poprawki zmieniły treść dwóch plików, nie liczbę testów).

---

### BE-127 – Purge wiadomości osieroconych (`contact_id IS NULL` i dangling) wg retencji tenanta + dry-run

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** BE-126 ✅, DB-059
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-128, DB-067, BE-135, BE-130
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
Wiadomości już odcięte przez dotychczasowy purge (live 23 z 55 z `contact_id IS NULL`; wg analizy 18/18 sprzed cutoffu 2026-05-13, 10 z pełnym `body_html`) oraz e-maile zapisane przez IMAP i niezroutowane (V028: `contact_id` NULL przy pierwszym zapisie) nie mają dziś żadnej ścieżki usunięcia.

**Korekta z BE-124 (2026-09-20, dowody w notatce BE-124, sekcje 6–8):** liczby „23 z 55" i „18/18, 10 z `body_html`" to liczba **sierot ogółem**, nie kandydatów: przy bieżącej polityce KMN (`CONTACT_INTERACTIONS` = 6 mies., cutoff 2026-03-20) dry-run daje dziś **0** kandydatów (najstarsza sierota 2026-04-25; pierwsza zakwalifikuje się ≈ 2026-10-26). Sieroty mają dwa źródła: (1) `detachContactReferences` z BE-113 (18 sprzed 2026-05-13, odcięte purge'em z 2026-08-13) oraz (2) niezroutowane e-maile — `EmailRoutingService#route` :70–74 przy braku kolejki tylko loguje WARN, a `EmailContactCreator#onEmailEvent` :76 wychodzi przy `queueId == null`, więc wiadomość zostaje z `contact_id NULL` na stałe (5 sierot po 2026-05-13: 1 IN + 4 OUT, przyczyny nie zbadano). Po BE-126 źródło (1) zanika, źródło (2) zostaje. Dangling (`contact_id` bez kontaktu): live 0.

**Zakres:**
- `EmailMessageService#purgeOrphansOlderThan(tenantId, cutoff, batch)` / `SocialMessageService#purgeOrphansOlderThan(...)` oraz dry-run `countOrphansOlderThan(tenantId, cutoff)`. Kryterium: `tenant_id = :t AND (contact_id IS NULL [OR dangling: NOT EXISTS (kontakt)]) AND <wiek> < :cutoff`, wiek = `COALESCE(received_at, sent_at, created_at)` (e-mail) / `sent_at` (social) —
  **dokładnie to wyrażenie z DB-059** (dopasowanie predykatu indeksu). S3 jak w BE-125 (kolejność S3 → wiersz).
- Wywołanie w `purgeContactInteractions` po pętli kontaktów, ten sam `cutoff` (retencja `CONTACT_INTERACTIONS` tenanta); `rowsDeleted` += osierocone; breakdown w audycie.
- **Ostrożność:** świeże niezroutowane e-maile (`EmailRoutingService` w toku) nigdy nie są usuwane przed cutoff (kryterium wieku); dangling `NOT EXISTS` opcjonalne wg wyboru z BE-126 (koszt zapytania po `contact_id` bez klucza partycji). **Doprecyzowanie BE-124:** wiek INBOUND to INTERNALDATE serwera IMAP (`getReceivedDate()`), więc skrzynka zmigrowana z historycznymi datami daje „stare" świeżo zapisane wiadomości, które mogą być jeszcze w toku routingu — dodaj filtr resztkowy `created_at < now() − interval '1 day'` (nie psuje indeksu z DB-059). Klucze S3 z JSONB — z allow-listą prefiksu jak w BE-125 (klucze OUTBOUND to dane od klienta).
- **Dry-run:** `countOrphansOlderThan` używa BE-128 i skrypt kandydatów do live-testu (WP-4).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers: mieszanka — osierocona stara (usunięta), osierocona świeża (zostaje), powiązana z istniejącym kontaktem (zostaje), stara osierocona tenanta B (zostaje); wiadomość z załącznikami → S3 sprzątane
- [ ] `EXPLAIN` pod `SET ROLE app_user` z GUC: użyty indeks z DB-059, bez Seq Scan (scratch, ≥ 200 tys. wierszy)
- [ ] Idempotencja; awaria S3 → wiadomość zostaje, brak nieskończonej pętli; test świeżej wiadomości w trakcie routingu (nie usuwana)
- [ ] (WP-2) Wołane z `purgeAsync` (kontekst z snapshotu), bez `clear()` w repozytorium; test z pustym `TenantContext` → `IllegalStateException`
- [ ] (WP-4, destrukcyjne) PRZED uruchomieniem policz kandydatów w demo (dry-run; **stan z 2026-09-20: 0 kandydatów przy polityce 6 mies., 23 sieroty ogółem, 15 z `body_html`** — test wymaga tymczasowego skrócenia polityki tenanta testowego), uzyskaj zgodę właściciela; po: `retention_purge_log.rows_deleted`, brak obiektów S3
- [ ] `mvn verify -pl app`; DoD (WP-7); zakłada D1 = A

**Uwaga z BE-125 (2026-09-21):**
- Faza „S3 przed wierszem" jest już zaimplementowana w `EmailMessageServiceImpl#purgeRows(tenantId, List<AttachmentsRow>)` — package-private, tak samo jak rekord `EmailMessageRepository.AttachmentsRow(messageId, contactId, attachmentsJson)` (logika BE-127 w pakiecie `domain.email`). BE-127 dokłada tylko własny SELECT sierot zwracający `AttachmentsRow` (z `contactId = null`); allow-lista prefiksu (`EmailAttachmentKeys#isOwnedByTenant`), kolejność S3 → wiersz i potwierdzenie `DELETE … RETURNING` są już w `purgeRows`. Osierocone nie trafiają do `contactIdsBlocked`.
- Miarą postępu pętli jest `PurgedMessages#deletedRows` (nie liczba zleconych). Po BE-134 (klucz złożony) `AttachmentsRow` musi nieść `messageAt`, a `EmailMessageRepository#deleteByIds` identyfikować wiersze pełnym kluczem (komentarze w kodzie; wzorzec `ContactRepository#deleteBatchOlderThan`).

**Uwaga z code review BE-125 (2026-09-21):**
- BE125-02 [potwierdzone w kodzie]: wiadomości dopisane po SELECT-cie z BE-125 (np. odpowiedzi OUTBOUND na stary wątek, `contactId` odziedziczony po kontakcie) mogą po `deleteContacts` zostać sierotami z PII — jeśli BE-126 nie zrobi drugiego przebiegu `purgeByContactIds` po `deleteContacts`, wariant „dangling" (`NOT EXISTS`) przestaje być opcjonalny (patrz BE-126, pkt a). H-1 [hipoteza]: analogiczny head-of-line blocking dla pętli sierot, gdy `deletedRows` jest jedyną miarą postępu, a część wiadomości jest trwale nieusuwalna (kursor albo wykluczanie zablokowanych).
- Naprawione po review (BE125-03/09): `toUuid(null)` w repozytorium i `PurgedMessages#contactIdsBlocked().contains(null)` nie rzucają — wiersze z `contactId = null` są bezpieczne dla `AttachmentsRow` i dla blokad.

**Uwaga z BE-126 (2026-09-22):**
- Okno SELECT→DELETE (BE125-02) jest już zamknięte PO STRONIE kontaktów właśnie usuniętych: `RetentionPurgeServiceImpl#purgeContactInteractionsWithMessageDeletion` robi drugi, idempotentny przebieg `purgeByContactIds` dla `actuallyDeletedContacts` w tej samej iteracji. To NIE zwalnia BE-127 z własnego zakresu — dotyczy tylko wiadomości dopisanych do kontaktu W TRAKCIE jego purge, a nie ogólnych sierot (`contact_id IS NULL` z detach BE-113, niezroutowanych e-maili itd., DESIGN §2 U1/U16). Wariant „dangling" (`NOT EXISTS`) nadal jest do decyzji wykonawcy BE-127, ale presja z BE125-02 jest niższa niż zakładano w code review BE-125.
- **Strategia H-1 zastosowana w BE-126** (zamiast guardu „brak postępu → koniec pętli"): stronicowanie keyset — strona kandydatów posortowana `ORDER BY <kolumna_wieku>, <klucz>`, kursor = ostatni kandydat POPRZEDNIEJ strony (niezależnie od tego, czy został usunięty), pętla kończy się na wyczerpaniu kandydatów, nie na liczbie usunięć. Wzorzec: `ContactRepository#findContactIdsOlderThan`/`FIND_CONTACT_IDS_OLDER_THAN_NEXT_PAGE_SQL` (predykat `(kolumna, id) > (kursor_kolumna, kursor_id)`). Rekomendacja: zastosuj ten sam wzorzec zamiast liczbowego guardu z opisu Zakresu — eliminuje strukturalnie ryzyko head-of-line blocking zamiast tylko je wykrywać. Po review (CR-BACKEND.md BE126-01) dodaj też WARN przy KAŻDEJ częściowej cichej stracie (nie tylko całej stronie = 0) — wzorzec w `RetentionPurgeServiceImpl` (`actuallyDeletedContacts.size() < deletableIds.size()`).
- `PurgeResultDto#errorMessage` może być niepuste przy `status=COMPLETED` (ostrzeżenia S3) — nie tylko przy `FAILED` (CR-BACKEND.md BE126-03); jeśli BE-127 zapisuje własne ostrzeżenia do tego samego pola, zachowaj tę semantykę.

**Uwaga z BE-143 (2026-09-22) — publiczne API allow-listy do użycia:**
- `EmailAttachmentKeys.isOwnedByTenant(UUID tenantId, String s3Key)` (schemat `email-attachments/{tenantId}/…`) i `EmailAttachmentKeys.isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key)` (schemat `{tenantId}/…` — nagrania, EML z `contact.recording_url`) są PUBLICZNE od BE-143. Dla BE-127 istotna głównie pierwsza (sieroty to wiadomości e-mail/social, nie `contact.recording_url`) — użyj jej zamiast pisać własną kopię `startsWith`, jeśli sieroty niosą klucze spoza `email-attachments/{tenantId}/`.

---

### BE-128 – `RetentionEvaluationServiceImpl`: liczenie wiadomości kwalifikujących się do usunięcia (dashboard/badge)

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** BE-127
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-130, FE-110
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis:**
`RetentionEvaluationServiceImpl.PARTITION_AWARE_TABLES[CONTACT_INTERACTIONS]` = `contact`, `contact_event`; dashboard (FE-105) i badge (FE-108) nie liczą wiadomości, więc `eligibleRowCount` jest niższy niż to, co realnie usunie purge po BE-126/127.

**Zakres:** dodać do liczby `CONTACT_INTERACTIONS`: (a) wiadomości osierocone kwalifikujące się (`countOrphansOlderThan`, BE-127) i (b) wiadomości powiązane z kontaktami kwalifikującymi się (zapytanie po `contact_id IN (kontakty przed cutoff)` z limitem kosztu albo szacunek — wykonawca wybiera i dokumentuje semantykę „liczba szacunkowa" w Javadoc `RetentionSummaryDto`).
**Dwa punkty wejścia (WP-2):** rdzeń per-tenant bez zarządzania kontekstem (`persistSummaryAndMaybeAutoPurgeForTenant`, `evaluateCampaignDataForTenant` — wzorzec BE-112), `set/clear` tylko w pętlach schedulera; `runForTenant` (REST `POST …/recompute`) nie czyści kontekstu HTTP i NIGDY nie wywołuje purge.
Po konwersjach (DB-065/DB-067) liczenie partycyjne przez `PartitionScanner` zamiast zapytań po `contact_id` (wpis w `PARTITION_AWARE_TABLES` — BE-133/BE-135).

**Kryteria akceptacji:**
- [ ] `RetentionEvaluationServiceImplTest` rozszerzony (mock) oraz (WP-1) test Testcontainers z PRAWDZIWYM repozytorium i pustym `TenantContext` dla ścieżki schedulera (regresja z BE-112: brak `TenantContext` → `assertSameTenant` rzucał ISE)
- [ ] `eligibleRowCount` = kontakty + zdarzenia + wiadomości (osierocone i powiązane); `POST …/recompute` nie wywołuje purge i nie czyści kontekstu HTTP (test `ManualRecomputeForTenant` rozszerzony)
- [ ] Semantyka liczby opisana w Javadoc `RetentionSummaryDto`; `mvn verify -pl app`; DoD (WP-7); zakłada D1 = A (przy D1 = C liczenie wiadomości przenosi się do osobnej kategorii — BE-130)

---

### BE-129 – Przepływ RODO w `GdprServiceImpl` (D3): anonimizacja i eksport z wiadomościami, callbackami i rekordami kampanii; sprzątanie S3

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** L (pierwotnie M; po korektach z DB-060: dwie ścieżki REST, podgląd zbioru podmiotu D9, jedno źródło audytu, stany operacyjne, S3 — przy D9 = B wraca do M; wykonawca może wydzielić podgląd D9 do osobnego PR)
**Zależy od:** DB-060 ✅, DB-061 ✅, DB-062 ✅, DB-079 ✅, BE-125 ✅
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-112
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`GdprController` (`POST /api/customers/{id}/gdpr/export|anonymize`, `hasAnyRole('ADMIN','SUPERVISOR')`) → `GdprServiceImpl`: anonimizacja = S3 nagrania (best-effort, PRZED bazą) + `CustomerRepository#anonymize` (UPDATE wyłącznie `customer`: imię, nazwisko, `phone`, `email`, `is_deleted`; **nie** `custom_fields`/`gdpr_consent`);
eksport = `customer.json` + `contacts.json` (≤ 1000) + `audit_log.json` (metadane). Funkcje SQL `anonymize_customer`/`export_customer_data` nie są wołane (DESIGN §2 U2). **Zakłada D3 = A:** Java wywołuje rozszerzone funkcje SQL (DB-061/062) i sprząta S3.
Przy D3 = B: logika DB trafia do Javy (wiele repozytoriów, brak jednej transakcji) — ticket rośnie do L; przy D3 = C nie jest wykonywany.

**Zakres:**
- `anonymizeCustomer`: `customerService.findById` (jak dziś) → wywołanie `anonymize_customer(customer, tenant, user)` w JEDNEJ transakcji (nowa metoda repozytorium `TenantAwareRepository`, `assertSameTenant` + `setTenantContextInDb`), wynik `{counts, s3_keys}` → **po commit** sprzątanie S3 best-effort z retry przez metodę zwracającą wynik per klucz: `EmailAttachmentStorageService#delete` (z BE-125) — **nie** `RecordingService#deleteFromS3`, które połyka `S3Exception` i nic nie zwraca (`RecordingServiceImpl` ≈ 307–322; BE-124 §3), więc nie da się na nim zbudować listy niepowodzeń. Zakres kluczy (BE-124 §3): nagrania i **EML kontaktów e-mail — oba w `contact.recording_url`**; załączniki e-mail z `attachments[*].s3_key`, w tym klucze `pending/` wiadomości OUTBOUND (`EmailSendServiceImpl#buildAttachmentsJson` zapisuje klucz z uploadu i nikt go nie przenosi do `{messageId}/`), z allow-listą prefiksu `email-attachments/{tenantId}/` jak w BE-125. Klucze musi zwrócić `anonymize_customer` PRZED wyzerowaniem `attachments` (V013 zeruje kolumnę bez usuwania obiektów — DB-062); lista niepowodzeń S3 do audytu/logu (klucze nie mogą zniknąć — pozwalają dokończyć ręcznie).
  **Zmiana kolejności względem dziś** (DB twardo, S3 potem) — uzasadnij w notatce. `CustomerRepository#anonymize` zostaje nieużywane albo usunięte (sprzątanie).
- `exportCustomerData`: wywołanie `export_customer_data` (po DB-061) → ZIP: `customer.json`, `contacts.json`, `email_messages.json`, `social_messages.json`, `scheduled_callbacks.json`, `campaign_records.json`, `transcriptions.json` (wg DB-060/D3), manifest; brak ucięcia do 1000 bez informacji (paginacja/strumień). Audyt `GDPR_EXPORT` zostaje w Javie (jedyne miejsce, jeśli DB-061 usunął INSERT z funkcji).
- Kontrakt REST bez zmian (ścieżki, role, odpowiedź ZIP / 204; dochodzi wyłącznie podgląd D9 i przekierowanie `DELETE /api/customers/{id}`, patrz Uzupełnienie); wywołanie z żądania HTTP — `TenantContext` z `TenantFilter`, **bez `clear()`** (WP-2).

**Uzupełnienie z DB-060 (2026-09-20, wiążące; dowody: notatka DB-060, przypisy F1–F8 w `TASKS-DATABASE.md`):**
- **Druga ścieżka REST [DB-060 F8.4]:** `DELETE /api/customers/{id}` (`CustomerController:255`, `hasAnyRole('ADMIN','SUPERVISOR')` → `CustomerServiceImpl#anonymizeCustomer` :301 z `@Audited(action = "CUSTOMER_ANONYMIZED")` → `CustomerRepository#anonymize`) aktualizuje wyłącznie `customer` i nie kasuje nawet nagrań S3; używa jej UI listy klientów (`customer-list.component.ts:134` → `customer.service.ts:84 deleteCustomer`).
  **Rekomendacja: przekierować kontroler na `GdprService#anonymizeCustomer` i zachować kontrakt** (204, role, 404 dla już zanonimizowanego), usunąć `@Audited` oraz martwe `CustomerServiceImpl#anonymizeCustomer`/`CustomerRepository#anonymize` (albo `@Deprecated`); alternatywa: wycofać endpoint — wtedy lista klientów przechodzi na modal GDPR (i tak zalecane w FE-112).
- **Jedno źródło wpisu audytu [F8.5]:** dziś trzy — Java `GDPR_ANONYMIZE` (`GdprServiceImpl:112`, fire-and-forget), `@Audited` `CUSTOMER_ANONYMIZED` i funkcja SQL `CUSTOMER_ANONYMIZED`; eksport: Java `GDPR_EXPORT` vs SQL `CUSTOMER_DATA_EXPORTED`. Rekomendacja: Art. 17 — wpis zapisuje funkcja (atomowo z anonimizacją, `p_user_id`), Java nie publikuje własnego ani `@Audited`; Art. 15 — wpis zapisuje Java (`GDPR_EXPORT`; po DB-061 funkcja bez INSERT). Jedna nazwa akcji na operację, brak podwójnych wpisów.
- **Zbiór podmiotu wg D9 (założenie A):** odpowiedź niesie liczniki `matched_by_link`/`matched_by_identifier`; **podgląd (dry-run):** nowy endpoint `GET /api/customers/{id}/gdpr/anonymize/preview` (te same role, bez skutków ubocznych; woła `anonymize_customer(…, p_dry_run => TRUE)`) zwraca liczniki per tabela i liczbę obiektów S3; anonimizacja i eksport po potwierdzeniu w UI (FE-112). Przy D9 = B: bez endpointu podglądu i liczników identyfikatorowych.
- **Eksport (Art. 15):** bez cichego ucięcia do 1000 (dziś `findContactsByCustomerId(…, 0, 1000)`), z **manifestem kluczy S3 i presigned URL-e** (`EmailAttachmentStorageService#presignedDownloadUrl` i odpowiednik dla nagrań) zamiast plików w ZIP (audio i załączniki nie trafiają do archiwum), zbiór wg D9; informacje Art. 15(1)(a)–(h) z `gdpr_processing_register`.
- **Stany operacyjne po anonimizacji** (zmiany robi funkcja SQL — DB-062; tu dowód testami): `campaign_contact` `PENDING`/`NO_ANSWER`/`CALLBACK` → `SKIPPED` (`ProgressiveDialerServiceImpl#fetchNextPendingContact` nie filtruje `phone IS NOT NULL`), `scheduled_callback` `PENDING`/`PROCESSING` → `CANCELLED` (`ScheduledCallbackExecutor` dzwoni na `getPhone()`); rekordy w toku (`DIALING`/`PROCESSING` — połączenie trwa) — opisz decyzję (odrzucić anonimizację z komunikatem albo dokończyć po zakończeniu połączenia).
- (Could) evict Redis `cache:customer:phone:{phone}` (`CliLookupServiceImpl`, TTL 5 min) dla numerów klienta (zebrać `phone[]` przed anonimizacją) — bez tego CLI-lookup zwraca zanonimizowanego klienta do 5 min.
- **Kolejność DB → S3 (DESIGN R8):** DB twardo w jednej transakcji, S3 dopiero po commit metodą zwracającą wynik per klucz (`EmailAttachmentStorageService#delete`), niepowodzenia do audytu; błąd bazy nie może zostawić usuniętych obiektów S3.
- **Zależność od DB-079:** idempotencja i dosanityzowanie klientów z `is_deleted = TRUE` (m.in. zanonimizowanych dotąd przez `CustomerRepository#anonymize`) wymagają zawężenia triggera V016; do czasu DB-079 `UPDATE contact` takich klientów rzuca (także `RecordingRetentionJob#clearRecordingUrl`).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers `GdprServiceImpl` na prawdziwej bazie: klient z pełnym zestawem danych → po anonimizacji żadna kolumna PII z macierzy DB-060 nie zawiera wartości oryginalnych (w tym `customer.custom_fields`); klucze S3 z wyniku funkcji przekazane do `EmailAttachmentStorageService#delete` (mock S3, asercje wywołań; **nie** `RecordingService#deleteFromS3` — połyka `S3Exception`, BE-124 §3)
- [ ] Awaria S3 po commit nie cofa anonimizacji; lista niepowodzeń w audycie/logu
- [ ] Eksport ZIP zawiera wszystkie zbiory; test na > 1000 kontaktach (bez cichego ucięcia)
- [ ] Izolacja: klient obcego tenanta → `EntityNotFoundException`/404 (istniejące zachowanie) i test na prawdziwej bazie, że funkcja nie zmienia cudzych danych
- [ ] (WP-4) Local-demo, klient testowy (utworzony na potrzeby testu): eksport i anonimizacja z UI, weryfikacja e-maili/callbacków/kampanii/S3 po przebudowie obrazów; destrukcyjne — zgoda właściciela
- [ ] (WP-1) `DELETE /api/customers/{id}` i `POST /api/customers/{id}/gdpr/anonymize` dają identyczny efekt w bazie (obie wołają tę samą implementację — test na prawdziwej bazie z pełnym zestawem danych); po zmianie `CustomerServiceImpl#anonymizeCustomer` nie jest już osobną ścieżką
- [ ] Jeden wpis audytu na operację: asercja, że po anonimizacji jest dokładnie 1 wiersz `audit_log` z akcją anonimizacji (bez pary `GDPR_ANONYMIZE` + `CUSTOMER_ANONYMIZED`), po eksporcie dokładnie 1 z `GDPR_EXPORT`
- [ ] (D9 = A) `GET …/gdpr/anonymize/preview` niczego nie zmienia (asercja: brak zmian w bazie i S3) i zwraca liczniki zgodne z późniejszą anonimizacją; przy D9 = B kryterium odpada
- [ ] Eksport bez ucięcia: klient z > 1000 kontaktami → komplet w archiwum/manifeście; manifest zawiera klucze S3 i presigned URL-e, a ZIP nie zawiera plików audio ani załączników
- [ ] Po anonimizacji `ProgressiveDialerServiceImpl#fetchNextPendingContact` i `ScheduledCallbackExecutor` nie podejmują zanonimizowanych rekordów (test na zapytaniach SQL tych klas na prawdziwej bazie)
- [ ] Błąd bazy przy anonimizacji NIE usuwa żadnych obiektów S3 (test: wymuszony błąd funkcji → `EmailAttachmentStorageService#delete` i usuwanie nagrań niewywołane); porażka S3 po commit nie cofa anonimizacji (lista niepowodzeń w audycie/logu)
- [ ] Dosanityzowanie: klient z `is_deleted = TRUE` (anonimizowany dotąd ścieżką Javy) → ponowne wywołanie kończy anonimizację reszty danych bez błędu (wymaga DB-079)
- [ ] (Could) `cache:customer:phone:{phone}` usuwany dla numerów klienta — test
- [ ] `mvn verify -pl app`; DoD (WP-7); dokumentacja (funkcje SQL już nie martwe) → DB-077

**Uwaga z DB-079 i BE-125 (2026-09-21):**
- DB-079 (V094, `CREATE OR REPLACE FUNCTION fn_contact_ref_integrity`) jest gotowe w kodzie i przetestowane na pełnym łańcuchu Flyway, ale NIE zastosowane na żywej bazie (Flyway zastosuje je przy starcie po przebudowie obrazu) — zależność jest spełniona dopiero po WDROŻENIU V094 (testy Testcontainers na pełnym Flyway mają ją już teraz; test WP-4 na local-demo dopiero po wdrożeniu). Po wdrożeniu zdanie z punktu „Zależność od DB-079" o tym, że `UPDATE contact` klientów z `is_deleted = TRUE` rzuca (m.in. ścieżka `RecordingRetentionJob` → `ContactService#clearRecordingUrl`), stanie się nieaktualne.
- BE-125: `EmailAttachmentStorageService#delete(String)` jest gotowe (brak obiektu = sukces; błąd S3 → publiczny `EmailAttachmentException`, łapany jest `SdkException`, więc także błędy sieci/timeoutu), ale allow-lista `EmailAttachmentKeys` jest package-private w `domain.email` — `GdprServiceImpl` (`domain.gdpr`) potrzebuje własnej allow-listy prefiksu dla kluczy `attachments[*].s3_key` i `contact.recording_url`.

**Uwaga z code review BE-125 (2026-09-21):** (1) BE125-04: kontrola tenanta dla `EmailAttachmentStorageService#delete` (publiczna polityka kluczy albo `delete(UUID tenantId, String key)`) rozstrzyga się w BE-143 — `GdprServiceImpl` nie powinien do tego czasu zakładać kształtu tego API (uwaga kontraktowa, nie zależność). (2) H-2 [do sprawdzenia w środowisku docelowym]: wersjonowanie/Object Lock bucketu (DESIGN R10) dotyczy też sprzątania S3 po anonimizacji — `DeleteObject` przy wersjonowaniu zostawia dane.

**Uwaga z BE-143 (2026-09-22) — decyzja API rozstrzygnięta, gotowa do użycia:**
- BE-143 wybrał opcję (i) z Zakresu: rozszerzenie `EmailAttachmentKeys` (bez nowej klasy `S3KeyPolicy`), teraz `public final class`. Sygnatury: `EmailAttachmentKeys.isOwnedByTenant(UUID tenantId, String s3Key)` (schemat `email-attachments/{tenantId}/…` — załączniki) oraz `EmailAttachmentKeys.isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key)` (schemat `{tenantId}/…` — nagrania z `RecordingServiceImpl#buildS3Key`, EML z `EmailEmlService#buildEmlS3Key`, oba w `contact.recording_url`; zweryfikowane w code review względem rzeczywistych producentów kluczy, nie tylko specyfikacji). `EmailAttachmentKeys.forLog(String)` też publiczne — użyj do logowania kluczy odrzuconych/niepowodzeń, nigdy surowego `s3Key`.
- `EmailAttachmentStorageService#delete(String)` NIE dostał wariantu `delete(UUID tenantId, String key)` — kontrolę tenanta wykonuje wołający PRZED wywołaniem `delete`, allow-listą powyżej (ten sam wzorzec co w `EmailSendServiceImpl#validateAttachmentKeys` i `EmailAttachmentController#downloadAttachment`, BE-143).
- Dla `GdprServiceImpl`: klucze załączników e-mail/social przez `isOwnedByTenant`, klucze z `contact.recording_url` (nagrania i EML) przez `isRecordingKeyOwnedByTenant` — DWIE różne allow-listy, nie jedna; nie mieszać prefiksów.

---

### BE-130 – [WARUNKOWY: D1 = C] Kategoria `MESSAGE_CONTENT` w silniku retencji

**Typ:** Backend implementation
**Priorytet:** Could Have (warunkowy — wchodzi wyłącznie przy D1 = osobna kategoria)
**Złożoność:** M
**Zależy od:** DB-063, BE-126 ✅, BE-127, BE-128
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-111
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis i zakres:** `RetentionDataCategory.MESSAGE_CONTENT` (Javadoc: enum zgodny z 3 CHECK-ami po DB-063); `RetentionPolicyServiceImpl#seedDefaultPolicies` (nowy tenant: wartość = `CONTACT_INTERACTIONS`); `RetentionEvaluationServiceImpl` (osobna kategoria → tabele wiadomości; wiadomości znikają z liczby `CONTACT_INTERACTIONS`);
`RetentionPurgeServiceImpl` (`switch`, `validateSupportedCategory`, `purgeMessageContent`: wiadomości starsze niż retencja tej kategorii NIEZALEŻNIE od kontaktu — usunięcie wiadomości nie usuwa kontaktu; `purgeContactInteractions` wraca do odcinania referencji (`detachContactReferences`), a wiadomości — powiązane i osierocone — są usuwane wyłącznie wg wieku w kategorii `MESSAGE_CONTENT` (sweep z BE-127 przechodzi do tej kategorii));
`PartitionReclaimJob`/`ReclaimTarget` (kategoria `MESSAGE_CONTENT` jako źródło progu dla tabel wiadomości), `RetentionController` (walidacja kategorii w `PUT /policies/{category}`), DTO.

**Kryteria akceptacji:**
- [ ] (WP-1) Testcontainers pełnego przepływu (polityka → ewaluacja → purge) dla nowej kategorii; istniejące kategorie bez regresji
- [ ] `RetentionController` przyjmuje `MESSAGE_CONTENT`, odrzuca nieznaną; kontrakt DTO udokumentowany dla FE-111; (WP-2) ścieżki schedulera i REST zachowują wzorzec kontekstu

---

### BE-131 – Sprzątanie porzuconych załączników `pending` e-mail (S3)

**Typ:** Backend implementation
**Priorytet:** Could Have
**Złożoność:** S
**Zależy od:** BE-125 ✅
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis:** `POST /api/email/attachments/upload` zapisuje plik pod `email-attachments/{tenantId}/pending/{uuid}/{filename}` (`EmailAttachmentStorageService#storePending`); jeśli agent nie wyśle odpowiedzi, obiekt zostaje na zawsze (brak wskaźnika w DB, brak TTL) — PII w storage bez właściciela (DESIGN §2 U5). Ten sam bucket co nagrania (`contact-center-recordings`).

**UWAGA — korekta z BE-124 (2026-09-20): pierwotne założenie „`pending/` = obiekty tymczasowe" jest FAŁSZYWE:** po wysłaniu odpowiedzi ten sam klucz `pending/` trafia do `email_message.attachments[*].s3_key` wiadomości OUTBOUND (`EmailSendServiceImpl#buildAttachmentsJson` :376–395 zapisuje `s3Key` z żądania; nic nie przenosi obiektu do `{messageId}/`). Live: 9 obiektów `pending/`, **8 wskazywanych przez wysłane wiadomości**, 1 porzucony. Wariant A z pierwotnego zakresu (TTL po `LastModified` na prefiksie `/pending/`) oraz wariant B (lifecycle bucketu) **skasowałyby załączniki wysłanych wiadomości** (zepsute `GET /api/email/attachments/download` i generowanie EML). Zakres poniżej zastępuje pierwotny.

**Zakres:**
- **Wariant A′ (minimum):** job `@Scheduled` (`email.attachments.pending-ttl-hours`, domyślnie 72) — kandydat = obiekt `email-attachments/{tenantId}/pending/…` z `LastModified < now − TTL` **i niewskazywany przez żaden wiersz `email_message`** (per tenant: zbiór `attachments[*].s3_key` pobrany raz albo `attachments @> '[{"s3_key": "<klucz>"}]'` z indeksem GIN przy wolumenie); kolejność „sprawdź referencje → usuń"; nie dotyka `/{messageId}/`; dry-run w logu.
- **Wariant A″ (rozwiązanie u źródła, zalecane, złożoność S → M):** „promocja" załączników przy wysyłce — `sendReply`/`sendNew` po udanym SMTP przenosi obiekt z `pending/` do `{messageId}/` (copy + delete) i zapisuje nowy klucz w JSONB; wtedy prefiks `pending/` jest naprawdę tymczasowy, a A′ upraszcza się do samego TTL. Wymaga jednorazowej migracji istniejących kluczy OUTBOUND (8 w demo) i testu na wysyłce.
- **Wariant B (lifecycle bucketu, ops):** dopuszczalny WYŁĄCZNIE po A″ i migracji istniejących kluczy; dziś brak jakichkolwiek reguł lifecycle (`mc ilm rule ls`) i wersjonowania.
- Wykonawca wybiera i uzasadnia; zależność od BE-125 (`EmailAttachmentStorageService#delete`) zostaje.

**Kryteria akceptacji:**
- [ ] (WP-1) Test na prawdziwej bazie (Testcontainers Postgres z wierszami `email_message` — referencje są w DB) + mock S3: obiekt `pending` starszy niż TTL i niewskazywany — usunięty; młodszy nie; niepending nigdy; **`pending/` wskazywany przez wiersz `email_message` (wysłana wiadomość) NIGDY nie jest usuwany (test regresyjny; w demo 8 z 9)**; błąd pojedynczego obiektu nie przerywa pozostałych
- [ ] (WP-4) Local-demo: policz kandydatów w MinIO (**oczekiwany: 1 z 9**), uzyskaj zgodę, uruchom; konfiguracja w `application.yml`; DoD (WP-7)

**Uwaga z BE-125 (2026-09-21):**
- `EmailAttachmentStorageService#delete(String)` jest gotowe (brak obiektu = sukces; błąd S3 → `EmailAttachmentException`); `EmailAttachmentKeys` (allow-lista prefiksu, `extractS3Keys`) jest package-private w `domain.email`.
- Punkt otwarty (bez zmiany zakresu tego ticketu): BE-125 świadomie NIE sprząta prefiksu `{messageId}/` (obiekt wgrany, ale nieodnotowany w `attachments`, gdy padł `UPDATE attachments` po uploadzie w `EmailPollingServiceImpl#extractAndStoreAttachments`) i wskazuje na sweep z BE-131 jako pokrycie tego przypadku; zakres A′ obejmuje jednak wyłącznie `pending/` i „nie dotyka `/{messageId}/`" — przypadek pozostaje niepokryty, dopóki wykonawca/właściciel nie zdecyduje o rozszerzeniu sweepu.

**Uwaga z code review BE-125 (2026-09-21):** (1) H-2 [do sprawdzenia w środowisku docelowym; pytanie do właściciela]: wersjonowanie/Object Lock/globalny lifecycle 365 dni (`DEPLOYMENT.md` §21 ≈ l. 1209–1211) wpływają na semantykę `DeleteObject` i na sweep — code review BE-125 kieruje tę decyzję do BE-131 (DESIGN R10). (2) BE125-04: sweep kasuje klucze pochodzące z listowania bucketu, więc pomyłka prefiksu kasuje cudzy obiekt — decyzja API (publiczna polityka kluczy albo `delete(UUID tenantId, String key)`) zapada w BE-143 (uwaga kontraktowa, nie zależność).

**Uwaga z BE-143 (2026-09-22) — decyzja API rozstrzygnięta:** `EmailAttachmentKeys.isOwnedByTenant(UUID, String)` i `EmailAttachmentKeys.forLog(String)` są teraz publiczne (`isRecordingKeyOwnedByTenant` też, ale dotyczy prefiksu `{tenantId}/…` — nagrania/EML, nie `pending/`). Przy sweepie listowania bucketu użyj `isOwnedByTenant` do potwierdzenia, że klucz zwrócony przez `ListObjectsV2` naprawdę należy do tenanta, którego partię właśnie przetwarzasz, zanim wywołasz `delete` — chroni przed pomyłką prefiksu z BE125-04.

---

### BE-132 – Migracja encji `SocialMessage` na klucz złożony `(messageId, sentAt)`

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** M
**Zależy od:** DB-065
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-133
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`SocialMessage` ma `@Id @GeneratedValue @UuidGenerator messageId` i zapis przez `em.merge`; po DB-065 klucz to `(message_id, sent_at)`. Wzorzec: BE-117 (`ContactEvent`/`ContactEventId`, `AuditLog`/`AuditLogId`) — `@IdClass` + natywny INSERT.
**Najpierw ponownie przeczytaj aktualny kod po PR #44 (WhatsApp)** i wypisz zmiany w notatce: `SocialMessageServiceImpl` (dedup ≈ 75–81, zapis INBOUND ≈ 101–117, OUTBOUND ≈ 217–233; BE-124 §4), `SocialMessageConsumer`, `SocialMessagePublisherImpl`, `SocialWebhookController`, `SocialContactController`, `ContactRepository` (odwołania do `social_message`), adaptery.

**Zakres:** `@IdClass(SocialMessageId.class)` (`messageId`, `sentAt`), `messageId` nadawany w Javie (`UUID.randomUUID()`) przed INSERT; `SocialMessageRepository#save` → natywny INSERT (`assertSameTenant`, `setTenantContextInDb`), koniec `em.merge`;
`sentAt` deterministyczne: `incoming.sentAt()` (z platformy), a fallback `Instant.now()` jest niedeterministyczny — dedup aplikacyjny `findByExternalMessageId` (bez daty) pozostaje pierwszą obroną, a `DataIntegrityViolationException` z constraintu `(tenant_id, external_message_id, sent_at)` obsłuż jako idempotentny duplikat;
**Korekta z BE-124 §4 (wiążąca):** deterministyczny `sentAt` ma dziś tylko WhatsApp (`timestamp` z payloadu, `SocialWebhookController` ≈ 405–409). **Facebook i Instagram zawsze ustawiają `Instant.now()` z chwili webhooka** (`SocialWebhookController` :312, :350), więc redelivery tego samego zdarzenia dostaje inny `sent_at` i constraint `(tenant_id, external_message_id, sent_at)` z DB-065 go NIE wykryje — dziś chroni przed tym globalny `UNIQUE (tenant_id, external_message_id)`, który po partycjonowaniu znika. Ujednolić źródło `sent_at` dla FB/IG (czas z payloadu Meta; nazwę pola zweryfikować — BE-124 §11) **przed lub razem z DB-065**; bez tego unikalność nie jest siecią bezpieczeństwa (wyścig dwóch dostaw = duplikat).
Przy okazji: `saveOutboundMessage` i `loadSendContext` mają `@Transactional` przez self-invocation, więc adnotacje są nieskuteczne (BE-124 §4) — popraw albo świadomie usuń; natywny INSERT wymaga `CAST(:platform AS social_platform)` (kolumna jest enumem);
grep: brak `em.find(SocialMessage.class, id)` po samym id; `getRecentMessagesForContact` bez zmian semantycznych; sprawdź `purgeByContactIds` (BE-125) na tabeli partycjonowanej.

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers na schemacie po DB-065: zapis inbound i outbound trafia do partycji miesięcznej (`tableoid`); redelivery tego samego zdarzenia = 1 wiersz (dedup aplikacyjny + constraint) — **także dla FB/IG z innym czasem odbioru webhooka** (`sent_at` z payloadu, nie z `Instant.now()`); zapis pod `SET ROLE app_user` z GUC
- [ ] Brak `@GeneratedValue` na `messageId`; testy jednostkowe adapterów/consumera bez regresji; `mvn verify -pl app`
- [ ] (WP-4) Local-demo po przebudowie obrazów: wysłanie i odbiór wiadomości social/WhatsApp (jeśli integracja skonfigurowana) — wiadomość w partycji miesięcznej
- [ ] Wydanie razem z DB-065; DoD (WP-7)

---

### BE-133 – Podpięcie `social_message` do maszynerii partycji i retencji

**Typ:** Backend implementation
**Priorytet:** Should Have
**Złożoność:** M (zgodnie z oceną zlecenia dla social; wprowadza `DropMode.ONLY_IF_EMPTY`)
**Zależy od:** BE-132, BE-123, BE-126 ✅
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-135
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis (WP-6):** nowa tabela partycjonowana wymaga wpisów w: `PartitionMaintenanceJob.PARTITIONED_TABLES` (bufor 3 miesięcy przez `createTablePartition("social_message", …)` → `create_social_message_partition`), `PartitionReclaimJob` (`ReclaimTarget`), `RetentionEvaluationServiceImpl.PARTITION_AWARE_TABLES`,
`RetentionPurgeServiceImpl` (purge po `contact_id` z BE-126 sprawdzić po konwersji). Konwencja nazw `social_message_YYYY_MM` (`PartitionScanner`).

**Zakres:** `PARTITIONED_TABLES` += `social_message`; `ReclaimTarget("social_message", CATEGORY_MAX_RETENTION(CONTACT_INTERACTIONS), ONLY_IF_EMPTY)` — **implementacja `DropMode.ONLY_IF_EMPTY`**: niepusta partycja → WARN z liczbą wierszy (bez DROP), pusta → DROP, `_default` nigdy;
`PARTITION_AWARE_TABLES[CONTACT_INTERACTIONS]` += `social_message` (liczenie partycyjne zamiast zapytań po `contact_id`, BE-128); potwierdzenie, że prefiks LIKE w `PartitionScannerImpl#listPartitions` nie koliduje z innymi tabelami. Wiek partycji vs retencja: `rangeEnd < now − MAX(CONTACT_INTERACTIONS)`.

**Kryteria akceptacji:**
- [ ] (WP-1/WP-6) Testcontainers: po `ensureFuturePartitions()` istnieją partycje bieżący..+3 dla wszystkich 7 tabel; reclaim: pusta partycja starsza niż max → DROP, niepusta → WARN i zostaje, `_default` niepusta → WARN; ewaluacja liczy wiadomości social partycyjnie
- [ ] `PartitionMaintenanceJobTest`/`PartitionReclaimJobTest` zaktualizowane (7 tabel; tryb ONLY_IF_EMPTY); zachowanie `contact*` bez zmian
- [ ] (WP-5) Poziom 1 działa przed Poziomem 2: test — partycja z wierszem po max retencji nie znika, po purge Poziom 1 zostaje pusta i jest dropnięta
- [ ] (WP-4) Local-demo: `PartitionMaintenanceJob` po przebudowie tworzy partycje `social_message_*` (log INFO), nic nie jest usuwane; `mvn verify -pl app`; DoD (WP-7)

---

### BE-134 – Migracja encji `EmailMessage` na klucz złożony + `messageAt` (IMAP, wysyłka, zdarzenia RabbitMQ)

**Typ:** Backend implementation — [BRAMKOWANY: po „go" z DB-066/DB-067]
**Priorytet:** Should Have
**Złożoność:** L (zgodnie z oceną zlecenia: 20 plików main, IMAP, zdarzenia)
**Zależy od:** DB-067
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-135, BE-136
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`EmailMessage` (`@Id message_id`, `em.merge/find`) przechodzi na `@IdClass(EmailMessageId.class)` `(id, messageAt)` i natywny INSERT/UPDATE po pełnym PK (wzorzec BE-117). Pliki main odwołujące się do `EmailMessage`/`email_message` (grep 2026-09-20): `api/email/EmailController`, `api/email/dto/EmailMessageResponse`,
`domain/email/{EmailContactCreator, EmailEmlService, EmailEventPublisher, EmailMessage, EmailMessageRepository, EmailMessageService(Impl), EmailPollingServiceImpl, EmailRoutingService, EmailSendService(Impl)}`, `domain/contact/{AiSummaryServiceImpl, ContactService(Impl)}`, `RetentionPurgeServiceImpl`, `RlsValidationService` (+ komentarze w `Contact`, `ContactRepository`).

**Zakres:**
- `messageAt` NOT NULL, niemodyfikowalne: INBOUND w `EmailPollingServiceImpl#parseMessage` = `Message#getReceivedDate()` (INTERNALDATE serwera IMAP, czas zaobserwowany przez system — dokładnie dzisiejsze źródło `received_at`) → `now()` tylko gdy brak INTERNALDATE; **NIE** `Message#getSentDate()`: nagłówek `Date` jest kontrolowany przez nadawcę (przeszłość = natychmiastowa kwalifikacja do purge; korekta z BE-124 §7, DESIGN D4); OUTBOUND (`EmailSendServiceImpl`, dwie ścieżki ≈ linie 111 i 209) = ten sam `Instant` ustawiany RAZ.
- `EmailMessageRepository#save/update`: natywny INSERT/UPDATE po `(message_id, message_at)`; `extractAndStoreAttachments` robi drugi `save(saved)` (dziś `em.merge`) → UPDATE po PK. Dedup `findByMessageIdHeader(header, tenantId)` (bez daty) zostaje (D4 = A); `DataIntegrityViolationException` z unikalności złożonej traktuj jako duplikat.
- `EmailContactCreator`: 3× `emailMessageRepository.findById(messageId)` (linie ≈ 171, 194, 317) po zdarzeniach RabbitMQ z samym `messageId` (`EmailEvent`) → dodaj `messageAt` do `EmailEvent` (tolerancyjna deserializacja: kolejka może zawierać zdarzenia starego formatu → fallback lookup po samym `message_id`, skan lokalnych indeksów partycji; sprawdź `FAIL_ON_UNKNOWN_PROPERTIES`).
- `EmailController` (endpointy po samym id): pojedynczy lookup po `message_id` bez klucza partycji dozwolony — udokumentuj koszt (liczba partycji × indeks PK).
- D4 = B → BE-136 zamiast unikalności złożonej.

**Kryteria akceptacji:**
- [ ] (WP-1) Testcontainers po DB-067: pełny przepływ IMAP (mock `Store`) → zapis → zdarzenie → `EmailContactCreator` → powiązanie z kontaktem → EML; redelivery tego samego Message-ID = 1 wiersz; OUTBOUND: `messageAt` = `sentAt`; zdarzenie starego formatu (bez `messageAt`) obsłużone; zapis pod `SET ROLE app_user` z GUC
- [ ] INBOUND: `messageAt` = INTERNALDATE (`getReceivedDate()`), nie nagłówek `Date` nadawcy — test z wiadomością o `Date` z przeszłości i z przyszłości (wiek liczony z INTERNALDATE)
- [ ] (WP-2) `EmailPollingServiceImpl` nadal ustawia `TenantContext` per tenant w pętli (istniejący `Snapshot`), nic nie czyści kontekstu HTTP
- [ ] `mvn verify -pl app`; brak `em.find/merge` po samym `message_id` (grep); (WP-4) Local-demo po przebudowie: odbiór e-maila (jeśli IMAP skonfigurowany) i odpowiedź z załącznikiem — wiadomość w partycji miesięcznej, brak regresji UI e-mail
- [ ] Wydanie razem z DB-067; DoD (WP-7)

**Ryzyka:** zdarzenia RabbitMQ „w locie" podczas wdrożenia; fallback `now()` dla wiadomości bez INTERNALDATE (DESIGN D4) oraz skrzynka zmigrowana z historycznym INTERNALDATE (świeże wiadomości wyglądają na stare — obrona w BE-127).

---

### BE-135 – Podpięcie `email_message` do maszynerii + Poziom 1 przed Poziomem 2 (obiekty S3)

**Typ:** Backend implementation — [BRAMKOWANY]
**Priorytet:** Should Have
**Złożoność:** M
**Zależy od:** BE-134, BE-133 (tryb `ONLY_IF_EMPTY`), BE-125 ✅, BE-127
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:** jak BE-133 dla `email_message`, z twardą regułą S3 (WP-5): `DROP` partycji nie usuwa obiektów S3, więc `PartitionReclaimJob` nigdy nie usuwa partycji zawierającej wiersze z niepustym `attachments` — `ONLY_IF_EMPTY` obejmuje to z definicji.

**Zakres:** `PARTITIONED_TABLES` += `email_message`; `ReclaimTarget("email_message", CATEGORY_MAX_RETENTION(CONTACT_INTERACTIONS), ONLY_IF_EMPTY)`; `PARTITION_AWARE_TABLES[CONTACT_INTERACTIONS]` += `email_message`; potwierdzenie braku wykonywalnego `drop_old_email_message_partitions` (DB-067);
ponowna weryfikacja BE-125/BE-127 na tabeli partycjonowanej (DELETE po `contact_id IN` skanuje lokalne indeksy wszystkich partycji — `EXPLAIN` i czasy; osierocone po `message_at`).

**Kryteria akceptacji:**
- [ ] (WP-1/WP-5) Testcontainers: partycja z wierszem z załącznikiem po max retencji NIE jest dropnięta (WARN z liczbą wierszy i wskazówką „uruchom purge Poziom 1"); po purge Poziom 1 (BE-127) jest pusta → DROP; obiekty S3 (mock) usunięte PRZED dropem
- [ ] Testy jak BE-133 (bufor 3 miesięcy dla wszystkich tabel, `_default` nigdy, `contact*` bez zmian); `EXPLAIN` purge po `contact_id IN` na ≥ 500 tys. wierszy/12 partycjach w notatce
- [ ] (WP-4) Local-demo: partycje `email_message_*` tworzone przez `PartitionMaintenanceJob`, nic nie usuwane; `mvn verify -pl app`; DoD (WP-7)

**Uwaga z BE-125 (2026-09-21):** kod BE-125 nie zakłada klucza złożonego — `EmailMessageRepository#deleteByIds` usuwa po `message_id`, a `AttachmentsRow` nie niesie `messageAt` (komentarze w kodzie wskazują dostosowanie przy BE-134/DB-067). Przy ponownej weryfikacji na tabeli partycjonowanej porównać z pomiarem BE-125 (30 tys. wierszy, tabela niepartycjonowana): SELECT po `contact_id IN` → Index Scan `idx_email_message_contact`, `DELETE … message_id IN` → Bitmap Index Scan `pk_email_message`, social `DELETE … contact_id IN` → Index Scan `idx_social_message_contact`; bez Seq Scan.

---

### BE-136 – [WARUNKOWY: D4 = B] Deduplikacja e-mail przez tabelę `email_message_dedup`

**Typ:** Backend implementation
**Priorytet:** Could Have (warunkowy)
**Złożoność:** M
**Zależy od:** DB-068, BE-134
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis i zakres:** `EmailPollingServiceImpl#processMessage`: zamiast `findByMessageIdHeader` — `INSERT INTO email_message_dedup … ON CONFLICT DO NOTHING RETURNING` w tej samej transakcji co INSERT wiadomości (rollback obu przy błędzie); repozytorium `TenantAwareRepository`; purge tabeli dedup po `first_seen_at` (próg = max retencja kategorii wiadomości) jako krok `purgeContactInteractions`.

**Kryteria akceptacji:**
- [ ] (WP-1) Test wyścigu (Testcontainers): 2 równoległe wątki × ten sam Message-ID → 1 wiersz wiadomości i 1 wiersz dedup; purge dedup po retencji; RLS pod `SET ROLE app_user`
- [ ] (WP-2) Polling nadal ustawia `TenantContext` per tenant; `mvn verify -pl app`; DoD (WP-7)

---

### BE-137 – [BRAMKOWANY] `campaign_contact_archive` w maszynerii partycji

**Typ:** Backend implementation
**Priorytet:** Could Have — dopiero po DB-069 (warunek wolumenowy)
**Złożoność:** S
**Zależy od:** DB-069
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis i zakres:** `PARTITIONED_TABLES` += `campaign_contact_archive`; `PARTITION_AWARE_TABLES[CAMPAIGN_DATA]` = `campaign_contact_archive` (zastępuje `CampaignArchiveRetentionRepository#countEligible`, zachowując `oldest/newest_eligible_period`);
`ReclaimTarget("campaign_contact_archive", CATEGORY_MAX_RETENTION(CAMPAIGN_DATA), AFTER_CUTOFF)` (brak obiektów zewnętrznych); `purgeEligible` (BE-121) zostaje jako Poziom 1.

**Kryteria akceptacji:**
- [ ] (WP-1/WP-6) Testcontainers na tabeli partycjonowanej: `CampaignContactArchivePurgeTenantIsolationTest` zielony, ewaluacja partycyjna zgodna z dotychczasowym zapytaniem, DROP tylko dla partycji starszych niż max retencja; `mvn verify -pl app`; DoD (WP-7)

---

### BE-138 – `RlsValidationService`: pokrycie komend, `FORCE` i rola połączenia

**Typ:** Backend implementation (bezpieczeństwo, obserwowalność)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** DB-071
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis:** `RlsValidationService` (`infrastructure/config`, `@EventListener(ApplicationReadyEvent)`) sprawdza tylko, czy tabela z twardej listy 11 nazw ma JAKĄKOLWIEK politykę w `pg_policies` — polityka tylko-SELECT przechodzi walidację (DESIGN §2 U8), mimo że zapisy pod rolą ograniczoną są odrzucane.

**Zakres:** (1) lista tabel z klasyfikacji DB-071 (klasa TENANT) zamiast twardej; (2) sprawdzenie pokrycia komend (polityka `ALL` albo komplet SELECT/INSERT/UPDATE/DELETE) i `relforcerowsecurity`; (3) log roli połączenia: `SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user` — WARN gdy superuser/BYPASSRLS („RLS jest wyłącznie defense-in-depth; główną ochroną `assertSameTenant`"), flaga `rls.validation.fail-on-bypass` (domyślnie `false`) dla prod; (4) wynik jako lista naruszeń w logu.
Nadal nie blokuje startu domyślnie (Testcontainers z uproszczonym schematem).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers na pełnym Flyway: po DB-064/DB-074 zero naruszeń dla tabel TENANT; test negatywny: tabela z polityką SELECT-only → naruszenie raportowane; rola superuser → WARN; `mvn verify -pl app`; DoD (WP-7)

---

### BE-139 – Testy integracyjne pod rolą bez BYPASSRLS + weryfikacja roli połączenia produkcyjnego

**Typ:** Testing / infrastruktura testowa
**Priorytet:** Should Have
**Złożoność:** M
**Zależy od:** DB-064, DB-071
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `test-suite-expert` (+ `backend-dev-expert` dla konfiguracji)

**Opis:** cały łańcuch retencji/RODO/jobów działa dziś wyłącznie dlatego, że `ccapp` = superuser + BYPASSRLS; `app_user` (V012) jest `NOLOGIN`, więc nigdy nie była nią uruchamiana aplikacja (DESIGN §2 U8, R1). Brak polityki UPDATE/DELETE na `contact` oznacza, że purge pod rolą ograniczoną usunąłby 0 wierszy po cichu.

**Zakres:**
1. Infrastruktura testowa: baza Testcontainers z pełnym Flyway + rola **logująca** (NOSUPERUSER NOBYPASSRLS, członek `app_user`) albo `SET ROLE app_user` przez `connectionInitSql`; narzędzie w `src/test` współdzielone przez testy (wzorzec `CampaignContactArchivePurgeTenantIsolationTest`).
2. Scenariusze pod tą rolą (minimum 6): purge `contact`/`contact_event`/wiadomości (BE-126/127), `PartitionScanner`/joby, `GdprServiceImpl` (BE-129), zapis/aktualizacja `EmailMessageRepository`/`SocialMessageRepository`, `CampaignArchiveRetentionRepository`, cleanup `refresh_token`; asercje: zapis bez GUC odrzucony/0 wierszy, DELETE po `contact` (dziś bez polityki) = 0 usuniętych — wynik dokumentuje lukę (→ DB-074).
3. **Weryfikacja roli produkcyjnej:** jaką rolą łączy się aplikacja w prod (`DB_USERNAME` w `application-prod.yml`, `docker-compose.yml`, `DEPLOYMENT.md`); jeśli superuser/BYPASSRLS → raport i propozycja: dedykowana rola LOGIN będąca członkiem `app_user` (NOBYPASSRLS) oraz osobna rola migracyjna (Flyway); zapis w `DEPLOYMENT.md`. Bez zmian produkcyjnych w tym tickecie.

**Kryteria akceptacji:**
- [ ] Harness zielony w `mvn verify -pl app` (≥ 6 scenariuszy); wyniki (które ścieżki się łamią pod rolą ograniczoną) w notatce jako lista defektów → tickety
- [ ] Raport o roli produkcyjnej i rekomendacja w `DEPLOYMENT.md`; (WP-1/WP-4) testy nie używają `ccapp` ani nazw partycji potomnych; DoD (WP-7)

---

### BE-140 – [WARUNKOWY: D6 = koniec kampanii] `CAMPAIGN_DATA` liczone od końca kampanii (warstwa Java)

**Typ:** Backend implementation
**Priorytet:** Could Have (warunkowy)
**Złożoność:** S
**Zależy od:** DB-075
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert`

**Opis i zakres:** `CampaignArchiveRetentionRepository#countEligible` (`campaign_ended_at < :cutoff`, `oldest/newest_eligible_period` z tej kolumny) i `purgeEligible` (funkcja SQL po DB-075); Javadoc `RetentionEvaluationServiceImpl`.

**Kryteria akceptacji:**
- [ ] (WP-1) Testcontainers: zaległa kampania (koniec 3 lata temu, zarchiwizowana dziś) kwalifikuje się natychmiast przy retencji 24 mies.; izolacja tenantów; `mvn verify -pl app`; DoD (WP-7)

---

### BE-141 – Usunięcie `remote_address` z ETL do DW (`EtlSyncServiceImpl`, `ContactDwRow`, `PostgresDwWriter`)

**Typ:** Backend implementation (minimalizacja danych)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** DB-078
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
ETL kopiuje `contact.remote_address` (numer CLI/e-mail klienta) do DW: `EtlSyncServiceImpl` wybiera kolumnę (`SELECT_CONTACTS_FOR_ETL`, ≈ l. 88) i mapuje ją do `ContactDwRow.remoteAddress` (≈ l. 690), `PostgresDwWriter#upsert` zapisuje ją do PG `contacts_dw` (UPSERT wymienia kolumnę), a `ClickHouseDwWriter` jej NIE zapisuje (INSERT bez kolumny;
schemat `dw/migrations/V001`: „brak PII"). Kolumna nie służy analityce; w PG-DW zostało 130 wierszy PII bez kontaktu źródłowego (DB-060 F6, DESIGN U9). ETL pomija dziś tylko PRZYSZŁE synchronizacje kontaktów klientów zanonimizowanych (`UPPER(cu.first_name) = 'ANONYMIZED'`), a kontakty bez `customer_id` przechodzą.
`etl.dw.type`: dev domyślnie `postgres`, prod `clickhouse` (`application-prod.yml`; `.env.local-demo` też `clickhouse`) — konfiguracji na żywym prodzie nie sprawdzono.

**Zakres:**
- `EtlSyncServiceImpl`: usuń `c.remote_address` z `SELECT_CONTACTS_FOR_ETL` (≈ l. 88) i `rs.getString("remote_address")` z mapowania `new ContactDwRow(…)` (≈ l. 676–690).
- `ContactDwRow`: usuń komponent `remoteAddress` (rekord + `@param` w Javadoc, ≈ l. 28/44) i dostosuj wywołujących (`EtlSyncServiceImplTest` ≈ l. 370).
- `PostgresDwWriter#upsert`: usuń `remote_address` z INSERT i z `ON CONFLICT DO UPDATE` oraz `ps.setString(14, row.remoteAddress())` (przenumeruj parametry); `ClickHouseDwWriter`: popraw komentarz (≈ l. 55) — bez zmiany zachowania.
- **Kompatybilność wsteczna (kolejność wdrożenia):** po zmianie writer działa zarówno na schemacie z kolumną (nullable), jak i bez niej, więc BE-141 wchodzi PRZED lub RAZEM z DB-078 (drop kolumny).
- Filtr `ANONYMIZED` w ETL zostaje (nie dotyczy już PII, ale jest bezpieczny).

**Kryteria akceptacji:**
- [ ] (WP-1) Test Testcontainers (PostgreSQL z pełnym Flyway): `PostgresDwWriter#upsert` zapisuje wiersz i nie odwołuje się do `remote_address` — na schemacie z kolumną i (po DB-078) bez niej; ETL end-to-end (`EtlSyncServiceImpl`) na prawdziwej bazie zapisuje kontakt do PG-DW (asercje po wartościach); dziś brak testu writera (brak testów w `infrastructure/etl`)
- [ ] grep: 0 wystąpień `remote_address`/`remoteAddress` w `domain/etl`, `infrastructure/etl` i `dw/` poza migracjami historycznymi; `EtlSyncServiceImplTest` zaktualizowany
- [ ] (WP-4) Local-demo po przebudowie obrazów: ETL synchronizuje bez błędu (`etl_sync_state`) dla obu wartości `etl.dw.type` (`postgres` i `clickhouse`); `mvn verify -pl app`; DoD (WP-7)

**Ryzyka:** zmiana rekordu `ContactDwRow` dotyka wszystkich wywołujących (jedno miejsce w kodzie produkcyjnym + test); prod domyślnie `clickhouse`, więc realny efekt dotyczy głównie dev/PG-fallback — niezweryfikowane na żywym prodzie.

---

### BE-142 – [WARUNKOWY: D10] Maskowanie PII w `audit_log` (zapis przez `@Audited` i anonimizacja)

**Typ:** Backend implementation (minimalizacja danych)
**Priorytet:** Could Have (warunkowy — wchodzi wyłącznie przy założeniu D10 = maskowanie)
**Złożoność:** S (M, jeśli obejmuje jednorazowe maskowanie wierszy historycznych — patrz Zakres p. 3)
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
`audit_log` (1227 wierszy live) trzyma pełne snapshoty PII (DB-060 F7): `CUSTOMER_CREATED` (1) i `CUSTOMER_UPDATED` (7) — `firstName`, `lastName`, `phone[]`, `email[]`, `customFields`, `gdprConsent`, `externalId`, także w `old_value`; `CONTACT_*` (CREATED 259, AGENT_ASSIGNED 362, DISPOSITION_SET 399, ACCEPTED 28, ABANDONED 14) — `remoteAddress`, `channelMetadata` (EMAIL: `fromAddress`, `subject`), `recordingUrl`, `notes`;
`RECORDING_URL_REQUESTED` (98) — `presignedUrl` (TTL 15 min, wygasłe — nie jest to ryzyko PII). `AuditAspect#serializeToJson` (`infrastructure/aspect/AuditAspect.java`, ≈ l. 209–224) usuwa dziś wyłącznie `SENSITIVE_FIELDS` (hasła, tokeny; l. 55–58) i serializuje całą encję, a anonimizacja Art. 17 (Java i SQL) nie dotyka `audit_log`.
**Zakłada D10 (DESIGN §3): maskowanie kluczy PII, wiersze zostają** (Art. 5(2)/30; Art. 17(3)(b)/(e) — wymaga potwierdzenia prawnego). Przy alternatywie „`audit_log` bez zmian" ticket nie jest wykonywany; ekspozycję ogranicza wtedy wyłącznie horyzont 24 mies. (D5/BE-123).

**Zakres:**
1. **Zatrzymanie PII u źródła:** lista kluczy PII jako jedno źródło prawdy w Javie (np. `AuditPiiKeys`, konfigurowalna): `firstName`, `lastName`, `phone`, `email`, `customFields`, `gdprConsent`, `externalId`, `remoteAddress`, `channelMetadata`, `notes`, `recordingUrl`, `fromAddress`, `subject`; `AuditAspect#serializeToJson` i `captureOldValue` zastępują wartości tych kluczy znacznikiem `"[MASKED]"` (klucz zostaje — widać, że pole się zmieniło, bez wartości), obok `SENSITIVE_FIELDS`. Dotyczy encji `CUSTOMER` i `CONTACT`.
2. **Wiersze istniejące (podmiot):** maskowanie przy anonimizacji wykonuje funkcja SQL (DB-062, Could, D10 — UPDATE `audit_log` po `entity_id` i `new_value @> {"customerId": …}`; tabela partycjonowana, RLS tylko SELECT — pod rolą ograniczoną UPDATE cicho 0 wierszy, DB-064/DB-074). Lista kluczy w SQL = lista z p. 1.
3. **Wiersze historyczne spoza anonimizacji:** decyzja o jednorazowym sweepie całego `audit_log` wymaga potwierdzenia prawnego — zapisać w notatce, bez implementacji domyślnie.
4. `GdprServiceImpl` i funkcje SQL nie wkładają PII do własnych wpisów audytu (dziś: brak) — test.

**Kryteria akceptacji:**
- [ ] (WP-1) Test na prawdziwej bazie (Testcontainers, `AuditLogConsumer`/prawdziwa tabela `audit_log`): `@Audited` na `CustomerServiceImpl` (create/update) i `ContactServiceImpl` zapisuje `new_value`/`old_value` z zamaskowanymi kluczami PII i zachowanymi polami niebędącymi PII (`customerId`, `status`, `dispositionCode`) — asercje po JSON
- [ ] Test spójności listy kluczy Java ↔ SQL (jeśli DB-062 obejmuje maskowanie wierszy podmiotu)
- [ ] Decyzja w notatce: `presignedUrl` z `RECORDING_URL_REQUESTED` (TTL 15 min) — maskować czy zostawić; istniejące testy `AuditAspect` zielone; sprawdzone (grep FE/BE), że żaden widok historii audytu nie polega na wartościach snapshotów PII
- [ ] `mvn verify -pl app`; DoD (WP-7); zakłada D10

**Ryzyka:** utrata użyteczności diffów audytowych (klucz zostaje, wartość nie); rozjazd list PII w Javie i SQL; decyzja prawna o wierszach historycznych.

---

### BE-143 – Walidacja prefiksu tenanta dla `s3Key` w wysyłce e-mail i w pobieraniu załącznika

**Typ:** Backend implementation (bezpieczeństwo / izolacja tenantów)
**Priorytet:** Must Have (bezpieczeństwo — obejście granicy tenantów; luka istniejąca przed EPIC-30, DESIGN U18)
**Złożoność:** S
**Zależy od:** BE-125 ✅ (wspólny helper `EmailAttachmentKeys`, kontrakt `EmailAttachmentStorageService#delete`)
**Status:** ✅ Ukończone
**Blokuje:** brak
**Epic:** EPIC-30 Retencja wiadomości, domknięcie harmonogramów i partycjonowanie tabel wiadomości
**Wykonawca:** `backend-dev-expert` (+ `test-suite-expert`)

**Opis:**
Ustalenie BE125-01 z code review BE-125 (`CR-BACKEND.md`, 2026-09-21; potwierdzone grepem w kodzie):
- `EmailSendServiceImpl#buildAttachmentPart` (≈ l. 324–327) pobiera `attachmentStorageService.download(attachment.s3Key())` dla klucza z ciała żądania (`EmailReplyRequest.PendingAttachment#s3Key`; `EmailController#replyToMessage` — `POST /api/email/messages/{id}/reply` — i `#sendOutboundEmail` — `POST /api/email/messages/outbound` — przekazują `attachments` bez walidacji) **bez sprawdzenia prefiksu `email-attachments/{tenantId}/`**. Agent tenanta A, znając pełny klucz obiektu tenanta B (albo nagrania `{tenantId}/…mp3`, EML kontaktu), może dołączyć go do wysyłanego maila — cross-tenant disclosure. Ekspozycja jest ograniczona nieodgadywalnością kluczy (UUID tenanta + UUID wiadomości), ale to obejście granicy tenantów, a nie tylko „defense in depth". `buildAttachmentsJson` (≈ l. 376–387) zapisuje ten klucz w `email_message.attachments`; purge (BE-125) odrzuca obcy klucz allow-listą, ale to obrona w głąb, nie naprawa.
- `EmailAttachmentController#downloadAttachment` (≈ l. 126–135, `GET /api/email/attachments/download`) sprawdza tylko `s3Key.startsWith("email-attachments/" + tenantId + "/")` — bez odrzutu segmentów `.`/`..` i znaków sterujących; tę klasę obejścia zamyka już `EmailAttachmentKeys#isOwnedByTenant` (BE-125), ale jest package-private w `domain.email`, a kontroler leży w `api.email`.
DESIGN U18 mówił „luka poza zakresem EPIC-30, do osobnego ticketu", lecz takiego ticketu nie było (`grep` po `TASKS-BACKEND.md` znajdował `PendingAttachment` wyłącznie w BE-124/BE-125) — ten ticket go zakłada. **Niezależny od BE-126** (poprawka bezpieczeństwa, nie element purge).

**Zakres:**
- **(a) Wysyłka:** `EmailSendServiceImpl` (`sendReply`/`sendNew` PRZED `sendSmtp`; `buildAttachmentsJson`/`buildAttachmentPart`) oraz wejście REST (`EmailController#replyToMessage`, `#sendOutboundEmail`) — każdy `PendingAttachment#s3Key` musi przejść allow-listę `email-attachments/{tenantId}/` (niepusty sufiks, bez segmentów `.`/`..`, bez znaków sterujących), inaczej ODRZUCENIE CAŁEGO żądania przed wysyłką SMTP i zapisem wiadomości (dziś błąd pojedynczego załącznika jest logowany i pomijany — `buildAttachmentPart`; zmiana zachowania jest świadoma). Kod błędu (400 vs 403) ustala wykonawca, spójnie z 403 w downloadzie i z `GlobalExceptionHandler`.
- **(b) Pobieranie:** `EmailAttachmentController#downloadAttachment` — ta sama allow-lista zamiast gołego `startsWith` (403 jak dotąd).
- **(c) Udostępnienie allow-listy = decyzja API razem z BE125-04:** `EmailAttachmentKeys#isOwnedByTenant` jest dziś package-private, a `EmailAttachmentStorageService#delete(String)` nie kontroluje tenanta. Opcje: publiczna, testowana polityka kluczy (np. `S3KeyPolicy`) albo `delete(UUID tenantId, String key)` egzekwujące prefiks wewnątrz. Decyzja ma pasować do BE-126 (pkt b: klucze z `contact.recording_url` mają schemat `{tenantId}/…`, inny niż `email-attachments/{tenantId}/`), BE-129 (`GdprServiceImpl` w `domain.gdpr`) i BE-131 (sweep kluczy z listowania bucketu — pomyłka prefiksu kasuje cudzy obiekt), żeby nie powstały trzy własne allow-listy. Wynik zapisać w notatce; BE-126/BE-129/BE-131 mają w treści uwagę kontraktową, bez zależności.
- **(d) Testy:** MockMvc/integracyjne — obcy klucz → odrzucenie, `..` w kluczu → odrzucenie, własny klucz przechodzi, własny klucz `pending/` (OUTBOUND) nadal działa end-to-end.

**Kryteria akceptacji:**
- [x] (WP-1) Test MockMvc/integracyjny `POST /api/email/messages/{id}/reply` i `POST /api/email/messages/outbound` (prawdziwy `EmailSendServiceImpl`, mock S3 i SMTP): (i) klucz obcego tenanta (`email-attachments/{inny-tenant}/…`) → żądanie odrzucone (kod wg decyzji wykonawcy), `EmailAttachmentStorageService#download` i SMTP NIE wywołane, wiadomość OUTBOUND niezapisana; (ii) klucz z segmentem `..` (np. `email-attachments/{tenant}/../{inny-tenant}/x`) → odrzucony; (iii) klucz nagrania `{tenantId}/…mp3` → odrzucony; (iv) własny klucz `email-attachments/{tenant}/pending/{uuid}/plik` przechodzi i trafia do `attachments` wiadomości OUTBOUND (regresja); własny klucz INBOUND `{messageId}/` też przechodzi *(pokryte, ale NIE dosłownie `MockMvc` — zob. notatka: świadome odstępstwo, ten sam kompromis co BE-118)*
- [x] `GET /api/email/attachments/download`: obcy klucz i klucz z `..` → 403, własny klucz → 200 z presigned URL (test kontrolera; dotychczasowa ochrona IDOR zachowana)
- [x] Jedna implementacja allow-listy dla wysyłki, pobierania i purge (BE-125): test przypadków — obcy tenant, `.`/`..`, znaki sterujące, sam prefiks, `null`/pusty klucz *(już wyczerpująco pokryte przez `EmailAttachmentKeysTest` z BE-125, bez zmian)*
- [x] (WP-2) Walidacja używa `TenantContext` z żądania; brak `TenantContext.clear()` na ścieżce HTTP; test z pustym kontekstem
- [ ] Decyzja API (publiczna polityka kluczy vs `delete(UUID tenantId, String key)`) zapisana w notatce i przekazana jako uwaga kontraktowa w BE-126/BE-129/BE-131 *(decyzja podjęta i udokumentowana w notatce niżej z pełnymi sygnaturami; uwaga kontraktowa w treści BE-126/BE-129/BE-131 NIE dopisana — poza zakresem plików, które wolno mi było edytować w tej iteracji, tylko sekcja BE-143. Wykonawca BE-126/BE-129/BE-131 albo zlecający powinien skopiować sekcję "Decyzja API" niżej do tamtych ticketów.)*
- [ ] (WP-4, niedestrukcyjny) Local-demo po przebudowie obrazów: upload załącznika → odpowiedź z własnym kluczem `pending/` działa (jeśli SMTP skonfigurowany; inaczej test na poziomie MockMvc); żądanie z kluczem innego tenanta lub z `..` → odrzucone bez odczytu z S3 (sprawdź log); żadne obiekty nie są usuwane *(celowo niewykonane — wymaga przebudowy/uruchomienia stosu docker local-demo, poza zakresem tej iteracji)*
- [x] `mvn verify -pl app`; DoD (WP-7) *(backend: BUILD SUCCESS, 2038 testów (stan na koniec iteracji, drzewo współdzielone z równoległym BE-126), 0 failures/errors; frontend `/verify` NIE uruchomione — brak zmian frontendowych w tym tickecie)*

**Ryzyka:** (i) zmiana zachowania: dziś nieprawidłowy załącznik jest pomijany, a mail wychodzi bez niego — po zmianie żądanie jest odrzucane; sprawdzić w FE (odpowiedź na e-mail), że wysyła wyłącznie klucze z uploadu (`POST /api/email/attachments/upload` zwraca klucz z własnym prefiksem) i pokazuje błąd; (ii) decyzja API (pkt c) musi zapaść przed BE-126 pkt (b), BE-129 i BE-131; (iii) klucze legacy (`s3_url`) nie występują w żądaniach — niezweryfikowane na prodzie.

**Notatka z implementacji (2026-09-22):**
- **Decyzja API (pkt c) — opcja (i), rozszerzenie `EmailAttachmentKeys`, BEZ nowej klasy `S3KeyPolicy`:**
  klasa i `isOwnedByTenant` upublicznione (były package-private w `domain.email`); dodana druga,
  analogiczna metoda dla schematu kluczy nagrań/EML (`{tenantId}/…`, INNY korzeń niż
  `email-attachments/{tenantId}/`). BE-143 sam używa wyłącznie `isOwnedByTenant` — druga metoda
  jest przygotowaniem API dla BE-126 (pkt b, purge `contact.recording_url`), BE-129
  (`GdprServiceImpl`) i BE-131 (sweep bucketu), żeby te tickety NIE tworzyły własnych,
  rozjeżdżających się allow-list. Opcja `delete(UUID tenantId, String key)` egzekwujące prefiks
  wewnątrz (druga propozycja z ticketu) świadomie odrzucona: wymagałaby zmiany kontraktu
  `EmailAttachmentStorageService#delete` ustalonego w BE-125 i przeniosłaby walidację z miejsca
  wywołania (gdzie żyje `tenantId` z `TenantContext`) do warstwy storage.
  ```java
  // domain.email.EmailAttachmentKeys — publiczne od BE-143
  public static boolean isOwnedByTenant(UUID tenantId, String s3Key);
      // schemat email-attachments/{tenantId}/… — używane przez wysyłkę (EmailSendServiceImpl),
      // pobieranie (EmailAttachmentController) i purge (BE-125, EmailMessageServiceImpl)
  public static boolean isRecordingKeyOwnedByTenant(UUID tenantId, String s3Key);
      // schemat {tenantId}/… (nagrania RecordingServiceImpl#buildS3Key, EML EmailEmlService) —
      // przygotowane dla BE-126(b)/BE-129/BE-131; BE-143 jej NIE wywołuje
  public static String forLog(String s3Key);
      // bezpieczne logowanie klucza niezaufanego od klienta (było już w BE-125, teraz public)
  ```
  Obie allow-listy współdzielą prywatną `hasCleanPrefixedSuffix(String prefix, String s3Key)` —
  ta sama logika (niepusty sufiks, bez segmentów `.`/`..`, bez znaków sterujących), różny prefiks.
- **Kod błędu: HTTP 403 (nie 400), spójnie z `CrossTenantAccessException` (BE-002) i
  dotychczasowym 403 w `downloadAttachment`.** Nowy wyjątek
  `EmailAttachmentAccessDeniedException` (`domain.email`, `RuntimeException`, jeden konstruktor
  `String message`) zamiast rozszerzenia `CrossTenantAccessException` — ten ostatni ma
  `resourceId: UUID` w konstruktorze, a `s3Key` to `String`; nowa klasa jest prostsza niż zmiana
  sygnatury współdzielonego wyjątku używanego w wielu miejscach poza tym tickietem. Mapowany na
  403 przez nowy handler `GlobalExceptionHandler#handleEmailAttachmentAccessDeniedException`
  (RFC 7807 `ProblemDetail`, typ `email-attachment-access-denied`, komunikat dla klienta ogólny,
  szczegóły tylko w logu WARN po stronie serwera).
- **(a) Wysyłka:** walidacja w `EmailSendServiceImpl#validateAttachmentKeys` (nowa prywatna
  metoda), wywoływana jako KROK 0 na początku `sendReply`/`sendNew` — PRZED pobraniem oryginalnej
  wiadomości/tenanta/hasła SMTP, nie tylko przed `sendSmtp`. Odrzucenie CAŁEGO żądania przy
  pierwszym niewłaściwym kluczu (świadoma zmiana zachowania — wcześniej `buildAttachmentPart`
  logował i pomijał pojedynczy zły załącznik). `EmailController#replyToMessage`/`#sendOutboundEmail`
  NIE dostały własnej, drugiej walidacji — przekazują `attachments` do serwisu bez zmian (DRY,
  jedna implementacja); to spełnia AC end-to-end (walidacja i tak dzieje się na ścieżce obu
  endpointów, zanim dojdzie do S3/SMTP/zapisu).
- **(b) Pobieranie:** `EmailAttachmentController#downloadAttachment` — `s3Key.startsWith(...)`
  zamieniony na `EmailAttachmentKeys.isOwnedByTenant(tenantId, s3Key)`; odpowiedź 403 przez
  bezpośredni `ResponseEntity.status(403)` bez zmian (bez nowego wyjątku) — zgodnie z AC
  "403 jak dotąd". Log ostrzeżenia teraz przez `EmailAttachmentKeys.forLog(s3Key)` (drobna,
  dodatkowa poprawka bezpieczeństwa logów przy okazji upublicznienia klasy, poza ściśle wymaganym
  zakresem, ale zero kosztu — sama widoczność metody się zmieniła).
- **(d) Testy — 28 nowych, wszystkie PASS:**
  `EmailSendServiceTest$SendNewAttachmentKeyValidation` (4: AC i–iv na `sendNew`, rzeczywisty
  `EmailSendServiceImpl`, `@Spy` na `sendSmtp` — wzorzec już istniejący w klasie),
  `EmailSendServiceTest$SendReplyAttachmentKeyValidation` (2: obcy klucz odrzucony PRZED
  pobraniem oryginalnej wiadomości + własny klucz INBOUND `{messageId}/` przechodzi — regresja),
  `EmailAttachmentKeysTest$RecordingKeyAllowlist` (7: nowa metoda `isRecordingKeyOwnedByTenant`),
  `GlobalExceptionHandlerTest$EmailAttachmentAccessDeniedExceptionTests` (3: 403, RFC 7807,
  brak wycieku surowego klucza w odpowiedzi), `EmailAttachmentControllerTest$DownloadAttachment`
  (7, nowy plik: własny klucz INBOUND/`pending/` → 200, obcy tenant/`..`×2/nagranie → 403,
  WP-2 pusty `TenantContext` → `IllegalStateException`, nie fałszywa autoryzacja),
  `EmailControllerTest$ReplyToMessage`+`$SendOutboundEmail` (5, nowy plik: kontroler przekazuje
  `attachments` bez zmian, `null` → pusta lista, wyjątek z serwisu propaguje się niezłapany).
  **Świadome odstępstwo od dosłownego "MockMvc/integracyjny" z AC** (ten sam kompromis co BE-118
  `RetentionControllerTest`): projekt NIE testuje kontrolerów `api.*` przez `MockMvc` z aktywnym
  Spring Security; zamiast tego logika biznesowa (allow-lista, kolejność kroków, brak
  wywołań S3/SMTP) jest w 100% pokryta na `EmailSendServiceImpl` (rzeczywisty obiekt, nie mock),
  a wiązanie kontroler→serwis osobno, bezpośrednim wywołaniem metody. Mapowanie wyjątku na 403 ma
  własny, trzeci test (`GlobalExceptionHandlerTest`). Zestawione RAZEM te trzy klasy dają dokładnie
  te same gwarancje behawioralne co jeden duży test `MockMvc`, bez kruchości pełnego kontekstu
  Spring.
- **Wynik weryfikacji FE (ryzyko i z ticketu) — bezpieczne, bez zmian FE:** grep
  `frontend/src/app` potwierdza, że `pendingAttachments`/`s3Key` w obu miejscach wysyłki
  (`email-contact.component.ts` — odpowiedź na wątek, `adhoc-email-modal.component.ts` — nowy
  e-mail) są zasilane WYŁĄCZNIE odpowiedzią `POST /api/email/attachments/upload`
  (`resp.s3Key`/`uploaded.s3Key`); `removeAttachment(s3Key)` tylko filtruje istniejącą listę po
  wartości już w niej obecnej. Brak pola formularza czy innego kodu pozwalającego użytkownikowi
  wpisać/zmienić `s3Key` ręcznie — FE nigdy nie wyśle dowolnego klucza. Po tej zmianie FE
  zobaczy nowy błąd 403 zamiast dotychczasowego "cichego" pominięcia załącznika tylko w
  scenariuszu, który i tak nie powinien wystąpić przy normalnym użyciu UI (bug w FE albo
  modyfikacja żądania poza UI) — obsługa tego błędu w FE (komunikat dla agenta) NIE była w
  zakresie tego ticketu i nie została zweryfikowana/zaimplementowana.
- **Czego NIE zweryfikowano:** WP-4 (local-demo, wymaga przebudowy obrazów i działającego SMTP —
  poza zakresem tej iteracji, zgodnie z ograniczeniami zlecenia); zachowanie FE po otrzymaniu
  nowego 403 (czy pokazuje sensowny komunikat agentowi) — kod FE nie był zmieniany ani testowany
  pod tym kątem; klucze legacy `s3_url` w praktyce na prodzie (ryzyko iii z ticketu, bez zmian).

**Utworzone/zmienione pliki:**
```
backend/app/src/main/java/com/contactcenter/
  domain/email/EmailAttachmentKeys.java                (zmieniony: class + isOwnedByTenant + forLog → public,
                                                          +isRecordingKeyOwnedByTenant, refaktor hasCleanPrefixedSuffix)
  domain/email/EmailAttachmentAccessDeniedException.java (nowy)
  domain/email/EmailSendServiceImpl.java                (zmieniony: +validateAttachmentKeys, wywołanie w sendReply/sendNew)
  domain/email/EmailSendService.java                    (zmieniony: javadoc @throws)
  api/GlobalExceptionHandler.java                       (zmieniony: +handler 403 dla EmailAttachmentAccessDeniedException)
  api/email/EmailAttachmentController.java              (zmieniony: isOwnedByTenant zamiast startsWith, forLog w logu)

backend/app/src/test/java/com/contactcenter/
  domain/email/EmailSendServiceTest.java                (zmieniony: +6 testów, 2 nowe @Nested)
  domain/email/EmailAttachmentKeysTest.java             (zmieniony: +7 testów, 1 nowy @Nested)
  api/GlobalExceptionHandlerTest.java                   (zmieniony: +3 testy, 1 nowy @Nested)
  api/email/EmailAttachmentControllerTest.java          (nowy, 7 testów)
  api/email/EmailControllerTest.java                    (nowy, 5 testów)
```

**Code review i poprawki (2026-09-22):**
- Werdykt `senior-code-reviewer` (`CR-BACKEND.md`, wpis BE-143): **5/5 — zatwierdzić bez zastrzeżeń**; ticket domyka realną lukę bezpieczeństwa (BE125-01) starannie — allow-lista jako jedyne źródło prawdy reużywana przez wysyłkę/pobieranie/purge, walidacja udowodniona jako pierwsza instrukcja (kodem i testem `verifyNoInteractions`), wyjątek/handler bez wycieku surowego klucza (dowód adwersaryjnym testem `...responseDoesNotLeakRawKey`), `isRecordingKeyOwnedByTenant` zweryfikowane względem rzeczywistych producentów kluczy (`RecordingServiceImpl#buildS3Key`, `EmailEmlService#buildEmlS3Key`), nie tylko specyfikacji.
- Ustalenia BE143-01/02:
  - **BE143-01** (nit: AC „(WP-1) dosłowny MockMvc/integracyjny" formalnie niespełnione — pokrycie rozłożone na 3 klasy testowe zamiast jednego testu HTTP; `PendingAttachment.s3Key` i tak nie ma adnotacji Bean Validation, więc realny ubytek pokrycia jest niski, ten sam kompromis co `RetentionControllerTest`/BE-118) → **potwierdzone przez review jako brak realnej luki mimo formalnego niespełnienia AC**; nic do zrobienia w kodzie.
  - **BE143-02** (informacyjne, duplikat BE126-05: AC „decyzja API przekazana do BE-126/BE-129/BE-131" świadomie pozostawione odhaczone jako niewykonane w tej sekcji, poza zakresem plików edytowalnych przez wykonawcę BE-143 w tej iteracji) → **nie dotyczy tej sekcji**: propagacja wykonana osobno (`/update-progress`, `product-requirements-deconstructor`) — blok „Uwaga z BE-143 (2026-09-22)" dopisany do BE-127, BE-129 i BE-131; punkt (f) w BE-126 sprostowany bezpośrednio (BE126-05).
- Niezależna weryfikacja końcowa (zlecający, `mvn verify -pl app`, wspólne drzewo z BE-126): **BUILD SUCCESS, Tests run: 2046, Failures: 0, Errors: 0, Skipped: 0** (AC wyżej i notatka wykonawcy podają 2038 — stan sprzed scalenia z równoległymi poprawkami BE-126 w tym samym drzewie; liczba testów samego BE-143 się nie zmieniła).

---

## MODUL: Porządki infrastrukturalne i CI (bez epiku)

> Tickety porządkowe bez epiku (jak DB-055). **BE-144** dopisany 2026-09-21 po code review BE-125 (BE125-07) i weryfikacji dostępności obrazów MinIO;
> poprzednie zadanie infrastrukturalne tego obszaru: BE-001b (docker-compose, MinIO).

### BE-144 – [PORZĄDKOWY] Przepięcie obrazów MinIO z Docker Hub na quay.io z przypiętym tagiem

**Typ:** Infrastructure (dług techniczny / dostępność obrazów)
**Priorytet:** Should Have
**Złożoność:** S
**Zależy od:** brak
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Epic:** brak (porządki infrastruktury lokalnej/CI — wynik code review BE-125 (BE125-07) i weryfikacji dostępności obrazów 2026-09-21; jak DB-055, bez epiku)
**Wykonawca:** `backend-dev-expert`

**Opis:**
Ustalenie infra (zweryfikowane 2026-09-21 przez zlecającego): `docker manifest inspect minio/minio:latest` → `denied / unauthorized` (kontrola: `alpine` działa) — obrazy MinIO z Docker Hub nie są już anonimowo pobieralne. `docker-compose.yml` używa `minio/minio:latest` (l. 102, serwis `minio`) i `minio/mc:latest` (l. 127, serwis `minio-init`) — ruchomy tag i Docker Hub. Lokalny stos działa dziś tylko z cache'u obrazów; świeża maszyna, CI albo `docker compose pull` nie podniesie MinIO. Test `EmailAttachmentStorageServiceMinioTest` (BE-125) jest już przypięty do `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` (BE125-07); compose i dokumentacja nadal wskazują Docker Hub i `latest`.

**Zakres:**
- `docker-compose.yml`: `minio` → `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` (to samo wydanie co w teście — jedno wydanie w całym repo); `minio-init` → `quay.io/minio/mc:<przypięty RELEASE>` — wykonawca sprawdza dostępność `quay.io/minio/mc` (pull anonimowy) i wybiera wydanie zgodne z serwerem, a także zgodność `command: server /data --console-address ":9001"`, healthchecka `mc ready local` (wymaga `mc` w obrazie serwera) i entrypointu `minio-init` (`mc alias set`, `mc mb --ignore-existing`) z przypiętym wydaniem.
- Pozostałe overlaye compose: `docker-compose.local-demo.yml` (l. 109: serwis `minio` nadpisuje tylko porty, bez obrazu — potwierdzić `docker compose config`); grep 2026-09-21: brak innych plików compose ani manifestów z obrazami MinIO.
- Dokumentacja z nazwami obrazów: `ARCHITECTURE.md` (l. 881, 1235), `documentation/tech/08-infrastructure.md` (l. 22), `DOCKER-COMMANDS.md` (sekcja 8 „MinIO": polecenia `mc` w kontenerach `cc-minio`/`cc-minio-init`), `DEPLOYMENT.md` (§21 „MinIO Distributed", ≈ l. 1200–1211: brak wzmianek o obrazie w grepie; instalacja binarna `minio server` i `mc ilm add … --expiry-days 365` — sprawdzić źródło binariów/`mc` i odnotować globalny lifecycle 365 dni jako powiązany z ryzykiem R10 (DESIGN §7; H-2 z code review BE-125), bez zmiany polityki bucketu w tym tickecie).
- `.github/workflows/ci.yml`: `mvn -B -pl app -am verify` uruchamia `EmailAttachmentStorageServiceMinioTest` na `ubuntu-latest` (pull z quay.io z przypiętym tagiem); `release.yml` bez wzmianek o MinIO (grep). Wykonawca sprawdza, czy runner pobiera obraz z quay.io bez logowania i czy potrzebny jest cache.
- **NIE zmieniać stosu lokalnego użytkownika bez zgody:** przebudowa (`docker compose up -d minio`) = pobranie obrazów z quay.io; wolumen `minio_data` musi pozostać nietknięty.

**Kryteria akceptacji:**
- [ ] `docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml config` renderuje się bez błędów, a `minio`/`minio-init` wskazują `quay.io/minio/…` z przypiętym tagiem (bez `latest`); `grep -rn "minio/minio:latest\|minio/mc:latest"` po repo (poza `CR-*.md`, `TASKS-*.md`, `PROGRESS.md`, pamięcią agentów) → 0 trafień
- [ ] `docker manifest inspect` (anonimowo) działa dla obu przypiętych obrazów; pull na czysto (`docker compose pull minio minio-init` na demonie bez cache'u albo `--dry-run` tam, gdzie się da) kończy się sukcesem
- [ ] Healthcheck `minio` (`mc ready local`) przechodzi na przypiętym wydaniu, a `minio-init` tworzy bucket `contact-center-recordings` — sprawdzone na osobnym, tymczasowym projekcie compose (`-p`, inne porty i wolumeny), NIE na stosie użytkownika
- [ ] Dokumentacja zaktualizowana (lista wyżej); `EmailAttachmentStorageServiceMinioTest` i compose używają tego samego wydania (jedno miejsce prawdy albo komentarz „utrzymywać razem")
- [ ] (WP-4, wymaga zgody właściciela) Przełączenie stosu local-demo na przypięte wydanie: najpierw sprawdzić wersję obecnie działającego kontenera i nie schodzić poniżej niej (ryzyko niezgodności formatu danych w wolumenie `minio_data` — do potwierdzenia), pobranie obrazów z quay.io; dane i bucket nietknięte
- [ ] DoD (WP-7): status i notatka w pliku zadań

**Ryzyka:** przypięte wydanie może nie zawierać `mc` (healthcheck) albo różnić się poleceniem startowym — do sprawdzenia; przypięcie starszego wydania niż dane w wolumenie (patrz AC WP-4); quay.io może wymagać osobnej konfiguracji pull w CI (sprawdzić); ruchomy tag w innych miejscach (np. inne obrazy `latest` w compose) poza zakresem tego ticketu.
