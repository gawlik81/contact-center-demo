---
name: fe110-cr-fixes
description: Poprawki CR FE-110 (retencja danych) - CAMPAIGN_DATA opis, hinty tylko dla CONTACT_INTERACTIONS, test parytetu i18n przez JSON import
metadata:
  type: project
---

- `CAMPAIGN_DATA` purge usuwa WYLACZNIE `campaign_contact_archive` - opisy i18n musza to mowic (nie "kontakty kampanii").
- Hinty "suma roznych typow" tylko dla `isMultiTypeCategory` (= CONTACT_INTERACTIONS); widoczny `<p class="dr-hint">` + aria-describedby zamiast `title`. Brak wspolnego komponentu tooltipa w repo (follow-up).
- Test parytetu i18n: import JSON z `public/i18n/*.json` wymaga `resolveJsonModule` w tsconfig.spec.json; brak @types/node, wiec `node:fs` w specach nie dziala.
