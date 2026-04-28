---
name: FE-052 Transloco i18n – Auth + AppShell
description: Wzorce i decyzje implementacyjne dla zadania internacjonalizacji modułów Auth i AppShell przy użyciu Transloco
type: project
---

Implementacja FE-052 zakończona. Zastąpiono polskie hardkodowane stringi kluczami Transloco w 4 komponentach + zaktualizowano 3 pliki JSON.

**Why:** Zadanie FE-052 – wielojęzyczność platformy (pl/en/de).

**How to apply:** Przy kolejnych zadaniach i18n stosuj te same wzorce.

## Wzorce zastosowane

### Wstrzykiwanie TranslocoService w .ts
```typescript
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
private readonly transloco = inject(TranslocoService);
// W computed signals:
return this.transloco.translate('auth.validation.emailRequired');
```

### Dodawanie TranslocoModule do imports[]
```typescript
imports: [ReactiveFormsModule, TranslocoModule],
```

### Pipe w szablonie
```html
{{ 'auth.login.title' | transloco }}
[attr.aria-label]="'nav.mainNav' | transloco"
[attr.title]="isCollapsed() ? (item.label | transloco) : null"
```

### Strategia sidenav – klucze zamiast wartości
Pola `label` i `ariaLabel` w tablicach `ADMIN_NAV`, `SUPERVISOR_NAV`, `AGENT_NAV` zawierają teraz klucze tłumaczeń (np. `'nav.dashboard'`), a w szablonie używa się `{{ item.label | transloco }}`.

## Struktura kluczy JSON
- `auth.login.*` – formularz logowania (emailLabel, nextButton, checking, loggingIn, verifying, mfaTitle, mfaSubtitle, mfaCodeLabel, mfaSubmitButton, orgLabel, orgPlaceholder, back, backToLogin)
- `auth.changePassword.*` – zmiana hasła (title, subtitle, currentLabel, newLabel, confirmLabel, submitButton, saving, passwordStrength, strengthWeak/Fair/Good/Strong)
- `auth.validation.*` – komunikaty walidacji pól
- `auth.errors.*` – błędy sieciowe/serwera
- `nav.*` – nawigacja (tenantLabel, menu, collapse, mainNav, collapseNav, expandNav, desktop, tenants, users, metrics, agentGroups, callbacks, ivr, configuration, settings*, reports*)
- `role.*` – etykiety ról (admin, supervisor, agent)

## Wynik buildu
Build OK – brak błędów TypeScript/Angular. Pre-egzystujące ostrzeżenia CSS budget i CommonJS (Twilio SDK) bez zmian. Lint zmodyfikowanych plików czysty.
