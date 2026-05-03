---
name: Transloco i18n setup (FE-049)
description: Konfiguracja @jsverse/transloco v8 w Angular 21 – własny HttpLoader, pliki i18n w public/i18n/
type: project
---

Transloco v8 (`@jsverse/transloco@8.3.0`) zainstalowany i skonfigurowany. Brak gotowego `TranslocoHttpLoader` – należy implementować własny serwis implementujący `TranslocoLoader`.

**Why:** W v8 `TranslocoHttpLoader` nie jest eksportowany z pakietu – zmiana w stosunku do wcześniejszych wersji. Własny loader to jedyne działające rozwiązanie.

**How to apply:**
- Loader: `/home/pawelm/contact-center/frontend/src/app/core/transloco-http-loader.ts` – `@Injectable({ providedIn: 'root' })`, implementuje `TranslocoLoader`, `getTranslation` zwraca `http.get<Translation>(\`/i18n/\${lang}.json\`)`.
- Konfiguracja w `app.config.ts`: `provideTransloco({ config: { availableLangs: ['pl','en','de'], defaultLang: 'pl', fallbackLang: 'en', reRenderOnLangChange: true, prodMode: environment.production }, loader: TranslocoHttpLoader })`.
- Pliki JSON: `public/i18n/{pl,en,de}.json` – assets serwowane z katalogu `public/` (nie `src/assets`), loader używa URL `/i18n/{lang}.json`.
- `angular.json` assets: `{ "glob": "**/*", "input": "public" }` – katalog `public/` już obejmuje `public/i18n/`, brak konieczności dodawania osobnego wpisu.
- Preegzystujący błąd TS w `agent-calendar.service.spec.ts` blokuje `npm test` (brak pola `activeDays`/`activeHoursFrom`/`activeHoursTo`/`timezone` w obiekcie testowym) – niezwiązany z Transloco.
