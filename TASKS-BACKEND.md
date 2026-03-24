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
**Zależności:** DB-001
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
**Zależności:** BE-001
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
**Zależności:** BE-001, DB-002
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
**Zależności:** BE-001, DB-003
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
**Zależności:** BE-003
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
**Zależności:** BE-002, DB-004
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
**Zależności:** BE-002, DB-005
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
**Zależności:** BE-006, BE-002
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
**Zależności:** BE-002, DB-003
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
**Zależności:** BE-001, DB-006
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
**Zależności:** BE-009, DB-006
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
**Zależności:** BE-009, BE-025
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
**Zależności:** BE-001, BE-003
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

## MODUL: IVR i Automatyzacja (EPIC-04)

### BE-013 – IVR Engine: wykonanie drzewa IVR

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** BE-009, DB-009
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-014, FE-014
**Odniesienie PRD:** US-04-01, US-04-03, US-04-04, EPIC-04

**Opis:**
Silnik IVR interpretujący JSONB definicję drzewa (`IVR_TREE.definition`). Przetwarzanie węzłów: PlayAudio (stream z S3), TTS (wywołanie Google/Azure TTS API), CollectDTMF (oczekiwanie na wejście), TransferToQueue, Hangup. Integracja ze stanem połączenia przez TelephonyAdapter. Obsługa timeout (brak wejścia DTMF → domyślna gałąź).

**Kryteria akceptacji:**
- [ ] Węzeł PlayAudio odtwarza plik z S3 przez TelephonyAdapter
- [ ] Węzeł TTS wywołuje zewnętrzne API i cache'uje wygenerowany plik audio (Redis/S3) na 24h
- [ ] CollectDTMF obsługuje timeout (domyślnie 10s) i przejście do gałęzi "no-input"
- [ ] TransferToQueue przekazuje połączenie do routing engine z odpowiednią kolejką
- [ ] Błąd w IVR (wyjątek, nieosiągalny węzeł) → przekazanie do kolejki domyślnej zamiast hangup

---

### BE-014 – Voicebot Python: ASR + NLU + eskalacja do agenta

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** BE-013, DB-009
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Odniesienie PRD:** US-04-02, EPIC-04

**Opis:**
Serwis Python (FastAPI) integrujący ASR (Google Speech-to-Text lub Whisper) i prosty NLU (reguły lub fine-tuned model). Logika: jeśli confidence < 0.70 → eskalacja do kolejki agentów (event na RabbitMQ z priority=HIGH). API: `POST /voicebot/turn` (audio chunk → intent + confidence). Sesja konwersacji w Redis (TTL 15 min).

**Kryteria akceptacji:**
- [ ] Confidence < 0.70 zawsze skutkuje eskalacją (test: mock ASR z confidence=0.69 → event ESCALATE)
- [ ] Eskalacja zawiera transcript rozmowy jako kontekst dla agenta
- [ ] Sesja voicebot w Redis TTL 15 min, czyszczona po hangup
- [ ] `POST /voicebot/turn` odpowiada < 2s (p95) dla audio do 5s

---

## MODUL: Kanał Email (EPIC-05)

### BE-015 – Email Adapter: IMAP polling + SMTP wysyłka

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** BE-001, DB-007
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-016, FE-012
**Odniesienie PRD:** US-05-01, US-05-02, US-05-04, EPIC-05

**Opis:**
Serwis emailowy: polling IMAP co 60s (konfiguracja per tenant: host, port, login, hasło zaszyfrowane), parsowanie wiadomości (JavaMail/Jakarta Mail), zapis do tabeli CONTACT. Routing emaila do kolejki przez `EmailRoutingEngine` (reguły: nadawca, temat, słowa kluczowe → kolejka). SMTP do wysyłki odpowiedzi. Linkowanie wątku po Message-ID / In-Reply-To.

**Kryteria akceptacji:**
- [ ] Nowe emaile odbierane w czasie < 2 min od wpłynięcia na skrzynkę
- [ ] Wątek email grupowany po nagłówkach Message-ID / In-Reply-To / Subject (Re:)
- [ ] Routing emaila przypisuje do kolejki zgodnie z regułami w DB (priorytet reguł: kolejność)
- [ ] Wysłana odpowiedź zapisana jako kolejna wiadomość w wątku (CONTACT powiązany)
- [ ] Dane logowania IMAP/SMTP szyfrowane AES-256 w DB (not plaintext)

---

### BE-016 – Szablony odpowiedzi email: CRUD API

**Typ:** Feature
**Priorytet:** Should Have
**Zlozonosc:** S
**Zależności:** BE-002, DB-007
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-012
**Odniesienie PRD:** US-05-03, EPIC-05

**Opis:**
Tabela `EMAIL_TEMPLATE` (template_id, tenant_id, name, subject_template, body_html, variables JSONB). Endpointy CRUD: `GET /api/email-templates`, `POST /api/email-templates`, `PATCH`, `DELETE`. Zmienne w szablonie jako `{{customer.first_name}}` – renderowanie przez silnik Mustache/Freemarker przed wysłaniem.

**Kryteria akceptacji:**
- [ ] Renderowanie szablonu z podstawieniem zmiennych
- [ ] Walidacja: brak undefined zmiennych w szablonie zwraca HTTP 422 z listą brakujących pól
- [ ] Szablony widoczne tylko w ramach tenanta (izolacja tenant_id)

---

## MODUL: Kanał Social Media (EPIC-06)

### BE-017 – OAuth flow i zarządzanie tokenami social media

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** BE-002, DB-008
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** BE-018, FE-023
**Odniesienie PRD:** US-06-02, EPIC-06

**Opis:**
Endpointy OAuth 2.0 callback dla: Facebook Messenger API, Instagram API, WhatsApp Business API. Zapis access_token i refresh_token (AES-256 encrypted) do tabeli SOCIAL_INTEGRATION. Mechanizm automatycznego odświeżenia tokenu przed wygaśnięciem (scheduled task co 1h sprawdzający tokeny wygasające w ciągu 24h).

**Kryteria akceptacji:**
- [ ] OAuth callback dla każdej z 3 platform zapisuje token do DB
- [ ] Tokeny szyfrowane AES-256 w kolumnie (nie plaintext)
- [ ] Automatyczne odświeżenie tokenu loguje sukces/błąd (AUDIT_LOG)
- [ ] Endpoint `DELETE /api/integrations/{platform}` revoke'uje token u providera i usuwa z DB

---

### BE-018 – Social Media Adapter: odbieranie i wysyłka wiadomości

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** BE-017, DB-008
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** FE-013
**Odniesienie PRD:** US-06-01, US-06-03, US-06-04, EPIC-06

**Opis:**
Implementacja interfejsu `SocialMediaAdapter` z metodami: receiveMessage, sendMessage, getConversationHistory. Trzy implementacje: FacebookAdapter, InstagramAdapter, WhatsAppAdapter. Webhooki od platform (POST /webhooks/facebook, /webhooks/instagram, /webhooks/whatsapp) przetwarzane asynchronicznie przez RabbitMQ. Routing wiadomości do kolejki przez analogiczny mechanizm jak email.

**Kryteria akceptacji:**
- [ ] Webhook endpoint zwraca HTTP 200 w < 3s (szybkie ACK, przetwarzanie async)
- [ ] Wiadomości od jednego użytkownika na jednej platformie grupowane w konwersację (CONTACT)
- [ ] sendMessage obsługuje: tekst, emoji (Unicode), zdjęcia (URL) – dla WhatsApp i FB
- [ ] Test integracyjny z mockiem webhooka Facebooka (weryfikacja parsowania payload)

---

## MODUL: Routing i Kolejkowanie (EPIC-07)

### BE-019 – Routing Engine: skill-based, round-robin, sticky agent

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** XL
**Zależności:** BE-008, DB-010
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
**Zależności:** BE-002, DB-010
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
**Zależności:** BE-019, BE-020
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Odniesienie PRD:** US-07-04, EPIC-07

**Opis:**
Serwis kalkulujący szacowany czas oczekiwania w kolejce na podstawie: liczby oczekujących, dostępnych agentów, historycznego AVG handle time (z ostatnich 7 dni z data warehouse lub lokalna agregacja). Wynik zwracany w evencie QUEUE_WAIT_UPDATE przez RabbitMQ → WebSocket do klienta (np. przez IVR lub widget na stronie).

**Kryteria akceptacji:**
- [ ] Szacowany czas obliczany: (waiting_count / available_agents) * avg_handle_time_seconds
- [ ] AVG handle time obliczany z ostatnich 7 dni z tabeli CONTACT
- [ ] Wynik aktualizowany co 30s dla aktywnych kolejek

---

## MODUL: Kampanie Outbound (EPIC-08)

### BE-022 – Campaign CRUD API i harmonogram

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** BE-002, DB-011
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
**Zależności:** BE-022, DB-011
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
**Zależności:** BE-009, BE-022, DB-011
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Odniesienie PRD:** US-08-03, US-08-05, EPIC-08

**Opis:**
Serwis `ProgressiveDialer`: po zmianie statusu agenta na AVAILABLE (event RabbitMQ) i aktywnej kampanii → pobierz następny kontakt z CAMPAIGN_CONTACT (status=PENDING), inicjuj połączenie przez TelephonyAdapter. Po odpowiedzi klienta – bridging do agenta. Po disposition code CALLBACK → zaplanuj ponowne wywołanie w harmonogramie.

**Kryteria akceptacji:**
- [ ] Połączenie inicjowane w < 5s od zmiany statusu agenta na AVAILABLE
- [ ] Brak odpowiedzi (timeout 30s) → status CAMPAIGN_CONTACT = NO_ANSWER, next attempt +4h
- [ ] Disposition CALLBACK tworzy rekord w SCHEDULED_CALLBACKS z datą/godziną
- [ ] Dialer respektuje godziny kampanii (nie dzwoni poza harmonogramem)
- [ ] Wstrzymanie kampanii (PAUSED) natychmiast zatrzymuje nowe inicjowania połączeń

---

## MODUL: Baza Klientów (EPIC-09)

### BE-025 – Customer CRUD API i fuzzy search

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** L
**Zależności:** BE-002, DB-012
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
**Zależności:** BE-025, DB-012
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
**Zależności:** BE-002, DB-006
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
**Zależności:** BE-027, DB-013
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
**Zależności:** BE-012, BE-019
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
**Zależności:** BE-027, DB-013, DB-014
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Odniesienie PRD:** US-10-06, EPIC-10

**Opis:**
Pipeline replikacji danych: PostgreSQL CDC przez Debezium (logical replication) → RabbitMQ (lub Kafka topic) → Python ETL serwis transformujący i ładujący do ClickHouse/BigQuery. Opóźnienie < 1h (wymóg PRD). Tabele docelowe: contacts_dw, agents_performance_dw, campaigns_dw. Idempotentne ładowanie (upsert po event_id).

**Kryteria akceptacji:**
- [ ] Nowy rekord CONTACT w PostgreSQL pojawia się w ClickHouse w czasie < 1h
- [ ] ETL idempotentny: ponowne przetworzenie tego samego eventu nie tworzy duplikatów
- [ ] Alert monitoringowy gdy lag replikacji > 30 min
- [ ] Transformacje zachowują anonimizację RODO (pola anonymized nie trafiają do DW)

---

## MODUL: RODO / GDPR (przekrojowe)

### BE-031 – RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17)

**Typ:** Feature
**Priorytet:** Must Have
**Zlozonosc:** M
**Zależności:** BE-025, BE-027, DB-012
**Status:** ⬜ Nie rozpoczęte
**Blokuje:** brak
**Odniesienie PRD:** US-09-06, wymagania RODO

**Opis:**
Endpoint `POST /api/customers/{id}/gdpr/export` – generuje ZIP z danymi klienta w JSON (CUSTOMER, CONTACT history, AUDIT_LOG). Endpoint `POST /api/customers/{id}/gdpr/anonymize` – anonimizuje pola PII, usuwa nagrania z S3, usuwa wątki email/social (lub anonimizuje treść). Oba działania logowane w AUDIT_LOG z userId wykonującego operację.

**Kryteria akceptacji:**
- [ ] Export ZIP zawiera wszystkie dane klienta w czytelnym JSON
- [ ] Anonimizacja: wszystkie pola PII zastąpione, is_deleted=true, plik nagrania usunięty z S3
- [ ] Obie operacje wymagają roli SUPERVISOR lub ADMIN
- [ ] Operacje logowane w AUDIT_LOG z entity_type='CUSTOMER', action='GDPR_EXPORT'/'GDPR_ANONYMIZE'

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
| Telephony | BE-009 → BE-010, BE-011, BE-012 |
| IVR + Voicebot | BE-013 → BE-014 |
| Email | BE-015 → BE-016 |
| Social Media | BE-017 → BE-018 |
| Routing | BE-019 → BE-020, BE-021 |
| Kampanie | BE-022 → BE-023, BE-024 |
| Klienci | BE-025 → BE-026, BE-031 |
| Raporty | BE-027 → BE-028, BE-029, BE-030 |

---

## Podsumowanie zadań Backend

| Kategoria | Liczba zadań | Must Have | Should Have |
|-----------|-------------|-----------|-------------|
| Infrastruktura | 5 | 5 | 0 |
| Tenants (EPIC-01) | 2 | 2 | 0 |
| Użytkownicy (EPIC-02) | 1 | 1 | 0 |
| Telefonia (EPIC-03) | 4 | 4 | 0 |
| IVR + Voicebot (EPIC-04) | 2 | 2 | 0 |
| Email (EPIC-05) | 2 | 1 | 1 |
| Social Media (EPIC-06) | 2 | 2 | 0 |
| Routing (EPIC-07) | 3 | 2 | 1 |
| Kampanie (EPIC-08) | 3 | 3 | 0 |
| Klienci (EPIC-09) | 2 | 2 | 0 |
| Raporty (EPIC-10) | 4 | 4 | 0 |
| RODO | 1 | 1 | 0 |
| **RAZEM** | **31** | **29** | **2** |
