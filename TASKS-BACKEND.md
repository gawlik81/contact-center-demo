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
**Blokuje:** BE-002, BE-005, BE-009, BE-015
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
**Blokuje:** BE-003, BE-005, BE-006, BE-008, BE-012, BE-015, BE-017, BE-020, BE-022, BE-025, BE-027
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
**Blokuje:** BE-004, BE-012
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
**Blokuje:** BE-007, FE-006
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
**Blokuje:** BE-019, FE-008
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
**Blokuje:** BE-010, BE-011, BE-012, BE-013, BE-024
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
**Zależy od:** BE-009, DB-006
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-19
**Blokuje:** brak
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
**Blokuje:** brak
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
**Zależy od:** BE-001, BE-003
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-18
**Blokuje:** FE-009, FE-021
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
**Zależy od:** BE-009, DB-009
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-03-25
**Blokuje:** BE-014, FE-014
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
- [x] Test integracyjny z mockiem webhooka Facebooka (weryfikacja parsowania payload)

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
**Blokuje:** FE-024
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
**Blokuje:** BE-023, BE-024, FE-015
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
**Blokuje:** brak
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
**Blokuje:** BE-026, BE-031, FE-018, FE-019, FE-011
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
**Blokuje:** BE-028, BE-029, BE-030, BE-031, FE-019, FE-022
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
**Blokuje:** BE-030b
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
- [x] Testy integracyjne weryfikują zapis do ClickHouse (testcontainers lub mock)

---

## MODUL: RODO / GDPR (przekrojowe)

### BE-031 – RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależy od:** BE-025, BE-027, DB-012
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-04-09
**Blokuje:** brak
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
**Blokuje:** FE-028, FE-029
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
**Blokuje:** FE-034, FE-035

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
**Blokuje:** BE-050, BE-051
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
**Blokuje:** FE-045
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
**Blokuje:** BE-056, BE-057, BE-058
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
**Blokuje:** BE-057
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
**Blokuje:** BE-059, BE-060
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
**Blokuje:** BE-063
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
**Blokuje:** BE-065
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
**Status:** [ ] Do zrobienia
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
**Blokuje:** FE-073
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
**Status:** ⬜ Nie rozpoczęte
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
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-078
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
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-079
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
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-080
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
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-080, BE-081, FE-081
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
- [ ] `POST /api/campaigns` bez pola `queueId` zwraca 201 (kampania tworzona bez kolejki)
- [ ] `POST /api/campaigns` z `queueId` nadal działa (backward compat — pole zapisywane, ale nie walidowane)
- [ ] `mvn verify -pl app` przechodzi — brak kompilacji do `QueueRepository` w `CampaignService`
- [ ] Istniejące kampanie z `queue_id != NULL` działają bez zmian

---

### BE-080 – Campaign Assignment API: trójpoziomowe przypisanie agentów (`CampaignAssignmentController`)

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-036, BE-079
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-081, BE-084, FE-082
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
- [ ] `GET /api/campaigns/{id}/assignment` — zwraca `allAgents=true` dla migrowanych kampanii
- [ ] `PUT /api/campaigns/{id}/assignment` z `allAgents=true` → ustawia flagę, listy puste w response
- [ ] `PUT` z `allAgents=false, directAgentIds=[A,B], groupIds=[G1]` → atomowo podmienia przypisanie
- [ ] Przypisanie agenta z innego tenanta → HTTP 400 (jak `QueueAssignmentService`)
- [ ] Przypisanie grupy z innego tenanta → HTTP 400
- [ ] `resolveEligibleAgentIds()` zwraca UNION bezpośrednich agentów + członków grup (bez duplikatów)
- [ ] Wszystkie endpointy wymagają ADMIN lub SUPERVISOR
- [ ] `mvn verify -pl app` przechodzi

---

### BE-081 – Aktualizacja `ProgressiveDialerService`: trójpoziomowa kwalifikacja agentów do kampanii

**Typ:** Refactor + Feature
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-036, BE-079, BE-080
**Status:** ⬜ Nie rozpoczęte
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
- [ ] `all_agents=true`: dialer inicjuje połączenia dla każdego AVAILABLE agenta tenanta
- [ ] `all_agents=false` + przypisany bezpośrednio: dialer dzwoni przez tego agenta
- [ ] `all_agents=false` + agent należy do przypisanej grupy: dialer dzwoni przez tego agenta
- [ ] `all_agents=false` + brak przypisania: kampania pominięta (WARN log), brak połączeń
- [ ] `all_agents=false` + agent nie przypisany: kampania pominięta dla tego agenta
- [ ] Dialer nie wywołuje `QueueRepository` — kompilacja bez tej zależności
- [ ] Testy jednostkowe `ProgressiveDialerServiceTest` pokrywają wszystkie 5 przypadków powyżej
- [ ] `mvn verify -pl app` przechodzi

---

### BE-084 – Filtrowanie `GET /api/dialer/manual/records` według przypisania agenta do kampanii

**Typ:** Feature / Bug fix
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-036, BE-080
**Status:** ⬜ Nie rozpoczęte
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
- [ ] Agent z `all_agents=true` dla kampanii: widzi rekordy tej kampanii
- [ ] Agent bezpośrednio przypisany (`campaign_agent`): widzi rekordy
- [ ] Agent w grupie przypisanej do kampanii (`campaign_agent_group`): widzi rekordy
- [ ] Agent nieprzypisany (`all_agents=false`, brak bezpośredniego/grupowego przypisania): **nie widzi** kampanii w panelu manualnym
- [ ] Kampania bez żadnych przypisań (`all_agents=false`, puste tabele): żaden agent jej nie widzi
- [ ] Testy jednostkowe: 5 przypadków powyżej
- [ ] `mvn verify -pl app` przechodzi

---

### BE-082 – Ustawienie `campaign_id` na kontakcie wychodzącym — przepięcie `queueId` na `campaignId` w `TelephonyAdapter.initiateCall()`

**Typ:** Bug fix
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-079
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
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
- [ ] `GET /api/contacts?campaignId={id}` zwraca kontakty wychodzące zainicjowane przez dialer dla tej kampanii
- [ ] Rekord `contact` ma `campaign_id = campaign.campaignId` po wywołaniu dialera
- [ ] Rekord `contact` ma `campaign_id = callback.campaignId` po wykonaniu campaign-callback przez `ScheduledCallbackExecutor`
- [ ] Rekord `contact` ma `campaign_id = NULL` dla połączeń ad-hoc (`AgentCallController`)
- [ ] `contact.queue_id` pozostaje `NULL` dla kontaktów wychodzących (nie wchodzą do routingu kolejkowego)
- [ ] `RoutingService` nadal poprawnie pomija outbound kontakty z `agentId != null`
- [ ] `MockTelephonyAdapter` zaktualizowany — testy jednostkowe przechodzą
- [ ] `mvn verify -pl app` przechodzi

---

### BE-078 – Endpoint `POST /api/telephony/calls/{callId}/bridge/{secondCallId}` — łączenie nóg dla attended transfer

**Typ:** Feature
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-074
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-080
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
- [ ] `POST /api/telephony/calls/{callId}/bridge/{secondCallId}` zwraca 204
- [ ] Po bridge: callId → `TRANSFERRED`, secondCallId → `ACTIVE`
- [ ] Publikowany event `CALL_TRANSFERRED` z `transferType=ATTENDED`
- [ ] callId nienależący do agenta → 403
- [ ] Niezgodny stan sesji → 409
- [ ] `mvn verify -pl app` przechodzi

---

## MODUŁ: Blokada transferu do kolejki dla połączeń wychodzących (EPIC-25)

### BE-083 – Guard: odrzucenie transferu OUTBOUND → QUEUE w endpoincie transferu

**Typ:** Bug fix / Validation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** BE-077 (endpoint transferu), BE-082 (contact.direction ustawiony poprawnie)
**Status:** ⬜ Nie rozpoczęte
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
- [ ] `POST /api/telephony/calls/{callId}/transfer` z `targetType=QUEUE` dla kontaktu `direction=OUTBOUND` → HTTP 400 z opisowym komunikatem
- [ ] Ten sam endpoint z `targetType=QUEUE` dla kontaktu `direction=INBOUND` → działa poprawnie (bez zmian)
- [ ] Transfer `OUTBOUND` z `targetType=PHONE` lub `targetType=AGENT` → działa poprawnie (bez zmian)
- [ ] Test jednostkowy: `outbound + QUEUE → InvalidOperationException`
- [ ] `mvn verify -pl app` przechodzi

---

## MODUŁ: Historia prób wydzwonienia rekordu kampanii (EPIC-25)

### BE-085 – Powiązanie kontaktu z rekordem kampanii: zapis `campaign_contact_record_id` + endpoint historii

**Typ:** Feature + Bug fix
**Priorytet:** Must Have
**Złożoność:** M
**Zależy od:** DB-037, BE-082 (campaign_id na kontakcie)
**Status:** ⬜ Nie rozpoczęte
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
- [ ] Po zainicjowaniu połączenia przez dialer: `contact.campaign_contact_record_id = recordId`
- [ ] Po odebraniu przez klienta (CONNECTED): `campaign_contact.last_contact_id = contactId`
- [ ] `GET /api/campaigns/{campaignId}/contacts/{recordId}/attempts` zwraca listę kontaktów dla rekordu
- [ ] Lista posortowana `started_at DESC` — najnowsza próba na górze
- [ ] `CampaignContactResponse.lastContactId` wypełnione gdy kampania ma przynajmniej jedną próbę
- [ ] Backward compat: istniejące rekordy bez `campaign_contact_record_id` (NULL) — endpoint zwraca pustą listę
- [ ] Redis backward compat: stary format (4 części) obsługiwany przez `DialerCallbackHandler`
- [ ] Test jednostkowy: `DialerCallbackHandlerTest` — hangup z 5-elementowym Redis state
- [ ] `mvn verify -pl app` przechodzi

---

## EPIC-26: AI-Powered Conversation Summary

### BE-086 – Encja `TenantAiConfig` + Repository + konwerter szyfrowania

**Typ:** Backend implementation
**Priorytet:** Must Have
**Złożoność:** S
**Zależy od:** DB-038 (tabela `tenant_ai_config`), BE-055 (wzorzec `EncryptedStringConverter`)
**Status:** ✅ Ukończone
**Zrealizowane:** 2026-05-24
**Zależy od:** DB-038
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
**Blokuje:** BE-093, BE-094
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
**Status:** ⬜ Do zrobienia
**Blokuje:** FE-090
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
**Status:** ⬜ Do zrobienia
**Blokuje:** FE-093
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
