# Procedura wdrożenia produkcyjnego – Contact Center SaaS

**Wersja:** 2.0  
**Data:** 2026-04-21  
**Stack:** Spring Boot 3.3 · Angular 18 · PostgreSQL 16 · Redis 7 · RabbitMQ 3.13 · MinIO · ClickHouse · Twilio

---

## Spis treści

### Część A — Wdrożenie demo na Hostinger VPS (zalecane na start)
1. [Co kupić i dlaczego](#1-co-kupić-i-dlaczego)
2. [Architektura single-server](#2-architektura-single-server)
3. [Krok 1 — Zakup VPS i domeny w Hostinger](#3-krok-1--zakup-vps-i-domeny-w-hostinger)
4. [Krok 2 — Konfiguracja DNS](#4-krok-2--konfiguracja-dns)
5. [Krok 3 — Przygotowanie serwera (SSH, Docker, firewall)](#5-krok-3--przygotowanie-serwera-ssh-docker-firewall)
6. [Krok 4 — Klonowanie repozytorium i struktura plików](#6-krok-4--klonowanie-repozytorium-i-struktura-plików)
7. [Krok 5 — Pliki do stworzenia (Dockerfiles, Nginx, Compose)](#7-krok-5--pliki-do-stworzenia-dockerfiles-nginx-compose)
8. [Krok 6 — Zmienne środowiskowe (.env.prod)](#8-krok-6--zmienne-środowiskowe-envprod)
9. [Krok 7 — Certyfikat SSL (Let's Encrypt / Certbot)](#9-krok-7--certyfikat-ssl-lets-encrypt--certbot)
10. [Krok 8 — Budowanie obrazów Docker](#10-krok-8--budowanie-obrazów-docker)
11. [Krok 9 — Uruchomienie systemu](#11-krok-9--uruchomienie-systemu)
12. [Krok 10 — Weryfikacja i smoke tests](#12-krok-10--weryfikacja-i-smoke-tests)
13. [Krok 11 — Inicjalizacja danych (seed)](#13-krok-11--inicjalizacja-danych-seed)
14. [Procedura aktualizacji (re-deploy)](#14-procedura-aktualizacji-re-deploy)
15. [Monitorowanie i logi](#15-monitorowanie-i-logi)
16. [Rozwiązywanie problemów (Troubleshooting)](#16-rozwiązywanie-problemów-troubleshooting)

### Część B — Wdrożenie enterprise (HA, skala produkcyjna)
17. [Wymagania infrastrukturalne HA](#17-wymagania-infrastrukturalne-ha)
18. [Baza danych PostgreSQL (Primary + Replica)](#18-baza-danych-postgresql-primary--replica)
19. [Redis Sentinel](#19-redis-sentinel)
20. [RabbitMQ Cluster](#20-rabbitmq-cluster)
21. [MinIO Distributed](#21-minio-distributed)
22. [Twilio — konfiguracja webhooków](#22-twilio--konfiguracja-webhooków)
23. [Procedura rollback](#23-procedura-rollback)
24. [Checklista bezpieczeństwa produkcyjnego](#24-checklista-bezpieczeństwa-produkcyjnego)

---

# CZĘŚĆ A — Wdrożenie demo na Hostinger VPS

---

## 1. Co kupić i dlaczego

### 1.1 Plan VPS

| Plan | vCPU | RAM | NVMe | Cena/mies. | Ocena |
|------|------|-----|------|-----------|-------|
| KVM 1 | 1 | 4 GB | 50 GB | ~$5 | **Za mały** — ClickHouse sam wymaga ~2 GB |
| **KVM 2** | **2** | **8 GB** | **100 GB** | **~$8** | **Minimum — wystarczy do demo** |
| KVM 4 | 4 | 16 GB | 200 GB | ~$16 | Komfortowy margines, płynne działanie |

**Rekomendacja:** KVM 2 na start; jeśli system będzie prezentowany intensywnie, KVM 4.

> Hostinger → hPanel → VPS → zamów → wybierz **Ubuntu 22.04 LTS** jako system operacyjny.

### 1.2 Domena

Domena zapewnia stały, zapamiętywalny adres (np. `cc-demo.twojafirma.pl`).

- Kup domenę w Hostinger (zakładka **Domeny**) lub użyj własnej z innego rejestratora.
- Cena: ~$10–15/rok za `.pl` lub `.com`.
- Alternatywnie: Hostinger oferuje bezpłatną domenę przy rocznym planie VPS.

---

## 2. Architektura single-server

Wszystkie serwisy działają na jednym hoście Docker. Tylko porty **80** i **443** są dostępne z internetu.

```
Internet
    │
    │  HTTPS :443 / HTTP :80 (redirect)
    ▼
┌─────────────────────────────────────────────────────┐
│  Nginx (reverse proxy + SSL termination)            │
│                                                     │
│  /          → Angular (pliki statyczne z /dist)     │
│  /api/*     → Spring Boot :8080                     │
│  /ws/*      → Spring Boot WebSocket :8080           │
│  /actuator  → blokowane z zewnątrz                  │
└─────────────────────────────────────────────────────┘
    │ (sieć wewnętrzna Docker: cc-network)
    ▼
┌──────────────────────────────────────────────────────────────────┐
│  Spring Boot :8080  ←→  PostgreSQL :5432                        │
│                     ←→  Redis :6379                             │
│                     ←→  RabbitMQ :5672                          │
│                     ←→  MinIO :9000                             │
│                     ←→  ClickHouse :8123                        │
└──────────────────────────────────────────────────────────────────┘
```

**Serwisy NIE mają publicznych portów** — komunikują się wyłącznie przez sieć `cc-network`.  
Frontend serwowany jest jako statyczne pliki przez Nginx (zbudowane `npm run build`).

---

## 3. Krok 1 — Zakup VPS i domeny w Hostinger

1. Zaloguj się na [hostinger.pl](https://hostinger.pl) lub [hostinger.com](https://hostinger.com).
2. **Zamów VPS** → wybierz plan KVM 2 → system operacyjny: **Ubuntu 22.04 LTS**.
3. Ustaw hasło root lub wgraj klucz SSH publiczny (zalecane).
4. **Zamów domenę** (np. `contact-center.twojafirma.pl`) lub dodaj własną.
5. Zapisz **IP serwera VPS** (widoczne w hPanel → VPS → szczegóły).

> Po zamówieniu VPS jest gotowy w ciągu ~5 minut. Zapis DNS potrzebuje do 24h na propagację.

---

## 4. Krok 2 — Konfiguracja DNS

W hPanel Hostinger → **DNS / Strefy DNS** dla wybranej domeny dodaj rekord:

| Typ | Nazwa (Host) | Wartość | TTL |
|-----|-------------|---------|-----|
| A | `@` lub `cc-demo` | `<IP_VPS>` | 300 |

Jeśli chcesz subdomeny (np. `app.twojafirma.pl`):

| Typ | Nazwa | Wartość | TTL |
|-----|-------|---------|-----|
| A | `app` | `<IP_VPS>` | 300 |

Sprawdź propagację DNS:
```bash
# Z dowolnego terminala (lokalnie lub online: dnschecker.org)
nslookup twojadomena.pl
# lub
dig +short twojadomena.pl A
```

Poczekaj aż zwróci IP Twojego VPS zanim przejdziesz do SSL.

---

## 5. Krok 3 — Przygotowanie serwera (SSH, Docker, firewall)

### 5.1 Połączenie SSH

```bash
ssh root@<IP_VPS>
# lub z kluczem:
ssh -i ~/.ssh/id_rsa root@<IP_VPS>
```

### 5.2 Aktualizacja systemu

```bash
apt update && apt upgrade -y
apt install -y curl git unzip htop nano ufw
```

### 5.3 Instalacja Docker i Docker Compose

```bash
# Instalacja Docker Engine
curl -fsSL https://get.docker.com | bash

# Weryfikacja
docker --version       # Docker version 26.x.x
docker compose version # Docker Compose version v2.x.x

# Uruchom Docker przy starcie systemu
systemctl enable docker
systemctl start docker
```

### 5.4 Konfiguracja firewall (UFW)

```bash
# Domyślna polityka
ufw default deny incoming
ufw default allow outgoing

# Zezwól na SSH (KRYTYCZNE – nie zamknij sobie dostępu!)
ufw allow 22/tcp

# Zezwól na HTTP i HTTPS (Nginx)
ufw allow 80/tcp
ufw allow 443/tcp

# Włącz firewall
ufw enable

# Sprawdź status
ufw status verbose
```

> **WAŻNE:** Porty baz danych (5432, 6379, 5672, 9000, 8123) nie są otwierane — są dostępne tylko wewnątrz Dockera.

### 5.5 Tworzenie użytkownika deploy (opcjonalne, zalecane)

```bash
useradd -m -s /bin/bash deploy
usermod -aG docker deploy
# Opcjonalnie: dodaj klucz SSH do ~/.ssh/authorized_keys tego użytkownika
```

---

## 6. Krok 4 — Klonowanie repozytorium i struktura plików

```bash
# Na serwerze VPS
cd /opt
git clone <URL_REPOZYTORIUM> contact-center
cd contact-center

# Struktura po klonowaniu
ls -la
# backend/     frontend/     docker-compose.yml     dw/     voicebot/
```

---

## 7. Krok 5 — Pliki do stworzenia (Dockerfiles, Nginx, Compose)

Poniższe pliki **nie istnieją w repozytorium** i musisz je stworzyć przed deployem.

---

### 7.1 `Dockerfile.backend` — multi-stage build (w katalogu `backend/`)

```dockerfile
# Etap 1: Budowanie artefaktu Maven
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder

WORKDIR /build
COPY pom.xml .
COPY app/pom.xml app/

# Pobierz zależności (cached layer)
RUN mvn dependency:go-offline -pl app -q

COPY app/src app/src

# Buduj JAR bez testów
RUN mvn clean package -pl app -DskipTests -q

# Etap 2: Minimalny obraz runtime
FROM eclipse-temurin:21-jre-alpine AS runtime

# Niepriwilegowany użytkownik
RUN addgroup -S ccapp && adduser -S ccapp -G ccapp

WORKDIR /app

COPY --from=builder /build/app/target/app-*.jar app.jar

# Katalog na logi
RUN mkdir -p /var/log/contact-center && chown ccapp:ccapp /var/log/contact-center

USER ccapp

ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD wget -q -O- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
```

---

### 7.2 `Dockerfile.frontend` — multi-stage build (w katalogu `frontend/`)

```dockerfile
# Etap 1: Budowanie aplikacji Angular
FROM node:20-alpine AS builder

WORKDIR /app

# Instalacja zależności (cached layer)
COPY package.json package-lock.json ./
RUN npm ci --prefer-offline

COPY . .

# Build produkcyjny
RUN npm run build -- --configuration production

# Etap 2: Nginx serwujący statyczne pliki
FROM nginx:1.26-alpine AS runtime

# Usuń domyślną konfigurację Nginx
RUN rm /etc/nginx/conf.d/default.conf

# Kopiuj zbudowaną aplikację
COPY --from=builder /app/dist/contact-center/browser /usr/share/nginx/html

# Kopiuj konfigurację Nginx dla SPA (Angular Router)
COPY nginx-spa.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s \
  CMD wget -q -O- http://localhost:80/ | grep -q '<app-root' || exit 1
```

---

### 7.3 `frontend/nginx-spa.conf` — konfiguracja Nginx dla Angular SPA

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    # Kompresja gzip
    gzip on;
    gzip_types text/plain text/css application/json application/javascript
               text/xml application/xml text/javascript image/svg+xml;
    gzip_min_length 1024;

    # Cache długoterminowy dla plików z hashem w nazwie (Angular buduje je z fingerprint)
    location ~* \.(js|css|woff2?|png|svg|ico|webp)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # Fallback do index.html — wymagane dla Angular Router (HTML5 history API)
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

---

### 7.4 `nginx/nginx.conf` — główny reverse proxy z SSL

Utwórz katalog `nginx/` w głównym katalogu projektu:

```bash
mkdir -p nginx
```

Plik `nginx/nginx.conf`:

```nginx
# ============================================================
# Nginx — reverse proxy dla Contact Center SaaS
# Obsługuje: frontend (SPA), backend (REST + WebSocket), SSL
# ============================================================

worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid       /var/run/nginx.pid;

events {
    worker_connections 1024;
    use epoll;
}

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    # Logowanie
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent"';
    access_log /var/log/nginx/access.log main;

    sendfile        on;
    tcp_nopush      on;
    tcp_nodelay     on;
    keepalive_timeout 65;
    client_max_body_size 50m;

    # Rate limiting: 100 żądań/s per IP
    limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/s;
    limit_req_zone $binary_remote_addr zone=auth_limit:10m rate=5r/s;

    # ── Upstream: Spring Boot backend ──────────────────────────
    upstream backend {
        server backend:8080;
        keepalive 32;
    }

    # ── HTTP → HTTPS redirect ──────────────────────────────────
    server {
        listen 80;
        server_name TWOJA_DOMENA;

        # Let's Encrypt ACME challenge (potrzebne przy wydawaniu certyfikatu)
        location /.well-known/acme-challenge/ {
            root /var/www/certbot;
        }

        location / {
            return 301 https://$host$request_uri;
        }
    }

    # ── Główny serwer HTTPS ────────────────────────────────────
    server {
        listen 443 ssl http2;
        server_name TWOJA_DOMENA;

        # SSL — certyfikaty Let's Encrypt (certbot uzupełni te ścieżki)
        ssl_certificate     /etc/letsencrypt/live/TWOJA_DOMENA/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/TWOJA_DOMENA/privkey.pem;
        ssl_protocols       TLSv1.2 TLSv1.3;
        ssl_ciphers         ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
        ssl_prefer_server_ciphers off;
        ssl_session_cache   shared:SSL:10m;
        ssl_session_timeout 1d;

        # HSTS (opcjonalne — wymuś HTTPS na zawsze)
        add_header Strict-Transport-Security "max-age=31536000" always;

        # ── REST API ─────────────────────────────────────────────
        location /api/ {
            limit_req zone=api_limit burst=200 nodelay;

            proxy_pass         http://backend;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_connect_timeout 10s;
            proxy_read_timeout    60s;
            proxy_send_timeout    60s;
        }

        # Rate limiting zaostrzone dla auth (ochrona przed brute-force)
        location /api/auth/ {
            limit_req zone=auth_limit burst=10 nodelay;

            proxy_pass         http://backend;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
        }

        # ── WebSocket (STOMP over SockJS) ────────────────────────
        location /ws {
            proxy_pass         http://backend;
            proxy_http_version 1.1;
            proxy_set_header   Upgrade    $http_upgrade;
            proxy_set_header   Connection "upgrade";
            proxy_set_header   Host       $host;
            proxy_set_header   X-Real-IP  $remote_addr;
            proxy_read_timeout  3600s;
            proxy_send_timeout  3600s;
        }

        # ── Actuator — dostępny tylko lokalnie ──────────────────
        location /actuator {
            allow 127.0.0.1;
            allow 172.16.0.0/12;   # sieć Docker
            deny all;
            proxy_pass http://backend;
        }

        # ── Webhooks Twilio ──────────────────────────────────────
        location /api/telephony/webhook/ {
            limit_req zone=api_limit burst=50 nodelay;
            proxy_pass         http://backend;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-Proto $scheme;
        }

        # ── Frontend Angular SPA ─────────────────────────────────
        location / {
            proxy_pass         http://frontend:80;
            proxy_http_version 1.1;
            proxy_set_header   Host $host;
        }
    }
}
```

> **WAŻNE:** Zastąp wszystkie wystąpienia `TWOJA_DOMENA` właściwą nazwą domeny (np. `cc-demo.twojafirma.pl`).

---

### 7.5 `docker-compose.prod.yml` — kompletna konfiguracja produkcyjna

```yaml
# ============================================================
# docker-compose.prod.yml — Produkcja / Demo na Hostinger VPS
#
# Uruchomienie:
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
#
# Budowanie:
#   docker compose -f docker-compose.yml -f docker-compose.prod.yml build
# ============================================================

services:

  # ── Backend: Spring Boot ──────────────────────────────────
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile.backend
    container_name: cc-backend
    env_file: .env.prod
    environment:
      SPRING_PROFILES_ACTIVE: prod
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    restart: unless-stopped
    networks:
      - cc-network
    # Port NIE jest eksponowany publicznie — Nginx komunikuje się wewnętrznie
    expose:
      - "8080"
    volumes:
      - backend_logs:/var/log/contact-center
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 30s
      timeout: 10s
      start_period: 120s
      retries: 5
    logging:
      driver: "json-file"
      options:
        max-size: "100m"
        max-file: "5"

  # ── Frontend: Angular SPA (budowany i serwowany przez Nginx) ──
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile.frontend
    container_name: cc-frontend
    restart: unless-stopped
    networks:
      - cc-network
    expose:
      - "80"
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O- http://localhost:80/ > /dev/null || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
    logging:
      driver: "json-file"
      options:
        max-size: "50m"
        max-file: "3"

  # ── Nginx: reverse proxy + SSL ────────────────────────────
  nginx:
    image: nginx:1.26-alpine
    container_name: cc-nginx
    depends_on:
      backend:
        condition: service_healthy
      frontend:
        condition: service_healthy
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro
      - /var/www/certbot:/var/www/certbot:ro
      - nginx_logs:/var/log/nginx
    restart: unless-stopped
    networks:
      - cc-network
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O- http://localhost:80/api/auth/health > /dev/null || exit 0"]
      interval: 30s
      timeout: 5s
      retries: 3
    logging:
      driver: "json-file"
      options:
        max-size: "100m"
        max-file: "5"

  # ── Nadpisania serwisów infrastruktury (bez publicznych portów) ──

  postgres:
    # Produkcja: nie eksponuj portu publicznie
    ports: !reset []
    expose:
      - "5432"
    environment:
      POSTGRES_DB: contact_center
      POSTGRES_USER: ${DB_USERNAME}
      POSTGRES_PASSWORD: ${DB_PASSWORD}

  redis:
    ports: !reset []
    expose:
      - "6379"
    command: >
      redis-server
      --requirepass ${REDIS_PASSWORD}
      --appendonly yes
      --maxmemory 512mb
      --maxmemory-policy allkeys-lru

  rabbitmq:
    ports: !reset []
    expose:
      - "5672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
      RABBITMQ_DEFAULT_VHOST: /

  minio:
    ports: !reset []
    expose:
      - "9000"
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY}

  clickhouse:
    ports: !reset []
    expose:
      - "8123"
      - "9000"

# ── Dodatkowe volumes ─────────────────────────────────────
volumes:
  backend_logs:
  nginx_logs:
```

---

## 8. Krok 6 — Zmienne środowiskowe (.env.prod)

Utwórz plik `.env.prod` na serwerze VPS (NIE wgrywaj go do repozytorium!):

```bash
# Na serwerze VPS:
nano /opt/contact-center/.env.prod
```

Wypełnij poniższy template:

```bash
# ── Baza danych ──────────────────────────────────────────────
DB_URL=jdbc:postgresql://postgres:5432/contact_center
DB_USERNAME=ccapp
DB_PASSWORD=<min-20-znakow-losowe-haslo>
DB_POOL_MAX_SIZE=20
DB_POOL_MIN_IDLE=5

# ── Redis ────────────────────────────────────────────────────
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<min-20-znakow-losowe-haslo>
REDIS_DATABASE=0
REDIS_SSL_ENABLED=false
REDIS_POOL_MAX_ACTIVE=16
REDIS_POOL_MAX_IDLE=16
REDIS_POOL_MIN_IDLE=4

# ── RabbitMQ ─────────────────────────────────────────────────
RABBITMQ_HOST=rabbitmq
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=ccapp
RABBITMQ_PASSWORD=<min-20-znakow-losowe-haslo>
RABBITMQ_VHOST=/
RABBITMQ_SSL_ENABLED=false

# ── JWT ──────────────────────────────────────────────────────
# Wygeneruj: openssl rand -base64 64
JWT_SECRET=<min-64-znakow-losowy-ciag-base64>
JWT_EXPIRATION_MS=900000
JWT_REFRESH_EXPIRATION_MS=604800000

# ── MinIO (nagrania rozmów) ──────────────────────────────────
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=<min-16-znakow>
MINIO_SECRET_KEY=<min-32-znakow>
MINIO_BUCKET=contact-center-recordings

# ── ClickHouse (Data Warehouse) ──────────────────────────────
CLICKHOUSE_URL=jdbc:clickhouse://clickhouse:8123/contact_center_dw
CLICKHOUSE_USER=default
CLICKHOUSE_PASSWORD=

# ── Twilio (jeśli używasz VoIP) ──────────────────────────────
TWILIO_ACCOUNT_SID=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_AUTH_TOKEN=<auth-token-z-twilio-console>
TWILIO_PHONE_NUMBER=+48XXXXXXXXX
TWILIO_API_KEY_SID=SKxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_API_KEY_SECRET=<api-key-secret>
TWILIO_TWIML_APP_SID=APxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
TWILIO_RECORDING_ENABLED=true

# ── Aplikacja ────────────────────────────────────────────────
APP_BASE_URL=https://TWOJA_DOMENA
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
LOG_FILE=/var/log/contact-center/app.log
PROMETHEUS_ENABLED=false
```

### Generowanie silnych haseł

```bash
# Hasło do bazy danych
openssl rand -base64 32

# JWT Secret (min. 64 znaki)
openssl rand -base64 64

# Weryfikacja przed startem — sprawdź czy zmienne są wypełnione
grep -E "^(DB_PASSWORD|REDIS_PASSWORD|RABBITMQ_PASSWORD|JWT_SECRET|MINIO_ACCESS_KEY|MINIO_SECRET_KEY)=" .env.prod | \
  awk -F= '{if ($2=="<"||length($2)<10) print "UWAGA: "$1" jest puste lub za krótkie!"}'
```

```bash
# Zabezpiecz plik — dostępny tylko dla root
chmod 600 .env.prod
```

---

## 9. Krok 7 — Certyfikat SSL (Let's Encrypt / Certbot)

SSL jest wymagany do działania aplikacji (Twilio webhooks, bezpieczeństwo sesji).

### 9.1 Instalacja Certbot

```bash
apt install -y certbot
```

### 9.2 Wydanie certyfikatu (przed startem Nginx)

```bash
# Zatrzymaj ewentualnie działający Nginx (lub inny serwer na :80)
# Certbot użyje trybu standalone (samodzielnie na chwilę zajmie port 80)

certbot certonly \
  --standalone \
  --non-interactive \
  --agree-tos \
  --email TWOJ_EMAIL@domena.pl \
  -d TWOJA_DOMENA

# Certyfikat znajdzie się w:
ls /etc/letsencrypt/live/TWOJA_DOMENA/
# fullchain.pem  privkey.pem  cert.pem  chain.pem
```

### 9.3 Automatyczne odnawianie

Let's Encrypt certyfikaty są ważne 90 dni. Skonfiguruj auto-renew:

```bash
# Certbot instaluje timer systemd automatycznie — sprawdź:
systemctl status certbot.timer

# Lub dodaj cron (jeśli timer nie działa):
echo "0 3 * * * root certbot renew --quiet && docker exec cc-nginx nginx -s reload" \
  >> /etc/crontab
```

---

## 10. Krok 8 — Budowanie obrazów Docker

```bash
cd /opt/contact-center

# Buduj oba obrazy (backend + frontend) — może potrwać 5-10 minut
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  build --no-cache

# Sprawdź zbudowane obrazy
docker images | grep contact-center
```

> Podczas pierwszego buildu Maven pobierze zależności (~500 MB) i Node.js zbuduje Angular. Kolejne buildy będą szybsze dzięki cache warstw Docker.

---

## 11. Krok 9 — Uruchomienie systemu

### 11.1 Pierwsze uruchomienie (krok po kroku)

```bash
cd /opt/contact-center

# 1. Uruchom tylko infrastrukturę (baza danych, Redis, kolejka)
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  up -d postgres redis rabbitmq minio clickhouse

# Poczekaj na gotowość serwisów (~30s)
sleep 30
docker compose -f docker-compose.yml ps

# 2. Uruchom init kontenery (tworzą bucket MinIO i schemat ClickHouse)
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  up minio-init clickhouse-init
# Te kontenery zakończą pracę po inicjalizacji — to normalne

# 3. Uruchom backend (Flyway automatycznie wykona migracje)
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  up -d backend

# Obserwuj logi — szukaj "Started ContactCenterApplication" i braku błędów Flyway
docker logs cc-backend -f --tail=100

# 4. Uruchom frontend i Nginx
docker compose \
  -f docker-compose.yml \
  -f docker-compose.prod.yml \
  up -d frontend nginx
```

### 11.2 Sprawdzenie stanu wszystkich kontenerów

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
```

Oczekiwany wynik:

```
NAME                  STATUS              PORTS
cc-backend            running (healthy)
cc-clickhouse         running (healthy)
cc-frontend           running (healthy)
cc-minio              running (healthy)
cc-nginx              running (healthy)   0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp
cc-postgres           running (healthy)
cc-rabbitmq           running (healthy)
cc-redis              running (healthy)
```

### 11.3 Alias dla wygody (opcjonalne)

```bash
# Dodaj do ~/.bashrc na serwerze
echo 'alias cc-compose="docker compose -f /opt/contact-center/docker-compose.yml -f /opt/contact-center/docker-compose.prod.yml"' >> ~/.bashrc
source ~/.bashrc

# Teraz możesz używać:
cc-compose ps
cc-compose logs -f backend
cc-compose restart backend
```

---

## 12. Krok 10 — Weryfikacja i smoke tests

```bash
DOMAIN=https://TWOJA_DOMENA

# 1. Frontend dostępny
curl -sf -o /dev/null -w "%{http_code}" $DOMAIN/
# Oczekiwane: 200

# 2. Backend health check
curl -sf $DOMAIN/api/actuator/health | python3 -m json.tool
# Oczekiwane: {"status":"UP", "components":{...}}

# 3. Test logowania
curl -sf -X POST $DOMAIN/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"tenantSlug":"acme","username":"admin@acme.com","password":"Admin@12345"}' \
  | python3 -m json.tool
# Oczekiwane: {"accessToken":"eyJ...", "role":"ADMIN"}

# 4. Test WebSocket (SockJS info)
curl -sf $DOMAIN/ws/info | python3 -m json.tool
# Oczekiwane: {"entropy":...,"origins":["*:*"],...}

# 5. Sprawdź certyfikat SSL
curl -sv $DOMAIN 2>&1 | grep -E "SSL|certificate|expire"
```

---

## 13. Krok 11 — Inicjalizacja danych (seed)

Migracja `V999__dev_seed.sql` jest dostępna **tylko w profilu dev** — w produkcji baza jest pusta. Utwórz pierwszego administratora:

### 13.1 Konto platformy ADMIN (ręcznie przez SQL)

```bash
# Wejdź do kontenera PostgreSQL
docker exec -it cc-postgres psql -U ccapp -d contact_center

# Utwórz tenanta demo
INSERT INTO tenant (id, name, slug, status, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'Demo Company',
  'demo',
  'ACTIVE',
  NOW(),
  NOW()
);

-- Zapisz UUID tenanta z poprzedniego INSERT
-- Następnie utwórz użytkownika ADMIN (hasło: Admin@12345 — zmień po pierwszym logowaniu!)
INSERT INTO app_user (id, tenant_id, email, password_hash, role, status, first_name, last_name, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  (SELECT id FROM tenant WHERE slug = 'demo'),
  'admin@demo.com',
  '$2a$12$b7S/mPXPbip0cNDfN5oFB.UCLXFqGaAO97oXynzYjMFlBuA.zLjt6',
  'ADMIN',
  'ACTIVE',
  'Admin',
  'Demo',
  NOW(),
  NOW()
);

\q
```

> Hash BCrypt powyżej odpowiada hasłu `Test@12345`. Zmień hasło po pierwszym logowaniu przez UI.

### 13.2 Pierwsze logowanie

Wejdź na `https://TWOJA_DOMENA` i zaloguj się:
- Email: `admin@demo.com`
- Hasło: `Test@12345`
- Organizacja: `Demo Company` (pojawi się po wpisaniu emaila)

---

## 14. Procedura aktualizacji (re-deploy)

Gdy wgrasz zmiany do repozytorium, wykonaj na serwerze:

```bash
cd /opt/contact-center

# 1. Pobierz nowe zmiany
git pull origin main

# 2. Zbuduj nowe obrazy
docker compose -f docker-compose.yml -f docker-compose.prod.yml build

# 3. Zastąp kontenery (zero-downtime dla infrastruktury)
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps backend frontend nginx

# 4. Sprawdź zdrowie po aktualizacji
sleep 60
docker compose -f docker-compose.yml -f docker-compose.prod.yml ps
curl -sf https://TWOJA_DOMENA/api/actuator/health | python3 -m json.tool

# 5. Wyczyść stare obrazy Docker (oszczędność dysku)
docker image prune -f
```

---

## 15. Monitorowanie i logi

### 15.1 Podgląd logów w czasie rzeczywistym

```bash
# Logi backendu
docker logs cc-backend -f --tail=100

# Logi Nginx (requesty HTTP)
docker exec cc-nginx tail -f /var/log/nginx/access.log

# Logi Nginx (błędy)
docker exec cc-nginx tail -f /var/log/nginx/error.log

# Logi wszystkich serwisów naraz
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f
```

### 15.2 Metryki zasobów serwera

```bash
# Użycie zasobów przez kontenery Docker
docker stats --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

# Użycie dysku
df -h /
docker system df
```

### 15.3 Health checks endpointów

```bash
# Szczegółowy health check backendu (wszystkie komponenty)
curl -sf https://TWOJA_DOMENA/api/actuator/health | python3 -m json.tool

# Oczekiwane komponenty: db, redis, rabbit, diskSpace
```

---

## 16. Rozwiązywanie problemów (Troubleshooting)

### Problem: backend nie startuje (port 8080 niedostępny)

```bash
docker logs cc-backend 2>&1 | grep -E "ERROR|WARN|Flyway|migration"
```

Najczęstsze przyczyny:
- Błąd migracji Flyway → sprawdź logi pod kątem `FlywayException`
- Brak połączenia z PostgreSQL → sprawdź czy `cc-postgres` jest `healthy`
- Błąd zmiennych środowiskowych → sprawdź `.env.prod`

### Problem: HTTPS nie działa (błąd certyfikatu)

```bash
# Sprawdź czy certyfikat istnieje
ls /etc/letsencrypt/live/TWOJA_DOMENA/

# Sprawdź ważność
openssl x509 -noout -dates -in /etc/letsencrypt/live/TWOJA_DOMENA/fullchain.pem

# Przeładuj Nginx po odnowieniu certyfikatu
docker exec cc-nginx nginx -s reload
```

### Problem: Angular pokazuje białą stronę lub 404

```bash
# Sprawdź czy build Angular zakończył się sukcesem
docker logs cc-frontend

# Sprawdź konfigurację nginx-spa.conf — musi być `try_files $uri /index.html`
```

### Problem: WebSocket nie łączy się

```bash
# Sprawdź konfigurację Nginx — musi być Upgrade + Connection: upgrade
# Sprawdź czy proxy_read_timeout jest ≥ 3600s dla /ws lokacji
docker exec cc-nginx nginx -T | grep -A 10 "location /ws"
```

### Problem: za mało pamięci RAM (OOM Killer)

```bash
# Sprawdź czy jakiś kontener został ubity przez OOM
dmesg | grep -i "oom\|kill"
docker inspect cc-backend | grep -i "OOMKilled"

# ClickHouse jest najbardziej żarłoczny — ogranicz pamięć jeśli potrzeba
# W docker-compose.prod.yml dodaj:
# clickhouse:
#   mem_limit: 1.5g
```

---

# CZĘŚĆ B — Wdrożenie enterprise (HA, skala produkcyjna)

> Ta sekcja dotyczy wdrożeń wieloserwerowych dla prawdziwej produkcji z wieloma tenantami.

---

## 17. Wymagania infrastrukturalne HA

| Serwis | CPU | RAM | Dysk | Ilość |
|--------|-----|-----|------|-------|
| Backend (Spring Boot) | 2 vCPU | 4 GB | 20 GB | 2+ |
| PostgreSQL Primary+Replica | 4 vCPU | 8 GB | 100 GB SSD | 2 |
| Redis Sentinel | 2 vCPU | 4 GB | 20 GB | 3 |
| RabbitMQ Cluster | 2 vCPU | 4 GB | 50 GB | 3 |
| MinIO Distributed | 2 vCPU | 4 GB | 500 GB | 4 |
| Nginx (Load Balancer) | 2 vCPU | 2 GB | 10 GB | 2 |

---

## 18. Baza danych PostgreSQL (Primary + Replica)

### 18.1 Tworzenie użytkownika aplikacyjnego

```sql
CREATE DATABASE contact_center
  WITH ENCODING 'UTF8'
       LC_COLLATE 'en_US.UTF-8'
       LC_CTYPE 'en_US.UTF-8'
       TEMPLATE template0;

CREATE USER cc_app WITH PASSWORD '<silne-haslo>';
GRANT CONNECT ON DATABASE contact_center TO cc_app;
GRANT CREATE ON DATABASE contact_center TO cc_app;

\c contact_center
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

GRANT USAGE ON SCHEMA public TO cc_app;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO cc_app;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO cc_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO cc_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO cc_app;
```

### 18.2 Streaming Replica

```bash
pg_basebackup -h pg-primary -U replicator -D /var/lib/postgresql/16/main \
  --wal-method=stream --checkpoint=fast --progress
echo "primary_conninfo = 'host=pg-primary port=5432 user=replicator'" \
  >> /etc/postgresql/16/main/postgresql.auto.conf
touch /var/lib/postgresql/16/main/standby.signal
```

### 18.3 Backup

```bash
# Cron: codziennie o 2:00 UTC
pg_dump -h pg-primary -U cc_app -Fc contact_center \
  -f /backups/contact_center_$(date +%Y%m%d_%H%M).dump

# Retencja 30 dni
find /backups -name "*.dump" -mtime +30 -delete
```

---

## 19. Redis Sentinel

```conf
# sentinel.conf
sentinel monitor cc-redis redis-primary 6379 2
sentinel auth-pass cc-redis <haslo>
sentinel down-after-milliseconds cc-redis 5000
sentinel failover-timeout cc-redis 60000
sentinel parallel-syncs cc-redis 1
```

---

## 20. RabbitMQ Cluster

```bash
# Usuń domyślnego użytkownika guest (KRYTYCZNE!)
rabbitmqctl delete_user guest

# Utwórz vhost i użytkownika
rabbitmqctl add_vhost /contact-center
rabbitmqctl add_user cc_app <silne-haslo>
rabbitmqctl set_permissions -p /contact-center cc_app ".*" ".*" ".*"
rabbitmqctl set_user_tags cc_app management

# Polityka HA dla kolejek
rabbitmqctl set_policy --vhost /contact-center \
  ha-quorum ".*" '{"queue-type":"quorum"}' --apply-to queues
```

---

## 21. MinIO Distributed

```bash
# 4 węzły, każdy z 4 dyskami
minio server \
  https://minio{1...4}.internal/data{1...4} \
  --console-address ":9001"

# Tworzenie bucket i lifecycle
mc mb prod/contact-center-recordings
mc ilm add prod/contact-center-recordings --expiry-days 365
```

---

## 22. Twilio — konfiguracja webhooków

### 22.1 Wymagania

- URL musi być publicznie dostępny przez HTTPS z ważnym certyfikatem (nie self-signed)
- Twilio musi osiągnąć Twój serwer — sprawdź czy Twilio IP ranges nie są blokowane

### 22.2 Konfiguracja w Twilio Console

| Ustawienie | Wartość |
|-----------|---------|
| TwiML App → Request URL | `https://TWOJA_DOMENA/api/telephony/twiml/voice` |
| Status Callback | `https://TWOJA_DOMENA/api/telephony/webhook/twilio?tenantId=<UUID>` |
| Recording Callback | `https://TWOJA_DOMENA/api/telephony/webhook/twilio/recording` |

### 22.3 Weryfikacja

```bash
docker logs cc-backend 2>&1 | grep -i "twilio\|webhook\|signature"
```

---

## 23. Procedura rollback

### 23.1 Rollback kontenera

```bash
# Przywróć poprzedni obraz (musi być w cache lub registry)
docker tag contact-center/backend:previous contact-center/backend:latest
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps backend
```

### 23.2 Rollback migracji Flyway

```bash
# Sprawdź ostatnie migracje
docker exec cc-postgres psql -U ccapp -d contact_center \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"

# Flyway Community nie wspiera auto-rollback — przy addytywnych zmianach
# zazwyczaj bezpieczne jest pozostawienie stanu bazy i cofnięcie kodu.
# Przy destruktywnych zmianach wymagany jest restore z backupu.
```

### 23.3 Restore bazy z backupu

```bash
# TYLKO gdy rollback Flyway jest niemożliwy
pg_restore -h pg-primary -U postgres -d contact_center \
  --clean --if-exists \
  /backups/contact_center_pre_deploy.dump
```

---

## 24. Checklista bezpieczeństwa produkcyjnego

Przed udostępnieniem systemu na zewnątrz zweryfikuj:

- [ ] Swagger UI wyłączone (`springdoc.swagger-ui.enabled: false` w `application-prod.yml`)
- [ ] Actuator dostępny tylko z sieci wewnętrznej (Nginx `deny all` dla `/actuator`)
- [ ] `guest` user usunięty z RabbitMQ
- [ ] Firewall blokuje porty 5432, 6379, 5672, 9000, 8123 z zewnątrz (`ufw status`)
- [ ] `JWT_SECRET` ma co najmniej 64 znaki losowe (`openssl rand -base64 64`)
- [ ] Wszystkie hasła w `.env.prod` są silne (min. 20 znaków, generowane losowo)
- [ ] Plik `.env.prod` ma uprawnienia `600` (`chmod 600 .env.prod`)
- [ ] Certyfikat SSL jest ważny i auto-odnawia się (`certbot renew --dry-run`)
- [ ] Backup PostgreSQL skonfigurowany i przetestowany (test restore!)
- [ ] HSTS header włączony w Nginx (`Strict-Transport-Security`)
- [ ] Rate limiting w Nginx aktywny (`limit_req_zone`)
- [ ] Rotacja sekretów zaplanowana (co 90 dni)
- [ ] Logi są archiwizowane (`max-file: "5"` w Docker logging)

---

*Dokument wygenerowany na podstawie `ARCHITECTURE.md`, `application-prod.yml` i `docker-compose.yml`.*  
*Ostatnia aktualizacja: 2026-04-21*
