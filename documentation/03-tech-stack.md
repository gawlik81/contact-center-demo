# 3. Stos technologiczny

## 3.1 Backend

| Komponent | Technologia | Wersja / uwagi |
|-----------|-------------|-----------------|
| Język | Java | 21 |
| Framework | Spring Boot | 3.3.5 (`spring-boot-starter-parent`) |
| Build | Maven (multi-moduł: `backend/pom.xml` + `backend/app`) | |
| Web | `spring-boot-starter-web` | REST, Spring MVC |
| Bezpieczeństwo | `spring-boot-starter-security` + JJWT (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`) | tokeny JWT podpisywane RS256 |
| Baza danych | `spring-boot-starter-data-jpa` + PostgreSQL 16 | Hibernate/JPA |
| Migracje | Flyway (`flyway-core`, `flyway-database-postgresql`) | `backend/src/main/resources/db/migration/V001...` |
| Cache / stan realtime | `spring-boot-starter-data-redis` + Caffeine (lokalny cache per-tenant, np. `TwilioRestClient`) | |
| Broker komunikatów | `spring-boot-starter-amqp` → RabbitMQ 3.13 | |
| Realtime | `spring-boot-starter-websocket` (STOMP) | `WebSocketController` |
| Walidacja | `spring-boot-starter-validation` | |
| Mapowanie DTO↔Encja | MapStruct | generowane w czasie kompilacji |
| Boilerplate | Lombok | gettery/settery/builder |
| Telefonia | Twilio Java SDK (`com.twilio.sdk:twilio`) | Programmable Voice REST API |
| Monitoring | `spring-boot-starter-actuator` | healthcheck `/actuator/health` |
| Dokumentacja API | springdoc-openapi (Swagger UI) | `http://localhost:8080/swagger-ui.html` |
| Object storage | AWS S3 SDK (MinIO-kompatybilny) | nagrania rozmów (`recording`) |
| Data Warehouse | ClickHouse JDBC driver | `ClickHouseDwWriter`, `EtlSyncService` |

## 3.2 Frontend

| Komponent | Technologia | Wersja / uwagi |
|-----------|-------------|-----------------|
| Framework | Angular | ^21.2.0, **standalone components**, brak NgModules |
| Język | TypeScript | ~5.9.2 |
| Reaktywność | RxJS (~7.8.0) + Angular Signals (`signal()`/`computed()`) | signals preferowane dla stanu lokalnego, RxJS dla streamów/pollingu |
| i18n | `@jsverse/transloco` (^8.3.0) | tłumaczenia PL/EN |
| Build/dev server | Angular CLI (`@angular/build`) | proxy do `localhost:8080` w dev |
| Testy | Vitest | `npm test` |
| Lint/format | ESLint + Prettier (`printWidth: 100`, `singleQuote: true`, `tabWidth: 2`) | |
| Realtime | STOMP over WebSocket (klient JS) | korespondent backendowego `WebSocketController` |

## 3.3 Serwis AI / Voicebot (Python)

Katalog `voicebot/` – samodzielny serwis FastAPI wywoływany przez backend i/lub przez RabbitMQ.

| Komponent | Technologia | Uwagi |
|-----------|-------------|-------|
| Framework | FastAPI + Uvicorn | `voicebot/app/main.py` |
| ASR (speech-to-text) | `openai-whisper` | model wczytywany przy starcie (`_get_model`) |
| NLU | własny moduł `nlu.py` + ewentualnie modele LLM | `detect_intent` |
| Podsumowania rozmów | `summarize.py` | wykorzystuje `anthropic` i/lub `openai` SDK |
| Sesje rozmów | Redis (`redis.asyncio`) | `session.py` – get/update/delete session |
| Komunikacja async | RabbitMQ (`aio_pika`, robust connection) | `rabbit.py` – `publish_escalation` |
| Konfiguracja | `pydantic-settings` | `config.py` |
| Testy | pytest + pytest-asyncio | `voicebot/tests/` |

## 3.4 Bazy danych i magazyny danych

| Magazyn | Technologia | Rola |
|---------|-------------|------|
| Relacyjna (OLTP) | PostgreSQL 16 (alpine) | główna baza operacyjna, multi-tenant (RLS + `tenant_id`) |
| Cache / stan in-memory | Redis 7 (alpine) | sesje, JWT blacklist, presence agentów, sesje voicebota |
| Message broker | RabbitMQ 3.13 (management) | komunikacja asynchroniczna BE ↔ voicebot, eventy domenowe |
| Object storage | MinIO (S3-compatible) | nagrania rozmów, pliki |
| Data Warehouse (OLAP) | ClickHouse 24.3 | raporty/analityka – zasilana przez `EtlSyncService` |

## 3.5 Infrastruktura

| Element | Technologia |
|---------|-------------|
| Konteneryzacja | Docker / Docker Compose (`docker-compose.yml` + `docker-compose.local-demo.yml`) |
| Reverse proxy | Nginx (`nginx/`) |
| Local demo / tunneling | ngrok (SSL terminowany przez ngrok w trybie local-demo) |

Szczegółowy opis usług i ich konfiguracji – patrz [`08-infrastructure.md`](08-infrastructure.md).

## 3.6 Wersjonowanie i konwencje

- Backend: Maven multi-module (`backend/pom.xml` – rodzic, `backend/app` – aplikacja).
- Frontend: jeden projekt Angular (`frontend/`).
- Migracje DB: Flyway, nazewnictwo `Vxxx__opis.sql`, **nigdy nie edytować zastosowanej migracji**
  (patrz [`06-database.md`](06-database.md) i zasady w `CLAUDE.md`).
- Gałęzie git: `feature/<opis>`, `fix/<opis>`, `chore/<opis>`.
