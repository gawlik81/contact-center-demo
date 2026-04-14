---
name: Phone numbers and routing rules management (FE-026)
description: PhoneNumbersComponent, RoutingRulesComponent, RoutingRuleFormComponent, PhoneNumberService – supervisor settings panel
type: project
---

Panel zarządzania numerami telefonów i regułami routingu dla supervisora. Route: `/supervisor/settings/phone-numbers`.

**Why:** Zastąpienie poprzedniego widoku „Twilio VoIP" (`/supervisor/settings/twilio`) pełnym panelem numerów i routingu IVR.

**How to apply:** Plik `sidenav.component.ts` SUPERVISOR_NAV sekcja „Konfiguracja" ma teraz wpis „Numery telefonów" zamiast „Twilio VoIP". Route `settings/twilio` usunięte z `supervisor.routes.ts`, dodane `settings/phone-numbers`.

Nowe pliki:
- `supervisor/models/phone-number.model.ts` – PhoneNumber, PhoneRoutingRule, DAY_LABELS, ALL_DAYS
- `supervisor/services/phone-number.service.ts` – GET/POST/PUT/DELETE dla phone-numbers i routing-rules
- `supervisor/pages/settings/phone-numbers/phone-numbers.component.*` – lista numerów (expand/collapse), native dialog dla add/edit/delete, skeleton, empty state
- `.../routing-rules/routing-rules.component.*` – karty reguł, warning badge (brak pełnego pokrycia), delete
- `.../routing-rules/routing-rule-form/routing-rule-form.component.*` – modal: checkboxy dni, time pickery, radio IVR/queue, kolizja 409
