# Dokumentacja techniczna – Contact Center SaaS

Ten katalog zawiera szczegółową dokumentację techniczną platformy Contact Center SaaS,
przeznaczoną dla osób dołączających do projektu. Opisuje **stan faktyczny implementacji**
(architektura, technologie, przepływy, struktury danych) – w odróżnieniu od pierwotnych
dokumentów projektowych (`PRD.md`, `ARCHITECTURE.md`), które opisują plan z początku projektu.

## Spis treści

1. [Przegląd projektu](01-overview.md) – cel systemu, persony, mapa modułów
2. [Architektura systemu (as-built)](02-architecture.md) – diagramy, ADR, status decyzji
3. [Stos technologiczny](03-tech-stack.md) – wersje, biblioteki, narzędzia
4. [Backend (Spring Boot)](04-backend.md) – moduły, endpointy, security, multi-tenancy
5. [Frontend (Angular)](05-frontend.md) – routing, feature moduły, state management
6. [Baza danych (PostgreSQL/Flyway)](06-database.md) – schemat, RLS, konwencje migracji
7. [Przepływy end-to-end](07-data-flows.md) – inbound/outbound call, IVR, kampanie, ETL, realtime
8. [Infrastruktura i wdrożenie](08-infrastructure.md) – Docker Compose, env, sieci, porty
9. [Getting Started](09-getting-started.md) – jak uruchomić projekt i zacząć pracę
10. [Tworzenie pluginów (EPIC-28)](10-plugin-development.md) – SDK, manifest, cykl życia,
    integracja UI, bezpieczeństwo
11. [Plugin Developer Guide](../plugin/plugin-development-guide.md) – dokumentacja techniczna
    dla zewnętrznych dostawców: pełna referencja SDK, extension pointy, manifest, uprawnienia,
    integracja UI, budowa JAR, REST API, przykłady ([HTML](../plugin/plugin-development-guide.html))
12. [Integracja Twilio](../twalio/twilio-integration.md) – szczegółowy opis adaptera telefonii,
    inbound/outbound, webhooki, transfer połączeń, nagrywanie, konfiguracja per-tenant (BYOT),
    bezpieczeństwo ([HTML](../twalio/twilio-integration.html))

## Sugerowana kolejność czytania dla nowej osoby

```
01 (przegląd)  →  03 (tech stack)  →  02 (architektura)  →  09 (getting started)
   → 04 / 05 / 06 (wg obszaru zadania)  →  07 (przepływy)  →  08 (infrastruktura)
   → 10 (pluginy, jeśli zadanie dotyczy EPIC-28)
   → plugin/ (zewnętrzny dostawca pluginu)
```

## Wersja HTML

Każdy dokument ma odpowiednik HTML w katalogu [`html/`](html/) – wygodny do przeglądania
w przeglądarce bez renderera Markdown. Punkt startowy: [`html/00-index.html`](html/00-index.html).

## Status i aktualizacja

Dokumentacja odzwierciedla stan repozytorium na **2026-07-05** (dodano rozdział 11 –
Plugin Developer Guide dla zewnętrznych dostawców). Backend i frontend rozwijane są
równolegle (patrz `PROGRESS.md`, `TASKS-*.md`) – w razie rozbieżności między tym dokumentem a
kodem, **kod jest źródłem prawdy**. Aktualizuj te pliki przy istotnych zmianach architektonicznych.
