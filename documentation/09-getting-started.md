# 9. Getting Started – jak zacząć pracę

## 9.1 Wymagania

- Docker + Docker Compose
- Java 21 + Maven (do pracy z backendem poza kontenerem)
- Node.js (LTS) + npm (do pracy z frontendem)
- Dostęp do repozytorium git

## 9.2 Uruchomienie infrastruktury (Postgres, Redis, RabbitMQ, MinIO, ClickHouse, voicebot)

```bash
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d --remove-orphans
```

> Zawsze z `--env-file .env.local-demo` i obydwoma plikami `-f` (patrz `CLAUDE.md` i
> [`08-infrastructure.md`](08-infrastructure.md)) – inaczej kontenery odtworzą się z domyślnymi
> hasłami i mogą uszkodzić dane wolumenów.

Zatrzymanie (zachowuje dane):

```bash
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml down
```

## 9.3 Backend (uruchamianie lokalnie, poza kontenerem)

```bash
cd backend
mvn package -pl app -DskipTests            # build
mvn test -pl app                           # wszystkie testy
mvn test -pl app -Dtest=JwtServiceTest     # jeden test
mvn verify -pl app                         # weryfikacja bez pakowania
cd app && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # start lokalny (port 8080)
```

Po starcie:
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Healthcheck: `http://localhost:8080/actuator/health`
- Flyway uruchamia migracje automatycznie przy starcie.

## 9.4 Frontend

```bash
cd frontend
npm install
npm start          # dev server na :4200, proxy do :8080
npm run build      # build produkcyjny
npm test           # Vitest
npm run lint       # ESLint
npm run lint:fix
npm run format        # Prettier (write)
npm run format:check  # Prettier (dry-run)
```

## 9.5 Voicebot (Python/FastAPI)

```bash
cd voicebot
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

Wymaga działającego Redis i RabbitMQ (z `docker compose up -d`).

## 9.6 Pierwsze kroki w kodzie – checklist dla nowej osoby

1. Przeczytaj [`01-overview.md`](01-overview.md) i [`02-architecture.md`](02-architecture.md) –
   zrozum mapę modułów i ogólny przepływ.
2. Uruchom infrastrukturę (9.2) i backend (9.3) – sprawdź Swagger, zaloguj się testowym
   użytkownikiem (dane logowania – patrz `.env.local-demo` / dane seedowe w migracjach Flyway,
   `06-database.md`).
3. Uruchom frontend (9.4), zaloguj się i przejdź przez UI dla roli, której dotyczy Twoje
   zadanie (`admin`/`supervisor`/`agent`).
4. Dla zadań backendowych: zapoznaj się z [`04-backend.md`](04-backend.md) – szczególnie z
   sekcją multi-tenancy i kolejnością filtrów bezpieczeństwa (krytyczne, łatwo złamać).
5. Dla zadań frontendowych: [`05-frontend.md`](05-frontend.md) – konwencje standalone
   components, signals, routing per rola.
6. Dla zmian w bazie danych: [`06-database.md`](06-database.md) – **nigdy nie edytuj
   zastosowanej migracji Flyway**, zawsze nowy plik `Vxxx__opis.sql`.
7. Przed oznaczeniem zadania jako zakończone – uruchom `/verify` (lint + format + testy FE i BE).

## 9.7 Konwencje pracy

- Gałęzie: `feature/<opis>`, `fix/<opis>`, `chore/<opis>`.
- Dokumentacja postępu: `PROGRESS.md`; zadania: `TASKS-BACKEND.md`, `TASKS-FRONTEND.md`,
  `TASKS-DATABASE.md`.
- Komunikacja w repo (komentarze, dokumentacja PR) – po polsku; kod, identyfikatory, komentarze
  w kodzie – po angielsku.
- Code review – wyniki trafiają do `CR-BACKEND.md`, `CR-FRONTEND.md`, `CR-DATABASE.md`,
  `CR-TELECOM.md`.

## 9.8 Najczęstsze pułapki

| Pułapka | Skutek | Jak unikać |
|---------|--------|------------|
| Brak `--env-file`/`-f` przy `docker compose` | błędne hasła, możliwa utrata danych wolumenów | zawsze pełna komenda z 9.2 |
| Edycja zastosowanej migracji Flyway | błąd walidacji blokujący start aplikacji | nowy plik migracji |
| Zła kolejność filtrów security | obejście tenant isolation / auth | nie modyfikować bez zrozumienia `JwtAuthFilter`→`TenantFilter`→auth |
| Brak `TenantContext.restore()`/`clear()` w `@Async` | wycieki danych między tenantami / NPE | snapshot/restore/clear wg `CLAUDE.md` |
| Nowy publiczny endpoint dodany tylko w `SecurityConfig` | `TenantFilter` i tak zablokuje request | dodać też do `PUBLIC_PATH_PREFIXES` |
| Przeciążanie istniejącej kolumny pod nową relację | nieczytelny, kosztowny w utrzymaniu schemat | nowa kolumna z opisową nazwą (patrz `06-database.md`) |
