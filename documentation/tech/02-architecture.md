# 2. Architektura systemu (as-built)

> Ten dokument opisuje architekturę **w stanie faktycznym implementacji**. Pierwotny projekt
> architektoniczny (przed implementacją) znajduje się w [`ARCHITECTURE.md`](../../ARCHITECTURE.md) –
> wiele decyzji (ADR-01..08) pozostało aktualnych, ale rzeczywisty system poszerzył stos o
> ClickHouse i MinIO, których nie było w pierwotnym dokumencie.

## 2.1 Styl architektury

System pozostaje **modularnym monolitem** (ADR-01 z `ARCHITECTURE.md` jest wciąż aktualny):

- Jedna aplikacja Spring Boot (`backend/app`) z pakietami modułowymi pod
  `com.contactcenter.api.<domena>` (zob. [01-overview.md](01-overview.md) – mapa modułów).
- Jeden frontend Angular (`frontend/`) z lazy-loadowanymi feature modułami per rola.
- Samodzielny serwis Python/FastAPI (`voicebot/`) do zadań AI/ASR/NLU – jedyny serwis
  działający poza JVM, zgodnie z ADR-06.

Logika biznesowa żyje w pakietach `com.contactcenter.domain.<domena>` – każda domena
(`campaign`, `routing`, `voicebot`, `etl`, ...) jest samodzielnym, zenkapsulowanym modułem
(repozytoria package-private, dostęp cross-domain wyłącznie przez publiczny interfejs serwisu).
Pełny opis konwencji pakietów domenowych i wzorca enkapsulacji – sekcja "Konwencje pakietów
domenowych i enkapsulacja" w [`04-backend.md`](04-backend.md).

## 2.2 Diagram wysokopoziomowy (as-built)

```
                         BROWSER (Angular SPA)
            Agent Desktop | Supervisor Dashboard | Admin Panel
                 HTTP/REST  +  WebSocket (STOMP)
                              |
                         NGINX (reverse proxy, TLS w prod / ngrok w local-demo)
                              |
                 +------------+-------------+
                 |                          |
        Spring Boot Backend          Angular static (Nginx)
        (modularny monolit, :8080)
                 |
   +-------------+--------------------------------------------+
   | JwtAuthFilter -> TenantFilter -> UsernamePasswordAuthFilter|
   +-------------------------------------------------------------+
        |        |          |          |        |       |
     auth/    tenant/     queue/     campaign/  ivr/    recording/
     user     agentgroup  routing    dialer     telephony  email/
                                                            social/  ...
        |        |          |          |        |       |
        v        v          v          v        v       v
   +----------------------------------------------------------+
   |                     PostgreSQL 16 (RLS, tenant_id)        |
   +----------------------------------------------------------+

        |                    |                    |
        v                    v                    v
     Redis 7              RabbitMQ 3.13         MinIO (S3)
  (cache/presence/    (eventy domenowe,      (nagrania rozmów,
   JWT blacklist)      voicebot escalation)    pliki)

        |                                          |
        v                                          v
   Voicebot (FastAPI, Python)               ClickHouse 24.3 (DWH)
   - ASR (Whisper)                          zasilane przez EtlSyncService
   - NLU / intencje                         (sync co 60s, @Scheduled)
   - podsumowania (Anthropic/OpenAI)
        |
        v
   RabbitMQ (eskalacja do agenta) / Redis (sesja rozmowy)

   Zewnętrzne: Twilio (Programmable Voice) – webhooki HTTP do
   TwilioWebhookController / TwilioVoiceController
```

## 2.3 Warstwa bezpieczeństwa i multi-tenancy (przekrojowa)

Krytyczna kolejność filtrów (musi być zachowana – patrz `CLAUDE.md`):

```
JwtAuthFilter  →  TenantFilter  →  UsernamePasswordAuthenticationFilter
```

- `JwtAuthFilter` – waliduje JWT (RS256), wyciąga `userId`, `tenantId`, role.
- `TenantFilter` – ustawia `TenantContext` (ThreadLocal) na czas requestu; pomija
  weryfikację dla ścieżek z `TenantFilter.PUBLIC_PATH_PREFIXES`.
- Repozytoria danych dziedziczą po `TenantAwareRepository` i wywołują
  `assertSameTenant(entity.getTenantId())` przed zapisem.
- W warstwie DB dodatkowo Row-Level Security (RLS) – patrz [`06-database.md`](06-database.md).
- Przy granicach wątków (`@Async`, `CompletableFuture`, `@Scheduled`) – `TenantContext.snapshot()`
  / `restore()` / `clear()` (patrz [`04-backend.md`](04-backend.md)).

Pełny opis JWT/MFA/role – sekcja "Security" w [`04-backend.md`](04-backend.md).

## 2.4 Komunikacja w czasie rzeczywistym

- **STOMP over WebSocket** (`WebSocketController`, endpoint `/ws` + `@SendToUser("/events")`) –
  używany do powiadomień push: zmiany statusu agenta, nowe kontakty w kolejce, KPI live na
  dashboardzie supervisora.
- Frontend konsumuje te eventy przez serwis WebSocket (RxJS `Subject`/`Observable`), aktualizując
  signale komponentów.

## 2.5 Integracje zewnętrzne

| Integracja | Kierunek | Mechanizm |
|------------|----------|-----------|
| Twilio Programmable Voice | inbound: Twilio → `TwilioWebhookController`/`TwilioVoiceController` (TwiML); outbound: backend → Twilio REST API (`com.twilio.sdk`) | HTTP webhooks + REST, per-tenant `TwilioRestClient` cache (Caffeine) |
| Voicebot (Python) | backend ↔ voicebot | REST (np. transkrypcja, NLU, podsumowanie) + RabbitMQ (eskalacja do agenta) |
| MinIO/S3 | backend → MinIO | AWS S3 SDK – upload/download nagrań (`RecordingService`, `TwilioRecordingDownloadService`) |
| ClickHouse | backend → ClickHouse | JDBC, zapisy wsadowe przez `EtlSyncService` (co 60s) na potrzeby `reports`/`admin` |
| Serwery IMAP/SMTP (per tenant) | backend ↔ skrzynka tenanta | Jakarta Mail – `EmailPollingService` (polling co ~60s) i `EmailSendService` (moduł `email`) |
| Facebook/Instagram/WhatsApp | dwustronnie: webhooki (inbound) + Graph/WhatsApp Cloud API (outbound) | OAuth2 + webhooki (`SocialOAuthController`/`SocialWebhookController`), adaptery w `infrastructure/social` (moduł `social`) |

## 2.6 Przepływ danych operacyjnych → analitycznych (DWH)

`EtlSyncService` (zob. `backend/app/src/main/java/com/contactcenter/domain/etl/EtlSyncService.java`
+ `EtlSyncServiceImpl.java`) działa jako zadania `@Scheduled(fixedDelayString = "${etl.sync.fixed-delay-ms:60000}")`:

- `runContactSync()` – synchronizuje tabelę kontaktów do `ContactDwRow` w ClickHouse.
- `runCampaignContactSync()` – fakty kampanii (`CampaignDwRow`).
- `runAgentDimSync()` / `runQueueDimSync()` – wymiary (dimensions) agentów i kolejek.
- Status synchronizacji śledzony per tabela (`markDone`/`markError`/`checkLagAndAlert`) i
  widoczny w panelu admina (`EtlStatusController`, `AdminMetricsController`).

To odpowiada ADR-07 z pierwotnej architektury (replikacja do DWH), zaimplementowanej jako
okresowy ETL (polling), a nie ścisły outbox/CDC.

## 2.7 Status decyzji architektonicznych (ADR) z `ARCHITECTURE.md`

| ADR | Decyzja | Status w implementacji |
|-----|---------|--------------------------|
| ADR-01 | Modularny monolit | ✅ aktualne |
| ADR-02 | PostgreSQL + logiczny multi-tenancy (tenant_id + RLS) | ✅ aktualne, zob. `06-database.md` |
| ADR-03 | RabbitMQ jako broker | ✅ aktualne |
| ADR-04 | Redis – cache/presence/stan kolejek | ✅ aktualne |
| ADR-05 | Adapter dla providera telefonii | ✅ – aktualnie zaimplementowany dla Twilio (`telephony`) |
| ADR-06 | Python AI service (voicebot) | ✅ aktualne |
| ADR-07 | DWH przez CDC/event streaming | ⚠️ zmodyfikowane – zaimplementowano jako okresowy ETL (`EtlSyncService`) do **ClickHouse** (nie wspomnianego w oryginale) |
| ADR-08 | Modularna architektura social media | ⚠️ częściowo – moduł `social` istnieje, zakres integracji do weryfikacji w kodzie |

**Nowe elementy względem pierwotnego projektu:**
- **MinIO** jako S3-compatible storage nagrań (`recording`, `S3Config`/`S3Properties`).
- **ClickHouse** jako silnik DWH (zamiast niesprecyzowanego "Data Warehouse" z ADR-07).
- Moduły `ivr` i `dialer` jako odrębne, rozwinięte domeny (edytor IVR z pozycjonowaniem węzłów,
  zoom/fit-to-view – patrz historia commitów).

## 2.8 Gdzie szukać szczegółów

- Backend (moduły, endpointy, wzorce): [`04-backend.md`](04-backend.md)
- Frontend (routing, feature moduły, state): [`05-frontend.md`](05-frontend.md)
- Schemat bazy danych: [`06-database.md`](06-database.md)
- Przepływy end-to-end (np. inbound call, kampania, IVR): [`07-data-flows.md`](07-data-flows.md)
- Infrastruktura/Docker/deployment: [`08-infrastructure.md`](08-infrastructure.md)
