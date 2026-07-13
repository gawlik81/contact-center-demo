---
name: project-design-alignment
description: Wyniki weryfikacji i naprawy zgodności designu panelu agenta z prototypem HTML/JSX (maj 2026)
metadata:
  type: project
---

Przeprowadzono weryfikację 13 ekranów panelu agenta względem prototypu JSX z katalogu `/tmp/design_extracted/kmncontactcenter-newdesigne/project/`.

**Zmiany naniesione:**

1. **agent-desktop.component** – dodano przyciski "Kalendarz" i "Dodaj przerwę" w status bar (`desktop__header-actions`), empty state kolejki z ikoną i dashed border, metoda `openCalendarAndAddBreak()`.

2. **softphone.component** – controls przebudowane na `grid 4-kolumnowy`, dodano 4. przycisk "Notatka" (disabled), ctrl-btn używa `ctrl-icon-wrap` jako osobna tło dla ikony, hangup ma `box-shadow danger`, recording-dot używa `pulse-dot` z `box-shadow`, ringing state z `avatar-wrap`+`avatar-ring`, przyciski Odbierz/Odrzuć prostokąty zamiast kółek, callback z `border-style: dashed`.

3. **customer-panel.component** – avatar `border-radius: 12px` (prostokąt) zamiast `50%`, gradient `violet→accent`, empty state z `icon-wrap` + tytuł + subtitle.

4. **agent-customer-card.component** – avatar prostokąt 12px zamiast kółka, `violet→accent` gradient, card `border-radius: 14px`.

5. **agent-calendar.component** – grid dni w obramowanej karcie z `border-radius: 14px` i paddingiem, każdy dzień-kolumna jako osobna karta z zaokrągleniami i `min-height: 320px`.

6. **agent-callbacks-page.component** – count chip jako badge `accent-soft`, table-wrapper `border-radius: 14px`.

7. **disposition-panel.component** – grid kategorii `auto-fit, minmax(190px, 1fr)`.

8. **i18n** – dodano klucze: `agent.customerPanel.emptyTitle`, `agent.softphone.note`, `agent.softphone.noteLabel`.

9. **angular.json** – zwiększono budżet `anyComponentStyle` z 16kB/8kB na 24kB/12kB (preistniejące arkusze były blisko limitu).

**Nie zmieniano (poza zakresem wizualnym lub komponent brakuje):**
- Disposition: podkategorie chips i checkbox "Zaplanuj oddzwonienie" wymagają zmian w modelu — poza zakresem CSS-only.
- IncomingEmail: brak osobnego komponentu Angular (obsługiwane przez contact-tabs EMAIL).
- Desktop breadcrumb: przesuwa się do status-bar, ale current state różni się — nie zmieniano logiki routera.

**Why:** Zadanie designowe — zbliżenie wyglądu do prototypu bez zmiany logiki biznesowej.
**How to apply:** Przy nowych ekranach agenta wzorować się na prototypie JSX, szczególnie kształtach avatarów (prostokąt 12px dla klientów, kółko dla agenta).
