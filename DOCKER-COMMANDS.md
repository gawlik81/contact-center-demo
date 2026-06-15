# Zarządzanie systemem Contact Center — komendy Docker

Skróty używane w dokumencie:

```bash
# Środowisko DEV (infrastruktura lokalna)
alias dc="docker compose"

# Demo lokalne z ngrok
alias cc-demo="docker compose --env-file .env.local-demo \
  -f docker-compose.yml \
  -f docker-compose.local-demo.yml"

# Produkcja / VPS
alias cc-prod="docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml"
```

Dodaj aliasy do `~/.bashrc` i załaduj: `source ~/.bashrc`.

---

## 1. Środowisko DEV (lokalne — tylko infrastruktura)

Używa `docker-compose.yml`. Backend i frontend uruchamiasz lokalnie (`mvn`, `npm start`).

| Akcja | Komenda |
|-------|---------|
| Uruchom wszystko | `docker compose up -d` |
| Zatrzymaj (dane zostają) | `docker compose down` |
| Zatrzymaj + usuń dane (reset DB) | `docker compose down -v` |
| Status kontenerów | `docker compose ps` |
| Uruchom tylko wybrany serwis | `docker compose up -d postgres` |
| Uruchom z profilem AI (voicebot) | `docker compose --profile ai up -d` |
| Przebuduj obraz | `docker compose build --no-cache <serwis>` |

Dostępne porty po `docker compose up -d`:

| Serwis | Port | Dane dostępowe |
|--------|------|----------------|
| PostgreSQL | `localhost:5432` | db: `contact_center_dev`, user: `postgres`, pass: `postgres` |
| Redis | `localhost:6379` | bez hasła |
| RabbitMQ AMQP | `localhost:5672` | guest/guest |
| RabbitMQ UI | `localhost:15672` | guest/guest |
| MinIO S3 API | `localhost:9000` | minioadmin/minioadmin |
| MinIO Console | `localhost:9001` | minioadmin/minioadmin |
| ClickHouse HTTP | `localhost:8123` | user: `default`, brak hasła |

---

## 2. Demo lokalne z ngrok

Pełny stack w Dockerze: backend + frontend + nginx + infrastruktura. Publiczny dostęp przez tunel ngrok.

### Pierwsze uruchomienie (krok po kroku)

```bash
# 1. Infrastruktura
cc-demo up -d postgres redis rabbitmq minio clickhouse

# 2. Inicjalizacja (MinIO bucket + ClickHouse schemat)
cc-demo up minio-init clickhouse-init

# 3. Backend (Flyway wykona migracje automatycznie)
cc-demo up -d backend
docker logs cc-backend -f --tail=50   # czekaj na "Started ContactCenterApplication"

# 4. Frontend i Nginx
cc-demo up -d frontend nginx

# 5. Status
cc-demo ps
```

### Codzienne użytkowanie

```bash
# Uruchom wszystko naraz
cc-demo up -d

# Zatrzymaj (dane w volumes zostają)
cc-demo down

# Zatrzymaj + usuń dane (reset DB)
cc-demo down -v

# Rebuild i restart kontenera po zmianie kodu
cc-demo build backend && cc-demo up -d --no-deps backend

# Restart jednego serwisu
cc-demo restart backend

# Status wszystkich kontenerów
cc-demo ps

# Logi w czasie rzeczywistym
cc-demo logs -f backend
cc-demo logs -f --tail=100 nginx
```

### Aktualizacja URL ngrok po restarcie tunelu

```bash
NGROK_URL=$(curl -s http://localhost:4040/api/tunnels \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['tunnels'][0]['public_url'])")
echo "Nowy URL: $NGROK_URL"

# Zaktualizuj .env.local-demo
sed -i "s|APP_BASE_URL=.*|APP_BASE_URL=$NGROK_URL|" .env.local-demo
sed -i "s|S3_PUBLIC_ENDPOINT=.*|S3_PUBLIC_ENDPOINT=$NGROK_URL|" .env.local-demo
sed -i "s|CORS_ALLOWED_ORIGINS=.*|CORS_ALLOWED_ORIGINS=$NGROK_URL,http://localhost:4200|" .env.local-demo
sed -i "s|WEBSOCKET_ALLOWED_ORIGINS=.*|WEBSOCKET_ALLOWED_ORIGINS=$NGROK_URL,http://localhost:4200|" .env.local-demo

# Zrestartuj backend z nowym URL
cc-demo up -d --no-deps backend
```

### ngrok

```bash
# Uruchom tunel (losowy URL)
ngrok http 80

# Uruchom ze statyczną domeną
ngrok http --domain=twoja-nazwa.ngrok-free.app 80

# Sprawdź aktualny URL (jeśli ngrok działa w tle / systemd)
curl -s http://localhost:4040/api/tunnels \
  | python3 -c "import sys,json; t=json.load(sys.stdin)['tunnels']; print(t[0]['public_url'] if t else 'brak tunelu')"

# Status usługi systemd
sudo systemctl status ngrok-cc
sudo systemctl restart ngrok-cc
sudo journalctl -u ngrok-cc -f
```

---

## 3. Produkcja (VPS Hostinger)

```bash
# Budowanie obrazów
cc-prod build --no-cache

# Pierwsze uruchomienie
cc-prod up -d postgres redis rabbitmq minio clickhouse
cc-prod up minio-init clickhouse-init
cc-prod up -d backend
cc-prod up -d frontend nginx

# Re-deploy po aktualizacji kodu
git pull origin main
cc-prod build
cc-prod up -d --no-deps backend frontend nginx

# Status
cc-prod ps

# Wyczyść stare obrazy
docker image prune -f
```

---

## 4. Logi

```bash
# Backend (ostatnie 100 linii + śledzenie)
docker logs cc-backend -f --tail=100

# Tylko błędy backendu
docker logs cc-backend 2>&1 | grep -E "ERROR|WARN|Exception"

# Logi Flyway (migracje)
docker logs cc-backend 2>&1 | grep -i "flyway\|migration"

# Nginx — access log
docker exec cc-nginx tail -f /var/log/nginx/access.log

# Nginx — error log
docker exec cc-nginx tail -f /var/log/nginx/error.log

# Wszystkie kontenery naraz (dev)
docker compose logs -f

# Wszystkie kontenery naraz (demo)
cc-demo logs -f
```

---

## 5. Baza danych (PostgreSQL)

```bash
# Wejście do psql (DEV)
docker exec -it cc-postgres psql -U postgres -d contact_center_dev

# Wejście do psql (demo/prod — użytkownik aplikacyjny)
docker exec -it cc-postgres psql -U ccapp -d contact_center

# Historia migracji Flyway
docker exec cc-postgres psql -U ccapp -d contact_center \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"

# Backup bazy (prod)
docker exec cc-postgres pg_dump -U ccapp -Fc contact_center \
  > backup_$(date +%Y%m%d_%H%M).dump

# Restore z backupu
docker exec -i cc-postgres pg_restore -U ccapp -d contact_center \
  --clean --if-exists < backup_YYYYMMDD_HHMM.dump
```

### Seed danych demo

```sql
-- Uruchom po: docker exec -it cc-postgres psql -U ccapp -d contact_center

INSERT INTO tenant (name, status) VALUES ('Demo Company', 'ACTIVE');

INSERT INTO app_user (tenant_id, email, password_hash, role, status, first_name, last_name)
VALUES (
  (SELECT tenant_id FROM tenant WHERE LOWER(name) = 'demo company'),
  'admin@demo.com',
  '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
  'ADMIN', 'ACTIVE', 'Admin', 'Demo'
);
-- Hasło: Test@12345
```

---

## 6. Redis

```bash
# Wejście do redis-cli (DEV — bez hasła)
docker exec -it cc-redis redis-cli

# Wejście do redis-cli (demo/prod — z hasłem)
docker exec -it cc-redis redis-cli -a "$REDIS_PASSWORD"

# Ping
docker exec cc-redis redis-cli ping

# Podgląd kluczy (ostrożnie na produkcji)
docker exec cc-redis redis-cli KEYS "*"

# Wyczyść cache (flush wszystkich kluczy)
docker exec cc-redis redis-cli FLUSHALL
```

---

## 7. RabbitMQ

```bash
# Podgląd kolejek (DEV)
docker exec cc-rabbitmq rabbitmqctl list_queues name messages consumers

# Status klastra
docker exec cc-rabbitmq rabbitmqctl cluster_status

# Lista połączeń
docker exec cc-rabbitmq rabbitmqctl list_connections

# Zarządzanie przez UI: http://localhost:15672 (DEV, guest/guest)
```

---

## 8. MinIO

```bash
# Wejście do mc (MinIO client)
docker exec -it cc-minio-init mc alias set local http://minio:9000 minioadmin minioadmin

# Lista bucketów
docker exec cc-minio mc ls local/

# Lista plików w buckecie
docker exec cc-minio-init mc ls local/contact-center-recordings/

# Zarządzanie przez UI: http://localhost:9001 (DEV, minioadmin/minioadmin)
```

---

## 9. Metryki i zdrowie systemu

```bash
# Health check backendu
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# Użycie zasobów przez kontenery
docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

# Zajętość dysku przez Docker
docker system df

# Sprawdź OOM (kontenery ubite przez brak RAM)
docker inspect cc-backend | python3 -c \
  "import sys,json; d=json.load(sys.stdin)[0]; print('OOMKilled:', d['State']['OOMKilled'])"

# Wyczyść nieużywane zasoby (obrazy, kontenery, sieci)
docker system prune -f

# Wyczyść wszystko łącznie z volumes (UWAGA: usuwa dane!)
docker system prune -a --volumes -f
```

---

## 10. Generowanie sekretów

```bash
# Hasło (32 znaki)
openssl rand -base64 32

# JWT Secret (min. 64 znaki)
openssl rand -base64 64

# Weryfikacja czy .env ma wypełnione kluczowe zmienne
grep -E "^(DB_PASSWORD|REDIS_PASSWORD|RABBITMQ_PASSWORD|JWT_SECRET)=" .env.local-demo
```
