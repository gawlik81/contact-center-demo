# 1. Przegląd projektu – Contact Center SaaS

## 1.1 Czym jest system

Contact Center SaaS to **wielokanałowa, wielonajemna (multi-tenant) platforma** do zarządzania
kontaktami przychodzącymi (inbound) i wychodzącymi (outbound). Pozwala organizacjom obsługiwać
klientów przez telefon (VoIP/WebRTC za pomocą Twilio), e-mail i media społecznościowe z jednego
"agent desktopu", a supervisorom i administratorom – monitorować i konfigurować pracę zespołów
w czasie rzeczywistym.

Pełny kontekst biznesowy, persony i wymagania funkcjonalne znajdują się w [`PRD.md`](../../PRD.md).
Ten dokument (oraz pliki w `documentation/`) opisują **stan faktyczny implementacji** – jak system
jest *zbudowany*, nie jak był pierwotnie *zaplanowany* (pierwotny projekt architektury w
[`ARCHITECTURE.md`](../../ARCHITECTURE.md) bywa częściowo nieaktualny).

## 1.2 Persony użytkowników

| Rola | Zakres | Typowe zadania |
|------|--------|----------------|
| **ADMIN** | cała platforma, wszyscy tenanci | zarządzanie tenantami, użytkownikami globalnymi, monitoring techniczny, integracje |
| **SUPERVISOR** | jeden tenant | zarządzanie agentami, kolejkami, kampaniami, IVR, raporty, dyspozycje (dispositions) |
| **AGENT** | jeden tenant, przypisane kolejki | obsługa kontaktów (telefon/e-mail/social), zmiana statusu, przerwy, callbacki |

Role są wymuszane na froncie przez `roleGuard` (sekcja routingu w
[`05-frontend.md`](05-frontend.md)) oraz na backendzie przez `SecurityConfig`
(sekcja security w [`04-backend.md`](04-backend.md)).

## 1.3 Mapa modułów domenowych (backend)

Backend (`backend/app/src/main/java/com/contactcenter/api/`) jest podzielony na moduły wg
domeny biznesowej:

| Moduł | Odpowiedzialność |
|-------|-------------------|
| `tenant` | Zarządzanie tenantami (najemcami) platformy |
| `user`, `auth` | Użytkownicy, logowanie, JWT, MFA, zmiana hasła |
| `agentgroup`, `agentbreak` | Grupy agentów, przerwy/statusy "niedostępności" |
| `queue` | Kolejki kontaktów i routing |
| `telephony`, `dialer`, `ivr` | Telefonia (Twilio), progresywny dialer kampanii, drzewa IVR |
| `campaign` | Kampanie outbound |
| `contact`, `customer` | Kontakty (interakcje) i baza klientów |
| `disposition` | Kody dyspozycji (wyniku) kontaktu |
| `email`, `social` | Kanały e-mail i social media |
| `recording` | Nagrania rozmów (S3/MinIO) |
| `reports`, `telemetry`, `admin` | Raporty, ETL do Data Warehouse, metryki techniczne |
| `auditlog` | Log audytowy zmian |
| `phonenumber` | Zarządzanie numerami telefonów (DID) |
| `websocket` | Kanał realtime (STOMP/WebSocket) |
| `public_` | Endpointy publiczne (np. webhooki Twilio, formularze) |

Szczegóły każdego modułu (klasy, endpointy, relacje) – patrz [`04-backend.md`](04-backend.md).

## 1.4 Mapa funkcji frontendu (Angular)

`frontend/src/app/features/`:

| Feature | Opis |
|---------|------|
| `auth` | Logowanie, zmiana hasła, MFA |
| `admin` | Panel administratora platformy (tenanci, metryki, ETL) |
| `supervisor` | Dashboard supervisora: agenci, kolejki, kampanie, IVR editor, raporty |
| `agent` | Desktop agenta: softphone, aktywny kontakt, dyspozycje, status |
| `campaigns`, `customers`, `dispositions`, `integrations`, `reports`, `tenants` | Współdzielone widoki zarządcze wykorzystywane przez ww. role |

Szczegóły – patrz [`05-frontend.md`](05-frontend.md).

## 1.5 Najważniejsze przepływy biznesowe

Pełne opisy w [`07-data-flows.md`](07-data-flows.md):

1. **Logowanie i multi-tenancy** – JWT + `TenantContext` per-request.
2. **Połączenie przychodzące (inbound call)** – Twilio webhook → IVR → routing do kolejki → agent.
3. **Kampania outbound (progressive dialer)** – dialer wybiera kontakty, inicjuje połączenia Twilio.
4. **Realtime UI** – WebSocket/STOMP: statusy agentów, KPI kolejek, powiadomienia.
5. **Voicebot/AI** – serwis Python (FastAPI) wspomaga IVR (ASR, NLU, podsumowania rozmów).
6. **ETL do Data Warehouse** – `EtlSyncService` synchronizuje dane operacyjne do ClickHouse na potrzeby raportów.
7. **Nagrania** – pobieranie nagrań z Twilio i zapis do MinIO/S3.
8. **Kanał e-mail** – IMAP polling, routing do kolejek, szablony odpowiedzi, wysyłka SMTP.
9. **Kanał social media (chat)** – integracje OAuth (Facebook/Instagram/WhatsApp), odbiór
   wiadomości przez webhooki + RabbitMQ, odpowiedzi agenta. Chatbot tekstowy – patrz §7.13
   (status: planowany, niezaimplementowany).

## 1.6 Jak korzystać z tej dokumentacji

| Dokument | Zawartość |
|----------|-----------|
| [00-index.md](00-index.md) | Spis treści / punkt startowy |
| [01-overview.md](01-overview.md) | Ten dokument – przegląd ogólny |
| [02-architecture.md](02-architecture.md) | Architektura systemu "as-built" |
| [03-tech-stack.md](03-tech-stack.md) | Stos technologiczny i wersje |
| [04-backend.md](04-backend.md) | Dokumentacja backendu (Spring Boot) |
| [05-frontend.md](05-frontend.md) | Dokumentacja frontendu (Angular) |
| [06-database.md](06-database.md) | Schemat bazy danych (PostgreSQL/Flyway) |
| [07-data-flows.md](07-data-flows.md) | Kluczowe przepływy end-to-end |
| [08-infrastructure.md](08-infrastructure.md) | Infrastruktura, Docker Compose, deployment |
| [09-getting-started.md](09-getting-started.md) | Jak uruchomić projekt lokalnie i zacząć pracę |

Dla osoby zaczynającej pracę nad projektem zalecana kolejność czytania:
**01 → 03 → 02 → 09 → 04/05/06 (wg zadania) → 07 → 08**.
