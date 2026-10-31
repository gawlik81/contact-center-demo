---
name: fe119-hardcoded-copy
description: FE-119 (EPIC-32) - KPI bez fałszywych celów, BRAND jako jedno źródło, placeholder CHAT, i18n literałów na powierzchni agenta, usunięty duplikat "Dodaj przerwę"
metadata:
  type: project
---

KPI agenta żyją w `shared/components/top-navbar` (nie w features/agent). Usunięto "cel 90%", "z 8:00" i pasek czasu pracy (fałszywy cel 8h); pasek SLA zostaje (realne %).

- Nazwa marki: `src/app/core/brand.ts` (`BRAND.name/vendor`), używana w navbarze, login i change-password. `index.html` title nadal `ContactCenterFrontend` (robocza).
- Przycisk "Dodaj przerwę": zostawiony w nagłówku agent-desktop (wcześnie w kolejności Tab, zawsze widoczny, odświeża kalendarz); usunięty FAB z `agent-calendar` (+ `addBreakMode`, klucz `agent.calendar.addBreakLabel`).
- CHAT: klucze `agent.desktop.chatPlaceholder*` zmienione na "Czat nie jest jeszcze dostępny" + `role="status"`.
- Nowa sekcja i18n `agent.aiSummary.*`; błędy `AiConfigNotSetError`/`AiServiceUnavailableError` mają teraz angielskie komunikaty developerskie, panel mapuje typ błędu na klucz i18n (aiError trzyma klucz, nie tekst).
- Testy: nowy `agent-desktop.copy.spec.ts`, `top-navbar.component.spec.ts`, `ai-summary-panel.component.spec.ts`. Zmiana języka w teście OnPush+transloco nie odświeża widoku - testuj osobny TestBed per język (`describe.each`).

**Why:** PRODUCT.md - żaden tekst nie może być na sztywno w jednym języku; nie pokazywać danych, których system nie zna.
**How to apply:** nowe teksty na powierzchni agenta tylko przez klucze w pl/en/de/uk (równoległe agenty edytują te same JSON-y - tylko Edit z małymi wstawkami). Pas KPI: `justify-content: safe center` + shrink/ellipsis dla tłumaczeń.
