---
name: Angular workspace setup (FE-001)
description: Angular 21 project initialized in frontend/ – stack, structure, tooling decisions made in FE-001
type: project
---

Angular project lives at `D:/CloudeAI/contact-center-demo/frontend/`.
Angular version: 21.2.x (detected as "21" by angular-mcp).
Node: 25.8.1 (odd/unsupported but works).
Package manager: npm 11.3.0.
Test runner: Vitest (NOT Karma/Jasmine) – `ng test --watch=false` to run once.
Style language: SCSS.
Component pattern: standalone (Angular 17+).

**Why:** Project generated with `ng new --standalone true --style scss --routing true`.
**How to apply:** Always use standalone components; never generate NgModules for feature code.

Key file locations:
- `frontend/angular.json` – proxy wired in development serve config (`proxyConfig: proxy.conf.json`)
- `frontend/proxy.conf.json` – `/api` → `http://localhost:8080` (pathRewrite strips `/api`), `/ws` → `ws://localhost:8080`
- `frontend/src/environments/environment.ts` – dev: `apiUrl: '/api'`
- `frontend/src/environments/environment.prod.ts` – prod: same, `production: true`
- `frontend/eslint.config.js` – angular-eslint + typescript-eslint + eslint-config-prettier (prettier last)
- `frontend/.prettierrc` – singleQuote, trailingComma all, printWidth 100, LF endings

Husky `.husky/pre-commit` lives at git root (`D:/CloudeAI/contact-center-demo/.husky/pre-commit`).
Pre-commit hook: `cd frontend && npx lint-staged`
lint-staged config in `frontend/package.json`: runs eslint --fix + prettier on ts/html/scss.

Directory structure under `frontend/src/app/`:
- `core/` – guards, interceptors, services, models (singleton, root-provided)
- `shared/` – components, pipes, directives, models (reusable UI)
- `features/` – auth, admin, supervisor, agent, customers, reports, campaigns (lazy-loaded)
- `environments/` at `frontend/src/environments/`
