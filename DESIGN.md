---
name: Contact Center (nazwa robocza)
description: Spokojny, precyzyjny pulpit pracy na wielogodzinną zmianę w contact center
colors:
  bg-app: "oklch(98% 0.004 80)"
  bg-sidebar: "oklch(96% 0.004 80)"
  bg-surface: "oklch(100% 0 0)"
  bg-elevated: "oklch(99% 0.004 80)"
  bg-subtle: "oklch(94.5% 0.005 80)"
  bg-input: "oklch(98% 0.004 80)"
  text-1: "oklch(24% 0.012 250)"
  text-2: "oklch(42% 0.012 250)"
  text-3: "oklch(47% 0.012 250)"
  text-muted: "oklch(51% 0.012 250)"
  border-1: "oklch(91% 0.005 80)"
  border-2: "oklch(87% 0.005 80)"
  border-strong: "oklch(80% 0.008 80)"
  border-control: "oklch(60% 0.008 250)"
  accent: "oklch(55% 0.13 255)"
  accent-soft: "oklch(94% 0.025 255)"
  accent-text: "oklch(45% 0.14 255)"
  on-accent: "oklch(100% 0 0)"
  accent-fg: "oklch(100% 0 0)"
  on-danger: "oklch(100% 0 0)"
  on-success: "oklch(100% 0 0)"
  success: "oklch(52% 0.12 155)"
  success-soft: "oklch(94% 0.03 155)"
  success-text: "oklch(42% 0.12 155)"
  warning: "oklch(70% 0.13 70)"
  warning-soft: "oklch(95% 0.04 75)"
  warning-text: "oklch(50% 0.12 60)"
  danger: "oklch(55% 0.18 25)"
  danger-soft: "oklch(95% 0.025 25)"
  danger-text: "oklch(48% 0.18 25)"
  violet: "oklch(58% 0.14 290)"
  violet-soft: "oklch(95% 0.025 290)"
  violet-text: "oklch(45% 0.15 290)"
  neutral: "oklch(60% 0.012 250)"
  neutral-soft: "oklch(94% 0.006 250)"
  neutral-text: "oklch(40% 0.012 250)"
typography:
  display:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "2rem"
    fontWeight: 700
    lineHeight: 1.1
  headline:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
  title:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "1.0625rem"
    fontWeight: 600
  control:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 500
  body:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.45
    letterSpacing: "-0.005em"
  body-small:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 400
  label:
    fontFamily: "Geist, system-ui, -apple-system, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    letterSpacing: "0.04em"
  mono:
    fontFamily: "Geist Mono, ui-monospace, monospace"
    fontSize: "0.8125rem"
    fontWeight: 400
rounded:
  sm: "6px"
  md: "10px"
  lg: "14px"
  xl: "18px"
  full: "9999px"
spacing:
  xxs: "4px"
  xs: "6px"
  sm: "8px"
  sm-plus: "10px"
  md: "12px"
  lg: "16px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accent-fg}"
    typography: "{typography.control}"
    rounded: "{rounded.sm}"
    padding: "0.625rem 1.125rem"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.accent-fg}"
    typography: "{typography.control}"
    rounded: "{rounded.sm}"
    padding: "0.625rem 1.125rem"
  input:
    backgroundColor: "{colors.bg-surface}"
    textColor: "{colors.text-1}"
    typography: "{typography.control}"
    rounded: "{rounded.sm}"
    padding: "0.5rem 0.75rem"
  nav-item:
    textColor: "{colors.text-2}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
  nav-item-hover:
    backgroundColor: "{colors.bg-subtle}"
    textColor: "{colors.text-1}"
  nav-item-active:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-text}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
  badge:
    backgroundColor: "{colors.neutral-soft}"
    textColor: "{colors.neutral-text}"
    rounded: "{rounded.full}"
    padding: "0.2em 0.7em"
  card:
    backgroundColor: "{colors.bg-surface}"
    rounded: "{rounded.md}"
    padding: "20px 16px 16px"
---

# Design System: Contact Center (nazwa robocza)

## Overview

**Creative North Star: "The Well-Lit Workbench" (Dobrze oświetlony warsztat)**

Interfejs to stół warsztatowy, przy którym agent spędza całą zmianę: tło ma kolor lekko ciepłego papieru, tekst ma kolor chłodnego grafitu, a jedyne mocne światło, czyli Lampa robocza, pada tam, gdzie trzeba działać: na główną akcję, aktywną pozycję nawigacji, fokus i zaznaczenie. Wszystko inne jest ciche, żeby po ośmiu godzinach oczy nadal odróżniały ważne od tła.

Nastrój jest **spokojny i precyzyjny**. System jest gęsty, ale nie ciasny: tekst bazowy ma 14 px, kontrolki 15 px, a odstępy idą skokami 4–16 px. Kolory semantyczne (sukces, ostrzeżenie, błąd, fiolet kanału e-mail) zawsze występują jako trójka „pełny / miękki / tekst”, a stan pokazuje się miękkim tłem z ciemniejszym tekstem, nie krzykliwym wypełnieniem. Wyjątkiem są zdarzenia wymagające natychmiastowej reakcji: przychodzące połączenie dostaje pełny kolor i ruch.

System ma pełny motyw ciemny (`[data-theme='dark']`) o tych samych nazwach tokenów: tła przechodzą w grafit o odcieniu 250, a akcenty się rozjaśniają. Każdy nowy komponent musi działać w obu motywach bez dodatkowego kodu, czyli wyłącznie przez tokeny.

**Key Characteristics:**
- Ciepłe, papierowe neutralne tła (odcień 80) i chłodny grafitowy tekst (odcień 250).
- Jeden akcent, Lampa robocza, zarezerwowany dla akcji, aktywności i fokusu.
- Kolory stanu jako miękkie pigułki: miękkie tło i tekst w tym samym odcieniu.
- Spokój w spoczynku. Na interakcję element odpowiada zmianą tonu, a nie ruchem.
- Geist i Geist Mono, gęsta typografia narzędziowa.
- Dwa motywy (jasny i ciemny) na jednym zestawie nazw tokenów OKLCH.

## Colors

Paleta to papier i grafit z jednym niebieskim światłem roboczym oraz zestawem stonowanych kolorów stanu w trójkach. Źródłem prawdy są tokeny OKLCH w `frontend/src/styles.scss`.

### Primary
- **Work-Lamp Blue / Lampa robocza** (`accent`): główny przycisk, aktywna pozycja nawigacji (ikona), obrys fokusu, pierścień pulsujący softphone'u, kanał telefoniczny. **Lampa robocza miękka** (`accent-soft`) to tło aktywnej pozycji nawigacji i pigułek telefon/outbound. **Lampa robocza – tekst** (`accent-text`) to tekst na miękkim tle i wartości KPI połączeń. Na pełnym akcencie tekst ma kolor `on-accent` (`accent-fg` zostaje aliasem); analogicznie `on-danger` i `on-success`. W jasnym motywie to biel, a wypełnienia `accent`, `danger` i `success` są przyciemnione tak, by dawały co najmniej 4,5:1 (4,87 / 5,32 / 5,20); w ciemnym motywie ciemny grafit (6,3–7,8:1).

### Secondary
- **Violet / Fiolet** (`violet`, `violet-soft`, `violet-text`): kanał e-mail, status „przekazany”, drugi biegun gradientu awatara w softphonie. To kolor rozróżniający, nie drugi akcent akcji.

### Neutral
- **Workbench Paper / Papier warsztatowy** (`bg-app`): tło aplikacji. **Papier boczny** (`bg-sidebar`) to tło nawigacji bocznej, o ton ciemniejsze.
- **Czysta karta** (`bg-surface`): karty, tabele, modale, pola formularzy.
- **Uniesiony papier** (`bg-elevated`): powierzchnie nad kartą (menu, popovery).
- **Przetarty papier** (`bg-subtle`): hover wierszy i pozycji nawigacji, tła sekcji pomocniczych.
- **Graphite Ink / Grafit** (`text-1`): tekst główny. Stopnie jaśniejsze: `text-2` dla tekstu drugorzędnego i etykiet KPI, `text-3` dla opisów, `text-muted` dla ikon w spoczynku, placeholderów i nagłówków sekcji nawigacji. Wszystkie trzy mają co najmniej 4,5:1 na każdej powierzchni (`bg-*`, `accent-soft`), a hierarchię niosą waga i rozmiar, nie osłabiony kontrast.
- **Linie** (`border-1`): domyślne obramowania i separatory. `border-control` to obrys kontrolek (input, select, checkbox, radio) i ma co najmniej 3:1 względem każdej powierzchni (WCAG 1.4.11); `border-2` zostaje dekoracyjnym, subtelnym podziałem, a `border-strong` to obramowania wymagające wyraźnego podziału.

### Stany
- **Sukces** (`success` / `-soft` / `-text`): agent dostępny, kontakt aktywny lub zakończony, kanał czat, kierunek inbound.
- **Ostrzeżenie** (`warning` / `-soft` / `-text`): przerwa, kolejka, wstrzymanie, kanał social.
- **Błąd** (`danger` / `-soft` / `-text`): błędy formularzy, nieudane i nieosiągnięte kontakty, akcje destrukcyjne, baner przychodzącego połączenia.
- **Neutralny** (`neutral` / `-soft` / `-text`): domyślny i porzucony stan pigułki.

### Named Rules
**The Lamp Rule.** Lampa robocza świeci tylko na to, co agent może teraz zrobić lub gdzie właśnie jest: na główną akcję, aktywną pozycję, fokus i zaznaczenie. Nie służy do dekoracji, nagłówków ani ilustracji.

**The Triad Rule.** Każdy kolor semantyczny istnieje jako trójka `X` / `X-soft` / `X-text`. Pigułki i odznaki używają `X-soft` jako tła i `X-text` jako tekstu. Pełne `X` jest dla ikon, kropek statusu, przycisków i zdarzeń wymagających reakcji.

**The Contrast Floor Rule.** Tekst (także `text-3` i `text-muted`) ma co najmniej 4,5:1 na każdej powierzchni, na której leży; tekst na pełnym wypełnieniu `accent` / `danger` / `success` ma co najmniej 4,5:1; obrys kontrolki i fokus mają co najmniej 3:1. Nowe wartości tokenów sprawdza się skryptem (OKLCH → WCAG), nie na oko.

**The Token-Only Rule.** Nowy kod używa wyłącznie tokenów OKLCH z `styles.scss`. Warstwa `--color-*` (hex) i literalne kolory hex w ok. 28 plikach SCSS to dług techniczny do wygaszenia, a nie wzorzec. Literalne wartości łamią motyw ciemny.

## Typography

**Body Font:** Geist (z fallbackiem system-ui, -apple-system, sans-serif)
**Label/Mono Font:** Geist Mono (z fallbackiem ui-monospace, monospace)

**Character:** Jedna rodzina bezszeryfowa o neutralnym, technicznym rysunku, z lekko ściśniętym światłem (-0.005em) i wygładzaniem. Mono jest dla danych, które się porównuje lub kopiuje.

### Hierarchy
- **Display** (700, 2rem, 1.1): wartości KPI na dashboardzie supervisora. Kolor wartości może przyjąć `X-text` stanu, który mierzy.
- **Headline** (600, 1.5rem): tytuł strony.
- **Title** (600, 1.0625rem): tytuły modali i sekcji.
- **Control** (500, 0.9375rem): przyciski i pola formularzy.
- **Body** (400, 0.875rem / 14 px, 1.45): tekst bazowy, komórki tabel, nawigacja.
- **Body small** (400, 0.8125rem): metadane, opisy pod polami, drugie linie wierszy.
- **Label** (500, 0.75rem, 0.04em, WERSALIKI): etykiety KPI i nagłówki grup nawigacji (te mają 700 i 0.06em).
- **Pill** (600, 0.71875rem): tekst w pigułkach stanu i kanału.
- **Mono** (400, ok. 0.8125rem): identyfikatory, numery telefonów, czasy trwania, fragmenty techniczne.

### Named Rules
**The One Family Rule.** Cały interfejs jest pisany Geistem. Hierarchię buduje się wagą (400/500/600/700) i rozmiarem, nie drugą rodziną.

**The Real Mono Rule.** Dane o stałej szerokości używają `'Geist Mono', ui-monospace, monospace`. Generyczne `monospace`, `Courier New`, `Consolas` czy `Fira Mono` w obecnym kodzie to niespójności do usunięcia.

## Layout

Aplikacja to powłoka z nawigacją boczną i górnym paskiem (64 px). Nawigacja ma 232 px, a po zwinięciu 72 px (same ikony). Treść wypełnia resztę.

- **Desktop (≥ 1280 px):** nawigacja jest statyczna, zawsze widoczna i zwijalna.
- **Tablet (1024–1279 px):** nawigacja to szuflada nad treścią, pod górnym paskiem, z przyciemnieniem tła.
- **< 1024 px:** szuflada na całą wysokość. Powłoka ma dodatkowe dostosowania poniżej 600 px.
- Produkt celuje w 1366×768–4K, więc węższe układy to zapas, a nie główny scenariusz.

Rytm odstępów: 4 / 6 / 8 / 10 / 12 / 16 px. Najczęstsze odstępy między elementami to 0.5rem, 0.75rem i 1rem, a w gęstych grupach (ikona + etykieta) 0.375rem. Lista nawigacji ma odstęp 2 px i wewnętrzny padding 10 px.

## Elevation & Depth

Powierzchnie leżą płasko. Głębię daje ton tła (papier aplikacji → czysta karta → przetarty papier na hover) i jednopikselowa linia `border-1`. Cień jest zarezerwowany dla tego, co faktycznie unosi się nad treścią i ją zasłania: modali, menu, popoverów, toastów, szuflady nawigacji i banera połączenia.

### Shadow Vocabulary
- **Pop** (`--shadow-pop`: `0 12px 40px oklch(20% 0.02 250 / 0.16)`): modale, menu rozwijane, popovery.
- **Large** (`--shadow-lg`): duże panele nakładane.
- **Medium** (`--shadow-md`): małe pływające elementy (tooltipy, toasty).
- **Small** (`--shadow-sm`): obecnie stoi na wielu kartach i kontenerach list (KPI, kolejki, użytkownicy, raporty), często razem z `border-1`. Według zasady poniżej to dryf: przy następnej pracy nad danym ekranem zostaje samo obramowanie.
- **Szuflada** (`8px 0 32px oklch(0% 0 0 / 0.12)`): nawigacja boczna w trybie nakładki.

W motywie ciemnym cienie są czarne i mocniejsze (0.3–0.5 krycia), bo na ciemnym tle słabsze cienie znikają.

### Named Rules
**The Flat-at-Rest Rule.** Karty, tabele i panele w obrębie strony nie mają cienia. Rozdziela je ton tła i linia `border-1`. Jeśli element ma cień, to dlatego, że przykrywa coś pod sobą.

## Shapes

Lekko zaokrąglone, narzędziowe formy. `radius-sm` (6 px) to domyślny promień przycisków, pól i małych elementów. `radius-md` (10 px) mają karty, pozycje nawigacji i modale, a `radius-lg` (14 px) i `radius-xl` (18 px) większe kontenery. Pigułki stanu i kanału są w pełni zaokrąglone (`radius-full`), a awatary i kropki statusu są kołami. Obramowania mają 1 px, a pola formularzy 1.5 px.

Literalne promienie w kodzie (4, 8, 12, 20 px) to niespójności. Nowy kod używa skali tokenów.

## Components

Precyzyjne narzędzia: ciche w spoczynku, wyraźne przy dotyku. Interakcja zmienia ton, krycie lub obrys, nigdy położenia.

### Buttons
- **Shape:** promień `radius-sm` (6 px), bez obramowania, ikona i tekst z odstępem 0.375rem.
- **Primary:** pełna Lampa robocza z tekstem `accent-fg`, padding 0.625rem × 1.125rem, typografia Control.
- **Hover / Focus:** hover obniża krycie do 0.88 (przejście 150 ms). Fokus to globalny obrys 2 px w kolorze `accent`, odsunięty o 3 px.
- **Danger:** ta sama forma na pełnym `danger`, tylko dla akcji destrukcyjnych.
- **Secondary / Cancel:** przezroczyste tło, tekst `text-2`, obrys 1.5 px `border-1`. Na hover tło przechodzi w `neutral-soft`, a obrys w `border-strong`.
- **Icon:** kwadrat 30 × 30 px na `bg-surface`, z obrysem 1.5 px `border-1` i promieniem `radius-sm`.

### Chips (pigułki stanu i kanału)
- **Style:** pełne zaokrąglenie, padding 0.2em × 0.7em, 0.71875rem / 600, tło `X-soft` i tekst `X-text`. Wspólny partial: `shared/styles/_contact-badges.scss`.
- **Mapowanie kanałów:** telefon = Lampa robocza, e-mail = fiolet, czat = sukces, social = ostrzeżenie.
- **Mapowanie statusów:** zgodne z sekcją „Stany” w Colors.

### Cards / Containers
- **Corner Style:** `radius-md` (10 px).
- **Background:** `bg-surface` na tle `bg-app`.
- **Shadow Strategy:** brak cienia (The Flat-at-Rest Rule), separacja linią `border-1`.
- **Internal Padding:** 16 px (karta KPI: 20 px u góry, 16 px po bokach i u dołu).

### Inputs / Fields
- **Style:** obrys 1.5 px `border-1`, tło `bg-surface`, promień `radius-sm`, padding 0.5rem × 0.75rem, typografia Control. Natywne `select` mają chevron w SVG, który dziedziczy kolor w obu motywach.
- **Focus:** obrys przechodzi w `accent`, z poświatą 3 px w `accent-soft`.
- **Error:** obrys `danger`, a przy fokusie poświata 3 px `danger` na 20% krycia. Komunikat błędu pod polem w `danger-text`.
- **Label:** 0.875rem / 500, `text-1`.

### Navigation
- **Pozycja:** 0.875rem / 500, `text-2`, ikona 18 px w `text-muted`, padding 8 × 10 px, promień `radius-md`.
- **Hover:** tło `bg-subtle`, tekst `text-1`.
- **Active:** tło `accent-soft`, tekst `accent-text` / 600, ikona w `accent`.
- **Zwinięta:** same ikony, etykiety wygaszane przez krycie i szerokość (150 ms).
- **Nagłówki grup:** Label, 700, 0.06em, `text-muted`.

### Incoming Call Banner (sygnaturowy)
Pasek przypięty na całą szerokość u góry ekranu, nad wszystkim (z-index 9000), na pełnym `danger` z tekstem `accent-fg` i kolorową poświatą. To jedyne miejsce, gdzie interfejs świadomie podnosi głos: przychodzący kontakt musi przerwać pracę.

### Softphone (sygnaturowy)
Okrągły awatar rozmówcy (5.25rem) z gradientem od Lampy roboczej do fioletu i pulsującym pierścieniem (`pulse-ring`, 1.6 s) w trakcie dzwonienia. Ruch oznacza tu stan „coś się dzieje teraz” i nie pojawia się nigdzie dekoracyjnie.

## Do's and Don'ts

### Do:
- **Do** używaj tokenów z `styles.scss` (`--accent`, `--text-1`, `--border-1`, `--radius-sm`…) zamiast literalnych wartości, żeby komponent działał w obu motywach.
- **Do** pokazuj stan jako pigułkę `X-soft` + `X-text` (The Triad Rule).
- **Do** rozdzielaj powierzchnie tonem tła i linią 1 px `border-1`, a cień dawaj tylko nakładkom.
- **Do** odpowiadaj na hover zmianą tła lub krycia (ok. 120–150 ms, `--ease-standard`), a na fokus globalnym obrysem 2 px w kolorze `accent`.
- **Do** pisz dane porównywalne (ID, numery, czasy) w Geist Mono.
- **Do** zostawiaj ruch dla zdarzeń na żywo (dzwonienie, nowy kontakt). Globalny `prefers-reduced-motion` musi go wyłączać.

### Don't:
- **Don't** dodawaj nowych kolorów hex ani tokenów `--color-*`, bo to warstwa legacy.
- **Don't** podnoś kart ani wierszy na hover (`translateY`, rosnący cień). Precyzyjne narzędzia nie podskakują.
- **Don't** używaj Lampy roboczej dekoracyjnie: na nagłówkach, tłach sekcji, ilustracjach.
- **Don't** dawaj kartom i tabelom w obrębie strony cienia spoczynkowego.
- **Don't** używaj pełnego `danger` jako tła poza zdarzeniami wymagającymi natychmiastowej reakcji i akcjami destrukcyjnymi.
- **Don't** wprowadzaj drugiej rodziny pisma ani generycznego `monospace`.
