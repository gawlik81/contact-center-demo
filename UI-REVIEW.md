# UI Review — Contact Center SaaS Frontend
**Data analizy:** 2026-03-26
**Stack:** Angular 21, standalone components, SCSS, Angular Material
**Analizowane pliki:** ~25 komponentów SCSS + HTML

---

## Executive Summary — Ocena ogólna: 6.2 / 10

UI jest **solidnie zbudowane pod względem technicznym** — poprawna semantyka HTML, dostępność WCAG AA, skeleton loadery, responsywność, BEM w SCSS. Jednak **brakuje charakteru premium**. Interfejs wygląda jak dobrze zrobione narzędzie administracyjne z 2021 roku, a nie produkt klasy Stripe/Linear.

Główne problemy:

- **Brak spójnego design systemu** — każdy komponent definiuje własne zmienne SCSS (`$brand-blue: #1565c0` w login, `$brand-blue: #1565c0` w dashboard, ale `#4a90d9` w IVR editor). Brak globalnych tokenów CSS.
- **Animacje praktycznie nieobecne** — żadnych page transitions, żadnych staggered list animations, żadnych microinteractions poza prostym `transition: background 0.15s`.
- **Brak `prefers-reduced-motion`** — ani jeden komponent nie zawiera tego media query, mimo że projekt używa animacji (shimmer, spin, pulse).
- **Tabele bez hover elevation** — hover tylko zmienia tło na `#f8fafc`, bez `translateY`, bez subtelnego cienia — brak poczucia "kliknięcia".
- **Login page pozbawiony wizualnego charakteru** — płaskie tło `#f0f4f8`, brak gradienta, brak ilustracji, brak entrance animation na card.
- **KPI cards** — wartości 2rem/700 wagi są ok, ale karty są płaskie — brak premium depth (wielowarstwowych boxshadow), brak gradient accent na górnej krawędzi.
- **Sidenav aktywny element** — `background: #2d6cdf` to twarda wartość, brak lewej krawędzi akcentowej (vertical indicator) — charakterystycznej dla Linear, Vercel, Stripe.

---

## Analiza per-komponent

### 1. LoginComponent (`/features/auth/login/`)

**Ocena: 5 / 10**

**Co działa:**
- Poprawny multi-step flow (email → credentials → MFA)
- `step-wrapper` ma animację opacity+translateX — dobry kierunek
- Error banner z `aria-live="assertive"` — WCAG compliant
- Inline spinner w przycisku podczas ładowania

**Problemy:**
```scss
// PROBLEM 1: Tło strony
background: #f0f4f8; // płaski kolor — zero charakteru

// PROBLEM 2: Card — brak premium depth
box-shadow:
  0 4px 6px rgba(0, 0, 0, 0.07),
  0 10px 24px rgba(0, 0, 0, 0.1);
// Zbyt słaby shadow — karta wtapia się w tło

// PROBLEM 3: Brand icon
background: $brand-blue; // twardy kolor, brak gradientu
// Brak logo SVG — tylko "CC" jako tekst

// PROBLEM 4: Animacja step-wrapper używa `ease` zamiast cubic-bezier
transition:
  opacity $transition-speed ease,
  transform $transition-speed ease;
// $transition-speed: 0.35s — za wolno dla microinteraction

// PROBLEM 5: Brak entrance animation na .auth-card
// Karta pojawia się natychmiastowo bez fade/slide in
```

**Brak:** entrance animation na card przy załadowaniu strony, gradient tła, `prefers-reduced-motion`.

---

### 2. AppShell + Sidenav (`/shared/components/`)

**Ocena: 6.5 / 10**

**Co działa:**
- Responsywny layout (mobile overlay, tablet overlay, desktop sticky)
- Poprawna obsługa `transform: translateX` dla wydajności GPU
- `will-change: transform` na sidenav
- Focus ring z `outline: 2px solid #60a5fa`

**Problemy:**
```scss
// PROBLEM 1: Aktywny link — brak lewej krawędzi akcentowej
&--active {
  background: $sidenav-active-bg; // tylko tło
  // BRAKUJE:
  // border-left: 3px solid rgba(255,255,255,0.9);
  // padding-left: calc(0.75rem - 3px);
}

// PROBLEM 2: Hover bez depth
&:hover {
  background: $sidenav-hover-bg; // rgba(255,255,255,0.07)
  // BRAKUJE translateX(2px) dla poczucia ruchu
}

// PROBLEM 3: Sidenav transition — linear easing
transition: transform 0.25s ease; // brak cubic-bezier

// PROBLEM 4: Alert badge — brak pulsowania
// .sidenav__alert-badge nie ma animation: pulse
// Czerwona kropka powinna pulsować dla attention

// PROBLEM 5: Breadcrumb area
background: #ffffff;
border-bottom: 1px solid #e2e8f0;
// Mogłoby mieć subtelny gradient: background: linear-gradient(to bottom, #ffffff, #fafbfc)
```

---

### 3. TopNavbar (`/shared/components/top-navbar/`)

**Ocena: 6 / 10**

**Co działa:**
- Hover na logout button z ładnym efektem czerwonego koloru
- Responsywne ukrywanie elementów na mobile
- Role badges z czytelną kolorystyką pill-style

**Problemy:**
```scss
// PROBLEM 1: Navbar shadow — zbyt subtelny
box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
// Brak oddzielenia od contentu na scrollu

// PROBLEM 2: Brand name — brak letter-spacing dla premium feel
font-size: 1rem;
font-weight: 600;
// BRAKUJE: letter-spacing: -0.01em

// PROBLEM 3: Hamburger button — hover bez transition na transform
&:hover {
  background: #f1f5f9;
  border-color: #e2e8f0;
  // BRAKUJE: transform: scale(1.05)
}

// PROBLEM 4: Logo — to jest SVG icon, nie ma gradient fill
// Mógłby mieć gradient blue-to-indigo dla premium touch
```

---

### 4. AdminDashboard (`/features/admin/pages/dashboard/`)

**Ocena: 7 / 10**

**Co działa (najlepszy komponent w projekcie):**
- Skeleton loader z shimmer animation — dobrze zrobiony
- KPI cards z icon-wrap kolorystyką
- Progress bar z `transition: width 0.3s ease`
- Empty state z ikoną
- Hover na `.data-row` (choć minimalistyczny)
- Poprawny `aria-live` na timestamp

**Problemy:**
```scss
// PROBLEM 1: KPI cards — brak górnego akcentu (colored top border)
.kpi-card {
  border: 1px solid $border-color; // jednolita ramka
  // BRAKUJE:
  // border-top: 3px solid; // kolor per typ karty
  // Lub: gradient na icon-wrap zamiast flat background
}

// PROBLEM 2: Hover na KPI card — zbyt słaby
&:hover {
  box-shadow: 0 3px 8px rgba(0, 0, 0, 0.1);
  // BRAKUJE: transform: translateY(-2px)
}

// PROBLEM 3: KPI value — brak count-up animation
// Gdy dane się ładują, liczby skaczą bez animacji

// PROBLEM 4: Section headers — brak sticky
// Na długich stronach sekcje nie są sticky — content się gubi

// PROBLEM 5: Data rows — brak animacji wejścia (staggered list)
// Wiersze tabeli pojawiają się bez żadnej animacji
```

---

### 5. SupervisorDashboard (`/features/supervisor/supervisor-dashboard.component.scss`)

**Ocena: 5.5 / 10**

**Uwaga:** To jest najsłabiej wystylizowany komponent. Używa `px` zamiast `rem`, magic numbers, brak spójności z resztą projektu.

**Problemy:**
```scss
// PROBLEM 1: Mieszanie px i rem
th, td {
  padding: 10px 16px; // px zamiast rem
}

// PROBLEM 2: KPI cards — brak icon wrap
.kpi-card {
  // Brak struktury icon + content jak w admin-dashboard
  // Tylko label + value — wizualnie ubogie
}

// PROBLEM 3: Queue bar — dobry pomysł (gradient fill), ale bez hover
.queue-row {
  // Brak hover state na całym wierszu
  // Brak transition na samej krawędzi
}

// PROBLEM 4: Status badges — `text-transform: uppercase` z dużym letter-spacing
// na małych tokenach (7px) — czytelność cierpi na małych ekranach

// PROBLEM 5: btn-fullscreen — zbyt generyczny wygląd
// border: 1px solid #bdbdbd — ta sama szarość co placeholder
```

---

### 6. AgentDesktop (`/features/agent/pages/agent-desktop/`)

**Ocena: 7 / 10**

**Co działa:**
- Status button jako pill z obramowaniem w kolorze statusu — dobry design decision
- Contact tabs z kolorowym górnym border per typ (phone=blue, email=purple, chat=green)
- Avatar z gradient background (`linear-gradient(135deg, #3b82f6, #8b5cf6)`)
- WS indicator z dynamicznym kolorem
- Queue items z nicely styled cards

**Problemy:**
```scss
// PROBLEM 1: Status menu — pojawia się bez animacji
.status-menu {
  // Brak: opacity 0 → 1 + translateY(-4px) → 0 przy otwarciu
}

// PROBLEM 2: Contact tabs — brak microinteraction przy zamknięciu
.contact-tab__close {
  &:hover {
    background: #e2e8f0;
    color: #ef4444;
    // BRAKUJE: transform: scale(1.1) dla wyraźniejszego feedback
  }
}

// PROBLEM 3: No-contacts placeholder — statyczna ikona
// SVG ikona mogłaby mieć subtle float animation

// PROBLEM 4: Queue items — hover tylko zmienia tło
.queue-item:hover {
  background: #f1f5f9;
  // BRAKUJE: border-color transition do wyraźniejszego koloru
  // BRAKUJE: box-shadow: 0 2px 4px rgba(0,0,0,0.06)
}

// PROBLEM 5: WS banner — brak animowanego disappear/appear
// Banner pojawia się/znika natychmiastowo
```

---

### 7. Softphone (`/features/agent/components/softphone/`)

**Ocena: 8 / 10** — Najlepiej wykonany komponent!

**Co działa:**
- Pulse animation na avatar podczas dzwonienia
- Colored background gradients per stan (ringing/active/hold)
- Scale(1.07) na hover przycisków, scale(0.96) na active — prawidłowe
- Blink animation na recording dot
- Transfer modes z `--active` state
- Box-shadow na przyciskach answer/reject z kolorem tematycznym

**Problemy:**
```scss
// PROBLEM 1: Animacje używają `linear` easing zamiast cubic-bezier
transition:
  transform 0.1s,  // brak easing
  box-shadow 0.1s; // brak easing

// PROBLEM 2: Brak prefers-reduced-motion
// softphone-pulse, softphone-blink, softphone-spin — żadna nie ma @media fallback

// PROBLEM 3: Hold state — brak visual pulsowania timera
// Hold timer mógłby pulsować kolorystycznie gdy przekroczy próg

// PROBLEM 4: Ended state — brak checkmark animation
// Po zakończeniu rozmowy ikona pojawia się bez animacji
```

---

### 8. EmailContact (`/features/agent/pages/agent-desktop/email-contact/`)

**Ocena: 6.5 / 10**

**Co działa:**
- Split layout (thread 40% / reply 60%) — przemyślany
- Custom scrollbar styling
- Editor z focus-within border-color change
- Autocomplete list z dobrym shadow

**Problemy:**
```scss
// PROBLEM 1: Toolbar buttons (editor-btn) — za małe target area (2rem x 2rem)
// WCAG zaleca 44px minimum dla touch targets

// PROBLEM 2: Brak animacji wejścia wiadomości w wątku
// Nowe wiadomości mogłyby mieć fade-in animation

// PROBLEM 3: Reply area — brak character counter dla długich emaili

// PROBLEM 4: Loading state używa spinnera zamiast skeleton
.email-loading__spinner — okrągły spinner
// Lepiej: skeleton placeholder imitujący layout emaila
```

---

### 9. IVR Editor (`/features/supervisor/pages/ivr/ivr-editor/`)

**Ocena: 7.5 / 10** — Technicznie najbardziej skomplikowany komponent.

**Co działa:**
- Dot-grid canvas tło — profesjonalny wygląd
- Color-coded nodes (border-top z kolorem per typ)
- Pulse animation na active connections (dash-flow)
- Selected node z blue outline glow
- Entry node z zielonym outline
- Debug panel z dark theme

**Problemy:**
```scss
// PROBLEM 1: Toolbar nodes — hover zbyt subtelny
.toolbar-node:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  // BRAKUJE: border-color transition do koloru akcentowego danego node
  // BRAKUJE: transform: scale(1.02)
}

// PROBLEM 2: Node ikony — tło #e2e8f0 szare zamiast kolorowego
.toolbar-node__icon {
  background: #e2e8f0; // szare dla wszystkich
  // Powinno być kolorowe jak border-left na node
}

// PROBLEM 3: IVR nodes — brak animacji drop przy drag&drop

// PROBLEM 4: Properties panel — form-control brak transition na invalid state
```

---

### 10. Toast/Notifications

**Brak pliku SCSS** (toast nie ma osobnego .scss) — style prawdopodobnie w globalnym lub inline. Wymaga analizy po znalezieniu implementacji.

---

### 11. Tabele (TenantList, UserList, QueueList)

**Ocena: 5.5 / 10** — Powtarzalny wzorzec, ten sam problem we wszystkich.

**Co działa:**
- Skeleton loading zaimplementowany wszędzie
- Responsywne ukrywanie kolumn (`hide-xs`, `hide-sm`)
- Górna krawędź `thead` z `background: #f8fafc`

**Problemy:**
```scss
// PROBLEM 1: Row hover — tylko zmiana koloru tła
.tenant-row:hover,
.queue-row:hover {
  background: $bg-row-hover; // #f8fafc — prawie niewidoczna zmiana
}
// BRAKUJE: transition z cubic-bezier, subtelny cień, cursor: pointer

// PROBLEM 2: Brak sortowania kolumn — th bez hover state sugerującego klikalność

// PROBLEM 3: Status badges — brak border w tenant-list (queue-list ma border, tenant nie)
// Niespójność między komponentami

// PROBLEM 4: Action buttons — małe ikony bez tooltip (brak aria-label w HTML)

// PROBLEM 5: Pagination — plain text "Poprzednia / Następna"
// Brak ikon strzałek w btn-pagination
```

---

## Kluczowe Braki Systemowe

### A. Brak globalnych tokenów CSS

Każdy plik SCSS redefiniuje te same kolory:
```scss
// Pojawia się w 6 różnych plikach:
$brand-blue: #1565c0; // login, admin-dashboard, tenant-list, queue-list
// Ale IVR editor używa:
$brand-blue: #4a90d9; // INNY KOLOR!
// Email contact używa:
// #3b82f6 (hardcoded, bez zmiennej)
```

Brak `styles.scss` z design tokenami — `styles.scss` jest pusty (1 linia komentarza).

### B. Brak `prefers-reduced-motion`

Żaden z 25 analizowanych plików SCSS nie zawiera:
```scss
@media (prefers-reduced-motion: reduce) { ... }
```

Animacje: `shimmer`, `spin`, `softphone-pulse`, `softphone-blink`, `dash-flow`, `blink` — wszystkie bez fallbacku.

### C. Brak Angular Animations API

Żaden komponent nie importuje `@angular/animations`. Wszystkie "animacje" to CSS transitions na `:hover`. Brak:
- Page transitions przy nawigacji
- Staggered list animations
- Entrance animations dla kart i modali
- Exit animations

### D. Niespójne border-radius

```scss
// W różnych komponentach:
border-radius: 6px;   // queue-list, tenant-list, login
border-radius: 8px;   // agent-desktop, softphone, sidenav items
border-radius: 10px;  // admin-dashboard kpi-cards
border-radius: 12px;  // auth-card, IVR modal
```
Brak jednej zmiennej `--radius-md`, `--radius-lg`.

---

## TOP 5 Priorytetów do Poprawy

### PRIORYTET 1 — Globalny design system (tokeny CSS)
**Wpływ: bardzo wysoki | Effort: średni**

Stwórz `frontend/src/styles.scss` z CSS custom properties:

```scss
// frontend/src/styles.scss
:root {
  // Brand colors
  --color-brand: #1565c0;
  --color-brand-dark: #0d47a1;
  --color-brand-light: #e3f2fd;
  --color-brand-600: #1a56db;

  // Semantic colors
  --color-success: #2e7d32;
  --color-success-bg: #e8f5e9;
  --color-warning: #e65100;
  --color-warning-bg: #fff3e0;
  --color-danger: #c62828;
  --color-danger-bg: #ffebee;

  // Text
  --color-text-primary: #1e293b;
  --color-text-secondary: #64748b;
  --color-text-muted: #94a3b8;

  // Surface
  --color-surface: #ffffff;
  --color-surface-2: #f8fafc;
  --color-surface-3: #f1f5f9;
  --color-border: #e2e8f0;
  --color-border-focus: var(--color-brand-600);

  // Radius
  --radius-sm: 4px;
  --radius-md: 6px;
  --radius-lg: 10px;
  --radius-xl: 12px;
  --radius-full: 9999px;

  // Shadows
  --shadow-xs: 0 1px 2px rgba(0,0,0,0.05);
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06);
  --shadow-md: 0 4px 6px rgba(0,0,0,0.07), 0 10px 15px rgba(0,0,0,0.05);
  --shadow-lg: 0 10px 15px rgba(0,0,0,0.1), 0 4px 6px rgba(0,0,0,0.05);

  // Transitions
  --ease-standard: cubic-bezier(0.4, 0, 0.2, 1);
  --ease-spring: cubic-bezier(0.16, 1, 0.3, 1);
  --ease-out: cubic-bezier(0, 0, 0.2, 1);
  --duration-fast: 120ms;
  --duration-normal: 200ms;
  --duration-slow: 300ms;
}

// Obligatoryjny fallback dla wszystkich animacji
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
    scroll-behavior: auto !important;
  }
}
```

---

### PRIORYTET 2 — Premium table rows (wszystkie tabele)
**Wpływ: wysoki | Effort: mały**

Zmiana w każdym pliku SCSS zawierającym `.tenant-row`, `.queue-row`, `.data-row`:

```scss
// PRZED (np. tenant-list.component.scss):
.tenant-row {
  transition: background 0.12s ease;
  &:hover {
    background: $bg-row-hover;
  }
}

// PO:
.tenant-row {
  transition:
    background var(--duration-fast) var(--ease-standard),
    box-shadow var(--duration-fast) var(--ease-standard);
  cursor: pointer;

  td {
    transition: color var(--duration-fast) var(--ease-standard);
  }

  &:hover {
    background: var(--color-surface-2, #f8fafc);
    box-shadow: inset 3px 0 0 var(--color-brand, #1565c0);

    .tenant-name {
      color: var(--color-brand, #1565c0);
    }
  }

  &:active {
    background: var(--color-surface-3, #f1f5f9);
  }
}
```

Efekt: prawa krawędź tabeli podświetla się pionowym paskiem brandowym — wzorzec z Linear.

---

### PRIORYTET 3 — Login page entrance animation + premium tło
**Wpływ: wysoki (first impression) | Effort: mały**

```scss
// login.component.scss — ZMIENIĆ:

// PRZED:
.auth-page {
  background: $bg-page; // #f0f4f8
}
.auth-card {
  // brak entrance animation
}

// PO:
.auth-page {
  background:
    radial-gradient(ellipse at 30% 20%, rgba(21, 101, 192, 0.08) 0%, transparent 60%),
    radial-gradient(ellipse at 70% 80%, rgba(107, 70, 193, 0.06) 0%, transparent 60%),
    #f0f4f8;
}

.auth-card {
  // Dodać entrance animation
  animation: card-enter 400ms cubic-bezier(0.16, 1, 0.3, 1) forwards;
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.04),
    0 4px 8px rgba(0,0,0,0.08),
    0 16px 48px rgba(0,0,0,0.12);
}

@keyframes card-enter {
  from {
    opacity: 0;
    transform: translateY(16px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

// Step animation — zmienić timing function:
.step-wrapper {
  transition:
    opacity 200ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 200ms cubic-bezier(0.4, 0, 0.2, 1);
}

// Brand icon — gradient zamiast flat:
.brand-icon {
  background: linear-gradient(135deg, #1976d2 0%, #1a56db 100%);
  box-shadow: 0 2px 8px rgba(21, 86, 219, 0.4);
}
```

---

### PRIORYTET 4 — Sidenav active state z pionowym wskaźnikiem
**Wpływ: wysoki (używany cały czas) | Effort: mały**

```scss
// sidenav.component.scss — ZMIENIĆ .sidenav__link--active:

// PRZED:
&--active {
  background: $sidenav-active-bg; // #2d6cdf
  color: #ffffff;
  font-weight: 500;
}

// PO:
&--active {
  background: rgba(255, 255, 255, 0.1);
  color: #ffffff;
  font-weight: 600;

  // Pionowy wskaźnik aktywności (wzorzec Linear/Vercel)
  &::before {
    content: '';
    position: absolute;
    left: -0.5rem;  // wyrównanie z padding kontenera
    top: 50%;
    transform: translateY(-50%);
    width: 3px;
    height: 60%;
    background: #60a5fa;
    border-radius: 0 2px 2px 0;
  }
}

// Hover — dodać subtelny ruch:
&:hover {
  background: $sidenav-hover-bg;
  transform: translateX(2px); // subtelny ruch w prawo
  // Wymagane: position: relative już jest
}
```

Alert badge — dodać pulsowanie:
```scss
.sidenav__alert-badge {
  // Istniejący styl +
  animation: badge-pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

@keyframes badge-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; transform: scale(0.95); }
}
```

---

### PRIORYTET 5 — KPI Cards premium depth
**Wpływ: wysoki (dashboard — pierwsza strona po logowaniu) | Effort: mały**

```scss
// admin-dashboard.component.scss — ZMIENIĆ .kpi-card:

// PRZED:
.kpi-card {
  border: 1px solid $border-color;
  border-radius: $radius-lg;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  transition: box-shadow 0.15s ease;
  &:hover {
    box-shadow: 0 3px 8px rgba(0, 0, 0, 0.1);
  }
}

// PO:
.kpi-card {
  border: 1px solid rgba(0,0,0,0.06);
  border-radius: var(--radius-lg, 10px);
  box-shadow:
    0 0 0 1px rgba(0,0,0,0.03),
    0 1px 3px rgba(0,0,0,0.06),
    0 4px 8px rgba(0,0,0,0.04);
  transition:
    box-shadow 200ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 200ms cubic-bezier(0.4, 0, 0.2, 1);
  position: relative;
  overflow: hidden;

  // Górny kolorowy akcent zamiast border-top
  &::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 3px;
    border-radius: var(--radius-lg, 10px) var(--radius-lg, 10px) 0 0;
  }

  &:hover {
    box-shadow:
      0 0 0 1px rgba(0,0,0,0.04),
      0 4px 8px rgba(0,0,0,0.08),
      0 12px 24px rgba(0,0,0,0.06);
    transform: translateY(-2px);
  }

  // Kolory akcentu per wariant
  &--blue::before { background: linear-gradient(90deg, #1565c0, #3b82f6); }
  &--green::before { background: linear-gradient(90deg, #2e7d32, #22c55e); }
  &--red::before { background: linear-gradient(90deg, #c62828, #ef4444); }
  &--gray::before { background: linear-gradient(90deg, #616161, #94a3b8); }
}
```

---

## Quick Wins (zmiany poniżej 30 minut każda)

### QW-1 — `prefers-reduced-motion` (10 min)
Dodaj do `frontend/src/styles.scss` (plik jest pusty):
```scss
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

### QW-2 — Supervisor dashboard KPI cards — dodaj ikony (20 min)
Supervisor dashboard KPI cards mają tylko `label + value`. Dodaj strukturę identyczną jak w admin-dashboard z `icon-wrap`.

### QW-3 — Table row hover upgrade (15 min per tabela, 5 tabel)
Zmień we wszystkich 5 tabelach:
```scss
// PRZED:
.queue-row:hover { background: $bg-row-hover; }

// PO:
.queue-row {
  transition: background 0.12s cubic-bezier(0.4, 0, 0.2, 1),
              box-shadow 0.12s cubic-bezier(0.4, 0, 0.2, 1);
  &:hover {
    background: #f8fafc;
    box-shadow: inset 3px 0 0 #1565c0;
  }
}
```

### QW-4 — Status menu animation (15 min)
W `agent-desktop.component.scss` dodaj:
```scss
.status-menu {
  transform-origin: top left;
  animation: menu-enter 150ms cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes menu-enter {
  from {
    opacity: 0;
    transform: scale(0.95) translateY(-4px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
```

### QW-5 — Navbar border-bottom upgrade (5 min)
```scss
// top-navbar.component.scss:
// PRZED:
box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
// PO:
box-shadow:
  0 1px 0 #e2e8f0,
  0 1px 8px rgba(0, 0, 0, 0.04);
```

### QW-6 — Button focus ring upgrade (10 min globalnie)
Dodaj do `styles.scss`:
```scss
:focus-visible {
  outline: 2px solid var(--color-brand-600, #1a56db);
  outline-offset: 3px;
  border-radius: 4px;
}
```

### QW-7 — Skeleton shimmer color upgrade (5 min)
```scss
// PRZED (każdy plik osobno):
background: linear-gradient(90deg, #f0f0f0 25%, #e0e0e0 50%, #f0f0f0 75%);
// PO:
background: linear-gradient(
  90deg,
  rgba(226, 232, 240, 0.5) 0%,
  rgba(241, 245, 249, 0.9) 40%,
  rgba(226, 232, 240, 0.5) 100%
);
// Bardziej subtelny, mniej agresywny
```

### QW-8 — Zmiana `linear` na `cubic-bezier` w przejściach sidenav (5 min)
```scss
// sidenav.component.scss:
transition: transform 0.25s cubic-bezier(0.16, 1, 0.3, 1);
// Sprężysty efekt wejścia sidenav na mobile
```

---

## Roadmap ulepszeń

### Faza 1 — Fundament (tydzień 1): Priorytety 1-5 + Quick Wins 1-8
- Stworzenie globalnego design systemu (tokeny CSS)
- `prefers-reduced-motion` w styles.scss
- Upgrade tabeli (hover z vertical indicator)
- Login entrance animation
- Sidenav active state z pionowym wskaźnikiem
- KPI cards premium depth

### Faza 2 — Mikrointerakcje (tydzień 2)
- Angular Animations API — page transitions przy nawigacji routera
- Status menu dropdown animation
- Toast notifications wejście/wyjście z `slideInRight` + progress bar
- Modal overlay fade-in + dialog scale-in
- Staggered list animations dla pierwszego załadowania tabel

### Faza 3 — Zaawansowane (tydzień 3-4)
- Admin dashboard: count-up animation dla wartości KPI
- Softphone: ring animation z wielokrotnymi ripple circles
- IVR editor: drag-and-drop z ghost node + drop animation
- Supervisor dashboard: przebudowa do struktury spójnej z admin-dashboard
- Global search (jeśli planowany): command palette z fuzzy search

### Faza 4 — Szlify (tydzień 5)
- Dark mode support (CSS custom properties już to umożliwiają)
- Print stylesheets dla raportów
- Performance audit: lazy-load obrazów, font-display swap
- Storybook dla design system components

---

## Notatki dla implementacji

1. **IVR editor używa innego brand blue** (`#4a90d9`) niż reszta aplikacji (`#1565c0`). Jest to celowe (distinct area) — zachować, ale udokumentować w design systemie jako `--color-canvas-accent`.

2. **Softphone jest najlepiej wykonanym komponentem** — ma prawidłową strukturę animacji, scale effects, state-based gradients. Używać jako referencję przy refaktorze innych.

3. **Angular Material** jest w stacku ale nie znaleziono jego użycia w analizowanych komponentach — wszystkie komponenty używają własnych stylów. Sprawdzić czy mat-button, mat-card etc. są używane gdziekolwiek.

4. **supervisor-dashboard.component.scss** to jedyna niezgodność stylistyczna — używa `px` zamiast `rem`, magic numbers, brak SCSS zmiennych. Wymaga osobnego refaktoru.

5. **Toast component** — brak pliku SCSS w `/shared/components/toast/`. Należy sprawdzić implementację TS/HTML przed ocean.
