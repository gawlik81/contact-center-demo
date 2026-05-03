---
name: FE-052 / masowa i18n – Transloco wzorce dla całej aplikacji
description: Wzorce i decyzje implementacyjne dla internacjonalizacji Transloco – auth, nav, agent, supervisor, admin – wszystkie 4 pliki JSON
type: project
---

Masowa implementacja i18n zakończona (2026-04-28). Zastąpiono polskie hardkodowane stringi kluczami Transloco w 60+ plikach TS/HTML. Build OK.

**Why:** Zadanie FE-052 + masowa i18n – wielojęzyczność platformy (pl/en/de/uk).

**How to apply:** Przy kolejnych zadaniach i18n stosuj te same wzorce. Wszystkie 4 pliki JSON muszą być aktualizowane jednocześnie.

## Kluczowe wzorce

### Wstrzykiwanie TranslocoService w .ts
```typescript
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
private readonly transloco = inject(TranslocoService);
this.notifications.error(this.transloco.translate('klucz'));
```

### Dodawanie TranslocoModule do imports[] w dekoratorze
```typescript
imports: [ReactiveFormsModule, TranslocoModule],
```

### Pipe w szablonie
```html
{{ 'agent.incomingCall.label' | transloco }}
[attr.aria-label]="'klucz' | transloco"
[title]="isAvailable() ? '' : ('klucz' | transloco)"
```

### Mapy statusów – transloco zamiast stałych
```typescript
getStatusLabel(status: string): string {
  return this.transloco.translate(`agent.callbacksPage.statusLabels.${status}`, {}, status);
}
```

### Model z labelKey zamiast label (np. disposition.model.ts)
```typescript
{ code: 'SALE', labelKey: 'agent.dispositionCodes.SALE' }
// W HTML: {{ item.labelKey | transloco }}
```

## Struktura kluczy JSON (nowe sekcje dodane w masowej i18n)
- `common.errorLabel`, `common.warningLabel` – etykiety toast
- `toast.*` – toast ariaLabel (success/error/warning/info, closeLabel)
- `auth.forbidden.*` – strona 403
- `contactDetailModal.*` – modal szczegółów kontaktu (statusLabels, channelLabels, directionLabels itd.)
- `agent.dispositionCodes.*` – kody dyspozycji (SALE, NO_INTEREST itd.)
- `agent.calendarStatus.*` – breakLabels, breakStatusLabels, callbackSourceLabels, callbackStatusLabels, campaignStatusLabels
- `agent.status.errorLoad/errorChange` – błędy serwisu statusu
- `agent.customerPanel.lookupError` – błąd lookup klienta
- `agent.incomingCall.limitMaxPhone/limitMaxAsync/limitMaxTotal/limitGeneral/systemNotificationTitle`
- `agent.emailThread.noContent` – brak treści wiadomości email
- `agent.callbacksPage.*` – paginacja, dialogi potwierdzenia, statusLabels
- `agent.manualCampaign.errorLoad/callError/callRecordNotFound/callInitiating`
- `agent.addBreak.breakTypes.*` – typy przerw jako klucze transloco
- `supervisor.ivrNodeLabels.*` – etykiety węzłów IVR
- `supervisor.agentGroups.*` – CRUD powiadomienia
- `supervisor.callbacks.*` – paginacja, dialogi, statusLabels
- `supervisor.campaigns.confirm*/error*/success*` – akcje pause/stop/revert
- `supervisor.customerDetail.*`, `supervisor.customerEdit.*`, `supervisor.customerCreate.*`
- `supervisor.customers.*`, `supervisor.customerImport.*`, `supervisor.gdprAnonymize.*`
- `supervisor.ivr.*` – powiadomienia IVR list
- `supervisor.queueAssignment.*`, `supervisor.editCallback.*`
- `supervisor.settings.email.*`, `supervisor.settings.emailTemplates.*`
- `supervisor.settings.phoneNumbers.*`, `supervisor.settings.routingRules.*`, `supervisor.settings.routingRuleForm.*`
- `supervisor.users.*` – CRUD agentów
- `admin.userForm.*`, `admin.userList.*`, `admin.tenants.*`
- `integrations.social.*` – social media integrations
- `breadcrumbs.*` – breadcrumby (statyczne, breadcrumb.service.ts nie transluje dynamicznie)

## Wynik buildu
Build OK – brak błędów TypeScript/Angular. Pre-egzystujące ostrzeżenia CSS/CommonJS (Twilio) bez zmian.

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
