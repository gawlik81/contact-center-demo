# 8. Infrastruktura i wdrożenie

## 8.1 Docker Compose – usługi

Projekt uruchamia się przez dwa pliki compose łączone razem:

```bash
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d --remove-orphans
```

> **Zawsze** podawaj `--env-file .env.local-demo` i **oba** pliki `-f`. Pominięcie tych flag
> użyje domyślnych/puste haseł (`guest:guest`) i może odtworzyć kontenery z błędną konfiguracją,
> niszcząc dane w wolumenach (patrz `CLAUDE.md`).

### Usługi z `docker-compose.yml` (baza infrastruktury)

| Usługa | Obraz | Port (host) | Rola |
|--------|-------|-------------|------|
| `postgres` | `postgres:16-alpine` | 5432 | Główna baza OLTP, multi-tenant |
| `redis` | `redis:7-alpine` | 6379 | Cache, sesje, presence, JWT blacklist |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | 5672 (AMQP), 15672 (UI) | Broker eventów (BE ↔ voicebot) |
| `minio` + `minio-init` | `minio/minio:latest` / `minio/mc:latest` | 9000 (S3 API), 9001 (Console) | Object storage – nagrania rozmów. `minio-init` tworzy bucket przy starcie |
| `clickhouse` + `clickhouse-init` | `clickhouse/clickhouse-server:24.3` | 8123 (HTTP/JDBC), 9002→9000 (native TCP) | Data Warehouse (raporty) |
| `voicebot` | build z `voicebot/` (FastAPI) | 8001 | Serwis AI: ASR/NLU/podsumowania |

### Nadpisania z `docker-compose.local-demo.yml`

| Usługa | Zmiana |
|--------|--------|
| `backend` | build z `./backend` (`Dockerfile.backend`), profil Spring `prod`, healthcheck na `/actuator/health`, porty wewnętrzne (`expose: 8080`) |
| `frontend` | build z `./frontend` (`Dockerfile.frontend`), serwowany przez wewnętrzny Nginx (`expose: 80`) |
| `nginx` | dodatkowy reverse-proxy `cc-nginx`, **jedyny port wystawiony na hosta: 80** (SSL terminowany przez ngrok – stąd brak 443 lokalnie); konfiguracja: `nginx/nginx-local-demo.conf` |
| `postgres`, inne | porty hosta zresetowane (`!reset []`) – usługi dostępne tylko w sieci `cc-network`, nie na hoście |

Sieć: wszystkie usługi w `cc-network` (Docker bridge network).

## 8.2 Zmienne środowiskowe (`.env.local-demo`)

Plik `.env.local-demo` definiuje konfigurację dla local-demo. Najważniejsze grupy zmiennych
(nazwy – wartości w pliku lokalnym, nie commitować realnych sekretów produkcyjnych):

| Grupa | Zmienne |
|-------|---------|
| Baza danych | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` |
| Redis | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DATABASE`, `REDIS_SSL_ENABLED`, `REDIS_POOL_*` |
| RabbitMQ | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `RABBITMQ_VHOST`, `RABBITMQ_SSL_ENABLED` |
| S3/MinIO | `S3_ENDPOINT`, `S3_PUBLIC_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PATH_STYLE_ACCESS` |
| ClickHouse | `CLICKHOUSE_URL`, `CLICKHOUSE_USER`, `CLICKHOUSE_PASSWORD`, `ETL_DW_TYPE` |
| JWT / bezpieczeństwo | `JWT_SECRET`, `JWT_EXPIRATION_MS`, `JWT_REFRESH_EXPIRATION_MS`, `APP_ENCRYPTION_SECRET`, `EMAIL_ENCRYPTION_KEY`, `SOCIAL_TOKEN_ENCRYPTION_KEY` |
| Twilio | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_STATUS_CALLBACK_URL`, `TWILIO_SIGNATURE_VALIDATION_ENABLED`, `TWILIO_RECORDING_ENABLED`, `TWILIO_PER_TENANT_CALLBACK_URL_ENABLED` |
| Voicebot | `VOICEBOT_ENABLED`, `VOICEBOT_URL` |
| Dialer | `DIALER_AGENT_POLL_INTERVAL_MS` |
| Inne | `SERVER_PORT`, `SPRING_PROFILES_ACTIVE`, `CORS_ALLOWED_ORIGINS`, `WEBSOCKET_ALLOWED_ORIGINS`, `LOG_PATH`, `LOG_FILE`, `PROMETHEUS_ENABLED`, `APP_BASE_URL` |

## 8.3 Lokalne porty (po `docker compose up -d` – usługi bazowe)

| Usługa | Adres lokalny |
|--------|----------------|
| PostgreSQL | `localhost:5432` (db=`contact_center_dev`, user=`postgres`, pass=`postgres`) |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672`, UI: `localhost:15672` (guest/guest) |
| MinIO | `localhost:9000` (S3 API), `localhost:9001` (Console) |
| ClickHouse | `localhost:8123` (HTTP), `localhost:9002` (native TCP) |
| Voicebot | `localhost:8001` |
| Backend (dev, poza Docker) | `localhost:8080`, Swagger: `/swagger-ui.html` |
| Frontend (dev, `npm start`) | `localhost:4200` z proxy → `localhost:8080` |

W trybie **local-demo** (pełny stack w Dockerze + Nginx + ngrok) wystawiony jest tylko port **80**
przez kontener `cc-nginx` – pozostałe usługi komunikują się wewnętrznie przez `cc-network`.

## 8.4 Reverse proxy (Nginx)

`nginx/nginx-local-demo.conf` – konfiguracja Nginx dla local-demo:
- routing `/` → `cc-frontend` (statyczny Angular build),
- routing `/api`, `/ws` (i webhooki Twilio) → `cc-backend:8080`,
- SSL terminowany na zewnątrz (ngrok), Nginx słucha tylko HTTP (port 80).

## 8.5 Persystencja (wolumeny)

| Wolumen | Zawartość |
|---------|-----------|
| `postgres_data` | dane PostgreSQL |
| `redis_data` | dane Redis (AOF/RDB) |
| RabbitMQ, MinIO, ClickHouse | własne wolumeny danych (definiowane w `docker-compose.yml`) |
| `backend_logs`, `nginx_logs` | logi aplikacyjne (local-demo) |

`docker compose down` zachowuje wolumeny; `docker compose down -v` usuwa dane – używać tylko
świadomie.

## 8.6 Build obrazów

- Backend: `backend/Dockerfile.backend` – wieloetapowy build Maven → JRE 21.
- Frontend: `frontend/Dockerfile.frontend` – `npm run build` → statyczne pliki serwowane przez
  Nginx w kontenerze.
- Voicebot: `voicebot/Dockerfile` (jeśli istnieje) – Python + zależności z `requirements.txt`,
  model Whisper ładowany przy starcie (`lifespan`).

## 8.7 Healthchecki i kolejność startu

`docker-compose.local-demo.yml` definiuje `depends_on` z `condition: service_healthy` dla
`postgres`, `redis`, `rabbitmq` – backend startuje dopiero gdy te usługi są zdrowe. Backend ma
własny healthcheck (`/actuator/health`) z `start_period: 120s` (czas na migracje Flyway i
rozgrzanie).

## 8.8 Więcej informacji

- Pełny opis środowisk produkcyjnych/K8s (jeśli aktualny) – [`DEPLOYMENT.md`](../../DEPLOYMENT.md).
- Komendy referencyjne Dockera – [`DOCKER-COMMANDS.md`](../../DOCKER-COMMANDS.md).
- Jak uruchomić projekt od zera lokalnie – [`09-getting-started.md`](09-getting-started.md).
