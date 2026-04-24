# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Język komunikacji

Odpowiadaj użytkownikowi **po polsku**. Kod, komentarze w kodzie i nazwy techniczne pozostają w języku angielskim.

---

## Delegowanie pracy i użycie skilli

> **BEZWZGLĘDNY WYMÓG** — nie wykonuj samodzielnie pracy, którą może wykonać agent lub skill.

- Używaj skilli (`/verify`, `/update-progress`, `/simplify`, `/frontend-design` itp.) zawsze gdy pasują do zadania.
- Deleguj do agentów gdy zadanie pasuje do ich opisu — nie wykonuj samodzielnie tego, co wyspecjalizowany agent zrobi lepiej.
- Agentów można uruchamiać **równolegle** gdy zadania są niezależne — zawsze preferuj równoległe uruchomienie.
- Po zakończeniu pracy przez agenta/skill — podsumuj wynik użytkownikowi po polsku.

| Typ zmiany                                                             | Agent                                |
|------------------------------------------------------------------------|--------------------------------------|
| Angular (komponenty, serwisy, szablony)                                | `angular-frontend-expert`            |
| Spring Boot / Java                                                     | `backend-dev-expert`                 |
| Obie warstwy jednocześnie                                              | OBA agenty **równolegle**            |
| Code review po implementacji                                           | `senior-code-reviewer`               |
| Migracja / schemat DB                                                  | `db-schema-architect`                |
| Debugowanie, analiza błędów                                            | `debug-specialist`                   |
| Projektowanie architektury oprogramowania                              | `architecture-designer`              |
| Planowania produktów i tworzenia dokumentów wymagań projektowych (PRD) | `prd-planner`                        |
| Dekonstrukcja wymagań na zadania                                       | `product-requirements-deconstructor` |
| Projektowania UI premium                                               | `ui-premium-designer`                |
| Testy jednostkowe, integracyjne, E2E                                   | `test-suite-expert`                  |

---

## Konwencje Git

- Gałęzie: `feature/<opis>`, `fix/<opis>`, `chore/<opis>`
- Dokumentacja postępu: `PROGRESS.md`. Zadania: `TASKS-DATABASE.md`, `TASKS-BACKEND.md`, `TASKS-FRONTEND.md`.

---

## Komendy

### Infrastruktura

```bash
docker compose up -d          # start (PostgreSQL 16, Redis 7, RabbitMQ 3.13)
docker compose down            # stop (zachowaj dane)
docker compose down -v         # stop + wyczyść dane
```

### Backend (uruchamiać z `backend/`)

```bash
mvn package -pl app -DskipTests            # build
mvn test -pl app                           # wszystkie testy
mvn test -pl app -Dtest=JwtServiceTest     # jeden test class
mvn verify -pl app                         # weryfikacja bez pakowania
cd app && mvn spring-boot:run -Dspring-boot.run.profiles=dev  # uruchomienie lokalne
```

### Frontend (uruchamiać z `frontend/`)

```bash
npm start        # dev server z proxy → localhost:8080
npm run build    # build produkcyjny
npm test         # Vitest
npm run lint     # ESLint
npm run lint:fix # ESLint auto-fix
npm run format   # Prettier (write)
npm run format:check  # Prettier (dry-run)
```

Lokalne usługi po `docker compose up -d`:
- PostgreSQL: `localhost:5432` db=`contact_center_dev` user=`postgres` pass=`postgres`
- Redis: `localhost:6379`
- RabbitMQ: `localhost:5672`, UI: `localhost:15672` (guest/guest)
- Backend: `localhost:8080`, Swagger: `http://localhost:8080/swagger-ui.html`

---

## Krytyczne reguły backendowe

### Multi-tenancy

- Każde repozytorium musi rozszerzać `TenantAwareRepository`.
- Przed każdym zapisem wywołaj `assertSameTenant(entity.getTenantId())`.
- Przy przekraczaniu granic wątków (`@Async`, `CompletableFuture`): `TenantContext.snapshot()` na wątku wywołującym, `TenantContext.restore(snapshot)` +
  `TenantContext.clear()` w `finally` na wątku roboczym.

### Kolejność filtrów (krytyczne)

`JwtAuthFilter` → `TenantFilter` → `UsernamePasswordAuthenticationFilter`

### Nowy publiczny endpoint — dwa miejsca

1. `SecurityConfig` – lista `requestMatchers` (permit)
2. `TenantFilter.PUBLIC_PATH_PREFIXES` – pomija weryfikację JWT

### Flyway — zasady migracji

- **Nigdy nie edytuj pliku migracji, który już został zastosowany.** Zamiast tego zawsze twórz nowy plik `V0xx__fix_something.sql`.
- Automatyczne czyszczenie DB jest wyłączone (`clean-on-validation-error: false`, `clean-disabled: true`) — błąd walidacji zablokuje start aplikacji, co jest sygnałem, że naruszono powyższą zasadę.
- Poniższe ustawienia **nigdy nie mogą trafić na prod**: `clean-on-validation-error: true`, `clean-disabled: false`.

---

## Krytyczne reguły frontendowe

- Tylko standalone components — bez NgModules.
- Stan: `signal()` / `computed()` gdzie możliwe; `BehaviorSubject` tylko dla streaming/polling.
- Prefix selektora: `app-` (features), `cc-` (shared/shell).
- Prettier: `printWidth: 100`, `singleQuote: true`, `tabWidth: 2`.

---

## Architektura i wymagania

Szczegóły: `ARCHITECTURE.md`, `PRD.md`.
