---
name: new-design-system
description: Nowy design system tokenów oklch + ThemeService + przebudowa wizualna shell/agent desktop
metadata:
  type: project
---

Wdrożono nowy design system oparty na CSS custom properties oklch, w trybach light/dark/auto.

**Tokeny zdefiniowane w `src/styles.scss`:**
- `--bg-app`, `--bg-sidebar`, `--bg-surface`, `--bg-elevated`, `--bg-subtle`, `--bg-input`
- `--text-1`, `--text-2`, `--text-3`, `--text-muted`
- `--border-1`, `--border-2`, `--border-strong`
- Kolory semantyczne: `--accent`, `--success`, `--warning`, `--danger`, `--violet`, `--neutral` + `-soft` / `-text` / `-fg` warianty
- `--shadow-sm/md/lg/pop`, `--radius-sm/md/lg/xl`
- Animacje globalne: `pulse-ring`, `pulse-dot`, `slide-up`

**ThemeService** (`src/app/core/services/theme.service.ts`):
- Signal `mode: 'light' | 'dark' | 'auto'` + `resolved: 'light' | 'dark'`
- Reaguje na `prefers-color-scheme` media query
- Persystuje w `localStorage['kmn-theme']`
- Ustawia atrybut `data-theme` na `<html>`

**Czcionka:** Geist + Geist Mono z Google Fonts dodana do `src/index.html`.

**Przebudowane wizualnie (zachowana cała logika):**
- `TopNavbarComponent` – h=64px, KPI strip (5 kafelków tylko dla AGENT), ThemeSwitcher (3 segmenty), user chip z avatarem + tenantname, badge ról
- `SidenavComponent` – jasne tło (`--bg-sidebar`), accent-soft dla aktywnego linku, wskaźnik "Połączono" w footerze, scrollbar thin
- `AppShellComponent` – używa `--bg-app`, `--bg-elevated`, `--border-1`
- `AgentDesktopComponent` – pełna migracja na tokeny (sidebar 264px, header 48px, zakładki 44px)
- `CustomerPanelComponent` – tokeny zamiast hardkodowanych HEX
- `DispositionPanelComponent` – backdrop blur(4px), tokeny, Geist Mono dla timera

**Why:** Wymaganie UI premium z prototypu; tryb ciemny + systemowy bez JS toggle.
**How to apply:** Wszystkie nowe komponenty muszą używać `var(--token)` — nigdy nie hardkoduj `#hex` ani `rgb()` bezpośrednio.
