---
name: LanguageService and language model (FE-050)
description: LanguageService manages active UI language with localStorage persistence and backend sync via /api/users/me/preferences
type: project
---

LanguageService (`core/services/language.service.ts`) + `SupportedLanguage` type + `APP_INITIALIZER` registration.

**Why:** FE-050 — i18n language preference management with priority chain: backend → localStorage → navigator.language → 'pl' fallback.

**How to apply:**
- `AuthService.isAuthenticated()` is a `Signal<boolean>` (computed) — call it as `this.authService.isAuthenticated()` in service code; in tests mock it as `signal(true/false)`, not as plain function.
- `init()` is async, uses `firstValueFrom` + `catchError(() => of(null))` — never throws, errors silently degrade to next priority level.
- `setLanguage()` is fire-and-forget for backend PUT — `catchError` logs and returns `of(null)`, UI change is never reverted.
- Backend endpoints: `GET /api/users/me/preferences` → `{ preferredLanguage: string }`, `PUT /api/users/me/preferences` body `{ preferredLanguage: lang }`.
- `SupportedLanguage` = `'pl' | 'en' | 'de'`, stored in `core/models/language.model.ts`.
- `LANGUAGE_STORAGE_KEY = 'preferred_language'` in localStorage.
- Test isolation: `navigator.language` must be reset in `beforeEach` with `Object.defineProperty` to avoid bleed between tests. Use `{ value: 'xx-XX', configurable: true }` as the reset value.
- `TranslocoTestingModule.forRoot({ langs: { pl:{}, en:{}, de:{} }, ... })` used in tests (no `provideTranslocoTesting`).
